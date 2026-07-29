package com.alarmcontrol.data.repository

import com.alarmcontrol.data.db.dao.RuleDao
import com.alarmcontrol.data.db.entity.RuleConditionEntity
import com.alarmcontrol.data.db.entity.RuleEntity
import com.alarmcontrol.data.db.relation.RuleWithConditions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [RuleDao] for JVM unit tests — lets `:data:test` exercise the repository without Room,
 * Robolectric, or an emulator. Mirrors the real DAO's ordering (priority desc, id asc) and the
 * `ON DELETE CASCADE` behaviour of [deleteRuleById].
 */
class FakeRuleDao : RuleDao {
    private val rules = mutableListOf<RuleEntity>()
    private val conditions = mutableListOf<RuleConditionEntity>()
    private var nextRuleId = 1L
    private var nextConditionId = 1L

    private val state = MutableStateFlow<List<RuleWithConditions>>(emptyList())
    var countOverride: Int? = null
    var deleteFailureAfterMutation: Throwable? = null

    override suspend fun countAll(): Int = countOverride ?: rules.size

    private fun refresh() {
        state.value =
            rules
                .sortedWith(compareByDescending<RuleEntity> { it.priority }.thenBy { it.id })
                .map { rule -> RuleWithConditions(rule, conditions.filter { it.ruleId == rule.id }) }
    }

    override fun observeRulesWithConditions(): Flow<List<RuleWithConditions>> = state

    override suspend fun getRulesWithConditions(): List<RuleWithConditions> = state.value

    override suspend fun insertRule(rule: RuleEntity): Long {
        val id = if (rule.id == 0L) nextRuleId++ else rule.id.also { if (it >= nextRuleId) nextRuleId = it + 1 }
        rules += rule.copy(id = id)
        refresh()
        return id
    }

    override suspend fun insertCondition(condition: RuleConditionEntity): Long {
        val id = nextConditionId++
        conditions += condition.copy(id = id)
        refresh()
        return id
    }

    override suspend fun updateRule(rule: RuleEntity) {
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) rules[index] = rule
        refresh()
    }

    override suspend fun findRuleById(id: Long): RuleEntity? = rules.firstOrNull { it.id == id }

    override suspend fun deleteRule(rule: RuleEntity) = removeRule(rule.id)

    override suspend fun deleteRuleById(id: Long) {
        removeRule(id)
        deleteFailureAfterMutation?.let { throw it }
    }

    override suspend fun deleteAllRules(): Int {
        val count = rules.size
        rules.clear()
        conditions.clear()
        refresh()
        return count
    }

    override suspend fun deleteConditionsForRule(ruleId: Long) {
        conditions.removeAll { it.ruleId == ruleId }
        refresh()
    }

    override suspend fun setRulesEnabled(
        ids: List<Long>,
        enabled: Boolean,
        updatedAtMillis: Long,
    ): Int {
        var changed = 0
        rules.replaceAll { rule ->
            if (rule.id in ids && rule.enabled != enabled) {
                changed++
                rule.copy(enabled = enabled, updatedAtMillis = updatedAtMillis)
            } else {
                rule
            }
        }
        refresh()
        return changed
    }

    private fun removeRule(id: Long) {
        rules.removeAll { it.id == id }
        conditions.removeAll { it.ruleId == id }
        refresh()
    }
}
