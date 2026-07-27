package com.alarmcontrol.data.repository

import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.core.profile.MAX_PROFILE_NAME_CHARS
import com.alarmcontrol.core.profile.MAX_PROFILE_RULE_IDS
import com.alarmcontrol.core.profile.MAX_SAVED_PROFILES
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRepositoryImplTest {
    private val dao = FakeProfileDao()
    private val repository = ProfileRepositoryImpl(dao)

    @Test
    fun `saving and editing a profile preserves its id and replaces membership`() =
        runTest {
            val id = repository.save(FilteringProfile(name = "Focus", ruleIds = setOf("1", "2")))

            repository.save(FilteringProfile(id = id, name = "Deep focus", ruleIds = setOf("2", "3")))

            assertEquals(
                FilteringProfile(id = id, name = "Deep focus", ruleIds = setOf("2", "3")),
                repository.observeProfiles().first().single(),
            )
        }

    @Test
    fun `deleting a profile leaves no profile row`() =
        runTest {
            val id = repository.save(FilteringProfile(name = "Sleep", ruleIds = emptySet()))

            repository.delete(id)

            assertTrue(repository.observeProfiles().first().isEmpty())
        }

    @Test
    fun `saving rejects a profile name that portable backup cannot represent`() =
        runTest {
            val result =
                runCatching {
                    repository.save(
                        FilteringProfile(name = "x".repeat(MAX_PROFILE_NAME_CHARS + 1), ruleIds = emptySet()),
                    )
                }

            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }

    @Test
    fun `saving rejects duplicate profile names ignoring case`() =
        runTest {
            repository.save(FilteringProfile(name = "Focus", ruleIds = emptySet()))

            val result =
                runCatching {
                    repository.save(FilteringProfile(name = " focus ", ruleIds = emptySet()))
                }

            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertEquals(1, repository.observeProfiles().first().size)
        }

    @Test
    fun `countUsingRule reports affected profiles before rule deletion`() =
        runTest {
            repository.save(FilteringProfile(name = "Focus", ruleIds = setOf("1", "2")))
            repository.save(FilteringProfile(name = "Sleep", ruleIds = setOf("2")))

            assertEquals(1, repository.countUsingRule("1"))
            assertEquals(2, repository.countUsingRule("2"))
            assertEquals(0, repository.countUsingRule("invalid"))
        }

    @Test
    fun `saving enforces profile and membership storage bounds`() =
        runTest {
            dao.countOverride = MAX_SAVED_PROFILES
            assertTrue(
                runCatching {
                    repository.save(FilteringProfile(name = "Overflow", ruleIds = emptySet()))
                }.exceptionOrNull() is IllegalArgumentException,
            )

            dao.countOverride = 0
            val tooManyRules = (1..MAX_PROFILE_RULE_IDS + 1).map(Int::toString).toSet()
            assertTrue(
                runCatching {
                    repository.save(FilteringProfile(name = "Large", ruleIds = tooManyRules))
                }.exceptionOrNull() is IllegalArgumentException,
            )
        }
}
