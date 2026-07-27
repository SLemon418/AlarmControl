package com.alarmcontrol.ui.rules

import com.alarmcontrol.core.filtering.MAX_CONDITION_VALUE_CHARS
import com.alarmcontrol.core.filtering.MAX_RATE_THRESHOLD
import com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.MAX_RULE_NAME_CHARS
import com.alarmcontrol.core.filtering.MIN_RATE_THRESHOLD
import com.alarmcontrol.core.filtering.MIN_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.NotificationImportance
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.ui.NotificationAccessUiState
import com.alarmcontrol.ui.UiText

/**
 * Immutable UI state for the rules screen (CLAUDE.md §8 — one state `data class` via `StateFlow`).
 *
 * @property editor non-null while the dedicated add/edit destination is open.
 * @property errorMessage a persistent load failure shown in the body.
 * @property userMessage a transient one-shot message (snackbar); cleared via `onUserMessageShown`.
 */
data class RulesUiState(
    val isLoading: Boolean = true,
    val rules: List<RuleListItem> = emptyList(),
    val editor: RuleEditorState? = null,
    val errorMessage: UiText? = null,
    val userMessage: UiText? = null,
    /** Show the "enable external automation" hint — true when the opt-in is currently off (§7). */
    val showAutomationHint: Boolean = false,
    /** Whether notification-listener access is granted; when false the app can't filter anything. */
    val notificationAccessGranted: Boolean = false,
    val notificationAccessState: NotificationAccessUiState = NotificationAccessUiState.CHECKING,
    /** Global master switch, shown alongside access/rule readiness in the local setup card. */
    val filteringEnabled: Boolean = true,
    val enabledRuleCount: Int = 0,
    /** Recently observed content-free app/channel sources for guided rule creation. */
    val availableSources: List<RuleSourceUi> = emptyList(),
    val pendingDelete: RuleDeleteConfirmationUi? = null,
)

data class RuleDeleteConfirmationUi(
    val ruleId: String,
    val ruleName: String,
    val profileCount: Int,
)

data class RuleSourceUi(
    val key: String,
    val packageName: String,
    val appName: String,
    val channelId: String?,
    val channelName: String?,
    val eventCount: Int,
)

/** In-memory handoff from an activity row to a prefilled, unsaved rule editor. */
data class QuickRuleDraft(
    val packageName: String,
    val category: String?,
    val channelId: String? = null,
    val keep: Boolean = false,
    val marketingMonitor: Boolean = false,
)

/** Display row for a single rule. */
data class RuleListItem(
    val id: String,
    val name: String,
    val summary: UiText,
    val actionLabel: UiText,
    val enabled: Boolean,
    val executionMode: RuleExecutionMode,
    val warnings: List<UiText> = emptyList(),
)

