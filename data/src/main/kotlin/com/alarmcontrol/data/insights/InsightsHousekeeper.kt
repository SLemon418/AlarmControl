package com.alarmcontrol.data.insights

import com.alarmcontrol.core.filtering.NotificationEventRepository
import com.alarmcontrol.core.insights.DailyInsightRepository
import com.alarmcontrol.core.insights.InsightsAnalyzer
import com.alarmcontrol.core.insights.InsightsReport
import com.alarmcontrol.core.insights.InsightsSummary
import com.alarmcontrol.core.insights.InsightsSummaryRepository
import com.alarmcontrol.core.privacy.LocalDataRepository
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.core.settings.RetentionDefaults
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.data.security.MaintenancePolicyAccessGuard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The actual work behind the periodic insights worker (CLAUDE.md §5), kept here as plain,
 * framework-free logic so it is unit-testable without WorkManager — the `:app` worker is just a thin
 * shell that calls [run]. Everything is local: SQL aggregation + a DataStore write, no network.
 *
 * Each run: (1) removes expired encrypted content, (2) files completed-day rollups before raw-event
 * retention can truncate their oldest day, (3) deletes expired event rows, (4) computes and saves
 * the compact headline, and (5) caps the event table. Both retention deletion and size trimming
 * happen after daily aggregation so a high-volume day is counted before source rows disappear.
 */
@Singleton
class InsightsHousekeeper
    @Inject
    internal constructor(
        private val eventRepository: NotificationEventRepository,
        private val summaryRepository: InsightsSummaryRepository,
        private val dailyInsightRepository: DailyInsightRepository,
        private val settingsRepository: SettingsRepository,
        private val localDataRepository: LocalDataRepository,
        private val maintenancePolicyAccessGuard: MaintenancePolicyAccessGuard =
            MaintenancePolicyAccessGuard(),
    ) {
        private val runMutex = Mutex()

        /** Runs one pass anchored at [nowMillis]; wraps failures but preserves coroutine cancellation. */
        suspend fun run(
            nowMillis: Long,
            zoneId: ZoneId = ZoneOffset.UTC,
        ): DataResult<InsightsReport> =
            runMutex.withLock {
                runCatchingPreservingCancellation {
                    val purged =
                        maintenancePolicyAccessGuard.withLock {
                            val settings = settingsRepository.maintenanceSnapshot()
                            val eventRetentionDays = settings.eventRetentionDays.toLong()
                            val dailyRetentionDays = settings.dailyInsightRetentionDays.toLong()
                            // Retry privacy cleanup after an earlier UI-triggered deletion failure.
                            localDataRepository.reconcileStoredNotificationContentPolicy(settings)
                            eventRepository.purgeEncryptedContentOlderThan(
                                nowMillis - RetentionDefaults.ENCRYPTED_CONTENT_DAYS * DAY_MILLIS,
                            )
                            aggregateDailyHistory(
                                nowMillis = nowMillis,
                                zoneId = zoneId,
                                eventRetentionDays = eventRetentionDays,
                                dailyRetentionDays = dailyRetentionDays,
                            )
                            eventRepository.purgeEventsOlderThan(
                                nowMillis - eventRetentionDays * DAY_MILLIS,
                            )
                        }

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

                    // Size guard after every aggregation: retain only the newest raw rows without
                    // undercounting the day that was just summarized.
                    eventRepository.trimToMostRecent(MAX_EVENTS)
                    eventRepository.trimDecisionTracesToMostRecent(MAX_TRACE_EVENTS)

                    report
                }.fold(
                    onSuccess = { DataResult.Success(it) },
                    onFailure = { DataResult.Failure(it) },
                )
            }

        private suspend fun aggregateDailyHistory(
            nowMillis: Long,
            zoneId: ZoneId,
            eventRetentionDays: Long,
            dailyRetentionDays: Long,
        ) {
            val completedDay =
                Instant
                    .ofEpochMilli(nowMillis)
                    .atZone(zoneId)
                    .toLocalDate()
                    .minusDays(1)
            val oldestRetainedDay = completedDay.minusDays((eventRetentionDays - 1).coerceAtLeast(0))
            val bounds = eventRepository.postedAtBounds()
            val oldestTimestampDay =
                bounds
                    ?.oldestPostedAtMillis
                    ?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
            val oldestStoredDay =
                bounds
                    ?.oldestPostedEpochDay
                    ?.let(::localDateOrNull)
            val oldestEventDay =
                listOfNotNull(oldestTimestampDay, oldestStoredDay)
                    .filterNot { it.isAfter(completedDay) }
                    .minOrNull()
                    ?: completedDay
            val firstCandidateDay = maxOf(oldestRetainedDay, oldestEventDay)
            val existingDays =
                dailyInsightRepository.existingEpochDaysBetween(
                    firstCandidateDay.toEpochDay(),
                    completedDay.toEpochDay(),
                )
            val backfillDays =
                missingDays(
                    start = firstCandidateDay,
                    endInclusive = completedDay.minusDays(1),
                    existingEpochDays = existingDays,
                    limit = MAX_BACKFILL_DAYS - 1,
                )
            (backfillDays + completedDay).forEach { day ->
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
            dailyInsightRepository.purgeOlderThan(
                completedDay.minusDays((dailyRetentionDays - 1).coerceAtLeast(0)).toEpochDay(),
            )
        }

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
            limit: Int,
        ): List<LocalDate> =
            if (start.isAfter(endInclusive)) {
                emptyList()
            } else {
                generateSequence(start) { current ->
                    current.plusDays(1).takeUnless { it.isAfter(endInclusive) }
                }.filterNot { it.toEpochDay() in existingEpochDays }
                    .take(limit)
                    .toList()
            }

        private fun localDateOrNull(epochDay: Long): LocalDate? =
            runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull()

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
