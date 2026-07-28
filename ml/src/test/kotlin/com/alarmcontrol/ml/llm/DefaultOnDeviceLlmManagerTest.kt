package com.alarmcontrol.ml.llm

import com.alarmcontrol.core.result.DataResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun `idle ttl closes the native engine and permits lazy reload`() =
        runTest {
            val engine = FakeLlmEngine(available = true)
            val manager =
                DefaultOnDeviceLlmManager(
                    engine = engine,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    idleTtlMillis = 60_000L,
                )

            manager.initialize()
            manager.analyze("Flash sale 50% off!")
            advanceTimeBy(60_000L)
            runCurrent()

            assertEquals(LlmInitState.Idle, manager.initState.value)
            assertTrue(engine.closed)

            manager.initialize()
            assertEquals(LlmInitState.Ready, manager.initState.value)
            assertEquals(2, engine.loadCalls)
        }

    @Test
    fun `new analysis refreshes idle ttl`() =
        runTest {
            val engine = FakeLlmEngine(available = true)
            val manager =
                DefaultOnDeviceLlmManager(
                    engine = engine,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    idleTtlMillis = 60_000L,
                )

            manager.initialize()
            advanceTimeBy(50_000L)
            manager.analyze("First notification")
            advanceTimeBy(50_000L)
            runCurrent()

            assertEquals(LlmInitState.Ready, manager.initState.value)

            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(LlmInitState.Idle, manager.initState.value)
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
    fun `idempotent initialize does not disable the existing idle ttl`() =
        runTest {
            val engine = FakeLlmEngine(available = true)
            val manager =
                DefaultOnDeviceLlmManager(
                    engine = engine,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    idleTtlMillis = 60_000L,
                )

            manager.initialize()
            advanceTimeBy(30_000L)
            manager.initialize()
            advanceTimeBy(30_000L)
            runCurrent()

            assertEquals(LlmInitState.Idle, manager.initState.value)
            assertTrue(engine.closed)
            assertEquals(1, engine.loadCalls)
        }

    @Test
    fun `initialize contains native linkage failures and keeps the actor responsive`() =
        runTest {
            val engine = FakeLlmEngine(available = true, loadError = UnsatisfiedLinkError("unsupported ABI"))
            val manager = DefaultOnDeviceLlmManager(engine, UnconfinedTestDispatcher(testScheduler))

            manager.initialize()
            assertEquals(
                LlmInitState.Unavailable(LlmFailure.LOAD_FAILED),
                manager.initState.value,
            )
            assertTrue(engine.closed)
            manager.close()

            assertEquals(LlmInitState.Idle, manager.initState.value)
            assertTrue(engine.closed)
        }

    @Test
    fun `native analysis linkage failure revokes and closes the engine`() =
        runTest {
            val engine =
                FakeLlmEngine(
                    available = true,
                    analyzeError = UnsatisfiedLinkError("native inference failed"),
                )
            val manager = DefaultOnDeviceLlmManager(engine, UnconfinedTestDispatcher(testScheduler))
            manager.initialize()

            assertNull(manager.analyze("notification"))

            assertEquals(
                LlmInitState.Unavailable(LlmFailure.LOAD_FAILED),
                manager.initState.value,
            )
            assertTrue(engine.closed)
            assertNull(manager.analyze("must not reuse failed runtime"))
            assertEquals(1, engine.analyzeCalls)
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
    fun `new initialize supersedes a queued close without closing the reloaded engine`() =
        runTest {
            val engine = GateEngine()
            val manager = DefaultOnDeviceLlmManager(engine, Dispatchers.Default)
            manager.initialize()
            val analysis = async(Dispatchers.Default) { manager.analyze("hold actor") }
            engine.started.await()

            val close =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    manager.close()
                }
            val initialize =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    manager.initialize()
                }
            engine.release.complete(Unit)

            analysis.await()
            close.await()
            initialize.await()
            assertEquals(LlmInitState.Ready, manager.initState.value)
            assertEquals(2, engine.loadCalls.get())
            assertEquals(0, engine.closeCalls.get())
        }

    @Test
    fun `new install supersedes a queued removal without deleting or closing its model`() =
        runTest {
            val model = temporaryFolder.root.resolve("llm/stale-removal.task")
            val store = LocalLlmModelStore(model)
            store.install(byteArrayOf(1).inputStream())
            val engine = GateEngine()
            val manager = DefaultOnDeviceLlmManager(engine, Dispatchers.Default, store)
            manager.initialize()
            val analysis = async(Dispatchers.Default) { manager.analyze("hold actor") }
            engine.started.await()

            val removal =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    manager.removeModel()
                }
            val installation =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    manager.installModel(byteArrayOf(2).inputStream(), expectedBytes = 1)
                }
            engine.release.complete(Unit)

            analysis.await()
            assertTrue(removal.await() is DataResult.Failure)
            assertTrue(installation.await() is DataResult.Success)
            assertEquals(LlmInitState.Ready, manager.initState.value)
            assertEquals(listOf<Byte>(2), model.readBytes().toList())
            assertEquals(2, engine.loadCalls.get())
            assertEquals(1, engine.closeCalls.get())
        }

    @Test
    fun `hung native analysis revokes the engine before another request can overlap`() =
        runTest {
            val engine = NeverCompletingEngine()
            val manager = DefaultOnDeviceLlmManager(engine, UnconfinedTestDispatcher(testScheduler))
            manager.initialize()

            val result = async { manager.analyze("stuck") }
            engine.started.await()
            advanceTimeBy(9_001)
            runCurrent()

            assertNull(result.await())
            assertTrue(manager.initState.value is LlmInitState.Unavailable)
            assertNull(manager.analyze("must not overlap"))
            assertEquals(1, engine.analyzeCalls)
            manager.close()
            assertTrue(engine.closed)
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
            assertEquals(3L, manager.modelInfo.value?.sizeBytes)
            assertEquals(
                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                manager.modelInfo.value?.sha256,
            )
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
    fun `cancelled install caller cannot start a second overlapping installation`() =
        runTest {
            val engine = GateEngine()
            val model = temporaryFolder.root.resolve("llm/cancelled-install.task")
            val store = LocalLlmModelStore(model)
            store.install(byteArrayOf(0).inputStream())
            val manager =
                DefaultOnDeviceLlmManager(
                    engine,
                    Dispatchers.Default,
                    store,
                )
            manager.initialize()
            val analysis = async(Dispatchers.Default) { manager.analyze("hold actor") }
            engine.started.await()
            val firstInstall =
                async(Dispatchers.Default) {
                    manager.installModel(byteArrayOf(1).inputStream(), expectedBytes = 1)
                }
            withTimeout(1_000) {
                while (manager.initState.value !is LlmInitState.Installing) yield()
            }

            firstInstall.cancelAndJoin()
            val overlapping = manager.installModel(byteArrayOf(2).inputStream(), expectedBytes = 1)

            assertTrue(overlapping is DataResult.Failure)
            engine.release.complete(Unit)
            analysis.await()
            manager.close()
            val afterCleanup = manager.installModel(byteArrayOf(3).inputStream(), expectedBytes = 1)
            assertTrue(afterCleanup is DataResult.Success)
        }

    @Test
    fun `invalid replacement rollback keeps idle ttl for the restored model`() =
        runTest {
            val model = temporaryFolder.root.resolve("rollback.task")
            val store = LocalLlmModelStore(model)
            store.install(byteArrayOf(1).inputStream())
            val engine = FileAwareEngine(model)
            val manager =
                DefaultOnDeviceLlmManager(
                    engine,
                    UnconfinedTestDispatcher(testScheduler),
                    store,
                )
            manager.initialize()

            val result = manager.installModel(byteArrayOf(2).inputStream(), expectedBytes = 1)

            assertTrue(result is DataResult.Failure)
            assertEquals(listOf<Byte>(1), model.readBytes().toList())
            assertEquals(LlmInitState.Ready, manager.initState.value)
            assertEquals(3, engine.loadCalls)

            advanceTimeBy(60_000L)
            runCurrent()

            assertEquals(LlmInitState.Idle, manager.initState.value)
        }

    @Test
    fun `staging failure keeps idle ttl for the existing ready model`() =
        runTest {
            val model = temporaryFolder.root.resolve("staging-failure.task")
            val store = LocalLlmModelStore(model)
            store.install(byteArrayOf(1).inputStream())
            val engine = FakeLlmEngine(available = true)
            val manager =
                DefaultOnDeviceLlmManager(
                    engine,
                    UnconfinedTestDispatcher(testScheduler),
                    store,
                )
            manager.initialize()

            val result = manager.installModel(byteArrayOf().inputStream(), expectedBytes = 1)

            assertTrue(result is DataResult.Failure)
            assertEquals(LlmInitState.Ready, manager.initState.value)
            assertEquals(1, engine.loadCalls)

            advanceTimeBy(60_000L)
            runCurrent()

            assertEquals(LlmInitState.Idle, manager.initState.value)
            assertTrue(engine.closed)
        }

    @Test
    fun `failed rollback reload closes the partially restored native engine`() =
        runTest {
            val model = temporaryFolder.root.resolve("failed-rollback-reload.task")
            val store = LocalLlmModelStore(model)
            store.install(byteArrayOf(1).inputStream())
            val engine = RestoreFailureEngine()
            val manager =
                DefaultOnDeviceLlmManager(
                    engine,
                    UnconfinedTestDispatcher(testScheduler),
                    store,
                )
            manager.initialize()

            val result = manager.installModel(byteArrayOf(2).inputStream(), expectedBytes = 1)

            assertTrue(result is DataResult.Failure)
            assertEquals(listOf<Byte>(1), model.readBytes().toList())
            assertEquals(
                LlmInitState.Unavailable(LlmFailure.LOAD_FAILED),
                manager.initState.value,
            )
            assertEquals(3, engine.loadCalls)
            assertEquals(3, engine.closeCalls)
        }

    @Test
    fun `unexpected install oom fails safely and leaves the actor reusable`() =
        runTest {
            val model = temporaryFolder.root.resolve("install-oom.task")
            val engine = FakeLlmEngine()
            val manager =
                DefaultOnDeviceLlmManager(
                    engine,
                    UnconfinedTestDispatcher(testScheduler),
                    LocalLlmModelStore(model),
                )
            val failingSource =
                object : InputStream() {
                    override fun read(): Int = throw OutOfMemoryError("copy failed")
                }

            val failure = manager.installModel(failingSource, expectedBytes = 1)

            assertTrue(failure is DataResult.Failure)
            assertEquals(
                LlmInitState.Unavailable(LlmFailure.LOAD_FAILED),
                manager.initState.value,
            )
            assertTrue(engine.closed)

            val recovery = manager.installModel(byteArrayOf(1).inputStream(), expectedBytes = 1)
            assertTrue(recovery is DataResult.Success)
            assertEquals(LlmInitState.Ready, manager.initState.value)
            assertEquals(listOf<Byte>(1), model.readBytes().toList())
        }

    @Test
    fun `initialize recovers the previous model after activation was interrupted`() =
        runTest {
            val model = temporaryFolder.root.resolve("interrupted-activation.task")
            val store = LocalLlmModelStore(model)
            store.install(byteArrayOf(1).inputStream())
            store.stage(byteArrayOf(2).inputStream()).activate()
            val engine = FileAwareEngine(model)
            val manager =
                DefaultOnDeviceLlmManager(
                    engine,
                    UnconfinedTestDispatcher(testScheduler),
                    store,
                )

            manager.initialize()

            assertEquals(LlmInitState.Ready, manager.initState.value)
            assertEquals(listOf<Byte>(1), model.readBytes().toList())
            assertEquals(1L, manager.modelInfo.value?.sizeBytes)
            assertEquals(2, engine.loadCalls)
        }

    @Test
    fun `initialize rejects a model changed after import before native loading`() =
        runTest {
            val model = temporaryFolder.root.resolve("tampered.task")
            val store = LocalLlmModelStore(model)
            store.install(byteArrayOf(1).inputStream())
            model.appendBytes(byteArrayOf(2))
            val engine = FakeLlmEngine(available = true)
            val manager =
                DefaultOnDeviceLlmManager(
                    engine,
                    UnconfinedTestDispatcher(testScheduler),
                    store,
                )

            manager.initialize()

            assertEquals(
                LlmInitState.Unavailable(LlmFailure.MODEL_INTEGRITY_FAILED),
                manager.initState.value,
            )
            assertNull(manager.modelInfo.value)
            assertEquals(0, engine.loadCalls)
        }

    @Test
    fun `remove model closes the engine deletes the private file and resets state`() =
        runTest {
            val model = temporaryFolder.root.resolve("remove.task")
            val store = LocalLlmModelStore(model)
            store.install(byteArrayOf(1).inputStream())
            val engine = FakeLlmEngine(available = true)
            val manager =
                DefaultOnDeviceLlmManager(
                    engine,
                    UnconfinedTestDispatcher(testScheduler),
                    store,
                )
            manager.initialize()

            val result = manager.removeModel()

            assertTrue(result is DataResult.Success)
            assertTrue(!model.exists())
            assertTrue(engine.closed)
            assertEquals(LlmInitState.Idle, manager.initState.value)
            assertNull(manager.modelInfo.value)
        }

    @Test
    fun `native close failure cannot prevent private model deletion`() =
        runTest {
            val model = temporaryFolder.root.resolve("close-failure.task")
            val store = LocalLlmModelStore(model)
            store.install(byteArrayOf(1).inputStream())
            val manager =
                DefaultOnDeviceLlmManager(
                    FakeLlmEngine(available = true, closeError = UnsatisfiedLinkError("native close")),
                    UnconfinedTestDispatcher(testScheduler),
                    store,
                )

            val result = manager.removeModel()

            assertTrue(result is DataResult.Success)
            assertTrue(!model.exists())
            assertEquals(LlmInitState.Idle, manager.initState.value)
        }

    @Test
    fun `remove during model copy aborts staging before native loading`() =
        runTest {
            val model = temporaryFolder.root.resolve("remove-during-copy.task")
            val engine = FakeLlmEngine(available = true)
            val manager =
                DefaultOnDeviceLlmManager(
                    engine,
                    Dispatchers.Default,
                    LocalLlmModelStore(model),
                )
            val source = BlockingModelInputStream()
            val installation =
                async(Dispatchers.Default) {
                    manager.installModel(source, expectedBytes = 1)
                }
            assertTrue(source.started.await(1, TimeUnit.SECONDS))

            val removal = async(Dispatchers.Default) { manager.removeModel() }
            withTimeout(1_000) {
                while (manager.initState.value != LlmInitState.Idle) yield()
            }
            source.release.countDown()

            assertTrue(installation.await() is DataResult.Failure)
            assertTrue(removal.await() is DataResult.Success)
            assertTrue(!model.exists())
            assertEquals(0, engine.loadCalls)
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
        val loadCalls = AtomicInteger(0)
        val closeCalls = AtomicInteger(0)

        override fun isModelAvailable(): Boolean = true

        override fun load() {
            loadCalls.incrementAndGet()
        }

        override suspend fun analyze(text: String): LlmAnalysisResult {
            analyzeCalls.incrementAndGet()
            started.complete(Unit)
            release.await()
            return LlmAnalysisResult.of(false, 0.9f, "transactional")
        }

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    private class RestoreFailureEngine : LlmEngine {
        var loadCalls = 0
        var closeCalls = 0

        override fun isModelAvailable(): Boolean = true

        override fun load() {
            loadCalls++
            if (loadCalls > 1) error("model cannot be loaded")
        }

        override suspend fun analyze(text: String): LlmAnalysisResult = LlmAnalysisResult.UNAVAILABLE

        override fun close() {
            closeCalls++
        }
    }

    private class NeverCompletingEngine : LlmEngine {
        val started = CompletableDeferred<Unit>()
        var closed = false
        var analyzeCalls = 0

        override fun isModelAvailable(): Boolean = true

        override fun load() = Unit

        override suspend fun analyze(text: String): LlmAnalysisResult {
            analyzeCalls += 1
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
            return LlmAnalysisResult.UNAVAILABLE
        }

        override fun close() {
            closed = true
        }
    }

    private class BlockingModelInputStream : InputStream() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        private var emitted = false

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (emitted) return -1
            require(length > 0)
            started.countDown()
            check(release.await(1, TimeUnit.SECONDS)) { "Timed out waiting to release model copy" }
            buffer[offset] = 1
            emitted = true
            return 1
        }
    }
}
