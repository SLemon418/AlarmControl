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
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Serializes access to the native LLM runtime on the injected background [dispatcher]. At most one
 * analysis runs and one waits; further requests return `null` immediately so notification bursts
 * cannot build an unbounded native-inference queue. A cancelled caller only abandons its result:
 * the actor retains ownership of the native call and prevents overlapping executions.
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
internal class DefaultOnDeviceLlmManager(
    private val engine: LlmEngine,
    dispatcher: CoroutineDispatcher,
    private val modelStore: LocalLlmModelStore? = null,
    private val feedbackAdjuster: LlmFeedbackAdjuster = LlmFeedbackAdjuster { _, result -> result },
) : OnDeviceLlmManager {
    private val _initState = MutableStateFlow<LlmInitState>(LlmInitState.Idle)
    override val initState: StateFlow<LlmInitState> = _initState.asStateFlow()

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
        val requestedGeneration =
            synchronized(stateLock) {
                when (_initState.value) {
                    LlmInitState.Loading, LlmInitState.Ready -> return
                    is LlmInitState.Installing -> return
                    LlmInitState.Idle, is LlmInitState.Unavailable -> {
                        _initState.value = LlmInitState.Loading
                        generation.get()
                    }
                }
            }
        val reply = CompletableDeferred<Unit>()
        commands.send(EngineCommand.Initialize(requestedGeneration, reply))
        reply.await()
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
            reply.await()
        } finally {
            installInProgress.set(false)
        }
    }

    override suspend fun removeModel(): DataResult<Unit> {
        val requestedGeneration =
            synchronized(stateLock) {
                generation.incrementAndGet().also { _initState.value = LlmInitState.Idle }
            }
        val reply = CompletableDeferred<DataResult<Unit>>()
        commands.send(EngineCommand.Remove(requestedGeneration, reply))
        return reply.await()
    }

    override suspend fun close() {
        val requestedGeneration =
            synchronized(stateLock) {
                generation.incrementAndGet().also { _initState.value = LlmInitState.Idle }
            }
        val reply = CompletableDeferred<Unit>()
        commands.send(EngineCommand.Close(requestedGeneration, reply))
        reply.await()
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
            if (!engine.isModelAvailable()) {
                updateState(command.generation, LlmInitState.Unavailable(LlmFailure.MODEL_MISSING))
            } else {
                engine.load()
                updateState(command.generation, LlmInitState.Ready)
            }
        } catch (error: Exception) {
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
        } catch (_: Exception) {
            command.reply.complete(null)
        } finally {
            analysisSlots.release()
        }
    }

    private suspend fun analyzeIfCurrent(command: EngineCommand.Analyze): LlmAnalysisResult? {
        if (!isCurrent(command.generation, LlmInitState.Ready)) return null
        val raw = engine.analyze(command.text)
        val adjusted = command.packageName?.let { feedbackAdjuster.adjust(it, raw) } ?: raw
        return adjusted.takeIf {
            it.intent != com.alarmcontrol.core.filtering.SemanticIntent.AMBIGUOUS &&
                it.confidenceScore >= MIN_TRUSTED_CONFIDENCE
        } ?: LlmAnalysisResult.UNAVAILABLE
    }

    private fun processInstall(command: EngineCommand.Install) {
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
                        updateState(
                            command.generation,
                            LlmInitState.Installing(copied, command.expectedBytes),
                        )
                    }
                updateState(command.generation, LlmInitState.Loading)
                try {
                    engine.close()
                    staged.activate()
                    check(engine.isModelAvailable()) { MODEL_MISSING }
                    engine.load()
                    staged.commit()
                    updateState(command.generation, LlmInitState.Ready)
                    DataResult.Success(Unit)
                } catch (error: Exception) {
                    engine.close()
                    staged.rollback()
                    updateState(command.generation, restorePreviousEngine(command.previousState))
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

    private fun processRemove(command: EngineCommand.Remove) {
        val store = modelStore
        val result =
            if (store == null) {
                DataResult.Failure(IllegalStateException("Model installation is not configured"))
            } else {
                try {
                    engine.close()
                    store.delete()
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
        try {
            engine.close()
        } catch (_: Exception) {
            // Closing is best-effort; Idle still permits a later initialization attempt.
        }
        updateState(command.generation, LlmInitState.Idle)
        command.reply.complete(Unit)
    }

    private fun restorePreviousEngine(previousState: LlmInitState): LlmInitState {
        if (previousState != LlmInitState.Ready || !engine.isModelAvailable()) {
            return LlmInitState.Unavailable(LlmFailure.MODEL_INVALID)
        }
        return try {
            engine.load()
            LlmInitState.Ready
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

    private fun LlmInitState.orUnavailable(failure: LlmFailure): LlmInitState =
        if (this == LlmInitState.Ready) this else LlmInitState.Unavailable(failure)

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
        const val MODEL_MISSING = "On-device model file is missing"
    }
}
