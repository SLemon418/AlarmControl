package com.alarmcontrol.service

import android.app.Notification
import android.os.Bundle
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NotificationSnapshotMapperTest {
    @Test
    fun `minuteOfDay converts an instant to local wall-clock minutes`() {
        val millis = Instant.parse("2024-01-01T22:30:00Z").toEpochMilli()
        assertEquals(22 * 60 + 30, minuteOfDay(millis, ZoneId.of("UTC")))
    }

    @Test
    fun `minuteOfDay respects the time zone`() {
        val millis = Instant.parse("2024-01-01T22:30:00Z").toEpochMilli()
        // +02:00 -> 00:30 local -> 30 minutes past midnight.
        assertEquals(30, minuteOfDay(millis, ZoneId.of("+02:00")))
    }

    @Test
    fun `conversation ranking starts at API 31`() {
        assertFalse(supportsConversationRanking(30))
        assertTrue(supportsConversationRanking(31))
    }

    @Test
    fun `notification text is bounded before entering the processing pipeline`() {
        val input = "x".repeat(20)

        assertEquals("xxxxx", input.boundedNotificationText(5))
        assertEquals("", input.boundedNotificationText(0))
    }

    @Test
    fun `notification text bound never leaves a dangling high surrogate`() {
        val input = "A\uD83D\uDE00B"

        val bounded = input.boundedNotificationText(2)

        assertEquals("A", bounded)
        assertFalse(requireNotNull(bounded).last().isHighSurrogate())
    }

    @Test
    fun `malformed notification text is ignored and a safe fallback is used`() {
        val extras =
            mockk<Bundle> {
                every { getCharSequence(Notification.EXTRA_TITLE_BIG) } returns ThrowingCharSequence
                every { getCharSequence(Notification.EXTRA_TITLE) } returns "Fallback"
            }

        assertNull(ThrowingCharSequence.boundedNotificationText(20))
        assertEquals(
            "Fallback",
            extras.boundedNotificationExtra(
                20,
                Notification.EXTRA_TITLE_BIG,
                Notification.EXTRA_TITLE,
            ),
        )
    }

    @Test
    fun `blank expanded text falls back to the regular notification text`() {
        val extras =
            mockk<Bundle> {
                every { getCharSequence(Notification.EXTRA_BIG_TEXT) } returns " "
                every { getCharSequence(Notification.EXTRA_TEXT) } returns "Payment completed"
            }

        assertEquals(
            "Payment completed",
            extras.boundedNotificationExtra(
                40,
                Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_TEXT,
            ),
        )
    }

    @Test
    fun `missing foreign notification extras map to empty text instead of crashing`() {
        val extras: Bundle? = null

        assertNull(
            extras.boundedNotificationExtra(
                40,
                Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_TEXT,
            ),
        )
    }
}

private data object ThrowingCharSequence : CharSequence {
    override val length: Int
        get() = error("Malformed external text")

    override fun get(index: Int): Char = error("Malformed external text")

    override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): CharSequence = error("Malformed external text")
}
