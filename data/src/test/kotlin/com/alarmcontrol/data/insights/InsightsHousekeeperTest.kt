package com.alarmcontrol.data.insights

import com.alarmcontrol.core.filtering.ActionKind
import com.alarmcontrol.core.filtering.NotificationContent
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.NotificationEventRepository
import com.alarmcontrol.core.filtering.NotificationEventTimeBounds
import com.alarmcontrol.core.insights.ActionBreakdown
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.insights.DailyInsightRepository
import com.alarmcontrol.core.insights.InsightsSummary
import com.alarmcontrol.core.insights.InsightsSummaryRepository
import com.alarmcontrol.core.privacy.ClearedDataCounts
import com.alarmcontrol.core.privacy.LocalDataRepository
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.core.settings.SettingsSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private const val DAY = 24L * 60 * 60 * 1000

class InsightsHousekeeperTest {
    private val now = Instant.parse("2024-01-15T12:00:00Z").toEpochMilli()

    private fun housekeeper(
        events: NotificationEventRepository = RecordingEventRepository(),
        summary: InsightsSummaryRepository = RecordingSummaryRepository(),
        daily: DailyInsightRepository = RecordingDailyInsightRepository(),
        localData: LocalDataRepository = RecordingLocalDataRepository(),
        eventDays: Int = 30,
        insightDays: Int = 365,
        contentEnabled: Boolean = false,
        excludedPackages: Set<String> = emptySet(),
    ) = InsightsHousekeeper(
        events,
        summary,
        daily,
        RetentionSettings(eventDays, insightDays, contentEnabled, excludedPackages),
        localData,
    )

    @Test
    fun `purges events older than the 30-day retention window`() =
        runTest {
            val events = RecordingEventRepository(purgeCount = 7)

            housekeeper(events = events).run(now)

            assertEquals(now - 30 * DAY, events.purgeCutoff)
        }

    @Test
    fun `purges encrypted content after seven days while retaining event metadata`() =
        runTest {
            val events = RecordingEventRepository()

            housekeeper(events = events).run(now)

            assertEquals(now - 7 * DAY, events.encryptedContentPurgeCutoff)
            assertEquals(now - 30 * DAY, events.purgeCutoff)
        }

    @Test
    fun `retries full encrypted content cleanup while content history is disabled`() =
        runTest {
            val localData = RecordingLocalDataRepository()

            housekeeper(localData = localData, contentEnabled = false).run(now)

            assertEquals(1, localData.clearAllContentCalls)
            assertTrue(localData.clearedPackages.isEmpty())
        }

    @Test
    fun `retries cleanup only for excluded packages while content history is enabled`() =
        runTest {
            val localData = RecordingLocalDataRepository()

            housekeeper(
                localData = localData,
                contentEnabled = true,
                excludedPackages = setOf("com.private", "com.bank"),
            ).run(now)

            assertEquals(0, localData.clearAllContentCalls)
            assertEquals(setOf("com.private", "com.bank"), localData.clearedPackages)
        }

    @Test
    fun `aggregates the recent window and persists the headline summary`() =
        runTest {
            val events =
                RecordingEventRepository(
                    recent = mapOf("com.a" to 9, "com.b" to 3),
                    baseline = mapOf("com.a" to 1),
                    recentStart = now - 7 * DAY,
                    recentEnd = now,
                )
            val summaryRepo = RecordingSummaryRepository()

            val result = housekeeper(events = events, summary = summaryRepo).run(now)

            assertTrue(result is DataResult.Success)
            val report = (result as DataResult.Success).data
            assertEquals(listOf("com.a", "com.b"), report.topMutedApps.map { it.packageName })
            assertEquals(listOf("com.a"), report.anomalies.map { it.packageName }) // 9 >= 5 and 9 >= 2*1

            val saved = summaryRepo.saved!!
            assertEquals("com.a", saved.mostMutedPackage)
            assertEquals(9, saved.mostMutedCount)
            assertEquals(1, saved.anomalyCount)
            assertEquals(now, saved.generatedAtMillis)
        }

    @Test
    fun `reports the purged count in the result`() =
        runTest {
            val result =
                housekeeper(events = RecordingEventRepository(purgeCount = 12)).run(now)

            assertEquals(12, (result as DataResult.Success).data.purgedEvents)
        }

