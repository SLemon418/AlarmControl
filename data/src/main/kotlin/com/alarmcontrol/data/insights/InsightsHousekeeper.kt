package com.alarmcontrol.data.insights

import com.alarmcontrol.core.filtering.NotificationEventRepository
import com.alarmcontrol.core.insights.DailyInsightRepository
import com.alarmcontrol.core.insights.InsightsAnalyzer
import com.alarmcontrol.core.insights.InsightsReport
import com.alarmcontrol.core.insights.InsightsSummary
import com.alarmcontrol.core.insights.InsightsSummaryRepository
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.core.settings.RetentionDefaults
import com.alarmcontrol.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * The actual work behind the periodic insights worker (CLAUDE.md §5), kept here as plain,
 * framework-free logic so it is unit-testable without WorkManager — the `:app` worker is just a thin
 * shell that calls [run]. Everything is local: SQL aggregation + a DataStore write, no network.
 *
 * Each run: (1) deletes expired log rows for disk hygiene, (2) aggregates muted-event counts per
 * package over the recent and prior windows, (3) ranks the most-muted apps and flags anomaly spikes,
 * (4) persists a compact summary for the UI to surface later, and (5) files a per-day rollup
 * ([DailyInsightRepository]) of the completed local day, then (6) caps the event table. Trimming is
 * deliberately last so a high-volume day is fully counted before old rows are discarded.
 */
class InsightsHousekeeper
    @Inject
    constructor(
        private val eventRepository: NotificationEventRepository,
        private val summaryRepository: InsightsSummaryRepository,
        private val dailyInsightRepository: DailyInsightRepository,
        private val settingsRepository: SettingsRepository,
    ) {
        /** Runs one pass anchored at [nowMillis]; wraps failures but preserves coroutine cancellation. */
        suspend fun run(
            nowMillis: Long,
            zoneId: ZoneId = ZoneOffset.UTC,
        ): DataResult<InsightsReport> =
            runCatchingPreservingCancellation {
                val eventRetentionDays = settingsRepository.eventRetentionDays.first().toLong()
                val dailyRetentionDays = settingsRepository.dailyInsightRetentionDays.first().toLong()
                eventRepository.purgeEncryptedContentOlderThan(
                    nowMillis - RetentionDefaults.ENCRYPTED_CONTENT_DAYS * DAY_MILLIS,
                )
                val purged = eventRepository.purgeEventsOlderThan(nowMillis - eventRetentionDays * DAY_MILLIS)

                val windowMillis = WINDOW_DAYS * DAY_MILLIS
                val recent = eventRepository.mutedCountsByPackageBetween(nowMillis - windowMillis, nowMillis)
                val baseline =
                    eventRepository.mutedCountsByPackageBetween(
                        nowMillis - 2 * windowMillis,
                        nowMillis - windowMillis,
                    )

                val report =
                    InsightsAnalyzer
                        .analyze(
                            recentCounts = recent,
                            baselineCounts = baseline,
                            windowDays = WINDOW_DAYS.toInt(),
                            topN = TOP_N,
                            anomalyMinEvents = ANOMALY_MIN_EVENTS,
                            anomalySpikeFactor = ANOMALY_SPIKE_FACTOR,
                        ).copy(purgedEvents = purged)

                summaryRepository.save(report.toSummary(nowMillis))

                // Backfill bounded gaps caused by Doze/OEM scheduling. Newest missing days are most
                // useful to the user and each run is capped to keep background work battery-friendly.
                val completedDay =
                    Instant
                        .ofEpochMilli(nowMillis)
                        .atZone(zoneId)
                        .toLocalDate()
                        .minusDays(1)
                val oldestRetainedDay = completedDay.minusDays((eventRetentionDays - 1).coerceAtLeast(0))
                val oldestEventDay =
                    eventRepository
                        .postedAtBounds()
                        ?.oldestPostedAtMillis
                        ?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
                        ?.takeIf { !it.isAfter(completedDay) }
                        ?: completedDay
                val firstCandidateDay = maxOf(oldestRetainedDay, oldestEventDay)
                val existingDays =
                    dailyInsightRepository.existingEpochDaysBetween(
                        firstCandidateDay.toEpochDay(),
                        completedDay.toEpochDay(),
                    )
                missingDays(firstCandidateDay, completedDay, existingDays).forEach { day ->
                    dailyInsightRepository.aggregateAndStore(
                        epochDay = day.toEpochDay(),
                        startMillis = day.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                        endMillis =
                            day
                                .plusDays(1)
                                .atStartOfDay(zoneId)
                                .toInstant()
                                .toEpochMilli(),
                        generatedAtMillis = nowMillis,
                        topRules = RULE_BREAKDOWN_LIMIT,
                    )
                }
                // Keep the history table bounded (the event log is already purged above).
                dailyInsightRepository.purgeOlderThan(
                    completedDay.minusDays((dailyRetentionDays - 1).coerceAtLeast(0)).toEpochDay(),
                )

                // Size guard after every aggregation: retain only the newest raw rows without
                // undercounting the day that was just summarized.
                eventRepository.trimToMostRecent(MAX_EVENTS)
                eventRepository.trimDecisionTracesToMostRecent(MAX_TRACE_EVENTS)

                report
            }.fold(
                onSuccess = { DataResult.Success(it) },
                onFailure = { DataResult.Failure(it) },
            )

        private fun InsightsReport.toSummary(nowMillis: Long): InsightsSummary {
            val mostMuted = topMutedApps.firstOrNull()
            return InsightsSummary(
                generatedAtMillis = nowMillis,
                mostMutedPackage = mostMuted?.packageName,
                mostMutedCount = mostMuted?.count ?: 0,
                anomalyCount = anomalies.size,
            )
        }

        private fun missingDays(
            start: LocalDate,
            endInclusive: LocalDate,
            existingEpochDays: Set<Long>,
        ): List<LocalDate> =
            generateSequence(start) { current ->
                current.plusDays(1).takeUnless { it.isAfter(endInclusive) }
            }.filterNot { it.toEpochDay() in existingEpochDays }
                .toList()
                .takeLast(MAX_BACKFILL_DAYS)

        private companion object {
            const val DAY_MILLIS = 24L * 60 * 60 * 1000
            const val MAX_EVENTS = 10_000
            const val MAX_TRACE_EVENTS = 1_000
            const val MAX_BACKFILL_DAYS = 7
            const val RULE_BREAKDOWN_LIMIT = 50
            const val WINDOW_DAYS = 7L
            const val TOP_N = 5
            const val ANOMALY_MIN_EVENTS = 5
            const val ANOMALY_SPIKE_FACTOR = 2
        }
    }
