package com.alarmcontrol.data.repository

import app.cash.turbine.test
import com.alarmcontrol.core.filtering.ActionKind
import com.alarmcontrol.core.filtering.ConditionResult
import com.alarmcontrol.core.filtering.DecisionConditionKind
import com.alarmcontrol.core.filtering.DecisionTraceLane
import com.alarmcontrol.core.filtering.DecisionTraceNode
import com.alarmcontrol.core.filtering.NotificationContent
import com.alarmcontrol.core.filtering.NotificationContentState
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.NotificationHistoryQuery
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.insights.ActionBreakdown
import com.alarmcontrol.data.db.model.StoredRuleAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEventRepositoryImplTest {
    private val dao = FakeNotificationEventDao()
    private val cipher = FakeNotificationContentCipher()
    private val repository =
        NotificationEventRepositoryImpl(
            dao,
            cipher,
            Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC),
            Dispatchers.Unconfined,
        )

    private fun event(
        action: RuleAction = RuleAction.Cancel,
        matchedRuleId: String? = "42",
        recordedAtMillis: Long = 2_000L,
        packageName: String = "com.example.clock",
        mlCategory: String? = null,
    ) = NotificationEvent(
        packageName = packageName,
        mlCategory = mlCategory,
        category = "alarm",
        postedAtMillis = 1_000L,
        action = action,
        matchedRuleId = matchedRuleId,
        recordedAtMillis = recordedAtMillis,
    )

    @Test
    fun `record maps the domain event and inserts a single row`() =
        runTest {
            repository.record(event(action = RuleAction.Snooze(60_000L), mlCategory = "alarm"))

            assertEquals(1, dao.inserted.size)
            val entity = dao.inserted.single()
            assertEquals("com.example.clock", entity.packageName)
            assertEquals("alarm", entity.mlCategory)
            assertEquals(StoredRuleAction.SNOOZE, entity.action)
            assertEquals(42L, entity.matchedRuleId)
            assertEquals(2_000L, entity.recordedAtMillis)
        }

    @Test
    fun `encrypted content is stored separately and decrypted only for detail`() =
        runTest {
            val id =
                repository.record(
                    event(),
                    NotificationContent(title = "Bank alert", text = "Withdrawal completed"),
                )

            val recent = repository.observeRecent(1).first().single()
            val detail = repository.getDetail(id)

            assertTrue(recent.hadEncryptedContent)
            assertEquals(
                NotificationContentState.Available("Bank alert", "Withdrawal completed"),
                detail?.content,
            )
        }

    @Test
    fun `encryption failure degrades to metadata without failing event recording`() =
        runTest {
            cipher.failEncryption = true

            val id = repository.record(event(), NotificationContent("secret", "body"))

            assertEquals(NotificationContentState.NotStored, repository.getDetail(id)?.content)
            assertEquals(1, dao.countAll())
        }

    @Test
    fun `missing encrypted row is reported as expired`() =
        runTest {
            val id = repository.record(event(), NotificationContent("title", "body"))

            repository.purgeEncryptedContentOlderThan(Long.MAX_VALUE)

            assertEquals(NotificationContentState.Expired, repository.getDetail(id)?.content)
        }

    @Test
    fun `content older than seven days is rejected before decryption`() =
        runTest {
            val expiringRepository =
                NotificationEventRepositoryImpl(
                    dao,
                    cipher,
                    Clock.fixed(Instant.ofEpochMilli(8L * 24 * 60 * 60 * 1_000), ZoneOffset.UTC),
                    Dispatchers.Unconfined,
                )
            val id =
                expiringRepository.record(
                    event(recordedAtMillis = 0),
                    NotificationContent("old title", "old body"),
                )

            assertEquals(NotificationContentState.Expired, expiringRepository.getDetail(id)?.content)
        }

    @Test
    fun `unavailable key reports unreadable content without exposing plaintext`() =
        runTest {
            val id = repository.record(event(), NotificationContent("title", "body"))
            cipher.failDecryption = true

            assertEquals(NotificationContentState.Unreadable, repository.getDetail(id)?.content)
        }

    @Test
    fun `trimToMostRecent keeps only the newest events`() =
        runTest {
            listOf(1_000L, 2_000L, 3_000L, 4_000L).forEach { repository.record(event(recordedAtMillis = it)) }

            val removed = repository.trimToMostRecent(max = 2)

            assertEquals(2, removed)
            assertEquals(listOf(4_000L, 3_000L), repository.observeRecent(10).first().map { it.recordedAtMillis })
        }

    @Test
    fun `observeRecent maps stored rows back to domain newest-first`() =
        runTest {
            repository.record(event(recordedAtMillis = 1_000L))
            repository.record(event(recordedAtMillis = 3_000L))

            val recent = repository.observeRecent(limit = 10).first()

            assertEquals(listOf(3_000L, 1_000L), recent.map { it.recordedAtMillis })
            assertTrue(recent.all { it.packageName == "com.example.clock" })
        }

    @Test
    fun `record round-trips channel confidence monitor decision and content-free trace`() =
        runTest {
            val input =
                event().copy(
                    channelId = "offers",
                    mlConfidence = 0.81f,
                    monitoredRuleId = "9",
                    monitoredAction = RuleAction.Cancel,
                    decisionTrace =
                        listOf(
                            DecisionTraceNode(
                                DecisionTraceLane.MONITOR,
                                0,
                                0,
                                DecisionConditionKind.SEMANTIC_INTENT,
                                ConditionResult.MATCH,
                            ),
                        ),
                )

            repository.record(input)
            val stored = repository.observeRecent(1).first().single()

            assertEquals("offers", stored.channelId)
            assertEquals(0.81f, stored.mlConfidence)
            assertEquals("9", stored.monitoredRuleId)
            assertEquals(RuleAction.Cancel, stored.monitoredAction)
            assertEquals(input.decisionTrace, stored.decisionTrace)
        }

    @Test
    fun `rate history exposes only content-free metadata since cutoff`() =
        runTest {
            repository.record(event(recordedAtMillis = 1_000).copy(channelId = "old", postedAtMillis = 1_000))
            repository.record(event(recordedAtMillis = 2_000).copy(channelId = "new", postedAtMillis = 2_000))

            val history = repository.rateHistorySince(1_500)

            assertEquals(1, history.size)
            assertEquals("new", history.single().channelId)
            assertEquals("com.example.clock", history.single().packageName)
        }

    @Test
    fun `history search treats SQL wildcard characters as literal text`() =
        runTest {
            repository.record(
                event(packageName = "com.example.percent%app").copy(postedAtMillis = 2_000),
            )
            repository.record(
                event(packageName = "com.example.normal").copy(postedAtMillis = 2_100),
            )

            val page =
                repository
                    .observeHistory(
                        NotificationHistoryQuery(
                            startMillis = 0,
                            endMillis = Long.MAX_VALUE,
                            search = "%",
                        ),
                    ).first()

            assertEquals(listOf("com.example.percent%app"), page.items.map { it.packageName })
        }

    @Test
    fun `count excludes undone events`() =
        runTest {
            repository.record(event(action = RuleAction.Cancel))
            repository.record(event(action = RuleAction.Cancel))

            repository.countByActionSince(ActionKind.CANCEL, sinceMillis = 0L).test {
                assertEquals(2, awaitItem())

                val firstId =
                    repository
                        .observeRecent(10)
                        .first()
                        .first()
                        .id
                repository.undo(firstId)

                assertEquals(1, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `action breakdown reacts to inserts and undo using one grouped stream`() =
        runTest {
            repository.observeActionBreakdownSince(sinceMillis = 1_500L).test {
                assertEquals(ActionBreakdown(), awaitItem())

                repository.record(event(action = RuleAction.Cancel, recordedAtMillis = 2_000L))
                assertEquals(ActionBreakdown(cancelled = 1), awaitItem())

                repository.record(event(action = RuleAction.Snooze(60_000L), recordedAtMillis = 2_100L))
                assertEquals(ActionBreakdown(cancelled = 1, snoozed = 1), awaitItem())

                repository.record(event(action = RuleAction.MarkRead, recordedAtMillis = 2_200L))
                assertEquals(ActionBreakdown(cancelled = 1, snoozed = 1, loggedOnly = 1), awaitItem())

                repository.record(event(action = RuleAction.Keep, recordedAtMillis = 2_300L))
                assertEquals(
                    ActionBreakdown(cancelled = 1, snoozed = 1, loggedOnly = 1, kept = 1),
                    awaitItem(),
                )

                val newestId =
                    repository
                        .observeRecent(10)
                        .first()
                        .first()
                        .id
                repository.undo(newestId)
                assertEquals(ActionBreakdown(cancelled = 1, snoozed = 1, loggedOnly = 1), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `purge deletes events older than the cutoff`() =
        runTest {
            repository.record(event(recordedAtMillis = 1_000L))
            repository.record(event(recordedAtMillis = 5_000L))

            val deleted = repository.purgeEventsOlderThan(3_000L)

            assertEquals(1, deleted)
            assertEquals(listOf(5_000L), repository.observeRecent(10).first().map { it.recordedAtMillis })
        }

    @Test
    fun `muted counts include only cancel and snooze`() =
        runTest {
            repository.record(event(packageName = "com.a", action = RuleAction.Cancel, recordedAtMillis = 2_000L))
            repository.record(
                event(packageName = "com.a", action = RuleAction.Snooze(60_000L), recordedAtMillis = 2_500L),
            )
            repository.record(event(packageName = "com.b", action = RuleAction.Keep, recordedAtMillis = 2_700L))
            repository.record(event(packageName = "com.b", action = RuleAction.MarkRead, recordedAtMillis = 2_800L))

            val counts = repository.mutedCountsByPackageBetween(startMillis = 0L, endMillis = 10_000L)

            assertEquals(mapOf("com.a" to 2), counts)
        }
}
