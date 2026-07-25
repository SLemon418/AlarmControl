package com.alarmcontrol.data.mapper

import com.alarmcontrol.core.filtering.ActionKind
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.NotificationImportance
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.filtering.kind
import com.alarmcontrol.data.db.entity.RuleConditionEntity
import com.alarmcontrol.data.db.entity.RuleEntity
import com.alarmcontrol.data.db.model.StoredConditionType
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.db.model.StoredRuleExecutionMode
import com.alarmcontrol.data.db.relation.RuleWithConditions

/*
 * Pure conversions between Room entities and the `:core` domain model (CLAUDE.md §4). The condition
 * tree is persisted as self-referential [RuleConditionEntity] rows: this file turns a domain
 * `Condition` into a [PendingConditionNode] tree (the repository assigns real ids as it inserts) and
 * rebuilds a `Condition` from fetched rows. No Room/Android type leaks into the domain.
 */

// region rule fields (non-condition)

fun Rule.toRuleEntity(
    id: Long,
    createdAtMillis: Long,
    updatedAtMillis: Long,
): RuleEntity =
    RuleEntity(
        id = id,
        name = name,
        enabled = enabled,
        priority = priority,
        action = action.toStoredType(),
        executionMode = executionMode.toStored(),
        snoozeDurationMillis = (action as? RuleAction.Snooze)?.durationMillis,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

internal fun RuleAction.toStoredType(): StoredRuleAction = kind.toStored()

internal fun ActionKind.toStored(): StoredRuleAction =
    when (this) {
        ActionKind.CANCEL -> StoredRuleAction.CANCEL
        ActionKind.SNOOZE -> StoredRuleAction.SNOOZE
        ActionKind.MARK_READ -> StoredRuleAction.MARK_READ
        ActionKind.KEEP -> StoredRuleAction.KEEP
    }

private fun RuleExecutionMode.toStored(): StoredRuleExecutionMode = StoredRuleExecutionMode.valueOf(name)

private fun RuleEntity.toAction(): RuleAction =
    when (action) {
        StoredRuleAction.CANCEL -> RuleAction.Cancel
        StoredRuleAction.MARK_READ -> RuleAction.MarkRead
        StoredRuleAction.KEEP -> RuleAction.Keep
        StoredRuleAction.SNOOZE ->
            RuleAction.Snooze(
                requireNotNull(snoozeDurationMillis) { "SNOOZE rule $id is missing snoozeDurationMillis" },
            )
    }

// endregion

// region condition tree: domain -> pending nodes (real ids assigned by the repository at insert time)

/** A condition row to insert, with its children. Ids/parent ids are assigned when inserted. */
data class PendingConditionNode(
    val type: StoredConditionType,
    val value: String,
    val ignoreCase: Boolean,
    val children: List<PendingConditionNode>,
)

internal fun Condition.toPendingTree(): PendingConditionNode =
    when (this) {
        is Condition.AllOf -> composite(StoredConditionType.ALL_OF, conditions)
        is Condition.AnyOf -> composite(StoredConditionType.ANY_OF, conditions)
        is Condition.Not ->
            PendingConditionNode(
                StoredConditionType.NOT,
                value = "",
                ignoreCase = true,
                children = listOf(condition.toPendingTree()),
            )
        is Condition.PackageEquals -> leaf(StoredConditionType.PACKAGE, packageName)
        is Condition.TitleContains -> leaf(StoredConditionType.TITLE_CONTAINS, text, ignoreCase)
        is Condition.TextContains -> leaf(StoredConditionType.TEXT_CONTAINS, text, ignoreCase)
        is Condition.CategoryEquals -> leaf(StoredConditionType.CATEGORY, category)
        is Condition.ChannelEquals -> leaf(StoredConditionType.CHANNEL, channelId)
        is Condition.Ongoing -> leaf(StoredConditionType.ONGOING, value.toString())
        is Condition.MlCategoryEquals -> leaf(StoredConditionType.ML_CATEGORY, category)
        is Condition.IsAdvertisement -> leaf(StoredConditionType.IS_ADVERTISEMENT, value.toString())
        is Condition.SemanticIntentEquals -> leaf(StoredConditionType.SEMANTIC_INTENT, intent.name)
        is Condition.RateAtLeast ->
            leaf(StoredConditionType.RATE_AT_LEAST, "${scope.name},$windowMillis,$threshold")
        is Condition.Conversation -> leaf(StoredConditionType.CONVERSATION, value.toString())
        is Condition.ForegroundService -> leaf(StoredConditionType.FOREGROUND_SERVICE, value.toString())
        is Condition.ImportanceAtLeast -> leaf(StoredConditionType.IMPORTANCE_AT_LEAST, minimum.name)
        is Condition.TimeWindow -> leaf(StoredConditionType.TIME_WINDOW, "$startMinuteOfDay,$endMinuteOfDay")
    }

private fun composite(
    type: StoredConditionType,
    children: List<Condition>,
) = PendingConditionNode(type, value = "", ignoreCase = true, children = children.map { it.toPendingTree() })

private fun leaf(
    type: StoredConditionType,
    value: String,
    ignoreCase: Boolean = true,
) = PendingConditionNode(type, value, ignoreCase, children = emptyList())

// endregion

// region condition tree: rows -> domain

fun RuleWithConditions.toDomain(): Rule =
    Rule(
        id = rule.id.toString(),
        name = rule.name,
        enabled = rule.enabled,
        priority = rule.priority,
        condition = conditions.rebuildCondition(),
        action = rule.toAction(),
        executionMode = RuleExecutionMode.valueOf(rule.executionMode.name),
    )

/** Rebuilds the domain condition by walking [RuleConditionEntity.parentId]; tolerates legacy flat rows. */
internal fun List<RuleConditionEntity>.rebuildCondition(): Condition {
    val childrenByParent = groupBy { it.parentId }
    val roots = childrenByParent[null].orEmpty().sortedBy { it.position }
    return when (roots.size) {
        0 -> Condition.AllOf(emptyList())
        1 -> roots.single().toCondition(childrenByParent)
        // Legacy flat schema: multiple parent-less leaves were AND-ed.
        else -> Condition.AllOf(roots.map { it.toCondition(childrenByParent) })
    }
}

private fun RuleConditionEntity.toCondition(childrenByParent: Map<Long?, List<RuleConditionEntity>>): Condition {
    val children = childrenByParent[id].orEmpty().sortedBy { it.position }
    val base =
        when (type) {
            StoredConditionType.ALL_OF -> Condition.AllOf(children.map { it.toCondition(childrenByParent) })
            StoredConditionType.ANY_OF -> Condition.AnyOf(children.map { it.toCondition(childrenByParent) })
            StoredConditionType.NOT -> Condition.Not(children.single().toCondition(childrenByParent))
            StoredConditionType.PACKAGE -> Condition.PackageEquals(value)
            StoredConditionType.TITLE_CONTAINS -> Condition.TitleContains(value, ignoreCase)
            StoredConditionType.TEXT_CONTAINS -> Condition.TextContains(value, ignoreCase)
            StoredConditionType.CATEGORY -> Condition.CategoryEquals(value)
            StoredConditionType.CHANNEL -> Condition.ChannelEquals(value)
            StoredConditionType.ONGOING -> Condition.Ongoing(value.toBooleanStrict())
            StoredConditionType.ML_CATEGORY -> Condition.MlCategoryEquals(value)
            StoredConditionType.IS_ADVERTISEMENT -> Condition.IsAdvertisement(value.toBooleanStrict())
            StoredConditionType.SEMANTIC_INTENT -> Condition.SemanticIntentEquals(SemanticIntent.valueOf(value))
            StoredConditionType.RATE_AT_LEAST -> {
                val (scope, window, threshold) = value.split(",")
                Condition.RateAtLeast(RateScope.valueOf(scope), window.toLong(), threshold.toInt())
            }
            StoredConditionType.CONVERSATION -> Condition.Conversation(value.toBooleanStrict())
            StoredConditionType.FOREGROUND_SERVICE -> Condition.ForegroundService(value.toBooleanStrict())
            StoredConditionType.IMPORTANCE_AT_LEAST ->
                Condition.ImportanceAtLeast(NotificationImportance.valueOf(value))
            StoredConditionType.TIME_WINDOW -> {
                val (start, end) = value.split(",")
                Condition.TimeWindow(start.toInt(), end.toInt())
            }
        }
    return if (negate) Condition.Not(base) else base
}

// endregion
