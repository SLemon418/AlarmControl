package com.alarmcontrol.notifications

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.ConditionResult
import com.alarmcontrol.core.filtering.Rule

/** Side-effect-free diagnostic result used by the rule simulator, never by the hot service path. */
data class MatchExplanation(
    val decision: MatchDecision,
    val evaluatedRules: List<RuleTrace>,
)

data class RuleTrace(
    val rule: Rule,
    val result: ConditionResult,
    val condition: ConditionTrace,
)

data class ConditionTrace(
    val condition: Condition,
    val result: ConditionResult,
    val children: List<ConditionTrace> = emptyList(),
)
