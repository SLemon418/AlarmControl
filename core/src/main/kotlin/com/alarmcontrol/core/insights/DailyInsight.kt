package com.alarmcontrol.core.insights

/** Current persisted breakdown shape; older rows cannot prove complete modern analytics. */
const val CURRENT_DAILY_INSIGHT_BREAKDOWN_VERSION = 2

/**
 * A persisted per-day rollup of the local decision log (CLAUDE.md §5) — a pure SQL aggregation, no
 * ML and no network. Stored so the UI and trend views can read history without rescanning the whole
 * event log on every open.
 *
 * Privacy (§3/§6): only metadata counts — package/channel/rule ids and Android categories — never content.
 *
 * @property epochDay day key (days since the Unix epoch) the window is filed under; the dedup key.
 * @property windowStartMillis inclusive start of the aggregated window.
 * @property windowEndMillis exclusive end of the aggregated window.
 * @property totalNotifications non-undone decisions recorded in the window.
 * @property mutedCount decisions whose platform action silenced the notification (cancelled/snoozed).
 * @property topRules the rules that fired most in the window, highest first.
 * @property categoryBreakdown per-Android-category counts in the window, highest first.
 * @property generatedAtMillis when the aggregation ran.
 */
data class DailyInsight(
    val epochDay: Long,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val totalNotifications: Int,
    val mutedCount: Int,
    val topRules: List<RuleTriggerCount>,
    val topMonitoredRules: List<RuleTriggerCount> = emptyList(),
    val categoryBreakdown: List<CategoryCount>,
    val generatedAtMillis: Long,
    val actionBreakdown: ActionBreakdown = ActionBreakdown(),
    val monitoredActionBreakdown: ActionBreakdown = ActionBreakdown(),
    val channelBreakdown: List<ChannelCount> = emptyList(),
    val appBreakdown: List<AppInsightCount> = emptyList(),
    val hourBreakdown: List<HourInsightCount> = emptyList(),
    val semanticBreakdown: List<SemanticIntentCount> = emptyList(),
    val mlClassifiedCount: Int = 0,
    val categoryCorrectionCount: Int = 0,
    val semanticCorrectionCount: Int = 0,
    val breakdownVersion: Int = 0,
    /** Snapshot captured at write time; false when this rollup was built from incomplete raw rows. */
    val sourceComplete: Boolean = true,
    /** False means the corresponding list is a bounded top-N view, not an exhaustive breakdown. */
    val ruleBreakdownComplete: Boolean = false,
    val monitorRuleBreakdownComplete: Boolean = false,
    val appBreakdownComplete: Boolean = false,
    val channelBreakdownComplete: Boolean = false,
)

/** How many times a rule produced a decision within a window. */
data class RuleTriggerCount(
    val ruleId: String,
    val count: Int,
)

/** How many notifications carried a given Android category (`null` = uncategorised) in a window. */
data class CategoryCount(
    val category: String?,
    val count: Int,
)

/** Per-package notification-channel activity count; no notification content is retained. */
data class ChannelCount(
    val packageName: String,
    val channelId: String,
    val count: Int,
    val channelName: String? = null,
)

/** Per-app analyzed and actually silenced counts for a completed local day. */
data class AppInsightCount(
    val packageName: String,
    val totalCount: Int,
    val silencedCount: Int,
)

/** Local clock-hour distribution for one completed day. */
data class HourInsightCount(
    val hour: Int,
    val totalCount: Int,
    val silencedCount: Int,
)

/** Seven-way local semantic-intent distribution for one completed day. */
data class SemanticIntentCount(
    val intent: com.alarmcontrol.core.filtering.SemanticIntent,
    val count: Int,
)
