package com.alarmcontrol.data.mapper

import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.data.db.entity.FilteringProfileEntity
import com.alarmcontrol.data.db.relation.ProfileWithRules

fun ProfileWithRules.toDomain(): FilteringProfile =
    FilteringProfile(
        id = profile.id.toString(),
        name = profile.name,
        ruleIds = links.mapTo(mutableSetOf()) { it.ruleId.toString() },
    )

fun FilteringProfile.toEntity(
    id: Long,
    nowMillis: Long,
): FilteringProfileEntity =
    FilteringProfileEntity(
        id = id,
        name = name.trim(),
        createdAtMillis = nowMillis,
        updatedAtMillis = nowMillis,
    )
