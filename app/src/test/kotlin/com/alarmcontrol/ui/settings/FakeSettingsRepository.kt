package com.alarmcontrol.ui.settings

import com.alarmcontrol.core.settings.RetentionDefaults
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.core.settings.SettingsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [SettingsRepository] for JVM tests. */
class FakeSettingsRepository(
    enabled: Boolean = false,
    filtering: Boolean = true,
    llmEnabled: Boolean = false,
    llmAutoActions: Boolean = false,
    eventDays: Int = RetentionDefaults.EVENT_DAYS,
    insightDays: Int = RetentionDefaults.DAILY_INSIGHT_DAYS,
    dynamicColor: Boolean = false,
) : SettingsRepository {
    private val state = MutableStateFlow(enabled)
    private val filteringState = MutableStateFlow(filtering)
    private val llmState = MutableStateFlow(llmEnabled)
    private val llmAutoActionsState = MutableStateFlow(llmAutoActions)
    private val eventRetentionState = MutableStateFlow(eventDays)
    private val insightRetentionState = MutableStateFlow(insightDays)
    private val tokenState = MutableStateFlow("")
    private val dynamicColorState = MutableStateFlow(dynamicColor)
    private val contentStorageState = MutableStateFlow(false)
    private val excludedPackagesState = MutableStateFlow(emptySet<String>())

    override val filteringEnabled: Flow<Boolean> = filteringState

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
        filteringState.value = enabled
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

    override suspend fun snapshot(): SettingsSnapshot =
        SettingsSnapshot(
            filteringEnabled = filteringState.value,
            externalAutomationEnabled = state.value,
            llmAnalysisEnabled = llmState.value,
            llmAutoActionsEnabled = llmAutoActionsState.value,
            eventRetentionDays = eventRetentionState.value,
            dailyInsightRetentionDays = insightRetentionState.value,
        )

    override suspend fun restore(snapshot: SettingsSnapshot) {
        filteringState.value = snapshot.filteringEnabled
        state.value = snapshot.externalAutomationEnabled
        llmState.value = snapshot.llmAnalysisEnabled
        llmAutoActionsState.value = snapshot.llmAutoActionsEnabled
        eventRetentionState.value = snapshot.eventRetentionDays
        insightRetentionState.value = snapshot.dailyInsightRetentionDays
    }

    override suspend fun reset() {
        state.value = false
        filteringState.value = true
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
