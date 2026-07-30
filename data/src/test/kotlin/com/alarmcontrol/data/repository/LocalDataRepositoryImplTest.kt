package com.alarmcontrol.data.repository

import com.alarmcontrol.core.automation.AutomationAuditEntry
import com.alarmcontrol.core.automation.AutomationOperation
import com.alarmcontrol.core.automation.AutomationOutcome
import com.alarmcontrol.core.automation.AutomationSource
import com.alarmcontrol.core.automation.AutomationTarget
import com.alarmcontrol.core.feedback.CategoryFeedback
import com.alarmcontrol.core.filtering.NotificationHistoryWriteFence
import com.alarmcontrol.core.filtering.RateOccurrenceLifecycleGate
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.privacy.DailyInsightWriteFence
import com.alarmcontrol.core.privacy.FeedbackWriteFence
import com.alarmcontrol.core.privacy.LocalDataResetWriteFence
import com.alarmcontrol.core.privacy.StaleLocalDataWriteException
import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.core.settings.ExternalAutomationAuthorizationFence
import com.alarmcontrol.core.settings.FilteringActionGate
import com.alarmcontrol.core.settings.MaintenanceSettingsSnapshot
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.data.db.TransactionRunner
import com.alarmcontrol.data.db.entity.AdFeedbackPriorEntity
import com.alarmcontrol.data.db.entity.CategoryFeedbackEntity
import com.alarmcontrol.data.db.entity.DailyInsightEntity
import com.alarmcontrol.data.db.entity.LlmObservationEntity
import com.alarmcontrol.data.db.entity.LocalSemanticFeedbackEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity
import com.alarmcontrol.data.db.entity.PendingNotificationActionEntity
import com.alarmcontrol.data.db.entity.RuleEntity
import com.alarmcontrol.data.db.entity.RuleSuggestionDismissalEntity
import com.alarmcontrol.data.db.entity.SemanticFeedbackPriorEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.security.MaintenancePolicyAccessGuard
import com.alarmcontrol.data.security.NotificationContentAccessGuard
import com.alarmcontrol.data.security.RateOccurrenceDataCleaner
import com.alarmcontrol.data.security.StoredNotificationContentCleaner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@Suppress("LargeClass") // Keeps destructive-clear lock ordering and failure boundaries in one fixture.
@OptIn(ExperimentalCoroutinesApi::class)
class LocalDataRepositoryImplTest {
    private val rules = FakeRuleDao()
    private val events = FakeNotificationEventDao()
    private val pendingActions = FakePendingNotificationActionDao()
    private val feedback = FakeCategoryFeedbackDao()
    private val insights = FakeDailyInsightDao()
    private val profiles = FakeProfileDao()
    private val llmObservations = FakeLlmObservationDao()
    private val automationAudit = FakeAutomationAuditDao()
    private val suggestions = FakeRuleSuggestionDao()
    private val contentCipher = FakeNotificationContentCipher()
    private val contentAccessGuard = NotificationContentAccessGuard()
    private val maintenancePolicyAccessGuard = MaintenancePolicyAccessGuard()
    private val rateOccurrenceDataCleaner = FakeRateOccurrenceDataCleaner()
    private val settings =
        FakeContentSettingsRepository(
            contentEnabled = true,
            contentAccessGuard = contentAccessGuard,
            maintenancePolicyAccessGuard = maintenancePolicyAccessGuard,
        )
    private val repository = createRepository(settings)

    private fun createRepository(
        settingsRepository: SettingsRepository,
        filteringActionGate: FilteringActionGate = FilteringActionGate(),
        rateOccurrenceLifecycleGate: RateOccurrenceLifecycleGate = RateOccurrenceLifecycleGate(),
        transactionRunner: TransactionRunner = ImmediateTransactionRunner(),
        notificationHistoryWriteFence: NotificationHistoryWriteFence = NotificationHistoryWriteFence(),
        localDataResetWriteFence: LocalDataResetWriteFence = LocalDataResetWriteFence(),
        feedbackWriteFence: FeedbackWriteFence = FeedbackWriteFence(),
        dailyInsightWriteFence: DailyInsightWriteFence = DailyInsightWriteFence(),
        clock: Clock = Clock.systemDefaultZone(),
        externalAutomationAuthorizationFence: ExternalAutomationAuthorizationFence =
            ExternalAutomationAuthorizationFence(),
    ): LocalDataRepositoryImpl {
        val storedNotificationContentCleaner =
            StoredNotificationContentCleaner(transactionRunner, events, pendingActions, contentCipher)
        return LocalDataRepositoryImpl(
            transactionRunner,
            rules,
            events,
            pendingActions,
            feedback,
            insights,
            profiles,
            llmObservations,
            automationAudit,
            suggestions,
            settingsRepository,
            contentCipher,
            storedNotificationContentCleaner,
            contentAccessGuard,
            maintenancePolicyAccessGuard,
            filteringActionGate,
            rateOccurrenceLifecycleGate,
            rateOccurrenceDataCleaner,
            notificationHistoryWriteFence,
            localDataResetWriteFence,
            feedbackWriteFence,
            dailyInsightWriteFence,
            clock,
            externalAutomationAuthorizationFence,
        )
    }

