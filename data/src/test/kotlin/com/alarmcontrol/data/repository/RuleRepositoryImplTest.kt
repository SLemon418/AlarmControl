package com.alarmcontrol.data.repository

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.MAX_RULE_NAME_CHARS
import com.alarmcontrol.core.filtering.MAX_SAVED_RULES
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.settings.FilteringActionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleRepositoryImplTest {
    private val dao = FakeRuleDao()
    private val repository = RuleRepositoryImpl(dao)

    private fun newRule(
        name: String = "Mute promos",
        priority: Int = 5,
        condition: Condition =
            Condition.AllOf(
                listOf(
                    Condition.PackageEquals("com.example.shop"),
                    Condition.MlCategoryEquals("promotion"),
                ),
            ),
        action: RuleAction = RuleAction.Snooze(60_000L),
    ) = Rule(id = "", name = name, enabled = true, priority = priority, condition = condition, action = action)

    @Test
    fun `saveRule persists and observe returns the domain rule with the assigned id`() =
        runTest {
            val rule = newRule()

            val id = repository.saveRule(rule)
            val stored = repository.observeRules().first()

            assertEquals(listOf(rule.copy(id = id)), stored)
        }

    @Test
    fun `deleteRule removes the rule and its conditions`() =
        runTest {
            val id = repository.saveRule(newRule())

            repository.deleteRule(id)

            assertTrue(repository.observeRules().first().isEmpty())
        }

    @Test
    fun `saving with an existing id updates in place instead of duplicating`() =
        runTest {
            val id = repository.saveRule(newRule(name = "original"))

            val edited =
                newRule(name = "renamed", condition = Condition.CategoryEquals("alarm"), action = RuleAction.Cancel)
                    .copy(id = id)
            repository.saveRule(edited)

            val stored = repository.observeRules().first()
            assertEquals(listOf(edited), stored)
        }

    @Test
    fun `compound nested rule with a time window round-trips through the repository`() =
        runTest {
            val condition =
                Condition.AllOf(
                    listOf(
                        Condition.PackageEquals("com.example.shop"),
                        Condition.AnyOf(
                            listOf(
                                Condition.TextContains("coupon"),
                                Condition.MlCategoryEquals("promotion"),
                            ),
                        ),
                        Condition.TimeWindow(startMinuteOfDay = 1320, endMinuteOfDay = 420),
                    ),
                )
            val rule = newRule(name = "night promo mute", condition = condition, action = RuleAction.Cancel)

            val id = repository.saveRule(rule)
            val stored = repository.observeRules().first()

            assertEquals(listOf(rule.copy(id = id)), stored)
        }

    @Test
    fun `rules are observed in descending priority order`() =
        runTest {
            val low = repository.saveRule(newRule(name = "low", priority = 1))
            val high = repository.saveRule(newRule(name = "high", priority = 10))

            val ids = repository.observeRules().first().map { it.id }

            assertEquals(listOf(high, low), ids)
        }

    @Test
    fun `saving rejects a rule name that portable backup cannot represent`() =
        runTest {
            val result = runCatching { repository.saveRule(newRule(name = "x".repeat(MAX_RULE_NAME_CHARS + 1))) }

            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }

    @Test
    fun `saving rejects malformed ids and new rules beyond the evaluation bound`() =
        runTest {
            assertTrue(
                runCatching { repository.saveRule(newRule().copy(id = "not-an-id")) }.exceptionOrNull() is
                    IllegalArgumentException,
            )
            dao.countOverride = MAX_SAVED_RULES

            assertTrue(
                runCatching { repository.saveRule(newRule()) }.exceptionOrNull() is IllegalArgumentException,
            )
        }

    @Test
    fun `successful rule mutations request fresh listener snapshots`() =
        runTest {
            val gate = FilteringActionGate()
            val repository = RuleRepositoryImpl(dao, gate)

            val id = repository.saveRule(newRule())
            assertEquals(1L, gate.ruleRefreshRequests.value)

            assertEquals(1, repository.setRulesEnabled(setOf(id), enabled = false))
            assertEquals(2L, gate.ruleRefreshRequests.value)

            assertEquals(0, repository.setRulesEnabled(setOf(id), enabled = false))
            assertEquals(3L, gate.ruleRefreshRequests.value)

            repository.deleteRule(id)
            assertEquals(4L, gate.ruleRefreshRequests.value)
        }

    @Test
    fun `cancelled rule mutation still publishes a closed refresh request`() =
        runTest {
            val gate = FilteringActionGate()
            gate.initializeFromPersistedState(true)
            gate.acknowledgeRuleRefresh(gate.ruleRefreshRequests.value)
            val repository = RuleRepositoryImpl(dao, gate)
            val id = repository.saveRule(newRule())
            gate.acknowledgeRuleRefresh(gate.ruleRefreshRequests.value)
            dao.deleteFailureAfterMutation = CancellationException("cancelled after commit")

            val failure = runCatching { repository.deleteRule(id) }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertTrue(repository.observeRules().first().isEmpty())
            assertEquals(2L, gate.ruleRefreshRequests.value)
            assertFalse(gate.runIfAllowed {})
        }
}
