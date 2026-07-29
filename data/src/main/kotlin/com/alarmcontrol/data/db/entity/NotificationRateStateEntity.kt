package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Singleton completeness marker for persisted notification-rate history. */
@Entity(tableName = "notification_rate_state")
data class NotificationRateStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = SINGLETON_ID,
    @ColumnInfo(name = "incomplete_until_millis") val incompleteUntilMillis: Long,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
