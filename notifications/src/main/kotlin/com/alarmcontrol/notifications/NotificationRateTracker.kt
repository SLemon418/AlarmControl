package com.alarmcontrol.notifications

import com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.PersistedRateOccurrence
import com.alarmcontrol.core.filtering.RateOccurrenceId
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.RateSignal

/**
 * Content-free, in-memory frequency tracker for the notification hot path.
 *
 * The listener pipeline serializes durable occurrence writes and calls [record] only after they
 * commit. Identity is therefore the opaque [RateOccurrenceId], not an Android listener key or a
 * timestamp: an update moves one occurrence, while a remove followed by a repost has a new id.
 */
class NotificationRateTracker {
    private val occurrencesById = mutableMapOf<RateOccurrenceId, PersistedRateOccurrence>()
    private val packageOccurrences = mutableMapOf<String, MutableList<TimedOccurrence>>()
    private val channelOccurrences = mutableMapOf<ChannelKey, MutableList<TimedOccurrence>>()
    private var initialized = false
    private var coverageStartMillis = Long.MAX_VALUE
    private var newestPostedAtMillis = Long.MIN_VALUE

    /**
     * Replaces current state with a bounded occurrence snapshot ending at [nowMillis].
     *
     * Only windows whose inclusive cutoff is at or after [coverageStartMillis] are exposed. Returns
     * false when the snapshot exceeded the safety bound and the tracker became unavailable.
     */
    @Synchronized
    fun seed(
        occurrences: List<PersistedRateOccurrence>,
        nowMillis: Long,
        coverageStartMillis: Long = subtractSaturated(nowMillis, MAX_RATE_WINDOW_MILLIS),
    ): Boolean {
        clear()
        if (occurrences.any { occurrence -> occurrence.postedAtMillis > nowMillis }) {
            return false
        }
        val cutoff =
            maxOf(
                coverageStartMillis,
                subtractSaturated(nowMillis, MAX_RATE_WINDOW_MILLIS),
            )
        val retainedById = mutableMapOf<RateOccurrenceId, PersistedRateOccurrence>()
        occurrences.forEach { occurrence ->
            if (occurrence.postedAtMillis !in cutoff..nowMillis) return@forEach
            val previous = retainedById[occurrence.occurrenceId]
            if (
                previous == null ||
                occurrence.postedAtMillis >= previous.postedAtMillis
            ) {
                retainedById[occurrence.occurrenceId] = occurrence
            }
        }
        if (retainedById.size > MAX_TRACKED_OCCURRENCES) {
            markUnavailable()
            return false
        }
        retainedById.values
            .sortedWith(
                compareBy<PersistedRateOccurrence> { it.postedAtMillis }
                    .thenBy { it.occurrenceId.value },
            ).forEach { occurrence ->
                occurrencesById[occurrence.occurrenceId] = occurrence
                append(occurrence)
            }
        this.coverageStartMillis = cutoff
        initialized = true
        return true
    }

    /**
     * Moves the earliest complete timestamp forward after a newly persisted gap marker.
     *
     * Moving it backward would claim history that was not present in the seed, so it is ignored.
     */
    @Synchronized
    fun restrictCoverage(coverageStartMillis: Long): RateTrackerRecordResult {
        if (!initialized) return RateTrackerRecordResult.UNAVAILABLE
        if (coverageStartMillis <= this.coverageStartMillis) {
            return RateTrackerRecordResult.UNCHANGED
        }
        this.coverageStartMillis = coverageStartMillis
        packageOccurrences.prune(coverageStartMillis)
        channelOccurrences.prune(coverageStartMillis)
        occurrencesById.entries.removeAll { it.value.postedAtMillis < coverageStartMillis }
        newestPostedAtMillis =
            occurrencesById.values
                .maxOfOrNull(PersistedRateOccurrence::postedAtMillis)
                ?: Long.MIN_VALUE
        return RateTrackerRecordResult.CHANGED
    }

    /** Marks frequency history incomplete; every requested rate signal then remains unknown. */
    @Synchronized
    fun markUnavailable() {
        clear()
    }

    /**
     * Applies the latest durable metadata for one occurrence and reports whether counts changed.
     *
     * An older update for the same id makes the tracker unavailable instead of moving state
     * backward. Distinct out-of-order occurrences remain supported; [counts] omits any signal whose
     * full window predates the tracker's known coverage.
     */
    @Synchronized
    fun record(occurrence: PersistedRateOccurrence): RateTrackerRecordResult {
        if (!initialized) return RateTrackerRecordResult.UNAVAILABLE
        val previous = occurrencesById[occurrence.occurrenceId]
        if (previous != null && occurrence.postedAtMillis < previous.postedAtMillis) {
            markUnavailable()
            return RateTrackerRecordResult.UNAVAILABLE
        }

        // Do not advance coverage from a later post: an in-flight destructive decision must still
        // be able to recalculate the exact count at its earlier snapshot time. The global bound
        // remains the memory guard; a reseed or durable gap can deliberately move coverage.
        if (occurrence.postedAtMillis < coverageStartMillis) {
            return RateTrackerRecordResult.UNCHANGED
        }
        if (
            previous == null &&
            occurrencesById.size >= MAX_TRACKED_OCCURRENCES
        ) {
            markUnavailable()
            return RateTrackerRecordResult.UNAVAILABLE
        }
        if (previous == occurrence) return RateTrackerRecordResult.UNCHANGED

        previous?.let(::remove)
        occurrencesById[occurrence.occurrenceId] = occurrence
        append(occurrence)
        return RateTrackerRecordResult.CHANGED
    }

