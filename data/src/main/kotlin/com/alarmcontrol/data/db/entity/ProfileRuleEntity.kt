package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "profile_rules",
    primaryKeys = ["profile_id", "rule_id"],
    foreignKeys = [
        ForeignKey(
            entity = FilteringProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["rule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rule_id")],
)
data class ProfileRuleEntity(
    @ColumnInfo(name = "profile_id") val profileId: Long,
    @ColumnInfo(name = "rule_id") val ruleId: Long,
)
