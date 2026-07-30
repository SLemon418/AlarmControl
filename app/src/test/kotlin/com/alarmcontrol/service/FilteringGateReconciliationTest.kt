package com.alarmcontrol.service

import com.alarmcontrol.core.settings.FilteringActionGate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilteringGateReconciliationTest {
    @Test
    fun `delayed stale enable emission cannot reopen a gate already persisted false`() =
        runTest {
            val gate = FilteringActionGate()
            gate.initializeFromPersistedState(true)
            gate.acknowledgeRuleRefresh(gate.ruleRefreshRequests.value)
            gate.blockActions()

            reconcileListenerFilteringState(
                filteringActionGate = gate,
                previousFiltering = false,
                filtering = true,
            )
            gate.acknowledgeRuleRefresh(gate.ruleRefreshRequests.value)

            assertFalse(gate.runIfAllowed {})
        }

    @Test
    fun `repository authorized enable still waits for fresh rules before acting`() =
        runTest {
            val gate = FilteringActionGate()
            gate.initializeFromPersistedState(false)
            gate.allowActions()

            reconcileListenerFilteringState(
                filteringActionGate = gate,
                previousFiltering = false,
                filtering = true,
            )
            assertFalse(gate.runIfAllowed {})

            gate.acknowledgeRuleRefresh(gate.ruleRefreshRequests.value)

            assertTrue(gate.runIfAllowed {})
        }
}
