package com.alarmcontrol.core.filtering

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local deletion boundary for committed notification-history work.
 *
 * Listener and linked-feedback work captures an [Epoch] at operation entry. A successful user
 * deletion advances the epoch while holding the same write mutex used by [writeIfCurrent], so work
 * started before that deletion can neither finish after it nor reinsert deleted history metadata.
 */
@Singleton
class NotificationHistoryWriteFence
    @Inject
    constructor() {
        @JvmInline
        value class Epoch
            internal constructor(
                internal val value: Long,
            )

        private val epoch = AtomicLong()
        private val writeMutex = Mutex()

        /** Captures the current deletion generation without suspending the filtering commit path. */
        fun captureEpoch(): Epoch = Epoch(epoch.get())

        /**
         * Runs [write] only if [capturedEpoch] still represents the current history generation.
         * Returns `null` when a successful deletion made the committed work stale.
         */
        suspend fun <T : Any> writeIfCurrent(
            capturedEpoch: Epoch,
            write: suspend () -> T,
        ): T? =
            writeMutex.withLock {
                if (capturedEpoch.value == epoch.get()) write() else null
            }

        /**
         * Serializes a transactional deletion against history writes. [delete] must invoke its
         * callback only after the database commit succeeds. Once invoked, the generation remains
         * advanced even if later key cleanup or result delivery fails.
         */
        suspend fun <T> deleteAndAdvanceOnCommit(delete: suspend (onCommitted: () -> Unit) -> T): T =
            writeMutex.withLock {
                var advanced = false
                val result =
                    delete {
                        check(!advanced) { "History deletion commit was reported more than once" }
                        epoch.incrementAndGet()
                        advanced = true
                    }
                check(advanced) { "History deletion returned before reporting its commit" }
                result
            }
    }
