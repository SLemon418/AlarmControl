package com.alarmcontrol.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.alarmcontrol.data.db.entity.AdFeedbackPriorEntity
import com.alarmcontrol.data.db.entity.LlmObservationEntity
import com.alarmcontrol.data.db.entity.LocalSemanticFeedbackEntity
import com.alarmcontrol.data.db.entity.SemanticFeedbackPriorEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // Observation, correction, and prior queries share one Room table boundary.
interface LlmObservationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(observation: LlmObservationEntity): Long

    @Query(
        "UPDATE llm_observations SET package_name = :packageName, " +
            "predicted_is_ad = :predictedIsAdvertisement, predicted_intent = :predictedIntent, " +
            "confidence_score = :confidenceScore, analyzed_at_millis = :analyzedAtMillis " +
            "WHERE notification_event_id = :notificationEventId",
    )
    suspend fun updatePrediction(
        notificationEventId: Long,
        packageName: String,
        predictedIsAdvertisement: Boolean,
        predictedIntent: String,
        confidenceScore: Float,
        analyzedAtMillis: Long,
    ): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM notification_events WHERE id = :notificationEventId)",
    )
    suspend fun notificationEventExists(notificationEventId: Long): Boolean

    /**
     * Replaces a model prediction without erasing an explicit correction that may have arrived
     * before a delayed local analysis completed. A history row may be removed before deferred
     * analysis finishes, so the parent check and write share one database transaction.
     */
    @Transaction
    suspend fun upsertIfEventExists(observation: LlmObservationEntity): Boolean {
        if (!notificationEventExists(observation.notificationEventId)) return false
        if (insertIfAbsent(observation) == -1L) {
            check(
                updatePrediction(
                    notificationEventId = observation.notificationEventId,
                    packageName = observation.packageName,
                    predictedIsAdvertisement = observation.predictedIsAdvertisement,
                    predictedIntent = observation.predictedIntent,
                    confidenceScore = observation.confidenceScore,
                    analyzedAtMillis = observation.analyzedAtMillis,
                ) == 1,
            )
        }
        return true
    }

    @Query(
        "UPDATE llm_observations SET corrected_is_ad = :corrected " +
            "WHERE notification_event_id = :eventId",
    )
    suspend fun setCorrection(
        eventId: Long,
        corrected: Boolean,
    ): Int

    @Query(
        "UPDATE llm_observations SET corrected_intent = :intent, corrected_is_ad = :isAdvertisement " +
            "WHERE notification_event_id = :eventId",
    )
    suspend fun setIntentCorrection(
        eventId: Long,
        intent: String,
        isAdvertisement: Boolean,
    ): Int

    @Query(
        "SELECT notification_event_id, package_name FROM llm_observations " +
            "WHERE notification_event_id = :eventId LIMIT 1",
    )
    suspend fun getCorrectionTarget(eventId: Long): SemanticCorrectionTarget?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalSemanticFeedback(feedback: LocalSemanticFeedbackEntity)

    @Query(
        "DELETE FROM local_semantic_feedback WHERE source_event_id NOT IN (" +
            "SELECT source_event_id FROM local_semantic_feedback " +
            "ORDER BY recorded_at_millis DESC, source_event_id DESC LIMIT :max)",
    )
    suspend fun trimLocalSemanticFeedback(max: Int): Int

    @Query("SELECT * FROM local_semantic_feedback ORDER BY recorded_at_millis DESC, source_event_id DESC")
    suspend fun getLocalSemanticFeedback(): List<LocalSemanticFeedbackEntity>

    @Query("SELECT COUNT(*) FROM local_semantic_feedback")
    suspend fun countLocalSemanticFeedback(): Int

    @Query("DELETE FROM local_semantic_feedback")
    suspend fun deleteLocalSemanticFeedback(): Int

    @Query("SELECT * FROM llm_observations ORDER BY analyzed_at_millis DESC")
    fun observeAll(): Flow<List<LlmObservationEntity>>

    @Query(FEEDBACK_COUNTS_QUERY)
    fun observeFeedbackCounts(): Flow<List<AdVerdictCount>>

    @Query(FEEDBACK_COUNTS_QUERY)
    suspend fun getFeedbackCounts(): List<AdVerdictCount>

    @Query(SEMANTIC_FEEDBACK_COUNTS_QUERY)
    fun observeSemanticFeedbackCounts(): Flow<List<SemanticVerdictCount>>

    @Query(SEMANTIC_FEEDBACK_COUNTS_QUERY)
    suspend fun getSemanticFeedbackCounts(): List<SemanticVerdictCount>

    @Query("SELECT * FROM semantic_feedback_priors")
    suspend fun getSemanticImportedPriors(): List<SemanticFeedbackPriorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSemanticImportedPriors(rows: List<SemanticFeedbackPriorEntity>)

    @Query("DELETE FROM semantic_feedback_priors")
    suspend fun deleteSemanticImportedPriors(): Int

    @Query("SELECT COALESCE(SUM(count), 0) FROM semantic_feedback_priors")
    suspend fun countSemanticImportedPriorVotes(): Long

    @Query("SELECT * FROM ad_feedback_priors")
    suspend fun getImportedPriors(): List<AdFeedbackPriorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImportedPriors(rows: List<AdFeedbackPriorEntity>)

    @Query("DELETE FROM ad_feedback_priors")
    suspend fun deleteImportedPriors(): Int

    @Query("SELECT COALESCE(SUM(count), 0) FROM ad_feedback_priors")
    suspend fun countImportedPriorVotes(): Long

    @Query("UPDATE llm_observations SET corrected_is_ad = NULL WHERE corrected_is_ad IS NOT NULL")
    suspend fun clearCorrections(): Int

    @Query(
        "UPDATE llm_observations SET corrected_intent = NULL, corrected_is_ad = NULL " +
            "WHERE corrected_intent IS NOT NULL OR corrected_is_ad IS NOT NULL",
    )
    suspend fun clearSemanticCorrections(): Int

    @Query("SELECT COUNT(*) FROM llm_observations")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM local_semantic_feedback")
    suspend fun countCorrections(): Int

    @Query("DELETE FROM llm_observations")
    suspend fun deleteAll(): Int

    companion object {
        const val FEEDBACK_COUNTS_QUERY =
            "SELECT package_name, corrected_is_ad, SUM(count) AS count FROM (" +
                "SELECT package_name, CASE WHEN corrected_intent = 'MARKETING' THEN 1 ELSE 0 END " +
                "AS corrected_is_ad, COUNT(*) AS count FROM local_semantic_feedback " +
                "GROUP BY package_name, corrected_is_ad " +
                "UNION ALL SELECT package_name, is_ad AS corrected_is_ad, count " +
                "FROM ad_feedback_priors) GROUP BY package_name, corrected_is_ad"

        const val SEMANTIC_FEEDBACK_COUNTS_QUERY =
            "SELECT package_name, intent, SUM(count) AS count FROM (" +
                "SELECT package_name, corrected_intent AS intent, COUNT(*) AS count " +
                "FROM local_semantic_feedback " +
                "GROUP BY package_name, corrected_intent UNION ALL " +
                "SELECT package_name, intent, count FROM semantic_feedback_priors) " +
                "GROUP BY package_name, intent"

        const val MAX_LOCAL_SEMANTIC_FEEDBACK = 25_000
    }
}

data class SemanticCorrectionTarget(
    @ColumnInfo(name = "notification_event_id") val notificationEventId: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
)

data class AdVerdictCount(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "corrected_is_ad") val isAdvertisement: Boolean,
    @ColumnInfo(name = "count") val count: Int,
)

data class SemanticVerdictCount(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "intent") val intent: String,
    @ColumnInfo(name = "count") val count: Int,
)
