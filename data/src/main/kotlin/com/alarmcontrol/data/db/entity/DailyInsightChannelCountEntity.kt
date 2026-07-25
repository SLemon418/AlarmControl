package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Per-day package/channel count; no notification content is retained. */
@Entity(
    tableName = "daily_insight_channel_counts",
    primaryKeys = ["epoch_day", "package_name", "channel_id"],
    foreignKeys = [
        ForeignKey(
            entity = DailyInsightEntity::class,
            parentColumns = ["epoch_day"],
            childColumns = ["epoch_day"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("epoch_day")],
)
data class DailyInsightChannelCountEntity(
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "channel_id") val channelId: String,
    @ColumnInfo(name = "channel_name") val channelName: String? = null,
    @ColumnInfo(name = "count") val count: Int,
)
