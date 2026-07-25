package com.alarmcontrol.data.repository

import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.insights.ActionBreakdown
import com.alarmcontrol.core.insights.AppInsightCount
import com.alarmcontrol.core.insights.CategoryCount
import com.alarmcontrol.core.insights.ChannelCount
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.insights.HourInsightCount
import com.alarmcontrol.core.insights.InsightsBucket
import com.alarmcontrol.core.insights.InsightsDateRange
import com.alarmcontrol.core.insights.RuleTriggerCount
import com.alarmcontrol.core.insights.SemanticIntentCount
import com.alarmcontrol.data.mapper.toWrite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InsightsAnalyticsRepositoryImplTest {
    private val dao = FakeDailyInsightDao()
    private val repository = InsightsAnalyticsRepositoryImpl(dao)

    @Test
    fun `combines retained daily aggregates across a selected range`() =
        runTest {
            store(day(10, total = 10, cancelled = 3, kept = 7))
            store(day(11, total = 20, cancelled = 4, kept = 16))
            store(day(12, total = 100, cancelled = 100, kept = 0))

            val analytics = repository.observe(InsightsDateRange(10, 11)).first()

            assertEquals(30, analytics.totalNotifications)
            assertEquals(ActionBreakdown(cancelled = 7, kept = 23), analytics.actionBreakdown)
            assertEquals(23, analytics.silencedPercent)
            assertEquals(listOf(AppInsightCount("com.shop", 30, 7)), analytics.apps)
            assertEquals(5, analytics.rules.first { it.ruleId == "1" }.actualCount)
            assertEquals(3, analytics.rules.first { it.ruleId == "2" }.monitoredCount)
            assertEquals(30, analytics.categories.single().count)
            assertEquals(30, analytics.channels.single().count)
            assertEquals(30, analytics.hours.single().totalCount)
            assertEquals(30, analytics.countFor(SemanticIntent.MARKETING))
            assertEquals(3, analytics.mlClassifiedCount)
            assertEquals(2, analytics.categoryCorrectionCount)
            assertEquals(1, analytics.semanticCorrectionCount)
            assertEquals(10L, analytics.breakdownCoverageStartEpochDay)
            assertEquals(InsightsBucket.DAY, analytics.bucket)
            assertEquals(listOf(10L, 11L), analytics.trend.map { it.startEpochDay })
        }

    @Test
    fun `reports the full available date range`() =
        runTest {
            store(day(3, total = 1, cancelled = 0, kept = 1))
            store(day(9, total = 1, cancelled = 0, kept = 1))

            assertEquals(InsightsDateRange(3, 9), repository.observeAvailableRange().first())
        }

    private suspend fun store(insight: DailyInsight) {
        dao.store(insight.toWrite())
    }

    private fun day(
        epochDay: Long,
        total: Int,
        cancelled: Int,
        kept: Int,
    ): DailyInsight =
        DailyInsight(
            epochDay = epochDay,
            windowStartMillis = epochDay * DAY_MILLIS,
            windowEndMillis = (epochDay + 1) * DAY_MILLIS,
            totalNotifications = total,
            mutedCount = cancelled,
            topRules = listOf(RuleTriggerCount("1", if (epochDay == 10L) 2 else 3)),
            topMonitoredRules = listOf(RuleTriggerCount("2", if (epochDay == 10L) 1 else 2)),
            categoryBreakdown = listOf(CategoryCount("promotion", total)),
            generatedAtMillis = epochDay * DAY_MILLIS,
            actionBreakdown = ActionBreakdown(cancelled = cancelled, kept = kept),
            channelBreakdown = listOf(ChannelCount("com.shop", "offers", total, "Offers")),
            appBreakdown = listOf(AppInsightCount("com.shop", total, cancelled)),
            hourBreakdown = listOf(HourInsightCount(9, total, cancelled)),
            semanticBreakdown = listOf(SemanticIntentCount(SemanticIntent.MARKETING, total)),
            mlClassifiedCount = if (epochDay == 10L) 1 else 2,
            categoryCorrectionCount = 1,
            semanticCorrectionCount = if (epochDay == 10L) 1 else 0,
            breakdownVersion = 1,
        )

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
