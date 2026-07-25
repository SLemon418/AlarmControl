package com.alarmcontrol.data.repository

import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleDefinitionValidator
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.data.db.dao.RuleDao
import com.alarmcontrol.data.db.relation.RuleWithConditions
import com.alarmcontrol.data.mapper.toDomain
import com.alarmcontrol.data.mapper.toPendingTree
import com.alarmcontrol.data.mapper.toRuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Room-backed [RuleRepository]. Maps persisted entities to the domain on the way out and back on the
 * way in; callers see only `:core` domain types. The condition tree is written depth-first so each
 * node's generated id can parent its children.
 */
class RuleRepositoryImpl
    @Inject
    constructor(
        private val ruleDao: RuleDao,
    ) : RuleRepository {
        override fun observeRules(): Flow<List<Rule>> =
            ruleDao.observeRulesWithConditions().map { rows -> rows.map(RuleWithConditions::toDomain) }

        override suspend fun saveRule(rule: Rule): String {
            RuleDefinitionValidator.requireValid(rule)
            val now = System.currentTimeMillis()
            val existingId = rule.id.toLongOrNull()?.takeIf { it != 0L }
            val entity =
                rule.toRuleEntity(
                    id = existingId ?: 0,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                )
            val ruleId = ruleDao.storeRuleWithConditions(entity, rule.condition.toPendingTree())
            return ruleId.toString()
        }

        override suspend fun deleteRule(ruleId: String) {
            val id = ruleId.toLongOrNull() ?: return
            ruleDao.deleteRuleById(id)
        }

        override suspend fun setRulesEnabled(
            ruleIds: Set<String>,
            enabled: Boolean,
        ): Int {
            val ids = ruleIds.mapNotNull(String::toLongOrNull)
            if (ids.isEmpty()) return 0
            return ruleDao.setRulesEnabled(ids, enabled, System.currentTimeMillis())
        }
    }
