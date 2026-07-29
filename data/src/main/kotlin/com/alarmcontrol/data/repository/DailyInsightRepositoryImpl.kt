package com.alarmcontrol.data.repository

import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.insights.ActionBreakdown
import com.alarmcontrol.core.insights.AppInsightCount
import com.alarmcontrol.core.insights.CURRENT_DAILY_INSIGHT_BREAKDOWN_VERSION
import com.alarmcontrol.core.insights.CategoryCount
import com.alarmcontrol.core.insights.ChannelCount
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.insights.DailyInsightRepository
import com.alarmcontrol.core.insights.HourInsightCount
import com.alarmcontrol.core.insights.RuleTriggerCount
import com.alarmcontrol.core.insights.SemanticIntentCount
import com.alarmcontrol.data.db.TransactionRunner
import com.alarmcontrol.data.db.dao.ActionCountRow
import com.alarmcontrol.data.db.dao.DailyInsightDao
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.mapper.toDomain
import com.alarmcontrol.data.mapper.toWrite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Room-backed [DailyInsightRepository]; aggregates via SQL and maps at the boundary. */
class DailyInsightRepositoryImpl
    @Inject
    constructor(
        private val dao: DailyInsightDao,
        private val transactionRunner: TransactionRunner,
    ) : DailyInsightRepository {
        override suspend fun aggregateAndStore(
            epochDay: Long,
            startMillis: Long,
            endMillis: Long,
            generatedAtMillis: Long,
            topRules: Int,
        ): DailyInsight {
            require(startMillis < endMillis) { "Daily insight window must not be empty or reversed" }
            require(topRules in 1..MAX_RULE_BREAKDOWN_LIMIT) { "Daily rule limit is out of range" }
            return transactionRunner.run {
                val actionBreakdown =
                    dao.actionCountsBetween(epochDay, startMillis, endMillis).toActionBreakdown()
                val monitoredActionBreakdown =
                    dao.monitoredActionCountsBetween(epochDay, startMillis, endMillis).toActionBreakdown()
                val ruleRows = dao.topRulesBetween(epochDay, startMillis, endMillis, topRules)
                val monitorRuleRows =
                    dao.topMonitoredRulesBetween(epochDay, startMillis, endMillis, topRules)
                val channelRows =
                    dao.channelBreakdownBetween(epochDay, startMillis, endMillis, BREAKDOWN_LIMIT)
                val appRows =
                    dao.appBreakdownBetween(
                        epochDay,
                        startMillis,
                        endMillis,
                        StoredRuleAction.CANCEL,
                        StoredRuleAction.SNOOZE,
                        BREAKDOWN_LIMIT,
                    )
                // Only actions with a real platform silencing side effect count as muted.
                val insight =
                    DailyInsight(
                        epochDay = epochDay,
                        windowStartMillis = startMillis,
                        windowEndMillis = endMillis,
                        totalNotifications = dao.countBetween(epochDay, startMillis, endMillis),
                        mutedCount = actionBreakdown.silenced,
                        topRules =
                            ruleRows
                                .map { RuleTriggerCount(it.ruleId.toString(), it.count) },
                        topMonitoredRules =
                            monitorRuleRows
                                .map { RuleTriggerCount(it.ruleId.toString(), it.count) },
                        categoryBreakdown =
                            dao
                                .categoryBreakdownBetween(epochDay, startMillis, endMillis)
                                .map { CategoryCount(it.category, it.count) },
                        generatedAtMillis = generatedAtMillis,
                        actionBreakdown = actionBreakdown,
                        monitoredActionBreakdown = monitoredActionBreakdown,
                        channelBreakdown =
                            channelRows
                                .map { ChannelCount(it.packageName, it.channelId, it.count, it.channelName) },
                        appBreakdown =
                            appRows.map { AppInsightCount(it.packageName, it.totalCount, it.silencedCount) },
                        hourBreakdown =
                            dao
                                .hourBreakdownBetween(
                                    epochDay,
                                    startMillis,
                                    endMillis,
                                    StoredRuleAction.CANCEL,
                                    StoredRuleAction.SNOOZE,
                                ).map { HourInsightCount(it.hour, it.totalCount, it.silencedCount) },
                        semanticBreakdown =
                            dao
                                .semanticBreakdownBetween(epochDay, startMillis, endMillis)
                                .map { SemanticIntentCount(SemanticIntent.valueOf(it.intent), it.count) },
                        mlClassifiedCount = dao.countMlClassifiedBetween(epochDay, startMillis, endMillis),
                        categoryCorrectionCount =
                            dao.countCategoryCorrectionsBetween(epochDay, startMillis, endMillis),
                        semanticCorrectionCount =
                            dao.countSemanticCorrectionsBetween(epochDay, startMillis, endMillis),
                        breakdownVersion = CURRENT_DAILY_INSIGHT_BREAKDOWN_VERSION,
                        sourceComplete = !dao.hasSourceGap(epochDay),
                        ruleBreakdownComplete =
                            dao.countMatchedRulesBetween(epochDay, startMillis, endMillis) <= topRules,
                        monitorRuleBreakdownComplete =
                            dao.countMonitoredRulesBetween(epochDay, startMillis, endMillis) <= topRules,
                        appBreakdownComplete =
                            dao.countAppsBetween(epochDay, startMillis, endMillis) <= BREAKDOWN_LIMIT,
                        channelBreakdownComplete =
                            dao.countChannelsBetween(epochDay, startMillis, endMillis) <= BREAKDOWN_LIMIT,
                    )

                dao.store(insight.toWrite())
                insight
            }
        }

        override fun observeRecent(limit: Int): Flow<List<DailyInsight>> =
            dao
                .observeRecent(
                    limit
                        .also {
                            require(it in 1..MAX_DAILY_INSIGHT_READ_LIMIT) {
                                "Daily insight read limit is out of range"
                            }
                        },
                ).map { rows -> rows.map { it.toDomain() } }

        override suspend fun existingEpochDaysBetween(
            startEpochDay: Long,
            endEpochDay: Long,
        ): Set<Long> = dao.getEpochDaysBetween(startEpochDay, endEpochDay).toSet()

        override suspend fun purgeOlderThan(epochDay: Long): Int = dao.deleteOlderThan(epochDay)

        private companion object {
            const val BREAKDOWN_LIMIT = 50
            const val MAX_RULE_BREAKDOWN_LIMIT = 1_000
            const val MAX_DAILY_INSIGHT_READ_LIMIT = 3_650
        }
    }

private fun List<ActionCountRow>.toActionBreakdown(): ActionBreakdown {
    val counts = associate { it.action to it.count }
    return ActionBreakdown(
        cancelled = counts[StoredRuleAction.CANCEL] ?: 0,
        snoozed = counts[StoredRuleAction.SNOOZE] ?: 0,
        loggedOnly = counts[StoredRuleAction.MARK_READ] ?: 0,
        kept = counts[StoredRuleAction.KEEP] ?: 0,
    )
}
