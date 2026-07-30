package com.alarmcontrol.data.repository

import com.alarmcontrol.core.coroutines.AppDispatcher
import com.alarmcontrol.core.coroutines.Dispatcher
import com.alarmcontrol.core.filtering.MAX_RETAINED_NOTIFICATION_EVENTS
import com.alarmcontrol.core.filtering.MAX_RETAINED_NOTIFICATION_TRACE_EVENTS
import com.alarmcontrol.core.filtering.NotificationActionOutbox
import com.alarmcontrol.core.filtering.NotificationContent
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.StagedNotificationAction
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.data.db.TransactionRunner
import com.alarmcontrol.data.db.dao.NotificationEventDao
import com.alarmcontrol.data.db.dao.PendingNotificationActionDao
import com.alarmcontrol.data.db.entity.EncryptedNotificationContentEntity
import com.alarmcontrol.data.db.entity.NotificationActionPromotionReceiptEntity
import com.alarmcontrol.data.db.entity.NotificationDecisionTraceEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity
import com.alarmcontrol.data.db.entity.PendingNotificationActionContentEntity
import com.alarmcontrol.data.db.entity.PendingNotificationActionEntity
import com.alarmcontrol.data.db.entity.PendingNotificationActionTraceEntity
import com.alarmcontrol.data.db.relation.PendingNotificationActionRelation
import com.alarmcontrol.data.mapper.toEncryptedContent
import com.alarmcontrol.data.mapper.toEntity
import com.alarmcontrol.data.security.NotificationContentAccessGuard
import com.alarmcontrol.data.security.NotificationContentCipher
import com.alarmcontrol.data.security.NotificationContentCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

/**
 * Room-backed two-phase action outbox. Only encrypted optional content enters the pending tables;
 * unarmed rows are not evidence of an action and are discarded during startup recovery.
 */
class NotificationActionOutboxImpl
    @Inject
    internal constructor(
        private val pendingDao: PendingNotificationActionDao,
        private val eventDao: NotificationEventDao,
        private val contentCipher: NotificationContentCipher,
        private val settingsRepository: SettingsRepository,
        private val contentAccessGuard: NotificationContentAccessGuard,
        private val transactionRunner: TransactionRunner,
        private val clock: Clock,
        @Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
    ) : NotificationActionOutbox {
        override suspend fun stage(
            event: NotificationEvent,
            content: NotificationContent?,
        ): StagedNotificationAction =
            withContext(NonCancellable + ioDispatcher) {
                contentAccessGuard.withLock {
                    val token = UUID.randomUUID().toString()
                    val permittedContent =
                        content?.takeIf {
                            settingsRepository.notificationContentStorageEnabled.first() &&
                                event.packageName !in settingsRepository.contentExcludedPackages.first()
                        }
                    val encrypted =
                        permittedContent
                            ?.takeUnless { it.title.isNullOrBlank() && it.text.isNullOrBlank() }
                            ?.let { value ->
                                runCatchingPreservingCancellation {
                                    val plaintext = NotificationContentCodec.encode(value)
                                    try {
                                        contentCipher.encrypt(plaintext).toEntity(event.recordedAtMillis)
                                    } finally {
                                        plaintext.fill(0)
                                    }
                                }.getOrNull()
                            }
                    val eventEntity = event.copy(hadEncryptedContent = encrypted != null).toEntity()
                    transactionRunner.run {
                        pendingDao.insert(
                            action = eventEntity.toPending(token, clock.millis()),
                            trace =
                                event.decisionTrace.mapIndexed { index, node ->
                                    node.toEntity().toPending(token, index)
                                },
                            content = encrypted?.toPending(token),
                        )
                        // Current listener work is capped at 64. Trimming only older unarmed rows
                        // bounds crash/cancellation residue; an evicted live stage fails open at arm.
                        pendingDao.trimUnarmedToMostRecent(MAX_UNARMED_ACTIONS)
                    }
                    StagedNotificationAction(token)
                }
            }

        override fun arm(staged: StagedNotificationAction): Boolean = pendingDao.arm(staged.value) == 1

        override suspend fun promote(staged: StagedNotificationAction): String? =
            withContext(ioDispatcher) {
                contentAccessGuard.withLock {
                    val retentionMillis =
                        settingsRepository.notificationContentRetentionDays
                            .first()
                            .toContentRetentionMillis()
                    transactionRunner.run {
                        pendingDao.getPromotedEventId(staged.value)?.let { return@run it.toString() }
                        val pending = pendingDao.getArmed(staged.value) ?: return@run null
                        val nowMillis = clock.millis()
                        val content =
                            pending.contents
                                .singleOrNull()
                                ?.takeIf { it.isInsideRetention(nowMillis, retentionMillis) }
                        val eventId =
                            eventDao.insertWithTraceAndTrim(
                                // Promotion is the durable record commit. Normalizing this timestamp
                                // keeps a delayed/crash-recovered action from being selected as the
                                // oldest row and trimmed before its idempotency receipt can be stored.
                                event = pending.toEvent(content != null, nowMillis),
                                trace = pending.toTrace(),
                                encryptedContent = content?.toEncryptedContent(),
                                max = MAX_RETAINED_NOTIFICATION_EVENTS,
                                maxTraceEvents = MAX_RETAINED_NOTIFICATION_TRACE_EVENTS,
                                legacyZoneId = clock.zone,
                                nowMillis = nowMillis,
                            )
                        pendingDao.insertPromotionReceipt(
                            NotificationActionPromotionReceiptEntity(staged.value, eventId),
                        )
                        check(pendingDao.delete(staged.value) == 1) {
                            "Armed notification action disappeared during promotion"
                        }
                        eventId.toString()
                    }
                }
            }

        override suspend fun discard(staged: StagedNotificationAction) {
            withContext(ioDispatcher) {
                pendingDao.delete(staged.value)
            }
        }

        override suspend fun recover(): Int =
            withContext(ioDispatcher) {
                pendingDao.deleteUnarmed()
                recoverArmed()
            }

        override suspend fun recoverArmed(): Int =
            withContext(ioDispatcher) {
                pendingDao
                    .getArmedTokens()
                    .count { token -> promote(StagedNotificationAction(token)) != null }
            }

        private companion object {
            const val MAX_UNARMED_ACTIONS = 64
        }
    }

