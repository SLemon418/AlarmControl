package com.alarmcontrol.service

import com.alarmcontrol.core.filtering.ActiveRateOccurrence
import com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.PersistedRateOccurrence
import com.alarmcontrol.core.filtering.RateListenerKeyDigest
import com.alarmcontrol.core.filtering.RateListenerKeyHashResult
import com.alarmcontrol.core.filtering.RateListenerKeyHasher
import com.alarmcontrol.core.filtering.RateOccurrenceId
import com.alarmcontrol.core.filtering.RateOccurrenceIncompleteReason
import com.alarmcontrol.core.filtering.RateOccurrenceLifecycleGate
import com.alarmcontrol.core.filtering.RateOccurrencePersistenceFailure
import com.alarmcontrol.core.filtering.RateOccurrencePersistenceResult
import com.alarmcontrol.core.filtering.RateOccurrenceRepository
import com.alarmcontrol.core.filtering.RateOccurrenceSeed
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.RateSignal
import com.alarmcontrol.core.filtering.RecordedRateOccurrence
import com.alarmcontrol.notifications.NotificationRateTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // One deterministic actor fixture covers lifecycle, persistence, and queue races.
class NotificationRateLifecycleActorTest {
    private val packageMinute = RateSignal(RateScope.PACKAGE, 60_000L)

