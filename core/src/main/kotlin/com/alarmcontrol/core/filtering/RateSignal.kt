package com.alarmcontrol.core.filtering

const val MIN_RATE_WINDOW_MILLIS = 60_000L
const val MAX_RATE_WINDOW_MILLIS = 24L * 60 * 60 * 1_000
const val MIN_RATE_THRESHOLD = 2
const val MAX_RATE_THRESHOLD = 1_000

/** Scope used by a stateful notification-frequency condition. */
enum class RateScope {
    PACKAGE,
    CHANNEL,
}

/** A frequency signal requested by a compiled rule set and supplied by the service pipeline. */
data class RateSignal(
    val scope: RateScope,
    val windowMillis: Long,
)
