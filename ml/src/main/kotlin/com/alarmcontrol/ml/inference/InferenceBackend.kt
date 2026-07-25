package com.alarmcontrol.ml.inference

/**
 * The model runtime, isolated behind an interface so the classifier's decision logic can be tested
 * deterministically with a fake, and so the real backend (LiteRT today, another later) can be
 * swapped without touching the classifier (§5).
 */
internal interface InferenceBackend {
    /** Per-label scores for [features], or `null` when no model is available (graceful degradation). */
    fun run(features: FloatArray): FloatArray?
}
