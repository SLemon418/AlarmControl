package com.alarmcontrol.data.repository

import com.alarmcontrol.data.db.dao.CategoryFeedbackDao
import com.alarmcontrol.data.db.dao.EventCorrection
import com.alarmcontrol.data.db.dao.LabelCount
import com.alarmcontrol.data.db.dao.PackageLabelCount
import com.alarmcontrol.data.db.entity.CategoryFeedbackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [CategoryFeedbackDao] for JVM unit tests. Reactive: writes bump [revision] so observed
 * queries re-emit, mirroring Room's GROUP BY aggregation. [inserted] is exposed for assertions.
 */
class FakeCategoryFeedbackDao : CategoryFeedbackDao {
    private val rows = mutableListOf<CategoryFeedbackEntity>()
    val inserted: List<CategoryFeedbackEntity> get() = rows
    var lastTrimMaximum: Int? = null
        private set
    private var nextId = 1L
    private val revision = MutableStateFlow(0)

    override suspend fun countAll(): Int = rows.size

    override suspend fun insert(feedback: CategoryFeedbackEntity): Long {
        val id = nextId++
        rows += feedback.copy(id = id)
        revision.value++
        return id
    }

    override suspend fun insertAll(feedback: List<CategoryFeedbackEntity>) {
        feedback.forEach { insert(it) }
    }

    override suspend fun getEffectiveFeedback(): List<CategoryFeedbackEntity> = rows.effectiveForLearning()

    override suspend fun deleteForEvent(eventId: Long) {
        rows.removeAll { it.notificationEventId == eventId }
        // The default DAO record() calls insert immediately afterwards. Only insert bumps the fake
        // revision, mirroring Room's single post-transaction invalidation.
    }

    override suspend fun deleteAll(): Int {
        val count = rows.size
        rows.clear()
        revision.value++
        return count
    }

    override suspend fun deleteLinkedToEvents(): Int {
        val before = rows.size
        rows.removeAll { it.notificationEventId != null }
        revision.value++
        return before - rows.size
    }

    override suspend fun trimToMostRecent(max: Int): Int {
        require(max >= 0)
        lastTrimMaximum = max
        val retainedIds = rows.sortedByDescending { it.id }.take(max).mapTo(mutableSetOf()) { it.id }
        val before = rows.size
        rows.removeAll { it.id !in retainedIds }
        val removed = before - rows.size
        if (removed > 0) revision.value++
        return removed
    }

    override fun observeLabelCounts(packageName: String): Flow<List<LabelCount>> =
        revision.map {
            rows
                .effectiveForLearning()
                .filter { it.packageName == packageName }
                .groupingBy { it.correctedLabel }
                .eachCount()
                .map { (label, count) -> LabelCount(label, count) }
        }

    override fun observeAllLabelCounts(): Flow<List<PackageLabelCount>> =
        revision.map {
            rows
                .effectiveForLearning()
                .groupingBy { it.packageName to it.correctedLabel }
                .eachCount()
                .map { (key, count) -> PackageLabelCount(key.first, key.second, count) }
        }

    override fun observeLatestEventCorrections(): Flow<List<EventCorrection>> =
        revision.map {
            rows
                .filter { it.notificationEventId != null }
                .groupBy { it.notificationEventId!! }
                .map { (eventId, corrections) ->
                    EventCorrection(eventId, corrections.maxBy { it.id }.correctedLabel)
                }
        }

    private fun List<CategoryFeedbackEntity>.effectiveForLearning(): List<CategoryFeedbackEntity> {
        val latestLinkedIds =
            filter { it.notificationEventId != null }
                .groupBy { it.notificationEventId }
                .values
                .mapTo(mutableSetOf()) { corrections -> corrections.maxOf { it.id } }
        return filter { it.notificationEventId == null || it.id in latestLinkedIds }
    }
}
