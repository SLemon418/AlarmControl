package com.alarmcontrol.data.repository

import com.alarmcontrol.core.filtering.RateOccurrenceLifecycleGate
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LocalDataRepositoryImplTest {
    private val rules = FakeRuleDao()
    private val events = FakeNotificationEventDao()
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
    ): LocalDataRepositoryImpl {
        val storedNotificationContentCleaner =
            StoredNotificationContentCleaner(transactionRunner, events, contentCipher)
        return LocalDataRepositoryImpl(
            transactionRunner,
            rules,
            events,
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
            val commitThenCancel =
                object : TransactionRunner {
                    override suspend fun <T> run(block: suspend () -> T): T {
                        block()
                        throw CancellationException("cancelled after commit")
                    }
                }
            val repository =
                createRepository(
                    settingsRepository = settings,
                    filteringActionGate = gate,
                    transactionRunner = commitThenCancel,
                )

            val failure = runCatching { repository.clearAllDatabaseData() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(0, rules.countAll())
            assertEquals(1L, gate.ruleRefreshRequests.value)
            assertFalse(gate.runIfAllowed {})
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
    var keyDeleted = false
        private set

    override suspend fun clearDatabaseState() {
        databaseCleared = true
    }

    override fun deleteHmacKey() {
        keyDeleted = true
    }
}
