package com.alarmcontrol.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alarmcontrol.R
import com.alarmcontrol.core.coroutines.AppDispatcher
import com.alarmcontrol.core.coroutines.Dispatcher
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.NotificationHistoryRepository
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.RateSignal
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAnalyzer
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.profile.ProfileRepository
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.core.result.asDataResult
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.notifications.MatchDecision
import com.alarmcontrol.notifications.Matcher
import com.alarmcontrol.service.AppHealthProvider
import com.alarmcontrol.ui.NotificationAccessUiState
import com.alarmcontrol.ui.UiText
import com.alarmcontrol.ui.app.AppIdentityResolver
import com.alarmcontrol.ui.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Suppress("TooManyFunctions")
@HiltViewModel
class RulesViewModel
    @Inject
    constructor(
        private val ruleRepository: RuleRepository,
        private val profileRepository: ProfileRepository,
        notificationHistoryRepository: NotificationHistoryRepository,
        private val settingsRepository: SettingsRepository,
        private val matcher: Matcher,
        private val ruleAnalyzer: RuleAnalyzer,
        private val appHealthProvider: AppHealthProvider,
        private val appIdentityResolver: AppIdentityResolver,
        @Dispatcher(AppDispatcher.Default) private val dispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val editor = MutableStateFlow<RuleEditorState?>(null)
        private val messages = MutableStateFlow<UiText?>(null)
        private val pendingDelete = MutableStateFlow<RuleDeleteConfirmationUi?>(null)

        private val notificationAccess =
            MutableStateFlow(NotificationAccessUiState.CHECKING)

        private val rulesResult: StateFlow<DataResult<List<Rule>>> =
            ruleRepository
                .observeRules()
                .asDataResult()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DataResult.Loading)

        private val ruleContent =
            combine(
                rulesResult,
                notificationHistoryRepository.observeSources(SOURCE_LIMIT),
            ) { result, sources ->
                RuleContent(
                    result = result,
                    sources =
                        sources.map { source ->
                            RuleSourceUi(
                                key = "${source.packageName}:${source.channelId.orEmpty()}",
                                packageName = source.packageName,
                                appName = appIdentityResolver.resolve(source.packageName).label,
                                channelId = source.channelId,
                                channelName = source.channelName,
                                eventCount = source.eventCount,
                            )
                        },
                )
            }

        private val settingsState =
            combine(
                settingsRepository.externalAutomationEnabled,
                settingsRepository.filteringEnabled,
                ::RuleSettings,
            )

        private val ruleAnalysis =
            combine(rulesResult, editor) { result, editorState ->
                AnalysisInput(
                    rules = (result as? DataResult.Success)?.data.orEmpty(),
                    editor = editorState?.copy(warnings = emptyList()),
                )
            }.debounce(ANALYSIS_DEBOUNCE_MILLIS)
                .mapLatest { input ->
                    withContext(dispatcher) {
                        val warningsByRule =
                            ruleAnalyzer
                                .analyze(input.rules)
                                .groupBy { it.ruleId }
                                .mapValues { (_, issues) ->
                                    issues.map { it.kind.warningText() }.distinct()
                                }
                        RuleAnalysisPresentation(
                            rules = input.rules,
                            editor = input.editor,
                            warningsByRule = warningsByRule,
                            editorWarnings =
                                input.editor
                                    ?.withAnalysisWarnings(input.rules)
                                    ?.warnings
                                    .orEmpty(),
                        )
                    }
                }.stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    RuleAnalysisPresentation(),
                )

        val uiState: StateFlow<RulesUiState> =
            combine(
                ruleContent,
                editor,
                messages,
                settingsState,
                notificationAccess,
                pendingDelete,
                ruleAnalysis,
            ) { values ->
                val content = values[CONTENT_INDEX] as RuleContent
                val editorState = values[EDITOR_INDEX] as RuleEditorState?
                val message = values[MESSAGE_INDEX] as UiText?
                val settings = values[SETTINGS_INDEX] as RuleSettings
                val accessState = values[ACCESS_INDEX] as NotificationAccessUiState
                val deleteConfirmation = values[DELETE_INDEX] as RuleDeleteConfirmationUi?
                val analysis = values[ANALYSIS_INDEX] as RuleAnalysisPresentation
                val result = content.result
                val rules = (result as? DataResult.Success)?.data.orEmpty()
                val warningsByRule =
                    analysis.takeIf { it.rules == rules }?.warningsByRule.orEmpty()
                val normalizedEditor = editorState?.copy(warnings = emptyList())
                val editorWithWarnings =
                    editorState?.copy(
                        warnings =
                            analysis
                                .takeIf { it.editor == normalizedEditor }
                                ?.editorWarnings
                                .orEmpty(),
                    )
                RulesUiState(
                    isLoading = result is DataResult.Loading,
                    rules = rules.map { it.toListItem(warningsByRule[it.id].orEmpty()) },
                    editor = editorWithWarnings,
                    errorMessage = if (result is DataResult.Failure) uiText(R.string.message_generic_error) else null,
                    userMessage = message,
                    showAutomationHint = !settings.automationEnabled,
                    notificationAccessGranted = accessState == NotificationAccessUiState.GRANTED,
                    notificationAccessState = accessState,
                    filteringEnabled = settings.filteringEnabled,
                    enabledRuleCount =
                        rules.count { it.enabled && it.executionMode == RuleExecutionMode.ACTIVE },
                    availableSources = content.sources,
                    pendingDelete = deleteConfirmation,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RulesUiState())

        /** Re-reads whether notification access is granted; call when the screen resumes. */
        fun refreshNotificationAccess() {
            notificationAccess.value =
                if (appHealthProvider.snapshot().notificationAccessGranted) {
                    NotificationAccessUiState.GRANTED
                } else {
                    NotificationAccessUiState.DENIED
                }
        }

        /** Opens an unsaved package-level draft from a privacy-safe activity-feed item. */
        fun onCreateRuleFromActivity(draft: QuickRuleDraft) {
            val conditions =
                if (draft.marketingMonitor) {
                    listOf(
                        Condition.PackageEquals(draft.packageName),
                        Condition.AnyOf(
                            listOf(
                                Condition.MlCategoryEquals("promotion"),
                                Condition.SemanticIntentEquals(
                                    com.alarmcontrol.core.filtering.SemanticIntent.MARKETING,
                                ),
                            ),
                        ),
                    )
                } else {
                    buildList {
                        add(Condition.PackageEquals(draft.packageName))
                        if (draft.channelId.isNullOrBlank()) {
                            draft.category?.takeIf(String::isNotBlank)?.let { add(Condition.CategoryEquals(it)) }
                        } else {
                            add(Condition.ChannelEquals(draft.channelId))
                        }
                    }
                }
            editor.value =
                RuleEditorState(
                    name =
                        if (draft.marketingMonitor) {
                            "Filter promotions from ${draft.packageName}"
                        } else {
                            ""
                        },
                    root = Condition.AllOf(conditions).toEditableRoot(),
                    action = if (draft.keep) EditorAction.KEEP else EditorAction.CANCEL,
                    executionMode =
                        if (draft.keep) {
                            RuleExecutionMode.ACTIVE
                        } else {
                            RuleExecutionMode.MONITOR
                        },
                    priority = if (draft.keep) suggestedProtectionPriority().toString() else "0",
                    simulation =
                        RuleSimulationState(
                            packageName = draft.packageName,
                            category = draft.category.orEmpty(),
                            channelId = draft.channelId.orEmpty(),
                        ),
                    hasUnsavedChanges = true,
                    editorMode =
                        if (draft.marketingMonitor || (!draft.keep && !draft.category.isNullOrBlank())) {
                            RuleEditorMode.ADVANCED
                        } else {
                            RuleEditorMode.GUIDED
                        },
                    guidedPackageName = draft.packageName,
                    guidedAppName = appIdentityResolver.resolve(draft.packageName).label,
                    guidedChannelId = draft.channelId,
                    guidedScope =
                        if (draft.channelId.isNullOrBlank()) {
                            GuidedRuleScope.APP
                        } else {
                            GuidedRuleScope.CHANNEL
                        },
                )
        }

        fun onAddRule() {
            editor.value = RuleEditorState()
        }

        fun onUseTemplate(template: RuleTemplate) {
            editor.value =
                template
                    .toEditorState(suggestedProtectionPriority())
                    .copy(hasUnsavedChanges = true)
        }

        fun onEditRule(ruleId: String) {
            currentRule(ruleId)?.let { editor.value = it.toEditorState() }
        }

        fun onEditorChange(state: RuleEditorState) {
            val previous = editor.value
            var normalized = state
            if (state.editorMode == RuleEditorMode.GUIDED) {
                if (previous?.action == EditorAction.KEEP && state.action != EditorAction.KEEP) {
                    normalized = normalized.copy(executionMode = RuleExecutionMode.MONITOR, priority = "0")
                } else if (state.action == EditorAction.KEEP && previous?.action != EditorAction.KEEP) {
                    normalized =
                        normalized.copy(
                            executionMode = RuleExecutionMode.ACTIVE,
                            priority = suggestedProtectionPriority().toString(),
                        )
                }
                normalized = normalized.copy(root = normalized.toGuidedRoot())
            }
            editor.value =
                normalized.copy(
                    simulation = normalized.simulation.copy(result = null, trace = emptyList()),
                    hasUnsavedChanges = true,
                    showDiscardConfirmation = false,
                )
        }

        /** Evaluates the draft against sample metadata without applying a platform action. */
        fun onRunSimulation() {
            val state = editor.value ?: return
            val rule =
                state
                    .copy(name = state.name.ifBlank { SIMULATION_RULE_NAME })
                    .toRuleOrNull()
            val explanation =
                rule?.let {
                    matcher.explain(
                        buildSimulationSnapshot(state.simulation, it.condition),
                        listOf(it.copy(enabled = true, executionMode = RuleExecutionMode.ACTIVE)),
                    )
                }
            val result =
                if (rule == null) {
                    uiText(R.string.simulator_invalid_rule)
                } else {
                    when (val decision = requireNotNull(explanation).decision) {
                        is MatchDecision.Matched -> uiText(R.string.simulator_matched, decision.action.label())
                        MatchDecision.NoMatch -> uiText(R.string.simulator_no_match)
                    }
                }
            val trace =
                explanation
                    ?.evaluatedRules
                    ?.firstOrNull()
                    ?.condition
                    ?.toSimulationTrace()
                    .orEmpty()
            editor.value = state.copy(simulation = state.simulation.copy(result = result, trace = trace))
        }

        private fun buildSimulationSnapshot(
            sample: RuleSimulationState,
            condition: Condition,
        ): NotificationSnapshot =
            NotificationSnapshot(
                packageName = sample.packageName,
                title = sample.title.takeIf { it.isNotBlank() },
                text = sample.text.takeIf { it.isNotBlank() },
                category = sample.category.takeIf { it.isNotBlank() },
                channelId = sample.channelId.takeIf { it.isNotBlank() },
                postedAtMillis = 0L,
                isOngoing = sample.ongoing,
                mlCategory = sample.mlCategory.takeIf { it.isNotBlank() },
                postedMinuteOfDay = parseMinuteOfDay(sample.localTime),
                isAdvertisement = sample.advertisement,
                semanticIntent = sample.semanticIntent,
                importance = sample.importance,
                isConversation = sample.conversation,
                isForegroundService = sample.foregroundService,
                rateCounts =
                    if (sample.rateKnown) {
                        condition.rateSignals().associateWith {
                            sample.rateCount.toIntOrNull()?.coerceAtLeast(0) ?: 0
                        }
                    } else {
                        emptyMap()
                    },
            )

        fun onDismissEditor() {
            val current = editor.value ?: return
            editor.value =
                if (current.hasUnsavedChanges) {
                    current.copy(showDiscardConfirmation = true)
                } else {
                    null
                }
        }

        fun onCancelDiscardEditor() {
            editor.value = editor.value?.copy(showDiscardConfirmation = false)
        }

        fun onConfirmDiscardEditor() {
            editor.value = null
        }

        fun onSaveRule() {
            val built = editor.value?.toRuleOrNull()
            if (built == null) {
                messages.value = uiText(R.string.message_rule_condition_required)
                return
            }
            // The list owns the enabled toggle; all other editable fields come from the dialog.
            val existing = currentRule(built.id)
            val rule =
                if (existing != null) {
                    built.copy(enabled = existing.enabled)
                } else {
                    built
                }
            launchOp(onSuccess = { editor.value = null }) { ruleRepository.saveRule(rule) }
        }

        fun onToggleRule(
            ruleId: String,
            enabled: Boolean,
        ) {
            if (currentRule(ruleId) == null) return
            launchOp { ruleRepository.setRulesEnabled(setOf(ruleId), enabled) }
        }

        fun onDeleteRule(ruleId: String) {
            val rule = currentRule(ruleId) ?: return
            viewModelScope.launch(dispatcher) {
                runCatchingPreservingCancellation {
                    profileRepository.countUsingRule(ruleId)
                }.onSuccess { count ->
                    pendingDelete.value =
                        RuleDeleteConfirmationUi(
                            ruleId = ruleId,
                            ruleName = rule.name,
                            profileCount = count,
                        )
                }.onFailure {
                    messages.value = uiText(R.string.message_generic_error)
                }
            }
        }

        fun confirmDeleteRule() {
            val ruleId = pendingDelete.value?.ruleId ?: return
            pendingDelete.value = null
            launchOp { ruleRepository.deleteRule(ruleId) }
        }

        fun cancelDeleteRule() {
            pendingDelete.value = null
        }

        fun onUserMessageShown() {
            messages.value = null
        }

        private fun currentRule(id: String): Rule? =
            (rulesResult.value as? DataResult.Success)?.data?.firstOrNull { it.id == id }

        private fun RuleEditorState.withAnalysisWarnings(rules: List<Rule>): RuleEditorState {
            val draft = toRuleOrNull() ?: return copy(warnings = emptyList())
            val draftId = id.ifBlank { DRAFT_RULE_ID }
            val candidates = rules.filterNot { it.id == id } + draft.copy(id = draftId)
            val warnings =
                ruleAnalyzer
                    .analyze(candidates)
                    .filter { it.ruleId == draftId }
                    .map { it.kind.warningText() }
                    .distinct()
            return copy(warnings = warnings)
        }

        private fun suggestedProtectionPriority(): Int {
            val highest =
                (rulesResult.value as? DataResult.Success)
                    ?.data
                    .orEmpty()
                    .maxOfOrNull(Rule::priority)
                    ?: 0
            return if (highest > Int.MAX_VALUE - PROTECTION_PRIORITY_OFFSET) {
                Int.MAX_VALUE
            } else {
                highest + PROTECTION_PRIORITY_OFFSET
            }
        }

        private fun launchOp(
            onSuccess: () -> Unit = {},
            block: suspend () -> Unit,
        ) {
            viewModelScope.launch(dispatcher) {
                runCatchingPreservingCancellation { block() }
                    .onSuccess { onSuccess() }
                    .onFailure { messages.value = uiText(R.string.message_generic_error) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val SOURCE_LIMIT = 100
            const val PROTECTION_PRIORITY_OFFSET = 100
            const val DRAFT_RULE_ID = "__draft__"
            const val SIMULATION_RULE_NAME = "Simulation"
            const val ANALYSIS_DEBOUNCE_MILLIS = 200L
            const val CONTENT_INDEX = 0
            const val EDITOR_INDEX = 1
            const val MESSAGE_INDEX = 2
            const val SETTINGS_INDEX = 3
            const val ACCESS_INDEX = 4
            const val DELETE_INDEX = 5
            const val ANALYSIS_INDEX = 6
        }

        private data class RuleSettings(
            val automationEnabled: Boolean,
            val filteringEnabled: Boolean,
        )

        private data class RuleContent(
            val result: DataResult<List<Rule>>,
            val sources: List<RuleSourceUi>,
        )

        private data class AnalysisInput(
            val rules: List<Rule>,
            val editor: RuleEditorState?,
        )

        private data class RuleAnalysisPresentation(
            val rules: List<Rule> = emptyList(),
            val editor: RuleEditorState? = null,
            val warningsByRule: Map<String, List<UiText>> = emptyMap(),
            val editorWarnings: List<UiText> = emptyList(),
        )
    }

private fun RuleEditorState.toGuidedRoot(): GroupNode {
    val children =
        buildList<ConditionNode> {
            add(LeafNode(nextNodeKey(), LeafKind.PACKAGE, guidedPackageName))
            if (guidedScope == GuidedRuleScope.CHANNEL) {
                add(LeafNode(nextNodeKey(), LeafKind.CHANNEL, guidedChannelId.orEmpty()))
            }
            if (guidedTimeEnabled) {
                add(TimeWindowNode(nextNodeKey(), guidedStartTime, guidedEndTime))
            }
            if (guidedFrequencyEnabled) {
                add(
                    RateNode(
                        key = nextNodeKey(),
                        scope =
                            if (guidedScope == GuidedRuleScope.CHANNEL) {
                                com.alarmcontrol.core.filtering.RateScope.CHANNEL
                            } else {
                                com.alarmcontrol.core.filtering.RateScope.PACKAGE
                            },
                        windowMinutes = guidedFrequencyMinutes,
                        threshold = guidedFrequencyThreshold,
                    ),
                )
            }
        }
    return GroupNode(root.key, anyOf = false, children = children)
}

private fun Condition.rateSignals(): Set<RateSignal> =
    when (this) {
        is Condition.RateAtLeast -> setOf(RateSignal(scope, windowMillis))
        is Condition.AllOf -> conditions.flatMapTo(mutableSetOf()) { it.rateSignals() }
        is Condition.AnyOf -> conditions.flatMapTo(mutableSetOf()) { it.rateSignals() }
        is Condition.Not -> condition.rateSignals()
        else -> emptySet()
    }