/** Editable form state for adding or editing a rule. [root] is the editable condition tree. */
data class RuleEditorState(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val priority: String = "0",
    val action: EditorAction = EditorAction.CANCEL,
    val executionMode: RuleExecutionMode = RuleExecutionMode.MONITOR,
    val snoozeMinutes: String = "30",
    val root: GroupNode = emptyRootGroup(),
    val simulation: RuleSimulationState = RuleSimulationState(),
    val warnings: List<UiText> = emptyList(),
    val isSaving: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val showDiscardConfirmation: Boolean = false,
    val editorMode: RuleEditorMode = RuleEditorMode.GUIDED,
    val guidedPackageName: String = "",
    val guidedAppName: String = "",
    val guidedChannelId: String? = null,
    val guidedChannelName: String? = null,
    val guidedScope: GuidedRuleScope = GuidedRuleScope.APP,
    val guidedTimeEnabled: Boolean = false,
    val guidedStartTime: String = "22:00",
    val guidedEndTime: String = "07:00",
    val guidedFrequencyEnabled: Boolean = false,
    val guidedFrequencyMinutes: String = "5",
    val guidedFrequencyThreshold: String = "5",
) {
    val isEditing: Boolean get() = id.isNotBlank()

    val canSave: Boolean
        get() =
            !isSaving &&
                name.isNotBlank() &&
                name.length <= MAX_RULE_NAME_CHARS &&
                guidedDefinitionValid &&
                root.toConditionOrNull() != null &&
                priority.toIntOrNull() != null &&
                (action != EditorAction.SNOOZE || snoozeMinutes.toLongOrNull() in VALID_SNOOZE_MINUTES)

    /**
     * Validates every enabled guided input independently of [root]. This prevents an invalid
     * optional condition from being omitted while a broader package-only rule is persisted.
     */
    internal val guidedDefinitionValid: Boolean
        get() {
            if (editorMode != RuleEditorMode.GUIDED) return true
            if (!isLikelyAndroidPackage(guidedPackageName)) return false
            if (guidedPackageName.length > MAX_CONDITION_VALUE_CHARS) return false
            if (
                guidedScope == GuidedRuleScope.CHANNEL &&
                (guidedChannelId.isNullOrBlank() || guidedChannelId.length > MAX_CONDITION_VALUE_CHARS)
            ) {
                return false
            }
            if (
                guidedTimeEnabled &&
                (parseMinuteOfDay(guidedStartTime) == null || parseMinuteOfDay(guidedEndTime) == null)
            ) {
                return false
            }
            if (guidedFrequencyEnabled) {
                val minutes = guidedFrequencyMinutes.toLongOrNull()
                val threshold = guidedFrequencyThreshold.toIntOrNull()
                if (minutes !in VALID_GUIDED_RATE_WINDOW_MINUTES || threshold !in VALID_GUIDED_RATE_THRESHOLDS) {
                    return false
                }
            }
            return true
        }
}

enum class RuleEditorMode { GUIDED, ADVANCED }

enum class GuidedRuleScope { APP, CHANNEL }

internal fun isLikelyAndroidPackage(value: String): Boolean {
    val segments = value.trim().split('.')
    return segments.size >= MIN_PACKAGE_SEGMENTS &&
        segments.all { segment ->
            segment.isNotEmpty() &&
                (segment.first().isLetter() || segment.first() == '_') &&
                segment.drop(1).all { it.isLetterOrDigit() || it == '_' }
        }
}

/** Sample notification metadata for the side-effect-free rule simulator. */
data class RuleSimulationState(
    val expanded: Boolean = false,
    val packageName: String = "",
    val title: String = "",
    val text: String = "",
    val category: String = "",
    val channelId: String = "",
    val mlCategory: String = "",
    val localTime: String = "12:00",
    val ongoing: Boolean = false,
    val advertisement: Boolean? = null,
    val semanticIntent: SemanticIntent? = null,
    val importance: NotificationImportance? = null,
    val conversation: Boolean? = null,
    val foregroundService: Boolean? = null,
    val rateKnown: Boolean = false,
    val rateCount: String = "",
    val result: UiText? = null,
    val trace: List<SimulationTraceItem> = emptyList(),
)

private const val MIN_PACKAGE_SEGMENTS = 2

data class SimulationTraceItem(
    val condition: UiText,
    val status: SimulationTraceStatus,
    val depth: Int,
)

enum class SimulationTraceStatus { MATCH, NO_MATCH, UNKNOWN }

/** The actions a user can pick in the editor. */
enum class EditorAction { CANCEL, SNOOZE, MARK_READ, KEEP }

internal val VALID_SNOOZE_MINUTES = 1L..10_080L

private const val MILLIS_PER_MINUTE = 60_000L
private val VALID_GUIDED_RATE_WINDOW_MINUTES =
    (MIN_RATE_WINDOW_MILLIS / MILLIS_PER_MINUTE)..(MAX_RATE_WINDOW_MILLIS / MILLIS_PER_MINUTE)
private val VALID_GUIDED_RATE_THRESHOLDS = MIN_RATE_THRESHOLD..MAX_RATE_THRESHOLD
