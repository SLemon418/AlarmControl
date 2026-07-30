package com.alarmcontrol.service

import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.RuleAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `blocked enrichment preserves every middle decision during a burst`() =
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

            dispatcher.submit(requireNotNull(dispatcher.tryReserve()), decisions[0])
            enrichmentStarted.await()
            dispatcher.submit(requireNotNull(dispatcher.tryReserve()), decisions[1])
            dispatcher.submit(requireNotNull(dispatcher.tryReserve()), decisions[2])
            runCurrent()

            assertEquals(decisions, persisted)
            assertFalse(releaseEnrichment.isCompleted)

            releaseEnrichment.complete(Unit)
            dispatcher.close()
            advanceUntilIdle()

            assertEquals(decisions, enriched)
        }

    @Test
    fun `service cancellation cannot cancel handed-off persistence or enrichment`() =
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
                    enrichmentScope = applicationScope,
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
                    dispatcher.submit(requireNotNull(dispatcher.tryReserve()), 1)
                }
            runCurrent()
            persistenceStarted.await()

            serviceJob.cancel()
            dispatcher.close()
            runCurrent()

            assertTrue(submission.isCancelled)
            releasePersistence.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(1), persisted)
            assertEquals(listOf(1), enriched)

            applicationScope.cancel()
        }

    @Test
    fun `process loss leaves the already persisted unknown monitor decision intact`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val applicationJob = SupervisorJob()
            val applicationScope = CoroutineScope(applicationJob + testDispatcher)
            val enrichmentStarted = CompletableDeferred<Unit>()
            val persisted = mutableListOf<NotificationEvent>()
            val enriched = mutableListOf<NotificationEvent>()
            val baseDecision =
                NotificationEvent(
                    packageName = "com.example",
                    category = null,
                    postedAtMillis = 1,
                    action = RuleAction.Cancel,
                    matchedRuleId = "1",
                    monitoredRuleId = null,
                    monitoredAction = null,
                    recordedAtMillis = 1,
                )
            val dispatcher =
                PostCommitWorkDispatcher<NotificationEvent, NotificationEvent>(
                    persistenceScope = applicationScope,
                    enrichmentScope = applicationScope,
                    persist = { decision ->
                        persisted += decision
                        decision
                    },
                    enrich = { decision ->
                        enrichmentStarted.complete(Unit)
                        awaitCancellation()
                        enriched += decision
                    },
                )

            dispatcher.submit(requireNotNull(dispatcher.tryReserve()), baseDecision)
            enrichmentStarted.await()
            applicationScope.cancel()
            advanceUntilIdle()

            assertEquals(listOf(baseDecision), persisted)
            assertTrue(enriched.isEmpty())
            assertNull(persisted.single().monitoredRuleId)
            assertNull(persisted.single().monitoredAction)
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
                        dispatcher.submit(requireNotNull(dispatcher.tryReserve()), request)
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
    fun `retained enrichment requests never exceed the configured total bound`() =
        runTest {
            val releaseEnrichment = CompletableDeferred<Unit>()
            val persisted = mutableListOf<Int>()
            val dispatcher =
                PostCommitWorkDispatcher<Int, Int>(
                    persistenceScope = this,
                    enrichmentScope = this,
                    persist = { request ->
                        persisted += request
                        request
                    },
                    enrich = { releaseEnrichment.await() },
                    maxConcurrentPersistence = 2,
                    maxRetainedEnrichment = 6,
                )
            val reservations = List(6) { requireNotNull(dispatcher.tryReserve()) }
            assertNull(dispatcher.tryReserve())
            val submissions =
                (1..6).map { request ->
                    launch {
                        dispatcher.submit(reservations[request - 1], request)
                    }
                }

            runCurrent()

            assertEquals(listOf(1, 2, 3, 4, 5, 6), persisted)

            releaseEnrichment.complete(Unit)
            advanceUntilIdle()

            assertEquals((1..6).toList(), persisted)
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

            val result =
                runCatching {
                    dispatcher.submit(requireNotNull(dispatcher.tryReserve()), 1)
                }

            val propagated = result.exceptionOrNull()
            assertTrue(propagated is IllegalStateException)
            assertEquals(failure.message, propagated?.message)
            assertTrue(failures.single() === failure)
            dispatcher.close()
        }

    @Test
    fun `reserved action handoff survives dispatcher close before submit`() =
        runTest {
            val persisted = mutableListOf<Int>()
            val enriched = mutableListOf<Int>()
            val dispatcher =
                PostCommitWorkDispatcher<Int, Int>(
                    persistenceScope = this,
                    enrichmentScope = this,
                    persist = { request ->
                        persisted += request
                        request
                    },
                    enrich = enriched::add,
                )
            val reservation = requireNotNull(dispatcher.tryReserve())
            var platformActions = 0

            platformActions += 1
            dispatcher.close()
            dispatcher.submit(reservation, 1)
            advanceUntilIdle()

            assertEquals(1, platformActions)
            assertEquals(listOf(1), persisted)
            assertEquals(listOf(1), enriched)
            assertNull(dispatcher.tryReserve())
        }

    @Test
    fun `fatal enrichment failure releases its slot and worker continues`() =
        runTest {
            val failures = mutableListOf<Throwable>()
            val enriched = mutableListOf<Int>()
            val dispatcher =
                PostCommitWorkDispatcher<Int, Int>(
                    persistenceScope = this,
                    enrichmentScope = this,
                    persist = { it },
                    onEnrichmentFailure = failures::add,
                    enrich = { request ->
                        if (request == 1) throw LinkageError("broken local model")
                        enriched += request
                    },
                    maxRetainedEnrichment = 2,
                )
            val first = requireNotNull(dispatcher.tryReserve())
            val second = requireNotNull(dispatcher.tryReserve())

            dispatcher.submit(first, 1)
            dispatcher.submit(second, 2)
            advanceUntilIdle()

            assertTrue(failures.single() is LinkageError)
            assertEquals(listOf(2), enriched)
            val replacementOne = requireNotNull(dispatcher.tryReserve())
            val replacementTwo = requireNotNull(dispatcher.tryReserve())
            dispatcher.release(replacementOne)
            dispatcher.release(replacementTwo)
            dispatcher.close()
        }
}
