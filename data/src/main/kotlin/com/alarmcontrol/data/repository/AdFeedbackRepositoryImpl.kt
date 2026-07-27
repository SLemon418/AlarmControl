package com.alarmcontrol.data.repository

import com.alarmcontrol.core.feedback.AdFeedbackCounts
import com.alarmcontrol.core.feedback.AdFeedbackRepository
import com.alarmcontrol.core.feedback.AdObservation
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.data.db.TransactionRunner
import com.alarmcontrol.data.db.dao.DailyInsightDao
import com.alarmcontrol.data.db.dao.LlmObservationDao
import com.alarmcontrol.data.mapper.toDomain
import com.alarmcontrol.data.mapper.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class AdFeedbackRepositoryImpl
    @Inject
    constructor(
        private val dao: LlmObservationDao,
        private val dailyInsightDao: DailyInsightDao,
        private val transactionRunner: TransactionRunner,
    ) : AdFeedbackRepository {
        override suspend fun recordObservation(observation: AdObservation) {
            dao.upsert(observation.toEntity())
        }

        override suspend fun recordCorrection(
            notificationEventId: String,
            correctedIntent: SemanticIntent,
        ) {
            notificationEventId.toLongOrNull()?.let {
                transactionRunner.run {
                    val changed = dao.setIntentCorrection(it, correctedIntent.name, correctedIntent.isAdvertisement)
                    if (changed > 0) dailyInsightDao.deleteContainingEvent(it)
                }
            }
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
