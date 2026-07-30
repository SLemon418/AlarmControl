package com.alarmcontrol.data.repository

import com.alarmcontrol.core.automation.AutomationAuditEntry
import com.alarmcontrol.core.automation.AutomationAuditRepository
import com.alarmcontrol.core.automation.AutomationOperation
import com.alarmcontrol.core.automation.AutomationOutcome
import com.alarmcontrol.core.automation.AutomationSource
import com.alarmcontrol.core.automation.AutomationTarget
import com.alarmcontrol.core.privacy.LocalDataResetWriteFence
import com.alarmcontrol.core.privacy.StaleLocalDataWriteException
import com.alarmcontrol.data.db.dao.AutomationAuditDao
import com.alarmcontrol.data.db.entity.AutomationAuditEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class AutomationAuditRepositoryImpl
    @Inject
    constructor(
        private val dao: AutomationAuditDao,
        private val localDataResetWriteFence: LocalDataResetWriteFence = LocalDataResetWriteFence(),
    ) : AutomationAuditRepository {
        override suspend fun record(entry: AutomationAuditEntry) {
            recordIfCurrent(entry, localDataResetWriteFence.captureEpoch())
        }

        override suspend fun recordIfCurrent(
            entry: AutomationAuditEntry,
            resetEpoch: LocalDataResetWriteFence.Epoch,
        ) {
            localDataResetWriteFence.writeIfCurrent(resetEpoch) {
                dao.recordBounded(entry.toEntity(), MAX_ROWS)
                Unit
            } ?: throw StaleLocalDataWriteException()
        }

        override fun observeRecent(limit: Int): Flow<List<AutomationAuditEntry>> {
            require(limit in 1..MAX_ROWS) { "Audit limit is out of range" }
            return dao.observeRecent(limit).map { rows -> rows.map(AutomationAuditEntity::toDomain) }
        }

        private companion object {
            const val MAX_ROWS = 200
        }
    }

private fun AutomationAuditEntry.toEntity(): AutomationAuditEntity =
    AutomationAuditEntity(
        requestedAtMillis = requestedAtMillis,
        source = source.name,
        operation = operation.name,
        targetType = target.name,
        outcome = outcome.name,
        changedCount = changedCount,
    )

private fun AutomationAuditEntity.toDomain(): AutomationAuditEntry =
    AutomationAuditEntry(
        id = id.toString(),
        requestedAtMillis = requestedAtMillis,
        source = AutomationSource.valueOf(source),
        operation = AutomationOperation.valueOf(operation),
        target = AutomationTarget.valueOf(targetType),
        outcome = AutomationOutcome.valueOf(outcome),
        changedCount = changedCount,
    )
