package com.alarmcontrol.data.mapper

import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.insights.ActionBreakdown
import com.alarmcontrol.core.insights.AppInsightCount
import com.alarmcontrol.core.insights.CategoryCount
import com.alarmcontrol.core.insights.ChannelCount
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.insights.HourInsightCount
import com.alarmcontrol.core.insights.RuleTriggerCount
import com.alarmcontrol.core.insights.SemanticIntentCount
import com.alarmcontrol.data.db.dao.DailyInsightWrite
import com.alarmcontrol.data.db.entity.DailyInsightAppCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightCategoryCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightChannelCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightEntity
import com.alarmcontrol.data.db.entity.DailyInsightHourCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightMonitorRuleCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightRuleCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightSemanticCountEntity
import com.alarmcontrol.data.db.relation.DailyInsightWithBreakdown

/** Domain rollup -> the scalar Room row (write path). */
fun DailyInsight.toEntity(): DailyInsightEntity =
    DailyInsightEntity(
        epochDay = epochDay,
        windowStartMillis = windowStartMillis,
        windowEndMillis = windowEndMillis,
        totalNotifications = totalNotifications,
        mutedCount = mutedCount,
        cancelledCount = actionBreakdown.cancelled,
        snoozedCount = actionBreakdown.snoozed,
        loggedCount = actionBreakdown.loggedOnly,
        keptCount = actionBreakdown.kept,
        monitoredCancelledCount = monitoredActionBreakdown.cancelled,
        monitoredSnoozedCount = monitoredActionBreakdown.snoozed,
        monitoredLoggedCount = monitoredActionBreakdown.loggedOnly,
        monitoredKeptCount = monitoredActionBreakdown.kept,
        generatedAtMillis = generatedAtMillis,
        mlClassifiedCount = mlClassifiedCount,
        categoryCorrectionCount = categoryCorrectionCount,
        semanticCorrectionCount = semanticCorrectionCount,
        breakdownVersion = breakdownVersion,
        sourceComplete = sourceComplete,
        ruleBreakdownComplete = ruleBreakdownComplete,
        monitorRuleBreakdownComplete = monitorRuleBreakdownComplete,
        appBreakdownComplete = appBreakdownComplete,
        channelBreakdownComplete = channelBreakdownComplete,
    )

/** Domain rollup -> its rule-count child rows (write path). */
fun DailyInsight.toRuleCountEntities(): List<DailyInsightRuleCountEntity> =
    topRules.map { DailyInsightRuleCountEntity(epochDay = epochDay, ruleId = it.ruleId, count = it.count) }

fun DailyInsight.toMonitorRuleCountEntities(): List<DailyInsightMonitorRuleCountEntity> =
    topMonitoredRules.map {
        DailyInsightMonitorRuleCountEntity(epochDay = epochDay, ruleId = it.ruleId, count = it.count)
    }

/** Domain rollup -> its category-count child rows (write path). */
fun DailyInsight.toCategoryCountEntities(): List<DailyInsightCategoryCountEntity> =
    categoryBreakdown.map {
        DailyInsightCategoryCountEntity(
            epochDay = epochDay,
            category = it.category,
            count = it.count,
        )
    }

fun DailyInsight.toChannelCountEntities(): List<DailyInsightChannelCountEntity> =
    channelBreakdown.map {
        DailyInsightChannelCountEntity(
            epochDay = epochDay,
            packageName = it.packageName,
            channelId = it.channelId,
            channelName = it.channelName,
            count = it.count,
        )
    }

fun DailyInsight.toAppCountEntities(): List<DailyInsightAppCountEntity> =
    appBreakdown.map {
        DailyInsightAppCountEntity(
            epochDay = epochDay,
            packageName = it.packageName,
            totalCount = it.totalCount,
            silencedCount = it.silencedCount,
        )
    }

fun DailyInsight.toHourCountEntities(): List<DailyInsightHourCountEntity> =
    hourBreakdown.map {
        DailyInsightHourCountEntity(
            epochDay = epochDay,
            hour = it.hour,
            totalCount = it.totalCount,
            silencedCount = it.silencedCount,
        )
    }

