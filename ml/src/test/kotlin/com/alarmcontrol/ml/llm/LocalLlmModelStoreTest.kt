package com.alarmcontrol.ml.llm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `empty input is rejected without replacing an existing model`() {
        val model = temporaryFolder.newFile("model.task").apply { writeBytes(byteArrayOf(9)) }

        assertThrows(IllegalArgumentException::class.java) {
            LocalLlmModelStore(model).install(byteArrayOf().inputStream())
        }

        assertArrayEquals(byteArrayOf(9), model.readBytes())
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
        val store = LocalLlmModelStore(model)
        store.install(byteArrayOf(9).inputStream())
        val progress = mutableListOf<Long>()
        val staged =
            store.stage(
                source = byteArrayOf(1, 2, 3).inputStream(),
                onProgress = progress::add,
            )

        staged.activate()
        assertArrayEquals(byteArrayOf(1, 2, 3), model.readBytes())
        staged.rollback()

        assertArrayEquals(byteArrayOf(9), model.readBytes())
        assertEquals(1L, store.verifyInstalledModel()?.sizeBytes)
        assertEquals(listOf(3L), progress)
    }

    @Test
    fun `verification restores a previous model after an interrupted activation`() {
        val model = temporaryFolder.root.resolve("interrupted/model.task")
        val store = LocalLlmModelStore(model)
        store.install(byteArrayOf(9).inputStream())
        val metadata = requireNotNull(model.parentFile).resolve("model.task.sha256")

        assertTrue(model.renameTo(requireNotNull(model.parentFile).resolve("model.task.previous")))
        assertTrue(metadata.renameTo(requireNotNull(model.parentFile).resolve("model.task.sha256.previous")))

        assertEquals(1L, LocalLlmModelStore(model).verifyInstalledModel()?.sizeBytes)
        assertArrayEquals(byteArrayOf(9), model.readBytes())
    }

    @Test
    fun `startup recovery removes stale staging without touching live or rollback pairs`() {
        val model = temporaryFolder.root.resolve("startup-recovery/model.task")
        val store = LocalLlmModelStore(model)
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
        val store = LocalLlmModelStore(model)
        store.install(byteArrayOf(9).inputStream())
        val staged = store.stage(byteArrayOf(1, 2).inputStream())
        staged.activate()

        assertEquals(1L, store.restorePreviousModel()?.sizeBytes)
        assertArrayEquals(byteArrayOf(9), model.readBytes())
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
}
