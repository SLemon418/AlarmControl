package com.alarmcontrol.automation

import java.util.ArrayDeque
import javax.inject.Inject

/** Small process-local brute-force/broadcast-storm guard for the exported receiver. */
class AutomationRateLimiter
    @Inject
    constructor() {
        private val accepted = ArrayDeque<Long>()
        private val rejected = ArrayDeque<Long>()

        @Synchronized
        fun tryAcquire(nowMillis: Long): Boolean = tryAcquire(accepted, nowMillis)

        /**
         * Bounds audit writes for disabled, unauthorized, malformed, and throttled requests without
         * consuming the independent allowance for authenticated operations.
         */
        @Synchronized
        fun tryAcquireRejected(nowMillis: Long): Boolean = tryAcquire(rejected, nowMillis)

        private fun tryAcquire(
            requests: ArrayDeque<Long>,
            nowMillis: Long,
        ): Boolean {
            if (requests.peekLast()?.let { nowMillis < it } == true) {
                // Wall-clock corrections must not lock valid local automation out for an arbitrary time.
                requests.clear()
            }
            while (requests.isNotEmpty() && nowMillis - requests.first() >= WINDOW_MILLIS) {
                requests.removeFirst()
            }
            if (requests.size >= MAX_REQUESTS) return false
            requests.addLast(nowMillis)
            return true
        }

        private companion object {
            const val MAX_REQUESTS = 12
            const val WINDOW_MILLIS = 60_000L
        }
    }
