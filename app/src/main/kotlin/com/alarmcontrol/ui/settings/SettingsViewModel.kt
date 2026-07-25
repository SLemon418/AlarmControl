package com.alarmcontrol.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alarmcontrol.R
import com.alarmcontrol.core.automation.AutomationAuditEntry
import com.alarmcontrol.core.automation.AutomationAuditRepository
import com.alarmcontrol.core.backup.BackupPreview
import com.alarmcontrol.core.backup.BackupRepository
import com.alarmcontrol.core.backup.BackupSummary
import com.alarmcontrol.core.backup.RestoreMode
import com.alarmcontrol.core.backup.RestoreOptions
import com.alarmcontrol.core.coroutines.AppDispatcher
import com.alarmcontrol.core.coroutines.Dispatcher
import com.alarmcontrol.core.filtering.NotificationHistoryRepository
import com.alarmcontrol.core.privacy.LocalDataRepository
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.core.settings.SemanticAnalysisScope
import com.alarmcontrol.ml.llm.LlmFailure
import com.alarmcontrol.ml.llm.LlmInitState
import com.alarmcontrol.ml.llm.OnDeviceLlmManager
import com.alarmcontrol.service.AppHealthProvider
import com.alarmcontrol.service.AppHealthSnapshot
import com.alarmcontrol.ui.NotificationAccessUiState
import com.alarmcontrol.ui.UiText
import com.alarmcontrol.ui.app.AppIdentityResolver
import com.alarmcontrol.ui.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// One public method per user intent keeps the screen's UDF callback surface explicit.
@Suppress("TooManyFunctions")
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val automationAuditRepository: AutomationAuditRepository,
        private val backupRepository: BackupRepository,
        private val localDataRepository: LocalDataRepository,
        notificationHistoryRepository: NotificationHistoryRepository,
        private val llmManager: OnDeviceLlmManager,
        private val appHealthProvider: AppHealthProvider,
        private val appIdentityResolver: AppIdentityResolver,
        @ApplicationContext private val appContext: Context,
        @Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val messages = MutableStateFlow<UiText?>(null)
        private val backupPreview = MutableStateFlow<BackupPreviewUi?>(null)
        private var pendingRestore: PendingRestore? = null
        private val appHealth = MutableStateFlow<AppHealthSnapshot?>(null)

        private val storedSettings =
            combine(
                settingsRepository.llmAnalysisEnabled,
                settingsRepository.llmAutoActionsEnabled,
                settingsRepository.semanticAnalysisScope,
                ::LlmSettings,
            ).let { llmSettings ->
                val generalSettings =
                    combine(
                        settingsRepository.filteringEnabled,
                        settingsRepository.dynamicColorEnabled,
                        ::GeneralSettings,
                    )
                val automationSettings =
                    combine(
                        settingsRepository.externalAutomationEnabled,
                        settingsRepository.externalAutomationToken,
                        ::AutomationSettings,
                    )
                val retentionSettings =
                    combine(
                        settingsRepository.eventRetentionDays,
                        settingsRepository.dailyInsightRetentionDays,
                        ::RetentionSettings,
                    )
                val contentSettings =
                    combine(
                        settingsRepository.notificationContentStorageEnabled,
                        settingsRepository.contentExcludedPackages,
                        notificationHistoryRepository.observeSources(CONTENT_SOURCE_LIMIT),
                    ) { enabled, excludedPackages, sources ->
                        ContentSettings(
                            enabled = enabled,
                            excludedPackages = excludedPackages,
                            sources =
                                (sources.groupBy { it.packageName }.keys + excludedPackages)
                                    .map { packageName ->
                                        ContentSourceAppUi(
                                            packageName = packageName,
                                            appName = appIdentityResolver.resolve(packageName).label,
                                            excluded = packageName in excludedPackages,
                                        ) to
                                            sources
                                                .filter { it.packageName == packageName }
                                                .maxOfOrNull { it.lastSeenMillis }
                                                .orDefaultTimestamp()
                                    }.sortedByDescending { it.second }
                                    .map(Pair<ContentSourceAppUi, Long>::first),
                        )
                    }
                combine(
                    generalSettings,
                    automationSettings,
                    llmSettings,
                    retentionSettings,
                    contentSettings,
                ) { general, automation, llm, retention, content ->
                    StoredSettings(
                        general.filtering,
                        automation.enabled,
                        automation.token,
                        llm.enabled,
                        llm.autoActions,
                        llm.scope,
                        retention.eventDays,
                        retention.insightDays,
                        general.dynamicColor,
                        content.enabled,
                        content.excludedPackages,
                        content.sources,
                    )
                }
            }

        private val healthAndAudit =
            combine(appHealth, automationAuditRepository.observeRecent(AUTOMATION_AUDIT_LIMIT)) { health, audit ->
                HealthAndAudit(health, audit.map(AutomationAuditEntry::toUiModel))
            }

        val uiState: StateFlow<SettingsUiState> =
            combine(
                storedSettings,
                llmManager.initState,
                messages,
                healthAndAudit,
                backupPreview,
            ) { settings, llmState, message, healthAndAudit, preview ->
                val health = healthAndAudit.health
                SettingsUiState(
                    filteringEnabled = settings.filtering,
                    externalAutomationEnabled = settings.automation,
                    externalAutomationToken = settings.automationToken,
                    automationAudit = healthAndAudit.audit,
                    llmAnalysisEnabled = settings.llmEnabled,
                    llmAutoActionsEnabled = settings.llmAutoActions,
                    semanticAnalysisScope = settings.semanticScope,
                    eventRetentionDays = settings.eventDays,
                    dailyInsightRetentionDays = settings.insightDays,
                    dynamicColorEnabled = settings.dynamicColor,
                    notificationContentStorageEnabled = settings.contentStorageEnabled,
                    contentExcludedPackages = settings.contentExcludedPackages,
                    contentSourceApps = settings.contentSources,
                    llmModelStatus = llmState.toUiStatus(),
                    llmModelCopiedBytes = (llmState as? LlmInitState.Installing)?.copiedBytes ?: 0,
                    llmModelTotalBytes = (llmState as? LlmInitState.Installing)?.totalBytes,
                    llmModelError = (llmState as? LlmInitState.Unavailable)?.failure?.toUiError(),
                    notificationAccessGranted = health?.notificationAccessGranted == true,
                    notificationAccessState =
                        when (health?.notificationAccessGranted) {
                            true -> NotificationAccessUiState.GRANTED
                            false -> NotificationAccessUiState.DENIED
                            null -> NotificationAccessUiState.CHECKING
                        },
                    batteryOptimizationExempt = health?.batteryOptimizationExempt == true,
                    backupPreview = preview,
                    userMessage = message,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

        fun refreshAppHealth() {
            appHealth.value = appHealthProvider.snapshot()
        }

        fun setExternalAutomationEnabled(enabled: Boolean) {
            launchSettingUpdate { settingsRepository.setExternalAutomationEnabled(enabled) }
        }

        fun rotateExternalAutomationToken() {
            launchSettingUpdate { settingsRepository.rotateExternalAutomationToken() }
        }

        fun setFilteringEnabled(enabled: Boolean) {
            launchSettingUpdate { settingsRepository.setFilteringEnabled(enabled) }
        }

        fun setLlmAnalysisEnabled(enabled: Boolean) {
            launchSettingUpdate {
                settingsRepository.setLlmAnalysisEnabled(enabled)
                if (!enabled) settingsRepository.setLlmAutoActionsEnabled(false)
                if (enabled) llmManager.initialize() else llmManager.close()
            }
        }

        fun setLlmAutoActionsEnabled(enabled: Boolean) {
            launchSettingUpdate { settingsRepository.setLlmAutoActionsEnabled(enabled) }
        }

        fun setSemanticAnalysisScope(scope: SemanticAnalysisScope) {
            launchSettingUpdate { settingsRepository.setSemanticAnalysisScope(scope) }
        }

        fun setEventRetentionDays(days: Int) {
            launchSettingUpdate { settingsRepository.setEventRetentionDays(days) }
        }

        fun setDailyInsightRetentionDays(days: Int) {
            launchSettingUpdate { settingsRepository.setDailyInsightRetentionDays(days) }
        }

        fun setDynamicColorEnabled(enabled: Boolean) {
            launchSettingUpdate { settingsRepository.setDynamicColorEnabled(enabled) }
        }

        fun setNotificationContentStorageEnabled(enabled: Boolean) {
            launchSettingUpdate {
                if (enabled) {
                    settingsRepository.setNotificationContentStorageEnabled(true)
                } else {
                    settingsRepository.setNotificationContentStorageEnabled(false)
                    localDataRepository.clearStoredNotificationContent()
                }
            }
        }

        fun setContentPackageExcluded(
            packageName: String,
            excluded: Boolean,
        ) {
            launchSettingUpdate {
                val current = settingsRepository.contentExcludedPackages.first()
                settingsRepository.setContentExcludedPackages(
                    if (excluded) current + packageName else current - packageName,
                )
                if (excluded) {
                    localDataRepository.clearStoredNotificationContentForPackage(packageName)
                }
            }
        }

        fun clearActivityHistory() {
            clearData(R.string.message_clear_activity_done) { localDataRepository.clearActivityHistory() }
        }

        fun clearFeedback() {
            clearData(R.string.message_clear_feedback_done) { localDataRepository.clearFeedback() }
        }

        fun clearDailyInsights() {
            clearData(R.string.message_clear_insights_done) { localDataRepository.clearDailyInsights() }
        }

        fun clearStoredNotificationContent() {
            clearData(R.string.message_clear_content_done) {
                localDataRepository.clearStoredNotificationContent()
            }
        }

        fun clearAllData() {
            viewModelScope.launch(ioDispatcher) {
                val failures = mutableListOf<Throwable>()
                suspend fun attempt(block: suspend () -> Unit) {
                    runCatchingPreservingCancellation { block() }.onFailure(failures::add)
                }

                // Disable future sensitive capture/entry points before deleting existing state.
                attempt { settingsRepository.setNotificationContentStorageEnabled(false) }
                attempt { settingsRepository.setLlmAutoActionsEnabled(false) }
                attempt { settingsRepository.setLlmAnalysisEnabled(false) }
                attempt { settingsRepository.setExternalAutomationEnabled(false) }
                attempt { llmManager.close() }
                attempt {
                    when (val removal = llmManager.removeModel()) {
                        is DataResult.Failure -> throw removal.throwable
                        DataResult.Loading -> error("Model removal is busy")
                        is DataResult.Success -> Unit
                    }
                }
                attempt { localDataRepository.clearAllDatabaseData() }
                attempt { settingsRepository.reset() }
                messages.value =
                    if (failures.isEmpty()) {
                        uiText(R.string.message_clear_all_done)
                    } else {
                        uiText(R.string.message_clear_failed)
                    }
            }
        }

        fun removeLlmModel() {
            viewModelScope.launch(ioDispatcher) {
                messages.value =
                    when (llmManager.removeModel()) {
                        is DataResult.Success -> {
                            settingsRepository.setLlmAnalysisEnabled(false)
                            uiText(R.string.message_model_removed)
                        }
                        is DataResult.Failure -> uiText(R.string.message_model_remove_failed)
                        DataResult.Loading -> uiText(R.string.message_model_installing)
                    }
            }
        }

        /** Installs and validates a model selected through SAF; the app never downloads one. */
        fun importLlmModelFrom(uri: Uri) {
            viewModelScope.launch(ioDispatcher) {
                val result: DataResult<Unit> =
                    runCatchingPreservingCancellation {
                        appContext.contentResolver.openInputStream(uri)?.use { source ->
                            llmManager.installModel(source, appContext.contentResolver.contentLength(uri))
                        } ?: DataResult.Failure(IllegalStateException("Model source unavailable"))
                    }.getOrElse { error -> DataResult.Failure(error) }

                messages.value =
                    when (result) {
                        is DataResult.Success -> {
                            if (!settingsRepository.llmAnalysisEnabled.first()) llmManager.close()
                            uiText(R.string.message_model_installed)
                        }
                        is DataResult.Failure -> uiText(R.string.message_model_install_failed)
                        DataResult.Loading -> uiText(R.string.message_model_installing)
                    }
            }
        }

        /** Writes the current backup JSON to the SAF-chosen [uri]; all on-device (CLAUDE.md §3). */
        fun exportBackupTo(
            uri: Uri,
            passphrase: CharArray?,
            includeLearningFeedback: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) {
                try {
                    messages.value =
                        runCatchingPreservingCancellation {
                            val json =
                                backupRepository.export(
                                    passphrase = passphrase?.takeIf { it.isNotEmpty() },
                                    includeLearningFeedback = includeLearningFeedback,
                                )
                            appContext.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                                ?: error("Backup destination unavailable")
                        }.fold(
                            onSuccess = { uiText(R.string.message_backup_exported) },
                            onFailure = { uiText(R.string.message_backup_export_failed) },
                        )
                } finally {
                    passphrase?.fill('\u0000')
                }
            }
        }

        /** Reads and validates a SAF backup, then exposes a confirmation preview without mutating data. */
        fun importBackupFrom(
            uri: Uri,
            passphrase: CharArray?,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val retainedPassphrase = passphrase?.takeIf { it.isNotEmpty() }?.copyOf()
                try {
                    val read =
                        runCatchingPreservingCancellation {
                            appContext.contentResolver
                                .openInputStream(uri)
                                ?.use { it.readBackupText() }
                                ?: error("Backup source unavailable")
                        }
                    read.fold(
                        onSuccess = { text ->
                            when (val result = backupRepository.preview(text, retainedPassphrase)) {
                                is DataResult.Success -> {
                                    clearPendingRestore()
                                    pendingRestore = PendingRestore(text, retainedPassphrase)
                                    backupPreview.value = result.data.toUiModel()
                                }
                                is DataResult.Failure -> {
                                    retainedPassphrase?.fill('\u0000')
                                    messages.value = uiText(R.string.message_backup_restore_failed)
                                }
                                DataResult.Loading -> messages.value = uiText(R.string.message_backup_restoring)
                            }
                        },
                        onFailure = {
                            retainedPassphrase?.fill('\u0000')
                            messages.value = uiText(R.string.message_backup_read_failed)
                        },
                    )
                } finally {
                    passphrase?.fill('\u0000')
                }
            }
        }

        fun updateRestoreSelection(selection: RestoreSelectionUi) {
            backupPreview.value = backupPreview.value?.copy(selection = selection)
        }

        fun cancelRestore() {
            clearPendingRestore()
        }

        fun confirmRestore() {
            val pending = pendingRestore ?: return
            val preview = backupPreview.value ?: return
            if (!preview.selection.hasSelection) return
            pendingRestore = null
            backupPreview.value = null
            viewModelScope.launch(ioDispatcher) {
                try {
                    val result =
                        backupRepository.restore(
                            serialized = pending.serialized,
                            passphrase = pending.passphrase,
                            options = preview.selection.toDomain(),
                        )
                    messages.value = result.restoreMessage()
                    if (result is DataResult.Success && result.data.settingsRestored) {
                        if (settingsRepository.llmAnalysisEnabled.first()) {
                            llmManager.initialize()
                        } else {
                            llmManager.close()
                        }
                    }
                } finally {
                    pending.passphrase?.fill('\u0000')
                }
            }
        }

        fun onUserMessageShown() {
            messages.value = null
        }

        override fun onCleared() {
            clearPendingRestore()
            super.onCleared()
        }

        private fun clearPendingRestore() {
            pendingRestore?.passphrase?.fill('\u0000')
            pendingRestore = null
            backupPreview.value = null
        }

        private fun launchSettingUpdate(block: suspend () -> Unit) {
            viewModelScope.launch(ioDispatcher) {
                runCatchingPreservingCancellation { block() }
                    .onFailure { messages.value = uiText(R.string.message_setting_update_failed) }
            }
        }

        private fun clearData(
            successMessage: Int,
            block: suspend () -> Unit,
        ) {
            viewModelScope.launch(ioDispatcher) {
                runCatchingPreservingCancellation { block() }
                    .onSuccess { messages.value = uiText(successMessage) }
                    .onFailure { messages.value = uiText(R.string.message_clear_failed) }
            }
        }

        private data class StoredSettings(
            val filtering: Boolean,
            val automation: Boolean,
            val automationToken: String,
            val llmEnabled: Boolean,
            val llmAutoActions: Boolean,
            val semanticScope: SemanticAnalysisScope,
            val eventDays: Int,
            val insightDays: Int,
            val dynamicColor: Boolean,
            val contentStorageEnabled: Boolean,
            val contentExcludedPackages: Set<String>,
            val contentSources: List<ContentSourceAppUi>,
        )

        private data class GeneralSettings(
            val filtering: Boolean,
            val dynamicColor: Boolean,
        )

        private data class LlmSettings(
            val enabled: Boolean,
            val autoActions: Boolean,
            val scope: SemanticAnalysisScope,
        )

        private data class AutomationSettings(
            val enabled: Boolean,
            val token: String,
        )

        private data class RetentionSettings(
            val eventDays: Int,
            val insightDays: Int,
        )

        private data class ContentSettings(
            val enabled: Boolean,
            val excludedPackages: Set<String>,
            val sources: List<ContentSourceAppUi>,
        )

        private data class PendingRestore(
            val serialized: String,
            val passphrase: CharArray?,
        )

        private data class HealthAndAudit(
            val health: AppHealthSnapshot?,
            val audit: List<AutomationAuditUi>,
        )

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val CONTENT_SOURCE_LIMIT = 200
            const val AUTOMATION_AUDIT_LIMIT = 5
        }
    }

