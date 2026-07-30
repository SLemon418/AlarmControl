package com.alarmcontrol.data.repository

import com.alarmcontrol.core.backup.BackupCategoryFeedback
import com.alarmcontrol.core.backup.BackupData
import com.alarmcontrol.core.backup.BackupSemanticFeedback
import com.alarmcontrol.core.backup.BackupSummary
import com.alarmcontrol.core.backup.MAX_BACKUP_FILE_BYTES
import com.alarmcontrol.core.backup.RestoreMode
import com.alarmcontrol.core.backup.RestoreOptions
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.insights.CategoryCount
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.insights.RuleTriggerCount
import com.alarmcontrol.core.privacy.LocalDataResetWriteFence
import com.alarmcontrol.core.privacy.StaleLocalDataWriteException
import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.core.settings.MaintenanceSettingsSnapshot
import com.alarmcontrol.core.settings.SettingsMutationFence
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.core.settings.SettingsSnapshot
import com.alarmcontrol.data.backup.BackupCodec
import com.alarmcontrol.data.backup.BackupCryptor
import com.alarmcontrol.data.db.TransactionRunner
import com.alarmcontrol.data.db.dao.CategoryFeedbackDao
import com.alarmcontrol.data.db.entity.CategoryFeedbackEntity
import com.alarmcontrol.data.db.entity.LlmObservationEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.mapper.toWrite
import com.alarmcontrol.data.security.MaintenancePolicyAccessGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val BACKUP_NOW_MILLIS = 100_000L

