package com.alarmcontrol.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alarmcontrol.data.db.entity.RuleSuggestionDismissalEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleSuggestionDao {
    @Query(
        "SELECT package_name, channel_id, COUNT(*) AS total_count, " +
            "SUM(CASE WHEN action IN (:cancelAction, :snoozeAction) THEN 1 ELSE 0 END) AS silenced_count " +
            "FROM notification_events WHERE recorded_at_millis >= :sinceMillis AND undone = 0 " +
            "AND channel_id IS NOT NULL GROUP BY package_name, channel_id " +
            "HAVING COUNT(*) >= :minimumEvents AND " +
            "SUM(CASE WHEN action IN (:cancelAction, :snoozeAction) THEN 1 ELSE 0 END) * 100 " +
            ">= COUNT(*) * :minimumPercent",
    )
    fun observeChannelCandidates(
        sinceMillis: Long,
        minimumEvents: Int,
        minimumPercent: Int,
        cancelAction: StoredRuleAction,
        snoozeAction: StoredRuleAction,
    ): Flow<List<ChannelSuggestionRow>>

    @Query(
        "SELECT package_name, SUM(is_marketing) AS marketing_count, COUNT(*) AS total_count FROM (" +
            "SELECT package_name, CASE WHEN LOWER(corrected_label) = 'promotion' THEN 1 ELSE 0 END " +
            "AS is_marketing FROM category_feedback WHERE recorded_at_millis >= :sinceMillis " +
            "UNION ALL SELECT package_name, CASE WHEN corrected_intent = 'MARKETING' THEN 1 ELSE 0 END " +
            "AS is_marketing FROM llm_observations WHERE analyzed_at_millis >= :sinceMillis " +
            "AND corrected_intent IS NOT NULL) GROUP BY package_name " +
            "HAVING SUM(is_marketing) >= :minimumCorrections " +
            "AND SUM(is_marketing) * 100 >= COUNT(*) * :minimumPercent",
    )
    fun observeMarketingCandidates(
        sinceMillis: Long,
        minimumCorrections: Int,
        minimumPercent: Int,
    ): Flow<List<MarketingSuggestionRow>>

    @Query("SELECT suggestion_key FROM rule_suggestion_dismissals")
    fun observeDismissedKeys(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun dismiss(entity: RuleSuggestionDismissalEntity)

    @Query("DELETE FROM rule_suggestion_dismissals")
    suspend fun deleteAllDismissals(): Int
}

data class ChannelSuggestionRow(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "channel_id") val channelId: String,
    @ColumnInfo(name = "total_count") val totalCount: Int,
    @ColumnInfo(name = "silenced_count") val silencedCount: Int,
)

data class MarketingSuggestionRow(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "marketing_count") val marketingCount: Int,
    @ColumnInfo(name = "total_count") val totalCount: Int,
)
