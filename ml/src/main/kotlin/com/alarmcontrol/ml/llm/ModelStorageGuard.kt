package com.alarmcontrol.ml.llm

import android.annotation.SuppressLint
import android.content.Context
import android.os.storage.StorageManager
import java.io.File

/** Storage preflight kept injectable so model-file staging remains deterministic in JVM tests. */
internal interface ModelStorageGuard {
    fun prepare(
        directory: File,
        expectedBytes: Long,
        headroomBytes: Long,
    )

    fun hasHeadroom(
        directory: File,
        headroomBytes: Long,
    ): Boolean
}

/** Android API 26+ capacity guard used for user-directed, app-private model imports. */
internal class AndroidModelStorageGuard(
    context: Context,
) : ModelStorageGuard {
    private val storageManager = context.getSystemService(StorageManager::class.java)

    override fun prepare(
        directory: File,
        expectedBytes: Long,
        headroomBytes: Long,
    ) {
        val storageUuid = storageManager.getUuidForPath(directory)
        require(storageManager.getAllocatableBytes(storageUuid) >= expectedBytes + headroomBytes) {
            "Not enough local storage for model staging"
        }
    }

    @SuppressLint("UsableSpace")
    override fun hasHeadroom(
        directory: File,
        headroomBytes: Long,
    ): Boolean = directory.usableSpace >= headroomBytes
}

/** Filesystem fallback for pure JVM tests; production injects [AndroidModelStorageGuard]. */
private data object FileSystemModelStorageGuard : ModelStorageGuard {
    @SuppressLint("UsableSpace")
    override fun prepare(
        directory: File,
        expectedBytes: Long,
        headroomBytes: Long,
    ) {
        require(directory.usableSpace >= expectedBytes + headroomBytes) {
            "Not enough local storage for model staging"
        }
    }

    @SuppressLint("UsableSpace")
    override fun hasHeadroom(
        directory: File,
        headroomBytes: Long,
    ): Boolean = directory.usableSpace >= headroomBytes
}

internal fun defaultModelStorageGuard(): ModelStorageGuard = FileSystemModelStorageGuard
