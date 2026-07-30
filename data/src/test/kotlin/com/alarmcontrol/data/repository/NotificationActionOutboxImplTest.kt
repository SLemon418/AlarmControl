package com.alarmcontrol.data.repository

import com.alarmcontrol.core.filtering.ConditionResult
import com.alarmcontrol.core.filtering.DecisionConditionKind
import com.alarmcontrol.core.filtering.DecisionTraceLane
import com.alarmcontrol.core.filtering.DecisionTraceNode
import com.alarmcontrol.core.filtering.NotificationContent
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.data.db.dao.PendingNotificationActionDao
import com.alarmcontrol.data.db.relation.PendingNotificationActionRelation
import com.alarmcontrol.data.mapper.toEntity
import com.alarmcontrol.data.security.NotificationContentAccessGuard
import com.alarmcontrol.data.security.StoredNotificationContentCleaner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationActionOutboxImplTest {
    private val pending = FakePendingNotificationActionDao()
    private val events = FakeNotificationEventDao()
    private val cipher = FakeNotificationContentCipher()
    private val contentGuard = NotificationContentAccessGuard()
    private val settings = FakeContentSettingsRepository(contentAccessGuard = contentGuard)
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW_MILLIS), ZoneOffset.UTC)
    private val outbox = outbox(pending, clock)

    @Test
    fun `stage stores only encrypted detail and does not create history`() =
        runTest {
            val staged =
                outbox.stage(
                    event = event(),
                    content = NotificationContent("private title", "private body"),
                )

            val relation = requireNotNull(pending.relation(staged.value))
            val encrypted = relation.contents.single()
            assertFalse(encrypted.ciphertext.toString(Charsets.UTF_8).contains("private"))
            assertEquals(0, events.countAll())
            assertFalse(relation.action.armed)
        }

    @Test
    fun `armed action promotes event trace and encrypted detail atomically`() =
        runTest {
            val staged = outbox.stage(event(), NotificationContent("title", "body"))

            assertTrue(outbox.arm(staged))
            val eventId = requireNotNull(outbox.promote(staged)).toLong()

            assertEquals(0, pending.actionCount())
            val detail = requireNotNull(events.getDetail(eventId))
            assertEquals("com.example.clock", detail.event.packageName)
            assertEquals(1, detail.trace.size)
            assertTrue(detail.event.hadEncryptedContent)
            assertTrue(detail.encryptedContent != null)
        }

    @Test
    fun `startup recovery discards unarmed stage and promotes armed attempt without an action callback`() =
        runTest {
            outbox.stage(event(packageName = "com.example.unarmed"))
            val armed = outbox.stage(event(packageName = "com.example.armed"))
            assertTrue(outbox.arm(armed))
            var platformActions = 0

            val recovered = outbox.recover()

            assertEquals(1, recovered)
            assertEquals(0, platformActions)
            assertEquals(0, pending.actionCount())
            assertEquals(1, events.countAll())
        }

    @Test
    fun `live armed recovery leaves concurrent unarmed staging untouched`() =
        runTest {
            val unarmed = outbox.stage(event(packageName = "com.example.live"))
            val armed = outbox.stage(event(packageName = "com.example.failed-promotion"))
            assertTrue(outbox.arm(armed))

            val recovered = outbox.recoverArmed()

            assertEquals(1, recovered)
            assertTrue(pending.relation(unarmed.value) != null)
            assertEquals(1, pending.actionCount())
            assertEquals(1, events.countAll())
        }

    @Test
    fun `recovery and delayed live promotion share one durable event receipt`() =
        runTest {
            val staged = outbox.stage(event(), null)
            assertTrue(outbox.arm(staged))

            assertEquals(1, outbox.recoverArmed())
            val delayedLiveResult = requireNotNull(outbox.promote(staged))

            assertEquals("1", delayedLiveResult)
            assertEquals(1, events.countAll())
            assertEquals(0, pending.actionCount())
        }

    @Test
    fun `promotion timestamp keeps a delayed action inside a full history cap`() =
        runTest {
            repeat(10_000) { index ->
                events.insert(
                    event(
                        packageName = "com.example.existing.$index",
                        recordedAtMillis = NOW_MILLIS - 10_000 + index,
                    ).toEntity(),
                )
            }
            val staged =
                outbox.stage(
                    event(
                        packageName = "com.example.delayed",
                        postedAtMillis = NOW_MILLIS + 50_000,
                        recordedAtMillis = 1,
                    ),
                    null,
                )
            assertTrue(outbox.arm(staged))

            val eventId = requireNotNull(outbox.promote(staged)).toLong()

            assertEquals(10_000, events.countAll())
            val promoted = requireNotNull(events.getDetail(eventId)).event
            assertEquals("com.example.delayed", promoted.packageName)
            assertEquals(NOW_MILLIS + 50_000, promoted.postedAtMillis)
            assertEquals(NOW_MILLIS, promoted.recordedAtMillis)
            assertEquals(eventId.toString(), outbox.promote(staged))
        }

    @Test
    fun `promotion drops expired and future dated encrypted detail`() =
        runTest {
            val old =
                outbox.stage(
                    event(recordedAtMillis = NOW_MILLIS - EIGHT_DAYS_MILLIS),
                    content = NotificationContent("old", "old"),
                )
            val future =
                outbox.stage(
                    event(recordedAtMillis = NOW_MILLIS + 1),
                    content = NotificationContent("future", "future"),
                )
            assertTrue(outbox.arm(old))
            assertTrue(outbox.arm(future))

            val oldId = requireNotNull(outbox.promote(old)).toLong()
            val futureId = requireNotNull(outbox.promote(future)).toLong()

            assertNull(events.getDetail(oldId)?.encryptedContent)
            assertNull(events.getDetail(futureId)?.encryptedContent)
            assertFalse(requireNotNull(events.getDetail(oldId)).event.hadEncryptedContent)
            assertFalse(requireNotNull(events.getDetail(futureId)).event.hadEncryptedContent)
        }

    @Test
    fun `promotion keeps encrypted detail inside the configured retention period`() =
        runTest {
            val extendedSettings =
                FakeContentSettingsRepository(
                    contentRetentionDays = 14,
                    contentAccessGuard = contentGuard,
                )
            val extendedOutbox = outbox(pending, clock, extendedSettings)
            val staged =
                extendedOutbox.stage(
                    event(recordedAtMillis = NOW_MILLIS - EIGHT_DAYS_MILLIS),
                    content = NotificationContent("still retained", "body"),
                )
            assertTrue(extendedOutbox.arm(staged))

            val eventId = requireNotNull(extendedOutbox.promote(staged)).toLong()

            assertTrue(requireNotNull(events.getDetail(eventId)).event.hadEncryptedContent)
            assertTrue(events.getDetail(eventId)?.encryptedContent != null)
        }

    @Test
    fun `retention purge removes expired and future pending ciphertext`() =
        runTest {
            outbox.stage(
                event(recordedAtMillis = NOW_MILLIS - EIGHT_DAYS_MILLIS),
                content = NotificationContent("old", "old"),
            )
            outbox.stage(
                event(recordedAtMillis = NOW_MILLIS + 1),
                content = NotificationContent("future", "future"),
            )
            val history =
                NotificationEventRepositoryImpl(
                    eventDao = events,
                    pendingActionDao = pending,
                    contentCipher = cipher,
                    clock = clock,
                    settingsRepository = settings,
                    contentAccessGuard = contentGuard,
                    dailyInsightDao = FakeDailyInsightDao(),
                    transactionRunner = ImmediateTransactionRunner(),
                    ioDispatcher = Dispatchers.Unconfined,
                )

            val removed =
                history.purgeEncryptedContentOlderThan(
                    NOW_MILLIS - 7L * 24 * 60 * 60 * 1_000,
                )

            assertEquals(2, removed)
            assertEquals(0, pending.contentCount())
            assertEquals(2, pending.actionCount())
        }

    @Test
    fun `content clear cannot race promotion and resurrect ciphertext after key deletion`() =
        runTest {
            val delegate = FakePendingNotificationActionDao()
            val blocking = BlockingGetArmedPendingDao(delegate)
            val guardedOutbox = outbox(blocking, clock)
            val staged = guardedOutbox.stage(event(), NotificationContent("title", "body"))
            assertTrue(guardedOutbox.arm(staged))
            val cleaner =
                StoredNotificationContentCleaner(
                    ImmediateTransactionRunner(),
                    events,
                    blocking,
                    cipher,
                )

            val promotion = async { guardedOutbox.promote(staged) }
            blocking.readStarted.await()
            val clear =
                async {
                    contentGuard.withLock {
                        cleaner.clear()
                    }
                }
            runCurrent()
            assertFalse(clear.isCompleted)

            blocking.releaseRead.complete(Unit)
            val eventId = requireNotNull(promotion.await()).toLong()
            clear.await()

            assertNull(events.getDetail(eventId)?.encryptedContent)
            assertEquals(0, blocking.contentCount())
            assertTrue(cipher.keyDeleted)
        }

    @Test
    fun `package exclusion cleanup removes only matching pending ciphertext`() =
        runTest {
            val privateStage =
                outbox.stage(
                    event(packageName = "com.example.private"),
                    NotificationContent("private", "private"),
                )
            val publicStage =
                outbox.stage(
                    event(packageName = "com.example.public"),
                    NotificationContent("public", "public"),
                )
            val cleaner =
                StoredNotificationContentCleaner(
                    ImmediateTransactionRunner(),
                    events,
                    pending,
                    cipher,
                )

            contentGuard.withLock {
                cleaner.clearForPackage("com.example.private")
            }

            assertTrue(requireNotNull(pending.relation(privateStage.value)).contents.isEmpty())
            assertEquals(1, requireNotNull(pending.relation(publicStage.value)).contents.size)
        }

    @Test
    fun `unarmed crash residue is bounded to listener capacity`() =
        runTest {
            repeat(80) { index ->
                outbox.stage(event(packageName = "com.example.$index"))
            }

            assertEquals(64, pending.actionCount())
        }

    private fun outbox(
        pendingDao: PendingNotificationActionDao,
        actionClock: Clock,
        contentSettings: FakeContentSettingsRepository = settings,
    ): NotificationActionOutboxImpl =
        NotificationActionOutboxImpl(
            pendingDao = pendingDao,
            eventDao = events,
            contentCipher = cipher,
            settingsRepository = contentSettings,
            contentAccessGuard = contentGuard,
            transactionRunner = ImmediateTransactionRunner(),
            clock = actionClock,
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun event(
        packageName: String = "com.example.clock",
        postedAtMillis: Long = NOW_MILLIS,
        recordedAtMillis: Long = NOW_MILLIS,
    ): NotificationEvent =
        NotificationEvent(
            packageName = packageName,
            category = "alarm",
            postedAtMillis = postedAtMillis,
            action = RuleAction.Cancel,
            matchedRuleId = "42",
            recordedAtMillis = recordedAtMillis,
            decisionTrace =
                listOf(
                    DecisionTraceNode(
                        lane = DecisionTraceLane.ACTIVE,
                        position = 0,
                        depth = 0,
                        kind = DecisionConditionKind.PACKAGE,
                        result = ConditionResult.MATCH,
                    ),
                ),
        )

    private class BlockingGetArmedPendingDao(
        private val delegate: FakePendingNotificationActionDao,
    ) : PendingNotificationActionDao by delegate {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()

        override suspend fun getArmed(token: String): PendingNotificationActionRelation? {
            readStarted.complete(Unit)
            releaseRead.await()
            return delegate.getArmed(token)
        }

        fun contentCount(): Int = delegate.contentCount()
    }

    private companion object {
        const val NOW_MILLIS = 10L * 24 * 60 * 60 * 1_000
        const val EIGHT_DAYS_MILLIS = 8L * 24 * 60 * 60 * 1_000
    }
}
