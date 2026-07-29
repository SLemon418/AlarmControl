package com.alarmcontrol.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.alarmcontrol.data.db.entity.DailyInsightSourceGapEntity
import com.alarmcontrol.data.db.entity.EncryptedNotificationContentEntity
import com.alarmcontrol.data.db.entity.NotificationDecisionTraceEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.db.relation.NotificationEventDetailRelation
import com.alarmcontrol.data.db.relation.NotificationEventWithTrace
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId

private const val EFFECTIVE_CATEGORY =
    "COALESCE((SELECT corrected_label FROM category_feedback " +
        "WHERE notification_event_id = notification_events.id ORDER BY id DESC LIMIT 1), " +
        "ml_category, category)"

@Dao
@Suppress(
    "LongParameterList",
    "TooManyFunctions",
) // Room binds history filters as SQL parameters; all methods share one event-table boundary.
interface NotificationEventDao {
    @Query("SELECT COUNT(*) FROM notification_events")
    suspend fun countAll(): Int

    @Insert
    suspend fun insert(event: NotificationEventEntity): Long

    @Insert
    suspend fun insertTrace(rows: List<NotificationDecisionTraceEntity>)

    @Insert
    suspend fun insertEncryptedContent(content: EncryptedNotificationContentEntity)

    @Query(
        "DELETE FROM daily_insights WHERE " +
            "(:postedEpochDay IS NOT NULL AND epoch_day = :postedEpochDay) OR " +
            "(:postedEpochDay IS NULL AND :postedAtMillis >= window_start_millis " +
            "AND :postedAtMillis < window_end_millis)",
    )
    suspend fun deleteRollupContainingPost(
        postedEpochDay: Long?,
        postedAtMillis: Long,
    ): Int

    @Transaction
    suspend fun insertWithTrace(
        event: NotificationEventEntity,
        trace: List<NotificationDecisionTraceEntity>,
        encryptedContent: EncryptedNotificationContentEntity? = null,
    ): Long {
        val eventId = insert(event)
        if (trace.isNotEmpty()) insertTrace(trace.map { it.copy(eventId = eventId) })
        encryptedContent?.let { insertEncryptedContent(it.copy(eventId = eventId)) }
        return eventId
    }

    /**
     * Persists one complete decision, invalidates any retained rollup for its posted day, and
     * enforces [max] plus [maxTraceEvents] before commit.
     *
     * The generated id is returned even when an out-of-order, older event is the row removed by the
     * cap. Callers may still safely attempt post-commit enrichment because a missing row is a no-op.
     */
    @Transaction
    suspend fun insertWithTraceAndTrim(
        event: NotificationEventEntity,
        trace: List<NotificationDecisionTraceEntity>,
        encryptedContent: EncryptedNotificationContentEntity?,
        max: Int,
        maxTraceEvents: Int,
        legacyZoneId: ZoneId,
    ): Long {
        require(max >= 0) { "Recent event maximum must not be negative" }
        require(maxTraceEvents >= 0) { "Trace event maximum must not be negative" }
        val eventId = insert(event)
        deleteRollupContainingPost(event.postedEpochDay, event.postedAtMillis)
        if (trace.isNotEmpty()) insertTrace(trace.map { it.copy(eventId = eventId) })
        encryptedContent?.let { insertEncryptedContent(it.copy(eventId = eventId)) }
        val eventCount = countAll()
        if (eventCount > max) deleteOverLimitWithSourceGaps(max, legacyZoneId)
        if (eventCount > maxTraceEvents) deleteTracesOutsideMostRecent(maxTraceEvents)
        return eventId
    }

    @Query(
        "UPDATE notification_events SET " +
            "ml_category = COALESCE(:mlCategory, ml_category), " +
            "ml_confidence = COALESCE(:mlConfidence, ml_confidence), " +
            "monitored_rule_id = :monitoredRuleId, " +
            "monitored_action = :monitoredAction " +
            "WHERE id = :eventId",
    )
    suspend fun updatePostCommitEnrichment(
        eventId: Long,
        mlCategory: String?,
        mlConfidence: Float?,
        monitoredRuleId: Long?,
        monitoredAction: StoredRuleAction?,
    ): Int