@Suppress("LargeClass") // Keeps the cross-section backup and restore matrix in one fixture.
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRepositoryImplTest {
    private val ruleDao = FakeRuleDao()
    private val dailyDao = FakeDailyInsightDao()
    private val profileDao = FakeProfileDao()
    private val feedbackDao = FakeCategoryFeedbackDao()
    private val llmObservationDao = FakeLlmObservationDao()
    private val settings = InMemoryBackupSettingsRepository()
    private val transactionRunner = ImmediateTransactionRunner()
    private val ruleRepository = RuleRepositoryImpl(ruleDao)
    private val dailyRepository = DailyInsightRepositoryImpl(dailyDao, transactionRunner)
    private val profileRepository = ProfileRepositoryImpl(profileDao)
    private val backup =
        BackupRepositoryImpl(
            transactionRunner,
            ruleDao,
            dailyDao,
            profileDao,
            feedbackDao,
            llmObservationDao,
            settings,
            clock = Clock.fixed(Instant.ofEpochMilli(BACKUP_NOW_MILLIS), ZoneOffset.UTC),
        )

    @Test
    fun `preview and restore reject an oversized string at the repository boundary`() =
        runTest {
            val oversized = "x".repeat(MAX_BACKUP_FILE_BYTES + 1)

            assertTrue(backup.preview(oversized, null) is DataResult.Failure)
            assertTrue(backup.restore(oversized) is DataResult.Failure)
        }

    @Test
    fun `whole data reset waits through restore settings finalization and wins afterward`() =
        runTest {
            val resetFence = LocalDataResetWriteFence()
            val blockingSettings = BlockingFinalRestoreSettingsRepository(settings, resetFence)
            val guardedBackup =
                BackupRepositoryImpl(
                    transactionRunner,
                    ruleDao,
                    dailyDao,
                    profileDao,
                    feedbackDao,
                    llmObservationDao,
                    blockingSettings,
                    clock = Clock.fixed(Instant.ofEpochMilli(BACKUP_NOW_MILLIS), ZoneOffset.UTC),
                    localDataResetWriteFence = resetFence,
                )
            val payload =
                BackupCodec.encode(
                    BackupData(
                        rules =
                            listOf(
                                Rule(
                                    id = "1",
                                    name = "stale",
                                    condition = Condition.PackageEquals("com.stale"),
                                    action = RuleAction.Cancel,
                                ),
                            ),
                        dailyInsights = emptyList(),
                        settings =
                            SettingsSnapshot(
                                externalAutomationEnabled = true,
                                eventRetentionDays = 14,
                            ),
                    ),
                )

            val restoring = async { guardedBackup.restore(payload) }
            blockingSettings.finalRestoreStarted.await()
            val resetting =
                async {
                    resetFence.resetAndAdvanceOnCommit { onCommitted ->
                        blockingSettings.reset()
                        onCommitted()
                    }
                }
            runCurrent()
            assertFalse(resetting.isCompleted)
            blockingSettings.releaseFinalRestore.complete(Unit)

            assertTrue(restoring.await() is DataResult.Success)
            resetting.await()
            assertFalse(settings.current.externalAutomationEnabled)
            assertEquals(30, settings.current.eventRetentionDays)
        }

    @Test
    fun `restore replaces existing rules and remaps history references to the new ids`() =
        runTest {
            // A pre-existing rule that restore must wipe.
            ruleRepository.saveRule(
                Rule(id = "", name = "old", condition = Condition.PackageEquals("com.old"), action = RuleAction.Cancel),
            )

            val data =
                BackupData(
                    rules =
                        listOf(
                            Rule(
                                id = "7",
                                name = "imported",
                                priority = 3,
                                condition =
                                    Condition.AllOf(
                                        listOf(Condition.PackageEquals("com.x"), Condition.CategoryEquals("alarm")),
                                    ),
                                action = RuleAction.Snooze(60_000L),
                            ),
                        ),
                    dailyInsights =
                        listOf(
                            DailyInsight(
                                epochDay = 20_000,
                                windowStartMillis = 0,
                                windowEndMillis = 1,
                                totalNotifications = 4,
                                mutedCount = 2,
                                // "2" is a deleted rule in the backup. The fake assigns id 2 to
                                // the imported rule, deliberately reproducing a local-id collision.
                                topRules = listOf(RuleTriggerCount("7", 2), RuleTriggerCount("2", 1)),
                                categoryBreakdown = listOf(CategoryCount("alarm", 2), CategoryCount(null, 1)),
                                generatedAtMillis = 5,
                            ),
                        ),
                    profiles =
                        listOf(
                            FilteringProfile(
                                id = "9",
                                name = "Focus",
                                ruleIds = setOf("7"),
                            ),
                        ),
                )

            val result = backup.restore(BackupCodec.encode(data))

            assertTrue(result is DataResult.Success)
            assertEquals(1, transactionRunner.invocations)
            assertEquals(
                BackupSummary(rulesRestored = 1, insightsRestored = 1, profilesRestored = 1),
                (result as DataResult.Success).data,
            )

            val rules = ruleRepository.observeRules().first()
            assertEquals(listOf("imported"), rules.map { it.name }) // old gone, tree intact
            assertEquals(data.rules.single().condition, rules.single().condition)

            // The history's rule reference was re-pointed at the restored rule's new id.
            val newId = rules.single().id
            val insight = dailyRepository.observeRecent(10).first().single()
            assertEquals(
                listOf(RuleTriggerCount(newId, 2), RuleTriggerCount("deleted:2", 1)),
                insight.topRules,
            )
            assertEquals(listOf(CategoryCount("alarm", 2), CategoryCount(null, 1)), insight.categoryBreakdown)
            assertEquals(
                FilteringProfile(id = "1", name = "Focus", ruleIds = setOf(newId)),
                profileRepository.observeProfiles().first().single(),
            )
        }

    @Test
    fun `merge keeps an existing local insight day and imports only missing days`() =
        runTest {
            val local =
                DailyInsight(
                    epochDay = 20_000,
                    windowStartMillis = 10,
                    windowEndMillis = 20,
                    totalNotifications = 4,
                    mutedCount = 1,
                    topRules = emptyList(),
                    categoryBreakdown = listOf(CategoryCount("local", 4)),
                    generatedAtMillis = 30,
                )
            val missing =
                local.copy(
                    epochDay = 20_001,
                    windowStartMillis = 20,
                    windowEndMillis = 30,
                    totalNotifications = 2,
                    mutedCount = 0,
                    categoryBreakdown = listOf(CategoryCount("backup", 2)),
                    generatedAtMillis = 40,
                )
            dailyDao.store(local.toWrite())
            val payload =
                BackupCodec.encode(
                    BackupData(
                        rules = emptyList(),
                        dailyInsights =
                            listOf(
                                local.copy(
                                    totalNotifications = 99,
                                    mutedCount = 99,
                                    categoryBreakdown = listOf(CategoryCount("overwritten", 99)),
                                ),
                                missing,
                            ),
                    ),
                )

            val result =
                backup.restore(
                    serialized = payload,
                    options =
                        RestoreOptions(
                            mode = RestoreMode.MERGE,
                            rulesAndProfiles = false,
                            settings = false,
                        ),
                )

            assertTrue(result is DataResult.Success)
            assertEquals(
                BackupSummary(
                    rulesRestored = 0,
                    insightsRestored = 1,
                    insightConflictsSkipped = 1,
                ),
                (result as DataResult.Success).data,
            )
            val insights = dailyRepository.observeRecent(10).first().associateBy(DailyInsight::epochDay)
            assertEquals(local, insights.getValue(local.epochDay))
            assertEquals(missing, insights.getValue(missing.epochDay))
            assertEquals(
                listOf(missing.epochDay),
                dailyDao.observeSourceGapDaysBetween(local.epochDay, missing.epochDay).first(),
            )
        }

    @Test
    fun `restore preserves an incomplete daily source marker without exporting raw gap rows`() =
        runTest {
            dailyDao.seedSourceGap(19_999)
            val incomplete =
                DailyInsight(
                    epochDay = 20_000,
                    windowStartMillis = 10,
                    windowEndMillis = 20,
                    totalNotifications = 1,
                    mutedCount = 0,
                    topRules = emptyList(),
                    categoryBreakdown = emptyList(),
                    generatedAtMillis = 30,
                    sourceComplete = false,
                )

            val result =
                backup.restore(
                    BackupCodec.encode(BackupData(emptyList(), listOf(incomplete))),
                    options =
                        RestoreOptions(
                            mode = RestoreMode.REPLACE,
                            rulesAndProfiles = false,
                            settings = false,
                        ),
                )

            assertTrue(result is DataResult.Success)
            assertEquals(
                false,
                dailyRepository
                    .observeRecent(1)
                    .first()
                    .single()
                    .sourceComplete,
            )
            assertEquals(
                listOf(19_999L, 20_000L),
                dailyDao.observeSourceGapDaysBetween(19_999, 20_000).first(),
            )
        }

    @Test
    fun `restore stores a complete snapshot without erasing local source provenance`() =
        runTest {
            dailyDao.seedSourceGap(20_000)
            val complete =
                DailyInsight(
                    epochDay = 20_000,
                    windowStartMillis = 10,
                    windowEndMillis = 20,
                    totalNotifications = 1,
                    mutedCount = 0,
                    topRules = emptyList(),
                    categoryBreakdown = emptyList(),
                    generatedAtMillis = 30,
                    sourceComplete = true,
                )

            val result =
                backup.restore(
                    BackupCodec.encode(BackupData(emptyList(), listOf(complete))),
                    options =
                        RestoreOptions(
                            mode = RestoreMode.REPLACE,
                            rulesAndProfiles = false,
                            settings = false,
                        ),
                )

            assertTrue(result is DataResult.Success)
            assertEquals(complete, dailyRepository.observeRecent(1).first().single())
            assertEquals(
                listOf(20_000L),
                dailyDao.observeSourceGapDaysBetween(20_000, 20_000).first(),
            )
        }

    @Test
    fun `complete imported snapshot remains complete but a later rebuild is source incomplete`() =
        runTest {
            val epochDay = 20_000L
            val complete =
                DailyInsight(
                    epochDay = epochDay,
                    windowStartMillis = 0,
                    windowEndMillis = 1_000,
                    totalNotifications = 7,
                    mutedCount = 2,
                    topRules = emptyList(),
                    categoryBreakdown = emptyList(),
                    generatedAtMillis = 1_000,
                    sourceComplete = true,
                )

            val result =
                backup.restore(
                    BackupCodec.encode(BackupData(emptyList(), listOf(complete))),
                    options =
                        RestoreOptions(
                            mode = RestoreMode.REPLACE,
                            rulesAndProfiles = false,
                            settings = false,
                        ),
                )

            assertTrue(result is DataResult.Success)
            assertEquals(complete, dailyRepository.observeRecent(1).first().single())
            assertEquals(
                listOf(epochDay),
                dailyDao.observeSourceGapDaysBetween(epochDay, epochDay).first(),
            )

            val localEvent =
                NotificationEventEntity(
                    id = 77,
                    packageName = "com.local",
                    category = null,
                    postedAtMillis = 100,
                    postedEpochDay = epochDay,
                    action = StoredRuleAction.KEEP,
                    matchedRuleId = null,
                    recordedAtMillis = 100,
                )
            dailyDao.seedEvents(localEvent)
            dailyDao.deleteContainingEvent(localEvent.id)
            val rebuilt =
                dailyRepository.aggregateAndStore(
                    epochDay = epochDay,
                    startMillis = 0,
                    endMillis = 1_000,
                    generatedAtMillis = 2_000,
                    topRules = 5,
                )

            assertFalse(rebuilt.sourceComplete)
            assertEquals(1, rebuilt.totalNotifications)
            assertEquals(rebuilt, dailyRepository.observeRecent(1).first().single())
        }

    @Test
    fun `feedback replace invalidates local correction before importing the same day rollup`() =
        runTest {
            val epochDay = 20_000L
            val correctedEvent =
                NotificationEventEntity(
                    id = 1,
                    packageName = "com.local",
                    category = null,
                    postedAtMillis = 10,
                    postedEpochDay = epochDay,
                    action = StoredRuleAction.KEEP,
                    matchedRuleId = null,
                    recordedAtMillis = 10,
                )
            dailyDao.seedEvents(correctedEvent)
            dailyDao.seedSemantic(
                eventId = correctedEvent.id,
                predicted = "OTHER",
                corrected = "DELIVERY",
            )
            llmObservationDao.upsertIfEventExists(
                LlmObservationEntity(
                    notificationEventId = correctedEvent.id,
                    packageName = correctedEvent.packageName,
                    predictedIsAdvertisement = false,
                    predictedIntent = "OTHER",
                    confidenceScore = 0.8f,
                    correctedIsAdvertisement = false,
                    correctedIntent = "DELIVERY",
                    analyzedAtMillis = 10,
                ),
            )
            val local =
                DailyInsight(
                    epochDay = epochDay,
                    windowStartMillis = 0,
                    windowEndMillis = 20,
                    totalNotifications = 1,
                    mutedCount = 0,
                    topRules = emptyList(),
                    categoryBreakdown = emptyList(),
                    generatedAtMillis = 20,
                )
            dailyDao.store(local.toWrite())
            val imported = local.copy(totalNotifications = 7, generatedAtMillis = 30)
            val password = "password".toCharArray()
            val payload =
                BackupCryptor.encrypt(
                    BackupCodec.encode(
                        BackupData(
                            rules = emptyList(),
                            dailyInsights = listOf(imported),
                            semanticFeedback =
                                listOf(
                                    BackupSemanticFeedback(
                                        packageName = "com.remote",
                                        intent = SemanticIntent.SECURITY,
                                        count = 1,
                                    ),
                                ),
                        ),
                    ),
                    password,
                )

            val result =
                backup.restore(
                    payload,
                    password,
                    RestoreOptions(
                        mode = RestoreMode.REPLACE,
                        rulesAndProfiles = false,
                        settings = false,
                        learningFeedback = true,
                    ),
                )

            assertTrue(result is DataResult.Success)
            assertEquals(1, dailyDao.linkedFeedbackInvalidationCalls)
            assertEquals(imported, dailyRepository.observeRecent(1).first().single())
        }

    @Test
    fun `export captures the current rules`() =
        runTest {
            ruleRepository.saveRule(
                Rule(
                    id = "",
                    name = "r1",
                    priority = 2,
                    condition = Condition.PackageEquals("com.a"),
                    action = RuleAction.Cancel,
                ),
            )

            val decoded = BackupCodec.decode(backup.export())

            assertEquals(listOf("r1"), decoded.rules.map { it.name })
            assertEquals(settings.current, decoded.settings)
        }

    @Test
    fun `preview validates without mutating the database`() =
        runTest {
            val payload =
                BackupCodec.encode(
                    BackupData(
                        rules =
                            listOf(
                                Rule(
                                    id = "1",
                                    name = "preview",
                                    condition = Condition.PackageEquals("com.preview"),
                                    action = RuleAction.Keep,
                                ),
                            ),
                        dailyInsights = emptyList(),
                    ),
                )

            val preview = backup.preview(payload)

            assertTrue(preview is DataResult.Success)
            assertEquals(1, (preview as DataResult.Success).data.rules)
            assertEquals(0, transactionRunner.invocations)
            assertEquals(0, ruleDao.countAll())
        }

    @Test
    fun `preview vote totals saturate instead of wrapping negative`() =
        runTest {
            val password = "password".toCharArray()
            val payload =
                BackupCryptor.encrypt(
                    BackupCodec.encode(
                        BackupData(
                            rules = emptyList(),
                            dailyInsights = emptyList(),
                            semanticFeedback =
                                List(2_148) { index ->
                                    BackupSemanticFeedback(
                                        packageName = "com.example.$index",
                                        intent = SemanticIntent.MARKETING,
                                        count = 1_000_000,
                                    )
                                },
                        ),
                    ),
                    password,
                )

            val result = backup.preview(payload, password)

            assertTrue(result is DataResult.Success)
            assertEquals(Int.MAX_VALUE, (result as DataResult.Success).data.adFeedbackVotes)
        }

    @Test
    fun `merge keeps existing rules and reuses an identical imported rule`() =
        runTest {
            val existing =
                Rule(
                    id = "",
                    name = "same",
                    condition = Condition.PackageEquals("com.same"),
                    action = RuleAction.Cancel,
                )
            ruleRepository.saveRule(existing)
            val stored = ruleRepository.observeRules().first().single()
            val payload = BackupData(rules = listOf(stored.copy(id = "remote")), dailyInsights = emptyList())

            val result =
                backup.restore(
                    BackupCodec.encode(payload),
                    options = RestoreOptions(mode = RestoreMode.MERGE, dailyInsights = false, settings = false),
                )

            assertTrue(result is DataResult.Success)
            assertEquals(1, ruleRepository.observeRules().first().size)
        }

    @Test
    fun `learning feedback requires encryption and restores as detached local priors`() =
        runTest {
            feedbackDao.insert(
                com.alarmcontrol.data.db.entity.CategoryFeedbackEntity(
                    packageName = "com.shop",
                    predictedLabel = "social",
                    correctedLabel = "promotion",
                    recordedAtMillis = 10,
                ),
            )
            llmObservationDao.upsertSemanticImportedPriors(
                listOf(
                    com.alarmcontrol.data.db.entity
                        .SemanticFeedbackPriorEntity("com.shop", "MARKETING", 3),
                ),
            )
            assertTrue(
                runCatching { backup.export(includeLearningFeedback = true) }.isFailure,
            )

            val encrypted = backup.export("password".toCharArray(), includeLearningFeedback = true)
            feedbackDao.deleteAll()
            llmObservationDao.deleteSemanticImportedPriors()
            val result =
                backup.restore(
                    encrypted,
                    "password".toCharArray(),
                    RestoreOptions(
                        mode = RestoreMode.REPLACE,
                        rulesAndProfiles = false,
                        dailyInsights = false,
                        settings = false,
                        learningFeedback = true,
                    ),
                )

            assertTrue(result is DataResult.Success)
            assertEquals(1, feedbackDao.getEffectiveFeedback().size)
            assertEquals(
                com.alarmcontrol.data.db.dao.CategoryFeedbackDao.MAX_RETAINED_ROWS,
                feedbackDao.lastTrimMaximum,
            )
            assertEquals(3, llmObservationDao.getSemanticFeedbackCounts().single().count)
        }

    @Test
    fun `learning export combines one local vote with imported priors without double counting live columns`() =
        runTest {
            llmObservationDao.upsertIfEventExists(
                com.alarmcontrol.data.db.entity.LlmObservationEntity(
                    notificationEventId = 1,
                    packageName = "com.shop",
                    predictedIsAdvertisement = false,
                    predictedIntent = "OTHER",
                    confidenceScore = 0.8f,
                    correctedIsAdvertisement = false,
                    correctedIntent = "DELIVERY",
                    analyzedAtMillis = 10,
                ),
            )
            llmObservationDao.upsertLocalSemanticFeedback(
                com.alarmcontrol.data.db.entity.LocalSemanticFeedbackEntity(
                    sourceEventId = 1,
                    packageName = "com.shop",
                    correctedIntent = "DELIVERY",
                    recordedAtMillis = 11,
                ),
            )
            llmObservationDao.upsertSemanticImportedPriors(
                listOf(
                    com.alarmcontrol.data.db.entity.SemanticFeedbackPriorEntity(
                        "com.shop",
                        "DELIVERY",
                        2,
                    ),
                ),
            )

            val encrypted = backup.export("password".toCharArray(), includeLearningFeedback = true)
            val decoded =
                BackupCodec.decode(
                    BackupCryptor.decrypt(encrypted, "password".toCharArray()),
                )

            assertEquals(
                listOf(BackupSemanticFeedback("com.shop", SemanticIntent.DELIVERY, 3)),
                decoded.semanticFeedback,
            )
        }

    @Test
    fun `feedback merge preserves local votes while replace removes local and live corrections`() =
        runTest {
            llmObservationDao.upsertIfEventExists(
                com.alarmcontrol.data.db.entity.LlmObservationEntity(
                    notificationEventId = 1,
                    packageName = "com.local",
                    predictedIsAdvertisement = true,
                    predictedIntent = "MARKETING",
                    confidenceScore = 0.8f,
                    correctedIsAdvertisement = true,
                    correctedIntent = "MARKETING",
                    analyzedAtMillis = 10,
                ),
            )
            llmObservationDao.upsertLocalSemanticFeedback(
                com.alarmcontrol.data.db.entity.LocalSemanticFeedbackEntity(
                    1,
                    "com.local",
                    "MARKETING",
                    11,
                ),
            )
            val password = "password".toCharArray()
            val payload =
                BackupCryptor.encrypt(
                    BackupCodec.encode(
                        BackupData(
                            rules = emptyList(),
                            dailyInsights = emptyList(),
                            semanticFeedback =
                                listOf(BackupSemanticFeedback("com.remote", SemanticIntent.SECURITY, 2)),
                        ),
                    ),
                    password,
                )

            val merged =
                backup.restore(
                    payload,
                    password,
                    RestoreOptions(
                        mode = RestoreMode.MERGE,
                        rulesAndProfiles = false,
                        dailyInsights = false,
                        settings = false,
                        learningFeedback = true,
                    ),
                )

            assertTrue(merged is DataResult.Success)
            assertEquals(1, llmObservationDao.countLocalSemanticFeedback())
            assertEquals(
                mapOf("com.local" to 1, "com.remote" to 2),
                llmObservationDao
                    .getSemanticFeedbackCounts()
                    .associate { it.packageName to it.count },
            )

            val replaced =
                backup.restore(
                    payload,
                    password,
                    RestoreOptions(
                        mode = RestoreMode.REPLACE,
                        rulesAndProfiles = false,
                        dailyInsights = false,
                        settings = false,
                        learningFeedback = true,
                    ),
                )

            assertTrue(replaced is DataResult.Success)
            assertEquals(0, llmObservationDao.countLocalSemanticFeedback())
            assertEquals(
                null,
                llmObservationDao
                    .observeAll()
                    .first()
                    .single()
                    .correctedIntent,
            )
            assertEquals(
                mapOf("com.remote" to 2),
                llmObservationDao
                    .getSemanticFeedbackCounts()
                    .associate { it.packageName to it.count },
            )
        }

    @Test
    fun `repeated semantic feedback merge preserves validator bounds and export round trip`() =
        runTest {
            val password = "password".toCharArray()
            val payload =
                BackupCryptor.encrypt(
                    BackupCodec.encode(
                        BackupData(
                            rules = emptyList(),
                            dailyInsights = emptyList(),
                            semanticFeedback =
                                listOf(
                                    BackupSemanticFeedback(
                                        packageName = "com.remote",
                                        intent = SemanticIntent.MARKETING,
                                        count = 500_000,
                                    ),
                                ),
                        ),
                    ),
                    password,
                )
            val options =
                RestoreOptions(
                    mode = RestoreMode.MERGE,
                    rulesAndProfiles = false,
                    dailyInsights = false,
                    settings = false,
                    learningFeedback = true,
                )

            repeat(2) {
                assertTrue(backup.restore(payload, password, options) is DataResult.Success)
            }
            assertEquals(1_000_000, llmObservationDao.getSemanticFeedbackCounts().single().count)
            assertTrue(
                backup.preview(
                    backup.export(password, includeLearningFeedback = true),
                    password,
                ) is DataResult.Success,
            )

            assertTrue(backup.restore(payload, password, options) is DataResult.Failure)
            assertEquals(1_000_000, llmObservationDao.getSemanticFeedbackCounts().single().count)
            assertTrue(
                backup.preview(
                    backup.export(password, includeLearningFeedback = true),
                    password,
                ) is DataResult.Success,
            )
        }

    @Test
    fun `learning export bounds feedback while preserving runtime priors and local groups`() =
        runTest {
            val priors =
                List(25_000) { index ->
                    com.alarmcontrol.data.db.entity.SemanticFeedbackPriorEntity(
                        packageName = "com.imported.$index",
                        intent = SemanticIntent.OTHER.name,
                        count = if (index == 0) Int.MAX_VALUE else index + 1,
                    )
                }
            llmObservationDao.upsertSemanticImportedPriors(priors)
            val localFeedback =
                listOf(
                    com.alarmcontrol.data.db.entity.LocalSemanticFeedbackEntity(
                        sourceEventId = 1,
                        packageName = "com.imported.0",
                        correctedIntent = SemanticIntent.OTHER.name,
                        recordedAtMillis = 100,
                    ),
                    com.alarmcontrol.data.db.entity.LocalSemanticFeedbackEntity(
                        sourceEventId = 2,
                        packageName = "com.local",
                        correctedIntent = SemanticIntent.SECURITY.name,
                        recordedAtMillis = 101,
                    ),
                )
            localFeedback.forEach {
                llmObservationDao.upsertLocalSemanticFeedback(it)
            }
            val priorsBeforeExport = llmObservationDao.getSemanticImportedPriors()
            val localBeforeExport = llmObservationDao.getLocalSemanticFeedback()
            val password = "password".toCharArray()

            val exported = backup.export(password, includeLearningFeedback = true)
            val decoded =
                BackupCodec.decode(
                    BackupCryptor.decrypt(exported, password),
                )

            assertEquals(25_000, decoded.semanticFeedback.size)
            assertTrue(
                decoded.semanticFeedback.any {
                    it.packageName == "com.local" &&
                        it.intent == SemanticIntent.SECURITY &&
                        it.count == 1
                },
            )
            assertEquals(
                1_000_000,
                decoded.semanticFeedback
                    .single {
                        it.packageName == "com.imported.0" &&
                            it.intent == SemanticIntent.OTHER
                    }.count,
            )
            assertEquals(priorsBeforeExport, llmObservationDao.getSemanticImportedPriors())
            assertEquals(localBeforeExport, llmObservationDao.getLocalSemanticFeedback())
            assertTrue(backup.preview(exported, password) is DataResult.Success)
        }

    @Test
    fun `semantic feedback merge rejects a disjoint key beyond the export row limit`() =
        runTest {
            llmObservationDao.upsertSemanticImportedPriors(
                List(25_000) { index ->
                    com.alarmcontrol.data.db.entity.SemanticFeedbackPriorEntity(
                        packageName = "com.existing.$index",
                        intent = SemanticIntent.OTHER.name,
                        count = 1,
                    )
                },
            )
            val password = "password".toCharArray()
            val payload =
                BackupCryptor.encrypt(
                    BackupCodec.encode(
                        BackupData(
                            rules = emptyList(),
                            dailyInsights = emptyList(),
                            semanticFeedback =
                                listOf(
                                    BackupSemanticFeedback(
                                        packageName = "com.new",
                                        intent = SemanticIntent.SECURITY,
                                        count = 1,
                                    ),
                                ),
                        ),
                    ),
                    password,
                )

            val result =
                backup.restore(
                    payload,
                    password,
                    RestoreOptions(
                        mode = RestoreMode.MERGE,
                        rulesAndProfiles = false,
                        dailyInsights = false,
                        settings = false,
                        learningFeedback = true,
                    ),
                )

            assertTrue(result is DataResult.Failure)
            assertEquals(25_000, llmObservationDao.getSemanticImportedPriors().size)
            assertTrue(
                llmObservationDao
                    .getSemanticImportedPriors()
                    .none { it.packageName == "com.new" },
            )
        }

    @Test
    fun `feedback merge never evicts existing local learning at the global cap`() =
        runTest {
            feedbackDao.seedRows(
                List(CategoryFeedbackDao.MAX_RETAINED_ROWS) { index ->
                    CategoryFeedbackEntity(
                        id = index + 1L,
                        packageName = "com.local.$index",
                        notificationEventId = if (index == 0) 77L else null,
                        predictedLabel = "promotion",
                        correctedLabel = "social",
                        recordedAtMillis = index.toLong(),
                    )
                },
            )
            val password = "password".toCharArray()
            val payload =
                BackupCryptor.encrypt(
                    BackupCodec.encode(
                        BackupData(
                            rules = emptyList(),
                            dailyInsights = emptyList(),
                            categoryFeedback =
                                listOf(
                                    BackupCategoryFeedback(
                                        packageName = "com.remote",
                                        predictedLabel = "promotion",
                                        correctedLabel = "news",
                                        recordedAtMillis = 100_000,
                                    ),
                                ),
                        ),
                    ),
                    password,
                )

            val result =
                backup.restore(
                    payload,
                    password,
                    RestoreOptions(
                        mode = RestoreMode.MERGE,
                        rulesAndProfiles = false,
                        dailyInsights = false,
                        settings = false,
                        learningFeedback = true,
                    ),
                )

            assertTrue(result is DataResult.Success)
            assertEquals(emptyList<Long>(), dailyDao.invalidatedEventIds)
            assertEquals(CategoryFeedbackDao.MAX_RETAINED_ROWS, feedbackDao.countAll())
            assertTrue(feedbackDao.inserted.any { it.notificationEventId == 77L })
            assertTrue(feedbackDao.inserted.none { it.packageName == "com.remote" })
        }

    @Test
    fun `feedback merge cannot evict newer local learning with old imported rows`() =
        runTest {
            feedbackDao.seedRows(
                List(CategoryFeedbackDao.MAX_RETAINED_ROWS) { index ->
                    CategoryFeedbackEntity(
                        id = index + 1L,
                        packageName = "com.local.$index",
                        notificationEventId = if (index == 0) 88L else null,
                        predictedLabel = "promotion",
                        correctedLabel = "social",
                        recordedAtMillis = 10_000L + index,
                    )
                },
            )
            val password = "password".toCharArray()
            val payload =
                BackupCryptor.encrypt(
                    BackupCodec.encode(
                        BackupData(
                            rules = emptyList(),
                            dailyInsights = emptyList(),
                            categoryFeedback =
                                listOf(
                                    BackupCategoryFeedback(
                                        packageName = "com.old-backup",
                                        predictedLabel = "promotion",
                                        correctedLabel = "news",
                                        recordedAtMillis = 1,
                                    ),
                                ),
                        ),
                    ),
                    password,
                )

            val result =
                backup.restore(
                    payload,
                    password,
                    RestoreOptions(
                        mode = RestoreMode.MERGE,
                        rulesAndProfiles = false,
                        dailyInsights = false,
                        settings = false,
                        learningFeedback = true,
                    ),
                )

            assertTrue(result is DataResult.Success)
            assertEquals(CategoryFeedbackDao.MAX_RETAINED_ROWS, feedbackDao.countAll())
            assertTrue(feedbackDao.inserted.none { it.packageName == "com.old-backup" })
            assertTrue(feedbackDao.inserted.any { it.notificationEventId == 88L })
            assertEquals(emptyList<Long>(), dailyDao.invalidatedEventIds)
        }

    @Test
    fun `far future feedback is rejected before it can evict local learning`() =
        runTest {
            feedbackDao.seedRows(
                List(CategoryFeedbackDao.MAX_RETAINED_ROWS) { index ->
                    CategoryFeedbackEntity(
                        id = index + 1L,
                        packageName = "com.local.$index",
                        predictedLabel = "promotion",
                        correctedLabel = "social",
                        recordedAtMillis = BACKUP_NOW_MILLIS - index,
                    )
                },
            )
            val password = "password".toCharArray()
            val payload =
                BackupCryptor.encrypt(
                    BackupCodec.encode(
                        BackupData(
                            rules = emptyList(),
                            dailyInsights = emptyList(),
                            categoryFeedback =
                                listOf(
                                    BackupCategoryFeedback(
                                        packageName = "com.future-backup",
                                        predictedLabel = "promotion",
                                        correctedLabel = "news",
                                        recordedAtMillis = Long.MAX_VALUE,
                                    ),
                                ),
                        ),
                    ),
                    password,
                )

            val result =
                backup.restore(
                    payload,
                    password,
                    RestoreOptions(
                        mode = RestoreMode.MERGE,
                        rulesAndProfiles = false,
                        dailyInsights = false,
                        settings = false,
                        learningFeedback = true,
                    ),
                )

            assertTrue(result is DataResult.Failure)
            assertEquals(CategoryFeedbackDao.MAX_RETAINED_ROWS, feedbackDao.countAll())
            assertTrue(feedbackDao.inserted.none { it.packageName == "com.future-backup" })
        }

    @Test
    fun `encrypted export restores only with its password`() =
        runTest {
            ruleRepository.saveRule(
                Rule(
                    id = "",
                    name = "private rule",
                    condition = Condition.PackageEquals("com.private"),
                    action = RuleAction.Cancel,
                ),
            )
            val encrypted = backup.export("password".toCharArray())

            assertTrue(backup.restore(encrypted, "wrong".toCharArray()) is DataResult.Failure)
            assertTrue(backup.restore(encrypted, "password".toCharArray()) is DataResult.Success)
        }

    @Test
    fun `new encrypted export requires eight characters but legacy short password still restores`() =
        runTest {
            assertTrue(runCatching { backup.export("short".toCharArray()) }.isFailure)
            val legacy =
                BackupCryptor.encrypt(
                    BackupCodec.encode(BackupData(emptyList(), emptyList())),
                    "short".toCharArray(),
                )

            assertTrue(backup.restore(legacy, "short".toCharArray()) is DataResult.Success)
        }

    @Test
    fun `restore of malformed input fails gracefully`() =
        runTest {
            assertTrue(backup.restore("{ not valid json") is DataResult.Failure)
            assertEquals(0, transactionRunner.invocations)
        }

    @Test
    fun `restore validates the complete payload before deleting existing rules`() =
        runTest {
            ruleRepository.saveRule(
                Rule(
                    id = "",
                    name = "keep me",
                    condition = Condition.PackageEquals("com.safe"),
                    action = RuleAction.Keep,
                ),
            )
            val unsafe =
                BackupData(
                    rules =
                        listOf(
                            Rule(
                                id = "7",
                                name = "empty destructive rule",
                                condition = Condition.AllOf(emptyList()),
                                action = RuleAction.Cancel,
                            ),
                        ),
                    dailyInsights = emptyList(),
                )

            assertTrue(backup.restore(BackupCodec.encode(unsafe)) is DataResult.Failure)
            assertEquals(listOf("keep me"), ruleRepository.observeRules().first().map { it.name })
            assertEquals(0, transactionRunner.invocations)
        }

    @Test
    fun `rule restore pauses destructive settings until the database transaction commits`() =
        runTest {
            val checkpoints = mutableListOf<SettingsSnapshot>()
            val runner =
                object : TransactionRunner {
                    override suspend fun <T> run(block: suspend () -> T): T {
                        checkpoints += settings.current
                        return block()
                    }
                }
            val repository =
                BackupRepositoryImpl(
                    runner,
                    ruleDao,
                    dailyDao,
                    profileDao,
                    feedbackDao,
                    llmObservationDao,
                    settings,
                )
            val desired =
                SettingsSnapshot(
                    filteringEnabled = true,
                    semanticClassifierEnabled = false,
                    externalAutomationEnabled = true,
                    llmAnalysisEnabled = true,
                )
            val payload =
                BackupCodec.encode(
                    BackupData(
                        rules =
                            listOf(
                                Rule(
                                    id = "restored",
                                    name = "Restored",
                                    condition = Condition.PackageEquals("com.example"),
                                    action = RuleAction.Cancel,
                                ),
                            ),
                        dailyInsights = emptyList(),
                        settings = desired,
                    ),
                )

            val result = repository.restore(payload)

            assertTrue(result is DataResult.Success)
            assertFalse(checkpoints.single().filteringEnabled)
            assertFalse(checkpoints.single().externalAutomationEnabled)
            assertFalse(checkpoints.single().llmAutoActionsEnabled)
            assertEquals(desired, settings.current)
        }

    @Test
    fun `concurrent user setting waits for restore finalization and applies afterward`() =
        runTest {
            val mutationFence = SettingsMutationFence()
            val maintenanceGuard = MaintenancePolicyAccessGuard()
            val guardedSettings = MutationFencedBackupSettingsRepository(settings, mutationFence)
            val transactionStarted = CompletableDeferred<Unit>()
            val releaseTransaction = CompletableDeferred<Unit>()
            val runner =
                object : TransactionRunner {
                    override suspend fun <T> run(block: suspend () -> T): T {
                        transactionStarted.complete(Unit)
                        releaseTransaction.await()
                        return block()
                    }
                }
            val repository =
                BackupRepositoryImpl(
                    runner,
                    ruleDao,
                    dailyDao,
                    profileDao,
                    feedbackDao,
                    llmObservationDao,
                    guardedSettings,
                    settingsMutationFence = mutationFence,
                    maintenancePolicyAccessGuard = maintenanceGuard,
                )
            val payload =
                BackupCodec.encode(
                    BackupData(
                        rules =
                            listOf(
                                Rule(
                                    id = "restored",
                                    name = "Restored",
                                    condition = Condition.PackageEquals("com.example"),
                                    action = RuleAction.Cancel,
                                ),
                            ),
                        dailyInsights = emptyList(),
                    ),
                )

            val restoring =
                async {
                    repository.restore(
                        payload,
                        options = RestoreOptions(settings = false),
                    )
                }
            transactionStarted.await()
            val userDisablingFiltering = async { guardedSettings.setFilteringEnabled(false) }
            val housekeeping = async { maintenanceGuard.withLock { Unit } }
            runCurrent()

            assertFalse(userDisablingFiltering.isCompleted)
            assertFalse(housekeeping.isCompleted)
            releaseTransaction.complete(Unit)
            assertTrue(restoring.await() is DataResult.Success)
            userDisablingFiltering.await()
            housekeeping.await()
            assertFalse(settings.current.filteringEnabled)
        }

    @Test
    fun `failed database restore puts the original settings back`() =
        runTest {
            val original =
                SettingsSnapshot(
                    filteringEnabled = true,
                    externalAutomationEnabled = true,
                    llmAnalysisEnabled = true,
                )
            settings.restore(original)
            val runner =
                object : TransactionRunner {
                    override suspend fun <T> run(block: suspend () -> T): T = error("database unavailable")
                }
            val repository =
                BackupRepositoryImpl(
                    runner,
                    ruleDao,
                    dailyDao,
                    profileDao,
                    feedbackDao,
                    llmObservationDao,
                    settings,
                )
            val payload = BackupCodec.encode(BackupData(emptyList(), emptyList()))

            val result = repository.restore(payload)

            assertTrue(result is DataResult.Failure)
            assertEquals(original, settings.current)
        }

    @Test
    fun `settings failure after database commit reports partial success and leaves gates off`() =
        runTest {
            val original =
                SettingsSnapshot(
                    filteringEnabled = true,
                    externalAutomationEnabled = true,
                    llmAnalysisEnabled = false,
                )
            settings.restore(original)
            val checkpoints = mutableListOf<SettingsSnapshot>()
            val runner =
                object : TransactionRunner {
                    override suspend fun <T> run(block: suspend () -> T): T {
                        checkpoints += settings.current
                        return block()
                    }
                }
            val repository =
                BackupRepositoryImpl(
                    runner,
                    ruleDao,
                    dailyDao,
                    profileDao,
                    feedbackDao,
                    llmObservationDao,
                    settings,
                )
            val desired =
                SettingsSnapshot(
                    filteringEnabled = true,
                    externalAutomationEnabled = true,
                    llmAnalysisEnabled = true,
                )
            settings.failEnabledRestore = true
            val imported =
                DailyInsight(
                    epochDay = 20_100,
                    windowStartMillis = 100,
                    windowEndMillis = 200,
                    totalNotifications = 3,
                    mutedCount = 1,
                    topRules = emptyList(),
                    categoryBreakdown = emptyList(),
                    generatedAtMillis = 300,
                )
            val payload =
                BackupCodec.encode(
                    BackupData(
                        rules = emptyList(),
                        dailyInsights = listOf(imported),
                        settings = desired,
                    ),
                )

            val result =
                repository.restore(
                    payload,
                    options =
                        RestoreOptions(
                            rulesAndProfiles = false,
                            dailyInsights = true,
                            settings = true,
                        ),
                )

            assertTrue(result is DataResult.Success)
            assertEquals(
                BackupSummary(
                    rulesRestored = 0,
                    insightsRestored = 1,
                    settingsReviewRequired = true,
                ),
                (result as DataResult.Success).data,
            )
            assertFalse(checkpoints.single().filteringEnabled)
            assertFalse(checkpoints.single().externalAutomationEnabled)
            assertFalse(checkpoints.single().llmAutoActionsEnabled)
            assertEquals(imported, dailyRepository.observeRecent(10).first().single())
            assertFalse(settings.current.filteringEnabled)
            assertFalse(settings.current.externalAutomationEnabled)
            assertFalse(settings.current.llmAutoActionsEnabled)
        }

    @Test
    fun `settings-only failure returns failure and restores prior settings`() =
        runTest {
            val original =
                SettingsSnapshot(
                    filteringEnabled = false,
                    externalAutomationEnabled = false,
                    llmAnalysisEnabled = false,
                    eventRetentionDays = 14,
                    dailyInsightRetentionDays = 90,
                )
            settings.restore(original)
            settings.failEnabledRestore = true
            val desired =
                original.copy(
                    filteringEnabled = true,
                    externalAutomationEnabled = true,
                    llmAnalysisEnabled = true,
                    eventRetentionDays = 30,
                )
            val payload =
                BackupCodec.encode(
                    BackupData(
                        rules = emptyList(),
                        dailyInsights = emptyList(),
                        settings = desired,
                    ),
                )

            val result =
                backup.restore(
                    payload,
                    options =
                        RestoreOptions(
                            rulesAndProfiles = false,
                            dailyInsights = false,
                            settings = true,
                        ),
                )

            assertTrue(result is DataResult.Failure)
            assertEquals(original, settings.current)
        }
}

