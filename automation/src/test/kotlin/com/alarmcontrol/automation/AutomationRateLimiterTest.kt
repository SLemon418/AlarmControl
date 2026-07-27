package com.alarmcontrol.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRateLimiterTest {
    @Test
    fun `allows twelve requests then rejects until the rolling window expires`() {
        val limiter = AutomationRateLimiter()

        repeat(12) { assertTrue(limiter.tryAcquire(nowMillis = 1_000L)) }
        assertFalse(limiter.tryAcquire(nowMillis = 1_000L))
        assertTrue(limiter.tryAcquire(nowMillis = 61_000L))
    }

    @Test
    fun `rejected storm allowance is bounded independently from valid requests`() {
        val limiter = AutomationRateLimiter()

        repeat(12) { assertTrue(limiter.tryAcquireRejected(nowMillis = 1_000L)) }
        assertFalse(limiter.tryAcquireRejected(nowMillis = 1_000L))

        repeat(12) { assertTrue(limiter.tryAcquire(nowMillis = 1_000L)) }
        assertFalse(limiter.tryAcquire(nowMillis = 1_000L))
    }

    @Test
    fun `wall clock rollback does not lock automation indefinitely`() {
        val limiter = AutomationRateLimiter()

        repeat(12) { assertTrue(limiter.tryAcquire(nowMillis = 60_000L)) }
        assertFalse(limiter.tryAcquire(nowMillis = 60_000L))

        assertTrue(limiter.tryAcquire(nowMillis = 1_000L))
    }
}
