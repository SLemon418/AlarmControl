package com.alarmcontrol.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.alarmcontrol.core.automation.AutomationAuditRepository
import com.alarmcontrol.core.automation.AutomationOperation
import com.alarmcontrol.core.automation.AutomationOutcome
import com.alarmcontrol.core.automation.AutomationSource
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.core.profile.ProfileRepository
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.data.testing.DeviceValidationDataAccess
import com.alarmcontrol.service.DeviceValidationEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real exported receiver, Hilt graph, DataStore token gate, Room repositories, and
 * serialized [ProfileController] path without exposing the per-install token in host-side commands.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AutomationReceiverInstrumentedTest {
    private lateinit var targetContext: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var ruleRepository: RuleRepository
    private lateinit var profileRepository: ProfileRepository
    private lateinit var auditRepository: AutomationAuditRepository
    private lateinit var validationDataAccess: DeviceValidationDataAccess
    private var originalExternalAutomationEnabled = false
    private var originalAutomationToken = ""
    private var validationAutomationToken = ""
    private var initialAuditMaxId = 0L
    private var ruleId: String? = null
    private var profileId: String? = null

    @Before
    fun setUp() =
        runBlocking {
            targetContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val entryPoint =
                EntryPointAccessors.fromApplication(
                    targetContext,
                    DeviceValidationEntryPoint::class.java,
                )
            settingsRepository = entryPoint.settingsRepository()
            ruleRepository = entryPoint.ruleRepository()
            profileRepository = entryPoint.profileRepository()
            auditRepository = entryPoint.automationAuditRepository()
            validationDataAccess = entryPoint.deviceValidationDataAccess()
            initialAuditMaxId = validationDataAccess.latestAutomationAuditId()
            originalExternalAutomationEnabled = settingsRepository.externalAutomationEnabled.first()
            originalAutomationToken = settingsRepository.externalAutomationToken.first()

            ruleId =
                ruleRepository.saveRule(
                    Rule(
                        id = "",
                        name = "Device validation automation rule",
                        condition = Condition.PackageEquals("com.alarmcontrol.device.validation"),
                        action = RuleAction.Keep,
                    ),
                )
            profileId =
                profileRepository.save(
                    FilteringProfile(
                        name = "Device validation automation profile",
                        ruleIds = setOf(requireNotNull(ruleId)),
                    ),
                )
            settingsRepository.setExternalAutomationEnabled(true)
            validationAutomationToken = settingsRepository.externalAutomationToken.first()
        }

    @After
    fun tearDown() =
        runBlocking {
            val failures = mutableListOf<Throwable>()

            suspend fun attempt(block: suspend () -> Unit) {
                runCatching { block() }.onFailure(failures::add)
            }

            attempt { profileId?.let { profileRepository.delete(it) } }
            attempt { ruleId?.let { ruleRepository.deleteRule(it) } }
            attempt { settingsRepository.setExternalAutomationEnabled(originalExternalAutomationEnabled) }
            attempt {
                validationDataAccess.restoreAutomationTokenIfUnchanged(
                    originalToken = originalAutomationToken,
                    validationToken = validationAutomationToken,
                )
            }
            attempt {
                validationDataAccess.deleteAutomationAuditsAfter(initialAuditMaxId)
            }
            if (failures.isNotEmpty()) {
                throw AssertionError("Automation validation cleanup failed", failures.first()).also { aggregate ->
                    failures.drop(1).forEach(aggregate::addSuppressed)
                }
            }
        }

    @Test
    fun invalidTokenCannotToggleAndDoesNotThrottleTheNextAuthorizedRequest() =
        runBlocking {
            val token = validationAutomationToken
            val invalidToken = token.withDifferentFirstCharacter()
            val targetProfileId = requireNotNull(profileId)
            val targetRuleId = requireNotNull(ruleId)

            sendToggle(
                action = AutomationContract.ACTION_DISABLE_PROFILE,
                profileId = targetProfileId,
                token = invalidToken,
            )
            awaitAudit(AutomationOperation.DISABLE, AutomationOutcome.UNAUTHORIZED)
            assertTrue(ruleEnabled(targetRuleId))

            sendToggle(
                action = AutomationContract.ACTION_DISABLE_PROFILE,
                profileId = targetProfileId,
                token = token,
            )
            awaitRuleEnabled(targetRuleId, expected = false)
            awaitAudit(AutomationOperation.DISABLE, AutomationOutcome.APPLIED)
            assertFalse(ruleEnabled(targetRuleId))

            sendToggle(
                action = AutomationContract.ACTION_ENABLE_PROFILE,
                profileId = targetProfileId,
                token = token,
            )
            awaitRuleEnabled(targetRuleId, expected = true)
            awaitAudit(AutomationOperation.ENABLE, AutomationOutcome.APPLIED)
            assertTrue(ruleEnabled(targetRuleId))
        }

    private fun sendToggle(
        action: String,
        profileId: String,
        token: String,
    ) {
        targetContext.sendBroadcast(
            Intent(action)
                .setComponent(ComponentName(targetContext, ProfileToggleReceiver::class.java))
                .putExtra(AutomationContract.EXTRA_PROFILE_ID, profileId)
                .putExtra(AutomationContract.EXTRA_AUTH_TOKEN, token),
        )
    }

    private suspend fun awaitAudit(
        operation: AutomationOperation,
        outcome: AutomationOutcome,
    ) {
        withTimeout(RESULT_TIMEOUT_MILLIS) {
            auditRepository
                .observeRecent(AUDIT_LIMIT)
                .map { entries ->
                    entries.firstOrNull { entry ->
                        entry.source == AutomationSource.EXTERNAL &&
                            entry.operation == operation &&
                            entry.outcome == outcome &&
                            entry.id.toLongOrNull()?.let { it > initialAuditMaxId } == true
                    }
                }.filterNotNull()
                .first()
        }
    }

    private suspend fun awaitRuleEnabled(
        id: String,
        expected: Boolean,
    ) {
        withTimeout(RESULT_TIMEOUT_MILLIS) {
            ruleRepository.observeRules().first { rules ->
                rules.firstOrNull { it.id == id }?.enabled == expected
            }
        }
    }

    private suspend fun ruleEnabled(id: String): Boolean =
        ruleRepository
            .observeRules()
            .first()
            .first { it.id == id }
            .enabled

    private fun String.withDifferentFirstCharacter(): String {
        check(isNotEmpty())
        val replacement = if (first() == 'A') 'B' else 'A'
        return replacement + drop(1)
    }

    private companion object {
        const val AUDIT_LIMIT = 20
        const val RESULT_TIMEOUT_MILLIS = 10_000L
    }
}
