package com.alarmcontrol.data.repository

import com.alarmcontrol.core.coroutines.AppDispatcher
import com.alarmcontrol.core.coroutines.Dispatcher
import com.alarmcontrol.core.filtering.ActiveRateOccurrence
import com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.PersistedRateOccurrence
import com.alarmcontrol.core.filtering.RateListenerKeyDigest
import com.alarmcontrol.core.filtering.RateOccurrenceId
import com.alarmcontrol.core.filtering.RateOccurrenceIncompleteReason
import com.alarmcontrol.core.filtering.RateOccurrencePersistenceFailure
import com.alarmcontrol.core.filtering.RateOccurrencePersistenceResult
import com.alarmcontrol.core.filtering.RateOccurrenceRepository
import com.alarmcontrol.core.filtering.RateOccurrenceSeed
import com.alarmcontrol.core.filtering.RecordedRateOccurrence
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.data.db.dao.NotificationRateStateDao
import com.alarmcontrol.data.db.entity.ActiveNotificationRateOccurrenceEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Room-backed, completeness-aware storage for restart-safe notification frequency state. */
class RateOccurrenceRepositoryImpl
    @Inject
    internal constructor(
        private val dao: NotificationRateStateDao,
        @Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
    ) : RateOccurrenceRepository {
        override suspend fun loadSeed(
            sinceMillis: Long,
            nowMillis: Long,
        ): RateOccurrenceSeed {
            require(sinceMillis <= nowMillis) { "Rate seed start must not follow now" }
            return withContext(ioDispatcher) {
                runCatchingPreservingCancellation {
                    val snapshot =
                        dao.rateSeedSnapshot(
                            sinceMillis = sinceMillis,
                            nowMillis = nowMillis,
                            limit = SEED_QUERY_LIMIT,
                        )
                    val persistedIncompleteUntil =
                        snapshot.rateState
                            ?.incompleteUntilMillis
                            ?.takeIf { it == Long.MAX_VALUE || it > nowMillis }
                    val rows = snapshot.occurrences

                    when {
                        snapshot.coverageStartMillis == null ->
                            RateOccurrenceSeed.Incomplete(
                                RateOccurrenceIncompleteReason.PERSISTED_GAP,
                                persistedIncompleteUntil,
                            )

                        rows.size > MAX_SEED_OCCURRENCES ->
                            RateOccurrenceSeed.Incomplete(
                                RateOccurrenceIncompleteReason.HISTORY_LIMIT_EXCEEDED,
                                retryAtMillis = null,
                            )

                        else ->
                            RateOccurrenceSeed.Available(
                                occurrences =
                                    rows.map { row ->
                                        PersistedRateOccurrence(
                                            occurrenceId = RateOccurrenceId(row.occurrenceId),
                                            packageName = row.packageName,
                                            channelId = row.channelId,
                                            postedAtMillis = row.latestPostedAtMillis,
                                        )
                                    },
                                coverageStartMillis = checkNotNull(snapshot.coverageStartMillis),
                            )
                    }
                }.getOrElse {
                    RateOccurrenceSeed.Unavailable(
                        RateOccurrencePersistenceFailure.PERSISTENCE_UNAVAILABLE,
                    )
                }
            }
        }

        override suspend fun activeOccurrences(): RateOccurrencePersistenceResult<List<ActiveRateOccurrence>> =
            withContext(ioDispatcher) {
                persistenceResult {
                    dao.activeOccurrences().map(ActiveNotificationRateOccurrenceEntity::toDomain)
                }
            }

        override suspend fun activeOccurrence(
            listenerKeyDigest: RateListenerKeyDigest,
        ): RateOccurrencePersistenceResult<ActiveRateOccurrence?> =
            withContext(ioDispatcher) {
                persistenceResult {
                    dao.activeOccurrence(listenerKeyDigest.value)?.toDomain()
                }
            }

        override suspend fun recordPost(
            listenerKeyDigest: RateListenerKeyDigest,
            candidateOccurrenceId: RateOccurrenceId,
            packageName: String,
            channelId: String?,
            postedAtMillis: Long,
        ): RateOccurrencePersistenceResult<RecordedRateOccurrence> {
            require(packageName.isNotBlank()) { "Package name is blank" }
            return withContext(ioDispatcher) {
                persistenceResult {
                    val result =
                        dao.recordPost(
                            listenerKeyHmac = listenerKeyDigest.value,
                            candidateOccurrenceId = candidateOccurrenceId.value,
                            packageName = packageName,
                            channelId = channelId,
                            postedAtMillis = postedAtMillis,
                        )
                    RecordedRateOccurrence(
                        activeOccurrence = result.activeOccurrence.toDomain(),
                        accepted = result.accepted,
                        incompleteUntilMillis = result.incompleteUntilMillis,
                    )
                }
            }
        }

        override suspend fun deleteActiveOccurrence(
            listenerKeyDigest: RateListenerKeyDigest,
            occurrenceId: RateOccurrenceId,
            removedPostTimeMillis: Long,
        ): RateOccurrencePersistenceResult<Boolean> =
            withContext(ioDispatcher) {
                persistenceResult {
                    dao.deleteActiveOccurrence(
                        listenerKeyHmac = listenerKeyDigest.value,
                        occurrenceId = occurrenceId.value,
                        removedPostTimeMillis = removedPostTimeMillis,
                    ) > 0
                }
            }

        override suspend fun purgeExpiredHistory(nowMillis: Long): RateOccurrencePersistenceResult<Int> =
            withContext(ioDispatcher) {
                persistenceResult {
                    dao.purgeExpiredRateData(
                        cutoffMillis = oldestSupportedPostAt(nowMillis),
                        rollbackBarrierMillis = nowMillis,
                    )
                }
            }

        override suspend fun extendIncompleteWindowFrom(anchorMillis: Long): RateOccurrencePersistenceResult<Long> =
            withContext(ioDispatcher) {
                persistenceResult {
                    dao.extendIncompleteUntilAndRead(incompleteUntilAfter(anchorMillis))
                }
            }
    }

