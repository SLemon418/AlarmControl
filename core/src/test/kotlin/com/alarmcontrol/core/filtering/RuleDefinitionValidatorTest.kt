package com.alarmcontrol.core.filtering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleDefinitionValidatorTest {
    @Test
    fun `valid nested rule has no issues`() {
        val rule =
            rule(
                name = "Night promotions",
                condition =
                    Condition.AllOf(
                        listOf(
                            Condition.PackageEquals("com.example"),
                            Condition.TimeWindow(1_320, 420),
                        ),
                    ),
            )

        assertTrue(RuleDefinitionValidator.validate(rule).isEmpty())
    }

    @Test
    fun `blank name and empty group are rejected`() {
        val issues = RuleDefinitionValidator.validate(rule(name = " ", condition = Condition.AllOf(emptyList())))

        assertEquals(
            setOf(RuleValidationIssue.BLANK_NAME, RuleValidationIssue.EMPTY_COMPOSITE),
            issues,
        )
    }

    @Test
    fun `oversized condition tree is rejected`() {
        val condition =
            Condition.AllOf(
                List(MAX_RULE_CONDITION_NODES) { Condition.CategoryEquals("message") },
            )

        assertTrue(
            RuleValidationIssue.CONDITION_TREE_TOO_LARGE in
                RuleDefinitionValidator.validate(rule(condition = condition)),
        )
    }

    private fun rule(
        name: String = "Rule",
        condition: Condition,
    ) = Rule(
        id = "",
        name = name,
        condition = condition,
        action = RuleAction.Cancel,
    )
}
