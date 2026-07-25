package com.alarmcontrol.data.repository

import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.insights.ActionBreakdown
import com.alarmcontrol.core.insights.AppInsightCount
import com.alarmcontrol.core.insights.CategoryCount
import com.alarmcontrol.core.insights.ChannelCount
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.insights.DailyInsightRepository
import com.alarmcontrol.core.insights.HourInsightCount
import com.alarmcontrol.core.insights.RuleTriggerCount
import com.alarmcontrol.core.insights.SemanticIntentCount
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
    ) : DailyInsightRepository {
        override suspend fun aggregateAndStore(
            epochDay: Long,
            startMillis: Long,
            endMillis: Long,
            generatedAtMillis: Long,
            topRules: Int,
        ): DailyInsight {
            val actionBreakdown = dao.actionCountsBetween(startMillis, endMillis).toActionBreakdown()
            val monitoredActionBreakdown =
                dao.monitoredActionCountsBetween(startMillis, endMillis).toActionBreakdown()
            val ruleRows = dao.topRulesBetween(startMillis, endMillis, topRules)
            val monitorRuleRows = dao.topMonitoredRulesBetween(startMillis, endMillis, topRules)
            val channelRows = dao.channelBreakdownBetween(startMillis, endMillis, BREAKDOWN_LIMIT)
            val appRows =
                dao.appBreakdownBetween(
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
                    totalNotifications = dao.countBetween(startMillis, endMillis),
                    mutedCount = actionBreakdown.silenced,
                    topRules =
                        ruleRows
                            .map { RuleTriggerCount(it.ruleId.toString(), it.count) },
                    topMonitoredRules =
                        monitorRuleRows
                            .map { RuleTriggerCount(it.ruleId.toString(), it.count) },
                    categoryBreakdown =
                        dao
                            .categoryBreakdownBetween(startMillis, endMillis)
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
                                startMillis,
                                endMillis,
                                StoredRuleAction.CANCEL,
                                StoredRuleAction.SNOOZE,
                            ).map { HourInsightCount(it.hour, it.totalCount, it.silencedCount) },
                    semanticBreakdown =
                        dao
                            .semanticBreakdownBetween(startMillis, endMillis)
                            .map { SemanticIntentCount(SemanticIntent.valueOf(it.intent), it.count) },
                    mlClassifiedCount = dao.countMlClassifiedBetween(startMillis, endMillis),
                    categoryCorrectionCount = dao.countCategoryCorrectionsBetween(startMillis, endMillis),
                    semanticCorrectionCount = dao.countSemanticCorrectionsBetween(startMillis, endMillis),
                    breakdownVersion = CURRENT_BREAKDOWN_VERSION,
                    ruleBreakdownComplete = dao.countMatchedRulesBetween(startMillis, endMillis) <= topRules,
                    monitorRuleBreakdownComplete =
                        dao.countMonitoredRulesBetween(startMillis, endMillis) <= topRules,
                    appBreakdownComplete = dao.countAppsBetween(startMillis, endMillis) <= BREAKDOWN_LIMIT,
                    channelBreakdownComplete = dao.countChannelsBetween(startMillis, endMillis) <= BREAKDOWN_LIMIT,
                )

            dao.store(insight.toWrite())
            return insight
        }

        override fun observeRecent(limit: Int): Flow<List<DailyInsight>> =
            dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

        override suspend fun existingEpochDaysBetween(
            startEpochDay: Long,
            endEpochDay: Long,
        ): Set<Long> = dao.getEpochDaysBetween(startEpochDay, endEpochDay).toSet()

        override suspend fun purgeOlderThan(epochDay: Long): Int = dao.deleteOlderThan(epochDay)

        private companion object {
            const val BREAKDOWN_LIMIT = 50
            const val CURRENT_BREAKDOWN_VERSION = 2
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
