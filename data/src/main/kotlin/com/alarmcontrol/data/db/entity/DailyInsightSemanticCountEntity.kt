package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "daily_insight_semantic_counts",
    primaryKeys = ["epoch_day", "intent"],
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
data class DailyInsightSemanticCountEntity(
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "intent") val intent: String,
    @ColumnInfo(name = "count") val count: Int,
)
