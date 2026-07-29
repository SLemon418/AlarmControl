package com.alarmcontrol.ui.settings

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.alarmcontrol.ui.NotificationAccessUiState
import com.alarmcontrol.ui.privacy.LocalSensitiveWindowController
import com.alarmcontrol.ui.privacy.SensitiveWindowController
import com.alarmcontrol.ui.theme.AlarmControlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `local LLM controls render from presentation state`() {
        setScreen(
            SettingsUiState(
                llmModelStatus = LlmModelUiStatus.READY,
                llmModelSha256 = "a".repeat(64),
                llmModelSizeBytes = 1_024,
            ),
            destination = SettingsDestination.LOCAL_AI,
        )

        composeRule.onNodeWithText("Use bundled 7-intent classifier").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Use deferred local LLM analysis").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Allow LLM verdicts to trigger rules").assertDoesNotExist()
        composeRule.onNodeWithText("Model status: ready").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Local file integrity verified",
                substring = true,
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("SHA-256: ${"a".repeat(64)}").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Choose local model").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText("Import models only from a source you trust", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `bundled classifier switch hoists its change`() {
        var enabled = true
        setScreen(
            state = SettingsUiState(semanticClassifierEnabled = true),
            destination = SettingsDestination.LOCAL_AI,
            onSemanticClassifierChange = { enabled = it },
        )

        composeRule
            .onNodeWithContentDescription("Use bundled 7-intent classifier")
            .performScrollTo()
            .performClick()

        assertFalse(enabled)
    }

    @Test
    fun `local LLM switch hoists its change`() {
        var enabled = false
        setScreen(
            state =
                SettingsUiState(
                    llmAnalysisEnabled = false,
                    llmBackgroundAnalysisAvailable = true,
                ),
            destination = SettingsDestination.LOCAL_AI,
            onLlmAnalysisChange = { enabled = it },
        )

        composeRule
            .onNodeWithContentDescription("Use deferred local LLM analysis")
            .performScrollTo()
            .performClick()

        assertTrue(enabled)
    }

    @Test
    fun `unverified background model keeps automatic analysis disabled`() {
        setScreen(
            state =
                SettingsUiState(
                    llmAnalysisEnabled = false,
                    llmBackgroundAnalysisAvailable = false,
                ),
            destination = SettingsDestination.LOCAL_AI,
        )

        composeRule
            .onNodeWithContentDescription("Use deferred local LLM analysis")
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule
            .onNodeWithText(
                "Automatic background LLM analysis is unavailable",
                substring = true,
            ).assertIsDisplayed()
    }

    @Test
    fun `local model copy progress is visible`() {
        setScreen(
            SettingsUiState(
                llmModelStatus = LlmModelUiStatus.INSTALLING,
                llmModelCopiedBytes = 50,
                llmModelTotalBytes = 100,
            ),
            destination = SettingsDestination.LOCAL_AI,
        )

        composeRule.onNodeWithText("50%", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `invalid model status is actionable and does not expose raw exceptions`() {
        setScreen(
            SettingsUiState(
                llmModelStatus = LlmModelUiStatus.UNAVAILABLE,
                llmModelError = LlmModelErrorUi.INVALID,
            ),
            destination = SettingsDestination.LOCAL_AI,
        )

        composeRule
            .onNodeWithText("incompatible or invalid model", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `model integrity failure asks the user to reimport`() {
        setScreen(
            SettingsUiState(
                llmModelStatus = LlmModelUiStatus.UNAVAILABLE,
                llmModelError = LlmModelErrorUi.INTEGRITY_FAILED,
            ),
            destination = SettingsDestination.LOCAL_AI,
        )

        composeRule
            .onNodeWithText("changed or its integrity record is missing", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `app health shows missing access and exposes local system settings actions`() {
        var accessOpened = false
        var batteryOpened = false
        setScreen(
            state =
                SettingsUiState(
                    notificationAccessGranted = false,
                    notificationAccessState = NotificationAccessUiState.DENIED,
                ),
            onOpenNotificationAccess = { accessOpened = true },
            onOpenBatterySettings = { batteryOpened = true },
        )

        composeRule.onNodeWithText("Notification access: action needed").assertIsDisplayed()
        composeRule.onNodeWithText("Open settings").performClick()
        composeRule.onNodeWithText("Review battery settings").performClick()

        assertTrue(accessOpened)
        assertTrue(batteryOpened)
    }

    @Test
    fun `restore preview shows validated counts and hoists merge selection`() {
        var selection: RestoreSelectionUi? = null
        setScreen(
            state =
                SettingsUiState(
                    backupPreview =
                        BackupPreviewUi(
                            encrypted = true,
                            rules = 4,
                            profiles = 2,
                            dailyInsights = 30,
                            hasSettings = true,
                            categoryFeedback = 3,
                            adFeedbackVotes = 5,
                        ),
                ),
            onRestoreSelectionChange = { selection = it },
            destination = SettingsDestination.BACKUP,
        )

        composeRule.onNodeWithText("Review restore").assertIsDisplayed()
        composeRule.onNodeWithText("4 rules · 2 profiles · 30 days of history").assertIsDisplayed()
        composeRule.onNodeWithText("Replace selected").performClick()
        assertTrue(selection?.replaceExisting == true)
    }

    @Test
    fun `restore preview protects capture outside the backup destination`() {
        val protectionChanges = mutableListOf<Boolean>()
        val controller = SensitiveWindowController(protectionChanges::add)

        setScreen(
            state =
                SettingsUiState(
                    backupPreview =
                        BackupPreviewUi(
                            encrypted = false,
                            rules = 1,
                            profiles = 0,
                            dailyInsights = 0,
                            hasSettings = false,
                            categoryFeedback = 0,
                            adFeedbackVotes = 0,
                        ),
                ),
            destination = SettingsDestination.OVERVIEW,
            sensitiveWindowController = controller,
        )

        composeRule.onNodeWithText("Review restore").assertIsDisplayed()
        assertTrue(protectionChanges.lastOrNull() == true)
    }

    @Test
    fun `backup warns about plaintext and validates only new encrypted exports`() {
        setScreen(
            state = SettingsUiState(),
            destination = SettingsDestination.BACKUP,
        )

        composeRule
            .onNodeWithText("Without a password", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Backup password (optional)")
            .performScrollTo()
            .performTextInput("short")
        composeRule
            .onNodeWithText("New encrypted backups require at least 8 characters", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Back up").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Restore").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `automation token and local audit are visible only after opt in`() {
        var copied = ""
        setScreen(
            state =
                SettingsUiState(
                    externalAutomationEnabled = true,
                    externalAutomationToken = "device-token-123",
                    automationAudit =
                        listOf(
                            AutomationAuditUi(
                                id = "1",
                                source = AutomationSourceUi.EXTERNAL,
                                operation = AutomationOperationUi.DISABLE,
                                outcome = AutomationOutcomeUi.UNAUTHORIZED,
                                changedCount = 0,
                                requestedAtMillis = System.currentTimeMillis(),
                            ),
                        ),
                ),
            onCopyAutomationToken = { copied = it },
            destination = SettingsDestination.AUTOMATION,
        )

        composeRule.onNodeWithText("device-token-123").assertDoesNotExist()
        composeRule.onNodeWithText("Show").performScrollTo().performClick()
        composeRule.onNodeWithText("device-token-123").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Copy").performScrollTo().performClick()
        assertTrue(copied == "device-token-123")
        composeRule.onNodeWithText("Wrong token", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `overview hoists dynamic color and detail navigation`() {
        var dynamicColor = false
        var destination: SettingsDestination? = null
        setScreen(
            state = SettingsUiState(dynamicColorEnabled = false),
            onDynamicColorChange = { dynamicColor = it },
            onNavigate = { destination = it },
        )

        composeRule.onNodeWithContentDescription("Use dynamic color").performClick()
        composeRule.onNodeWithText("Automation").performScrollTo().performClick()

        assertTrue(dynamicColor)
        assertTrue(destination == SettingsDestination.AUTOMATION)
    }

    @Test
    fun `overview language picker shows current language and hoists selection`() {
        var selected = AppLanguage.ENGLISH
        setScreen(
            state = SettingsUiState(),
            appLanguage = selected,
            onAppLanguageChange = { selected = it },
        )

        composeRule.onNodeWithText("English").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("App language").performClick()
        composeRule.onNodeWithText("한국어").performClick()

        assertEquals(AppLanguage.KOREAN, selected)
    }

    @Test
    fun `overview distinguishes bundled classifier from custom LLM status`() {
        setScreen(
            state =
                SettingsUiState(
                    semanticClassifierEnabled = false,
                    llmModelStatus = LlmModelUiStatus.NOT_LOADED,
                ),
        )

        composeRule
            .onNodeWithText(
                "Bundled classifier: Inactive · Custom LLM — Model status: not loaded",
            ).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `large font stacks setting switches below their full text`() {
        setScreen(
            state = SettingsUiState(filteringEnabled = true),
            fontScale = 2f,
        )

        val subtitleBounds =
            composeRule
                .onNodeWithText(
                    "Pause or resume filtering without changing individual rule switches.",
                    substring = true,
                ).fetchSemanticsNode()
                .boundsInRoot
        val switchBounds =
            composeRule
                .onNodeWithContentDescription("Filtering enabled")
                .fetchSemanticsNode()
                .boundsInRoot

        assertTrue(
            "The switch must be placed below the full subtitle at large font scales.",
            subtitleBounds.bottom <= switchBounds.top,
        )
    }

    private fun setScreen(
        state: SettingsUiState,
        destination: SettingsDestination = SettingsDestination.OVERVIEW,
        onSemanticClassifierChange: (Boolean) -> Unit = {},
        onLlmAnalysisChange: (Boolean) -> Unit = {},
        onOpenNotificationAccess: () -> Unit = {},
        onOpenBatterySettings: () -> Unit = {},
        onRestoreSelectionChange: (RestoreSelectionUi) -> Unit = {},
        onCopyAutomationToken: (String) -> Unit = {},
        onDynamicColorChange: (Boolean) -> Unit = {},
        appLanguage: AppLanguage = AppLanguage.SYSTEM,
        onAppLanguageChange: (AppLanguage) -> Unit = {},
        onNavigate: (SettingsDestination) -> Unit = {},
        sensitiveWindowController: SensitiveWindowController? = null,
        fontScale: Float? = null,
    ) {
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            val density =
                fontScale?.let {
                    Density(density = currentDensity.density, fontScale = it)
                } ?: currentDensity
            CompositionLocalProvider(
                LocalSensitiveWindowController provides sensitiveWindowController,
                LocalDensity provides density,
            ) {
                AlarmControlTheme(dynamicColor = false) {
                    SettingsScreen(
                        state = state,
                        destination = destination,
                        onNavigate = onNavigate,
                        onFilteringChange = {},
                        onExternalAutomationChange = {},
                        onSemanticClassifierChange = onSemanticClassifierChange,
                        onLlmAnalysisChange = onLlmAnalysisChange,
                        onImportLlmModel = {},
                        onPrepareExport = { _, _ -> },
                        onCompleteExport = {},
                        onPrepareImport = {},
                        onCompleteImport = {},
                        onUserMessageShown = {},
                        onOpenNotificationAccess = onOpenNotificationAccess,
                        onOpenBatterySettings = onOpenBatterySettings,
                        onRestoreSelectionChange = onRestoreSelectionChange,
                        onCopyAutomationToken = onCopyAutomationToken,
                        onDynamicColorChange = onDynamicColorChange,
                        appLanguage = appLanguage,
                        onAppLanguageChange = onAppLanguageChange,
                    )
                }
            }
        }
    }
}
