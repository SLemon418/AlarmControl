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
@Suppress("TooGenericExceptionCaught", "TooManyFunctions") // One storage transaction owns every crash-safe state.
internal class LocalLlmModelStore(
    private val modelFile: File,
    private val storageGuard: ModelStorageGuard = defaultModelStorageGuard(),
    private val directorySync: ModelDirectorySync = defaultModelDirectorySync(),
) {
    private val metadataFile: File
        get() = relatedFile(METADATA_SUFFIX)
    private val deletionTransaction =
        LocalModelDeletion(
            directory = requireNotNull(modelFile.parentFile) { "Model path has no parent directory" },
            marker = relatedFile(DELETING_SUFFIX),
            dataFiles =
                listOf(
                    modelFile,
                    metadataFile,
                    relatedFile(INSTALLING_SUFFIX),
                    relatedFile(PREVIOUS_SUFFIX),
                    relatedFile("$METADATA_SUFFIX$INSTALLING_SUFFIX"),
                    relatedFile("$METADATA_SUFFIX$PREVIOUS_SUFFIX"),
                ),
            directorySync = directorySync,
        )

    /** Copies to a temporary file. The caller activates it only when ready to verify native loading. */
    fun stage(
        source: InputStream,
        expectedBytes: Long? = null,
        onProgress: (Long) -> Unit = {},
    ): StagedModel {
        val directory = requireNotNull(modelFile.parentFile) { "Model path has no parent directory" }
        val directoryCreated = !directory.isDirectory
        check(!directoryCreated || directory.mkdirs() || directory.isDirectory) {
            "Couldn't create the model directory"
        }
        if (directoryCreated) {
            directorySync.sync(
                requireNotNull(directory.parentFile) {
                    "Model directory has no parent directory"
                },
            )
        }
        check(!deletionTransaction.isPending) { "Previous model deletion is incomplete" }
        prepareForStaging()
        expectedBytes?.let {
            require(it in 1..MAX_MODEL_BYTES) { "Model file is too large" }
        }
        val temporary = relatedFile(INSTALLING_SUFFIX)
        val temporaryMetadata = relatedFile("$METADATA_SUFFIX$INSTALLING_SUFFIX")
        try {
            val modelInfo = copyToTemporaryFile(source, temporary, expectedBytes, onProgress)
            writeMetadata(temporaryMetadata, modelInfo)
            directorySync.sync(directory)
            val previous = relatedFile(PREVIOUS_SUFFIX)
            val previousMetadata = relatedFile("$METADATA_SUFFIX$PREVIOUS_SUFFIX")
            return StagedModel(
                temporary = temporary,
                previous = previous,
                temporaryMetadata = temporaryMetadata,
                previousMetadata = previousMetadata,
                modelInfo = modelInfo,
                retainRollback = LocalModelIntegrity.verifyOrNull(previous, previousMetadata) != null,
            )
        } catch (error: Throwable) {
            temporary.delete()
            temporaryMetadata.delete()
            syncDirectoryBestEffort(directory)
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
        deletionTransaction.delete()
    }

    /**
     * Best-effort cleanup for staging files left by a terminated process. Production calls this once
     * before exposing the singleton store, so an import owned by the current process cannot be removed.
     * Live and rollback pairs are deliberately preserved for [verifyInstalledModel] recovery.
     */
    fun recoverStaleStagingAtStartup() {
        if (deletionTransaction.completeIfPending()) return
        val changed =
            deleteBestEffort(relatedFile(INSTALLING_SUFFIX)) or
                deleteBestEffort(relatedFile("$METADATA_SUFFIX$INSTALLING_SUFFIX"))
        if (changed) syncDirectoryBestEffort()
    }

    /**
     * Hashes the installed file and compares it with the import-time sidecar before native loading.
     * A model without a sidecar predates integrity support and must be re-imported.
     */
    fun verifyInstalledModel(): LlmModelInfo? {
        if (deletionTransaction.completeIfPending()) return null
        recoverInterruptedActivation()
        if (!modelFile.isFile || modelFile.length() <= 0) return null
        return LocalModelIntegrity.verify(modelFile, metadataFile)
    }

    /**
     * Restores the retained model after a replacement reached the live path but failed native
     * loading. The rollback pair is fully verified before the current model is touched.
     */
    fun restorePreviousModel(): LlmModelInfo? {
        if (deletionTransaction.completeIfPending()) return null
        val previous = relatedFile(PREVIOUS_SUFFIX)
        val previousMetadata = relatedFile("$METADATA_SUFFIX$PREVIOUS_SUFFIX")
        val restored = LocalModelIntegrity.verifyOrNull(previous, previousMetadata) ?: return null

        check(!metadataFile.exists() || metadataFile.delete()) {
            "Couldn't replace the local model integrity record"
        }
        moveReplacing(previousMetadata, metadataFile)
        check(!modelFile.exists() || modelFile.delete()) { "Couldn't replace the local model" }
        moveReplacing(previous, modelFile)
        directorySync.sync(requireModelDirectory())
        return restored
    }

    /** Best-effort cleanup after the live model has passed native loading. */
    fun discardRollbackArtifacts() {
        deleteRollbackArtifacts()
    }

    inner class StagedModel internal constructor(
        private val temporary: File,
        private val previous: File,
        private val temporaryMetadata: File,
        private val previousMetadata: File,
        val modelInfo: LlmModelInfo,
        private val retainRollback: Boolean,
    ) {
        private var active = false
        private var activationStarted = false

        /** Replaces the live path while retaining the previous model for rollback. */
        fun activate() {
            activationStarted = true
            try {
                if (retainRollback) {
                    check(!modelFile.exists() || modelFile.delete()) {
                        "Couldn't replace the interrupted local model"
                    }
                    check(!metadataFile.exists() || metadataFile.delete()) {
                        "Couldn't replace the interrupted model integrity record"
                    }
                } else {
                    previous.delete()
                    previousMetadata.delete()
                    if (modelFile.exists()) moveReplacing(modelFile, previous)
                    if (metadataFile.exists()) moveReplacing(metadataFile, previousMetadata)
                }
                moveReplacing(temporary, modelFile)
                moveReplacing(temporaryMetadata, metadataFile)
                directorySync.sync(requireModelDirectory())
                active = true
            } catch (error: Exception) {
                modelFile.delete()
                metadataFile.delete()
                if (previousMetadata.exists()) moveReplacing(previousMetadata, metadataFile)
                if (previous.exists()) moveReplacing(previous, modelFile)
                try {
                    directorySync.sync(requireModelDirectory())
                } catch (recoveryError: Exception) {
                    error.addSuppressed(recoveryError)
                }
                throw error
            }
        }

        /**
         * Keeps the newly activated model and best-effort removes rollback material. Cleanup alone
         * must not turn a successfully loaded replacement into a destructive rollback.
         */
        fun commit() {
            active = false
            activationStarted = false
            val changed =
                deleteBestEffort(previous) or
                    deleteBestEffort(previousMetadata) or
                    deleteBestEffort(temporary) or
                    deleteBestEffort(temporaryMetadata)
            if (changed) syncDirectoryBestEffort()
        }

        /** Restores the model that existed before [activate], if any. */
        fun rollback() {
            if (!activationStarted) {
                val changed =
                    deleteBestEffort(temporary) or
                        deleteBestEffort(temporaryMetadata)
                if (changed) syncDirectoryBestEffort()
                return
            }
            if (active) {
                modelFile.delete()
                metadataFile.delete()
            }
            if (previousMetadata.exists()) moveReplacing(previousMetadata, metadataFile)
            if (previous.exists()) moveReplacing(previous, modelFile)
            temporary.delete()
            temporaryMetadata.delete()
            active = false
            activationStarted = false
            directorySync.sync(requireModelDirectory())
        }
    }

    /**
     * Normalizes a torn activation before a new import can reuse the single rollback slot. A fully
     * moved but not yet committed activation keeps its verified previous pair; [StagedModel] then
     * replaces only the uncommitted live pair so a failed re-import still has known rollback data.
     */
    private fun prepareForStaging() {
        val previous = relatedFile(PREVIOUS_SUFFIX)
        val previousMetadata = relatedFile("$METADATA_SUFFIX$PREVIOUS_SUFFIX")
        if (previous.exists() || previousMetadata.exists()) {
            recoverInterruptedActivation()
        } else {
            recoverStaleStagingAtStartup()
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
        if (deletionTransaction.isPending) return
        val previous = relatedFile(PREVIOUS_SUFFIX)
        val previousMetadata = relatedFile("$METADATA_SUFFIX$PREVIOUS_SUFFIX")
        val currentValid = LocalModelIntegrity.verifyOrNull(modelFile, metadataFile) != null
        var recovered = false
        if (!currentValid) {
            when {
                LocalModelIntegrity.verifyOrNull(previous, previousMetadata) != null -> {
                    modelFile.delete()
                    metadataFile.delete()
                    moveReplacing(previousMetadata, metadataFile)
                    moveReplacing(previous, modelFile)
                    recovered = true
                }
                LocalModelIntegrity.verifyOrNull(previous, metadataFile) != null -> {
                    modelFile.delete()
                    moveReplacing(previous, modelFile)
                    recovered = true
                }
            }
        }
        if (recovered) directorySync.sync(requireModelDirectory())
        recoverStaleStagingAtStartup()
    }

    private fun deleteRollbackArtifacts() {
        val changed =
            deleteBestEffort(relatedFile(PREVIOUS_SUFFIX)) or
                deleteBestEffort(relatedFile("$METADATA_SUFFIX$PREVIOUS_SUFFIX"))
        if (changed) syncDirectoryBestEffort()
    }

    private fun deleteBestEffort(file: File): Boolean = file.exists() && file.delete()

    private fun syncDirectoryBestEffort(directory: File = requireModelDirectory()) {
        try {
            directorySync.sync(directory)
        } catch (_: Exception) {
            // The verified live pair remains durable; cleanup can be retried after restart.
        }
    }

    private fun requireModelDirectory(): File =
        requireNotNull(modelFile.parentFile) { "Model path has no parent directory" }

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
        const val DELETING_SUFFIX = ".deleting"
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
