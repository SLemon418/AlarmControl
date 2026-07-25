package com.alarmcontrol.data.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.alarmcontrol.data.db.entity.EncryptedNotificationContentEntity
import com.alarmcontrol.data.db.entity.NotificationDecisionTraceEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity

/** One event detail relation; encrypted content remains opaque until the repository decrypts it. */
data class NotificationEventDetailRelation(
    @Embedded val event: NotificationEventEntity,
    @Relation(parentColumn = "id", entityColumn = "event_id")
    val trace: List<NotificationDecisionTraceEntity>,
    @Relation(parentColumn = "id", entityColumn = "event_id")
    val encryptedContent: EncryptedNotificationContentEntity?,
)
