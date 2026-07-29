package com.alarmcontrol.notifications

import com.alarmcontrol.core.filtering.Rule

/**
 * Rules prepared for fast, repeated evaluation (CLAUDE.md §6 / Milestone 3 performance): disabled
 * rules are dropped and the rest sorted by descending priority **once**, so evaluating each incoming
 * notification avoids re-filtering and re-sorting. Build it with [Matcher.compile] whenever the rule
 * set changes; use [EMPTY] as an initial/empty value.
 */
class CompiledRuleSet internal constructor(
    val activeRules: List<Rule>,
    val monitorRules: List<Rule>,
    val activeRequiredSignals: RuleSignalRequirements,
    val monitorRequiredSignals: RuleSignalRequirements,
    val requiredSignals: RuleSignalRequirements,
) {
    /** Compatibility view used by the existing active-rule simulator. */
    val rules: List<Rule> = activeRules

    companion object {
        val EMPTY =
            CompiledRuleSet(
                activeRules = emptyList(),
                monitorRules = emptyList(),
                activeRequiredSignals = RuleSignalRequirements(),
                monitorRequiredSignals = RuleSignalRequirements(),
                requiredSignals = RuleSignalRequirements(),
            )
    }
}

/** Expensive enrichment signals actually referenced by at least one enabled rule. */
data class RuleSignalRequirements(
    val mlCategory: Boolean = false,
    val advertisement: Boolean = false,
    val semanticIntent: Boolean = false,
    val rateSignals: Set<com.alarmcontrol.core.filtering.RateSignal> = emptySet(),
)

/** Whether resolving the legacy ML category can change either lane's selected rule. */
data class CategoryResolutionRequirements(
    val activeNeedsCategory: Boolean,
    val monitorNeedsCategory: Boolean,
) {
    val any: Boolean = activeNeedsCategory || monitorNeedsCategory
}

/** Whether a missing frequency signal blocks the first definite match in either lane. */
data class RateResolutionRequirements(
    val activeNeedsRate: Boolean,
    val monitorNeedsRate: Boolean,
) {
    val any: Boolean = activeNeedsRate || monitorNeedsRate
}

/** Whether resolving the trusted semantic intent can change either lane's selected rule. */
data class SemanticResolutionRequirements(
    val activeNeedsSemantic: Boolean,
    val monitorNeedsSemantic: Boolean,
) {
    val any: Boolean = activeNeedsSemantic || monitorNeedsSemantic
}
