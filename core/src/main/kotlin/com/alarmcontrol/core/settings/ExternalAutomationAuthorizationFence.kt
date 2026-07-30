package com.alarmcontrol.core.settings

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes an authoritative external-automation check and its applied mutation against opt-in
 * revocation, token rotation, restore, and reset.
 *
 * When another boundary is also required, the global order is settings mutation, maintenance
 * policy, this authorization fence, whole-data reset, and then the narrower persistence fences.
 */
@Singleton
class ExternalAutomationAuthorizationFence
    @Inject
    constructor() {
        private val mutex = Mutex()

        suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
    }
