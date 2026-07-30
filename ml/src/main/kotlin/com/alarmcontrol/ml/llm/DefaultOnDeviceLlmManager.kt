@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.alarmcontrol.ml.llm

import com.alarmcontrol.core.result.DataResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Serializes access to the native LLM runtime on the injected background [dispatcher]. At most one
 * analysis runs and one waits; further requests return `null` immediately so notification bursts
 * cannot build an unbounded native-inference queue. A cancelled caller only abandons its result:
 * the actor retains ownership of the native call and prevents overlapping executions.
 */
internal class DefaultOnDeviceLlmManager(
    private val engine: LlmEngine,
    dispatcher: CoroutineDispatcher,
    private val modelStore: LocalLlmModelStore? = null,
    private val feedbackAdjuster: LlmFeedbackAdjuster = LlmFeedbackAdjuster { _, result -> result },
    private val idleTtlMillis: Long = DEFAULT_IDLE_TTL_MILLIS,
) : OnDeviceLlmManager {
    private val _initState = MutableStateFlow<LlmInitState>(LlmInitState.Idle)
    override val initState: StateFlow<LlmInitState> = _initState.asStateFlow()
    private val _modelInfo = MutableStateFlow<LlmModelInfo?>(null)
    override val modelInfo: StateFlow<LlmModelInfo?> = _modelInfo.asStateFlow()

    private val stateLock = Any()
    private val generation = AtomicLong(0)
    private val analysisSlots = Semaphore(ANALYSIS_CAPACITY)
    private val commands = Channel<EngineCommand>(COMMAND_CAPACITY)
    private val submissionMutex = Mutex()
    private val actorScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val installInProgress = AtomicBoolean(false)
    private val idleCloseLock = Any()
    private val idleCloseSequence = AtomicLong(0)
    private var idleCloseJob: Job? = null

    init {
        actorScope.launch {
            for (command in commands) processSafely(command)
        }
    }

    override suspend fun initialize() {
        var requestedGeneration: Long? = null
        var previousState: LlmInitState? = null
        val reply = CompletableDeferred<Unit>()
        var enqueued = false
        try {
            val submitted =
                submissionMutex.withLock {
                    val request =
                        synchronized(stateLock) {
                            val current = _initState.value
                            when (current) {
                                LlmInitState.Loading, LlmInitState.Ready -> null
                                is LlmInitState.Installing -> null
                                LlmInitState.Idle, is LlmInitState.Unavailable -> {
                                    previousState = current
                                    generation.incrementAndGet().also { requested ->
                                        requestedGeneration = requested
                                        _initState.value = LlmInitState.Loading
                                    }
                                }
                            }
                        } ?: return@withLock false
                    commands.send(EngineCommand.Initialize(request, reply))
                    enqueued = true
                    true
                }
            if (!submitted) return
            reply.await()
        } catch (error: CancellationException) {
            val requested = requestedGeneration
            val previous = previousState
            if (!enqueued && requested != null && previous != null) {
                updateState(requested, previous)
            }
            throw error
        }
    }

    override suspend fun analyze(
        text: String,
        packageName: String?,
    ): LlmAnalysisResult? {
        val requestedGeneration =
            synchronized(stateLock) {
                if (_initState.value != LlmInitState.Ready) return null
                generation.get()
            }
        if (!analysisSlots.tryAcquire()) return null
        cancelIdleClose()

        val reply = CompletableDeferred<LlmAnalysisResult?>()
        val sent = commands.trySend(EngineCommand.Analyze(requestedGeneration, text, packageName, reply))
        if (sent.isFailure) {
            analysisSlots.release()
            if (isCurrent(requestedGeneration, LlmInitState.Ready)) {
                scheduleIdleClose(requestedGeneration)
            }
            return null
        }
        val result = reply.await()
        return synchronized(stateLock) {
            result.takeIf {
                generation.get() == requestedGeneration && _initState.value == LlmInitState.Ready
            }
        }
    }

    override suspend fun installModel(
        source: InputStream,
        expectedBytes: Long?,
    ): DataResult<Unit> {
        cancelIdleClose()
        if (!installInProgress.compareAndSet(false, true)) {
            return DataResult.Failure(IllegalStateException("Model installation is already running"))
        }
        var previousState: LlmInitState? = null
        var requestedGeneration: Long? = null
        val reply = CompletableDeferred<DataResult<Unit>>()
        var enqueued = false
        return try {
            submissionMutex.withLock {
                synchronized(stateLock) {
                    previousState = _initState.value
                    requestedGeneration = generation.incrementAndGet()
                    _initState.value = LlmInitState.Installing(copiedBytes = 0, totalBytes = expectedBytes)
                }
                commands.send(
                    EngineCommand.Install(
                        generation = requireNotNull(requestedGeneration),
                        source = source,
                        expectedBytes = expectedBytes,
                        previousState = requireNotNull(previousState),
                        reply = reply,
                    ),
                )
                enqueued = true
            }
            reply.await()
        } catch (error: CancellationException) {
            val requested = requestedGeneration
            val previous = previousState
            if (!enqueued) {
                if (requested != null && previous != null) updateState(requested, previous)
                installInProgress.set(false)
            }
            throw error
        } catch (error: Exception) {
            val requested = requestedGeneration
            val previous = previousState
            if (!enqueued) {
                if (requested != null && previous != null) updateState(requested, previous)
                installInProgress.set(false)
            }
            DataResult.Failure(error)
        }
    }

    override suspend fun removeModel(): DataResult<Unit> {
        cancelIdleClose()
        var requestedGeneration: Long? = null
        var previousState: LlmInitState? = null
        val reply = CompletableDeferred<DataResult<Unit>>()
        var enqueued = false
        return try {
            submissionMutex.withLock {
                synchronized(stateLock) {
                    previousState = _initState.value
                    requestedGeneration = generation.incrementAndGet()
                    _initState.value = LlmInitState.Idle
                }
                commands.send(EngineCommand.Remove(requireNotNull(requestedGeneration), reply))
                enqueued = true
            }
            reply.await()
        } catch (error: CancellationException) {
            val requested = requestedGeneration
            val previous = previousState
            if (!enqueued && requested != null && previous != null) {
                updateState(requested, previous)
            }
            throw error
        }
    }

    override suspend fun close() {
        cancelIdleClose()
        var requestedGeneration: Long? = null
        var previousState: LlmInitState? = null
        val reply = CompletableDeferred<Unit>()
        var enqueued = false
        try {
            submissionMutex.withLock {
                synchronized(stateLock) {
                    previousState = _initState.value
                    requestedGeneration = generation.incrementAndGet()
                    _initState.value = LlmInitState.Idle
                }
                commands.send(EngineCommand.Close(requireNotNull(requestedGeneration), reply))
                enqueued = true
            }
            reply.await()
        } catch (error: CancellationException) {
            val requested = requestedGeneration
            val previous = previousState
            if (!enqueued && requested != null && previous != null) {
                updateState(requested, previous)
            }
            throw error
        }
    }

    private suspend fun processSafely(command: EngineCommand) {
        try {
            process(command)
        } catch (error: CancellationException) {
            command.completeExceptionally(error)
            throw error
        } catch (error: LinkageError) {
            failUnexpectedCommand(command, error)
        } catch (error: OutOfMemoryError) {
            failUnexpectedCommand(command, error)
        } catch (error: Exception) {
            failUnexpectedCommand(command, error)
        }
    }

    private fun failUnexpectedCommand(
        command: EngineCommand,
        error: Throwable,
    ) {
        when (command) {
            is EngineCommand.Initialize -> {
                engine.closeBestEffort()
                updateState(command.generation, LlmInitState.Unavailable(LlmFailure.LOAD_FAILED))
                command.reply.complete(Unit)
            }
            is EngineCommand.Analyze -> {
                engine.closeBestEffort()
                updateState(command.generation, LlmInitState.Unavailable(LlmFailure.LOAD_FAILED))
                command.reply.complete(null)
            }
            is EngineCommand.Install -> {
                engine.closeBestEffort()
                updateState(command.generation, LlmInitState.Unavailable(LlmFailure.LOAD_FAILED))
                installInProgress.set(false)
                command.reply.complete(DataResult.Failure(error))
            }
            is EngineCommand.Remove -> {
                engine.closeBestEffort()
                updateState(command.generation, LlmInitState.Unavailable(LlmFailure.STORAGE_FAILURE))
                command.reply.complete(DataResult.Failure(error))
            }
            is EngineCommand.Close -> {
                engine.closeBestEffort()
                updateState(command.generation, LlmInitState.Idle)
                command.reply.complete(Unit)
            }
            is EngineCommand.CloseIfIdle -> {
                engine.closeBestEffort()
                command.reply.complete(Unit)
            }
        }
    }

    private suspend fun process(command: EngineCommand) {
        when (command) {
            is EngineCommand.Initialize -> processInitialize(command)
            is EngineCommand.Analyze -> processAnalyze(command)
            is EngineCommand.Install -> processInstall(command)
            is EngineCommand.Remove -> processRemove(command)
            is EngineCommand.Close -> {
                if (generation.isCurrent(stateLock, command.generation)) {
                    engine.closeBestEffort()
                    updateState(command.generation, LlmInitState.Idle)
                }
                command.reply.complete(Unit)
            }
            is EngineCommand.CloseIfIdle -> {
                val shouldClose =
                    synchronized(stateLock) {
                        if (generation.get() == command.generation &&
                            idleCloseSequence.get() == command.idleSequence &&
                            _initState.value == LlmInitState.Ready
                        ) {
                            generation.incrementAndGet()
                            _initState.value = LlmInitState.Idle
                            true
                        } else {
                            false
                        }
                    }
                if (shouldClose) engine.closeBestEffort()
                command.reply.complete(Unit)
            }
        }
    }

    private fun processInitialize(command: EngineCommand.Initialize) {
        if (!isCurrent(command.generation, LlmInitState.Loading)) {
            command.reply.complete(Unit)
            return
        }
        try {
            val verifiedInfo = modelStore?.verifyInstalledModel()
            if ((modelStore != null && verifiedInfo == null) || !engine.isModelAvailable()) {
                updateModelInfo(command.generation, null)
                updateState(command.generation, LlmInitState.Unavailable(LlmFailure.MODEL_MISSING))
            } else {
                val loadedInfo = loadVerifiedOrPreviousModel(engine, modelStore, verifiedInfo)
                updateModelInfo(command.generation, loadedInfo)
                updateState(command.generation, LlmInitState.Ready)
                scheduleIdleClose(command.generation)
            }
        } catch (_: ModelIntegrityException) {
            updateModelInfo(command.generation, null)
            updateState(command.generation, LlmInitState.Unavailable(LlmFailure.MODEL_INTEGRITY_FAILED))
        } catch (_: LinkageError) {
            engine.closeBestEffort()
            updateState(command.generation, LlmInitState.Unavailable(LlmFailure.LOAD_FAILED))
        } catch (_: OutOfMemoryError) {
            engine.closeBestEffort()
            updateState(command.generation, LlmInitState.Unavailable(LlmFailure.LOAD_FAILED))
        } catch (_: Exception) {
            engine.closeBestEffort()
            updateState(command.generation, LlmInitState.Unavailable(LlmFailure.LOAD_FAILED))
        } finally {
            command.reply.complete(Unit)
        }
    }

    private suspend fun processAnalyze(command: EngineCommand.Analyze) {
        try {
            command.reply.complete(analyzeIfCurrent(command))
        } catch (error: CancellationException) {
            command.reply.completeExceptionally(error)
        } catch (_: LinkageError) {
            updateState(command.generation, LlmInitState.Unavailable(LlmFailure.LOAD_FAILED))
            engine.closeBestEffort()
            command.reply.complete(null)
        } catch (_: OutOfMemoryError) {
            updateState(command.generation, LlmInitState.Unavailable(LlmFailure.LOAD_FAILED))
            engine.closeBestEffort()
            command.reply.complete(null)
        } catch (_: Exception) {
            command.reply.complete(null)
        } finally {
            analysisSlots.release()
            if (isCurrent(command.generation, LlmInitState.Ready)) {
                scheduleIdleClose(command.generation)
            }
        }
    }

    private suspend fun analyzeIfCurrent(command: EngineCommand.Analyze): LlmAnalysisResult? {
        if (!isCurrent(command.generation, LlmInitState.Ready)) return null
        val raw =
            withTimeoutOrNull(ENGINE_ANALYSIS_TIMEOUT_MILLIS) {
                engine.analyze(command.text)
            }
        if (raw == null) {
            // A timed-out native call may ignore Future cancellation. Revoke Ready before replying
            // and close the engine inside the actor so no later request can overlap stale native
            // work on the same runtime instance.
            updateState(command.generation, LlmInitState.Unavailable(LlmFailure.LOAD_FAILED))
            engine.closeBestEffort()
            return null
        }
        val adjusted = command.packageName?.let { feedbackAdjuster.adjust(it, raw) } ?: raw
        return adjusted.takeIf {
            it.intent != com.alarmcontrol.core.filtering.SemanticIntent.AMBIGUOUS &&
                it.confidenceScore >= MIN_TRUSTED_CONFIDENCE
        } ?: LlmAnalysisResult.UNAVAILABLE
    }

    private fun processInstall(command: EngineCommand.Install) {
        try {
            processInstallOwned(command)
        } catch (error: Exception) {
            updateState(command.generation, LlmInitState.Unavailable(LlmFailure.STORAGE_FAILURE))
            command.reply.complete(DataResult.Failure(error))
        } finally {
            installInProgress.set(false)
            if (isCurrent(command.generation, LlmInitState.Ready)) {
                scheduleIdleClose(command.generation)
            }
        }
    }

    private fun processInstallOwned(command: EngineCommand.Install) {
        if (!generation.isCurrent(stateLock, command.generation)) {
            command.reply.complete(DataResult.Failure(IllegalStateException("Model installation was superseded")))
            return
        }
        val store = modelStore
        if (store == null) {
            updateState(command.generation, command.previousState)
            command.reply.complete(DataResult.Failure(IllegalStateException("Model installation is not configured")))
            return
        }
        val result =
            try {
                val staged =
                    store.stage(command.source, command.expectedBytes) { copied ->
                        generation.requireCurrentInstallation(stateLock, command.generation)
                        updateState(
                            command.generation,
                            LlmInitState.Installing(copied, command.expectedBytes),
                        )
                    }

                fun recoverReplacementFailure(error: Throwable): DataResult.Failure {
                    engine.closeBestEffort()
                    updateState(
                        command.generation,
                        recoverAfterFailedReplacement(
                            engine = engine,
                            modelStore = modelStore,
                            previousState = command.previousState,
                            staged = staged,
                            originalFailure = error,
                        ) { updateModelInfo(command.generation, it) },
                    )
                    return DataResult.Failure(error)
                }
                try {
                    generation.requireCurrentInstallation(stateLock, command.generation)
                    updateState(command.generation, LlmInitState.Loading)
                    engine.close()
                    generation.requireCurrentInstallation(stateLock, command.generation)
                    staged.activate()
                    generation.requireCurrentInstallation(stateLock, command.generation)
                    check(engine.isModelAvailable()) { MODEL_MISSING_MESSAGE }
                    engine.load()
                    generation.commitInstallationIfCurrent(
                        lock = stateLock,
                        requestedGeneration = command.generation,
                    ) {
                        staged.commit()
                        _modelInfo.value = staged.modelInfo
                        _initState.value = LlmInitState.Ready
                    }
                    DataResult.Success(Unit)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ModelInstallationSupersededException) {
                    engine.closeBestEffort()
                    if (!staged.rollbackPreserving(error)) {
                        updateModelInfo(command.generation, null)
                        updateState(command.generation, LlmInitState.Unavailable(LlmFailure.STORAGE_FAILURE))
                    }
                    DataResult.Failure(error)
                } catch (error: OutOfMemoryError) {
                    recoverReplacementFailure(error)
                } catch (error: LinkageError) {
                    recoverReplacementFailure(error)
                } catch (error: Exception) {
                    recoverReplacementFailure(error)
                }
            } catch (error: OutOfMemoryError) {
                updateState(command.generation, stateAfterStagingOom(engine, command.previousState))
                DataResult.Failure(error)
            } catch (error: IllegalArgumentException) {
                updateState(command.generation, command.previousState.orUnavailable(LlmFailure.MODEL_INVALID))
                DataResult.Failure(error)
            } catch (error: Exception) {
                updateState(command.generation, command.previousState.orUnavailable(LlmFailure.STORAGE_FAILURE))
                DataResult.Failure(error)
            }
        command.reply.complete(result)
    }

    private fun processRemove(command: EngineCommand.Remove) {
        if (!generation.isCurrent(stateLock, command.generation)) {
            command.reply.complete(DataResult.Failure(IllegalStateException("Model removal was superseded")))
            return
        }
        val store = modelStore
        val result =
            if (store == null) {
                DataResult.Failure(IllegalStateException("Model installation is not configured"))
            } else {
                engine.closeBestEffort()
                try {
                    store.delete()
                    updateModelInfo(command.generation, null)
                    updateState(command.generation, LlmInitState.Idle)
                    DataResult.Success(Unit)
                } catch (error: Exception) {
                    updateState(command.generation, LlmInitState.Unavailable(LlmFailure.STORAGE_FAILURE))
                    DataResult.Failure(error)
                }
            }
        command.reply.complete(result)
    }

    private fun scheduleIdleClose(requestedGeneration: Long) {
        cancelIdleClose()
        val idleSequence = idleCloseSequence.incrementAndGet()
        val newJob =
            actorScope.launch {
                if (idleTtlMillis > 0) delay(idleTtlMillis)
                val reply = CompletableDeferred<Unit>()
                commands.send(
                    EngineCommand.CloseIfIdle(
                        generation = requestedGeneration,
                        idleSequence = idleSequence,
                        reply = reply,
                    ),
                )
                reply.await()
            }
        synchronized(idleCloseLock) {
            idleCloseJob = newJob
        }
    }

    private fun cancelIdleClose() {
        idleCloseSequence.incrementAndGet()
        synchronized(idleCloseLock) {
            idleCloseJob?.cancel()
            idleCloseJob = null
        }
    }

    private fun isCurrent(
        requestedGeneration: Long,
        requiredState: LlmInitState,
    ): Boolean =
        synchronized(stateLock) {
            generation.get() == requestedGeneration && _initState.value == requiredState
        }

    private fun updateState(
        requestedGeneration: Long,
        state: LlmInitState,
    ) {
        synchronized(stateLock) {
            if (generation.get() == requestedGeneration) _initState.value = state
        }
    }

    private fun updateModelInfo(
        requestedGeneration: Long,
        info: LlmModelInfo?,
    ) {
        synchronized(stateLock) {
            if (generation.get() == requestedGeneration) _modelInfo.value = info
        }
    }

    private companion object {
        const val MIN_TRUSTED_CONFIDENCE = 0.6f
        const val ANALYSIS_CAPACITY = 2
        const val COMMAND_CAPACITY = 4
        const val ENGINE_ANALYSIS_TIMEOUT_MILLIS = 9_000L
        const val DEFAULT_IDLE_TTL_MILLIS = 60_000L
    }
}

