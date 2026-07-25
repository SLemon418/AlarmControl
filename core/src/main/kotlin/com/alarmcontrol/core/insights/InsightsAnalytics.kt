package com.alarmcontrol.core.insights

import com.alarmcontrol.core.filtering.SemanticIntent
import kotlinx.coroutines.flow.Flow

/** Inclusive local-day range selected by the user. */
data class InsightsDateRange(
    val startEpochDay: Long,
    val endEpochDay: Long,
) {
    init {
        require(startEpochDay <= endEpochDay) { "Insight start day must not follow end day" }
    }
}

data class RuleInsightCount(
    val ruleId: String,
    val actualCount: Int,
    val monitoredCount: Int,
)

enum class InsightsBucket {
    DAY,
    WEEK,
    MONTH,
}

data class InsightsTrendPoint(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val totalCount: Int,
    val silencedCount: Int,
)

data class InsightsAnalytics(
    val range: InsightsDateRange,
    val totalNotifications: Int,
    val actionBreakdown: ActionBreakdown,
    val monitoredActionBreakdown: ActionBreakdown,
    val apps: List<AppInsightCount>,
    val rules: List<RuleInsightCount>,
    val categories: List<CategoryCount>,
    val channels: List<ChannelCount>,
    val hours: List<HourInsightCount>,
    val semanticIntents: List<SemanticIntentCount>,
    val mlClassifiedCount: Int,
    val categoryCorrectionCount: Int,
    val semanticCorrectionCount: Int,
    val bucket: InsightsBucket,
    val trend: List<InsightsTrendPoint>,
    /** Old migrations may lack newer breakdowns; these days must not be presented as zero. */
    val breakdownCoverageStartEpochDay: Long?,
) {
    val silencedCount: Int get() = actionBreakdown.silenced

    val silencedPercent: Int
        get() = if (totalNotifications == 0) 0 else silencedCount * 100 / totalNotifications

    val semanticTotal: Int get() = semanticIntents.sumOf(SemanticIntentCount::count)

    fun countFor(intent: SemanticIntent): Int = semanticIntents.firstOrNull { it.intent == intent }?.count ?: 0
}

interface InsightsAnalyticsRepository {
    /** Combines local daily rollups for [range]; no notification content is read. */
    fun observe(range: InsightsDateRange): Flow<InsightsAnalytics>

    /** Oldest and newest locally retained daily rollup, or `null` when no day has completed. */
    fun observeAvailableRange(): Flow<InsightsDateRange?>
}
