package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single user correction of an ML categorization, kept locally to bias future predictions (§5).
 *
 * Privacy (HARD RULE §3): stores **no notification content** — only the package, the labels involved,
 * and when it happened. Indexed by package because that is how the learning signal is aggregated.
 */
@Entity(
    tableName = "category_feedback",
    indices = [Index("package_name"), Index("notification_event_id")],
)
data class CategoryFeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "notification_event_id") val notificationEventId: Long? = null,
    @ColumnInfo(name = "predicted_label") val predictedLabel: String?,
    @ColumnInfo(name = "corrected_label") val correctedLabel: String,
    @ColumnInfo(name = "recorded_at_millis") val recordedAtMillis: Long,
)
