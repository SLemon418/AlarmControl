package com.alarmcontrol.core.backup

import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.core.settings.SettingsSnapshot

/**
 * A portable snapshot of the user's local data (CLAUDE.md §3): the filtering [rules] tree, named
 * [profiles], and [dailyInsights] history. It is serialized to a structured string the user saves
 * via the Storage Access Framework; nothing is uploaded and no network is involved.
 */
data class BackupData(
    val rules: List<Rule>,
    val dailyInsights: List<DailyInsight>,
    val profiles: List<FilteringProfile> = emptyList(),
    val settings: SettingsSnapshot? = null,
    val categoryFeedback: List<BackupCategoryFeedback> = emptyList(),
    val adFeedback: List<BackupAdFeedback> = emptyList(),
    val semanticFeedback: List<BackupSemanticFeedback> = emptyList(),
)

/** Content-free category learning vote; exported only inside a password-encrypted backup. */
data class BackupCategoryFeedback(
    val packageName: String,
    val predictedLabel: String?,
    val correctedLabel: String,
    val recordedAtMillis: Long,
)

/** Aggregated package-level ad/transactional learning votes; no notification text or reasoning. */
data class BackupAdFeedback(
    val packageName: String,
    val isAdvertisement: Boolean,
    val count: Int,
)

/** Aggregated package-level seven-way semantic vote; exported only when encrypted. */
data class BackupSemanticFeedback(
    val packageName: String,
    val intent: SemanticIntent,
    val count: Int,
)

enum class RestoreMode { MERGE, REPLACE }

/** User-reviewed sections applied by restore. Rules and profiles stay coupled to preserve references. */
data class RestoreOptions(
    val mode: RestoreMode = RestoreMode.MERGE,
    val rulesAndProfiles: Boolean = true,
    val dailyInsights: Boolean = true,
    val settings: Boolean = true,
    val learningFeedback: Boolean = false,
)

/** Validated, read-only description shown before any local data is changed. */
data class BackupPreview(
    val encrypted: Boolean,
    val rules: Int,
    val profiles: Int,
    val dailyInsights: Int,
    val hasSettings: Boolean,
    val categoryFeedback: Int,
    val adFeedbackVotes: Int,
)

/** Outcome counts from a restore, surfaced to the user. */
data class BackupSummary(
    val rulesRestored: Int,
    val insightsRestored: Int,
    val profilesRestored: Int = 0,
    val settingsRestored: Boolean = false,
    val feedbackRestored: Int = 0,
)