private fun Long?.orDefaultTimestamp(): Long = this ?: Long.MIN_VALUE

private fun LlmInitState.toUiStatus(): LlmModelUiStatus =
    when (this) {
        LlmInitState.Idle -> LlmModelUiStatus.NOT_LOADED
        is LlmInitState.Installing -> LlmModelUiStatus.INSTALLING
        LlmInitState.Loading -> LlmModelUiStatus.LOADING
        LlmInitState.Ready -> LlmModelUiStatus.READY
        is LlmInitState.Unavailable -> LlmModelUiStatus.UNAVAILABLE
    }

private fun LlmFailure.toUiError(): LlmModelErrorUi =
    when (this) {
        LlmFailure.MODEL_MISSING -> LlmModelErrorUi.MISSING
        LlmFailure.MODEL_INVALID -> LlmModelErrorUi.INVALID
        LlmFailure.LOAD_FAILED -> LlmModelErrorUi.LOAD_FAILED
        LlmFailure.STORAGE_FAILURE -> LlmModelErrorUi.STORAGE_FAILURE
    }

private fun BackupPreview.toUiModel(): BackupPreviewUi =
    BackupPreviewUi(
        encrypted = encrypted,
        rules = rules,
        profiles = profiles,
        dailyInsights = dailyInsights,
        hasSettings = hasSettings,
        categoryFeedback = categoryFeedback,
        adFeedbackVotes = adFeedbackVotes,
        selection =
            RestoreSelectionUi(
                rulesAndProfiles = rules + profiles > 0,
                dailyInsights = dailyInsights > 0,
                settings = hasSettings,
                learningFeedback = false,
            ),
    )

