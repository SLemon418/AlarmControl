package com.alarmcontrol.core.insights

/**
 * The result of a periodic insights run (CLAUDE.md §5) — pure aggregations over the local decision
 * log, no ML and no network. Computed in the background and surfaced locally.
 *
 * @property windowDays the recent window analysed.
 * @property totalEvents muted events (any action except Keep) in the window.
 * @property topMutedApps the most-muted packages, highest first.
 * @property anomalies packages whose recent volume spiked vs. the prior window.
 * @property purgedEvents how many expired log rows the run deleted (retention housekeeping).
 */
data class InsightsReport(
    val windowDays: Int,
    val totalEvents: Int,
    val topMutedApps: List<AppMuteCount>,
    val anomalies: List<AppMuteCount>,
    val purgedEvents: Int = 0,
)

/** A package and how many of its notifications were muted in a window. */
data class AppMuteCount(
    val packageName: String,
    val count: Int,
)
