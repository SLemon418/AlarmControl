package com.alarmcontrol.core.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Local source of named rule groups used by UI, shortcuts, and external automation. */
interface ProfileRepository {
    /** Observes profiles alphabetically, including profiles whose rules were later deleted. */
    fun observeProfiles(): Flow<List<FilteringProfile>>

    /** Inserts or updates [profile] and returns its local id. */
    suspend fun save(profile: FilteringProfile): String

    /** Deletes one profile without deleting any member rule. */
    suspend fun delete(profileId: String)

    /** Number of saved profiles that currently reference [ruleId]. */
    suspend fun countUsingRule(ruleId: String): Int =
        observeProfiles().first().count { ruleId in it.ruleIds }
}
