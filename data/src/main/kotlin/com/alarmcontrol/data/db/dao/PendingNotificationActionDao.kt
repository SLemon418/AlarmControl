package com.alarmcontrol.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.alarmcontrol.data.db.entity.NotificationActionPromotionReceiptEntity
import com.alarmcontrol.data.db.entity.PendingNotificationActionContentEntity
import com.alarmcontrol.data.db.entity.PendingNotificationActionEntity
import com.alarmcontrol.data.db.entity.PendingNotificationActionTraceEntity
import com.alarmcontrol.data.db.relation.PendingNotificationActionRelation

@Dao
interface PendingNotificationActionDao {
    @Insert
    suspend fun insertAction(action: PendingNotificationActionEntity)

    @Insert
    suspend fun insertTrace(trace: List<PendingNotificationActionTraceEntity>)

    @Insert
    suspend fun insertContent(content: PendingNotificationActionContentEntity)

    @Transaction
    suspend fun insert(
        action: PendingNotificationActionEntity,
        trace: List<PendingNotificationActionTraceEntity>,
        content: PendingNotificationActionContentEntity?,
    ) {
        insertAction(action)
        if (trace.isNotEmpty()) insertTrace(trace)
        content?.let { insertContent(it) }
    }

    @Query(
        "UPDATE pending_notification_actions SET armed = 1 " +
            "WHERE token = :token AND armed = 0",
    )
    fun arm(token: String): Int

    @Transaction
    @Query("SELECT * FROM pending_notification_actions WHERE token = :token AND armed = 1")
    suspend fun getArmed(token: String): PendingNotificationActionRelation?

    @Query("SELECT event_id FROM notification_action_promotion_receipts WHERE token = :token")
    suspend fun getPromotedEventId(token: String): Long?

    @Insert
    suspend fun insertPromotionReceipt(receipt: NotificationActionPromotionReceiptEntity)

    @Query(
        "SELECT token FROM pending_notification_actions WHERE armed = 1 " +
            "ORDER BY created_at_millis, token",
    )
    suspend fun getArmedTokens(): List<String>

    @Query(
        "SELECT posted_at_millis, posted_epoch_day FROM pending_notification_actions " +
            "WHERE armed = 1",
    )
    suspend fun getArmedSourceGapCandidates(): List<PendingActionSourceGapRow>

    @Query("DELETE FROM pending_notification_actions WHERE token = :token")
    suspend fun delete(token: String): Int

    @Query("DELETE FROM pending_notification_actions WHERE armed = 0")
    suspend fun deleteUnarmed(): Int

    @Query(
        "DELETE FROM pending_notification_actions WHERE armed = 0 AND token NOT IN " +
            "(SELECT token FROM pending_notification_actions WHERE armed = 0 " +
            "ORDER BY created_at_millis DESC, token DESC LIMIT :max)",
    )
    suspend fun trimUnarmedToMostRecent(max: Int): Int

    @Query("DELETE FROM pending_notification_actions")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM pending_notification_action_contents")
    suspend fun countContents(): Int

    @Query("DELETE FROM pending_notification_action_contents")
    suspend fun deleteAllContents(): Int

    @Query(
        "DELETE FROM pending_notification_action_contents WHERE " +
            "created_at_millis < :cutoffMillis OR created_at_millis > :nowMillis",
    )
    suspend fun deleteContentsOutsideRetention(
        cutoffMillis: Long,
        nowMillis: Long,
    ): Int

    @Query(
        "DELETE FROM pending_notification_action_contents WHERE outbox_token IN " +
            "(SELECT token FROM pending_notification_actions WHERE package_name = :packageName)",
    )
    suspend fun deleteContentsForPackage(packageName: String): Int
}

/** Content-free post date needed when an explicit clear removes an armed, not-yet-promoted row. */
data class PendingActionSourceGapRow(
    @ColumnInfo(name = "posted_at_millis") val postedAtMillis: Long,
    @ColumnInfo(name = "posted_epoch_day") val postedEpochDay: Long?,
)
