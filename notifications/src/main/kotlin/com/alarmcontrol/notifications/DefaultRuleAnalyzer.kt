package com.alarmcontrol.notifications

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAnalysisIssue
import com.alarmcontrol.core.filtering.RuleAnalysisIssueKind
import com.alarmcontrol.core.filtering.RuleAnalyzer

/** Pure conservative analyzer; it reports only structural facts and never guesses string overlap. */
class DefaultRuleAnalyzer : RuleAnalyzer {
    override fun analyze(rules: List<Rule>): List<RuleAnalysisIssue> =
        buildList {
            addAll(analyzeRuleOrdering(rules))
            rules.forEach { rule -> analyzeCondition(rule.id, rule.condition, this) }
        }

    private fun analyzeRuleOrdering(rules: List<Rule>): List<RuleAnalysisIssue> {
        val enabled = rules.filter { it.enabled }
        return buildList {
            enabled.forEachIndexed { index, rule ->
                val blocker =
                    enabled
                        .take(index)
                        .filter { it.executionMode == rule.executionMode }
                        .filter { it.priority >= rule.priority }
                        .firstOrNull { it.condition == rule.condition }
                        ?: enabled
                            .filter { it.executionMode == rule.executionMode }
                            .filter { it.priority > rule.priority }
                            .firstOrNull { it.condition == rule.condition }
                if (blocker != null) {
                    val duplicate = blocker.action == rule.action
                    add(
                        RuleAnalysisIssue(
                            ruleId = rule.id,
                            kind =
                                if (duplicate) {
                                    RuleAnalysisIssueKind.DUPLICATE
                                } else {
                                    RuleAnalysisIssueKind.SHADOWED
                                },
                            message =
                                if (duplicate) {
                                    "This rule duplicates an earlier rule in the same mode."
                                } else {
                                    "An earlier rule with the same condition always wins."
                                },
                            relatedRuleId = blocker.id,
                        ),
                    )
                }
            }
        }
    }

    private fun analyzeCondition(
        ruleId: String,
        condition: Condition,
        issues: MutableList<RuleAnalysisIssue>,
    ) {
        when (condition) {
            is Condition.AllOf -> {
                analyzeAllOf(ruleId, condition.conditions, issues)
                if (condition.conditions.size == 1) issues += redundantGroup(ruleId)
                condition.conditions.forEach { analyzeCondition(ruleId, it, issues) }
            }
            is Condition.AnyOf -> {
                if (condition.conditions.size == 1) issues += redundantGroup(ruleId)
                condition.conditions.forEach { analyzeCondition(ruleId, it, issues) }
            }
            is Condition.Not -> {
                if (condition.condition is Condition.Not) {
                    issues +=
                        RuleAnalysisIssue(
                            ruleId,
                            RuleAnalysisIssueKind.DOUBLE_NEGATION,
                            "Double negation can be replaced by its inner condition.",
                        )
                }
                analyzeCondition(ruleId, condition.condition, issues)
            }
            else -> Unit
        }
    }

    private fun analyzeAllOf(
        ruleId: String,
        children: List<Condition>,
        issues: MutableList<RuleAnalysisIssue>,
    ) {
        val impossible =
            listOf(
                children
                    .filterIsInstance<Condition.PackageEquals>()
                    .map { it.packageName }
                    .distinct()
                    .size,
                children
                    .filterIsInstance<Condition.CategoryEquals>()
                    .map { it.category }
                    .distinct()
                    .size,
                children
                    .filterIsInstance<Condition.ChannelEquals>()
                    .map { it.channelId }
                    .distinct()
                    .size,
            ).any { it > 1 }
        if (impossible) {
            issues +=
                RuleAnalysisIssue(
                    ruleId,
                    RuleAnalysisIssueKind.IMPOSSIBLE_CONJUNCTION,
                    "This AND group requires mutually exclusive package, category, or channel values.",
                )
        }

        val booleanContradiction =
            listOf(
                children
                    .filterIsInstance<Condition.Ongoing>()
                    .map { it.value }
                    .distinct()
                    .size,
                children
                    .filterIsInstance<Condition.Conversation>()
                    .map { it.value }
                    .distinct()
                    .size,
                children
                    .filterIsInstance<Condition.ForegroundService>()
                    .map { it.value }
                    .distinct()
                    .size,
                children
                    .filterIsInstance<Condition.IsAdvertisement>()
                    .map { it.value }
                    .distinct()
                    .size,
            ).any { it > 1 }
        if (booleanContradiction) {
            issues +=
                RuleAnalysisIssue(
                    ruleId,
                    RuleAnalysisIssueKind.BOOLEAN_CONTRADICTION,
                    "This AND group requires both true and false for the same Boolean signal.",
                )
        }

        val positives = children.filterNot { it is Condition.Not }.toSet()
        if (children.filterIsInstance<Condition.Not>().any { it.condition in positives }) {
            issues +=
                RuleAnalysisIssue(
                    ruleId,
                    RuleAnalysisIssueKind.CONDITION_AND_NEGATION,
                    "This AND group contains both a condition and its negation.",
                )
        }
    }

    private fun redundantGroup(ruleId: String) =
        RuleAnalysisIssue(
            ruleId,
            RuleAnalysisIssueKind.REDUNDANT_GROUP,
            "A one-child group can be replaced by its child.",
        )
}
