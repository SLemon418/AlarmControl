package com.alarmcontrol.ui.rules

import com.alarmcontrol.R
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.ConditionResult
import com.alarmcontrol.core.filtering.MAX_RULE_NAME_CHARS
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleDefinitionValidator
import com.alarmcontrol.core.filtering.RuleAnalysisIssueKind
import com.alarmcontrol.notifications.ConditionTrace
import com.alarmcontrol.ui.UiText
import com.alarmcontrol.ui.uiText

/**
 * Pure presentation mapping between the domain [Rule] and the screen's UI models. Kept out of the
 * Composables so they stay dumb (§8) and this stays unit-testable.
 *
 * The summary and editor both preserve the complete condition tree (AND/OR/NOT/time windows).
 */

internal fun Rule.toListItem(warnings: List<UiText> = emptyList()): RuleListItem =
    RuleListItem(
        id = id,
        name = name,
        summary = condition.summary(),
        actionLabel = action.label(),
        enabled = enabled,
        executionMode = executionMode,
        warnings = warnings,
    )

internal fun RuleAction.label(): UiText =
    when (this) {
        RuleAction.Cancel -> uiText(R.string.action_cancel)
        is RuleAction.Snooze -> uiText(R.string.rule_action_snooze, durationMillis / MILLIS_PER_MINUTE)
        RuleAction.MarkRead -> uiText(R.string.action_log_only)
        RuleAction.Keep -> uiText(R.string.action_keep)
    }

/** Renders the condition tree readably, preserving AND/OR/NOT structure. */
internal fun Condition.summary(): UiText =
    when (this) {
        is Condition.AllOf ->
            conditions
                .map(Condition::summarizeChild)
                .joinedOr(R.string.rule_summary_all_empty, R.string.rule_summary_and)
        is Condition.AnyOf ->
            conditions
                .map(Condition::summarizeChild)
                .joinedOr(R.string.rule_summary_any_empty, R.string.rule_summary_or)
        is Condition.Not -> uiText(R.string.rule_summary_not, condition.summarizeChild())
        else -> leafSummary()
    }

private fun Condition.leafSummary(): UiText =
    when (this) {
        is Condition.PackageEquals -> uiText(R.string.rule_summary_package, packageName)
        is Condition.TitleContains -> uiText(R.string.rule_summary_title, text)
        is Condition.TextContains -> uiText(R.string.rule_summary_text, text)
        is Condition.CategoryEquals -> uiText(R.string.rule_summary_category, category)
        is Condition.ChannelEquals -> uiText(R.string.rule_summary_channel, channelId)
        is Condition.Ongoing -> booleanSummary(value, R.string.rule_summary_ongoing, R.string.rule_summary_dismissible)
        is Condition.MlCategoryEquals -> uiText(R.string.rule_summary_ml, category)
        is Condition.IsAdvertisement -> booleanSummary(value, R.string.rule_summary_is_ad, R.string.rule_summary_not_ad)
        is Condition.SemanticIntentEquals -> uiText(R.string.rule_summary_semantic_intent, intent.name)
        is Condition.Conversation ->
            booleanSummary(value, R.string.rule_summary_conversation, R.string.rule_summary_not_conversation)
        is Condition.ForegroundService ->
            booleanSummary(value, R.string.rule_summary_foreground, R.string.rule_summary_not_foreground)
        is Condition.ImportanceAtLeast -> uiText(R.string.rule_summary_importance, minimum.name)
        is Condition.RateAtLeast -> rateSummary()
        is Condition.TimeWindow ->
            uiText(
                R.string.rule_summary_time,
                formatMinuteOfDay(startMinuteOfDay),
                formatMinuteOfDay(endMinuteOfDay),
            )
        is Condition.AllOf, is Condition.AnyOf, is Condition.Not -> error("Composite condition delegated incorrectly")
    }

private fun booleanSummary(
    value: Boolean,
    trueText: Int,
    falseText: Int,
): UiText = uiText(if (value) trueText else falseText)

private fun Condition.RateAtLeast.rateSummary(): UiText =
    uiText(
        if (scope == com.alarmcontrol.core.filtering.RateScope.PACKAGE) {
            R.string.rule_summary_rate_package
        } else {
            R.string.rule_summary_rate_channel
        },
        threshold,
        windowMillis / MILLIS_PER_MINUTE,
    )

/** Parenthesizes nested composites so the structure reads clearly. */
private fun Condition.summarizeChild(): UiText =
    when (this) {
        is Condition.AllOf, is Condition.AnyOf -> uiText(R.string.rule_summary_parenthesized, summary())
        else -> summary()
    }