fun DailyInsight.toSemanticCountEntities(): List<DailyInsightSemanticCountEntity> =
    semanticBreakdown.map {
        DailyInsightSemanticCountEntity(
            epochDay = epochDay,
            intent = it.intent.name,
            count = it.count,
        )
    }

fun DailyInsight.toWrite(): DailyInsightWrite =
    DailyInsightWrite(
        insight = toEntity(),
        ruleCounts = toRuleCountEntities(),
        monitorRuleCounts = toMonitorRuleCountEntities(),
        categoryCounts = toCategoryCountEntities(),
        channelCounts = toChannelCountEntities(),
        appCounts = toAppCountEntities(),
        hourCounts = toHourCountEntities(),
        semanticCounts = toSemanticCountEntities(),
        sourceComplete = sourceComplete,
    )

/**
 * Room relation -> domain rollup (read path). Children are re-sorted (count desc, then id/category)
 * because `@Relation` makes no ordering guarantee — keeping reads deterministic for the UI and tests.
 */
fun DailyInsightWithBreakdown.toDomain(): DailyInsight =
    DailyInsight(
        epochDay = insight.epochDay,
        windowStartMillis = insight.windowStartMillis,
        windowEndMillis = insight.windowEndMillis,
        totalNotifications = insight.totalNotifications,
        mutedCount = insight.mutedCount,
        topRules =
            ruleCounts
                .sortedWith(compareByDescending<DailyInsightRuleCountEntity> { it.count }.thenBy { it.ruleId })
                .map { RuleTriggerCount(it.ruleId, it.count) },
        topMonitoredRules =
            monitorRuleCounts
                .sortedWith(
                    compareByDescending<DailyInsightMonitorRuleCountEntity> { it.count }.thenBy { it.ruleId },
                ).map { RuleTriggerCount(it.ruleId, it.count) },
        categoryBreakdown =
            categoryCounts
                .sortedWith(
                    compareByDescending<DailyInsightCategoryCountEntity> { it.count }.thenBy { it.category ?: "" },
                ).map { CategoryCount(it.category, it.count) },
        generatedAtMillis = insight.generatedAtMillis,
        actionBreakdown =
            ActionBreakdown(
                cancelled = insight.cancelledCount,
                snoozed = insight.snoozedCount,
                loggedOnly = insight.loggedCount,
                kept = insight.keptCount,
            ),
        monitoredActionBreakdown =
            ActionBreakdown(
                cancelled = insight.monitoredCancelledCount,
                snoozed = insight.monitoredSnoozedCount,
                loggedOnly = insight.monitoredLoggedCount,
                kept = insight.monitoredKeptCount,
            ),
        channelBreakdown =
            channelCounts
                .sortedWith(
                    compareByDescending<DailyInsightChannelCountEntity> { it.count }
                        .thenBy { it.packageName }
                        .thenBy { it.channelId },
                ).map { ChannelCount(it.packageName, it.channelId, it.count, it.channelName) },
        appBreakdown =
            appCounts
                .sortedWith(
                    compareByDescending<DailyInsightAppCountEntity> { it.totalCount }.thenBy { it.packageName },
                ).map { AppInsightCount(it.packageName, it.totalCount, it.silencedCount) },
        hourBreakdown =
            hourCounts
                .sortedBy(DailyInsightHourCountEntity::hour)
                .map { HourInsightCount(it.hour, it.totalCount, it.silencedCount) },
        semanticBreakdown =
            semanticCounts
                .sortedWith(
                    compareByDescending<DailyInsightSemanticCountEntity> { it.count }.thenBy { it.intent },
                ).map { SemanticIntentCount(SemanticIntent.valueOf(it.intent), it.count) },
        mlClassifiedCount = insight.mlClassifiedCount,
        categoryCorrectionCount = insight.categoryCorrectionCount,
        semanticCorrectionCount = insight.semanticCorrectionCount,
        breakdownVersion = insight.breakdownVersion,
        sourceComplete = insight.sourceComplete,
        ruleBreakdownComplete = insight.ruleBreakdownComplete,
        monitorRuleBreakdownComplete = insight.monitorRuleBreakdownComplete,
        appBreakdownComplete = insight.appBreakdownComplete,
        channelBreakdownComplete = insight.channelBreakdownComplete,
    )
