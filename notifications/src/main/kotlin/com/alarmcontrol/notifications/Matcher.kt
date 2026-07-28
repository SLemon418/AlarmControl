package com.alarmcontrol.notifications

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.ConditionResult
import com.alarmcontrol.core.filtering.DecisionConditionKind
import com.alarmcontrol.core.filtering.DecisionTraceLane
import com.alarmcontrol.core.filtering.DecisionTraceNode
import com.alarmcontrol.core.filtering.MAX_PERSISTED_TRACE_NODES
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.SemanticIntent

/**
 * Pure, framework-free rule engine (CLAUDE.md §4/§6): given a [NotificationSnapshot] and the set of
 * user [Rule]s, it returns the [MatchDecision] for that notification. It performs no I/O, holds no
 * state, and never touches the Android framework or runs ML inference itself — the caller supplies
 * any ML signal on the snapshot.
 *
 * Constructed plainly (no DI annotations here, since `:notifications` carries no Hilt dependency);
 * `:app` provides it via constructor injection.
 *
 * **Performance (Milestone 3).** A notification stream is evaluated against the same rules many
 * times, so [compile] the rules **once** into a [CompiledRuleSet] (enabled-only, priority-sorted) and
 * reuse it across notifications via [evaluate]; this avoids re-filtering and re-sorting per event.
 * Condition evaluation also short-circuits — `AllOf` stops at the first non-matching child, `AnyOf`
 * at the first matching child, and rule scanning stops at the first matching rule — so deep nests
 * cost only as much as the shortest disproving path.
 */
class Matcher {
    /**
     * Prepares [rules] for repeated evaluation: drops disabled rules and sorts the rest by descending
     * [Rule.priority] (a stable sort, so ties keep their original order). Call this once per rule-set
     * change and reuse the result; see [CompiledRuleSet].
     */
    fun compile(rules: List<Rule>): CompiledRuleSet =
        rules
            .filter { it.enabled }
            .sortedByDescending { it.priority }
            .let { enabled ->
                val active = enabled.filter { it.executionMode == RuleExecutionMode.ACTIVE }
                val monitor = enabled.filter { it.executionMode == RuleExecutionMode.MONITOR }
                val activeSignals = active.requiredSignals()
                val monitorSignals = monitor.requiredSignals()
                CompiledRuleSet(
                    activeRules = active,
                    monitorRules = monitor,
                    activeRequiredSignals = activeSignals,
                    monitorRequiredSignals = monitorSignals,
                    requiredSignals = activeSignals + monitorSignals,
                )
            }

    /**
     * Evaluates [snapshot] against a pre-[compile]d rule set. Rules are already enabled-only and in
     * priority order, so this just returns the first whose condition matches (short-circuiting), or
     * [MatchDecision.NoMatch].
     */
    fun evaluate(
        snapshot: NotificationSnapshot,
        compiled: CompiledRuleSet,
    ): MatchDecision = evaluateRules(snapshot, compiled.activeRules)

    /** Evaluates monitor rules independently; this decision must never trigger a platform action. */
    fun evaluateMonitor(
        snapshot: NotificationSnapshot,
        compiled: CompiledRuleSet,
    ): MatchDecision = evaluateRules(snapshot, compiled.monitorRules)

    /**
     * Reports whether trusted semantic inference could let an unresolved higher-priority rule
     * preempt the lane's current first match. Active and monitor lanes are considered independently.
     */
    fun semanticResolutionRequirements(
        snapshot: NotificationSnapshot,
        compiled: CompiledRuleSet,
    ): SemanticResolutionRequirements =
        SemanticResolutionRequirements(
            activeNeedsSemantic = laneNeedsSemantic(snapshot, compiled.activeRules),
            monitorNeedsSemantic = laneNeedsSemantic(snapshot, compiled.monitorRules),
        )

