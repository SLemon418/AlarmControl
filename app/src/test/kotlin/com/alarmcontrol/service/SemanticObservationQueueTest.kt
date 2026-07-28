package com.alarmcontrol.service

import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.RuleAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SemanticObservationQueueTest {
    @Test
    fun `keeps one running and only the newest pending observation`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val handled = mutableListOf<Int>()
            val queue =
                SemanticObservationQueue<Int>(this) { value ->
                    if (value == 1) release.await()
                    handled += value
                }

            queue.offer(1)
            runCurrent()
            queue.offer(2)
            queue.offer(3)
            release.complete(Unit)
            queue.close()
            advanceUntilIdle()

            assertEquals(listOf(1, 3), handled)
        }

    @Test
    fun `one failed observation does not stop later local analytics`() =
        runTest {
            val handled = mutableListOf<Int>()
            val failures = mutableListOf<Throwable>()
            val queue =
                SemanticObservationQueue<Int>(
                    scope = this,
                    onFailure = failures::add,
                ) { value ->
                    if (value == 1) error("local database unavailable")
                    handled += value
                }

            queue.offer(1)
            runCurrent()
            queue.offer(2)
            queue.close()
            advanceUntilIdle()

            assertEquals(listOf(2), handled)
            assertTrue(failures.single() is IllegalStateException)
        }

    @Test
    fun `blocked enrichment cannot drop any active notification decision during a burst`() =
        runTest {
            val enrichmentStarted = CompletableDeferred<Unit>()
            val releaseEnrichment = CompletableDeferred<Unit>()
            val persisted = mutableListOf<NotificationEvent>()
            val enriched = mutableListOf<NotificationEvent>()
            val dispatcher =
                PostCommitWorkDispatcher<NotificationEvent, NotificationEvent>(
                    persistenceScope = this,
                    enrichmentScope = this,
                    persist = { decision ->
                        persisted += decision
                        decision
                    },
                    enrich = { decision ->
                        enrichmentStarted.complete(Unit)
                        releaseEnrichment.await()
                        enriched += decision
                    },
                )
            val decisions =
                (1..3).map { index ->
                    NotificationEvent(
                        packageName = "com.example.$index",
                        category = null,
                        postedAtMillis = index.toLong(),
                        action = RuleAction.Cancel,
                        matchedRuleId = index.toString(),
                        recordedAtMillis = index.toLong(),
                    )
                }

            dispatcher.submit(decisions[0])
            enrichmentStarted.await()
            dispatcher.submit(decisions[1])
            dispatcher.submit(decisions[2])
            runCurrent()

            assertEquals(decisions, persisted)
            assertFalse(releaseEnrichment.isCompleted)

            releaseEnrichment.complete(Unit)
            dispatcher.close()
            advanceUntilIdle()

            assertEquals(listOf(decisions[0], decisions[2]), enriched)
        }

    @Test
    fun `service cancellation cannot cancel handed-off persistence or replay enrichment`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val serviceJob = SupervisorJob()
            val applicationJob = SupervisorJob()
            val serviceScope = CoroutineScope(serviceJob + testDispatcher)
            val applicationScope = CoroutineScope(applicationJob + testDispatcher)
            val persistenceStarted = CompletableDeferred<Unit>()
            val releasePersistence = CompletableDeferred<Unit>()
            val persisted = mutableListOf<Int>()
            val enriched = mutableListOf<Int>()
            val dispatcher =
                PostCommitWorkDispatcher<Int, Int>(
                    persistenceScope = applicationScope,
                    enrichmentScope = serviceScope,
                    persist = { request ->
                        persistenceStarted.complete(Unit)
                        releasePersistence.await()
                        persisted += request
                        request
                    },
                    enrich = { request -> enriched += request },
                )

            val submission =
                serviceScope.launch {
                    dispatcher.submit(1)
                }
            runCurrent()
            persistenceStarted.await()

            serviceJob.cancel()
            dispatcher.clearPending()
            dispatcher.close()
            runCurrent()

            assertTrue(submission.isCancelled)
            releasePersistence.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(1), persisted)
            assertTrue(enriched.isEmpty())

            applicationScope.cancel()
        }

    @Test
    fun `persistence handoff never exceeds four concurrent writes`() =
        runTest {
            val releases = List(5) { CompletableDeferred<Unit>() }
            val started = mutableListOf<Int>()
            val dispatcher =
                PostCommitWorkDispatcher<Int, Unit>(
                    persistenceScope = this,
                    enrichmentScope = this,
                    persist = { request ->
                        started += request
                        releases[request - 1].await()
                        null
                    },
                    enrich = {},
                )
            val submissions =
                (1..5).map { request ->
                    launch {
                        dispatcher.submit(request)
                    }
                }

            runCurrent()

            assertEquals(listOf(1, 2, 3, 4), started)
            releases[0].complete(Unit)
            runCurrent()
            assertEquals(listOf(1, 2, 3, 4, 5), started)

            releases.drop(1).forEach { it.complete(Unit) }
            advanceUntilIdle()

            assertTrue(submissions.all { it.isCompleted })
            dispatcher.close()
        }

    @Test
    fun `persistence failure is reported and propagated to a live caller`() =
        runTest {
            val failure = IllegalStateException("local database unavailable")
            val failures = mutableListOf<Throwable>()
            val dispatcher =
                PostCommitWorkDispatcher<Int, Unit>(
                    persistenceScope = this,
                    enrichmentScope = this,
                    persist = { throw failure },
                    onPersistenceFailure = failures::add,
                    enrich = {},
                )

            val result = runCatching { dispatcher.submit(1) }

            val propagated = result.exceptionOrNull()
            assertTrue(propagated is IllegalStateException)
            assertEquals(failure.message, propagated?.message)
            assertTrue(failures.single() === failure)
            dispatcher.close()
        }
}