    @Test
    fun `clear all reports and removes every database category`() =
        runTest {
            rules.insertRule(
                RuleEntity(
                    name = "rule",
                    enabled = true,
                    priority = 0,
                    action = StoredRuleAction.CANCEL,
                    createdAtMillis = 0,
                    updatedAtMillis = 0,
                ),
            )
            events.insert(sampleEvent())
            feedback.insert(
                CategoryFeedbackEntity(
                    packageName = "com.example",
                    predictedLabel = "promotion",
                    correctedLabel = "social",
                    recordedAtMillis = 1L,
                ),
            )
            insights.upsertInsight(
                DailyInsightEntity(
                    epochDay = 1,
                    windowStartMillis = 0,
                    windowEndMillis = 1,
                    totalNotifications = 1,
                    mutedCount = 1,
                    generatedAtMillis = 1,
                ),
            )
            insights.seedSourceGap(1)
            profiles.store(
                com.alarmcontrol.data.db.entity.FilteringProfileEntity(
                    name = "Focus",
                    createdAtMillis = 1,
                    updatedAtMillis = 1,
                ),
                setOf(1),
            )
            suggestions.dismiss(RuleSuggestionDismissalEntity("channel:com.example:offers", 1L))

            val counts = repository.clearAllDatabaseData()

            assertEquals(1, counts.rules)
            assertEquals(1, counts.profiles)
            assertEquals(1, counts.events)
            assertEquals(1, counts.feedback)
            assertEquals(1, counts.insightDays)
            assertEquals(0, counts.encryptedContents)
            assertEquals(true, contentCipher.keyDeleted)
            assertEquals(true, rateOccurrenceDataCleaner.databaseCleared)
            assertEquals(true, rateOccurrenceDataCleaner.keyDeleted)
            assertEquals(0, rules.countAll())
            assertEquals(0, events.countAll())
            assertEquals(0, feedback.countAll())
            assertEquals(0, insights.countAll())
            assertEquals(emptyList<Long>(), insights.observeSourceGapDaysBetween(0, 10).first())
            assertEquals(0, profiles.countAll())
            assertEquals(emptyList<String>(), suggestions.observeDismissedKeys().first())
            assertEquals(1, settings.resetWhileMaintenanceLockedCalls)
            assertFalse(settings.notificationContentStorageEnabled.first())
        }

    @Test
    fun `clear all deletes durable staged and armed action records`() =
        runTest {
            pendingActions.insertAction(pendingAction("unarmed"))
            pendingActions.insertAction(pendingAction("armed", armed = true))

            repository.clearAllDatabaseData()

            assertEquals(0, pendingActions.actionCount())
        }

    @Test
    fun `clear all rejects queued profile and automation audit writes from the old epoch`() =
        runTest {
            val resetFence = LocalDataResetWriteFence()
            val resetFenceHeld = CompletableDeferred<Unit>()
            val releaseResetFence = CompletableDeferred<Unit>()
            val target =
                createRepository(
                    settingsRepository = settings,
                    localDataResetWriteFence = resetFence,
                )
            val profileRepository = ProfileRepositoryImpl(profiles, resetFence)
            val auditRepository = AutomationAuditRepositoryImpl(automationAudit, resetFence)
            val holder =
                async {
                    resetFence.writeIfCurrent(resetFence.captureEpoch()) {
                        resetFenceHeld.complete(Unit)
                        releaseResetFence.await()
                        Unit
                    }
                }
            resetFenceHeld.await()
            val clearing = async { target.clearAllDatabaseData() }
            runCurrent()
            val profileSave =
                async {
                    runCatching {
                        profileRepository.save(
                            FilteringProfile(name = "stale", ruleIds = emptySet()),
                        )
                    }
                }
            val auditRecord =
                async {
                    runCatching {
                        auditRepository.record(
                            AutomationAuditEntry(
                                requestedAtMillis = 1,
                                source = AutomationSource.EXTERNAL,
                                operation = AutomationOperation.ENABLE,
                                target = AutomationTarget.PROFILE,
                                outcome = AutomationOutcome.APPLIED,
                                changedCount = 1,
                            ),
                        )
                    }
                }
            runCurrent()

            releaseResetFence.complete(Unit)
            holder.await()
            clearing.await()
            val auditFailure = auditRecord.await().exceptionOrNull()
            val saveFailure = profileSave.await().exceptionOrNull()

            assertTrue(saveFailure is StaleLocalDataWriteException)
            assertTrue(auditFailure is StaleLocalDataWriteException)
            assertTrue(profiles.observeProfiles().first().isEmpty())
            assertTrue(automationAudit.observeRecent(10).first().isEmpty())
        }

    @Test
    fun `clear all requests a fresh listener rule snapshot after commit`() =
        runTest {
            val gate = FilteringActionGate()
            val repository = createRepository(settings, gate)

            repository.clearAllDatabaseData()

            assertEquals(1L, gate.ruleRefreshRequests.value)
        }

