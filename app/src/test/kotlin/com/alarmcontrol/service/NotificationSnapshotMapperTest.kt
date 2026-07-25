package com.alarmcontrol.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