    /**
     * Evaluates both lanes while building the selected rules' traces from the same short-circuiting
     * condition traversal. Use this on the notification hot path when a persisted explanation is
     * required; it avoids evaluating the matched conditions a second time.
     */
    fun evaluateWithTraces(
        activeSnapshot: NotificationSnapshot,
        monitorSnapshot: NotificationSnapshot,
        compiled: CompiledRuleSet,
    ): MatchEvaluation {
        val active = evaluateRulesWithTree(activeSnapshot, compiled.activeRules)
        val monitor = evaluateRulesWithTree(monitorSnapshot, compiled.monitorRules)
        val bothMatched = active.tree != null && monitor.tree != null
        val activeBudget =
            when {
                bothMatched -> ACTIVE_TRACE_BUDGET
                active.tree != null -> MAX_PERSISTED_TRACE_NODES
                else -> 0
            }
        val activeTrace =
            active.tree?.toDecisionTrace(DecisionTraceLane.ACTIVE, activeBudget).orEmpty()
        val monitorTrace =
            monitor.tree
                ?.toDecisionTrace(
                    DecisionTraceLane.MONITOR,
                    MAX_PERSISTED_TRACE_NODES - activeTrace.size,
                ).orEmpty()
        return MatchEvaluation(
            activeDecision = active.decision,
            monitorDecision = monitor.decision,
            decisionTrace = activeTrace + monitorTrace,
        )
    }

    /**
     * Convenience overload that [compile]s [rules] on every call — fine for one-off evaluation and
     * tests, but in a hot loop compile once and use the [CompiledRuleSet] overload instead.
     */
    fun evaluate(
        snapshot: NotificationSnapshot,
        rules: List<Rule>,
    ): MatchDecision = evaluate(snapshot, compile(rules))

    /**
     * Produces a recursive condition trace for editor diagnostics. This intentionally stays off the
     * notification-service hot path, where [evaluate] avoids allocating diagnostic objects.
     */
    fun explain(
        snapshot: NotificationSnapshot,
        rules: List<Rule>,
    ): MatchExplanation {
        val active = compile(rules).rules
        val traces = mutableListOf<RuleTrace>()
        var decision: MatchDecision = MatchDecision.NoMatch
        for (rule in active) {
            val trace = rule.condition.trace(snapshot)
            traces += RuleTrace(rule, trace.result, trace)
            if (trace.result == ConditionResult.MATCH) {
                decision = MatchDecision.Matched(rule, rule.action)
                break
            }
        }
        return MatchExplanation(decision, traces)
    }

    /**
     * Converts the selected rule's recursive condition path into a bounded, content-free trace for
     * local persistence. Predicate values, notification text, and LLM reasoning are never included.
     */
    fun decisionTrace(
        snapshot: NotificationSnapshot,
        decision: MatchDecision,
        lane: DecisionTraceLane,
        maxNodes: Int = MAX_PERSISTED_TRACE_NODES,
    ): List<DecisionTraceNode> {
        val matched = decision as? MatchDecision.Matched ?: return emptyList()
        if (maxNodes <= 0) return emptyList()
        return matched.rule.condition
            .evaluateTree(snapshot)
            .toDecisionTrace(lane, maxNodes)
    }

    /**
     * Builds both persisted lanes under the single global privacy/performance cap. When both lanes
     * match, active decisions receive most of the budget because they caused the platform outcome.
     */
    fun decisionTraces(
        activeSnapshot: NotificationSnapshot,
        activeDecision: MatchDecision,
        monitorSnapshot: NotificationSnapshot,
        monitorDecision: MatchDecision,
    ): List<DecisionTraceNode> {
        val activeMatched = activeDecision is MatchDecision.Matched
        val monitorMatched = monitorDecision is MatchDecision.Matched
        val activeBudget =
            when {
                activeMatched && monitorMatched -> ACTIVE_TRACE_BUDGET
                activeMatched -> MAX_PERSISTED_TRACE_NODES
                else -> 0
            }
        val active = decisionTrace(activeSnapshot, activeDecision, DecisionTraceLane.ACTIVE, activeBudget)
        val monitorBudget = MAX_PERSISTED_TRACE_NODES - active.size
        val monitor = decisionTrace(monitorSnapshot, monitorDecision, DecisionTraceLane.MONITOR, monitorBudget)
        return active + monitor
    }

