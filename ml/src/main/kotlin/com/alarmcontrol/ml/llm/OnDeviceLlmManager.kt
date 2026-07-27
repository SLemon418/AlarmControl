package com.alarmcontrol.ml.llm

import com.alarmcontrol.core.result.DataResult
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream

/**
 * On-device LLM context analyzer for semantic ad-detection (Milestone 4). The **only** public LLM
 * surface; the MediaPipe runtime behind it can change without touching callers (§5).
 *
 * Everything runs locally — no network, ever (§1/§3). Model loading is asynchronous and reported via
 * [initState] so the `NotificationListenerService` is never blocked.
 */
interface OnDeviceLlmManager {
    /** The engine lifecycle; starts at [LlmInitState.Idle]. Observe to gate routing to the LLM. */
    val initState: StateFlow<LlmInitState>

    /** Verified local-model fingerprint, or `null` when no intact imported model is available. */
    val modelInfo: StateFlow<LlmModelInfo?>

    /**
     * Loads the model off the main thread, driving [initState] `Loading -> Ready` (or
     * `-> Unavailable` if the model is missing or fails to load). Idempotent: a no-op while already
     * loading or ready. Engine failures become `Unavailable`; coroutine cancellation propagates.
     */
    suspend fun initialize()

    /**
     * Analyzes [text] for hidden promotional intent. Returns `null` on an unavailable engine or
     * inference failure so callers fall back to rules/classifier (§5); cancellation propagates.
     */
    suspend fun analyze(
        text: String,
        packageName: String? = null,
    ): LlmAnalysisResult?

    /**
     * Atomically installs a user-selected local model and verifies it by loading the native engine.
     * The caller owns [source]. No network path exists; failures are returned as [DataResult.Failure].
     */
    suspend fun installModel(
        source: InputStream,
        expectedBytes: Long? = null,
    ): DataResult<Unit>

    /** Deletes the app-private imported model. Compact bundled classifier assets are untouched. */
    suspend fun removeModel(): DataResult<Unit>

    /** Releases the native engine and resets [initState] to [LlmInitState.Idle]. */
    suspend fun close()
}
