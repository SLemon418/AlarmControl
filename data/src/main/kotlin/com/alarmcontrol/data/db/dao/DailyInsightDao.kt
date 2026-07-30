package com.alarmcontrol.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.alarmcontrol.data.db.entity.DailyInsightAppCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightCategoryCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightChannelCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightEntity
import com.alarmcontrol.data.db.entity.DailyInsightHourCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightMonitorRuleCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightRuleCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightSemanticCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightSourceGapEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.db.relation.DailyInsightWithBreakdown
import kotlinx.coroutines.flow.Flow

private const val EVENT_DAY_FILTER =
    "((posted_epoch_day = :epochDay) OR " +
        "(posted_epoch_day IS NULL AND posted_at_millis >= :startMillis AND posted_at_millis < :endMillis))"
private const val ALIASED_EVENT_DAY_FILTER =
    "((event.posted_epoch_day = :epochDay) OR " +
        "(event.posted_epoch_day IS NULL AND event.posted_at_millis >= :startMillis " +
        "AND event.posted_at_millis < :endMillis))"
private const val LATEST_EVENT_DAY_FILTER =
    "((latest.posted_epoch_day = :epochDay) OR " +
        "(latest.posted_epoch_day IS NULL AND latest.posted_at_millis >= :startMillis " +
        "AND latest.posted_at_millis < :endMillis))"

/**
 * Aggregates the decision log into per-day rollups and persists them (CLAUDE.md §5). The summarising
 * runs as pure SQL `GROUP BY` over `notification_events` — never loading rows into memory — which is
 * what keeps the periodic worker cheap on battery even with a large log.
 */
@Dao
@Suppress("TooManyFunctions") // One cohesive Room aggregate contract; splitting would break transactions.
interface DailyInsightDao {
    @Query("SELECT COUNT(*) FROM daily_insights")
    suspend fun countAll(): Int

    // ---- aggregation over the decision log (read side) ----

    /** Decisions posted on [epochDay], with the millisecond window retained for legacy rows. */
    @Query(
        "SELECT COUNT(*) FROM notification_events " +
            "WHERE " + EVENT_DAY_FILTER + " AND undone = 0",
    )
    suspend fun countBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int

