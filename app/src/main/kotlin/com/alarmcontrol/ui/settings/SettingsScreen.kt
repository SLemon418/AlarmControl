package com.alarmcontrol.ui.settings

import android.content.ClipboardManager
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alarmcontrol.R
import com.alarmcontrol.core.settings.SemanticAnalysisScope
import com.alarmcontrol.service.NotificationAccess
import com.alarmcontrol.ui.NotificationAccessUiState
import com.alarmcontrol.ui.asString
import com.alarmcontrol.ui.designsystem.ExpressiveHeroCard
import com.alarmcontrol.ui.designsystem.MaxWidthContent
import com.alarmcontrol.ui.designsystem.StatusPill
import com.alarmcontrol.ui.privacy.ProtectSensitiveWindow
import com.alarmcontrol.ui.privacy.copySensitiveText

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel = hiltViewModel(),
    destination: SettingsDestination = SettingsDestination.OVERVIEW,
    onNavigate: (SettingsDestination) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAppHealth()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SettingsScreen(
        state = state,
        destination = destination,
        onNavigate = onNavigate,
        onBack = onBack,
        onFilteringChange = viewModel::setFilteringEnabled,
        onDynamicColorChange = viewModel::setDynamicColorEnabled,
        onExternalAutomationChange = viewModel::setExternalAutomationEnabled,
        onRotateAutomationToken = viewModel::rotateExternalAutomationToken,
        onCopyAutomationToken = { token ->
            context
                .getSystemService(ClipboardManager::class.java)
                ?.let { clipboard ->
                    copySensitiveText(
                        clipboard = clipboard,
                        label = context.getString(R.string.settings_automation_token),
                        value = token,
                        scope = clipboardScope,
                    )
                }
        },
        onLlmAnalysisChange = viewModel::setLlmAnalysisEnabled,
        onLlmAutoActionsChange = viewModel::setLlmAutoActionsEnabled,
        onSemanticAnalysisScopeChange = viewModel::setSemanticAnalysisScope,
        onEventRetentionChange = viewModel::setEventRetentionDays,
        onInsightRetentionChange = viewModel::setDailyInsightRetentionDays,
        onNotificationContentStorageChange = viewModel::setNotificationContentStorageEnabled,
        onContentPackageExcluded = viewModel::setContentPackageExcluded,
        onClearActivity = viewModel::clearActivityHistory,
        onClearStoredContent = viewModel::clearStoredNotificationContent,
        onClearFeedback = viewModel::clearFeedback,
        onClearInsights = viewModel::clearDailyInsights,
        onClearAll = viewModel::clearAllData,
        onImportLlmModel = viewModel::importLlmModelFrom,
        onRemoveLlmModel = viewModel::removeLlmModel,
        onExport = viewModel::exportBackupTo,
        onImport = viewModel::importBackupFrom,
        onRestoreSelectionChange = viewModel::updateRestoreSelection,
        onConfirmRestore = viewModel::confirmRestore,
        onCancelRestore = viewModel::cancelRestore,
        onUserMessageShown = viewModel::onUserMessageShown,
        onOpenNotificationAccess = {
            NotificationAccess.openWithAppDetailsFallback(context, NotificationAccess.settingsIntent())
        },
        onOpenBatterySettings = {
            NotificationAccess.openWithAppDetailsFallback(
                context,
                NotificationAccess.batteryOptimizationSettingsIntent(context),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    destination: SettingsDestination = SettingsDestination.OVERVIEW,
    onNavigate: (SettingsDestination) -> Unit = {},
    onBack: () -> Unit = {},
    onFilteringChange: (Boolean) -> Unit,
    onExternalAutomationChange: (Boolean) -> Unit,
    onLlmAnalysisChange: (Boolean) -> Unit,
    onImportLlmModel: (Uri) -> Unit,
    onExport: (Uri, CharArray?, Boolean) -> Unit,
    onImport: (Uri, CharArray?) -> Unit,
    onUserMessageShown: () -> Unit,
    onLlmAutoActionsChange: (Boolean) -> Unit = {},
    onSemanticAnalysisScopeChange: (SemanticAnalysisScope) -> Unit = {},
    onRemoveLlmModel: () -> Unit = {},
    onEventRetentionChange: (Int) -> Unit = {},
    onInsightRetentionChange: (Int) -> Unit = {},
    onNotificationContentStorageChange: (Boolean) -> Unit = {},
    onContentPackageExcluded: (String, Boolean) -> Unit = { _, _ -> },
    onClearActivity: () -> Unit = {},
    onClearStoredContent: () -> Unit = {},
    onClearFeedback: () -> Unit = {},
    onClearInsights: () -> Unit = {},
    onClearAll: () -> Unit = {},
    onOpenNotificationAccess: () -> Unit = {},
    onOpenBatterySettings: () -> Unit = {},
    onRestoreSelectionChange: (RestoreSelectionUi) -> Unit = {},
    onConfirmRestore: () -> Unit = {},
    onCancelRestore: () -> Unit = {},
    onRotateAutomationToken: () -> Unit = {},
    onCopyAutomationToken: (String) -> Unit = {},
    onDynamicColorChange: (Boolean) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var backupPassword by remember { mutableStateOf("") }
    var includeLearningFeedback by remember { mutableStateOf(false) }
    val userMessage = state.userMessage?.asString()
    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbarHostState.showSnackbar(it)
            onUserMessageShown()
        }
    }

    // Storage Access Framework: the user picks where the JSON lives; the app never touches the network.
    val exportLauncher =
        rememberLauncherForActivityResult(CreateLocalDocument("application/json")) { uri ->
            uri?.let { onExport(it, backupPassword.toPassphrase(), includeLearningFeedback) }
            backupPassword = ""
            includeLearningFeedback = false
        }
    val importLauncher =
        rememberLauncherForActivityResult(OpenLocalDocument()) { uri ->
            uri?.let { onImport(it, backupPassword.toPassphrase()) }
            backupPassword = ""
        }
    val modelImportLauncher =
        rememberLauncherForActivityResult(OpenLocalDocument()) { uri ->
            uri?.let(onImportLlmModel)
        }
    var pendingClear by remember { mutableStateOf<ClearAction?>(null) }

    SettingsContent(
        state = state,
        destination = destination,
        onNavigate = onNavigate,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        backupPassword = backupPassword,
        includeLearningFeedback = includeLearningFeedback,
        onPasswordChange = {
            backupPassword = it
            if (it.isEmpty()) includeLearningFeedback = false
        },
        onIncludeLearningFeedbackChange = { includeLearningFeedback = it },
        onFilteringChange = onFilteringChange,
        onDynamicColorChange = onDynamicColorChange,
        onExternalAutomationChange = onExternalAutomationChange,
        onRotateAutomationToken = onRotateAutomationToken,
        onCopyAutomationToken = onCopyAutomationToken,
        onLlmAnalysisChange = onLlmAnalysisChange,
        onLlmAutoActionsChange = onLlmAutoActionsChange,
        onSemanticAnalysisScopeChange = onSemanticAnalysisScopeChange,
        onChooseModel = { modelImportLauncher.launch(arrayOf("application/octet-stream", "application/zip")) },
        onRemoveLlmModel = onRemoveLlmModel,
        onBackup = { exportLauncher.launch("alarmcontrol-backup.json") },
        onRestore = { importLauncher.launch(arrayOf("application/json")) },
        onEventRetentionChange = onEventRetentionChange,
        onInsightRetentionChange = onInsightRetentionChange,
        onNotificationContentStorageChange = onNotificationContentStorageChange,
        onContentPackageExcluded = onContentPackageExcluded,
        onOpenNotificationAccess = onOpenNotificationAccess,
        onOpenBatterySettings = onOpenBatterySettings,
        onRequestClear = { pendingClear = it },
    )

    pendingClear?.let { action ->
        ClearConfirmationDialog(
            action = action,
            onConfirm = {
                when (action) {
                    ClearAction.ACTIVITY -> onClearActivity()
                    ClearAction.CONTENT -> onClearStoredContent()
                    ClearAction.FEEDBACK -> onClearFeedback()
                    ClearAction.INSIGHTS -> onClearInsights()
                    ClearAction.ALL -> onClearAll()
                }
                pendingClear = null
            },
            onDismiss = { pendingClear = null },
        )
    }

    state.backupPreview?.let { preview ->
        BackupRestorePreviewDialog(
            preview = preview,
            onSelectionChange = onRestoreSelectionChange,
            onConfirm = onConfirmRestore,
            onDismiss = onCancelRestore,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    state: SettingsUiState,
    destination: SettingsDestination,
    onNavigate: (SettingsDestination) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    backupPassword: String,
    includeLearningFeedback: Boolean,
    onPasswordChange: (String) -> Unit,
    onIncludeLearningFeedbackChange: (Boolean) -> Unit,
    onFilteringChange: (Boolean) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onExternalAutomationChange: (Boolean) -> Unit,
    onRotateAutomationToken: () -> Unit,
    onCopyAutomationToken: (String) -> Unit,
    onLlmAnalysisChange: (Boolean) -> Unit,
    onLlmAutoActionsChange: (Boolean) -> Unit,
    onSemanticAnalysisScopeChange: (SemanticAnalysisScope) -> Unit,
    onChooseModel: () -> Unit,
    onRemoveLlmModel: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onEventRetentionChange: (Int) -> Unit,
    onInsightRetentionChange: (Int) -> Unit,
    onNotificationContentStorageChange: (Boolean) -> Unit,
    onContentPackageExcluded: (String, Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRequestClear: (ClearAction) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(destination.titleRes)) },
                navigationIcon = {
                    if (destination != SettingsDestination.OVERVIEW) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        MaxWidthContent(Modifier.fillMaxSize().padding(padding)) {
            if (destination == SettingsDestination.CONTENT_EXCLUSIONS) {
                ContentExclusionsScreen(
                    apps = state.contentSourceApps,
                    onPackageExcluded = onContentPackageExcluded,
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (destination) {
                    SettingsDestination.OVERVIEW ->
                        SettingsOverview(
                            state = state,
                            onFilteringChange = onFilteringChange,
                            onDynamicColorChange = onDynamicColorChange,
                            onOpenNotificationAccess = onOpenNotificationAccess,
                            onOpenBatterySettings = onOpenBatterySettings,
                            onNavigate = onNavigate,
                        )
                    SettingsDestination.AUTOMATION ->
                        AutomationSettingsSection(
                            state = state,
                            onExternalAutomationChange = onExternalAutomationChange,
                            onRotateAutomationToken = onRotateAutomationToken,
                            onCopyAutomationToken = onCopyAutomationToken,
                        )
                    SettingsDestination.LOCAL_AI ->
                        LlmSettingsSection(
                            state,
                            onLlmAnalysisChange,
                            onLlmAutoActionsChange,
                            onSemanticAnalysisScopeChange,
                            onChooseModel,
                            onRemoveLlmModel,
                        )
                    SettingsDestination.BACKUP ->
                        BackupSettingsSection(
                            backupPassword,
                            onPasswordChange,
                            includeLearningFeedback,
                            onIncludeLearningFeedbackChange,
                            onBackup,
                            onRestore,
                        )
                    SettingsDestination.DATA_PRIVACY -> {
                        NotificationContentSettingsSection(
                            state = state,
                            onStorageChange = onNotificationContentStorageChange,
                            onOpenExclusions = { onNavigate(SettingsDestination.CONTENT_EXCLUSIONS) },
                            onRequestClear = { onRequestClear(ClearAction.CONTENT) },
                        )
                        HorizontalDivider()
                        RetentionSettingsSection(state, onEventRetentionChange, onInsightRetentionChange)
                        HorizontalDivider()
                        PrivacySettingsSection(onRequestClear)
                    }
                        SettingsDestination.CONTENT_EXCLUSIONS -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationContentSettingsSection(
    state: SettingsUiState,
    onStorageChange: (Boolean) -> Unit,
    onOpenExclusions: () -> Unit,
    onRequestClear: () -> Unit,
) {
    SettingsSectionTitle(R.string.settings_content_history_section)
    SettingSwitchRow(
        title = stringResource(R.string.settings_content_history),
        subtitle = stringResource(R.string.settings_content_history_summary),
        checked = state.notificationContentStorageEnabled,
        onCheckedChange = onStorageChange,
    )
    Text(
        stringResource(R.string.settings_content_history_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.notificationContentStorageEnabled) {
        OutlinedButton(
            onClick = onOpenExclusions,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.settings_content_exclusions_manage, state.contentSourceApps.size))
        }
        OutlinedButton(
            onClick = onRequestClear,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.settings_clear_content))
        }
    }
}

@Composable
private fun ContentExclusionsScreen(
    apps: List<ContentSourceAppUi>,
    onPackageExcluded: (String, Boolean) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered =
        remember(apps, query) {
            val needle = query.trim()
            if (needle.isEmpty()) {
                apps
            } else {
                apps.filter {
                    it.appName.contains(needle, ignoreCase = true) ||
                        it.packageName.contains(needle, ignoreCase = true)
                }
            }
        }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(CONTENT_SEARCH_MAX_CHARS) },
            label = { Text(stringResource(R.string.settings_content_exclusions_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (filtered.isEmpty()) {
            Text(
                stringResource(R.string.settings_content_exclusions_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filtered, key = ContentSourceAppUi::packageName) { app ->
                    SettingSwitchRow(
                        title = app.appName,
                        subtitle = app.packageName,
                        checked = !app.excluded,
                        onCheckedChange = { store -> onPackageExcluded(app.packageName, !store) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsOverview(
    state: SettingsUiState,
    onFilteringChange: (Boolean) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onNavigate: (SettingsDestination) -> Unit,
) {
    AppHealthSection(state, onOpenNotificationAccess, onOpenBatterySettings)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_filtering_enabled),
                subtitle = stringResource(R.string.settings_filtering_summary),
                checked = state.filteringEnabled,
                onCheckedChange = onFilteringChange,
            )
            HorizontalDivider()
            SettingSwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(R.string.settings_dynamic_color_summary),
                checked = state.dynamicColorEnabled,
                onCheckedChange = onDynamicColorChange,
            )
        }
    }
    SettingsNavigationCard(
        title = stringResource(R.string.settings_automation_section),
        summary = stringResource(R.string.settings_external_automation_summary),
        status =
            stringResource(
                if (state.externalAutomationEnabled) R.string.profiles_active else R.string.profiles_inactive,
            ),
        statusPositive = state.externalAutomationEnabled,
        onClick = { onNavigate(SettingsDestination.AUTOMATION) },
    )
    SettingsNavigationCard(
        title = stringResource(R.string.settings_llm_section),
        summary = state.llmStatusText(),
        status = null,
        onClick = { onNavigate(SettingsDestination.LOCAL_AI) },
    )
    SettingsNavigationCard(
        title = stringResource(R.string.settings_backup_section),
        summary = stringResource(R.string.settings_backup_summary),
        status = null,
        onClick = { onNavigate(SettingsDestination.BACKUP) },
    )
    SettingsNavigationCard(
        title = stringResource(R.string.settings_data_privacy),
        summary = stringResource(R.string.settings_data_privacy_summary),
        status = null,
        onClick = { onNavigate(SettingsDestination.DATA_PRIVACY) },
    )
}

@Composable
private fun SettingsNavigationCard(
    title: String,
    summary: String,
    status: String?,
    statusPositive: Boolean = false,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                status?.let { StatusPill(it, statusPositive) }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

private val SettingsDestination.titleRes: Int
    get() =
        when (this) {
            SettingsDestination.OVERVIEW -> R.string.settings_title
            SettingsDestination.AUTOMATION -> R.string.settings_automation_section
            SettingsDestination.LOCAL_AI -> R.string.settings_llm_section
            SettingsDestination.BACKUP -> R.string.settings_backup_section
            SettingsDestination.DATA_PRIVACY -> R.string.settings_data_privacy
            SettingsDestination.CONTENT_EXCLUSIONS -> R.string.settings_content_exclusions
        }

@Composable
private fun AppHealthSection(
    state: SettingsUiState,
    onOpenNotificationAccess: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    val ready = state.notificationAccessState == NotificationAccessUiState.GRANTED
    ExpressiveHeroCard(
        containerColor =
            if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_health_section),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            StatusPill(
                text = stringResource(if (ready) R.string.profiles_active else R.string.settings_action_needed),
                positive = ready,
            )
        }
        Text(
            when (state.notificationAccessState) {
                NotificationAccessUiState.CHECKING -> stringResource(R.string.notification_access_checking)
                NotificationAccessUiState.GRANTED -> stringResource(R.string.settings_notification_access_ready)
                NotificationAccessUiState.DENIED -> stringResource(R.string.settings_notification_access_needed)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.notificationAccessState == NotificationAccessUiState.DENIED) {
            OutlinedButton(onClick = onOpenNotificationAccess) {
                Text(stringResource(R.string.open_settings))
            }
        }
        Text(
            if (state.batteryOptimizationExempt) {
                stringResource(R.string.settings_battery_unrestricted)
            } else {
                stringResource(R.string.settings_battery_optimized)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onOpenBatterySettings) {
            Text(stringResource(R.string.settings_review_battery))
        }
    }
}

@Composable
private fun AutomationSettingsSection(
    state: SettingsUiState,
    onExternalAutomationChange: (Boolean) -> Unit,
    onRotateAutomationToken: () -> Unit,
    onCopyAutomationToken: (String) -> Unit,
) {
    var tokenVisible by remember { mutableStateOf(false) }
    var confirmRotation by remember { mutableStateOf(false) }
    ProtectSensitiveWindow(active = tokenVisible)
    SettingSwitchRow(
        title = stringResource(R.string.settings_external_automation),
        subtitle = stringResource(R.string.settings_external_automation_summary),
        checked = state.externalAutomationEnabled,
        onCheckedChange = onExternalAutomationChange,
    )
    if (state.externalAutomationEnabled && state.externalAutomationToken.isNotBlank()) {
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_automation_token), style = MaterialTheme.typography.labelLarge)
                if (tokenVisible) {
                    SelectionContainer {
                        Text(state.externalAutomationToken, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("••••••••••••••••", style = MaterialTheme.typography.bodySmall)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { tokenVisible = !tokenVisible },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (tokenVisible) R.string.settings_token_hide else R.string.settings_token_show,
                            ),
                        )
                    }
                    TextButton(
                        onClick = { onCopyAutomationToken(state.externalAutomationToken) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_automation_token_copy))
                    }
                    TextButton(
                        onClick = { confirmRotation = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_automation_token_rotate))
                    }
                }
                Text(
                    stringResource(R.string.settings_automation_token_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirmRotation) {
        AlertDialog(
            onDismissRequest = { confirmRotation = false },
            title = { Text(stringResource(R.string.settings_token_rotate_confirm_title)) },
            text = { Text(stringResource(R.string.settings_token_rotate_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRotation = false
                        tokenVisible = false
                        onRotateAutomationToken()
                    },
                ) {
                    Text(stringResource(R.string.settings_automation_token_rotate))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRotation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (state.automationAudit.isNotEmpty()) {
        Text(
            stringResource(R.string.settings_automation_recent),
            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
        )
        state.automationAudit.forEach { entry ->
            Text(
                stringResource(
                    R.string.settings_automation_audit_row,
                    stringResource(entry.source.labelRes),
                    stringResource(entry.operation.labelRes),
                    stringResource(entry.outcome.labelRes),
                    entry.changedCount,
                    DateUtils.getRelativeTimeSpanString(entry.requestedAtMillis),
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private val AutomationSourceUi.labelRes: Int
    get() =
        when (this) {
            AutomationSourceUi.EXTERNAL -> R.string.automation_source_external
            AutomationSourceUi.QUICK_SETTINGS -> R.string.automation_source_qs
            AutomationSourceUi.SHORTCUT -> R.string.automation_source_shortcut
            AutomationSourceUi.IN_APP -> R.string.automation_source_app
        }

private val AutomationOperationUi.labelRes: Int
    get() =
        when (this) {
            AutomationOperationUi.ENABLE -> R.string.automation_operation_enable
            AutomationOperationUi.DISABLE -> R.string.automation_operation_disable
            AutomationOperationUi.TOGGLE -> R.string.automation_operation_toggle
        }

private val AutomationOutcomeUi.labelRes: Int
    get() =
        when (this) {
            AutomationOutcomeUi.APPLIED -> R.string.automation_outcome_applied
            AutomationOutcomeUi.NO_CHANGE -> R.string.automation_outcome_no_change
            AutomationOutcomeUi.DISABLED -> R.string.automation_outcome_disabled
            AutomationOutcomeUi.UNAUTHORIZED -> R.string.automation_outcome_unauthorized
            AutomationOutcomeUi.THROTTLED -> R.string.automation_outcome_throttled
            AutomationOutcomeUi.INVALID -> R.string.automation_outcome_invalid
            AutomationOutcomeUi.NOT_FOUND -> R.string.automation_outcome_not_found
        }

@Composable
private fun LlmSettingsSection(
    state: SettingsUiState,
    onLlmAnalysisChange: (Boolean) -> Unit,
    onLlmAutoActionsChange: (Boolean) -> Unit,
    onSemanticAnalysisScopeChange: (SemanticAnalysisScope) -> Unit,
    onChooseModel: () -> Unit,
    onRemoveModel: () -> Unit,
) {
    SettingSwitchRow(
        title = stringResource(R.string.settings_llm_enabled),
        subtitle = stringResource(R.string.settings_llm_summary),
        checked = state.llmAnalysisEnabled,
        onCheckedChange = onLlmAnalysisChange,
    )
    SettingSwitchRow(
        title = stringResource(R.string.settings_llm_auto_actions),
        subtitle = stringResource(R.string.settings_llm_auto_actions_summary),
        checked = state.llmAutoActionsEnabled,
        enabled = state.llmAnalysisEnabled,
        onCheckedChange = onLlmAutoActionsChange,
    )
    Text(
        stringResource(R.string.settings_llm_scope),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.semanticAnalysisScope == SemanticAnalysisScope.RULES_ONLY,
            onClick = { onSemanticAnalysisScopeChange(SemanticAnalysisScope.RULES_ONLY) },
            enabled = state.llmAnalysisEnabled,
            label = { Text(stringResource(R.string.settings_llm_scope_rules)) },
        )
        FilterChip(
            selected = state.semanticAnalysisScope == SemanticAnalysisScope.ALL_NOTIFICATIONS,
            onClick = { onSemanticAnalysisScopeChange(SemanticAnalysisScope.ALL_NOTIFICATIONS) },
            enabled = state.llmAnalysisEnabled,
            label = { Text(stringResource(R.string.settings_llm_scope_all)) },
        )
    }
    Text(
        stringResource(R.string.settings_llm_scope_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = state.llmStatusText(), style = MaterialTheme.typography.bodySmall)
    LlmInstallProgress(state)
    val controlsEnabled =
        state.llmModelStatus != LlmModelUiStatus.LOADING &&
            state.llmModelStatus != LlmModelUiStatus.INSTALLING
    OutlinedButton(
        onClick = onChooseModel,
        enabled = controlsEnabled,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(stringResource(R.string.settings_choose_model))
    }
    OutlinedButton(
        onClick = onRemoveModel,
        enabled = controlsEnabled,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(stringResource(R.string.settings_remove_model))
    }
}

@Composable
private fun LlmInstallProgress(state: SettingsUiState) {
    if (state.llmModelStatus != LlmModelUiStatus.INSTALLING) return
    val modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    val total = state.llmModelTotalBytes
    if (total != null && total > 0) {
        LinearProgressIndicator(
            progress = { (state.llmModelCopiedBytes.toFloat() / total).coerceIn(0f, 1f) },
            modifier = modifier,
        )
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}

@Composable
private fun BackupSettingsSection(
    password: String,
    onPasswordChange: (String) -> Unit,
    includeLearningFeedback: Boolean,
    onIncludeLearningFeedbackChange: (Boolean) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    ProtectSensitiveWindow()
    Text(stringResource(R.string.settings_backup_summary), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.settings_backup_password)) },
        supportingText = { Text(stringResource(R.string.settings_backup_password_summary)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    SettingSwitchRow(
        title = stringResource(R.string.settings_backup_feedback),
        subtitle = stringResource(R.string.settings_backup_feedback_summary),
        checked = includeLearningFeedback,
        onCheckedChange = onIncludeLearningFeedbackChange,
        enabled = password.isNotEmpty(),
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_backup))
        }
        OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_restore))
        }
    }
}

@Composable
private fun BackupRestorePreviewDialog(
    preview: BackupPreviewUi,
    onSelectionChange: (RestoreSelectionUi) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selection = preview.selection
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_restore_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.settings_restore_preview_counts,
                        preview.rules,
                        preview.profiles,
                        preview.dailyInsights,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !selection.replaceExisting,
                        onClick = { onSelectionChange(selection.copy(replaceExisting = false)) },
                        label = { Text(stringResource(R.string.settings_restore_merge)) },
                    )
                    FilterChip(
                        selected = selection.replaceExisting,
                        onClick = { onSelectionChange(selection.copy(replaceExisting = true)) },
                        label = { Text(stringResource(R.string.settings_restore_replace)) },
                    )
                }
                RestoreOptionRow(
                    title = stringResource(R.string.settings_restore_rules_profiles),
                    checked = selection.rulesAndProfiles,
                    enabled = preview.rules > 0 || preview.profiles > 0,
                ) { onSelectionChange(selection.copy(rulesAndProfiles = it)) }
                RestoreOptionRow(
                    title = stringResource(R.string.settings_restore_history),
                    checked = selection.dailyInsights,
                    enabled = preview.dailyInsights > 0,
                ) { onSelectionChange(selection.copy(dailyInsights = it)) }
                RestoreOptionRow(
                    title = stringResource(R.string.settings_restore_settings),
                    checked = selection.settings,
                    enabled = preview.hasSettings,
                ) { onSelectionChange(selection.copy(settings = it)) }
                RestoreOptionRow(
                    title = stringResource(R.string.settings_restore_feedback),
                    checked = selection.learningFeedback,
                    enabled =
                        preview.encrypted &&
                            preview.categoryFeedback + preview.adFeedbackVotes > 0,
                ) { onSelectionChange(selection.copy(learningFeedback = it)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = selection.hasSelection) {
                Text(stringResource(R.string.settings_restore_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun RestoreOptionRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked && enabled, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun RetentionSettingsSection(
    state: SettingsUiState,
    onEventRetentionChange: (Int) -> Unit,
    onInsightRetentionChange: (Int) -> Unit,
) {
    SettingsSectionTitle(R.string.settings_retention_section)
    RetentionPicker(
        label = stringResource(R.string.settings_event_retention),
        selectedDays = state.eventRetentionDays,
        options = EVENT_RETENTION_OPTIONS,
        onSelect = onEventRetentionChange,
    )
    RetentionPicker(
        label = stringResource(R.string.settings_insight_retention),
        selectedDays = state.dailyInsightRetentionDays,
        options = INSIGHT_RETENTION_OPTIONS,
        onSelect = onInsightRetentionChange,
    )
}

@Composable
private fun PrivacySettingsSection(onRequestClear: (ClearAction) -> Unit) {
    SettingsSectionTitle(R.string.settings_privacy_section)
    Text(stringResource(R.string.settings_privacy_summary), style = MaterialTheme.typography.bodySmall)
    ClearDataButton(R.string.settings_clear_activity) { onRequestClear(ClearAction.ACTIVITY) }
    ClearDataButton(R.string.settings_clear_feedback) { onRequestClear(ClearAction.FEEDBACK) }
    ClearDataButton(R.string.settings_clear_insights) { onRequestClear(ClearAction.INSIGHTS) }
    ClearDataButton(R.string.settings_clear_all) { onRequestClear(ClearAction.ALL) }
}

@Composable
private fun ClearConfirmationDialog(
    action: ClearAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_clear_confirm_title)) },
        text = { Text(stringResource(action.messageRes)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_clear_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RetentionPicker(
    label: String,
    selectedDays: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(stringResource(R.string.settings_retention_days, selectedDays))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { days ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_retention_days, days)) },
                        onClick = {
                            expanded = false
                            onSelect(days)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClearDataButton(
    labelRes: Int,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(labelRes))
    }
}

@Composable
private fun SettingsSectionTitle(
    @androidx.annotation.StringRes labelRes: Int,
) {
    Text(
        stringResource(labelRes),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
}

private enum class ClearAction(
    val messageRes: Int,
) {
    ACTIVITY(R.string.settings_clear_activity_confirm),
    CONTENT(R.string.settings_clear_content_confirm),
    FEEDBACK(R.string.settings_clear_feedback_confirm),
    INSIGHTS(R.string.settings_clear_insights_confirm),
    ALL(R.string.settings_clear_all_confirm),
}

private val EVENT_RETENTION_OPTIONS = listOf(7, 30, 90, 180)
private val INSIGHT_RETENTION_OPTIONS = listOf(30, 90, 365, 730)

private fun String.toPassphrase(): CharArray? = takeIf { it.isNotEmpty() }?.toCharArray()

private const val CONTENT_SEARCH_MAX_CHARS = 100

@Composable
private fun SettingsUiState.llmStatusText(): String {
    val context = LocalContext.current
    return when (llmModelStatus) {
        LlmModelUiStatus.NOT_LOADED -> stringResource(R.string.settings_model_not_loaded)
        LlmModelUiStatus.INSTALLING -> {
            val copied = Formatter.formatShortFileSize(context, llmModelCopiedBytes)
            val total = llmModelTotalBytes
            if (total == null || total <= 0) {
                stringResource(R.string.settings_model_installing_bytes, copied)
            } else {
                val percent = ((llmModelCopiedBytes * 100) / total).coerceIn(0, 100)
                stringResource(
                    R.string.settings_model_installing_progress,
                    percent,
                    copied,
                    Formatter.formatShortFileSize(context, total),
                )
            }
        }
        LlmModelUiStatus.LOADING -> stringResource(R.string.settings_model_loading)
        LlmModelUiStatus.READY -> stringResource(R.string.settings_model_ready)
        LlmModelUiStatus.UNAVAILABLE ->
            when (llmModelError) {
                LlmModelErrorUi.MISSING -> stringResource(R.string.settings_model_missing)
                LlmModelErrorUi.INVALID -> stringResource(R.string.settings_model_invalid)
                LlmModelErrorUi.LOAD_FAILED -> stringResource(R.string.settings_model_load_failed)
                LlmModelErrorUi.STORAGE_FAILURE -> stringResource(R.string.settings_model_storage_failed)
                null -> stringResource(R.string.settings_model_unavailable)
            }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}
