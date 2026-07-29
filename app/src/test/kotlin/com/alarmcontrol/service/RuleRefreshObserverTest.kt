package com.alarmcontrol.service

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.settings.FilteringActionGate
import com.alarmcontrol.notifications.Matcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RuleRefreshObserverTest {
    @Test
    fun `refresh request resubscribes and acknowledges the newly compiled snapshot`() =
        runTest {
            val gate = FilteringActionGate()
            gate.initializeFromPersistedState(true)
            var subscriptions = 0
            var currentRules = listOf(rule("old"))
            val published = mutableListOf<Pair<Long, List<String>>>()
            backgroundScope.launch {
                collectCompiledRuleRefreshes(
                    refreshRequests = gate.ruleRefreshRequests,
                    observeRules = {
                        subscriptions += 1
                        activeRuleFlow(currentRules)
                    },
                    matcher = Matcher(),
                    publish = { requestId, compiled ->
                        published += requestId to compiled.rules.map(Rule::id)
                        gate.acknowledgeRuleRefresh(requestId)
                    },
                )
            }
            runCurrent()

            assertEquals(1, subscriptions)
            assertEquals(0L to listOf("old"), published.single())
            assertTrue(gate.runIfAllowed {})

            // Simulates a Room invalidation that already happened before the explicit request.
            currentRules = listOf(rule("new"))
            val requestId = gate.requestRuleRefresh()
            assertFalse(gate.runIfAllowed {})
            runCurrent()

            assertEquals(2, subscriptions)
            assertEquals(requestId to listOf("new"), published.last())
            assertTrue(gate.runIfAllowed {})
        }

    @Test
    fun `completed rule flow stays closed and recovers after bounded retry`() =
        runTest {
            val gate = FilteringActionGate()
            gate.initializeFromPersistedState(true)
            var subscriptions = 0
            var failures = 0
            val published = mutableListOf<List<String>>()
            backgroundScope.launch {
                collectCompiledRuleRefreshes(
                    refreshRequests = gate.ruleRefreshRequests,
                    observeRules = {
                        subscriptions += 1
                        if (subscriptions == 1) {
                            flow { emit(listOf(rule("old"))) }
                        } else {
                            activeRuleFlow(listOf(rule("recovered")))
                        }
                    },
                    matcher = Matcher(),
                    publish = { requestId, compiled ->
                        published += compiled.rules.map(Rule::id)
                        gate.acknowledgeRuleRefresh(requestId)
                    },
                    onFailure = { requestId ->
                        failures += 1
                        gate.rejectRuleRefresh(requestId)
                    },
                )
            }
            runCurrent()

            assertEquals(1, subscriptions)
            assertEquals(1, failures)
            assertEquals(listOf("old"), published.single())
            assertFalse(gate.runIfAllowed {})

            advanceTimeBy(499)
            runCurrent()
            assertEquals(1, subscriptions)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, subscriptions)
            assertEquals(listOf("recovered"), published.last())
            assertTrue(gate.runIfAllowed {})
        }

    @Test
    fun `new refresh request cancels retry delay and immediately resubscribes`() =
        runTest {
            val gate = FilteringActionGate()
            gate.initializeFromPersistedState(true)
            var subscriptions = 0
            backgroundScope.launch {
                collectCompiledRuleRefreshes(
                    refreshRequests = gate.ruleRefreshRequests,
                    observeRules = {
                        subscriptions += 1
                        if (subscriptions == 1) {
                            flow { throw IOException("database unavailable") }
                        } else {
                            activeRuleFlow(listOf(rule("latest")))
                        }
                    },
                    matcher = Matcher(),
                    publish = { requestId, _ -> gate.acknowledgeRuleRefresh(requestId) },
                    onFailure = gate::rejectRuleRefresh,
                )
            }
            runCurrent()
            assertEquals(1, subscriptions)

            gate.requestRuleRefresh()
            runCurrent()

            assertEquals(2, subscriptions)
            assertTrue(gate.runIfAllowed {})
        }

    @Test
    fun `rule refresh retry delay is capped without overflow`() {
        assertEquals(500L, ruleRefreshRetryDelayMillis(0))
        assertEquals(1_000L, ruleRefreshRetryDelayMillis(1))
        assertEquals(30_000L, ruleRefreshRetryDelayMillis(Int.MAX_VALUE))
    }

    private fun rule(id: String) =
        Rule(
            id = id,
            name = id,
            condition = Condition.PackageEquals("com.example.$id"),
            action = RuleAction.Cancel,
        )

    private fun activeRuleFlow(rules: List<Rule>) =
        flow {
            emit(rules)
            awaitCancellation()
        }
}
