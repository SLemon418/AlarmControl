package com.alarmcontrol.core.profile

/** Maximum profile-name length accepted by editors, persistence, and portable backups. */
const val MAX_PROFILE_NAME_CHARS = 200

/** Storage bounds shared with portable backup validation. */
const val MAX_SAVED_PROFILES = 500
const val MAX_PROFILE_RULE_IDS = 1_000

/** Named group of rule ids that can be toggled together by UI, shortcuts, or automation. */
data class FilteringProfile(
    val id: String = "",
    val name: String,
    val ruleIds: Set<String>,
)
