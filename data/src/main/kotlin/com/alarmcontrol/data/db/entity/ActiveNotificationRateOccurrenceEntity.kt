package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.alarmcontrol.core.filtering.RateListenerKeyDigest
import com.alarmcontrol.core.filtering.RateOccurrenceId

/** Content-free mapping for notification occurrences that are currently active. */
@Entity(
    tableName = "active_notification_rate_occurrences",
    indices = [
        Index(value = ["occurrence_id"], unique = true),
        Index(value = ["last_posted_at_millis", "occurrence_id"]),
    ],
)
data class ActiveNotificationRateOccurrenceEntity(
    /** URL-safe HMAC of the transient listener key; the raw key is never persisted. */
    @PrimaryKey
    @ColumnInfo(name = "listener_key_hmac")
    val listenerKeyHmac: String,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "channel_id") val channelId: String? = null,
    @ColumnInfo(name = "last_posted_at_millis") val lastPostedAtMillis: Long,
) {
    init {
        RateListenerKeyDigest(listenerKeyHmac)
        RateOccurrenceId(occurrenceId)
    }
}
