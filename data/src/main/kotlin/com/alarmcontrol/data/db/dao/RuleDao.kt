package com.alarmcontrol.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.alarmcontrol.core.filtering.MAX_SAVED_RULES
import com.alarmcontrol.data.db.entity.RuleConditionEntity
import com.alarmcontrol.data.db.entity.RuleEntity
import com.alarmcontrol.data.db.relation.RuleWithConditions
import com.alarmcontrol.data.mapper.PendingConditionNode
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Query("SELECT COUNT(*) FROM rules")
    suspend fun countAll(): Int

    /** Observes all rules with their conditions, highest priority first. */
    @Transaction
    @Query("SELECT * FROM rules ORDER BY priority DESC, id ASC")
    fun observeRulesWithConditions(): Flow<List<RuleWithConditions>>

    /** One coherent snapshot for local backup export. */
    @Transaction
    @Query("SELECT * FROM rules ORDER BY priority DESC, id ASC")
    suspend fun getRulesWithConditions(): List<RuleWithConditions>

    @Insert
    suspend fun insertRule(rule: RuleEntity): Long

    /** Inserts one condition-tree node and returns its generated id (used to parent its children). */
    @Insert
    suspend fun insertCondition(condition: RuleConditionEntity): Long

    @Update
    suspend fun updateRule(rule: RuleEntity)

    @Query("SELECT * FROM rules WHERE id = :id LIMIT 1")
    suspend fun findRuleById(id: Long): RuleEntity?

    @Delete
    suspend fun deleteRule(rule: RuleEntity)

    /** Deletes a rule by id; its conditions are removed by the `ON DELETE CASCADE` foreign key. */
    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteRuleById(id: Long)

    /** Deletes every rule; condition rows cascade. Used only by atomic backup restore. */
    @Query("DELETE FROM rules")
    suspend fun deleteAllRules(): Int

    /** Removes a rule's existing conditions before re-inserting an edited set. */
    @Query("DELETE FROM rule_conditions WHERE rule_id = :ruleId")
    suspend fun deleteConditionsForRule(ruleId: Long)

    @Query(
        "UPDATE rules SET enabled = :enabled, updated_at_millis = :updatedAtMillis " +
            "WHERE id IN (:ids) AND enabled != :enabled",
    )
    suspend fun setRulesEnabled(
        ids: List<Long>,
        enabled: Boolean,
        updatedAtMillis: Long,
    ): Int

    /**
     * Atomically inserts or updates a rule together with its complete condition tree. An interrupted
     * tree rewrite therefore cannot leave a destructive rule with no conditions.
     */
    @Transaction
    suspend fun storeRuleWithConditions(
        rule: RuleEntity,
        root: PendingConditionNode,
    ): Long {
        val ruleId =
            if (rule.id == 0L) {
                require(countAll() < MAX_SAVED_RULES) { "Rule limit reached" }
                insertRule(rule)
            } else {
                val existing = requireNotNull(findRuleById(rule.id)) { "Rule ${rule.id} does not exist" }
                updateRule(
                    rule.copy(
                        enabled = existing.enabled,
                        createdAtMillis = existing.createdAtMillis,
                    ),
                )
                deleteConditionsForRule(rule.id)
                rule.id
            }

        suspend fun insertNode(
            node: PendingConditionNode,
            parentId: Long?,
            position: Int,
        ) {
            val id =
                insertCondition(
                    RuleConditionEntity(
                        ruleId = ruleId,
                        parentId = parentId,
                        position = position,
                        type = node.type,
                        value = node.value,
                        ignoreCase = node.ignoreCase,
                    ),
                )
            node.children.forEachIndexed { index, child -> insertNode(child, id, index) }
        }

        insertNode(root, parentId = null, position = 0)
        return ruleId
    }
}
