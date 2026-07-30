package com.alarmcontrol.core.privacy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local generation boundary between whole-database reset and independently started writers.
 *
 * A repository captures an [Epoch] at public-operation entry and performs its Room write through
 * [writeIfCurrent]. A successful reset advances the generation under the same mutex, so an older
 * operation either commits before the reset (and is deleted by it) or is rejected afterward.
 *
 * When multiple boundaries are needed, lock order is settings mutation, maintenance policy,
 * external-automation authorization, this reset fence, notification history, feedback, daily
 * insights, notification content, filtering actions, rate occurrences, then Room.
 */
@Singleton
class LocalDataResetWriteFence
    @Inject
    constructor() {
        @JvmInline
        value class Epoch
            internal constructor(
                internal val value: Long,
            )

        private val epoch = AtomicLong()
        private val writeMutex = Mutex()

        fun captureEpoch(): Epoch = Epoch(epoch.get())

        /** Non-blocking check for callers already serialized against reset by the maintenance lock. */
        fun isCurrent(capturedEpoch: Epoch): Boolean = capturedEpoch.value == epoch.get()

        suspend fun <T : Any> writeIfCurrent(
            capturedEpoch: Epoch,
            write: suspend () -> T,
        ): T? =
            writeMutex.withLock {
                if (capturedEpoch.value == epoch.get()) write() else null
            }

        suspend fun <T> resetAndAdvanceOnCommit(reset: suspend (onCommitted: () -> Unit) -> T): T =
            writeMutex.withLock {
                var advanced = false
                val result =
                    reset {
                        check(!advanced) { "Local data reset commit was reported more than once" }
                        epoch.incrementAndGet()
                        advanced = true
                    }
                check(advanced) { "Local data reset returned before reporting its commit" }
                result
            }
    }

/** Signals that a profile mutation was intentionally cancelled by a committed whole-data reset. */
class StaleLocalDataWriteException : IllegalStateException("Local data was reset before this write could commit")
