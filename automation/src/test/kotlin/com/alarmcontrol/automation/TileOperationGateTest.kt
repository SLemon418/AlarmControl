package com.alarmcontrol.automation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TileOperationGateTest {
    @Test
    fun `delayed refresh cannot overwrite a later click result`() =
        runTest {
            val gate = TileOperationGate()
            var filteringEnabled = true
            val publishedStates = mutableListOf<Boolean>()
            val refreshRead = CompletableDeferred<Unit>()
            val releaseRefresh = CompletableDeferred<Unit>()

            val refresh =
                async {
                    gate.run {
                        val state = filteringEnabled
                        refreshRead.complete(Unit)
                        releaseRefresh.await()
                        publishedStates += state
                    }
                }
            refreshRead.await()

            val click =
                async {
                    gate.run {
                        filteringEnabled = !filteringEnabled
                        publishedStates += filteringEnabled
                    }
                }
            runCurrent()

            assertFalse(click.isCompleted)
            releaseRefresh.complete(Unit)
            refresh.await()
            click.await()

            assertEquals(listOf(true, false), publishedStates)
            assertFalse(publishedStates.last())
        }
}
