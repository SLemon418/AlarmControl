package com.alarmcontrol.core.settings

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes user-visible settings mutations with multi-store operations that temporarily change
 * settings. When another boundary is also required, this fence is acquired first.
 */
@Singleton
class SettingsMutationFence
    @Inject
    constructor() {
        private val mutex = Mutex()

        suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
    }
