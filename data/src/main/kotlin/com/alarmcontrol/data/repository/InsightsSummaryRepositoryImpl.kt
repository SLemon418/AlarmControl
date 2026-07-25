package com.alarmcontrol.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.alarmcontrol.core.insights.InsightsSummary
import com.alarmcontrol.core.insights.InsightsSummaryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

/**
 * DataStore (Preferences)-backed [InsightsSummaryRepository]. The summary is a few primitives, so it
 * fits Preferences directly — no serialization dependency. Reads degrade to defaults on I/O error.
 */
class InsightsSummaryRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : InsightsSummaryRepository {
        override val summary: Flow<InsightsSummary?> =
            dataStore.data
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .map { prefs ->
                    val generatedAt = prefs[GENERATED_AT] ?: return@map null
                    InsightsSummary(
                        generatedAtMillis = generatedAt,
                        mostMutedPackage = prefs[MOST_MUTED_PACKAGE],
                        mostMutedCount = prefs[MOST_MUTED_COUNT] ?: 0,
                        anomalyCount = prefs[ANOMALY_COUNT] ?: 0,
                    )
                }

        override suspend fun save(summary: InsightsSummary) {
            dataStore.edit { prefs ->
                prefs[GENERATED_AT] = summary.generatedAtMillis
                summary.mostMutedPackage
                    ?.let { prefs[MOST_MUTED_PACKAGE] = it }
                    ?: prefs.remove(MOST_MUTED_PACKAGE)
                prefs[MOST_MUTED_COUNT] = summary.mostMutedCount
                prefs[ANOMALY_COUNT] = summary.anomalyCount
            }
        }

        private companion object {
            val GENERATED_AT = longPreferencesKey("insights_generated_at")
            val MOST_MUTED_PACKAGE = stringPreferencesKey("insights_most_muted_package")
            val MOST_MUTED_COUNT = intPreferencesKey("insights_most_muted_count")
            val ANOMALY_COUNT = intPreferencesKey("insights_anomaly_count")
        }
    }
