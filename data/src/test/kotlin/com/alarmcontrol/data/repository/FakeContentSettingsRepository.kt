package com.alarmcontrol.data.repository

import com.alarmcontrol.core.settings.RetentionDefaults
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.core.settings.SettingsSnapshot
import com.alarmcontrol.data.security.NotificationContentAccessGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** Minimal mutable settings fake for repository privacy-boundary tests. */
internal class FakeContentSettingsRepository(
    contentEnabled: Boolean = true,
    excludedPackages: Set<String> = emptySet(),
    private val contentAccessGuard: NotificationContentAccessGuard = NotificationContentAccessGuard(),
) : SettingsRepository {
    private val contentEnabledState = MutableStateFlow(contentEnabled)
    private val excludedPackagesState = MutableStateFlow(excludedPackages)

    override val filteringEnabled: Flow<Boolean> = flowOf(true)
    override val llmAnalysisEnabled: Flow<Boolean> = flowOf(false)
    override val llmAutoActionsEnabled: Flow<Boolean> = flowOf(false)
    override val externalAutomationEnabled: Flow<Boolean> = flowOf(false)
    override val externalAutomationToken: Flow<String> = flowOf("")
    override val eventRetentionDays: Flow<Int> = flowOf(RetentionDefaults.EVENT_DAYS)
    override val dailyInsightRetentionDays: Flow<Int> = flowOf(RetentionDefaults.DAILY_INSIGHT_DAYS)
    override val dynamicColorEnabled: Flow<Boolean> = flowOf(false)
    override val notificationContentStorageEnabled: Flow<Boolean> = contentEnabledState
    override val contentExcludedPackages: Flow<Set<String>> = excludedPackagesState

    override suspend fun setFilteringEnabled(enabled: Boolean) = Unit

    override suspend fun setLlmAnalysisEnabled(enabled: Boolean) = Unit

    override suspend fun setLlmAutoActionsEnabled(enabled: Boolean) = Unit

    override suspend fun setExternalAutomationEnabled(enabled: Boolean) = Unit

    override suspend fun ensureExternalAutomationToken(): String = ""

    override suspend fun rotateExternalAutomationToken(): String = ""

    override suspend fun setEventRetentionDays(days: Int) = Unit

    override suspend fun setDailyInsightRetentionDays(days: Int) = Unit

    override suspend fun setDynamicColorEnabled(enabled: Boolean) = Unit

    override suspend fun setNotificationContentStorageEnabled(enabled: Boolean) {
        contentAccessGuard.withLock {
            contentEnabledState.value = enabled
        }
    }

    override suspend fun setContentExcludedPackages(packageNames: Set<String>) {
        contentAccessGuard.withLock {
            excludedPackagesState.value = packageNames
        }
    }

    override suspend fun snapshot(): SettingsSnapshot = SettingsSnapshot()

    override suspend fun restore(snapshot: SettingsSnapshot) = Unit

    override suspend fun reset() {
        contentAccessGuard.withLock {
            contentEnabledState.value = false
            excludedPackagesState.value = emptySet()
        }
    }
}
