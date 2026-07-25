package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/** Imported aggregate learning votes, detached from activity rows that intentionally are not backed up. */
@Entity(
    tableName = "ad_feedback_priors",
    primaryKeys = ["package_name", "is_ad"],
)
data class AdFeedbackPriorEntity(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "is_ad") val isAdvertisement: Boolean,
    @ColumnInfo(name = "count") val count: Int,
)
