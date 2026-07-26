package com.alarmcontrol.ui.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.cash.turbine.test
import com.alarmcontrol.core.automation.AutomationAuditRepository
import com.alarmcontrol.core.backup.BackupPreview
import com.alarmcontrol.core.backup.BackupRepository
import com.alarmcontrol.core.backup.BackupSummary
import com.alarmcontrol.core.filtering.NotificationHistoryRepository
import com.alarmcontrol.core.privacy.ClearedDataCounts
import com.alarmcontrol.core.privacy.LocalDataRepository
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.ml.llm.LlmInitState
import com.alarmcontrol.ml.llm.OnDeviceLlmManager
import com.alarmcontrol.service.AppHealthProvider
import com.alarmcontrol.service.AppHealthSnapshot
import com.alarmcontrol.testsupport.MainDispatcherRule
import com.alarmcontrol.testsupport.awaitUntil
import com.alarmcontrol.ui.app.AppIdentityResolver
import com.alarmcontrol.ui.app.AppIdentityUi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeSettingsRepository(enabled = false)
    private val llmManager = mockk<OnDeviceLlmManager>(relaxed = true)
    private val llmState = MutableStateFlow<LlmInitState>(LlmInitState.Idle)
    private val localDataRepository = mockk<LocalDataRepository>(relaxed = true)
    private val appHealthProvider = mockk<AppHealthProvider>()
    private val backupRepository = mockk<BackupRepository>(relaxed = true)
    private val automationAuditRepository = mockk<AutomationAuditRepository>(relaxed = true)
    private val notificationHistoryRepository = mockk<NotificationHistoryRepository>()
    private val appIdentityResolver = mockk<AppIdentityResolver>()
    private val appContext = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)

    init {
        every { llmManager.initState } returns llmState
        io.mockk.coEvery { llmManager.removeModel() } returns DataResult.Success(Unit)
        every { appHealthProvider.snapshot() } returns
            AppHealthSnapshot(notificationAccessGranted = true, batteryOptimizationExempt = false)
        every { appContext.contentResolver } returns contentResolver
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
        )

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
    fun `disabling encrypted content storage blocks new capture before deleting payloads`() =
        runTest {
            repository.setNotificationContentStorageEnabled(true)
            repository.operationLog.clear()
            coEvery { localDataRepository.clearStoredNotificationContent() } answers {
                repository.operationLog += "clear-content"
                ClearedDataCounts()
            }
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.notificationContentStorageEnabled }
                vm.setNotificationContentStorageEnabled(false)
                assertFalse(awaitUntil { !it.notificationContentStorageEnabled }.notificationContentStorageEnabled)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { localDataRepository.clearStoredNotificationContent() }
            assertTrue(
                repository.operationLog.indexOf("content-storage:false") <
                    repository.operationLog.indexOf("clear-content"),
            )
        }

    @Test
    fun `excluding a package blocks future capture before deleting its ciphertext`() =
        runTest {
            coEvery { localDataRepository.clearStoredNotificationContentForPackage("com.example.bank") } answers {
                repository.operationLog += "clear-package"
                ClearedDataCounts()
            }
            val vm = viewModel()

            vm.setContentPackageExcluded("com.example.bank", excluded = true)

            assertTrue(
                repository.operationLog.indexOf("excluded-packages:com.example.bank") <
                    repository.operationLog.indexOf("clear-package"),
            )
            coVerify(exactly = 1) {
                localDataRepository.clearStoredNotificationContentForPackage("com.example.bank")
            }
        }

    @Test
    fun `LLM analysis opt in initializes and opt out closes the local engine`() =
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

            coVerify { llmManager.initialize() }
            coVerify { llmManager.close() }
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
                vm.importBackupFrom(uri, null)
                val preview = awaitUntil { it.backupPreview != null }.backupPreview!!
                assertTrue(preview.rules == 2 && preview.dailyInsights == 3)
                vm.confirmRestore()
                awaitUntil { it.backupPreview == null && it.userMessage != null }
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { backupRepository.preview(serialized, null) }
            coVerify(exactly = 1) {
                backupRepository.restore(
                    serialized = serialized,
                    passphrase = null,
                    options = match { it.rulesAndProfiles && it.dailyInsights && it.settings },
                )
            }
        }
}
