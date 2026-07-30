package com.alarmcontrol.data.repository

import com.alarmcontrol.core.privacy.LocalDataResetWriteFence
import com.alarmcontrol.core.privacy.StaleLocalDataWriteException
import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.core.profile.MAX_PROFILE_NAME_CHARS
import com.alarmcontrol.core.profile.MAX_PROFILE_RULE_IDS
import com.alarmcontrol.core.profile.ProfileRepository
import com.alarmcontrol.data.db.dao.ProfileDao
import com.alarmcontrol.data.mapper.toDomain
import com.alarmcontrol.data.mapper.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ProfileRepositoryImpl
    @Inject
    constructor(
        private val profileDao: ProfileDao,
        private val localDataResetWriteFence: LocalDataResetWriteFence = LocalDataResetWriteFence(),
    ) : ProfileRepository {
        override fun observeProfiles(): Flow<List<FilteringProfile>> =
            profileDao.observeProfiles().map { rows -> rows.map { it.toDomain() } }

        override suspend fun save(profile: FilteringProfile): String {
            val resetEpoch = localDataResetWriteFence.captureEpoch()
            val name = profile.name.trim()
            require(name.isNotBlank()) { "Profile name is required" }
            require(name.length <= MAX_PROFILE_NAME_CHARS) { "Profile name is too long" }
            val id =
                if (profile.id.isBlank()) {
                    0L
                } else {
                    requireNotNull(profile.id.toLongOrNull()?.takeIf { it > 0 }) { "Invalid profile id" }
                }
            require(profile.ruleIds.size <= MAX_PROFILE_RULE_IDS) { "Profile contains too many rules" }
            val ruleIds =
                profile.ruleIds.mapTo(mutableSetOf()) { ruleId ->
                    requireNotNull(ruleId.toLongOrNull()?.takeIf { it > 0 }) { "Invalid rule id" }
                }
            val now = System.currentTimeMillis()
            return localDataResetWriteFence
                .writeIfCurrent(resetEpoch) {
                    profileDao.store(profile.copy(name = name).toEntity(id, now), ruleIds).toString()
                } ?: throw StaleLocalDataWriteException()
        }

        override suspend fun delete(profileId: String) {
            val resetEpoch = localDataResetWriteFence.captureEpoch()
            profileId.toLongOrNull()?.let { id ->
                localDataResetWriteFence.writeIfCurrent(resetEpoch) {
                    profileDao.deleteById(id)
                    Unit
                } ?: throw StaleLocalDataWriteException()
            }
        }

        override suspend fun countUsingRule(ruleId: String): Int =
            ruleId.toLongOrNull()?.let { profileDao.countUsingRule(it) } ?: 0
    }
