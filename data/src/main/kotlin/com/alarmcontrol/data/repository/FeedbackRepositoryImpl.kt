package com.alarmcontrol.data.repository

import com.alarmcontrol.core.feedback.CategoryFeedback
import com.alarmcontrol.core.feedback.FeedbackRepository
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
    ) : FeedbackRepository {
        override suspend fun recordCorrection(feedback: CategoryFeedback) {
            transactionRunner.run {
                feedbackDao.record(feedback.toEntity())
                feedback.notificationEventId
                    ?.toLongOrNull()
                    ?.let { eventId -> dailyInsightDao.deleteContainingEvent(eventId) }
            }
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
