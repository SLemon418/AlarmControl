package com.alarmcontrol.data.repository

import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleDefinitionValidator
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.privacy.LocalDataResetWriteFence
import com.alarmcontrol.core.privacy.StaleLocalDataWriteException
import com.alarmcontrol.core.settings.FilteringActionGate
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
        private val filteringActionGate: FilteringActionGate = FilteringActionGate(),
        private val localDataResetWriteFence: LocalDataResetWriteFence = LocalDataResetWriteFence(),
    ) : RuleRepository {
        override fun observeRules(): Flow<List<Rule>> =
            ruleDao.observeRulesWithConditions().map { rows -> rows.map(RuleWithConditions::toDomain) }

        override suspend fun saveRule(rule: Rule): String {
            val resetEpoch = localDataResetWriteFence.captureEpoch()
            RuleDefinitionValidator.requireValid(rule)
            val now = System.currentTimeMillis()
            val existingId =
                if (rule.id.isBlank()) {
                    null
                } else {
                    requireNotNull(rule.id.toLongOrNull()?.takeIf { it > 0 }) { "Invalid rule id" }
                }
            val entity =
                rule.toRuleEntity(
                    id = existingId ?: 0,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                )
            val ruleId =
                localDataResetWriteFence
                    .writeIfCurrent(resetEpoch) {
                        filteringActionGate.withRuleMutation {
                            ruleDao.storeRuleWithConditions(entity, rule.condition.toPendingTree())
                        }
                    } ?: throw StaleLocalDataWriteException()
            return ruleId.toString()
        }

        override suspend fun deleteRule(ruleId: String) {
            val resetEpoch = localDataResetWriteFence.captureEpoch()
            val id = ruleId.toLongOrNull() ?: return
            localDataResetWriteFence.writeIfCurrent(resetEpoch) {
                filteringActionGate.withRuleMutation {
                    ruleDao.deleteRuleById(id)
                }
                Unit
            } ?: throw StaleLocalDataWriteException()
        }

        override suspend fun setRulesEnabled(
            ruleIds: Set<String>,
            enabled: Boolean,
        ): Int =
            setRulesEnabledIfCurrent(
                ruleIds,
                enabled,
                localDataResetWriteFence.captureEpoch(),
            )

        override suspend fun setRulesEnabledIfCurrent(
            ruleIds: Set<String>,
            enabled: Boolean,
            resetEpoch: LocalDataResetWriteFence.Epoch,
        ): Int {
            val ids = ruleIds.mapNotNull(String::toLongOrNull)
            if (ids.isEmpty()) return 0
            return localDataResetWriteFence.writeIfCurrent(resetEpoch) {
                filteringActionGate.withRuleMutation {
                    ruleDao.setRulesEnabled(ids, enabled, System.currentTimeMillis())
                }
            } ?: throw StaleLocalDataWriteException()
        }
    }
