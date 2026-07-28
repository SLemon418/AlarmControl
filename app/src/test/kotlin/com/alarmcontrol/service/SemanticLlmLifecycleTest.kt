package com.alarmcontrol.service

import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.ml.llm.LlmAnalysisResult
import com.alarmcontrol.ml.llm.LlmInitState
import com.alarmcontrol.ml.llm.LlmModelInfo
import com.alarmcontrol.ml.llm.OnDeviceLlmManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SemanticLlmLifecycleTest {
    @Test
    fun `disable waits for in-flight initialize then closes the native manager`() =
        runTest {
            val manager = BlockingLlmManager()
            val lifecycle = SemanticLlmLifecycle(manager)
            var current = true
            val initialize = async { lifecycle.initializeIfCurrent { current } }
            manager.initializeStarted.await()

            current = false
            val close = async { lifecycle.close() }
            runCurrent()
            assertEquals(0, manager.closeCalls)

            manager.allowInitialize.complete(Unit)

            assertFalse(initialize.await())
            close.await()
            assertEquals(LlmInitState.Idle, manager.initState.value)
            assertEquals(1, manager.closeCalls)
        }

    @Test
    fun `stale observation resuming after close cannot initialize`() =
        runTest {
            val manager = BlockingLlmManager()
            val lifecycle = SemanticLlmLifecycle(manager)
            var current = true
            val resumeObservation = CompletableDeferred<Unit>()
            val observationPaused = CompletableDeferred<Unit>()
            val initialize =
                async {
                    observationPaused.complete(Unit)
                    resumeObservation.await()
                    lifecycle.initializeIfCurrent { current }
                }
            observationPaused.await()

            current = false
            lifecycle.close()
            resumeObservation.complete(Unit)

            assertFalse(initialize.await())
            assertEquals(0, manager.initializeCalls)
            assertEquals(1, manager.closeCalls)
            assertEquals(LlmInitState.Idle, manager.initState.value)
        }

    private class BlockingLlmManager : OnDeviceLlmManager {
        private val mutableInitState = MutableStateFlow<LlmInitState>(LlmInitState.Idle)
        override val initState: StateFlow<LlmInitState> = mutableInitState
        override val modelInfo: StateFlow<LlmModelInfo?> = MutableStateFlow(null)
        val initializeStarted = CompletableDeferred<Unit>()
        val allowInitialize = CompletableDeferred<Unit>()
        var initializeCalls = 0
            private set
        var closeCalls = 0
            private set

        override suspend fun initialize() {
            initializeCalls += 1
            mutableInitState.value = LlmInitState.Loading
            initializeStarted.complete(Unit)
            allowInitialize.await()
            mutableInitState.value = LlmInitState.Ready
        }

        override suspend fun analyze(
            text: String,
            packageName: String?,
        ): LlmAnalysisResult? = null

        override suspend fun installModel(
            source: InputStream,
            expectedBytes: Long?,
        ): DataResult<Unit> = DataResult.Success(Unit)

        override suspend fun removeModel(): DataResult<Unit> = DataResult.Success(Unit)

        override suspend fun close() {
            closeCalls += 1
            mutableInitState.value = LlmInitState.Idle
        }
    }
}
