package com.alarmcontrol.ui.insights

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.alarmcontrol.R
import com.alarmcontrol.core.filtering.DecisionTraceLane
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.ui.UiText
import com.alarmcontrol.ui.uiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Local JVM Compose UI test (Robolectric) for the recategorize affordance on [InsightsScreen]. Runs
 * via `./gradlew :app:testDebugUnitTest`, no emulator. Driven through the public screen because the
 * row and its menu are private composables.
 *
 * Same Robolectric setup as RulesScreenTest: plain [Application] (no Hilt boot — state is passed in),
 * SDK 34 (Robolectric 4.11.1's max), NATIVE graphics so Compose actually lays out.
 */
@Suppress("LargeClass") // Keeps one public-screen Compose fixture and its interaction matrix together.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class InsightsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val categories = listOf("promotion", "social", "news", "alarm")

    private val event =
        EventListItem(
            id = "1",
            packageName = "com.example.shop",
            predictedCategory = "promotion",
            category = "promotion",
            actionLabel = uiText(R.string.insights_action_cancelled),
            recordedAtMillis = 0L,
            undone = false,
            canUndo = true,
        )

    private fun setInsightsScreen() {
        composeRule.setContent {
            InsightsScreen(
                state =
                    InsightsUiState(
                        isLoading = false,
                        events = listOf(event),
                        availableCategories = categories,
                    ),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }
    }

    @Test
    fun fixLabelButtonExpandsTheDropdown() {
        setInsightsScreen()

        // Collapsed: the capitalized category items are not present yet.
        composeRule.onNodeWithText("Social").assertDoesNotExist()

        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG).performScrollToNode(hasText("Fix label"))
        composeRule.onNodeWithText("Fix label").performClick()

        composeRule.onNodeWithText("Social").assertIsDisplayed()
    }

    @Test
    fun dropdownShowsEveryAvailableCategory() {
        setInsightsScreen()

        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG).performScrollToNode(hasText("Fix label"))
        composeRule.onNodeWithText("Fix label").performClick()

        composeRule.onNodeWithText("Promotion").assertIsDisplayed()
        composeRule.onNodeWithText("Social").assertIsDisplayed()
        composeRule.onNodeWithText("News").assertIsDisplayed()
        composeRule.onNodeWithText("Alarm").assertIsDisplayed()
    }

    @Test
    fun recategorizing_updatesTheRowAnnotation() {
        // Hoist the feed state in the test so onRecategorize updates it like the real ViewModel does,
        // letting us assert the screen re-renders the annotation end-to-end.
        val events = mutableStateOf(listOf(event))
        composeRule.setContent {
            InsightsScreen(
                state =
                    InsightsUiState(
                        isLoading = false,
                        events = events.value,
                        availableCategories = categories,
                    ),
                onUndo = {},
                onRecategorize = { eventId, _, _, corrected ->
                    events.value =
                        events.value.map {
                            if (it.id == eventId) it.copy(correctedCategory = corrected) else it
                        }
                },
                onUserMessageShown = {},
            )
        }

        composeRule.onNodeWithText("Recategorized as Promotion").assertDoesNotExist()

        // The Daily-history section sits above the feed, so scroll the row into view before driving it.
        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG).performScrollToNode(hasText("Fix label"))
        composeRule.onNodeWithText("Fix label").performClick()
        composeRule.onNodeWithText("Promotion").performClick()

        composeRule
            .onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(hasText("Recategorized as Promotion"))
        composeRule.onNodeWithText("Recategorized as Promotion").assertIsDisplayed()
    }

    @Test
    fun summaryCard_showsHeadlineAndTimestamp() {
        composeRule.setContent {
            InsightsScreen(
                state =
                    InsightsUiState(
                        isLoading = false,
                        summary =
                            InsightsSummaryUi(
                                mostMutedPackage = "com.example.shop",
                                mostMutedAppName = "Shop",
                                mostMutedCount = 7,
                                anomalyCount = 0,
                                generatedAtMillis = System.currentTimeMillis(),
                            ),
                    ),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        composeRule.onNodeWithText("Most muted: Shop (7)").assertIsDisplayed()
        composeRule.onNodeWithText("com.example.shop").assertDoesNotExist()
        composeRule.onNodeWithText("Updated", substring = true).assertIsDisplayed()
    }

    @Test
    fun summaryCard_hidesPackageFallbackBehindUnknownAppLabel() {
        composeRule.setContent {
            InsightsScreen(
                state =
                    InsightsUiState(
                        isLoading = false,
                        summary =
                            InsightsSummaryUi(
                                mostMutedPackage = "com.example.removed",
                                mostMutedAppName = "com.example.removed",
                                mostMutedCount = 3,
                                anomalyCount = 0,
                                generatedAtMillis = System.currentTimeMillis(),
                                mostMutedAppNameIsPackageFallback = true,
                            ),
                    ),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        composeRule.onNodeWithText("Most muted: Unknown or removed app (3)").assertIsDisplayed()
        composeRule.onNodeWithText("com.example.removed").assertDoesNotExist()
    }

    @Test
    fun summaryCard_showsPlaceholderWhenNoSummary() {
        composeRule.setContent {
            InsightsScreen(
                state = InsightsUiState(isLoading = false, summary = null),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        composeRule
            .onNodeWithText("Preparing a private summary from activity on this device.")
            .assertIsDisplayed()
    }

    @Test
    fun todayMetricsShowAllFourRecordedActions() {
        composeRule.setContent {
            InsightsScreen(
                state =
                    InsightsUiState(
                        isLoading = false,
                        metrics =
                            InsightsMetrics(
                                cancelled = 1,
                                snoozed = 2,
                                loggedOnly = 3,
                                kept = 4,
                            ),
                    ),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        composeRule.onNodeWithContentDescription("Cancelled: 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Snoozed: 2").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Logged only: 3").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Kept: 4").assertIsDisplayed()
        composeRule
            .onNodeWithText("10 decisions recorded locally today")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // Labels are already resolved by the ViewModel: a real rule name and a deleted-rule fallback.
    private val day =
        DailyInsightUi(
            epochDay = 20_263,
            totalNotifications = 5,
            mutedCount = 3,
            topRules =
                listOf(
                    RuleTriggerUi(UiText.Dynamic("Mute promos"), 4),
                    RuleTriggerUi(uiText(R.string.insights_deleted_rule), 1),
                ),
            categories =
                listOf(
                    CategoryShareUi(uiText(R.string.category_alarm), 3),
                    CategoryShareUi(uiText(R.string.category_promotion), 2),
                ),
        )

    @Test
    fun dailyHistory_rendersCardWithCategoryBarsAndResolvedRuleNames() {
        composeRule.setContent {
            InsightsScreen(
                state = InsightsUiState(isLoading = false, dailyInsights = listOf(day)),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        val list = composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
        list.performScrollToNode(hasText("3 muted · 5 total"))
        composeRule.onNodeWithText("3 muted · 5 total").assertIsDisplayed()
        list.performScrollToNode(hasText("Alarm"))
        composeRule.onNodeWithText("Alarm").assertIsDisplayed()
        // Resolved rule name and the graceful fallback for a deleted rule both render.
        list.performScrollToNode(hasText("Mute promos"))
        composeRule.onNodeWithText("Mute promos").assertIsDisplayed()
        list.performScrollToNode(hasText("Deleted rule"))
        composeRule.onNodeWithText("Deleted rule").assertIsDisplayed()
    }

    @Test
    fun dailyHistory_expandsAndCollapsesDetails() {
        composeRule.setContent {
            InsightsScreen(
                state = InsightsUiState(isLoading = false, dailyInsights = listOf(day)),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        val list = composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
        list.performScrollToNode(hasText("Alarm"))
        composeRule.onNodeWithText("Alarm").assertIsDisplayed()

        composeRule.onNode(hasText("3 muted · 5 total") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Alarm").assertDoesNotExist()

        composeRule.onNode(hasText("3 muted · 5 total") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Alarm").assertIsDisplayed()
    }

    @Test
    fun dailyHistory_rendersTrendDeltaAndActionBreakdown() {
        val newest =
            day.copy(
                epochDay = 20_264,
                mutedCount = 5,
                actions = ActionBreakdownUi(cancelled = 2, snoozed = 3, loggedOnly = 1, kept = 4),
                mutedDelta = 2,
            )
        composeRule.setContent {
            InsightsScreen(
                state = InsightsUiState(isLoading = false, dailyInsights = listOf(newest, day)),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        val list = composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
        list.performScrollToNode(hasText("Silenced trend"))
        composeRule.onNodeWithText("Silenced trend").assertIsDisplayed()
        list.performScrollToNode(hasText("2 more silenced than the previous day"))
        composeRule.onNodeWithText("2 more silenced than the previous day").assertIsDisplayed()
        list.performScrollToNode(hasText("Cancelled 2 · Snoozed 3 · Logged 1 · Kept 4"))
        composeRule.onNodeWithText("Cancelled 2 · Snoozed 3 · Logged 1 · Kept 4").assertIsDisplayed()
    }

    @Test
    fun dailyHistory_showsEmptyPlaceholderWhenNoHistory() {
        composeRule.setContent {
            InsightsScreen(
                state = InsightsUiState(isLoading = false, dailyInsights = emptyList()),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        composeRule
            .onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(
                hasText(
                    "Daily summaries will appear after AlarmControl has activity to summarize.",
                ),
            )
        composeRule
            .onNodeWithText("Daily summaries will appear after AlarmControl has activity to summarize.")
            .assertIsDisplayed()
    }

    @Test
    fun dailyHistory_warnsWhenStorageCleanupMadeSourceIncomplete() {
        composeRule.setContent {
            InsightsScreen(
                state =
                    InsightsUiState(
                        isLoading = false,
                        dailyInsights = listOf(day.copy(sourceComplete = false)),
                    ),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        composeRule
            .onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(
                hasText(
                    "Some older activity in this period was already removed to save space",
                    substring = true,
                ),
            )
        composeRule
            .onNodeWithText(
                "Some older activity in this period was already removed to save space",
                substring = true,
            ).assertIsDisplayed()
    }

    @Test
    fun dailyCard_showsEmptyDayMessageWhenNothingRecorded() {
        val emptyDay =
            DailyInsightUi(
                epochDay = 20_263,
                totalNotifications = 0,
                mutedCount = 0,
                topRules = emptyList(),
                categories = emptyList(),
            )
        composeRule.setContent {
            InsightsScreen(
                state = InsightsUiState(isLoading = false, dailyInsights = listOf(emptyDay)),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        composeRule
            .onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(hasText("No notifications recorded."))
        composeRule.onNodeWithText("No notifications recorded.").assertIsDisplayed()
    }

    @Test
    fun activityFilterEmptyState_distinguishesNoMatchesFromNoHistory() {
        composeRule.setContent {
            InsightsScreen(
                state =
                    InsightsUiState(
                        isLoading = false,
                        events = emptyList(),
                        activityTotalCount = 3,
                        activityQuery = "clock",
                    ),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        composeRule
            .onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(hasText("No activity matches", substring = true))
        composeRule.onNodeWithText("No activity matches", substring = true).assertIsDisplayed()
    }

    @Test
    fun activitySearch_hoistsTextChanges() {
        var query = ""
        composeRule.setContent {
            InsightsScreen(
                state = InsightsUiState(isLoading = false),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
                onActivityQueryChange = { query = it },
            )
        }

        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG).performScrollToNode(hasText("Search app or category"))
        composeRule.onNodeWithText("Search app or category").performTextInput("clock")
        assertEquals("clock", query)
    }

    @Test
    fun activityActions_createPrefilledRuleAndOpenPackageSettings() {
        var ruleRequest: Pair<String, String?>? = null
        var settingsPackage: String? = null
        composeRule.setContent {
            InsightsScreen(
                state =
                    InsightsUiState(
                        isLoading = false,
                        events = listOf(event),
                        availableCategories = categories,
                    ),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
                onCreateRule = { packageName, category -> ruleRequest = packageName to category },
                onOpenNotificationSettings = { packageName, _ -> settingsPackage = packageName },
            )
        }

        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG).performScrollToNode(hasText("Fix label"))
        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Create rule for this app").performClick()
        assertEquals("com.example.shop" to "promotion", ruleRequest)

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Open app notification settings").performClick()
        assertEquals("com.example.shop", settingsPackage)
    }

    @Test
    fun localLlmObservationCanBeCorrectedFromTheActivityMenu() {
        var correction: SemanticIntent? = null
        composeRule.setContent {
            InsightsScreen(
                state =
                    InsightsUiState(
                        isLoading = false,
                        events =
                            listOf(
                                event.copy(
                                    adObservation =
                                        AdObservationUi(
                                            predictedIntent = SemanticIntent.MARKETING,
                                            confidencePercent = 78,
                                        ),
                                ),
                            ),
                    ),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
                onSemanticCorrection = { _, corrected -> correction = corrected },
            )
        }

        composeRule
            .onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(hasText("Ads & promotions", substring = true))
        composeRule.onNodeWithText("Ads & promotions", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Mark as Security").assertIsDisplayed()
        composeRule.onNodeWithText("Mark as Delivery").assertIsDisplayed()
        composeRule.onNodeWithText("Mark as Social").assertIsDisplayed()
        composeRule.onNodeWithText("Mark as Other").assertIsDisplayed()
        composeRule.onNodeWithText("Mark as Not sure").assertIsDisplayed()
        composeRule.onNodeWithText("Mark as purchase or account activity").performClick()

        assertEquals(SemanticIntent.TRANSACTIONAL, correction)
    }

    @Test
    fun dailyCardShowsMonitorCountsAndOpensExactChannel() {
        var opened: Pair<String, String?>? = null
        val withChannel =
            day.copy(
                monitoredActions = ActionBreakdownUi(cancelled = 4, snoozed = 2),
                channels = listOf(ChannelShareUi("com.shop", "Shop", "offers", 6)),
            )
        composeRule.setContent {
            InsightsScreen(
                state = InsightsUiState(isLoading = false, dailyInsights = listOf(withChannel)),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
                onOpenNotificationSettings = { packageName, channelId -> opened = packageName to channelId },
            )
        }

        val list = composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
        list.performScrollToNode(hasText("Monitor predictions: 4 would cancel · 2 would snooze"))
        composeRule.onNodeWithText("Monitor predictions: 4 would cancel · 2 would snooze").assertIsDisplayed()
        list.performScrollToNode(hasText("Shop · offers · 6"))
        composeRule
            .onNodeWithText("Shop · offers · 6")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals("com.shop" to "offers", opened)
    }

    @Test
    fun activityExplanationShowsBothRuleLanesConfidenceAndContentFreeTrace() {
        val explained =
            event.copy(
                channelId = "offers",
                mlConfidencePercent = 87,
                matchedRuleName = UiText.Dynamic("Mute offers"),
                monitoredActionLabel = UiText.Dynamic("Cancelled"),
                monitoredRuleName = UiText.Dynamic("Observe ads"),
                decisionTrace =
                    listOf(
                        DecisionTraceUi(
                            lane = DecisionTraceLane.ACTIVE,
                            depth = 0,
                            conditionLabel = UiText.Dynamic("Package"),
                            resultLabel = UiText.Dynamic("Match"),
                        ),
                        DecisionTraceUi(
                            lane = DecisionTraceLane.MONITOR,
                            depth = 1,
                            conditionLabel = UiText.Dynamic("Semantic intent"),
                            resultLabel = UiText.Dynamic("Unknown"),
                        ),
                    ),
            )
        composeRule.setContent {
            InsightsScreen(
                state = InsightsUiState(isLoading = false, events = listOf(explained)),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        val list = composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
        list.performScrollToNode(hasText("Why?"))
        composeRule.onNodeWithText("Why?").performClick()
        composeRule.onNodeWithText("Actual: Cancelled · Mute offers").assertExists()
        composeRule.onNodeWithText("Monitor prediction: Cancelled · Observe ads").assertExists()
        composeRule.onNodeWithText("Smart category confidence: 87%").assertExists()
        composeRule.onNodeWithText("Active · Package · Match").assertExists()
        composeRule.onNodeWithText("Monitor · Semantic intent · Unknown").assertExists()
    }

    @Test
    fun channelSuggestionCanOpenSettingsAndBeDismissed() {
        var opened: Pair<String, String?>? = null
        var dismissed: String? = null
        val suggestion =
            RuleSuggestionUi(
                key = "channel:com.shop:offers",
                type = RuleSuggestionTypeUi.QUIET_CHANNEL,
                appName = "Shop",
                packageName = "com.shop",
                channelId = "offers",
                numerator = 8,
                denominator = 10,
            )
        composeRule.setContent {
            InsightsScreen(
                state = InsightsUiState(isLoading = false, suggestions = listOf(suggestion)),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
                onDismissSuggestion = { dismissed = it },
                onOpenNotificationSettings = { packageName, channelId -> opened = packageName to channelId },
            )
        }

        val list = composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
        list.performScrollToNode(hasText("Consider quieting this channel"))
        composeRule.onNodeWithText("Open settings").performClick()
        assertEquals("com.shop" to "offers", opened)
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(suggestion.key, dismissed)
    }

    @Test
    fun marketingSuggestionOnlyOpensAnUnsavedDraft() {
        var requestedPackage: String? = null
        val suggestion =
            RuleSuggestionUi(
                key = "marketing:com.shop",
                type = RuleSuggestionTypeUi.MARKETING_RULE,
                appName = "Shop",
                packageName = "com.shop",
                numerator = 3,
                denominator = 4,
            )
        composeRule.setContent {
            InsightsScreen(
                state = InsightsUiState(isLoading = false, suggestions = listOf(suggestion)),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
                onCreateMarketingMonitor = { requestedPackage = it },
            )
        }

        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG).performScrollToNode(hasText("Review a promotion filter"))
        composeRule.onNodeWithText("Review draft").performClick()
        assertEquals("com.shop", requestedPackage)
    }

    @Test
    fun analysisTabShowsRangeTotalsAndRichLocalBreakdowns() {
        val analysis =
            InsightsAnalysisUi(
                startEpochDay = 20_260,
                endEpochDay = 20_266,
                totalNotifications = 20,
                silencedCount = 10,
                silencedPercent = 50,
                actions = ActionBreakdownUi(cancelled = 8, snoozed = 2, kept = 10),
                apps = listOf(AppAnalysisUi("com.shop", "Shop", 20, 10)),
                rules = listOf(RuleAnalysisUi(UiText.Dynamic("Quiet offers"), 8, 3)),
                categories = listOf(CategoryShareUi(UiText.Dynamic("Promotion"), 12)),
                hours = listOf(HourAnalysisUi(9, 20, 10)),
                trend = listOf(TrendPointUi(20_260, 20_260, 20, 10)),
                bucketLabel = UiText.Dynamic("day"),
                mlClassifiedCount = 18,
                categoryCorrectionCount = 2,
                semanticCorrectionCount = 1,
            )
        composeRule.setContent {
            InsightsScreen(
                state =
                    InsightsUiState(
                        isLoading = false,
                        selectedTab = InsightsTab.ANALYSIS,
                        analysis = analysis,
                    ),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        val list = composeRule.onNodeWithTag(INSIGHTS_ANALYSIS_TEST_TAG)
        composeRule.onNodeWithText("Summary for this device").assertIsDisplayed()
        composeRule.onNodeWithText("10 of 20 silenced (50%)").assertIsDisplayed()
        list.performScrollToNode(hasText("All notifications"))
        composeRule.onNodeWithText("All notifications").assertIsDisplayed()
        composeRule.onNodeWithText("Silenced").assertIsDisplayed()
        list.performScrollToNode(hasText("Time of day"))
        composeRule
            .onNodeWithContentDescription(
                "24-hour chart showing all and silenced notifications. Peak at 09:00 with 20 notifications.",
            ).assertIsDisplayed()
        list.performScrollToNode(hasText("Shop"))
        composeRule.onNodeWithText("Shop").assertIsDisplayed()
        list.performScrollToNode(hasText("Quiet offers"))
        composeRule.onNodeWithText("Quiet offers").assertIsDisplayed()
        list.performScrollToNode(hasText("Smart sorting 18", substring = true))
        composeRule.onNodeWithText("Smart sorting 18", substring = true).assertIsDisplayed()
    }

    @Test
    fun emptyAnalysisRangeStillWarnsAboutKnownSourceGap() {
        composeRule.setContent {
            InsightsScreen(
                state =
                    InsightsUiState(
                        isLoading = false,
                        selectedTab = InsightsTab.ANALYSIS,
                        analysis =
                            InsightsAnalysisUi(
                                startEpochDay = 20_260,
                                endEpochDay = 20_266,
                                sourceComplete = false,
                            ),
                    ),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        composeRule
            .onNodeWithText(
                "Some older activity in this period was already removed to save space",
                substring = true,
            ).assertIsDisplayed()
        composeRule.onNodeWithText("No completed data in this range").assertIsDisplayed()
    }

    @Test
    fun recordsTabShowsKeptEventsAndDelegatesDetailOpening() {
        var openedId: String? = null
        val kept =
            event.copy(
                id = "42",
                appName = "Shop",
                appNameIsPackageFallback = false,
                action = EventActionUi.KEPT,
                actionLabel = uiText(R.string.insights_action_kept),
                canUndo = false,
                hadEncryptedContent = true,
            )
        composeRule.setContent {
            InsightsScreen(
                state =
                    InsightsUiState(
                        isLoading = false,
                        selectedTab = InsightsTab.RECORDS,
                        historyEvents = listOf(kept),
                        historyTotalCount = 1,
                    ),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
                onOpenEventDetail = { openedId = it },
            )
        }

        val list = composeRule.onNodeWithTag(INSIGHTS_RECORDS_TEST_TAG)
        composeRule.onNodeWithText("1 matching records").assertIsDisplayed()
        list.performScrollToNode(hasText("Shop"))
        composeRule.onNodeWithText("Shop").assertIsDisplayed()
        composeRule.onNodeWithText("com.example.shop").assertDoesNotExist()
        list.performScrollToNode(hasText("Details"))
        composeRule.onNodeWithText("Details").performClick()
        assertEquals("42", openedId)
    }

    @Test
    fun notificationDetailShowsDecryptedLocalContent() {
        composeRule.setContent {
            InsightsScreen(
                state =
                    InsightsUiState(
                        isLoading = false,
                        selectedEventDetail =
                            NotificationDetailUi(
                                eventId = "42",
                                appName = "Bank",
                                packageName = "com.bank",
                                title = "Payment received",
                                text = "₩10,000",
                                contentState = NotificationDetailContentUi.AVAILABLE,
                            ),
                    ),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        composeRule.onNodeWithText("Bank").assertIsDisplayed()
        composeRule.onNodeWithText("com.bank").assertIsDisplayed()
        composeRule.onNodeWithText("Payment received").assertIsDisplayed()
        composeRule.onNodeWithText("₩10,000").assertIsDisplayed()
        composeRule
            .onNodeWithText("AlarmControl opened this saved detail on this device only.")
            .assertIsDisplayed()
    }
}
