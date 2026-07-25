package com.alarmcontrol.automation

import com.alarmcontrol.core.automation.AutomationAuditEntry
import com.alarmcontrol.core.automation.AutomationAuditRepository
import com.alarmcontrol.core.automation.AutomationOutcome
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.profile.FilteringProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ProfileControllerTest {
    private fun rule(
        id: String,
        name: String,
        enabled: Boolean,
    ) = Rule(
        id = id,
        name = name,
        enabled = enabled,
        priority = 0,
        condition = Condition.PackageEquals("com.example.$id"),
        action = RuleAction.Cancel,
    )

    @Test
    fun `omitting the profile id pauses filtering without changing rule states`() =
        runTest {
            val repo =
                FakeRuleRepository(
                    listOf(rule("1", "Work", enabled = true), rule("2", "Sleep", enabled = false)),
                )
            val settings = FakeSettingsRepository(filtering = true)
            val controller = ProfileController(repo, FakeProfileRepository(), settings)

            val changed = controller.setEnabled(profileId = null, enabled = false)

            assertEquals(1, changed)
            assertFalse(settings.isFilteringEnabled())
            assertEquals(listOf(true, false), repo.current().map { it.enabled })
            assertEquals(0, repo.bulkUpdateCount)
        }

    @Test
    fun `a profile id matches a rule by name case-insensitively`() =
        runTest {
            val repo =
                FakeRuleRepository(
                    listOf(rule("1", "Work", enabled = false), rule("2", "Sleep", enabled = false)),
                )
            val controller = ProfileController(repo, FakeProfileRepository(), FakeSettingsRepository())

            val changed = controller.setEnabled(profileId = "work", enabled = true)

            assertEquals(1, changed)
            assertTrue(repo.current().single { it.name == "Work" }.enabled)
            assertFalse(repo.current().single { it.name == "Sleep" }.enabled)
        }

    @Test
    fun `a duplicate rule name is rejected as ambiguous`() =
        runTest {
            val repo =
                FakeRuleRepository(
                    listOf(rule("1", "Work", enabled = false), rule("2", "work", enabled = false)),
                )
            val audit = RecordingAutomationAuditRepository()
            val controller =
                ProfileController(
                    repo,
                    FakeProfileRepository(),
                    FakeSettingsRepository(),
                    audit,
                )

            assertEquals(0, controller.setEnabled(profileId = "WORK", enabled = true))
            assertFalse(repo.current().any { it.enabled })
            assertEquals(AutomationOutcome.INVALID, audit.entries.single().outcome)
        }

    @Test
    fun `a profile id also matches a rule by id`() =
        runTest {
            val repo = FakeRuleRepository(listOf(rule("42", "Work", enabled = true)))
            val controller = ProfileController(repo, FakeProfileRepository(), FakeSettingsRepository())

            assertEquals(1, controller.setEnabled(profileId = "42", enabled = false))
            assertFalse(repo.current().single().enabled)
        }

    @Test
    fun `an exact id takes precedence over a duplicate numeric name`() =
        runTest {
            val repo =
                FakeRuleRepository(
                    listOf(rule("42", "Work", enabled = true), rule("7", "42", enabled = true)),
                )
            val controller = ProfileController(repo, FakeProfileRepository(), FakeSettingsRepository())

            assertEquals(1, controller.setEnabled(profileId = "42", enabled = false))
            assertFalse(repo.current().single { it.id == "42" }.enabled)
            assertTrue(repo.current().single { it.id == "7" }.enabled)
        }

    @Test
    fun `rules already in the desired state are left untouched`() =
        runTest {
            val repo = FakeRuleRepository(listOf(rule("1", "Work", enabled = false)))
            val controller = ProfileController(repo, FakeProfileRepository(), FakeSettingsRepository())

            val changed = controller.setEnabled(profileId = null, enabled = true)

            assertEquals(0, changed)
            assertEquals(0, repo.saveCount)
            assertEquals(0, repo.bulkUpdateCount)
        }

    @Test
    fun `an unknown profile id changes nothing`() =
        runTest {
            val repo = FakeRuleRepository(listOf(rule("1", "Work", enabled = true)))
            val controller = ProfileController(repo, FakeProfileRepository(), FakeSettingsRepository())

            assertEquals(0, controller.setEnabled(profileId = "nope", enabled = false))
            assertTrue(repo.current().single().enabled)
        }

    @Test
    fun `external automation is ignored when the opt-in is off`() =
        runTest {
            val repo = FakeRuleRepository(listOf(rule("1", "Work", enabled = true)))
            val settings = FakeSettingsRepository(enabled = false, filtering = true)
            val controller = ProfileController(repo, FakeProfileRepository(), settings)

            val changed =
                controller.setEnabledFromExternalAutomation(profileId = null, enabled = false, token = "test-token")

            assertEquals(0, changed)
            assertEquals(0, repo.saveCount)
            assertTrue(settings.isFilteringEnabled())
            assertTrue(repo.current().single().enabled)
        }

    @Test
    fun `external automation applies when the opt-in is on`() =
        runTest {
            val repo = FakeRuleRepository(listOf(rule("1", "Work", enabled = true)))
            val settings = FakeSettingsRepository(enabled = true, filtering = true)
            val controller = ProfileController(repo, FakeProfileRepository(), settings)

            val changed =
                controller.setEnabledFromExternalAutomation(profileId = null, enabled = false, token = "test-token")

            assertEquals(1, changed)
            assertFalse(settings.isFilteringEnabled())
            assertTrue(repo.current().single().enabled)
        }

    @Test
    fun `external automation rejects a wrong token and records a content-free audit outcome`() =
        runTest {
            val repo = FakeRuleRepository(listOf(rule("1", "Work", enabled = true)))
            val settings = FakeSettingsRepository(enabled = true, filtering = true)
            val audit = RecordingAutomationAuditRepository()
            val controller =
                ProfileController(
                    repo,
                    FakeProfileRepository(),
                    settings,
                    audit,
                    Clock.fixed(Instant.ofEpochMilli(123), ZoneOffset.UTC),
                )

            val changed =
                controller.setEnabledFromExternalAutomation(
                    profileId = "private-profile-name",
                    enabled = false,
                    token = "wrong",
                )

            assertEquals(0, changed)
            assertEquals(AutomationOutcome.UNAUTHORIZED, audit.entries.single().outcome)
            assertEquals(123L, audit.entries.single().requestedAtMillis)
            assertTrue(settings.isFilteringEnabled())
        }

    @Test
    fun `external automation throttles a broadcast storm`() =
        runTest {
            val settings = FakeSettingsRepository(enabled = true, filtering = true)
            val audit = RecordingAutomationAuditRepository()
            val controller =
                ProfileController(
                    FakeRuleRepository(emptyList()),
                    FakeProfileRepository(),
                    settings,
                    audit,
                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                )

            repeat(13) {
                controller.setEnabledFromExternalAutomation(null, enabled = false, token = "test-token")
            }

            assertEquals(AutomationOutcome.THROTTLED, audit.entries.last().outcome)
            assertEquals(13, audit.entries.size)
        }

    @Test
    fun `unauthorized requests do not consume the valid request rate limit`() =
        runTest {
            val settings = FakeSettingsRepository(enabled = true, filtering = true)
            val audit = RecordingAutomationAuditRepository()
            val controller =
                ProfileController(
                    FakeRuleRepository(emptyList()),
                    FakeProfileRepository(),
                    settings,
                    audit,
                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                )

            repeat(20) {
                controller.setEnabledFromExternalAutomation(null, enabled = false, token = "wrong")
            }
            val changed =
                controller.setEnabledFromExternalAutomation(null, enabled = false, token = "test-token")

            assertEquals(1, changed)
            assertFalse(settings.isFilteringEnabled())
            assertEquals(AutomationOutcome.APPLIED, audit.entries.last().outcome)
        }

    @Test
    fun `audit failure does not reverse an applied automation operation`() =
        runTest {
            val settings = FakeSettingsRepository(enabled = true, filtering = true)
            val controller =
                ProfileController(
                    FakeRuleRepository(emptyList()),
                    FakeProfileRepository(),
                    settings,
                    ThrowingAutomationAuditRepository,
                )

            val changed =
                controller.setEnabledFromExternalAutomation(null, enabled = false, token = "test-token")

            assertEquals(1, changed)
            assertFalse(settings.isFilteringEnabled())
        }

    @Test
    fun `a stored profile toggles all member rules before legacy rule lookup`() =
        runTest {
            val repo =
                FakeRuleRepository(
                    listOf(
                        rule("1", "First", enabled = false),
                        rule("2", "Second", enabled = false),
                        rule("3", "Focus", enabled = false),
                    ),
                )
            val profiles =
                FakeProfileRepository(
                    listOf(FilteringProfile(id = "10", name = "Focus", ruleIds = setOf("1", "2"))),
                )
            val controller = ProfileController(repo, profiles, FakeSettingsRepository())

            assertEquals(2, controller.setEnabled(profileId = "focus", enabled = true))
            assertEquals(listOf(true, true, false), repo.current().map { it.enabled })
        }

    @Test
    fun `duplicate legacy profile names are rejected instead of merged`() =
        runTest {
            val repo =
                FakeRuleRepository(
                    listOf(rule("1", "First", enabled = false), rule("2", "Second", enabled = false)),
                )
            val profiles =
                FakeProfileRepository(
                    listOf(
                        FilteringProfile(id = "10", name = "Focus", ruleIds = setOf("1")),
                        FilteringProfile(id = "11", name = "focus", ruleIds = setOf("2")),
                    ),
                )
            val audit = RecordingAutomationAuditRepository()
            val controller =
                ProfileController(repo, profiles, FakeSettingsRepository(), audit)

            assertEquals(0, controller.setEnabled(profileId = "FOCUS", enabled = true))
            assertTrue(repo.current().none { it.enabled })
            assertEquals(AutomationOutcome.INVALID, audit.entries.single().outcome)
        }

    @Test
    fun `toggle enables a partially disabled profile then disables a fully enabled profile`() =
        runTest {
            val repo =
                FakeRuleRepository(
                    listOf(rule("1", "First", enabled = true), rule("2", "Second", enabled = false)),
                )
            val profiles =
                FakeProfileRepository(
                    listOf(FilteringProfile(id = "10", name = "Focus", ruleIds = setOf("1", "2"))),
                )
            val controller = ProfileController(repo, profiles, FakeSettingsRepository())

            assertEquals(1, controller.toggle("10"))
            assertTrue(repo.current().all { it.enabled })
            assertEquals(2, controller.toggle("10"))
            assertTrue(repo.current().none { it.enabled })
        }
}

private class RecordingAutomationAuditRepository : AutomationAuditRepository {
    val entries = mutableListOf<AutomationAuditEntry>()

    override suspend fun record(entry: AutomationAuditEntry) {
        entries += entry
    }

    override fun observeRecent(limit: Int): Flow<List<AutomationAuditEntry>> =
        flowOf(entries.takeLast(limit).reversed())
}

private data object ThrowingAutomationAuditRepository : AutomationAuditRepository {
    override suspend fun record(entry: AutomationAuditEntry) {
        error("audit unavailable")
    }

    override fun observeRecent(limit: Int): Flow<List<AutomationAuditEntry>> = flowOf(emptyList())
}
