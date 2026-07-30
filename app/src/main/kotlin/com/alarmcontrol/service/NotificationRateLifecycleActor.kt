package com.alarmcontrol.service

import com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.PersistedRateOccurrence
import com.alarmcontrol.core.filtering.RateListenerKeyHashResult
import com.alarmcontrol.core.filtering.RateListenerKeyHasher
import com.alarmcontrol.core.filtering.RateOccurrenceId
import com.alarmcontrol.core.filtering.RateOccurrenceIncompleteReason
import com.alarmcontrol.core.filtering.RateOccurrenceLifecycleGate
import com.alarmcontrol.core.filtering.RateOccurrencePersistenceResult
import com.alarmcontrol.core.filtering.RateOccurrenceRepository
import com.alarmcontrol.core.filtering.RateOccurrenceSeed
import com.alarmcontrol.core.filtering.RateSignal
import com.alarmcontrol.notifications.NotificationRateTracker
import com.alarmcontrol.notifications.RateTrackerRecordResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Serializes listener-key hashing, occurrence persistence, and in-memory rate state.
 *
 * Android listener keys exist only in bounded in-memory commands. They are never logged or passed
 * to persistence; only [RateListenerKeyHasher] output crosses the repository boundary.
 */
@Suppress(
    "LongParameterList",
    "ReturnCount",
    "TooManyFunctions",
) // One serialized fail-open state machine owns these collaborators and explicit stale exits.
internal class NotificationRateLifecycleActor(
    scope: CoroutineScope,
    private val repository: RateOccurrenceRepository,
    private val hasher: RateListenerKeyHasher,
    private val lifecycleGate: RateOccurrenceLifecycleGate,
    private val tracker: NotificationRateTracker,
    private val clock: Clock,
    private val commandCapacity: Int = DEFAULT_COMMAND_CAPACITY,
    private val newOccurrenceId: () -> RateOccurrenceId = {
        RateOccurrenceId(UUID.randomUUID().toString())
    },
    private val onFailure: () -> Unit = {},
) {
    init {
        require(commandCapacity > 0)
    }

    // One command can already be handed directly to, or running in, the worker. The buffer holds
    // only the remainder so the configured bound covers all in-flight commands rather than 65.
    private val commands = Channel<Command>(capacity = commandCapacity - 1)
    private val lifecycleGeneration = AtomicLong(0)
    private val pendingGap = AtomicReference<PendingGap?>(null)
    private val rateStateLock = Any()
    private var trackerAvailable = false
    private var nextSeedRetryAtMillis: Long? = null
    private var appliedResetGeneration = lifecycleGate.currentResetMarker.generation

    private val worker =
        scope.launch {
            for (command in commands) {
                processSafely(command)
            }
        }

    val currentLifecycleGeneration: Long
        get() =
            synchronized(rateStateLock) {
                applyExternalResetIfNeededLocked()
                lifecycleGeneration.get()
            }

    /** Marks a new connected generation unavailable before any callback can observe stale counts. */
    fun connected(anchorMillis: Long = clock.millis()): Long {
        val generation = lifecycleGeneration.incrementAndGet()
        markUnavailableAndRetainGap(anchorMillis)
        enqueueControl(Command.Connected(generation, anchorMillis))
        return generation
    }

    /** Invalidates queued work immediately and records a durable completeness gap best-effort. */
    fun disconnected(anchorMillis: Long = clock.millis()) {
        lifecycleGeneration.incrementAndGet()
        markUnavailableAndRetainGap(anchorMillis)
        enqueueControl(Command.FlushGap(anchorMillis))
    }

    /**
     * Invalidates current rate signals and requests a serialized Room reseed.
     *
     * This is used before publishing a new compiled rule set, so newly required frequency signals
     * cannot act on state captured before the rule refresh.
     */
    fun requestReseed(anchorMillis: Long = clock.millis()) {
        markTrackerUnavailable()
        enqueueControl(
            Command.Reseed(
                generation = lifecycleGeneration.get(),
                anchorMillis = anchorMillis,
            ),
        )
    }

    /**
     * Offers one posted notification without suspending its listener callback.
     *
     * [outcome] must not inherit the processing job. A newer post may cancel that waiter while this
     * actor still durably records the occurrence for later frequency decisions.
     */
    fun tryPost(
        generation: Long,
        rawListenerKey: String,
        packageName: String,
        channelId: String?,
        postedAtMillis: Long,
        outcome: CompletableDeferred<RatePostOutcome>,
    ): Boolean =
        tryEnqueue(
            Command.Post(
                generation = generation,
                rawListenerKey = rawListenerKey,
                packageName = packageName,
                channelId = channelId,
                postedAtMillis = postedAtMillis,
                outcome = outcome,
            ),
        )

    /** Offers a conditional active-occurrence removal without suspending the listener callback. */
    fun tryRemove(
        generation: Long,
        rawListenerKey: String,
        removedPostTimeMillis: Long,
    ): Boolean =
        tryEnqueue(
            Command.Remove(
                generation = generation,
                rawListenerKey = rawListenerKey,
                removedPostTimeMillis = removedPostTimeMillis,
            ),
        )

    /** Captures requested counts at one short synchronous boundary. */
    fun captureCounts(
        snapshot: NotificationSnapshot,
        requestedSignals: Set<RateSignal>,
    ): RateCountSnapshot =
        synchronized(rateStateLock) {
            applyExternalResetIfNeededLocked()
            RateCountSnapshot(
                counts =
                    tracker.counts(
                        snapshot = snapshot,
                        requestedSignals = requestedSignals,
                        observationNowMillis = clock.millis(),
                    ),
            )
        }

    /**
     * Runs the coordinator token claim only if the active rate inputs are still identical.
     *
     * A null expectation means the decision performs no destructive platform action or its active
     * lane has no frequency condition. Recalculating at the original post time lets a later post
     * proceed without spuriously revoking an unrelated decision, while an out-of-order occurrence,
     * coverage loss, or changed reseed still rejects stale input. The lock is deliberately held
     * through the platform action; lock ordering is filtering gate, this lock, then the coordinator
     * token.
     */
    fun commitIfRateCountsCurrent(
        snapshot: NotificationSnapshot,
        expectation: RateCountExpectation?,
        commit: () -> Boolean,
    ): Boolean {
        if (expectation == null) return commit()
        return synchronized(rateStateLock) {
            applyExternalResetIfNeededLocked()
            val currentCounts =
                tracker.counts(
                    snapshot = snapshot,
                    requestedSignals = expectation.requestedSignals,
                    observationNowMillis = clock.millis(),
                )
            if (currentCounts == expectation.expectedCounts) {
                commit()
            } else {
                false
            }
        }
    }

    fun close() {
        lifecycleGeneration.incrementAndGet()
        markTrackerUnavailable()
        commands.close()
        worker.cancel()
    }

    private fun tryEnqueue(command: Command): Boolean {
        if (commands.trySend(command).isSuccess) return true
        handleSynchronousEnqueueFailure(command)
        return false
    }

    private fun enqueueControl(command: Command) {
        if (commands.trySend(command).isFailure) {
            markUnavailableAndRetainGap(failureAnchor(command))
            onFailure()
        }
    }

    private fun handleSynchronousEnqueueFailure(command: Command) {
        markUnavailableAndRetainGap(failureAnchor(command))
        if (command is Command.Post) {
            command.outcome.complete(
                if (command.generation == lifecycleGeneration.get()) {
                    RatePostOutcome.Proceed
                } else {
                    RatePostOutcome.Stale
                },
            )
        }
        onFailure()
    }

    private suspend fun processSafely(command: Command) {
        try {
            lifecycleGate.withOperation {
                applyExternalResetIfNeeded()
                persistPendingGap()
                when (command) {
                    is Command.Connected -> processConnected(command)
                    is Command.Reseed -> processReseed(command)
                    is Command.Post -> processPost(command)
                    is Command.Remove -> processRemove(command)
                    is Command.FlushGap -> Unit
                }
                persistPendingGap()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: LinkageError) {
            handleCommandFailure(command)
        } catch (_: OutOfMemoryError) {
            handleCommandFailure(command)
        } catch (_: Exception) {
            handleCommandFailure(command)
        }
    }

    private suspend fun processConnected(command: Command.Connected) {
        if (!isCurrent(command.generation)) return
        nextSeedRetryAtMillis = null
        attemptSeed(force = true, generation = command.generation)
    }

    private suspend fun processReseed(command: Command.Reseed) {
        if (!isCurrent(command.generation)) return
        attemptSeed(force = false, generation = command.generation)
    }

    private suspend fun processPost(command: Command.Post) {
        if (!isCurrent(command.generation)) {
            command.outcome.complete(RatePostOutcome.Stale)
            return
        }
        retrySeedIfDue(command.generation)
        if (!isCurrent(command.generation)) {
            command.outcome.complete(RatePostOutcome.Stale)
            return
        }

        val hash = hasher.hash(command.rawListenerKey)
        if (!isCurrent(command.generation)) {
            command.outcome.complete(RatePostOutcome.Stale)
            return
        }
        val hashSuccess =
            when (hash) {
                is RateListenerKeyHashResult.Success -> hash
                is RateListenerKeyHashResult.Unavailable -> return proceedAfterFailure(command)
            }
        val candidateOccurrenceId = newOccurrenceId()
        val recorded =
            repository.recordPost(
                listenerKeyDigest = hashSuccess.digest,
                candidateOccurrenceId = candidateOccurrenceId,
                packageName = command.packageName,
                channelId = command.channelId,
                postedAtMillis = command.postedAtMillis,
            )
        if (!isCurrent(command.generation)) {
            command.outcome.complete(RatePostOutcome.Stale)
            return
        }
        val result =
            when (recorded) {
                is RateOccurrencePersistenceResult.Success -> recorded.value
                is RateOccurrencePersistenceResult.Unavailable -> return proceedAfterFailure(command)
            }
        if (!result.accepted) {
            markUnavailableAndRetainGap(failureAnchor(command))
            command.outcome.complete(RatePostOutcome.Stale)
            return
        }

        val nowMillis = clock.millis()
        val incompleteUntilMillis =
            maxOfNullable(
                hashSuccess.recoveryIncompleteUntilMillis,
                result.incompleteUntilMillis,
            )
        if (applyPersistedCoverageBarrier(incompleteUntilMillis, nowMillis)) {
            val tracked =
                recordIfAvailable(
                    PersistedRateOccurrence(
                        occurrenceId = result.activeOccurrence.occurrenceId,
                        packageName = result.activeOccurrence.packageName,
                        channelId = result.activeOccurrence.channelId,
                        postedAtMillis = result.activeOccurrence.lastPostedAtMillis,
                    ),
                )
            if (!tracked) {
                markUnavailableAndRetainGap(failureAnchor(command))
            }
        }
        command.outcome.complete(RatePostOutcome.Proceed)
    }

    private suspend fun processRemove(command: Command.Remove) {
        if (!isCurrent(command.generation)) return
        val hash = hasher.hash(command.rawListenerKey)
        if (!isCurrent(command.generation)) return
        val hashSuccess =
            when (hash) {
                is RateListenerKeyHashResult.Success -> hash
                is RateListenerKeyHashResult.Unavailable ->
                    return markUnavailableAndRetainGap(failureAnchor(command))
            }
        val nowMillis = clock.millis()
        if (
            hashSuccess.recoveryIncompleteUntilMillis
                ?.let { it == Long.MAX_VALUE || it > nowMillis } == true
        ) {
            applyPersistedCoverageBarrier(
                hashSuccess.recoveryIncompleteUntilMillis,
                nowMillis,
            )
            return
        }
        val activeResult = repository.activeOccurrence(hashSuccess.digest)
        if (!isCurrent(command.generation)) return
        val active =
            when (activeResult) {
                is RateOccurrencePersistenceResult.Success -> activeResult.value
                is RateOccurrencePersistenceResult.Unavailable -> {
                    markUnavailableAndRetainGap(failureAnchor(command))
                    return
                }
            }
        if (active == null) return
        if (active.lastPostedAtMillis > command.removedPostTimeMillis) return
        val removed =
            repository.deleteActiveOccurrence(
                listenerKeyDigest = hashSuccess.digest,
                occurrenceId = active.occurrenceId,
                removedPostTimeMillis = command.removedPostTimeMillis,
            )
        if (!isCurrent(command.generation)) return
        if (removed !is RateOccurrencePersistenceResult.Success || !removed.value) {
            markUnavailableAndRetainGap(failureAnchor(command))
        }
    }

    private suspend fun proceedAfterFailure(command: Command.Post) {
        markUnavailableAndRetainGap(failureAnchor(command))
        command.outcome.complete(RatePostOutcome.Proceed)
    }

    private suspend fun retrySeedIfDue(generation: Long) {
        val retryAtMillis = nextSeedRetryAtMillis ?: return
        if (clock.millis() >= retryAtMillis) {
            attemptSeed(force = false, generation = generation)
        }
    }

    private suspend fun attemptSeed(
        force: Boolean,
        generation: Long,
    ) {
        if (!isCurrent(generation)) return
        val nowMillis = clock.millis()
        val retryAtMillis = nextSeedRetryAtMillis
        if (!force && retryAtMillis != null && nowMillis < retryAtMillis) return
        // A seed cannot claim any complete prefix until the latest in-memory gap is durable.
        if (pendingGap.get() != null) return
        val seed =
            repository.loadSeed(
                sinceMillis = subtractSaturated(nowMillis, MAX_RATE_WINDOW_MILLIS),
                nowMillis = nowMillis,
            )
        if (!isCurrent(generation) || pendingGap.get() != null) return
        when (seed) {
            is RateOccurrenceSeed.Available -> {
                if (
                    replaceSeed(
                        occurrences = seed.occurrences,
                        nowMillis = nowMillis,
                        coverageStartMillis = seed.coverageStartMillis,
                    )
                ) {
                    nextSeedRetryAtMillis = null
                } else {
                    markUnavailableAndRetainGap(nowMillis)
                    nextSeedRetryAtMillis = addSaturated(nowMillis, MINIMUM_SEED_RETRY_MILLIS)
                }
            }

            is RateOccurrenceSeed.Incomplete -> {
                markTrackerUnavailable()
                nextSeedRetryAtMillis =
                    if (seed.reason == RateOccurrenceIncompleteReason.FUTURE_OCCURRENCE) {
                        seed.retryAtMillis
                            ?: addSaturated(nowMillis, MINIMUM_SEED_RETRY_MILLIS)
                    } else {
                        maxOf(
                            addSaturated(nowMillis, MINIMUM_SEED_RETRY_MILLIS),
                            seed.retryAtMillis ?: Long.MIN_VALUE,
                        )
                    }
            }

            is RateOccurrenceSeed.Unavailable -> {
                markUnavailableAndRetainGap(nowMillis)
                nextSeedRetryAtMillis = addSaturated(nowMillis, MINIMUM_SEED_RETRY_MILLIS)
            }
        }
    }

    private suspend fun persistPendingGap() {
        val pending = pendingGap.get() ?: return
        when (val result = repository.extendIncompleteWindowFrom(pending.anchorMillis)) {
            is RateOccurrencePersistenceResult.Success -> {
                pendingGap.compareAndSet(pending, null)
                if (result.value == Long.MAX_VALUE) {
                    nextSeedRetryAtMillis = Long.MAX_VALUE
                } else {
                    scheduleSeedRetryAtOrBefore(clock.millis())
                }
            }

            is RateOccurrencePersistenceResult.Unavailable -> Unit
        }
    }

    private suspend fun handleCommandFailure(command: Command) {
        try {
            markUnavailableAndRetainGap(failureAnchor(command))
        } finally {
            if (command is Command.Post) {
                command.outcome.complete(
                    if (isCurrent(command.generation)) {
                        RatePostOutcome.Proceed
                    } else {
                        RatePostOutcome.Stale
                    },
                )
            }
        }
        onFailure()
        try {
            lifecycleGate.withOperation {
                persistPendingGap()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: LinkageError) {
            // The in-memory gap remains pending; a later command retries persistence.
        } catch (_: OutOfMemoryError) {
            // The in-memory gap remains pending; a later command retries persistence.
        } catch (_: Exception) {
            // The pending anchor remains in memory; the next command or connection retries it.
        }
    }

    private fun markUnavailableAndRetainGap(anchorMillis: Long) {
        synchronized(rateStateLock) {
            applyExternalResetIfNeededLocked()
            trackerAvailable = false
            tracker.markUnavailable()
            retainGap(anchorMillis)
        }
    }

    private fun markTrackerUnavailable() {
        synchronized(rateStateLock) {
            applyExternalResetIfNeededLocked()
            trackerAvailable = false
            tracker.markUnavailable()
        }
    }

    private fun applyExternalResetIfNeeded() {
        synchronized(rateStateLock) {
            applyExternalResetIfNeededLocked()
        }
    }

    private fun applyExternalResetIfNeededLocked() {
        val marker = lifecycleGate.currentResetMarker
        if (marker.generation == appliedResetGeneration) return
        lifecycleGeneration.incrementAndGet()
        pendingGap.set(null)
        nextSeedRetryAtMillis = null
        // Windows are inclusive, so history becomes complete only from the first millisecond after
        // the clear. This matches Room's reset marker and keeps negated rate rules fail-open.
        trackerAvailable =
            tracker.seed(
                occurrences = emptyList(),
                nowMillis = marker.resetAtMillis,
                coverageStartMillis = addSaturated(marker.resetAtMillis, 1L),
            )
        appliedResetGeneration = marker.generation
    }

    private fun replaceSeed(
        occurrences: List<PersistedRateOccurrence>,
        nowMillis: Long,
        coverageStartMillis: Long,
    ): Boolean {
        synchronized(rateStateLock) {
            if (pendingGap.get() != null) return false
            val seeded =
                tracker.seed(
                    occurrences = occurrences,
                    nowMillis = nowMillis,
                    coverageStartMillis = coverageStartMillis,
                )
            trackerAvailable = seeded
            return seeded
        }
    }

    private fun applyPersistedCoverageBarrier(
        incompleteUntilMillis: Long?,
        nowMillis: Long,
    ): Boolean {
        if (incompleteUntilMillis == null) return true
        if (incompleteUntilMillis == Long.MAX_VALUE) {
            markTrackerUnavailable()
            nextSeedRetryAtMillis = Long.MAX_VALUE
            return false
        }
        if (incompleteUntilMillis <= nowMillis) return true
        val coverageStartMillis =
            subtractSaturated(incompleteUntilMillis, MAX_RATE_WINDOW_MILLIS)
        val available =
            synchronized(rateStateLock) {
                if (!trackerAvailable) return@synchronized false
                when (tracker.restrictCoverage(coverageStartMillis)) {
                    RateTrackerRecordResult.CHANGED -> true

                    RateTrackerRecordResult.UNCHANGED -> true
                    RateTrackerRecordResult.UNAVAILABLE -> {
                        trackerAvailable = false
                        false
                    }
                }
            }
        if (!available) {
            scheduleSeedRetryAtOrBefore(
                addSaturated(nowMillis, MINIMUM_SEED_RETRY_MILLIS),
            )
        }
        return available
    }

    private fun scheduleSeedRetryAtOrBefore(candidateMillis: Long) {
        nextSeedRetryAtMillis =
            nextSeedRetryAtMillis
                ?.let { current -> minOf(current, candidateMillis) }
                ?: candidateMillis
    }

    private fun recordIfAvailable(occurrence: PersistedRateOccurrence): Boolean {
        synchronized(rateStateLock) {
            if (!trackerAvailable) return true
            return when (tracker.record(occurrence)) {
                RateTrackerRecordResult.CHANGED -> true

                RateTrackerRecordResult.UNCHANGED -> true
                RateTrackerRecordResult.UNAVAILABLE -> {
                    trackerAvailable = false
                    false
                }
            }
        }
    }

    private fun retainGap(anchorMillis: Long) {
        while (true) {
            val current = pendingGap.get()
            if (current != null && current.anchorMillis >= anchorMillis) return
            if (pendingGap.compareAndSet(current, PendingGap(anchorMillis))) return
        }
    }

    private fun isCurrent(generation: Long): Boolean = lifecycleGeneration.get() == generation

    private fun failureAnchor(command: Command): Long = maxOf(clock.millis(), command.anchorMillis)

    private sealed interface Command {
        val anchorMillis: Long

        data class Connected(
            val generation: Long,
            override val anchorMillis: Long,
        ) : Command

        data class Reseed(
            val generation: Long,
            override val anchorMillis: Long,
        ) : Command

        data class Post(
            val generation: Long,
            val rawListenerKey: String,
            val packageName: String,
            val channelId: String?,
            val postedAtMillis: Long,
            val outcome: CompletableDeferred<RatePostOutcome>,
        ) : Command {
            override val anchorMillis: Long = postedAtMillis
        }

        data class Remove(
            val generation: Long,
            val rawListenerKey: String,
            val removedPostTimeMillis: Long,
        ) : Command {
            override val anchorMillis: Long = removedPostTimeMillis
        }

        data class FlushGap(
            override val anchorMillis: Long,
        ) : Command
    }

    private data class PendingGap(
        val anchorMillis: Long,
    )

    private companion object {
        const val DEFAULT_COMMAND_CAPACITY = 64
        const val MINIMUM_SEED_RETRY_MILLIS = 60_000L
    }
}

internal sealed interface RatePostOutcome {
    data object Proceed : RatePostOutcome

    data object Stale : RatePostOutcome
}

internal data class RateCountSnapshot(
    val counts: Map<RateSignal, Int>,
)

internal data class RateCountExpectation(
    val requestedSignals: Set<RateSignal>,
    val expectedCounts: Map<RateSignal, Int>,
) {
    init {
        require(expectedCounts.keys.all(requestedSignals::contains))
    }
}

private fun maxOfNullable(
    first: Long?,
    second: Long?,
): Long? =
    when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

private fun addSaturated(
    value: Long,
    amount: Long,
): Long =
    if (value > Long.MAX_VALUE - amount) {
        Long.MAX_VALUE
    } else {
        value + amount
    }

private fun subtractSaturated(
    value: Long,
    amount: Long,
): Long =
    if (value < Long.MIN_VALUE + amount) {
        Long.MIN_VALUE
    } else {
        value - amount
    }
