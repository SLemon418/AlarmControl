package com.alarmcontrol.core.insights

import com.alarmcontrol.core.filtering.SemanticIntent
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Largest day that still renders as the app's fixed `yyyy-MM-dd` date contract. */
val MAX_SUPPORTED_INSIGHT_EPOCH_DAY: Long = LocalDate.of(9_999, 12, 31).toEpochDay()

/** Inclusive local-day range selected by the user. */
data class InsightsDateRange(
    val startEpochDay: Long,
    val endEpochDay: Long,
) {
    init {
        require(startEpochDay <= endEpochDay) { "Insight start day must not follow end day" }
        require(startEpochDay >= 0 && endEpochDay <= MAX_SUPPORTED_INSIGHT_EPOCH_DAY) {
            "Insight date range is outside the supported calendar"
        }
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
        get() =
            if (totalNotifications <= 0) {
                0
            } else {
                ((silencedCount.toLong() * 100) / totalNotifications)
                    .coerceIn(0, 100)
                    .toInt()
            }

    val semanticTotal: Int
        get() =
            semanticIntents
                .fold(0L) { total, count -> total + count.count }
                .coerceIn(0, Int.MAX_VALUE.toLong())
                .toInt()

    fun countFor(intent: SemanticIntent): Int = semanticIntents.firstOrNull { it.intent == intent }?.count ?: 0
}

interface InsightsAnalyticsRepository {
    /** Combines local daily rollups for [range]; no notification content is read. */
    fun observe(range: InsightsDateRange): Flow<InsightsAnalytics>

    /** Oldest and newest locally retained daily rollup, or `null` when no day has completed. */
    fun observeAvailableRange(): Flow<InsightsDateRange?>
}
