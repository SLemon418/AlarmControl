package com.alarmcontrol.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns in-flight notification work by listener key. A newer post cancels the older generation for
 * that key, while removal/disconnection invalidates it. [ProcessingToken.commit] is the single
 * atomic boundary at which a still-current result may perform its platform action.
 */
internal class NotificationProcessingCoordinator(
    private val scope: CoroutineScope,
    private val maxTrackedWork: Int = DEFAULT_MAX_TRACKED_WORK,
    maxConcurrentWork: Int = DEFAULT_MAX_CONCURRENT_WORK,
) {
    init {
        require(maxTrackedWork > 0)
        require(maxConcurrentWork in 1..maxTrackedWork)
    }

    private val lock = Any()
    private val tokenSource = AtomicLong(0)
    private val generationSource = AtomicLong(0)
    private val droppedSource = AtomicLong(0)
    private val workPermits = Semaphore(maxConcurrentWork)
    private val entries = linkedMapOf<String, WorkEntry>()
    private val actionGates = Array(ACTION_GATE_COUNT) { Any() }

    internal val droppedSubmissionCount: Long
        get() = droppedSource.get()

    fun submit(
        key: String,
        freshness: Long? = null,
        block: suspend (ProcessingToken) -> Unit,
    ): Job {
        lateinit var job: Job
        val tokenId = tokenSource.incrementAndGet()
        val workFreshness = freshness ?: tokenId
        val generation = generationSource.get()
        val token = ProcessingToken(key, tokenId, generation, this)
        val replacedAndEvicted = mutableListOf<Job>()
        var accepted = false
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    workPermits.withPermit {
                        if (markRunningIfCurrent(key, tokenId, generation)) {
                            block(token)
                        }
                    }
                } finally {
                    releaseIfCurrent(key, tokenId, generation, job)
                }
            }
        synchronized(actionGate(key)) {
            synchronized(lock) {
                val sameKey = entries[key]
                val staleForSameKey =
                    sameKey != null &&
                        compareFreshness(workFreshness, tokenId, sameKey) < 0
                if (!staleForSameKey) {
                    entries.remove(key)?.job?.let(replacedAndEvicted::add)
                    var hasCapacity = entries.size < maxTrackedWork
                    if (!hasCapacity) {
                        val oldestWaiting =
                            entries.entries
                                .asSequence()
                                .filterNot { it.value.running }
                                .minWithOrNull(
                                    compareBy<Map.Entry<String, WorkEntry>> { it.value.freshness }
                                        .thenBy { it.value.tokenId },
                                )
                        if (
                            oldestWaiting != null &&
                            compareFreshness(workFreshness, tokenId, oldestWaiting.value) > 0
                        ) {
                            entries.remove(oldestWaiting.key)
                            oldestWaiting.value.job?.let(replacedAndEvicted::add)
                            droppedSource.incrementAndGet()
                            hasCapacity = true
                        }
                    }
                    if (hasCapacity) {
                        entries[key] =
                            WorkEntry(
                                tokenId = tokenId,
                                generation = generation,
                                freshness = workFreshness,
                                job = job,
                            )
                        accepted = true
                    }
                }
                if (!accepted) {
                    droppedSource.incrementAndGet()
                }
            }
        }
        replacedAndEvicted.forEach(Job::cancel)
        if (accepted) {
            job.start()
        } else {
            job.cancel()
        }
        return job
    }

    fun invalidate(key: String) {
        val job =
            synchronized(actionGate(key)) {
                synchronized(lock) {
                    entries.remove(key)?.job
                }
            }
        job?.cancel()
    }

    /**
     * Invalidates same-key work only when a callback rejected before [submit] is not older.
     *
     * The synthetic token id preserves [submit]'s equal-post-time ordering, while the per-key action
     * gate keeps this comparison atomic with a platform commit.
     */
    fun invalidateIfAtLeastAsFresh(
        key: String,
        freshness: Long,
    ) {
        val tokenId = tokenSource.incrementAndGet()
        val job =
            synchronized(actionGate(key)) {
                synchronized(lock) {
                    entries[key]
                        ?.takeIf { current -> compareFreshness(freshness, tokenId, current) >= 0 }
                        ?.let {
                            entries.remove(key)
                            it.job
                        }
                }
            }
        job?.cancel()
    }

    fun invalidateAll() {
        invalidateAllAndUpdate {}
    }

    /**
     * Invalidates every existing token and publishes related listener state at one linearization
     * point. New submissions can only run against [update]'s completed state.
     */
    fun invalidateAllAndUpdate(update: () -> Unit) {
        val activeJobs =
            synchronized(lock) {
                generationSource.incrementAndGet()
                entries.values.mapNotNull(WorkEntry::job).also {
                    entries.clear()
                    update()
                }
            }
        activeJobs.forEach(Job::cancel)
    }

    private fun isCurrent(
        key: String,
        tokenId: Long,
        generation: Long,
    ): Boolean =
        synchronized(lock) {
            generationSource.get() == generation &&
                entries[key]?.let { it.tokenId == tokenId && it.generation == generation && !it.claimed } == true
        }

    private fun markRunningIfCurrent(
        key: String,
        tokenId: Long,
        generation: Long,
    ): Boolean =
        synchronized(lock) {
            entries[key]
                ?.takeIf { it.canCommit(tokenId, generation) }
                ?.also { it.running = true } != null
        }

    private fun commit(
        key: String,
        tokenId: Long,
        generation: Long,
        action: () -> Unit,
    ): Boolean =
        synchronized(actionGate(key)) {
            val claimed =
                synchronized(lock) {
                    val entry = entries[key]
                    if (entry?.canCommit(tokenId, generation) != true) {
                        false
                    } else {
                        entry.claimed = true
                        entries.remove(key)
                        true
                    }
                }
            if (!claimed) return@synchronized false

            // Binder calls can be slow. The global lock remains free; only a new callback for the
            // same notification key waits until this atomic platform action has returned.
            action()
            true
        }

    private fun releaseIfCurrent(
        key: String,
        tokenId: Long,
        generation: Long,
        job: Job,
    ) {
        synchronized(lock) {
            val entry = entries[key]
            if (
                entry?.tokenId == tokenId &&
                entry.generation == generation &&
                entry.job === job
            ) {
                entries.remove(key)
            }
        }
    }

    private fun WorkEntry.canCommit(
        tokenId: Long,
        generation: Long,
    ): Boolean {
        if (generationSource.get() != generation) return false
        return this.tokenId == tokenId && this.generation == generation && !claimed
    }

    private fun compareFreshness(
        freshness: Long,
        tokenId: Long,
        other: WorkEntry,
    ): Int {
        val freshnessComparison = freshness.compareTo(other.freshness)
        return if (freshnessComparison != 0) freshnessComparison else tokenId.compareTo(other.tokenId)
    }

    private fun actionGate(key: String): Any = actionGates[(key.hashCode() and Int.MAX_VALUE) % actionGates.size]

    private data class WorkEntry(
        val tokenId: Long,
        val generation: Long,
        val freshness: Long,
        var job: Job? = null,
        var running: Boolean = false,
        var claimed: Boolean = false,
    )

    private companion object {
        const val DEFAULT_MAX_TRACKED_WORK = 64
        const val DEFAULT_MAX_CONCURRENT_WORK = 4
        const val ACTION_GATE_COUNT = 256
    }

    internal class ProcessingToken internal constructor(
        private val key: String,
        private val tokenId: Long,
        private val generation: Long,
        private val owner: NotificationProcessingCoordinator,
    ) {
        fun isCurrent(): Boolean = owner.isCurrent(key, tokenId, generation)

        /** Runs [action] at most once, and only while this is still the latest work for its key. */
        fun commit(action: () -> Unit): Boolean = owner.commit(key, tokenId, generation, action)
    }
}
