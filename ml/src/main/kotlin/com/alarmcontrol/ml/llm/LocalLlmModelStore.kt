package com.alarmcontrol.ml.llm

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Stages user-selected models in app-private storage without exposing a partial target file. */
@Suppress("TooGenericExceptionCaught")
internal class LocalLlmModelStore(
    private val modelFile: File,
    private val storageGuard: ModelStorageGuard = defaultModelStorageGuard(),
) {
    private val metadataFile: File
        get() = relatedFile(METADATA_SUFFIX)

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
        }
        val temporary = relatedFile(INSTALLING_SUFFIX)
        val temporaryMetadata = relatedFile("$METADATA_SUFFIX$INSTALLING_SUFFIX")
        try {
            val modelInfo = copyToTemporaryFile(source, temporary, expectedBytes, onProgress)
            writeMetadata(temporaryMetadata, modelInfo)
            return StagedModel(
                temporary = temporary,
                previous = relatedFile(PREVIOUS_SUFFIX),
                temporaryMetadata = temporaryMetadata,
                previousMetadata = relatedFile("$METADATA_SUFFIX$PREVIOUS_SUFFIX"),
                modelInfo = modelInfo,
            )
        } catch (error: Exception) {
            temporary.delete()
            temporaryMetadata.delete()
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
        listOf(
            modelFile,
            metadataFile,
            relatedFile(INSTALLING_SUFFIX),
            relatedFile(PREVIOUS_SUFFIX),
            relatedFile("$METADATA_SUFFIX$INSTALLING_SUFFIX"),
            relatedFile("$METADATA_SUFFIX$PREVIOUS_SUFFIX"),
        ).forEach { file ->
            check(!file.exists() || file.delete()) { "Couldn't delete local model data" }
        }
    }

    /**
     * Hashes the installed file and compares it with the import-time sidecar before native loading.
     * A model without a sidecar predates integrity support and must be re-imported.
     */
    fun verifyInstalledModel(): LlmModelInfo? {
        recoverInterruptedActivation()
        if (!modelFile.isFile || modelFile.length() <= 0) return null
        return LocalModelIntegrity.verify(modelFile, metadataFile)
    }

    /**
     * Restores the retained model after a replacement reached the live path but failed native
     * loading. The rollback pair is fully verified before the current model is touched.
     */
    fun restorePreviousModel(): LlmModelInfo? {
        val previous = relatedFile(PREVIOUS_SUFFIX)
        val previousMetadata = relatedFile("$METADATA_SUFFIX$PREVIOUS_SUFFIX")
        val restored = LocalModelIntegrity.verifyOrNull(previous, previousMetadata) ?: return null

        check(!metadataFile.exists() || metadataFile.delete()) {
            "Couldn't replace the local model integrity record"
        }
        moveReplacing(previousMetadata, metadataFile)
        check(!modelFile.exists() || modelFile.delete()) { "Couldn't replace the local model" }
        moveReplacing(previous, modelFile)
        return restored
    }

    /** Best-effort cleanup after the live model has passed native loading. */
    fun discardRollbackArtifacts() {
        relatedFile(PREVIOUS_SUFFIX).delete()
        relatedFile("$METADATA_SUFFIX$PREVIOUS_SUFFIX").delete()
    }

    inner class StagedModel internal constructor(
        private val temporary: File,
        private val previous: File,
        private val temporaryMetadata: File,
        private val previousMetadata: File,
        val modelInfo: LlmModelInfo,
    ) {
        private var active = false

        /** Replaces the live path while retaining the previous model for rollback. */
        fun activate() {
            previous.delete()
            previousMetadata.delete()
            if (modelFile.exists()) moveReplacing(modelFile, previous)
            if (metadataFile.exists()) moveReplacing(metadataFile, previousMetadata)
            try {
                moveReplacing(temporary, modelFile)
                moveReplacing(temporaryMetadata, metadataFile)
                active = true
            } catch (error: Exception) {
                modelFile.delete()
                metadataFile.delete()
                if (previous.exists()) moveReplacing(previous, modelFile)
                if (previousMetadata.exists()) moveReplacing(previousMetadata, metadataFile)
                throw error
            }
        }

        /**
         * Keeps the newly activated model and best-effort removes rollback material. Cleanup alone
         * must not turn a successfully loaded replacement into a destructive rollback.
         */
        fun commit() {
            previous.delete()
            previousMetadata.delete()
            temporary.delete()
            temporaryMetadata.delete()
        }

        /** Restores the model that existed before [activate], if any. */
        fun rollback() {
            if (active) {
                modelFile.delete()
                metadataFile.delete()
            }
            if (previous.exists()) moveReplacing(previous, modelFile)
            if (previousMetadata.exists()) moveReplacing(previousMetadata, metadataFile)
            temporary.delete()
            temporaryMetadata.delete()
            active = false
        }
    }

    private fun copyToTemporaryFile(
        source: InputStream,
        temporary: File,
        expectedBytes: Long?,
        onProgress: (Long) -> Unit,
    ): LlmModelInfo {
        var modelInfo: LlmModelInfo? = null
        FileOutputStream(temporary).use { fileOutput ->
            val directory = requireNotNull(temporary.parentFile)
            prepareStorage(directory, expectedBytes)
            fileOutput.channel.position(0)
            val output = BufferedOutputStream(fileOutput)
            val copied = copyBytes(source, output, directory, onProgress)
            modelInfo = copied.modelInfo
            output.flush()
            fileOutput.channel.truncate(copied.modelInfo.sizeBytes)
            fileOutput.fd.sync()
            if (copied.lastReported != copied.modelInfo.sizeBytes) onProgress(copied.modelInfo.sizeBytes)
        }
        return requireNotNull(modelInfo)
    }

    private fun prepareStorage(
        directory: File,
        expectedBytes: Long?,
    ) {
        if (expectedBytes == null) {
            require(storageGuard.hasHeadroom(directory, FREE_SPACE_HEADROOM_BYTES)) {
                "Not enough local storage for model staging"
            }
        } else {
            storageGuard.prepare(
                directory = directory,
                expectedBytes = expectedBytes,
                headroomBytes = FREE_SPACE_HEADROOM_BYTES,
            )
        }
    }

    private fun copyBytes(
        source: InputStream,
        output: BufferedOutputStream,
        directory: File,
        onProgress: (Long) -> Unit,
    ): CopyProgress {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        var total = 0L
        var lastReported = 0L
        var lastStorageCheck = 0L
        try {
            var count = source.read(buffer)
            while (count >= 0) {
                if (count > 0) {
                    total += count
                    require(total <= MAX_MODEL_BYTES) { "Model file is too large" }
                    lastStorageCheck = checkStorageHeadroom(total, lastStorageCheck, directory)
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    lastReported = reportProgress(total, lastReported, onProgress)
                }
                count = source.read(buffer)
            }
            require(total > 0) { "Model file is empty" }
            return CopyProgress(
                modelInfo =
                    LlmModelInfo(
                        sha256 = LocalModelIntegrity.digestToHex(digest.digest()),
                        sizeBytes = total,
                    ),
                lastReported = lastReported,
            )
        } finally {
            buffer.fill(0)
        }
    }

    private fun checkStorageHeadroom(
        total: Long,
        lastStorageCheck: Long,
        directory: File,
    ): Long {
        if (total - lastStorageCheck < STORAGE_CHECK_STEP_BYTES) return lastStorageCheck
        require(storageGuard.hasHeadroom(directory, FREE_SPACE_HEADROOM_BYTES)) {
            "Not enough local storage for model staging"
        }
        return total
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

    private fun writeMetadata(
        destination: File,
        modelInfo: LlmModelInfo,
    ) {
        val content = LocalModelIntegrity.metadataBytes(modelInfo)
        try {
            FileOutputStream(destination).use { output ->
                output.write(content)
                output.fd.sync()
            }
        } finally {
            content.fill(0)
        }
    }

    private fun recoverInterruptedActivation() {
        val previous = relatedFile(PREVIOUS_SUFFIX)
        val previousMetadata = relatedFile("$METADATA_SUFFIX$PREVIOUS_SUFFIX")
        val currentValid = LocalModelIntegrity.verifyOrNull(modelFile, metadataFile) != null
        if (!currentValid) {
            when {
                LocalModelIntegrity.verifyOrNull(previous, previousMetadata) != null -> {
                    modelFile.delete()
                    metadataFile.delete()
                    moveReplacing(previous, modelFile)
                    moveReplacing(previousMetadata, metadataFile)
                }
                LocalModelIntegrity.verifyOrNull(previous, metadataFile) != null -> {
                    modelFile.delete()
                    moveReplacing(previous, modelFile)
                }
            }
        }
        relatedFile(INSTALLING_SUFFIX).delete()
        relatedFile("$METADATA_SUFFIX$INSTALLING_SUFFIX").delete()
    }

    private fun relatedFile(suffix: String): File =
        File(requireNotNull(modelFile.parentFile) { "Model path has no parent directory" }, "${modelFile.name}$suffix")

    private data class CopyProgress(
        val modelInfo: LlmModelInfo,
        val lastReported: Long,
    )

    private companion object {
        const val HASH_ALGORITHM = "SHA-256"
        const val METADATA_SUFFIX = ".sha256"
        const val INSTALLING_SUFFIX = ".installing"
        const val PREVIOUS_SUFFIX = ".previous"
        const val COPY_BUFFER_BYTES = 64 * 1_024
        const val PROGRESS_STEP_BYTES = 4L * 1_024 * 1_024
        const val STORAGE_CHECK_STEP_BYTES = 64L * 1_024 * 1_024
        const val MAX_MODEL_BYTES = 4L * 1_024 * 1_024 * 1_024
        const val FREE_SPACE_HEADROOM_BYTES = 256L * 1_024 * 1_024
    }
}

internal class ModelIntegrityException(
    message: String,
) : IllegalStateException(message)
