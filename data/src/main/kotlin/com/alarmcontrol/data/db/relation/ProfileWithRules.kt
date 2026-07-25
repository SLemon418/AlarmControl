package com.alarmcontrol.data.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.alarmcontrol.data.db.entity.FilteringProfileEntity
import com.alarmcontrol.data.db.entity.ProfileRuleEntity

data class ProfileWithRules(
    @Embedded val profile: FilteringProfileEntity,
    @Relation(parentColumn = "id", entityColumn = "profile_id")
    val links: List<ProfileRuleEntity>,
)