    private fun evaluateRulesWithTree(
        snapshot: NotificationSnapshot,
        rules: List<Rule>,
    ): EvaluatedRuleDecision {
        for (rule in rules) {
            val tree = rule.condition.evaluateTree(snapshot)
            if (tree.result == ConditionResult.MATCH) {
                return EvaluatedRuleDecision(MatchDecision.Matched(rule, rule.action), tree)
            }
        }
        return EvaluatedRuleDecision(MatchDecision.NoMatch, null)
    }

    private fun evaluateRules(
        snapshot: NotificationSnapshot,
        rules: List<Rule>,
    ): MatchDecision {
        val matched = rules.firstOrNull { it.condition.evaluate(snapshot) == ConditionResult.MATCH }
        return matched?.let { MatchDecision.Matched(it, it.action) } ?: MatchDecision.NoMatch
    }

    private fun laneNeedsSemantic(
        snapshot: NotificationSnapshot,
        rules: List<Rule>,
    ): Boolean {
        for (rule in rules) {
            if (rule.condition.evaluate(snapshot) == ConditionResult.MATCH) return false
            if (rule.condition.canMatchWithTrustedSemantic(snapshot)) return true
        }
        return false
    }
}

private fun Condition.canMatchWithTrustedSemantic(snapshot: NotificationSnapshot): Boolean =
    SemanticIntent.entries
        .asSequence()
        .filterNot { it == SemanticIntent.AMBIGUOUS }
        .any { intent ->
            evaluate(
                snapshot.copy(
                    semanticIntent = intent,
                    isAdvertisement = intent.isAdvertisement,
                ),
            ) == ConditionResult.MATCH
        }

private fun EvaluatedCondition.toDecisionTrace(
    lane: DecisionTraceLane,
    maxNodes: Int,
): List<DecisionTraceNode> {
    if (maxNodes <= 0) return emptyList()
    val nodes = mutableListOf<DecisionTraceNode>()
    var truncated = false

    fun visit(
        condition: EvaluatedCondition,
        depth: Int,
    ) {
        if (nodes.size >= maxNodes - 1) {
            truncated = true
            return
        }
        nodes +=
            DecisionTraceNode(
                lane = lane,
                position = nodes.size,
                depth = depth,
                kind = condition.condition.kind(),
                result = condition.result,
            )
        condition.children.forEach { child ->
            if (!truncated) visit(child, depth + 1)
        }
    }

    visit(this, 0)
    if (truncated) {
        nodes +=
            DecisionTraceNode(
                lane = lane,
                position = nodes.size,
                depth = 0,
                kind = DecisionConditionKind.TRUNCATED,
                result = ConditionResult.UNKNOWN,
            )
    }
    return nodes
}

private fun List<Rule>.requiredSignals(): RuleSignalRequirements =
    fold(RuleSignalRequirements()) { result, rule -> result + rule.condition.requiredSignals() }

private fun Condition.trace(snapshot: NotificationSnapshot): ConditionTrace = evaluateTree(snapshot).toTrace()

private fun Condition.evaluateTree(snapshot: NotificationSnapshot): EvaluatedCondition =
    when (this) {
        is Condition.AllOf -> evaluateAllOf(snapshot)
        is Condition.AnyOf -> evaluateAnyOf(snapshot)
        is Condition.Not -> {
            val child = condition.evaluateTree(snapshot)
            EvaluatedCondition(this, child.result.not(), listOf(child))
        }
        else -> EvaluatedCondition(this, evaluate(snapshot), emptyList())
    }

private fun Condition.AllOf.evaluateAllOf(snapshot: NotificationSnapshot): EvaluatedCondition {
    if (conditions.isEmpty()) return EvaluatedCondition(this, ConditionResult.NO_MATCH, emptyList())
    val children = mutableListOf<EvaluatedCondition>()
    var sawUnknown = false
    for (condition in conditions) {
        val child = condition.evaluateTree(snapshot)
        children += child
        when (child.result) {
            ConditionResult.NO_MATCH -> return EvaluatedCondition(this, ConditionResult.NO_MATCH, children)
            ConditionResult.UNKNOWN -> sawUnknown = true
            ConditionResult.MATCH -> Unit
        }
    }
    val result = if (sawUnknown) ConditionResult.UNKNOWN else ConditionResult.MATCH
    return EvaluatedCondition(this, result, children)
}

