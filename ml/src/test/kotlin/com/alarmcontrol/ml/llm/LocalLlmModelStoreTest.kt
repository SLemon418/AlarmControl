package com.alarmcontrol.ml.llm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLlmModelStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `install creates parent directories and replaces the model`() {
        val model = temporaryFolder.root.resolve("llm/model.task")
        val bytes = byteArrayOf(1, 2, 3, 4)

        LocalLlmModelStore(model).install(bytes.inputStream())

        assertArrayEquals(bytes, model.readBytes())
        assertFalse(requireNotNull(model.parentFile).resolve("model.task.installing").exists())
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
        val model = temporaryFolder.newFile("rollback.task").apply { writeBytes(byteArrayOf(9)) }
        val progress = mutableListOf<Long>()
        val staged =
            LocalLlmModelStore(model).stage(
                source = byteArrayOf(1, 2, 3).inputStream(),
                onProgress = progress::add,
            )

        staged.activate()
        assertArrayEquals(byteArrayOf(1, 2, 3), model.readBytes())
        staged.rollback()

        assertArrayEquals(byteArrayOf(9), model.readBytes())
        assertEquals(listOf(3L), progress)
    }
}
