package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Content-free output of one optional local LLM analysis, linked to its activity record. */
@Entity(
    tableName = "llm_observations",
    foreignKeys = [
        ForeignKey(
            entity = NotificationEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["notification_event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["notification_event_id"], unique = true),
        Index("package_name"),
    ],
)
data class LlmObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "notification_event_id") val notificationEventId: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "predicted_is_ad") val predictedIsAdvertisement: Boolean,
    @ColumnInfo(name = "predicted_intent", defaultValue = "'AMBIGUOUS'")
    val predictedIntent: String = "AMBIGUOUS",
    @ColumnInfo(name = "confidence_score") val confidenceScore: Float,
    @ColumnInfo(name = "corrected_is_ad") val correctedIsAdvertisement: Boolean? = null,
    @ColumnInfo(name = "corrected_intent") val correctedIntent: String? = null,
    @ColumnInfo(name = "analyzed_at_millis") val analyzedAtMillis: Long,
)
