package com.alarmcontrol.automation

import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.yield

/** In-memory [RuleRepository] for JVM tests; [saveRule] replaces by id and re-emits. */
class FakeRuleRepository(
    initial: List<Rule>,
) : RuleRepository {
    private val rules = MutableStateFlow(initial)
    var saveCount = 0
        private set
    var bulkUpdateCount = 0
        private set
    var beforeBulkUpdate: suspend () -> Unit = { yield() }

    override fun observeRules(): Flow<List<Rule>> = rules

    override suspend fun saveRule(rule: Rule): String {
        saveCount++
        rules.value = rules.value.map { if (it.id == rule.id) rule else it }
        return rule.id
    }

    override suspend fun deleteRule(ruleId: String) {
        rules.value = rules.value.filterNot { it.id == ruleId }
    }

    override suspend fun setRulesEnabled(
        ruleIds: Set<String>,
        enabled: Boolean,
    ): Int {
        bulkUpdateCount++
        beforeBulkUpdate()
        var changed = 0
        rules.value =
            rules.value.map { rule ->
                if (rule.id in ruleIds && rule.enabled != enabled) {
                    changed++
                    rule.copy(enabled = enabled)
                } else {
                    rule
                }
            }
        return changed
    }

    fun current(): List<Rule> = rules.value
}
