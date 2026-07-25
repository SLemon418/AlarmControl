package com.alarmcontrol.data.repository

import com.alarmcontrol.core.coroutines.AppDispatcher
import com.alarmcontrol.core.coroutines.Dispatcher
import com.alarmcontrol.core.filtering.ActionKind
import com.alarmcontrol.core.filtering.HistoryActionFilter
import com.alarmcontrol.core.filtering.NotificationContent
import com.alarmcontrol.core.filtering.NotificationContentState
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.NotificationEventDetail
import com.alarmcontrol.core.filtering.NotificationEventRepository
import com.alarmcontrol.core.filtering.NotificationEventTimeBounds
import com.alarmcontrol.core.filtering.NotificationHistoryPage
import com.alarmcontrol.core.filtering.NotificationHistoryQuery
import com.alarmcontrol.core.filtering.NotificationHistoryCoverage
import com.alarmcontrol.core.filtering.NotificationHistoryRepository
import com.alarmcontrol.core.filtering.NotificationRateEvent
import com.alarmcontrol.core.filtering.NotificationSource
import com.alarmcontrol.core.insights.ActionBreakdown
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.data.db.dao.ActionCountRow
import com.alarmcontrol.data.db.dao.NotificationEventDao
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.db.relation.NotificationEventDetailRelation
import com.alarmcontrol.data.mapper.toDomain
import com.alarmcontrol.data.mapper.toEncryptedContent
import com.alarmcontrol.data.mapper.toEntity
import com.alarmcontrol.data.mapper.toStored
import com.alarmcontrol.data.security.NotificationContentCipher
import com.alarmcontrol.data.security.NotificationContentCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject

