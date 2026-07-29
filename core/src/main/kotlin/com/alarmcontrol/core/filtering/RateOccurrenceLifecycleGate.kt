package com.alarmcontrol.core.filtering

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local boundary for rate-occurrence state and its listener-key HMAC key.
 *
 * Notification handling must keep hashing, durable mutation, and in-memory tracker synchronization
 * inside one operation. Full-data reset uses the same boundary while clearing Room state and
 * deleting the HMAC key, so an occurrence derived with an old key cannot commit after the reset.
 */
@Singleton
class RateOccurrenceLifecycleGate
    @Inject
    constructor() {
        private val mutex = Mutex()

        suspend fun <T> withOperation(operation: suspend () -> T): T =
            mutex.withLock {
                operation()
            }
    }