internal class InMemoryBackupSettingsRepository : SettingsRepository {
    private val state = MutableStateFlow(SettingsSnapshot())
    val current: SettingsSnapshot get() = state.value
    var failEnabledRestore = false

    override val filteringEnabled: Flow<Boolean> = state.mapValue(SettingsSnapshot::filteringEnabled)
    override val semanticClassifierEnabled: Flow<Boolean> =
        state.mapValue(SettingsSnapshot::semanticClassifierEnabled)
    override val llmAnalysisEnabled: Flow<Boolean> = state.mapValue(SettingsSnapshot::llmAnalysisEnabled)
    override val llmAutoActionsEnabled: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
    override val externalAutomationEnabled: Flow<Boolean> = state.mapValue(SettingsSnapshot::externalAutomationEnabled)
    override val externalAutomationToken: Flow<String> = kotlinx.coroutines.flow.flowOf("local-only-token")
    override val eventRetentionDays: Flow<Int> = state.mapValue(SettingsSnapshot::eventRetentionDays)
    override val dailyInsightRetentionDays: Flow<Int> = state.mapValue(SettingsSnapshot::dailyInsightRetentionDays)
    override val dynamicColorEnabled: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
    override val notificationContentStorageEnabled: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
    override val contentExcludedPackages: Flow<Set<String>> = kotlinx.coroutines.flow.flowOf(emptySet())

