package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/** Imported aggregate seven-way semantic votes, never raw notification content. */
@Entity(
    tableName = "semantic_feedback_priors",
    primaryKeys = ["package_name", "intent"],
)
data class SemanticFeedbackPriorEntity(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "intent") val intent: String,
    @ColumnInfo(name = "count") val count: Int,
)