    @Test
    fun `clear activity deletes durable staged and armed action records`() =
        runTest {
            val lifecycleGate = RateOccurrenceLifecycleGate()
            val target =
                createRepository(
                    settingsRepository = settings,
                    rateOccurrenceLifecycleGate = lifecycleGate,
                )
            pendingActions.insertAction(pendingAction("unarmed"))
            pendingActions.insertAction(pendingAction("armed", armed = true))

            target.clearActivityHistory()

            assertEquals(0, pendingActions.actionCount())
            assertEquals(1L, lifecycleGate.currentResetMarker.generation)
        }

    @Test
    fun `clear activity uses one reset anchor for Room and the lifecycle marker`() =
        runTest {
            val lifecycleGate = RateOccurrenceLifecycleGate()
            val rollingBackClock = RollingBackClock(initialMillis = 2_000L, rolledBackMillis = 1_000L)
            val target =
                createRepository(
                    settingsRepository = settings,
                    rateOccurrenceLifecycleGate = lifecycleGate,
                    clock = rollingBackClock,
                )

            target.clearActivityHistory()

            assertEquals(1, rollingBackClock.millisCalls)
            assertEquals(2_000L, rateOccurrenceDataCleaner.databaseClearAtMillis)
            assertEquals(2_000L, lifecycleGate.currentResetMarker.resetAtMillis)
        }

    @Test
    fun `clear activity marks source days from armed pending decisions only`() =
        runTest {
            val zone = ZoneOffset.ofHours(9)
            val legacyPost = Instant.parse("2026-07-30T16:00:00Z")
            pendingActions.insertAction(
                pendingAction(
                    token = "explicit-day",
                    armed = true,
                    postedAtMillis = 1,
                    postedEpochDay = 20_000,
                ),
            )
            pendingActions.insertAction(
                pendingAction(
                    token = "legacy-day",
                    armed = true,
                    postedAtMillis = legacyPost.toEpochMilli(),
                    postedEpochDay = null,
                ),
            )
            pendingActions.insertAction(
                pendingAction(
                    token = "unarmed",
                    postedAtMillis = 1,
                    postedEpochDay = 30_000,
                ),
            )
            val target =
                createRepository(
                    settingsRepository = settings,
                    clock = Clock.fixed(legacyPost, zone),
                )

            target.clearActivityHistory()

            assertEquals(
                setOf(
                    20_000L,
                    legacyPost.atZone(zone).toLocalDate().toEpochDay(),
                ),
                events.sourceGapDays,
            )
        }

    @Test
    fun `clear activity rejects a queued category correction tied to old history`() =
        runTest {
            val historyFence = NotificationHistoryWriteFence()
            val historyFenceHeld = CompletableDeferred<Unit>()
            val releaseHistoryFence = CompletableDeferred<Unit>()
            val target =
                createRepository(
                    settingsRepository = settings,
                    notificationHistoryWriteFence = historyFence,
                )
            val feedbackRepository =
                FeedbackRepositoryImpl(
                    feedback,
                    insights,
                    ImmediateTransactionRunner(),
                    notificationHistoryWriteFence = historyFence,
                )
            val holder =
                async {
                    historyFence.writeIfCurrent(historyFence.captureEpoch()) {
                        historyFenceHeld.complete(Unit)
                        releaseHistoryFence.await()
                        Unit
                    }
                }
            historyFenceHeld.await()
            val clearing = async { target.clearActivityHistory() }
            runCurrent()
            val correction =
                async {
                    runCatching {
                        feedbackRepository.recordCorrection(
                            CategoryFeedback(
                                packageName = "com.example",
                                notificationEventId = "1",
                                predictedLabel = "other",
                                correctedLabel = "social",
                                recordedAtMillis = 1,
                            ),
                        )
                    }
                }
            runCurrent()

            releaseHistoryFence.complete(Unit)
            holder.await()
            clearing.await()

            assertTrue(correction.await().exceptionOrNull() is StaleLocalDataWriteException)
            assertEquals(0, feedback.countAll())
        }

