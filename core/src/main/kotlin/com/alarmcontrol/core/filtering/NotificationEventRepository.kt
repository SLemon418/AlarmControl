package com.alarmcontrol.core.filtering

import com.alarmcontrol.core.insights.ActionBreakdown
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to the local decision log used for insights and statistics exclusion
 * (CLAUDE.md §5/§6). Pure domain: the interface lives in `:core`, the Room-backed implementation in
 * `:data`. Insight totals are SQL aggregations, never ML (§5).
 */
interface NotificationEventRepository {
    /** Persists one decision record and returns its local row id. */
    suspend fun record(
        event: NotificationEvent,
        content: NotificationContent? = null,
    ): String

    /**
     * Adds best-effort monitor/classifier metadata to an already persisted decision. This contract
     * deliberately cannot change the committed active action or matched rule.
     */
    suspend fun enrichRecordedDecision(
        eventId: String,
        enrichment: NotificationDecisionEnrichment,
    ) = Unit

    /** Streams the most recent decisions (newest first), capped at [limit], for the activity feed. */
    fun observeRecent(limit: Int): Flow<List<NotificationEvent>>

    /**
     * Streams how many notifications got the [kind] action since [sinceMillis], excluding entries
     * the user removed from statistics. Reactive so insight cards update immediately.
     */
    fun countByActionSince(
        kind: ActionKind,
        sinceMillis: Long,
    ): Flow<Int>

    /** One reactive SQL aggregation for all current action counters. */
    fun observeActionBreakdownSince(sinceMillis: Long): Flow<ActionBreakdown>

    /**
     * Observes actions posted on [epochDay]. Implementations should use
     * `[legacyStartMillis, legacyEndMillis)` only for rows written before a posted-day key was
     * available.
     */
    fun observeActionBreakdownForDay(
        epochDay: Long,
        legacyStartMillis: Long,
        legacyEndMillis: Long,
    ): Flow<ActionBreakdown>

    /** Excludes [eventId] from local statistics; it cannot restore a dismissed notification. */
    suspend fun undo(eventId: String)

    /** Deletes events recorded before [cutoffMillis] (retention housekeeping); returns rows removed. */
    suspend fun purgeEventsOlderThan(cutoffMillis: Long): Int

    /**
     * Caps the log at the [max] most recent events, deleting older overflow — a size guard so a
     * high-volume device can't grow the table without bound within the retention window. Returns rows
     * removed.
     */
    suspend fun trimToMostRecent(max: Int): Int

    /** Removes diagnostic child rows outside the newest [max] events while retaining event metadata. */
    suspend fun trimDecisionTracesToMostRecent(max: Int): Int = 0

    /**
     * Oldest/newest retained post boundaries, or `null` when the event log is empty. Stored epoch
     * days preserve the local date at post time across later device time-zone changes.
     */
    suspend fun postedAtBounds(): NotificationEventTimeBounds? = null

    /**
     * Per-package counts of events actually silenced by [RuleAction.Cancel] or [RuleAction.Snooze],
     * excluding entries removed from statistics in `[startMillis, endMillis)`. Pure SQL (§5).
     */
    suspend fun mutedCountsByPackageBetween(
        startMillis: Long,
        endMillis: Long,
    ): Map<String, Int>

    /** Loads a bounded, content-free history once to warm stateful frequency conditions. */
    suspend fun rateHistorySince(sinceMillis: Long): List<NotificationRateEvent>

    /** Deletes encrypted title/body payloads older than [cutoffMillis], retaining event metadata. */
    suspend fun purgeEncryptedContentOlderThan(cutoffMillis: Long): Int
}

/** Content-free metadata that may finish after the active notification action has committed. */
data class NotificationDecisionEnrichment(
    val mlCategory: String?,
    val mlConfidence: Float?,
    val monitoredRuleId: String?,
    val monitoredAction: RuleAction?,
    val decisionTrace: List<DecisionTraceNode>,
)

data class NotificationEventTimeBounds(
    val oldestPostedAtMillis: Long,
    val newestPostedAtMillis: Long,
    val oldestPostedEpochDay: Long? = null,
    val newestPostedEpochDay: Long? = null,
)
