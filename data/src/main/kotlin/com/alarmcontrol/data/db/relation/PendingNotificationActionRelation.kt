package com.alarmcontrol.data.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.alarmcontrol.data.db.entity.PendingNotificationActionContentEntity
import com.alarmcontrol.data.db.entity.PendingNotificationActionEntity
import com.alarmcontrol.data.db.entity.PendingNotificationActionTraceEntity

/** Complete durable payload that can be promoted without notification framework state. */
data class PendingNotificationActionRelation(
    @Embedded val action: PendingNotificationActionEntity,
    @Relation(
        parentColumn = "token",
        entityColumn = "outbox_token",
    )
    val trace: List<PendingNotificationActionTraceEntity>,
    @Relation(
        parentColumn = "token",
        entityColumn = "outbox_token",
    )
    val contents: List<PendingNotificationActionContentEntity>,
)
