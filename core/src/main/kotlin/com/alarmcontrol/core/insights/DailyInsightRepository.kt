package com.alarmcontrol.core.insights

import kotlinx.coroutines.flow.Flow

/**
 * Persists and reads the per-day insight history (CLAUDE.md §5). Interface in `:core`; the
 * Room-backed implementation lives in `:data`. Everything stays on-device (§1/§3).
 */
interface DailyInsightRepository {
    /**
     * Aggregates the decision log over `[startMillis, endMillis)` into a [DailyInsight] filed under
     * [epochDay] and persists it, replacing any existing rollup for that day (so re-runs are
     * idempotent). [topRules] caps how many of the most-triggered rules to keep. Returns the stored
     * insight.
     */
    suspend fun aggregateAndStore(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
        generatedAtMillis: Long,
        topRules: Int,
    ): DailyInsight

    /** Streams the most recent [limit] daily insights, newest day first, for the UI. */
    fun observeRecent(limit: Int): Flow<List<DailyInsight>>

    /** Existing rollup keys in the inclusive range, used to backfill missed WorkManager runs. */
    suspend fun existingEpochDaysBetween(
        startEpochDay: Long,
        endEpochDay: Long,
    ): Set<Long> = emptySet()

    /**
     * Deletes rollups filed before [epochDay] (retention housekeeping); their breakdown rows cascade.
     * Returns how many days were removed. Keeps the history table bounded like the event log (§6).
     */
    suspend fun purgeOlderThan(epochDay: Long): Int
}
