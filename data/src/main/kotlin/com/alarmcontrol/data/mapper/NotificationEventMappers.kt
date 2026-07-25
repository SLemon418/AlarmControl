package com.alarmcontrol.data.mapper

import com.alarmcontrol.core.filtering.ConditionResult
import com.alarmcontrol.core.filtering.DecisionConditionKind
import com.alarmcontrol.core.filtering.DecisionTraceLane
import com.alarmcontrol.core.filtering.DecisionTraceNode
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.NotificationImportance
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.data.db.entity.EncryptedNotificationContentEntity
import com.alarmcontrol.data.db.entity.NotificationDecisionTraceEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.db.relation.NotificationEventDetailRelation
import com.alarmcontrol.data.db.relation.NotificationEventWithTrace
import com.alarmcontrol.data.security.EncryptedContent

/** Domain [NotificationEvent] -> Room entity (write path; the engine records decisions). */
fun NotificationEvent.toEntity(): NotificationEventEntity =
    NotificationEventEntity(
        id = id.toLongOrNull() ?: 0L,
        packageName = packageName,
        channelId = channelId,
        channelName = channelName,
        mlCategory = mlCategory,
        mlConfidence = mlConfidence,
        category = category,
        postedAtMillis = postedAtMillis,
        postedEpochDay = postedEpochDay,
        postedMinuteOfDay = postedMinuteOfDay,
        importance = importance?.name,
        isConversation = isConversation,
        isForegroundService = isForegroundService,
        action = action.toStoredType(),
        matchedRuleId = matchedRuleId?.toLongOrNull(),
        monitoredRuleId = monitoredRuleId?.toLongOrNull(),
        monitoredAction = monitoredAction?.toStoredType(),
        recordedAtMillis = recordedAtMillis,
        undone = undone,
        hadEncryptedContent = hadEncryptedContent,
    )

/** Room entity -> domain [NotificationEvent] (read path; the activity feed). */
fun NotificationEventWithTrace.toDomain(): NotificationEvent = event.toDomain(trace)

fun NotificationEventDetailRelation.toDomain(): NotificationEvent = event.toDomain(trace)

private fun NotificationEventEntity.toDomain(trace: List<NotificationDecisionTraceEntity>): NotificationEvent =
    NotificationEvent(
        id = id.toString(),
        packageName = packageName,
        channelId = channelId,
        channelName = channelName,
        mlCategory = mlCategory,
        mlConfidence = mlConfidence,
        category = category,
        postedAtMillis = postedAtMillis,
        postedEpochDay = postedEpochDay,
        postedMinuteOfDay = postedMinuteOfDay,
        importance = importance?.let(NotificationImportance::valueOf),
        isConversation = isConversation,
        isForegroundService = isForegroundService,
        action = action.toRuleAction(),
        matchedRuleId = matchedRuleId?.toString(),
        recordedAtMillis = recordedAtMillis,
        undone = undone,
        monitoredRuleId = monitoredRuleId?.toString(),
        monitoredAction = monitoredAction?.toRuleAction(),
        decisionTrace =
            trace
                .sortedWith(compareBy<NotificationDecisionTraceEntity> { it.lane }.thenBy { it.position })
                .map(NotificationDecisionTraceEntity::toDomain),
        hadEncryptedContent = hadEncryptedContent,
    )

internal fun EncryptedContent.toEntity(createdAtMillis: Long): EncryptedNotificationContentEntity =
    EncryptedNotificationContentEntity(
        formatVersion = formatVersion,
        aadId = aadId,
        nonce = nonce,
        ciphertext = ciphertext,
        createdAtMillis = createdAtMillis,
    )

internal fun EncryptedNotificationContentEntity.toEncryptedContent(): EncryptedContent =
    EncryptedContent(
        formatVersion = formatVersion,
        aadId = aadId,
        nonce = nonce,
        ciphertext = ciphertext,
    )

fun DecisionTraceNode.toEntity(eventId: Long = 0): NotificationDecisionTraceEntity =
    NotificationDecisionTraceEntity(
        eventId = eventId,
        lane = lane.name,
        position = position,
        depth = depth,
        conditionKind = kind.name,
        result = result.name,
    )

private fun NotificationDecisionTraceEntity.toDomain(): DecisionTraceNode =
    DecisionTraceNode(
        lane = DecisionTraceLane.valueOf(lane),
        position = position,
        depth = depth,
        kind = DecisionConditionKind.valueOf(conditionKind),
        result = ConditionResult.valueOf(result),
    )

internal fun StoredRuleAction.toRuleAction(): RuleAction =
    when (this) {
        StoredRuleAction.CANCEL -> RuleAction.Cancel
        StoredRuleAction.MARK_READ -> RuleAction.MarkRead
        StoredRuleAction.KEEP -> RuleAction.Keep
        // The event log doesn't persist the snooze duration; the activity feed shows only "snoozed".
        StoredRuleAction.SNOOZE -> RuleAction.Snooze(0L)
    }
