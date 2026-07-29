package com.alarmcontrol.data.backup

import com.alarmcontrol.core.backup.BackupAdFeedback
import com.alarmcontrol.core.backup.BackupCategoryFeedback
import com.alarmcontrol.core.backup.BackupData
import com.alarmcontrol.core.backup.BackupSemanticFeedback
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.MAX_RULE_CONDITION_DEPTH
import com.alarmcontrol.core.filtering.MAX_RULE_CONDITION_NODES
import com.alarmcontrol.core.filtering.MAX_SAVED_RULES
import com.alarmcontrol.core.filtering.NotificationImportance
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.insights.ActionBreakdown
import com.alarmcontrol.core.insights.AppInsightCount
import com.alarmcontrol.core.insights.CategoryCount
import com.alarmcontrol.core.insights.ChannelCount
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.insights.HourInsightCount
import com.alarmcontrol.core.insights.RuleTriggerCount
import com.alarmcontrol.core.insights.SemanticIntentCount
import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.core.profile.MAX_PROFILE_RULE_IDS
import com.alarmcontrol.core.profile.MAX_SAVED_PROFILES
import com.alarmcontrol.core.settings.RetentionDefaults
import com.alarmcontrol.core.settings.SemanticAnalysisScope
import com.alarmcontrol.core.settings.SettingsSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure (de)serialization between [BackupData] and a structured JSON string (CLAUDE.md §3). Uses only
 * the platform `org.json` — no third-party serialization library, nothing leaves the device. The
 * format is versioned ([FORMAT_VERSION]) so future readers can detect older backups.
 */
object BackupCodec {
    const val FORMAT_VERSION = 6
    private const val LEGACY_FORMAT_VERSION = 1
    private const val PRETTY_INDENT = 2

    fun encode(data: BackupData): String =
        JSONObject()
            .put("version", FORMAT_VERSION)
            .put("rules", JSONArray(data.rules.map { it.toJson() }))
            .put("dailyInsights", JSONArray(data.dailyInsights.map { it.toJson() }))
            .put("profiles", JSONArray(data.profiles.map { it.toJson() }))
            .put("settings", data.settings?.toJson() ?: JSONObject.NULL)
            .put("categoryFeedback", JSONArray(data.categoryFeedback.map { it.toJson() }))
            .put("adFeedback", JSONArray(data.adFeedback.map { it.toJson() }))
            .put("semanticFeedback", JSONArray(data.semanticFeedback.map { it.toJson() }))
            .toString(PRETTY_INDENT)

    fun decode(serialized: String): BackupData {
        serialized.requireSafeJsonNesting()
        val root = JSONObject(serialized)
        val version = root.getInt("version")
        require(version in LEGACY_FORMAT_VERSION..FORMAT_VERSION) {
            "Unsupported backup format version"
        }
        val legacyAdFeedback =
            root
                .optJSONArray("adFeedback")
                .orEmpty()
                .objects(MAX_FEEDBACK_ROWS)
                .map { it.toAdFeedback() }
        return BackupData(
            rules = root.getJSONArray("rules").objects(MAX_SAVED_RULES).map { it.toRule() },
            dailyInsights =
                root
                    .optJSONArray("dailyInsights")
                    .orEmpty()
                    .objects(MAX_INSIGHT_ROWS)
                    .map { it.toDailyInsight() },
            profiles =
                root
                    .optJSONArray("profiles")
                    .orEmpty()
                    .objects(MAX_SAVED_PROFILES)
                    .map { it.toProfile() },
            settings = root.optJSONObject("settings")?.toSettings(),
            categoryFeedback =
                root
                    .optJSONArray("categoryFeedback")
                    .orEmpty()
                    .objects(MAX_FEEDBACK_ROWS)
                    .map { it.toCategoryFeedback() },
            adFeedback = legacyAdFeedback,
            semanticFeedback =
                root
                    .optJSONArray("semanticFeedback")
                    ?.objects(MAX_FEEDBACK_ROWS)
                    ?.map { it.toSemanticFeedback() }
                    ?: legacyAdFeedback.map {
                        BackupSemanticFeedback(
                            packageName = it.packageName,
                            intent =
                                if (it.isAdvertisement) {
                                    SemanticIntent.MARKETING
                                } else {
                                    SemanticIntent.TRANSACTIONAL
                                },
                            count = it.count,
                        )
                    },
        )
    }

