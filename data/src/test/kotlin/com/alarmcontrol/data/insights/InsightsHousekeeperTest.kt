package com.alarmcontrol.data.insights

import com.alarmcontrol.core.filtering.ActionKind
import com.alarmcontrol.core.filtering.ActiveRateOccurrence
import com.alarmcontrol.core.filtering.NotificationContent
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.NotificationEventRepository
import com.alarmcontrol.core.filtering.NotificationEventTimeBounds
import com.alarmcontrol.core.filtering.RateListenerKeyDigest
import com.alarmcontrol.core.filtering.RateOccurrenceId
import com.alarmcontrol.core.filtering.RateOccurrencePersistenceFailure
import com.alarmcontrol.core.filtering.RateOccurrencePersistenceResult
import com.alarmcontrol.core.filtering.RateOccurrenceRepository
import com.alarmcontrol.core.filtering.RateOccurrenceSeed
import com.alarmcontrol.core.filtering.RecordedRateOccurrence
import com.alarmcontrol.core.insights.ActionBreakdown
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.insights.DailyInsightRepository
import com.alarmcontrol.core.insights.InsightsSummary
import com.alarmcontrol.core.insights.InsightsSummaryRepository
import com.alarmcontrol.core.privacy.ClearedDataCounts
import com.alarmcontrol.core.privacy.LocalDataRepository
import com.alarmcontrol.core.privacy.LocalDataResetWriteFence
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.core.settings.MaintenanceSettingsSnapshot
import com.alarmcontrol.core.settings.RetentionDefaults
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.core.settings.SettingsSnapshot
import com.alarmcontrol.data.security.MaintenancePolicyAccessGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

private const val DAY = 24L * 60 * 60 * 1000

class InsightsHousekeeperTest {
    private val now = Instant.parse("2024-01-15T12:00:00Z").toEpochMilli()

    private fun housekeeper(
        events: NotificationEventRepository = RecordingEventRepository(),
        summary: InsightsSummaryRepository = RecordingSummaryRepository(),
        daily: DailyInsightRepository = RecordingDailyInsightRepository(),
        localData: LocalDataRepository = RecordingLocalDataRepository(),
        rateOccurrences: RateOccurrenceRepository = RecordingRateOccurrenceRepository(),
        eventDays: Int = 30,
        insightDays: Int = 365,
        contentEnabled: Boolean = false,
        contentDays: Int = RetentionDefaults.ENCRYPTED_CONTENT_DAYS,
        excludedPackages: Set<String> = emptySet(),
        maintenancePolicyAccessGuard: MaintenancePolicyAccessGuard = MaintenancePolicyAccessGuard(),
        localDataResetWriteFence: LocalDataResetWriteFence = LocalDataResetWriteFence(),
    ) = InsightsHousekeeper(
        events,
        summary,
        daily,
        RetentionSettings(eventDays, insightDays, contentEnabled, excludedPackages, contentDays),
        localData,
        rateOccurrences,
        maintenancePolicyAccessGuard,
        localDataResetWriteFence,
    )

    @Test
    fun `purges events older than the 30-day retention window`() =
        runTest {
            val events = RecordingEventRepository(purgeCount = 7)

            housekeeper(events = events).run(now)

            assertEquals(now - 30 * DAY, events.purgeCutoff)
        }

    @Test
    fun `purges durable rate metadata outside the maximum window`() =
        runTest {
            val rateOccurrences = RecordingRateOccurrenceRepository()

            val result = housekeeper(rateOccurrences = rateOccurrences).run(now)

            assertTrue(result is DataResult.Success)
            assertEquals(now, rateOccurrences.purgeNowMillis)
        }

    @Test
    fun `rate metadata purge failure makes housekeeping retryable`() =
        runTest {
            val events = RecordingEventRepository()
            val rateOccurrences =
                RecordingRateOccurrenceRepository(
                    purgeResult =
                        RateOccurrencePersistenceResult.Unavailable(
                            RateOccurrencePersistenceFailure.PERSISTENCE_UNAVAILABLE,
                        ),
                )

            val result =
                housekeeper(
                    events = events,
                    rateOccurrences = rateOccurrences,
                ).run(now)

            assertTrue(result is DataResult.Failure)
            assertEquals(now, rateOccurrences.purgeNowMillis)
            assertEquals(null, events.purgeCutoff)
        }

