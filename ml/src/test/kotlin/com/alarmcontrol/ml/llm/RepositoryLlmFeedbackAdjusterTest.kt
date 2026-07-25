package com.alarmcontrol.ml.llm

import com.alarmcontrol.core.feedback.AdFeedbackCounts
import com.alarmcontrol.core.filtering.SemanticIntent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryLlmFeedbackAdjusterTest {
    @Test
    fun `no feedback preserves the raw verdict`() {
        val adjuster = RepositoryLlmFeedbackAdjuster(MutableStateFlow(emptyMap()))
        val raw = LlmAnalysisResult.of(true, 0.8f, "promotion")

        assertEquals(raw, adjuster.adjust("com.shop", raw))
    }

    @Test
    fun `consistent local transactional feedback can correct a weak ad verdict`() {
        val adjuster =
            RepositoryLlmFeedbackAdjuster(
                MutableStateFlow(
                    mapOf("com.bank" to AdFeedbackCounts(advertisement = 0, transactional = 12)),
                ),
            )

        val adjusted =
            adjuster.adjust(
                "com.bank",
                LlmAnalysisResult.of(true, 0.6f, "ambiguous offer wording"),
            )

        assertEquals(false, adjusted.isAdvertisement)
        assertEquals(SemanticIntent.TRANSACTIONAL, adjusted.intent)
        assertEquals(0.81f, adjusted.confidenceScore, 0.01f)
    }

    @Test
    fun `one correction is shrunk toward the model rather than replacing it`() {
        val adjuster =
            RepositoryLlmFeedbackAdjuster(
                MutableStateFlow(
                    mapOf("com.shop" to AdFeedbackCounts(transactional = 1)),
                ),
            )

        val adjusted = adjuster.adjust("com.shop", LlmAnalysisResult.of(true, 0.95f, "sale"))

        assertEquals(true, adjusted.isAdvertisement)
    }

    @Test
    fun `seven-class prior can shift a weak verdict to delivery`() {
        val adjuster =
            RepositoryLlmFeedbackAdjuster(
                MutableStateFlow(
                    mapOf(
                        "com.shop" to
                            AdFeedbackCounts(
                                byIntent = mapOf(SemanticIntent.DELIVERY to 9),
                            ),
                    ),
                ),
            )

        val adjusted =
            adjuster.adjust(
                "com.shop",
                LlmAnalysisResult.of(SemanticIntent.MARKETING, 0.55f, "ambiguous shipment offer"),
            )

        assertEquals(SemanticIntent.DELIVERY, adjusted.intent)
    }

    @Test
    fun `unavailable result cannot be turned into a decision by feedback`() {
        val adjuster =
            RepositoryLlmFeedbackAdjuster(
                MutableStateFlow(
                    mapOf("pkg" to AdFeedbackCounts(byIntent = mapOf(SemanticIntent.MARKETING to 100))),
                ),
            )

        assertEquals(LlmAnalysisResult.UNAVAILABLE, adjuster.adjust("pkg", LlmAnalysisResult.UNAVAILABLE))
    }
}