    // ---- profiles ----
    private fun FilteringProfile.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("ruleIds", JSONArray(ruleIds.sorted()))

    private fun JSONObject.toProfile(): FilteringProfile =
        FilteringProfile(
            id = getString("id"),
            name = getString("name"),
            ruleIds = getJSONArray("ruleIds").strings(MAX_PROFILE_RULE_IDS).toSet(),
        )

    // ---- rules ----
    private fun Rule.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("enabled", enabled)
            .put("priority", priority)
            .put("executionMode", executionMode.name)
            .put("action", action.toJson())
            .put("condition", condition.toJson())

    private fun JSONObject.toRule(): Rule {
        val conditionBudget = ConditionDecodeBudget()
        return Rule(
            id = getString("id"),
            name = getString("name"),
            enabled = getBoolean("enabled"),
            priority = getInt("priority"),
            executionMode = RuleExecutionMode.valueOf(optString("executionMode", RuleExecutionMode.ACTIVE.name)),
            condition = getJSONObject("condition").toCondition(depth = 1, budget = conditionBudget),
            action = getJSONObject("action").toAction(),
        )
    }

    private fun RuleAction.toJson(): JSONObject =
        when (this) {
            RuleAction.Cancel -> JSONObject().put("type", "CANCEL")
            RuleAction.MarkRead -> JSONObject().put("type", "MARK_READ")
            RuleAction.Keep -> JSONObject().put("type", "KEEP")
            is RuleAction.Snooze -> JSONObject().put("type", "SNOOZE").put("durationMillis", durationMillis)
        }

    private fun JSONObject.toAction(): RuleAction =
        when (val type = getString("type")) {
            "CANCEL" -> RuleAction.Cancel
            "MARK_READ" -> RuleAction.MarkRead
            "KEEP" -> RuleAction.Keep
            "SNOOZE" -> RuleAction.Snooze(getLong("durationMillis"))
            else -> error("Unknown action type: $type")
        }

    // ---- condition tree (recursive) ----
    private fun Condition.toJson(): JSONObject {
        val o = JSONObject()
        when (this) {
            is Condition.PackageEquals -> o.put("type", "PACKAGE").put("value", packageName)
            is Condition.TitleContains -> o.put("type", "TITLE").put("value", text).put("ignoreCase", ignoreCase)
            is Condition.TextContains -> o.put("type", "TEXT").put("value", text).put("ignoreCase", ignoreCase)
            is Condition.CategoryEquals -> o.put("type", "CATEGORY").put("value", category)
            is Condition.ChannelEquals -> o.put("type", "CHANNEL").put("value", channelId)
            is Condition.Ongoing -> o.put("type", "ONGOING").put("value", value)
            is Condition.MlCategoryEquals -> o.put("type", "ML_CATEGORY").put("value", category)
            is Condition.IsAdvertisement -> o.put("type", "IS_AD").put("value", value)
            is Condition.SemanticIntentEquals -> o.put("type", "SEMANTIC_INTENT").put("value", intent.name)
            is Condition.RateAtLeast ->
                o
                    .put("type", "RATE_AT_LEAST")
                    .put("scope", scope.name)
                    .put("windowMillis", windowMillis)
                    .put("threshold", threshold)
            is Condition.Conversation -> o.put("type", "CONVERSATION").put("value", value)
            is Condition.ForegroundService -> o.put("type", "FOREGROUND_SERVICE").put("value", value)
            is Condition.ImportanceAtLeast -> o.put("type", "IMPORTANCE_AT_LEAST").put("value", minimum.name)
            is Condition.TimeWindow ->
                o.put("type", "TIME_WINDOW").put("start", startMinuteOfDay).put("end", endMinuteOfDay)
            is Condition.AllOf -> o.put("type", "ALL_OF").put("children", JSONArray(conditions.map { it.toJson() }))
            is Condition.AnyOf -> o.put("type", "ANY_OF").put("children", JSONArray(conditions.map { it.toJson() }))
            is Condition.Not -> o.put("type", "NOT").put("child", condition.toJson())
        }
        return o
    }

