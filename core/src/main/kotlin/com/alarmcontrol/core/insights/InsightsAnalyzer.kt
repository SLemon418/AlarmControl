package com.alarmcontrol.core.insights

/**
 * Pure, deterministic analysis turning per-package counts into an [InsightsReport] (CLAUDE.md §5/§9).
 * No Android, no I/O — unit-tested in isolation. The repository/worker supplies the SQL-aggregated
 * counts; this only ranks and flags them.
 */
object InsightsAnalyzer {
    /**
     * @param recentCounts muted counts per package in the recent window.
     * @param baselineCounts muted counts per package in the immediately preceding window.
     * @param topN how many "most muted" apps to surface.
     * @param anomalyMinEvents a package needs at least this many recent events to be an anomaly.
     * @param anomalySpikeFactor recent must be at least this multiple of the baseline to be an anomaly.
     */
    fun analyze(
        recentCounts: Map<String, Int>,
        baselineCounts: Map<String, Int>,
        windowDays: Int,
        topN: Int,
        anomalyMinEvents: Int,
        anomalySpikeFactor: Int,
    ): InsightsReport {
        require(windowDays > 0) { "Insight window must be positive" }
        require(topN >= 0) { "Top app count must not be negative" }
        require(anomalyMinEvents >= 0) { "Anomaly minimum must not be negative" }
        require(anomalySpikeFactor >= 1) { "Anomaly factor must be positive" }

        // Stable ordering (count desc, then package asc) so results are deterministic in tests.
        val ranked =
            recentCounts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })

        val topMutedApps = ranked.take(topN).map { AppMuteCount(it.key, it.value) }

        val anomalies =
            ranked
                .filter { (pkg, count) ->
                    val baseline = baselineCounts[pkg] ?: 0
                    count >= anomalyMinEvents &&
                        count.toLong() >= anomalySpikeFactor.toLong() * maxOf(baseline, 1)
                }.map { AppMuteCount(it.key, it.value) }

        return InsightsReport(
            windowDays = windowDays,
            totalEvents =
                recentCounts.values
                    .fold(0L) { total, count -> total + count }
                    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                    .toInt(),
            topMutedApps = topMutedApps,
            anomalies = anomalies,
        )
    }
}
