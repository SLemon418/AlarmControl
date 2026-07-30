package com.alarmcontrol.ml.llm

import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPipeFutureTest {
    @Test
    fun `completed response returns without requesting native cancellation`() =
        runTest {
            val future = SettableFuture.create<String>()
            var cancellationCalls = 0
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    awaitMediaPipeResponse(
                        future = future,
                        cancelGeneration = { cancellationCalls++ },
                    )
                }

            future.set("response")

            assertEquals("response", result.await())
            assertEquals(0, cancellationCalls)
        }

    @Test
    fun `coroutine cancellation requests native cancellation and drains before returning`() =
        runTest {
            val future = SettableFuture.create<String>()
            val events = mutableListOf<String>()
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    awaitMediaPipeResponse(
                        future = future,
                        cancelGeneration = { events += "cancel" },
                    )
                }

            result.cancel()
            runCurrent()

            assertEquals(listOf("cancel"), events)
            assertFalse(result.isCompleted)

            events += "native-complete"
            future.set("")
            result.join()

            assertTrue(result.isCancelled)
            assertTrue(result.isCompleted)
            assertEquals(listOf("cancel", "native-complete"), events)
        }

    @Test
    fun `native cancellation failure is suppressed without replacing caller cancellation`() =
        runTest {
            val future = SettableFuture.create<String>()
            val cancellation = CancellationException("caller cancelled")
            val cancellationFailure = IllegalStateException("native cancellation failed")
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    awaitMediaPipeResponse(
                        future = future,
                        cancelGeneration = { throw cancellationFailure },
                    )
                }

            result.cancel(cancellation)
            runCurrent()

            assertFalse(result.isCompleted)
            future.set("")
            result.join()

            assertTrue(result.isCancelled)
            assertEquals(listOf(cancellationFailure), cancellation.suppressed.toList())
        }

    @Test
    fun `never completing native future releases caller within bound and cleans up late`() =
        runBlocking {
            val future = SettableFuture.create<String>()
            var terminalCallbacks = 0
            val result =
                async(
                    context = Dispatchers.Default,
                    start = CoroutineStart.UNDISPATCHED,
                ) {
                    awaitMediaPipeResponse(
                        future = future,
                        cancelGeneration = {},
                        onTerminal = { terminalCallbacks++ },
                    )
                }

            result.cancel()
            withTimeout(MEDIAPIPE_CANCELLATION_DRAIN_TIMEOUT_MILLIS * 3) {
                result.join()
            }

            assertTrue(result.isCancelled)
            assertEquals(0, terminalCallbacks)

            future.set("late")
            assertEquals(1, terminalCallbacks)
        }
}
