package com.alarmcontrol.ml.classifier

import com.alarmcontrol.ml.inference.InferenceBackend

/**
 * Deterministic [InferenceBackend] for classifier tests: returns fixed [scores] (or `null` for an
 * unavailable model), or throws [error] to exercise failure handling. Records call count.
 */
internal class FakeInferenceBackend(
    private val scores: FloatArray?,
    private val error: Throwable? = null,
) : InferenceBackend {
    var calls = 0
        private set

    override fun run(features: FloatArray): FloatArray? {
        calls++
        error?.let { throw it }
        return scores
    }
}
