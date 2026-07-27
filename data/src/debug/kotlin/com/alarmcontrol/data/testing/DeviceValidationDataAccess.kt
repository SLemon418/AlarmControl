package com.alarmcontrol.data.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.alarmcontrol.data.db.AppDatabase
import javax.inject.Inject

/** Debug-only, content-free cleanup support for connected-device validation. */
class DeviceValidationDataAccess
    @Inject
    internal constructor(
        private val database: AppDatabase,
        private val dataStore: DataStore<Preferences>,
    ) {
        fun latestAutomationAuditId(): Long =
            database.openHelper.readableDatabase
                .query("SELECT COALESCE(MAX(id), 0) FROM automation_audit")
                .use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }

        fun deleteAutomationAuditsAfter(id: Long) {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM automation_audit WHERE id > ?",
                arrayOf(id),
            )
        }

        suspend fun restoreAutomationTokenIfUnchanged(
            originalToken: String,
            validationToken: String,
        ) {
            dataStore.edit { preferences ->
                if (preferences[AUTOMATION_TOKEN] != validationToken) return@edit
                if (originalToken.isEmpty()) {
                    preferences.remove(AUTOMATION_TOKEN)
                } else {
                    preferences[AUTOMATION_TOKEN] = originalToken
                }
            }
        }

        private companion object {
            val AUTOMATION_TOKEN = stringPreferencesKey("external_automation_token")
        }
    }
