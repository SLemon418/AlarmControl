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

    internal val droppedSubmissionCount: Long
        get() = droppedSource.get()

    fun submit(
        key: String,
        block: suspend (ProcessingToken) -> Unit,
    ): Job {
        lateinit var job: Job
        val tokenId = tokenSource.incrementAndGet()
        val generation = generationSource.get()
        val token = ProcessingToken(key, tokenId, generation, this)
        val replacedAndEvicted = mutableListOf<Job>()
        synchronized(lock) {
            entries.remove(key)?.job?.let(replacedAndEvicted::add)
            if (entries.size >= maxTrackedWork) {
                val evicted =
                    entries.entries.firstOrNull { !it.value.running }
                        ?: entries.entries.first()
                entries.remove(evicted.key)
                evicted.value.job?.let(replacedAndEvicted::add)
                droppedSource.incrementAndGet()
            }
            val entry = WorkEntry(tokenId, generation)
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
            entry.job = job
            entries[key] = entry
        }
        replacedAndEvicted.forEach(Job::cancel)
        job.start()
        return job
    }

    fun invalidate(key: String) {
        val job =
            synchronized(lock) {
                entries.remove(key)?.job
            }
        job?.cancel()
    }

    fun invalidateAll() {
        val activeJobs =
            synchronized(lock) {
                generationSource.incrementAndGet()
                entries.values.mapNotNull(WorkEntry::job).also { entries.clear() }
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
    ): Boolean {
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
        if (!claimed) return false

        // Binder calls can be slow. Never hold the coordinator's global lock across platform work.
        action()
        return true
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

    private data class WorkEntry(
        val tokenId: Long,
        val generation: Long,
        var job: Job? = null,
        var running: Boolean = false,
        var claimed: Boolean = false,
    )

    private companion object {
        const val DEFAULT_MAX_TRACKED_WORK = 64
        const val DEFAULT_MAX_CONCURRENT_WORK = 4
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
