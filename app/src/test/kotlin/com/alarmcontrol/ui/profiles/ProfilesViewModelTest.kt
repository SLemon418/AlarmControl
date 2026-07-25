package com.alarmcontrol.ui.profiles

import app.cash.turbine.test
import com.alarmcontrol.automation.ProfileController
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.core.profile.MAX_PROFILE_NAME_CHARS
import com.alarmcontrol.core.profile.ProfileRepository
import com.alarmcontrol.testsupport.MainDispatcherRule
import com.alarmcontrol.testsupport.awaitUntil
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule as JUnitRule

class ProfilesViewModelTest {
    @get:JUnitRule
    val mainDispatcherRule = MainDispatcherRule()

    private val profiles = MutableStateFlow<List<FilteringProfile>>(emptyList())
    private val rules = MutableStateFlow<List<Rule>>(emptyList())
    private val profileRepository = mockk<ProfileRepository>(relaxed = true)
    private val ruleRepository = mockk<RuleRepository>(relaxed = true)
    private val controller = mockk<ProfileController>(relaxed = true)

    private fun viewModel(): ProfilesViewModel {
        every { profileRepository.observeProfiles() } returns profiles
        every { ruleRepository.observeRules() } returns rules
        return ProfilesViewModel(profileRepository, ruleRepository, controller, mainDispatcherRule.dispatcher)
    }

    @Test
    fun `editor rejects an overlong profile name`() {
        val state =
            ProfileEditorState(
                name = "x".repeat(MAX_PROFILE_NAME_CHARS + 1),
                selectedRuleIds = setOf("1"),
            )

        assertFalse(state.canSave)
    }

    @Test
    fun `maps profile activation reactively from current member rule states`() =
        runTest {
            profiles.value = listOf(FilteringProfile(id = "10", name = "Focus", ruleIds = setOf("1", "2")))
            rules.value = listOf(rule("1", true), rule("2", false))
            val vm = viewModel()

            vm.uiState.test {
                val partial = awaitUntil { !it.isLoading }.profiles.single()
                assertTrue(partial.isPartial)

                rules.value = rules.value.map { it.copy(enabled = true) }
                assertTrue(awaitUntil { it.profiles.singleOrNull()?.isActive == true }.profiles.single().isActive)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `saving an editor delegates a trimmed profile and closes the dialog`() =
        runTest {
            rules.value = listOf(rule("1", true))
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.onAddProfile()
                awaitUntil { it.editor != null }
                vm.onEditorChange(ProfileEditorState(name = "  Focus  ", selectedRuleIds = setOf("1")))
                vm.onSaveProfile()
                awaitUntil { it.editor == null }
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                profileRepository.save(FilteringProfile(name = "Focus", ruleIds = setOf("1")))
            }
        }

    @Test
    fun `profile toggle delegates to the shared automation controller`() =
        runTest {
            val vm = viewModel()
            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.onToggleProfile("7")
                cancelAndIgnoreRemainingEvents()
            }

            coVerify { controller.toggle("7") }
        }

    @Test
    fun `profile deletion waits for explicit confirmation`() =
        runTest {
            profiles.value = listOf(FilteringProfile(id = "7", name = "Focus", ruleIds = setOf("1")))
            rules.value = listOf(rule("1", true))
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.onRequestDeleteProfile("7")
                assertTrue(awaitUntil { it.pendingDelete?.id == "7" }.pendingDelete != null)
                coVerify(exactly = 0) { profileRepository.delete(any()) }

                vm.onConfirmDeleteProfile()
                assertNull(awaitUntil { it.pendingDelete == null }.pendingDelete)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { profileRepository.delete("7") }
        }

    private fun rule(
        id: String,
        enabled: Boolean,
    ) = Rule(
        id = id,
        name = "Rule $id",
        enabled = enabled,
        condition = Condition.PackageEquals("com.example.$id"),
        action = RuleAction.Cancel,
    )
}
