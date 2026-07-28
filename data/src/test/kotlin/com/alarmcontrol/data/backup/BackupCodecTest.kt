package com.alarmcontrol.data.backup

import com.alarmcontrol.core.backup.BackupAdFeedback
import com.alarmcontrol.core.backup.BackupCategoryFeedback
import com.alarmcontrol.core.backup.BackupData
import com.alarmcontrol.core.backup.BackupSemanticFeedback
import com.alarmcontrol.core.filtering.Condition
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
import com.alarmcontrol.core.settings.SemanticAnalysisScope
import com.alarmcontrol.core.settings.SettingsSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCodecTest {
    private val sample =
        BackupData(
            rules =
                listOf(
                    Rule(
                        id = "1",
                        name = "Night mute",
                        enabled = true,
                        priority = 5,
                        // Deeply nested tree exercising every condition kind + NOT/ALL_OF/ANY_OF.
                        condition =
                            Condition.AllOf(
                                listOf(
                                    Condition.PackageEquals("com.example.clock"),
                                    Condition.AnyOf(
                                        listOf(
                                            Condition.CategoryEquals("alarm"),
                                            Condition.MlCategoryEquals("promotion"),
                                            Condition.Not(Condition.ChannelEquals("noise")),
                                        ),
                                    ),
                                    Condition.TitleContains("sale", ignoreCase = false),
                                    Condition.TextContains("now"),
                                    Condition.Ongoing(true),
                                    Condition.IsAdvertisement(true),
                                    Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                                    Condition.RateAtLeast(RateScope.CHANNEL, 5 * 60_000L, 4),
                                    Condition.Conversation(true),
                                    Condition.ForegroundService(false),
                                    Condition.ImportanceAtLeast(NotificationImportance.HIGH),
                                    Condition.TimeWindow(startMinuteOfDay = 1_320, endMinuteOfDay = 420),
                                ),
                            ),
                        action = RuleAction.Snooze(1_800_000L),
                        executionMode = RuleExecutionMode.MONITOR,
                    ),
                    Rule(
                        id = "2",
                        name = "Keep clock",
                        enabled = false,
                        priority = 0,
                        condition = Condition.PackageEquals("com.android.deskclock"),
                        action = RuleAction.Keep,
                    ),
                ),
            dailyInsights =
                listOf(
                    DailyInsight(
                        epochDay = 20_000,
                        windowStartMillis = 1_000,
                        windowEndMillis = 2_000,
                        totalNotifications = 5,
                        mutedCount = 3,
                        topRules = listOf(RuleTriggerCount("1", 2), RuleTriggerCount("2", 1)),
                        topMonitoredRules = listOf(RuleTriggerCount("2", 2)),
                        // Includes a null (uncategorised) bucket to prove null survives the round trip.
                        categoryBreakdown = listOf(CategoryCount("alarm", 2), CategoryCount(null, 1)),
                        generatedAtMillis = 1_500,
                        actionBreakdown =
                            ActionBreakdown(cancelled = 2, snoozed = 1, loggedOnly = 1, kept = 1),
                        monitoredActionBreakdown = ActionBreakdown(cancelled = 2, snoozed = 1),
                        channelBreakdown = listOf(ChannelCount("com.shop", "offers", 3, "Offers")),
                        appBreakdown = listOf(AppInsightCount("com.shop", 5, 3)),
                        hourBreakdown = listOf(HourInsightCount(9, 5, 3)),
                        semanticBreakdown =
                            listOf(SemanticIntentCount(SemanticIntent.MARKETING, 3)),
                        mlClassifiedCount = 4,
                        categoryCorrectionCount = 1,
                        semanticCorrectionCount = 2,
                        breakdownVersion = 1,
                        ruleBreakdownComplete = true,
                        monitorRuleBreakdownComplete = true,
                        appBreakdownComplete = true,
                        channelBreakdownComplete = true,
                    ),
                ),
            profiles =
                listOf(
                    FilteringProfile(
                        id = "10",
                        name = "Night",
                        ruleIds = setOf("1", "2"),
                    ),
                ),
            settings =
                SettingsSnapshot(
                    filteringEnabled = false,
                    eventRetentionDays = 90,
                    dailyInsightRetentionDays = 730,
                    semanticAnalysisScope = SemanticAnalysisScope.ALL_NOTIFICATIONS,
                ),
            categoryFeedback =
                listOf(BackupCategoryFeedback("com.shop", "social", "promotion", 1_234)),
            adFeedback = listOf(BackupAdFeedback("com.shop", isAdvertisement = true, count = 3)),
            semanticFeedback = listOf(BackupSemanticFeedback("com.shop", SemanticIntent.DELIVERY, 2)),
        )

    @Test
    fun `encode then decode round-trips the full backup losslessly`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(sample))
        assertEquals(sample, decoded)
    }

    @Test
    fun `decode tolerates a backup with no daily insights`() {
        val rulesOnly = sample.copy(dailyInsights = emptyList())
        assertEquals(rulesOnly, BackupCodec.decode(BackupCodec.encode(rulesOnly)))
    }

    @Test
    fun `encoded form carries the format version`() {
        assertEquals(true, BackupCodec.encode(sample).contains("\"version\": ${BackupCodec.FORMAT_VERSION}"))
    }

    @Test
    fun `retired LLM auto actions are omitted from exports and ignored from old backups`() {
        val root =
            JSONObject(
                BackupCodec.encode(
                    sample.copy(
                        settings =
                            SettingsSnapshot(
                                llmAnalysisEnabled = true,
                                llmAutoActionsEnabled = true,
                            ),
                    ),
                ),
            )
        val settings = root.getJSONObject("settings")

        assertEquals(false, settings.has("llmAutoActionsEnabled"))

        settings.put("llmAutoActionsEnabled", true)
        val restored = BackupCodec.decode(root.toString())

        assertEquals(false, restored.settings?.llmAutoActionsEnabled)
        assertEquals(true, restored.settings?.llmAnalysisEnabled)
    }

    @Test
    fun `decode rejects an unsupported format version`() {
        val unsupported = JSONObject(BackupCodec.encode(sample)).put("version", 99).toString()

        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(unsupported) }
    }

    @Test
    fun `legacy version one backup restores with no profiles`() {
        val legacy =
            JSONObject(BackupCodec.encode(sample))
                .apply {
                    put("version", 1)
                    remove("profiles")
                    remove("settings")
                    remove("categoryFeedback")
                    remove("adFeedback")
                    remove("semanticFeedback")
                }.toString()

        val decoded = BackupCodec.decode(legacy)
        assertEquals(emptyList<FilteringProfile>(), decoded.profiles)
        assertEquals(null, decoded.settings)
        assertEquals(emptyList<BackupCategoryFeedback>(), decoded.categoryFeedback)
    }

    @Test
    fun `version two backup restores legacy settings profile and feedback sections`() {
        val rule =
            JSONObject()
                .put("id", "legacy-rule")
                .put("name", "Legacy package rule")
                .put("enabled", true)
                .put("priority", 7)
                .put("action", JSONObject().put("type", "CANCEL"))
                .put("condition", JSONObject().put("type", "PACKAGE").put("value", "com.legacy"))
        val legacy =
            JSONObject()
                .put("version", 2)
                .put("rules", JSONArray().put(rule))
                .put("dailyInsights", JSONArray())
                .put(
                    "profiles",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("id", "legacy-profile")
                                .put("name", "Legacy")
                                .put("ruleIds", JSONArray().put("legacy-rule")),
                        ),
                ).put("settings", JSONObject().put("filteringEnabled", false))
                .put("categoryFeedback", JSONArray())
                .put(
                    "adFeedback",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("packageName", "com.legacy")
                                .put("isAdvertisement", false)
                                .put("count", 2),
                        ),
                ).toString()

        val decoded = BackupCodec.decode(legacy)

        assertEquals(RuleExecutionMode.ACTIVE, decoded.rules.single().executionMode)
        assertEquals(setOf("legacy-rule"), decoded.profiles.single().ruleIds)
        assertEquals(false, decoded.settings?.filteringEnabled)
        assertEquals(
            listOf(BackupSemanticFeedback("com.legacy", SemanticIntent.TRANSACTIONAL, 2)),
            decoded.semanticFeedback,
        )
    }

    @Test
    fun `version three binary ad votes migrate to semantic marketing and transactional votes`() {
        val root = JSONObject(BackupCodec.encode(sample.copy(semanticFeedback = emptyList())))
        root.put("version", 3).remove("semanticFeedback")
        root.getJSONArray("rules").getJSONObject(0).remove("executionMode")
        val legacy = root.toString()

        val decoded = BackupCodec.decode(legacy)

        assertEquals(
            listOf(BackupSemanticFeedback("com.shop", SemanticIntent.MARKETING, 3)),
            decoded.semanticFeedback,
        )
        assertEquals(RuleExecutionMode.ACTIVE, decoded.rules.first().executionMode)
    }

    @Test
    fun `version four daily history restores with safe defaults for new aggregates`() {
        val root = JSONObject(BackupCodec.encode(sample))
        root.put("version", 4)
        root.getJSONArray("dailyInsights").getJSONObject(0).apply {
            remove("topMonitoredRules")
            remove("apps")
            remove("hours")
            remove("semanticIntents")
            remove("mlClassifiedCount")
            remove("categoryCorrectionCount")
            remove("semanticCorrectionCount")
            remove("breakdownVersion")
            remove("ruleBreakdownComplete")
            remove("monitorRuleBreakdownComplete")
            remove("appBreakdownComplete")
            remove("channelBreakdownComplete")
            getJSONArray("channels").getJSONObject(0).remove("channelName")
        }

        val restored = BackupCodec.decode(root.toString()).dailyInsights.single()

        assertEquals(emptyList<RuleTriggerCount>(), restored.topMonitoredRules)
        assertEquals(emptyList<AppInsightCount>(), restored.appBreakdown)
        assertEquals(emptyList<HourInsightCount>(), restored.hourBreakdown)
        assertEquals(emptyList<SemanticIntentCount>(), restored.semanticBreakdown)
        assertEquals(null, restored.channelBreakdown.single().channelName)
        assertEquals(0, restored.breakdownVersion)
        assertEquals(false, restored.ruleBreakdownComplete)
        assertEquals(false, restored.channelBreakdownComplete)
    }

    @Test
    fun `version five restores with rules-only semantic scope and partial breakdown defaults`() {
        val root = JSONObject(BackupCodec.encode(sample))
        root.put("version", 5)
        root.getJSONObject("settings").remove("semanticAnalysisScope")
        root.getJSONArray("dailyInsights").getJSONObject(0).apply {
            remove("ruleBreakdownComplete")
            remove("monitorRuleBreakdownComplete")
            remove("appBreakdownComplete")
            remove("channelBreakdownComplete")
        }

        val restored = BackupCodec.decode(root.toString())

        assertEquals(SemanticAnalysisScope.RULES_ONLY, restored.settings?.semanticAnalysisScope)
        assertEquals(false, restored.dailyInsights.single().appBreakdownComplete)
    }

    @Test
    fun `portable backup never contains notification title body or encrypted payload fields`() {
        val encoded = BackupCodec.encode(sample)

        assertEquals(false, encoded.contains("ciphertext"))
        assertEquals(false, encoded.contains("notificationTitle"))
        assertEquals(false, encoded.contains("notificationText"))
    }

    @Test
    fun `decode rejects excessive condition depth before materializing the domain tree`() {
        var condition = JSONObject().put("type", "PACKAGE").put("value", "com.example")
        repeat(40) { condition = JSONObject().put("type", "NOT").put("child", condition) }
        val rule =
            JSONObject()
                .put("id", "1")
                .put("name", "deep")
                .put("enabled", true)
                .put("priority", 0)
                .put("action", JSONObject().put("type", "CANCEL"))
                .put("condition", condition)
        val backup =
            JSONObject()
                .put("version", BackupCodec.FORMAT_VERSION)
                .put("rules", JSONArray().put(rule))
                .put("dailyInsights", JSONArray())

        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(backup.toString()) }
    }

    @Test
    fun `decode rejects oversized root arrays before decoding their elements`() {
        val rules = JSONArray()
        repeat(1_001) { rules.put(JSONObject()) }
        val backup =
            JSONObject()
                .put("version", BackupCodec.FORMAT_VERSION)
                .put("rules", rules)

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                BackupCodec.decode(backup.toString())
            }

        assertEquals("Backup array is too large", error.message)
    }
}
