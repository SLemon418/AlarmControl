package com.alarmcontrol.ml.semantic

import com.alarmcontrol.ml.SemanticInferenceUrgency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedSemanticInferenceRunnerTest {
    @Test
    fun `realtime evicts one queued background inference`() =
        runTest {
            val runner = BoundedSemanticInferenceRunner()
            val runningStarted = CountDownLatch(1)
            val releaseRunning = CountDownLatch(1)
            val evictedRan = AtomicBoolean()
            val realtimeStarted = CountDownLatch(1)
            val running =
                async {
                    runner.run(SemanticInferenceUrgency.BACKGROUND) {
                        runningStarted.countDown()
                        check(releaseRunning.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        floatArrayOf(1f)
                    }
                }
            runCurrent()
            assertTrue(runningStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val queuedBackground =
                async {
                    runner.run(SemanticInferenceUrgency.BACKGROUND) {
                        evictedRan.set(true)
                        floatArrayOf(2f)
                    }
                }
            runCurrent()

            val realtime =
                async {
                    runner.run(SemanticInferenceUrgency.REALTIME) {
                        realtimeStarted.countDown()
                        floatArrayOf(3f)
                    }
                }
            runCurrent()

            assertNull(queuedBackground.await())
            assertFalse(evictedRan.get())
            releaseRunning.countDown()
            assertTrue(realtimeStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            runCurrent()
            assertArrayEquals(floatArrayOf(1f), requireNotNull(running.await()), 0f)
            assertArrayEquals(floatArrayOf(3f), requireNotNull(realtime.await()), 0f)
        }

    @Test
    fun `realtime never evicts queued realtime inference`() =
        runTest {
            val runner = BoundedSemanticInferenceRunner()
            val runningStarted = CountDownLatch(1)
            val releaseRunning = CountDownLatch(1)
            val queuedRan = AtomicBoolean()
            val overflowRan = AtomicBoolean()
            val running =
                async {
                    runner.run(SemanticInferenceUrgency.BACKGROUND) {
                        runningStarted.countDown()
                        check(releaseRunning.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        floatArrayOf(1f)
                    }
                }
            runCurrent()
            assertTrue(runningStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val queuedRealtime =
                async {
                    runner.run(SemanticInferenceUrgency.REALTIME) {
                        queuedRan.set(true)
                        floatArrayOf(2f)
                    }
                }
            runCurrent()
            val overflowRealtime =
                async {
                    runner.run(SemanticInferenceUrgency.REALTIME) {
                        overflowRan.set(true)
                        floatArrayOf(3f)
                    }
                }
            runCurrent()

            assertNull(overflowRealtime.await())
            assertFalse(overflowRan.get())
            releaseRunning.countDown()
            runCurrent()
            assertArrayEquals(floatArrayOf(1f), requireNotNull(running.await()), 0f)
            assertArrayEquals(floatArrayOf(2f), requireNotNull(queuedRealtime.await()), 0f)
            assertTrue(queuedRan.get())
        }

    @Test
    fun `native inference concurrency remains one`() =
        runTest {
            val runner = BoundedSemanticInferenceRunner()
            val active = AtomicInteger()
            val maximumActive = AtomicInteger()
            val runningStarted = CountDownLatch(1)
            val releaseRunning = CountDownLatch(1)
            val queuedStarted = CountDownLatch(1)

            fun enterNative() {
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { maximum -> maxOf(maximum, current) }
            }

            val running =
                async {
                    runner.run(SemanticInferenceUrgency.BACKGROUND) {
                        enterNative()
                        try {
                            runningStarted.countDown()
                            check(releaseRunning.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            floatArrayOf(1f)
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            runCurrent()
            assertTrue(runningStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val queued =
                async {
                    runner.run(SemanticInferenceUrgency.BACKGROUND) {
                        enterNative()
                        try {
                            queuedStarted.countDown()
                            floatArrayOf(2f)
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            runCurrent()

            assertEquals(1L, queuedStarted.count)
            assertEquals(1, maximumActive.get())
            releaseRunning.countDown()
            assertTrue(queuedStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            runCurrent()
            assertArrayEquals(floatArrayOf(1f), requireNotNull(running.await()), 0f)
            assertArrayEquals(floatArrayOf(2f), requireNotNull(queued.await()), 0f)
            assertEquals(1, maximumActive.get())
        }

    @Test
    fun `queued cancellation returns admission and overflow remains fail open`() =
        runTest {
            val runner = BoundedSemanticInferenceRunner()
            val runningStarted = CountDownLatch(1)
            val releaseRunning = CountDownLatch(1)
            val cancelledRan = AtomicBoolean()
            val replacementRan = AtomicBoolean()
            val overflowRan = AtomicBoolean()
            val running =
                async {
                    runner.run(SemanticInferenceUrgency.BACKGROUND) {
                        runningStarted.countDown()
                        check(releaseRunning.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        floatArrayOf(1f)
                    }
                }
            runCurrent()
            assertTrue(runningStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val cancelled =
                async {
                    runner.run(SemanticInferenceUrgency.BACKGROUND) {
                        cancelledRan.set(true)
                        floatArrayOf(2f)
                    }
                }
            runCurrent()
            val initialOverflow =
                async {
                    runner.run(SemanticInferenceUrgency.BACKGROUND) {
                        overflowRan.set(true)
                        floatArrayOf(3f)
                    }
                }
            runCurrent()
            assertNull(initialOverflow.await())

            cancelled.cancelAndJoin()
            val replacement =
                async {
                    runner.run(SemanticInferenceUrgency.BACKGROUND) {
                        replacementRan.set(true)
                        floatArrayOf(4f)
                    }
                }
            runCurrent()
            val replacementOverflow =
                async {
                    runner.run(SemanticInferenceUrgency.BACKGROUND) {
                        overflowRan.set(true)
                        floatArrayOf(5f)
                    }
                }
            runCurrent()

            assertNull(replacementOverflow.await())
            assertFalse(cancelledRan.get())
            assertFalse(overflowRan.get())
            releaseRunning.countDown()
            runCurrent()
            assertArrayEquals(floatArrayOf(1f), requireNotNull(running.await()), 0f)
            assertArrayEquals(floatArrayOf(4f), requireNotNull(replacement.await()), 0f)
            assertTrue(replacementRan.get())
        }

    @Test
    fun `queued cancellation racing realtime eviction completes once without leaking admission`() =
        runTest {
            val runner = BoundedSemanticInferenceRunner()

            repeat(RACE_ITERATIONS) {
                val runningStarted = CountDownLatch(1)
                val releaseRunning = CountDownLatch(1)
                val queuedRan = AtomicBoolean()
                val realtimeStarted = CountDownLatch(1)
                val overflowRan = AtomicBoolean()
                val running =
                    async {
                        runner.run(SemanticInferenceUrgency.BACKGROUND) {
                            runningStarted.countDown()
                            check(releaseRunning.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            floatArrayOf(1f)
                        }
                    }
                runCurrent()
                assertTrue(runningStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val queued =
                    async {
                        runner.run(SemanticInferenceUrgency.BACKGROUND) {
                            queuedRan.set(true)
                            floatArrayOf(2f)
                        }
                    }
                runCurrent()

                val raceReady = CountDownLatch(2)
                val startRace = CountDownLatch(1)
                val realtime =
                    async(Dispatchers.Default) {
                        raceReady.countDown()
                        check(startRace.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        runner.run(SemanticInferenceUrgency.REALTIME) {
                            realtimeStarted.countDown()
                            floatArrayOf(3f)
                        }
                    }
                val cancellation =
                    async(Dispatchers.Default) {
                        raceReady.countDown()
                        check(startRace.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        queued.cancel()
                    }
                assertTrue(raceReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                startRace.countDown()
                cancellation.await()
                queued.join()

                val overflow =
                    runner.run(SemanticInferenceUrgency.BACKGROUND) {
                        overflowRan.set(true)
                        floatArrayOf(4f)
                    }
                assertNull(overflow)
                assertFalse(queuedRan.get())
                assertFalse(overflowRan.get())

                releaseRunning.countDown()
                assertTrue(realtimeStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                runCurrent()
                assertArrayEquals(floatArrayOf(1f), requireNotNull(running.await()), 0f)
                assertArrayEquals(floatArrayOf(3f), requireNotNull(realtime.await()), 0f)

                val probe =
                    runner.run(SemanticInferenceUrgency.BACKGROUND) {
                        floatArrayOf(5f)
                    }
                assertArrayEquals(floatArrayOf(5f), requireNotNull(probe), 0f)
            }
        }

    private companion object {
        const val RACE_ITERATIONS = 25
        const val TIMEOUT_SECONDS = 2L
    }
}
