package com.alarmcontrol.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.alarmcontrol.data.db.entity.AutomationAuditEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationAuditDao {
    @Insert
    suspend fun insert(entry: AutomationAuditEntity): Long

    @Query("SELECT * FROM automation_audit ORDER BY requested_at_millis DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AutomationAuditEntity>>

    @Query(
        "DELETE FROM automation_audit WHERE id NOT IN (" +
            "SELECT id FROM automation_audit ORDER BY requested_at_millis DESC, id DESC LIMIT :maxRows)",
    )
    suspend fun trim(maxRows: Int): Int

    @Query("DELETE FROM automation_audit")
    suspend fun deleteAll(): Int

    @Transaction
    suspend fun recordBounded(
        entry: AutomationAuditEntity,
        maxRows: Int,
    ) {
        insert(entry)
        trim(maxRows)
    }
}
