package com.alarmcontrol.automation

import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.core.profile.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeProfileRepository(
    initial: List<FilteringProfile> = emptyList(),
) : ProfileRepository {
    private val profiles = MutableStateFlow(initial)

    override fun observeProfiles(): Flow<List<FilteringProfile>> = profiles

    override suspend fun save(profile: FilteringProfile): String {
        val id = profile.id.ifBlank { (profiles.value.size + 1).toString() }
        val stored = profile.copy(id = id)
        profiles.value = profiles.value.filterNot { it.id == id } + stored
        return id
    }

    override suspend fun delete(profileId: String) {
        profiles.value = profiles.value.filterNot { it.id == profileId }
    }
}