    @Test
    fun `wraps failures in DataResult Failure`() =
        runTest {
            val failing =
                object : NotificationEventRepository by RecordingEventRepository() {
                    override suspend fun purgeEventsOlderThan(cutoffMillis: Long): Int = error("db down")
                }

            val result =
                housekeeper(events = failing).run(now)

            assertTrue(result is DataResult.Failure)
        }

    @Test
    fun `files the previous completed local calendar day`() =
        runTest {
            val daily = RecordingDailyInsightRepository()

            housekeeper(daily = daily).run(now)

            val call = daily.lastCall!!
            assertEquals(Instant.parse("2024-01-14T00:00:00Z").epochSecond / 86_400, call.epochDay)
            assertEquals(Instant.parse("2024-01-14T00:00:00Z").toEpochMilli(), call.startMillis)
            assertEquals(Instant.parse("2024-01-15T00:00:00Z").toEpochMilli(), call.endMillis)
            assertEquals(now, call.generatedAtMillis)
        }

    @Test
    fun `reaggregates the latest completed day even when its rollup already exists`() =
        runTest {
            val completedDay = Instant.parse("2024-01-14T00:00:00Z").epochSecond / 86_400
            val daily = RecordingDailyInsightRepository(existingDays = setOf(completedDay))

            housekeeper(daily = daily).run(now)

            assertEquals(completedDay, daily.lastCall?.epochDay)
        }

    @Test
    fun `purges daily history older than a year`() =
        runTest {
            val daily = RecordingDailyInsightRepository()

            housekeeper(daily = daily).run(now)

            val completedDay = Instant.parse("2024-01-14T00:00:00Z").epochSecond / 86_400
            assertEquals(completedDay - 364, daily.purgeCutoff)
        }

    @Test
    fun `caps the event log at the maximum size`() =
        runTest {
            val events = RecordingEventRepository()

            housekeeper(events = events).run(now)

            assertEquals(10_000, events.trimMax)
            assertEquals(1_000, events.traceTrimMax)
            assertTrue(events.aggregatedBeforeTrim)
        }

    @Test
    fun `uses user configured retention windows`() =
        runTest {
            val events = RecordingEventRepository()
            val daily = RecordingDailyInsightRepository()

            housekeeper(events = events, daily = daily, eventDays = 7, insightDays = 90).run(now)

            assertEquals(now - 7 * DAY, events.purgeCutoff)
            val completedDay = Instant.parse("2024-01-14T00:00:00Z").epochSecond / 86_400
            assertEquals(completedDay - 89, daily.purgeCutoff)
        }

    @Test
    fun `aggregates completed days before raw retention can truncate them`() =
        runTest {
            val operations = mutableListOf<String>()
            val events = RecordingEventRepository(operations = operations)
            val daily = RecordingDailyInsightRepository(operations = operations)

            housekeeper(events = events, daily = daily, eventDays = 1).run(now)

            assertTrue(operations.indexOfFirst { it.startsWith("aggregate:") } < operations.indexOf("purge"))
        }

    @Test
    fun `backfills oldest missing days first while always refreshing yesterday`() =
        runTest {
            val oldest = Instant.parse("2024-01-05T00:00:00Z").toEpochMilli()
            val daily = RecordingDailyInsightRepository()

            housekeeper(
                events = RecordingEventRepository(oldestPostedAtMillis = oldest),
                daily = daily,
            ).run(now)

            val expected =
                listOf(
                    "2024-01-05",
                    "2024-01-06",
                    "2024-01-07",
                    "2024-01-08",
                    "2024-01-09",
                    "2024-01-10",
                    "2024-01-14",
                ).map { Instant.parse("${it}T00:00:00Z").epochSecond / 86_400 }
            assertEquals(expected, daily.calls.map { it.epochDay })
        }

