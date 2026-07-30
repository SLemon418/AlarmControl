package com.alarmcontrol.core.settings

import com.alarmcontrol.core.privacy.LocalDataResetWriteFence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class SemanticAnalysisScope {
    RULES_ONLY,
    ALL_NOTIFICATIONS,
}

/**
 * User preferences that affect app behavior (CLAUDE.md §4). The interface lives in `:core`; the
 * DataStore-backed implementation is in `:data`. Stored locally only — nothing leaves the device
 * (§1/§3).
 */
@Suppress("TooManyFunctions") // One cohesive persisted settings contract owns atomic snapshots and reset.
interface SettingsRepository {
    /** Global filtering gate. Defaults to true and never rewrites individual rule enabled states. */
    val filteringEnabled: Flow<Boolean>

    /** Whether the bundled seven-intent classifier may run for semantic rule conditions. */
    val semanticClassifierEnabled: Flow<Boolean>

    /** Whether optional, resource-intensive local LLM analysis is enabled. Defaults to false. */
    val llmAnalysisEnabled: Flow<Boolean>

    /** Retired compatibility surface. LLM analysis is always observation-only. */
    val llmAutoActionsEnabled: Flow<Boolean>
        get() = flowOf(false)

    /** Whether optional LLM history analysis is rule-triggered only or best-effort for every post. */
    val semanticAnalysisScope: Flow<SemanticAnalysisScope>
        get() = flowOf(SemanticAnalysisScope.RULES_ONLY)

    /**
     * Whether external automation (Samsung Routines, Tasker, …) may toggle filtering via the exported
     * receiver. Defaults to **false**: the exported entry point is inert until the user opts in (§7).
     */
    val externalAutomationEnabled: Flow<Boolean>

    /** Per-install secret required only by the exported automation receiver; never backed up. */
    val externalAutomationToken: Flow<String>

    /** Raw decision-log retention in days. */
    val eventRetentionDays: Flow<Int>

    /** Pre-aggregated daily-history retention in days. */
    val dailyInsightRetentionDays: Flow<Int>

    /** Whether Android 12+ wallpaper-derived Material You colors replace the bundled brand palette. */
    val dynamicColorEnabled: Flow<Boolean>

    /** Whether eligible title/body history is stored with Android-Keystore encryption. */
    val notificationContentStorageEnabled: Flow<Boolean>

    /** User-selected encrypted title/body retention in days. */
    val notificationContentRetentionDays: Flow<Int>
        get() = flowOf(RetentionDefaults.ENCRYPTED_CONTENT_DAYS)

    /** Packages whose title/body must not be retained even while encrypted storage is enabled. */
    val contentExcludedPackages: Flow<Set<String>>

    /** Persists the global filtering gate without changing any rule. */
    suspend fun setFilteringEnabled(enabled: Boolean)

    /** Persists the gate only while the caller's whole-data-reset generation remains current. */
    suspend fun setFilteringEnabledIfCurrent(
        enabled: Boolean,
        resetEpoch: LocalDataResetWriteFence.Epoch,
    ) {
        setFilteringEnabled(enabled)
    }

    /**
     * Filtering variant for an orchestrator that already owns [SettingsMutationFence]. Persistent
     * implementations must not reacquire that non-reentrant fence.
     */
    suspend fun setFilteringEnabledIfCurrentWhileMutationLocked(
        enabled: Boolean,
        resetEpoch: LocalDataResetWriteFence.Epoch,
    ) {
        setFilteringEnabledIfCurrent(enabled, resetEpoch)
    }

    /** Enables or disables bundled seven-intent inference without changing any rule. */
    suspend fun setSemanticClassifierEnabled(enabled: Boolean)

    /** Persists the explicit opt-in for optional on-device LLM analysis. */
    suspend fun setLlmAnalysisEnabled(enabled: Boolean)

    /** Retired compatibility surface; automatic LLM actions can no longer be enabled. */
    suspend fun setLlmAutoActionsEnabled(enabled: Boolean) = Unit

    suspend fun setSemanticAnalysisScope(scope: SemanticAnalysisScope) = Unit

    /** Persists the [externalAutomationEnabled] preference. */
    suspend fun setExternalAutomationEnabled(enabled: Boolean)

    /** Returns the existing secret or creates one locally using a cryptographic RNG. */
    suspend fun ensureExternalAutomationToken(): String

    /** Invalidates previous external integrations and returns a freshly generated secret. */
    suspend fun rotateExternalAutomationToken(): String

    suspend fun setEventRetentionDays(days: Int)

    suspend fun setDailyInsightRetentionDays(days: Int)

    /** Persists this device's appearance preference; it is intentionally excluded from backups. */
    suspend fun setDynamicColorEnabled(enabled: Boolean)

    /**
     * Changes encrypted notification-content retention. Disabling must delete every ciphertext row
     * and the local encryption key before the disabled preference is committed.
     */
    suspend fun setNotificationContentStorageEnabled(enabled: Boolean)

    /** Persists the bounded encrypted title/body retention period. */
    suspend fun setNotificationContentRetentionDays(days: Int) = Unit

