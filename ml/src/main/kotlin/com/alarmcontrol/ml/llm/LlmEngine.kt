package com.alarmcontrol.ml.llm

/**
 * The LLM runtime, isolated behind an interface so [DefaultOnDeviceLlmManager]'s state machine and
 * threading can be unit-tested with a fake, and so the MediaPipe Tasks GenAI backend can be swapped
 * without touching the manager or its callers (§5). Mirrors the classifier's
 * `InferenceBackend` seam.
 *
 * Implementations load a **local** model only; no download is ever attempted (§3).
 */
internal interface LlmEngine {
    /** Whether the on-device model is present and loadable. Checked before [load]. */
    fun isModelAvailable(): Boolean

    /**
     * Loads the model, replacing and releasing any previously loaded native instance. Blocking and
     * native — always invoked off the main thread. Throws on failure.
     */
    fun load()

    /** Runs analysis on [text] using the loaded model without blocking the caller thread. */
    suspend fun analyze(text: String): LlmAnalysisResult

    /** Releases native resources. */
    fun close()
}
