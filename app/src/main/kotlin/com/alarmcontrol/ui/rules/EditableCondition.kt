package com.alarmcontrol.ui.rules

import androidx.annotation.StringRes
import com.alarmcontrol.R
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.MAX_CONDITION_VALUE_CHARS
import com.alarmcontrol.core.filtering.MAX_RATE_THRESHOLD
import com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.MAX_RULE_CONDITION_DEPTH
import com.alarmcontrol.core.filtering.MAX_RULE_CONDITION_NODES
import com.alarmcontrol.core.filtering.MIN_RATE_THRESHOLD
import com.alarmcontrol.core.filtering.MIN_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.NotificationImportance
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.SemanticIntent
import java.util.concurrent.atomic.AtomicLong

/**
 * Editable, UI-side representation of a rule's condition tree (CLAUDE.md §8). It mirrors the domain
 * [Condition] but adds a stable [key] (for Compose identity) and free-text in-progress values, so the
 * recursive builder can construct deeply nested logic without losing structure. Converted to/from the
 * domain by [toEditableRoot] and [toConditionOrNull]. No Compose types live here, so it stays unit-
 * testable.
 */
sealed interface ConditionNode {
    val key: Long
}

/** AND ([anyOf] = false) / OR ([anyOf] = true) group of child nodes. */
data class GroupNode(
    override val key: Long,
    val anyOf: Boolean,
    val children: List<ConditionNode>,
) : ConditionNode

/** Inverts its [child]. */
data class NotNode(
    override val key: Long,
    val child: ConditionNode,
) : ConditionNode

/** A single predicate of [kind] over [value]; [ignoreCase] is preserved (not exposed in the UI). */
data class LeafNode(
    override val key: Long,
    val kind: LeafKind,
    val value: String,
    val ignoreCase: Boolean = true,
) : ConditionNode

/** A daily time window as editable "HH:mm" strings. */
data class TimeWindowNode(
    override val key: Long,
    val start: String,
    val end: String,
) : ConditionNode

/** Frequency predicate with editable minute/threshold values and package/channel scope. */
data class RateNode(
    override val key: Long,
    val scope: RateScope,
    val windowMinutes: String,
    val threshold: String,
) : ConditionNode

/** The leaf predicate kinds the builder offers; [labelRes] resolves the user-facing name. */
enum class LeafKind(
    @StringRes val labelRes: Int,
) {
    PACKAGE(R.string.condition_package),
    TITLE(R.string.condition_title),
    TEXT(R.string.condition_text),
    CATEGORY(R.string.condition_category),
    CHANNEL(R.string.condition_channel),
    ML_CATEGORY(R.string.condition_ml_category),
    ONGOING(R.string.condition_ongoing),
    IS_ADVERTISEMENT(R.string.condition_advertisement),
    SEMANTIC_INTENT(R.string.condition_semantic_intent),
    CONVERSATION(R.string.condition_conversation),
    FOREGROUND_SERVICE(R.string.condition_foreground_service),
    IMPORTANCE_AT_LEAST(R.string.condition_importance),
}

enum class ConditionValidation(
    @StringRes val messageRes: Int,
) {
    ADD_CONDITION(R.string.validation_add_condition),
    COMPLETE_NOT(R.string.validation_complete_not),
    ENTER_VALUE(R.string.validation_enter_value),
    VALUE_TOO_LONG(R.string.validation_condition_too_long),
    BOOLEAN(R.string.validation_boolean),
    TIME(R.string.validation_time),
    RATE_WINDOW(R.string.validation_rate_window),
    RATE_THRESHOLD(R.string.validation_rate_threshold),
    ENUM_VALUE(R.string.validation_select_value),
}

private val keySource = AtomicLong(0)

/** A unique node key for Compose identity; uniqueness matters, the value does not. */
internal fun nextNodeKey(): Long = keySource.getAndIncrement()

internal fun newLeafNode(): LeafNode = LeafNode(nextNodeKey(), LeafKind.PACKAGE, value = "")

internal fun newGroupNode(): GroupNode = GroupNode(nextNodeKey(), anyOf = false, children = emptyList())

internal fun newNotNode(): NotNode = NotNode(nextNodeKey(), newLeafNode())

internal fun newTimeWindowNode(): TimeWindowNode = TimeWindowNode(nextNodeKey(), start = "22:00", end = "07:00")

internal fun newRateNode(): RateNode =
    RateNode(
        key = nextNodeKey(),
        scope = RateScope.PACKAGE,
        windowMinutes = "5",
        threshold = "5",
    )

internal fun emptyRootGroup(): GroupNode = newGroupNode()

// region sibling reordering (adjusts evaluation order within a group)

/** Returns the list with the item at [index] moved one slot earlier; a no-op at the top or OOB. */
internal fun <T> List<T>.movedUp(index: Int): List<T> =
    if (index <= 0 || index >= size) this else toMutableList().apply { add(index - 1, removeAt(index)) }