    @Test
    fun `clear feedback rejects queued category and semantic corrections`() =
        runTest {
            val feedbackFence = FeedbackWriteFence()
            val feedbackFenceHeld = CompletableDeferred<Unit>()
            val releaseFeedbackFence = CompletableDeferred<Unit>()
            val target =
                createRepository(
                    settingsRepository = settings,
                    feedbackWriteFence = feedbackFence,
                )
            val categoryRepository =
                FeedbackRepositoryImpl(
                    feedback,
                    insights,
                    ImmediateTransactionRunner(),
                    feedbackWriteFence = feedbackFence,
                )
            val semanticRepository =
                AdFeedbackRepositoryImpl(
                    llmObservations,
                    insights,
                    ImmediateTransactionRunner(),
                    Clock.systemUTC(),
                    feedbackWriteFence = feedbackFence,
                )
            val eventId = events.insert(sampleEvent())
            llmObservations.upsertIfEventExists(
                LlmObservationEntity(
                    notificationEventId = eventId,
                    packageName = "com.example",
                    predictedIsAdvertisement = false,
                    predictedIntent = "OTHER",
                    confidenceScore = 0.8f,
                    analyzedAtMillis = 1,
                ),
            )
            val holder =
                async {
                    feedbackFence.writeIfCurrent(feedbackFence.captureEpoch()) {
                        feedbackFenceHeld.complete(Unit)
                        releaseFeedbackFence.await()
                        Unit
                    }
                }
            feedbackFenceHeld.await()
            val clearing = async { target.clearFeedback() }
            runCurrent()
            val categoryCorrection =
                async {
                    runCatching {
                        categoryRepository.recordCorrection(
                            CategoryFeedback(
                                packageName = "com.example",
                                predictedLabel = "other",
                                correctedLabel = "social",
                                recordedAtMillis = 1,
                            ),
                        )
                    }
                }
            val semanticCorrection =
                async {
                    runCatching {
                        semanticRepository.recordCorrection(
                            eventId.toString(),
                            SemanticIntent.SECURITY,
                        )
                    }
                }
            runCurrent()

            releaseFeedbackFence.complete(Unit)
            holder.await()
            clearing.await()

            assertTrue(categoryCorrection.await().exceptionOrNull() is StaleLocalDataWriteException)
            assertTrue(semanticCorrection.await().exceptionOrNull() is StaleLocalDataWriteException)
            assertEquals(0, feedback.countAll())
            assertNull(
                llmObservations
                    .observeAll()
                    .first()
                    .single()
                    .correctedIntent,
            )
        }

    @Test
    fun `clear daily insights rejects queued aggregation from the old epoch`() =
        runTest {
            val dailyFence = DailyInsightWriteFence()
            val dailyFenceHeld = CompletableDeferred<Unit>()
            val releaseDailyFence = CompletableDeferred<Unit>()
            val target =
                createRepository(
                    settingsRepository = settings,
                    dailyInsightWriteFence = dailyFence,
                )
            val dailyRepository =
                DailyInsightRepositoryImpl(
                    insights,
                    ImmediateTransactionRunner(),
                    dailyInsightWriteFence = dailyFence,
                )
            val holder =
                async {
                    dailyFence.writeIfCurrent(dailyFence.captureEpoch()) {
                        dailyFenceHeld.complete(Unit)
                        releaseDailyFence.await()
                        Unit
                    }
                }
            dailyFenceHeld.await()
            val clearing = async { target.clearDailyInsights() }
            runCurrent()
            val aggregation =
                async {
                    runCatching {
                        dailyRepository.aggregateAndStore(
                            epochDay = 20_000,
                            startMillis = 1,
                            endMillis = 2,
                            generatedAtMillis = 2,
                            topRules = 10,
                        )
                    }
                }
            runCurrent()

            releaseDailyFence.complete(Unit)
            holder.await()
            clearing.await()

            assertTrue(aggregation.await().exceptionOrNull() is StaleLocalDataWriteException)
            assertEquals(0, insights.countAll())
        }

    @Test
    fun `clear activity resets rate occurrence identity and requests a fresh rule snapshot`() =
        runTest {
            events.insert(sampleEvent())
            feedback.insert(
                CategoryFeedbackEntity(
                    notificationEventId = 1,
                    packageName = "com.example",
                    predictedLabel = "promotion",
                    correctedLabel = "social",
                    recordedAtMillis = 1L,
                ),
            )
            val gate = FilteringActionGate()
            val target = createRepository(settings, gate)

            val counts = target.clearActivityHistory()

            assertEquals(1, counts.events)
            assertEquals(1, counts.feedback)
            assertTrue(rateOccurrenceDataCleaner.databaseCleared)
            assertTrue(rateOccurrenceDataCleaner.keyDeleted)
            assertEquals(1L, gate.ruleRefreshRequests.value)
        }

    @Test
    fun `clear activity fences pre-clear persistence while allowing a new notification`() =
        runTest {
            val historyFence = NotificationHistoryWriteFence()
            val staleEpoch = historyFence.captureEpoch()
            events.insert(sampleEvent())
            val target =
                createRepository(
                    settingsRepository = settings,
                    notificationHistoryWriteFence = historyFence,
                )

            target.clearActivityHistory()

            val staleInsert =
                historyFence.writeIfCurrent(staleEpoch) {
                    events.insert(sampleEvent())
                }
            val freshInsert =
                historyFence.writeIfCurrent(historyFence.captureEpoch()) {
                    events.insert(sampleEvent())
                }
            assertNull(staleInsert)
            assertEquals(2L, freshInsert)
            assertEquals(1, events.countAll())
        }

    @Test
    fun `clear activity does not invert history and content lock order`() =
        runTest {
            val historyFence = NotificationHistoryWriteFence()
            val writerMayTakeContentLock = CompletableDeferred<Unit>()
            val historyLockHeld = CompletableDeferred<Unit>()
            val target =
                createRepository(
                    settingsRepository = settings,
                    notificationHistoryWriteFence = historyFence,
                )
            val writer =
                async {
                    historyFence.writeIfCurrent(historyFence.captureEpoch()) {
                        historyLockHeld.complete(Unit)
                        writerMayTakeContentLock.await()
                        contentAccessGuard.withLock { Unit }
                    }
                }
            historyLockHeld.await()
            val deletion = async { target.clearActivityHistory() }
            runCurrent()

            var contentLockWasAvailable = false
            val contentLockProbe =
                async {
                    contentAccessGuard.withLock {
                        contentLockWasAvailable = true
                    }
                }
            runCurrent()

            assertTrue(contentLockWasAvailable)
            contentLockProbe.await()
            writerMayTakeContentLock.complete(Unit)
            writer.await()
            deletion.await()
        }