private sealed interface EngineCommand {
    val generation: Long

    data class Initialize(
        override val generation: Long,
        val reply: CompletableDeferred<Unit>,
    ) : EngineCommand

    data class Analyze(
        override val generation: Long,
        val text: String,
        val packageName: String?,
        val reply: CompletableDeferred<LlmAnalysisResult?>,
    ) : EngineCommand

    data class Install(
        override val generation: Long,
        val source: InputStream,
        val expectedBytes: Long?,
        val previousState: LlmInitState,
        val reply: CompletableDeferred<DataResult<Unit>>,
    ) : EngineCommand

    data class Remove(
        override val generation: Long,
        val reply: CompletableDeferred<DataResult<Unit>>,
    ) : EngineCommand

    data class Close(
        override val generation: Long,
        val reply: CompletableDeferred<Unit>,
    ) : EngineCommand

    data class CloseIfIdle(
        override val generation: Long,
        val idleSequence: Long,
        val reply: CompletableDeferred<Unit>,
    ) : EngineCommand
}

private fun EngineCommand.completeExceptionally(error: Throwable) {
    when (this) {
        is EngineCommand.Initialize -> reply.completeExceptionally(error)
        is EngineCommand.Analyze -> reply.completeExceptionally(error)
        is EngineCommand.Install -> reply.completeExceptionally(error)
        is EngineCommand.Remove -> reply.completeExceptionally(error)
        is EngineCommand.Close -> reply.completeExceptionally(error)
        is EngineCommand.CloseIfIdle -> reply.completeExceptionally(error)
    }
}

