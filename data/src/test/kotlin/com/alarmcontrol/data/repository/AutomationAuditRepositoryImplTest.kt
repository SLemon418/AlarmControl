package com.alarmcontrol.data.repository

import com.alarmcontrol.core.automation.AutomationAuditEntry
import com.alarmcontrol.core.automation.AutomationOperation
import com.alarmcontrol.core.automation.AutomationOutcome
import com.alarmcontrol.core.automation.AutomationSource
import com.alarmcontrol.core.automation.AutomationTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationAuditRepositoryImplTest {
    @Test
    fun `records and maps a bounded content-free audit entry`() =
        runTest {
            val dao = FakeAutomationAuditDao()
            val repository = AutomationAuditRepositoryImpl(dao)
            val entry =
                AutomationAuditEntry(
                    requestedAtMillis = 123,
                    source = AutomationSource.EXTERNAL,
                    operation = AutomationOperation.DISABLE,
                    target = AutomationTarget.PROFILE,
                    outcome = AutomationOutcome.UNAUTHORIZED,
                    changedCount = 0,
                )

            repository.record(entry)

            assertEquals(
                entry,
                repository
                    .observeRecent(1)
                    .first()
                    .single()
                    .copy(id = ""),
            )
            assertEquals(200, dao.lastTrimLimit)
        }

    @Test
    fun `clock rollback keeps the newly inserted audit row inside the cap`() =
        runTest {
            val dao = FakeAutomationAuditDao()
            val repository = AutomationAuditRepositoryImpl(dao)
            repeat(200) { index ->
                repository.record(entry(requestedAtMillis = 10_000L + index))
            }

            repository.record(entry(requestedAtMillis = 1))

            val retained = repository.observeRecent(200).first()
            assertEquals(200, retained.size)
            assertEquals(1, retained.count { it.requestedAtMillis == 1L })
            assertEquals(0, retained.count { it.requestedAtMillis == 10_000L })
        }

    private fun entry(requestedAtMillis: Long): AutomationAuditEntry =
        AutomationAuditEntry(
            requestedAtMillis = requestedAtMillis,
            source = AutomationSource.EXTERNAL,
            operation = AutomationOperation.DISABLE,
            target = AutomationTarget.PROFILE,
            outcome = AutomationOutcome.UNAUTHORIZED,
            changedCount = 0,
        )
}