private fun ActiveNotificationRateOccurrenceEntity.toDomain(): ActiveRateOccurrence =
    ActiveRateOccurrence(
        listenerKeyDigest = RateListenerKeyDigest(listenerKeyHmac),
        occurrenceId = RateOccurrenceId(occurrenceId),
        packageName = packageName,
        channelId = channelId,
        lastPostedAtMillis = lastPostedAtMillis,
    )

private inline fun <T> persistenceResult(block: () -> T): RateOccurrencePersistenceResult<T> =
    runCatchingPreservingCancellation(block).fold(
        onSuccess = { RateOccurrencePersistenceResult.Success(it) },
        onFailure = {
            RateOccurrencePersistenceResult.Unavailable(
                RateOccurrencePersistenceFailure.PERSISTENCE_UNAVAILABLE,
            )
        },
    )

private fun incompleteUntilAfter(anchorMillis: Long): Long =
    if (anchorMillis > Long.MAX_VALUE - INCOMPLETE_WINDOW_MILLIS) {
        Long.MAX_VALUE
    } else {
        anchorMillis + INCOMPLETE_WINDOW_MILLIS
    }

private fun oldestSupportedPostAt(nowMillis: Long): Long =
    if (nowMillis < Long.MIN_VALUE + MAX_RATE_WINDOW_MILLIS) {
        Long.MIN_VALUE
    } else {
        nowMillis - MAX_RATE_WINDOW_MILLIS
    }

private const val MAX_SEED_OCCURRENCES = 10_000
private const val SEED_QUERY_LIMIT = MAX_SEED_OCCURRENCES + 1
private const val INCOMPLETE_WINDOW_MILLIS = MAX_RATE_WINDOW_MILLIS + 1
