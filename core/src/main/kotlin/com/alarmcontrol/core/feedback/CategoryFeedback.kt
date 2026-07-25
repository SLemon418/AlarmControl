package com.alarmcontrol.core.feedback

/**
 * One explicit user correction of an ML categorization: "notifications from [packageName] that the
 * model called [predictedLabel] should be [correctedLabel]" (CLAUDE.md §5).
 *
 * Privacy (HARD RULE §1/§3): like the decision log, this holds **no notification content** — only the
 * package, the labels involved, and a timestamp. It is the on-device learning signal and never leaves
 * the device.
 */
data class CategoryFeedback(
    val packageName: String,
    /** Activity-log row being corrected, when the feedback came from the feed. */
    val notificationEventId: String? = null,
    /** What the classifier predicted, when known; `null` if the user labelled an unclassified item. */
    val predictedLabel: String?,
    val correctedLabel: String,
    val recordedAtMillis: Long,
)
