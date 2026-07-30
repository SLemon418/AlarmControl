package com.alarmcontrol.automation

import com.alarmcontrol.core.privacy.LocalDataResetWriteFence
import com.alarmcontrol.core.settings.ExternalAutomationAuthorizationFence
import com.alarmcontrol.core.settings.MaintenanceSettingsSnapshot
import com.alarmcontrol.core.settings.RetentionDefaults
import com.alarmcontrol.core.settings.SettingsMutationFence
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.core.settings.SettingsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [SettingsRepository] for JVM tests; defaults to automation allowed. */
class FakeSettingsRepository(
    enabled: Boolean = true,
    filtering: Boolean = true,
    semanticClassifier: Boolean = true,
    llmEnabled: Boolean = false,
    private val externalAutomationAuthorizationFence: ExternalAutomationAuthorizationFence =
        ExternalAutomationAuthorizationFence(),
    private val settingsMutationFence: SettingsMutationFence = SettingsMutationFence(),
) : SettingsRepository {
    private val state = MutableStateFlow(enabled)
    private val filteringState = MutableStateFlow(filtering)
    private val semanticClassifierState = MutableStateFlow(semanticClassifier)
    private val llmState = MutableStateFlow(llmEnabled)
    private val llmAutoActionsState = MutableStateFlow(false)
    private val eventRetentionState = MutableStateFlow(RetentionDefaults.EVENT_DAYS)
    private val insightRetentionState = MutableStateFlow(RetentionDefaults.DAILY_INSIGHT_DAYS)
    private val tokenState = MutableStateFlow("test-token")
    private val dynamicColorState = MutableStateFlow(false)
    private val contentStorageState = MutableStateFlow(false)
    private val excludedPackagesState = MutableStateFlow(emptySet<String>())

    override val filteringEnabled: Flow<Boolean> = filteringState
    override val semanticClassifierEnabled: Flow<Boolean> = semanticClassifierState

    override val externalAutomationEnabled: Flow<Boolean> = state
    override val externalAutomationToken: Flow<String> = tokenState

    override val llmAnalysisEnabled: Flow<Boolean> = llmState

    override val llmAutoActionsEnabled: Flow<Boolean> = llmAutoActionsState

    override val eventRetentionDays: Flow<Int> = eventRetentionState

    override val dailyInsightRetentionDays: Flow<Int> = insightRetentionState

    override val dynamicColorEnabled: Flow<Boolean> = dynamicColorState
    override val notificationContentStorageEnabled: Flow<Boolean> = contentStorageState
    override val contentExcludedPackages: Flow<Set<String>> = excludedPackagesState

    override suspend fun setFilteringEnabled(enabled: Boolean) {
        settingsMutationFence.withLock {
            filteringState.value = enabled
        }
    }

    override suspend fun setFilteringEnabledIfCurrentWhileMutationLocked(
        enabled: Boolean,
        resetEpoch: LocalDataResetWriteFence.Epoch,
    ) {
        filteringState.value = enabled
    }

    override suspend fun setSemanticClassifierEnabled(enabled: Boolean) {
        semanticClassifierState.value = enabled
    }

    override suspend fun setExternalAutomationEnabled(enabled: Boolean) {
        externalAutomationAuthorizationFence.withLock {
            state.value = enabled
        }
    }

    override suspend fun ensureExternalAutomationToken(): String =
        externalAutomationAuthorizationFence.withLock { tokenState.value }

    override suspend fun rotateExternalAutomationToken(): String =
        externalAutomationAuthorizationFence.withLock {
            "rotated-token".also { tokenState.value = it }
        }

    override suspend fun setLlmAnalysisEnabled(enabled: Boolean) {
        llmState.value = enabled
    }

    override suspend fun setLlmAutoActionsEnabled(enabled: Boolean) {
        llmAutoActionsState.value = enabled
    }

    override suspend fun setEventRetentionDays(days: Int) {
        eventRetentionState.value = days
    }

    override suspend fun setDailyInsightRetentionDays(days: Int) {
        insightRetentionState.value = days
    }

    override suspend fun setDynamicColorEnabled(enabled: Boolean) {
        dynamicColorState.value = enabled
    }

    override suspend fun setNotificationContentStorageEnabled(enabled: Boolean) {
        contentStorageState.value = enabled
    }

    override suspend fun setContentExcludedPackages(packageNames: Set<String>) {
        excludedPackagesState.value = packageNames
    }

    override suspend fun setContentPackageExcluded(
        packageName: String,
        excluded: Boolean,
    ) {
        excludedPackagesState.value =
            if (excluded) {
                excludedPackagesState.value + packageName
            } else {
                excludedPackagesState.value - packageName
            }
    }

    override suspend fun snapshot(): SettingsSnapshot =
        SettingsSnapshot(
            filteringEnabled = filteringState.value,
            semanticClassifierEnabled = semanticClassifierState.value,
            externalAutomationEnabled = state.value,
            llmAnalysisEnabled = llmState.value,
            llmAutoActionsEnabled = llmAutoActionsState.value,
            eventRetentionDays = eventRetentionState.value,
            dailyInsightRetentionDays = insightRetentionState.value,
        )

    override suspend fun maintenanceSnapshot(): MaintenanceSettingsSnapshot =
        MaintenanceSettingsSnapshot(
            eventRetentionDays = eventRetentionState.value,
            dailyInsightRetentionDays = insightRetentionState.value,
            notificationContentStorageEnabled = contentStorageState.value,
            contentExcludedPackages = excludedPackagesState.value,
        )

    override suspend fun restore(snapshot: SettingsSnapshot) {
        externalAutomationAuthorizationFence.withLock {
            filteringState.value = snapshot.filteringEnabled
            semanticClassifierState.value = snapshot.semanticClassifierEnabled
            state.value = snapshot.externalAutomationEnabled
            llmState.value = snapshot.llmAnalysisEnabled
            llmAutoActionsState.value = snapshot.llmAutoActionsEnabled
            eventRetentionState.value = snapshot.eventRetentionDays
            insightRetentionState.value = snapshot.dailyInsightRetentionDays
        }
    }

    override suspend fun reset() {
        externalAutomationAuthorizationFence.withLock {
            state.value = false
            filteringState.value = false
            semanticClassifierState.value = true
            llmState.value = false
            llmAutoActionsState.value = false
            eventRetentionState.value = RetentionDefaults.EVENT_DAYS
            insightRetentionState.value = RetentionDefaults.DAILY_INSIGHT_DAYS
            tokenState.value = ""
            dynamicColorState.value = false
            contentStorageState.value = false
            excludedPackagesState.value = emptySet()
        }
    }

    fun isFilteringEnabled(): Boolean = filteringState.value

    fun currentAutomationToken(): String = tokenState.value
}
