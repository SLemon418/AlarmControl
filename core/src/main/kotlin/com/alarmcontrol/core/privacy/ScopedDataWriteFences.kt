package com.alarmcontrol.core.privacy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Commit-aware generation boundary for one selective-clear domain.
 *
 * Global acquisition order is whole-data reset, notification history, feedback, daily insights,
 * then lower-level content/filtering/rate/Room locks. Selective clears advance only their own fence,
 * so unrelated writes remain valid.
 */
abstract class ScopedDataWriteFence protected constructor(
    private val label: String,
) {
    @JvmInline
    value class Epoch
        internal constructor(
            internal val value: Long,
        )

    private val epoch = AtomicLong()
    private val writeMutex = Mutex()

    fun captureEpoch(): Epoch = Epoch(epoch.get())

    suspend fun <T : Any> writeIfCurrent(
        capturedEpoch: Epoch,
        write: suspend () -> T,
    ): T? =
        writeMutex.withLock {
            if (capturedEpoch.value == epoch.get()) write() else null
        }

    suspend fun <T> clearAndAdvanceOnCommit(clear: suspend (onCommitted: () -> Unit) -> T): T =
        writeMutex.withLock {
            var advanced = false
            val result =
                clear {
                    check(!advanced) { "$label clear commit was reported more than once" }
                    epoch.incrementAndGet()
                    advanced = true
                }
            check(advanced) { "$label clear returned before reporting its commit" }
            result
        }
}

@Singleton
class FeedbackWriteFence
    @Inject
    constructor() : ScopedDataWriteFence("Feedback")

@Singleton
class DailyInsightWriteFence
    @Inject
    constructor() : ScopedDataWriteFence("Daily insight")
