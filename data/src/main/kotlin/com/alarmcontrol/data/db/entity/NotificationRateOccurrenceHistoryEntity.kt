package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.alarmcontrol.core.filtering.RateOccurrenceId

/** Latest durable metadata for one notification occurrence; content is never stored here. */
@Entity(
    tableName = "notification_rate_occurrence_history",
    indices = [Index(value = ["latest_posted_at_millis", "occurrence_id"])],
)
data class NotificationRateOccurrenceHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "occurrence_id")
    val occurrenceId: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "channel_id") val channelId: String? = null,
    @ColumnInfo(name = "latest_posted_at_millis") val latestPostedAtMillis: Long,
) {
    init {
        RateOccurrenceId(occurrenceId)
    }
}
