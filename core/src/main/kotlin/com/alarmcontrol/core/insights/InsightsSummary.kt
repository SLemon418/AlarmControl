package com.alarmcontrol.core.insights

import kotlinx.coroutines.flow.Flow

/**
 * The compact, durable headline from the latest insights run, kept locally so the UI can show it
 * without recomputing (CLAUDE.md §5). Privacy: package name + counts only, never notification
 * content (§3).
 */
data class InsightsSummary(
    val generatedAtMillis: Long,
    val mostMutedPackage: String?,
    val mostMutedCount: Int,
    val anomalyCount: Int,
)

/**
 * Stores the latest [InsightsSummary]. Interface in `:core`; the DataStore-backed implementation
 * lives in `:data`. Nothing leaves the device (§1/§3).
 */
interface InsightsSummaryRepository {
    /** The latest summary, or `null` until the first run has completed. */
    val summary: Flow<InsightsSummary?>

    /** Persists the latest [summary]. */
    suspend fun save(summary: InsightsSummary)
}
