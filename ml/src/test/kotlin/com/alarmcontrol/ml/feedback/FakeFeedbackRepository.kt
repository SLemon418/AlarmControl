package com.alarmcontrol.ml.feedback

import com.alarmcontrol.core.feedback.CategoryFeedback
import com.alarmcontrol.core.feedback.FeedbackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [FeedbackRepository] for JVM tests: [recordCorrection] appends and the observed counts
 * re-emit, so tests can assert how classification adapts as feedback accrues.
 */
class FakeFeedbackRepository : FeedbackRepository {
    private val corrections = MutableStateFlow<List<CategoryFeedback>>(emptyList())
    val countsByPackage = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())

    override suspend fun recordCorrection(feedback: CategoryFeedback) {
        corrections.value = corrections.value + feedback
        countsByPackage.value = aggregate(corrections.value)
    }

    override fun observeLabelCounts(packageName: String): Flow<Map<String, Int>> =
        corrections.map { list ->
            list
                .filter { it.packageName == packageName }
                .groupingBy { it.correctedLabel }
                .eachCount()
        }

    override fun observeAllLabelCounts(): Flow<Map<String, Map<String, Int>>> = countsByPackage

    override fun observeEventCorrections(): Flow<Map<String, String>> =
        corrections.map { list ->
            list
                .filter { it.notificationEventId != null }
                .groupBy { it.notificationEventId!! }
                .mapValues { (_, values) -> values.last().correctedLabel }
        }

    private fun aggregate(list: List<CategoryFeedback>): Map<String, Map<String, Int>> =
        list.groupBy { it.packageName }.mapValues { (_, packageCorrections) ->
            packageCorrections.groupingBy { it.correctedLabel }.eachCount()
        }
}
