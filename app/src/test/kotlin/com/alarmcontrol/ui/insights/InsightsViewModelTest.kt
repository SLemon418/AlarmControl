package com.alarmcontrol.ui.insights

import app.cash.turbine.test
import com.alarmcontrol.R
import com.alarmcontrol.core.feedback.AdFeedbackRepository
import com.alarmcontrol.core.feedback.AdObservation
import com.alarmcontrol.core.feedback.FeedbackRepository
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.ConditionResult
import com.alarmcontrol.core.filtering.DecisionConditionKind
import com.alarmcontrol.core.filtering.DecisionTraceLane
import com.alarmcontrol.core.filtering.DecisionTraceNode
import com.alarmcontrol.core.filtering.NotificationContentState
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.NotificationEventDetail
import com.alarmcontrol.core.filtering.NotificationEventRepository
import com.alarmcontrol.core.filtering.NotificationHistoryCoverage
import com.alarmcontrol.core.filtering.NotificationHistoryPage
import com.alarmcontrol.core.filtering.NotificationHistoryQuery
import com.alarmcontrol.core.filtering.NotificationHistoryRepository
import com.alarmcontrol.core.filtering.NotificationSource
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.filtering.RuleSuggestion
import com.alarmcontrol.core.filtering.RuleSuggestionRepository
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.insights.ActionBreakdown
import com.alarmcontrol.core.insights.AppInsightCount
import com.alarmcontrol.core.insights.CategoryCount
import com.alarmcontrol.core.insights.ChannelCount
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.insights.DailyInsightRepository
import com.alarmcontrol.core.insights.InsightsAnalytics
import com.alarmcontrol.core.insights.InsightsAnalyticsRepository
import com.alarmcontrol.core.insights.InsightsBucket
import com.alarmcontrol.core.insights.InsightsDateRange
import com.alarmcontrol.core.insights.InsightsSummary
import com.alarmcontrol.core.insights.InsightsSummaryRepository
import com.alarmcontrol.core.insights.InsightsTrendPoint
import com.alarmcontrol.core.insights.RuleInsightCount
import com.alarmcontrol.core.insights.RuleTriggerCount
import com.alarmcontrol.core.privacy.DailyInsightWriteFence
import com.alarmcontrol.ml.NotificationCategories
import com.alarmcontrol.testsupport.MainDispatcherRule
import com.alarmcontrol.testsupport.awaitUntil
import com.alarmcontrol.ui.UiText
import com.alarmcontrol.ui.app.AppIdentityResolver
import com.alarmcontrol.ui.app.AppIdentityUi
import com.alarmcontrol.ui.uiText
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class InsightsViewModelTest {
    @get:org.junit.Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val eventRepository = mockk<NotificationEventRepository>(relaxed = true)
    private val feedbackRepository = mockk<FeedbackRepository>(relaxed = true)
    private val adFeedbackRepository = mockk<AdFeedbackRepository>(relaxed = true)
    private val correctionsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val adObservationsFlow = MutableStateFlow<Map<String, AdObservation>>(emptyMap())
    private val insightsSummaryRepository = mockk<InsightsSummaryRepository>(relaxed = true)
    private val summaryFlow = MutableStateFlow<InsightsSummary?>(null)
    private val dailyInsightRepository = mockk<DailyInsightRepository>(relaxed = true)
    private val dailyFlow = MutableStateFlow<List<DailyInsight>>(emptyList())
    private val notificationHistoryRepository = mockk<NotificationHistoryRepository>(relaxed = true)
    private val historyFlow = MutableStateFlow(NotificationHistoryPage(emptyList(), 0))
    private val sourceFlow = MutableStateFlow<List<NotificationSource>>(emptyList())
    private val insightsAnalyticsRepository = mockk<InsightsAnalyticsRepository>(relaxed = true)
    private val availableRangeFlow = MutableStateFlow<InsightsDateRange?>(null)
    private val analyticsFlow =
        MutableStateFlow(
            InsightsAnalytics(
                range = InsightsDateRange(20_620, 20_626),
                totalNotifications = 0,
                actionBreakdown = ActionBreakdown(),
                monitoredActionBreakdown = ActionBreakdown(),
                apps = emptyList(),
                rules = emptyList(),
                categories = emptyList(),
                channels = emptyList(),
                hours = emptyList(),
                semanticIntents = emptyList(),
                mlClassifiedCount = 0,
                categoryCorrectionCount = 0,
                semanticCorrectionCount = 0,
                bucket = InsightsBucket.DAY,
                trend = emptyList(),
                breakdownCoverageStartEpochDay = null,
            ),
        )
    private val ruleRepository = mockk<RuleRepository>(relaxed = true)
    private val ruleSuggestionRepository = mockk<RuleSuggestionRepository>(relaxed = true)
    private val suggestionsFlow = MutableStateFlow<List<RuleSuggestion>>(emptyList())
    private val rulesFlow = MutableStateFlow<List<Rule>>(emptyList())
    private val categories = NotificationCategories(listOf("promotion", "social", "news", "alarm"))
    private val recent = MutableStateFlow<List<NotificationEvent>>(emptyList())
    private val actionBreakdownFlow = MutableStateFlow(ActionBreakdown())
    private val clock = Clock.fixed(Instant.parse("2026-06-22T10:00:00Z"), ZoneOffset.UTC)
    private val appIdentityResolver = AppIdentityResolver { AppIdentityUi("App: $it", null) }

    private fun viewModel(
        dailyInsightWriteFence: DailyInsightWriteFence = DailyInsightWriteFence(),
        testClock: Clock = clock,
    ): InsightsViewModel {
        every { eventRepository.observeRecent(any()) } returns recent
        every { eventRepository.observeActionBreakdownSince(any()) } returns actionBreakdownFlow
        every {
            eventRepository.observeActionBreakdownForDay(any(), any(), any())
        } returns actionBreakdownFlow
        every { feedbackRepository.observeEventCorrections() } returns correctionsFlow
        every { adFeedbackRepository.observeByEvent() } returns adObservationsFlow
        every { insightsSummaryRepository.summary } returns summaryFlow
        every { dailyInsightRepository.observeRecent(any()) } returns dailyFlow
        every { notificationHistoryRepository.observeHistory(any()) } returns historyFlow
        every { notificationHistoryRepository.observeSources(any()) } returns sourceFlow
        every { notificationHistoryRepository.observeCoverage() } returns
            flowOf(
                NotificationHistoryCoverage(
                    totalEvents = 0,
                    oldestPostedAtMillis = null,
                    newestPostedAtMillis = null,
                    eventsWithTrace = 0,
                ),
            )
        every { insightsAnalyticsRepository.observe(any()) } returns analyticsFlow
        every { insightsAnalyticsRepository.observeAvailableRange() } returns availableRangeFlow
        every { ruleRepository.observeRules() } returns rulesFlow
        every { ruleSuggestionRepository.observeSuggestions(any(), any()) } returns suggestionsFlow
        return InsightsViewModel(
            eventRepository,
            feedbackRepository,
            adFeedbackRepository,
            insightsSummaryRepository,
            dailyInsightRepository,
            notificationHistoryRepository,
            insightsAnalyticsRepository,
            ruleRepository,
            ruleSuggestionRepository,
            categories,
            appIdentityResolver,
            testClock,
            mainDispatcherRule.dispatcher,
            dailyInsightWriteFence,
        )
    }

    @Test
    fun `maps recent events into the activity feed`() =
        runTest {
            recent.value =
                listOf(
                    NotificationEvent(
                        packageName = "com.example.clock",
                        category = "alarm",
                        postedAtMillis = 1_000L,
                        action = RuleAction.Cancel,
                        matchedRuleId = "1",
                        recordedAtMillis = 2_000L,
                        id = "5",
                    ),
                )

            viewModel().uiState.test {
                val loaded = awaitUntil { it.events.isNotEmpty() }
                val item = loaded.events.single()
                assertEquals("com.example.clock", item.packageName)
                assertEquals("App: com.example.clock", item.appName)
                assertEquals(uiText(R.string.insights_action_cancelled), item.actionLabel)
                assertEquals(true, item.canUndo)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `maps channel confidence active and monitor explanations without content`() =
        runTest {
            rulesFlow.value =
                listOf(
                    Rule(
                        "1",
                        "Mute offers",
                        condition = Condition.PackageEquals("com.shop"),
                        action = RuleAction.Cancel,
                    ),
                    Rule(
                        "2",
                        "Observe ads",
                        condition = Condition.PackageEquals("com.shop"),
                        action = RuleAction.Cancel,
                    ),
                )
            recent.value =
                listOf(
                    NotificationEvent(
                        packageName = "com.shop",
                        channelId = "offers",
                        mlCategory = "promotion",
                        mlConfidence = 0.87f,
                        category = "msg",
                        postedAtMillis = 1,
                        action = RuleAction.Cancel,
                        matchedRuleId = "1",
                        monitoredRuleId = "2",
                        monitoredAction = RuleAction.Cancel,
                        decisionTrace =
                            listOf(
                                DecisionTraceNode(
                                    DecisionTraceLane.ACTIVE,
                                    0,
                                    0,
                                    DecisionConditionKind.PACKAGE,
                                    ConditionResult.MATCH,
                                ),
                                DecisionTraceNode(
                                    DecisionTraceLane.MONITOR,
                                    1,
                                    1,
                                    DecisionConditionKind.SEMANTIC_INTENT,
                                    ConditionResult.UNKNOWN,
                                ),
                            ),
                        recordedAtMillis = 2,
                        id = "11",
                    ),
                )

            viewModel().uiState.test {
                val item = awaitUntil { it.events.isNotEmpty() }.events.single()
                assertEquals("offers", item.channelId)
                assertEquals(87, item.mlConfidencePercent)
                assertEquals(UiText.Dynamic("Mute offers"), item.matchedRuleName)
                assertEquals(UiText.Dynamic("Observe ads"), item.monitoredRuleName)
                assertEquals(DecisionTraceLane.ACTIVE, item.decisionTrace.first().lane)
                assertEquals(uiText(R.string.condition_package), item.decisionTrace.first().conditionLabel)
                assertEquals(
                    uiText(R.string.condition_semantic_intent),
                    item.decisionTrace.last().conditionLabel,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `activity search and action filter react without changing aggregate totals`() =
        runTest {
            recent.value =
                listOf(
                    NotificationEvent(
                        packageName = "com.example.shop",
                        category = "promotion",
                        postedAtMillis = 1,
                        action = RuleAction.Cancel,
                        matchedRuleId = "1",
                        recordedAtMillis = 2,
                        id = "1",
                    ),
                    NotificationEvent(
                        packageName = "com.example.clock",
                        category = "alarm",
                        postedAtMillis = 3,
                        action = RuleAction.Snooze(60_000),
                        matchedRuleId = "2",
                        recordedAtMillis = 4,
                        id = "2",
                    ),
                )
            val vm = viewModel()

            vm.uiState.test {
                assertEquals(2, awaitUntil { it.activityTotalCount == 2 }.events.size)
                vm.onActivityQueryChange("clock")
                val searched = awaitUntil { it.activityQuery == "clock" }
                assertEquals(listOf("2"), searched.events.map { it.id })
                assertEquals(2, searched.activityTotalCount)

                vm.onActivityQueryChange("")
                vm.onActivityFilterChange(ActivityActionFilter.CANCELLED)
                val filtered = awaitUntil { it.activityFilter == ActivityActionFilter.CANCELLED }
                assertEquals(listOf("1"), filtered.events.map { it.id })
                assertEquals(2, filtered.activityTotalCount)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `undo delegates to the repository and confirms`() =
        runTest {
            coEvery { eventRepository.undo(any()) } returns true
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.onUndo("5")
                val confirmed = awaitUntil { it.userMessage != null }
                assertEquals(uiText(R.string.message_insights_excluded), confirmed.userMessage)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify { eventRepository.undo("5") }
        }

    @Test
    fun `undo refreshes the captured daily rollup after the event leaves the feed`() =
        runTest {
            val day = LocalDate.of(2026, 6, 21)
            recent.value =
                listOf(
                    NotificationEvent(
                        packageName = "com.example.clock",
                        category = "alarm",
                        postedAtMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                        postedEpochDay = day.toEpochDay(),
                        action = RuleAction.Cancel,
                        matchedRuleId = "1",
                        recordedAtMillis = clock.millis(),
                        id = "5",
                    ),
                )
            coEvery { eventRepository.undo("5") } answers {
                recent.value = emptyList()
                true
            }
            coEvery {
                dailyInsightRepository.aggregateAndStoreIfCurrent(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns
                DailyInsight(
                    epochDay = day.toEpochDay(),
                    windowStartMillis = 0,
                    windowEndMillis = 1,
                    totalNotifications = 0,
                    mutedCount = 0,
                    topRules = emptyList(),
                    categoryBreakdown = emptyList(),
                    generatedAtMillis = clock.millis(),
                )
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.events.singleOrNull()?.id == "5" }
                vm.onUndo("5")
                awaitUntil { it.userMessage != null }
                assertTrue(
                    vm.uiState.value.events
                        .isEmpty(),
                )
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                dailyInsightRepository.aggregateAndStoreIfCurrent(
                    epochDay = day.toEpochDay(),
                    startMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    endMillis =
                        day
                            .plusDays(1)
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant()
                            .toEpochMilli(),
                    generatedAtMillis = clock.millis(),
                    topRules = 50,
                    dailyInsightEpoch = any(),
                )
            }
        }

    @Test
    fun `missing undo target reports failure without recreating a daily rollup`() =
        runTest {
            coEvery { eventRepository.undo("missing") } returns false
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.onUndo("missing")
                val failed = awaitUntil { it.userMessage != null }
                assertEquals(uiText(R.string.message_insights_update_failed), failed.userMessage)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 0) {
                dailyInsightRepository.aggregateAndStoreIfCurrent(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `correctCategory records feedback and confirms`() =
        runTest {
            coEvery { feedbackRepository.recordCorrection(any()) } just Runs
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.correctCategory("9", "com.example.shop", predictedLabel = "promotion", correctedLabel = "social")
                val confirmed = awaitUntil { it.userMessage != null }
                assertEquals(uiText(R.string.message_recategorized, "social"), confirmed.userMessage)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                feedbackRepository.recordCorrection(
                    match {
                        it.packageName == "com.example.shop" &&
                            it.notificationEventId == "9" &&
                            it.predictedLabel == "promotion" &&
                            it.correctedLabel == "social"
                    },
                )
            }
        }

    @Test
    fun `LLM observation and explicit ad correction stay linked to one event`() =
        runTest {
            coEvery { adFeedbackRepository.recordCorrection("7", false) } returns true
            recent.value =
                listOf(
                    NotificationEvent(
                        packageName = "com.example.bank",
                        category = "msg",
                        postedAtMillis = 1,
                        action = RuleAction.Keep,
                        matchedRuleId = null,
                        recordedAtMillis = 2,
                        id = "7",
                    ),
                )
            adObservationsFlow.value =
                mapOf(
                    "7" to
                        AdObservation(
                            notificationEventId = "7",
                            packageName = "com.example.bank",
                            predictedIsAdvertisement = true,
                            confidenceScore = 0.74f,
                            analyzedAtMillis = 3,
                        ),
                )
            val vm = viewModel()

            vm.uiState.test {
                val loaded = awaitUntil { it.events.singleOrNull()?.adObservation != null }
                assertEquals(
                    74,
                    loaded.events
                        .single()
                        .adObservation!!
                        .confidencePercent,
                )
                vm.correctAdvertisement("7", correctedIsAdvertisement = false)
                awaitUntil { it.userMessage != null }
                cancelAndIgnoreRemainingEvents()
            }

            coVerify { adFeedbackRepository.recordCorrection("7", false) }
        }

    @Test
    fun `semantic correction records one of the seven intents`() =
        runTest {
            coEvery { adFeedbackRepository.recordCorrection("7", SemanticIntent.SECURITY) } returns true
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.correctSemanticIntent("7", SemanticIntent.SECURITY)
                val confirmed = awaitUntil { it.userMessage != null }
                assertEquals(
                    uiText(R.string.message_semantic_corrected, uiText(R.string.semantic_security)),
                    confirmed.userMessage,
                )
                cancelAndIgnoreRemainingEvents()
            }

            coVerify { adFeedbackRepository.recordCorrection("7", SemanticIntent.SECURITY) }
        }

    @Test
    fun `maps local suggestions and persists dismissal`() =
        runTest {
            suggestionsFlow.value =
                listOf(
                    RuleSuggestion.QuietChannel(
                        key = "channel:com.shop:offers",
                        packageName = "com.shop",
                        channelId = "offers",
                        totalCount = 10,
                        silencedCount = 8,
                    ),
                    RuleSuggestion.MarketingRuleDraft(
                        key = "marketing:com.shop",
                        packageName = "com.shop",
                        marketingCorrections = 3,
                        totalCorrections = 4,
                        draft =
                            Rule(
                                id = "",
                                name = "draft",
                                condition = Condition.PackageEquals("com.shop"),
                                action = RuleAction.Cancel,
                            ),
                    ),
                )
            coEvery { ruleSuggestionRepository.dismiss(any(), any()) } just Runs
            val vm = viewModel()

            vm.uiState.test {
                val mapped = awaitUntil { it.suggestions.size == 2 }.suggestions
                assertEquals(listOf(8, 3), mapped.map { it.numerator })
                assertEquals("App: com.shop", mapped.first().appName)
                vm.dismissSuggestion(mapped.first().key)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                ruleSuggestionRepository.dismiss(
                    "channel:com.shop:offers",
                    Instant.parse("2026-06-22T10:00:00Z").toEpochMilli(),
                )
            }
        }

    @Test
    fun `suggestion bounds advance with elapsed time new activity and screen resume`() =
        runTest {
            val testClock =
                MutableTestClock(
                    Instant.parse("2026-06-22T10:00:00Z"),
                    ZoneOffset.UTC,
                )
            val observedBounds = mutableListOf<Pair<Long, Long>>()
            val vm = viewModel(testClock = testClock)
            every { ruleSuggestionRepository.observeSuggestions(any(), any()) } answers {
                observedBounds += firstArg<Long>() to secondArg<Long>()
                suggestionsFlow
            }

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                mainDispatcherRule.dispatcher.scheduler.runCurrent()
                val windowMillis = 7L * 24 * 60 * 60 * 1_000
                val initialNow = testClock.millis()
                assertEquals(listOf(initialNow - windowMillis to initialNow), observedBounds)

                val refreshIntervalMillis = 60L * 60 * 1_000
                testClock.advanceMillis(refreshIntervalMillis)
                mainDispatcherRule.dispatcher.scheduler.advanceTimeBy(refreshIntervalMillis)
                mainDispatcherRule.dispatcher.scheduler.runCurrent()
                val elapsedNow = testClock.millis()
                assertEquals(elapsedNow - windowMillis to elapsedNow, observedBounds.last())
                assertEquals(2, observedBounds.size)

                testClock.advanceMillis(60_000)
                recent.value =
                    listOf(
                        NotificationEvent(
                            packageName = "com.example.new",
                            category = null,
                            postedAtMillis = testClock.millis(),
                            action = RuleAction.Keep,
                            matchedRuleId = null,
                            recordedAtMillis = testClock.millis(),
                            id = "new",
                        ),
                    )
                mainDispatcherRule.dispatcher.scheduler.runCurrent()
                val activityNow = testClock.millis()
                assertEquals(activityNow - windowMillis to activityNow, observedBounds.last())
                assertEquals(3, observedBounds.size)

                testClock.advanceMillis(60_000)
                vm.refreshDayBoundary()
                mainDispatcherRule.dispatcher.scheduler.runCurrent()
                val resumedNow = testClock.millis()
                assertEquals(resumedNow - windowMillis to resumedNow, observedBounds.last())
                assertEquals(4, observedBounds.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `persisted correction annotates only its exact activity event`() =
        runTest {
            coEvery { feedbackRepository.recordCorrection(any()) } just Runs
            recent.value =
                listOf(
                    NotificationEvent(
                        packageName = "com.example.shop",
                        mlCategory = "promotion",
                        category = "msg",
                        postedAtMillis = 1_000L,
                        action = RuleAction.Cancel,
                        matchedRuleId = null,
                        recordedAtMillis = 2_000L,
                        id = "9",
                    ),
                    NotificationEvent(
                        packageName = "com.example.shop",
                        mlCategory = "promotion",
                        category = "msg",
                        postedAtMillis = 1_100L,
                        action = RuleAction.Keep,
                        matchedRuleId = null,
                        recordedAtMillis = 2_100L,
                        id = "10",
                    ),
                )
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { it.events.isNotEmpty() }
                vm.correctCategory("9", "com.example.shop", predictedLabel = "promotion", correctedLabel = "social")
                correctionsFlow.value = mapOf("9" to "social")
                val updated = awaitUntil { state -> state.events.any { it.correctedCategory == "social" } }
                assertEquals("social", updated.events.single { it.id == "9" }.correctedCategory)
                assertEquals(null, updated.events.single { it.id == "10" }.correctedCategory)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `surfaces the latest insights summary as ui state`() =
        runTest {
            summaryFlow.value =
                InsightsSummary(
                    generatedAtMillis = 123L,
                    mostMutedPackage = "com.example.shop",
                    mostMutedCount = 7,
                    anomalyCount = 2,
                )

            viewModel().uiState.test {
                val state = awaitUntil { it.summary != null }
                assertEquals("com.example.shop", state.summary!!.mostMutedPackage)
                assertEquals(7, state.summary!!.mostMutedCount)
                assertEquals(2, state.summary!!.anomalyCount)
                assertEquals(123L, state.summary!!.generatedAtMillis)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `surfaces the daily insight history mapped for display`() =
        runTest {
            // Rule "1" still exists; "99" was deleted, so its label must fall back gracefully.
            rulesFlow.value =
                listOf(
                    Rule(
                        id = "1",
                        name = "Mute promos",
                        condition = Condition.PackageEquals("com.a"),
                        action = RuleAction.Cancel,
                    ),
                    Rule(
                        id = "2",
                        name = "Night quiet",
                        condition = Condition.PackageEquals("com.b"),
                        action = RuleAction.Cancel,
                    ),
                )
            dailyFlow.value =
                listOf(
                    DailyInsight(
                        epochDay = 20_263,
                        windowStartMillis = 0L,
                        windowEndMillis = 0L,
                        totalNotifications = 5,
                        mutedCount = 3,
                        topRules = listOf(RuleTriggerCount("1", 4), RuleTriggerCount("99", 1)),
                        categoryBreakdown = listOf(CategoryCount("alarm", 3), CategoryCount(null, 2)),
                        monitoredActionBreakdown = ActionBreakdown(cancelled = 2, snoozed = 1),
                        channelBreakdown = listOf(ChannelCount("com.example.shop", "offers", 5)),
                        sourceComplete = false,
                        generatedAtMillis = 0L,
                    ),
                )

            viewModel().uiState.test {
                val state = awaitUntil { it.dailyInsights.isNotEmpty() }
                val day = state.dailyInsights.single()
                assertEquals(20_263L, day.epochDay)
                assertEquals(5, day.totalNotifications)
                assertEquals(3, day.mutedCount)
                // "1" resolves to its name; "99" was deleted -> graceful fallback.
                assertEquals(
                    listOf(UiText.Dynamic("Mute promos"), uiText(R.string.insights_deleted_rule)),
                    day.topRules.map { it.label },
                )
                assertEquals(listOf(4, 1), day.topRules.map { it.count })
                assertEquals(2, day.monitoredActions.cancelled)
                assertEquals("offers", day.channels.single().channelId)
                assertEquals("App: com.example.shop", day.channels.single().appName)
                assertFalse(day.sourceComplete)
                // null category -> "Uncategorized"; others capitalized.
                assertEquals(
                    listOf(uiText(R.string.category_alarm), uiText(R.string.insights_uncategorized)),
                    day.categories.map { it.label },
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `maps one reactive action breakdown into complete today metrics`() =
        runTest {
            actionBreakdownFlow.value =
                ActionBreakdown(
                    cancelled = 2,
                    snoozed = 3,
                    loggedOnly = 4,
                    kept = 5,
                )

            viewModel().uiState.test {
                val metrics = awaitUntil { it.metrics.totalRecorded == 14 }.metrics
                assertEquals(5, metrics.total)
                assertEquals(14, metrics.totalRecorded)
                assertEquals(4, metrics.loggedOnly)
                assertEquals(5, metrics.kept)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `daily history computes newest day delta against the preceding stored day`() =
        runTest {
            dailyFlow.value =
                listOf(
                    dailyInsight(epochDay = 12, muted = 9),
                    dailyInsight(epochDay = 11, muted = 4),
                    dailyInsight(epochDay = 10, muted = 6),
                )

            viewModel().uiState.test {
                val days = awaitUntil { it.dailyInsights.size == 3 }.dailyInsights
                assertEquals(listOf(5, -2, null), days.map(DailyInsightUi::mutedDelta))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `overview surfaces a source gap before today's rollup exists`() =
        runTest {
            analyticsFlow.value = analyticsFlow.value.copy(sourceComplete = false)

            viewModel().uiState.test {
                val state = awaitUntil { !it.overviewSourceComplete }
                assertTrue(state.dailyInsights.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `maps selected range analytics with resolved app and rule names`() =
        runTest {
            rulesFlow.value =
                listOf(
                    Rule(
                        id = "4",
                        name = "Quiet promotions",
                        condition = Condition.PackageEquals("com.shop"),
                        action = RuleAction.Cancel,
                    ),
                )
            availableRangeFlow.value = InsightsDateRange(20_620, 20_626)
            analyticsFlow.value =
                analyticsFlow.value.copy(
                    totalNotifications = 20,
                    actionBreakdown = ActionBreakdown(cancelled = 5, snoozed = 1, kept = 14),
                    apps = listOf(AppInsightCount("com.shop", 20, 6)),
                    rules = listOf(RuleInsightCount("4", actualCount = 5, monitoredCount = 2)),
                    trend = listOf(InsightsTrendPoint(20_620, 20_620, 20, 6)),
                    sourceComplete = false,
                )
            val vm = viewModel()

            vm.uiState.test {
                vm.onTabSelected(InsightsTab.ANALYSIS)
                val state =
                    awaitUntil { it.selectedTab == InsightsTab.ANALYSIS && it.analysis.totalNotifications == 20 }
                assertEquals(
                    "App: com.shop",
                    state.analysis.apps
                        .single()
                        .appName,
                )
                assertEquals(
                    UiText.Dynamic("Quiet promotions"),
                    state.analysis.rules
                        .single()
                        .label,
                )
                assertEquals(30, state.analysis.silencedPercent)
                assertFalse(state.analysis.sourceComplete)
                assertEquals(InsightsDateRange(20_620, 20_626).toUiRange(), state.availableRange)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `history filters sources and decrypts content only for selected detail`() =
        runTest {
            val event =
                NotificationEvent(
                    packageName = "com.bank",
                    channelId = "transactions",
                    channelName = "Transactions",
                    category = "transaction",
                    postedAtMillis = 1,
                    action = RuleAction.Keep,
                    matchedRuleId = null,
                    recordedAtMillis = 2,
                    id = "7",
                    hadEncryptedContent = true,
                )
            historyFlow.value = NotificationHistoryPage(listOf(event), totalCount = 1)
            sourceFlow.value =
                listOf(
                    NotificationSource(
                        packageName = "com.bank",
                        channelId = "transactions",
                        channelName = "Transactions",
                        eventCount = 1,
                        lastSeenMillis = 2,
                    ),
                )
            coEvery { notificationHistoryRepository.getDetail("7") } returns
                NotificationEventDetail(
                    event,
                    NotificationContentState.Available("Payment received", "₩10,000"),
                )
            val vm = viewModel()

            vm.uiState.test {
                vm.onTabSelected(InsightsTab.RECORDS)
                val records =
                    awaitUntil {
                        it.selectedTab == InsightsTab.RECORDS &&
                            it.historyEvents.size == 1 &&
                            it.historySources.size == 1
                    }
                assertEquals(EventActionUi.KEPT, records.historyEvents.single().action)
                assertEquals("Transactions", records.historySources.single().channelName)

                vm.openEventDetail("7")
                val detail = awaitUntil { it.selectedEventDetail != null }.selectedEventDetail!!
                assertEquals("Payment received", detail.title)
                assertEquals(NotificationDetailContentUi.AVAILABLE, detail.contentState)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { notificationHistoryRepository.getDetail("7") }
        }

    @Test
    fun `a late detail result cannot replace the latest selected event`() =
        runTest {
            var resumeFirst: ((NotificationEventDetail?) -> Unit)? = null
            coEvery { notificationHistoryRepository.getDetail("first") } coAnswers {
                suspendCoroutine { continuation ->
                    resumeFirst = continuation::resume
                }
            }
            coEvery { notificationHistoryRepository.getDetail("second") } returns
                notificationDetail("second", "Latest detail")
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.openEventDetail("first")
                vm.openEventDetail("second")
                awaitUntil { it.selectedEventDetail?.eventId == "second" }

                requireNotNull(resumeFirst).invoke(notificationDetail("first", "Stale detail"))
                mainDispatcherRule.dispatcher.scheduler.runCurrent()

                assertEquals(
                    "second",
                    vm.uiState.value
                        .selectedEventDetail
                        ?.eventId,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `closing detail prevents an uncancellable late result from reopening sensitive content`() =
        runTest {
            var resumeDetail: ((NotificationEventDetail?) -> Unit)? = null
            coEvery { notificationHistoryRepository.getDetail("late") } coAnswers {
                suspendCoroutine { continuation ->
                    resumeDetail = continuation::resume
                }
            }
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                vm.openEventDetail("late")
                vm.closeEventDetail()

                requireNotNull(resumeDetail).invoke(notificationDetail("late", "Must stay hidden"))
                mainDispatcherRule.dispatcher.scheduler.runCurrent()

                assertEquals(null, vm.uiState.value.selectedEventDetail)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `records queries activate only on their tab and page size is capped at one thousand`() =
        runTest {
            val queries = mutableListOf<NotificationHistoryQuery>()
            val vm = viewModel()
            every { notificationHistoryRepository.observeHistory(any()) } answers {
                val query = firstArg<NotificationHistoryQuery>()
                queries += query
                flowOf(NotificationHistoryPage(emptyList(), totalCount = 5_000))
            }

            vm.uiState.test {
                awaitUntil { !it.isLoading }
                verify(exactly = 0) { notificationHistoryRepository.observeHistory(any()) }

                vm.onTabSelected(InsightsTab.RECORDS)
                awaitUntil { it.selectedTab == InsightsTab.RECORDS && it.historyTotalCount == 5_000 }
                assertEquals(100, queries.last().limit)

                repeat(20) { vm.onLoadMoreHistory() }
                mainDispatcherRule.dispatcher.scheduler.runCurrent()
                assertEquals(1_000, queries.last().limit)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `history search debounces rapid typing for three hundred milliseconds`() =
        runTest {
            val queries = mutableListOf<NotificationHistoryQuery>()
            historyFlow.value = NotificationHistoryPage(emptyList(), totalCount = 1)
            val vm = viewModel()
            every { notificationHistoryRepository.observeHistory(any()) } answers {
                queries += firstArg<NotificationHistoryQuery>()
                historyFlow
            }

            vm.uiState.test {
                vm.onTabSelected(InsightsTab.RECORDS)
                awaitUntil { it.selectedTab == InsightsTab.RECORDS && it.historyTotalCount == 1 }
                assertTrue(queries.isNotEmpty())
                queries.clear()

                vm.onActivityQueryChange("%")
                vm.onActivityQueryChange("%_")
                vm.onActivityQueryChange("%_\\")
                mainDispatcherRule.dispatcher.scheduler.runCurrent()
                mainDispatcherRule.dispatcher.scheduler.advanceTimeBy(299)
                mainDispatcherRule.dispatcher.scheduler.runCurrent()
                assertTrue(queries.isEmpty())

                mainDispatcherRule.dispatcher.scheduler.advanceTimeBy(1)
                mainDispatcherRule.dispatcher.scheduler.runCurrent()
                assertEquals("%_\\", queries.single().search)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun dailyInsight(
        epochDay: Long,
        muted: Int,
    ) = DailyInsight(
        epochDay = epochDay,
        windowStartMillis = 0,
        windowEndMillis = 1,
        totalNotifications = muted,
        mutedCount = muted,
        topRules = emptyList(),
        categoryBreakdown = emptyList(),
        generatedAtMillis = 2,
    )

    private fun notificationDetail(
        eventId: String,
        title: String,
    ): NotificationEventDetail =
        NotificationEventDetail(
            event =
                NotificationEvent(
                    packageName = "com.example.$eventId",
                    category = null,
                    postedAtMillis = 1,
                    action = RuleAction.Keep,
                    matchedRuleId = null,
                    recordedAtMillis = 2,
                    id = eventId,
                    hadEncryptedContent = true,
                ),
            content = NotificationContentState.Available(title, null),
        )

    private fun InsightsDateRange.toUiRange(): AvailableRangeUi = AvailableRangeUi(startEpochDay, endEpochDay)
}

private class MutableTestClock(
    initialInstant: Instant,
    private val zoneId: ZoneId,
) : Clock() {
    private var currentInstant = initialInstant

    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = MutableTestClock(currentInstant, zone)

    override fun instant(): Instant = currentInstant

    fun advanceMillis(millis: Long) {
        currentInstant = currentInstant.plusMillis(millis)
    }
}
