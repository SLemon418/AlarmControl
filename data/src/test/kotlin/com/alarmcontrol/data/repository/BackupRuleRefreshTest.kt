package com.alarmcontrol.data.repository

import com.alarmcontrol.core.backup.BackupData
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.core.settings.FilteringActionGate
import com.alarmcontrol.data.backup.BackupCodec
import com.alarmcontrol.data.db.TransactionRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRuleRefreshTest {
    private val ruleDao = FakeRuleDao()
    private val dailyDao = FakeDailyInsightDao()
    private val profileDao = FakeProfileDao()
    private val feedbackDao = FakeCategoryFeedbackDao()
    private val llmObservationDao = FakeLlmObservationDao()
    private val settings = InMemoryBackupSettingsRepository()

    @Test
    fun `successful rule restore requests a fresh listener snapshot`() =
        runTest {
            val gate = readyGate()
            val repository = repository(ImmediateTransactionRunner(), gate)

            assertTrue(repository.restore(rulePayload()) is DataResult.Success)

            assertEquals(1L, gate.ruleRefreshRequests.value)
            assertFalse(gate.runIfAllowed {})
        }

    @Test
    fun `committed rule restore publishes refresh when result delivery is cancelled`() =
        runTest {
            val gate = readyGate()
            val commitThenCancel =
                object : TransactionRunner {
                    override suspend fun <T> run(block: suspend () -> T): T {
                        block()
                        throw CancellationException("cancelled after commit")
                    }
                }
            val repository = repository(commitThenCancel, gate)

            val failure = runCatching { repository.restore(rulePayload()) }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(listOf("Restored"), ruleDao.getRulesWithConditions().map { it.rule.name })
            assertEquals(1L, gate.ruleRefreshRequests.value)
            assertFalse(gate.runIfAllowed {})
        }

    private fun repository(
        transactionRunner: TransactionRunner,
        gate: FilteringActionGate,
    ) = BackupRepositoryImpl(
        transactionRunner,
        ruleDao,
        dailyDao,
        profileDao,
        feedbackDao,
        llmObservationDao,
        settings,
        gate,
    )

    private fun readyGate() =
        FilteringActionGate().apply {
            initializeFromPersistedState(true)
            acknowledgeRuleRefresh(ruleRefreshRequests.value)
        }

    private fun rulePayload(): String =
        BackupCodec.encode(
            BackupData(
                rules =
                    listOf(
                        Rule(
                            id = "remote",
                            name = "Restored",
                            condition = Condition.PackageEquals("com.restored"),
                            action = RuleAction.Keep,
                        ),
                    ),
                dailyInsights = emptyList(),
            ),
        )
}
