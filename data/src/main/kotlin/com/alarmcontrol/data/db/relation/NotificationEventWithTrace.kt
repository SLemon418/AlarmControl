package com.alarmcontrol.data.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.alarmcontrol.data.db.entity.NotificationDecisionTraceEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity

data class NotificationEventWithTrace(
    @Embedded val event: NotificationEventEntity,
    @Relation(parentColumn = "id", entityColumn = "event_id")
    val trace: List<NotificationDecisionTraceEntity>,
)
