package com.alarmcontrol.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.alarmcontrol.core.privacy.LocalDataResetWriteFence
import com.alarmcontrol.core.privacy.StaleLocalDataWriteException
import com.alarmcontrol.core.settings.ExternalAutomationAuthorizationFence
import com.alarmcontrol.core.settings.FilteringActionGate
import com.alarmcontrol.core.settings.MaintenanceSettingsSnapshot
import com.alarmcontrol.core.settings.RetentionDefaults
import com.alarmcontrol.core.settings.SemanticAnalysisScope
import com.alarmcontrol.core.settings.SettingsMutationFence
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.core.settings.SettingsSnapshot
import com.alarmcontrol.data.security.MaintenancePolicyAccessGuard
import com.alarmcontrol.data.security.NotificationContentAccessGuard
import com.alarmcontrol.data.security.StoredNotificationContentCleaner
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.inject.Inject

/**
 * DataStore-backed settings. UI flows degrade to privacy-safe defaults on I/O errors; authoritative
 * backup and maintenance snapshots propagate failures so callers cannot persist or delete from
 * substituted values.
 */
@Suppress("TooManyFunctions") // One cohesive DataStore settings contract owns snapshot and restore.
class SettingsRepositoryImpl
    @Inject
    internal constructor(
        private val dataStore: DataStore<Preferences>,
        private val contentAccessGuard: NotificationContentAccessGuard,
        private val storedNotificationContentCleaner: StoredNotificationContentCleaner,
        private val clock: Clock = Clock.systemDefaultZone(),
        private val maintenancePolicyAccessGuard: MaintenancePolicyAccessGuard =
            MaintenancePolicyAccessGuard(),
        private val filteringActionGate: FilteringActionGate = FilteringActionGate(),
        private val localDataResetWriteFence: LocalDataResetWriteFence = LocalDataResetWriteFence(),
        private val externalAutomationAuthorizationFence: ExternalAutomationAuthorizationFence =
            ExternalAutomationAuthorizationFence(),
        private val settingsMutationFence: SettingsMutationFence = SettingsMutationFence(),
    ) : SettingsRepository {
        private val filteringMutationMutex = Mutex()

        override val filteringEnabled: Flow<Boolean> =
            dataStore.data
                .map { prefs -> prefs[FILTERING_ENABLED] ?: true }
                .catch { error -> if (error is IOException) emit(false) else throw error }

        override val semanticClassifierEnabled: Flow<Boolean> =
            dataStore.data
                .map { prefs -> prefs[SEMANTIC_CLASSIFIER_ENABLED] ?: true }
                .catch { error -> if (error is IOException) emit(false) else throw error }

        override val externalAutomationEnabled: Flow<Boolean> =
            dataStore.data
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .map { prefs -> prefs[EXTERNAL_AUTOMATION_ENABLED] ?: false }

        override val externalAutomationToken: Flow<String> =
            dataStore.data
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .map { prefs ->
                    prefs[EXTERNAL_AUTOMATION_TOKEN]
                        .orEmpty()
                        .takeIf { it.isValidAutomationToken() }
                        .orEmpty()
                }

        override val llmAnalysisEnabled: Flow<Boolean> =
            dataStore.data
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .map { prefs -> prefs[LLM_ANALYSIS_ENABLED] ?: false }

        override val llmAutoActionsEnabled: Flow<Boolean> = flowOf(false)

        override val semanticAnalysisScope: Flow<SemanticAnalysisScope> =
            dataStore.data
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .map { prefs ->
                    prefs[SEMANTIC_ANALYSIS_SCOPE]
                        ?.let { stored -> enumValues<SemanticAnalysisScope>().firstOrNull { it.name == stored } }
                        ?: SemanticAnalysisScope.RULES_ONLY
                }

        override val eventRetentionDays: Flow<Int> =
            dataStore.data
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .map { prefs ->
                    prefs[EVENT_RETENTION_DAYS]
                        ?.takeIf { it in RETENTION_RANGE }
                        ?: RetentionDefaults.EVENT_DAYS
                }

        override val dailyInsightRetentionDays: Flow<Int> =
            dataStore.data
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .map { prefs ->
                    prefs[DAILY_INSIGHT_RETENTION_DAYS]
                        ?.takeIf { it in RETENTION_RANGE }
                        ?: RetentionDefaults.DAILY_INSIGHT_DAYS
                }

        override val dynamicColorEnabled: Flow<Boolean> =
            dataStore.data
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .map { prefs -> prefs[DYNAMIC_COLOR_ENABLED] ?: false }

        override val notificationContentStorageEnabled: Flow<Boolean> =
            dataStore.data
                .map { prefs ->
                    prefs[NOTIFICATION_CONTENT_STORAGE_INITIALIZED] == true &&
                        prefs[NOTIFICATION_CONTENT_STORAGE_ENABLED] == true
                }.catch { error -> if (error is IOException) emit(false) else throw error }

        override val notificationContentRetentionDays: Flow<Int> =
            dataStore.data
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .map { prefs ->
                    prefs[NOTIFICATION_CONTENT_RETENTION_DAYS]
                        ?.takeIf { it in CONTENT_RETENTION_RANGE }
                        ?: RetentionDefaults.ENCRYPTED_CONTENT_DAYS
                }

        override val contentExcludedPackages: Flow<Set<String>> =
            dataStore.data
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .map { prefs -> prefs[CONTENT_EXCLUDED_PACKAGES].orEmpty() }

        override suspend fun setExternalAutomationEnabled(enabled: Boolean) =
            settingsMutationFence.withLock {
                externalAutomationAuthorizationFence.withLock {
                    dataStore.edit { prefs ->
                        prefs[EXTERNAL_AUTOMATION_ENABLED] = enabled
                        if (enabled && prefs[EXTERNAL_AUTOMATION_TOKEN]?.isValidAutomationToken() != true) {
                            prefs[EXTERNAL_AUTOMATION_TOKEN] = newAutomationToken()
                        }
                    }
                    Unit
                }
            }

        override suspend fun ensureExternalAutomationToken(): String =
            settingsMutationFence.withLock {
                externalAutomationAuthorizationFence.withLock {
                    var token = ""
                    dataStore.edit { prefs ->
                        token =
                            prefs[EXTERNAL_AUTOMATION_TOKEN]
                                ?.takeIf { it.isValidAutomationToken() }
                                ?: newAutomationToken()
                        prefs[EXTERNAL_AUTOMATION_TOKEN] = token
                    }
                    token
                }
            }

        override suspend fun rotateExternalAutomationToken(): String =
            settingsMutationFence.withLock {
                externalAutomationAuthorizationFence.withLock {
                    val token = newAutomationToken()
                    dataStore.edit { prefs -> prefs[EXTERNAL_AUTOMATION_TOKEN] = token }
                    token
                }
            }

        override suspend fun setFilteringEnabled(enabled: Boolean) =
            setFilteringEnabledIfCurrent(enabled, localDataResetWriteFence.captureEpoch())

        override suspend fun setFilteringEnabledIfCurrent(
            enabled: Boolean,
            resetEpoch: LocalDataResetWriteFence.Epoch,
        ) = settingsMutationFence.withLock {
            setFilteringEnabledIfCurrentWhileMutationLocked(enabled, resetEpoch)
        }

        override suspend fun setFilteringEnabledIfCurrentWhileMutationLocked(
            enabled: Boolean,
            resetEpoch: LocalDataResetWriteFence.Epoch,
        ) {
            localDataResetWriteFence.writeIfCurrent(resetEpoch) {
                withFilteringGateReconciliation {
                    dataStore.edit { prefs -> prefs[FILTERING_ENABLED] = enabled }
                }
                Unit
            } ?: throw StaleLocalDataWriteException()
        }

        override suspend fun setSemanticClassifierEnabled(enabled: Boolean) =
            settingsMutationFence.withLock {
                dataStore.edit { prefs -> prefs[SEMANTIC_CLASSIFIER_ENABLED] = enabled }
                Unit
            }

        override suspend fun setLlmAnalysisEnabled(enabled: Boolean) =
            settingsMutationFence.withLock {
                dataStore.edit { prefs ->
                    prefs[LLM_ANALYSIS_ENABLED] = enabled
                    prefs.remove(LLM_AUTO_ACTIONS_ENABLED)
                }
                Unit
            }

        override suspend fun setLlmAutoActionsEnabled(enabled: Boolean) =
            settingsMutationFence.withLock {
                dataStore.edit { prefs -> prefs.remove(LLM_AUTO_ACTIONS_ENABLED) }
                Unit
            }

        override suspend fun setSemanticAnalysisScope(scope: SemanticAnalysisScope) =
            settingsMutationFence.withLock {
                dataStore.edit { prefs -> prefs[SEMANTIC_ANALYSIS_SCOPE] = scope.name }
                Unit
            }

        override suspend fun setEventRetentionDays(days: Int) {
            require(days in RETENTION_RANGE) { "Event retention is out of range" }
            settingsMutationFence.withLock {
                maintenancePolicyAccessGuard.withLock {
                    dataStore.edit { prefs -> prefs[EVENT_RETENTION_DAYS] = days }
                }
            }
        }

        override suspend fun setDailyInsightRetentionDays(days: Int) {
            require(days in RETENTION_RANGE) { "Insight retention is out of range" }
            settingsMutationFence.withLock {
                maintenancePolicyAccessGuard.withLock {
                    dataStore.edit { prefs -> prefs[DAILY_INSIGHT_RETENTION_DAYS] = days }
                }
            }
        }

        override suspend fun setDynamicColorEnabled(enabled: Boolean) =
            settingsMutationFence.withLock {
                dataStore.edit { prefs -> prefs[DYNAMIC_COLOR_ENABLED] = enabled }
                Unit
            }

        override suspend fun setNotificationContentStorageEnabled(enabled: Boolean) {
            settingsMutationFence.withLock {
                maintenancePolicyAccessGuard.withLock {
                    contentAccessGuard.withLock {
                        val current = dataStore.data.first()
                        val currentlyEnabled =
                            current[NOTIFICATION_CONTENT_STORAGE_INITIALIZED] == true &&
                                current[NOTIFICATION_CONTENT_STORAGE_ENABLED] == true
                        if (!enabled || !currentlyEnabled) {
                            // A failed prior reset may have committed the safe disabled preference
                            // while ciphertext cleanup failed. Retry the scrub before any later
                            // enable so disabling can never make old detail readable again.
                            storedNotificationContentCleaner.clear()
                        }
                        dataStore.edit { prefs ->
                            prefs[NOTIFICATION_CONTENT_STORAGE_ENABLED] = enabled
                            prefs[NOTIFICATION_CONTENT_STORAGE_INITIALIZED] = true
                        }
                    }
                }
            }
        }

        override suspend fun setNotificationContentRetentionDays(days: Int) {
            require(days in CONTENT_RETENTION_RANGE) { "Content retention is out of range" }
            settingsMutationFence.withLock {
                maintenancePolicyAccessGuard.withLock {
                    contentAccessGuard.withLock {
                        scrubBeforeRetentionIncrease(days)
                        dataStore.edit { prefs -> prefs[NOTIFICATION_CONTENT_RETENTION_DAYS] = days }
                    }
                }
            }
        }

        override suspend fun initializeNotificationContentStorageDefault() {
            settingsMutationFence.withLock {
                maintenancePolicyAccessGuard.withLock {
                    contentAccessGuard.withLock {
                        val current = dataStore.data.first()
                        if (current[NOTIFICATION_CONTENT_STORAGE_INITIALIZED] != true) {
                            val persistedChoice = current[NOTIFICATION_CONTENT_STORAGE_ENABLED]
                            if (persistedChoice == null) {
                                // A missing key can mean either a fresh install or an older failed
                                // reset. Scrub first so the new default cannot expose legacy
                                // ciphertext.
                                storedNotificationContentCleaner.clear()
                            }
                            dataStore.edit { prefs ->
                                if (persistedChoice == null) {
                                    prefs[NOTIFICATION_CONTENT_STORAGE_ENABLED] = true
                                }
                                prefs[NOTIFICATION_CONTENT_STORAGE_INITIALIZED] = true
                            }
                        }
                    }
                }
            }
        }

        override suspend fun setContentExcludedPackages(packageNames: Set<String>) {
            require(packageNames.size <= MAX_EXCLUDED_PACKAGES) { "Too many excluded packages" }
            require(packageNames.all { it.isNotBlank() && it.length <= MAX_PACKAGE_NAME_CHARS }) {
                "Invalid excluded package"
            }
            settingsMutationFence.withLock {
                maintenancePolicyAccessGuard.withLock {
                    contentAccessGuard.withLock {
                        replaceContentExcludedPackages(packageNames)
                    }
                }
            }
        }

        override suspend fun setContentPackageExcluded(
            packageName: String,
            excluded: Boolean,
        ) {
            require(packageName.isNotBlank() && packageName.length <= MAX_PACKAGE_NAME_CHARS) {
                "Invalid excluded package"
            }
            settingsMutationFence.withLock {
                maintenancePolicyAccessGuard.withLock {
                    contentAccessGuard.withLock {
                        var current = emptySet<String>()
                        dataStore.edit { prefs ->
                            current = prefs[CONTENT_EXCLUDED_PACKAGES].orEmpty()
                        }
                        val updated = if (excluded) current + packageName else current - packageName
                        require(updated.size <= MAX_EXCLUDED_PACKAGES) { "Too many excluded packages" }
                        replaceContentExcludedPackages(current, updated)
                    }
                }
            }
        }

        override suspend fun snapshot(): SettingsSnapshot =
            settingsMutationFence.withLock {
                snapshotWhileMutationLocked()
            }

        override suspend fun snapshotWhileMutationLocked(): SettingsSnapshot {
            val prefs = dataStore.data.first()
            return SettingsSnapshot(
                filteringEnabled = prefs[FILTERING_ENABLED] ?: true,
                semanticClassifierEnabled = prefs[SEMANTIC_CLASSIFIER_ENABLED] ?: true,
                externalAutomationEnabled = prefs[EXTERNAL_AUTOMATION_ENABLED] ?: false,
                llmAnalysisEnabled = prefs[LLM_ANALYSIS_ENABLED] ?: false,
                llmAutoActionsEnabled = false,
                semanticAnalysisScope =
                    prefs[SEMANTIC_ANALYSIS_SCOPE]
                        ?.let { stored -> enumValues<SemanticAnalysisScope>().firstOrNull { it.name == stored } }
                        ?: SemanticAnalysisScope.RULES_ONLY,
                eventRetentionDays =
                    prefs[EVENT_RETENTION_DAYS]?.takeIf { it in RETENTION_RANGE }
                        ?: RetentionDefaults.EVENT_DAYS,
                dailyInsightRetentionDays =
                    prefs[DAILY_INSIGHT_RETENTION_DAYS]?.takeIf { it in RETENTION_RANGE }
                        ?: RetentionDefaults.DAILY_INSIGHT_DAYS,
                notificationContentRetentionDays =
                    prefs[NOTIFICATION_CONTENT_RETENTION_DAYS]?.takeIf { it in CONTENT_RETENTION_RANGE }
                        ?: RetentionDefaults.ENCRYPTED_CONTENT_DAYS,
            )
        }

        override suspend fun maintenanceSnapshot(): MaintenanceSettingsSnapshot {
            val prefs = dataStore.data.first()
            val eventRetentionDays = prefs[EVENT_RETENTION_DAYS] ?: RetentionDefaults.EVENT_DAYS
            val dailyInsightRetentionDays =
                prefs[DAILY_INSIGHT_RETENTION_DAYS] ?: RetentionDefaults.DAILY_INSIGHT_DAYS
            val notificationContentRetentionDays =
                prefs[NOTIFICATION_CONTENT_RETENTION_DAYS] ?: RetentionDefaults.ENCRYPTED_CONTENT_DAYS
            check(eventRetentionDays in RETENTION_RANGE) { "Stored event retention is invalid" }
            check(dailyInsightRetentionDays in RETENTION_RANGE) {
                "Stored insight retention is invalid"
            }
            check(notificationContentRetentionDays in CONTENT_RETENTION_RANGE) {
                "Stored content retention is invalid"
            }
            val excludedPackages = prefs[CONTENT_EXCLUDED_PACKAGES].orEmpty()
            check(excludedPackages.size <= MAX_EXCLUDED_PACKAGES) {
                "Stored content exclusions are invalid"
            }
            check(
                excludedPackages.all {
                    it.isNotBlank() && it.length <= MAX_PACKAGE_NAME_CHARS
                },
            ) {
                "Stored content exclusions are invalid"
            }
            return MaintenanceSettingsSnapshot(
                eventRetentionDays = eventRetentionDays,
                dailyInsightRetentionDays = dailyInsightRetentionDays,
                notificationContentStorageEnabled =
                    prefs[NOTIFICATION_CONTENT_STORAGE_INITIALIZED] == true &&
                        prefs[NOTIFICATION_CONTENT_STORAGE_ENABLED] == true,
                notificationContentRetentionDays = notificationContentRetentionDays,
                contentExcludedPackages = excludedPackages,
            )
        }

        override suspend fun restore(snapshot: SettingsSnapshot) =
            settingsMutationFence.withLock {
                maintenancePolicyAccessGuard.withLock {
                    externalAutomationAuthorizationFence.withLock {
                        restoreWhileMaintenanceLocked(snapshot)
                    }
                }
            }

        override suspend fun restoreIfCurrent(
            snapshot: SettingsSnapshot,
            resetEpoch: LocalDataResetWriteFence.Epoch,
        ) = settingsMutationFence.withLock {
            restoreIfCurrentWhileMutationLocked(snapshot, resetEpoch)
        }

        override suspend fun restoreIfCurrentWhileMutationLocked(
            snapshot: SettingsSnapshot,
            resetEpoch: LocalDataResetWriteFence.Epoch,
        ) {
            maintenancePolicyAccessGuard.withLock {
                restoreIfCurrentWhileMutationAndMaintenanceLocked(snapshot, resetEpoch)
            }
        }

        override suspend fun restoreIfCurrentWhileMutationAndMaintenanceLocked(
            snapshot: SettingsSnapshot,
            resetEpoch: LocalDataResetWriteFence.Epoch,
        ) {
            externalAutomationAuthorizationFence.withLock {
                localDataResetWriteFence.writeIfCurrent(resetEpoch) {
                    restoreWhileMaintenanceLocked(snapshot)
                    Unit
                } ?: throw StaleLocalDataWriteException()
            }
        }

        private suspend fun restoreWhileMaintenanceLocked(snapshot: SettingsSnapshot) {
            require(snapshot.eventRetentionDays in RETENTION_RANGE) { "Event retention is out of range" }
            require(snapshot.dailyInsightRetentionDays in RETENTION_RANGE) { "Insight retention is out of range" }
            require(snapshot.notificationContentRetentionDays in CONTENT_RETENTION_RANGE) {
                "Content retention is out of range"
            }
            contentAccessGuard.withLock {
                scrubBeforeRetentionIncrease(snapshot.notificationContentRetentionDays)
                withFilteringGateReconciliation {
                    dataStore.edit { prefs ->
                        prefs[FILTERING_ENABLED] = snapshot.filteringEnabled
                        prefs[SEMANTIC_CLASSIFIER_ENABLED] = snapshot.semanticClassifierEnabled
                        prefs[EXTERNAL_AUTOMATION_ENABLED] = snapshot.externalAutomationEnabled
                        if (
                            snapshot.externalAutomationEnabled &&
                            prefs[EXTERNAL_AUTOMATION_TOKEN]?.isValidAutomationToken() != true
                        ) {
                            // The per-install secret is deliberately absent from portable backups.
                            // A fresh restore still needs a valid local token or the UI would claim
                            // automation is enabled while every authenticated broadcast is rejected.
                            prefs[EXTERNAL_AUTOMATION_TOKEN] = newAutomationToken()
                        }
                        prefs[LLM_ANALYSIS_ENABLED] = snapshot.llmAnalysisEnabled
                        prefs.remove(LLM_AUTO_ACTIONS_ENABLED)
                        prefs[SEMANTIC_ANALYSIS_SCOPE] = snapshot.semanticAnalysisScope.name
                        prefs[EVENT_RETENTION_DAYS] = snapshot.eventRetentionDays
                        prefs[DAILY_INSIGHT_RETENTION_DAYS] = snapshot.dailyInsightRetentionDays
                        prefs[NOTIFICATION_CONTENT_RETENTION_DAYS] =
                            snapshot.notificationContentRetentionDays
                    }
                }
            }
        }

        override suspend fun reset() =
            settingsMutationFence.withLock {
                maintenancePolicyAccessGuard.withLock {
                    externalAutomationAuthorizationFence.withLock {
                        resetWhileMaintenanceLocked()
                    }
                }
            }

        override suspend fun resetWhileMaintenanceLocked() {
            withFilteringGateReconciliation(reconcilePersistedStateOnFailure = false) {
                contentAccessGuard.withLock {
                    val failures = mutableListOf<Throwable>()
                    failures.captureResetFailure {
                        storedNotificationContentCleaner.clear()
                    }
                    failures.captureResetFailure {
                        dataStore.edit { preferences ->
                            preferences.clear()
                            // A destructive local-data reset must never reactivate surviving rules
                            // when a separate database deletion failed. Keep the master gate
                            // fail-safe and require the user to resume filtering explicitly.
                            preferences[FILTERING_ENABLED] = false
                            // Whole-data reset is deliberately safer than the fresh-install
                            // default: do not restart detail capture after deleting local data.
                            preferences[NOTIFICATION_CONTENT_STORAGE_ENABLED] = false
                            preferences[NOTIFICATION_CONTENT_STORAGE_INITIALIZED] = true
                        }
                    }
                    failures.throwFirstResetFailure()
                }
            }
        }

        private suspend fun <T> withFilteringGateReconciliation(
            reconcilePersistedStateOnFailure: Boolean = true,
            mutation: suspend () -> T,
        ): T =
            filteringMutationMutex.withLock {
                var completed = false
                try {
                    filteringActionGate.blockActions()
                    mutation().also { completed = true }
                } finally {
                    withContext(NonCancellable) {
                        if (completed || reconcilePersistedStateOnFailure) {
                            reconcileFilteringGateWithPersistedState()
                        } else {
                            filteringActionGate.blockActions()
                        }
                    }
                }
            }

        private suspend fun reconcileFilteringGateWithPersistedState() {
            val persistedEnabled =
                runCatching {
                    dataStore.data.first()[FILTERING_ENABLED] ?: true
                }.getOrNull()
            if (persistedEnabled == true) {
                filteringActionGate.requestRuleRefresh()
                filteringActionGate.allowActions()
            } else {
                filteringActionGate.blockActions()
            }
        }

        private suspend fun replaceContentExcludedPackages(packageNames: Set<String>) {
            var current = emptySet<String>()
            dataStore.edit { prefs ->
                current = prefs[CONTENT_EXCLUDED_PACKAGES].orEmpty()
            }
            replaceContentExcludedPackages(current, packageNames)
        }

        private suspend fun replaceContentExcludedPackages(
            current: Set<String>,
            updated: Set<String>,
        ) {
            // Never make old ciphertext readable: scrub reallowed packages before policy removal.
            // New exclusions commit first so a failed scrub still blocks reads and future capture.
            (current - updated).forEach { packageName ->
                storedNotificationContentCleaner.clearForPackage(packageName)
            }
            dataStore.edit { prefs -> prefs[CONTENT_EXCLUDED_PACKAGES] = updated }
            (updated - current).forEach { packageName ->
                storedNotificationContentCleaner.clearForPackage(packageName)
            }
        }

        private suspend fun scrubBeforeRetentionIncrease(updatedDays: Int) {
            val currentDays =
                dataStore.data
                    .first()[NOTIFICATION_CONTENT_RETENTION_DAYS]
                    ?: RetentionDefaults.ENCRYPTED_CONTENT_DAYS
            check(currentDays in CONTENT_RETENTION_RANGE) { "Stored content retention is invalid" }
            if (updatedDays > currentDays) {
                val nowMillis = clock.millis()
                storedNotificationContentCleaner.clearOutsideRetention(
                    cutoffMillis = nowMillis - currentDays.toLong() * CONTENT_RETENTION_DAY_MILLIS,
                    nowMillis = nowMillis,
                )
            }
        }

        private companion object {
            val EXTERNAL_AUTOMATION_ENABLED = booleanPreferencesKey("external_automation_enabled")
            val EXTERNAL_AUTOMATION_TOKEN = stringPreferencesKey("external_automation_token")
            val FILTERING_ENABLED = booleanPreferencesKey("filtering_enabled")
            val SEMANTIC_CLASSIFIER_ENABLED = booleanPreferencesKey("semantic_classifier_enabled")
            val LLM_ANALYSIS_ENABLED = booleanPreferencesKey("llm_analysis_enabled")
            val LLM_AUTO_ACTIONS_ENABLED = booleanPreferencesKey("llm_auto_actions_enabled")
            val SEMANTIC_ANALYSIS_SCOPE = stringPreferencesKey("semantic_analysis_scope")
            val EVENT_RETENTION_DAYS = intPreferencesKey("event_retention_days")
            val DAILY_INSIGHT_RETENTION_DAYS = intPreferencesKey("daily_insight_retention_days")
            val DYNAMIC_COLOR_ENABLED = booleanPreferencesKey("dynamic_color_enabled")
            val NOTIFICATION_CONTENT_STORAGE_ENABLED =
                booleanPreferencesKey("notification_content_storage_enabled")
            val NOTIFICATION_CONTENT_STORAGE_INITIALIZED =
                booleanPreferencesKey("notification_content_storage_initialized")
            val NOTIFICATION_CONTENT_RETENTION_DAYS =
                intPreferencesKey("notification_content_retention_days")
            val CONTENT_EXCLUDED_PACKAGES = stringSetPreferencesKey("content_excluded_packages")
            val RETENTION_RANGE = 1..3_650
            val CONTENT_RETENTION_RANGE =
                RetentionDefaults.MIN_ENCRYPTED_CONTENT_DAYS..RetentionDefaults.MAX_ENCRYPTED_CONTENT_DAYS
            const val AUTOMATION_TOKEN_BYTES = 32
            const val AUTOMATION_TOKEN_CHARS = 43
            const val MAX_EXCLUDED_PACKAGES = 200
            const val MAX_PACKAGE_NAME_CHARS = 255
            const val CONTENT_RETENTION_DAY_MILLIS = 24L * 60 * 60 * 1_000

            fun newAutomationToken(): String {
                val bytes = ByteArray(AUTOMATION_TOKEN_BYTES)
                SecureRandom().nextBytes(bytes)
                return try {
                    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                } finally {
                    bytes.fill(0)
                }
            }

            fun String.isValidAutomationToken(): Boolean =
                length == AUTOMATION_TOKEN_CHARS &&
                    all { character ->
                        character in 'A'..'Z' ||
                            character in 'a'..'z' ||
                            character in '0'..'9' ||
                            character == '-' ||
                            character == '_'
                    }
        }
    }

private inline fun MutableList<Throwable>.captureResetFailure(cleanup: () -> Unit) {
    runCatching(cleanup).exceptionOrNull()?.let(::add)
}

private fun List<Throwable>.throwFirstResetFailure() {
    val first = firstOrNull() ?: return
    drop(1).forEach(first::addSuppressed)
    throw first
}
