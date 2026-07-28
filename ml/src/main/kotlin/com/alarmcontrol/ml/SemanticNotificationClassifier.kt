package com.alarmcontrol.ml

import com.alarmcontrol.core.filtering.NotificationSnapshot

/**
 * Bundled, non-generative seven-way notification classifier.
 *
 * Implementations run entirely on-device and never fetch a model. `null` means the bundled encoder
 * is absent, incompatible, or failed; callers must keep filtering rule-first and fail open.
 */
interface SemanticNotificationClassifier {
    /**
     * Returns encoder logits plus a calibrated semantic verdict for [snapshot], or `null` when
     * inference is unavailable. Low-confidence inference is represented by a non-confident result
     * rather than being discarded so it can trigger optional delayed analysis.
     */
    suspend fun classify(snapshot: NotificationSnapshot): SemanticClassificationResult?

    /**
     * Classifies [snapshot] with scheduling [urgency]. The default preserves compatibility with
     * implementations that only provide the original one-argument contract.
     */
    suspend fun classify(
        snapshot: NotificationSnapshot,
        urgency: SemanticInferenceUrgency,
    ): SemanticClassificationResult? = classify(snapshot)
}