private fun loadVerifiedOrPreviousModel(
    engine: LlmEngine,
    store: LocalLlmModelStore?,
    verifiedInfo: LlmModelInfo?,
): LlmModelInfo? =
    try {
        engine.load()
        store?.discardRollbackArtifacts()
        verifiedInfo
    } catch (error: LinkageError) {
        restorePreviousModel(engine, store) ?: throw error
    } catch (error: OutOfMemoryError) {
        restorePreviousModel(engine, store) ?: throw error
    } catch (error: Exception) {
        restorePreviousModel(engine, store) ?: throw error
    }

private fun restorePreviousModel(
    engine: LlmEngine,
    store: LocalLlmModelStore?,
): LlmModelInfo? =
    try {
        engine.close()
        val restoredInfo = store?.restorePreviousModel() ?: return null
        check(engine.isModelAvailable()) { MODEL_MISSING_MESSAGE }
        engine.load()
        store.discardRollbackArtifacts()
        restoredInfo
    } catch (_: LinkageError) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    }

private fun restorePreviousEngine(
    engine: LlmEngine,
    modelStore: LocalLlmModelStore?,
    previousState: LlmInitState,
    originalFailure: Throwable,
    updateModelInfo: (LlmModelInfo?) -> Unit,
): LlmInitState {
    if (previousState != LlmInitState.Ready) {
        updateModelInfo(null)
        return LlmInitState.Unavailable(LlmFailure.MODEL_INVALID)
    }
    if (!engine.isModelAvailable()) {
        originalFailure.addSuppressed(IllegalStateException(MODEL_MISSING_MESSAGE))
        updateModelInfo(null)
        return LlmInitState.Unavailable(LlmFailure.MODEL_INVALID)
    }
    return try {
        val verifiedInfo = modelStore?.verifyInstalledModel()
        if (modelStore != null && verifiedInfo == null) {
            originalFailure.addSuppressed(IllegalStateException(MODEL_MISSING_MESSAGE))
            updateModelInfo(null)
            return LlmInitState.Unavailable(LlmFailure.MODEL_MISSING)
        }
        engine.load()
        updateModelInfo(verifiedInfo)
        LlmInitState.Ready
    } catch (error: ModelIntegrityException) {
        originalFailure.addSuppressedDistinct(error)
        engine.closeBestEffort()
        updateModelInfo(null)
        LlmInitState.Unavailable(LlmFailure.MODEL_INTEGRITY_FAILED)
    } catch (error: LinkageError) {
        originalFailure.addSuppressedDistinct(error)
        engine.closeBestEffort()
        LlmInitState.Unavailable(LlmFailure.LOAD_FAILED)
    } catch (error: OutOfMemoryError) {
        originalFailure.addSuppressedDistinct(error)
        engine.closeBestEffort()
        LlmInitState.Unavailable(LlmFailure.LOAD_FAILED)
    } catch (error: Exception) {
        originalFailure.addSuppressedDistinct(error)
        engine.closeBestEffort()
        LlmInitState.Unavailable(LlmFailure.LOAD_FAILED)
    }
}

