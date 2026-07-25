package com.alarmcontrol.core.filtering

/** Conservative, side-effect-free diagnostics for user-authored rule trees. */
interface RuleAnalyzer {
    /** Returns non-blocking findings. Saving remains allowed even when findings exist. */
    fun analyze(rules: List<Rule>): List<RuleAnalysisIssue>
}

data class RuleAnalysisIssue(
    val ruleId: String,
    val kind: RuleAnalysisIssueKind,
    val message: String,
    val relatedRuleId: String? = null,
)

enum class RuleAnalysisIssueKind {
    DUPLICATE,
    SHADOWED,
    IMPOSSIBLE_CONJUNCTION,
    BOOLEAN_CONTRADICTION,
    CONDITION_AND_NEGATION,
    DOUBLE_NEGATION,
    REDUNDANT_GROUP,
}