    override suspend fun setFilteringEnabled(enabled: Boolean) = update { copy(filteringEnabled = enabled) }

    override suspend fun setSemanticClassifierEnabled(enabled: Boolean) =
        update { copy(semanticClassifierEnabled = enabled) }

    override suspend fun setLlmAnalysisEnabled(enabled: Boolean) = update { copy(llmAnalysisEnabled = enabled) }

    override suspend fun setLlmAutoActionsEnabled(enabled: Boolean) = Unit

    override suspend fun setExternalAutomationEnabled(enabled: Boolean) =
        update { copy(externalAutomationEnabled = enabled) }

    override suspend fun ensureExternalAutomationToken(): String = "local-only-token"

    override suspend fun rotateExternalAutomationToken(): String = "rotated-local-token"

    override suspend fun setEventRetentionDays(days: Int) = update { copy(eventRetentionDays = days) }

    override suspend fun setDailyInsightRetentionDays(days: Int) = update { copy(dailyInsightRetentionDays = days) }

    override suspend fun setDynamicColorEnabled(enabled: Boolean) = Unit

    override suspend fun setNotificationContentStorageEnabled(enabled: Boolean) = Unit

    override suspend fun setContentExcludedPackages(packageNames: Set<String>) = Unit

    override suspend fun setContentPackageExcluded(
        packageName: String,
        excluded: Boolean,
    ) = Unit

