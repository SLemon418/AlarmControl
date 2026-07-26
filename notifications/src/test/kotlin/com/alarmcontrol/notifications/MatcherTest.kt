package com.alarmcontrol.notifications

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.ConditionResult
import com.alarmcontrol.core.filtering.DecisionConditionKind
import com.alarmcontrol.core.filtering.DecisionTraceLane
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.RateSignal
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.SemanticIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {
    private val matcher = Matcher()

    private val alarmNotification =
        NotificationSnapshot(
            packageName = "com.example.clock",
            title = "Alarm",
            text = "Time to wake up",
            category = "alarm",
            channelId = "alarms",
            postedAtMillis = 0L,
            isOngoing = false,
        )

    private fun rule(
        id: String,
        condition: Condition,
        action: RuleAction = RuleAction.Cancel,
        enabled: Boolean = true,
        priority: Int = 0,
        mode: RuleExecutionMode = RuleExecutionMode.ACTIVE,
    ) = Rule(
        id = id,
        name = id,
        enabled = enabled,
        priority = priority,
        condition = condition,
        action = action,
        executionMode = mode,
    )

    @Test
    fun `returns NoMatch when no rule applies`() {
        val rules = listOf(rule("r1", Condition.PackageEquals("com.other.app")))
        assertEquals(MatchDecision.NoMatch, matcher.evaluate(alarmNotification, rules))
    }

    @Test
    fun `returns NoMatch for an empty rule set`() {
        assertEquals(MatchDecision.NoMatch, matcher.evaluate(alarmNotification, emptyList()))
    }

    @Test
    fun `matched decision carries the rule and its action`() {
        val r = rule("snooze-alarms", Condition.CategoryEquals("alarm"), action = RuleAction.Snooze(60_000L))
        val decision = matcher.evaluate(alarmNotification, listOf(r))
        assertEquals(MatchDecision.Matched(r, RuleAction.Snooze(60_000L)), decision)
    }

    @Test
    fun `disabled rules are skipped`() {
        val disabled = rule("r1", Condition.CategoryEquals("alarm"), enabled = false)
        assertEquals(MatchDecision.NoMatch, matcher.evaluate(alarmNotification, listOf(disabled)))
    }

    @Test
    fun `higher priority wins over a lower-priority match`() {
        val low = rule("low", Condition.CategoryEquals("alarm"), action = RuleAction.MarkRead, priority = 1)
        val high = rule("high", Condition.CategoryEquals("alarm"), action = RuleAction.Cancel, priority = 10)
        val decision = matcher.evaluate(alarmNotification, listOf(low, high))
        assertEquals(MatchDecision.Matched(high, RuleAction.Cancel), decision)
    }

    @Test
    fun `equal priority keeps declaration order`() {
        val first = rule("first", Condition.CategoryEquals("alarm"), action = RuleAction.Cancel, priority = 5)
        val second = rule("second", Condition.CategoryEquals("alarm"), action = RuleAction.MarkRead, priority = 5)
        val decision = matcher.evaluate(alarmNotification, listOf(first, second))
        assertEquals(MatchDecision.Matched(first, RuleAction.Cancel), decision)
    }

    @Test
    fun `ml category condition participates like any other rule condition`() {
        val withMl = alarmNotification.copy(mlCategory = "promotion")
        val r =
            rule(
                "mute-promos",
                Condition.AllOf(
                    listOf(
                        Condition.PackageEquals("com.example.clock"),
                        Condition.MlCategoryEquals("promotion"),
                    ),
                ),
                action = RuleAction.Cancel,
            )
        assertEquals(MatchDecision.Matched(r, RuleAction.Cancel), matcher.evaluate(withMl, listOf(r)))
        // Without the ML signal the same rule no longer matches (graceful degradation, §5).
        assertEquals(MatchDecision.NoMatch, matcher.evaluate(alarmNotification, listOf(r)))
    }

    @Test
    fun `first matching rule wins among different conditions`() {
        val byPackage = rule("by-package", Condition.PackageEquals("com.example.clock"), action = RuleAction.Cancel)
        val byCategory = rule("by-category", Condition.CategoryEquals("alarm"), action = RuleAction.MarkRead)
        val decision = matcher.evaluate(alarmNotification, listOf(byPackage, byCategory))
        assertTrue(decision is MatchDecision.Matched)
        assertEquals("by-package", (decision as MatchDecision.Matched).rule.id)
    }

    @Test
    fun `compound rule with a night time-window only matches at night`() {
        // "from com.example.clock AND (category alarm OR ML promotion) AND between 22:00 and 07:00"
        val nightMute =
            rule(
                "night-mute",
                Condition.AllOf(
                    listOf(
                        Condition.PackageEquals("com.example.clock"),
                        Condition.AnyOf(
                            listOf(
                                Condition.CategoryEquals("alarm"),
                                Condition.MlCategoryEquals("promotion"),
                            ),
                        ),
                        Condition.TimeWindow(startMinuteOfDay = 22 * 60, endMinuteOfDay = 7 * 60),
                    ),
                ),
                action = RuleAction.Snooze(60_000L),
            )

        val atNight = alarmNotification.copy(postedMinuteOfDay = 23 * 60)
        val atNoon = alarmNotification.copy(postedMinuteOfDay = 12 * 60)

        assertEquals(
            MatchDecision.Matched(nightMute, RuleAction.Snooze(60_000L)),
            matcher.evaluate(atNight, listOf(nightMute)),
        )
        assertEquals(MatchDecision.NoMatch, matcher.evaluate(atNoon, listOf(nightMute)))
    }

    @Test
    fun `compile drops disabled rules and sorts by descending priority`() {
        val compiled =
            matcher.compile(
                listOf(
                    rule("low", Condition.CategoryEquals("alarm"), priority = 1),
                    rule("disabled", Condition.CategoryEquals("alarm"), enabled = false, priority = 100),
                    rule("high", Condition.CategoryEquals("alarm"), priority = 10),
                ),
            )
        assertEquals(listOf("high", "low"), compiled.rules.map { it.id })
    }

    @Test
    fun `compile reports only expensive signals referenced by enabled nested rules`() {
        val compiled =
            matcher.compile(
                listOf(
                    rule(
                        "nested",
                        Condition.AllOf(
                            listOf(
                                Condition.PackageEquals("com.x"),
                                Condition.Not(Condition.MlCategoryEquals("promotion")),
                            ),
                        ),
                    ),
                    rule("disabled-ad", Condition.IsAdvertisement(true), enabled = false),
                ),
            )

        assertEquals(RuleSignalRequirements(mlCategory = true, advertisement = false), compiled.requiredSignals)
    }

    @Test
    fun `compile reports advertisement signal through a nested any group`() {
        val compiled =
            matcher.compile(
                listOf(
                    rule(
                        "ad",
                        Condition.AnyOf(
                            listOf(Condition.CategoryEquals("promo"), Condition.IsAdvertisement(true)),
                        ),
                    ),
                ),
            )

        assertEquals(RuleSignalRequirements(advertisement = true), compiled.requiredSignals)
    }

    @Test
    fun `compile unions semantic and frequency requirements across active and monitor lanes`() {
        val rateSignal = RateSignal(RateScope.PACKAGE, 60_000)
        val compiled =
            matcher.compile(
                listOf(
                    rule(
                        "rate",
                        Condition.RateAtLeast(RateScope.PACKAGE, 60_000, 2),
                    ),
                    rule(
                        "semantic-monitor",
                        Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                        mode = RuleExecutionMode.MONITOR,
                    ),
                ),
            )

        assertEquals(setOf(rateSignal), compiled.activeRequiredSignals.rateSignals)
        assertTrue(compiled.monitorRequiredSignals.semanticIntent)
        assertEquals(setOf(rateSignal), compiled.requiredSignals.rateSignals)
        assertTrue(compiled.requiredSignals.semanticIntent)
    }

    @Test
    fun `evaluate over a compiled set returns the highest-priority match`() {
        val high = rule("high", Condition.CategoryEquals("alarm"), action = RuleAction.Cancel, priority = 10)
        val compiled =
            matcher.compile(
                listOf(
                    rule("low", Condition.CategoryEquals("alarm"), action = RuleAction.MarkRead, priority = 1),
                    high,
                ),
            )
        assertEquals(MatchDecision.Matched(high, RuleAction.Cancel), matcher.evaluate(alarmNotification, compiled))
    }

    @Test
    fun `active and monitor rules are evaluated independently`() {
        val active =
            rule(
                "active",
                Condition.CategoryEquals("alarm"),
                action = RuleAction.Keep,
                priority = 1,
            )
        val monitor =
            rule(
                "monitor",
                Condition.CategoryEquals("alarm"),
                action = RuleAction.Cancel,
                priority = 100,
                mode = RuleExecutionMode.MONITOR,
            )
        val compiled = matcher.compile(listOf(monitor, active))

        assertEquals(MatchDecision.Matched(active, RuleAction.Keep), matcher.evaluate(alarmNotification, compiled))
        assertEquals(
            MatchDecision.Matched(monitor, RuleAction.Cancel),
            matcher.evaluateMonitor(alarmNotification, compiled),
        )
    }

    @Test
    fun `persisted decision trace is content free and bounded`() {
        var condition: Condition = Condition.PackageEquals("com.example.clock")
        repeat(140) { condition = Condition.Not(condition) }
        val monitored = rule("monitor", condition, mode = RuleExecutionMode.MONITOR)
        val decision = MatchDecision.Matched(monitored, monitored.action)

        val trace = matcher.decisionTrace(alarmNotification, decision, DecisionTraceLane.MONITOR)

        assertEquals(128, trace.size)
        assertEquals(DecisionConditionKind.TRUNCATED, trace.last().kind)
        assertTrue(trace.all { it.lane == DecisionTraceLane.MONITOR })
    }

    @Test
    fun `active and monitor traces share one global 128 node cap`() {
        var activeCondition: Condition = Condition.PackageEquals("com.example.clock")
        var monitorCondition: Condition = Condition.PackageEquals("com.example.clock")
        repeat(100) {
            activeCondition = Condition.Not(activeCondition)
            monitorCondition = Condition.Not(monitorCondition)
        }
        val active = rule("active", activeCondition)
        val monitor = rule("monitor", monitorCondition, mode = RuleExecutionMode.MONITOR)

        val evaluation =
            matcher.evaluateWithTraces(
                activeSnapshot = alarmNotification,
                monitorSnapshot = alarmNotification,
                compiled = matcher.compile(listOf(active, monitor)),
            )
        val trace = evaluation.decisionTrace

        assertEquals(128, trace.size)
        assertEquals(MatchDecision.Matched(active, active.action), evaluation.activeDecision)
        assertEquals(MatchDecision.Matched(monitor, monitor.action), evaluation.monitorDecision)
        assertEquals(96, trace.count { it.lane == DecisionTraceLane.ACTIVE })
        assertEquals(32, trace.count { it.lane == DecisionTraceLane.MONITOR })
        assertEquals(DecisionConditionKind.TRUNCATED, trace[95].kind)
        assertEquals(DecisionConditionKind.TRUNCATED, trace.last().kind)
    }

    @Test
    fun `evaluation trace contains only the short circuited condition path`() {
        val condition =
            Condition.AnyOf(
                listOf(
                    Condition.PackageEquals("com.example.clock"),
                    Condition.TextContains("must not be evaluated"),
                ),
            )
        val active = rule("active", condition)

        val evaluation =
            matcher.evaluateWithTraces(
                activeSnapshot = alarmNotification,
                monitorSnapshot = alarmNotification,
                compiled = matcher.compile(listOf(active)),
            )

        assertEquals(MatchDecision.Matched(active, active.action), evaluation.activeDecision)
        assertEquals(
            listOf(DecisionConditionKind.ANY_OF, DecisionConditionKind.PACKAGE),
            evaluation.decisionTrace.map { it.kind },
        )
    }

    @Test
    fun `unknown high-priority signal is skipped and explain reports why`() {
        val unknown = rule("unknown", Condition.IsAdvertisement(true), priority = 10)
        val fallback = rule("fallback", Condition.CategoryEquals("alarm"), priority = 1)

        val explanation = matcher.explain(alarmNotification, listOf(fallback, unknown))

        assertEquals(MatchDecision.Matched(fallback, RuleAction.Cancel), explanation.decision)
        assertEquals(
            listOf(ConditionResult.UNKNOWN, ConditionResult.MATCH),
            explanation.evaluatedRules.map { it.result },
        )
    }

    @Test
    fun `explain preserves the recursive condition tree`() {
        val condition =
            Condition.AllOf(
                listOf(
                    Condition.PackageEquals("com.example.clock"),
                    Condition.Not(Condition.MlCategoryEquals("promotion")),
                ),
            )

        val trace = matcher.explain(alarmNotification, listOf(rule("nested", condition))).evaluatedRules.single()

        assertEquals(ConditionResult.UNKNOWN, trace.result)
        assertEquals(2, trace.condition.children.size)
        assertEquals(
            1,
            trace.condition.children
                .last()
                .children.size,
        )
    }
}
