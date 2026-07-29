package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records a local day whose analytics source became incomplete. This provenance is independent of
 * a rollup so invalidating a previously complete rollup cannot make partial raw data look complete.
 * The marker carries no notification content and is shared by every analytics view of that day.
 */
@Entity(tableName = "daily_insight_source_gaps")
data class DailyInsightSourceGapEntity(
    @PrimaryKey @ColumnInfo(name = "epoch_day") val epochDay: Long,
)
