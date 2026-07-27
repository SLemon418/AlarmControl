package com.alarmcontrol.data.backup

import com.alarmcontrol.core.backup.BackupData
import com.alarmcontrol.core.filtering.MAX_CONDITION_VALUE_CHARS
import com.alarmcontrol.core.filtering.MAX_SAVED_RULES
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleDefinitionValidator
import com.alarmcontrol.core.insights.ActionBreakdown
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.insights.MAX_SUPPORTED_INSIGHT_EPOCH_DAY
import com.alarmcontrol.core.insights.RuleTriggerCount
import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.core.profile.MAX_PROFILE_NAME_CHARS
import com.alarmcontrol.core.profile.MAX_PROFILE_RULE_IDS
import com.alarmcontrol.core.profile.MAX_SAVED_PROFILES
import java.util.Locale

/** Defensive validation for user-selected backup files, performed before a restore transaction. */
internal object BackupValidator {
    fun validate(data: BackupData): BackupData =
        data.also {
            require(it.rules.size <= MAX_SAVED_RULES) { "Backup contains too many rules" }
            require(it.profiles.size <= MAX_SAVED_PROFILES) { "Backup contains too many profiles" }
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
            requireGroupedFeedbackFits(
                it.adFeedback.groupBy { feedback -> feedback.packageName to feedback.isAdvertisement },
                "Ad feedback total is invalid",
                count = { feedback -> feedback.count },
            )
            requireGroupedFeedbackFits(
                it.semanticFeedback.groupBy { feedback -> feedback.packageName to feedback.intent },
                "Semantic feedback total is invalid",
                count = { feedback -> feedback.count },
            )
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
        require(
            profiles
                .map { it.name.trim().lowercase(Locale.ROOT) }
                .distinct()
                .size == profiles.size,
        ) { "Backup profile names must be unique" }
        profiles.forEach { profile ->
            require(
                profile.name.isNotBlank() &&
                    profile.name == profile.name.trim() &&
                    profile.name.length <= MAX_PROFILE_NAME_CHARS,
            ) {
                "Profile name is invalid"
            }
            require(profile.ruleIds.size <= MAX_PROFILE_RULE_IDS) { "Profile contains too many rules" }
            require(profile.ruleIds.all { it in ruleIds }) { "Profile references an unknown rule" }
        }
    }

    private fun validateRule(rule: Rule) {
        RuleDefinitionValidator.requireValid(rule)
    }

    private fun validateInsight(insight: DailyInsight) {
        validateInsightHeader(insight)
        validateActionBreakdown(insight.actionBreakdown, insight.totalNotifications, "Insight action breakdown")
        validateActionBreakdown(
            insight.monitoredActionBreakdown,
            insight.totalNotifications,
            "Insight monitor breakdown",
        )
        validateInsightBreakdownSizes(insight)
        validateRuleAndCategoryCounts(insight)
        validateChannelAndAppCounts(insight)
        validateHourAndSemanticCounts(insight)
        require(insight.mlClassifiedCount in 0..insight.totalNotifications) {
            "ML classified count is invalid"
        }
        require(insight.categoryCorrectionCount >= 0 && insight.semanticCorrectionCount >= 0) {
            "Correction count is invalid"
        }
        require(insight.breakdownVersion >= 0) { "Breakdown version is invalid" }
    }

    private fun validateInsightHeader(insight: DailyInsight) {
        require(insight.epochDay in 0..MAX_SUPPORTED_INSIGHT_EPOCH_DAY) { "Insight day is invalid" }
        require(insight.windowStartMillis >= 0 && insight.windowEndMillis > insight.windowStartMillis) {
            "Insight window is invalid"
        }
        require(insight.totalNotifications >= 0) { "Insight total is invalid" }
        require(insight.mutedCount in 0..insight.totalNotifications) { "Insight muted count is invalid" }
        require(insight.generatedAtMillis >= 0) { "Insight generation time is invalid" }
    }

    private fun validateActionBreakdown(
        action: ActionBreakdown,
        total: Int,
        label: String,
    ) {
        require(
            listOf(action.cancelled, action.snoozed, action.loggedOnly, action.kept).all { it >= 0 } &&
                action.total <= total,
        ) { "$label is invalid" }
    }

