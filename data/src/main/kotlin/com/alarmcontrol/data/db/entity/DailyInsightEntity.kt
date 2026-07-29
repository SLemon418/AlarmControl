package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The scalar half of a per-day insight rollup (CLAUDE.md §5). One row per [epochDay]; the top-rule
 * and category breakdowns hang off it in [DailyInsightRuleCountEntity] / [DailyInsightCategoryCountEntity].
 *
 * Privacy (§3/§6): counts and timestamps only — no notification content, ever.
 */
@Entity(tableName = "daily_insights")
data class DailyInsightEntity(
    @PrimaryKey @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "window_start_millis") val windowStartMillis: Long,
    @ColumnInfo(name = "window_end_millis") val windowEndMillis: Long,
    @ColumnInfo(name = "total_notifications") val totalNotifications: Int,
    @ColumnInfo(name = "muted_count") val mutedCount: Int,
    @ColumnInfo(name = "cancelled_count", defaultValue = "0") val cancelledCount: Int = 0,
    @ColumnInfo(name = "snoozed_count", defaultValue = "0") val snoozedCount: Int = 0,
    @ColumnInfo(name = "logged_count", defaultValue = "0") val loggedCount: Int = 0,
    @ColumnInfo(name = "kept_count", defaultValue = "0") val keptCount: Int = 0,
    @ColumnInfo(name = "monitored_cancelled_count", defaultValue = "0")
    val monitoredCancelledCount: Int = 0,
    @ColumnInfo(name = "monitored_snoozed_count", defaultValue = "0")
    val monitoredSnoozedCount: Int = 0,
    @ColumnInfo(name = "monitored_logged_count", defaultValue = "0")
    val monitoredLoggedCount: Int = 0,
    @ColumnInfo(name = "monitored_kept_count", defaultValue = "0")
    val monitoredKeptCount: Int = 0,
    @ColumnInfo(name = "generated_at_millis") val generatedAtMillis: Long,
    @ColumnInfo(name = "ml_classified_count", defaultValue = "0") val mlClassifiedCount: Int = 0,
    @ColumnInfo(name = "category_correction_count", defaultValue = "0")
    val categoryCorrectionCount: Int = 0,
    @ColumnInfo(name = "semantic_correction_count", defaultValue = "0")
    val semanticCorrectionCount: Int = 0,
    @ColumnInfo(name = "breakdown_version", defaultValue = "0") val breakdownVersion: Int = 0,
    /** Room writes this value explicitly; the SQL default is conservative for legacy/raw rows. */
    @ColumnInfo(name = "source_complete", defaultValue = "0") val sourceComplete: Boolean = true,
    @ColumnInfo(name = "rule_breakdown_complete", defaultValue = "0")
    val ruleBreakdownComplete: Boolean = false,
    @ColumnInfo(name = "monitor_rule_breakdown_complete", defaultValue = "0")
    val monitorRuleBreakdownComplete: Boolean = false,
    @ColumnInfo(name = "app_breakdown_complete", defaultValue = "0")
    val appBreakdownComplete: Boolean = false,
    @ColumnInfo(name = "channel_breakdown_complete", defaultValue = "0")
    val channelBreakdownComplete: Boolean = false,
)
