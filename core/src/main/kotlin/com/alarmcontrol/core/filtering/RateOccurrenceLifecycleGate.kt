package com.alarmcontrol.core.filtering

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
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
        private val resetMarker =
            AtomicReference(
                RateOccurrenceResetMarker(
                    generation = 0,
                    resetAtMillis = Long.MIN_VALUE,
                ),
            )

        suspend fun <T> withOperation(operation: suspend () -> T): T =
            mutex.withLock {
                operation()
            }

        /** Coherent marker read by the listener actor before it exposes or commits rate counts. */
        val currentResetMarker: RateOccurrenceResetMarker
            get() = resetMarker.get()

        /** Publishes a committed user clear so process-local tracker state cannot outlive Room state. */
        fun markStateCleared(resetAtMillis: Long): RateOccurrenceResetMarker {
            while (true) {
                val current = resetMarker.get()
                val updated =
                    RateOccurrenceResetMarker(
                        generation = current.generation + 1,
                        resetAtMillis = resetAtMillis,
                    )
                if (resetMarker.compareAndSet(current, updated)) return updated
            }
        }
    }

/** One committed rate-history reset and the wall-clock baseline for the new empty history. */
data class RateOccurrenceResetMarker(
    val generation: Long,
    val resetAtMillis: Long,
)
