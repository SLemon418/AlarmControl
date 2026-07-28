package com.alarmcontrol.ui.rules

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alarmcontrol.R
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.MAX_CONDITION_VALUE_CHARS
import com.alarmcontrol.core.filtering.MAX_RATE_THRESHOLD
import com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.MAX_RULE_CONDITION_NODES
import com.alarmcontrol.core.filtering.MAX_RULE_NAME_CHARS
import com.alarmcontrol.core.filtering.MIN_RATE_THRESHOLD
import com.alarmcontrol.core.filtering.MIN_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.NotificationImportance
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.service.NotificationAccess
import com.alarmcontrol.ui.NotificationAccessUiState
import com.alarmcontrol.ui.asString
import com.alarmcontrol.ui.designsystem.EmptyState
import com.alarmcontrol.ui.designsystem.ExpressiveHeroCard
import com.alarmcontrol.ui.designsystem.FilterShieldGraphic
import com.alarmcontrol.ui.designsystem.MaxWidthContent
import com.alarmcontrol.ui.designsystem.SectionHeader
import com.alarmcontrol.ui.designsystem.StatusPill

@Composable
fun RulesRoute(
    viewModel: RulesViewModel = hiltViewModel(),
    quickRuleDraft: QuickRuleDraft? = null,
    onQuickRuleConsumed: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenEditor: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Re-check access on every resume — the user grants it in system Settings, then returns here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshNotificationAccess()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(quickRuleDraft) {
        quickRuleDraft?.let {
            viewModel.onCreateRuleFromActivity(it)
            onQuickRuleConsumed()
            onOpenEditor()
        }
    }

    RulesScreen(
        state = state,
        onAddRule = {
            viewModel.onAddRule()
            onOpenEditor()
        },
        onUseTemplate = { template ->
            viewModel.onUseTemplate(template)
            onOpenEditor()
        },
        onEditRule = { id ->
            viewModel.onEditRule(id)
            onOpenEditor()
        },
        onToggleRule = viewModel::onToggleRule,
        onDeleteRule = viewModel::onDeleteRule,
        onConfirmDeleteRule = viewModel::confirmDeleteRule,
        onCancelDeleteRule = viewModel::cancelDeleteRule,
        onUserMessageShown = viewModel::onUserMessageShown,
        onGrantAccess = {
            NotificationAccess.openWithAppDetailsFallback(context, NotificationAccess.settingsIntent())
        },
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun RuleEditorRoute(
    viewModel: RulesViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor = state.editor
    LaunchedEffect(editor) {
        if (editor == null) onClose()
    }
    editor?.let {
        RuleEditorScreen(
            state = it,
            availableSources = state.availableSources,
            onChange = viewModel::onEditorChange,
            onSimulate = viewModel::onRunSimulation,
            onSave = viewModel::onSaveRule,
            onRequestClose = viewModel::onDismissEditor,
            onConfirmDiscard = viewModel::onConfirmDiscardEditor,
            onCancelDiscard = viewModel::onCancelDiscardEditor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    state: RulesUiState,
    onAddRule: () -> Unit,
    onEditRule: (String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onDeleteRule: (String) -> Unit,
    onUserMessageShown: () -> Unit,
    onConfirmDeleteRule: () -> Unit = {},
    onCancelDeleteRule: () -> Unit = {},
    onUseTemplate: (RuleTemplate) -> Unit = {},
    onGrantAccess: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val userMessage = state.userMessage?.asString()
    val addRuleLabel = stringResource(R.string.rules_add)
    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbarHostState.showSnackbar(it)
            onUserMessageShown()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_rules)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddRule,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(addRuleLabel) },
                modifier =
                    Modifier
                        .testTag(RULE_ADD_FAB_TEST_TAG)
                        .semantics { contentDescription = addRuleLabel },
            )
        },
    ) { padding ->
        RulesContent(
            state = state,
            modifier = Modifier.fillMaxSize().padding(padding),
            onAddRule = onAddRule,
            onEditRule = onEditRule,
            onToggleRule = onToggleRule,
            onDeleteRule = onDeleteRule,
            onUseTemplate = onUseTemplate,
            onGrantAccess = onGrantAccess,
            onOpenSettings = onOpenSettings,
        )
    }
    DeleteRuleConfirmation(
        pending = state.pendingDelete,
        onConfirm = onConfirmDeleteRule,
        onCancel = onCancelDeleteRule,
    )
}

@Composable
private fun RulesContent(
    state: RulesUiState,
    modifier: Modifier,
    onAddRule: () -> Unit,
    onEditRule: (String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onDeleteRule: (String) -> Unit,
    onUseTemplate: (RuleTemplate) -> Unit,
    onGrantAccess: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(modifier) {
        when {
            state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.errorMessage != null ->
                Text(
                    text = state.errorMessage.asString(),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            else ->
                MaxWidthContent(Modifier.fillMaxSize()) {
                    RulesList(
                        state = state,
                        onAddRule = onAddRule,
                        onEditRule = onEditRule,
                        onToggleRule = onToggleRule,
                        onDeleteRule = onDeleteRule,
                        onUseTemplate = onUseTemplate,
                        onGrantAccess = onGrantAccess,
                        onOpenSettings = onOpenSettings,
                    )
                }
        }
    }
}

@Composable
private fun RulesList(
    state: RulesUiState,
    onAddRule: () -> Unit,
    onEditRule: (String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onDeleteRule: (String) -> Unit,
    onUseTemplate: (RuleTemplate) -> Unit,
    onGrantAccess: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val accessGranted = state.notificationAccessState == NotificationAccessUiState.GRANTED
    val hasMonitorRule =
        state.rules.any { it.enabled && it.executionMode == RuleExecutionMode.MONITOR }
    val hasObservedRecords = state.availableSources.isNotEmpty()
    val hasActiveRule = state.enabledRuleCount > 0
    val showChecklist = !accessGranted || !hasMonitorRule || !hasObservedRecords || !hasActiveRule

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(RULES_LIST_TEST_TAG),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = 96.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.notificationAccessState) {
            NotificationAccessUiState.CHECKING -> item { NotificationAccessCheckingRow() }
            NotificationAccessUiState.DENIED -> item { NotificationAccessBanner(onGrant = onGrantAccess) }
            NotificationAccessUiState.GRANTED -> Unit
        }
        if (showChecklist) {
            item {
                GettingStartedChecklist(
                    accessGranted = accessGranted,
                    hasMonitorRule = hasMonitorRule,
                    hasObservedRecords = hasObservedRecords,
                    hasActiveRule = hasActiveRule,
                )
            }
        }
        item {
            SetupHealthCard(
                filteringEnabled = state.filteringEnabled,
                enabledRuleCount = state.enabledRuleCount,
                onAddRule = onAddRule,
                onOpenSettings = onOpenSettings,
            )
        }
        item { RuleTemplatePicker(onSelect = onUseTemplate) }
        if (state.showAutomationHint) item { AutomationHintCard() }
        if (state.rules.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Add,
                    title = stringResource(R.string.rules_empty_title),
                    body = stringResource(R.string.rules_empty_body),
                    actionLabel = stringResource(R.string.rules_add),
                    onAction = onAddRule,
                    illustration = { FilterShieldGraphic() },
                )
            }
        } else {
            item { SectionHeader(stringResource(R.string.rules_your_rules)) }
            items(state.rules, key = { it.id }) { rule ->
                RuleRow(
                    item = rule,
                    onToggle = { enabled -> onToggleRule(rule.id, enabled) },
                    onEdit = { onEditRule(rule.id) },
                    onDelete = { onDeleteRule(rule.id) },
                )
            }
        }
    }
}

