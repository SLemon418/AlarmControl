package com.alarmcontrol.data.backup

import com.alarmcontrol.core.backup.BackupData
import com.alarmcontrol.core.filtering.MAX_CONDITION_VALUE_CHARS
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleDefinitionValidator
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.core.profile.MAX_PROFILE_NAME_CHARS

/** Defensive validation for user-selected backup files, performed before a restore transaction. */
internal object BackupValidator {
    fun validate(data: BackupData): BackupData =
        data.also {
            require(it.rules.size <= MAX_RULES) { "Backup contains too many rules" }
            require(it.profiles.size <= MAX_PROFILES) { "Backup contains too many profiles" }
            require(it.dailyInsights.size <= MAX_INSIGHTS) { "Backup contains too many insight rows" }
            require(it.categoryFeedback.size <= MAX_FEEDBACK_ROWS) { "Backup contains too much feedback" }
            require(it.adFeedback.size <= MAX_FEEDBACK_ROWS) { "Backup contains too much ad feedback" }
            require(it.semanticFeedback.size <= MAX_FEEDBACK_ROWS) { "Backup contains too much semantic feedback" }
            require(it.rules.map(Rule::id).all(String::isNotBlank)) { "Every backup rule needs an id" }
            require(
                it.rules
                    .map(Rule::id)
                    .distinct()
                    .size == it.rules.size,
            ) { "Backup rule ids must be unique" }
            require(
                it.dailyInsights
                    .map(DailyInsight::epochDay)
                    .distinct()
                    .size == it.dailyInsights.size,
            ) {
                "Backup insight days must be unique"
            }
            it.rules.forEach(::validateRule)
            validateProfiles(it.profiles, it.rules.mapTo(mutableSetOf(), Rule::id))
            it.dailyInsights.forEach(::validateInsight)
            it.settings?.let { settings ->
                require(settings.eventRetentionDays in RETENTION_RANGE) { "Event retention is invalid" }
                require(settings.dailyInsightRetentionDays in RETENTION_RANGE) { "Insight retention is invalid" }
                require(!settings.llmAutoActionsEnabled || settings.llmAnalysisEnabled) {
                    "LLM auto actions require LLM analysis"
                }
            }
            it.categoryFeedback.forEach { feedback ->
                require(feedback.packageName.isNotBlank() && feedback.packageName.length <= MAX_PACKAGE_CHARS) {
                    "Feedback package is invalid"
                }
                require(feedback.correctedLabel.isNotBlank() && feedback.correctedLabel.length <= MAX_LABEL_CHARS) {
                    "Corrected label is invalid"
                }
                require((feedback.predictedLabel?.length ?: 0) <= MAX_LABEL_CHARS) { "Predicted label is invalid" }
                require(feedback.recordedAtMillis >= 0) { "Feedback timestamp is invalid" }
            }
            it.adFeedback.forEach { feedback ->
                require(feedback.packageName.isNotBlank() && feedback.packageName.length <= MAX_PACKAGE_CHARS) {
                    "Ad feedback package is invalid"
                }
                require(feedback.count in 1..MAX_FEEDBACK_VOTES) { "Ad feedback count is invalid" }
            }
            it.semanticFeedback.forEach { feedback ->
                require(feedback.packageName.isNotBlank() && feedback.packageName.length <= MAX_PACKAGE_CHARS) {
                    "Semantic feedback package is invalid"
                }
                require(feedback.count in 1..MAX_FEEDBACK_VOTES) { "Semantic feedback count is invalid" }
            }
        }

    private fun validateProfiles(
        profiles: List<FilteringProfile>,
        ruleIds: Set<String>,
    ) {
        require(profiles.map(FilteringProfile::id).all(String::isNotBlank)) {
            "Every backup profile needs an id"
        }
        require(profiles.map(FilteringProfile::id).distinct().size == profiles.size) {
            "Backup profile ids must be unique"
        }
        profiles.forEach { profile ->
            require(profile.name.isNotBlank() && profile.name.length <= MAX_PROFILE_NAME_CHARS) {
                "Profile name is invalid"
            }
            require(profile.ruleIds.size <= MAX_RULES) { "Profile contains too many rules" }
            require(profile.ruleIds.all { it in ruleIds }) { "Profile references an unknown rule" }
        }
    }

    private fun validateRule(rule: Rule) {
        RuleDefinitionValidator.requireValid(rule)
    }

    private fun validateInsight(insight: DailyInsight) {
        require(insight.epochDay >= 0) { "Insight day is invalid" }
        require(insight.windowStartMillis >= 0 && insight.windowEndMillis > insight.windowStartMillis) {
            "Insight window is invalid"
        }
        require(insight.totalNotifications >= 0) { "Insight total is invalid" }
        require(insight.mutedCount in 0..insight.totalNotifications) { "Insight muted count is invalid" }
        require(insight.generatedAtMillis >= 0) { "Insight generation time is invalid" }
        val action = insight.actionBreakdown
        require(
            listOf(action.cancelled, action.snoozed, action.loggedOnly, action.kept).all { it >= 0 } &&
                action.total <= insight.totalNotifications,
        ) { "Insight action breakdown is invalid" }
        val monitored = insight.monitoredActionBreakdown
        require(
            listOf(monitored.cancelled, monitored.snoozed, monitored.loggedOnly, monitored.kept).all { it >= 0 } &&
                monitored.total <= insight.totalNotifications,
        ) { "Insight monitor breakdown is invalid" }
        require(insight.topRules.size <= MAX_BREAKDOWN_ROWS) { "Insight has too many rule counts" }
        require(insight.categoryBreakdown.size <= MAX_BREAKDOWN_ROWS) { "Insight has too many category counts" }
        require(insight.channelBreakdown.size <= MAX_BREAKDOWN_ROWS) { "Insight has too many channel counts" }
        require(insight.topRules.all { it.ruleId.isNotBlank() && it.count > 0 }) { "Rule count is invalid" }
        require(
            insight.categoryBreakdown.all { (it.category?.length ?: 0) <= MAX_CONDITION_VALUE_CHARS && it.count > 0 },
        ) {
            "Category count is invalid"
        }
        require(
            insight.channelBreakdown.all {
                it.packageName.isNotBlank() &&
                    it.packageName.length <= MAX_PACKAGE_CHARS &&
                    it.channelId.isNotBlank() &&
                    it.channelId.length <= MAX_CONDITION_VALUE_CHARS &&
                    it.count > 0
            },
        ) { "Channel count is invalid" }
    }

    private const val MAX_RULES = 1_000
    private const val MAX_PROFILES = 500
    private const val MAX_INSIGHTS = 10_000
    private const val MAX_FEEDBACK_ROWS = 25_000
    private const val MAX_FEEDBACK_VOTES = 1_000_000
    private const val MAX_PACKAGE_CHARS = 255
    private const val MAX_LABEL_CHARS = 100
    private const val MAX_BREAKDOWN_ROWS = 1_000
    private val RETENTION_RANGE = 1..3_650
}