private fun recoverAfterFailedReplacement(
    engine: LlmEngine,
    modelStore: LocalLlmModelStore?,
    previousState: LlmInitState,
    staged: LocalLlmModelStore.StagedModel,
    originalFailure: Throwable,
    updateModelInfo: (LlmModelInfo?) -> Unit,
): LlmInitState =
    if (staged.rollbackPreserving(originalFailure)) {
        restorePreviousEngine(
            engine = engine,
            modelStore = modelStore,
            previousState = previousState,
            originalFailure = originalFailure,
            updateModelInfo = updateModelInfo,
        )
    } else {
        updateModelInfo(null)
        LlmInitState.Unavailable(LlmFailure.STORAGE_FAILURE)
    }

private fun LocalLlmModelStore.StagedModel.rollbackPreserving(originalFailure: Throwable): Boolean =
    try {
        rollback()
        true
    } catch (error: LinkageError) {
        originalFailure.addSuppressedDistinct(error)
        false
    } catch (error: OutOfMemoryError) {
        originalFailure.addSuppressedDistinct(error)
        false
    } catch (error: Exception) {
        originalFailure.addSuppressedDistinct(error)
        false
    }

private fun Throwable.addSuppressedDistinct(error: Throwable) {
    if (error !== this) addSuppressed(error)
}