/** Room-backed [NotificationEventRepository]; maps entities to the domain at the boundary. */
class NotificationEventRepositoryImpl
    @Inject
    internal constructor(
        private val eventDao: NotificationEventDao,
        private val contentCipher: NotificationContentCipher,
        private val clock: Clock,
        @Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
    ) : NotificationEventRepository,
        NotificationHistoryRepository {
        override suspend fun record(
            event: NotificationEvent,
            content: NotificationContent?,
        ): String =
            withContext(ioDispatcher) {
                val encrypted =
                    content
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
                eventDao
                    .insertWithTrace(
                        event.copy(hadEncryptedContent = encrypted != null).toEntity(),
                        event.decisionTrace.map { it.toEntity() },
                        encrypted,
                    ).toString()
            }

        override fun observeRecent(limit: Int): Flow<List<NotificationEvent>> =
            eventDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

        override fun observeHistory(query: NotificationHistoryQuery): Flow<NotificationHistoryPage> {
            val action = query.action.toStoredOrNull()
            val ruleId = query.ruleId?.toLongOrNull()
            val escapedSearch = query.search.trim().escapeForLike()
            return combine(
                eventDao.observeHistory(
                    startMillis = query.startMillis,
                    endMillis = query.endMillis,
                    search = escapedSearch,
                    packageName = query.packageName,
                    channelId = query.channelId,
                    category = query.category,
                    ruleId = ruleId,
                    action = action,
                    includeExcluded = query.includeExcluded,
                    limit = query.limit,
                ),
                eventDao.observeHistoryCount(
                    startMillis = query.startMillis,
                    endMillis = query.endMillis,
                    search = escapedSearch,
                    packageName = query.packageName,
                    channelId = query.channelId,
                    category = query.category,
                    ruleId = ruleId,
                    action = action,
                    includeExcluded = query.includeExcluded,
                ),
            ) { rows, count ->
                NotificationHistoryPage(rows.map { it.toDomain() }, count)
            }.flowOn(ioDispatcher)
        }

        override fun observeSources(limit: Int): Flow<List<NotificationSource>> =
            eventDao.observeSources(limit).map { rows ->
                rows.map {
                    NotificationSource(
                        packageName = it.packageName,
                        channelId = it.channelId,
                        channelName = it.channelName,
                        eventCount = it.eventCount,
                        lastSeenMillis = it.lastSeenMillis,
                    )
                }
            }

        override fun observeCoverage(): Flow<NotificationHistoryCoverage> =
            eventDao.observeCoverage().map { row ->
                NotificationHistoryCoverage(
                    totalEvents = row.totalEventCount,
                    oldestPostedAtMillis = row.oldestPostedAtMillis,
                    newestPostedAtMillis = row.newestPostedAtMillis,
                    eventsWithTrace = row.traceEventCount,
                )
            }

        override suspend fun getDetail(eventId: String): NotificationEventDetail? =
            withContext(ioDispatcher) {
                eventId.toLongOrNull()?.let { id ->
                    eventDao.getDetail(id)?.let { row ->
                        NotificationEventDetail(
                            event = row.toDomain(),
                            content = row.contentState(),
                        )
                    }
                }
            }

        override suspend fun recentSimulationSamples(
            packageName: String?,
            limit: Int,
        ): List<NotificationEventDetail> =
            withContext(ioDispatcher) {
                eventDao.getSimulationSamples(packageName, limit.coerceIn(1, MAX_SIMULATION_SAMPLES)).map { row ->
                    NotificationEventDetail(row.toDomain(), row.contentState())
                }
            }

        override fun countByActionSince(
            kind: ActionKind,
            sinceMillis: Long,
        ): Flow<Int> = eventDao.countByActionSince(kind.toStored(), sinceMillis)

        override fun observeActionBreakdownSince(sinceMillis: Long): Flow<ActionBreakdown> =
            eventDao.observeActionCountsSince(sinceMillis).map(List<ActionCountRow>::toActionBreakdown)

        override suspend fun undo(eventId: String) {
            eventId.toLongOrNull()?.let { eventDao.markUndone(it) }
        }

        override suspend fun purgeEventsOlderThan(cutoffMillis: Long): Int = eventDao.deleteOlderThan(cutoffMillis)

        override suspend fun trimToMostRecent(max: Int): Int = eventDao.deleteOverLimit(max)

        override suspend fun trimDecisionTracesToMostRecent(max: Int): Int =
            eventDao.deleteTracesOutsideMostRecent(max)

        override suspend fun postedAtBounds(): NotificationEventTimeBounds? =
            eventDao.getPostedAtBounds().let { bounds ->
                val oldest = bounds.oldestPostedAtMillis ?: return@let null
                val newest = bounds.newestPostedAtMillis ?: return@let null
                NotificationEventTimeBounds(oldest, newest)
            }

        override suspend fun mutedCountsByPackageBetween(
            startMillis: Long,
            endMillis: Long,
        ): Map<String, Int> =
            // Only actions with a real platform silencing side effect count as muted.
            eventDao
                .countByPackageBetween(
                    startMillis,
                    endMillis,
                    StoredRuleAction.CANCEL,
                    StoredRuleAction.SNOOZE,
                ).associate { it.packageName to it.count }

        override suspend fun rateHistorySince(sinceMillis: Long): List<NotificationRateEvent> =
            eventDao.rateHistorySince(sinceMillis).map {
                NotificationRateEvent(it.packageName, it.channelId, it.postedAtMillis)
            }

        override suspend fun purgeEncryptedContentOlderThan(cutoffMillis: Long): Int =
            eventDao.deleteEncryptedContentsOlderThan(cutoffMillis)

        private fun NotificationEventDetailRelation.contentState(): NotificationContentState {
            val payload = encryptedContent
            if (payload == null) {
                return if (event.hadEncryptedContent) {
                    NotificationContentState.Expired
                } else {
                    NotificationContentState.NotStored
                }
            }
            if (clock.millis() - payload.createdAtMillis >= ENCRYPTED_CONTENT_RETENTION_MILLIS) {
                return NotificationContentState.Expired
            }
            return runCatchingPreservingCancellation {
                val plaintext = contentCipher.decrypt(payload.toEncryptedContent())
                try {
                    NotificationContentCodec.decode(plaintext)
                } finally {
                    plaintext.fill(0)
                }
            }.fold(
                onSuccess = { NotificationContentState.Available(it.title, it.text) },
                onFailure = { NotificationContentState.Unreadable },
            )
        }
    }

private fun HistoryActionFilter.toStoredOrNull(): StoredRuleAction? =
    when (this) {
        HistoryActionFilter.ALL -> null
        HistoryActionFilter.CANCELLED -> StoredRuleAction.CANCEL
        HistoryActionFilter.SNOOZED -> StoredRuleAction.SNOOZE
        HistoryActionFilter.LOGGED -> StoredRuleAction.MARK_READ
        HistoryActionFilter.KEPT -> StoredRuleAction.KEEP
    }

private fun List<ActionCountRow>.toActionBreakdown(): ActionBreakdown {
    val counts = associate { it.action to it.count }
    return ActionBreakdown(
        cancelled = counts[StoredRuleAction.CANCEL] ?: 0,
        snoozed = counts[StoredRuleAction.SNOOZE] ?: 0,
        loggedOnly = counts[StoredRuleAction.MARK_READ] ?: 0,
        kept = counts[StoredRuleAction.KEEP] ?: 0,
    )
}

private const val MAX_SIMULATION_SAMPLES = 20
private const val ENCRYPTED_CONTENT_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1_000

private fun String.escapeForLike(): String =
    replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
