package com.alarmcontrol.data.db

import androidx.room.TypeConverter
import com.alarmcontrol.data.db.model.StoredConditionType
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.db.model.StoredRuleExecutionMode

/** Stores the schema enums as their stable `name` strings. */
class Converters {
    @TypeConverter
    fun fromRuleAction(value: StoredRuleAction): String = value.name

    @TypeConverter
    fun toRuleAction(value: String): StoredRuleAction = StoredRuleAction.valueOf(value)

    @TypeConverter
    fun fromRuleExecutionMode(value: StoredRuleExecutionMode): String = value.name

    @TypeConverter
    fun toRuleExecutionMode(value: String): StoredRuleExecutionMode = StoredRuleExecutionMode.valueOf(value)

    @TypeConverter
    fun fromConditionType(value: StoredConditionType): String = value.name

    @TypeConverter
    fun toConditionType(value: String): StoredConditionType = StoredConditionType.valueOf(value)
}
