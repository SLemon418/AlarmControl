package com.alarmcontrol.core.filtering

const val MAX_RULE_CONDITION_DEPTH = 32
const val MAX_RULE_CONDITION_NODES = 256

/** Stable validation failures shared by editors, persistence, and local backup restore. */
enum class RuleValidationIssue {
    BLANK_NAME,
    NAME_TOO_LONG,
    INVALID_SNOOZE,
    EMPTY_COMPOSITE,
    BLANK_CONDITION_VALUE,
    CONDITION_VALUE_TOO_LONG,
    CONDITION_TREE_TOO_DEEP,
    CONDITION_TREE_TOO_LARGE,
}

/** Pure structural validator for user-authored rule definitions. */
object RuleDefinitionValidator {
    fun validate(rule: Rule): Set<RuleValidationIssue> =
        buildSet {
            if (rule.name.isBlank()) add(RuleValidationIssue.BLANK_NAME)
            if (rule.name.length > MAX_RULE_NAME_CHARS) add(RuleValidationIssue.NAME_TOO_LONG)
            val snooze = rule.action as? RuleAction.Snooze
            if (
                snooze != null &&
                snooze.durationMillis !in MIN_SNOOZE_DURATION_MILLIS..MAX_SNOOZE_DURATION_MILLIS
            ) {
                add(RuleValidationIssue.INVALID_SNOOZE)
            }
            val stats = rule.condition.validate(depth = 1, issues = this)
            if (stats > MAX_RULE_CONDITION_NODES) add(RuleValidationIssue.CONDITION_TREE_TOO_LARGE)
        }

    fun requireValid(rule: Rule) {
        val issues = validate(rule)
        require(issues.isEmpty()) { "Invalid rule: ${issues.joinToString()}" }
    }
}

private fun Condition.validate(
    depth: Int,
    issues: MutableSet<RuleValidationIssue>,
): Int {
    if (depth > MAX_RULE_CONDITION_DEPTH) {
        issues += RuleValidationIssue.CONDITION_TREE_TOO_DEEP
        return 1
    }
    return when (this) {
        is Condition.AllOf -> validateChildren(conditions, depth, issues)
        is Condition.AnyOf -> validateChildren(conditions, depth, issues)
        is Condition.Not -> 1 + condition.validate(depth + 1, issues)
        is Condition.PackageEquals -> validateValue(packageName, issues)
        is Condition.TitleContains -> validateValue(text, issues)
        is Condition.TextContains -> validateValue(text, issues)
        is Condition.CategoryEquals -> validateValue(category, issues)
        is Condition.ChannelEquals -> validateValue(channelId, issues)
        is Condition.MlCategoryEquals -> validateValue(category, issues)
        is Condition.Ongoing,
        is Condition.IsAdvertisement,
        is Condition.SemanticIntentEquals,
        is Condition.Conversation,
        is Condition.ForegroundService,
        is Condition.ImportanceAtLeast,
        is Condition.RateAtLeast,
        is Condition.TimeWindow,
        -> 1
    }
}

private fun validateChildren(
    children: List<Condition>,
    depth: Int,
    issues: MutableSet<RuleValidationIssue>,
): Int {
    if (children.isEmpty()) issues += RuleValidationIssue.EMPTY_COMPOSITE
    return 1 + children.sumOf { it.validate(depth + 1, issues) }
}

private fun validateValue(
    value: String,
    issues: MutableSet<RuleValidationIssue>,
): Int {
    if (value.isBlank()) issues += RuleValidationIssue.BLANK_CONDITION_VALUE
    if (value.length > MAX_CONDITION_VALUE_CHARS) issues += RuleValidationIssue.CONDITION_VALUE_TOO_LONG
    return 1
}
