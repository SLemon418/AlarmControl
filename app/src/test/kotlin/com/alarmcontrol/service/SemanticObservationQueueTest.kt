package com.alarmcontrol.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
