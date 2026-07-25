package com.alarmcontrol.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * One running plus one pending best-effort semantic observation. Overflow drops stale analytics
 * work and never delays the notification action path.
 */
internal class SemanticObservationQueue<T>(
    scope: CoroutineScope,
    handler: suspend (T) -> Unit,
) : AutoCloseable {
    private val requests =
        Channel<T>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    init {
        scope.launch {
            for (request in requests) handler(request)
        }
    }

    fun offer(request: T): Boolean = requests.trySend(request).isSuccess

    fun clearPending() {
        while (requests.tryReceive().isSuccess) {
            // Content stays in memory only until this bounded queue entry is discarded.
        }
    }

    override fun close() {
        requests.close()
    }
}
