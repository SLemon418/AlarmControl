package com.alarmcontrol.core.filtering

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationHistoryWriteFenceTest {
    @Test
    fun `successful deletion rejects old committed work and accepts a new notification`() =
        runTest {
            val fence = NotificationHistoryWriteFence()
            val beforeDeletion = fence.captureEpoch()

            fence.deleteAndAdvanceOnCommit { committed -> committed() }

            assertNull(fence.writeIfCurrent(beforeDeletion) { "stale" })
            assertEquals(
                "fresh",
                fence.writeIfCurrent(fence.captureEpoch()) { "fresh" },
            )
        }

    @Test
    fun `deletion waits for an in-flight write and advances only after it completes`() =
        runTest {
            val fence = NotificationHistoryWriteFence()
            val epoch = fence.captureEpoch()
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            val write =
                async {
                    fence.writeIfCurrent(epoch) {
                        writeStarted.complete(Unit)
                        releaseWrite.await()
                        "stored"
                    }
                }
            writeStarted.await()
            var deleted = false
            val deletion =
                async {
                    fence.deleteAndAdvanceOnCommit { committed ->
                        deleted = true
                        committed()
                    }
                }

            runCurrent()
            assertFalse(deleted)

            releaseWrite.complete(Unit)
            assertEquals("stored", write.await())
            deletion.await()
            assertTrue(deleted)
            assertNull(fence.writeIfCurrent(epoch) { "late" })
        }

    @Test
    fun `failed deletion leaves the current generation writable`() =
        runTest {
            val fence = NotificationHistoryWriteFence()
            val epoch = fence.captureEpoch()

            runCatching {
                fence.deleteAndAdvanceOnCommit<Unit> { error("delete failed") }
            }

            assertEquals("still-current", fence.writeIfCurrent(epoch) { "still-current" })
        }

    @Test
    fun `post commit failure still rejects work captured before deletion`() =
        runTest {
            val fence = NotificationHistoryWriteFence()
            val epoch = fence.captureEpoch()

            val failure =
                runCatching {
                    fence.deleteAndAdvanceOnCommit<Unit> { committed ->
                        committed()
                        error("key deletion failed")
                    }
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertNull(fence.writeIfCurrent(epoch) { "stale" })
        }
}