    /**
     * Returns only counts backed by a complete retained window at the snapshot's post time.
     * [observationNowMillis] additionally fails open after a wall-clock rollback leaves retained
     * occurrences in the future; late callbacks remain valid because their caller supplies current
     * wall time rather than the callback's older post time.
     */
    @Synchronized
    fun counts(
        snapshot: NotificationSnapshot,
        requestedSignals: Set<RateSignal>,
        observationNowMillis: Long? = null,
    ): Map<RateSignal, Int> {
        if (!initialized) return emptyMap()
        if (
            observationNowMillis != null &&
            newestPostedAtMillis > observationNowMillis
        ) {
            return emptyMap()
        }
        return buildMap {
            requestedSignals.forEach { signal ->
                val cutoff = subtractSaturated(snapshot.postedAtMillis, signal.windowMillis)
                if (cutoff < coverageStartMillis) return@forEach
                val timestamps =
                    when (signal.scope) {
                        RateScope.PACKAGE -> packageOccurrences[snapshot.packageName]
                        RateScope.CHANNEL -> {
                            val channelId = snapshot.channelId ?: return@forEach
                            channelOccurrences[ChannelKey(snapshot.packageName, channelId)]
                        }
                    }
                if (timestamps == null) {
                    put(signal, 0)
                } else {
                    val first = timestamps.lowerBound(cutoff)
                    val afterLast = timestamps.upperBound(snapshot.postedAtMillis)
                    put(signal, (afterLast - first).coerceAtLeast(0))
                }
            }
        }
    }

    private fun append(occurrence: PersistedRateOccurrence) {
        newestPostedAtMillis = maxOf(newestPostedAtMillis, occurrence.postedAtMillis)
        val timed = occurrence.toTimedOccurrence()
        packageOccurrences
            .getOrPut(occurrence.packageName, ::mutableListOf)
            .also { values ->
                values.insertSorted(timed)
            }
        occurrence.channelId?.let { channelId ->
            channelOccurrences
                .getOrPut(ChannelKey(occurrence.packageName, channelId), ::mutableListOf)
                .also { values ->
                    values.insertSorted(timed)
                }
        }
    }

    private fun remove(occurrence: PersistedRateOccurrence) {
        packageOccurrences.removeOccurrence(occurrence.packageName, occurrence.occurrenceId)
        occurrence.channelId?.let { channelId ->
            channelOccurrences.removeOccurrence(
                ChannelKey(occurrence.packageName, channelId),
                occurrence.occurrenceId,
            )
        }
    }

    private fun clear() {
        occurrencesById.clear()
        packageOccurrences.clear()
        channelOccurrences.clear()
        initialized = false
        coverageStartMillis = Long.MAX_VALUE
        newestPostedAtMillis = Long.MIN_VALUE
    }

    private fun <K> MutableMap<K, MutableList<TimedOccurrence>>.prune(cutoffMillis: Long) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val occurrences = iterator.next().value
            val firstRetained = occurrences.lowerBound(cutoffMillis)
            if (firstRetained > 0) occurrences.subList(0, firstRetained).clear()
            if (occurrences.isEmpty()) iterator.remove()
        }
    }

    private fun MutableList<TimedOccurrence>.insertSorted(occurrence: TimedOccurrence) {
        add(upperBound(occurrence.postedAtMillis), occurrence)
    }

    private fun List<TimedOccurrence>.lowerBound(value: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (this[middle].postedAtMillis < value) low = middle + 1 else high = middle
        }
        return low
    }

    private fun List<TimedOccurrence>.upperBound(value: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (this[middle].postedAtMillis <= value) low = middle + 1 else high = middle
        }
        return low
    }

    private fun <K> MutableMap<K, MutableList<TimedOccurrence>>.removeOccurrence(
        key: K,
        occurrenceId: RateOccurrenceId,
    ) {
        val occurrences = this[key] ?: return
        occurrences.removeAll { it.occurrenceId == occurrenceId }
        if (occurrences.isEmpty()) remove(key)
    }

    private fun PersistedRateOccurrence.toTimedOccurrence(): TimedOccurrence =
        TimedOccurrence(occurrenceId, postedAtMillis)

    private data class TimedOccurrence(
        val occurrenceId: RateOccurrenceId,
        val postedAtMillis: Long,
    )

    private data class ChannelKey(
        val packageName: String,
        val channelId: String,
    )

    private companion object {
        // The global bound also bounds every package/channel index. Per-scope truncation would lose
        // rows needed to backfill an exact count when a retained occurrence later changes scope.
        const val MAX_TRACKED_OCCURRENCES = 10_000
    }
}

/** Whether one durable occurrence or coverage update changed usable in-memory frequency state. */
enum class RateTrackerRecordResult {
    CHANGED,
    UNCHANGED,
    UNAVAILABLE,
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
