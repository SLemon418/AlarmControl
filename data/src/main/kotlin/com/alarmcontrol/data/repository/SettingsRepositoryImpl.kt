package com.alarmcontrol.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.alarmcontrol.core.settings.MaintenanceSettingsSnapshot
import com.alarmcontrol.core.settings.RetentionDefaults
import com.alarmcontrol.core.settings.SemanticAnalysisScope
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.core.settings.SettingsSnapshot
import com.alarmcontrol.data.security.MaintenancePolicyAccessGuard
import com.alarmcontrol.data.security.NotificationContentAccessGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject

/**
 * DataStore-backed settings. UI flows degrade to privacy-safe defaults on I/O errors; authoritative
 * backup and maintenance snapshots propagate failures so callers cannot persist or delete from
 * substituted values.
 */
class SettingsRepositoryImpl
    @Inject
    internal constructor(
        private val dataStore: DataStore<Preferences>,
        private val contentAccessGuard: NotificationContentAccessGuard,
        private val maintenancePolicyAccessGuard: MaintenancePolicyAccessGuard =
            MaintenancePolicyAccessGuard(),
    ) : SettingsRepository {
        override val filteringEnabled: Flow<Boolean> =
            dataStore.data
                .map { prefs -> prefs[FILTERING_ENABLED] ?: true }
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
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .map { prefs -> prefs[NOTIFICATION_CONTENT_STORAGE_ENABLED] ?: false }

        override val contentExcludedPackages: Flow<Set<String>> =
            dataStore.data
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .map { prefs -> prefs[CONTENT_EXCLUDED_PACKAGES].orEmpty() }

        override suspend fun setExternalAutomationEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                prefs[EXTERNAL_AUTOMATION_ENABLED] = enabled
                if (enabled && prefs[EXTERNAL_AUTOMATION_TOKEN]?.isValidAutomationToken() != true) {
                    prefs[EXTERNAL_AUTOMATION_TOKEN] = newAutomationToken()
                }
            }
        }

        override suspend fun ensureExternalAutomationToken(): String {
            var token = ""
            dataStore.edit { prefs ->
                token =
                    prefs[EXTERNAL_AUTOMATION_TOKEN]
                        ?.takeIf { it.isValidAutomationToken() }
                        ?: newAutomationToken()
                prefs[EXTERNAL_AUTOMATION_TOKEN] = token
            }
            return token
        }

        override suspend fun rotateExternalAutomationToken(): String {
            val token = newAutomationToken()
            dataStore.edit { prefs -> prefs[EXTERNAL_AUTOMATION_TOKEN] = token }
            return token
        }

        override suspend fun setFilteringEnabled(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[FILTERING_ENABLED] = enabled }
        }

        override suspend fun setLlmAnalysisEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                prefs[LLM_ANALYSIS_ENABLED] = enabled
                prefs.remove(LLM_AUTO_ACTIONS_ENABLED)
            }
        }

        override suspend fun setLlmAutoActionsEnabled(enabled: Boolean) {
            dataStore.edit { prefs -> prefs.remove(LLM_AUTO_ACTIONS_ENABLED) }
        }

        override suspend fun setSemanticAnalysisScope(scope: SemanticAnalysisScope) {
            dataStore.edit { prefs -> prefs[SEMANTIC_ANALYSIS_SCOPE] = scope.name }
        }

        override suspend fun setEventRetentionDays(days: Int) {
            require(days in RETENTION_RANGE) { "Event retention is out of range" }
            maintenancePolicyAccessGuard.withLock {
                dataStore.edit { prefs -> prefs[EVENT_RETENTION_DAYS] = days }
            }
        }

        override suspend fun setDailyInsightRetentionDays(days: Int) {
            require(days in RETENTION_RANGE) { "Insight retention is out of range" }
            maintenancePolicyAccessGuard.withLock {
                dataStore.edit { prefs -> prefs[DAILY_INSIGHT_RETENTION_DAYS] = days }
            }
        }

        override suspend fun setDynamicColorEnabled(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[DYNAMIC_COLOR_ENABLED] = enabled }
        }

        override suspend fun setNotificationContentStorageEnabled(enabled: Boolean) {
            maintenancePolicyAccessGuard.withLock {
                contentAccessGuard.withLock {
                    dataStore.edit { prefs -> prefs[NOTIFICATION_CONTENT_STORAGE_ENABLED] = enabled }
                }
            }
        }

        override suspend fun setContentExcludedPackages(packageNames: Set<String>) {
            require(packageNames.size <= MAX_EXCLUDED_PACKAGES) { "Too many excluded packages" }
            require(packageNames.all { it.isNotBlank() && it.length <= MAX_PACKAGE_NAME_CHARS }) {
                "Invalid excluded package"
            }
            maintenancePolicyAccessGuard.withLock {
                contentAccessGuard.withLock {
                    dataStore.edit { prefs -> prefs[CONTENT_EXCLUDED_PACKAGES] = packageNames }
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
            maintenancePolicyAccessGuard.withLock {
                contentAccessGuard.withLock {
                    dataStore.edit { prefs ->
                        val current = prefs[CONTENT_EXCLUDED_PACKAGES].orEmpty()
                        val updated = if (excluded) current + packageName else current - packageName
                        require(updated.size <= MAX_EXCLUDED_PACKAGES) {
                            "Too many excluded packages"
                        }
                        prefs[CONTENT_EXCLUDED_PACKAGES] = updated
                    }
                }
            }
        }

        override suspend fun snapshot(): SettingsSnapshot {
            val prefs = dataStore.data.first()
            return SettingsSnapshot(
                filteringEnabled = prefs[FILTERING_ENABLED] ?: true,
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
            )
        }

        override suspend fun maintenanceSnapshot(): MaintenanceSettingsSnapshot {
            val prefs = dataStore.data.first()
            val eventRetentionDays = prefs[EVENT_RETENTION_DAYS] ?: RetentionDefaults.EVENT_DAYS
            val dailyInsightRetentionDays =
                prefs[DAILY_INSIGHT_RETENTION_DAYS] ?: RetentionDefaults.DAILY_INSIGHT_DAYS
            check(eventRetentionDays in RETENTION_RANGE) { "Stored event retention is invalid" }
            check(dailyInsightRetentionDays in RETENTION_RANGE) {
                "Stored insight retention is invalid"
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
                    prefs[NOTIFICATION_CONTENT_STORAGE_ENABLED] ?: false,
                contentExcludedPackages = excludedPackages,
            )
        }

        override suspend fun restore(snapshot: SettingsSnapshot) {
            require(snapshot.eventRetentionDays in RETENTION_RANGE) { "Event retention is out of range" }
            require(snapshot.dailyInsightRetentionDays in RETENTION_RANGE) { "Insight retention is out of range" }
            maintenancePolicyAccessGuard.withLock {
                dataStore.edit { prefs ->
                    prefs[FILTERING_ENABLED] = snapshot.filteringEnabled
                    prefs[EXTERNAL_AUTOMATION_ENABLED] = snapshot.externalAutomationEnabled
                    if (
                        snapshot.externalAutomationEnabled &&
                        prefs[EXTERNAL_AUTOMATION_TOKEN]?.isValidAutomationToken() != true
                    ) {
                        // The per-install secret is deliberately absent from portable backups. A
                        // fresh restore still needs a valid local token or the UI would claim
                        // automation is enabled while every authenticated broadcast is rejected.
                        prefs[EXTERNAL_AUTOMATION_TOKEN] = newAutomationToken()
                    }
                    prefs[LLM_ANALYSIS_ENABLED] = snapshot.llmAnalysisEnabled
                    prefs.remove(LLM_AUTO_ACTIONS_ENABLED)
                    prefs[SEMANTIC_ANALYSIS_SCOPE] = snapshot.semanticAnalysisScope.name
                    prefs[EVENT_RETENTION_DAYS] = snapshot.eventRetentionDays
                    prefs[DAILY_INSIGHT_RETENTION_DAYS] = snapshot.dailyInsightRetentionDays
                }
            }
        }

        override suspend fun reset() {
            maintenancePolicyAccessGuard.withLock {
                contentAccessGuard.withLock {
                    dataStore.edit { preferences ->
                        preferences.clear()
                        // A destructive local-data reset must never reactivate surviving rules when
                        // a separate database deletion failed. Keep the master gate fail-safe and
                        // require the user to resume filtering explicitly after a reset.
                        preferences[FILTERING_ENABLED] = false
                    }
                }
            }
        }

        private companion object {
            val EXTERNAL_AUTOMATION_ENABLED = booleanPreferencesKey("external_automation_enabled")
            val EXTERNAL_AUTOMATION_TOKEN = stringPreferencesKey("external_automation_token")
            val FILTERING_ENABLED = booleanPreferencesKey("filtering_enabled")
            val LLM_ANALYSIS_ENABLED = booleanPreferencesKey("llm_analysis_enabled")
            val LLM_AUTO_ACTIONS_ENABLED = booleanPreferencesKey("llm_auto_actions_enabled")
            val SEMANTIC_ANALYSIS_SCOPE = stringPreferencesKey("semantic_analysis_scope")
            val EVENT_RETENTION_DAYS = intPreferencesKey("event_retention_days")
            val DAILY_INSIGHT_RETENTION_DAYS = intPreferencesKey("daily_insight_retention_days")
            val DYNAMIC_COLOR_ENABLED = booleanPreferencesKey("dynamic_color_enabled")
            val NOTIFICATION_CONTENT_STORAGE_ENABLED =
                booleanPreferencesKey("notification_content_storage_enabled")
            val CONTENT_EXCLUDED_PACKAGES = stringSetPreferencesKey("content_excluded_packages")
            val RETENTION_RANGE = 1..3_650
            const val AUTOMATION_TOKEN_BYTES = 32
            const val AUTOMATION_TOKEN_CHARS = 43
            const val MAX_EXCLUDED_PACKAGES = 200
            const val MAX_PACKAGE_NAME_CHARS = 255

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