    @Test
    fun `committed clear all publishes refresh when result delivery is cancelled`() =
        runTest {
            rules.insertRule(
                RuleEntity(
                    name = "rule",
                    enabled = true,
                    priority = 0,
                    action = StoredRuleAction.CANCEL,
                    createdAtMillis = 0,
                    updatedAtMillis = 0,
                ),
            )
            val gate = FilteringActionGate()
            gate.initializeFromPersistedState(true)
            gate.acknowledgeRuleRefresh(gate.ruleRefreshRequests.value)
            val historyFence = NotificationHistoryWriteFence()
            val staleEpoch = historyFence.captureEpoch()
            val commitThenCancel =
                object : TransactionRunner {
                    override suspend fun <T> run(block: suspend () -> T): T = block()

                    override suspend fun <T> runAndNotifyCommit(
                        onCommitted: () -> Unit,
                        block: suspend () -> T,
                    ): T {
                        block()
                        onCommitted()
                        throw CancellationException("cancelled after commit")
                    }
                }
            val repository =
                createRepository(
                    settingsRepository = settings,
                    filteringActionGate = gate,
                    transactionRunner = commitThenCancel,
                    notificationHistoryWriteFence = historyFence,
                )

            val failure = runCatching { repository.clearAllDatabaseData() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(0, rules.countAll())
            assertEquals(1L, gate.ruleRefreshRequests.value)
            assertFalse(gate.runIfAllowed {})
            assertNull(historyFence.writeIfCurrent(staleEpoch) { "stale" })
            assertTrue(contentCipher.keyDeleted)
            assertTrue(rateOccurrenceDataCleaner.keyDeleted)
            assertEquals(1, settings.resetWhileMaintenanceLockedCalls)
        }

    @Test
    fun `key deletion failure after clear commit still fences a stale history writer`() =
        runTest {
            events.insert(sampleEvent())
            val historyFence = NotificationHistoryWriteFence()
            val staleEpoch = historyFence.captureEpoch()
            contentCipher.failKeyDeletion = true
            val target =
                createRepository(
                    settingsRepository = settings,
                    notificationHistoryWriteFence = historyFence,
                )

            val failure = runCatching { target.clearAllDatabaseData() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(0, events.countAll())
            assertNull(
                historyFence.writeIfCurrent(staleEpoch) {
                    events.insert(sampleEvent())
                },
            )
            assertFalse(contentCipher.keyDeleted)
            assertTrue(rateOccurrenceDataCleaner.keyDeleted)
            assertEquals(1, settings.resetWhileMaintenanceLockedCalls)
            assertFalse(settings.notificationContentStorageEnabled.first())
        }

    @Test
    fun `HMAC key failure after clear commit still attempts content and settings cleanup`() =
        runTest {
            events.insert(sampleEvent())
            rateOccurrenceDataCleaner.failKeyDeletion = true
            val target = createRepository(settingsRepository = settings)

            val failure = runCatching { target.clearAllDatabaseData() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(0, events.countAll())
            assertTrue(contentCipher.keyDeleted)
            assertFalse(rateOccurrenceDataCleaner.keyDeleted)
            assertEquals(1, rateOccurrenceDataCleaner.keyDeletionAttempts)
            assertEquals(1, settings.resetWhileMaintenanceLockedCalls)
            assertFalse(settings.notificationContentStorageEnabled.first())
        }

    @Test
    fun `settings reset failure after clear commit does not skip either key cleanup`() =
        runTest {
            events.insert(sampleEvent())
            val failingSettings =
                object : SettingsRepository by settings {
                    override suspend fun resetWhileMaintenanceLocked() {
                        settings.resetWhileMaintenanceLocked()
                        throw IOException("settings reset failed")
                    }
                }
            val target = createRepository(settingsRepository = failingSettings)

            val failure = runCatching { target.clearAllDatabaseData() }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals(0, events.countAll())
            assertTrue(contentCipher.keyDeleted)
            assertTrue(rateOccurrenceDataCleaner.keyDeleted)
            assertEquals(1, settings.resetWhileMaintenanceLockedCalls)
            assertFalse(settings.notificationContentStorageEnabled.first())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `clear all waits for an in-flight rate occurrence operation before resetting key state`() =
        runTest {
            val lifecycleGate = RateOccurrenceLifecycleGate()
            val filteringGate = FilteringActionGate()
            val operationStarted = CompletableDeferred<Unit>()
            val releaseOperation = CompletableDeferred<Unit>()
            val inFlight =
                async {
                    lifecycleGate.withOperation {
                        operationStarted.complete(Unit)
                        releaseOperation.await()
                    }
                }
            operationStarted.await()
            val target =
                createRepository(
                    settingsRepository = settings,
                    filteringActionGate = filteringGate,
                    rateOccurrenceLifecycleGate = lifecycleGate,
                )

            val clear = async { target.clearAllDatabaseData() }
            runCurrent()

            assertFalse(rateOccurrenceDataCleaner.databaseCleared)
            assertFalse(rateOccurrenceDataCleaner.keyDeleted)
            assertEquals(0L, filteringGate.ruleRefreshRequests.value)

            releaseOperation.complete(Unit)
            advanceUntilIdle()

            inFlight.await()
            clear.await()
            assertTrue(rateOccurrenceDataCleaner.databaseCleared)
            assertTrue(rateOccurrenceDataCleaner.keyDeleted)
            assertEquals(1L, filteringGate.ruleRefreshRequests.value)
            assertEquals(1L, lifecycleGate.currentResetMarker.generation)
        }

    @Test
    fun `clear all uses one reset anchor for Room and the lifecycle marker`() =
        runTest {
            val lifecycleGate = RateOccurrenceLifecycleGate()
            val rollingBackClock = RollingBackClock(initialMillis = 4_000L, rolledBackMillis = 3_000L)
            val target =
                createRepository(
                    settingsRepository = settings,
                    rateOccurrenceLifecycleGate = lifecycleGate,
                    clock = rollingBackClock,
                )

            target.clearAllDatabaseData()

            assertEquals(1, rollingBackClock.millisCalls)
            assertEquals(4_000L, rateOccurrenceDataCleaner.databaseClearAtMillis)
            assertEquals(4_000L, lifecycleGate.currentResetMarker.resetAtMillis)
        }

    @Test
    fun `clear all waits for the shared maintenance boundary`() =
        runTest {
            events.insert(sampleEvent())
            val maintenanceHeld = CompletableDeferred<Unit>()
            val releaseMaintenance = CompletableDeferred<Unit>()
            val inFlightMaintenance =
                async {
                    maintenancePolicyAccessGuard.withLock {
                        maintenanceHeld.complete(Unit)
                        releaseMaintenance.await()
                    }
                }
            maintenanceHeld.await()

            val clear = async { repository.clearAllDatabaseData() }
            runCurrent()

            assertEquals(1, events.countAll())

            releaseMaintenance.complete(Unit)
            advanceUntilIdle()
            inFlightMaintenance.await()
            clear.await()
            assertEquals(0, events.countAll())
        }

    @Test
    fun `clear all acquires authorization before the reset fence`() =
        runTest {
            events.insert(sampleEvent())
            val authorizationFence = ExternalAutomationAuthorizationFence()
            val authorizationHeld = CompletableDeferred<Unit>()
            val releaseAuthorization = CompletableDeferred<Unit>()
            val holder =
                async {
                    authorizationFence.withLock {
                        authorizationHeld.complete(Unit)
                        releaseAuthorization.await()
                    }
                }
            authorizationHeld.await()
            val target =
                createRepository(
                    settingsRepository = settings,
                    externalAutomationAuthorizationFence = authorizationFence,
                )

            val clear = async { target.clearAllDatabaseData() }
            runCurrent()

            assertEquals(1, events.countAll())
            releaseAuthorization.complete(Unit)
            clear.await()
            holder.await()
            assertEquals(0, events.countAll())
        }

    @Test
    fun `clearing stored notification content leaves metadata and deletes the key`() =
        runTest {
            events.insertWithTrace(
                sampleEvent().copy(hadEncryptedContent = true),
                emptyList(),
                contentCipher
                    .encrypt("content".encodeToByteArray())
                    .let {
                        com.alarmcontrol.data.db.entity.EncryptedNotificationContentEntity(
                            formatVersion = it.formatVersion,
                            aadId = it.aadId,
                            nonce = it.nonce,
                            ciphertext = it.ciphertext,
                            createdAtMillis = 1,
                        )
                    },
            )

            val counts = repository.clearStoredNotificationContent()

            assertEquals(1, counts.encryptedContents)
            assertEquals(1, events.countAll())
            assertEquals(0, events.countEncryptedContents())
            assertEquals(true, contentCipher.keyDeleted)
        }

    @Test
    fun `clearing one excluded package removes only its ciphertext`() =
        runTest {
            listOf("com.private" to 1L, "com.allowed" to 2L).forEach { (packageName, id) ->
                events.insertWithTrace(
                    sampleEvent().copy(id = id, packageName = packageName, hadEncryptedContent = true),
                    emptyList(),
                    contentCipher
                        .encrypt("$packageName-content".encodeToByteArray())
                        .let {
                            com.alarmcontrol.data.db.entity.EncryptedNotificationContentEntity(
                                formatVersion = it.formatVersion,
                                aadId = it.aadId,
                                nonce = it.nonce,
                                ciphertext = it.ciphertext,
                                createdAtMillis = 1,
                            )
                        },
                )
            }

            val counts = repository.clearStoredNotificationContentForPackage("com.private")

            assertEquals(1, counts.encryptedContents)
            assertEquals(2, events.countAll())
            assertEquals(1, events.countEncryptedContents())
        }

    @Test
    fun `policy reconciliation removes only content forbidden by the current settings`() =
        runTest {
            storeEncryptedContent("com.private", 1L)
            storeEncryptedContent("com.allowed", 2L)
            settings.setContentExcludedPackages(setOf("com.private"))

            val counts = repository.reconcileStoredNotificationContentPolicy()

            assertEquals(1, counts.encryptedContents)
            assertEquals(1, events.countEncryptedContents())

            settings.setNotificationContentStorageEnabled(false)
            val disabledCounts = repository.reconcileStoredNotificationContentPolicy()

            assertEquals(1, disabledCounts.encryptedContents)
            assertEquals(0, events.countEncryptedContents())
            assertEquals(true, contentCipher.keyDeleted)
        }

    @Test
    fun `policy read failure preserves every ciphertext and the encryption key`() =
        runTest {
            storeEncryptedContent("com.allowed", 1L)
            val failingSettings =
                object : SettingsRepository by settings {
                    override suspend fun maintenanceSnapshot(): MaintenanceSettingsSnapshot =
                        throw IOException("unreadable")
                }
            val target = createRepository(failingSettings)

            val error =
                runCatching { target.reconcileStoredNotificationContentPolicy() }
                    .exceptionOrNull()

            assertEquals(IOException::class.java, error?.javaClass)
            assertEquals(1, events.countEncryptedContents())
            assertFalse(contentCipher.keyDeleted)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `queued enable wins before reconciliation and preserves newly allowed content`() =
        runTest {
            settings.setNotificationContentStorageEnabled(false)
            storeEncryptedContent("com.allowed", 1L)
            val guardHeld = CompletableDeferred<Unit>()
            val releaseGuard = CompletableDeferred<Unit>()
            val blocker =
                async {
                    contentAccessGuard.withLock {
                        guardHeld.complete(Unit)
                        releaseGuard.await()
                    }
                }
            guardHeld.await()

            val enable = async { settings.setNotificationContentStorageEnabled(true) }
            runCurrent()
            val reconcile = async { repository.reconcileStoredNotificationContentPolicy() }
            runCurrent()
            releaseGuard.complete(Unit)
            advanceUntilIdle()

            blocker.await()
            enable.await()
            reconcile.await()
            assertEquals(1, events.countEncryptedContents())
            assertFalse(contentCipher.keyDeleted)
        }

    @Test
    fun `partial clear leaves unrelated categories intact`() =
        runTest {
            events.insert(sampleEvent())
            feedback.insert(
                CategoryFeedbackEntity(
                    packageName = "com.example",
                    predictedLabel = null,
                    correctedLabel = "news",
                    recordedAtMillis = 1L,
                ),
            )

            repository.clearFeedback()

            assertEquals(1, events.countAll())
            assertEquals(0, feedback.countAll())
        }

    @Test
    fun `clear feedback invalidates linked rollups before deleting their corrections`() =
        runTest {
            val linkedEvent = sampleEvent().copy(id = 1, postedEpochDay = 10)
            events.insert(linkedEvent)
            insights.seedEvents(linkedEvent)
            insights.seedCorrection(linkedEvent.id, "social")
            insights.seedSourceGap(10)
            insights.upsertInsight(
                DailyInsightEntity(
                    epochDay = 10,
                    windowStartMillis = 0,
                    windowEndMillis = 2,
                    totalNotifications = 1,
                    mutedCount = 1,
                    generatedAtMillis = 2,
                ),
            )
            feedback.insert(
                CategoryFeedbackEntity(
                    notificationEventId = linkedEvent.id,
                    packageName = linkedEvent.packageName,
                    predictedLabel = "promotion",
                    correctedLabel = "social",
                    recordedAtMillis = 1,
                ),
            )

            repository.clearFeedback()

            assertEquals(0, insights.countAll())
            assertEquals(
                listOf(10L),
                insights.observeSourceGapDaysBetween(0, 20).first(),
            )
        }

    @Test
    fun `clear feedback reports semantic and legacy imported votes without double counting`() =
        runTest {
            feedback.insert(
                CategoryFeedbackEntity(
                    packageName = "com.example",
                    predictedLabel = "promotion",
                    correctedLabel = "social",
                    recordedAtMillis = 1L,
                ),
            )
            llmObservations.upsertIfEventExists(
                LlmObservationEntity(
                    notificationEventId = 1,
                    packageName = "com.example",
                    predictedIsAdvertisement = true,
                    predictedIntent = "MARKETING",
                    confidenceScore = 0.8f,
                    correctedIsAdvertisement = false,
                    correctedIntent = "TRANSACTIONAL",
                    analyzedAtMillis = 1,
                ),
            )
            llmObservations.upsertLocalSemanticFeedback(
                LocalSemanticFeedbackEntity(
                    sourceEventId = 1,
                    packageName = "com.example",
                    correctedIntent = "TRANSACTIONAL",
                    recordedAtMillis = 1,
                ),
            )
            llmObservations.upsertImportedPriors(
                listOf(AdFeedbackPriorEntity("com.example", true, 2)),
            )
            llmObservations.upsertSemanticImportedPriors(
                listOf(SemanticFeedbackPriorEntity("com.example", "MARKETING", 3)),
            )

            val counts = repository.clearFeedback()

            assertEquals(7, counts.feedback)
            assertEquals(emptyList<Any>(), llmObservations.getFeedbackCounts())
            assertEquals(emptyList<Any>(), llmObservations.getSemanticFeedbackCounts())
            assertEquals(
                null,
                llmObservations
                    .observeAll()
                    .first()
                    .single()
                    .correctedIntent,
            )
        }

    @Test
    fun `clear activity removes local votes but preserves imported semantic priors`() =
        runTest {
            events.insert(sampleEvent().copy(postedEpochDay = 10))
            llmObservations.upsertIfEventExists(
                LlmObservationEntity(
                    notificationEventId = 1,
                    packageName = "com.example",
                    predictedIsAdvertisement = false,
                    predictedIntent = "OTHER",
                    confidenceScore = 0.8f,
                    analyzedAtMillis = 1,
                ),
            )
            llmObservations.upsertLocalSemanticFeedback(
                LocalSemanticFeedbackEntity(1, "com.example", "DELIVERY", 2),
            )
            llmObservations.upsertSemanticImportedPriors(
                listOf(SemanticFeedbackPriorEntity("com.example", "SECURITY", 3)),
            )

            val counts = repository.clearActivityHistory()

            assertEquals(1, counts.events)
            assertEquals(1, counts.feedback)
            assertEquals(emptyList<Any>(), llmObservations.getLocalSemanticFeedback())
            assertEquals(0, llmObservations.countAll())
            assertEquals(3L, llmObservations.countSemanticImportedPriorVotes())
            assertEquals(setOf(10L), events.sourceGapDays)
        }

    @Test
    fun `clear daily insights preserves raw source loss provenance`() =
        runTest {
            insights.seedSourceGap(10)
            insights.upsertInsight(
                DailyInsightEntity(
                    epochDay = 10,
                    windowStartMillis = 0,
                    windowEndMillis = 1,
                    totalNotifications = 1,
                    mutedCount = 0,
                    generatedAtMillis = 1,
                ),
            )

            val counts = repository.clearDailyInsights()

            assertEquals(1, counts.insightDays)
            assertEquals(0, insights.countAll())
            assertEquals(
                listOf(10L),
                insights.observeSourceGapDaysBetween(0, 20).first(),
            )
        }

    private fun sampleEvent() =
        NotificationEventEntity(
            packageName = "com.example",
            category = null,
            postedAtMillis = 1,
            action = StoredRuleAction.CANCEL,
            matchedRuleId = null,
            recordedAtMillis = 1,
        )

    private fun pendingAction(
        token: String,
        armed: Boolean = false,
        postedAtMillis: Long = 1,
        postedEpochDay: Long? = null,
    ): PendingNotificationActionEntity =
        PendingNotificationActionEntity(
            token = token,
            armed = armed,
            packageName = "com.example",
            category = "alarm",
            postedAtMillis = postedAtMillis,
            postedEpochDay = postedEpochDay,
            action = StoredRuleAction.CANCEL,
            matchedRuleId = null,
            recordedAtMillis = 1,
            createdAtMillis = 1,
        )

    private suspend fun storeEncryptedContent(
        packageName: String,
        id: Long,
    ) {
        events.insertWithTrace(
            sampleEvent().copy(id = id, packageName = packageName, hadEncryptedContent = true),
            emptyList(),
            contentCipher
                .encrypt("$packageName-content".encodeToByteArray())
                .let {
                    com.alarmcontrol.data.db.entity.EncryptedNotificationContentEntity(
                        formatVersion = it.formatVersion,
                        aadId = it.aadId,
                        nonce = it.nonce,
                        ciphertext = it.ciphertext,
                        createdAtMillis = 1,
                    )
                },
        )
    }
}

private class FakeRateOccurrenceDataCleaner : RateOccurrenceDataCleaner {
    var databaseCleared = false
        private set
    var databaseClearAtMillis: Long? = null
        private set
    var keyDeleted = false
        private set
    var keyDeletionAttempts = 0
        private set
    var failKeyDeletion = false

    override suspend fun clearDatabaseState(resetAtMillis: Long) {
        databaseCleared = true
        databaseClearAtMillis = resetAtMillis
    }

    override fun deleteHmacKey() {
        keyDeletionAttempts += 1
        check(!failKeyDeletion) { "HMAC key deletion failed" }
        keyDeleted = true
    }
}

private class RollingBackClock(
    private val initialMillis: Long,
    private val rolledBackMillis: Long,
    private val clockZone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    var millisCalls = 0
        private set

    override fun millis(): Long {
        millisCalls += 1
        return if (millisCalls == 1) initialMillis else rolledBackMillis
    }

    override fun instant(): Instant = Instant.ofEpochMilli(millis())

    override fun getZone(): ZoneId = clockZone

    override fun withZone(zone: ZoneId): Clock =
        RollingBackClock(
            initialMillis = initialMillis,
            rolledBackMillis = rolledBackMillis,
            clockZone = zone,
        )
}