    @Test
    fun `purges encrypted content after the configured period while retaining event metadata`() =
        runTest {
            val events = RecordingEventRepository()

            housekeeper(events = events, contentDays = 14).run(now)

            assertEquals(now - 14 * DAY, events.encryptedContentPurgeCutoff)
            assertEquals(now - 30 * DAY, events.purgeCutoff)
        }

    @Test
    fun `reconciles encrypted content policy before retention work`() =
        runTest {
            val localData = RecordingLocalDataRepository()

            housekeeper(localData = localData).run(now)

            assertEquals(1, localData.reconcileContentPolicyCalls)
        }

    @Test
    fun `DataStore failure aborts every destructive maintenance operation`() =
        runTest {
            val events = RecordingEventRepository()
            val daily = RecordingDailyInsightRepository()
            val localData = RecordingLocalDataRepository()
            val summary = RecordingSummaryRepository()
            val rateOccurrences = RecordingRateOccurrenceRepository()
            val failingSettings =
                object : SettingsRepository by RetentionSettings(30, 365, false, emptySet()) {
                    override suspend fun maintenanceSnapshot(): MaintenanceSettingsSnapshot =
                        throw IOException("unreadable")
                }
            val target =
                InsightsHousekeeper(
                    events,
                    summary,
                    daily,
                    failingSettings,
                    localData,
                    rateOccurrences,
                )

            val result = target.run(now)

            assertTrue(result is DataResult.Failure)
            assertEquals(0, localData.reconcileContentPolicyCalls)
            assertEquals(null, rateOccurrences.purgeNowMillis)
            assertEquals(null, events.encryptedContentPurgeCutoff)
            assertEquals(null, events.purgeCutoff)
            assertEquals(null, events.trimMax)
            assertEquals(null, events.traceTrimMax)
            assertTrue(daily.calls.isEmpty())
            assertEquals(null, daily.purgeCutoff)
            assertEquals(null, summary.saved)
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

                    override suspend fun purgeEventsOlderThan(
                        cutoffMillis: Long,
                        legacyZoneId: ZoneId,
                    ): Int = error("db down")
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `completed retention update cannot be followed by cleanup using the older policy`() =
        runTest {
            val operations = mutableListOf<String>()
            val events = RecordingEventRepository(operations = operations)
            val policyGuard = MaintenancePolicyAccessGuard()
            val snapshotStarted = CompletableDeferred<Unit>()
            val releaseSnapshot = CompletableDeferred<Unit>()
            var eventDays = 30
            val settings =
                object : SettingsRepository by RetentionSettings(30, 365, false, emptySet()) {
                    override suspend fun maintenanceSnapshot(): MaintenanceSettingsSnapshot {
                        snapshotStarted.complete(Unit)
                        releaseSnapshot.await()
                        return MaintenanceSettingsSnapshot(eventRetentionDays = eventDays)
                    }

                    override suspend fun setEventRetentionDays(days: Int) {
                        policyGuard.withLock {
                            eventDays = days
                            operations += "set-retention:$days"
                        }
                    }
                }
            val target =
                InsightsHousekeeper(
                    events,
                    RecordingSummaryRepository(),
                    RecordingDailyInsightRepository(),
                    settings,
                    RecordingLocalDataRepository(),
                    RecordingRateOccurrenceRepository(),
                    policyGuard,
                )

            val maintenance = async { target.run(now) }
            snapshotStarted.await()
            val update = async { settings.setEventRetentionDays(365) }
            runCurrent()

            assertFalse(update.isCompleted)
            releaseSnapshot.complete(Unit)
            advanceUntilIdle()

            assertTrue(maintenance.await() is DataResult.Success)
            update.await()
            assertTrue(operations.indexOf("purge") < operations.indexOf("set-retention:365"))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `maintenance waits for an earlier retention update and uses the new cutoff`() =
        runTest {
            val events = RecordingEventRepository()
            val policyGuard = MaintenancePolicyAccessGuard()
            val updateStarted = CompletableDeferred<Unit>()
            val releaseUpdate = CompletableDeferred<Unit>()
            var eventDays = 30
            val settings =
                object : SettingsRepository by RetentionSettings(30, 365, false, emptySet()) {
                    override suspend fun maintenanceSnapshot(): MaintenanceSettingsSnapshot =
                        MaintenanceSettingsSnapshot(eventRetentionDays = eventDays)

                    override suspend fun setEventRetentionDays(days: Int) {
                        policyGuard.withLock {
                            updateStarted.complete(Unit)
                            releaseUpdate.await()
                            eventDays = days
                        }
                    }
                }
            val target =
                InsightsHousekeeper(
                    events,
                    RecordingSummaryRepository(),
                    RecordingDailyInsightRepository(),
                    settings,
                    RecordingLocalDataRepository(),
                    RecordingRateOccurrenceRepository(),
                    policyGuard,
                )

            val update = async { settings.setEventRetentionDays(365) }
            updateStarted.await()
            val maintenance = async { target.run(now) }
            runCurrent()

            assertEquals(null, events.purgeCutoff)
            releaseUpdate.complete(Unit)
            advanceUntilIdle()

            update.await()
            assertTrue(maintenance.await() is DataResult.Success)
            assertEquals(now - 365 * DAY, events.purgeCutoff)
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
                    override suspend fun reconcileStoredNotificationContentPolicy(): ClearedDataCounts {
                        cleanupCalls += 1
                        if (cleanupCalls == 1) releaseFirstRun.await()
                        return ClearedDataCounts()
                    }

                    override suspend fun reconcileStoredNotificationContentPolicy(
                        policy: MaintenanceSettingsSnapshot,
                    ): ClearedDataCounts = reconcileStoredNotificationContentPolicy()
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `clear cannot finish before a stale housekeeping summary is saved`() =
        runTest {
            val maintenanceGuard = MaintenancePolicyAccessGuard()
            val summary = BlockingSummaryRepository()
            val events =
                RecordingEventRepository(
                    recent = mapOf("com.example.pre-clear" to 10),
                    recentStart = now - 7 * DAY,
                    recentEnd = now,
                )
            val target =
                housekeeper(
                    events = events,
                    summary = summary,
                    maintenancePolicyAccessGuard = maintenanceGuard,
                )
            val housekeeping = async { target.run(now) }
            summary.saveStarted.await()

            val clear =
                async {
                    maintenanceGuard.withLock {
                        summary.clear()
                    }
                }
            runCurrent()

            assertFalse(clear.isCompleted)

            summary.releaseSave.complete(Unit)
            advanceUntilIdle()
            assertTrue(housekeeping.await() is DataResult.Success)
            clear.await()
            assertEquals(null, summary.summary.first())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `housekeeping queued before reset exits without recreating local data`() =
        runTest {
            val maintenanceGuard = MaintenancePolicyAccessGuard()
            val resetFence = LocalDataResetWriteFence()
            val guardHeld = CompletableDeferred<Unit>()
            val releaseGuard = CompletableDeferred<Unit>()
            val events = RecordingEventRepository()
            val holder =
                async {
                    maintenanceGuard.withLock {
                        guardHeld.complete(Unit)
                        releaseGuard.await()
                    }
                }
            guardHeld.await()
            val queued =
                async {
                    housekeeper(
                        events = events,
                        maintenancePolicyAccessGuard = maintenanceGuard,
                        localDataResetWriteFence = resetFence,
                    ).run(now)
                }
            runCurrent()

            resetFence.resetAndAdvanceOnCommit { onCommitted -> onCommitted() }
            releaseGuard.complete(Unit)

            assertTrue(queued.await() is DataResult.Failure)
            holder.await()
            assertEquals(null, events.purgeCutoff)
            assertEquals(null, events.trimMax)
        }
}

private class RecordingRateOccurrenceRepository(
    private val purgeResult: RateOccurrencePersistenceResult<Int> =
        RateOccurrencePersistenceResult.Success(0),
) : RateOccurrenceRepository {
    var purgeNowMillis: Long? = null
        private set

    override suspend fun purgeExpiredHistory(nowMillis: Long): RateOccurrencePersistenceResult<Int> {
        purgeNowMillis = nowMillis
        return purgeResult
    }

    override suspend fun loadSeed(
        sinceMillis: Long,
        nowMillis: Long,
    ): RateOccurrenceSeed = error("unused")

    override suspend fun activeOccurrences(): RateOccurrencePersistenceResult<List<ActiveRateOccurrence>> =
        error("unused")

    override suspend fun activeOccurrence(
        listenerKeyDigest: RateListenerKeyDigest,
    ): RateOccurrencePersistenceResult<ActiveRateOccurrence?> = error("unused")

    override suspend fun recordPost(
        listenerKeyDigest: RateListenerKeyDigest,
        candidateOccurrenceId: RateOccurrenceId,
        packageName: String,
        channelId: String?,
        postedAtMillis: Long,
    ): RateOccurrencePersistenceResult<RecordedRateOccurrence> = error("unused")

    override suspend fun deleteActiveOccurrence(
        listenerKeyDigest: RateListenerKeyDigest,
        occurrenceId: RateOccurrenceId,
        removedPostTimeMillis: Long,
    ): RateOccurrencePersistenceResult<Boolean> = error("unused")

    override suspend fun extendIncompleteWindowFrom(anchorMillis: Long): RateOccurrencePersistenceResult<Long> =
        error("unused")
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

    override suspend fun undo(eventId: String): Boolean = false

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
    private val contentDays: Int = RetentionDefaults.ENCRYPTED_CONTENT_DAYS,
) : SettingsRepository {
    override val filteringEnabled: Flow<Boolean> = flowOf(true)
    override val semanticClassifierEnabled: Flow<Boolean> = flowOf(true)
    override val llmAnalysisEnabled: Flow<Boolean> = flowOf(false)
    override val llmAutoActionsEnabled: Flow<Boolean> = flowOf(false)
    override val externalAutomationEnabled: Flow<Boolean> = flowOf(false)
    override val externalAutomationToken: Flow<String> = flowOf("")
    override val eventRetentionDays: Flow<Int> = flowOf(eventDays)
    override val dailyInsightRetentionDays: Flow<Int> = flowOf(insightDays)
    override val dynamicColorEnabled: Flow<Boolean> = flowOf(false)
    override val notificationContentStorageEnabled: Flow<Boolean> = flowOf(contentEnabled)
    override val notificationContentRetentionDays: Flow<Int> = flowOf(contentDays)
    override val contentExcludedPackages: Flow<Set<String>> = flowOf(excludedPackages)

    override suspend fun setFilteringEnabled(enabled: Boolean) = Unit

    override suspend fun setSemanticClassifierEnabled(enabled: Boolean) = Unit

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

    override suspend fun setContentPackageExcluded(
        packageName: String,
        excluded: Boolean,
    ) = Unit

    override suspend fun snapshot(): SettingsSnapshot =
        SettingsSnapshot(
            eventRetentionDays = eventDays,
            dailyInsightRetentionDays = insightDays,
            notificationContentRetentionDays = contentDays,
        )

    override suspend fun maintenanceSnapshot(): MaintenanceSettingsSnapshot =
        MaintenanceSettingsSnapshot(
            eventRetentionDays = eventDays,
            dailyInsightRetentionDays = insightDays,
            notificationContentStorageEnabled = notificationContentStorageEnabled.first(),
            notificationContentRetentionDays = contentDays,
            contentExcludedPackages = contentExcludedPackages.first(),
        )

    override suspend fun restore(snapshot: SettingsSnapshot) = Unit

    override suspend fun reset() = Unit
}

private class RecordingLocalDataRepository : LocalDataRepository {
    var reconcileContentPolicyCalls = 0
        private set

    override suspend fun clearActivityHistory(): ClearedDataCounts = ClearedDataCounts()

    override suspend fun clearFeedback(): ClearedDataCounts = ClearedDataCounts()

    override suspend fun clearDailyInsights(): ClearedDataCounts = ClearedDataCounts()

    override suspend fun clearStoredNotificationContent(): ClearedDataCounts = ClearedDataCounts()

    override suspend fun clearStoredNotificationContentForPackage(packageName: String): ClearedDataCounts =
        ClearedDataCounts()

    override suspend fun reconcileStoredNotificationContentPolicy(): ClearedDataCounts {
        reconcileContentPolicyCalls += 1
        return ClearedDataCounts()
    }

    override suspend fun reconcileStoredNotificationContentPolicy(
        policy: MaintenanceSettingsSnapshot,
    ): ClearedDataCounts = reconcileStoredNotificationContentPolicy()

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

private class BlockingSummaryRepository : InsightsSummaryRepository {
    val saveStarted = CompletableDeferred<Unit>()
    val releaseSave = CompletableDeferred<Unit>()
    private val state = MutableStateFlow<InsightsSummary?>(null)
    override val summary: Flow<InsightsSummary?> = state

    override suspend fun save(summary: InsightsSummary) {
        saveStarted.complete(Unit)
        releaseSave.await()
        state.value = summary
    }

    fun clear() {
        state.value = null
    }
}