    @Test
    fun `post outcome waits for durable occurrence commit`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.actor.connected()
            runCurrent()
            val releaseRecord = CompletableDeferred<Unit>()
            fixture.repository.recordGate = releaseRecord
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)

            assertTrue(fixture.actor.tryPost(fixture.generation, "raw", outcome = outcome))
            runCurrent()

            assertFalse(outcome.isCompleted)
            assertEquals(1, fixture.repository.recordCalls)

            releaseRecord.complete(Unit)
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, outcome.await())
        }

    @Test
    fun `persistence failure proceeds with unrelated rules and invalidates rate counts`() =
        runTest {
            val fixture = fixture(backgroundScope, seed = listOf(occurrence(1, at = 100_000L)))
            fixture.actor.connected()
            runCurrent()
            assertEquals(1, fixture.counts(at = 100_000L).counts[packageMinute])
            fixture.repository.recordUnavailable = true
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)

            fixture.actor.tryPost(fixture.generation, "raw", postedAtMillis = 100_001L, outcome = outcome)
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, outcome.await())
            assertTrue(fixture.counts(at = 100_001L).counts.isEmpty())
        }

    @Test
    fun `future post failure retains the command timestamp as the gap anchor`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.actor.connected()
            runCurrent()
            fixture.repository.extendedAnchors.clear()
            fixture.repository.recordUnavailable = true
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)

            fixture.actor.tryPost(
                fixture.generation,
                "raw",
                postedAtMillis = 200_000L,
                outcome = outcome,
            )
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, outcome.await())
            assertEquals(200_000L, fixture.repository.extendedAnchors.last())
        }

    @Test
    fun `hash recovery and recorded gap markers each invalidate rate counts`() =
        runTest {
            val hashRecovery = fixture(backgroundScope, seed = listOf(occurrence(1, at = 100_000L)))
            hashRecovery.actor.connected()
            runCurrent()
            hashRecovery.hasher.result =
                RateListenerKeyHashResult.Success(
                    digest = DIGEST,
                    recoveryIncompleteUntilMillis =
                        100_000L + MAX_RATE_WINDOW_MILLIS + 1L,
                )
            val hashOutcome = CompletableDeferred<RatePostOutcome>(parent = null)
            hashRecovery.actor.tryPost(hashRecovery.generation, "raw-hash", outcome = hashOutcome)
            runCurrent()

            val recordedGap = fixture(backgroundScope, seed = listOf(occurrence(2, at = 100_000L)))
            recordedGap.actor.connected()
            runCurrent()
            recordedGap.repository.recordedIncompleteUntilMillis =
                100_000L + MAX_RATE_WINDOW_MILLIS + 1L
            val recordedOutcome = CompletableDeferred<RatePostOutcome>(parent = null)
            recordedGap.actor.tryPost(recordedGap.generation, "raw-record", outcome = recordedOutcome)
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, hashOutcome.await())
            assertTrue(hashRecovery.counts(at = 100_000L).counts.isEmpty())
            assertEquals(RatePostOutcome.Proceed, recordedOutcome.await())
            assertTrue(recordedGap.counts(at = 100_000L).counts.isEmpty())
        }

    @Test
    fun `durably rejected stale callback skips evaluation and records a gap`() =
        runTest {
            val fixture = fixture(backgroundScope, seed = listOf(occurrence(1, at = 100_000L)))
            fixture.actor.connected()
            runCurrent()
            fixture.repository.extendedAnchors.clear()
            fixture.repository.recordAccepted = false
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)

            fixture.actor.tryPost(fixture.generation, "raw", postedAtMillis = 90_000L, outcome = outcome)
            runCurrent()

            assertEquals(RatePostOutcome.Stale, outcome.await())
            assertTrue(fixture.counts(at = 100_000L).counts.isEmpty())
            assertTrue(fixture.repository.extendedAnchors.isNotEmpty())
        }

    @Test
    fun `tracker rejected durable update invalidates captured counts and records gap`() =
        runTest {
            val persisted = occurrence(1, at = 100_000L)
            val fixture = fixture(backgroundScope, seed = listOf(persisted))
            fixture.actor.connected()
            runCurrent()
            val captured = fixture.counts(at = 100_000L)
            fixture.repository.extendedAnchors.clear()
            fixture.repository.recordedActiveOverride =
                ActiveRateOccurrence(
                    listenerKeyDigest = DIGEST,
                    occurrenceId = persisted.occurrenceId,
                    packageName = persisted.packageName,
                    channelId = persisted.channelId,
                    lastPostedAtMillis = 90_000L,
                )
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)

            fixture.actor.tryPost(fixture.generation, "raw", postedAtMillis = 100_001L, outcome = outcome)
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, outcome.await())
            assertTrue(fixture.counts(at = 100_001L).counts.isEmpty())
            assertTrue(fixture.repository.extendedAnchors.isNotEmpty())
            assertFalse(
                fixture.actor.commitIfRateCountsCurrent(
                    snapshot = fixture.snapshot(at = 100_000L),
                    expectation = expectation(captured, packageMinute),
                ) {
                    true
                },
            )
        }

    @Test
    fun `later occurrence does not invalidate counts at the original snapshot time`() =
        runTest {
            val fixture = fixture(backgroundScope, seed = listOf(occurrence(1, at = 100_000L)))
            fixture.actor.connected()
            runCurrent()
            val firstEvaluation = fixture.counts(at = 100_000L)
            val secondOutcome = CompletableDeferred<RatePostOutcome>(parent = null)

            fixture.clock.setMillis(100_001L)
            fixture.actor.tryPost(
                fixture.generation,
                "raw-b",
                postedAtMillis = 100_001L,
                outcome = secondOutcome,
            )
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, secondOutcome.await())
            assertTrue(
                fixture.actor.commitIfRateCountsCurrent(
                    snapshot = fixture.snapshot(at = 100_000L),
                    expectation = expectation(firstEvaluation, packageMinute),
                ) {
                    true
                },
            )
            assertTrue(
                fixture.actor.commitIfRateCountsCurrent(
                    snapshot = fixture.snapshot(at = 100_000L),
                    expectation = null,
                ) {
                    true
                },
            )
        }

    @Test
    fun `out of order occurrence invalidates counts at the original snapshot time`() =
        runTest {
            val fixture = fixture(backgroundScope, seed = listOf(occurrence(1, at = 100_000L)))
            fixture.actor.connected()
            runCurrent()
            val firstEvaluation = fixture.counts(at = 100_000L)
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)

            fixture.actor.tryPost(
                fixture.generation,
                "raw-older",
                postedAtMillis = 99_999L,
                outcome = outcome,
            )
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, outcome.await())
            assertFalse(
                fixture.actor.commitIfRateCountsCurrent(
                    snapshot = fixture.snapshot(at = 100_000L),
                    expectation = expectation(firstEvaluation, packageMinute),
                ) {
                    true
                },
            )
        }

    @Test
    fun `committed history clear revokes captured counts and starts an empty rate baseline`() =
        runTest {
            val fixture =
                fixture(
                    backgroundScope,
                    seed =
                        listOf(
                            occurrence(1, at = 99_000L),
                            occurrence(2, at = 100_000L),
                        ),
                )
            fixture.actor.connected()
            runCurrent()
            val captured = fixture.counts(at = 100_000L)
            val generationBeforeClear = fixture.generation
            assertEquals(2, captured.counts[packageMinute])

            fixture.lifecycleGate.markStateCleared(resetAtMillis = 100_001L)
            val generationAfterClear = fixture.generation

            assertFalse(
                fixture.actor.commitIfRateCountsCurrent(
                    snapshot = fixture.snapshot(at = 100_000L),
                    expectation = expectation(captured, packageMinute),
                ) {
                    true
                },
            )
            assertTrue(generationAfterClear > generationBeforeClear)
            assertFalse(fixture.counts(at = 100_001L).counts.containsKey(packageMinute))

            fixture.clock.setMillis(100_002L)
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)
            fixture.actor.tryPost(
                generation = generationAfterClear,
                rawListenerKey = "after-clear",
                postedAtMillis = 100_002L,
                outcome = outcome,
            )
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, outcome.await())
            fixture.clock.setMillis(160_001L)
            assertFalse(fixture.counts(at = 160_001L).counts.containsKey(packageMinute))
            fixture.clock.setMillis(160_002L)
            assertEquals(1, fixture.counts(at = 160_002L).counts[packageMinute])
        }

    @Test
    fun `committed history clear releases a saturated seed retry barrier`() =
        runTest {
            val fixture =
                fixture(
                    scope = backgroundScope,
                    respectPersistedIncompleteWindow = true,
                )
            fixture.repository.persistedIncompleteUntilMillis = Long.MAX_VALUE
            fixture.actor.connected()
            runCurrent()
            assertTrue(fixture.counts(at = 100_000L).counts.isEmpty())
            val seedCallsBeforeClear = fixture.repository.callOrder.count { it == "seed" }

            fixture.repository.persistedIncompleteUntilMillis =
                100_001L + MAX_RATE_WINDOW_MILLIS + 1L
            fixture.clock.setMillis(100_001L)
            fixture.lifecycleGate.markStateCleared(resetAtMillis = 100_001L)
            fixture.actor.currentLifecycleGeneration
            fixture.actor.requestReseed(anchorMillis = 100_001L)
            runCurrent()

            assertEquals(
                seedCallsBeforeClear + 1,
                fixture.repository.callOrder.count { it == "seed" },
            )
            assertFalse(fixture.counts(at = 100_001L).counts.containsKey(packageMinute))
        }

    @Test
    fun `history clear at saturated time leaves every rate window unknown`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.lifecycleGate.markStateCleared(resetAtMillis = Long.MAX_VALUE)
            val generation = fixture.actor.currentLifecycleGeneration
            fixture.clock.setMillis(Long.MAX_VALUE)
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)

            fixture.actor.tryPost(
                generation = generation,
                rawListenerKey = "saturated-clear",
                postedAtMillis = Long.MAX_VALUE,
                outcome = outcome,
            )
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, outcome.await())
            assertFalse(fixture.counts(at = Long.MAX_VALUE).counts.containsKey(packageMinute))
        }

    @Test
    fun `history clear baseline stays unknown across rollback until the window refills`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.lifecycleGate.markStateCleared(resetAtMillis = 100_000L)
            val generation = fixture.actor.currentLifecycleGeneration
            fixture.clock.setMillis(90_000L)
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)

            fixture.actor.tryPost(
                generation = generation,
                rawListenerKey = "after-rollback",
                postedAtMillis = 90_000L,
                outcome = outcome,
            )
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, outcome.await())
            assertFalse(fixture.counts(at = 90_000L).counts.containsKey(packageMinute))
            fixture.clock.setMillis(160_001L)
            assertEquals(0, fixture.counts(at = 160_001L).counts[packageMinute])
        }

    @Test
    fun `reseed with identical inputs does not invalidate the decision`() =
        runTest {
            val fixture = fixture(backgroundScope, seed = listOf(occurrence(1, at = 100_000L)))
            fixture.actor.connected()
            runCurrent()
            val captured = fixture.counts(at = 100_000L)

            fixture.actor.requestReseed()
            runCurrent()

            assertTrue(
                fixture.actor.commitIfRateCountsCurrent(
                    snapshot = fixture.snapshot(at = 100_000L),
                    expectation = expectation(captured, packageMinute),
                ) {
                    true
                },
            )
        }

    @Test
    fun `reseed with changed inputs invalidates the decision`() =
        runTest {
            val first = occurrence(1, at = 100_000L)
            val fixture = fixture(backgroundScope, seed = listOf(first))
            fixture.actor.connected()
            runCurrent()
            val captured = fixture.counts(at = 100_000L)
            fixture.repository.seedOccurrences =
                listOf(
                    first,
                    occurrence(2, at = 99_999L),
                )

            fixture.actor.requestReseed()
            runCurrent()

            assertFalse(
                fixture.actor.commitIfRateCountsCurrent(
                    snapshot = fixture.snapshot(at = 100_000L),
                    expectation = expectation(captured, packageMinute),
                ) {
                    true
                },
            )
        }

    @Test
    fun `stale remove older than the active repost is a no-op`() =
        runTest {
            val persisted = occurrence(1, at = 100_000L)
            val fixture = fixture(backgroundScope, seed = listOf(persisted))
            fixture.repository.active =
                ActiveRateOccurrence(
                    listenerKeyDigest = DIGEST,
                    occurrenceId = persisted.occurrenceId,
                    packageName = persisted.packageName,
                    channelId = persisted.channelId,
                    lastPostedAtMillis = persisted.postedAtMillis,
                )
            fixture.actor.connected()
            runCurrent()
            fixture.repository.extendedAnchors.clear()
            fixture.repository.deleteAccepted = false

            fixture.actor.tryRemove(
                generation = fixture.generation,
                rawListenerKey = "raw",
                removedPostTimeMillis = 99_000L,
            )
            runCurrent()

            assertEquals(1, fixture.counts(at = 100_000L).counts[packageMinute])
            assertTrue(fixture.repository.extendedAnchors.isEmpty())
            assertEquals(0, fixture.repository.deleteCalls)
        }

    @Test
    fun `missing active mapping removal is a normal no-op`() =
        runTest {
            val fixture = fixture(backgroundScope, seed = listOf(occurrence(1, at = 100_000L)))
            fixture.actor.connected()
            runCurrent()
            fixture.repository.extendedAnchors.clear()

            fixture.actor.tryRemove(
                generation = fixture.generation,
                rawListenerKey = "raw",
                removedPostTimeMillis = 100_000L,
            )
            runCurrent()

            assertEquals(1, fixture.counts(at = 100_000L).counts[packageMinute])
            assertTrue(fixture.repository.extendedAnchors.isEmpty())
            assertEquals(0, fixture.repository.deleteCalls)
        }

    @Test
    fun `failed eligible active removal marks rate state incomplete`() =
        runTest {
            val persisted = occurrence(1, at = 100_000L)
            val fixture = fixture(backgroundScope, seed = listOf(persisted))
            fixture.repository.active =
                ActiveRateOccurrence(
                    listenerKeyDigest = DIGEST,
                    occurrenceId = persisted.occurrenceId,
                    packageName = persisted.packageName,
                    channelId = persisted.channelId,
                    lastPostedAtMillis = persisted.postedAtMillis,
                )
            fixture.actor.connected()
            runCurrent()
            fixture.repository.extendedAnchors.clear()
            fixture.repository.deleteAccepted = false

            fixture.actor.tryRemove(
                generation = fixture.generation,
                rawListenerKey = "raw",
                removedPostTimeMillis = 100_000L,
            )
            runCurrent()

            assertTrue(fixture.counts(at = 100_000L).counts.isEmpty())
            assertTrue(fixture.repository.extendedAnchors.isNotEmpty())
            assertEquals(1, fixture.repository.deleteCalls)
        }

    @Test
    fun `active lookup failure marks rate state incomplete`() =
        runTest {
            val fixture = fixture(backgroundScope, seed = listOf(occurrence(1, at = 100_000L)))
            fixture.actor.connected()
            runCurrent()
            fixture.repository.extendedAnchors.clear()
            fixture.repository.activeUnavailable = true

            fixture.actor.tryRemove(
                generation = fixture.generation,
                rawListenerKey = "raw",
                removedPostTimeMillis = 100_000L,
            )
            runCurrent()

            assertTrue(fixture.counts(at = 100_000L).counts.isEmpty())
            assertTrue(fixture.repository.extendedAnchors.isNotEmpty())
        }

    @Test
    fun `sixty fifth queued command fails open without evicting older commands`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.actor.connected()
            runCurrent()
            val outcomes = mutableListOf<CompletableDeferred<RatePostOutcome>>()

            repeat(65) { index ->
                val outcome = CompletableDeferred<RatePostOutcome>(parent = null)
                outcomes += outcome
                val accepted =
                    fixture.actor.tryPost(
                        generation = fixture.generation,
                        rawListenerKey = "raw-$index",
                        postedAtMillis = index.toLong(),
                        outcome = outcome,
                    )
                assertEquals(index < 64, accepted)
            }

            assertEquals(RatePostOutcome.Proceed, outcomes.last().await())
            assertTrue(outcomes.dropLast(1).none { it.isCompleted })
            fixture.repository.extendedAnchors.clear()
            runCurrent()
            assertTrue(fixture.repository.extendedAnchors.any { it >= 100_000L })
        }

    @Test
    fun `command exception does not stop the next post`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.actor.connected()
            runCurrent()
            fixture.repository.throwRecordCount = 1
            val first = CompletableDeferred<RatePostOutcome>(parent = null)
            val second = CompletableDeferred<RatePostOutcome>(parent = null)

            fixture.actor.tryPost(fixture.generation, "raw-1", postedAtMillis = 1L, outcome = first)
            fixture.actor.tryPost(fixture.generation, "raw-2", postedAtMillis = 2L, outcome = second)
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, first.await())
            assertEquals(RatePostOutcome.Proceed, second.await())
            assertEquals(2, fixture.repository.recordCalls)
        }

    @Test
    fun `linkage and memory failures complete the post and do not stop the worker`() =
        runTest {
            listOf(
                LinkageError("synthetic linkage failure"),
                OutOfMemoryError("synthetic allocation failure"),
            ).forEachIndexed { index, failure ->
                val fixture =
                    fixture(
                        backgroundScope,
                        seed = listOf(occurrence(1, at = 100_000L)),
                    )
                fixture.actor.connected()
                runCurrent()
                fixture.hasher.failure = failure
                val failed = CompletableDeferred<RatePostOutcome>(parent = null)
                val following = CompletableDeferred<RatePostOutcome>(parent = null)
                var unavailableWhenFailureCompleted = false
                failed.invokeOnCompletion {
                    unavailableWhenFailureCompleted =
                        fixture.counts(at = 100_001L).counts.isEmpty()
                }

                fixture.actor.tryPost(
                    fixture.generation,
                    "raw-failed-$index",
                    postedAtMillis = 100_001L,
                    outcome = failed,
                )
                fixture.actor.tryPost(
                    fixture.generation,
                    "raw-following-$index",
                    postedAtMillis = 100_002L,
                    outcome = following,
                )
                runCurrent()

                assertEquals(RatePostOutcome.Proceed, failed.await())
                assertEquals(RatePostOutcome.Proceed, following.await())
                assertEquals(1, fixture.repository.recordCalls)
                assertTrue(fixture.repository.extendedAnchors.isNotEmpty())
                assertTrue(unavailableWhenFailureCompleted)
            }
        }

    @Test
    fun `disconnect makes already queued post stale`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.actor.connected()
            runCurrent()
            val oldGeneration = fixture.generation
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)
            fixture.actor.tryPost(oldGeneration, "raw", outcome = outcome)

            fixture.actor.disconnected()
            runCurrent()

            assertEquals(RatePostOutcome.Stale, outcome.await())
            assertEquals(0, fixture.repository.recordCalls)
        }

    @Test
    fun `reconnect persists full window gap before attempting seed`() =
        runTest {
            val fixture = fixture(backgroundScope)

            fixture.actor.connected(anchorMillis = 123_000L)
            runCurrent()

            assertEquals(listOf("gap", "seed"), fixture.repository.callOrder.take(2))
            assertEquals(123_000L, fixture.repository.extendedAnchors.first())
        }

    @Test
    fun `future occurrence stays unknown and reseeds when wall clock catches up`() =
        runTest {
            val futurePostedAtMillis = 120_000L
            val fixture =
                fixture(
                    scope = backgroundScope,
                    seed = listOf(occurrence(1, at = futurePostedAtMillis)),
                )
            fixture.actor.connected()
            runCurrent()

            assertTrue(fixture.counts(at = 100_000L).counts.isEmpty())
            assertEquals(1, fixture.repository.callOrder.count { it == "seed" })

            fixture.clock.setMillis(futurePostedAtMillis - 1)
            fixture.actor.requestReseed()
            runCurrent()
            assertEquals(1, fixture.repository.callOrder.count { it == "seed" })

            fixture.clock.setMillis(futurePostedAtMillis)
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)
            fixture.actor.tryPost(
                generation = fixture.generation,
                rawListenerKey = "raw-current",
                postedAtMillis = futurePostedAtMillis,
                outcome = outcome,
            )
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, outcome.await())
            assertEquals(2, fixture.counts(at = futurePostedAtMillis).counts[packageMinute])
            assertEquals(2, fixture.repository.callOrder.count { it == "seed" })
        }

    @Test
    fun `wall clock rollback after seeding keeps rate counts unknown until catch up`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.actor.connected()
            runCurrent()

            fixture.clock.setMillis(200_000L)
            val newerOutcome = CompletableDeferred<RatePostOutcome>(parent = null)
            fixture.actor.tryPost(
                generation = fixture.generation,
                rawListenerKey = "raw-newer",
                postedAtMillis = 200_000L,
                outcome = newerOutcome,
            )
            runCurrent()
            assertEquals(RatePostOutcome.Proceed, newerOutcome.await())

            fixture.clock.setMillis(100_000L)
            val rollbackOutcome = CompletableDeferred<RatePostOutcome>(parent = null)
            fixture.actor.tryPost(
                generation = fixture.generation,
                rawListenerKey = "raw-after-rollback",
                postedAtMillis = 100_000L,
                outcome = rollbackOutcome,
            )
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, rollbackOutcome.await())
            assertTrue(fixture.counts(at = 100_000L).counts.isEmpty())

            fixture.clock.setMillis(200_000L)
            assertEquals(1, fixture.counts(at = 100_000L).counts[packageMinute])
        }

    @Test
    fun `pre rollback post time cannot bypass current wall clock barrier`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.actor.connected()
            runCurrent()

            fixture.clock.setMillis(200_000L)
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)
            fixture.actor.tryPost(
                generation = fixture.generation,
                rawListenerKey = "raw-before-rollback",
                postedAtMillis = 200_000L,
                outcome = outcome,
            )
            runCurrent()
            assertEquals(RatePostOutcome.Proceed, outcome.await())
            val captured = fixture.counts(at = 200_000L)
            assertEquals(1, captured.counts[packageMinute])

            fixture.clock.setMillis(100_000L)

            assertTrue(fixture.counts(at = 200_000L).counts.isEmpty())
            assertFalse(
                fixture.actor.commitIfRateCountsCurrent(
                    snapshot = fixture.snapshot(at = 200_000L),
                    expectation = expectation(captured, packageMinute),
                ) {
                    true
                },
            )
        }

    @Test
    fun `Long MIN value remains a valid pending gap anchor`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.clock.setMillis(Long.MIN_VALUE)

            fixture.actor.connected(anchorMillis = Long.MIN_VALUE)
            runCurrent()

            assertEquals(listOf("gap", "seed"), fixture.repository.callOrder.take(2))
            assertEquals(Long.MIN_VALUE, fixture.repository.extendedAnchors.first())
        }

    @Test
    fun `failed gap persistence cannot be bypassed by a successful seed read`() =
        runTest {
            val fixture = fixture(backgroundScope, seed = listOf(occurrence(1, at = 100_000L)))
            fixture.repository.extendUnavailable = true

            fixture.actor.connected(anchorMillis = 100_000L)
            runCurrent()

            assertEquals(listOf("gap", "gap"), fixture.repository.callOrder)
            assertTrue(fixture.counts(at = 100_000L).counts.isEmpty())
        }

    @Test
    fun `queue overflow during seed prevents the loaded snapshot from becoming ready`() =
        runTest {
            val fixture =
                fixture(
                    scope = backgroundScope,
                    seed = listOf(occurrence(1, at = 100_000L)),
                    commandCapacity = 1,
                )
            val seedStarted = CompletableDeferred<Unit>()
            val releaseSeed = CompletableDeferred<Unit>()
            fixture.repository.seedStarted = seedStarted
            fixture.repository.seedGate = releaseSeed
            runCurrent()
            fixture.actor.connected()
            runCurrent()
            seedStarted.await()
            val overflow = CompletableDeferred<RatePostOutcome>(parent = null)

            assertFalse(
                fixture.actor.tryPost(
                    fixture.generation,
                    "overflow",
                    postedAtMillis = 200_000L,
                    outcome = overflow,
                ),
            )
            assertEquals(RatePostOutcome.Proceed, overflow.await())
            releaseSeed.complete(Unit)
            runCurrent()

            assertTrue(fixture.counts(at = 100_000L).counts.isEmpty())
            assertTrue(fixture.repository.extendedAnchors.contains(200_000L))
        }

    @Test
    fun `reconnect recovers a short window without sliding the durable gap`() =
        runTest {
            val fullWindow = RateSignal(RateScope.PACKAGE, MAX_RATE_WINDOW_MILLIS)
            val fixture =
                fixture(
                    scope = backgroundScope,
                    respectPersistedIncompleteWindow = true,
                )
            fixture.actor.connected(anchorMillis = 100_000L)
            runCurrent()
            fixture.clock.setMillis(160_001L)
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)

            fixture.actor.tryPost(
                generation = fixture.generation,
                rawListenerKey = "raw",
                postedAtMillis = 160_001L,
                outcome = outcome,
            )
            runCurrent()

            val counts =
                fixture
                    .counts(
                        at = 160_001L,
                        signals = setOf(packageMinute, fullWindow),
                    ).counts
            assertEquals(RatePostOutcome.Proceed, outcome.await())
            assertEquals(1, counts[packageMinute])
            assertFalse(counts.containsKey(fullWindow))
            assertEquals(listOf(100_000L), fixture.repository.extendedAnchors)
        }

    @Test
    fun `persisted recovery marker on remove does not slide the durable gap`() =
        runTest {
            val fixture =
                fixture(
                    scope = backgroundScope,
                    respectPersistedIncompleteWindow = true,
                )
            fixture.actor.connected(anchorMillis = 100_000L)
            runCurrent()
            fixture.repository.extendedAnchors.clear()
            fixture.hasher.result =
                RateListenerKeyHashResult.Success(
                    digest = DIGEST,
                    recoveryIncompleteUntilMillis =
                        100_000L + MAX_RATE_WINDOW_MILLIS + 1L,
                )

            fixture.actor.tryRemove(
                generation = fixture.generation,
                rawListenerKey = "raw",
                removedPostTimeMillis = 100_000L,
            )
            runCurrent()

            assertTrue(fixture.repository.extendedAnchors.isEmpty())
        }

    @Test
    fun `stronger persisted barrier invalidates captured rate counts`() =
        runTest {
            val fixture = fixture(backgroundScope, seed = listOf(occurrence(1, at = 100_000L)))
            fixture.actor.connected()
            runCurrent()
            val captured = fixture.counts(at = 100_000L)
            fixture.repository.extendedAnchors.clear()
            fixture.repository.recordedIncompleteUntilMillis =
                100_000L + MAX_RATE_WINDOW_MILLIS + 1L
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)

            fixture.actor.tryPost(
                generation = fixture.generation,
                rawListenerKey = "raw",
                postedAtMillis = 100_001L,
                outcome = outcome,
            )
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, outcome.await())
            assertFalse(
                fixture.actor.commitIfRateCountsCurrent(
                    snapshot = fixture.snapshot(at = 100_000L),
                    expectation = expectation(captured, packageMinute),
                ) {
                    true
                },
            )
            assertTrue(fixture.repository.extendedAnchors.isEmpty())
        }

    @Test
    fun `saturated persisted barrier keeps every rate signal unknown`() =
        runTest {
            val fixture = fixture(backgroundScope, seed = listOf(occurrence(1, at = 100_000L)))
            fixture.actor.connected()
            runCurrent()
            fixture.repository.extendedAnchors.clear()
            fixture.repository.recordedIncompleteUntilMillis = Long.MAX_VALUE
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)

            fixture.actor.tryPost(
                generation = fixture.generation,
                rawListenerKey = "raw",
                postedAtMillis = 100_001L,
                outcome = outcome,
            )
            runCurrent()

            assertEquals(RatePostOutcome.Proceed, outcome.await())
            assertTrue(fixture.counts(at = 100_001L).counts.isEmpty())
            assertTrue(fixture.repository.extendedAnchors.isEmpty())
        }

    @Test
    fun `hashing and external clear share the lifecycle gate`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.actor.connected()
            runCurrent()
            val hashStarted = CompletableDeferred<Unit>()
            val releaseHash = CompletableDeferred<Unit>()
            fixture.hasher.hashStarted = hashStarted
            fixture.hasher.hashGate = releaseHash
            val outcome = CompletableDeferred<RatePostOutcome>(parent = null)
            fixture.actor.tryPost(fixture.generation, "raw", outcome = outcome)
            runCurrent()
            hashStarted.await()
            val clearEntered = CompletableDeferred<Unit>()

            launch {
                fixture.lifecycleGate.withOperation {
                    clearEntered.complete(Unit)
                }
            }
            runCurrent()

            assertFalse(clearEntered.isCompleted)
            releaseHash.complete(Unit)
            runCurrent()
            assertTrue(clearEntered.isCompleted)
            assertEquals(RatePostOutcome.Proceed, outcome.await())
        }

    @Test
    fun `unavailable transition is linearized after an in progress platform commit`() =
        runTest {
            val fixture = fixture(backgroundScope, seed = listOf(occurrence(1, at = 100_000L)))
            fixture.actor.connected()
            runCurrent()
            val captured = fixture.counts(at = 100_000L)
            val actionStarted = CountDownLatch(1)
            val releaseAction = CountDownLatch(1)
            val invalidationStarted = CountDownLatch(1)
            val invalidationReturned = CountDownLatch(1)
            var firstCommitted = false
            val commitThread =
                thread(name = "rate-commit") {
                    firstCommitted =
                        fixture.actor.commitIfRateCountsCurrent(
                            snapshot = fixture.snapshot(at = 100_000L),
                            expectation = expectation(captured, packageMinute),
                        ) {
                            actionStarted.countDown()
                            check(releaseAction.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            true
                        }
                }
            assertTrue(actionStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val invalidationThread =
                thread(name = "rate-invalidation") {
                    invalidationStarted.countDown()
                    fixture.actor.requestReseed()
                    invalidationReturned.countDown()
                }

            try {
                assertTrue(invalidationStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(invalidationReturned.await(100L, TimeUnit.MILLISECONDS))
                releaseAction.countDown()
                assertTrue(invalidationReturned.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                commitThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
                invalidationThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))

                assertTrue(firstCommitted)
                assertFalse(
                    fixture.actor.commitIfRateCountsCurrent(
                        snapshot = fixture.snapshot(at = 100_000L),
                        expectation = expectation(captured, packageMinute),
                    ) {
                        true
                    },
                )
            } finally {
                releaseAction.countDown()
                commitThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
                invalidationThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
            }
        }

    @Test
    fun `overflow gap cannot publish ahead of tracker invalidation during a platform commit`() =
        runTest {
            val fixture =
                fixture(
                    scope = backgroundScope,
                    seed = listOf(occurrence(1, at = 100_000L)),
                    commandCapacity = 1,
                )
            runCurrent()
            fixture.actor.connected()
            runCurrent()
            fixture.repository.extendedAnchors.clear()

            val removeHashStarted = CompletableDeferred<Unit>()
            val releaseRemoveHash = CompletableDeferred<Unit>()
            fixture.hasher.hashStarted = removeHashStarted
            fixture.hasher.hashGate = releaseRemoveHash
            assertTrue(
                fixture.actor.tryRemove(
                    generation = fixture.generation,
                    rawListenerKey = "remove",
                    removedPostTimeMillis = 100_000L,
                ),
            )
            runCurrent()
            removeHashStarted.await()

            val captured = fixture.counts(at = 100_000L)
            val actionStarted = CountDownLatch(1)
            val releaseAction = CountDownLatch(1)
            val overflowStarted = CountDownLatch(1)
            val overflowReturned = CountDownLatch(1)
            val commitThread =
                thread(name = "rate-gap-commit") {
                    fixture.actor.commitIfRateCountsCurrent(
                        snapshot = fixture.snapshot(at = 100_000L),
                        expectation = expectation(captured, packageMinute),
                    ) {
                        actionStarted.countDown()
                        check(releaseAction.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        true
                    }
                }
            assertTrue(actionStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val overflowOutcome = CompletableDeferred<RatePostOutcome>(parent = null)
            val overflowThread =
                thread(name = "rate-gap-overflow") {
                    overflowStarted.countDown()
                    fixture.actor.tryPost(
                        generation = fixture.generation,
                        rawListenerKey = "overflow",
                        postedAtMillis = 200_000L,
                        outcome = overflowOutcome,
                    )
                    overflowReturned.countDown()
                }

            try {
                assertTrue(overflowStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(overflowReturned.await(100L, TimeUnit.MILLISECONDS))
                releaseRemoveHash.complete(Unit)
                runCurrent()

                assertTrue(fixture.repository.extendedAnchors.isEmpty())

                releaseAction.countDown()
                assertTrue(overflowReturned.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                commitThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
                overflowThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
                assertEquals(RatePostOutcome.Proceed, overflowOutcome.await())
                assertTrue(fixture.counts(at = 100_000L).counts.isEmpty())
            } finally {
                releaseRemoveHash.complete(Unit)
                releaseAction.countDown()
                commitThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
                overflowThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
            }
        }

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        seed: List<PersistedRateOccurrence> = emptyList(),
        respectPersistedIncompleteWindow: Boolean = false,
        commandCapacity: Int = 64,
    ): Fixture {
        val repository =
            FakeRateOccurrenceRepository(
                seed = seed,
                respectPersistedIncompleteWindow = respectPersistedIncompleteWindow,
            )
        val hasher = FakeRateListenerKeyHasher()
        val lifecycleGate = RateOccurrenceLifecycleGate()
        val clock = MutableClock(100_000L)
        var nextId = 10
        val actor =
            NotificationRateLifecycleActor(
                scope = scope,
                repository = repository,
                hasher = hasher,
                lifecycleGate = lifecycleGate,
                tracker = NotificationRateTracker(),
                clock = clock,
                commandCapacity = commandCapacity,
                newOccurrenceId = {
                    nextId += 1
                    occurrenceId(nextId)
                },
            )
        return Fixture(actor, repository, hasher, lifecycleGate, clock)
    }

    private data class Fixture(
        val actor: NotificationRateLifecycleActor,
        val repository: FakeRateOccurrenceRepository,
        val hasher: FakeRateListenerKeyHasher,
        val lifecycleGate: RateOccurrenceLifecycleGate,
        val clock: MutableClock,
    ) {
        val generation: Long
            get() = actor.currentLifecycleGeneration

        fun counts(
            at: Long,
            signals: Set<RateSignal> = setOf(RateSignal(RateScope.PACKAGE, 60_000L)),
        ): RateCountSnapshot =
            actor.captureCounts(
                snapshot(at),
                signals,
            )

        fun snapshot(at: Long): NotificationSnapshot =
            NotificationSnapshot(
                packageName = "pkg",
                title = null,
                text = null,
                category = null,
                channelId = "channel",
                postedAtMillis = at,
                isOngoing = false,
            )
    }

    private class FakeRateListenerKeyHasher : RateListenerKeyHasher {
        var hashStarted: CompletableDeferred<Unit>? = null
        var hashGate: CompletableDeferred<Unit>? = null
        var result: RateListenerKeyHashResult = RateListenerKeyHashResult.Success(DIGEST)
        var failure: Throwable? = null

        override suspend fun hash(rawListenerKey: String): RateListenerKeyHashResult {
            hashStarted?.complete(Unit)
            hashGate?.await()
            failure?.let { error ->
                failure = null
                throw error
            }
            return result
        }
    }

    private class FakeRateOccurrenceRepository(
        seed: List<PersistedRateOccurrence>,
        private val respectPersistedIncompleteWindow: Boolean,
    ) : RateOccurrenceRepository {
        val callOrder = mutableListOf<String>()
        val extendedAnchors = mutableListOf<Long>()
        var seedOccurrences = seed
        var active: ActiveRateOccurrence? = null
        var activeUnavailable = false
        var seedStarted: CompletableDeferred<Unit>? = null
        var seedGate: CompletableDeferred<Unit>? = null
        var recordGate: CompletableDeferred<Unit>? = null
        var recordCalls = 0
        var recordAccepted = true
        var recordUnavailable = false
        var extendUnavailable = false
        var deleteAccepted = true
        var deleteCalls = 0
        var throwRecordCount = 0
        var recordedActiveOverride: ActiveRateOccurrence? = null
        var recordedIncompleteUntilMillis: Long? = null
        var persistedIncompleteUntilMillis: Long? = null

        override suspend fun loadSeed(
            sinceMillis: Long,
            nowMillis: Long,
        ): RateOccurrenceSeed {
            callOrder += "seed"
            seedStarted?.complete(Unit)
            seedGate?.await()
            val futureOccurrenceRetryAtMillis =
                seedOccurrences
                    .maxOfOrNull(PersistedRateOccurrence::postedAtMillis)
                    ?.takeIf { it > nowMillis }
            if (futureOccurrenceRetryAtMillis != null) {
                return RateOccurrenceSeed.Incomplete(
                    reason = RateOccurrenceIncompleteReason.FUTURE_OCCURRENCE,
                    retryAtMillis = futureOccurrenceRetryAtMillis,
                )
            }
            val incompleteUntilMillis =
                persistedIncompleteUntilMillis
                    ?.takeIf {
                        respectPersistedIncompleteWindow &&
                            (it == Long.MAX_VALUE || it > nowMillis)
                    }
            if (incompleteUntilMillis == Long.MAX_VALUE) {
                return RateOccurrenceSeed.Incomplete(
                    reason = RateOccurrenceIncompleteReason.PERSISTED_GAP,
                    retryAtMillis = Long.MAX_VALUE,
                )
            }
            val coverageStartMillis =
                incompleteUntilMillis
                    ?.let { maxOf(sinceMillis, it - MAX_RATE_WINDOW_MILLIS) }
                    ?: sinceMillis
            return RateOccurrenceSeed.Available(
                occurrences =
                    seedOccurrences.filter { occurrence ->
                        occurrence.postedAtMillis in coverageStartMillis..nowMillis
                    },
                coverageStartMillis = coverageStartMillis,
            )
        }

        override suspend fun activeOccurrences(): RateOccurrencePersistenceResult<List<ActiveRateOccurrence>> =
            RateOccurrencePersistenceResult.Success(listOfNotNull(active))

        override suspend fun activeOccurrence(
            listenerKeyDigest: RateListenerKeyDigest,
        ): RateOccurrencePersistenceResult<ActiveRateOccurrence?> =
            if (activeUnavailable) {
                RateOccurrencePersistenceResult.Unavailable(
                    RateOccurrencePersistenceFailure.PERSISTENCE_UNAVAILABLE,
                )
            } else {
                RateOccurrencePersistenceResult.Success(active)
            }

        override suspend fun recordPost(
            listenerKeyDigest: RateListenerKeyDigest,
            candidateOccurrenceId: RateOccurrenceId,
            packageName: String,
            channelId: String?,
            postedAtMillis: Long,
        ): RateOccurrencePersistenceResult<RecordedRateOccurrence> {
            recordCalls += 1
            recordGate?.await()
            if (throwRecordCount > 0) {
                throwRecordCount -= 1
                error("synthetic")
            }
            if (recordUnavailable) {
                return RateOccurrencePersistenceResult.Unavailable(
                    RateOccurrencePersistenceFailure.PERSISTENCE_UNAVAILABLE,
                )
            }
            val resolved =
                recordedActiveOverride
                    ?: ActiveRateOccurrence(
                        listenerKeyDigest = listenerKeyDigest,
                        occurrenceId = candidateOccurrenceId,
                        packageName = packageName,
                        channelId = channelId,
                        lastPostedAtMillis = postedAtMillis,
                    )
            active = resolved
            return RateOccurrencePersistenceResult.Success(
                RecordedRateOccurrence(
                    activeOccurrence = resolved,
                    accepted = recordAccepted,
                    incompleteUntilMillis =
                        listOfNotNull(
                            recordedIncompleteUntilMillis,
                            persistedIncompleteUntilMillis.takeIf {
                                respectPersistedIncompleteWindow
                            },
                        ).maxOrNull(),
                ),
            )
        }

        override suspend fun deleteActiveOccurrence(
            listenerKeyDigest: RateListenerKeyDigest,
            occurrenceId: RateOccurrenceId,
            removedPostTimeMillis: Long,
        ): RateOccurrencePersistenceResult<Boolean> {
            deleteCalls += 1
            return RateOccurrencePersistenceResult.Success(deleteAccepted)
        }

        override suspend fun purgeExpiredHistory(nowMillis: Long): RateOccurrencePersistenceResult<Int> =
            RateOccurrencePersistenceResult.Success(0)

        override suspend fun extendIncompleteWindowFrom(anchorMillis: Long): RateOccurrencePersistenceResult<Long> {
            callOrder += "gap"
            extendedAnchors += anchorMillis
            if (extendUnavailable) {
                return RateOccurrencePersistenceResult.Unavailable(
                    RateOccurrencePersistenceFailure.PERSISTENCE_UNAVAILABLE,
                )
            }
            val candidate =
                if (anchorMillis > Long.MAX_VALUE - MAX_RATE_WINDOW_MILLIS - 1L) {
                    Long.MAX_VALUE
                } else {
                    anchorMillis + MAX_RATE_WINDOW_MILLIS + 1L
                }
            persistedIncompleteUntilMillis =
                maxOf(
                    persistedIncompleteUntilMillis ?: Long.MIN_VALUE,
                    candidate,
                )
            return RateOccurrencePersistenceResult.Success(checkNotNull(persistedIncompleteUntilMillis))
        }
    }

    private class MutableClock(
        private var nowMillis: Long,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(nowMillis, zone)

        override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)

        override fun millis(): Long = nowMillis

        fun setMillis(value: Long) {
            nowMillis = value
        }
    }

    private fun occurrence(
        index: Int,
        at: Long,
    ): PersistedRateOccurrence =
        PersistedRateOccurrence(
            occurrenceId = occurrenceId(index),
            packageName = "pkg",
            channelId = "channel",
            postedAtMillis = at,
        )

    private fun expectation(
        captured: RateCountSnapshot,
        vararg signals: RateSignal,
    ): RateCountExpectation {
        val requestedSignals = signals.toSet()
        return RateCountExpectation(
            requestedSignals = requestedSignals,
            expectedCounts = captured.counts.filterKeys(requestedSignals::contains),
        )
    }

    private companion object {
        val DIGEST = RateListenerKeyDigest("A".repeat(43))
        const val TEST_TIMEOUT_SECONDS = 5L

        fun occurrenceId(index: Int): RateOccurrenceId =
            RateOccurrenceId(
                "00000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}",
            )
    }
}

private fun NotificationRateLifecycleActor.tryPost(
    generation: Long,
    rawListenerKey: String,
    postedAtMillis: Long = 100_000L,
    outcome: CompletableDeferred<RatePostOutcome>,
): Boolean =
    tryPost(
        generation = generation,
        rawListenerKey = rawListenerKey,
        packageName = "pkg",
        channelId = "channel",
        postedAtMillis = postedAtMillis,
        outcome = outcome,
    )
