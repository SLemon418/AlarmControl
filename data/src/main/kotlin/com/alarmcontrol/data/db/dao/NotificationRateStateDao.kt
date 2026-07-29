package com.alarmcontrol.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS
import com.alarmcontrol.data.db.entity.ActiveNotificationRateOccurrenceEntity
import com.alarmcontrol.data.db.entity.NotificationRateOccurrenceHistoryEntity
import com.alarmcontrol.data.db.entity.NotificationRateStateEntity

@Dao
@Suppress("TooManyFunctions") // One cohesive Room aggregate; post/history/marker writes must be atomic.
interface NotificationRateStateDao {
    @Query(
        "SELECT * FROM active_notification_rate_occurrences " +
            "ORDER BY last_posted_at_millis DESC, occurrence_id DESC",
    )
    suspend fun activeOccurrences(): List<ActiveNotificationRateOccurrenceEntity>

    @Query("SELECT COUNT(*) FROM active_notification_rate_occurrences")
    suspend fun activeOccurrenceCount(): Int

    @Query("SELECT MAX(last_posted_at_millis) FROM active_notification_rate_occurrences")
    suspend fun newestActivePostedAt(): Long?

    @Query(
        "SELECT * FROM active_notification_rate_occurrences " +
            "WHERE listener_key_hmac = :listenerKeyHmac LIMIT 1",
    )
    suspend fun activeOccurrence(listenerKeyHmac: String): ActiveNotificationRateOccurrenceEntity?

    @Query(
        "SELECT * FROM active_notification_rate_occurrences " +
            "WHERE occurrence_id = :occurrenceId LIMIT 1",
    )
    suspend fun activeOccurrenceByOccurrenceId(occurrenceId: String): ActiveNotificationRateOccurrenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActiveOccurrence(occurrence: ActiveNotificationRateOccurrenceEntity)

    @Query(
        "DELETE FROM active_notification_rate_occurrences " +
            "WHERE listener_key_hmac = :listenerKeyHmac AND occurrence_id = :occurrenceId " +
            "AND last_posted_at_millis <= :removedPostTimeMillis",
    )
    suspend fun deleteActiveOccurrence(
        listenerKeyHmac: String,
        occurrenceId: String,
        removedPostTimeMillis: Long,
    ): Int

    @Query("DELETE FROM active_notification_rate_occurrences")
    suspend fun deleteAllActiveOccurrences(): Int

    @Query(
        "SELECT MAX(last_posted_at_millis) FROM active_notification_rate_occurrences " +
            "WHERE listener_key_hmac NOT IN (SELECT listener_key_hmac " +
            "FROM active_notification_rate_occurrences " +
            "ORDER BY last_posted_at_millis DESC, occurrence_id DESC LIMIT :limit)",
    )
    suspend fun newestActiveOverflowPostedAt(limit: Int): Long?

    @Query(
        "DELETE FROM active_notification_rate_occurrences " +
            "WHERE listener_key_hmac NOT IN (SELECT listener_key_hmac " +
            "FROM active_notification_rate_occurrences " +
            "ORDER BY last_posted_at_millis DESC, occurrence_id DESC LIMIT :limit)",
    )
    suspend fun deleteActiveOverLimit(limit: Int): Int

    @Query(
        "DELETE FROM active_notification_rate_occurrences " +
            "WHERE last_posted_at_millis < :cutoffMillis",
    )
    suspend fun deleteActiveOlderThan(cutoffMillis: Long): Int

    @Query(
        "SELECT * FROM notification_rate_occurrence_history " +
            "WHERE occurrence_id = :occurrenceId LIMIT 1",
    )
    suspend fun historyOccurrence(occurrenceId: String): NotificationRateOccurrenceHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistoryOccurrence(occurrence: NotificationRateOccurrenceHistoryEntity)

    @Query(
        "SELECT * FROM notification_rate_occurrence_history " +
            "WHERE latest_posted_at_millis >= :sinceMillis " +
            "AND latest_posted_at_millis <= :nowMillis " +
            "ORDER BY latest_posted_at_millis DESC, occurrence_id DESC LIMIT :limit",
    )
    suspend fun historyForSeed(
        sinceMillis: Long,
        nowMillis: Long,
        limit: Int,
    ): List<NotificationRateOccurrenceHistoryEntity>

    @Query(
        "DELETE FROM notification_rate_occurrence_history " +
            "WHERE latest_posted_at_millis < :cutoffMillis",
    )
    suspend fun deleteHistoryOlderThan(cutoffMillis: Long): Int

    @Query("DELETE FROM notification_rate_occurrence_history")
    suspend fun deleteAllHistory(): Int

    @Query("SELECT COUNT(*) FROM notification_rate_occurrence_history")
    suspend fun historyCount(): Int

    @Query("SELECT MAX(latest_posted_at_millis) FROM notification_rate_occurrence_history")
    suspend fun newestHistoryPostedAt(): Long?

