package com.alarmcontrol.notifications

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleAnalysisIssueKind
import com.alarmcontrol.core.filtering.RuleExecutionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRuleAnalyzerTest {
    private val analyzer = DefaultRuleAnalyzer()

    @Test
    fun `reports duplicate and shadow only within the same execution mode`() {
        val active = rule("active", 10, RuleAction.Cancel)
        val duplicate = rule("duplicate", 1, RuleAction.Cancel)
        val shadowed = rule("shadowed", 0, RuleAction.Keep)
        val monitor = rule("monitor", 0, RuleAction.Cancel, RuleExecutionMode.MONITOR)

        val issues = analyzer.analyze(listOf(active, duplicate, shadowed, monitor))

        assertEquals(RuleAnalysisIssueKind.DUPLICATE, issues.first { it.ruleId == "duplicate" }.kind)
        assertEquals(RuleAnalysisIssueKind.SHADOWED, issues.first { it.ruleId == "shadowed" }.kind)
        assertTrue(issues.none { it.ruleId == "monitor" })
    }

    @Test
    fun `reports only deterministic structural contradictions`() {
        val condition =
            Condition.AllOf(
                listOf(
                    Condition.PackageEquals("one"),
                    Condition.PackageEquals("two"),
                    Condition.Ongoing(true),
                    Condition.Ongoing(false),
                    Condition.CategoryEquals("alarm"),
                    Condition.Not(Condition.CategoryEquals("alarm")),
                    Condition.Not(Condition.Not(Condition.ChannelEquals("x"))),
                    Condition.AnyOf(listOf(Condition.TextContains("word"))),
                ),
            )

        val kinds = analyzer.analyze(listOf(rule("r", 0, RuleAction.Cancel, condition = condition))).map { it.kind }

        assertTrue(RuleAnalysisIssueKind.IMPOSSIBLE_CONJUNCTION in kinds)
        assertTrue(RuleAnalysisIssueKind.BOOLEAN_CONTRADICTION in kinds)
        assertTrue(RuleAnalysisIssueKind.CONDITION_AND_NEGATION in kinds)
        assertTrue(RuleAnalysisIssueKind.DOUBLE_NEGATION in kinds)
        assertTrue(RuleAnalysisIssueKind.REDUNDANT_GROUP in kinds)
    }

    private fun rule(
        id: String,
        priority: Int,
        action: RuleAction,
        mode: RuleExecutionMode = RuleExecutionMode.ACTIVE,
        condition: Condition = Condition.PackageEquals("pkg"),
    ) = Rule(id, id, true, priority, condition, action, mode)
}