/** Returns the list with the item at [index] moved one slot later; a no-op at the bottom or OOB. */
internal fun <T> List<T>.movedDown(index: Int): List<T> =
    if (index < 0 || index >= size - 1) this else toMutableList().apply { add(index + 1, removeAt(index)) }

// endregion

// region domain -> editable

/** Loads a domain condition as an editable tree; a non-group root is wrapped in an AND group. */
internal fun Condition.toEditableRoot(): GroupNode {
    val node = toNode()
    return node as? GroupNode ?: GroupNode(nextNodeKey(), anyOf = false, children = listOf(node))
}

private fun Condition.toNode(): ConditionNode =
    when (this) {
        is Condition.AllOf -> GroupNode(nextNodeKey(), anyOf = false, children = conditions.map { it.toNode() })
        is Condition.AnyOf -> GroupNode(nextNodeKey(), anyOf = true, children = conditions.map { it.toNode() })
        is Condition.Not -> NotNode(nextNodeKey(), condition.toNode())
        is Condition.PackageEquals -> LeafNode(nextNodeKey(), LeafKind.PACKAGE, packageName)
        is Condition.TitleContains -> LeafNode(nextNodeKey(), LeafKind.TITLE, text, ignoreCase)
        is Condition.TextContains -> LeafNode(nextNodeKey(), LeafKind.TEXT, text, ignoreCase)
        is Condition.CategoryEquals -> LeafNode(nextNodeKey(), LeafKind.CATEGORY, category)
        is Condition.ChannelEquals -> LeafNode(nextNodeKey(), LeafKind.CHANNEL, channelId)
        is Condition.MlCategoryEquals -> LeafNode(nextNodeKey(), LeafKind.ML_CATEGORY, category)
        is Condition.Ongoing -> LeafNode(nextNodeKey(), LeafKind.ONGOING, value.toString())
        is Condition.IsAdvertisement -> LeafNode(nextNodeKey(), LeafKind.IS_ADVERTISEMENT, value.toString())
        is Condition.SemanticIntentEquals -> LeafNode(nextNodeKey(), LeafKind.SEMANTIC_INTENT, intent.name)
        is Condition.Conversation -> LeafNode(nextNodeKey(), LeafKind.CONVERSATION, value.toString())
        is Condition.ForegroundService -> LeafNode(nextNodeKey(), LeafKind.FOREGROUND_SERVICE, value.toString())
        is Condition.ImportanceAtLeast -> LeafNode(nextNodeKey(), LeafKind.IMPORTANCE_AT_LEAST, minimum.name)
        is Condition.RateAtLeast ->
            RateNode(
                key = nextNodeKey(),
                scope = scope,
                windowMinutes = (windowMillis / MILLIS_PER_MINUTE).toString(),
                threshold = threshold.toString(),
            )
        is Condition.TimeWindow ->
            TimeWindowNode(nextNodeKey(), formatMinuteOfDay(startMinuteOfDay), formatMinuteOfDay(endMinuteOfDay))
    }

// endregion

// region editable -> domain

/** Converts a completely valid tree to a domain condition; any invalid descendant makes it `null`. */
internal fun ConditionNode.toConditionOrNull(): Condition? =
    toConditionOrNull(depth = 1, budget = ConditionTreeBudget())

private fun ConditionNode.toConditionOrNull(
    depth: Int,
    budget: ConditionTreeBudget,
): Condition? {
    if (!budget.consume(depth)) return null
    return when (this) {
        is GroupNode -> toGroupConditionOrNull(depth, budget)
        is NotNode -> child.toConditionOrNull(depth + 1, budget)?.let { Condition.Not(it) }
        is LeafNode ->
            if (value.isBlank() || value.length > MAX_CONDITION_VALUE_CHARS) {
                null
            } else {
                toLeafConditionOrNull()
            }
        is TimeWindowNode -> toTimeConditionOrNull()
        is RateNode -> toRateConditionOrNull()
    }
}

private fun GroupNode.toGroupConditionOrNull(
    depth: Int,
    budget: ConditionTreeBudget,
): Condition? {
    if (children.isEmpty()) return null
    val mapped = children.map { child -> child.toConditionOrNull(depth + 1, budget) ?: return null }
    return if (anyOf) Condition.AnyOf(mapped) else Condition.AllOf(mapped)
}

/** Rejects malformed or restored trees before recursion exceeds the domain persistence limits. */
private class ConditionTreeBudget {
    private var remaining = MAX_RULE_CONDITION_NODES

    fun consume(depth: Int): Boolean {
        if (depth > MAX_RULE_CONDITION_DEPTH || remaining <= 0) return false
        remaining--
        return true
    }
}

private fun TimeWindowNode.toTimeConditionOrNull(): Condition? {
    val startMinute = parseMinuteOfDay(start) ?: return null
    val endMinute = parseMinuteOfDay(end) ?: return null
    return Condition.TimeWindow(startMinute, endMinute)
}