    private fun JSONObject.toCondition(
        depth: Int,
        budget: ConditionDecodeBudget,
    ): Condition {
        require(depth <= MAX_RULE_CONDITION_DEPTH) { "Backup condition tree is too deep" }
        budget.consume()
        return when (val type = getString("type")) {
            "PACKAGE" -> Condition.PackageEquals(getString("value"))
            "TITLE" -> Condition.TitleContains(getString("value"), getBoolean("ignoreCase"))
            "TEXT" -> Condition.TextContains(getString("value"), getBoolean("ignoreCase"))
            "CATEGORY" -> Condition.CategoryEquals(getString("value"))
            "CHANNEL" -> Condition.ChannelEquals(getString("value"))
            "ONGOING" -> Condition.Ongoing(getBoolean("value"))
            "ML_CATEGORY" -> Condition.MlCategoryEquals(getString("value"))
            "IS_AD" -> Condition.IsAdvertisement(getBoolean("value"))
            "SEMANTIC_INTENT" -> Condition.SemanticIntentEquals(SemanticIntent.valueOf(getString("value")))
            "RATE_AT_LEAST" ->
                Condition.RateAtLeast(
                    RateScope.valueOf(getString("scope")),
                    getLong("windowMillis"),
                    getInt("threshold"),
                )
            "CONVERSATION" -> Condition.Conversation(getBoolean("value"))
            "FOREGROUND_SERVICE" -> Condition.ForegroundService(getBoolean("value"))
            "IMPORTANCE_AT_LEAST" ->
                Condition.ImportanceAtLeast(NotificationImportance.valueOf(getString("value")))
            "TIME_WINDOW" -> Condition.TimeWindow(getInt("start"), getInt("end"))
            "ALL_OF" -> Condition.AllOf(getJSONArray("children").toConditions(depth, budget))
            "ANY_OF" -> Condition.AnyOf(getJSONArray("children").toConditions(depth, budget))
            "NOT" -> Condition.Not(getJSONObject("child").toCondition(depth + 1, budget))
            else -> error("Unknown condition type: $type")
        }
    }

    private fun JSONArray.toConditions(
        parentDepth: Int,
        budget: ConditionDecodeBudget,
    ): List<Condition> {
        require(length() in 1..MAX_RULE_CONDITION_NODES) { "Invalid backup condition group size" }
        return (0 until length()).map { getJSONObject(it).toCondition(parentDepth + 1, budget) }
    }

    // ---- daily insights ----
    private fun DailyInsight.toJson(): JSONObject =
        JSONObject()
            .put("epochDay", epochDay)
            .put("windowStartMillis", windowStartMillis)
            .put("windowEndMillis", windowEndMillis)
            .put("totalNotifications", totalNotifications)
            .put("mutedCount", mutedCount)
            .put("cancelledCount", actionBreakdown.cancelled)
            .put("snoozedCount", actionBreakdown.snoozed)
            .put("loggedCount", actionBreakdown.loggedOnly)
            .put("keptCount", actionBreakdown.kept)
            .put("monitoredCancelledCount", monitoredActionBreakdown.cancelled)
            .put("monitoredSnoozedCount", monitoredActionBreakdown.snoozed)
            .put("monitoredLoggedCount", monitoredActionBreakdown.loggedOnly)
            .put("monitoredKeptCount", monitoredActionBreakdown.kept)
            .put("generatedAtMillis", generatedAtMillis)
            .put("topRules", JSONArray(topRules.map { JSONObject().put("ruleId", it.ruleId).put("count", it.count) }))
            .put(
                "topMonitoredRules",
                JSONArray(topMonitoredRules.map { JSONObject().put("ruleId", it.ruleId).put("count", it.count) }),
            ).put("categories", JSONArray(categoryBreakdown.map { it.toJson() }))
            .put("channels", JSONArray(channelBreakdown.map { it.toJson() }))
            .put("apps", JSONArray(appBreakdown.map { it.toJson() }))
            .put("hours", JSONArray(hourBreakdown.map { it.toJson() }))
            .put("semanticIntents", JSONArray(semanticBreakdown.map { it.toJson() }))
            .put("mlClassifiedCount", mlClassifiedCount)
            .put("categoryCorrectionCount", categoryCorrectionCount)
            .put("semanticCorrectionCount", semanticCorrectionCount)
            .put("breakdownVersion", breakdownVersion)
            .put("sourceComplete", sourceComplete)
            .put("ruleBreakdownComplete", ruleBreakdownComplete)
            .put("monitorRuleBreakdownComplete", monitorRuleBreakdownComplete)
            .put("appBreakdownComplete", appBreakdownComplete)
            .put("channelBreakdownComplete", channelBreakdownComplete)