private fun NotificationEventEntity.toPending(
    token: String,
    createdAtMillis: Long,
): PendingNotificationActionEntity =
    PendingNotificationActionEntity(
        token = token,
        packageName = packageName,
        channelId = channelId,
        channelName = channelName,
        mlCategory = mlCategory,
        mlConfidence = mlConfidence,
        category = category,
        postedAtMillis = postedAtMillis,
        postedEpochDay = postedEpochDay,
        postedMinuteOfDay = postedMinuteOfDay,
        importance = importance,
        isConversation = isConversation,
        isForegroundService = isForegroundService,
        action = action,
        matchedRuleId = matchedRuleId,
        monitoredRuleId = monitoredRuleId,
        monitoredAction = monitoredAction,
        recordedAtMillis = recordedAtMillis,
        createdAtMillis = createdAtMillis,
    )

private fun NotificationDecisionTraceEntity.toPending(
    token: String,
    sequence: Int,
): PendingNotificationActionTraceEntity =
    PendingNotificationActionTraceEntity(
        outboxToken = token,
        sequence = sequence,
        lane = lane,
        position = position,
        depth = depth,
        conditionKind = conditionKind,
        result = result,
    )

private fun EncryptedNotificationContentEntity.toPending(token: String): PendingNotificationActionContentEntity =
    PendingNotificationActionContentEntity(
        outboxToken = token,
        formatVersion = formatVersion,
        aadId = aadId,
        nonce = nonce,
        ciphertext = ciphertext,
        createdAtMillis = createdAtMillis,
    )

private fun PendingNotificationActionRelation.toEvent(
    hadEncryptedContent: Boolean,
    recordedAtMillis: Long,
): NotificationEventEntity =
    NotificationEventEntity(
        packageName = action.packageName,
        channelId = action.channelId,
        channelName = action.channelName,
        mlCategory = action.mlCategory,
        mlConfidence = action.mlConfidence,
        category = action.category,
        postedAtMillis = action.postedAtMillis,
        postedEpochDay = action.postedEpochDay,
        postedMinuteOfDay = action.postedMinuteOfDay,
        importance = action.importance,
        isConversation = action.isConversation,
        isForegroundService = action.isForegroundService,
        action = action.action,
        matchedRuleId = action.matchedRuleId,
        monitoredRuleId = action.monitoredRuleId,
        monitoredAction = action.monitoredAction,
        recordedAtMillis = recordedAtMillis,
        hadEncryptedContent = hadEncryptedContent,
    )

private fun PendingNotificationActionRelation.toTrace(): List<NotificationDecisionTraceEntity> =
    trace
        .sortedBy(PendingNotificationActionTraceEntity::sequence)
        .map { row ->
            NotificationDecisionTraceEntity(
                eventId = 0,
                lane = row.lane,
                position = row.position,
                depth = row.depth,
                conditionKind = row.conditionKind,
                result = row.result,
            )
        }

private fun PendingNotificationActionContentEntity.toEncryptedContent(): EncryptedNotificationContentEntity =
    EncryptedNotificationContentEntity(
        formatVersion = formatVersion,
        aadId = aadId,
        nonce = nonce,
        ciphertext = ciphertext,
        createdAtMillis = createdAtMillis,
    )

private fun PendingNotificationActionContentEntity.isInsideRetention(
    nowMillis: Long,
    retentionMillis: Long,
): Boolean =
    createdAtMillis <= nowMillis &&
        nowMillis - createdAtMillis < retentionMillis
