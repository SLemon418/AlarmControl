package com.alarmcontrol.notifications

import com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.NotificationRateEvent
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.RateSignal

/**
 * Content-free, in-memory frequency tracker for the notification hot path. It is seeded once from
 * Room when the listener connects, then updated without per-notification database reads.
 */
class NotificationRateTracker {
    private val packageEvents = mutableMapOf<String, MutableList<Long>>()
    private val channelEvents = mutableMapOf<ChannelKey, MutableList<Long>>()
    private val livePostsByKey = linkedMapOf<String, NotificationRateEvent>()
    private var initialized = false
    private var newestTimestampMillis = Long.MIN_VALUE

    /** Replaces current state with at most the last 24 hours of local event metadata. */
    @Synchronized
    fun seed(
        events: List<NotificationRateEvent>,
        nowMillis: Long,
    ) {
        val livePosts = livePostsByKey.values.toList()
        packageEvents.clear()
        channelEvents.clear()
        val cutoff = nowMillis - MAX_RATE_WINDOW_MILLIS
        mergeSeedAndLive(events, livePosts)
            .asSequence()
            .filter { it.postedAtMillis in cutoff..nowMillis }
            .sortedBy { it.postedAtMillis }
            .forEach(::append)
        newestTimestampMillis = maxOf(nowMillis, livePosts.maxOfOrNull { it.postedAtMillis } ?: Long.MIN_VALUE)
        prune(newestTimestampMillis - MAX_RATE_WINDOW_MILLIS)
        initialized = true
    }

    /** Marks a failed seed; requested frequency signals then stay unknown instead of guessing. */
    @Synchronized
    fun markUnavailable() {
        packageEvents.clear()
        channelEvents.clear()
        livePostsByKey.clear()
        newestTimestampMillis = Long.MIN_VALUE
        initialized = false
    }

    /**
     * Records a post immediately, before optional ML/LLM work. Re-posts for the same listener [key]
     * replace the prior timestamp instead of inflating frequency counts.
     */
    @Synchronized
    fun record(
        snapshot: NotificationSnapshot,
        key: String,
    ) {
        val event = NotificationRateEvent(snapshot.packageName, snapshot.channelId, snapshot.postedAtMillis)
        val previous = livePostsByKey[key]
        if (previous == event) return
        if (previous != null && event.postedAtMillis < previous.postedAtMillis) return
        if (previous == null && livePostsByKey.size >= MAX_LIVE_KEYS) {
            // Losing update identity could otherwise over-count a later repost and trigger a
            // destructive rate rule. Degrade all rate signals to UNKNOWN until the next seed.
            initialized = false
            packageEvents.clear()
            channelEvents.clear()
            livePostsByKey.entries.iterator().let { iterator ->
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        livePostsByKey[key] = event
        if (initialized) {
            previous?.let(::remove)
            append(event)
        }
        newestTimestampMillis = maxOf(newestTimestampMillis, event.postedAtMillis)
        prune(newestTimestampMillis - MAX_RATE_WINDOW_MILLIS)
    }

    /**
     * Stops treating a later post with [key] as an update while retaining this post in historical
     * frequency counts. A notification removed and subsequently reposted is a new occurrence.
     */
    @Synchronized
    fun markRemoved(key: String) {
        livePostsByKey.remove(key)
    }

    /** Returns requested counts at the snapshot's post time without recording another event. */
    @Synchronized
    fun counts(
        snapshot: NotificationSnapshot,
        requestedSignals: Set<RateSignal>,
    ): Map<RateSignal, Int> {
        if (!initialized) return emptyMap()
        return calculateCounts(snapshot, requestedSignals, snapshot.postedAtMillis)
    }

    /**
     * Includes [snapshot] as the current post and returns counts only for requested signals. A
     * channel-scoped signal is omitted when the notification has no channel id.
     */
    @Synchronized
    fun recordAndCount(
        snapshot: NotificationSnapshot,
        requestedSignals: Set<RateSignal>,
        nowMillis: Long = snapshot.postedAtMillis,
    ): Map<RateSignal, Int> {
        if (!initialized) return emptyMap()
        val event = NotificationRateEvent(snapshot.packageName, snapshot.channelId, nowMillis)
        append(event)
        newestTimestampMillis = maxOf(newestTimestampMillis, nowMillis)
        prune(newestTimestampMillis - MAX_RATE_WINDOW_MILLIS)
        return calculateCounts(snapshot, requestedSignals, nowMillis)
    }

    private fun calculateCounts(
        snapshot: NotificationSnapshot,
        requestedSignals: Set<RateSignal>,
        nowMillis: Long,
    ): Map<RateSignal, Int> =
        buildMap {
            requestedSignals.forEach { signal ->
                val timestamps =
                    when (signal.scope) {
                        RateScope.PACKAGE -> packageEvents[snapshot.packageName]
                        RateScope.CHANNEL -> {
                            val channelId = snapshot.channelId ?: return@forEach
                            channelEvents[ChannelKey(snapshot.packageName, channelId)]
                        }
                    }
                if (timestamps != null) {
                    val cutoff = nowMillis - signal.windowMillis
                    val first = timestamps.lowerBound(cutoff)
                    val afterLast = timestamps.upperBound(nowMillis)
                    put(signal, (afterLast - first).coerceAtLeast(0))
                } else {
                    put(signal, 0)
                }
            }
        }

    private fun append(event: NotificationRateEvent) {
        packageEvents
            .getOrPut(event.packageName, ::mutableListOf)
            .also { timestamps ->
                timestamps.insertSorted(event.postedAtMillis)
                timestamps.retainNewest()
            }
        event.channelId?.let { channelId ->
            channelEvents
                .getOrPut(ChannelKey(event.packageName, channelId), ::mutableListOf)
                .also { timestamps ->
                    timestamps.insertSorted(event.postedAtMillis)
                    timestamps.retainNewest()
                }
        }
    }

    private fun remove(event: NotificationRateEvent) {
        packageEvents.removeTimestamp(event.packageName, event.postedAtMillis)
        event.channelId?.let { channelId ->
            channelEvents.removeTimestamp(ChannelKey(event.packageName, channelId), event.postedAtMillis)
        }
    }

    private fun prune(cutoffMillis: Long) {
        packageEvents.prune(cutoffMillis)
        channelEvents.prune(cutoffMillis)
        livePostsByKey.entries.removeAll { it.value.postedAtMillis < cutoffMillis }
    }

    private fun <K> MutableMap<K, MutableList<Long>>.prune(cutoffMillis: Long) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val timestamps = iterator.next().value
            val firstRetained = timestamps.lowerBound(cutoffMillis)
            if (firstRetained > 0) timestamps.subList(0, firstRetained).clear()
            if (timestamps.isEmpty()) iterator.remove()
        }
    }

