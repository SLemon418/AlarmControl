package com.alarmcontrol.ui.settings

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.cash.turbine.test
import com.alarmcontrol.R
import com.alarmcontrol.core.automation.AutomationAuditRepository
import com.alarmcontrol.core.backup.BackupPreview
import com.alarmcontrol.core.backup.BackupRepository
import com.alarmcontrol.core.backup.BackupSummary
import com.alarmcontrol.core.filtering.NotificationHistoryRepository
import com.alarmcontrol.core.privacy.ClearedDataCounts
import com.alarmcontrol.core.privacy.LocalDataRepository
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.ml.llm.LlmBackgroundAnalysisEligibility
import com.alarmcontrol.ml.llm.LlmInitState
import com.alarmcontrol.ml.llm.LlmModelInfo
import com.alarmcontrol.ml.llm.OnDeviceLlmManager
import com.alarmcontrol.service.AppHealthProvider
import com.alarmcontrol.service.AppHealthSnapshot
import com.alarmcontrol.testsupport.MainDispatcherRule
import com.alarmcontrol.testsupport.awaitUntil
import com.alarmcontrol.ui.UiText
import com.alarmcontrol.ui.app.AppIdentityResolver
import com.alarmcontrol.ui.app.AppIdentityUi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeSettingsRepository(enabled = false)
    private val llmManager = mockk<OnDeviceLlmManager>(relaxed = true)
    private val llmState = MutableStateFlow<LlmInitState>(LlmInitState.Idle)
    private val llmModelInfo = MutableStateFlow<LlmModelInfo?>(null)
    private val localDataRepository = mockk<LocalDataRepository>(relaxed = true)
    private val appHealthProvider = mockk<AppHealthProvider>()
    private val backupRepository = mockk<BackupRepository>(relaxed = true)
    private val automationAuditRepository = mockk<AutomationAuditRepository>(relaxed = true)
    private val notificationHistoryRepository = mockk<NotificationHistoryRepository>()
    private val appIdentityResolver = mockk<AppIdentityResolver>()
    private val appContext = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val clipboard = mockk<ClipboardManager>(relaxed = true)
    private val applicationScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.dispatcher)

    init {
        every { llmManager.initState } returns llmState
        every { llmManager.modelInfo } returns llmModelInfo
        every {
            llmManager.backgroundAnalysisEligibility
        } returns LlmBackgroundAnalysisEligibility.VERIFIED_COMPATIBLE
        io.mockk.coEvery { llmManager.removeModel() } returns DataResult.Success(Unit)
        every { appHealthProvider.snapshot() } returns
            AppHealthSnapshot(notificationAccessGranted = true, batteryOptimizationExempt = false)
        every { appContext.contentResolver } returns contentResolver
        every { appContext.getSystemService(ClipboardManager::class.java) } returns clipboard
        every { appContext.getString(R.string.settings_automation_token) } returns "Automation token"
        every { automationAuditRepository.observeRecent(any()) } returns flowOf(emptyList())
        every { notificationHistoryRepository.observeSources(any()) } returns flowOf(emptyList())
        every { appIdentityResolver.resolve(any()) } answers {
            AppIdentityUi(firstArg(), null)
        }
    }

    private fun viewModel() =
        SettingsViewModel(
            repository,
            automationAuditRepository,
            backupRepository,
            localDataRepository,
            notificationHistoryRepository,
            llmManager,
            appHealthProvider,
            appIdentityResolver,
            appContext,
            mainDispatcherRule.dispatcher,
            applicationScope,
        )

    @After
    fun tearDown() {
        applicationScope.cancel()
    }

    @Test
    fun `state reflects the stored preference`() =
        runTest {
            repository.setExternalAutomationEnabled(true)

            viewModel().uiState.test {
                val state = awaitUntil { it.externalAutomationEnabled }
                assertTrue(state.externalAutomationEnabled)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `copying automation token marks the process-owned clipboard entry sensitive`() {
        viewModel().copyAutomationToken("local-secret")

        verify {
            clipboard.setPrimaryClip(
                match { clip ->
                    clip.getItemAt(0).text.toString() == "local-secret" &&
                        clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
                },
            )
        }
    }

    @Test
    fun `toggling on persists and updates state`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.externalAutomationEnabled } // initial: off
                vm.setExternalAutomationEnabled(true)
                val updated = awaitUntil { it.externalAutomationEnabled }
                assertTrue(updated.externalAutomationEnabled)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `master filtering state is reactive and can be paused`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                assertTrue(awaitUntil { it.filteringEnabled }.filteringEnabled)
                vm.setFilteringEnabled(false)
                val paused = awaitUntil { !it.filteringEnabled }
                assertFalse(paused.filteringEnabled)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `dynamic color preference is reactive`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.dynamicColorEnabled }
                vm.setDynamicColorEnabled(true)
                assertTrue(awaitUntil { it.dynamicColorEnabled }.dynamicColorEnabled)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `disabling encrypted content storage uses the atomic settings boundary`() =
        runTest {
            repository.setNotificationContentStorageEnabled(true)
            repository.operationLog.clear()
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.notificationContentStorageEnabled }
                vm.setNotificationContentStorageEnabled(false)
                assertFalse(awaitUntil { !it.notificationContentStorageEnabled }.notificationContentStorageEnabled)
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(listOf("content-storage:false"), repository.operationLog)
            coVerify(exactly = 0) { localDataRepository.reconcileStoredNotificationContentPolicy() }
        }

    @Test
    fun `content deletion failure keeps storage enabled and shows a specific error`() =
        runTest {
            repository.setNotificationContentStorageEnabled(true)
            repository.beforeSetNotificationContentStorageEnabled = { enabled ->
                if (!enabled) throw IOException("content deletion failed")
            }
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.notificationContentStorageEnabled }
                vm.setNotificationContentStorageEnabled(false)
                val failed = awaitUntil { it.userMessage != null }

                assertTrue(failed.notificationContentStorageEnabled)
                assertEquals(
                    UiText.Resource(R.string.message_content_storage_disable_failed),
                    failed.userMessage,
                )
                cancelAndIgnoreRemainingEvents()
            }

            assertTrue(repository.notificationContentStorageEnabled.first())
            coVerify(exactly = 0) { localDataRepository.reconcileStoredNotificationContentPolicy() }
        }

    @Test
    fun `excluding a package blocks future capture before deleting its ciphertext`() =
        runTest {
            coEvery { localDataRepository.reconcileStoredNotificationContentPolicy() } answers {
                repository.operationLog += "reconcile-content"
                ClearedDataCounts()
            }
            val vm = viewModel()

            vm.setContentPackageExcluded("com.example.bank", excluded = true)

            assertTrue(
                repository.operationLog.indexOf("excluded-package:com.example.bank:true") <
                    repository.operationLog.indexOf("reconcile-content"),
            )
            assertTrue(repository.operationLog.none { it.startsWith("excluded-packages:") })
            coVerify(exactly = 1) { localDataRepository.reconcileStoredNotificationContentPolicy() }
        }

    @Test
    fun `concurrent package exclusions preserve every user change`() =
        runTest {
            val firstUpdateStarted = CompletableDeferred<Unit>()
            val releaseFirstUpdate = CompletableDeferred<Unit>()
            repository.beforeSetContentExcludedPackages = { packages ->
                if (packages == setOf("com.example.one")) {
                    firstUpdateStarted.complete(Unit)
                    releaseFirstUpdate.await()
                }
            }
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.contentExcludedPackages.isEmpty() }
                vm.setContentPackageExcluded("com.example.one", excluded = true)
                firstUpdateStarted.await()
                vm.setContentPackageExcluded("com.example.two", excluded = true)
                releaseFirstUpdate.complete(Unit)

                assertTrue(
                    awaitUntil {
                        it.contentExcludedPackages ==
                            setOf("com.example.one", "com.example.two")
                    }.contentExcludedPackages.size == 2,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `LLM analysis opt in stays lazy and opt out closes the local engine`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.llmAnalysisEnabled }
                vm.setLlmAnalysisEnabled(true)
                assertTrue(awaitUntil { it.llmAnalysisEnabled }.llmAnalysisEnabled)
                vm.setLlmAnalysisEnabled(false)
                assertFalse(awaitUntil { !it.llmAnalysisEnabled }.llmAnalysisEnabled)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 0) { llmManager.initialize() }
            coVerify(exactly = 1) { llmManager.close() }
        }

    @Test
    fun `unverified model profile cannot enable background LLM analysis`() =
        runTest {
            every {
                llmManager.backgroundAnalysisEligibility
            } returns LlmBackgroundAnalysisEligibility.UNVERIFIED
            val vm = viewModel()

            vm.uiState.test {
                val unavailable = awaitUntil { !it.llmBackgroundAnalysisAvailable }
                assertFalse(unavailable.llmAnalysisEnabled)
                vm.setLlmAnalysisEnabled(true)
                assertFalse(repository.llmAnalysisEnabled.first())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `retention choices are persisted and reflected`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.eventRetentionDays == 30 }
                vm.setEventRetentionDays(90)
                vm.setDailyInsightRetentionDays(730)
                val state = awaitUntil { it.eventRetentionDays == 90 && it.dailyInsightRetentionDays == 730 }
                assertTrue(state.eventRetentionDays == 90)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `model copy progress maps to stable presentation state`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.llmModelStatus == LlmModelUiStatus.NOT_LOADED }
                llmState.value = LlmInitState.Installing(copiedBytes = 50, totalBytes = 100)
                val installing = awaitUntil { it.llmModelStatus == LlmModelUiStatus.INSTALLING }
                assertTrue(installing.llmModelCopiedBytes == 50L)
                assertTrue(installing.llmModelTotalBytes == 100L)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `verified model fingerprint maps to presentation state`() =
        runTest {
            val vm = viewModel()
            val info = LlmModelInfo("a".repeat(64), 1_024)

            vm.uiState.test {
                awaitUntil { it.llmModelStatus == LlmModelUiStatus.NOT_LOADED }
                llmModelInfo.value = info
                llmState.value = LlmInitState.Ready
                val ready = awaitUntil { it.llmModelStatus == LlmModelUiStatus.READY && it.llmModelSha256 != null }
                assertTrue(ready.llmModelSha256 == info.sha256)
                assertTrue(ready.llmModelSizeBytes == info.sizeBytes)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `refreshing app health surfaces notification access and battery policy`() =
        runTest {
            every { appHealthProvider.snapshot() } returns
                AppHealthSnapshot(notificationAccessGranted = false, batteryOptimizationExempt = true)
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.llmModelStatus == LlmModelUiStatus.NOT_LOADED }
                vm.refreshAppHealth()
                val state = awaitUntil { !it.notificationAccessGranted }
                assertTrue(state.batteryOptimizationExempt)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `clear all removes imported model before clearing local repositories`() =
        runTest {
            val vm = viewModel()

            vm.clearAllData()

            assertTrue(repository.operationLog.indexOf("filtering:false") < repository.operationLog.indexOf("reset"))
            coVerify { llmManager.removeModel() }
            coVerify { localDataRepository.clearAllDatabaseData() }
        }

    @Test
    fun `clear all continues database and settings cleanup after model failures`() =
        runTest {
            coEvery { llmManager.close() } throws IllegalStateException("engine busy")
            coEvery { llmManager.removeModel() } returns
                DataResult.Failure(IllegalStateException("model locked"))
            val vm = viewModel()

            vm.clearAllData()

            coVerify(exactly = 1) { localDataRepository.clearAllDatabaseData() }
            assertTrue("reset" in repository.operationLog)
            assertFalse(repository.filteringEnabled.first())
        }

    @Test
    fun `clear all keeps filtering disabled when database deletion fails`() =
        runTest {
            coEvery { localDataRepository.clearAllDatabaseData() } throws
                IllegalStateException("database busy")
            val vm = viewModel()

            vm.clearAllData()

            assertFalse(repository.filteringEnabled.first())
            assertTrue("reset" in repository.operationLog)
            coVerify(exactly = 1) { localDataRepository.clearAllDatabaseData() }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `prepared encrypted export preserves its password and feedback option until SAF returns`() =
        runTest {
            val uri = mockk<Uri>()
            val output = ByteArrayOutputStream()
            val passphrase = "private-password".toCharArray()
            var receivedPassphrase: CharArray? = null
            every { contentResolver.openOutputStream(uri) } returns output
            coEvery { backupRepository.export(any(), includeLearningFeedback = true) } coAnswers {
                receivedPassphrase = firstArg<CharArray?>()?.copyOf()
                """{"version":6}"""
            }
            val vm = viewModel()

            vm.prepareBackupExport(passphrase, includeLearningFeedback = true)
            vm.completeBackupExport(uri)
            advanceUntilIdle()

            assertTrue(receivedPassphrase.contentEquals("private-password".toCharArray()))
            assertTrue(output.toString(Charsets.UTF_8.name()) == """{"version":6}""")
            assertTrue(passphrase.all { it == '\u0000' })
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SAF export result without an in-memory operation fails closed`() =
        runTest {
            val uri = mockk<Uri>()
            val vm = viewModel()

            vm.completeBackupExport(uri)
            advanceUntilIdle()

            coVerify(exactly = 0) { backupRepository.export(any(), any()) }
            verify(exactly = 0) { contentResolver.openOutputStream(any()) }
        }

    @Test
    fun `SAF import result without an in-memory operation fails closed`() =
        runTest {
            val uri = mockk<Uri>()
            val vm = viewModel()

            vm.completeBackupImport(uri)

            verify(exactly = 0) { contentResolver.openInputStream(any()) }
            coVerify(exactly = 0) { backupRepository.preview(any(), any()) }
        }

    @Test
    fun `cancelled SAF selection wipes the pending password without exporting`() =
        runTest {
            val passphrase = "private-password".toCharArray()
            val vm = viewModel()

            vm.prepareBackupExport(passphrase, includeLearningFeedback = false)
            vm.completeBackupExport(null)

            assertTrue(passphrase.all { it == '\u0000' })
            coVerify(exactly = 0) { backupRepository.export(any(), any()) }
        }

    @Test
    fun `restore message prioritizes settings review warning over restored counts`() {
        val result =
            DataResult.Success(
                BackupSummary(
                    rulesRestored = 2,
                    insightsRestored = 1,
                    profilesRestored = 3,
                    feedbackRestored = 4,
                    settingsReviewRequired = true,
                    insightConflictsSkipped = 5,
                ),
            )

        assertEquals(
            com.alarmcontrol.ui.uiText(R.string.message_backup_restored_settings_review),
            result.restoreMessage(),
        )
    }

    @Test
    fun `restore message reports existing insight days kept during merge`() {
        val result =
            DataResult.Success(
                BackupSummary(
                    rulesRestored = 2,
                    insightsRestored = 1,
                    profilesRestored = 3,
                    feedbackRestored = 4,
                    insightConflictsSkipped = 5,
                ),
            )

        assertEquals(
            com.alarmcontrol.ui.uiText(
                R.string.message_backup_restored_with_existing_days,
                2,
                3,
                1,
                4,
                5,
            ),
            result.restoreMessage(),
        )
    }

    @Test
    fun `backup import previews first and restores only after confirmation`() =
        runTest {
            val uri = mockk<Uri>()
            val serialized = "local backup"
            every { contentResolver.openInputStream(uri) } returns serialized.byteInputStream()
            coEvery { backupRepository.preview(serialized, null) } returns
                DataResult.Success(
                    BackupPreview(
                        encrypted = false,
                        rules = 2,
                        profiles = 1,
                        dailyInsights = 3,
                        hasSettings = true,
                        categoryFeedback = 0,
                        adFeedbackVotes = 0,
                    ),
                )
            coEvery { backupRepository.restore(any(), any(), any()) } returns
                DataResult.Success(BackupSummary(2, 3, 1, settingsRestored = true))
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.llmModelStatus == LlmModelUiStatus.NOT_LOADED }
                vm.prepareBackupImport(null)
                vm.completeBackupImport(uri)
                val preview = awaitUntil { it.backupPreview != null }.backupPreview!!
                assertTrue(preview.rules == 2 && preview.dailyInsights == 3)
                vm.updateRestoreSelection(preview.selection.copy(learningFeedback = true))
                vm.confirmRestore()
                awaitUntil { it.backupPreview == null && it.userMessage != null }
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { backupRepository.preview(serialized, null) }
            coVerify(exactly = 1) {
                backupRepository.restore(
                    serialized = serialized,
                    passphrase = null,
                    options =
                        match {
                            it.rulesAndProfiles &&
                                it.dailyInsights &&
                                it.settings &&
                                !it.learningFeedback
                        },
                )
            }
        }

    @Test
    fun `failed second backup selection clears the previous restore candidate`() =
        runTest {
            val firstUri = mockk<Uri>()
            val secondUri = mockk<Uri>()
            val firstBackup = "valid local backup"
            val secondBackup = "invalid local backup"
            val suppliedPassphrase = "password".toCharArray()
            var retainedPassphrase: CharArray? = null
            var receivedPassphrase: CharArray? = null
            every { contentResolver.openInputStream(firstUri) } returns firstBackup.byteInputStream()
            every { contentResolver.openInputStream(secondUri) } returns secondBackup.byteInputStream()
            coEvery { backupRepository.preview(firstBackup, null) } returns
                DataResult.Success(
                    BackupPreview(
                        encrypted = false,
                        rules = 1,
                        profiles = 0,
                        dailyInsights = 0,
                        hasSettings = false,
                        categoryFeedback = 0,
                        adFeedbackVotes = 0,
                    ),
                )
            coEvery { backupRepository.preview(secondBackup, any()) } coAnswers {
                retainedPassphrase = secondArg()
                receivedPassphrase = secondArg<CharArray?>()?.copyOf()
                error("Storage failure")
            }
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.llmModelStatus == LlmModelUiStatus.NOT_LOADED }
                vm.prepareBackupImport(null)
                vm.completeBackupImport(firstUri)
                awaitUntil { it.backupPreview != null }

                vm.prepareBackupImport(suppliedPassphrase)
                vm.completeBackupImport(secondUri)
                awaitUntil { it.backupPreview == null && it.userMessage != null }
                vm.confirmRestore()
                cancelAndIgnoreRemainingEvents()
            }

            assertTrue(suppliedPassphrase.all { it == '\u0000' })
            assertTrue(retainedPassphrase?.all { it == '\u0000' } == true)
            assertTrue(receivedPassphrase.contentEquals("password".toCharArray()))
            coVerify(exactly = 0) { backupRepository.restore(any(), any(), any()) }
        }
}
