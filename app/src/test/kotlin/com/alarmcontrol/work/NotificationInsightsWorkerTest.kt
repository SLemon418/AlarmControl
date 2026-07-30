package com.alarmcontrol.work

import com.alarmcontrol.core.privacy.StaleLocalDataWriteException
import com.alarmcontrol.core.result.DataResult
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationInsightsWorkerTest {
    @Test
    fun `successful housekeeping completes work`() {
        assertEquals(
            InsightWorkOutcome.SUCCESS,
            insightWorkOutcome(DataResult.Success(Unit), runAttemptCount = 0),
        )
    }

    @Test
    fun `local failure retries at most three times`() {
        val failure = DataResult.Failure(IllegalStateException("local database unavailable"))

        assertEquals(InsightWorkOutcome.RETRY, insightWorkOutcome(failure, runAttemptCount = 0))
        assertEquals(InsightWorkOutcome.RETRY, insightWorkOutcome(failure, runAttemptCount = 2))
        assertEquals(InsightWorkOutcome.FAILURE, insightWorkOutcome(failure, runAttemptCount = 3))
    }

    @Test
    fun `unexpected loading result follows the bounded retry policy`() {
        assertEquals(InsightWorkOutcome.RETRY, insightWorkOutcome(DataResult.Loading, runAttemptCount = 1))
        assertEquals(InsightWorkOutcome.FAILURE, insightWorkOutcome(DataResult.Loading, runAttemptCount = 3))
    }

    @Test
    fun `stale run cancelled by a committed reset is completed without retry`() {
        val stale = DataResult.Failure(StaleLocalDataWriteException())

        assertEquals(InsightWorkOutcome.SUCCESS, insightWorkOutcome(stale, runAttemptCount = 0))
    }
}
