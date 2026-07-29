package com.alarmcontrol.core.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class FilteringActionGateTest {
    @Test
    fun `a newer block cannot be overwritten by stale listener initialization`() =
        runTest {
            val gate = FilteringActionGate()
            gate.acknowledgeRuleRefresh(gate.ruleRefreshRequests.value)

            gate.blockActions()
            gate.initializeFromPersistedState(true)

            assertFalse(gate.runIfAllowed {})
        }

    @Test
    fun `blocking suspends behind an active action and rejects every later action`() =
        runTest {
            val gate = FilteringActionGate()
            val actionStarted = CompletableDeferred<Unit>()
            val releaseAction = CountDownLatch(1)
            val blockReturned = CompletableDeferred<Unit>()
            gate.initializeFromPersistedState(true)
            gate.acknowledgeRuleRefresh(gate.ruleRefreshRequests.value)

            val actionJob =
                launch(Dispatchers.Default) {
                    gate.runIfAllowed {
                        actionStarted.complete(Unit)
                        check(releaseAction.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            actionStarted.await()
            val blockJob =
                launch {
                    gate.blockActions()
                    blockReturned.complete(Unit)
                }
            runCurrent()

            assertFalse(blockReturned.isCompleted)

            releaseAction.countDown()
            actionJob.join()
            blockJob.join()

            assertTrue(blockReturned.isCompleted)
            assertFalse(gate.runIfAllowed {})
        }

    @Test
    fun `explicit persisted enable reopens a blocked gate`() =
        runTest {
            val gate = FilteringActionGate()
            gate.acknowledgeRuleRefresh(gate.ruleRefreshRequests.value)
            gate.blockActions()

            gate.allowActions()

            assertTrue(gate.runIfAllowed {})
        }

    @Test
    fun `initial rule request keeps an enabled filter closed until acknowledged`() =
        runTest {
            val gate = FilteringActionGate()
            gate.initializeFromPersistedState(true)

            assertEquals(0L, gate.ruleRefreshRequests.value)
            assertFalse(gate.runIfAllowed {})

            gate.acknowledgeRuleRefresh(0L)

            assertTrue(gate.runIfAllowed {})
        }

    @Test
    fun `stale rule acknowledgement cannot reopen the gate`() =
        runTest {
            val gate = FilteringActionGate()
            gate.initializeFromPersistedState(true)
            val staleRequest = gate.requestRuleRefresh()
            val latestRequest = gate.requestRuleRefresh()

            gate.acknowledgeRuleRefresh(staleRequest)

            assertFalse(gate.runIfAllowed {})

            gate.acknowledgeRuleRefresh(latestRequest)

            assertTrue(gate.runIfAllowed {})
        }

    @Test
    fun `only the latest failed refresh can close a ready rule snapshot`() =
        runTest {
            val gate = FilteringActionGate()
            gate.initializeFromPersistedState(true)
            val staleRequest = gate.requestRuleRefresh()
            val latestRequest = gate.requestRuleRefresh()
            gate.acknowledgeRuleRefresh(latestRequest)

            gate.rejectRuleRefresh(staleRequest)
            assertTrue(gate.runIfAllowed {})

            gate.rejectRuleRefresh(latestRequest)
            assertFalse(gate.runIfAllowed {})
        }

    @Test
    fun `rule mutation closes before work and emits only after work finishes`() =
        runTest {
            val gate = FilteringActionGate()
            val mutationStarted = CompletableDeferred<Unit>()
            val releaseMutation = CompletableDeferred<Unit>()
            gate.initializeFromPersistedState(true)
            gate.acknowledgeRuleRefresh(gate.ruleRefreshRequests.value)

            val mutation =
                launch {
                    gate.withRuleMutation {
                        mutationStarted.complete(Unit)
                        releaseMutation.await()
                    }
                }
            mutationStarted.await()

            assertEquals(0L, gate.ruleRefreshRequests.value)
            assertFalse(gate.runIfAllowed {})
            gate.acknowledgeRuleRefresh(0L)
            assertFalse(gate.runIfAllowed {})
            val latestRequest = gate.requestRuleRefresh()
            assertEquals(2L, latestRequest)
            assertEquals(0L, gate.ruleRefreshRequests.value)

            releaseMutation.complete(Unit)
            mutation.join()

            assertEquals(latestRequest, gate.ruleRefreshRequests.value)
            assertFalse(gate.runIfAllowed {})
            gate.acknowledgeRuleRefresh(1L)
            assertFalse(gate.runIfAllowed {})
            gate.acknowledgeRuleRefresh(latestRequest)
            assertTrue(gate.runIfAllowed {})
        }

    @Test
    fun `rule mutations are serialized through one gate`() =
        runTest {
            val gate = FilteringActionGate()
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            var secondStarted = false

            val first =
                launch {
                    gate.withRuleMutation {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                }
            firstStarted.await()
            val second =
                launch {
                    gate.withRuleMutation {
                        secondStarted = true
                    }
                }
            runCurrent()

            assertFalse(secondStarted)

            releaseFirst.complete(Unit)
            first.join()
            second.join()

            assertTrue(secondStarted)
            assertEquals(2L, gate.ruleRefreshRequests.value)
        }

    private companion object {
        const val TEST_TIMEOUT_SECONDS = 2L
    }
}
