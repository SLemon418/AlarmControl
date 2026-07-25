package com.alarmcontrol.core.insights

/** Local decision counts split by action; all values exclude statistics-excluded events. */
data class ActionBreakdown(
    val cancelled: Int = 0,
    val snoozed: Int = 0,
    val loggedOnly: Int = 0,
    val kept: Int = 0,
) {
    val total: Int get() = cancelled + snoozed + loggedOnly + kept
    val silenced: Int get() = cancelled + snoozed
}
