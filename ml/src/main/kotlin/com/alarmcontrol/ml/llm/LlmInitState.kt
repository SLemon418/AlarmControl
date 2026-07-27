package com.alarmcontrol.ml.llm

/**
 * Lifecycle of the on-device LLM engine (Milestone 4). The notification path observes this and only
 * routes to the LLM when [Ready]; in every other state it falls back to the rules engine and the
 * lightweight classifier, so analysis degrades gracefully and never blocks (CLAUDE.md §5).
 */
sealed interface LlmInitState {
    /** Nothing loaded yet; no work has started. */
    data object Idle : LlmInitState

    /** A user-selected local model is being copied into private storage. */
    data class Installing(
        val copiedBytes: Long,
        val totalBytes: Long?,
    ) : LlmInitState

    /** Model load is in progress on a background thread. */
    data object Loading : LlmInitState

    /** The engine is loaded and ready to analyze. */
    data object Ready : LlmInitState

    /**
     * The engine is unavailable — the model file is missing, invalid, or could not be loaded.
     * [failure] is stable and contains no notification content (§3), so UI can localize it safely.
     */
    data class Unavailable(
        val failure: LlmFailure,
    ) : LlmInitState
}

/** Stable, localizable reason why an optional on-device LLM cannot be used. */
enum class LlmFailure {
    MODEL_MISSING,
    MODEL_INVALID,
    MODEL_INTEGRITY_FAILED,
    LOAD_FAILED,
    STORAGE_FAILURE,
}
