package com.alarmcontrol.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.alarmcontrol.core.profile.MAX_SAVED_PROFILES
import com.alarmcontrol.data.db.entity.FilteringProfileEntity
import com.alarmcontrol.data.db.entity.ProfileRuleEntity
import com.alarmcontrol.data.db.relation.ProfileWithRules
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Transaction
    @Query("SELECT * FROM filtering_profiles ORDER BY name COLLATE NOCASE, id")
    fun observeProfiles(): Flow<List<ProfileWithRules>>

    @Transaction
    @Query("SELECT * FROM filtering_profiles ORDER BY name COLLATE NOCASE, id")
    suspend fun getProfiles(): List<ProfileWithRules>

    @Insert
    suspend fun insertProfile(profile: FilteringProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: FilteringProfileEntity)

    @Query("SELECT * FROM filtering_profiles WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): FilteringProfileEntity?

    @Insert
    suspend fun insertLinks(links: List<ProfileRuleEntity>)

    @Query("DELETE FROM profile_rules WHERE profile_id = :profileId")
    suspend fun deleteLinks(profileId: Long)

    @Query("DELETE FROM filtering_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM filtering_profiles")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM filtering_profiles")
    suspend fun countAll(): Int

    @Query(
        """
        SELECT COUNT(*) FROM filtering_profiles
        WHERE name = :name COLLATE NOCASE AND id != :excludingId
        """,
    )
    suspend fun countByNameExcluding(
        name: String,
        excludingId: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM profile_rules WHERE rule_id = :ruleId")
    suspend fun countUsingRule(ruleId: Long): Int

    @Transaction
    suspend fun store(
        profile: FilteringProfileEntity,
        ruleIds: Set<Long>,
    ): Long {
        require(countByNameExcluding(profile.name, profile.id) == 0) {
            "A profile with this name already exists"
        }
        val id =
            if (profile.id == 0L) {
                require(countAll() < MAX_SAVED_PROFILES) { "Profile limit reached" }
                insertProfile(profile)
            } else {
                val existing = requireNotNull(findById(profile.id)) { "Profile ${profile.id} does not exist" }
                updateProfile(profile.copy(createdAtMillis = existing.createdAtMillis))
                profile.id
            }
        deleteLinks(id)
        insertLinks(ruleIds.map { ProfileRuleEntity(id, it) })
        return id
    }
}
