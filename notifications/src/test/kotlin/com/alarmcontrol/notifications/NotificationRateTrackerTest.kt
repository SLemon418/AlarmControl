package com.alarmcontrol.notifications

import com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.PersistedRateOccurrence
import com.alarmcontrol.core.filtering.RateOccurrenceId
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.RateSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRateTrackerTest {
    private val packageMinute = RateSignal(RateScope.PACKAGE, 60_000)
    private val channelFiveMinutes = RateSignal(RateScope.CHANNEL, 5 * 60_000)

    @Test
    fun `current occurrence is included and old occurrences are pruned`() {
        val tracker = NotificationRateTracker()
        tracker.seed(
            listOf(
                occurrence(1, at = 1_000),
                occurrence(2, at = 99_000),
            ),
            nowMillis = 100_000,
        )
        tracker.record(occurrence(3, at = 100_000))

        val counts = tracker.counts(snapshot(100_000), setOf(packageMinute, channelFiveMinutes))

        assertEquals(2, counts[packageMinute])
        assertEquals(3, counts[channelFiveMinutes])
    }

    @Test
    fun `missing channel omits channel count so condition remains unknown`() {
        val tracker = NotificationRateTracker()
        tracker.seed(emptyList(), nowMillis = 100_000)
        tracker.record(occurrence(1, at = 100_000, channelId = null))

        val counts =
            tracker.counts(
                snapshot(100_000).copy(channelId = null),
                setOf(channelFiveMinutes),
            )

        assertFalse(counts.containsKey(channelFiveMinutes))
    }

    @Test
    fun `unavailable tracker returns no counts`() {
        val tracker = NotificationRateTracker()
        tracker.seed(listOf(occurrence(1, at = 100_000)), nowMillis = 100_000)
        tracker.markUnavailable()

        assertEquals(emptyMap<RateSignal, Int>(), tracker.counts(snapshot(100_000), setOf(packageMinute)))
    }

    @Test
    fun `future seed occurrence degrades every rate signal to unknown`() {
        val tracker = NotificationRateTracker()

        val seeded =
            tracker.seed(
                listOf(occurrence(1, at = 100_001)),
                nowMillis = 100_000,
            )

        assertFalse(seeded)
        assertTrue(tracker.counts(snapshot(100_000), setOf(packageMinute)).isEmpty())
    }

    @Test
    fun `clock rollback after a valid seed degrades counts until wall time catches up`() {
        val tracker = NotificationRateTracker()
        tracker.seed(
            occurrences = listOf(occurrence(1, at = 200_000)),
            nowMillis = 200_000,
        )
        tracker.record(occurrence(2, at = 100_000))

        assertTrue(
            tracker
                .counts(
                    snapshot = snapshot(100_000),
                    requestedSignals = setOf(packageMinute),
                    observationNowMillis = 100_000,
                ).isEmpty(),
        )
        assertEquals(
            1,
            tracker
                .counts(
                    snapshot = snapshot(100_000),
                    requestedSignals = setOf(packageMinute),
                    observationNowMillis = 200_000,
                )[packageMinute],
        )
    }

    @Test
    fun `distinct out of order occurrences remain sorted`() {
        val tracker = NotificationRateTracker()
        tracker.seed(emptyList(), nowMillis = 200_000)
        tracker.record(occurrence(1, at = 190_000))
        tracker.record(occurrence(2, at = 150_000))

        assertEquals(2, tracker.counts(snapshot(200_000), setOf(packageMinute))[packageMinute])
    }

    @Test
    fun `same occurrence update replaces its previous timestamp`() {
        val tracker = NotificationRateTracker()
        tracker.seed(emptyList(), nowMillis = 100_000)
        tracker.record(occurrence(1, at = 95_000))
        tracker.record(occurrence(1, at = 100_000))

        assertEquals(1, tracker.counts(snapshot(100_000), setOf(packageMinute))[packageMinute])
    }

    @Test
    fun `same occurrence update moves package and channel buckets`() {
        val tracker = NotificationRateTracker()
        tracker.seed(emptyList(), nowMillis = 100_000)
        tracker.record(occurrence(1, at = 95_000, packageName = "old.pkg", channelId = "old"))
        tracker.record(occurrence(1, at = 100_000, packageName = "new.pkg", channelId = "new"))

        assertEquals(
            0,
            tracker
                .counts(
                    snapshot(100_000).copy(packageName = "old.pkg", channelId = "old"),
                    setOf(packageMinute),
                )[packageMinute],
        )
        assertEquals(
            1,
            tracker
                .counts(
                    snapshot(100_000).copy(packageName = "new.pkg", channelId = "new"),
                    setOf(packageMinute),
                )[packageMinute],
        )
    }

    @Test
    fun `older update for the same occurrence degrades to unknown`() {
        val tracker = NotificationRateTracker()
        tracker.seed(emptyList(), nowMillis = 100_000)
        tracker.record(occurrence(1, at = 100_000))
        val retained = tracker.record(occurrence(1, at = 90_000))

        assertEquals(RateTrackerRecordResult.UNAVAILABLE, retained)
        assertTrue(tracker.counts(snapshot(100_000), setOf(packageMinute)).isEmpty())
    }

    @Test
    fun `distinct occurrences sharing one millisecond are preserved`() {
        val tracker = NotificationRateTracker()
        tracker.seed(
            listOf(
                occurrence(1, at = 100_000),
                occurrence(2, at = 100_000),
            ),
            nowMillis = 100_000,
        )

        assertEquals(2, tracker.counts(snapshot(100_000), setOf(packageMinute))[packageMinute])
    }

    @Test
    fun `repost with a new occurrence id counts again`() {
        val tracker = NotificationRateTracker()
        tracker.seed(emptyList(), nowMillis = 100_000)
        tracker.record(occurrence(1, at = 99_000))
        tracker.record(occurrence(2, at = 100_000))

        assertEquals(2, tracker.counts(snapshot(100_000), setOf(packageMinute))[packageMinute])
    }

    @Test
    fun `scope history retains every occurrence within the global bound`() {
        val tracker = NotificationRateTracker()
        tracker.seed(
            (1..1_500).map { index -> occurrence(index, at = index.toLong()) },
            nowMillis = 1_500,
        )

        assertEquals(1_500, tracker.counts(snapshot(1_500), setOf(packageMinute))[packageMinute])
    }

    @Test
    fun `moving one of 1001 occurrences keeps the old channel exact`() {
        val tracker = NotificationRateTracker()
        tracker.seed(
            (1..1_001).map { index -> occurrence(index, at = index.toLong()) },
            nowMillis = 1_001,
        )

        tracker.record(occurrence(1_001, at = 1_002, channelId = "moved"))

        assertEquals(
            1_000,
            tracker
                .counts(
                    snapshot(1_002),
                    setOf(channelFiveMinutes),
                )[channelFiveMinutes],
        )
        assertEquals(
            1,
            tracker
                .counts(
                    snapshot(1_002).copy(channelId = "moved"),
                    setOf(channelFiveMinutes),
                )[channelFiveMinutes],
        )
    }

    @Test
    fun `seed over total occurrence limit degrades to unknown`() {
        val tracker = NotificationRateTracker()
        val seeded =
            tracker.seed(
                (0..10_000).map { index -> occurrence(index, at = index.toLong()) },
                nowMillis = 10_000,
            )

        assertFalse(seeded)
        assertTrue(tracker.counts(snapshot(10_000), setOf(packageMinute)).isEmpty())
    }

    @Test
    fun `runtime total occurrence overflow degrades to unknown`() {
        val tracker = NotificationRateTracker()
        tracker.seed(emptyList(), nowMillis = 10_000)
        var retained = RateTrackerRecordResult.UNCHANGED
        repeat(10_001) { index ->
            retained = tracker.record(occurrence(index, at = index.toLong()))
        }

        assertEquals(RateTrackerRecordResult.UNAVAILABLE, retained)
        assertTrue(tracker.counts(snapshot(10_000), setOf(packageMinute)).isEmpty())
    }

    @Test
    fun `late query omits only windows that predate known coverage`() {
        val tracker = NotificationRateTracker()
        val fullWindow = RateSignal(RateScope.PACKAGE, MAX_RATE_WINDOW_MILLIS)
        tracker.seed(emptyList(), nowMillis = 100_000)
        tracker.record(occurrence(1, at = 99_000))

        val counts = tracker.counts(snapshot(99_000), setOf(packageMinute, fullWindow))

        assertEquals(1, counts[packageMinute])
        assertFalse(counts.containsKey(fullWindow))
    }

    @Test
    fun `later post preserves a full-window count at the earlier snapshot boundary`() {
        val tracker = NotificationRateTracker()
        val fullWindow = RateSignal(RateScope.PACKAGE, MAX_RATE_WINDOW_MILLIS)
        tracker.seed(
            occurrences = listOf(occurrence(1, at = 0L)),
            nowMillis = MAX_RATE_WINDOW_MILLIS,
        )
        val expected =
            tracker.counts(
                snapshot(MAX_RATE_WINDOW_MILLIS),
                setOf(fullWindow),
            )

        tracker.record(occurrence(2, at = MAX_RATE_WINDOW_MILLIS + 1L))

        assertEquals(
            expected,
            tracker.counts(
                snapshot(MAX_RATE_WINDOW_MILLIS),
                setOf(fullWindow),
            ),
        )
    }

    @Test
    fun `partial seed exposes each window only at its exact coverage boundary`() {
        val tracker = NotificationRateTracker()
        val fullWindow = RateSignal(RateScope.PACKAGE, MAX_RATE_WINDOW_MILLIS)
        val coverageStartMillis = 100_001L
        tracker.seed(
            occurrences = emptyList(),
            nowMillis = 100_000L,
            coverageStartMillis = coverageStartMillis,
        )
        tracker.record(occurrence(1, at = coverageStartMillis + packageMinute.windowMillis))

        val beforeBoundary =
            tracker.counts(
                snapshot(coverageStartMillis + packageMinute.windowMillis - 1),
                setOf(packageMinute, fullWindow),
            )
        val atBoundary =
            tracker.counts(
                snapshot(coverageStartMillis + packageMinute.windowMillis),
                setOf(packageMinute, fullWindow),
            )

        assertFalse(beforeBoundary.containsKey(packageMinute))
        assertEquals(1, atBoundary[packageMinute])
        assertFalse(atBoundary.containsKey(fullWindow))
    }

    @Test
    fun `stronger coverage barrier prunes earlier occurrences and reports a change`() {
        val tracker = NotificationRateTracker()
        tracker.seed(
            occurrences = listOf(occurrence(1, at = 100_000L)),
            nowMillis = 100_000L,
        )

        val result = tracker.restrictCoverage(100_001L)

        assertEquals(RateTrackerRecordResult.CHANGED, result)
        assertEquals(
            0,
            tracker.counts(
                snapshot(160_001L),
                setOf(packageMinute),
            )[packageMinute],
        )
    }

    private fun occurrence(
        index: Int,
        at: Long,
        packageName: String = "pkg",
        channelId: String? = "offers",
    ) = PersistedRateOccurrence(
        occurrenceId = occurrenceId(index),
        packageName = packageName,
        channelId = channelId,
        postedAtMillis = at,
    )

    private fun occurrenceId(index: Int): RateOccurrenceId =
        RateOccurrenceId(
            "00000000-0000-4000-8000-${index.toString(radix = 16).padStart(length = 12, padChar = '0')}",
        )

    private fun snapshot(at: Long) =
        NotificationSnapshot(
            packageName = "pkg",
            title = null,
            text = null,
            category = null,
            channelId = "offers",
            postedAtMillis = at,
            isOngoing = false,
        )
}
