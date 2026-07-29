package com.alarmcontrol.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local commit boundary between filtering preference mutations and notification actions.
 *
 * Disabling acquires the same coroutine boundary used by [runIfAllowed], so once it returns no
 * older evaluation can begin a cancel or snooze action. Actions require both an enabled filtering
 * preference and a rule snapshot acknowledged for the latest [ruleRefreshRequests] id.
 */
@Singleton
class FilteringActionGate
    @Inject
    constructor() {
        private val lock = Any()
        private val mutableRuleRefreshRequests = MutableStateFlow(INITIAL_RULE_REFRESH_ID)
        private var filteringInitialized = false
        private var filteringAllowed = false
        private var latestRuleRefreshId = INITIAL_RULE_REFRESH_ID
        private var ruleReady = false
        private var ruleMutationActive = false
        private val actionMutex = Mutex()
        private val ruleMutationMutex = Mutex()

        /**
         * Refresh ids that require a fresh rule-flow subscription. The initial id is deliberately
         * unacknowledged so a newly-started process cannot act before its first compiled snapshot.
         */
        val ruleRefreshRequests: StateFlow<Long> = mutableRuleRefreshRequests.asStateFlow()

        /** Publishes the listener's first persisted value without overriding a newer mutation. */
        fun initializeFromPersistedState(enabled: Boolean) {
            synchronized(lock) {
                if (!filteringInitialized) {
                    filteringInitialized = true
                    filteringAllowed = enabled
                }
            }
        }

        /** Immediately prevents new destructive actions and waits for an in-progress one to finish. */
        suspend fun blockActions() {
            actionMutex.withLock {
                synchronized(lock) {
                    filteringInitialized = true
                    filteringAllowed = false
                }
            }
        }

        /** Allows actions only after an enabling preference mutation has persisted successfully. */
        fun allowActions() {
            synchronized(lock) {
                filteringInitialized = true
                filteringAllowed = true
            }
        }

        /** Closes the gate and publishes a new id, or defers it until an active mutation finishes. */
        suspend fun requestRuleRefresh(): Long =
            actionMutex.withLock {
                synchronized(lock) {
                    latestRuleRefreshId += 1
                    ruleReady = false
                    if (!ruleMutationActive) {
                        mutableRuleRefreshRequests.value = latestRuleRefreshId
                    }
                    latestRuleRefreshId
                }
            }

        /** Marks rules ready only when [requestId] still names the newest requested snapshot. */
        fun acknowledgeRuleRefresh(requestId: Long) {
            synchronized(lock) {
                if (!ruleMutationActive && requestId == latestRuleRefreshId) {
                    ruleReady = true
                }
            }
        }

        /** Keeps the latest failed refresh pending without triggering an immediate retry loop. */
        fun rejectRuleRefresh(requestId: Long) {
            synchronized(lock) {
                if (requestId == latestRuleRefreshId) {
                    ruleReady = false
                }
            }
        }

        /**
         * Serializes a rule mutation, closes actions before it begins, and publishes one fresh-query
         * request after the mutation succeeds, fails, or is cancelled.
         */
        suspend fun <T> withRuleMutation(mutation: suspend () -> T): T =
            ruleMutationMutex.withLock {
                actionMutex.withLock {
                    synchronized(lock) {
                        ruleMutationActive = true
                        latestRuleRefreshId += 1
                        ruleReady = false
                    }
                }
                try {
                    mutation()
                } finally {
                    synchronized(lock) {
                        ruleMutationActive = false
                        mutableRuleRefreshRequests.value = latestRuleRefreshId
                    }
                }
            }

        /** Runs [action] while holding the same boundary used by [blockActions]. */
        suspend fun runIfAllowed(action: () -> Unit): Boolean =
            actionMutex.withLock {
                val allowed =
                    synchronized(lock) {
                        filteringInitialized && filteringAllowed && ruleReady
                    }
                if (allowed) action()
                allowed
            }

        private companion object {
            const val INITIAL_RULE_REFRESH_ID = 0L
        }
    }
