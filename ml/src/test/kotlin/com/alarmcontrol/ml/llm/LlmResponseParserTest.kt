package com.alarmcontrol.ml.llm

import com.alarmcontrol.core.filtering.SemanticIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmResponseParserTest {
    @Test
    fun `parses a clean JSON verdict`() {
        val result =
            LlmResponseParser.parse(
                """{"intent":"MARKETING","confidence":0.85,"reason":"Flash sale"}""",
            )
        assertTrue(result.isAdvertisement)
        assertEquals(SemanticIntent.MARKETING, result.intent)
        assertEquals(0.85f, result.confidenceScore, 1e-4f)
        assertEquals("Flash sale", result.reasoning)
    }

    @Test
    fun `extracts JSON even when the model wraps it in prose`() {
        val raw =
            "Sure! Here is the result:\n" +
                "{\"intent\":\"TRANSACTIONAL\",\"confidence\":0.9,\"reason\":\"Bank debit\"}\nThanks"
        val result = LlmResponseParser.parse(raw)
        assertFalse(result.isAdvertisement)
        assertEquals(0.9f, result.confidenceScore, 1e-4f)
    }

    @Test
    fun `rejects an out-of-range confidence`() {
        val result =
            LlmResponseParser.parse("""{"intent":"MARKETING","confidence":1.4,"reason":"x"}""")
        assertEquals(LlmAnalysisResult.UNAVAILABLE, result)
    }

    @Test
    fun `missing or mistyped fields yield unavailable`() {
        val result = LlmResponseParser.parse("""{"reason": "unsure"}""")
        assertEquals(LlmAnalysisResult.UNAVAILABLE, result)
        assertEquals(
            LlmAnalysisResult.UNAVAILABLE,
            LlmResponseParser.parse("""{"intent":"UNKNOWN","confidence":0.8,"reason":"x"}"""),
        )
    }

    @Test
    fun `rejects a contradictory compatibility verdict`() {
        val result =
            LlmResponseParser.parse(
                """{"intent":"MARKETING","ad":false,"confidence":0.8,"reason":"x"}""",
            )

        assertEquals(LlmAnalysisResult.UNAVAILABLE, result)
    }

    @Test
    fun `non-JSON or empty output yields UNAVAILABLE`() {
        assertEquals(LlmAnalysisResult.UNAVAILABLE, LlmResponseParser.parse("I cannot answer that."))
        assertEquals(LlmAnalysisResult.UNAVAILABLE, LlmResponseParser.parse(""))
        assertEquals(LlmAnalysisResult.UNAVAILABLE, LlmResponseParser.parse("{ broken json"))
    }
}
