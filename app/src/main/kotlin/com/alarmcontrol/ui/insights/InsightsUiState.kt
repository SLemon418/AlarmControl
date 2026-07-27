package com.alarmcontrol.ui.insights

import androidx.compose.ui.graphics.ImageBitmap
import com.alarmcontrol.core.filtering.DecisionTraceLane
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.ui.UiText

/**
 * Immutable UI state for the insights/activity screen (CLAUDE.md §8). [events] is a privacy-safe feed —
 * package/category/timestamp/action/ruleId only, never notification content (§1/§6).
 */
data class InsightsUiState(
    val isLoading: Boolean = true,
    val events: List<EventListItem> = emptyList(),
    val activityTotalCount: Int = 0,
    val activityQuery: String = "",
    val activityFilter: ActivityActionFilter = ActivityActionFilter.ALL,
    val metrics: InsightsMetrics = InsightsMetrics(),
    /** Categories the user can pick when recategorizing; empty hides the affordance (§5). */
    val availableCategories: List<String> = emptyList(),
    /** The latest background insights headline; `null` until the first periodic run completes. */
    val summary: InsightsSummaryUi? = null,
    /** Per-day rollups from the background worker, newest first; empty until the first run. */
    val dailyInsights: List<DailyInsightUi> = emptyList(),
    val suggestions: List<RuleSuggestionUi> = emptyList(),
    val errorMessage: UiText? = null,
    val userMessage: UiText? = null,
    val selectedTab: InsightsTab = InsightsTab.OVERVIEW,
    val analysis: InsightsAnalysisUi = InsightsAnalysisUi(),
    val analysisPreset: AnalysisRangePreset = AnalysisRangePreset.LAST_7_DAYS,
    val customRangeStart: String = "",
    val customRangeEnd: String = "",
    val availableRange: AvailableRangeUi? = null,
    val historyEvents: List<EventListItem> = emptyList(),
    val historyTotalCount: Int = 0,
    val historyActionFilter: HistoryActionFilterUi = HistoryActionFilterUi.ALL,
    val historySources: List<HistorySourceUi> = emptyList(),
    val historyPackageName: String? = null,
    val historyChannelId: String? = null,
    val historyCoverage: NotificationHistoryCoverageUi? = null,
    val selectedEventDetail: NotificationDetailUi? = null,
)

enum class InsightsTab { OVERVIEW, ANALYSIS, RECORDS }

enum class AnalysisRangePreset { LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS, ALL, CUSTOM }

enum class HistoryActionFilterUi { ALL, CANCELLED, SNOOZED, LOGGED, KEPT }

data class AvailableRangeUi(
    val startEpochDay: Long,
    val endEpochDay: Long,
)

data class InsightsAnalysisUi(
    val startEpochDay: Long? = null,
    val endEpochDay: Long? = null,
    val totalNotifications: Int = 0,
    val silencedCount: Int = 0,
    val silencedPercent: Int = 0,
    val actions: ActionBreakdownUi = ActionBreakdownUi(),
    val monitoredActions: ActionBreakdownUi = ActionBreakdownUi(),
    val apps: List<AppAnalysisUi> = emptyList(),
    val rules: List<RuleAnalysisUi> = emptyList(),
    val categories: List<CategoryShareUi> = emptyList(),
    val channels: List<ChannelShareUi> = emptyList(),
    val hours: List<HourAnalysisUi> = emptyList(),
    val semanticIntents: List<SemanticAnalysisUi> = emptyList(),
    val trend: List<TrendPointUi> = emptyList(),
    val bucketLabel: UiText? = null,
    val mlClassifiedCount: Int = 0,
    val categoryCorrectionCount: Int = 0,
    val semanticCorrectionCount: Int = 0,
    val coverageStartEpochDay: Long? = null,
)

data class AppAnalysisUi(
    val packageName: String,
    val appName: String,
    val totalCount: Int,
    val silencedCount: Int,
)

data class RuleAnalysisUi(
    val label: UiText,
    val actualCount: Int,
    val monitoredCount: Int,
)

data class HourAnalysisUi(
    val hour: Int,
    val totalCount: Int,
    val silencedCount: Int,
)

data class SemanticAnalysisUi(
    val label: UiText,
    val count: Int,
)

data class TrendPointUi(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val totalCount: Int,
    val silencedCount: Int,
)

data class HistorySourceUi(
    val packageName: String,
    val appName: String,
    val channelId: String?,
    val channelName: String?,
    val eventCount: Int,
)

data class NotificationHistoryCoverageUi(
    val totalEvents: Int,
    val oldestPostedAtMillis: Long?,
    val newestPostedAtMillis: Long?,
    val eventLimitReached: Boolean,
    val traceCoveragePartial: Boolean,
)

enum class NotificationDetailContentUi { AVAILABLE, NOT_STORED, EXPIRED, UNREADABLE }

