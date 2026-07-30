package com.alarmcontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AlarmControlApplicationTest {
    @Test
    fun `enqueue retries asynchronous failures until work is durable`() =
        runTest {
            var attempts = 0
            val failures = mutableListOf<Throwable>()

            retryWorkEnqueue(
                initialRetryMillis = 1,
                maxRetryMillis = 4,
                onFailure = failures::add,
            ) {
                attempts += 1
                if (attempts < 3) error("work database unavailable")
            }

            assertEquals(3, attempts)
            assertEquals(2, failures.size)
        }

    @Test
    fun `enqueue retry preserves structured cancellation`() =
        runTest {
            var failureCallbacks = 0

            try {
                retryWorkEnqueue(
                    initialRetryMillis = 1,
                    maxRetryMillis = 1,
                    onFailure = { failureCallbacks += 1 },
                ) {
                    throw CancellationException("application stopped")
                }
                fail("Cancellation must escape")
            } catch (_: CancellationException) {
                assertEquals(0, failureCallbacks)
            }
        }

    @Test
    fun `profile observation resubscribes after an upstream database failure`() =
        runTest {
            var subscriptions = 0
            val failures = mutableListOf<Throwable>()
            val profiles =
                flow {
                    subscriptions += 1
                    if (subscriptions < 3) error("profile database unavailable")
                    emit(listOf("focus"))
                }.retryWithBackoff(
                    initialRetryMillis = 1,
                    maxRetryMillis = 4,
                    onFailure = failures::add,
                ).first()

            assertEquals(listOf("focus"), profiles)
            assertEquals(3, subscriptions)
            assertEquals(2, failures.size)
        }

    @Test
    fun `profile observation retry preserves structured cancellation`() =
        runTest {
            var failureCallbacks = 0

            try {
                flow<Unit> {
                    throw CancellationException("application stopped")
                }.retryWithBackoff(
                    initialRetryMillis = 1,
                    maxRetryMillis = 1,
                    onFailure = { failureCallbacks += 1 },
                ).first()
                fail("Cancellation must escape")
            } catch (_: CancellationException) {
                assertEquals(0, failureCallbacks)
            }
        }

    @Test
    fun `idempotent local operation retries a rejected shortcut publication`() =
        runTest {
            var attempts = 0

            retryIdempotentOperation(
                initialRetryMillis = 1,
                maxRetryMillis = 2,
            ) {
                attempts += 1
                check(attempts >= 2) { "shortcut manager rejected the update" }
            }

            assertEquals(2, attempts)
        }
}
