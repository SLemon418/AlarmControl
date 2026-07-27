package com.alarmcontrol.data.security

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes notification-content policy changes with encrypted payload reads, writes, and
 * deletion. Room and DataStore cannot share one transaction, so this process-local boundary keeps
 * an in-flight write from landing after the user disables or clears content storage.
 */
@Singleton
internal class NotificationContentAccessGuard
    @Inject
    constructor() {
        private val mutex = Mutex()

        suspend fun <T> withLock(block: suspend () -> T): T {
            mutex.lock()
            return try {
                block()
            } finally {
                mutex.unlock()
            }
        }
    }
