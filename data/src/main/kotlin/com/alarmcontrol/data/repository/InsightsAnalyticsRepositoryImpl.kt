package com.alarmcontrol.data.repository

import com.alarmcontrol.core.insights.ActionBreakdown
import com.alarmcontrol.core.insights.AppInsightCount
import com.alarmcontrol.core.insights.CategoryCount
import com.alarmcontrol.core.insights.ChannelCount
import com.alarmcontrol.core.insights.HourInsightCount
import com.alarmcontrol.core.insights.InsightsAnalytics
import com.alarmcontrol.core.insights.InsightsAnalyticsRepository
import com.alarmcontrol.core.insights.InsightsBucket
import com.alarmcontrol.core.insights.InsightsDateRange
import com.alarmcontrol.core.insights.InsightsTrendPoint
import com.alarmcontrol.core.insights.RuleInsightCount
import com.alarmcontrol.core.insights.SemanticIntentCount
import com.alarmcontrol.data.db.dao.DailyInsightDao
import com.alarmcontrol.data.mapper.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

class InsightsAnalyticsRepositoryImpl
    @Inject
    constructor(
        private val dao: DailyInsightDao,
    ) : InsightsAnalyticsRepository {
        override fun observe(range: InsightsDateRange): Flow<InsightsAnalytics> =
            combine(
                dao.observeBetween(range.startEpochDay, range.endEpochDay),
                dao.observeSourceGapDaysBetween(range.startEpochDay, range.endEpochDay),
            ) { rows, sourceGapDays ->
                val days = rows.map { it.toDomain() }
                val actualRules =
                    days.flatMap { it.topRules }.groupingBy { it.ruleId }.fold(0) { sum, row ->
                        sum.saturatedPlus(row.count)
                    }
                val monitoredRules =
                    days
                        .flatMap { it.topMonitoredRules }
                        .groupingBy { it.ruleId }
                        .fold(0) { sum, row -> sum.saturatedPlus(row.count) }
                val ruleIds = actualRules.keys + monitoredRules.keys
                InsightsAnalytics(
                    range = range,
                    totalNotifications = days.map { it.totalNotifications }.saturatedSum(),
                    actionBreakdown = days.map { it.actionBreakdown }.summed(),
                    monitoredActionBreakdown = days.map { it.monitoredActionBreakdown }.summed(),
                    apps =
                        days
                            .flatMap { it.appBreakdown }
                            .groupBy { it.packageName }
                            .map { (packageName, counts) ->
                                AppInsightCount(
                                    packageName,
                                    counts.map { it.totalCount }.saturatedSum(),
                                    counts.map { it.silencedCount }.saturatedSum(),
                                )
                            }.sortedWith(
                                compareByDescending<AppInsightCount> { it.totalCount }.thenBy { it.packageName },
                            ),
                    rules =
                        ruleIds
                            .map { RuleInsightCount(it, actualRules[it] ?: 0, monitoredRules[it] ?: 0) }
                            .sortedWith(
                                compareByDescending<RuleInsightCount> {
                                    it.actualCount.toLong() + it.monitoredCount
                                }.thenBy { it.ruleId },
                            ),
                    categories =
                        days
                            .flatMap { it.categoryBreakdown }
                            .groupingBy { it.category }
                            .fold(0) { sum, row -> sum.saturatedPlus(row.count) }
                            .map { CategoryCount(it.key, it.value) }
                            .sortedByDescending { it.count },
                    channels =
                        days
                            .flatMap { it.channelBreakdown }
                            .groupBy { it.packageName to it.channelId }
                            .map { (key, counts) ->
                                ChannelCount(
                                    packageName = key.first,
                                    channelId = key.second,
                                    count = counts.map { it.count }.saturatedSum(),
                                    channelName = counts.mapNotNull { it.channelName }.lastOrNull(),
                                )
                            }.sortedByDescending { it.count },
                    hours =
                        days
                            .flatMap { it.hourBreakdown }
                            .groupBy { it.hour }
                            .map { (hour, counts) ->
                                HourInsightCount(
                                    hour,
                                    counts.map { it.totalCount }.saturatedSum(),
                                    counts.map { it.silencedCount }.saturatedSum(),
                                )
                            }.sortedBy { it.hour },
                    semanticIntents =
                        days
                            .flatMap { it.semanticBreakdown }
                            .groupingBy { it.intent }
                            .fold(0) { sum, row -> sum.saturatedPlus(row.count) }
                            .map { SemanticIntentCount(it.key, it.value) }
                            .sortedByDescending { it.count },
                    mlClassifiedCount = days.map { it.mlClassifiedCount }.saturatedSum(),
                    categoryCorrectionCount = days.map { it.categoryCorrectionCount }.saturatedSum(),
                    semanticCorrectionCount = days.map { it.semanticCorrectionCount }.saturatedSum(),
                    bucket = range.bucket(),
                    trend = days.toTrend(range.bucket()),
                    sourceComplete = days.hasCompleteSource(sourceGapDays),
                    breakdownCoverageStartEpochDay =
                        days.filter { it.breakdownVersion > 0 }.minOfOrNull { it.epochDay },
                )
            }

        override fun observeAvailableRange(): Flow<InsightsDateRange?> =
            dao.observeBounds().map { row ->
                val oldest = row.oldest
                val newest = row.newest
                if (oldest == null || newest == null) null else InsightsDateRange(oldest, newest)
            }
    }

private fun List<com.alarmcontrol.core.insights.DailyInsight>.hasCompleteSource(sourceGapDays: List<Long>): Boolean {
    val rolledDays = mapTo(mutableSetOf()) { it.epochDay }
    return all { it.sourceComplete } && sourceGapDays.none { it !in rolledDays }
}

private fun InsightsDateRange.bucket(): InsightsBucket =
    when (endEpochDay - startEpochDay + 1) {
        in 1..MAX_DAILY_BUCKET_DAYS -> InsightsBucket.DAY
        in (MAX_DAILY_BUCKET_DAYS + 1)..MAX_WEEKLY_BUCKET_DAYS -> InsightsBucket.WEEK
        else -> InsightsBucket.MONTH
    }

private fun List<com.alarmcontrol.core.insights.DailyInsight>.toTrend(
    bucket: InsightsBucket,
): List<InsightsTrendPoint> =
    groupBy { day ->
        val date = LocalDate.ofEpochDay(day.epochDay)
        when (bucket) {
            InsightsBucket.DAY -> date
            InsightsBucket.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            InsightsBucket.MONTH -> date.withDayOfMonth(1)
        }
    }.map { (start, days) ->
        InsightsTrendPoint(
            startEpochDay = start.toEpochDay(),
            endEpochDay = days.maxOf { it.epochDay },
            totalCount = days.map { it.totalNotifications }.saturatedSum(),
            silencedCount = days.map { it.mutedCount }.saturatedSum(),
        )
    }.sortedBy(InsightsTrendPoint::startEpochDay)

private fun List<ActionBreakdown>.summed(): ActionBreakdown =
    ActionBreakdown(
        cancelled = map { it.cancelled }.saturatedSum(),
        snoozed = map { it.snoozed }.saturatedSum(),
        loggedOnly = map { it.loggedOnly }.saturatedSum(),
        kept = map { it.kept }.saturatedSum(),
    )

private fun Iterable<Int>.saturatedSum(): Int = fold(0, Int::saturatedPlus)

private fun Int.saturatedPlus(other: Int): Int =
    (toLong() + other)
        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        .toInt()

private const val MAX_DAILY_BUCKET_DAYS = 31L
private const val MAX_WEEKLY_BUCKET_DAYS = 180L
