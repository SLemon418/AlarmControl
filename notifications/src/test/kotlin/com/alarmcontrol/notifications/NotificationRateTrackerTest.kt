package com.alarmcontrol.notifications

import com.alarmcontrol.core.filtering.NotificationRateEvent
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.RateSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationRateTrackerTest {
    private val packageMinute = RateSignal(RateScope.PACKAGE, 60_000)
    private val channelFiveMinutes = RateSignal(RateScope.CHANNEL, 5 * 60_000)

    @Test
    fun `current notification is included and old events are pruned`() {
        val tracker = NotificationRateTracker()
        tracker.seed(
            listOf(
                NotificationRateEvent("pkg", "offers", 1_000),
                NotificationRateEvent("pkg", "offers", 99_000),
            ),
            nowMillis = 100_000,
        )

        val counts = tracker.recordAndCount(snapshot(100_000), setOf(packageMinute, channelFiveMinutes))

        assertEquals(2, counts[packageMinute])
        assertEquals(3, counts[channelFiveMinutes])
    }

    @Test
    fun `missing channel omits channel count so condition remains unknown`() {
        val tracker = NotificationRateTracker()
        tracker.seed(emptyList(), nowMillis = 100_000)

        val counts =
            tracker.recordAndCount(
                snapshot(100_000).copy(channelId = null),
                setOf(channelFiveMinutes),
            )

        assertFalse(counts.containsKey(channelFiveMinutes))
    }

    @Test
    fun `failed initialization returns no counts`() {
        val tracker = NotificationRateTracker()
        tracker.markUnavailable()

        assertEquals(emptyMap<RateSignal, Int>(), tracker.recordAndCount(snapshot(100_000), setOf(packageMinute)))
    }

    @Test
    fun `out of order posts remain sorted and count only the requested window`() {
        val tracker = NotificationRateTracker()
        tracker.seed(emptyList(), nowMillis = 200_000)
        tracker.record(snapshot(190_000), "newer")
        tracker.record(snapshot(150_000), "older")

        val counts = tracker.counts(snapshot(200_000), setOf(packageMinute))

        assertEquals(2, counts[packageMinute])
    }

    @Test
    fun `same listener key replaces its previous post instead of inflating the count`() {
        val tracker = NotificationRateTracker()
        tracker.seed(emptyList(), nowMillis = 100_000)
        tracker.record(snapshot(95_000), "same")
        tracker.record(snapshot(100_000), "same")

        val counts = tracker.counts(snapshot(100_000), setOf(packageMinute))

        assertEquals(1, counts[packageMinute])
    }

    @Test
    fun `posts received while seed is loading are merged into history`() {
        val tracker = NotificationRateTracker()
        tracker.markUnavailable()
        tracker.record(snapshot(100_000), "live")
        tracker.seed(
            listOf(NotificationRateEvent("pkg", "offers", 90_000)),
            nowMillis = 100_000,
        )

        val counts = tracker.counts(snapshot(100_000), setOf(packageMinute))

        assertEquals(2, counts[packageMinute])
    }

    @Test
    fun `seed does not duplicate an identical post received while loading`() {
        val tracker = NotificationRateTracker()
        tracker.markUnavailable()
        tracker.record(snapshot(100_000), "live")
        tracker.seed(
            listOf(NotificationRateEvent("pkg", "offers", 100_000)),
            nowMillis = 100_000,
        )

        val counts = tracker.counts(snapshot(100_000), setOf(packageMinute))

        assertEquals(1, counts[packageMinute])
    }

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
