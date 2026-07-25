package com.alarmcontrol.data.repository

import com.alarmcontrol.data.db.dao.AdVerdictCount
import com.alarmcontrol.data.db.dao.LlmObservationDao
import com.alarmcontrol.data.db.dao.SemanticVerdictCount
import com.alarmcontrol.data.db.entity.AdFeedbackPriorEntity
import com.alarmcontrol.data.db.entity.LlmObservationEntity
import com.alarmcontrol.data.db.entity.SemanticFeedbackPriorEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeLlmObservationDao : LlmObservationDao {
    private val rows = MutableStateFlow<List<LlmObservationEntity>>(emptyList())
    private val importedPriors = MutableStateFlow<List<AdFeedbackPriorEntity>>(emptyList())
    private val semanticPriors = MutableStateFlow<List<SemanticFeedbackPriorEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun upsert(observation: LlmObservationEntity): Long {
        val existing = rows.value.firstOrNull { it.notificationEventId == observation.notificationEventId }
        val id = existing?.id ?: observation.id.takeIf { it != 0L } ?: nextId++
        rows.value =
            rows.value.filterNot { it.notificationEventId == observation.notificationEventId } +
            observation.copy(id = id)
        return id
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

    override fun observeAll(): Flow<List<LlmObservationEntity>> =
        rows.map { values -> values.sortedByDescending { it.analyzedAtMillis } }

    override fun observeFeedbackCounts(): Flow<List<AdVerdictCount>> =
        kotlinx.coroutines.flow.combine(rows, importedPriors) { values, priors -> counts(values, priors) }

    override suspend fun getFeedbackCounts(): List<AdVerdictCount> = counts(rows.value, importedPriors.value)

    override fun observeSemanticFeedbackCounts(): Flow<List<SemanticVerdictCount>> =
        kotlinx.coroutines.flow.combine(rows, semanticPriors, ::semanticCounts)

    override suspend fun getSemanticFeedbackCounts(): List<SemanticVerdictCount> =
        semanticCounts(rows.value, semanticPriors.value)

    override suspend fun getSemanticImportedPriors(): List<SemanticFeedbackPriorEntity> = semanticPriors.value

    override suspend fun upsertSemanticImportedPriors(rows: List<SemanticFeedbackPriorEntity>) {
        val incoming = rows.associateBy { it.packageName to it.intent }
        semanticPriors.value =
            semanticPriors.value.filterNot { (it.packageName to it.intent) in incoming } + rows
    }

    override suspend fun deleteSemanticImportedPriors(): Int =
        semanticPriors.value.size.also { semanticPriors.value = emptyList() }

    override suspend fun countSemanticImportedPriorVotes(): Int = semanticPriors.value.sumOf { it.count }

    override suspend fun getImportedPriors(): List<AdFeedbackPriorEntity> = importedPriors.value

    override suspend fun upsertImportedPriors(rows: List<AdFeedbackPriorEntity>) {
        val incoming = rows.associateBy { it.packageName to it.isAdvertisement }
        importedPriors.value =
            (importedPriors.value.filterNot { (it.packageName to it.isAdvertisement) in incoming } + rows)
    }

    override suspend fun deleteImportedPriors(): Int =
        importedPriors.value.size.also { importedPriors.value = emptyList() }

    override suspend fun countImportedPriorVotes(): Int = importedPriors.value.sumOf { it.count }

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

    override suspend fun countCorrections(): Int = rows.value.count { it.correctedIsAdvertisement != null }

    override suspend fun deleteAll(): Int = rows.value.size.also { rows.value = emptyList() }

    private fun counts(
        observations: List<LlmObservationEntity>,
        priors: List<AdFeedbackPriorEntity>,
    ): List<AdVerdictCount> {
        val counts = mutableMapOf<Pair<String, Boolean>, Int>()
        observations.forEach { row ->
            row.correctedIsAdvertisement?.let { verdict ->
                counts[row.packageName to verdict] = (counts[row.packageName to verdict] ?: 0) + 1
            }
        }
        priors.forEach { row ->
            val key = row.packageName to row.isAdvertisement
            counts[key] = (counts[key] ?: 0) + row.count
        }
        return counts.map { (key, count) -> AdVerdictCount(key.first, key.second, count) }
    }

    private fun semanticCounts(
        observations: List<LlmObservationEntity>,
        priors: List<SemanticFeedbackPriorEntity>,
    ): List<SemanticVerdictCount> {
        val counts = mutableMapOf<Pair<String, String>, Int>()
        observations.forEach { row ->
            row.correctedIntent?.let { intent ->
                val key = row.packageName to intent
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        priors.forEach { row ->
            val key = row.packageName to row.intent
            counts[key] = (counts[key] ?: 0) + row.count
        }
        return counts.map { (key, count) -> SemanticVerdictCount(key.first, key.second, count) }
    }
}
