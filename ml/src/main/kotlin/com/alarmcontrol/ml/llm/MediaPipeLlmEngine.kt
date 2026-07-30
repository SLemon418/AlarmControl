// The project pins MediaPipe Tasks GenAI for this milestone even though the 0.10.x Java API is
// maintenance-only and deprecated. Keep that warning localized to this backend.
@file:Suppress("DEPRECATION", "TooGenericExceptionCaught")

package com.alarmcontrol.ml.llm

import android.content.Context
import com.alarmcontrol.ml.MlConfig
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LlmEngine] backed by MediaPipe Tasks GenAI (Milestone 4). Loads a compatible LOCAL quantized
 * text model from [modelFile] and runs on-device inference — no network, ever (§1/§3). The model is
 * not bundled in the APK; the user prepares and imports it from local storage, and it is copied
 * under app-private storage. [isModelAvailable] reports its absence so the manager degrades
 * gracefully (§5).
 *
 * Device-only (native runtime), so — like the classifier's TFLite backend — it isn't part of the JVM
 * unit suite; the manager's state/threading logic, bounded prompt, and strict response parser are
 * covered independently with deterministic JVM tests.
 */
internal class MediaPipeLlmEngine(
    private val context: Context,
    private val modelFile: File,
) : LlmEngine {
    private val inferenceLifecycle = DeferredCloseResource<LlmInference>(LlmInference::close)

    override fun isModelAvailable(): Boolean = modelFile.isFile && modelFile.length() > 0

    override fun load() {
        inferenceLifecycle.replace {
            val options =
                LlmInferenceOptions
                    .builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(MlConfig.LLM_CONTEXT_TOKENS)
                    .build()
            LlmInference.createFromOptions(context, options)
        }
    }

    override suspend fun analyze(text: String): LlmAnalysisResult {
        val inferenceLease = inferenceLifecycle.acquire() ?: return LlmAnalysisResult.UNAVAILABLE
        var session: LlmInferenceSession? = null
        val releaseSession = {
            inferenceLease.release {
                session?.close()
            }
        }
        var terminalOwnsSession = false
        return try {
            val engine = inferenceLease.value
            val prompt =
                LlmPrompt.buildFitting(
                    text = text,
                    maxPromptTokens = MlConfig.LLM_CONTEXT_TOKENS - MlConfig.LLM_OUTPUT_TOKEN_RESERVE,
                    countTokens = engine::sizeInTokens,
                ) ?: return LlmAnalysisResult.UNAVAILABLE
            val sessionOptions =
                LlmInferenceSessionOptions
                    .builder()
                    .setTopK(MlConfig.LLM_TOP_K)
                    .setTopP(MlConfig.LLM_TOP_P)
                    .setTemperature(MlConfig.LLM_TEMPERATURE)
                    .setRandomSeed(MlConfig.LLM_RANDOM_SEED)
                    .build()
            val createdSession = LlmInferenceSession.createFromOptions(engine, sessionOptions)
            session = createdSession
            createdSession.addQueryChunk(prompt)
            val future = createdSession.generateResponseAsync()
            terminalOwnsSession = true
            val response =
                awaitMediaPipeResponse(
                    future = future,
                    cancelGeneration = createdSession::cancelGenerateResponseAsync,
                    onTerminal = releaseSession,
                )
            LlmResponseParser.parse(response)
        } finally {
            if (!terminalOwnsSession) {
                releaseSession()
            }
        }
    }

    override fun close() {
        inferenceLifecycle.close()
    }
}

/**
 * Owns one native resource while allowing in-flight leases to drain before a requested close.
 * Acquisition and the active-use increment are atomic, so close cannot detach a resource between
 * an analyzer's snapshot and its native session creation.
 */
