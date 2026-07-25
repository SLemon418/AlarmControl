package com.alarmcontrol.ml.llm

/**
 * Deterministic [LlmEngine] for manager tests (mirrors `FakeInferenceBackend`): simulates a present
 * or missing model, an optional load/analyze failure, and a canned result. Records call counts so
 * tests can assert idempotency and delegation.
 */
internal class FakeLlmEngine(
    private val available: Boolean = true,
    private val loadError: Throwable? = null,
    private val analyzeError: Throwable? = null,
    private val result: LlmAnalysisResult = LlmAnalysisResult.of(true, 0.9f, "promotional language detected"),
) : LlmEngine {
    var loadCalls = 0
        private set
    var analyzeCalls = 0
        private set
    var closed = false
        private set

    override fun isModelAvailable(): Boolean = available

    override fun load() {
        loadCalls++
        loadError?.let { throw it }
    }

    override suspend fun analyze(text: String): LlmAnalysisResult {
        analyzeCalls++
        analyzeError?.let { throw it }
        return result
    }

    override fun close() {
        closed = true
    }
}