    private fun CategoryCount.toJson(): JSONObject =
        JSONObject().put("category", category ?: JSONObject.NULL).put("count", count)

    private fun ChannelCount.toJson(): JSONObject =
        JSONObject()
            .put("packageName", packageName)
            .put("channelId", channelId)
            .put("channelName", channelName ?: JSONObject.NULL)
            .put("count", count)

    private fun AppInsightCount.toJson(): JSONObject =
        JSONObject()
            .put("packageName", packageName)
            .put("totalCount", totalCount)
            .put("silencedCount", silencedCount)

    private fun HourInsightCount.toJson(): JSONObject =
        JSONObject().put("hour", hour).put("totalCount", totalCount).put("silencedCount", silencedCount)

    private fun SemanticIntentCount.toJson(): JSONObject = JSONObject().put("intent", intent.name).put("count", count)

    private fun JSONObject.toDailyInsight(): DailyInsight =
        DailyInsight(
            epochDay = getLong("epochDay"),
            windowStartMillis = getLong("windowStartMillis"),
            windowEndMillis = getLong("windowEndMillis"),
            totalNotifications = getInt("totalNotifications"),
            mutedCount = getInt("mutedCount"),
            topRules =
                getJSONArray("topRules").objects(MAX_BREAKDOWN_ROWS).map {
                    RuleTriggerCount(it.getString("ruleId"), it.getInt("count"))
                },
            topMonitoredRules =
                optJSONArray("topMonitoredRules")
                    .orEmpty()
                    .objects(MAX_BREAKDOWN_ROWS)
                    .map { RuleTriggerCount(it.getString("ruleId"), it.getInt("count")) },
            categoryBreakdown =
                getJSONArray("categories").objects(MAX_BREAKDOWN_ROWS).map {
                    CategoryCount(if (it.isNull("category")) null else it.getString("category"), it.getInt("count"))
                },
            generatedAtMillis = getLong("generatedAtMillis"),
            actionBreakdown =
                ActionBreakdown(
                    cancelled = optInt("cancelledCount", 0),
                    snoozed = optInt("snoozedCount", 0),
                    loggedOnly = optInt("loggedCount", 0),
                    kept = optInt("keptCount", 0),
                ),
            monitoredActionBreakdown =
                ActionBreakdown(
                    cancelled = optInt("monitoredCancelledCount", 0),
                    snoozed = optInt("monitoredSnoozedCount", 0),
                    loggedOnly = optInt("monitoredLoggedCount", 0),
                    kept = optInt("monitoredKeptCount", 0),
                ),
            channelBreakdown =
                optJSONArray("channels")
                    .orEmpty()
                    .objects(MAX_BREAKDOWN_ROWS)
                    .map { it.toChannelCount() },
            appBreakdown =
                optJSONArray("apps")
                    .orEmpty()
                    .objects(MAX_BREAKDOWN_ROWS)
                    .map {
                        AppInsightCount(
                            packageName = it.getString("packageName"),
                            totalCount = it.getInt("totalCount"),
                            silencedCount = it.getInt("silencedCount"),
                        )
                    },
            hourBreakdown =
                optJSONArray("hours")
                    .orEmpty()
                    .objects(HOURS_PER_DAY)
                    .map {
                        HourInsightCount(
                            hour = it.getInt("hour"),
                            totalCount = it.getInt("totalCount"),
                            silencedCount = it.getInt("silencedCount"),
                        )
                    },
            semanticBreakdown =
                optJSONArray("semanticIntents")
                    .orEmpty()
                    .objects(SEMANTIC_INTENT_COUNT)
                    .map {
                        SemanticIntentCount(
                            intent = SemanticIntent.valueOf(it.getString("intent")),
                            count = it.getInt("count"),
                        )
                    },
            mlClassifiedCount = optInt("mlClassifiedCount", 0),
            categoryCorrectionCount = optInt("categoryCorrectionCount", 0),
            semanticCorrectionCount = optInt("semanticCorrectionCount", 0),
            breakdownVersion = optInt("breakdownVersion", 0),
            sourceComplete = optBoolean("sourceComplete", false),
            ruleBreakdownComplete = optBoolean("ruleBreakdownComplete", false),
            monitorRuleBreakdownComplete = optBoolean("monitorRuleBreakdownComplete", false),
            appBreakdownComplete = optBoolean("appBreakdownComplete", false),
            channelBreakdownComplete = optBoolean("channelBreakdownComplete", false),
        )

