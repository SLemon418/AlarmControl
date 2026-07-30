package com.alarmcontrol.data.repository

import com.alarmcontrol.core.feedback.CategoryFeedback
import com.alarmcontrol.core.feedback.FeedbackRepository
import com.alarmcontrol.core.filtering.NotificationHistoryWriteFence
import com.alarmcontrol.core.privacy.FeedbackWriteFence
import com.alarmcontrol.core.privacy.LocalDataResetWriteFence
import com.alarmcontrol.core.privacy.StaleLocalDataWriteException
import com.alarmcontrol.data.db.TransactionRunner
import com.alarmcontrol.data.db.dao.CategoryFeedbackDao
import com.alarmcontrol.data.db.dao.DailyInsightDao
import com.alarmcontrol.data.mapper.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Room-backed [FeedbackRepository]; aggregates correction counts at the boundary. */
class FeedbackRepositoryImpl
    @Inject
    constructor(
        private val feedbackDao: CategoryFeedbackDao,
        private val dailyInsightDao: DailyInsightDao,
        private val transactionRunner: TransactionRunner,
        private val localDataResetWriteFence: LocalDataResetWriteFence = LocalDataResetWriteFence(),
        private val notificationHistoryWriteFence: NotificationHistoryWriteFence =
            NotificationHistoryWriteFence(),
        private val feedbackWriteFence: FeedbackWriteFence = FeedbackWriteFence(),
    ) : FeedbackRepository {
        override suspend fun recordCorrection(feedback: CategoryFeedback) {
            val resetEpoch = localDataResetWriteFence.captureEpoch()
            val historyEpoch = notificationHistoryWriteFence.captureEpoch()
            val feedbackEpoch = feedbackWriteFence.captureEpoch()
            localDataResetWriteFence.writeIfCurrent(resetEpoch) {
                notificationHistoryWriteFence.writeIfCurrent(historyEpoch) {
                    feedbackWriteFence.writeIfCurrent(feedbackEpoch) {
                        transactionRunner.run {
                            feedbackDao.record(feedback.toEntity())
                            buildSet<Long> {
                                feedback.notificationEventId?.toLongOrNull()?.let(::add)
                                addAll(
                                    feedbackDao.getLinkedTrimVictimEventIds(
                                        CategoryFeedbackDao.MAX_RETAINED_ROWS,
                                    ),
                                )
                            }.forEach { eventId -> dailyInsightDao.deleteContainingEvent(eventId) }
                            feedbackDao.trimToMostRecent(CategoryFeedbackDao.MAX_RETAINED_ROWS)
                        }
                        Unit
                    } ?: throw StaleLocalDataWriteException()
                } ?: throw StaleLocalDataWriteException()
            } ?: throw StaleLocalDataWriteException()
        }

        override fun observeLabelCounts(packageName: String): Flow<Map<String, Int>> =
            feedbackDao
                .observeLabelCounts(packageName)
                .map { rows -> rows.associate { it.label to it.count } }

        override fun observeAllLabelCounts(): Flow<Map<String, Map<String, Int>>> =
            feedbackDao
                .observeAllLabelCounts()
                .map { rows ->
                    rows.groupBy { it.packageName }.mapValues { (_, counts) ->
                        counts.associate { it.label to it.count }
                    }
                }

        override fun observeEventCorrections(): Flow<Map<String, String>> =
            feedbackDao
                .observeLatestEventCorrections()
                .map { rows -> rows.associate { it.eventId.toString() to it.correctedLabel } }
    }
