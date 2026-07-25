package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.db.model.StoredRuleExecutionMode

/**
 * A persisted user filtering rule. Its leaf conditions live in [RuleConditionEntity] and are
 * recombined into a domain `Rule` by the mapper (see [com.alarmcontrol.data.db.relation.RuleWithConditions]).
 */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    /** Higher priority wins when multiple enabled rules match the same notification. */
    @ColumnInfo(name = "priority") val priority: Int = 0,
    @ColumnInfo(name = "action") val action: StoredRuleAction,
    @ColumnInfo(name = "execution_mode", defaultValue = "'ACTIVE'")
    val executionMode: StoredRuleExecutionMode = StoredRuleExecutionMode.ACTIVE,
    /** Set only when [action] is [StoredRuleAction.SNOOZE]; otherwise `null`. */
    @ColumnInfo(name = "snooze_duration_millis") val snoozeDurationMillis: Long? = null,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
)
