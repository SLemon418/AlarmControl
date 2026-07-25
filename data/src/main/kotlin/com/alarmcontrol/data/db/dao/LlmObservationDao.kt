package com.alarmcontrol.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alarmcontrol.data.db.entity.AdFeedbackPriorEntity
import com.alarmcontrol.data.db.entity.LlmObservationEntity
import com.alarmcontrol.data.db.entity.SemanticFeedbackPriorEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // Observation, correction, and prior queries share one Room table boundary.
interface LlmObservationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(observation: LlmObservationEntity): Long

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
    suspend fun countSemanticImportedPriorVotes(): Int

    @Query("SELECT * FROM ad_feedback_priors")
    suspend fun getImportedPriors(): List<AdFeedbackPriorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImportedPriors(rows: List<AdFeedbackPriorEntity>)

    @Query("DELETE FROM ad_feedback_priors")
    suspend fun deleteImportedPriors(): Int

    @Query("SELECT COALESCE(SUM(count), 0) FROM ad_feedback_priors")
    suspend fun countImportedPriorVotes(): Int

    @Query("UPDATE llm_observations SET corrected_is_ad = NULL WHERE corrected_is_ad IS NOT NULL")
    suspend fun clearCorrections(): Int

    @Query(
        "UPDATE llm_observations SET corrected_intent = NULL, corrected_is_ad = NULL " +
            "WHERE corrected_intent IS NOT NULL OR corrected_is_ad IS NOT NULL",
    )
    suspend fun clearSemanticCorrections(): Int

    @Query("SELECT COUNT(*) FROM llm_observations")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM llm_observations WHERE corrected_is_ad IS NOT NULL")
    suspend fun countCorrections(): Int

    @Query("DELETE FROM llm_observations")
    suspend fun deleteAll(): Int

    companion object {
        const val FEEDBACK_COUNTS_QUERY =
            "SELECT package_name, corrected_is_ad, SUM(count) AS count FROM (" +
                "SELECT package_name, corrected_is_ad, COUNT(*) AS count FROM llm_observations " +
                "WHERE corrected_is_ad IS NOT NULL GROUP BY package_name, corrected_is_ad " +
                "UNION ALL SELECT package_name, is_ad AS corrected_is_ad, count " +
                "FROM ad_feedback_priors) GROUP BY package_name, corrected_is_ad"

        const val SEMANTIC_FEEDBACK_COUNTS_QUERY =
            "SELECT package_name, intent, SUM(count) AS count FROM (" +
                "SELECT package_name, corrected_intent AS intent, COUNT(*) AS count " +
                "FROM llm_observations WHERE corrected_intent IS NOT NULL " +
                "GROUP BY package_name, corrected_intent UNION ALL " +
                "SELECT package_name, intent, count FROM semantic_feedback_priors) " +
                "GROUP BY package_name, intent"
    }
}

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
