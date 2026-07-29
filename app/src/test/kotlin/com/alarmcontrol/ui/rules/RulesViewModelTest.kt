package com.alarmcontrol.ui.rules

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.alarmcontrol.R
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.NotificationHistoryRepository
import com.alarmcontrol.core.filtering.NotificationSource
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.profile.ProfileRepository
import com.alarmcontrol.notifications.DefaultRuleAnalyzer
import com.alarmcontrol.notifications.Matcher
import com.alarmcontrol.service.AppHealthProvider
import com.alarmcontrol.service.AppHealthSnapshot
import com.alarmcontrol.testsupport.MainDispatcherRule
import com.alarmcontrol.testsupport.awaitUntil
import com.alarmcontrol.ui.app.AppIdentityResolver
import com.alarmcontrol.ui.app.AppIdentityUi
import com.alarmcontrol.ui.settings.FakeSettingsRepository
import com.alarmcontrol.ui.uiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class RulesViewModelTest {
    @get:org.junit.Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val ruleRepository = mockk<RuleRepository>(relaxed = true)
    private val profileRepository = mockk<ProfileRepository>(relaxed = true)
    private val rulesFlow = MutableStateFlow<List<Rule>>(emptyList())
    private val appHealthProvider = mockk<AppHealthProvider>()
    private val notificationHistoryRepository = mockk<NotificationHistoryRepository>()
    private val appIdentityResolver = mockk<AppIdentityResolver>()

    private val sampleRule =
        Rule(
            id = "1",
            name = "Mute promos",
            enabled = true,
            condition =
                Condition.AllOf(
                    listOf(Condition.PackageEquals("com.example.shop"), Condition.TextContains("sale")),
                ),
            action = RuleAction.Cancel,
        )

    private fun viewModel(
        automationEnabled: Boolean = false,
        sources: List<NotificationSource> = emptyList(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        workerDispatcher: CoroutineDispatcher = mainDispatcherRule.dispatcher,
    ): RulesViewModel {
        every { ruleRepository.observeRules() } returns rulesFlow
        coEvery { profileRepository.countUsingRule(any()) } returns 0
        every { appHealthProvider.snapshot() } returns
            AppHealthSnapshot(notificationAccessGranted = true, batteryOptimizationExempt = false)
        every { notificationHistoryRepository.observeSources(any()) } returns flowOf(sources)
        every { appIdentityResolver.resolve(any()) } answers { AppIdentityUi(firstArg(), null) }
        return RulesViewModel(
            ruleRepository,
            profileRepository,
            notificationHistoryRepository,
            FakeSettingsRepository(enabled = automationEnabled),
            Matcher(),
            DefaultRuleAnalyzer(),
            appHealthProvider,
            appIdentityResolver,
            savedStateHandle,
            workerDispatcher,
        )
    }

    @Test
    fun `maps repository rules into list items`() =
        runTest {
            rulesFlow.value = listOf(sampleRule)

            viewModel().uiState.test {
                val loaded = awaitUntil { !it.isLoading }
                assertEquals(listOf("Mute promos"), loaded.rules.map { it.name })
                assertEquals(uiText(R.string.action_cancel), loaded.rules.single().actionLabel)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `reflects repository updates reactively`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                assertTrue(awaitUntil { !it.isLoading }.rules.isEmpty())
                rulesFlow.value = listOf(sampleRule)
                val withRule = awaitUntil { it.rules.isNotEmpty() }
                assertEquals("Mute promos", withRule.rules.single().name)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `toggling a rule updates only its enabled flag`() =
        runTest {
            rulesFlow.value = listOf(sampleRule)
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.rules.isNotEmpty() }
                vm.onToggleRule("1", enabled = false)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify { ruleRepository.setRulesEnabled(setOf("1"), false) }
            coVerify(exactly = 0) { ruleRepository.saveRule(any()) }
        }

    @Test
    fun `editing a rule saves the entered priority while preserving enabled state`() =
        runTest {
            rulesFlow.value = listOf(sampleRule.copy(enabled = false, priority = 2))
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.rules.isNotEmpty() }
                vm.onEditRule("1")
                val editing = awaitUntil { it.editor != null }.editor!!
                vm.onEditorChange(editing.copy(priority = "9"))
                vm.onSaveRule()
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                ruleRepository.saveRule(
                    match { rule -> rule.id == "1" && rule.priority == 9 && !rule.enabled },
                )
            }
        }

    @Test
    fun `deleting a rule delegates to the repository`() =
        runTest {
            rulesFlow.value = listOf(sampleRule)
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.rules.isNotEmpty() }
                vm.onDeleteRule("1")
                awaitUntil { it.pendingDelete?.ruleId == "1" }
                vm.confirmDeleteRule()
                cancelAndIgnoreRemainingEvents()
            }

            coVerify { ruleRepository.deleteRule("1") }
        }

    @Test
    fun `late profile count cannot replace the latest rule deletion confirmation`() =
        runTest {
            val secondRule = sampleRule.copy(id = "2", name = "Keep banking")
            rulesFlow.value = listOf(sampleRule, secondRule)
            val vm = viewModel()
            var resumeFirst: ((Int) -> Unit)? = null
            coEvery { profileRepository.countUsingRule("1") } coAnswers {
                suspendCoroutine { continuation ->
                    resumeFirst = continuation::resume
                }
            }
            coEvery { profileRepository.countUsingRule("2") } returns 3

            vm.uiState.test {
                awaitUntil { it.rules.size == 2 }
                vm.onDeleteRule("1")
                vm.onDeleteRule("2")
                awaitUntil { it.pendingDelete?.ruleId == "2" }

                requireNotNull(resumeFirst).invoke(1)
                mainDispatcherRule.dispatcher.scheduler.runCurrent()
                assertEquals(
                    "2",
                    vm.uiState.value
                        .pendingDelete
                        ?.ruleId,
                )

                vm.confirmDeleteRule()
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { ruleRepository.deleteRule("2") }
            coVerify(exactly = 0) { ruleRepository.deleteRule("1") }
        }

    @Test
    fun `saving an empty editor surfaces a message and does not persist`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.onAddRule()
                awaitUntil { it.editor != null }
                vm.onSaveRule()
                val withMessage = awaitUntil { it.userMessage != null }
                assertEquals(uiText(R.string.message_rule_condition_required), withMessage.userMessage)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 0) { ruleRepository.saveRule(any()) }
        }

    @Test
    fun `saving a rule ignores repeated submissions until the first write completes`() =
        runTest {
            val releaseSave = CompletableDeferred<Unit>()
            coEvery { ruleRepository.saveRule(any()) } coAnswers {
                releaseSave.await()
                "42"
            }
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.onUseTemplate(RuleTemplate.KEEP_ALARMS)
                val draft = awaitUntil { it.editor != null }.editor!!
                vm.onEditorChange(draft.copy(name = "Keep alarms"))
                awaitUntil { it.editor?.canSave == true }
                vm.onSaveRule()
                val saving = awaitUntil { it.editor?.isSaving == true }.editor!!
                assertFalse(saving.canSave)

                vm.onSaveRule()
                releaseSave.complete(Unit)
                awaitUntil { it.editor == null }
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { ruleRepository.saveRule(any()) }
        }

    @Test
    fun `rule simulator evaluates the draft without persisting or applying an action`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.onAddRule()
                val editing = awaitUntil { it.editor != null }.editor!!
                vm.onEditorChange(
                    editing.copy(
                        editorMode = RuleEditorMode.ADVANCED,
                        root = Condition.PackageEquals("com.example.shop").toEditableRoot(),
                        simulation =
                            editing.simulation.copy(
                                expanded = true,
                                packageName = "com.example.shop",
                            ),
                    ),
                )
                vm.onRunSimulation()
                val result = awaitUntil { it.editor?.simulation?.result != null }.editor!!.simulation.result
                assertEquals(
                    uiText(R.string.simulator_matched, uiText(R.string.action_cancel)),
                    result,
                )
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 0) { ruleRepository.saveRule(any()) }
        }

    @Test
    fun `new guided rule exposes observed channel and starts destructive action in monitor mode`() =
        runTest {
            val vm =
                viewModel(
                    sources =
                        listOf(
                            NotificationSource(
                                packageName = "com.example.shop",
                                channelId = "offers",
                                channelName = "Offers",
                                eventCount = 12,
                                lastSeenMillis = 100,
                            ),
                        ),
                )

            vm.uiState.test {
                val loaded = awaitUntil { !it.isLoading && it.availableSources.isNotEmpty() }
                assertEquals("Offers", loaded.availableSources.single().channelName)
                vm.onAddRule()
                val editor = awaitUntil { it.editor != null }.editor!!
                assertEquals(RuleEditorMode.GUIDED, editor.editorMode)
                assertEquals(RuleExecutionMode.MONITOR, editor.executionMode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `activity shortcut opens a package and category prefilled draft without saving`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.onCreateRuleFromActivity(QuickRuleDraft("com.example.shop", "promotion"))
                val draft = awaitUntil { it.editor != null }.editor!!
                val condition = requireNotNull(draft.root.toConditionOrNull())
                assertEquals(
                    Condition.AllOf(
                        listOf(
                            Condition.PackageEquals("com.example.shop"),
                            Condition.CategoryEquals("promotion"),
                        ),
                    ),
                    condition,
                )
                assertEquals("com.example.shop", draft.simulation.packageName)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 0) { ruleRepository.saveRule(any()) }
        }

    @Test
    fun `activity shortcut completion waits until its editor is published`() =
        runTest {
            val workerDispatcher = StandardTestDispatcher(testScheduler)
            val vm = viewModel(workerDispatcher = workerDispatcher)

            vm.uiState.test {
                assertEquals(null, awaitItem().editor)
                val completion =
                    vm.onCreateRuleFromActivity(QuickRuleDraft("com.example.shop", "promotion"))

                assertFalse(completion.isCompleted)
                workerDispatcher.scheduler.runCurrent()
                assertTrue(completion.await())
                assertEquals(
                    "com.example.shop",
                    awaitUntil { it.editor != null }.editor?.guidedPackageName,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `late activity shortcut resolution cannot replace a newer editor draft`() =
        runTest {
            val workerDispatcher = StandardTestDispatcher(testScheduler)
            val vm = viewModel(workerDispatcher = workerDispatcher)

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                val completion =
                    vm.onCreateRuleFromActivity(QuickRuleDraft("com.example.shop", "promotion"))
                vm.onUseTemplate(RuleTemplate.KEEP_ALARMS)
                val draft = awaitUntil { it.editor != null }.editor!!
                workerDispatcher.scheduler.runCurrent()

                assertFalse(completion.await())
                assertEquals(EditorAction.KEEP, draft.action)
                assertEquals(
                    Condition.AllOf(listOf(Condition.CategoryEquals("alarm"))),
                    vm.uiState.value.editor
                        ?.root
                        ?.toConditionOrNull(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `template opens an unsaved reviewable editor`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.onUseTemplate(RuleTemplate.KEEP_ALARMS)
                val draft = awaitUntil { it.editor != null }.editor!!
                assertEquals(EditorAction.KEEP, draft.action)
                assertEquals("100", draft.priority)
                assertEquals(Condition.AllOf(listOf(Condition.CategoryEquals("alarm"))), draft.root.toConditionOrNull())
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 0) { ruleRepository.saveRule(any()) }
        }

    @Test
    fun `advertisement template is an unsaved monitor cancel draft`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.onUseTemplate(RuleTemplate.OBSERVE_ADS)
                val draft = awaitUntil { it.editor != null }.editor!!
                assertEquals(RuleExecutionMode.MONITOR, draft.executionMode)
                assertEquals(EditorAction.CANCEL, draft.action)
                assertEquals(Condition.AllOf(listOf(Condition.IsAdvertisement(true))), draft.root.toConditionOrNull())
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 0) { ruleRepository.saveRule(any()) }
        }

    @Test
    fun `protection template saturates priority and never auto saves`() =
        runTest {
            rulesFlow.value = listOf(sampleRule.copy(priority = Int.MAX_VALUE - 50))
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.rules.isNotEmpty() }
                vm.onUseTemplate(RuleTemplate.KEEP_HIGH_IMPORTANCE)
                val draft = awaitUntil { it.editor != null }.editor!!
                assertEquals(Int.MAX_VALUE.toString(), draft.priority)
                assertEquals(EditorAction.KEEP, draft.action)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 0) { ruleRepository.saveRule(any()) }
        }

    @Test
    fun `channel keep shortcut creates a package and channel protection draft`() =
        runTest {
            rulesFlow.value = listOf(sampleRule.copy(priority = 7))
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.rules.isNotEmpty() }
                vm.onCreateRuleFromActivity(
                    QuickRuleDraft(
                        packageName = "com.example.shop",
                        category = "promotion",
                        channelId = "offers",
                        keep = true,
                    ),
                )
                val draft = awaitUntil { it.editor != null }.editor!!
                assertEquals(EditorAction.KEEP, draft.action)
                assertEquals("107", draft.priority)
                assertEquals(
                    Condition.AllOf(
                        listOf(
                            Condition.PackageEquals("com.example.shop"),
                            Condition.ChannelEquals("offers"),
                        ),
                    ),
                    draft.root.toConditionOrNull(),
                )
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 0) { ruleRepository.saveRule(any()) }
        }

    @Test
    fun `structural analyzer warnings are visible but do not block rules`() =
        runTest {
            rulesFlow.value = listOf(sampleRule.copy(id = "1", priority = 10), sampleRule.copy(id = "2"))

            viewModel().uiState.test {
                awaitUntil { it.rules.size == 2 }
                mainDispatcherRule.dispatcher.scheduler.advanceTimeBy(201)
                mainDispatcherRule.dispatcher.scheduler.runCurrent()
                val loaded =
                    awaitUntil {
                        it.rules
                            .singleOrNull { rule -> rule.id == "2" }
                            ?.warnings
                            ?.isNotEmpty() == true
                    }
                assertTrue(
                    loaded.rules
                        .single { it.id == "2" }
                        .warnings
                        .isNotEmpty(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `rule simulator explains an unavailable optional signal`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.onUseTemplate(RuleTemplate.OBSERVE_ADS)
                val draft = awaitUntil { it.editor != null }.editor!!
                vm.onEditorChange(draft.copy(simulation = draft.simulation.copy(expanded = true)))
                vm.onRunSimulation()
                val simulated = awaitUntil { it.editor?.simulation?.result != null }.editor!!.simulation
                assertEquals(SimulationTraceStatus.UNKNOWN, simulated.trace.last().status)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `shows the automation hint when external automation is off`() =
        runTest {
            viewModel(automationEnabled = false).uiState.test {
                assertTrue(awaitUntil { !it.isLoading }.showAutomationHint)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `hides the automation hint when external automation is on`() =
        runTest {
            viewModel(automationEnabled = true).uiState.test {
                assertEquals(false, awaitUntil { !it.isLoading }.showAutomationHint)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `restores an unsaved nested rule draft after view model recreation`() =
        runTest {
            val savedState = SavedStateHandle()
            val first = viewModel(savedStateHandle = savedState)

            first.uiState.test {
                awaitUntil { !it.isLoading }
                first.onAddRule()
                val editor = awaitUntil { it.editor != null }.editor!!
                first.onEditorChange(
                    editor.copy(
                        name = "Quiet nested offers",
                        editorMode = RuleEditorMode.ADVANCED,
                        root =
                            GroupNode(
                                key = nextNodeKey(),
                                anyOf = false,
                                children =
                                    listOf(
                                        LeafNode(nextNodeKey(), LeafKind.PACKAGE, "com.example.shop"),
                                        NotNode(
                                            nextNodeKey(),
                                            GroupNode(
                                                nextNodeKey(),
                                                anyOf = true,
                                                children =
                                                    listOf(
                                                        LeafNode(nextNodeKey(), LeafKind.TEXT, "receipt"),
                                                        TimeWindowNode(nextNodeKey(), "22:00", "07:00"),
                                                    ),
                                            ),
                                        ),
                                    ),
                            ),
                    ),
                )
                awaitUntil { it.editor?.name == "Quiet nested offers" }
                cancelAndIgnoreRemainingEvents()
            }

            assertTrue(savedState.get<String>(RULE_EDITOR_DRAFT_SAVED_STATE_KEY).orEmpty().isNotEmpty())

            viewModel(savedStateHandle = savedState).uiState.test {
                val restored = awaitUntil { !it.isLoading && it.editor != null }.editor!!
                assertEquals("Quiet nested offers", restored.name)
                assertEquals(
                    Condition.AllOf(
                        listOf(
                            Condition.PackageEquals("com.example.shop"),
                            Condition.Not(
                                Condition.AnyOf(
                                    listOf(
                                        Condition.TextContains("receipt"),
                                        Condition.TimeWindow(22 * 60, 7 * 60),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    restored.root.toConditionOrNull(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `restored rule draft is present in the first public state`() {
        val draft =
            RuleEditorState(
                name = "Restored immediately",
                root = Condition.PackageEquals("com.example").toEditableRoot(),
                hasUnsavedChanges = true,
            )
        val savedState =
            SavedStateHandle(
                mapOf(RULE_EDITOR_DRAFT_SAVED_STATE_KEY to RuleEditorDraftCodec.encode(draft)),
            )

        val initial = viewModel(savedStateHandle = savedState).uiState.value

        assertTrue(initial.isLoading)
        assertEquals("Restored immediately", initial.editor?.name)
    }

    @Test
    fun `discarding a rule draft removes its process death state`() =
        runTest {
            val savedState = SavedStateHandle()
            val vm = viewModel(savedStateHandle = savedState)

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.onUseTemplate(RuleTemplate.KEEP_ALARMS)
                awaitUntil { it.editor != null }
                vm.onConfirmDiscardEditor()
                awaitUntil { it.editor == null }
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(null, savedState.get<String>(RULE_EDITOR_DRAFT_SAVED_STATE_KEY))

            viewModel(savedStateHandle = savedState).uiState.test {
                assertEquals(null, awaitUntil { !it.isLoading }.editor)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