    @Query(
        "DELETE FROM daily_insights WHERE EXISTS (" +
            "SELECT 1 FROM notification_events AS event WHERE event.id = :eventId AND (" +
            "(event.posted_epoch_day IS NOT NULL AND event.posted_epoch_day = daily_insights.epoch_day) OR " +
            "(event.posted_epoch_day IS NULL AND event.posted_at_millis >= daily_insights.window_start_millis " +
            "AND event.posted_at_millis < daily_insights.window_end_millis)))",
    )
    suspend fun deleteRollupContainingEvent(eventId: Long): Int

    @Query("DELETE FROM notification_decision_traces WHERE event_id = :eventId")
    suspend fun deleteTraceForEvent(eventId: Long)

    /**
     * Applies delayed ML/monitor metadata and trace atomically. A successful metadata write also
     * invalidates a rollup rebuilt after the initial insert; a missing trimmed event is a no-op.
     */
    @Transaction
    suspend fun updatePostCommitEnrichmentWithTrace(
        eventId: Long,
        mlCategory: String?,
        mlConfidence: Float?,
        monitoredRuleId: Long?,
        monitoredAction: StoredRuleAction?,
        trace: List<NotificationDecisionTraceEntity>,
        maxTraceEvents: Int,
    ) {
        require(maxTraceEvents >= 0) { "Trace event maximum must not be negative" }
        if (
            updatePostCommitEnrichment(
                eventId = eventId,
                mlCategory = mlCategory,
                mlConfidence = mlConfidence,
                monitoredRuleId = monitoredRuleId,
                monitoredAction = monitoredAction,
            ) == 0
        ) {
            return
        }
        deleteRollupContainingEvent(eventId)
        deleteTraceForEvent(eventId)
        if (trace.isNotEmpty()) insertTrace(trace.map { it.copy(eventId = eventId) })
        if (countAll() > maxTraceEvents) deleteTracesOutsideMostRecent(maxTraceEvents)
    }

