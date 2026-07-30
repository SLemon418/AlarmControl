package com.alarmcontrol.core.filtering

import com.alarmcontrol.core.privacy.LocalDataResetWriteFence
import kotlinx.coroutines.flow.Flow

/**
 * Source of the user's filtering [Rule]s, expressed purely in domain terms (CLAUDE.md §4).
 *
 * The interface lives in `:core` so both the persistence layer (`:data`, which implements it and
 * maps Room entities ↔ domain) and the engine layer (`:notifications`) can depend on it without
 * either depending on the other. Nothing here references Room or any Android type.
 */
interface RuleRepository {
    /** Observes all rules, highest [Rule.priority] first. Re-emits on any change. */
    fun observeRules(): Flow<List<Rule>>

    /**
     * Inserts a new rule (when [Rule.id] is blank/`"0"`) or replaces the existing one, and returns
     * the persisted id.
     */
    suspend fun saveRule(rule: Rule): String

    /** Atomically changes enabled state for existing [ruleIds] without rewriting condition trees. */
    suspend fun setRulesEnabled(
        ruleIds: Set<String>,
        enabled: Boolean,
    ): Int

    /**
     * Same mutation, but tied to the caller's operation-entry reset generation. Orchestrators use
     * this to prevent a request started before a whole-data reset from recapturing the new epoch.
     */
    suspend fun setRulesEnabledIfCurrent(
        ruleIds: Set<String>,
        enabled: Boolean,
        resetEpoch: LocalDataResetWriteFence.Epoch,
    ): Int = setRulesEnabled(ruleIds, enabled)

    /** Removes the rule with [ruleId]; a no-op if it does not exist. */
    suspend fun deleteRule(ruleId: String)
}
