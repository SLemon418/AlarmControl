package com.alarmcontrol.core.filtering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationHistoryQueryTest {
    @Test
    fun `rejects a reversed history window`() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationHistoryQuery(startMillis = 2, endMillis = 1)
        }
    }

    @Test
    fun `rejects an oversized search before it reaches Room`() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationHistoryQuery(
                startMillis = 0,
                endMillis = 1,
                search = "x".repeat(MAX_NOTIFICATION_HISTORY_QUERY_CHARS + 1),
            )
        }
    }

    @Test
    fun `rejects unbounded history page sizes`() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationHistoryQuery(startMillis = 0, endMillis = 1, limit = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotificationHistoryQuery(
                startMillis = 0,
                endMillis = 1,
                limit = MAX_NOTIFICATION_HISTORY_PAGE_SIZE + 1,
            )
        }
    }

    @Test
    fun `trace coverage ignores events that never matched a rule`() {
        val coverage =
            NotificationHistoryCoverage(
                totalEvents = 20,
                oldestPostedAtMillis = 1,
                newestPostedAtMillis = 2,
                eventsWithTrace = 4,
                traceEligibleEvents = 4,
            )

        assertFalse(coverage.traceCoveragePartial)
        assertTrue(coverage.copy(traceEligibleEvents = 5).traceCoveragePartial)
    }
}
