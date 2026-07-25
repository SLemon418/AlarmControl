package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.alarmcontrol.data.db.model.StoredRuleAction

/**
 * A local record of one engine decision, kept for insights and statistics exclusion (§6).
 *
 * Privacy (HARD RULE §3/§6): this deliberately stores **no notification content** — no title or
 * body. Only the metadata those features actually need (package, Android category,
 * timestamps, the action taken, and which rule produced it) is persisted. Do not add content
 * columns here; user data stays local and minimal, logs included.
 */
@Entity(
    tableName = "notification_events",
    indices = [
        Index("recorded_at_millis"),
        Index("posted_at_millis"),
        Index("package_name"),
        Index("channel_id"),
        Index("matched_rule_id"),
        Index(value = ["recorded_at_millis", "undone"]),
        Index(value = ["posted_epoch_day", "undone"]),
    ],
)
data class NotificationEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "channel_id") val channelId: String? = null,
    @ColumnInfo(name = "channel_name") val channelName: String? = null,
    /** On-device classifier label at decision time; `null` when unavailable/not required. */
    @ColumnInfo(name = "ml_category") val mlCategory: String? = null,
    @ColumnInfo(name = "ml_confidence") val mlConfidence: Float? = null,
    /** Android `Notification.category` (e.g. `"alarm"`); `null` when the source set none. */
    @ColumnInfo(name = "category") val category: String?,
    @ColumnInfo(name = "posted_at_millis") val postedAtMillis: Long,
    @ColumnInfo(name = "posted_epoch_day") val postedEpochDay: Long? = null,
    @ColumnInfo(name = "posted_minute_of_day") val postedMinuteOfDay: Int? = null,
    @ColumnInfo(name = "importance") val importance: String? = null,
    @ColumnInfo(name = "is_conversation") val isConversation: Boolean? = null,
    @ColumnInfo(name = "is_foreground_service") val isForegroundService: Boolean? = null,
    /** What the engine did. [StoredRuleAction.KEEP] means the notification was left untouched. */
    @ColumnInfo(name = "action") val action: StoredRuleAction,
    /** The rule that produced the decision; `null` when no rule matched (default keep). */
    @ColumnInfo(name = "matched_rule_id") val matchedRuleId: Long?,
    @ColumnInfo(name = "monitored_rule_id") val monitoredRuleId: Long? = null,
    @ColumnInfo(name = "monitored_action") val monitoredAction: StoredRuleAction? = null,
    @ColumnInfo(name = "recorded_at_millis") val recordedAtMillis: Long,
    /** Legacy column name; true means the user excluded this row from insight counts. */
    @ColumnInfo(name = "undone") val undone: Boolean = false,
    @ColumnInfo(name = "had_encrypted_content", defaultValue = "0")
    val hadEncryptedContent: Boolean = false,
)
