package com.alarmcontrol.core.automation

import kotlinx.coroutines.flow.Flow

enum class AutomationSource { EXTERNAL, QUICK_SETTINGS, SHORTCUT, IN_APP }

enum class AutomationOperation { ENABLE, DISABLE, TOGGLE }

enum class AutomationTarget { MASTER, PROFILE }

enum class AutomationOutcome { APPLIED, NO_CHANGE, DISABLED, UNAUTHORIZED, THROTTLED, INVALID, NOT_FOUND }

/** Content-free automation outcome. Target ids/names and notification data are deliberately absent. */
data class AutomationAuditEntry(
    val requestedAtMillis: Long,
    val source: AutomationSource,
    val operation: AutomationOperation,
    val target: AutomationTarget,
    val outcome: AutomationOutcome,
    val changedCount: Int,
    val id: String = "",
)

/** Local-only bounded audit trail for automation requests. */
interface AutomationAuditRepository {
    suspend fun record(entry: AutomationAuditEntry)

    fun observeRecent(limit: Int): Flow<List<AutomationAuditEntry>>
}
