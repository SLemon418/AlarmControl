package com.alarmcontrol.ui.rules

import com.alarmcontrol.core.filtering.MAX_RULE_CONDITION_DEPTH
import com.alarmcontrol.core.filtering.MAX_RULE_CONDITION_NODES

private const val LEAF_NODE_COST = 1
private const val CONTAINER_NODE_COST = 2
private const val LEAF_DEPTH_INCREMENT = 2
private const val CONTAINER_DEPTH_INCREMENT = 3

/** Counts nodes iteratively and stops once the persisted definition limit has been exceeded. */
internal fun ConditionNode.nodeCount(): Int {
    var count = 0
    val pending = ArrayDeque<ConditionNode>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        count++
        if (count > MAX_RULE_CONDITION_NODES) return count
        when (node) {
            is GroupNode -> pending.addAll(node.children)
            is NotNode -> pending.add(node.child)
            is LeafNode,
            is TimeWindowNode,
            is RateNode,
            -> Unit
        }
    }
    return count
}

internal fun canAddLeafCondition(
    groupDepth: Int,
    remainingNodes: Int,
): Boolean =
    remainingNodes >= LEAF_NODE_COST &&
        groupDepth + LEAF_DEPTH_INCREMENT <= MAX_RULE_CONDITION_DEPTH

internal fun canAddContainerCondition(
    groupDepth: Int,
    remainingNodes: Int,
): Boolean =
    remainingNodes >= CONTAINER_NODE_COST &&
        groupDepth + CONTAINER_DEPTH_INCREMENT <= MAX_RULE_CONDITION_DEPTH