private fun Condition.AnyOf.evaluateAnyOf(snapshot: NotificationSnapshot): EvaluatedCondition {
    if (conditions.isEmpty()) return EvaluatedCondition(this, ConditionResult.NO_MATCH, emptyList())
    val children = mutableListOf<EvaluatedCondition>()
    var sawUnknown = false
    for (condition in conditions) {
        val child = condition.evaluateTree(snapshot)
        children += child
        when (child.result) {
            ConditionResult.MATCH -> return EvaluatedCondition(this, ConditionResult.MATCH, children)
            ConditionResult.UNKNOWN -> sawUnknown = true
            ConditionResult.NO_MATCH -> Unit
        }
    }
    val result = if (sawUnknown) ConditionResult.UNKNOWN else ConditionResult.NO_MATCH
    return EvaluatedCondition(this, result, children)
}

private fun EvaluatedCondition.toTrace(): ConditionTrace =
    ConditionTrace(
        condition = condition,
        result = result,
        children = children.map(EvaluatedCondition::toTrace),
    )

private data class EvaluatedCondition(
    val condition: Condition,
    val result: ConditionResult,
    val children: List<EvaluatedCondition>,
)

private data class EvaluatedRuleDecision(
    val decision: MatchDecision,
    val tree: EvaluatedCondition?,
)

private fun Condition.requiredSignals(): RuleSignalRequirements =
    when (this) {
        is Condition.MlCategoryEquals -> RuleSignalRequirements(mlCategory = true)
        is Condition.IsAdvertisement -> RuleSignalRequirements(advertisement = true)
        is Condition.SemanticIntentEquals -> RuleSignalRequirements(semanticIntent = true)
        is Condition.RateAtLeast ->
            RuleSignalRequirements(
                rateSignals =
                    setOf(
                        com.alarmcontrol.core.filtering
                            .RateSignal(scope, windowMillis),
                    ),
            )
        is Condition.AllOf ->
            conditions.fold(RuleSignalRequirements()) { result, child ->
                result +
                    child.requiredSignals()
            }
        is Condition.AnyOf ->
            conditions.fold(RuleSignalRequirements()) { result, child ->
                result +
                    child.requiredSignals()
            }
        is Condition.Not -> condition.requiredSignals()
        else -> RuleSignalRequirements()
    }

private operator fun RuleSignalRequirements.plus(other: RuleSignalRequirements) =
    RuleSignalRequirements(
        mlCategory = mlCategory || other.mlCategory,
        advertisement = advertisement || other.advertisement,
        semanticIntent = semanticIntent || other.semanticIntent,
        rateSignals = rateSignals + other.rateSignals,
    )

private fun Condition.kind(): DecisionConditionKind =
    when (this) {
        is Condition.PackageEquals -> DecisionConditionKind.PACKAGE
        is Condition.TitleContains -> DecisionConditionKind.TITLE
        is Condition.TextContains -> DecisionConditionKind.TEXT
        is Condition.CategoryEquals -> DecisionConditionKind.CATEGORY
        is Condition.ChannelEquals -> DecisionConditionKind.CHANNEL
        is Condition.Ongoing -> DecisionConditionKind.ONGOING
        is Condition.MlCategoryEquals -> DecisionConditionKind.ML_CATEGORY
        is Condition.IsAdvertisement -> DecisionConditionKind.ADVERTISEMENT
        is Condition.SemanticIntentEquals -> DecisionConditionKind.SEMANTIC_INTENT
        is Condition.Conversation -> DecisionConditionKind.CONVERSATION
        is Condition.ForegroundService -> DecisionConditionKind.FOREGROUND_SERVICE
        is Condition.ImportanceAtLeast -> DecisionConditionKind.IMPORTANCE
        is Condition.RateAtLeast -> DecisionConditionKind.RATE
        is Condition.TimeWindow -> DecisionConditionKind.TIME_WINDOW
        is Condition.AllOf -> DecisionConditionKind.ALL_OF
        is Condition.AnyOf -> DecisionConditionKind.ANY_OF
        is Condition.Not -> DecisionConditionKind.NOT
    }

private const val ACTIVE_TRACE_BUDGET = 96
