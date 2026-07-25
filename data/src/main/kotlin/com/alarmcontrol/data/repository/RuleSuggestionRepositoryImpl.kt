package com.alarmcontrol.data.repository

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.filtering.RuleSuggestion
import com.alarmcontrol.core.filtering.RuleSuggestionRepository
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.data.db.dao.RuleSuggestionDao
import com.alarmcontrol.data.db.entity.RuleSuggestionDismissalEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class RuleSuggestionRepositoryImpl
    @Inject
    constructor(
        private val dao: RuleSuggestionDao,
        ruleRepository: RuleRepository,
    ) : RuleSuggestionRepository {
        private val rules = ruleRepository.observeRules()

        override fun observeSuggestions(sinceMillis: Long): Flow<List<RuleSuggestion>> =
            combine(
                dao.observeChannelCandidates(
                    sinceMillis = sinceMillis,
                    minimumEvents = MIN_CHANNEL_EVENTS,
                    minimumPercent = MIN_SILENCED_PERCENT,
                    cancelAction = StoredRuleAction.CANCEL,
                    snoozeAction = StoredRuleAction.SNOOZE,
                ),
                dao.observeMarketingCandidates(
                    sinceMillis = sinceMillis,
                    minimumCorrections = MIN_MARKETING_CORRECTIONS,
                    minimumPercent = MIN_MARKETING_PERCENT,
                ),
                dao.observeDismissedKeys(),
                rules,
            ) { channels, marketing, dismissedRows, currentRules ->
                val dismissed = dismissedRows.toSet()
                buildList {
                    channels.forEach { row ->
                        val key = channelKey(row.packageName, row.channelId)
                        if (key !in dismissed) {
                            add(
                                RuleSuggestion.QuietChannel(
                                    key,
                                    row.packageName,
                                    row.channelId,
                                    row.totalCount,
                                    row.silencedCount,
                                ),
                            )
                        }
                    }
                    marketing.forEach { row ->
                        val draft = marketingDraft(row.packageName)
                        val exists = currentRules.any { it.condition == draft.condition && it.action == draft.action }
                        val key = marketingKey(row.packageName)
                        if (!exists && key !in dismissed) {
                            add(
                                RuleSuggestion.MarketingRuleDraft(
                                    key,
                                    row.packageName,
                                    row.marketingCount,
                                    row.totalCount,
                                    draft,
                                ),
                            )
                        }
                    }
                }
            }

        override suspend fun dismiss(
            suggestionKey: String,
            dismissedAtMillis: Long,
        ) {
            dao.dismiss(RuleSuggestionDismissalEntity(suggestionKey, dismissedAtMillis))
        }

        private fun marketingDraft(packageName: String): Rule =
            Rule(
                id = "",
                name = "Filter promotions from $packageName",
                enabled = true,
                priority = 0,
                condition =
                    Condition.AllOf(
                        listOf(
                            Condition.PackageEquals(packageName),
                            Condition.AnyOf(
                                listOf(
                                    Condition.MlCategoryEquals("promotion"),
                                    Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                                ),
                            ),
                        ),
                    ),
                action = RuleAction.Cancel,
                executionMode = RuleExecutionMode.MONITOR,
            )

        private fun channelKey(
            packageName: String,
            channelId: String,
        ): String = "channel:$packageName:$channelId"

        private fun marketingKey(packageName: String): String = "marketing:$packageName"

        private companion object {
            const val MIN_CHANNEL_EVENTS = 10
            const val MIN_SILENCED_PERCENT = 80
            const val MIN_MARKETING_CORRECTIONS = 3
            const val MIN_MARKETING_PERCENT = 75
        }
    }
