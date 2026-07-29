package com.alarmcontrol.service

import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.RateSignal
import com.alarmcontrol.core.filtering.RuleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationFilterServiceRateCommitTest {
    private val packageMinute = RateSignal(RateScope.PACKAGE, 60_000L)
    private val channelMinute = RateSignal(RateScope.CHANNEL, 60_000L)
    private val monitorOnly = RateSignal(RateScope.PACKAGE, 120_000L)

    @Test
    fun `destructive actions retain only active rate inputs for commit validation`() {
        val captured =
            mapOf(
                packageMinute to 3,
                monitorOnly to 7,
            )

        val cancel =
            destructiveRateCountExpectation(
                action = RuleAction.Cancel,
                activeRateSignals = setOf(packageMinute, channelMinute),
                capturedCounts = captured,
            )
        val snooze =
            destructiveRateCountExpectation(
                action = RuleAction.Snooze(60_000L),
                activeRateSignals = setOf(packageMinute, channelMinute),
                capturedCounts = captured,
            )

        assertNotNull(cancel)
        assertNotNull(snooze)
        assertEquals(setOf(packageMinute, channelMinute), cancel?.requestedSignals)
        assertEquals(mapOf(packageMinute to 3), cancel?.expectedCounts)
        assertEquals(cancel, snooze)
    }

    @Test
    fun `record only actions never require a rate commit expectation`() {
        val captured = mapOf(packageMinute to 3)

        assertNull(
            destructiveRateCountExpectation(
                action = RuleAction.Keep,
                activeRateSignals = setOf(packageMinute),
                capturedCounts = captured,
            ),
        )
        assertNull(
            destructiveRateCountExpectation(
                action = RuleAction.MarkRead,
                activeRateSignals = setOf(packageMinute),
                capturedCounts = captured,
            ),
        )
    }

    @Test
    fun `destructive action without active rate signals bypasses rate validation`() {
        assertNull(
            destructiveRateCountExpectation(
                action = RuleAction.Cancel,
                activeRateSignals = emptySet(),
                capturedCounts = emptyMap(),
            ),
        )
    }
}