private fun RestoreSelectionUi.toDomain(): RestoreOptions =
    RestoreOptions(
        mode = if (replaceExisting) RestoreMode.REPLACE else RestoreMode.MERGE,
        rulesAndProfiles = rulesAndProfiles,
        dailyInsights = dailyInsights,
        settings = settings,
        learningFeedback = learningFeedback,
    )

private fun DataResult<BackupSummary>.restoreMessage(): UiText =
    when (this) {
        is DataResult.Success ->
            uiText(
                R.string.message_backup_restored,
                data.rulesRestored,
                data.profilesRestored,
                data.insightsRestored,
                data.feedbackRestored,
            )
        is DataResult.Failure -> uiText(R.string.message_backup_restore_failed)
        DataResult.Loading -> uiText(R.string.message_backup_restoring)
    }

private fun AutomationAuditEntry.toUiModel(): AutomationAuditUi =
    AutomationAuditUi(
        id = id,
        source = AutomationSourceUi.valueOf(source.name),
        operation = AutomationOperationUi.valueOf(operation.name),
        outcome = AutomationOutcomeUi.valueOf(outcome.name),
        changedCount = changedCount,
        requestedAtMillis = requestedAtMillis,
    )

private fun android.content.ContentResolver.contentLength(uri: Uri): Long? =
    runCatching {
        query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0).takeIf { it > 0 }
        }
    }.getOrNull()
