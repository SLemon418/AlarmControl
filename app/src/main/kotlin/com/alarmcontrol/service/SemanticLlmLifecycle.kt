package com.alarmcontrol.service

import com.alarmcontrol.ml.llm.OnDeviceLlmManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes listener-owned LLM initialization and release. The validity check runs while lifecycle
 * ownership is held so a delayed observation cannot reopen a model after a newer disable or
 * listener disconnect.
 */
internal class SemanticLlmLifecycle(
    private val manager: OnDeviceLlmManager,
) {
    private val mutex = Mutex()

    suspend fun initializeIfCurrent(isCurrent: () -> Boolean): Boolean =
        mutex.withLock {
            if (!isCurrent()) return@withLock false
            manager.initialize()
            isCurrent()
        }

    suspend fun close() {
        mutex.withLock {
            manager.close()
        }
    }
}
