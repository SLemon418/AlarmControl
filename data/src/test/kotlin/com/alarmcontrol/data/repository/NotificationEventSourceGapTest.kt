package com.alarmcontrol.data.repository

import com.alarmcontrol.data.db.entity.NotificationDecisionTraceEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class NotificationEventSourceGapTest {
    @Test
    fun `ten thousand and one rows trim by exact order and mark the deleted source day`() =
        runTest {
            val dao = FakeNotificationEventDao()
            repeat(10_001) { index ->
                dao.insert(event(postedAtMillis = index.toLong(), epochDay = 20))
            }

            val deleted = dao.deleteOverLimitWithSourceGaps(10_000, ZoneOffset.UTC)

            assertEquals(1, deleted)
            assertEquals(10_000, dao.countAll())
            assertEquals(1L, dao.inserted.minOf { it.postedAtMillis })
            assertEquals(setOf(20L), dao.sourceGapDays)
        }

    @Test
    fun `equal posted times retain the greatest ids`() =
        runTest {
            val dao = FakeNotificationEventDao()
            repeat(2) {
                dao.insert(event(postedAtMillis = 100, epochDay = 20))
            }

            dao.insertWithTraceAndTrim(
                event = event(postedAtMillis = 100, epochDay = 20),
                trace = emptyList(),
                encryptedContent = null,
                max = 2,
                maxTraceEvents = 1_000,
                legacyZoneId = ZoneOffset.UTC,
            )

            assertEquals(listOf(2L, 3L), dao.inserted.map { it.id })
        }

    @Test
    fun `atomic insert retains traces only for the newest event window`() =
        runTest {
            val dao = FakeNotificationEventDao()
            repeat(3) { index ->
                dao.insertWithTraceAndTrim(
                    event = event(postedAtMillis = index.toLong(), epochDay = 20),
                    trace = listOf(trace()),
                    encryptedContent = null,
                    max = 10,
                    maxTraceEvents = 2,
                    legacyZoneId = ZoneOffset.UTC,
                )
            }

            assertEquals(2, dao.observeCoverage().first().traceEventCount)
            assertEquals(emptyList<NotificationDecisionTraceEntity>(), dao.getDetail(1)?.trace)
            assertEquals(1, dao.getDetail(2)?.trace?.size)
            assertEquals(1, dao.getDetail(3)?.trace?.size)
        }

    @Test
    fun `delayed enrichment updates metadata but cannot restore a trace outside the newest window`() =
        runTest {
            val dao = FakeNotificationEventDao()
            repeat(3) { index ->
                dao.insert(event(postedAtMillis = index.toLong(), epochDay = 20))
            }

            dao.updatePostCommitEnrichmentWithTrace(
                eventId = 1,
                mlCategory = "OTHER",
                mlConfidence = 0.8f,
                monitoredRuleId = null,
                monitoredAction = null,
                trace = listOf(trace()),
                maxTraceEvents = 2,
            )

            val detail = dao.getDetail(1)
            assertEquals("OTHER", detail?.event?.mlCategory)
            assertEquals(emptyList<NotificationDecisionTraceEntity>(), detail?.trace)
        }

    @Test
    fun `retention marks a relevant day but ignores excluded rows`() =
        runTest {
            val dao = FakeNotificationEventDao()
            dao.insert(event(postedAtMillis = 1, epochDay = 20))
            dao.insert(event(postedAtMillis = 2, epochDay = 21, undone = true))

            val deleted = dao.deleteOlderThanWithSourceGaps(3, ZoneOffset.UTC)

            assertEquals(2, deleted)
            assertEquals(setOf(20L), dao.sourceGapDays)
        }

    @Test
    fun `retention preserves normal history left future dated by a clock rollback`() =
        runTest {
            val dao = FakeNotificationEventDao()
            dao.insert(event(postedAtMillis = 700, epochDay = 20))
            dao.insert(event(postedAtMillis = 2_000, epochDay = 21))

            val deleted =
                dao.deleteOlderThanWithSourceGaps(
                    cutoffMillis = 500,
                    legacyZoneId = ZoneOffset.UTC,
                )

            assertEquals(0, deleted)
            assertEquals(listOf(700L, 2_000L), dao.inserted.map { it.postedAtMillis })
            assertEquals(emptySet<Long>(), dao.sourceGapDays)
        }

    @Test
    fun `legacy event day uses the supplied local zone before deletion`() =
        runTest {
            val dao = FakeNotificationEventDao()
            // 1970-01-01T23:30Z is the next local day in UTC+09.
            dao.insert(event(postedAtMillis = 23L * 60 * 60 * 1_000 + 30L * 60 * 1_000, epochDay = null))

            dao.deleteOlderThanWithSourceGaps(Long.MAX_VALUE, ZoneOffset.ofHours(9))

            assertEquals(setOf(1L), dao.sourceGapDays)
        }

    private fun event(
        postedAtMillis: Long,
        epochDay: Long?,
        undone: Boolean = false,
    ) = NotificationEventEntity(
        packageName = "com.example",
        category = null,
        postedAtMillis = postedAtMillis,
        postedEpochDay = epochDay,
        action = StoredRuleAction.KEEP,
        matchedRuleId = null,
        recordedAtMillis = postedAtMillis,
        undone = undone,
    )

    private fun trace() =
        NotificationDecisionTraceEntity(
            eventId = 0,
            lane = "ACTIVE",
            position = 0,
            depth = 0,
            conditionKind = "PACKAGE",
            result = "MATCH",
        )
}
