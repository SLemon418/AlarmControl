package com.alarmcontrol.ui.settings

import com.alarmcontrol.core.settings.SemanticAnalysisScope
import com.alarmcontrol.ui.NotificationAccessUiState
import com.alarmcontrol.ui.UiText

/** Immutable UI state for the settings screen (CLAUDE.md §8). */
data class SettingsUiState(
    val filteringEnabled: Boolean = true,
    val externalAutomationEnabled: Boolean = false,
    val externalAutomationToken: String = "",
    val automationAudit: List<AutomationAuditUi> = emptyList(),
    val llmAnalysisEnabled: Boolean = false,
    val llmBackgroundAnalysisAvailable: Boolean = false,
    val semanticAnalysisScope: SemanticAnalysisScope = SemanticAnalysisScope.RULES_ONLY,
    val eventRetentionDays: Int = 30,
    val dailyInsightRetentionDays: Int = 365,
    val dynamicColorEnabled: Boolean = false,
    val notificationContentStorageEnabled: Boolean = false,
    val contentExcludedPackages: Set<String> = emptySet(),
    val contentSourceApps: List<ContentSourceAppUi> = emptyList(),
    val llmModelStatus: LlmModelUiStatus = LlmModelUiStatus.NOT_LOADED,
    val llmModelCopiedBytes: Long = 0,
    val llmModelTotalBytes: Long? = null,
    val llmModelError: LlmModelErrorUi? = null,
    val llmModelSha256: String? = null,
    val llmModelSizeBytes: Long? = null,
    val notificationAccessGranted: Boolean = false,
    val notificationAccessState: NotificationAccessUiState = NotificationAccessUiState.CHECKING,
    val batteryOptimizationExempt: Boolean = false,
    val backupPreview: BackupPreviewUi? = null,
    /** Transient feedback (e.g. backup/restore result) shown once via a snackbar. */
    val userMessage: UiText? = null,
)

data class ContentSourceAppUi(
    val packageName: String,
    val appName: String,
    val excluded: Boolean,
)

data class AutomationAuditUi(
    val id: String,
    val source: AutomationSourceUi,
    val operation: AutomationOperationUi,
    val outcome: AutomationOutcomeUi,
    val changedCount: Int,
    val requestedAtMillis: Long,
)

enum class AutomationSourceUi { EXTERNAL, QUICK_SETTINGS, SHORTCUT, IN_APP }

enum class AutomationOperationUi { ENABLE, DISABLE, TOGGLE }

enum class AutomationOutcomeUi { APPLIED, NO_CHANGE, DISABLED, UNAUTHORIZED, THROTTLED, INVALID, NOT_FOUND }

data class BackupPreviewUi(
    val encrypted: Boolean,
    val rules: Int,
    val profiles: Int,
    val dailyInsights: Int,
    val hasSettings: Boolean,
    val categoryFeedback: Int,
    val adFeedbackVotes: Int,
    val selection: RestoreSelectionUi = RestoreSelectionUi(),
) {
    val canRestoreLearningFeedback: Boolean
        get() = encrypted && (categoryFeedback > 0 || adFeedbackVotes > 0)
}

data class RestoreSelectionUi(
    val replaceExisting: Boolean = false,
    val rulesAndProfiles: Boolean = true,
    val dailyInsights: Boolean = true,
    val settings: Boolean = true,
    val learningFeedback: Boolean = false,
) {
    val hasSelection: Boolean get() = rulesAndProfiles || dailyInsights || settings || learningFeedback
}

/** App-local presentation state; no MediaPipe or ML domain type reaches the Composable. */
enum class LlmModelUiStatus { NOT_LOADED, INSTALLING, LOADING, READY, UNAVAILABLE }

enum class LlmModelErrorUi { MISSING, INVALID, INTEGRITY_FAILED, LOAD_FAILED, STORAGE_FAILURE }

enum class SettingsDestination(
    val route: String,
) {
    OVERVIEW("settings/overview"),
    AUTOMATION("settings/automation"),
    LOCAL_AI("settings/local-ai"),
    BACKUP("settings/backup"),
    DATA_PRIVACY("settings/data-privacy"),
    CONTENT_EXCLUSIONS("settings/content-exclusions"),
}
