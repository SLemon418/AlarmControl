package com.alarmcontrol.ml.feedback

/**
 * Adjusts a model's raw per-label scores using the user's stored corrections (CLAUDE.md §5). Kept
 * behind an interface so the classifier's decision logic stays testable and the blending strategy can
 * change without touching callers.
 */
internal interface FeedbackBlender {
    /**
     * Returns [scores] biased toward the labels the user has assigned to [packageName]. [labels] is
     * the model's output order (index-aligned with [scores]). Implementations must return [scores]
     * unchanged when there is no feedback, so classification degrades gracefully (§5).
     */
    suspend fun blend(
        packageName: String,
        labels: List<String>,
        scores: FloatArray,
    ): FloatArray
}

/** Identity blender: model output is used as-is. The default, so model-only behavior is preserved. */
internal object NoOpFeedbackBlender : FeedbackBlender {
    override suspend fun blend(
        packageName: String,
        labels: List<String>,
        scores: FloatArray,
    ) = scores
}
