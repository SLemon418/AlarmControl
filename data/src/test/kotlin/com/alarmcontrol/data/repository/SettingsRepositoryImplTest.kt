package com.alarmcontrol.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.alarmcontrol.core.settings.SettingsSnapshot
import com.alarmcontrol.data.security.NotificationContentAccessGuard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SettingsRepositoryImplTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun repository(): SettingsRepositoryImpl {
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempFolder.root, "settings.preferences_pb") },
            )
        return SettingsRepositoryImpl(dataStore, NotificationContentAccessGuard())
    }

    @Test
    fun `external automation defaults to false`() =
        runTest {
            assertFalse(repository().externalAutomationEnabled.first())
        }

    @Test
    fun `setting external automation persists`() =
        runTest {
            val repository = repository()
            repository.setExternalAutomationEnabled(true)
            assertTrue(repository.externalAutomationEnabled.first())
        }

    @Test
    fun `enabling automation creates a per-install token and rotation invalidates it`() =
        runTest {
            val repository = repository()

            repository.setExternalAutomationEnabled(true)
            val first = repository.externalAutomationToken.first()
            val rotated = repository.rotateExternalAutomationToken()

            assertTrue(first.length >= 40)
            assertTrue(rotated.length >= 40)
            assertTrue(first != rotated)
            assertEquals(rotated, repository.externalAutomationToken.first())
        }

    @Test
    fun `malformed persisted automation token is hidden and regenerated`() =
        runTest {
            val dataStore =
                PreferenceDataStoreFactory.create {
                    File(tempFolder.root, "invalid-token.preferences_pb")
                }
            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("external_automation_token")] = "not a valid token"
            }
            val repository = SettingsRepositoryImpl(dataStore, NotificationContentAccessGuard())

            assertEquals("", repository.externalAutomationToken.first())

            val regenerated = repository.ensureExternalAutomationToken()

            assertEquals(43, regenerated.length)
            assertEquals(regenerated, repository.externalAutomationToken.first())
        }

    @Test
    fun `restoring portable settings never overwrites the local automation token`() =
        runTest {
            val repository = repository()
            repository.setExternalAutomationEnabled(true)
            val token = repository.externalAutomationToken.first()

            repository.restore(SettingsSnapshot(externalAutomationEnabled = true))

            assertEquals(token, repository.externalAutomationToken.first())
        }

    @Test
    fun `restoring enabled automation creates the omitted per-install token`() =
        runTest {
            val repository = repository()

            repository.restore(SettingsSnapshot(externalAutomationEnabled = true))

            assertTrue(repository.externalAutomationEnabled.first())
            assertEquals(43, repository.externalAutomationToken.first().length)
        }

    @Test
    fun `filtering defaults to true`() =
        runTest {
            assertTrue(repository().filteringEnabled.first())
        }

    @Test
    fun `DataStore read failure pauses filtering instead of using the fresh install default`() =
        runTest {
            val failingStore =
                object : DataStore<Preferences> {
                    override val data = flow<Preferences> { throw IOException("unreadable") }

                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                        throw IOException("unwritable")
                }
            val repository = SettingsRepositoryImpl(failingStore, NotificationContentAccessGuard())

            assertFalse(repository.filteringEnabled.first())
            assertFalse(repository.snapshot().filteringEnabled)
        }

    @Test
    fun `dynamic color is device local opt in and resettable`() =
        runTest {
            val repository = repository()
            assertFalse(repository.dynamicColorEnabled.first())

            repository.setDynamicColorEnabled(true)
            assertTrue(repository.dynamicColorEnabled.first())

            repository.restore(SettingsSnapshot(filteringEnabled = false))
            assertTrue(repository.dynamicColorEnabled.first())

            repository.reset()
            assertFalse(repository.dynamicColorEnabled.first())
        }

    @Test
    fun `notification content history is opt in device local and resettable`() =
        runTest {
            val repository = repository()

            assertFalse(repository.notificationContentStorageEnabled.first())
            assertEquals(emptySet<String>(), repository.contentExcludedPackages.first())

            repository.setNotificationContentStorageEnabled(true)
            repository.setContentExcludedPackages(setOf("com.bank", "com.password"))

            assertTrue(repository.notificationContentStorageEnabled.first())
            assertEquals(setOf("com.bank", "com.password"), repository.contentExcludedPackages.first())
            assertFalse(
                repository
                    .snapshot()
                    .let { snapshot -> snapshot.toString().contains("com.bank") },
            )

            repository.reset()
            assertFalse(repository.notificationContentStorageEnabled.first())
            assertEquals(emptySet<String>(), repository.contentExcludedPackages.first())
        }

    @Test
    fun `content exclusion rejects blank or excessive package sets`() =
        runTest {
            val repository = repository()

            assertTrue(
                runCatching { repository.setContentExcludedPackages(setOf("")) }.exceptionOrNull() is
                    IllegalArgumentException,
            )
            assertTrue(
                runCatching {
                    repository.setContentExcludedPackages((0..200).map { "com.example.$it" }.toSet())
                }.exceptionOrNull() is IllegalArgumentException,
            )
        }

    @Test
    fun `filtering can be paused without changing automation opt in`() =
        runTest {
            val repository = repository()
            repository.setFilteringEnabled(false)

            assertFalse(repository.filteringEnabled.first())
            assertFalse(repository.externalAutomationEnabled.first())
        }

    @Test
    fun `LLM analysis requires explicit opt in`() =
        runTest {
            val repository = repository()
            assertFalse(repository.llmAnalysisEnabled.first())

            repository.setLlmAnalysisEnabled(true)

            assertTrue(repository.llmAnalysisEnabled.first())
        }

    @Test
    fun `LLM automatic actions are separately opted in and default off`() =
        runTest {
            val repository = repository()
            repository.setLlmAnalysisEnabled(true)
            assertFalse(repository.llmAutoActionsEnabled.first())

            repository.setLlmAutoActionsEnabled(true)

            assertTrue(repository.llmAutoActionsEnabled.first())
        }

    @Test
    fun `disabling LLM analysis also revokes automatic actions`() =
        runTest {
            val repository = repository()
            repository.setLlmAnalysisEnabled(true)
            repository.setLlmAutoActionsEnabled(true)

            repository.setLlmAnalysisEnabled(false)

            assertFalse(repository.llmAnalysisEnabled.first())
            assertFalse(repository.llmAutoActionsEnabled.first())
            assertTrue(runCatching { repository.setLlmAutoActionsEnabled(true) }.isFailure)
        }

    @Test
    fun `retention defaults can be changed and reset`() =
        runTest {
            val repository = repository()
            assertEquals(30, repository.eventRetentionDays.first())
            assertEquals(365, repository.dailyInsightRetentionDays.first())

            repository.setEventRetentionDays(90)
            repository.setDailyInsightRetentionDays(730)
            assertEquals(90, repository.eventRetentionDays.first())
            assertEquals(730, repository.dailyInsightRetentionDays.first())

            repository.reset()
            assertEquals(30, repository.eventRetentionDays.first())
            assertEquals(365, repository.dailyInsightRetentionDays.first())
            assertFalse(repository.filteringEnabled.first())
        }

    @Test
    fun `reset keeps filtering paused even though a fresh install defaults to enabled`() =
        runTest {
            val repository = repository()
            assertTrue(repository.filteringEnabled.first())

            repository.reset()

            assertFalse(repository.filteringEnabled.first())
        }

    @Test
    fun `invalid persisted retention values degrade to safe defaults`() =
        runTest {
            val dataStore =
                PreferenceDataStoreFactory.create {
                    File(tempFolder.root, "invalid-retention.preferences_pb")
                }
            dataStore.edit { preferences ->
                preferences[intPreferencesKey("event_retention_days")] = 0
                preferences[intPreferencesKey("daily_insight_retention_days")] = 10_000
            }
            val repository = SettingsRepositoryImpl(dataStore, NotificationContentAccessGuard())

            assertEquals(30, repository.eventRetentionDays.first())
            assertEquals(365, repository.dailyInsightRetentionDays.first())
        }

    @Test
    fun `snapshot and restore round-trip all portable preferences atomically`() =
        runTest {
            val repository = repository()
            val expected =
                SettingsSnapshot(
                    filteringEnabled = false,
                    externalAutomationEnabled = true,
                    llmAnalysisEnabled = true,
                    llmAutoActionsEnabled = true,
                    eventRetentionDays = 90,
                    dailyInsightRetentionDays = 730,
                )

            repository.restore(expected)

            assertEquals(expected, repository.snapshot())
        }
}
