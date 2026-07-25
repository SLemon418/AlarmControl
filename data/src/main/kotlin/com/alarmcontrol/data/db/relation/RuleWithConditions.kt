package com.alarmcontrol.data.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.alarmcontrol.data.db.entity.RuleConditionEntity
import com.alarmcontrol.data.db.entity.RuleEntity

/**
 * A [RuleEntity] together with every row in its nested condition tree, fetched in one transaction.
 * The mapper rebuilds parent/child relationships and preserves sibling order.
 */
data class RuleWithConditions(
    @Embedded val rule: RuleEntity,
    @Relation(parentColumn = "id", entityColumn = "rule_id")
    val conditions: List<RuleConditionEntity>,
)