    private fun validateInsightBreakdownSizes(insight: DailyInsight) {
        require(insight.topRules.size <= MAX_BREAKDOWN_ROWS) { "Insight has too many rule counts" }
        require(insight.topMonitoredRules.size <= MAX_BREAKDOWN_ROWS) {
            "Insight has too many monitored rule counts"
        }
        require(insight.categoryBreakdown.size <= MAX_BREAKDOWN_ROWS) { "Insight has too many category counts" }
        require(insight.channelBreakdown.size <= MAX_BREAKDOWN_ROWS) { "Insight has too many channel counts" }
        require(insight.appBreakdown.size <= MAX_BREAKDOWN_ROWS) { "Insight has too many app counts" }
        require(insight.hourBreakdown.size <= HOURS_PER_DAY) { "Insight has too many hour counts" }
        require(insight.semanticBreakdown.size <= MAX_SEMANTIC_INTENTS) {
            "Insight has too many semantic counts"
        }
    }

    private fun validateRuleAndCategoryCounts(insight: DailyInsight) {
        require(insight.topRules.validRuleCounts(insight.totalNotifications)) { "Rule count is invalid" }
        require(insight.topMonitoredRules.validRuleCounts(insight.totalNotifications)) {
            "Monitored rule count is invalid"
        }
        require(
            insight.categoryBreakdown.all {
                (it.category?.length ?: 0) <= MAX_CONDITION_VALUE_CHARS &&
                    it.count in 1..insight.totalNotifications
            } &&
                insight.categoryBreakdown.sumOf { it.count.toLong() } <= insight.totalNotifications.toLong(),
        ) {
            "Category count is invalid"
        }
    }

    private fun validateChannelAndAppCounts(insight: DailyInsight) {
        require(
            insight.channelBreakdown.all {
                it.packageName.isNotBlank() &&
                    it.packageName.length <= MAX_PACKAGE_CHARS &&
                    it.channelId.isNotBlank() &&
                    it.channelId.length <= MAX_CONDITION_VALUE_CHARS &&
                    (it.channelName?.length ?: 0) <= MAX_CONDITION_VALUE_CHARS &&
                    it.count in 1..insight.totalNotifications
            } &&
                insight.channelBreakdown.sumOf { it.count.toLong() } <= insight.totalNotifications.toLong(),
        ) { "Channel count is invalid" }
        require(
            insight.appBreakdown.all {
                it.packageName.isNotBlank() &&
                    it.packageName.length <= MAX_PACKAGE_CHARS &&
                    it.totalCount in 1..insight.totalNotifications &&
                    it.silencedCount in 0..it.totalCount
            } &&
                insight.appBreakdown.sumOf { it.totalCount.toLong() } <= insight.totalNotifications.toLong(),
        ) { "App count is invalid" }
    }

    private fun validateHourAndSemanticCounts(insight: DailyInsight) {
        require(
            insight.hourBreakdown
                .map { it.hour }
                .distinct()
                .size == insight.hourBreakdown.size &&
                insight.hourBreakdown.all {
                    it.hour in 0 until HOURS_PER_DAY &&
                        it.totalCount in 1..insight.totalNotifications &&
                        it.silencedCount in 0..it.totalCount
                } &&
                insight.hourBreakdown.sumOf { it.totalCount.toLong() } <= insight.totalNotifications.toLong(),
        ) { "Hour count is invalid" }
        require(
            insight.semanticBreakdown
                .map { it.intent }
                .distinct()
                .size == insight.semanticBreakdown.size &&
                insight.semanticBreakdown.all { it.count in 1..insight.totalNotifications } &&
                insight.semanticBreakdown.sumOf { it.count.toLong() } <= insight.totalNotifications.toLong(),
        ) { "Semantic count is invalid" }
    }

    private fun List<RuleTriggerCount>.validRuleCounts(total: Int): Boolean =
        all { it.ruleId.isNotBlank() && it.count in 1..total } &&
            sumOf { it.count.toLong() } <= total.toLong()

    private fun <K, T> requireGroupedFeedbackFits(
        groups: Map<K, List<T>>,
        message: String,
        count: (T) -> Int,
    ) {
        require(
            groups.values.all { rows -> rows.sumOf { count(it).toLong() } <= MAX_FEEDBACK_VOTES },
        ) { message }
    }

    private const val MAX_INSIGHTS = 10_000
    private const val MAX_FEEDBACK_ROWS = 25_000
    private const val MAX_FEEDBACK_VOTES = 1_000_000
    private const val MAX_PACKAGE_CHARS = 255
    private const val MAX_LABEL_CHARS = 100
    private const val MAX_BREAKDOWN_ROWS = 1_000
    private const val MAX_SEMANTIC_INTENTS = 7
    private const val HOURS_PER_DAY = 24
    private val RETENTION_RANGE = 1..3_650
}
