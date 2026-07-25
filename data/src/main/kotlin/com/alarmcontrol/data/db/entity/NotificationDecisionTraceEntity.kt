package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Content-free condition result linked to one local decision event. */
@Entity(
    tableName = "notification_decision_traces",
    foreignKeys = [
        ForeignKey(
            entity = NotificationEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("event_id")],
)
data class NotificationDecisionTraceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "lane") val lane: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "depth") val depth: Int,
    @ColumnInfo(name = "condition_kind") val conditionKind: String,
    @ColumnInfo(name = "result") val result: String,
)
