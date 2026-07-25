package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/** Locally dismissed derived suggestion; contains no notification content. */
@Entity(tableName = "rule_suggestion_dismissals", primaryKeys = ["suggestion_key"])
data class RuleSuggestionDismissalEntity(
    @ColumnInfo(name = "suggestion_key") val suggestionKey: String,
    @ColumnInfo(name = "dismissed_at_millis") val dismissedAtMillis: Long,
)
