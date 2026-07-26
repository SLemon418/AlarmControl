package com.alarmcontrol.data.repository

import com.alarmcontrol.data.db.dao.ProfileDao
import com.alarmcontrol.data.db.entity.FilteringProfileEntity
import com.alarmcontrol.data.db.entity.ProfileRuleEntity
import com.alarmcontrol.data.db.relation.ProfileWithRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [ProfileDao] matching Room ordering and cascade behaviour for JVM repository tests. */
class FakeProfileDao : ProfileDao {
    private val profiles = mutableListOf<FilteringProfileEntity>()
    private val links = mutableListOf<ProfileRuleEntity>()
    private val state = MutableStateFlow<List<ProfileWithRules>>(emptyList())
    private var nextId = 1L

    private fun refresh() {
        state.value =
            profiles
                .sortedWith(compareBy<FilteringProfileEntity>({ it.name.lowercase() }, { it.id }))
                .map { profile -> ProfileWithRules(profile, links.filter { it.profileId == profile.id }) }
    }

    override fun observeProfiles(): Flow<List<ProfileWithRules>> = state

    override suspend fun getProfiles(): List<ProfileWithRules> = state.value

    override suspend fun insertProfile(profile: FilteringProfileEntity): Long {
        val id = if (profile.id == 0L) nextId++ else profile.id.also { nextId = maxOf(nextId, it + 1) }
        profiles += profile.copy(id = id)
        refresh()
        return id
    }

    override suspend fun updateProfile(profile: FilteringProfileEntity) {
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) profiles[index] = profile
        refresh()
    }

    override suspend fun findById(id: Long): FilteringProfileEntity? = profiles.firstOrNull { it.id == id }

    override suspend fun insertLinks(links: List<ProfileRuleEntity>) {
        this.links += links
        refresh()
    }

    override suspend fun deleteLinks(profileId: Long) {
        links.removeAll { it.profileId == profileId }
        refresh()
    }

    override suspend fun deleteById(id: Long) {
        profiles.removeAll { it.id == id }
        links.removeAll { it.profileId == id }
        refresh()
    }

    override suspend fun deleteAll(): Int {
        val count = profiles.size
        profiles.clear()
        links.clear()
        refresh()
        return count
    }

    override suspend fun countAll(): Int = profiles.size

    override suspend fun countByNameExcluding(
        name: String,
        excludingId: Long,
    ): Int = profiles.count { it.id != excludingId && it.name.equals(name, ignoreCase = true) }

    override suspend fun countUsingRule(ruleId: Long): Int = links.count { it.ruleId == ruleId }
}