    /** Most recent decisions first, for the activity and statistics-exclusion feed. */
    @Transaction
    @Query("SELECT * FROM notification_events ORDER BY posted_at_millis DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NotificationEventWithTrace>>

    @Transaction
    @Query(
        "SELECT * FROM notification_events WHERE posted_at_millis >= :startMillis " +
            "AND posted_at_millis < :endMillis " +
            "AND (:includeExcluded = 1 OR undone = 0) " +
            "AND (:packageName IS NULL OR package_name = :packageName) " +
            "AND (:channelId IS NULL OR channel_id = :channelId) " +
            "AND (:category IS NULL OR " + EFFECTIVE_CATEGORY + " = :category) " +
            "AND (:ruleId IS NULL OR matched_rule_id = :ruleId) " +
            "AND (:action IS NULL OR action = :action) " +
            "AND (:search = '' OR package_name LIKE '%' || :search || '%' ESCAPE '\\' " +
            "OR COALESCE(channel_name, '') LIKE '%' || :search || '%' ESCAPE '\\' " +
            "OR COALESCE(channel_id, '') LIKE '%' || :search || '%' ESCAPE '\\' " +
            "OR COALESCE(" + EFFECTIVE_CATEGORY + ", '') LIKE '%' || :search || '%' ESCAPE '\\') " +
            "ORDER BY posted_at_millis DESC, id DESC LIMIT :limit",
    )
    fun observeHistory(
        startMillis: Long,
        endMillis: Long,
        search: String,
        packageName: String?,
        channelId: String?,
        category: String?,
        ruleId: Long?,
        action: StoredRuleAction?,
        includeExcluded: Boolean,
        limit: Int,
    ): Flow<List<NotificationEventWithTrace>>

    @Query(
        "SELECT COUNT(*) FROM notification_events WHERE posted_at_millis >= :startMillis " +
            "AND posted_at_millis < :endMillis " +
            "AND (:includeExcluded = 1 OR undone = 0) " +
            "AND (:packageName IS NULL OR package_name = :packageName) " +
            "AND (:channelId IS NULL OR channel_id = :channelId) " +
            "AND (:category IS NULL OR " + EFFECTIVE_CATEGORY + " = :category) " +
            "AND (:ruleId IS NULL OR matched_rule_id = :ruleId) " +
            "AND (:action IS NULL OR action = :action) " +
            "AND (:search = '' OR package_name LIKE '%' || :search || '%' ESCAPE '\\' " +
            "OR COALESCE(channel_name, '') LIKE '%' || :search || '%' ESCAPE '\\' " +
            "OR COALESCE(channel_id, '') LIKE '%' || :search || '%' ESCAPE '\\' " +
            "OR COALESCE(" + EFFECTIVE_CATEGORY + ", '') LIKE '%' || :search || '%' ESCAPE '\\')",
    )
    fun observeHistoryCount(
        startMillis: Long,
        endMillis: Long,
        search: String,
        packageName: String?,
        channelId: String?,
        category: String?,
        ruleId: Long?,
        action: StoredRuleAction?,
        includeExcluded: Boolean,
    ): Flow<Int>

    @Transaction
    @Query("SELECT * FROM notification_events WHERE id = :id LIMIT 1")
    suspend fun getDetail(id: Long): NotificationEventDetailRelation?

    @Transaction
    @Query(
        "SELECT * FROM notification_events WHERE (:packageName IS NULL OR package_name = :packageName) " +
            "ORDER BY posted_at_millis DESC, id DESC LIMIT :limit",
    )
    suspend fun getSimulationSamples(
        packageName: String?,
        limit: Int,
    ): List<NotificationEventDetailRelation>

    @Query(
        "SELECT package_name, channel_id, MAX(channel_name) AS channel_name, COUNT(*) AS event_count, " +
            "MAX(posted_at_millis) AS last_seen_millis FROM notification_events " +
            "GROUP BY package_name, channel_id ORDER BY last_seen_millis DESC LIMIT :limit",
    )
    fun observeSources(limit: Int): Flow<List<NotificationSourceRow>>

    @Query(
        "SELECT COUNT(*) AS total_event_count, MIN(posted_at_millis) AS oldest_posted_at_millis, " +
            "MAX(posted_at_millis) AS newest_posted_at_millis, " +
            "COALESCE(SUM(CASE WHEN matched_rule_id IS NOT NULL OR monitored_rule_id IS NOT NULL " +
            "THEN 1 ELSE 0 END), 0) AS trace_eligible_event_count, " +
            "(SELECT COUNT(DISTINCT event_id) FROM notification_decision_traces) AS trace_event_count " +
            "FROM notification_events",
    )
    fun observeCoverage(): Flow<HistoryCoverageRow>

    /**
     * Insight aggregation — pure SQL, no ML (CLAUDE.md §5): how many non-undone notifications got
     * [action] since [sinceMillis]. Observable so the insight cards stay live.
     */
    @Query(
        "SELECT COUNT(*) FROM notification_events " +
            "WHERE action = :action AND posted_at_millis >= :sinceMillis AND undone = 0",
    )
    fun countByActionSince(
        action: StoredRuleAction,
        sinceMillis: Long,
    ): Flow<Int>

    @Query(
        "SELECT action, COUNT(*) AS count FROM notification_events " +
            "WHERE posted_at_millis >= :sinceMillis AND undone = 0 GROUP BY action",
    )
    fun observeActionCountsSince(sinceMillis: Long): Flow<List<ActionCountRow>>

    @Query(
        "SELECT action, COUNT(*) AS count FROM notification_events " +
            "WHERE ((posted_epoch_day = :epochDay) OR " +
            "(posted_epoch_day IS NULL AND posted_at_millis >= :legacyStartMillis " +
            "AND posted_at_millis < :legacyEndMillis)) " +
            "AND undone = 0 GROUP BY action",
    )
    fun observeActionCountsForDay(
        epochDay: Long,
        legacyStartMillis: Long,
        legacyEndMillis: Long,
    ): Flow<List<ActionCountRow>>

    /** Excludes a logged event from insight counts; it cannot restore a dismissed notification. */
    @Query("UPDATE notification_events SET undone = 1 WHERE id = :id")
    suspend fun markUndone(id: Long)

    @Query(
        "SELECT id, posted_at_millis, posted_epoch_day, undone FROM notification_events " +
            "WHERE posted_at_millis < :cutoffMillis " +
            "ORDER BY posted_at_millis ASC, id ASC LIMIT :limit",
    )
    suspend fun getRetentionDeletionCandidates(
        cutoffMillis: Long,
        limit: Int,
    ): List<EventDeletionCandidateRow>

    @Query(
        "SELECT id, posted_at_millis, posted_epoch_day, undone FROM notification_events " +
            "WHERE id NOT IN (SELECT id FROM notification_events " +
            "ORDER BY posted_at_millis DESC, id DESC LIMIT :max) " +
            "ORDER BY posted_at_millis ASC, id ASC LIMIT :limit",
    )
    suspend fun getOverflowDeletionCandidates(
        max: Int,
        limit: Int,
    ): List<EventDeletionCandidateRow>

    @Query(
        "SELECT id, posted_at_millis, posted_epoch_day, undone FROM notification_events " +
            "ORDER BY posted_at_millis ASC, id ASC LIMIT :limit",
    )
    suspend fun getAllDeletionCandidates(limit: Int): List<EventDeletionCandidateRow>

    @Query("DELETE FROM notification_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInsightSourceGaps(rows: List<DailyInsightSourceGapEntity>)

    /**
     * Marks every analytics-relevant local day represented by the exact retention candidates before
     * deleting those same ids.
     */
    @Transaction
    suspend fun deleteOlderThanWithSourceGaps(
        cutoffMillis: Long,
        legacyZoneId: ZoneId,
    ): Int =
        deleteInBatches(legacyZoneId) {
            getRetentionDeletionCandidates(cutoffMillis, DELETION_BATCH_SIZE)
        }

    /**
     * Applies the deterministic newest-row cap while preserving an honest completeness marker for
     * every day represented by the exact overflow candidate set.
     */
    @Transaction
    suspend fun deleteOverLimitWithSourceGaps(
        max: Int,
        legacyZoneId: ZoneId,
    ): Int {
        require(max >= 0) { "Recent event maximum must not be negative" }
        return deleteInBatches(legacyZoneId) {
            getOverflowDeletionCandidates(max, DELETION_BATCH_SIZE)
        }
    }

    /** Explicit activity clearing keeps completed rollups and marks every affected source day. */
    @Transaction
    suspend fun deleteAllWithSourceGaps(legacyZoneId: ZoneId): Int =
        deleteInBatches(legacyZoneId) {
            getAllDeletionCandidates(DELETION_BATCH_SIZE)
        }

    @Query("DELETE FROM notification_events")
    suspend fun deleteAll(): Int

    /**
     * Removes content-free history for an exact package/channel set. Used by debug device
     * validation to clean only its private probe channels instead of wiping real user history.
     */
    @Query(
        "DELETE FROM notification_events WHERE package_name = :packageName " +
            "AND channel_id IN (:channelIds)",
    )
    suspend fun deleteForPackageChannels(
        packageName: String,
        channelIds: List<String>,
    ): Int

    @Query("SELECT COUNT(*) FROM encrypted_notification_contents")
    suspend fun countEncryptedContents(): Int

    @Query(
        "DELETE FROM encrypted_notification_contents WHERE event_id IN " +
            "(SELECT id FROM notification_events WHERE recorded_at_millis < :cutoffMillis)",
    )
    suspend fun deleteEncryptedContentsOlderThan(cutoffMillis: Long): Int

    @Query("DELETE FROM encrypted_notification_contents")
    suspend fun deleteAllEncryptedContents(): Int

    @Query(
        "DELETE FROM encrypted_notification_contents WHERE event_id IN " +
            "(SELECT id FROM notification_events WHERE package_name = :packageName)",
    )
    suspend fun deleteEncryptedContentsForPackage(packageName: String): Int

    @Query(
        "DELETE FROM notification_decision_traces WHERE event_id NOT IN " +
            "(SELECT id FROM notification_events ORDER BY posted_at_millis DESC, id DESC LIMIT :max)",
    )
    suspend fun deleteTracesOutsideMostRecent(max: Int): Int

    @Query(
        "SELECT MIN(posted_at_millis) AS oldest_posted_at_millis, " +
            "MAX(posted_at_millis) AS newest_posted_at_millis, " +
            "MIN(posted_epoch_day) AS oldest_posted_epoch_day, " +
            "MAX(posted_epoch_day) AS newest_posted_epoch_day FROM notification_events",
    )
    suspend fun getPostedAtBounds(): EventTimeBoundsRow

    /**
     * Per-package counts of non-undone events in `[startMillis, endMillis)` that were actually
     * silenced by cancel or snooze — pure SQL aggregation for periodic insights (§5).
     */
    @Query(
        "SELECT package_name, COUNT(*) AS count FROM notification_events " +
            "WHERE posted_at_millis >= :startMillis AND posted_at_millis < :endMillis " +
            "AND undone = 0 AND action IN (:cancelAction, :snoozeAction) GROUP BY package_name",
    )
    suspend fun countByPackageBetween(
        startMillis: Long,
        endMillis: Long,
        cancelAction: StoredRuleAction,
        snoozeAction: StoredRuleAction,
    ): List<PackageCount>

    private suspend fun deleteInBatches(
        legacyZoneId: ZoneId,
        select: suspend () -> List<EventDeletionCandidateRow>,
    ): Int {
        var total = 0
        while (true) {
            val candidates = select()
            if (candidates.isEmpty()) return total
            val analyticsDays =
                candidates
                    .asSequence()
                    .filterNot(EventDeletionCandidateRow::undone)
                    .map { candidate ->
                        candidate.postedEpochDay
                            ?: Instant
                                .ofEpochMilli(candidate.postedAtMillis)
                                .atZone(legacyZoneId)
                                .toLocalDate()
                                .toEpochDay()
                    }.distinct()
                    .toList()
            if (analyticsDays.isNotEmpty()) {
                insertInsightSourceGaps(analyticsDays.map(::DailyInsightSourceGapEntity))
            }
            val deleted = deleteByIds(candidates.map(EventDeletionCandidateRow::id))
            check(deleted == candidates.size) { "Event deletion candidate set changed inside transaction" }
            total += deleted
        }
    }

    companion object {
        private const val DELETION_BATCH_SIZE = 500
    }
}

/** Projection for [NotificationEventDao.countByPackageBetween]: a package and its event tally. */
data class PackageCount(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "count") val count: Int,
)

data class ActionCountRow(
    @ColumnInfo(name = "action") val action: StoredRuleAction,
    @ColumnInfo(name = "count") val count: Int,
)

data class NotificationSourceRow(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "channel_id") val channelId: String?,
    @ColumnInfo(name = "channel_name") val channelName: String?,
    @ColumnInfo(name = "event_count") val eventCount: Int,
    @ColumnInfo(name = "last_seen_millis") val lastSeenMillis: Long,
)

data class EventTimeBoundsRow(
    @ColumnInfo(name = "oldest_posted_at_millis") val oldestPostedAtMillis: Long?,
    @ColumnInfo(name = "newest_posted_at_millis") val newestPostedAtMillis: Long?,
    @ColumnInfo(name = "oldest_posted_epoch_day") val oldestPostedEpochDay: Long?,
    @ColumnInfo(name = "newest_posted_epoch_day") val newestPostedEpochDay: Long?,
)

data class EventDeletionCandidateRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "posted_at_millis") val postedAtMillis: Long,
    @ColumnInfo(name = "posted_epoch_day") val postedEpochDay: Long?,
    @ColumnInfo(name = "undone") val undone: Boolean,
)

data class HistoryCoverageRow(
    @ColumnInfo(name = "total_event_count") val totalEventCount: Int,
    @ColumnInfo(name = "oldest_posted_at_millis") val oldestPostedAtMillis: Long?,
    @ColumnInfo(name = "newest_posted_at_millis") val newestPostedAtMillis: Long?,
    @ColumnInfo(name = "trace_event_count") val traceEventCount: Int,
    @ColumnInfo(name = "trace_eligible_event_count") val traceEligibleEventCount: Int,
)