private fun RateNode.toRateConditionOrNull(): Condition? {
    val minutes = windowMinutes.toLongOrNull() ?: return null
    val windowMillis = minutes * MILLIS_PER_MINUTE
    val parsedThreshold = threshold.toIntOrNull() ?: return null
    if (windowMillis !in MIN_RATE_WINDOW_MILLIS..MAX_RATE_WINDOW_MILLIS) return null
    if (parsedThreshold !in MIN_RATE_THRESHOLD..MAX_RATE_THRESHOLD) return null
    return Condition.RateAtLeast(scope, windowMillis, parsedThreshold)
}

private fun LeafNode.toLeafConditionOrNull(): Condition? =
    when (kind) {
        LeafKind.PACKAGE -> Condition.PackageEquals(value.trim())
        LeafKind.TITLE -> Condition.TitleContains(value.trim(), ignoreCase)
        LeafKind.TEXT -> Condition.TextContains(value.trim(), ignoreCase)
        LeafKind.CATEGORY -> Condition.CategoryEquals(value.trim())
        LeafKind.CHANNEL -> Condition.ChannelEquals(value.trim())
        LeafKind.ML_CATEGORY -> Condition.MlCategoryEquals(value.trim())
        LeafKind.ONGOING -> value.trim().toBooleanStrictOrNull()?.let(Condition::Ongoing)
        LeafKind.IS_ADVERTISEMENT -> value.trim().toBooleanStrictOrNull()?.let(Condition::IsAdvertisement)
        LeafKind.SEMANTIC_INTENT ->
            enumValueOrNull<SemanticIntent>(value)?.let(Condition::SemanticIntentEquals)
        LeafKind.CONVERSATION -> value.trim().toBooleanStrictOrNull()?.let(Condition::Conversation)
        LeafKind.FOREGROUND_SERVICE -> value.trim().toBooleanStrictOrNull()?.let(Condition::ForegroundService)
        LeafKind.IMPORTANCE_AT_LEAST ->
            enumValueOrNull<NotificationImportance>(value)?.let(Condition::ImportanceAtLeast)
    }

/** A concise local validation message for the node's own editor. */
internal fun ConditionNode.validationError(): ConditionValidation? =
    when (this) {
        is GroupNode -> if (children.isEmpty()) ConditionValidation.ADD_CONDITION else null
        is NotNode -> if (child.toConditionOrNull() == null) ConditionValidation.COMPLETE_NOT else null
        is LeafNode -> validationError()
        is TimeWindowNode ->
            if (parseMinuteOfDay(start) == null || parseMinuteOfDay(end) == null) {
                ConditionValidation.TIME
            } else {
                null
            }
        is RateNode -> validationError()
    }

private fun LeafNode.validationError(): ConditionValidation? =
    when {
        value.isBlank() -> ConditionValidation.ENTER_VALUE
        value.length > MAX_CONDITION_VALUE_CHARS -> ConditionValidation.VALUE_TOO_LONG
        kind in BOOLEAN_LEAF_KINDS && value.trim().toBooleanStrictOrNull() == null -> ConditionValidation.BOOLEAN
        kind == LeafKind.SEMANTIC_INTENT && enumValueOrNull<SemanticIntent>(value) == null ->
            ConditionValidation.ENUM_VALUE
        kind == LeafKind.IMPORTANCE_AT_LEAST && enumValueOrNull<NotificationImportance>(value) == null ->
            ConditionValidation.ENUM_VALUE
        else -> null
    }

private fun RateNode.validationError(): ConditionValidation? =
    when {
        windowMinutes.toLongOrNull()?.times(MILLIS_PER_MINUTE) !in
            MIN_RATE_WINDOW_MILLIS..MAX_RATE_WINDOW_MILLIS -> ConditionValidation.RATE_WINDOW
        threshold.toIntOrNull() !in MIN_RATE_THRESHOLD..MAX_RATE_THRESHOLD -> ConditionValidation.RATE_THRESHOLD
        else -> null
    }

private val BOOLEAN_LEAF_KINDS =
    setOf(
        LeafKind.ONGOING,
        LeafKind.IS_ADVERTISEMENT,
        LeafKind.CONVERSATION,
        LeafKind.FOREGROUND_SERVICE,
    )

internal fun LeafKind.defaultValue(): String =
    when (this) {
        LeafKind.ONGOING,
        LeafKind.IS_ADVERTISEMENT,
        LeafKind.CONVERSATION,
        LeafKind.FOREGROUND_SERVICE,
        -> "true"
        LeafKind.SEMANTIC_INTENT -> SemanticIntent.MARKETING.name
        LeafKind.IMPORTANCE_AT_LEAST -> NotificationImportance.HIGH.name
        else -> ""
    }

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().singleOrNull { it.name == value.trim() }

private const val MILLIS_PER_MINUTE = 60_000L

// endregion