internal class DeferredCloseResource<T : Any>(
    private val closeResource: (T) -> Unit,
) {
    private val lock = Any()
    private var resource: T? = null
    private var activeLeases = 0
    private var closeRequested = false
    private var replacementGeneration = 0L

    fun replace(create: () -> T) {
        synchronized(lock) {
            check(activeLeases == 0) {
                "Previous native generation is still draining"
            }
            replacementGeneration += 1
            val generation = replacementGeneration
            closeRequested = false
            detachResourceLocked()?.let(closeResource)
            val replacement = create()
            if (replacementGeneration != generation || closeRequested) {
                closeResource(replacement)
            } else {
                resource = replacement
            }
        }
    }

    fun acquire(): DeferredCloseLease<T>? =
        synchronized(lock) {
            val current = resource?.takeUnless { closeRequested } ?: return null
            activeLeases += 1
            DeferredCloseLease(current, ::releaseLease)
        }

    fun close() {
        synchronized(lock) {
            closeRequested = true
            if (activeLeases == 0) {
                detachResourceLocked()?.let(closeResource)
            }
        }
    }

    private fun releaseLease() {
        synchronized(lock) {
            check(activeLeases > 0) {
                "Native resource lease released more than once"
            }
            activeLeases -= 1
            if (activeLeases == 0 && closeRequested) {
                try {
                    detachResourceLocked()?.let(closeResource)
                } catch (_: Throwable) {
                    // State already prevents reuse; a later load may retry with a fresh engine.
                }
            }
        }
    }

    private fun detachResourceLocked(): T? = resource.also { resource = null }
}

/** Exactly-once lease whose cleanup runs before the deferred native resource release. */
internal class DeferredCloseLease<T : Any>(
    val value: T,
    private val releaseResource: () -> Unit,
) {
    private val released = AtomicBoolean(false)

    fun release(cleanup: () -> Unit = {}) {
        if (!released.compareAndSet(false, true)) return
        try {
            cleanup()
        } catch (_: Throwable) {
            // A late native callback has no caller to receive teardown errors.
        } finally {
            releaseResource()
        }
    }
}

internal const val MEDIAPIPE_CANCELLATION_DRAIN_TIMEOUT_MILLIS = 1_000L

private fun runTerminalCallbackOnce(
    completed: AtomicBoolean,
    onTerminal: () -> Unit,
) {
    if (!completed.compareAndSet(false, true)) return
    try {
        onTerminal()
    } catch (_: Throwable) {
        // Never throw teardown failures on a native future callback thread.
    }
}

/**
 * MediaPipe 0.10.35 does not connect [ListenableFuture.cancel] to native generation cancellation.
 * Request cancellation through the session API and briefly drain its terminal callback. If the
 * native future never finishes, the caller still returns within a fixed bound; [onTerminal] owns
 * the eventual session and deferred-engine cleanup.
 */
internal suspend fun <T> awaitMediaPipeResponse(
    future: ListenableFuture<T>,
    cancelGeneration: () -> Unit,
    onTerminal: () -> Unit = {},
): T {
    val terminalCallbackCompleted = AtomicBoolean(false)
    try {
        future.addListener(
            {
                runTerminalCallbackOnce(terminalCallbackCompleted, onTerminal)
            },
            DIRECT_EXECUTOR,
        )
    } catch (error: Throwable) {
        runTerminalCallbackOnce(terminalCallbackCompleted, onTerminal)
        throw error
    }
    return try {
        future.awaitResult()
    } catch (error: CancellationException) {
        val cancellationFailure =
            if (future.isDone) {
                null
            } else {
                try {
                    cancelGeneration()
                    null
                } catch (failure: Throwable) {
                    failure
                }
            }
        withContext(NonCancellable) {
            withTimeoutOrNull(MEDIAPIPE_CANCELLATION_DRAIN_TIMEOUT_MILLIS) {
                future.awaitCompletion()
            }
        }
        if (cancellationFailure != null && cancellationFailure !== error) {
            error.addSuppressed(cancellationFailure)
            (error.cause as? CancellationException)
                ?.takeUnless { cause -> cause === error || cause === cancellationFailure }
                ?.addSuppressed(cancellationFailure)
        }
        throw error
    }
}

private suspend fun <T> ListenableFuture<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                runCatching { get() }
                    .onSuccess(continuation::resume)
                    .onFailure(continuation::resumeWithException)
            },
            DIRECT_EXECUTOR,
        )
    }

private suspend fun ListenableFuture<*>.awaitCompletion() {
    suspendCancellableCoroutine<Unit> { continuation ->
        addListener(
            { continuation.resume(Unit) },
            DIRECT_EXECUTOR,
        )
    }
}

private val DIRECT_EXECUTOR = Executor { command -> command.run() }