data class NotificationDetailUi(
    val eventId: String,
    val appName: String,
    val packageName: String,
    val title: String? = null,
    val text: String? = null,
    val contentState: NotificationDetailContentUi,
)

data class RuleSuggestionUi(
    val key: String,
    val type: RuleSuggestionTypeUi,
    val appName: String,
    val packageName: String,
    val channelId: String? = null,
    val numerator: Int,
    val denominator: Int,
)

enum class RuleSuggestionTypeUi { QUIET_CHANNEL, MARKETING_RULE }

/**
 * Display model for one day's background rollup (CLAUDE.md §5/§8) — a stable UI object mapped from
 * the domain `DailyInsight` so no domain type reaches Compose. Counts are raw; the Composable formats
 * the date and draws the charts.
 */
data class DailyInsightUi(
    val epochDay: Long,
    val totalNotifications: Int,
    val mutedCount: Int,
    val topRules: List<RuleTriggerUi>,
    val topMonitoredRules: List<RuleTriggerUi> = emptyList(),
    val categories: List<CategoryShareUi>,
    val actions: ActionBreakdownUi = ActionBreakdownUi(),
    val monitoredActions: ActionBreakdownUi = ActionBreakdownUi(),
    val channels: List<ChannelShareUi> = emptyList(),
    val apps: List<AppAnalysisUi> = emptyList(),
    val hours: List<HourAnalysisUi> = emptyList(),
    val semanticIntents: List<SemanticAnalysisUi> = emptyList(),
    val mlClassifiedCount: Int = 0,
    val categoryCorrectionCount: Int = 0,
    val semanticCorrectionCount: Int = 0,
    val breakdownComplete: Boolean = false,
    /** Difference in silenced count from the immediately preceding stored day. */
    val mutedDelta: Int? = null,
)

data class ActionBreakdownUi(
    val cancelled: Int = 0,
    val snoozed: Int = 0,
    val loggedOnly: Int = 0,
    val kept: Int = 0,
)

/** A rule and how many decisions it produced that day, pre-labelled for the list. */
data class RuleTriggerUi(
    val label: UiText,
    val count: Int,
)

/** A category and its tally that day, pre-labelled for the bar chart. */
data class CategoryShareUi(
    val label: UiText,
    val count: Int,
)

data class ChannelShareUi(
    val packageName: String,
    val appName: String,
    val channelId: String,
    val count: Int,
    val channelName: String? = null,
)

/**
 * Display model for the periodic insights headline — a stable UI object mapped from the domain
 * `InsightsSummary` so no domain type reaches Compose (CLAUDE.md §8). Raw values; the Composable
 * formats them.
 */
data class InsightsSummaryUi(
    val mostMutedPackage: String?,
    val mostMutedAppName: String? = null,
    val mostMutedCount: Int,
    val anomalyCount: Int,
    val generatedAtMillis: Long,
)

/** One activity-log row. */
data class EventListItem(
    val id: String,
    val packageName: String,
    val appName: String = packageName,
    val appIcon: ImageBitmap? = null,
    /** Raw on-device prediction used when recording an explicit correction. */
    val predictedCategory: String?,
    val category: String?,
    val actionLabel: UiText,
    val action: EventActionUi = EventActionUi.OTHER,
    val recordedAtMillis: Long,
    val postedAtMillis: Long = recordedAtMillis,
    val postedEpochDay: Long? = null,
    val undone: Boolean,
    val canUndo: Boolean,
    /** Latest persisted correction for this exact activity event. */
    val correctedCategory: String? = null,
    val adObservation: AdObservationUi? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val mlConfidencePercent: Int? = null,
    val matchedRuleName: UiText? = null,
    val monitoredActionLabel: UiText? = null,
    val monitoredRuleName: UiText? = null,
    val decisionTrace: List<DecisionTraceUi> = emptyList(),
    val hadEncryptedContent: Boolean = false,
)

data class AdObservationUi(
    val predictedIntent: SemanticIntent,
    val confidencePercent: Int,
    val correctedIntent: SemanticIntent? = null,
) {
    val predictedIsAdvertisement: Boolean get() = predictedIntent.isAdvertisement
    val correctedIsAdvertisement: Boolean? get() = correctedIntent?.isAdvertisement
}

data class DecisionTraceUi(
    val lane: DecisionTraceLane,
    val depth: Int,
    val conditionLabel: UiText,
    val resultLabel: UiText,
)

enum class EventActionUi { CANCELLED, SNOOZED, LOGGED, KEPT, OTHER }

enum class ActivityActionFilter { ALL, CANCELLED, SNOOZED, OTHER }

/** Today's totals — pure Room aggregations, no ML (§5). */
data class InsightsMetrics(
    val cancelled: Int = 0,
    val snoozed: Int = 0,
    val loggedOnly: Int = 0,
    val kept: Int = 0,
) {
    val total: Int get() = cancelled + snoozed
    val totalRecorded: Int get() = total + loggedOnly + kept
}
