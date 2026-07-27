@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.alarmcontrol.ml.llm

import com.alarmcontrol.core.result.DataResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
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
) : OnDeviceLlmManager {
    private val _initState = MutableStateFlow<LlmInitState>(LlmInitState.Idle)
    override val initState: StateFlow<LlmInitState> = _initState.asStateFlow()
    private val _modelInfo = MutableStateFlow<LlmModelInfo?>(null)
    override val modelInfo: StateFlow<LlmModelInfo?> = _modelInfo.asStateFlow()

    private val stateLock = Any()
    private val generation = AtomicLong(0)
    private val analysisSlots = Semaphore(ANALYSIS_CAPACITY)
    private val commands = Channel<EngineCommand>(COMMAND_CAPACITY)
    private val actorScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val installInProgress = AtomicBoolean(false)

    init {
        actorScope.launch {
            for (command in commands) process(command)
        }
    }

    override suspend fun initialize() {
        val (requestedGeneration, previousState) =
            synchronized(stateLock) {
                val current = _initState.value
                when (current) {
                    LlmInitState.Loading, LlmInitState.Ready -> return
                    is LlmInitState.Installing -> return
                    LlmInitState.Idle, is LlmInitState.Unavailable -> {
                        _initState.value = LlmInitState.Loading
                        generation.get() to current
                    }
                }
            }
        val reply = CompletableDeferred<Unit>()
        var enqueued = false
        try {
            commands.send(EngineCommand.Initialize(requestedGeneration, reply))
            enqueued = true
            reply.await()
        } catch (error: CancellationException) {
            if (!enqueued) updateState(requestedGeneration, previousState)
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

        val reply = CompletableDeferred<LlmAnalysisResult?>()
        val sent = commands.trySend(EngineCommand.Analyze(requestedGeneration, text, packageName, reply))
        if (sent.isFailure) {
            analysisSlots.release()
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
        if (!installInProgress.compareAndSet(false, true)) {
            return DataResult.Failure(IllegalStateException("Model installation is already running"))
        }
        val previousState: LlmInitState
        val requestedGeneration: Long
        synchronized(stateLock) {
            previousState = _initState.value
            requestedGeneration = generation.incrementAndGet()
            _initState.value = LlmInitState.Installing(copiedBytes = 0, totalBytes = expectedBytes)
        }
        val reply = CompletableDeferred<DataResult<Unit>>()
        var enqueued = false
        return try {
            commands.send(
                EngineCommand.Install(
                    generation = requestedGeneration,
                    source = source,
                    expectedBytes = expectedBytes,
                    previousState = previousState,
                    reply = reply,
                ),
            )
            enqueued = true
            reply.await()
        } catch (error: CancellationException) {
            if (!enqueued) {
                updateState(requestedGeneration, previousState)
                installInProgress.set(false)
            }
            throw error
        } catch (error: Exception) {
            if (!enqueued) {
                updateState(requestedGeneration, previousState)
                installInProgress.set(false)
            }
            DataResult.Failure(error)
        }
    }

    override suspend fun removeModel(): DataResult<Unit> {
        val (requestedGeneration, previousState) =
            synchronized(stateLock) {
                val current = _initState.value
                generation.incrementAndGet().also { _initState.value = LlmInitState.Idle } to current
            }
        val reply = CompletableDeferred<DataResult<Unit>>()
        var enqueued = false
        return try {
            commands.send(EngineCommand.Remove(requestedGeneration, reply))
            enqueued = true
            reply.await()
        } catch (error: CancellationException) {
            if (!enqueued) updateState(requestedGeneration, previousState)
            throw error
        }
    }

    override suspend fun close() {
        val (requestedGeneration, previousState) =
            synchronized(stateLock) {
                val current = _initState.value
                generation.incrementAndGet().also { _initState.value = LlmInitState.Idle } to current
            }
        val reply = CompletableDeferred<Unit>()
        var enqueued = false
        try {
            commands.send(EngineCommand.Close(requestedGeneration, reply))
            enqueued = true
            reply.await()
        } catch (error: CancellationException) {
            if (!enqueued) updateState(requestedGeneration, previousState)
            throw error
        }
    }

    private suspend fun process(command: EngineCommand) {
        when (command) {
            is EngineCommand.Initialize -> processInitialize(command)
            is EngineCommand.Analyze -> processAnalyze(command)
            is EngineCommand.Install -> processInstall(command)
            is EngineCommand.Remove -> processRemove(command)
            is EngineCommand.Close -> processClose(command)
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
        } catch (error: Exception) {
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
            command.reply.complete(
                DataResult.Failure(IllegalStateException("Model installation is not configured")),
            )
            return
        }
        val result =
            try {
                val staged =
                    store.stage(command.source, command.expectedBytes) { copied ->
                        requireCurrentInstallation(command.generation)
                        updateState(
                            command.generation,
                            LlmInitState.Installing(copied, command.expectedBytes),
                        )
                    }
                try {
                    requireCurrentInstallation(command.generation)
                    updateState(command.generation, LlmInitState.Loading)
                    engine.close()
                    requireCurrentInstallation(command.generation)
                    staged.activate()
                    requireCurrentInstallation(command.generation)
                    check(engine.isModelAvailable()) { MODEL_MISSING_MESSAGE }
                    engine.load()
                    commitInstallationIfCurrent(command.generation, staged)
                    DataResult.Success(Unit)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ModelInstallationSupersededException) {
                    engine.closeBestEffort()
                    staged.rollback()
                    DataResult.Failure(error)
                } catch (error: OutOfMemoryError) {
                    engine.closeBestEffort()
                    staged.rollback()
                    updateState(command.generation, LlmInitState.Unavailable(LlmFailure.LOAD_FAILED))
                    DataResult.Failure(error)
                } catch (error: LinkageError) {
                    engine.closeBestEffort()
                    staged.rollback()
                    updateState(
                        command.generation,
                        restorePreviousEngine(command.generation, command.previousState),
                    )
                    DataResult.Failure(error)
                } catch (error: Exception) {
                    engine.closeBestEffort()
                    staged.rollback()
                    updateState(
                        command.generation,
                        restorePreviousEngine(command.generation, command.previousState),
                    )
                    DataResult.Failure(error)
                }
            } catch (error: IllegalArgumentException) {
                updateState(command.generation, command.previousState.orUnavailable(LlmFailure.MODEL_INVALID))
                DataResult.Failure(error)
            } catch (error: Exception) {
                updateState(command.generation, command.previousState.orUnavailable(LlmFailure.STORAGE_FAILURE))
                DataResult.Failure(error)
            }
        command.reply.complete(result)
    }

    private fun requireCurrentInstallation(requestedGeneration: Long) {
        if (!generation.isCurrent(stateLock, requestedGeneration)) {
            throw ModelInstallationSupersededException()
        }
    }

    private fun commitInstallationIfCurrent(
        requestedGeneration: Long,
        staged: LocalLlmModelStore.StagedModel,
    ) {
        synchronized(stateLock) {
            if (generation.get() != requestedGeneration) {
                throw ModelInstallationSupersededException()
            }
            staged.commit()
            _modelInfo.value = staged.modelInfo
            _initState.value = LlmInitState.Ready
        }
    }

    private fun processRemove(command: EngineCommand.Remove) {
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

    private fun processClose(command: EngineCommand.Close) {
        engine.closeBestEffort()
        updateState(command.generation, LlmInitState.Idle)
        command.reply.complete(Unit)
    }

    private fun restorePreviousEngine(
        requestedGeneration: Long,
        previousState: LlmInitState,
    ): LlmInitState {
        if (previousState != LlmInitState.Ready || !engine.isModelAvailable()) {
            updateModelInfo(requestedGeneration, null)
            return LlmInitState.Unavailable(LlmFailure.MODEL_INVALID)
        }
        return try {
            val verifiedInfo = modelStore?.verifyInstalledModel()
            if (modelStore != null && verifiedInfo == null) {
                updateModelInfo(requestedGeneration, null)
                return LlmInitState.Unavailable(LlmFailure.MODEL_MISSING)
            }
            engine.load()
            updateModelInfo(requestedGeneration, verifiedInfo)
            LlmInitState.Ready
        } catch (_: ModelIntegrityException) {
            updateModelInfo(requestedGeneration, null)
            LlmInitState.Unavailable(LlmFailure.MODEL_INTEGRITY_FAILED)
        } catch (_: LinkageError) {
            LlmInitState.Unavailable(LlmFailure.LOAD_FAILED)
        } catch (_: OutOfMemoryError) {
            LlmInitState.Unavailable(LlmFailure.LOAD_FAILED)
        } catch (_: Exception) {
            LlmInitState.Unavailable(LlmFailure.LOAD_FAILED)
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
    }

    private companion object {
        const val MIN_TRUSTED_CONFIDENCE = 0.6f
        const val ANALYSIS_CAPACITY = 2
        const val COMMAND_CAPACITY = 4
        const val ENGINE_ANALYSIS_TIMEOUT_MILLIS = 9_000L
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
    } catch (_: Exception) {
        null
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
