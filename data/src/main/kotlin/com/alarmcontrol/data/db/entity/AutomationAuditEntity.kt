package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Content-free record of an automation request outcome; target names and notification data are omitted. */
@Entity(
    tableName = "automation_audit",
    indices = [Index("requested_at_millis")],
)
data class AutomationAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "requested_at_millis") val requestedAtMillis: Long,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "operation") val operation: String,
    @ColumnInfo(name = "target_type") val targetType: String,
    @ColumnInfo(name = "outcome") val outcome: String,
    @ColumnInfo(name = "changed_count") val changedCount: Int,
)
