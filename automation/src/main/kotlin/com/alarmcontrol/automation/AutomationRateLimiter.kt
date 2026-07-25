package com.alarmcontrol.automation

import java.util.ArrayDeque
import javax.inject.Inject

/** Small process-local brute-force/broadcast-storm guard for the exported receiver. */
class AutomationRateLimiter
    @Inject
    constructor() {
        private val accepted = ArrayDeque<Long>()

        @Synchronized
        fun tryAcquire(nowMillis: Long): Boolean {
            while (accepted.isNotEmpty() && nowMillis - accepted.first() >= WINDOW_MILLIS) {
                accepted.removeFirst()
            }
            if (accepted.size >= MAX_REQUESTS) return false
            accepted.addLast(nowMillis)
            return true
        }

        private companion object {
            const val MAX_REQUESTS = 12
            const val WINDOW_MILLIS = 60_000L
        }
    }