@Composable
private fun DeleteRuleConfirmation(
    pending: RuleDeleteConfirmationUi?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (pending == null) return
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.rules_delete_confirm_title)) },
        text = {
            Text(
                if (pending.profileCount > 0) {
                    pluralStringResource(
                        R.plurals.rules_delete_profile_warning,
                        pending.profileCount,
                        pending.ruleName,
                        pending.profileCount,
                    )
                } else {
                    stringResource(R.string.rules_delete_confirm_message, pending.ruleName)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun GettingStartedChecklist(
    accessGranted: Boolean,
    hasMonitorRule: Boolean,
    hasObservedRecords: Boolean,
    hasActiveRule: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            ChecklistRow(accessGranted, R.string.onboarding_notification_access)
            ChecklistRow(hasMonitorRule, R.string.onboarding_monitor_rule)
            ChecklistRow(hasObservedRecords, R.string.onboarding_review_records)
            ChecklistRow(hasActiveRule, R.string.onboarding_activate_rule)
            Text(
                stringResource(R.string.onboarding_optional),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChecklistRow(
    complete: Boolean,
    @androidx.annotation.StringRes textRes: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (complete) "✓" else "○", color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (complete) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
    }
}

@Composable
private fun NotificationAccessCheckingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(
            stringResource(R.string.notification_access_checking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RuleTemplatePicker(
    onSelect: (RuleTemplate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.rule_templates))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RuleTemplate.entries.forEach { template ->
                AssistChip(
                    onClick = { onSelect(template) },
                    label = { Text(stringResource(template.titleRes)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleRow(
    item: RuleListItem,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var warningsExpanded by remember(item.id) { mutableStateOf(false) }
    ElevatedCard(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val ruleName = if (item.name.isBlank()) stringResource(R.string.rule_untitled) else item.name
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ruleName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                val toggleDescription = stringResource(R.string.rule_toggle, ruleName)
                Switch(
                    checked = item.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = toggleDescription },
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rules_delete)) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            Text(
                item.summary.asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    text =
                        stringResource(
                            if (item.executionMode == RuleExecutionMode.ACTIVE) {
                                R.string.rule_mode_active
                            } else {
                                R.string.rule_mode_monitor
                            },
                        ),
                    positive = item.executionMode == RuleExecutionMode.ACTIVE,
                )
                StatusPill(
                    text = item.actionLabel.asString(),
                    positive = item.enabled,
                )
            }
            if (item.warnings.isNotEmpty()) {
                TextButton(onClick = { warningsExpanded = !warningsExpanded }) {
                    Text(
                        pluralStringResource(
                            R.plurals.rule_warning_count,
                            item.warnings.size,
                            item.warnings.size,
                        ),
                    )
                }
                if (warningsExpanded) {
                    item.warnings.forEach { warning ->
                        Text(
                            warning.asString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(
    state: RuleEditorState,
    availableSources: List<RuleSourceUi> = emptyList(),
    onChange: (RuleEditorState) -> Unit,
    onSimulate: () -> Unit,
    onSave: () -> Unit,
    onRequestClose: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onCancelDiscard: () -> Unit,
) {
    BackHandler(enabled = !state.isSaving, onBack = onRequestClose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isEditing) R.string.rule_edit else R.string.rule_new)) },
                navigationIcon = {
                    IconButton(onClick = onRequestClose, enabled = !state.isSaving) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = state.canSave) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        MaxWidthContent(Modifier.fillMaxSize().padding(padding)) {
            RuleEditorContent(
                state = state,
                availableSources = availableSources,
                onChange = onChange,
                onSimulate = onSimulate,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
    if (state.showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelDiscard,
            title = { Text(stringResource(R.string.discard_changes_title)) },
            text = { Text(stringResource(R.string.discard_changes_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmDiscard) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelDiscard) { Text(stringResource(R.string.keep_editing)) }
            },
        )
    }
}

@Composable
private fun RuleEditorContent(
    state: RuleEditorState,
    availableSources: List<RuleSourceUi>,
    onChange: (RuleEditorState) -> Unit,
    onSimulate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.editorMode == RuleEditorMode.GUIDED && !state.isEditing) {
            GuidedRuleEditor(state, availableSources, onChange)
        } else {
            RuleBasicsFields(state, onChange)
            EditorSectionHeading(R.string.conditions)
            val remainingNodes = (MAX_RULE_CONDITION_NODES - state.root.nodeCount()).coerceAtLeast(0)
            ConditionNodeEditor(
                node = state.root,
                // The root is always a group; the group editor only ever produces a group.
                onChange = { onChange(state.copy(root = it as GroupNode)) },
                onRemove = null,
                depth = 0,
                remainingNodes = remainingNodes,
            )
            RuleModeEditor(state, onChange)
            RuleActionEditor(state, onChange)
        }
        RuleWarningCards(state.warnings)
        RuleSimulator(
            state = state.simulation,
            condition = state.root.toConditionOrNull(),
            onChange = { onChange(state.copy(simulation = it)) },
            onRun = onSimulate,
        )
    }
}

@Composable
private fun GuidedRuleEditor(
    state: RuleEditorState,
    availableSources: List<RuleSourceUi>,
    onChange: (RuleEditorState) -> Unit,
) {
    Text(
        stringResource(R.string.guided_rule_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    EditorSectionHeading(R.string.guided_rule_source)
    GuidedSourcePicker(state, availableSources, onChange)
    if (state.guidedPackageName.isNotBlank()) {
        GuidedScopePicker(state, onChange)
    }

    EditorSectionHeading(R.string.guided_rule_action)
    GuidedActionEditor(state, onChange)
    RuleModeEditor(state, onChange)

    EditorSectionHeading(R.string.guided_rule_optional)
    SettingToggleRow(
        title = stringResource(R.string.guided_rule_time),
        checked = state.guidedTimeEnabled,
        onCheckedChange = { onChange(state.copy(guidedTimeEnabled = it)) },
    )
    if (state.guidedTimeEnabled) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.guidedStartTime,
                onValueChange = { onChange(state.copy(guidedStartTime = it)) },
                label = { Text(stringResource(R.string.condition_from_time)) },
                isError = parseMinuteOfDay(state.guidedStartTime) == null,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.guidedEndTime,
                onValueChange = { onChange(state.copy(guidedEndTime = it)) },
                label = { Text(stringResource(R.string.condition_to_time)) },
                isError = parseMinuteOfDay(state.guidedEndTime) == null,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
    SettingToggleRow(
        title = stringResource(R.string.guided_rule_frequency),
        checked = state.guidedFrequencyEnabled,
        onCheckedChange = { onChange(state.copy(guidedFrequencyEnabled = it)) },
    )
    if (state.guidedFrequencyEnabled) {
        val windowInvalid = state.guidedFrequencyMinutes.toLongOrNull() !in VALID_RATE_WINDOW_MINUTES
        val thresholdInvalid = state.guidedFrequencyThreshold.toIntOrNull() !in VALID_RATE_THRESHOLDS
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.guidedFrequencyMinutes,
                onValueChange = {
                    onChange(state.copy(guidedFrequencyMinutes = it.filter(Char::isDigit)))
                },
                label = { Text(stringResource(R.string.rate_window_minutes)) },
                supportingText = {
                    if (windowInvalid) Text(stringResource(R.string.validation_rate_window))
                },
                isError = windowInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.guidedFrequencyThreshold,
                onValueChange = {
                    onChange(state.copy(guidedFrequencyThreshold = it.filter(Char::isDigit)))
                },
                label = { Text(stringResource(R.string.rate_threshold)) },
                supportingText = {
                    if (thresholdInvalid) Text(stringResource(R.string.validation_rate_threshold))
                },
                isError = thresholdInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }

    EditorSectionHeading(R.string.guided_rule_name)
    RuleNameField(state.name) { onChange(state.copy(name = it)) }
    TextButton(onClick = { onChange(state.copy(editorMode = RuleEditorMode.ADVANCED)) }) {
        Text(stringResource(R.string.guided_rule_advanced))
    }
}

@Composable
private fun GuidedSourcePicker(
    state: RuleEditorState,
    availableSources: List<RuleSourceUi>,
    onChange: (RuleEditorState) -> Unit,
) {
    var pickerVisible by remember { mutableStateOf(false) }
    var sourceQuery by remember { mutableStateOf("") }
    var manualEntryVisible by
        remember(state.id) {
            mutableStateOf(
                availableSources.isEmpty() ||
                    (state.guidedPackageName.isNotBlank() && state.guidedAppName.isBlank()),
            )
        }
    GuidedSourceSelection(
        state = state,
        availableSources = availableSources,
        manualEntryVisible = manualEntryVisible,
        onChooseSource = {
            sourceQuery = ""
            pickerVisible = true
        },
        onManualEntry = { manualEntryVisible = true },
        onChange = onChange,
    )
    if (pickerVisible) {
        GuidedSourceDialog(
            sources = availableSources.filteredBy(sourceQuery),
            query = sourceQuery,
            onQueryChange = { sourceQuery = it.take(MAX_SOURCE_QUERY_CHARS) },
            onSelect = { source ->
                pickerVisible = false
                manualEntryVisible = false
                onChange(state.withGuidedSource(source))
            },
            onDismiss = { pickerVisible = false },
        )
    }
}

@Composable
private fun GuidedSourceSelection(
    state: RuleEditorState,
    availableSources: List<RuleSourceUi>,
    manualEntryVisible: Boolean,
    onChooseSource: () -> Unit,
    onManualEntry: () -> Unit,
    onChange: (RuleEditorState) -> Unit,
) {
    OutlinedButton(
        onClick = onChooseSource,
        enabled = availableSources.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (state.guidedAppName.isBlank()) {
                stringResource(R.string.guided_rule_choose_source)
            } else {
                state.guidedSourceLabel()
            },
            modifier = Modifier.weight(1f),
        )
    }
    if (availableSources.isEmpty()) {
        Text(
            stringResource(R.string.guided_rule_no_sources),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!manualEntryVisible && availableSources.isNotEmpty()) {
        TextButton(onClick = onManualEntry) {
            Text(stringResource(R.string.guided_rule_enter_manually))
        }
    } else {
        GuidedManualPackageField(state, onChange)
    }
}

@Composable
private fun GuidedManualPackageField(
    state: RuleEditorState,
    onChange: (RuleEditorState) -> Unit,
) {
    val packageInvalid =
        state.guidedPackageName.isNotBlank() &&
            !isLikelyAndroidPackage(state.guidedPackageName)
    OutlinedTextField(
        value = state.guidedPackageName,
        onValueChange = {
            onChange(
                state.copy(
                    guidedPackageName = it.take(MAX_CONDITION_VALUE_CHARS),
                    guidedAppName = "",
                    guidedChannelId = null,
                    guidedChannelName = null,
                    guidedScope = GuidedRuleScope.APP,
                    simulation = state.simulation.copy(packageName = it, channelId = ""),
                ),
            )
        },
        label = { Text(stringResource(R.string.guided_rule_manual_package)) },
        supportingText = {
            Text(
                stringResource(
                    if (packageInvalid) {
                        R.string.guided_rule_invalid_package
                    } else {
                        R.string.guided_rule_manual_package_help
                    },
                ),
            )
        },
        isError = packageInvalid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GuidedSourceDialog(
    sources: List<RuleSourceUi>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (RuleSourceUi) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.guided_rule_choose_source)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.guided_rule_search_source)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sources.isEmpty()) {
                    Text(stringResource(R.string.guided_rule_no_source_matches))
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(sources, key = RuleSourceUi::key) { source ->
                            TextButton(
                                onClick = { onSelect(source) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(source.appName)
                                    Text(
                                        source.channelName ?: source.channelId ?: source.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun List<RuleSourceUi>.filteredBy(query: String): List<RuleSourceUi> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return this
    return filter { source ->
        source.appName.contains(normalized, ignoreCase = true) ||
            source.packageName.contains(normalized, ignoreCase = true) ||
            source.channelName?.contains(normalized, ignoreCase = true) == true ||
            source.channelId?.contains(normalized, ignoreCase = true) == true
    }
}

private fun RuleEditorState.withGuidedSource(source: RuleSourceUi): RuleEditorState =
    copy(
        name = name.ifBlank { source.appName },
        guidedPackageName = source.packageName,
        guidedAppName = source.appName,
        guidedChannelId = source.channelId,
        guidedChannelName = source.channelName,
        guidedScope = if (source.channelId == null) GuidedRuleScope.APP else GuidedRuleScope.CHANNEL,
        simulation =
            simulation.copy(
                packageName = source.packageName,
                channelId = source.channelId.orEmpty(),
            ),
    )

@Composable
private fun GuidedScopePicker(
    state: RuleEditorState,
    onChange: (RuleEditorState) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.guidedScope == GuidedRuleScope.APP,
            onClick = { onChange(state.copy(guidedScope = GuidedRuleScope.APP)) },
            label = { Text(stringResource(R.string.guided_rule_whole_app)) },
        )
        FilterChip(
            selected = state.guidedScope == GuidedRuleScope.CHANNEL,
            onClick = { onChange(state.copy(guidedScope = GuidedRuleScope.CHANNEL)) },
            enabled = !state.guidedChannelId.isNullOrBlank(),
            label = { Text(stringResource(R.string.guided_rule_this_channel)) },
        )
    }
}

@Composable
private fun GuidedActionEditor(
    state: RuleEditorState,
    onChange: (RuleEditorState) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionChip(R.string.action_cancel, state.action == EditorAction.CANCEL) {
            onChange(state.copy(action = EditorAction.CANCEL))
        }
        ActionChip(R.string.action_snooze, state.action == EditorAction.SNOOZE) {
            onChange(state.copy(action = EditorAction.SNOOZE))
        }
        ActionChip(R.string.action_keep, state.action == EditorAction.KEEP) {
            onChange(state.copy(action = EditorAction.KEEP))
        }
    }
    if (state.action == EditorAction.SNOOZE) SnoozeDurationField(state, onChange)
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (state.executionMode == RuleExecutionMode.MONITOR) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
            ),
    ) {
        Text(
            stringResource(
                if (state.executionMode == RuleExecutionMode.MONITOR) {
                    R.string.guided_rule_monitor_safe
                } else {
                    R.string.guided_rule_active_effect
                },
            ),
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun RuleEditorState.guidedSourceLabel(): String =
    listOfNotNull(
        guidedAppName.ifBlank { guidedPackageName },
        guidedChannelName ?: guidedChannelId,
    ).joinToString(" · ")

@Composable
private fun RuleBasicsFields(
    state: RuleEditorState,
    onChange: (RuleEditorState) -> Unit,
) {
    RuleNameField(state.name) { onChange(state.copy(name = it)) }
    val priorityInvalid = state.priority.toIntOrNull() == null
    OutlinedTextField(
        value = state.priority,
        onValueChange = { value -> onChange(state.copy(priority = value.asSignedIntegerInput())) },
        label = { Text(stringResource(R.string.priority)) },
        singleLine = true,
        isError = priorityInvalid,
        supportingText = if (priorityInvalid) ({ Text(stringResource(R.string.validation_integer)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun RuleModeEditor(
    state: RuleEditorState,
    onChange: (RuleEditorState) -> Unit,
) {
    EditorSectionHeading(R.string.rule_mode)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.executionMode == RuleExecutionMode.ACTIVE,
            onClick = { onChange(state.copy(executionMode = RuleExecutionMode.ACTIVE)) },
            label = { Text(stringResource(R.string.rule_mode_active)) },
        )
        FilterChip(
            selected = state.executionMode == RuleExecutionMode.MONITOR,
            onClick = { onChange(state.copy(executionMode = RuleExecutionMode.MONITOR)) },
            label = { Text(stringResource(R.string.rule_mode_monitor)) },
        )
    }
    if (state.executionMode == RuleExecutionMode.MONITOR) {
        Text(stringResource(R.string.rule_mode_monitor_explanation), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RuleActionEditor(
    state: RuleEditorState,
    onChange: (RuleEditorState) -> Unit,
) {
    EditorSectionHeading(R.string.action)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionChip(R.string.action_cancel, state.action == EditorAction.CANCEL) {
            onChange(state.copy(action = EditorAction.CANCEL))
        }
        ActionChip(R.string.action_snooze, state.action == EditorAction.SNOOZE) {
            onChange(state.copy(action = EditorAction.SNOOZE))
        }
        ActionChip(R.string.action_log_only, state.action == EditorAction.MARK_READ) {
            onChange(state.copy(action = EditorAction.MARK_READ))
        }
        ActionChip(R.string.action_keep, state.action == EditorAction.KEEP) {
            onChange(state.copy(action = EditorAction.KEEP))
        }
    }
    if (state.action == EditorAction.SNOOZE) SnoozeDurationField(state, onChange)
}

@Composable
private fun SnoozeDurationField(
    state: RuleEditorState,
    onChange: (RuleEditorState) -> Unit,
) {
    val invalid = state.snoozeMinutes.toLongOrNull() !in VALID_SNOOZE_MINUTES
    OutlinedTextField(
        value = state.snoozeMinutes,
        onValueChange = { onChange(state.copy(snoozeMinutes = it.filter(Char::isDigit))) },
        label = { Text(stringResource(R.string.snooze_minutes)) },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(R.string.validation_snooze_minutes)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun RuleWarningCards(warnings: List<com.alarmcontrol.ui.UiText>) {
    var expanded by remember(warnings) { mutableStateOf(false) }
    if (warnings.isNotEmpty()) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(pluralStringResource(R.plurals.rule_warning_count, warnings.size, warnings.size))
        }
    }
    if (expanded) {
        warnings.forEach { warning ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    warning.asString(),
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun EditorSectionHeading(
    @androidx.annotation.StringRes textRes: Int,
) {
    Text(
        stringResource(textRes),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun RuleNameField(
    name: String,
    onNameChange: (String) -> Unit,
) {
    val tooLong = name.length > MAX_RULE_NAME_CHARS
    OutlinedTextField(
        value = name,
        onValueChange = { onNameChange(it.take(MAX_RULE_NAME_CHARS)) },
        label = { Text(stringResource(R.string.name)) },
        singleLine = true,
        isError = tooLong,
        supportingText =
            if (tooLong) {
                ({ Text(stringResource(R.string.validation_name_too_long, MAX_RULE_NAME_CHARS)) })
            } else {
                null
            },
    )
}

@Composable
private fun RuleSimulator(
    state: RuleSimulationState,
    condition: Condition?,
    onChange: (RuleSimulationState) -> Unit,
    onRun: () -> Unit,
) {
    TextButton(onClick = { onChange(state.copy(expanded = !state.expanded)) }) {
        Text(stringResource(if (state.expanded) R.string.simulator_hide else R.string.simulator_show))
    }
    if (!state.expanded) return

    Text(stringResource(R.string.simulator_summary), style = MaterialTheme.typography.bodySmall)
    SimulatorTextFields(state, condition, onChange)
    val invalidTime = condition.requiresSignal(SimulationSignal.TIME) && parseMinuteOfDay(state.localTime) == null
    SimulatorSignalFields(state, condition, invalidTime, onChange)
    Button(onClick = onRun, enabled = !invalidTime) {
        Text(stringResource(R.string.simulator_run))
    }
    state.result?.let { result ->
        SimulationResultCard(result, state.trace)
    }
}

@Composable
private fun SimulatorTextFields(
    state: RuleSimulationState,
    condition: Condition?,
    onChange: (RuleSimulationState) -> Unit,
) {
    if (condition.requiresSignal(SimulationSignal.PACKAGE)) {
        SimulatorField(state.packageName, R.string.simulator_package) { onChange(state.copy(packageName = it)) }
    }
    if (condition.requiresSignal(SimulationSignal.TITLE)) {
        SimulatorField(state.title, R.string.simulator_title) { onChange(state.copy(title = it)) }
    }
    if (condition.requiresSignal(SimulationSignal.TEXT)) {
        SimulatorField(state.text, R.string.simulator_text) { onChange(state.copy(text = it)) }
    }
    if (condition.requiresSignal(SimulationSignal.CATEGORY)) {
        SimulatorField(state.category, R.string.simulator_category) { onChange(state.copy(category = it)) }
    }
    if (condition.requiresSignal(SimulationSignal.CHANNEL)) {
        SimulatorField(state.channelId, R.string.simulator_channel) { onChange(state.copy(channelId = it)) }
    }
    if (condition.requiresSignal(SimulationSignal.ML_CATEGORY)) {
        SimulatorField(state.mlCategory, R.string.simulator_ml_category) { onChange(state.copy(mlCategory = it)) }
    }
}

@Composable
private fun SimulatorSignalFields(
    state: RuleSimulationState,
    condition: Condition?,
    invalidTime: Boolean,
    onChange: (RuleSimulationState) -> Unit,
) {
    if (condition.requiresSignal(SimulationSignal.TIME)) {
        OutlinedTextField(
            value = state.localTime,
            onValueChange = { onChange(state.copy(localTime = it)) },
            label = { Text(stringResource(R.string.simulator_time)) },
            singleLine = true,
            isError = invalidTime,
            supportingText = if (invalidTime) ({ Text(stringResource(R.string.validation_time)) }) else null,
        )
    }
    if (condition.requiresSignal(SimulationSignal.ONGOING)) {
        FilterChip(
            selected = state.ongoing,
            onClick = { onChange(state.copy(ongoing = !state.ongoing)) },
            label = { Text(stringResource(R.string.simulator_ongoing)) },
        )
    }
    if (condition.requiresSignal(SimulationSignal.ADVERTISEMENT)) {
        NullableBooleanSelector(
            value = state.advertisement,
            labelRes = R.string.simulator_advertisement,
            onChange = { onChange(state.copy(advertisement = it)) },
        )
    }
    if (condition.requiresSignal(SimulationSignal.SEMANTIC)) {
        SemanticIntentSelector(state.semanticIntent) {
            onChange(state.copy(semanticIntent = it))
        }
    }
    if (condition.requiresSignal(SimulationSignal.IMPORTANCE)) {
        ImportanceSelector(state.importance) {
            onChange(state.copy(importance = it))
        }
    }
    if (condition.requiresSignal(SimulationSignal.CONVERSATION)) {
        NullableBooleanSelector(
            value = state.conversation,
            labelRes = R.string.simulator_conversation,
            onChange = { onChange(state.copy(conversation = it)) },
        )
    }
    if (condition.requiresSignal(SimulationSignal.FOREGROUND_SERVICE)) {
        NullableBooleanSelector(
            value = state.foregroundService,
            labelRes = R.string.simulator_foreground_service,
            onChange = { onChange(state.copy(foregroundService = it)) },
        )
    }
    if (condition.requiresSignal(SimulationSignal.RATE)) {
        RateSimulationInput(state, onChange)
    }
}

@Composable
private fun SimulationResultCard(
    result: com.alarmcontrol.ui.UiText,
    trace: List<SimulationTraceItem>,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(result.asString(), style = MaterialTheme.typography.titleSmall)
            trace.forEach { item ->
                val status =
                    when (item.status) {
                        SimulationTraceStatus.MATCH -> stringResource(R.string.simulator_trace_match)
                        SimulationTraceStatus.NO_MATCH -> stringResource(R.string.simulator_trace_no_match)
                        SimulationTraceStatus.UNKNOWN -> stringResource(R.string.simulator_trace_unknown)
                    }
                Text(
                    text = stringResource(R.string.simulator_trace_line, status, item.condition.asString()),
                    modifier = Modifier.padding(start = (item.depth * TRACE_INDENT_DP).dp),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (item.status == SimulationTraceStatus.UNKNOWN) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                )
            }
        }
    }
}

@Composable
private fun NullableBooleanSelector(
    value: Boolean?,
    @androidx.annotation.StringRes labelRes: Int,
    onChange: (Boolean?) -> Unit,
) {
    Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            null to R.string.simulator_unknown,
            true to R.string.yes,
            false to R.string.no,
        ).forEach { (candidate, textRes) ->
            FilterChip(
                selected = value == candidate,
                onClick = { onChange(candidate) },
                label = { Text(stringResource(textRes)) },
            )
        }
    }
}

@Composable
private fun SemanticIntentSelector(
    value: SemanticIntent?,
    onChange: (SemanticIntent?) -> Unit,
) {
    Text(stringResource(R.string.simulator_semantic_intent), style = MaterialTheme.typography.labelLarge)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = value == null,
            onClick = { onChange(null) },
            label = { Text(stringResource(R.string.simulator_unknown)) },
        )
        SemanticIntent.entries
            .filterNot { it == SemanticIntent.AMBIGUOUS }
            .forEach { intent ->
                FilterChip(
                    selected = value == intent,
                    onClick = { onChange(intent) },
                    label = { Text(stringResource(intent.labelRes())) },
                )
            }
    }
}

@Composable
private fun ImportanceSelector(
    value: NotificationImportance?,
    onChange: (NotificationImportance?) -> Unit,
) {
    Text(stringResource(R.string.simulator_importance), style = MaterialTheme.typography.labelLarge)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = value == null,
            onClick = { onChange(null) },
            label = { Text(stringResource(R.string.simulator_unknown)) },
        )
        NotificationImportance.entries.forEach { importance ->
            FilterChip(
                selected = value == importance,
                onClick = { onChange(importance) },
                label = { Text(stringResource(importance.labelRes())) },
            )
        }
    }
}

@Composable
private fun RateSimulationInput(
    state: RuleSimulationState,
    onChange: (RuleSimulationState) -> Unit,
) {
    Text(stringResource(R.string.simulator_rate), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !state.rateKnown,
            onClick = { onChange(state.copy(rateKnown = false)) },
            label = { Text(stringResource(R.string.simulator_unknown)) },
        )
        FilterChip(
            selected = state.rateKnown,
            onClick = { onChange(state.copy(rateKnown = true)) },
            label = { Text(stringResource(R.string.simulator_known)) },
        )
    }
    if (state.rateKnown) {
        OutlinedTextField(
            value = state.rateCount,
            onValueChange = { onChange(state.copy(rateCount = it.filter(Char::isDigit))) },
            label = { Text(stringResource(R.string.simulator_rate_count)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SimulatorField(
    value: String,
    labelRes: Int,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

private enum class SimulationSignal {
    PACKAGE,
    TITLE,
    TEXT,
    CATEGORY,
    CHANNEL,
    ONGOING,
    ML_CATEGORY,
    ADVERTISEMENT,
    SEMANTIC,
    CONVERSATION,
    FOREGROUND_SERVICE,
    IMPORTANCE,
    RATE,
    TIME,
}

private fun Condition?.requiresSignal(signal: SimulationSignal): Boolean =
    when (this) {
        null -> false
        is Condition.PackageEquals -> signal == SimulationSignal.PACKAGE
        is Condition.TitleContains -> signal == SimulationSignal.TITLE
        is Condition.TextContains -> signal == SimulationSignal.TEXT
        is Condition.CategoryEquals -> signal == SimulationSignal.CATEGORY
        is Condition.ChannelEquals -> signal == SimulationSignal.CHANNEL
        is Condition.Ongoing -> signal == SimulationSignal.ONGOING
        is Condition.MlCategoryEquals -> signal == SimulationSignal.ML_CATEGORY
        is Condition.IsAdvertisement -> signal == SimulationSignal.ADVERTISEMENT
        is Condition.SemanticIntentEquals -> signal == SimulationSignal.SEMANTIC
        is Condition.Conversation -> signal == SimulationSignal.CONVERSATION
        is Condition.ForegroundService -> signal == SimulationSignal.FOREGROUND_SERVICE
        is Condition.ImportanceAtLeast -> signal == SimulationSignal.IMPORTANCE
        is Condition.RateAtLeast -> signal == SimulationSignal.RATE
        is Condition.TimeWindow -> signal == SimulationSignal.TIME
        is Condition.AllOf -> conditions.any { it.requiresSignal(signal) }
        is Condition.AnyOf -> conditions.any { it.requiresSignal(signal) }
        is Condition.Not -> condition.requiresSignal(signal)
    }

private fun SemanticIntent.labelRes(): Int =
    when (this) {
        SemanticIntent.MARKETING -> R.string.semantic_marketing
        SemanticIntent.TRANSACTIONAL -> R.string.semantic_transactional
        SemanticIntent.SECURITY -> R.string.semantic_security
        SemanticIntent.DELIVERY -> R.string.semantic_delivery
        SemanticIntent.SOCIAL -> R.string.semantic_social
        SemanticIntent.OTHER -> R.string.semantic_other
        SemanticIntent.AMBIGUOUS -> R.string.semantic_ambiguous
    }

private fun NotificationImportance.labelRes(): Int =
    when (this) {
        NotificationImportance.MIN -> R.string.importance_min
        NotificationImportance.LOW -> R.string.importance_low
        NotificationImportance.DEFAULT -> R.string.importance_default
        NotificationImportance.HIGH -> R.string.importance_high
        NotificationImportance.MAX -> R.string.importance_max
    }

/** Recursively edits one [ConditionNode]; child changes flow up via [onChange] (CLAUDE.md §8). */
@Composable
private fun ConditionNodeEditor(
    node: ConditionNode,
    onChange: (ConditionNode) -> Unit,
    onRemove: (() -> Unit)?,
    depth: Int,
    remainingNodes: Int,
) {
    val branchColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    if (depth > 0) {
                        val x = 1.dp.toPx()
                        drawLine(
                            color = branchColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                }.padding(start = if (depth > 0) 8.dp else 0.dp),
    ) {
        when (node) {
            is GroupNode -> GroupNodeEditor(node, onChange, onRemove, depth, remainingNodes)
            is NotNode -> NotNodeEditor(node, onChange, onRemove, depth, remainingNodes)
            is LeafNode -> LeafNodeEditor(node, onChange, onRemove)
            is TimeWindowNode -> TimeWindowNodeEditor(node, onChange, onRemove)
            is RateNode -> RateNodeEditor(node, onChange, onRemove)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupNodeEditor(
    node: GroupNode,
    onChange: (ConditionNode) -> Unit,
    onRemove: (() -> Unit)?,
    depth: Int,
    remainingNodes: Int,
) {
    val canAddLeaf = canAddLeafCondition(depth, remainingNodes)
    val canAddContainer = canAddContainerCondition(depth, remainingNodes)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !node.anyOf,
                onClick = { onChange(node.copy(anyOf = false)) },
                label = { Text(stringResource(R.string.match_all)) },
            )
            FilterChip(
                selected = node.anyOf,
                onClick = { onChange(node.copy(anyOf = true)) },
                label = { Text(stringResource(R.string.match_any)) },
            )
            if (onRemove != null) TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
        }
        node.validationError()?.let {
            Text(
                stringResource(it.messageRes),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        node.children.forEachIndexed { index, child ->
            key(child.key) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    // Reorder handles change sibling order, i.e. the evaluation/short-circuit order.
                    if (node.children.size > 1) {
                        ReorderControls(
                            canMoveUp = index > 0,
                            canMoveDown = index < node.children.lastIndex,
                            onMoveUp = { onChange(node.copy(children = node.children.movedUp(index))) },
                            onMoveDown = { onChange(node.copy(children = node.children.movedDown(index))) },
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        ConditionNodeEditor(
                            node = child,
                            onChange = { onChange(node.copy(children = node.children.replacedAt(index, it))) },
                            onRemove = { onChange(node.copy(children = node.children.withoutIndex(index))) },
                            depth = depth + 1,
                            remainingNodes = remainingNodes,
                        )
                    }
                }
            }
        }
        ConditionAdditionControls(
            node = node,
            onChange = onChange,
            canAddLeaf = canAddLeaf,
            canAddContainer = canAddContainer,
            remainingNodes = remainingNodes,
            depth = depth,
        )
    }
}

@Composable
private fun ConditionAdditionControls(
    node: GroupNode,
    onChange: (ConditionNode) -> Unit,
    canAddLeaf: Boolean,
    canAddContainer: Boolean,
    remainingNodes: Int,
    depth: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = { onChange(node.copy(children = node.children + newLeafNode())) },
                enabled = canAddLeaf,
            ) { Text(stringResource(R.string.add_condition)) }
            TextButton(
                onClick = { onChange(node.copy(children = node.children + newGroupNode())) },
                enabled = canAddContainer,
            ) {
                Text(stringResource(R.string.add_group))
            }
            TextButton(
                onClick = { onChange(node.copy(children = node.children + newNotNode())) },
                enabled = canAddContainer,
            ) {
                Text(stringResource(R.string.add_not))
            }
            TextButton(
                onClick = { onChange(node.copy(children = node.children + newTimeWindowNode())) },
                enabled = canAddLeaf,
            ) { Text(stringResource(R.string.add_time)) }
            TextButton(
                onClick = { onChange(node.copy(children = node.children + newRateNode())) },
                enabled = canAddLeaf,
            ) { Text(stringResource(R.string.add_rate)) }
        }
        val limitMessage =
            when {
                remainingNodes < 2 && depth == 0 -> R.string.validation_condition_node_limit
                remainingNodes >= 2 && !canAddContainer -> R.string.validation_condition_depth_limit
                else -> null
            }
        if (limitMessage != null) {
            Text(
                text = stringResource(limitMessage),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NotNodeEditor(
    node: NotNode,
    onChange: (ConditionNode) -> Unit,
    onRemove: (() -> Unit)?,
    depth: Int,
    remainingNodes: Int,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.not_operator),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (onRemove != null) TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
        }
        ConditionNodeEditor(
            node = node.child,
            onChange = { onChange(node.copy(child = it)) },
            onRemove = null,
            depth = depth + 1,
            remainingNodes = remainingNodes,
        )
    }
}

@Composable
private fun LeafNodeEditor(
    node: LeafNode,
    onChange: (ConditionNode) -> Unit,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { expanded = true }) { Text(stringResource(node.kind.labelRes)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LeafKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(stringResource(kind.labelRes)) },
                        onClick = {
                            expanded = false
                            onChange(node.copy(kind = kind, value = kind.defaultValue()))
                        },
                    )
                }
            }
        }
        Box(Modifier.weight(1f)) {
            LeafValueEditor(node = node, onChange = onChange)
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove))
            }
        }
    }
}

@Composable
private fun LeafValueEditor(
    node: LeafNode,
    onChange: (ConditionNode) -> Unit,
) {
    val validation = node.validationError()
    when (node.kind) {
        LeafKind.ONGOING,
        LeafKind.IS_ADVERTISEMENT,
        LeafKind.CONVERSATION,
        LeafKind.FOREGROUND_SERVICE,
        ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(true, false).forEach { value ->
                    FilterChip(
                        selected = node.value == value.toString(),
                        onClick = { onChange(node.copy(value = value.toString())) },
                        label = { Text(value.toString()) },
                    )
                }
            }
        LeafKind.SEMANTIC_INTENT ->
            EnumValueDropdown(
                value = node.value,
                values =
                    SemanticIntent.entries
                        .filterNot { it == SemanticIntent.AMBIGUOUS }
                        .map { it.name },
                onChange = { onChange(node.copy(value = it)) },
            )
        LeafKind.IMPORTANCE_AT_LEAST ->
            EnumValueDropdown(
                value = node.value,
                values = NotificationImportance.entries.map { it.name },
                onChange = { onChange(node.copy(value = it)) },
            )
        else ->
            OutlinedTextField(
                value = node.value,
                onValueChange = { onChange(node.copy(value = it.take(MAX_CONDITION_VALUE_CHARS))) },
                label = { Text(stringResource(R.string.condition_value)) },
                singleLine = true,
                isError = validation != null,
                supportingText = validation?.let { error -> ({ Text(stringResource(error.messageRes)) }) },
                modifier = Modifier.fillMaxWidth(),
            )
    }
}

@Composable
private fun EnumValueDropdown(
    value: String,
    values: List<String>,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(value.ifBlank { stringResource(R.string.select) }) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(candidate) },
                    onClick = {
                        expanded = false
                        onChange(candidate)
                    },
                )
            }
        }
    }
}

@Composable
private fun RateNodeEditor(
    node: RateNode,
    onChange: (ConditionNode) -> Unit,
    onRemove: (() -> Unit)?,
) {
    val validation = node.validationError()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = node.scope == RateScope.PACKAGE,
                onClick = { onChange(node.copy(scope = RateScope.PACKAGE)) },
                label = { Text(stringResource(R.string.rate_scope_package)) },
            )
            FilterChip(
                selected = node.scope == RateScope.CHANNEL,
                onClick = { onChange(node.copy(scope = RateScope.CHANNEL)) },
                label = { Text(stringResource(R.string.rate_scope_channel)) },
            )
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove))
                }
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RATE_WINDOW_PRESET_MINUTES.forEach { minutes ->
                FilterChip(
                    selected = node.windowMinutes == minutes.toString(),
                    onClick = { onChange(node.copy(windowMinutes = minutes.toString())) },
                    label = { Text(stringResource(R.string.rate_minutes, minutes)) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = node.windowMinutes,
                onValueChange = { onChange(node.copy(windowMinutes = it.filter(Char::isDigit))) },
                label = { Text(stringResource(R.string.rate_window_minutes)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = validation == ConditionValidation.RATE_WINDOW,
                supportingText =
                    if (validation == ConditionValidation.RATE_WINDOW) {
                        ({ Text(stringResource(validation.messageRes)) })
                    } else {
                        null
                    },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = node.threshold,
                onValueChange = { onChange(node.copy(threshold = it.filter(Char::isDigit))) },
                label = { Text(stringResource(R.string.rate_threshold)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = validation == ConditionValidation.RATE_THRESHOLD,
                supportingText =
                    if (validation == ConditionValidation.RATE_THRESHOLD) {
                        ({ Text(stringResource(validation.messageRes)) })
                    } else {
                        null
                    },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TimeWindowNodeEditor(
    node: TimeWindowNode,
    onChange: (ConditionNode) -> Unit,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val startInvalid = parseMinuteOfDay(node.start) == null
        val endInvalid = parseMinuteOfDay(node.end) == null
        OutlinedTextField(
            value = node.start,
            onValueChange = { onChange(node.copy(start = it)) },
            label = { Text(stringResource(R.string.condition_from_time)) },
            singleLine = true,
            isError = startInvalid,
            supportingText = if (startInvalid) ({ Text(stringResource(R.string.validation_time)) }) else null,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = node.end,
            onValueChange = { onChange(node.copy(end = it)) },
            label = { Text(stringResource(R.string.condition_to_time)) },
            singleLine = true,
            isError = endInvalid,
            supportingText = if (endInvalid) ({ Text(stringResource(R.string.validation_time)) }) else null,
            modifier = Modifier.weight(1f),
        )
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove))
            }
        }
    }
}

/** Up/down handles to reorder a node among its siblings (its evaluation order in the group). */
@Composable
private fun ReorderControls(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val moveUpDescription = stringResource(R.string.move_up)
    val moveDownDescription = stringResource(R.string.move_down)
    val moveUpTag = if (canMoveUp) CONDITION_MOVE_UP_ENABLED_TEST_TAG else CONDITION_MOVE_UP_TEST_TAG
    Column {
        IconButton(
            onClick = onMoveUp,
            enabled = canMoveUp,
            modifier =
                Modifier
                    .size(48.dp)
                    .testTag(moveUpTag)
                    .semantics { contentDescription = moveUpDescription },
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
        }
        IconButton(
            onClick = onMoveDown,
            enabled = canMoveDown,
            modifier = Modifier.size(48.dp).semantics { contentDescription = moveDownDescription },
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
    }
}

private fun <T> List<T>.replacedAt(
    index: Int,
    value: T,
): List<T> = toMutableList().also { it[index] = value }

private fun <T> List<T>.withoutIndex(index: Int): List<T> = filterIndexed { i, _ -> i != index }

private fun String.asSignedIntegerInput(): String =
    filterIndexed { index, char -> char.isDigit() || (char == '-' && index == 0) }

private const val MAX_SOURCE_QUERY_CHARS = 80
private const val MILLIS_PER_MINUTE = 60_000L
private const val TRACE_INDENT_DP = 12
internal const val RULE_ADD_FAB_TEST_TAG = "rule_add_fab"
internal const val RULES_LIST_TEST_TAG = "rules_list"
internal const val CONDITION_MOVE_UP_TEST_TAG = "condition_move_up"
internal const val CONDITION_MOVE_UP_ENABLED_TEST_TAG = "condition_move_up_enabled"
private val RATE_WINDOW_PRESET_MINUTES = listOf(1, 5, 15, 60)
private val VALID_RATE_WINDOW_MINUTES =
    (MIN_RATE_WINDOW_MILLIS / MILLIS_PER_MINUTE)..(MAX_RATE_WINDOW_MILLIS / MILLIS_PER_MINUTE)
private val VALID_RATE_THRESHOLDS = MIN_RATE_THRESHOLD..MAX_RATE_THRESHOLD

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionChip(
    @androidx.annotation.StringRes labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(labelRes)) })
}

/**
 * Shown when notification access is not granted — without it the listener never binds, so nothing is
 * filtered. Deep-links the user to the system grant screen. This is the app's most important state.
 */
@Composable
private fun NotificationAccessBanner(
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.notification_access_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.notification_access_summary),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onGrant) { Text(stringResource(R.string.open_settings)) }
        }
    }
}

/** First-run guidance and a persistent, local health summary for the filtering path. */
@Composable
private fun SetupHealthCard(
    filteringEnabled: Boolean,
    enabledRuleCount: Int,
    onAddRule: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = filteringEnabled && enabledRuleCount > 0
    ExpressiveHeroCard(
        modifier = modifier,
        containerColor =
            if (ready) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.setup_status_title),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            StatusPill(
                text =
                    stringResource(
                        if (ready) R.string.profiles_active else R.string.profiles_inactive,
                    ),
                positive = ready,
            )
        }
        Text(
            when {
                !filteringEnabled -> stringResource(R.string.setup_filtering_paused)
                enabledRuleCount == 0 -> stringResource(R.string.setup_no_active_rules)
                else ->
                    pluralStringResource(
                        R.plurals.setup_ready,
                        enabledRuleCount,
                        enabledRuleCount,
                    )
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!filteringEnabled) {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.review_settings)) }
        } else if (enabledRuleCount == 0) {
            TextButton(onClick = onAddRule) { Text(stringResource(R.string.setup_create_rule)) }
        }
    }
}

/** Lightweight, non-dismissible hint shown while external automation is opted out (§7). */
@Composable
private fun AutomationHintCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(R.string.automation_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
