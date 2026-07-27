package com.alarmcontrol.core.insights

/** Local decision counts split by action; all values exclude statistics-excluded events. */
data class ActionBreakdown(
    val cancelled: Int = 0,
    val snoozed: Int = 0,
    val loggedOnly: Int = 0,
    val kept: Int = 0,
) {
    val total: Int
        get() = saturatedCountSum(cancelled, snoozed, loggedOnly, kept)

    val silenced: Int
        get() = saturatedCountSum(cancelled, snoozed)
}

private fun saturatedCountSum(vararg values: Int): Int =
    values
        .fold(0L) { sum, value -> sum + value }
        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        .toInt()
