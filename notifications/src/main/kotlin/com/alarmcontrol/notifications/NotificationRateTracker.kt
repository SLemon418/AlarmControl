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
    private val livePostsByKey = mutableMapOf<String, NotificationRateEvent>()
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
        events
            .asSequence()
            .filter { it.postedAtMillis in cutoff..nowMillis }
            .distinct()
            .sortedBy { it.postedAtMillis }
            .forEach(::append)
        livePosts
            .asSequence()
            .filter { it.postedAtMillis >= cutoff }
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
        val previous = livePostsByKey.put(key, event)
        if (previous == event) return
        previous?.let(::remove)
        append(event)
        newestTimestampMillis = maxOf(newestTimestampMillis, event.postedAtMillis)
        prune(newestTimestampMillis - MAX_RATE_WINDOW_MILLIS)
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
                    put(signal, timestamps.count { it >= cutoff && it <= nowMillis })
                } else {
                    put(signal, 0)
                }
            }
        }

    private fun append(event: NotificationRateEvent) {
        packageEvents.getOrPut(event.packageName, ::mutableListOf).insertSorted(event.postedAtMillis)
        event.channelId?.let { channelId ->
            channelEvents
                .getOrPut(ChannelKey(event.packageName, channelId), ::mutableListOf)
                .insertSorted(event.postedAtMillis)
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
            val firstRetained = timestamps.binarySearch(cutoffMillis).let { if (it < 0) -it - 1 else it }
            if (firstRetained > 0) timestamps.subList(0, firstRetained).clear()
            if (timestamps.isEmpty()) iterator.remove()
        }
    }

    private fun MutableList<Long>.insertSorted(timestamp: Long) {
        val index = binarySearch(timestamp).let { if (it < 0) -it - 1 else it + 1 }
        add(index, timestamp)
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
}
