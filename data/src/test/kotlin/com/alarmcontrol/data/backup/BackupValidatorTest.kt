package com.alarmcontrol.data.backup

import com.alarmcontrol.core.backup.BackupCategoryFeedback
import com.alarmcontrol.core.backup.BackupData
import com.alarmcontrol.core.backup.BackupSemanticFeedback
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.insights.AppInsightCount
import com.alarmcontrol.core.insights.CategoryCount
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.insights.HourInsightCount
import com.alarmcontrol.core.insights.MAX_SUPPORTED_INSIGHT_EPOCH_DAY
import com.alarmcontrol.core.insights.RuleTriggerCount
import com.alarmcontrol.core.insights.SemanticIntentCount
import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.core.settings.SettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupValidatorTest {
    @Test
    fun `valid modern insight passes validation`() {
        val backup = backupWith(insight = validInsight())

        assertEquals(backup, BackupValidator.validate(backup))
    }

    @Test
    fun `duplicate profile names are rejected case insensitively`() {
        val backup =
            backupWith(
                profiles =
                    listOf(
                        FilteringProfile("1", "Focus", setOf("1")),
                        FilteringProfile("2", "focus", setOf("1")),
                    ),
            )

        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup)
        }
    }

    @Test
    fun `invalid modern insight breakdowns are rejected`() {
        val valid = validInsight()
        val invalidInsights =
            listOf(
                valid.copy(topMonitoredRules = listOf(RuleTriggerCount("1", 3))),
                valid.copy(appBreakdown = listOf(AppInsightCount("com.example", 2, 3))),
                valid.copy(hourBreakdown = listOf(HourInsightCount(24, 2, 1))),
                valid.copy(
                    semanticBreakdown =
                        listOf(
                            SemanticIntentCount(SemanticIntent.MARKETING, 1),
                            SemanticIntentCount(SemanticIntent.MARKETING, 1),
                        ),
                ),
                valid.copy(mlClassifiedCount = 3),
                valid.copy(categoryCorrectionCount = -1),
                valid.copy(epochDay = MAX_SUPPORTED_INSIGHT_EPOCH_DAY + 1),
            )

        invalidInsights.forEach { insight ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupValidator.validate(backupWith(insight = insight))
            }
        }
    }

    @Test
    fun `grouped semantic feedback cannot overflow a local prior`() {
        val backup =
            backupWith(
                semanticFeedback =
                    listOf(
                        BackupSemanticFeedback("com.example", SemanticIntent.MARKETING, 600_000),
                        BackupSemanticFeedback("com.example", SemanticIntent.MARKETING, 600_000),
                    ),
            )

        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup)
        }
    }

    @Test
    fun `small future feedback skew is normalized to validation time`() {
        val normalized =
            BackupValidator.validate(
                backupWith(
                    categoryFeedback =
                        listOf(
                            BackupCategoryFeedback("com.example", "promotion", "social", 1_001),
                        ),
                ),
                nowMillis = 1_000,
            )

        assertEquals(1_000, normalized.categoryFeedback.single().recordedAtMillis)
    }

    @Test
    fun `feedback timestamp beyond the accepted future skew is rejected`() {
        val backup =
            backupWith(
                categoryFeedback =
                    listOf(
                        BackupCategoryFeedback(
                            "com.example",
                            "promotion",
                            "social",
                            1_000 + MAX_BACKUP_FEEDBACK_FUTURE_SKEW_MILLIS + 1,
                        ),
                    ),
            )

        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup, nowMillis = 1_000)
        }
    }

    @Test
    fun `content retention outside the privacy bound is rejected`() {
        listOf(0, 31).forEach { days ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupValidator.validate(
                    backupWith(
                        settings = SettingsSnapshot(notificationContentRetentionDays = days),
                    ),
                )
            }
        }
    }

    private fun backupWith(
        insight: DailyInsight = validInsight(),
        profiles: List<FilteringProfile> = listOf(FilteringProfile("1", "Focus", setOf("1"))),
        categoryFeedback: List<BackupCategoryFeedback> = emptyList(),
        semanticFeedback: List<BackupSemanticFeedback> = emptyList(),
        settings: SettingsSnapshot? = null,
    ): BackupData =
        BackupData(
            rules = listOf(validRule),
            dailyInsights = listOf(insight),
            profiles = profiles,
            categoryFeedback = categoryFeedback,
            semanticFeedback = semanticFeedback,
            settings = settings,
        )

    private fun validInsight(): DailyInsight =
        DailyInsight(
            epochDay = 20_000,
            windowStartMillis = 1_000,
            windowEndMillis = 2_000,
            totalNotifications = 2,
            mutedCount = 1,
            topRules = listOf(RuleTriggerCount("1", 1)),
            topMonitoredRules = listOf(RuleTriggerCount("1", 1)),
            categoryBreakdown = listOf(CategoryCount("message", 2)),
            generatedAtMillis = 2_001,
            appBreakdown = listOf(AppInsightCount("com.example", 2, 1)),
            hourBreakdown = listOf(HourInsightCount(9, 2, 1)),
            semanticBreakdown = listOf(SemanticIntentCount(SemanticIntent.SOCIAL, 1)),
            mlClassifiedCount = 1,
            categoryCorrectionCount = 1,
            semanticCorrectionCount = 1,
            breakdownVersion = 2,
        )

    private companion object {
        val validRule =
            Rule(
                id = "1",
                name = "Rule",
                condition = Condition.PackageEquals("com.example"),
                action = RuleAction.Cancel,
            )
    }
}
