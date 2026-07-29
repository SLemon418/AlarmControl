package com.alarmcontrol.data.repository

import com.alarmcontrol.data.db.dao.AdVerdictCount
import com.alarmcontrol.data.db.dao.LlmObservationDao
import com.alarmcontrol.data.db.dao.SemanticCorrectionTarget
import com.alarmcontrol.data.db.dao.SemanticVerdictCount
import com.alarmcontrol.data.db.entity.AdFeedbackPriorEntity
import com.alarmcontrol.data.db.entity.LlmObservationEntity
import com.alarmcontrol.data.db.entity.LocalSemanticFeedbackEntity
import com.alarmcontrol.data.db.entity.SemanticFeedbackPriorEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeLlmObservationDao : LlmObservationDao {
    private val rows = MutableStateFlow<List<LlmObservationEntity>>(emptyList())
    private val importedPriors = MutableStateFlow<List<AdFeedbackPriorEntity>>(emptyList())
    private val semanticPriors = MutableStateFlow<List<SemanticFeedbackPriorEntity>>(emptyList())
    private val localFeedback = MutableStateFlow<List<LocalSemanticFeedbackEntity>>(emptyList())
    private val missingNotificationEventIds = mutableSetOf<Long>()
    private var nextId = 1L

    override suspend fun insertIfAbsent(observation: LlmObservationEntity): Long {
        val existing = rows.value.firstOrNull { it.notificationEventId == observation.notificationEventId }
        if (existing != null) return -1L
        val id = observation.id.takeIf { it != 0L } ?: nextId++
        rows.value = rows.value + observation.copy(id = id)
        return id
    }

    override suspend fun updatePrediction(
        notificationEventId: Long,
        packageName: String,
        predictedIsAdvertisement: Boolean,
        predictedIntent: String,
        confidenceScore: Float,
        analyzedAtMillis: Long,
    ): Int {
        val existing = rows.value.firstOrNull { it.notificationEventId == notificationEventId } ?: return 0
        rows.value =
            rows.value.filterNot { it.notificationEventId == notificationEventId } +
            existing.copy(
                packageName = packageName,
                predictedIsAdvertisement = predictedIsAdvertisement,
                predictedIntent = predictedIntent,
                confidenceScore = confidenceScore,
                analyzedAtMillis = analyzedAtMillis,
            )
        return 1
    }

    override suspend fun notificationEventExists(notificationEventId: Long): Boolean =
        notificationEventId !in missingNotificationEventIds

    fun deleteNotificationEvent(notificationEventId: Long) {
        missingNotificationEventIds += notificationEventId
        rows.value = rows.value.filterNot { it.notificationEventId == notificationEventId }
    }

    override suspend fun setCorrection(
        eventId: Long,
        corrected: Boolean,
    ): Int {
        val exists = rows.value.any { it.notificationEventId == eventId }
        rows.value =
            rows.value.map {
                if (it.notificationEventId ==
                    eventId
                ) {
                    it.copy(correctedIsAdvertisement = corrected)
                } else {
                    it
                }
            }
        return if (exists) 1 else 0
    }

    override suspend fun setIntentCorrection(
        eventId: Long,
        intent: String,
        isAdvertisement: Boolean,
    ): Int {
        val exists = rows.value.any { it.notificationEventId == eventId }
        rows.value =
            rows.value.map {
                if (it.notificationEventId == eventId) {
                    it.copy(correctedIntent = intent, correctedIsAdvertisement = isAdvertisement)
                } else {
                    it
                }
            }
        return if (exists) 1 else 0
    }

    override suspend fun getCorrectionTarget(eventId: Long): SemanticCorrectionTarget? =
        rows.value
            .firstOrNull { it.notificationEventId == eventId }
            ?.let { SemanticCorrectionTarget(it.notificationEventId, it.packageName) }

    override suspend fun upsertLocalSemanticFeedback(feedback: LocalSemanticFeedbackEntity) {
        localFeedback.value =
            localFeedback.value.filterNot { it.sourceEventId == feedback.sourceEventId } + feedback
    }

    override suspend fun trimLocalSemanticFeedback(max: Int): Int {
        val retained =
            localFeedback.value
                .sortedWith(
                    compareByDescending<LocalSemanticFeedbackEntity> { it.recordedAtMillis }
                        .thenByDescending { it.sourceEventId },
                ).take(max)
        val deleted = localFeedback.value.size - retained.size
        localFeedback.value = retained
        return deleted
    }

    override suspend fun getLocalSemanticFeedback(): List<LocalSemanticFeedbackEntity> =
        localFeedback.value.sortedWith(
            compareByDescending<LocalSemanticFeedbackEntity> { it.recordedAtMillis }
                .thenByDescending { it.sourceEventId },
        )

    override suspend fun countLocalSemanticFeedback(): Int = localFeedback.value.size

    override suspend fun deleteLocalSemanticFeedback(): Int =
        localFeedback.value.size.also { localFeedback.value = emptyList() }

    fun seedLocalSemanticFeedback(rows: List<LocalSemanticFeedbackEntity>) {
        localFeedback.value = rows.associateBy(LocalSemanticFeedbackEntity::sourceEventId).values.toList()
    }

    override fun observeAll(): Flow<List<LlmObservationEntity>> =
        rows.map { values -> values.sortedByDescending { it.analyzedAtMillis } }

    override fun observeFeedbackCounts(): Flow<List<AdVerdictCount>> =
        kotlinx.coroutines.flow.combine(localFeedback, importedPriors, ::counts)

    override suspend fun getFeedbackCounts(): List<AdVerdictCount> = counts(localFeedback.value, importedPriors.value)

    override fun observeSemanticFeedbackCounts(): Flow<List<SemanticVerdictCount>> =
        kotlinx.coroutines.flow.combine(localFeedback, semanticPriors, ::semanticCounts)

    override suspend fun getSemanticFeedbackCounts(): List<SemanticVerdictCount> =
        semanticCounts(localFeedback.value, semanticPriors.value)

    override suspend fun getSemanticImportedPriors(): List<SemanticFeedbackPriorEntity> = semanticPriors.value

    override suspend fun upsertSemanticImportedPriors(rows: List<SemanticFeedbackPriorEntity>) {
        val incoming = rows.associateBy { it.packageName to it.intent }
        semanticPriors.value =
            semanticPriors.value.filterNot { (it.packageName to it.intent) in incoming } + rows
    }

    override suspend fun deleteSemanticImportedPriors(): Int =
        semanticPriors.value.size.also { semanticPriors.value = emptyList() }

    override suspend fun countSemanticImportedPriorVotes(): Long = semanticPriors.value.sumOf { it.count.toLong() }

    override suspend fun getImportedPriors(): List<AdFeedbackPriorEntity> = importedPriors.value

    override suspend fun upsertImportedPriors(rows: List<AdFeedbackPriorEntity>) {
        val incoming = rows.associateBy { it.packageName to it.isAdvertisement }
        importedPriors.value =
            (importedPriors.value.filterNot { (it.packageName to it.isAdvertisement) in incoming } + rows)
    }

    override suspend fun deleteImportedPriors(): Int =
        importedPriors.value.size.also { importedPriors.value = emptyList() }

    override suspend fun countImportedPriorVotes(): Long = importedPriors.value.sumOf { it.count.toLong() }

    override suspend fun clearCorrections(): Int {
        val count = rows.value.count { it.correctedIsAdvertisement != null }
        rows.value = rows.value.map { it.copy(correctedIsAdvertisement = null) }
        return count
    }

    override suspend fun clearSemanticCorrections(): Int {
        val count = rows.value.count { it.correctedIntent != null || it.correctedIsAdvertisement != null }
        rows.value =
            rows.value.map { it.copy(correctedIntent = null, correctedIsAdvertisement = null) }
        return count
    }

    override suspend fun countAll(): Int = rows.value.size

    override suspend fun countCorrections(): Int = localFeedback.value.size

    override suspend fun deleteAll(): Int = rows.value.size.also { rows.value = emptyList() }

    private fun counts(
        feedback: List<LocalSemanticFeedbackEntity>,
        priors: List<AdFeedbackPriorEntity>,
    ): List<AdVerdictCount> {
        val counts = mutableMapOf<Pair<String, Boolean>, Int>()
        feedback.forEach { row ->
            val verdict = row.correctedIntent == "MARKETING"
            counts[row.packageName to verdict] = (counts[row.packageName to verdict] ?: 0) + 1
        }
        priors.forEach { row ->
            val key = row.packageName to row.isAdvertisement
            counts[key] = (counts[key] ?: 0) + row.count
        }
        return counts.map { (key, count) -> AdVerdictCount(key.first, key.second, count) }
    }

    private fun semanticCounts(
        feedback: List<LocalSemanticFeedbackEntity>,
        priors: List<SemanticFeedbackPriorEntity>,
    ): List<SemanticVerdictCount> {
        val counts = mutableMapOf<Pair<String, String>, Int>()
        feedback.forEach { row ->
            val key = row.packageName to row.correctedIntent
            counts[key] = (counts[key] ?: 0) + 1
        }
        priors.forEach { row ->
            val key = row.packageName to row.intent
            counts[key] = (counts[key] ?: 0) + row.count
        }
        return counts.map { (key, count) -> SemanticVerdictCount(key.first, key.second, count) }
    }
}
