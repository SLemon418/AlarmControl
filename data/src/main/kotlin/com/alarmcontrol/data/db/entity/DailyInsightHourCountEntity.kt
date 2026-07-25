package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "daily_insight_hour_counts",
    primaryKeys = ["epoch_day", "hour"],
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
data class DailyInsightHourCountEntity(
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "hour") val hour: Int,
    @ColumnInfo(name = "total_count") val totalCount: Int,
    @ColumnInfo(name = "silenced_count") val silencedCount: Int,
)
