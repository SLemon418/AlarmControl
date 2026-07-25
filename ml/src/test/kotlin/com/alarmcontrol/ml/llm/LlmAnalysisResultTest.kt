package com.alarmcontrol.ml.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LlmAnalysisResultTest {
    @Test
    fun `of coerces an over-range confidence down to 1`() {
        val result = LlmAnalysisResult.of(isAdvertisement = true, confidenceScore = 1.5f, reasoning = " spam ")
        assertEquals(true, result.isAdvertisement)
        assertEquals(1f, result.confidenceScore, 0f)
        assertEquals("spam", result.reasoning) // trimmed
    }

    @Test
    fun `of coerces a negative confidence up to 0`() {
        val result = LlmAnalysisResult.of(isAdvertisement = false, confidenceScore = -0.3f, reasoning = "transactional")
        assertEquals(0f, result.confidenceScore, 0f)
    }

    @Test
    fun `of maps a non-finite confidence to 0`() {
        val result = LlmAnalysisResult.of(isAdvertisement = true, confidenceScore = Float.NaN, reasoning = "x")
        assertEquals(0f, result.confidenceScore, 0f)
        assertEquals(0f, LlmAnalysisResult.of(true, Float.POSITIVE_INFINITY, "x").confidenceScore, 0f)
    }

    @Test
    fun `UNAVAILABLE is a safe non-committal result`() {
        assertFalse(LlmAnalysisResult.UNAVAILABLE.isAdvertisement)
        assertEquals(0f, LlmAnalysisResult.UNAVAILABLE.confidenceScore, 0f)
        assertEquals("", LlmAnalysisResult.UNAVAILABLE.reasoning)
    }
}