    private fun JSONObject.toChannelCount(): ChannelCount =
        ChannelCount(
            packageName = getString("packageName"),
            channelId = getString("channelId"),
            count = getInt("count"),
            channelName =
                if (!has("channelName") || isNull("channelName")) {
                    null
                } else {
                    getString("channelName")
                },
        )

    private class ConditionDecodeBudget {
        private var remaining = MAX_RULE_CONDITION_NODES

        fun consume() {
            require(remaining > 0) { "Backup condition tree is too large" }
            remaining--
        }
    }

    private const val MAX_INSIGHT_ROWS = 10_000
    private const val MAX_FEEDBACK_ROWS = 25_000
    private const val MAX_BREAKDOWN_ROWS = 1_000
    private const val HOURS_PER_DAY = 24
    private const val SEMANTIC_INTENT_COUNT = 7
}

private fun JSONArray.objects(max: Int): List<JSONObject> {
    require(length() <= max) { "Backup array is too large" }
    return (0 until length()).map { getJSONObject(it) }
}

private fun JSONArray.strings(max: Int): List<String> {
    require(length() <= max) { "Backup array is too large" }
    return (0 until length()).map { getString(it) }
}

private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

private fun SettingsSnapshot.toJson(): JSONObject =
    JSONObject()
        .put("filteringEnabled", filteringEnabled)
        .put("externalAutomationEnabled", externalAutomationEnabled)
        .put("llmAnalysisEnabled", llmAnalysisEnabled)
        .put("semanticAnalysisScope", semanticAnalysisScope.name)
        .put("eventRetentionDays", eventRetentionDays)
        .put("dailyInsightRetentionDays", dailyInsightRetentionDays)

private fun JSONObject.toSettings(): SettingsSnapshot =
    SettingsSnapshot(
        filteringEnabled = optBoolean("filteringEnabled", true),
        externalAutomationEnabled = optBoolean("externalAutomationEnabled", false),
        llmAnalysisEnabled = optBoolean("llmAnalysisEnabled", false),
        llmAutoActionsEnabled = false,
        semanticAnalysisScope =
            optString("semanticAnalysisScope", SemanticAnalysisScope.RULES_ONLY.name)
                .let(SemanticAnalysisScope::valueOf),
        eventRetentionDays = optInt("eventRetentionDays", RetentionDefaults.EVENT_DAYS),
        dailyInsightRetentionDays =
            optInt(
                "dailyInsightRetentionDays",
                RetentionDefaults.DAILY_INSIGHT_DAYS,
            ),
    )

private fun BackupCategoryFeedback.toJson(): JSONObject =
    JSONObject()
        .put("packageName", packageName)
        .put("predictedLabel", predictedLabel ?: JSONObject.NULL)
        .put("correctedLabel", correctedLabel)
        .put("recordedAtMillis", recordedAtMillis)

private fun JSONObject.toCategoryFeedback(): BackupCategoryFeedback =
    BackupCategoryFeedback(
        packageName = getString("packageName"),
        predictedLabel = if (isNull("predictedLabel")) null else getString("predictedLabel"),
        correctedLabel = getString("correctedLabel"),
        recordedAtMillis = getLong("recordedAtMillis"),
    )

private fun BackupAdFeedback.toJson(): JSONObject =
    JSONObject()
        .put("packageName", packageName)
        .put("isAdvertisement", isAdvertisement)
        .put("count", count)

private fun JSONObject.toAdFeedback(): BackupAdFeedback =
    BackupAdFeedback(
        packageName = getString("packageName"),
        isAdvertisement = getBoolean("isAdvertisement"),
        count = getInt("count"),
    )

private fun BackupSemanticFeedback.toJson(): JSONObject =
    JSONObject().put("packageName", packageName).put("intent", intent.name).put("count", count)

private fun JSONObject.toSemanticFeedback(): BackupSemanticFeedback =
    BackupSemanticFeedback(
        packageName = getString("packageName"),
        intent = SemanticIntent.valueOf(getString("intent")),
        count = getInt("count"),
    )
