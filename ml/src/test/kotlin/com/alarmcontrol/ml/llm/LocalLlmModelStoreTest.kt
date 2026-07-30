package com.alarmcontrol.ml.llm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalLlmModelStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `install creates parent directories and replaces the model`() {
        val model = temporaryFolder.root.resolve("llm/model.task")
        model.parentFile?.mkdirs()
        model.writeBytes(byteArrayOf(9))
        val bytes = byteArrayOf(1, 2, 3, 4)

        val store = LocalLlmModelStore(model)
        store.install(bytes.inputStream())

        assertArrayEquals(bytes, model.readBytes())
        assertEquals(
            LlmModelInfo(
                sha256 = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
                sizeBytes = 4,
            ),
            store.verifyInstalledModel(),
        )
        assertTrue(requireNotNull(model.parentFile).resolve("model.task.sha256").isFile)
        assertFalse(requireNotNull(model.parentFile).resolve("model.task.installing").exists())
        assertFalse(requireNotNull(model.parentFile).resolve("model.task.previous").exists())
    }

    @Test
    fun `first install syncs the new model directory entry through its parent`() {
        val model = temporaryFolder.root.resolve("new-llm/model.task")
        val modelDirectory = requireNotNull(model.parentFile)
        val syncedDirectories = mutableListOf<File>()
        val store =
            LocalLlmModelStore(
                modelFile = model,
                directorySync = ModelDirectorySync(syncedDirectories::add),
            )

        store.install(byteArrayOf(1, 2, 3).inputStream())

        assertEquals(
            listOf(
                temporaryFolder.root,
                modelDirectory,
                modelDirectory,
            ),
            syncedDirectories,
        )
        assertEquals(3L, store.verifyInstalledModel()?.sizeBytes)
    }

    @Test
    fun `replacement syncs staged activated and committed directory states`() {
        val model = temporaryFolder.root.resolve("durable-replacement/model.task")
        val observedStates = mutableListOf<ModelDirectoryState>()
        val store =
            LocalLlmModelStore(
                modelFile = model,
                directorySync =
                    ModelDirectorySync {
                        observedStates += model.directoryState()
                    },
            )
        store.install(byteArrayOf(9).inputStream())
        observedStates.clear()

        val staged = store.stage(byteArrayOf(1, 2, 3).inputStream())
        staged.activate()
        staged.commit()

        assertEquals(
            listOf(
                ModelDirectoryState(
                    live = true,
                    liveMetadata = true,
                    installing = true,
                    installingMetadata = true,
                    previous = false,
                    previousMetadata = false,
                ),
                ModelDirectoryState(
                    live = true,
                    liveMetadata = true,
                    installing = false,
                    installingMetadata = false,
                    previous = true,
                    previousMetadata = true,
                ),
                ModelDirectoryState(
                    live = true,
                    liveMetadata = true,
                    installing = false,
                    installingMetadata = false,
                    previous = false,
                    previousMetadata = false,
                ),
            ),
            observedStates,
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), model.readBytes())
        assertEquals(3L, store.verifyInstalledModel()?.sizeBytes)
    }

    @Test
    fun `commit directory sync failure keeps the verified replacement live`() {
        val model = temporaryFolder.root.resolve("failed-commit-sync/model.task")
        var failSync = false
        var failedSyncAttempted = false
        val store =
            LocalLlmModelStore(
                modelFile = model,
                directorySync =
                    ModelDirectorySync {
                        if (failSync) {
                            failedSyncAttempted = true
                            error("directory sync failed")
                        }
                    },
            )
        store.install(byteArrayOf(9).inputStream())
        val staged = store.stage(byteArrayOf(1, 2, 3).inputStream())
        staged.activate()

        failSync = true
        staged.commit()
        failSync = false

        assertTrue(failedSyncAttempted)
        assertArrayEquals(byteArrayOf(1, 2, 3), model.readBytes())
        assertEquals(3L, store.verifyInstalledModel()?.sizeBytes)
    }

    @Test
    fun `empty input is rejected without replacing an existing model`() {
        val model = temporaryFolder.newFile("model.task").apply { writeBytes(byteArrayOf(9)) }

        assertThrows(IllegalArgumentException::class.java) {
            LocalLlmModelStore(model).install(byteArrayOf().inputStream())
        }

        assertArrayEquals(byteArrayOf(9), model.readBytes())
    }

    @Test
    fun `fatal staging failure removes partial model and metadata`() {
        val model = temporaryFolder.root.resolve("fatal-stage/model.task")
        val directory = requireNotNull(model.parentFile).apply { mkdirs() }
        val temporary = directory.resolve("model.task.installing")
        val temporaryMetadata = directory.resolve("model.task.sha256.installing")
        temporaryMetadata.writeText("stale")
        val source =
            object : java.io.InputStream() {
                private var emitted = false

                override fun read(): Int = error("bulk read expected")

                override fun read(
                    target: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (emitted) throw OutOfMemoryError("copy failed")
                    emitted = true
                    target[offset] = 7
                    return 1
                }
            }

        assertThrows(OutOfMemoryError::class.java) {
            LocalLlmModelStore(model).stage(source)
        }

        assertFalse(temporary.exists())
        assertFalse(temporaryMetadata.exists())
        assertFalse(model.exists())
    }

    @Test
    fun `declared model larger than four gibibytes is rejected before copying`() {
        val model = temporaryFolder.root.resolve("large/model.task")
        val source = byteArrayOf(1).inputStream()

        assertThrows(IllegalArgumentException::class.java) {
            LocalLlmModelStore(model).stage(
                source = source,
                expectedBytes = 4L * 1_024 * 1_024 * 1_024 + 1,
            )
        }

        assertFalse(model.exists())
        assertEquals(1, source.available())
    }

    @Test
    fun `staged model can be rolled back after compatibility validation fails`() {
        val model = temporaryFolder.root.resolve("rollback.task")
        var syncCalls = 0
        val store =
            LocalLlmModelStore(
                modelFile = model,
                directorySync = ModelDirectorySync { syncCalls += 1 },
            )
        store.install(byteArrayOf(9).inputStream())
        val progress = mutableListOf<Long>()
        val staged =
            store.stage(
                source = byteArrayOf(1, 2, 3).inputStream(),
                onProgress = progress::add,
            )

        staged.activate()
        assertArrayEquals(byteArrayOf(1, 2, 3), model.readBytes())
        val callsBeforeRollback = syncCalls
        staged.rollback()

        assertArrayEquals(byteArrayOf(9), model.readBytes())
        assertEquals(1L, store.verifyInstalledModel()?.sizeBytes)
        assertEquals(listOf(3L), progress)
        assertEquals(callsBeforeRollback + 1, syncCalls)
    }

    @Test
    fun `rollback before activation preserves live and stale previous models`() {
        val model = temporaryFolder.root.resolve("pre-activation-rollback/model.task")
        val directory = requireNotNull(model.parentFile)
        val store = LocalLlmModelStore(model)
        store.install(byteArrayOf(7, 8).inputStream())
        val staleModel = temporaryFolder.root.resolve("stale-rollback/model.task")
        LocalLlmModelStore(staleModel).install(byteArrayOf(1).inputStream())
        staleModel.copyTo(directory.resolve("model.task.previous"))
        requireNotNull(staleModel.parentFile)
            .resolve("model.task.sha256")
            .copyTo(directory.resolve("model.task.sha256.previous"))
        val staged = store.stage(byteArrayOf(9, 10, 11).inputStream())

        staged.rollback()

        assertArrayEquals(byteArrayOf(7, 8), model.readBytes())
        assertEquals(2L, store.verifyInstalledModel()?.sizeBytes)
        assertArrayEquals(byteArrayOf(1), directory.resolve("model.task.previous").readBytes())
        assertTrue(directory.resolve("model.task.sha256.previous").isFile)
        assertFalse(directory.resolve("model.task.installing").exists())
        assertFalse(directory.resolve("model.task.sha256.installing").exists())
    }

    @Test
    fun `verification restores a previous model after an interrupted activation`() {
        val model = temporaryFolder.root.resolve("interrupted/model.task")
        var syncCalls = 0
        val store =
            LocalLlmModelStore(
                modelFile = model,
                directorySync = ModelDirectorySync { syncCalls += 1 },
            )
        store.install(byteArrayOf(9).inputStream())
        val metadata = requireNotNull(model.parentFile).resolve("model.task.sha256")

        assertTrue(model.renameTo(requireNotNull(model.parentFile).resolve("model.task.previous")))
        assertTrue(metadata.renameTo(requireNotNull(model.parentFile).resolve("model.task.sha256.previous")))
        val callsBeforeRecovery = syncCalls

        assertEquals(1L, store.verifyInstalledModel()?.sizeBytes)
        assertArrayEquals(byteArrayOf(9), model.readBytes())
        assertEquals(callsBeforeRecovery + 1, syncCalls)
    }

    @Test
    fun `deletion tombstone prevents rollback resurrection after restart`() {
        val model = temporaryFolder.root.resolve("interrupted-delete/model.task")
        val store = LocalLlmModelStore(model)
        store.install(byteArrayOf(9).inputStream())
        store.stage(byteArrayOf(1, 2).inputStream()).activate()
        val directory = requireNotNull(model.parentFile)
        val deletionMarker = directory.resolve("model.task.deleting")
        deletionMarker.writeText("delete\n")
        assertTrue(model.delete())
        assertTrue(directory.resolve("model.task.sha256").delete())

        val restarted = LocalLlmModelStore(model)

        assertNull(restarted.verifyInstalledModel())
        assertFalse(model.exists())
        assertFalse(directory.resolve("model.task.previous").exists())
        assertFalse(directory.resolve("model.task.sha256.previous").exists())
        assertFalse(deletionMarker.exists())
    }

    @Test
    fun `delete syncs marker intent before data and final directory state`() {
        val model = temporaryFolder.root.resolve("durable-delete/model.task")
        val directory = requireNotNull(model.parentFile)
        val deletionMarker = directory.resolve("model.task.deleting")
        val observedStates = mutableListOf<Pair<Boolean, Boolean>>()
        val store =
            LocalLlmModelStore(
                modelFile = model,
                directorySync =
                    ModelDirectorySync {
                        observedStates += model.exists() to deletionMarker.exists()
                    },
            )
        store.install(byteArrayOf(9).inputStream())
        observedStates.clear()

        store.delete()

        assertEquals(
            listOf(
                true to true,
                false to true,
                false to false,
            ),
            observedStates,
        )
    }

    @Test
    fun `delete keeps data when marker directory sync fails`() {
        val model = temporaryFolder.root.resolve("failed-delete-sync/model.task")
        LocalLlmModelStore(model).install(byteArrayOf(9).inputStream())
        val store =
            LocalLlmModelStore(
                modelFile = model,
                directorySync =
                    ModelDirectorySync {
                        throw IllegalStateException("directory sync failed")
                    },
            )
        val deletionMarker = requireNotNull(model.parentFile).resolve("model.task.deleting")

        assertThrows(IllegalStateException::class.java) {
            store.delete()
        }

        assertTrue(model.isFile)
        assertTrue(deletionMarker.isFile)
    }

    @Test
    fun `startup recovery removes stale staging without touching live or rollback pairs`() {
        val model = temporaryFolder.root.resolve("startup-recovery/model.task")
        var syncCalls = 0
        val store =
            LocalLlmModelStore(
                modelFile = model,
                directorySync = ModelDirectorySync { syncCalls += 1 },
            )
        store.install(byteArrayOf(9).inputStream())
        store.stage(byteArrayOf(1, 2).inputStream()).activate()
        val directory = requireNotNull(model.parentFile)
        val metadata = directory.resolve("model.task.sha256")
        val previous = directory.resolve("model.task.previous")
        val previousMetadata = directory.resolve("model.task.sha256.previous")
        val liveMetadata = metadata.readBytes()
        val rollbackMetadata = previousMetadata.readBytes()
        directory.resolve("model.task.installing").writeBytes(byteArrayOf(7, 8, 9))
        directory.resolve("model.task.sha256.installing").writeText("partial")
        val callsBeforeRecovery = syncCalls

        store.recoverStaleStagingAtStartup()

        assertFalse(directory.resolve("model.task.installing").exists())
        assertFalse(directory.resolve("model.task.sha256.installing").exists())
        assertArrayEquals(byteArrayOf(1, 2), model.readBytes())
        assertArrayEquals(liveMetadata, metadata.readBytes())
        assertArrayEquals(byteArrayOf(9), previous.readBytes())
        assertArrayEquals(rollbackMetadata, previousMetadata.readBytes())
        assertEquals(2L, store.verifyInstalledModel()?.sizeBytes)
        assertTrue(previous.isFile)
        assertEquals(1L, store.restorePreviousModel()?.sizeBytes)
        assertArrayEquals(byteArrayOf(9), model.readBytes())
        assertEquals(callsBeforeRecovery + 2, syncCalls)
    }

    @Test
    fun `constructing another store does not remove a stage owned by this process`() {
        val model = temporaryFolder.root.resolve("active-stage/model.task")
        val store = LocalLlmModelStore(model)
        val staged = store.stage(byteArrayOf(1, 2, 3).inputStream())
        val directory = requireNotNull(model.parentFile)

        LocalLlmModelStore(model)

        assertTrue(directory.resolve("model.task.installing").isFile)
        assertTrue(directory.resolve("model.task.sha256.installing").isFile)
        staged.activate()
        staged.commit()
        assertArrayEquals(byteArrayOf(1, 2, 3), model.readBytes())
    }

    @Test
    fun `a verified previous model can replace an incompatible live replacement`() {
        val model = temporaryFolder.root.resolve("restore-previous/model.task")
        var syncCalls = 0
        val store =
            LocalLlmModelStore(
                modelFile = model,
                directorySync = ModelDirectorySync { syncCalls += 1 },
            )
        store.install(byteArrayOf(9).inputStream())
        val staged = store.stage(byteArrayOf(1, 2).inputStream())
        staged.activate()
        val callsBeforeRestore = syncCalls

        assertEquals(1L, store.restorePreviousModel()?.sizeBytes)
        assertArrayEquals(byteArrayOf(9), model.readBytes())
        assertEquals(callsBeforeRestore + 1, syncCalls)
    }

    @Test
    fun `verification rejects a model changed after import`() {
        val model = temporaryFolder.root.resolve("integrity/model.task")
        val store = LocalLlmModelStore(model)
        store.install(byteArrayOf(1, 2, 3).inputStream())

        model.appendBytes(byteArrayOf(4))

        assertThrows(ModelIntegrityException::class.java) {
            store.verifyInstalledModel()
        }
    }

    @Test
    fun `verification rejects an imported model whose integrity record is missing`() {
        val model = temporaryFolder.root.resolve("missing-metadata/model.task")
        val store = LocalLlmModelStore(model)
        store.install(byteArrayOf(1, 2, 3).inputStream())
        requireNotNull(model.parentFile).resolve("model.task.sha256").delete()

        assertThrows(ModelIntegrityException::class.java) {
            store.verifyInstalledModel()
        }
    }

    @Test
    fun `declared size is preflighted once before copying`() {
        val model = temporaryFolder.root.resolve("preflight/model.task")
        val storageGuard = RecordingStorageGuard()

        LocalLlmModelStore(model, storageGuard)
            .stage(byteArrayOf(1, 2, 3).inputStream(), expectedBytes = 3)

        assertEquals(3L, storageGuard.expectedBytes)
        assertEquals(1, storageGuard.prepareCalls)
    }

    @Test
    fun `unknown size fails before copying when storage headroom is unavailable`() {
        val model = temporaryFolder.root.resolve("headroom/model.task")
        val source = byteArrayOf(1, 2, 3).inputStream()
        val storageGuard = RecordingStorageGuard(hasHeadroom = false)

        assertThrows(IllegalArgumentException::class.java) {
            LocalLlmModelStore(model, storageGuard).stage(source)
        }

        assertEquals(3, source.available())
        assertFalse(model.exists())
    }

    private class RecordingStorageGuard(
        private val hasHeadroom: Boolean = true,
    ) : ModelStorageGuard {
        var prepareCalls = 0
        var expectedBytes: Long? = null

        override fun prepare(
            directory: File,
            expectedBytes: Long,
            headroomBytes: Long,
        ) {
            prepareCalls += 1
            this.expectedBytes = expectedBytes
        }

        override fun hasHeadroom(
            directory: File,
            headroomBytes: Long,
        ): Boolean = hasHeadroom
    }

    private fun File.directoryState(): ModelDirectoryState {
        val directory = requireNotNull(parentFile)
        return ModelDirectoryState(
            live = isFile,
            liveMetadata = directory.resolve("$name.sha256").isFile,
            installing = directory.resolve("$name.installing").isFile,
            installingMetadata = directory.resolve("$name.sha256.installing").isFile,
            previous = directory.resolve("$name.previous").isFile,
            previousMetadata = directory.resolve("$name.sha256.previous").isFile,
        )
    }

    private data class ModelDirectoryState(
        val live: Boolean,
        val liveMetadata: Boolean,
        val installing: Boolean,
        val installingMetadata: Boolean,
        val previous: Boolean,
        val previousMetadata: Boolean,
    )
}
