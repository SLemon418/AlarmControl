package com.alarmcontrol.ui.settings

import com.alarmcontrol.core.settings.MaintenanceSettingsSnapshot
import com.alarmcontrol.core.settings.RetentionDefaults
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.core.settings.SettingsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [SettingsRepository] for JVM tests. */
class FakeSettingsRepository(
    enabled: Boolean = false,
    filtering: Boolean = true,
    semanticClassifier: Boolean = true,
    llmEnabled: Boolean = false,
    eventDays: Int = RetentionDefaults.EVENT_DAYS,
    insightDays: Int = RetentionDefaults.DAILY_INSIGHT_DAYS,
    dynamicColor: Boolean = false,
) : SettingsRepository {
    val operationLog = mutableListOf<String>()
    var beforeSetContentExcludedPackages: suspend (Set<String>) -> Unit = {}
    var beforeSetNotificationContentStorageEnabled: suspend (Boolean) -> Unit = {}
    private val state = MutableStateFlow(enabled)
    private val filteringState = MutableStateFlow(filtering)
    private val semanticClassifierState = MutableStateFlow(semanticClassifier)
    private val llmState = MutableStateFlow(llmEnabled)
    private val eventRetentionState = MutableStateFlow(eventDays)
    private val insightRetentionState = MutableStateFlow(insightDays)
    private val tokenState = MutableStateFlow("")
    private val dynamicColorState = MutableStateFlow(dynamicColor)
    private val contentStorageState = MutableStateFlow(false)
    private val excludedPackagesState = MutableStateFlow(emptySet<String>())

    override val filteringEnabled: Flow<Boolean> = filteringState
    override val semanticClassifierEnabled: Flow<Boolean> = semanticClassifierState

    override val externalAutomationEnabled: Flow<Boolean> = state
    override val externalAutomationToken: Flow<String> = tokenState

    override val llmAnalysisEnabled: Flow<Boolean> = llmState

    override val eventRetentionDays: Flow<Int> = eventRetentionState

    override val dailyInsightRetentionDays: Flow<Int> = insightRetentionState

    override val dynamicColorEnabled: Flow<Boolean> = dynamicColorState
    override val notificationContentStorageEnabled: Flow<Boolean> = contentStorageState
    override val contentExcludedPackages: Flow<Set<String>> = excludedPackagesState

    override suspend fun setFilteringEnabled(enabled: Boolean) {
        operationLog += "filtering:$enabled"
        filteringState.value = enabled
    }

    override suspend fun setSemanticClassifierEnabled(enabled: Boolean) {
        semanticClassifierState.value = enabled
    }

    override suspend fun setExternalAutomationEnabled(enabled: Boolean) {
        state.value = enabled
        if (enabled && tokenState.value.isBlank()) tokenState.value = "test-token"
    }

    override suspend fun ensureExternalAutomationToken(): String =
        tokenState.value.ifBlank { "test-token".also { tokenState.value = it } }

    override suspend fun rotateExternalAutomationToken(): String = "rotated-token".also { tokenState.value = it }

    override suspend fun setLlmAnalysisEnabled(enabled: Boolean) {
        llmState.value = enabled
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
        beforeSetNotificationContentStorageEnabled(enabled)
        operationLog += "content-storage:$enabled"
        contentStorageState.value = enabled
    }

    override suspend fun setContentExcludedPackages(packageNames: Set<String>) {
        beforeSetContentExcludedPackages(packageNames)
        operationLog += "excluded-packages:${packageNames.sorted().joinToString()}"
        excludedPackagesState.value = packageNames
    }

    override suspend fun setContentPackageExcluded(
        packageName: String,
        excluded: Boolean,
    ) {
        val updated =
            if (excluded) {
                excludedPackagesState.value + packageName
            } else {
                excludedPackagesState.value - packageName
            }
        beforeSetContentExcludedPackages(updated)
        operationLog += "excluded-package:$packageName:$excluded"
        excludedPackagesState.value = updated
    }

    override suspend fun snapshot(): SettingsSnapshot =
        SettingsSnapshot(
            filteringEnabled = filteringState.value,
            semanticClassifierEnabled = semanticClassifierState.value,
            externalAutomationEnabled = state.value,
            llmAnalysisEnabled = llmState.value,
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
        filteringState.value = snapshot.filteringEnabled
        semanticClassifierState.value = snapshot.semanticClassifierEnabled
        state.value = snapshot.externalAutomationEnabled
        llmState.value = snapshot.llmAnalysisEnabled
        eventRetentionState.value = snapshot.eventRetentionDays
        insightRetentionState.value = snapshot.dailyInsightRetentionDays
    }

    override suspend fun reset() {
        operationLog += "reset"
        state.value = false
        filteringState.value = false
        semanticClassifierState.value = true
        llmState.value = false
        eventRetentionState.value = RetentionDefaults.EVENT_DAYS
        insightRetentionState.value = RetentionDefaults.DAILY_INSIGHT_DAYS
        tokenState.value = ""
        dynamicColorState.value = false
        contentStorageState.value = false
        excludedPackagesState.value = emptySet()
    }
}
