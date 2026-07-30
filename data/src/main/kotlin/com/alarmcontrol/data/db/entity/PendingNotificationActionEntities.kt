package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.alarmcontrol.data.db.model.StoredRuleAction

/**
 * Privacy-safe event metadata staged before a destructive platform action. An armed row is a
 * durable action-attempt record and must be promoted after process recovery.
 */
@Entity(
    tableName = "pending_notification_actions",
    indices = [
        Index("armed"),
        Index("created_at_millis"),
    ],
    primaryKeys = ["token"],
)
data class PendingNotificationActionEntity(
    val token: String,
    val armed: Boolean = false,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "channel_id") val channelId: String? = null,
    @ColumnInfo(name = "channel_name") val channelName: String? = null,
    @ColumnInfo(name = "ml_category") val mlCategory: String? = null,
    @ColumnInfo(name = "ml_confidence") val mlConfidence: Float? = null,
    val category: String?,
    @ColumnInfo(name = "posted_at_millis") val postedAtMillis: Long,
    @ColumnInfo(name = "posted_epoch_day") val postedEpochDay: Long? = null,
    @ColumnInfo(name = "posted_minute_of_day") val postedMinuteOfDay: Int? = null,
    val importance: String? = null,
    @ColumnInfo(name = "is_conversation") val isConversation: Boolean? = null,
    @ColumnInfo(name = "is_foreground_service") val isForegroundService: Boolean? = null,
    val action: StoredRuleAction,
    @ColumnInfo(name = "matched_rule_id") val matchedRuleId: Long?,
    @ColumnInfo(name = "monitored_rule_id") val monitoredRuleId: Long? = null,
    @ColumnInfo(name = "monitored_action") val monitoredAction: StoredRuleAction? = null,
    @ColumnInfo(name = "recorded_at_millis") val recordedAtMillis: Long,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
)

/** Content-free trace staged with [PendingNotificationActionEntity]. */
@Entity(
    tableName = "pending_notification_action_traces",
    primaryKeys = ["outbox_token", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = PendingNotificationActionEntity::class,
            parentColumns = ["token"],
            childColumns = ["outbox_token"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PendingNotificationActionTraceEntity(
    @ColumnInfo(name = "outbox_token") val outboxToken: String,
    val sequence: Int,
    val lane: String,
    val position: Int,
    val depth: Int,
    @ColumnInfo(name = "condition_kind") val conditionKind: String,
    val result: String,
)

/** Optional already-encrypted notification detail staged with one pending action. */
@Entity(
    tableName = "pending_notification_action_contents",
    primaryKeys = ["outbox_token"],
    foreignKeys = [
        ForeignKey(
            entity = PendingNotificationActionEntity::class,
            parentColumns = ["token"],
            childColumns = ["outbox_token"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PendingNotificationActionContentEntity(
    @ColumnInfo(name = "outbox_token") val outboxToken: String,
    @ColumnInfo(name = "format_version") val formatVersion: Int,
    @ColumnInfo(name = "aad_id") val aadId: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is PendingNotificationActionContentEntity &&
            outboxToken == other.outboxToken &&
            formatVersion == other.formatVersion &&
            aadId == other.aadId &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext) &&
            createdAtMillis == other.createdAtMillis

    override fun hashCode(): Int {
        var result = outboxToken.hashCode()
        result = 31 * result + formatVersion
        result = 31 * result + aadId.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return 31 * result + createdAtMillis.hashCode()
    }
}

/**
 * Durable idempotency receipt for one promoted action. It remains only while the ordinary event
 * record exists and is removed automatically by event retention.
 */
@Entity(
    tableName = "notification_action_promotion_receipts",
    indices = [Index(value = ["event_id"], unique = true)],
    primaryKeys = ["token"],
    foreignKeys = [
        ForeignKey(
            entity = NotificationEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class NotificationActionPromotionReceiptEntity(
    val token: String,
    @ColumnInfo(name = "event_id") val eventId: Long,
)
