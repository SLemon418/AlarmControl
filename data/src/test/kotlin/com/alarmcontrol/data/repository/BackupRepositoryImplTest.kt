package com.alarmcontrol.data.repository

import com.alarmcontrol.core.backup.BackupData
import com.alarmcontrol.core.backup.BackupSemanticFeedback
import com.alarmcontrol.core.backup.BackupSummary
import com.alarmcontrol.core.backup.RestoreMode
import com.alarmcontrol.core.backup.RestoreOptions
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.insights.CategoryCount
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.insights.RuleTriggerCount
import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.core.settings.SettingsSnapshot
import com.alarmcontrol.data.backup.BackupCodec
import com.alarmcontrol.data.backup.BackupCryptor
import com.alarmcontrol.data.db.TransactionRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        )

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
    fun `settings activation failure after database commit leaves destructive gates off`() =
        runTest {
            val desired =
                SettingsSnapshot(
                    filteringEnabled = true,
                    externalAutomationEnabled = true,
                    llmAnalysisEnabled = true,
                )
            settings.failEnabledRestore = true
            val payload =
                BackupCodec.encode(
                    BackupData(
                        rules = emptyList(),
                        dailyInsights = emptyList(),
                        settings = desired,
                    ),
                )

            val result = backup.restore(payload)

            assertTrue(result is DataResult.Failure)
            assertFalse(settings.current.filteringEnabled)
            assertFalse(settings.current.externalAutomationEnabled)
            assertFalse(settings.current.llmAutoActionsEnabled)
        }
}

private class InMemoryBackupSettingsRepository : SettingsRepository {
    private val state = MutableStateFlow(SettingsSnapshot())
    val current: SettingsSnapshot get() = state.value
    var failEnabledRestore = false

    override val filteringEnabled: Flow<Boolean> = state.mapValue(SettingsSnapshot::filteringEnabled)
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

    override suspend fun snapshot(): SettingsSnapshot = state.value

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

private fun <T> MutableStateFlow<SettingsSnapshot>.mapValue(transform: (SettingsSnapshot) -> T): Flow<T> =
    map(transform)
