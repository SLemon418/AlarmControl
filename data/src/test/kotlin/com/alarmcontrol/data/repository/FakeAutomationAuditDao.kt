package com.alarmcontrol.data.repository

import com.alarmcontrol.data.db.dao.AutomationAuditDao
import com.alarmcontrol.data.db.entity.AutomationAuditEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeAutomationAuditDao : AutomationAuditDao {
    private val rows = MutableStateFlow<List<AutomationAuditEntity>>(emptyList())
    private var nextId = 1L
    var lastTrimLimit = 0

    override suspend fun insert(entry: AutomationAuditEntity): Long =
        nextId++.also { id -> rows.value = rows.value + entry.copy(id = id) }

    override fun observeRecent(limit: Int): Flow<List<AutomationAuditEntity>> =
        rows.map { values -> values.sortedByDescending { it.requestedAtMillis }.take(limit) }

    override suspend fun trim(maxRows: Int): Int {
        lastTrimLimit = maxRows
        val before = rows.value.size
        rows.value = rows.value.sortedByDescending { it.id }.take(maxRows)
        return before - rows.value.size
    }

    override suspend fun deleteAll(): Int = rows.value.size.also { rows.value = emptyList() }
}
