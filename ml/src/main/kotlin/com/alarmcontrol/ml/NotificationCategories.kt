package com.alarmcontrol.ml

/**
 * The categories the on-device classifier can assign, sourced from the bundled `labels.txt` (§5) so
 * callers (e.g. the recategorize UI) never hardcode a list that could drift from the model. Empty
 * when no model/labels are bundled.
 */
data class NotificationCategories(
    val labels: List<String>,
)
