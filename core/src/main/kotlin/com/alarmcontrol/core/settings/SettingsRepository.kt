package com.alarmcontrol.core.settings

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
interface SettingsRepository {
    /** Global filtering gate. Defaults to true and never rewrites individual rule enabled states. */
    val filteringEnabled: Flow<Boolean>

    /** Whether optional, resource-intensive local LLM analysis is enabled. Defaults to false. */
    val llmAnalysisEnabled: Flow<Boolean>

    /** Whether a confident LLM verdict may satisfy an ad rule. Defaults to false (observation-only). */
    val llmAutoActionsEnabled: Flow<Boolean>

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

    /** Explicit opt-in for seven-day Android-Keystore encrypted title/body history. */
    val notificationContentStorageEnabled: Flow<Boolean>

    /** Packages whose title/body must not be retained even while encrypted storage is enabled. */
    val contentExcludedPackages: Flow<Set<String>>

    /** Persists the global filtering gate without changing any rule. */
    suspend fun setFilteringEnabled(enabled: Boolean)

    /** Persists the explicit opt-in for optional on-device LLM analysis. */
    suspend fun setLlmAnalysisEnabled(enabled: Boolean)

    suspend fun setLlmAutoActionsEnabled(enabled: Boolean)

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

    suspend fun setNotificationContentStorageEnabled(enabled: Boolean)

    suspend fun setContentExcludedPackages(packageNames: Set<String>)

    /** Returns one coherent, portable snapshot for local backup. */
    suspend fun snapshot(): SettingsSnapshot

    /** Restores all preferences in one local DataStore edit after validation. */
    suspend fun restore(snapshot: SettingsSnapshot)

    /** Restores every preference to its privacy-safe default. */
    suspend fun reset()
}

/** Privacy-safe preferences included in the user-controlled local backup. */
data class SettingsSnapshot(
    val filteringEnabled: Boolean = true,
    val externalAutomationEnabled: Boolean = false,
    val llmAnalysisEnabled: Boolean = false,
    val llmAutoActionsEnabled: Boolean = false,
    val semanticAnalysisScope: SemanticAnalysisScope = SemanticAnalysisScope.RULES_ONLY,
    val eventRetentionDays: Int = RetentionDefaults.EVENT_DAYS,
    val dailyInsightRetentionDays: Int = RetentionDefaults.DAILY_INSIGHT_DAYS,
)

object RetentionDefaults {
    const val EVENT_DAYS = 30
    const val DAILY_INSIGHT_DAYS = 365
    const val ENCRYPTED_CONTENT_DAYS = 7
}
