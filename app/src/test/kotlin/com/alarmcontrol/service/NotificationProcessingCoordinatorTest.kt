package com.alarmcontrol.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
    fun `same key submission cannot overtake an in progress platform action`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = NotificationProcessingCoordinator(scope)
        val oldActionStarted = CountDownLatch(1)
        val releaseOldAction = CountDownLatch(1)
        val submitReturned = CountDownLatch(1)
        val newActionCommitted = CountDownLatch(1)

        try {
            coordinator.submit("same", freshness = 1) { token ->
                token.commit {
                    oldActionStarted.countDown()
                    check(releaseOldAction.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                }
            }
            assertTrue(oldActionStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val submitThread =
                thread(name = "same-key-submit") {
                    coordinator.submit("same", freshness = 2) { token ->
                        token.commit(newActionCommitted::countDown)
                    }
                    submitReturned.countDown()
                }
            assertTrue(
                awaitThreadBlockedOrReturned(
                    thread = submitThread,
                    returned = submitReturned,
                ),
            )
            assertEquals(Thread.State.BLOCKED, submitThread.state)

            releaseOldAction.countDown()
            assertTrue(submitReturned.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(newActionCommitted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            submitThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
            assertFalse(submitThread.isAlive)
        } finally {
            releaseOldAction.countDown()
            scope.cancel()
        }
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
    fun `late callback for an older post cannot evict fresher waiting work`() =
        runTest {
            val coordinator =
                NotificationProcessingCoordinator(
                    scope = this,
                    maxTrackedWork = 3,
                    maxConcurrentWork = 1,
                )
            val runningStarted = CompletableDeferred<Unit>()
            val releaseRunning = CompletableDeferred<Unit>()
            val committed = mutableListOf<String>()

            coordinator.submit("running", freshness = 0) { token ->
                runningStarted.complete(Unit)
                releaseRunning.await()
                token.commit { committed += "running" }
            }
            runningStarted.await()
            coordinator.submit("newest", freshness = 30) { token ->
                token.commit { committed += "newest" }
            }
            coordinator.submit("newer", freshness = 20) { token ->
                token.commit { committed += "newer" }
            }
            coordinator.submit("late-old", freshness = 10) { token ->
                token.commit { committed += "late-old" }
            }

            releaseRunning.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("running", "newest", "newer"), committed)
            assertEquals(1L, coordinator.droppedSubmissionCount)
        }

    @Test
    fun `older callback for the same key cannot replace a newer post`() =
        runTest {
            val coordinator = NotificationProcessingCoordinator(this)
            val newerStarted = CompletableDeferred<Unit>()
            val releaseNewer = CompletableDeferred<Unit>()
            val committed = mutableListOf<String>()

            coordinator.submit("same", freshness = 20) { token ->
                newerStarted.complete(Unit)
                releaseNewer.await()
                token.commit { committed += "newer" }
            }
            newerStarted.await()
            coordinator.submit("same", freshness = 10) { token ->
                token.commit { committed += "older" }
            }
            releaseNewer.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("newer"), committed)
            assertEquals(1L, coordinator.droppedSubmissionCount)
        }

    @Test
    fun `overflow never evicts work that is already running`() =
        runTest {
            val coordinator =
                NotificationProcessingCoordinator(
                    scope = this,
                    maxTrackedWork = 1,
                    maxConcurrentWork = 1,
                )
            val runningStarted = CompletableDeferred<Unit>()
            val releaseRunning = CompletableDeferred<Unit>()
            val committed = mutableListOf<String>()

            coordinator.submit("running", freshness = 1) { token ->
                runningStarted.complete(Unit)
                releaseRunning.await()
                token.commit { committed += "running" }
            }
            runningStarted.await()
            coordinator.submit("overflow", freshness = 2) { token ->
                token.commit { committed += "overflow" }
            }
            releaseRunning.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("running"), committed)
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
    fun `bounded post-commit work cannot hold permits needed by a fifth active action`() =
        runTest {
            val coordinator =
                NotificationProcessingCoordinator(
                    scope = this,
                    maxTrackedWork = 8,
                    maxConcurrentWork = 4,
                )
            val postCommitStarted = CompletableDeferred<Unit>()
            val releasePostCommit = CompletableDeferred<Unit>()
            val postCommitQueue =
                SemanticObservationQueue<Int>(this) {
                    postCommitStarted.complete(Unit)
                    releasePostCommit.await()
                }
            val committed = mutableSetOf<Int>()

            try {
                repeat(4) { index ->
                    coordinator.submit("key-$index") { token ->
                        if (token.commit { committed += index }) {
                            postCommitQueue.offer(index)
                        }
                    }
                }
                postCommitStarted.await()
                runCurrent()
                assertTrue((0..3).all { it in committed })

                coordinator.submit("fifth") { token ->
                    if (token.commit { committed += 4 }) {
                        postCommitQueue.offer(4)
                    }
                }
                runCurrent()

                assertTrue(4 in committed)
            } finally {
                releasePostCommit.complete(Unit)
                postCommitQueue.close()
            }
            advanceUntilIdle()
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

    @Test
    fun `state publication and generation invalidation are one boundary`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = NotificationProcessingCoordinator(scope)
        val updateStarted = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val updateReturned = CountDownLatch(1)
        val submitReturned = CountDownLatch(1)
        val newActionCommitted = CountDownLatch(1)
        var state = "old"
        var observedState: String? = null

        try {
            val updateThread =
                thread(name = "listener-state-update") {
                    coordinator.invalidateAllAndUpdate {
                        updateStarted.countDown()
                        check(releaseUpdate.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        state = "new"
                    }
                    updateReturned.countDown()
                }
            assertTrue(updateStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val submitThread =
                thread(name = "post-during-state-update") {
                    coordinator.submit("new-generation") { token ->
                        observedState = state
                        token.commit(newActionCommitted::countDown)
                    }
                    submitReturned.countDown()
                }
            assertTrue(
                awaitThreadBlockedOrReturned(
                    thread = submitThread,
                    returned = submitReturned,
                ),
            )
            assertEquals(Thread.State.BLOCKED, submitThread.state)

            releaseUpdate.countDown()
            assertTrue(updateReturned.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(submitReturned.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(newActionCommitted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("new", observedState)
            updateThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
            submitThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
            assertFalse(updateThread.isAlive)
            assertFalse(submitThread.isAlive)
        } finally {
            releaseUpdate.countDown()
            scope.cancel()
        }
    }

    private fun awaitThreadBlockedOrReturned(
        thread: Thread,
        returned: CountDownLatch,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEST_TIMEOUT_SECONDS)
        while (
            thread.state != Thread.State.BLOCKED &&
            returned.count > 0 &&
            System.nanoTime() < deadline
        ) {
            Thread.yield()
        }
        return thread.state == Thread.State.BLOCKED || returned.count == 0L
    }

    private companion object {
        const val TEST_TIMEOUT_SECONDS = 5L
    }
}
