package com.alarmcontrol.data.security

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes retention/content-policy writes with destructive housekeeping. This keeps a completed
 * settings change from being followed by deletion authorized by an older policy snapshot. Acquire
 * the process-wide settings-mutation fence before this guard when both are required.
 */
@Singleton
internal class MaintenancePolicyAccessGuard
    @Inject
    constructor() {
        private val mutex = Mutex()

        suspend fun <T> withLock(block: suspend () -> T): T {
            mutex.lock()
            return try {
                block()
            } finally {
                mutex.unlock()
            }
        }
    }
