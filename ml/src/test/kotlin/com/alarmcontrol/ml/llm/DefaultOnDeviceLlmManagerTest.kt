package com.alarmcontrol.ml.llm

import com.alarmcontrol.core.result.DataResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultOnDeviceLlmManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `initial state is Idle`() =
        runTest {
            val manager = DefaultOnDeviceLlmManager(FakeLlmEngine(), UnconfinedTestDispatcher(testScheduler))
            assertEquals(LlmInitState.Idle, manager.initState.value)
        }

    @Test
    fun `initialize emits Loading then Ready when the model is available`() =
        runTest {
            val engine = FakeLlmEngine(available = true)
            val manager = DefaultOnDeviceLlmManager(engine, UnconfinedTestDispatcher(testScheduler))
            val states = mutableListOf<LlmInitState>()
            val job = launch(UnconfinedTestDispatcher(testScheduler)) { manager.initState.collect { states.add(it) } }

            manager.initialize()

            assertEquals(listOf(LlmInitState.Idle, LlmInitState.Loading, LlmInitState.Ready), states)
            assertEquals(1, engine.loadCalls)
            job.cancel()
        }

    @Test
    fun `initialize reports Unavailable when the model file is missing`() =
        runTest {
            val engine = FakeLlmEngine(available = false)
            val manager = DefaultOnDeviceLlmManager(engine, UnconfinedTestDispatcher(testScheduler))

            manager.initialize()

            val state = manager.initState.value
            assertTrue(state is LlmInitState.Unavailable)
            assertEquals(LlmFailure.MODEL_MISSING, (state as LlmInitState.Unavailable).failure)
            assertEquals(0, engine.loadCalls) // never attempts to load a missing model
        }

    @Test
    fun `initialize reports Unavailable when loading fails`() =
        runTest {
            val engine = FakeLlmEngine(available = true, loadError = IllegalStateException("corrupt model"))
            val manager = DefaultOnDeviceLlmManager(engine, UnconfinedTestDispatcher(testScheduler))

            manager.initialize()

            val state = manager.initState.value
            assertTrue(state is LlmInitState.Unavailable)
            assertEquals(LlmFailure.LOAD_FAILED, (state as LlmInitState.Unavailable).failure)
        }

    @Test
    fun `analyze returns null until the engine is Ready`() =
        runTest {
            val engine = FakeLlmEngine(available = true)
            val manager = DefaultOnDeviceLlmManager(engine, UnconfinedTestDispatcher(testScheduler))

            assertNull(manager.analyze("Flash sale 50% off!"))
            assertEquals(0, engine.analyzeCalls)
        }

    @Test
    fun `analyze delegates to the engine once Ready`() =
        runTest {
            val engine = FakeLlmEngine(available = true)
            val manager = DefaultOnDeviceLlmManager(engine, UnconfinedTestDispatcher(testScheduler))

            manager.initialize()
            val result = manager.analyze("Flash sale 50% off!")

            assertEquals(LlmAnalysisResult.of(true, 0.9f, "promotional language detected"), result)
            assertEquals(1, engine.analyzeCalls)
        }

    @Test
    fun `analyze returns null without throwing when inference fails`() =
        runTest {
            val engine = FakeLlmEngine(available = true, analyzeError = RuntimeException("oom"))
            val manager = DefaultOnDeviceLlmManager(engine, UnconfinedTestDispatcher(testScheduler))

            manager.initialize()

            assertNull(manager.analyze("anything"))
        }

    @Test
    fun `low confidence analysis becomes unavailable after local adjustment`() =
        runTest {
            val engine =
                FakeLlmEngine(
                    result =
                        LlmAnalysisResult.of(
                            com.alarmcontrol.core.filtering.SemanticIntent.MARKETING,
                            0.59f,
                            "uncertain",
                        ),
                )
            val manager = DefaultOnDeviceLlmManager(engine, UnconfinedTestDispatcher(testScheduler))
            manager.initialize()

            assertEquals(LlmAnalysisResult.UNAVAILABLE, manager.analyze("maybe promotional"))
        }

    @Test
    fun `initialize is idempotent once Ready`() =
        runTest {
            val engine = FakeLlmEngine(available = true)
            val manager = DefaultOnDeviceLlmManager(engine, UnconfinedTestDispatcher(testScheduler))

            manager.initialize()
            manager.initialize()

            assertEquals(1, engine.loadCalls)
        }

    @Test
    fun `concurrent initialization loads the native engine exactly once`() =
        runTest {
            val engine = FakeLlmEngine(available = true)
            val manager = DefaultOnDeviceLlmManager(engine, Dispatchers.Default)

            coroutineScope {
                List(16) { async(Dispatchers.Default) { manager.initialize() } }.awaitAll()
            }

            assertEquals(1, engine.loadCalls)
            assertEquals(LlmInitState.Ready, manager.initState.value)
        }

    @Test
    fun `concurrent analyses are serialized around the native engine`() =
        runTest {
            val engine = ConcurrentProbeEngine()
            val manager = DefaultOnDeviceLlmManager(engine, Dispatchers.Default)
            manager.initialize()

            coroutineScope {
                List(12) { async(Dispatchers.Default) { manager.analyze("notification $it") } }.awaitAll()
            }

            assertEquals(1, engine.maxConcurrent.get())
        }

    @Test
    fun `analysis queue keeps one running and one waiting then degrades gracefully`() =
        runTest {
            val engine = GateEngine()
            val manager = DefaultOnDeviceLlmManager(engine, Dispatchers.Default)
            manager.initialize()

            val first = async(Dispatchers.Default) { manager.analyze("first") }
            engine.started.await()
            val second =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    manager.analyze("second")
                }

            assertNull(manager.analyze("overflow"))
            engine.release.complete(Unit)
            awaitAll(first, second)
            assertEquals(2, engine.analyzeCalls.get())
        }

    @Test
    fun `close releases the engine and returns to Idle`() =
        runTest {
            val engine = FakeLlmEngine(available = true)
            val manager = DefaultOnDeviceLlmManager(engine, UnconfinedTestDispatcher(testScheduler))

            manager.initialize()
            manager.close()

            assertEquals(LlmInitState.Idle, manager.initState.value)
            assertTrue(engine.closed)
        }

    @Test
    fun `install model copies locally and verifies the engine`() =
        runTest {
            val engine = FakeLlmEngine(available = true)
            val model = temporaryFolder.root.resolve("llm/model.task")
            val manager =
                DefaultOnDeviceLlmManager(
                    engine,
                    UnconfinedTestDispatcher(testScheduler),
                    LocalLlmModelStore(model),
                )

            val result = manager.installModel(byteArrayOf(1, 2, 3).inputStream())

            assertTrue(result is DataResult.Success)
            assertEquals(LlmInitState.Ready, manager.initState.value)
            assertEquals(1, engine.loadCalls)
            assertEquals(listOf<Byte>(1, 2, 3), model.readBytes().toList())
        }

    @Test
    fun `install exposes progress state while copying before native loading`() =
        runTest {
            val model = temporaryFolder.root.resolve("llm/progress.task")
            val manager =
                DefaultOnDeviceLlmManager(
                    FakeLlmEngine(),
                    UnconfinedTestDispatcher(testScheduler),
                    LocalLlmModelStore(model),
                )
            var stateDuringCopy: LlmInitState? = null
            val source =
                object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (stateDuringCopy == null) stateDuringCopy = manager.initState.value
                        return super.read(buffer, offset, length)
                    }
                }

            manager.installModel(source, expectedBytes = 3)

            assertEquals(LlmInitState.Installing(copiedBytes = 0, totalBytes = 3), stateDuringCopy)
            assertEquals(LlmInitState.Ready, manager.initState.value)
        }

    @Test
    fun `invalid replacement rolls back and reloads a previously working model`() =
        runTest {
            val model = temporaryFolder.newFile("rollback.task").apply { writeBytes(byteArrayOf(1)) }
            val engine = FileAwareEngine(model)
            val manager =
                DefaultOnDeviceLlmManager(
                    engine,
                    UnconfinedTestDispatcher(testScheduler),
                    LocalLlmModelStore(model),
                )
            manager.initialize()

            val result = manager.installModel(byteArrayOf(2).inputStream(), expectedBytes = 1)

            assertTrue(result is DataResult.Failure)
            assertEquals(listOf<Byte>(1), model.readBytes().toList())
            assertEquals(LlmInitState.Ready, manager.initState.value)
            assertEquals(3, engine.loadCalls)
        }

    @Test
    fun `remove model closes the engine deletes the private file and resets state`() =
        runTest {
            val model = temporaryFolder.newFile("remove.task").apply { writeBytes(byteArrayOf(1)) }
            val engine = FakeLlmEngine(available = true)
            val manager =
                DefaultOnDeviceLlmManager(
                    engine,
                    UnconfinedTestDispatcher(testScheduler),
                    LocalLlmModelStore(model),
                )
            manager.initialize()

            val result = manager.removeModel()

            assertTrue(result is DataResult.Success)
            assertTrue(!model.exists())
            assertTrue(engine.closed)
            assertEquals(LlmInitState.Idle, manager.initState.value)
        }

    private class ConcurrentProbeEngine : LlmEngine {
        val maxConcurrent = AtomicInteger(0)
        private val active = AtomicInteger(0)

        override fun isModelAvailable(): Boolean = true

        override fun load() = Unit

        override suspend fun analyze(text: String): LlmAnalysisResult {
            val current = active.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, current) }
            try {
                delay(5)
                return LlmAnalysisResult.of(false, 0.9f, "transactional")
            } finally {
                active.decrementAndGet()
            }
        }

        override fun close() = Unit
    }

    private class FileAwareEngine(
        private val model: java.io.File,
    ) : LlmEngine {
        var loadCalls = 0

        override fun isModelAvailable(): Boolean = model.isFile && model.length() > 0

        override fun load() {
            loadCalls++
            check(model.readBytes().first() == 1.toByte()) { "incompatible model" }
        }

        override suspend fun analyze(text: String): LlmAnalysisResult = LlmAnalysisResult.UNAVAILABLE

        override fun close() = Unit
    }

    private class GateEngine : LlmEngine {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val analyzeCalls = AtomicInteger(0)

        override fun isModelAvailable(): Boolean = true

        override fun load() = Unit

        override suspend fun analyze(text: String): LlmAnalysisResult {
            analyzeCalls.incrementAndGet()
            started.complete(Unit)
            release.await()
            return LlmAnalysisResult.of(false, 0.9f, "transactional")
        }

        override fun close() = Unit
    }
}
