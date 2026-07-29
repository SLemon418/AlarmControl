package com.alarmcontrol.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.alarmcontrol.data.db.entity.CategoryFeedbackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryFeedbackDao {
    @Query("SELECT COUNT(*) FROM category_feedback")
    suspend fun countAll(): Int

    @Insert
    suspend fun insert(feedback: CategoryFeedbackEntity): Long

    @Insert
    suspend fun insertAll(feedback: List<CategoryFeedbackEntity>)

    /** Effective local learning votes only; superseded linked corrections are omitted. */
    @Query(
        "SELECT * FROM category_feedback WHERE notification_event_id IS NULL OR id IN (" +
            "SELECT MAX(id) FROM category_feedback WHERE notification_event_id IS NOT NULL " +
            "GROUP BY notification_event_id) ORDER BY id",
    )
    suspend fun getEffectiveFeedback(): List<CategoryFeedbackEntity>

    @Query("DELETE FROM category_feedback WHERE notification_event_id = :eventId")
    suspend fun deleteForEvent(eventId: Long)

    @Query("DELETE FROM category_feedback")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM category_feedback WHERE notification_event_id IS NOT NULL")
    suspend fun deleteLinkedToEvents(): Int

    @Query(
        "DELETE FROM category_feedback WHERE id NOT IN (" +
            "SELECT id FROM category_feedback ORDER BY id DESC LIMIT :max)",
    )
    suspend fun trimToMostRecent(max: Int): Int

    /** Linked event ids whose correction rows would be removed by [trimToMostRecent]. */
    @Query(
        "SELECT DISTINCT notification_event_id FROM category_feedback " +
            "WHERE notification_event_id IS NOT NULL AND id NOT IN (" +
            "SELECT id FROM category_feedback ORDER BY id DESC LIMIT :max)",
    )
    suspend fun getLinkedTrimVictimEventIds(max: Int): List<Long>

    /** Keeps one current correction per linked event; legacy unlinked feedback remains additive. */
    @Transaction
    suspend fun record(feedback: CategoryFeedbackEntity): Long {
        feedback.notificationEventId?.let { deleteForEvent(it) }
        return insert(feedback)
    }

    /**
     * The on-device learning signal: per-label correction counts for one package — a pure SQL
     * aggregation, no ML (CLAUDE.md §5). Observable so consumers see new feedback immediately.
     */
    @Query(
        "SELECT corrected_label, COUNT(*) AS count FROM category_feedback " +
            "WHERE package_name = :packageName AND (notification_event_id IS NULL OR id IN (" +
            "SELECT MAX(id) FROM category_feedback WHERE notification_event_id IS NOT NULL " +
            "GROUP BY notification_event_id)) GROUP BY corrected_label",
    )
    fun observeLabelCounts(packageName: String): Flow<List<LabelCount>>

    /** All package/label counts, used to maintain one hot in-memory inference cache. */
    @Query(
        "SELECT package_name, corrected_label, COUNT(*) AS count FROM category_feedback " +
            "WHERE notification_event_id IS NULL OR id IN (" +
            "SELECT MAX(id) FROM category_feedback WHERE notification_event_id IS NOT NULL " +
            "GROUP BY notification_event_id) " +
            "GROUP BY package_name, corrected_label",
    )
    fun observeAllLabelCounts(): Flow<List<PackageLabelCount>>

    /** Latest correction row for each linked activity event. */
    @Query(
        "SELECT notification_event_id, corrected_label FROM category_feedback " +
            "WHERE notification_event_id IS NOT NULL AND id IN (" +
            "SELECT MAX(id) FROM category_feedback WHERE notification_event_id IS NOT NULL " +
            "GROUP BY notification_event_id)",
    )
    fun observeLatestEventCorrections(): Flow<List<EventCorrection>>

    companion object {
        /** Bounds local learning history and reactive query payloads without storing content. */
        const val MAX_RETAINED_ROWS = 25_000
    }
}

/** Projection for [CategoryFeedbackDao.observeLabelCounts]: one corrected label and its tally. */
data class LabelCount(
    @ColumnInfo(name = "corrected_label") val label: String,
    @ColumnInfo(name = "count") val count: Int,
)

/** Projection for [CategoryFeedbackDao.observeAllLabelCounts]. */
data class PackageLabelCount(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "corrected_label") val label: String,
    @ColumnInfo(name = "count") val count: Int,
)

/** Projection for [CategoryFeedbackDao.observeLatestEventCorrections]. */
data class EventCorrection(
    @ColumnInfo(name = "notification_event_id") val eventId: Long,
    @ColumnInfo(name = "corrected_label") val correctedLabel: String,
)
