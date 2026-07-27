// The project pins MediaPipe Tasks GenAI for this milestone even though the 0.10.x Java API is
// maintenance-only and deprecated. Keep that warning localized to this backend.
@file:Suppress("DEPRECATION")

package com.alarmcontrol.ml.llm

import android.content.Context
import com.alarmcontrol.ml.MlConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LlmEngine] backed by MediaPipe Tasks GenAI (Milestone 4). Loads a LOCAL quantized model (e.g.
 * Gemma) from [modelFile] and runs on-device inference — no network, ever (§1/§3). The model is not
 * bundled in the APK (far too large); the user imports it from local storage and it is copied under
 * app-private storage. [isModelAvailable] reports its absence so the manager degrades gracefully (§5).
 *
 * Device-only (native runtime), so — like the classifier's TFLite backend — it isn't part of the JVM
 * unit suite; the manager's state/threading logic, bounded prompt, and strict response parser are
 * covered independently with deterministic JVM tests.
 */
internal class MediaPipeLlmEngine(
    private val context: Context,
    private val modelFile: File,
) : LlmEngine {
    private var inference: LlmInference? = null

    override fun isModelAvailable(): Boolean = modelFile.isFile && modelFile.length() > 0

    override fun load() {
        val options =
            LlmInferenceOptions
                .builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(MlConfig.LLM_CONTEXT_TOKENS)
                .build()
        inference = LlmInference.createFromOptions(context, options)
    }

    override suspend fun analyze(text: String): LlmAnalysisResult {
        val engine = inference ?: return LlmAnalysisResult.UNAVAILABLE
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
        val response =
            LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
                session.addQueryChunk(prompt)
                val future = session.generateResponseAsync()
                suspendCancellableCoroutine { continuation ->
                    future.addListener(
                        {
                            runCatching { future.get() }
                                .onSuccess(continuation::resume)
                                .onFailure(continuation::resumeWithException)
                        },
                        DIRECT_EXECUTOR,
                    )
                    continuation.invokeOnCancellation { future.cancel(true) }
                }
            }
        return LlmResponseParser.parse(response)
    }

    override fun close() {
        inference?.close()
        inference = null
    }

    private companion object {
        val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}