    /** Non-undone decisions actually silenced by cancel or snooze. */
    @Query(
        "SELECT COUNT(*) FROM notification_events " +
            "WHERE " + EVENT_DAY_FILTER + " AND undone = 0 " +
            "AND action IN (:cancelAction, :snoozeAction)",
    )
    suspend fun countMutedBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
        cancelAction: StoredRuleAction,
        snoozeAction: StoredRuleAction,
    ): Int

    @Query(
        "SELECT action, COUNT(*) AS count FROM notification_events " +
            "WHERE " + EVENT_DAY_FILTER + " AND undone = 0 GROUP BY action",
    )
    suspend fun actionCountsBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): List<ActionCountRow>

    @Query(
        "SELECT monitored_action AS `action`, COUNT(*) AS count FROM notification_events " +
            "WHERE " + EVENT_DAY_FILTER + " AND undone = 0 " +
            "AND monitored_action IS NOT NULL GROUP BY monitored_action",
    )
    suspend fun monitoredActionCountsBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): List<ActionCountRow>

    /** The most-triggered rules in the window, highest first (ties by id), capped at [limit]. */
    @Query(
        "SELECT matched_rule_id AS rule_id, COUNT(*) AS count FROM notification_events " +
            "WHERE " + EVENT_DAY_FILTER + " AND undone = 0 AND matched_rule_id IS NOT NULL " +
            "GROUP BY matched_rule_id ORDER BY count DESC, matched_rule_id ASC LIMIT :limit",
    )
    suspend fun topRulesBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
        limit: Int,
    ): List<RuleCountRow>

    @Query(
        "SELECT COUNT(DISTINCT matched_rule_id) FROM notification_events " +
            "WHERE " + EVENT_DAY_FILTER + " AND undone = 0 AND matched_rule_id IS NOT NULL",
    )
    suspend fun countMatchedRulesBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int

    @Query(
        "SELECT monitored_rule_id AS rule_id, COUNT(*) AS count FROM notification_events " +
            "WHERE " + EVENT_DAY_FILTER + " AND undone = 0 AND monitored_rule_id IS NOT NULL " +
            "GROUP BY monitored_rule_id ORDER BY count DESC, monitored_rule_id ASC LIMIT :limit",
    )
    suspend fun topMonitoredRulesBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
        limit: Int,
    ): List<RuleCountRow>

    @Query(
        "SELECT COUNT(DISTINCT monitored_rule_id) FROM notification_events " +
            "WHERE " + EVENT_DAY_FILTER + " AND undone = 0 AND monitored_rule_id IS NOT NULL",
    )
    suspend fun countMonitoredRulesBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int

    /** Per-Android-category counts in the window, highest first (ties by category). */
    @Query(
        "SELECT COALESCE((SELECT corrected_label FROM category_feedback " +
            "WHERE notification_event_id = notification_events.id ORDER BY id DESC LIMIT 1), " +
            "ml_category, category) AS category, COUNT(*) AS count FROM notification_events " +
            "WHERE " + EVENT_DAY_FILTER + " AND undone = 0 " +
            "GROUP BY COALESCE((SELECT corrected_label FROM category_feedback " +
            "WHERE notification_event_id = notification_events.id ORDER BY id DESC LIMIT 1), ml_category, category) " +
            "ORDER BY count DESC, category ASC",
    )
    suspend fun categoryBreakdownBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): List<CategoryCountRow>

    @Query(
        "SELECT event.package_name, event.channel_id, " +
            "(SELECT latest.channel_name FROM notification_events AS latest " +
            "WHERE latest.package_name = event.package_name AND latest.channel_id = event.channel_id " +
            "AND " + LATEST_EVENT_DAY_FILTER + " AND latest.undone = 0 " +
            "ORDER BY latest.posted_at_millis DESC, latest.id DESC LIMIT 1) AS channel_name, " +
            "COUNT(*) AS count FROM notification_events AS event " +
            "WHERE " + ALIASED_EVENT_DAY_FILTER + " AND event.undone = 0 AND event.channel_id IS NOT NULL " +
            "GROUP BY event.package_name, event.channel_id " +
            "ORDER BY count DESC, event.package_name ASC, event.channel_id ASC LIMIT :limit",
    )
    suspend fun channelBreakdownBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
        limit: Int,
    ): List<ChannelCountRow>

    @Query(
        "SELECT COUNT(*) FROM (SELECT 1 FROM notification_events " +
            "WHERE " + EVENT_DAY_FILTER + " AND undone = 0 " +
            "AND channel_id IS NOT NULL GROUP BY package_name, channel_id)",
    )
    suspend fun countChannelsBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int

    @Query(
        "SELECT package_name, COUNT(*) AS total_count, " +
            "SUM(CASE WHEN action IN (:cancelAction, :snoozeAction) THEN 1 ELSE 0 END) AS silenced_count " +
            "FROM notification_events WHERE " + EVENT_DAY_FILTER + " " +
            "AND undone = 0 GROUP BY package_name " +
            "ORDER BY total_count DESC, package_name ASC LIMIT :limit",
    )
    suspend fun appBreakdownBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
        cancelAction: StoredRuleAction,
        snoozeAction: StoredRuleAction,
        limit: Int,
    ): List<AppCountRow>

    @Query(
        "SELECT COUNT(DISTINCT package_name) FROM notification_events " +
            "WHERE " + EVENT_DAY_FILTER + " AND undone = 0",
    )
    suspend fun countAppsBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int

    @Query(
        "SELECT CAST(posted_minute_of_day / 60 AS INTEGER) AS hour, COUNT(*) AS total_count, " +
            "SUM(CASE WHEN action IN (:cancelAction, :snoozeAction) THEN 1 ELSE 0 END) AS silenced_count " +
            "FROM notification_events WHERE " + EVENT_DAY_FILTER + " " +
            "AND undone = 0 AND posted_minute_of_day IS NOT NULL " +
            "GROUP BY hour ORDER BY hour ASC",
    )
    suspend fun hourBreakdownBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
        cancelAction: StoredRuleAction,
        snoozeAction: StoredRuleAction,
    ): List<HourCountRow>

    /**
     * Legacy events migrated before the posted-local-time fields existed cannot contribute to a
     * trustworthy local-hour breakdown. Their presence must mark the rollup source incomplete.
     */
    @Query(
        "SELECT COUNT(*) FROM notification_events WHERE " + EVENT_DAY_FILTER + " " +
            "AND undone = 0 AND posted_minute_of_day IS NULL",
    )
    suspend fun countMissingPostedMinuteBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int

    @Query(
        "SELECT COALESCE(llm.corrected_intent, llm.predicted_intent) AS intent, COUNT(*) AS count " +
            "FROM llm_observations AS llm INNER JOIN notification_events AS event " +
            "ON event.id = llm.notification_event_id WHERE " + ALIASED_EVENT_DAY_FILTER + " " +
            "AND event.undone = 0 " +
            "GROUP BY intent ORDER BY count DESC, intent ASC",
    )
    suspend fun semanticBreakdownBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): List<SemanticCountRow>

    @Query(
        "SELECT COUNT(*) FROM notification_events WHERE " + EVENT_DAY_FILTER + " " +
            "AND undone = 0 AND ml_category IS NOT NULL",
    )
    suspend fun countMlClassifiedBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int

    @Query(
        "SELECT COUNT(DISTINCT feedback.notification_event_id) FROM category_feedback AS feedback " +
            "INNER JOIN notification_events AS event ON event.id = feedback.notification_event_id " +
            "WHERE " + ALIASED_EVENT_DAY_FILTER + " AND event.undone = 0",
    )
    suspend fun countCategoryCorrectionsBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM llm_observations AS llm INNER JOIN notification_events AS event " +
            "ON event.id = llm.notification_event_id WHERE " + ALIASED_EVENT_DAY_FILTER + " " +
            "AND event.undone = 0 " +
            "AND llm.corrected_intent IS NOT NULL",
    )
    suspend fun countSemanticCorrectionsBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int

    // ---- persistence of the rollup (write side) ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInsight(insight: DailyInsightEntity)

    @Insert
    suspend fun insertRuleCounts(rows: List<DailyInsightRuleCountEntity>)

    @Insert
    suspend fun insertMonitorRuleCounts(rows: List<DailyInsightMonitorRuleCountEntity>)

    @Insert
    suspend fun insertCategoryCounts(rows: List<DailyInsightCategoryCountEntity>)

    @Insert
    suspend fun insertChannelCounts(rows: List<DailyInsightChannelCountEntity>)

    @Insert
    suspend fun insertAppCounts(rows: List<DailyInsightAppCountEntity>)

    @Insert
    suspend fun insertHourCounts(rows: List<DailyInsightHourCountEntity>)

    @Insert
    suspend fun insertSemanticCounts(rows: List<DailyInsightSemanticCountEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceGaps(rows: List<DailyInsightSourceGapEntity>)

    @Query("DELETE FROM daily_insight_rule_counts WHERE epoch_day = :epochDay")
    suspend fun deleteRuleCounts(epochDay: Long)

    @Query("DELETE FROM daily_insight_monitor_rule_counts WHERE epoch_day = :epochDay")
    suspend fun deleteMonitorRuleCounts(epochDay: Long)

    @Query("DELETE FROM daily_insight_category_counts WHERE epoch_day = :epochDay")
    suspend fun deleteCategoryCounts(epochDay: Long)

    @Query("DELETE FROM daily_insight_channel_counts WHERE epoch_day = :epochDay")
    suspend fun deleteChannelCounts(epochDay: Long)

    @Query("DELETE FROM daily_insight_app_counts WHERE epoch_day = :epochDay")
    suspend fun deleteAppCounts(epochDay: Long)

    @Query("DELETE FROM daily_insight_hour_counts WHERE epoch_day = :epochDay")
    suspend fun deleteHourCounts(epochDay: Long)

    @Query("DELETE FROM daily_insight_semantic_counts WHERE epoch_day = :epochDay")
    suspend fun deleteSemanticCounts(epochDay: Long)

    /** Atomically replaces a day's rollup: clears its old breakdown rows, then writes the new ones. */
    @Transaction
    suspend fun store(write: DailyInsightWrite) {
        val insight = write.insight
        deleteRuleCounts(insight.epochDay)
        deleteMonitorRuleCounts(insight.epochDay)
        deleteCategoryCounts(insight.epochDay)
        deleteChannelCounts(insight.epochDay)
        deleteAppCounts(insight.epochDay)
        deleteHourCounts(insight.epochDay)
        deleteSemanticCounts(insight.epochDay)
        upsertInsight(insight)
        insertRuleCounts(write.ruleCounts)
        insertMonitorRuleCounts(write.monitorRuleCounts)
        insertCategoryCounts(write.categoryCounts)
        insertChannelCounts(write.channelCounts)
        insertAppCounts(write.appCounts)
        insertHourCounts(write.hourCounts)
        insertSemanticCounts(write.semanticCounts)
        if (!write.sourceComplete) {
            insertSourceGaps(listOf(DailyInsightSourceGapEntity(insight.epochDay)))
        }
    }

    /** Retention housekeeping: deletes rollups before [epochDay]; breakdown rows cascade. */
    @Query("DELETE FROM daily_insights WHERE epoch_day < :epochDay")
    suspend fun deleteOlderThan(epochDay: Long): Int

    @Query("DELETE FROM daily_insights")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM daily_insight_source_gaps")
    suspend fun deleteAllSourceGaps(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM daily_insight_source_gaps WHERE epoch_day = :epochDay)")
    suspend fun hasSourceGap(epochDay: Long): Boolean

    @Query(
        "SELECT epoch_day FROM daily_insight_source_gaps " +
            "WHERE epoch_day BETWEEN :startEpochDay AND :endEpochDay ORDER BY epoch_day ASC",
    )
    fun observeSourceGapDaysBetween(
        startEpochDay: Long,
        endEpochDay: Long,
    ): Flow<List<Long>>

    /**
     * Removes a completed-day rollup whose category/semantic correction just changed. The next
     * bounded housekeeping backfill rebuilds it from raw local events instead of showing stale
     * historical analytics.
     */
    @Query(
        "DELETE FROM daily_insights WHERE EXISTS (" +
            "SELECT 1 FROM notification_events AS event WHERE event.id = :eventId AND (" +
            "(event.posted_epoch_day IS NOT NULL AND event.posted_epoch_day = daily_insights.epoch_day) OR " +
            "(event.posted_epoch_day IS NULL AND event.posted_at_millis >= daily_insights.window_start_millis " +
            "AND event.posted_at_millis < daily_insights.window_end_millis)))",
    )
    suspend fun deleteContainingEvent(eventId: Long): Int

    /**
     * Invalidates only rollups whose raw events currently carry linked category or semantic
     * corrections. Package priors and detached learning votes never enter a historical rollup.
     */
    @Query(
        "DELETE FROM daily_insights WHERE EXISTS (" +
            "SELECT 1 FROM notification_events AS event WHERE (" +
            "EXISTS (SELECT 1 FROM category_feedback AS feedback " +
            "WHERE feedback.notification_event_id = event.id) OR " +
            "EXISTS (SELECT 1 FROM llm_observations AS llm " +
            "WHERE llm.notification_event_id = event.id AND " +
            "(llm.corrected_intent IS NOT NULL OR llm.corrected_is_ad IS NOT NULL))) AND (" +
            "(event.posted_epoch_day IS NOT NULL AND event.posted_epoch_day = daily_insights.epoch_day) OR " +
            "(event.posted_epoch_day IS NULL AND event.posted_at_millis >= daily_insights.window_start_millis " +
            "AND event.posted_at_millis < daily_insights.window_end_millis)))",
    )
    suspend fun deleteRollupsAffectedByLinkedFeedback(): Int

    /** Most recent days first, with breakdowns attached, for the history UI. */
    @Transaction
    @Query("SELECT * FROM daily_insights ORDER BY epoch_day DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DailyInsightWithBreakdown>>

    @Transaction
    @Query(
        "SELECT * FROM daily_insights WHERE epoch_day BETWEEN :startEpochDay AND :endEpochDay " +
            "ORDER BY epoch_day ASC",
    )
    fun observeBetween(
        startEpochDay: Long,
        endEpochDay: Long,
    ): Flow<List<DailyInsightWithBreakdown>>

    @Query("SELECT MIN(epoch_day) AS oldest, MAX(epoch_day) AS newest FROM daily_insights")
    fun observeBounds(): Flow<InsightBoundsRow>

    @Query(
        "SELECT epoch_day FROM daily_insights " +
            "WHERE epoch_day BETWEEN :startEpochDay AND :endEpochDay ORDER BY epoch_day ASC",
    )
    suspend fun getEpochDaysBetween(
        startEpochDay: Long,
        endEpochDay: Long,
    ): List<Long>

    /** One coherent snapshot for local backup export. */
    @Transaction
    @Query("SELECT * FROM daily_insights ORDER BY epoch_day DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<DailyInsightWithBreakdown>
}

data class DailyInsightWrite(
    val insight: DailyInsightEntity,
    val ruleCounts: List<DailyInsightRuleCountEntity>,
    val monitorRuleCounts: List<DailyInsightMonitorRuleCountEntity>,
    val categoryCounts: List<DailyInsightCategoryCountEntity>,
    val channelCounts: List<DailyInsightChannelCountEntity>,
    val appCounts: List<DailyInsightAppCountEntity>,
    val hourCounts: List<DailyInsightHourCountEntity>,
    val semanticCounts: List<DailyInsightSemanticCountEntity>,
    val sourceComplete: Boolean,
)

/** Projection for [DailyInsightDao.topRulesBetween]: a rule's stored id and its tally. */
data class RuleCountRow(
    @ColumnInfo(name = "rule_id") val ruleId: Long,
    @ColumnInfo(name = "count") val count: Int,
)

/** Projection for [DailyInsightDao.categoryBreakdownBetween]: an Android category and its tally. */
data class CategoryCountRow(
    @ColumnInfo(name = "category") val category: String?,
    @ColumnInfo(name = "count") val count: Int,
)

data class ChannelCountRow(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "channel_id") val channelId: String,
    @ColumnInfo(name = "channel_name") val channelName: String?,
    @ColumnInfo(name = "count") val count: Int,
)

data class AppCountRow(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "total_count") val totalCount: Int,
    @ColumnInfo(name = "silenced_count") val silencedCount: Int,
)

data class HourCountRow(
    @ColumnInfo(name = "hour") val hour: Int,
    @ColumnInfo(name = "total_count") val totalCount: Int,
    @ColumnInfo(name = "silenced_count") val silencedCount: Int,
)

data class SemanticCountRow(
    @ColumnInfo(name = "intent") val intent: String,
    @ColumnInfo(name = "count") val count: Int,
)

data class InsightBoundsRow(
    @ColumnInfo(name = "oldest") val oldest: Long?,
    @ColumnInfo(name = "newest") val newest: Long?,
)
