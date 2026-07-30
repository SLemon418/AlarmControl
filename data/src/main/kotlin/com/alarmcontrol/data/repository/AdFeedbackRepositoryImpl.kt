package com.alarmcontrol.data.repository

import com.alarmcontrol.core.feedback.AdFeedbackCounts
import com.alarmcontrol.core.feedback.AdFeedbackRepository
import com.alarmcontrol.core.feedback.AdObservation
import com.alarmcontrol.core.filtering.NotificationHistoryWriteFence
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.privacy.FeedbackWriteFence
import com.alarmcontrol.core.privacy.LocalDataResetWriteFence
import com.alarmcontrol.core.privacy.StaleLocalDataWriteException
import com.alarmcontrol.data.db.TransactionRunner
import com.alarmcontrol.data.db.dao.DailyInsightDao
import com.alarmcontrol.data.db.dao.LlmObservationDao
import com.alarmcontrol.data.db.entity.LocalSemanticFeedbackEntity
import com.alarmcontrol.data.mapper.toDomain
import com.alarmcontrol.data.mapper.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject

class AdFeedbackRepositoryImpl
    @Inject
    constructor(
        private val dao: LlmObservationDao,
        private val dailyInsightDao: DailyInsightDao,
        private val transactionRunner: TransactionRunner,
        private val clock: Clock = Clock.systemUTC(),
        private val localDataResetWriteFence: LocalDataResetWriteFence = LocalDataResetWriteFence(),
        private val notificationHistoryWriteFence: NotificationHistoryWriteFence =
            NotificationHistoryWriteFence(),
        private val feedbackWriteFence: FeedbackWriteFence = FeedbackWriteFence(),
    ) : AdFeedbackRepository {
        override suspend fun recordObservation(observation: AdObservation) {
            val entity = observation.toEntity()
            transactionRunner.run {
                if (dao.upsertIfEventExists(entity)) {
                    dailyInsightDao.deleteContainingEvent(entity.notificationEventId)
                }
            }
        }

        override suspend fun recordCorrection(
            notificationEventId: String,
            correctedIntent: SemanticIntent,
        ): Boolean {
            val eventId = notificationEventId.toLongOrNull() ?: return false
            val resetEpoch = localDataResetWriteFence.captureEpoch()
            val historyEpoch = notificationHistoryWriteFence.captureEpoch()
            val feedbackEpoch = feedbackWriteFence.captureEpoch()
            return localDataResetWriteFence.writeIfCurrent(resetEpoch) {
                notificationHistoryWriteFence.writeIfCurrent(historyEpoch) {
                    feedbackWriteFence.writeIfCurrent(feedbackEpoch) {
                        transactionRunner.run {
                            val target = dao.getCorrectionTarget(eventId) ?: return@run false
                            val recordedAtMillis = clock.millis()
                            check(
                                dao.setIntentCorrection(
                                    eventId,
                                    correctedIntent.name,
                                    correctedIntent.isAdvertisement,
                                ) == 1,
                            )
                            dao.upsertLocalSemanticFeedback(
                                LocalSemanticFeedbackEntity(
                                    sourceEventId = target.notificationEventId,
                                    packageName = target.packageName,
                                    correctedIntent = correctedIntent.name,
                                    recordedAtMillis = recordedAtMillis,
                                ),
                            )
                            dao.trimLocalSemanticFeedback(LlmObservationDao.MAX_LOCAL_SEMANTIC_FEEDBACK)
                            dailyInsightDao.deleteContainingEvent(eventId)
                            true
                        }
                    } ?: throw StaleLocalDataWriteException()
                } ?: throw StaleLocalDataWriteException()
            } ?: throw StaleLocalDataWriteException()
        }

        override fun observeByEvent(): Flow<Map<String, AdObservation>> =
            dao.observeAll().map { rows ->
                rows.associate { row -> row.notificationEventId.toString() to row.toDomain() }
            }

        override fun observeAllFeedbackCounts(): Flow<Map<String, AdFeedbackCounts>> =
            dao.observeSemanticFeedbackCounts().map { rows ->
                rows.groupBy { it.packageName }.mapValues { (_, packageRows) ->
                    val observed =
                        packageRows.associate { row -> SemanticIntent.valueOf(row.intent) to row.count }
                    val byIntent =
                        observed +
                            mapOf(
                                SemanticIntent.MARKETING to (observed[SemanticIntent.MARKETING] ?: 0),
                                SemanticIntent.TRANSACTIONAL to (observed[SemanticIntent.TRANSACTIONAL] ?: 0),
                            )
                    AdFeedbackCounts(
                        advertisement = byIntent[SemanticIntent.MARKETING] ?: 0,
                        transactional = byIntent[SemanticIntent.TRANSACTIONAL] ?: 0,
                        byIntent = byIntent,
                    )
                }
            }
    }