    @Test
    fun `backfill preserves the local post day after a time zone change`() =
        runTest {
            val storedPostDay = Instant.parse("2024-01-05T00:00:00Z").epochSecond / 86_400
            val timestampNowReadsAsNextDay = Instant.parse("2024-01-06T00:30:00Z").toEpochMilli()
            val daily = RecordingDailyInsightRepository()

            housekeeper(
                events =
                    RecordingEventRepository(
                        oldestPostedAtMillis = timestampNowReadsAsNextDay,
                        oldestPostedEpochDay = storedPostDay,
                    ),
                daily = daily,
            ).run(now)

            assertEquals(storedPostDay, daily.calls.first().epochDay)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `serializes overlapping periodic and bootstrap runs`() =
        runTest {
            val releaseFirstRun = CompletableDeferred<Unit>()
            var cleanupCalls = 0
            val blockingLocalData =
                object : LocalDataRepository by RecordingLocalDataRepository() {
                    override suspend fun clearStoredNotificationContent(): ClearedDataCounts {
                        cleanupCalls += 1
                        if (cleanupCalls == 1) releaseFirstRun.await()
                        return ClearedDataCounts()
                    }
                }
            val target = housekeeper(localData = blockingLocalData)

            val first = async { target.run(now) }
            runCurrent()
            val second = async { target.run(now) }
            runCurrent()

            assertEquals(1, cleanupCalls)

            releaseFirstRun.complete(Unit)
            advanceUntilIdle()

            assertTrue(first.await() is DataResult.Success)
            assertTrue(second.await() is DataResult.Success)
            assertEquals(2, cleanupCalls)
        }
}

/** Fake [NotificationEventRepository] that records the purge cutoff and returns canned window counts. */
private class RecordingEventRepository(
    private val purgeCount: Int = 0,
    private val recent: Map<String, Int> = emptyMap(),
    private val baseline: Map<String, Int> = emptyMap(),
    private val recentStart: Long = Long.MIN_VALUE,
    private val recentEnd: Long = Long.MAX_VALUE,
    private val oldestPostedAtMillis: Long? = null,
    private val oldestPostedEpochDay: Long? = null,
    private val operations: MutableList<String>? = null,
) : NotificationEventRepository {
    var purgeCutoff: Long? = null
        private set

    var trimMax: Int? = null
        private set

    var encryptedContentPurgeCutoff: Long? = null
        private set

    var traceTrimMax: Int? = null
        private set

    var aggregatedBeforeTrim: Boolean = false
        private set

    override suspend fun purgeEventsOlderThan(cutoffMillis: Long): Int {
        operations?.add("purge")
        purgeCutoff = cutoffMillis
        return purgeCount
    }

    override suspend fun purgeEncryptedContentOlderThan(cutoffMillis: Long): Int {
        encryptedContentPurgeCutoff = cutoffMillis
        return 0
    }

    override suspend fun trimToMostRecent(max: Int): Int {
        trimMax = max
        return 0
    }

    override suspend fun trimDecisionTracesToMostRecent(max: Int): Int {
        traceTrimMax = max
        return 0
    }

    override suspend fun mutedCountsByPackageBetween(
        startMillis: Long,
        endMillis: Long,
    ): Map<String, Int> {
        if (trimMax == null) aggregatedBeforeTrim = true
        return if (startMillis == recentStart && endMillis == recentEnd) recent else baseline
    }

    // Unused by the housekeeper:
    override suspend fun record(
        event: NotificationEvent,
        content: NotificationContent?,
    ): String = "1"

    override fun observeRecent(limit: Int): Flow<List<NotificationEvent>> = flowOf(emptyList())

    override fun countByActionSince(
        kind: ActionKind,
        sinceMillis: Long,
    ): Flow<Int> = flowOf(0)

    override fun observeActionBreakdownSince(sinceMillis: Long): Flow<ActionBreakdown> = flowOf(ActionBreakdown())

    override fun observeActionBreakdownForDay(
        epochDay: Long,
        legacyStartMillis: Long,
        legacyEndMillis: Long,
    ): Flow<ActionBreakdown> = flowOf(ActionBreakdown())

    override suspend fun undo(eventId: String) = Unit

    override suspend fun rateHistorySince(sinceMillis: Long) =
        emptyList<com.alarmcontrol.core.filtering.NotificationRateEvent>()

    override suspend fun postedAtBounds(): NotificationEventTimeBounds? =
        oldestPostedAtMillis?.let {
            NotificationEventTimeBounds(
                oldestPostedAtMillis = it,
                newestPostedAtMillis = it,
                oldestPostedEpochDay = oldestPostedEpochDay,
                newestPostedEpochDay = oldestPostedEpochDay,
            )
        }
}

/** Captures the window the housekeeper asks the daily-insight repository to aggregate. */
private class RecordingDailyInsightRepository(
    private val existingDays: Set<Long> = emptySet(),
    private val operations: MutableList<String>? = null,
) : DailyInsightRepository {
    data class Call(
        val epochDay: Long,
        val startMillis: Long,
        val endMillis: Long,
        val generatedAtMillis: Long,
        val topRules: Int,
    )

    var lastCall: Call? = null
        private set
    val calls = mutableListOf<Call>()

    var purgeCutoff: Long? = null
        private set

    override suspend fun aggregateAndStore(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
        generatedAtMillis: Long,
        topRules: Int,
    ): DailyInsight {
        val call = Call(epochDay, startMillis, endMillis, generatedAtMillis, topRules)
        lastCall = call
        calls += call
        operations?.add("aggregate:$epochDay")
        return DailyInsight(
            epochDay = epochDay,
            windowStartMillis = startMillis,
            windowEndMillis = endMillis,
            totalNotifications = 0,
            mutedCount = 0,
            topRules = emptyList(),
            categoryBreakdown = emptyList(),
            generatedAtMillis = generatedAtMillis,
        )
    }

    override fun observeRecent(limit: Int): Flow<List<DailyInsight>> = flowOf(emptyList())

    override suspend fun existingEpochDaysBetween(
        startEpochDay: Long,
        endEpochDay: Long,
    ): Set<Long> = existingDays.filterTo(mutableSetOf()) { it in startEpochDay..endEpochDay }

    override suspend fun purgeOlderThan(epochDay: Long): Int {
        purgeCutoff = epochDay
        return 0
    }
}

private class RetentionSettings(
    private val eventDays: Int,
    private val insightDays: Int,
    contentEnabled: Boolean,
    excludedPackages: Set<String>,
) : SettingsRepository {
    override val filteringEnabled: Flow<Boolean> = flowOf(true)
    override val llmAnalysisEnabled: Flow<Boolean> = flowOf(false)
    override val llmAutoActionsEnabled: Flow<Boolean> = flowOf(false)
    override val externalAutomationEnabled: Flow<Boolean> = flowOf(false)
    override val externalAutomationToken: Flow<String> = flowOf("")
    override val eventRetentionDays: Flow<Int> = flowOf(eventDays)
    override val dailyInsightRetentionDays: Flow<Int> = flowOf(insightDays)
    override val dynamicColorEnabled: Flow<Boolean> = flowOf(false)
    override val notificationContentStorageEnabled: Flow<Boolean> = flowOf(contentEnabled)
    override val contentExcludedPackages: Flow<Set<String>> = flowOf(excludedPackages)

    override suspend fun setFilteringEnabled(enabled: Boolean) = Unit

    override suspend fun setLlmAnalysisEnabled(enabled: Boolean) = Unit

    override suspend fun setLlmAutoActionsEnabled(enabled: Boolean) = Unit

    override suspend fun setExternalAutomationEnabled(enabled: Boolean) = Unit

    override suspend fun ensureExternalAutomationToken(): String = ""

    override suspend fun rotateExternalAutomationToken(): String = ""

    override suspend fun setEventRetentionDays(days: Int) = Unit

    override suspend fun setDailyInsightRetentionDays(days: Int) = Unit

    override suspend fun setDynamicColorEnabled(enabled: Boolean) = Unit

    override suspend fun setNotificationContentStorageEnabled(enabled: Boolean) = Unit

    override suspend fun setContentExcludedPackages(packageNames: Set<String>) = Unit

    override suspend fun snapshot(): SettingsSnapshot =
        SettingsSnapshot(eventRetentionDays = eventDays, dailyInsightRetentionDays = insightDays)

    override suspend fun restore(snapshot: SettingsSnapshot) = Unit

    override suspend fun reset() = Unit
}

private class RecordingLocalDataRepository : LocalDataRepository {
    var clearAllContentCalls = 0
        private set
    val clearedPackages = mutableSetOf<String>()

    override suspend fun clearActivityHistory(): ClearedDataCounts = ClearedDataCounts()

    override suspend fun clearFeedback(): ClearedDataCounts = ClearedDataCounts()

    override suspend fun clearDailyInsights(): ClearedDataCounts = ClearedDataCounts()

    override suspend fun clearStoredNotificationContent(): ClearedDataCounts {
        clearAllContentCalls += 1
        return ClearedDataCounts()
    }

    override suspend fun clearStoredNotificationContentForPackage(packageName: String): ClearedDataCounts {
        clearedPackages += packageName
        return ClearedDataCounts()
    }

    override suspend fun clearAllDatabaseData(): ClearedDataCounts = ClearedDataCounts()
}

private class RecordingSummaryRepository : InsightsSummaryRepository {
    var saved: InsightsSummary? = null
        private set

    private val state = MutableStateFlow<InsightsSummary?>(null)
    override val summary: Flow<InsightsSummary?> = state

    override suspend fun save(summary: InsightsSummary) {
        saved = summary
        state.value = summary
    }
}
