package com.alarmcontrol.data.repository

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.filtering.RuleSuggestion
import com.alarmcontrol.data.db.dao.ChannelSuggestionRow
import com.alarmcontrol.data.db.dao.MarketingSuggestionRow
import com.alarmcontrol.data.db.dao.RuleSuggestionDao
import com.alarmcontrol.data.db.entity.RuleSuggestionDismissalEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSuggestionRepositoryImplTest {
    private val dao = FakeRuleSuggestionDao()
    private val rules = FakeSuggestionRuleRepository()
    private val repository = RuleSuggestionRepositoryImpl(dao, rules)

    @Test
    fun `maps SQL candidates to a channel action and monitor cancel draft`() =
        runTest {
            dao.channels.value = listOf(ChannelSuggestionRow("com.shop", "offers", 10, 8))
            dao.marketing.value = listOf(MarketingSuggestionRow("com.shop", 3, 4))

            val suggestions = repository.observeSuggestions(100, 1_000).first()

            assertEquals(2, suggestions.size)
            assertTrue(suggestions[0] is RuleSuggestion.QuietChannel)
            val draft = (suggestions[1] as RuleSuggestion.MarketingRuleDraft).draft
            assertEquals(RuleExecutionMode.MONITOR, draft.executionMode)
            assertEquals(RuleAction.Cancel, draft.action)
            assertTrue(draft.condition is Condition.AllOf)
        }

    @Test
    fun `dismissal and structurally identical existing rule suppress repeat suggestions`() =
        runTest {
            dao.channels.value = listOf(ChannelSuggestionRow("com.shop", "offers", 10, 8))
            dao.marketing.value = listOf(MarketingSuggestionRow("com.shop", 3, 4))
            val first = repository.observeSuggestions(0, 1_000).first()
            val marketing = first.filterIsInstance<RuleSuggestion.MarketingRuleDraft>().single()
            rules.values.value = listOf(marketing.draft.copy(id = "1", executionMode = RuleExecutionMode.ACTIVE))
            repository.dismiss(first.filterIsInstance<RuleSuggestion.QuietChannel>().single().key, 123)

            assertEquals(emptyList<RuleSuggestion>(), repository.observeSuggestions(0, 1_000).first())
            assertEquals(123L, dao.dismissedAt)
        }
}

class FakeRuleSuggestionDao : RuleSuggestionDao {
    val channels = MutableStateFlow<List<ChannelSuggestionRow>>(emptyList())
    val marketing = MutableStateFlow<List<MarketingSuggestionRow>>(emptyList())
    private val dismissed = MutableStateFlow<List<String>>(emptyList())
    var dismissedAt: Long? = null

    override fun observeChannelCandidates(
        sinceMillis: Long,
        nowMillis: Long,
        minimumEvents: Int,
        minimumPercent: Int,
        cancelAction: StoredRuleAction,
        snoozeAction: StoredRuleAction,
    ): Flow<List<ChannelSuggestionRow>> = channels

    override fun observeMarketingCandidates(
        sinceMillis: Long,
        nowMillis: Long,
        minimumCorrections: Int,
        minimumPercent: Int,
    ): Flow<List<MarketingSuggestionRow>> = marketing

    override fun observeDismissedKeys(): Flow<List<String>> = dismissed

    override suspend fun dismiss(entity: RuleSuggestionDismissalEntity) {
        dismissedAt = entity.dismissedAtMillis
        dismissed.value = (dismissed.value + entity.suggestionKey).distinct()
    }

    override suspend fun deleteAllDismissals(): Int =
        dismissed.value.size.also {
            dismissed.value = emptyList()
        }
}

private class FakeSuggestionRuleRepository : RuleRepository {
    val values = MutableStateFlow<List<Rule>>(emptyList())

    override fun observeRules(): Flow<List<Rule>> = values

    override suspend fun saveRule(rule: Rule): String = error("Not used")

    override suspend fun setRulesEnabled(
        ruleIds: Set<String>,
        enabled: Boolean,
    ): Int = error("Not used")

    override suspend fun deleteRule(ruleId: String) = error("Not used")
}
