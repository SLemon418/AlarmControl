package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * How many decisions a given rule produced on a day — a child of [DailyInsightEntity]. Cascade-deleted
 * with its parent day so re-aggregating a day never leaves orphans.
 */
@Entity(
    tableName = "daily_insight_rule_counts",
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
data class DailyInsightRuleCountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "count") val count: Int,
)