    override suspend fun snapshot(): SettingsSnapshot = state.value

    override suspend fun maintenanceSnapshot(): MaintenanceSettingsSnapshot =
        MaintenanceSettingsSnapshot(
            eventRetentionDays = state.value.eventRetentionDays,
            dailyInsightRetentionDays = state.value.dailyInsightRetentionDays,
        )

    override suspend fun restore(snapshot: SettingsSnapshot) {
        if (failEnabledRestore && snapshot.filteringEnabled) {
            error("settings unavailable")
        }
        state.value = snapshot.copy(llmAutoActionsEnabled = false)
    }

    override suspend fun reset() {
        state.value = SettingsSnapshot()
    }

    private fun update(block: SettingsSnapshot.() -> SettingsSnapshot) {
        state.value = state.value.block()
    }
}

private class BlockingFinalRestoreSettingsRepository(
    private val delegate: InMemoryBackupSettingsRepository,
    private val resetFence: LocalDataResetWriteFence,
) : SettingsRepository by delegate {
    val finalRestoreStarted = CompletableDeferred<Unit>()
    val releaseFinalRestore = CompletableDeferred<Unit>()

    override suspend fun restore(snapshot: SettingsSnapshot) {
        if (snapshot.externalAutomationEnabled) {
            finalRestoreStarted.complete(Unit)
            releaseFinalRestore.await()
        }
        delegate.restore(snapshot)
    }

    override suspend fun restoreIfCurrent(
        snapshot: SettingsSnapshot,
        resetEpoch: LocalDataResetWriteFence.Epoch,
    ) {
        resetFence.writeIfCurrent(resetEpoch) {
            restore(snapshot)
            Unit
        } ?: throw StaleLocalDataWriteException()
    }

    override suspend fun restoreIfCurrentWhileMutationAndMaintenanceLocked(
        snapshot: SettingsSnapshot,
        resetEpoch: LocalDataResetWriteFence.Epoch,
    ) {
        restoreIfCurrent(snapshot, resetEpoch)
    }
}

