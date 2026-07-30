package com.alarmcontrol.ml.llm

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Durability barrier for model-directory entry creation, replacement, and removal. */
internal fun interface ModelDirectorySync {
    fun sync(directory: File)
}

private data object FileChannelModelDirectorySync : ModelDirectorySync {
    override fun sync(directory: File) {
        FileChannel
            .open(directory.toPath(), StandardOpenOption.READ)
            .use { channel -> channel.force(true) }
    }
}

internal fun defaultModelDirectorySync(): ModelDirectorySync = FileChannelModelDirectorySync

internal class LocalModelDeletion(
    private val directory: File,
    private val marker: File,
    private val dataFiles: List<File>,
    private val directorySync: ModelDirectorySync,
) {
    val isPending: Boolean
        get() = marker.exists()

    fun delete() {
        writeMarker()
        check(continueDeletion()) { "Couldn't delete local model data" }
    }

    fun completeIfPending(): Boolean {
        if (!isPending) return false
        continueDeletion()
        return true
    }

    private fun writeMarker() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Couldn't create the model directory"
        }
        check(!marker.exists() || marker.isFile) {
            "Model deletion marker is invalid"
        }
        val content = DELETION_MARKER_CONTENT.toByteArray(Charsets.US_ASCII)
        try {
            FileOutputStream(marker).use { output ->
                output.write(content)
                output.fd.sync()
            }
            directorySync.sync(directory)
        } finally {
            content.fill(0)
        }
    }

    private fun continueDeletion(): Boolean {
        var complete = true
        dataFiles.forEach { file ->
            if (file.exists() && !file.delete()) complete = false
        }
        if (!complete || !syncDirectoryBestEffort()) return false
        if (marker.exists() && !marker.delete()) return false
        return syncDirectoryBestEffort()
    }

    private fun syncDirectoryBestEffort(): Boolean =
        try {
            directorySync.sync(directory)
            true
        } catch (_: Exception) {
            false
        }

    private companion object {
        const val DELETION_MARKER_CONTENT = "delete\n"
    }
}
