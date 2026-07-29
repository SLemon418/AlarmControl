package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One current, content-free semantic correction per local activity event.
 *
 * This row intentionally has no foreign key: routine raw-history trimming must not erase the
 * user's local learning vote. Explicit activity/feedback clearing removes it through policy code.
 */
@Entity(
    tableName = "local_semantic_feedback",
    indices = [
        Index(value = ["package_name", "corrected_intent"]),
        Index(value = ["recorded_at_millis", "source_event_id"]),
    ],
)
data class LocalSemanticFeedbackEntity(
    @PrimaryKey @ColumnInfo(name = "source_event_id") val sourceEventId: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "corrected_intent") val correctedIntent: String,
    @ColumnInfo(name = "recorded_at_millis") val recordedAtMillis: Long,
)
