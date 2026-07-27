package com.alarmcontrol.service

import com.alarmcontrol.core.result.runCatchingPreservingCancellation
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
    private val onFailure: (Throwable) -> Unit = {},
    handler: suspend (T) -> Unit,
) : AutoCloseable {
    private val requests =
        Channel<T>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    init {
        scope.launch {
            for (request in requests) {
                runCatchingPreservingCancellation {
                    handler(request)
                }.onFailure(onFailure)
            }
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
