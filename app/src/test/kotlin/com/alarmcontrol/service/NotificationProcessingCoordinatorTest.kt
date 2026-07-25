package com.alarmcontrol.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationProcessingCoordinatorTest {
    @Test
    fun `newer post for the same key is the only result allowed to commit`() =
        runTest {
            val coordinator = NotificationProcessingCoordinator(this)
            val firstStarted = CompletableDeferred<Unit>()
            val firstRelease = CompletableDeferred<Unit>()
            val committed = mutableListOf<String>()

            coordinator.submit("same") { token ->
                firstStarted.complete(Unit)
                firstRelease.await()
                token.commit { committed += "old" }
            }
            firstStarted.await()
            coordinator.submit("same") { token -> token.commit { committed += "new" } }
            firstRelease.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("new"), committed)
        }

    @Test
    fun `removal invalidates pending work before it can commit`() =
        runTest {
            val coordinator = NotificationProcessingCoordinator(this)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var committed = false

            coordinator.submit("removed") { token ->
                started.complete(Unit)
                release.await()
                committed = token.commit {}
            }
            started.await()
            coordinator.invalidate("removed")
            release.complete(Unit)
            advanceUntilIdle()

            assertFalse(committed)
        }

    @Test
    fun `different notification keys are independent`() =
        runTest {
            val coordinator = NotificationProcessingCoordinator(this)
            val committed = mutableSetOf<String>()

            coordinator.submit("one") { token -> token.commit { committed += "one" } }
            coordinator.submit("two") { token -> token.commit { committed += "two" } }
            advanceUntilIdle()

            assertEquals(setOf("one", "two"), committed)
        }

    @Test
    fun `a token commits exactly once`() =
        runTest {
            val coordinator = NotificationProcessingCoordinator(this)
            val outcomes = mutableListOf<Boolean>()

            coordinator.submit("once") { token ->
                outcomes += token.commit {}
                outcomes += token.commit {}
            }
            advanceUntilIdle()

            assertEquals(listOf(true, false), outcomes)
        }

    @Test
    fun `disconnect invalidates old work while allowing work after reconnect`() =
        runTest {
            val coordinator = NotificationProcessingCoordinator(this)
            lateinit var oldToken: NotificationProcessingCoordinator.ProcessingToken
            coordinator.submit("key") { token -> oldToken = token }
            advanceUntilIdle()
            coordinator.invalidateAll()

            var newCommitted = false
            coordinator.submit("key") { token -> newCommitted = token.commit {} }
            advanceUntilIdle()

            assertFalse(oldToken.isCurrent())
            assertTrue(newCommitted)
        }

    @Test
    fun `queue evicts the oldest waiting work when its bound is reached`() =
        runTest {
            val coordinator =
                NotificationProcessingCoordinator(
                    scope = this,
                    maxTrackedWork = 3,
                    maxConcurrentWork = 1,
                )
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val committed = mutableListOf<String>()

            coordinator.submit("running") { token ->
                firstStarted.complete(Unit)
                releaseFirst.await()
                token.commit { committed += "running" }
            }
            firstStarted.await()
            coordinator.submit("oldest-waiting") { token ->
                token.commit { committed += "oldest-waiting" }
            }
            coordinator.submit("newer-waiting") { token ->
                token.commit { committed += "newer-waiting" }
            }
            coordinator.submit("newest") { token ->
                token.commit { committed += "newest" }
            }
            runCurrent()
            releaseFirst.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("running", "newer-waiting", "newest"), committed)
            assertEquals(1L, coordinator.droppedSubmissionCount)
        }

    @Test
    fun `only the configured number of distinct keys run concurrently`() =
        runTest {
            val coordinator =
                NotificationProcessingCoordinator(
                    scope = this,
                    maxTrackedWork = 8,
                    maxConcurrentWork = 2,
                )
            val release = CompletableDeferred<Unit>()
            var running = 0
            var maximumRunning = 0

            repeat(6) { index ->
                coordinator.submit("key-$index") { token ->
                    running += 1
                    maximumRunning = maxOf(maximumRunning, running)
                    release.await()
                    running -= 1
                    token.commit {}
                }
            }
            runCurrent()
            assertEquals(2, maximumRunning)

            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, maximumRunning)
        }

    @Test
    fun `invalidating all generations rejects a late commit`() =
        runTest {
            val coordinator = NotificationProcessingCoordinator(this)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var committed = false

            coordinator.submit("old-generation") { token ->
                started.complete(Unit)
                release.await()
                committed = token.commit {}
            }
            started.await()
            coordinator.invalidateAll()
            release.complete(Unit)
            advanceUntilIdle()

            assertFalse(committed)
        }
}
