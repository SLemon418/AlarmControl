package com.alarmcontrol.ui.rules

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.NotificationImportance
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.ui.NotificationAccessUiState
import com.alarmcontrol.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Local JVM Compose UI test (Robolectric) — runs via `./gradlew :app:testDebugUnitTest`, no emulator
 * needed. Verifies the automation hint's visibility on [RulesScreen] is driven purely by
 * [RulesUiState.showAutomationHint].
 *
 * Pinned to a plain [Application] so Robolectric does not initialize the Hilt app (the screen is
 * stateless — state is passed in directly), and to SDK 34 (Robolectric 4.11.1's max) since the app
 * targets 36.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class RulesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val hintText =
        "Samsung Routines can use AlarmControl app shortcuts directly. " +
            "Enable external automation only for Tasker or MacroDroid Intents."

    @Test
    fun showsHint_whenShowAutomationHintIsTrue() {
        setRulesScreen(showHint = true)

        composeRule.onNodeWithTag(RULES_LIST_TEST_TAG).performScrollToNode(hasText(hintText))
        composeRule.onNodeWithText(hintText).assertIsDisplayed()
    }

    @Test
    fun hidesHint_whenShowAutomationHintIsFalse() {
        setRulesScreen(showHint = false)

        composeRule.onNodeWithText(hintText).assertDoesNotExist()
    }

    @Test
    fun showsNotificationAccessBanner_whenAccessNotGranted() {
        var grantClicked = false
        composeRule.setContent {
            RulesScreen(
                state =
                    RulesUiState(
                        isLoading = false,
                        notificationAccessGranted = false,
                        notificationAccessState = NotificationAccessUiState.DENIED,
                    ),
                onAddRule = {},
                onEditRule = {},
                onToggleRule = { _, _ -> },
                onDeleteRule = {},
                onUserMessageShown = {},
                onGrantAccess = { grantClicked = true },
            )
        }

        composeRule.onNodeWithText("Turn on notification access").assertIsDisplayed()
        composeRule.onNodeWithText("Open settings").performClick()
        assertTrue(grantClicked)
    }

    @Test
    fun hidesNotificationAccessBanner_whenGranted() {
        setRulesScreen(showHint = false) // default state has notificationAccessGranted = true

        composeRule.onNodeWithText("Turn on notification access").assertDoesNotExist()
    }

    @Test
    fun setupHealth_requiresNotificationAccess_whenDeniedWithFilteringAndActiveRule() {
        setSetupHealthScreen(NotificationAccessUiState.DENIED)

        assertSetupHealthText(
            "AlarmControl needs notification access to apply your rules and show activity. " +
                "Notifications are processed only on this device.",
        )
        composeRule.onNodeWithText("Inactive").assertIsDisplayed()
        composeRule.onNodeWithText("Active").assertDoesNotExist()
        composeRule.onNodeWithText("Filtering is ready", substring = true).assertDoesNotExist()
    }

    @Test
    fun setupHealth_checksNotificationAccess_beforeFilteringAndActiveRule() {
        setSetupHealthScreen(NotificationAccessUiState.CHECKING)

        assertSetupHealthText("Checking notification access…")
        composeRule.onNodeWithText("Inactive").assertIsDisplayed()
        composeRule.onNodeWithText("Active").assertDoesNotExist()
        composeRule.onNodeWithText("Filtering is ready", substring = true).assertDoesNotExist()
    }

    @Test
    fun ruleEditorRouteOnlyClosesAfterLoadingCompletesWithoutADraft() {
        val state = mutableStateOf(RulesUiState())
        var closeCount = 0
        composeRule.setContent {
            RuleEditorRouteCloseEffect(
                isLoading = state.value.isLoading,
                editorMissing = state.value.editor == null,
                onClose = { closeCount += 1 },
            )
        }

        composeRule.runOnIdle {
            assertEquals(0, closeCount)
            state.value = state.value.copy(isLoading = false)
        }
        composeRule.runOnIdle { assertEquals(1, closeCount) }
    }

    @Test
    fun addRuleFabHasAnAccessibleName() {
        setRulesScreen(showHint = false)

        composeRule
            .onNodeWithContentDescription("Add rule")
            .assertHasClickAction()
    }

    @Test
    fun deletingRuleShowsHowManyProfilesWillBeAffected() {
        var confirmed = false
        composeRule.setContent {
            RulesScreen(
                state =
                    RulesUiState(
                        isLoading = false,
                        notificationAccessState = NotificationAccessUiState.GRANTED,
                        pendingDelete =
                            RuleDeleteConfirmationUi(
                                ruleId = "1",
                                ruleName = "Focus",
                                profileCount = 2,
                            ),
                    ),
                onAddRule = {},
                onEditRule = {},
                onToggleRule = { _, _ -> },
                onDeleteRule = {},
                onConfirmDeleteRule = { confirmed = true },
                onUserMessageShown = {},
            )
        }

        composeRule.onNodeWithText("“Focus” is used by 2 profiles", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun templatePickerHoistsTheSelectedTemplate() {
        var selected: RuleTemplate? = null
        composeRule.setContent {
            RulesScreen(
                state = RulesUiState(isLoading = false),
                onAddRule = {},
                onEditRule = {},
                onToggleRule = { _, _ -> },
                onDeleteRule = {},
                onUserMessageShown = {},
                onUseTemplate = { selected = it },
            )
        }

        composeRule
            .onNodeWithTag(RULES_LIST_TEST_TAG)
            .performScrollToNode(hasText("Quiet one app at night"))
        composeRule
            .onNode(hasText("Quiet one app at night") and hasClickAction())
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(RuleTemplate.ONE_APP_AT_NIGHT, selected)
        }
    }

    private fun setRulesScreen(showHint: Boolean) {
        composeRule.setContent {
            RulesScreen(
                state =
                    RulesUiState(
                        isLoading = false,
                        showAutomationHint = showHint,
                        notificationAccessGranted = true,
                        notificationAccessState = NotificationAccessUiState.GRANTED,
                    ),
                onAddRule = {},
                onEditRule = {},
                onToggleRule = { _, _ -> },
                onDeleteRule = {},
                onUserMessageShown = {},
            )
        }
    }

    private fun setSetupHealthScreen(notificationAccessState: NotificationAccessUiState) {
        composeRule.setContent {
            RulesScreen(
                state =
                    RulesUiState(
                        isLoading = false,
                        notificationAccessState = notificationAccessState,
                        filteringEnabled = true,
                        enabledRuleCount = 1,
                    ),
                onAddRule = {},
                onEditRule = {},
                onToggleRule = { _, _ -> },
                onDeleteRule = {},
                onUserMessageShown = {},
            )
        }
        composeRule
            .onNodeWithTag(RULES_LIST_TEST_TAG)
            .performScrollToNode(hasTestTag(SETUP_HEALTH_CARD_TEST_TAG))
    }

    private fun assertSetupHealthText(text: String) {
        composeRule
            .onNode(
                hasText(text).and(hasAnyAncestor(hasTestTag(SETUP_HEALTH_CARD_TEST_TAG))),
                useUnmergedTree = true,
            ).assertIsDisplayed()
    }

    @Test
    fun ruleEditor_showsTheConditionBuilder() {
        setRuleEditor(mutableStateOf(RuleEditorState(editorMode = RuleEditorMode.ADVANCED)))

        composeRule.onNodeWithText("New rule").assertIsDisplayed()
        composeRule.onNodeWithText("Name").assertIsDisplayed()
        composeRule.onNodeWithText("Conditions").assertIsDisplayed()
        composeRule.onNodeWithText("Match all").assertIsDisplayed()
        composeRule.onNodeWithText("+ Condition").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun ruleEditor_addingAConditionShowsALeafEditor() {
        val editorState = mutableStateOf(RuleEditorState(editorMode = RuleEditorMode.ADVANCED))
        setRuleEditor(editorState)

        // No leaf yet; adding one reveals its kind selector ("Package").
        composeRule.onNodeWithText("Package").assertDoesNotExist()
        composeRule.onNodeWithText("+ Condition").performClick()
        composeRule.onNodeWithText("Package").assertIsDisplayed()
    }

    @Test
    fun ruleEditor_displaysFriendlyValuesWithoutRawBooleanOrEnumNames() {
        setRuleEditor(mutableStateOf(friendlyValueEditorState()))

        composeRule.onNodeWithText("Yes").assertExists()
        composeRule.onNodeWithText("Security").assertExists()
        composeRule.onNodeWithText("High").assertExists()
        composeRule.onNodeWithText("true").assertDoesNotExist()
        composeRule.onNodeWithText(SemanticIntent.SECURITY.name).assertDoesNotExist()
        composeRule.onNodeWithText(NotificationImportance.HIGH.name).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "ko")
    fun ruleEditor_displaysKoreanValuesWithoutRawBooleanOrEnumNames() {
        setRuleEditor(mutableStateOf(friendlyValueEditorState()))

        composeRule.onNodeWithText("예").assertExists()
        composeRule.onNodeWithText("보안").assertExists()
        composeRule.onNodeWithText("높음").assertExists()
        composeRule.onNodeWithText("true").assertDoesNotExist()
        composeRule.onNodeWithText(SemanticIntent.SECURITY.name).assertDoesNotExist()
        composeRule.onNodeWithText(NotificationImportance.HIGH.name).assertDoesNotExist()
    }

    @Test
    fun ruleEditor_typingNameThenSaving_invokesSave() {
        // Hoist the editor state so typing round-trips through onEditorChange like the real ViewModel.
        val editorState =
            mutableStateOf(
                RuleEditorState(
                    root = Condition.PackageEquals("com.example").toEditableRoot(),
                    editorMode = RuleEditorMode.ADVANCED,
                ),
            )
        var saved = false
        setRuleEditor(editorState, onSave = { saved = true })

        composeRule.onNodeWithText("Name").performTextInput("Mute promos")
        composeRule.onNodeWithText("Mute promos").assertIsDisplayed()

        composeRule.onNodeWithText("Save").performClick()
        assertTrue(saved)
    }

    @Test
    fun ruleEditor_blankConditionShowsValidationHint() {
        val editorState = mutableStateOf(RuleEditorState(editorMode = RuleEditorMode.ADVANCED))
        setRuleEditor(editorState)

        // A freshly added condition is blank, so the soft validation hint appears.
        composeRule.onNodeWithText("+ Condition").performClick()
        composeRule.onNodeWithText("Enter a value").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun ruleEditor_showsReorderHandlesWithMultipleConditions() {
        val first = newLeafNode().copy(value = "first")
        val second = newLeafNode().copy(value = "second")
        val root = GroupNode(nextNodeKey(), anyOf = false, children = listOf(first, second))
        val editorState =
            mutableStateOf(RuleEditorState(root = root, editorMode = RuleEditorMode.ADVANCED))
        setRuleEditor(editorState)

        composeRule.onAllNodesWithTag(CONDITION_MOVE_UP_TEST_TAG, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithTag(CONDITION_MOVE_UP_ENABLED_TEST_TAG, useUnmergedTree = true).assertCountEquals(1)
        composeRule
            .onNodeWithTag(CONDITION_MOVE_UP_ENABLED_TEST_TAG, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        val values =
            editorState.value.root.children
                .map { (it as LeafNode).value }
        assertEquals(listOf("second", "first"), values)
    }

    @Test
    fun ruleEditor_rendersFrequencyScopePresetsAndValidation() {
        val root =
            GroupNode(
                nextNodeKey(),
                anyOf = false,
                children = listOf(RateNode(nextNodeKey(), RateScope.CHANNEL, "5", "10")),
            )
        setRuleEditor(
            mutableStateOf(
                RuleEditorState(root = root, editorMode = RuleEditorMode.ADVANCED),
            ),
        )

        composeRule.onNodeWithText("App + channel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("5 min").assertIsDisplayed()
        composeRule.onNodeWithText("Window (1–1440 min)").assertExists()
        composeRule.onNodeWithText("Posts (2–1000)").assertExists()
    }

    @Test
    fun ruleEditor_switchesToMonitorModeAndShowsNonBlockingWarning() {
        val editorState =
            mutableStateOf(
                RuleEditorState(
                    name = "Monitor promotions",
                    root = Condition.PackageEquals("com.example").toEditableRoot(),
                    warnings = listOf(UiText.Dynamic("Structural warning")),
                    editorMode = RuleEditorMode.ADVANCED,
                ),
            )
        setRuleEditor(editorState)

        composeRule.onNodeWithText("Monitor").performScrollTo().performClick()
        assertEquals(RuleExecutionMode.MONITOR, editorState.value?.executionMode)
        composeRule
            .onNodeWithText("Monitor rules predict and record an action", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("1 rule warning").performScrollTo().performClick()
        composeRule.onNodeWithText("Structural warning").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun ruleCardCollapsesStructuralWarningsUntilRequested() {
        composeRule.setContent {
            RulesScreen(
                state =
                    RulesUiState(
                        isLoading = false,
                        notificationAccessState = NotificationAccessUiState.GRANTED,
                        rules =
                            listOf(
                                RuleListItem(
                                    id = "1",
                                    name = "Focus",
                                    summary = UiText.Dynamic("Package: example"),
                                    actionLabel = UiText.Dynamic("Cancel"),
                                    enabled = true,
                                    executionMode = RuleExecutionMode.ACTIVE,
                                    warnings = listOf(UiText.Dynamic("Structural warning")),
                                ),
                            ),
                        enabledRuleCount = 1,
                        availableSources =
                            listOf(
                                RuleSourceUi(
                                    key = "example:",
                                    packageName = "example",
                                    appName = "Example",
                                    channelId = null,
                                    channelName = null,
                                    eventCount = 1,
                                ),
                            ),
                    ),
                onAddRule = {},
                onEditRule = {},
                onToggleRule = { _, _ -> },
                onDeleteRule = {},
                onUserMessageShown = {},
            )
        }

        composeRule.onNodeWithText("Structural warning").assertDoesNotExist()
        composeRule.onNodeWithTag(RULES_LIST_TEST_TAG).performScrollToNode(hasText("1 rule warning"))
        composeRule.onNodeWithText("1 rule warning").performScrollTo().performClick()
        composeRule.onNodeWithText("Structural warning").assertIsDisplayed()
    }

    @Test
    fun ruleEditor_requestsConfirmationBeforeDiscardingChanges() {
        val editorState =
            mutableStateOf(
                RuleEditorState(
                    name = "Focus",
                    root = Condition.PackageEquals("com.example").toEditableRoot(),
                    hasUnsavedChanges = true,
                    showDiscardConfirmation = true,
                ),
            )
        var discarded = false
        setRuleEditor(editorState, onConfirmDiscard = { discarded = true })

        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Discard").performClick()

        assertTrue(discarded)
    }

    @Test
    fun guidedEditorSearchesObservedSourcesWithoutManualPackageGuessing() {
        val editorState = mutableStateOf(RuleEditorState())
        composeRule.setContent {
            RuleEditorScreen(
                state = editorState.value,
                availableSources =
                    listOf(
                        RuleSourceUi(
                            key = "com.shop:offers",
                            packageName = "com.shop",
                            appName = "Shop",
                            channelId = "offers",
                            channelName = "Offers",
                            eventCount = 12,
                        ),
                    ),
                onChange = { editorState.value = it },
                onSimulate = {},
                onSave = {},
                onRequestClose = {},
                onConfirmDiscard = {},
                onCancelDiscard = {},
            )
        }

        composeRule.onNodeWithText("Choose a recent app or channel").performClick()
        composeRule.onNodeWithText("Search app or channel").performTextInput("offer")
        composeRule.onNodeWithText("Shop").performClick()

        assertEquals("com.shop", editorState.value.guidedPackageName)
        assertEquals("offers", editorState.value.guidedChannelId)
        assertEquals(GuidedRuleScope.CHANNEL, editorState.value.guidedScope)
    }

    @Test
    fun guidedManualPackageShowsSoftValidation() {
        val editorState = mutableStateOf(RuleEditorState())
        setRuleEditor(editorState)

        composeRule.onNodeWithText("Android package name").performTextInput("not a package")
        composeRule.onNodeWithText("Enter a package such as com.example.app").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    private fun setRuleEditor(
        state: androidx.compose.runtime.MutableState<RuleEditorState>,
        onSave: () -> Unit = {},
        onConfirmDiscard: () -> Unit = {},
    ) {
        composeRule.setContent {
            RuleEditorScreen(
                state = state.value,
                onChange = { state.value = it },
                onSimulate = {},
                onSave = onSave,
                onRequestClose = {},
                onConfirmDiscard = onConfirmDiscard,
                onCancelDiscard = {},
            )
        }
    }

    private fun friendlyValueEditorState(): RuleEditorState =
        RuleEditorState(
            root =
                GroupNode(
                    key = nextNodeKey(),
                    anyOf = false,
                    children =
                        listOf(
                            LeafNode(nextNodeKey(), LeafKind.ONGOING, true.toString()),
                            LeafNode(nextNodeKey(), LeafKind.SEMANTIC_INTENT, SemanticIntent.SECURITY.name),
                            LeafNode(
                                nextNodeKey(),
                                LeafKind.IMPORTANCE_AT_LEAST,
                                NotificationImportance.HIGH.name,
                            ),
                        ),
                ),
            editorMode = RuleEditorMode.ADVANCED,
        )
}
