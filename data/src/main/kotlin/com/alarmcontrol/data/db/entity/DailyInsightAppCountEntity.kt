package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "daily_insight_app_counts",
    primaryKeys = ["epoch_day", "package_name"],
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
data class DailyInsightAppCountEntity(
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "total_count") val totalCount: Int,
    @ColumnInfo(name = "silenced_count") val silencedCount: Int,
)
