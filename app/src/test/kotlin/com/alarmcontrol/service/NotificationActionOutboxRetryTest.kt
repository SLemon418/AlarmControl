package com.alarmcontrol.service

import com.alarmcontrol.core.filtering.NotificationActionOutbox
import com.alarmcontrol.core.filtering.NotificationContent
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.StagedNotificationAction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationActionOutboxRetryTest {
    @Test
    fun `transient promotion failures retry and return one durable event id`() =
        runTest {
            var attempts = 0

            val eventId =
                promoteStagedActionWithRetry(StagedNotificationAction("token")) {
                    attempts += 1
                    if (attempts < 3) throw IOException("transient")
                    "17"
                }

            assertEquals("17", eventId)
            assertEquals(3, attempts)
        }

    @Test
    fun `retry operation remains bounded after persistent failure`() =
        runTest {
            var attempts = 0

            val result =
                retryActionOutboxOperation(
                    maxAttempts = 3,
                    initialDelayMillis = 0,
                ) {
                    attempts += 1
                    throw IOException("persistent")
                }

            assertTrue(result.exceptionOrNull() is IOException)
            assertEquals(3, attempts)
        }

    @Test
    fun `arm failure never invokes platform action`() {
        val outbox = mockk<NotificationActionOutbox>()
        val staged = StagedNotificationAction("token")
        every { outbox.arm(staged) } returns false
        var platformActions = 0

        val armed = armStagedActionAndRun(outbox, staged) { platformActions += 1 }

        assertTrue(!armed)
        assertEquals(0, platformActions)
    }

    @Test
    fun `cancelled token cleanup discards unarmed stage in non cancellable context`() =
        runTest {
            val outbox = mockk<NotificationActionOutbox>()
            val staged = StagedNotificationAction("token")
            coEvery { outbox.discard(staged) } coAnswers {
                delay(1)
            }
            val job =
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        discardUnarmedStagedAction(outbox, staged, armed = false)
                    }
                }
            runCurrent()

            job.cancelAndJoin()

            coVerify(exactly = 1) { outbox.discard(staged) }
        }

    @Test
    fun `action throw after durable arm preserves row for startup promotion`() =
        runTest {
            val outbox = InMemoryActionOutbox()
            val staged = StagedNotificationAction("token")
            var armed = false

            runCatching {
                try {
                    armStagedActionAndRun(
                        outbox = outbox,
                        staged = staged,
                        onArmed = { armed = true },
                    ) {
                        throw IOException("Binder failure")
                    }
                } finally {
                    discardUnarmedStagedAction(outbox, staged, armed)
                }
            }

            assertTrue(outbox.armed)
            assertEquals(1, outbox.recover())
            assertTrue(outbox.promoted)
        }

    @Test
    fun `startup recovery keeps retrying beyond three transient failures`() =
        runTest {
            var attempts = 0

            val recovered =
                recoverActionOutboxUntilSuccess(
                    isCurrent = { true },
                    recover = {
                        attempts += 1
                        if (attempts < 5) throw IOException("transient")
                        1
                    },
                )

            assertTrue(recovered)
            assertEquals(5, attempts)
        }

    @Test
    fun `concurrent recovery requests install one newest cancellable job`() =
        runTest {
            val activeRecoveries = AtomicInteger()
            val ready = AtomicBoolean()
            val coordinator =
                ActionOutboxRecoveryCoordinator(
                    scope = this,
                    recoverStartup = { 0 },
                    recoverArmed = {
                        activeRecoveries.incrementAndGet()
                        try {
                            awaitCancellation()
                        } finally {
                            activeRecoveries.decrementAndGet()
                        }
                    },
                    publishReady = ready::set,
                )

            coroutineScope {
                List(100) {
                    async(Dispatchers.Default) {
                        coordinator.request(discardUnarmed = false)
                    }
                }.awaitAll()
            }
            runCurrent()

            assertEquals(1, activeRecoveries.get())
            coordinator.stop()
            runCurrent()

            assertEquals(0, activeRecoveries.get())
            assertTrue(!ready.get())
        }

    @Test
    fun `new recovery cannot be overwritten by stale ready publication`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ready = AtomicBoolean()
        val staleReadyEntered = CountDownLatch(1)
        val releaseStaleReady = CountDownLatch(1)
        val replacementReturned = CountDownLatch(1)
        val replacementRecoveryStarted = CountDownLatch(1)
        lateinit var coordinator: ActionOutboxRecoveryCoordinator
        coordinator =
            ActionOutboxRecoveryCoordinator(
                scope = scope,
                recoverStartup = { 0 },
                recoverArmed = {
                    replacementRecoveryStarted.countDown()
                    awaitCancellation()
                },
                publishReady = { value ->
                    if (value) {
                        staleReadyEntered.countDown()
                        check(releaseStaleReady.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                    ready.set(value)
                },
            )

        try {
            coordinator.request(discardUnarmed = true)
            assertTrue(staleReadyEntered.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val replacement =
                thread(name = "replacement-outbox-recovery") {
                    coordinator.request(discardUnarmed = false)
                    replacementReturned.countDown()
                }

            assertFalse(replacementReturned.await(100, TimeUnit.MILLISECONDS))
            releaseStaleReady.countDown()
            assertTrue(replacementReturned.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(replacementRecoveryStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            replacement.join()
            assertFalse(ready.get())
        } finally {
            releaseStaleReady.countDown()
            coordinator.stop()
            scope.cancel()
        }
    }

    private class InMemoryActionOutbox : NotificationActionOutbox {
        var armed = false
        var promoted = false

        override suspend fun stage(
            event: NotificationEvent,
            content: NotificationContent?,
        ): StagedNotificationAction = StagedNotificationAction("token")

        override fun arm(staged: StagedNotificationAction): Boolean {
            armed = true
            return true
        }

        override suspend fun promote(staged: StagedNotificationAction): String? {
            if (!armed) return null
            promoted = true
            armed = false
            return "1"
        }

        override suspend fun discard(staged: StagedNotificationAction) {
            armed = false
        }

        override suspend fun recover(): Int = if (promote(StagedNotificationAction("token")) != null) 1 else 0

        override suspend fun recoverArmed(): Int = recover()
    }

    private companion object {
        const val TEST_TIMEOUT_SECONDS = 5L
    }
}
