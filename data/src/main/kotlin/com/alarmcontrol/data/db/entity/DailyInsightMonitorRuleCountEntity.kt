package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "daily_insight_monitor_rule_counts",
    primaryKeys = ["epoch_day", "rule_id"],
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
data class DailyInsightMonitorRuleCountEntity(
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "count") val count: Int,
)