private fun stateAfterStagingOom(
    engine: LlmEngine,
    previousState: LlmInitState,
): LlmInitState {
    if (previousState == LlmInitState.Ready) return LlmInitState.Ready
    engine.closeBestEffort()
    return LlmInitState.Unavailable(LlmFailure.LOAD_FAILED)
}

private const val MODEL_MISSING_MESSAGE = "On-device model file is missing"

private class ModelInstallationSupersededException : IllegalStateException("Model installation was superseded")

private fun LlmEngine.closeBestEffort() {
    try {
        close()
    } catch (_: LinkageError) {
        // The state generation already revoked this engine; local cleanup must continue.
    } catch (_: OutOfMemoryError) {
        // Avoid letting a native teardown failure strand model or settings cleanup.
    } catch (_: Exception) {
        // Closing is best-effort; Idle still permits a later initialization attempt.
    }
}

private fun LlmInitState.orUnavailable(failure: LlmFailure): LlmInitState =
    if (this == LlmInitState.Ready) this else LlmInitState.Unavailable(failure)

private fun AtomicLong.isCurrent(
    lock: Any,
    requestedGeneration: Long,
): Boolean = synchronized(lock) { get() == requestedGeneration }

private fun AtomicLong.requireCurrentInstallation(
    lock: Any,
    requestedGeneration: Long,
) {
    if (!isCurrent(lock, requestedGeneration)) {
        throw ModelInstallationSupersededException()
    }
}

private fun AtomicLong.commitInstallationIfCurrent(
    lock: Any,
    requestedGeneration: Long,
    commit: () -> Unit,
) {
    synchronized(lock) {
        if (get() != requestedGeneration) {
            throw ModelInstallationSupersededException()
        }
        commit()
    }
}