    /**
     * Applies the default-on content policy once per installation. Implementations must keep content
     * inaccessible until any legacy uninitialized ciphertext has been safely reconciled.
     */
    suspend fun initializeNotificationContentStorageDefault() = Unit

    /**
     * Replaces the package exclusion policy without allowing previously stored ciphertext to become
     * readable when a package is removed from the set.
     */
    suspend fun setContentExcludedPackages(packageNames: Set<String>)

    /**
     * Adds or removes one package while preserving unrelated entries. Exclusion becomes effective
     * before old ciphertext is cleared; removal clears old ciphertext before access is restored.
     * A cleanup failure may therefore leave the package safely excluded, but can never reveal its
     * previously stored detail.
     */
    suspend fun setContentPackageExcluded(
        packageName: String,
        excluded: Boolean,
    )

    /** Returns one coherent, portable snapshot for local backup. */
    suspend fun snapshot(): SettingsSnapshot

    /**
     * Snapshot variant for an orchestrator that already owns [SettingsMutationFence]. Persistent
     * implementations must not reacquire that non-reentrant fence.
     */
    suspend fun snapshotWhileMutationLocked(): SettingsSnapshot = snapshot()

    /**
     * Returns one coherent policy snapshot for destructive local maintenance. Unlike UI-facing
     * flows, implementations must propagate storage read failures instead of substituting defaults.
     */
    suspend fun maintenanceSnapshot(): MaintenanceSettingsSnapshot

    /** Restores all preferences in one local DataStore edit after validation. */
    suspend fun restore(snapshot: SettingsSnapshot)

    /** Restores only while an operation-entry whole-data-reset generation remains current. */
    suspend fun restoreIfCurrent(
        snapshot: SettingsSnapshot,
        resetEpoch: LocalDataResetWriteFence.Epoch,
    ) {
        restore(snapshot)
    }

    /**
     * Restore variant for an orchestrator that already owns [SettingsMutationFence]. Persistent
     * implementations must not reacquire that non-reentrant fence.
     */
    suspend fun restoreIfCurrentWhileMutationLocked(
        snapshot: SettingsSnapshot,
        resetEpoch: LocalDataResetWriteFence.Epoch,
    ) {
        restoreIfCurrent(snapshot, resetEpoch)
    }

    /**
     * Restore variant for an orchestrator that already owns [SettingsMutationFence] and its
     * maintenance-policy boundary. Persistent implementations must reacquire neither guard.
     */
    suspend fun restoreIfCurrentWhileMutationAndMaintenanceLocked(
        snapshot: SettingsSnapshot,
        resetEpoch: LocalDataResetWriteFence.Epoch,
    ) {
        restoreIfCurrentWhileMutationLocked(snapshot, resetEpoch)
    }

    /**
     * Restores every preference to its privacy-safe reset state. Any stored notification content
     * and its key must be deleted before content storage becomes disabled.
     */
    suspend fun reset()

    /**
     * Reset variant for the whole-data coordinator while it already owns the settings-mutation,
     * maintenance-policy, and external-automation authorization boundaries. Persistent
     * implementations must not reacquire those non-reentrant guards.
     */
    suspend fun resetWhileMaintenanceLocked() {
        reset()
    }
}

/** Privacy-safe preferences included in the user-controlled local backup. */
data class SettingsSnapshot(
    val filteringEnabled: Boolean = true,
    val semanticClassifierEnabled: Boolean = true,
    val externalAutomationEnabled: Boolean = false,
    val llmAnalysisEnabled: Boolean = false,
    /** Legacy source compatibility only; persistence and backup boundaries always normalize false. */
    val llmAutoActionsEnabled: Boolean = false,
    val semanticAnalysisScope: SemanticAnalysisScope = SemanticAnalysisScope.RULES_ONLY,
    val eventRetentionDays: Int = RetentionDefaults.EVENT_DAYS,
    val dailyInsightRetentionDays: Int = RetentionDefaults.DAILY_INSIGHT_DAYS,
    val notificationContentRetentionDays: Int = RetentionDefaults.ENCRYPTED_CONTENT_DAYS,
)

/** Settings that authorize local retention and encrypted-content deletion. */
data class MaintenanceSettingsSnapshot(
    val eventRetentionDays: Int = RetentionDefaults.EVENT_DAYS,
    val dailyInsightRetentionDays: Int = RetentionDefaults.DAILY_INSIGHT_DAYS,
    val notificationContentStorageEnabled: Boolean = false,
    val notificationContentRetentionDays: Int = RetentionDefaults.ENCRYPTED_CONTENT_DAYS,
    val contentExcludedPackages: Set<String> = emptySet(),
)

object RetentionDefaults {
    const val EVENT_DAYS = 30
    const val DAILY_INSIGHT_DAYS = 365
    const val ENCRYPTED_CONTENT_DAYS = 7
    const val MIN_ENCRYPTED_CONTENT_DAYS = 1
    const val MAX_ENCRYPTED_CONTENT_DAYS = 30
}
