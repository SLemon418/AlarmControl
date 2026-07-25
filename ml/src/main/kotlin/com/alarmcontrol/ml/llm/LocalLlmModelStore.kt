package com.alarmcontrol.ml.llm

import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Stages user-selected models in app-private storage without exposing a partial target file. */
@Suppress("TooGenericExceptionCaught")
internal class LocalLlmModelStore(
    private val modelFile: File,
) {
    /** Copies to a temporary file. The caller activates it only when ready to verify native loading. */
    fun stage(
        source: InputStream,
        expectedBytes: Long? = null,
        onProgress: (Long) -> Unit = {},
    ): StagedModel {
        val directory = requireNotNull(modelFile.parentFile) { "Model path has no parent directory" }
        check(directory.isDirectory || directory.mkdirs()) { "Couldn't create the model directory" }
        expectedBytes?.let {
            require(it in 1..MAX_MODEL_BYTES) { "Model file is too large" }
            require(directory.usableSpace >= it + FREE_SPACE_HEADROOM_BYTES) {
                "Not enough local storage for model staging"
            }
        }
        val temporary = File(directory, "${modelFile.name}.installing")
        try {
            copyToTemporaryFile(source, temporary, onProgress)
            return StagedModel(temporary, File(directory, "${modelFile.name}.previous"))
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    /** Convenience used by storage-only tests; production verifies through [StagedModel]. */
    fun install(source: InputStream) {
        stage(source).also { staged ->
            staged.activate()
            staged.commit()
        }
    }

    fun delete() {
        val directory = modelFile.parentFile
        listOf(
            modelFile,
            directory?.resolve("${modelFile.name}.installing"),
            directory?.resolve("${modelFile.name}.previous"),
        ).filterNotNull().forEach { file ->
            check(!file.exists() || file.delete()) { "Couldn't delete local model data" }
        }
    }

    inner class StagedModel internal constructor(
        private val temporary: File,
        private val previous: File,
    ) {
        private var active = false

        /** Replaces the live path while retaining the previous model for rollback. */
        fun activate() {
            previous.delete()
            if (modelFile.exists()) moveReplacing(modelFile, previous)
            try {
                moveReplacing(temporary, modelFile)
                active = true
            } catch (error: Exception) {
                if (previous.exists()) moveReplacing(previous, modelFile)
                throw error
            }
        }

        /** Keeps the newly activated model and removes rollback material. */
        fun commit() {
            previous.delete()
            temporary.delete()
        }

        /** Restores the model that existed before [activate], if any. */
        fun rollback() {
            if (active) modelFile.delete()
            if (previous.exists()) moveReplacing(previous, modelFile)
            temporary.delete()
            active = false
        }
    }

    private fun copyToTemporaryFile(
        source: InputStream,
        temporary: File,
        onProgress: (Long) -> Unit,
    ) {
        temporary.outputStream().buffered().use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var total = 0L
            var lastReported = 0L
            var count = source.read(buffer)
            while (count >= 0) {
                if (count > 0) {
                    total += count
                    require(total <= MAX_MODEL_BYTES) { "Model file is too large" }
                    require(
                        requireNotNull(temporary.parentFile).usableSpace >= FREE_SPACE_HEADROOM_BYTES,
                    ) { "Not enough local storage for model staging" }
                    output.write(buffer, 0, count)
                    lastReported = reportProgress(total, lastReported, onProgress)
                }
                count = source.read(buffer)
            }
            require(total > 0) { "Model file is empty" }
            if (lastReported != total) onProgress(total)
        }
    }

    private fun reportProgress(
        total: Long,
        lastReported: Long,
        onProgress: (Long) -> Unit,
    ): Long =
        if (total - lastReported >= PROGRESS_STEP_BYTES) {
            onProgress(total)
            total
        } else {
            lastReported
        }

    private fun moveReplacing(
        source: File,
        destination: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1_024
        const val PROGRESS_STEP_BYTES = 4L * 1_024 * 1_024
        const val MAX_MODEL_BYTES = 4L * 1_024 * 1_024 * 1_024
        const val FREE_SPACE_HEADROOM_BYTES = 256L * 1_024 * 1_024
    }
}
