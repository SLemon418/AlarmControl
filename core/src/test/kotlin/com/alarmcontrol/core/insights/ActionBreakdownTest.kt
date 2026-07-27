package com.alarmcontrol.core.insights

import com.alarmcontrol.core.filtering.SemanticIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ActionBreakdownTest {
    @Test
    fun `derived totals saturate instead of wrapping negative`() {
        val breakdown =
            ActionBreakdown(
                cancelled = Int.MAX_VALUE,
                snoozed = Int.MAX_VALUE,
                loggedOnly = Int.MAX_VALUE,
                kept = Int.MAX_VALUE,
            )

        assertEquals(Int.MAX_VALUE, breakdown.silenced)
        assertEquals(Int.MAX_VALUE, breakdown.total)
    }

    @Test
    fun `analytics percentages and semantic totals do not overflow`() {
        val analytics =
            InsightsAnalytics(
                range = InsightsDateRange(1, 1),
                totalNotifications = Int.MAX_VALUE,
                actionBreakdown = ActionBreakdown(cancelled = Int.MAX_VALUE),
                monitoredActionBreakdown = ActionBreakdown(),
                apps = emptyList(),
                rules = emptyList(),
                categories = emptyList(),
                channels = emptyList(),
                hours = emptyList(),
                semanticIntents =
                    listOf(
                        SemanticIntentCount(SemanticIntent.MARKETING, Int.MAX_VALUE),
                        SemanticIntentCount(SemanticIntent.OTHER, Int.MAX_VALUE),
                    ),
                mlClassifiedCount = 0,
                categoryCorrectionCount = 0,
                semanticCorrectionCount = 0,
                bucket = InsightsBucket.DAY,
                trend = emptyList(),
                breakdownCoverageStartEpochDay = null,
            )

        assertEquals(100, analytics.silencedPercent)
        assertEquals(Int.MAX_VALUE, analytics.semanticTotal)
    }

    @Test
    fun `analysis range rejects dates the UI cannot safely render`() {
        assertThrows(IllegalArgumentException::class.java) {
            InsightsDateRange(-1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InsightsDateRange(0, MAX_SUPPORTED_INSIGHT_EPOCH_DAY + 1)
        }
    }
}