private class MutationFencedBackupSettingsRepository(
    private val delegate: InMemoryBackupSettingsRepository,
    private val mutationFence: SettingsMutationFence,
) : SettingsRepository by delegate {
    override suspend fun setFilteringEnabled(enabled: Boolean) {
        mutationFence.withLock {
            delegate.setFilteringEnabled(enabled)
        }
    }

    override suspend fun setFilteringEnabledIfCurrent(
        enabled: Boolean,
        resetEpoch: LocalDataResetWriteFence.Epoch,
    ) {
        mutationFence.withLock {
            delegate.setFilteringEnabledIfCurrent(enabled, resetEpoch)
        }
    }

    override suspend fun setFilteringEnabledIfCurrentWhileMutationLocked(
        enabled: Boolean,
        resetEpoch: LocalDataResetWriteFence.Epoch,
    ) {
        delegate.setFilteringEnabledIfCurrent(enabled, resetEpoch)
    }

    override suspend fun snapshot(): SettingsSnapshot =
        mutationFence.withLock {
            delegate.snapshot()
        }

    override suspend fun snapshotWhileMutationLocked(): SettingsSnapshot = delegate.snapshot()

    override suspend fun restoreIfCurrent(
        snapshot: SettingsSnapshot,
        resetEpoch: LocalDataResetWriteFence.Epoch,
    ) {
        mutationFence.withLock {
            delegate.restoreIfCurrent(snapshot, resetEpoch)
        }
    }

    override suspend fun restoreIfCurrentWhileMutationLocked(
        snapshot: SettingsSnapshot,
        resetEpoch: LocalDataResetWriteFence.Epoch,
    ) {
        delegate.restoreIfCurrent(snapshot, resetEpoch)
    }

    override suspend fun restoreIfCurrentWhileMutationAndMaintenanceLocked(
        snapshot: SettingsSnapshot,
        resetEpoch: LocalDataResetWriteFence.Epoch,
    ) {
        delegate.restoreIfCurrent(snapshot, resetEpoch)
    }
}

private fun <T> MutableStateFlow<SettingsSnapshot>.mapValue(transform: (SettingsSnapshot) -> T): Flow<T> =
    map(transform)