internal fun ConditionTrace.toSimulationTrace(depth: Int = 0): List<SimulationTraceItem> =
    buildList {
        add(
            SimulationTraceItem(
                condition = condition.summary(),
                status =
                    when (result) {
                        ConditionResult.MATCH -> SimulationTraceStatus.MATCH
                        ConditionResult.NO_MATCH -> SimulationTraceStatus.NO_MATCH
                        ConditionResult.UNKNOWN -> SimulationTraceStatus.UNKNOWN
                    },
                depth = depth,
            ),
        )
        children.forEach { addAll(it.toSimulationTrace(depth + 1)) }
    }

private fun List<UiText>.joinedOr(
    emptyText: Int,
    joinText: Int,
): UiText = if (isEmpty()) uiText(emptyText) else reduce { left, right -> uiText(joinText, left, right) }

internal fun Rule.toEditorState(): RuleEditorState {
    val (editorAction, minutes) =
        when (val a = action) {
            is RuleAction.Snooze -> EditorAction.SNOOZE to (a.durationMillis / MILLIS_PER_MINUTE).toString()
            RuleAction.MarkRead -> EditorAction.MARK_READ to "30"
            RuleAction.Keep -> EditorAction.KEEP to "30"
            RuleAction.Cancel -> EditorAction.CANCEL to "30"
        }
    return RuleEditorState(
        id = id,
        name = name,
        enabled = enabled,
        priority = priority.toString(),
        action = editorAction,
        executionMode = executionMode,
        snoozeMinutes = minutes,
        // Load the full tree as-is so complex rules are not flattened (Task A / M3).
        root = condition.toEditableRoot(),
        editorMode = RuleEditorMode.ADVANCED,
    )
}

/** Builds a domain [Rule] from the editor, or `null` when the condition tree has nothing valid. */
internal fun RuleEditorState.toRuleOrNull(): Rule? {
    if (name.isBlank() || name.length > MAX_RULE_NAME_CHARS) return null
    if (editorMode == RuleEditorMode.GUIDED && !isLikelyAndroidPackage(guidedPackageName)) return null
    val condition = root.toConditionOrNull() ?: return null
    val parsedPriority = priority.toIntOrNull() ?: return null
    val ruleAction =
        when (action) {
            EditorAction.CANCEL -> RuleAction.Cancel
            EditorAction.MARK_READ -> RuleAction.MarkRead
            EditorAction.KEEP -> RuleAction.Keep
            EditorAction.SNOOZE ->
                RuleAction.Snooze(
                    (snoozeMinutes.toLongOrNull()?.takeIf { it in VALID_SNOOZE_MINUTES } ?: return null) *
                        MILLIS_PER_MINUTE,
                )
        }
    val rule =
        Rule(
        id = id,
        name = name.trim(),
        enabled = enabled,
        priority = parsedPriority,
        condition = condition,
        action = ruleAction,
        executionMode = executionMode,
    )
    return rule.takeIf { RuleDefinitionValidator.validate(it).isEmpty() }
}

internal fun RuleAnalysisIssueKind.warningText(): UiText =
    uiText(
        when (this) {
            RuleAnalysisIssueKind.DUPLICATE -> R.string.rule_warning_duplicate
            RuleAnalysisIssueKind.SHADOWED -> R.string.rule_warning_shadowed
            RuleAnalysisIssueKind.IMPOSSIBLE_CONJUNCTION -> R.string.rule_warning_impossible
            RuleAnalysisIssueKind.BOOLEAN_CONTRADICTION -> R.string.rule_warning_boolean
            RuleAnalysisIssueKind.CONDITION_AND_NEGATION -> R.string.rule_warning_negation
            RuleAnalysisIssueKind.DOUBLE_NEGATION -> R.string.rule_warning_double_not
            RuleAnalysisIssueKind.REDUNDANT_GROUP -> R.string.rule_warning_redundant_group
        },
    )

/** Parses "HH:mm" (24h) to a minute of day, or `null` if malformed/out of range. */
internal fun parseMinuteOfDay(text: String): Int? {
    val parts = text.split(":")
    if (parts.size != 2) return null
    val hour = parts[0].trim().toIntOrNull() ?: return null
    val minute = parts[1].trim().toIntOrNull() ?: return null
    if (hour !in 0..MAX_HOUR || minute !in 0..MAX_MINUTE) return null
    return hour * MINUTES_PER_HOUR + minute
}

internal fun formatMinuteOfDay(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60
private const val MAX_HOUR = 23
private const val MAX_MINUTE = 59
