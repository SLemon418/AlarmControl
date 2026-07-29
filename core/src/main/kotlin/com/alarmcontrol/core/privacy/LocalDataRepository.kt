package com.alarmcontrol.core.privacy

import com.alarmcontrol.core.settings.MaintenanceSettingsSnapshot

/** Counts of locally deleted records; no notification content is represented. */
data class ClearedDataCounts(
    val rules: Int = 0,
    val profiles: Int = 0,
    val events: Int = 0,
    val feedback: Int = 0,
    val insightDays: Int = 0,
    val encryptedContents: Int = 0,
)

/** Local-only privacy controls implemented transactionally by `:data`. */
interface LocalDataRepository {
    suspend fun clearActivityHistory(): ClearedDataCounts

    suspend fun clearFeedback(): ClearedDataCounts

    suspend fun clearDailyInsights(): ClearedDataCounts

    /** Deletes every encrypted title/body payload and its non-exportable local key. */
    suspend fun clearStoredNotificationContent(): ClearedDataCounts

    /** Deletes retained title/body payloads for one newly excluded package. */
    suspend fun clearStoredNotificationContentForPackage(packageName: String): ClearedDataCounts

    /**
     * Re-reads the current content-storage policy while holding the content access boundary, then
     * removes only payloads that policy currently forbids.
     */
    suspend fun reconcileStoredNotificationContentPolicy(): ClearedDataCounts

    /** Applies [policy] while the caller holds the maintenance-policy boundary. */
    suspend fun reconcileStoredNotificationContentPolicy(policy: MaintenanceSettingsSnapshot): ClearedDataCounts

    suspend fun clearAllDatabaseData(): ClearedDataCounts
}