    private fun MutableList<Long>.insertSorted(timestamp: Long) {
        add(upperBound(timestamp), timestamp)
    }

    private fun MutableList<Long>.retainNewest() {
        val overflow = size - MAX_POSTS_PER_SCOPE
        if (overflow > 0) subList(0, overflow).clear()
    }

    private fun List<Long>.lowerBound(value: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (this[middle] < value) low = middle + 1 else high = middle
        }
        return low
    }

    private fun List<Long>.upperBound(value: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (this[middle] <= value) low = middle + 1 else high = middle
        }
        return low
    }

    private fun <K> MutableMap<K, MutableList<Long>>.removeTimestamp(
        key: K,
        timestamp: Long,
    ) {
        val timestamps = this[key] ?: return
        val index = timestamps.binarySearch(timestamp)
        if (index >= 0) timestamps.removeAt(index)
        if (timestamps.isEmpty()) remove(key)
    }

    private data class ChannelKey(
        val packageName: String,
        val channelId: String,
    )

    private fun mergeSeedAndLive(
        seed: List<NotificationRateEvent>,
        live: List<NotificationRateEvent>,
    ): List<NotificationRateEvent> {
        val remainingSeedMatches = seed.groupingBy { it }.eachCount().toMutableMap()
        val additionalLive =
            live.filter { event ->
                val matches = remainingSeedMatches[event] ?: 0
                if (matches > 0) {
                    remainingSeedMatches[event] = matches - 1
                    false
                } else {
                    true
                }
            }
        return seed + additionalLive
    }

    private companion object {
        // RuleDefinitionValidator caps rate thresholds at 1,000. Retaining the newest 1,000
        // timestamps therefore preserves every possible RateAtLeast result without unbounded memory.
        const val MAX_POSTS_PER_SCOPE = 1_000
        const val MAX_LIVE_KEYS = 4_096
    }
}
