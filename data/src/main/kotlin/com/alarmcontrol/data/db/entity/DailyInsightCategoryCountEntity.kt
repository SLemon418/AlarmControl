package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * How many notifications carried a given Android category on a day — a child of [DailyInsightEntity].
 * [category] is nullable (a source may set none). Cascade-deleted with its parent day.
 */
@Entity(
    tableName = "daily_insight_category_counts",
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
data class DailyInsightCategoryCountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "category") val category: String?,
    @ColumnInfo(name = "count") val count: Int,
)