    @Query(
        "SELECT MAX(latest_posted_at_millis) FROM notification_rate_occurrence_history " +
            "WHERE occurrence_id NOT IN (SELECT occurrence_id " +
            "FROM notification_rate_occurrence_history " +
            "ORDER BY latest_posted_at_millis DESC, occurrence_id DESC LIMIT :limit)",
    )
    suspend fun newestOverflowPostedAt(limit: Int): Long?

    @Query(
        "DELETE FROM notification_rate_occurrence_history " +
            "WHERE occurrence_id NOT IN (SELECT occurrence_id " +
            "FROM notification_rate_occurrence_history " +
            "ORDER BY latest_posted_at_millis DESC, occurrence_id DESC LIMIT :limit)",
    )
    suspend fun deleteHistoryOverLimit(limit: Int): Int

    @Query(
        "SELECT * FROM notification_rate_state " +
            "WHERE singleton_id = :singletonId",
    )
    suspend fun rateState(singletonId: Int = NotificationRateStateEntity.SINGLETON_ID): NotificationRateStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRateState(state: NotificationRateStateEntity)

    @Query("DELETE FROM notification_rate_state")
    suspend fun deleteRateState(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRateStateIfAbsent(state: NotificationRateStateEntity): Long

    @Query(
        "UPDATE notification_rate_state SET incomplete_until_millis = " +
            "MAX(incomplete_until_millis, :candidateMillis) WHERE singleton_id = :singletonId",
    )
    suspend fun extendIncompleteUntil(
        candidateMillis: Long,
        singletonId: Int = NotificationRateStateEntity.SINGLETON_ID,
    ): Int

    @Transaction
    suspend fun extendIncompleteUntilAndRead(candidateMillis: Long): Long {
        insertRateStateIfAbsent(NotificationRateStateEntity(incompleteUntilMillis = candidateMillis))
        extendIncompleteUntil(candidateMillis)
        return checkNotNull(rateState()).incompleteUntilMillis
    }

    @Transaction
    suspend fun recordPost(
        listenerKeyHmac: String,
        candidateOccurrenceId: String,
        packageName: String,
        channelId: String?,
        postedAtMillis: Long,
    ): RateOccurrencePostTransaction {
        val storedActive = activeOccurrence(listenerKeyHmac)
        val active =
            storedActive?.takeUnless {
                it.lastPostedAtMillis < oldestSupportedPostAt(postedAtMillis)
            }
        if (active == null) {
            check(activeOccurrenceByOccurrenceId(candidateOccurrenceId) == null)
            check(historyOccurrence(candidateOccurrenceId) == null)
        }
        val occurrenceId = active?.occurrenceId ?: candidateOccurrenceId
        val history = historyOccurrence(occurrenceId)
        val authoritative =
            listOfNotNull(
                active?.asHistory(),
                history,
            ).maxByOrNull(NotificationRateOccurrenceHistoryEntity::latestPostedAtMillis)
        if (authoritative != null && postedAtMillis < authoritative.latestPostedAtMillis) {
            val repairedActive = authoritative.toActive(listenerKeyHmac)
            upsertHistoryOccurrence(authoritative)
            upsertActiveOccurrence(repairedActive)
            val incompleteUntilMillis =
                extendIncompleteUntilAndRead(
                    incompleteUntilAfter(authoritative.latestPostedAtMillis),
                )
            return RateOccurrencePostTransaction(
                activeOccurrence = repairedActive,
                accepted = false,
                incompleteUntilMillis = incompleteUntilMillis,
            )
        }

        val updatedHistory =
            NotificationRateOccurrenceHistoryEntity(
                occurrenceId = occurrenceId,
                packageName = packageName,
                channelId = channelId,
                latestPostedAtMillis = postedAtMillis,
            )
        val updatedActive = updatedHistory.toActive(listenerKeyHmac)
        upsertHistoryOccurrence(updatedHistory)
        upsertActiveOccurrence(updatedActive)
        trimHistoryAndMarkIncomplete()
        trimActiveAndMarkIncomplete()
        return RateOccurrencePostTransaction(
            activeOccurrence = updatedActive,
            accepted = true,
            incompleteUntilMillis = rateState()?.incompleteUntilMillis,
        )
    }

    @Transaction
    suspend fun rateSeedSnapshot(
        sinceMillis: Long,
        nowMillis: Long,
        limit: Int,
    ): RateOccurrenceSeedSnapshot {
        val state = rateState()
        val incompleteUntilMillis =
            state
                ?.incompleteUntilMillis
                ?.takeIf { it == Long.MAX_VALUE || it > nowMillis }
        val coverageStartMillis =
            when (incompleteUntilMillis) {
                null -> sinceMillis
                Long.MAX_VALUE -> null
                else ->
                    maxOf(
                        sinceMillis,
                        subtractSaturated(incompleteUntilMillis, MAX_RATE_WINDOW_MILLIS),
                    )
            }
        return RateOccurrenceSeedSnapshot(
            rateState = state,
            coverageStartMillis = coverageStartMillis,
            occurrences =
                if (coverageStartMillis == null) {
                    emptyList()
                } else {
                    historyForSeed(coverageStartMillis, nowMillis, limit)
                },
        )
    }

    @Transaction
    suspend fun clearAllRateData(anchorMillis: Long) {
        deleteAllActiveOccurrences()
        deleteAllHistory()
        deleteRateState()
        upsertRateState(
            NotificationRateStateEntity(
                incompleteUntilMillis = incompleteUntilAfter(anchorMillis),
            ),
        )
    }

    @Transaction
    suspend fun purgeExpiredRateData(
        cutoffMillis: Long,
        rollbackBarrierMillis: Long,
    ): Int {
        val deletedActive = deleteActiveOlderThan(cutoffMillis)
        val deletedHistory = deleteHistoryOlderThan(cutoffMillis)
        if (deletedActive > 0 || deletedHistory > 0) {
            extendIncompleteUntilAndRead(rollbackBarrierMillis)
        }
        return deletedHistory
    }

    @Transaction
    suspend fun prepareForHmacKeyRecovery(anchorMillis: Long): Long {
        val recoveryAnchorMillis =
            maxOf(
                anchorMillis,
                newestActivePostedAt() ?: Long.MIN_VALUE,
                newestHistoryPostedAt() ?: Long.MIN_VALUE,
            )
        deleteAllActiveOccurrences()
        deleteAllHistory()
        return extendIncompleteUntilAndRead(incompleteUntilAfter(recoveryAnchorMillis))
    }

    private suspend fun trimHistoryAndMarkIncomplete() {
        val newestDroppedPostAt = newestOverflowPostedAt(MAX_PERSISTED_RATE_OCCURRENCES) ?: return
        deleteHistoryOverLimit(MAX_PERSISTED_RATE_OCCURRENCES)
        extendIncompleteUntilAndRead(incompleteUntilAfter(newestDroppedPostAt))
    }

    private suspend fun trimActiveAndMarkIncomplete() {
        val newestDroppedPostAt = newestActiveOverflowPostedAt(MAX_PERSISTED_ACTIVE_OCCURRENCES) ?: return
        deleteActiveOverLimit(MAX_PERSISTED_ACTIVE_OCCURRENCES)
        extendIncompleteUntilAndRead(incompleteUntilAfter(newestDroppedPostAt))
    }
}

data class RateOccurrenceSeedSnapshot(
    val rateState: NotificationRateStateEntity?,
    val coverageStartMillis: Long?,
    val occurrences: List<NotificationRateOccurrenceHistoryEntity>,
)

data class RateOccurrencePostTransaction(
    val activeOccurrence: ActiveNotificationRateOccurrenceEntity,
    val accepted: Boolean,
    val incompleteUntilMillis: Long?,
)

private fun ActiveNotificationRateOccurrenceEntity.asHistory(): NotificationRateOccurrenceHistoryEntity =
    NotificationRateOccurrenceHistoryEntity(
        occurrenceId = occurrenceId,
        packageName = packageName,
        channelId = channelId,
        latestPostedAtMillis = lastPostedAtMillis,
    )

private fun NotificationRateOccurrenceHistoryEntity.toActive(
    listenerKeyHmac: String,
): ActiveNotificationRateOccurrenceEntity =
    ActiveNotificationRateOccurrenceEntity(
        listenerKeyHmac = listenerKeyHmac,
        occurrenceId = occurrenceId,
        packageName = packageName,
        channelId = channelId,
        lastPostedAtMillis = latestPostedAtMillis,
    )

private fun oldestSupportedPostAt(postedAtMillis: Long): Long =
    if (postedAtMillis < Long.MIN_VALUE + MAX_RATE_WINDOW_MILLIS) {
        Long.MIN_VALUE
    } else {
        postedAtMillis - MAX_RATE_WINDOW_MILLIS
    }

private fun subtractSaturated(
    value: Long,
    amount: Long,
): Long =
    if (value < Long.MIN_VALUE + amount) {
        Long.MIN_VALUE
    } else {
        value - amount
    }

private fun incompleteUntilAfter(anchorMillis: Long): Long =
    if (anchorMillis > Long.MAX_VALUE - INCOMPLETE_WINDOW_MILLIS) {
        Long.MAX_VALUE
    } else {
        anchorMillis + INCOMPLETE_WINDOW_MILLIS
    }

private const val MAX_PERSISTED_RATE_OCCURRENCES = 10_000
private const val MAX_PERSISTED_ACTIVE_OCCURRENCES = 10_000
private const val INCOMPLETE_WINDOW_MILLIS = MAX_RATE_WINDOW_MILLIS + 1
