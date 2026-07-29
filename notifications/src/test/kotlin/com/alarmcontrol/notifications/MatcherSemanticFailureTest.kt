package com.alarmcontrol.notifications

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.DecisionTraceLane
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.SemanticIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherSemanticFailureTest {
    private val matcher = Matcher()
    private val snapshot =
        NotificationSnapshot(
            packageName = "com.example.clock",
            title = "Alarm",
            text = "Time to wake up",
            category = "alarm",
            channelId = "alarms",
            postedAtMillis = 0L,
            isOngoing = false,
        )

    @Test
    fun `semantic failure preserves a lower same-action match and both lane traces`() {
        val higherSemantic =
            rule(
                "higher-semantic",
                Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                action = RuleAction.Cancel,
                priority = 100,
            )
        val fallback =
            rule(
                "fallback",
                Condition.CategoryEquals("alarm"),
                action = RuleAction.Cancel,
                priority = 10,
            )
        val monitor =
            rule(
                "monitor",
                Condition.CategoryEquals("alarm"),
                action = RuleAction.Keep,
                priority = 10,
                mode = RuleExecutionMode.MONITOR,
            )

        val evaluation =
            matcher.evaluateAfterSemanticFailureWithTraces(
                snapshot,
                matcher.compile(listOf(fallback, monitor, higherSemantic)),
            )

        assertEquals(MatchDecision.Matched(fallback, fallback.action), evaluation.activeDecision)
        assertEquals(MatchDecision.Matched(monitor, monitor.action), evaluation.monitorDecision)
        assertTrue(evaluation.decisionTrace.any { it.lane == DecisionTraceLane.ACTIVE })
        assertTrue(evaluation.decisionTrace.any { it.lane == DecisionTraceLane.MONITOR })
    }

    @Test
    fun `semantic action conflict drops active match while preserving the full monitor trace`() {
        val higherSemantic =
            rule(
                "higher-semantic",
                Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                action = RuleAction.Keep,
                priority = 100,
            )
        val fallback =
            rule(
                "fallback",
                Condition.CategoryEquals("alarm"),
                action = RuleAction.Cancel,
                priority = 10,
            )
        var monitorCondition: Condition = Condition.CategoryEquals("alarm")
        repeat(100) {
            monitorCondition = Condition.Not(monitorCondition)
        }
        val monitor =
            rule(
                "monitor",
                monitorCondition,
                action = RuleAction.Keep,
                priority = 10,
                mode = RuleExecutionMode.MONITOR,
            )

        val evaluation =
            matcher.evaluateAfterSemanticFailureWithTraces(
                snapshot,
                matcher.compile(listOf(fallback, monitor, higherSemantic)),
            )

        assertEquals(MatchDecision.NoMatch, evaluation.activeDecision)
        assertEquals(MatchDecision.Matched(monitor, monitor.action), evaluation.monitorDecision)
        assertEquals(101, evaluation.decisionTrace.size)
        assertTrue(evaluation.decisionTrace.all { it.lane == DecisionTraceLane.MONITOR })
    }

    @Test
    fun `shadowed different action does not conflict with the selected same action`() {
        val selectedSemantic =
            rule(
                "selected-semantic",
                Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                action = RuleAction.Cancel,
                priority = 100,
            )
        val shadowedSemantic =
            rule(
                "shadowed-semantic",
                Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                action = RuleAction.Keep,
                priority = 90,
            )
        val fallback =
            rule(
                "fallback",
                Condition.CategoryEquals("alarm"),
                action = RuleAction.Cancel,
                priority = 10,
            )

        val evaluation =
            matcher.evaluateAfterSemanticFailureWithTraces(
                snapshot,
                matcher.compile(listOf(fallback, shadowedSemantic, selectedSemantic)),
            )

        assertEquals(MatchDecision.Matched(fallback, fallback.action), evaluation.activeDecision)
        assertTrue(evaluation.decisionTrace.any { it.lane == DecisionTraceLane.ACTIVE })
    }

    @Test
    fun `explicit Keep fallback matches the default Keep effect`() {
        val semanticKeep =
            rule(
                "semantic-keep",
                Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                action = RuleAction.Keep,
                priority = 100,
            )
        val fallbackKeep =
            rule(
                "fallback-keep",
                Condition.CategoryEquals("alarm"),
                action = RuleAction.Keep,
                priority = 10,
            )

        val evaluation =
            matcher.evaluateAfterSemanticFailureWithTraces(
                snapshot,
                matcher.compile(listOf(fallbackKeep, semanticKeep)),
            )

        assertEquals(
            MatchDecision.Matched(fallbackKeep, RuleAction.Keep),
            evaluation.activeDecision,
        )
        assertTrue(evaluation.decisionTrace.any { it.lane == DecisionTraceLane.ACTIVE })
    }

    private fun rule(
        id: String,
        condition: Condition,
        action: RuleAction,
        priority: Int,
        mode: RuleExecutionMode = RuleExecutionMode.ACTIVE,
    ) = Rule(
        id = id,
        name = id,
        priority = priority,
        condition = condition,
        action = action,
        executionMode = mode,
    )
}
