package com.alarmcontrol.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alarmcontrol.R
import com.alarmcontrol.core.coroutines.AppDispatcher
import com.alarmcontrol.core.coroutines.Dispatcher
import com.alarmcontrol.core.feedback.AdFeedbackRepository
import com.alarmcontrol.core.feedback.AdObservation
import com.alarmcontrol.core.feedback.CategoryFeedback
import com.alarmcontrol.core.feedback.FeedbackRepository
import com.alarmcontrol.core.filtering.HistoryActionFilter
import com.alarmcontrol.core.filtering.NotificationEventRepository
import com.alarmcontrol.core.filtering.NotificationHistoryQuery
import com.alarmcontrol.core.filtering.NotificationHistoryRepository
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.filtering.RuleSuggestion
import com.alarmcontrol.core.filtering.RuleSuggestionRepository
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.insights.DailyInsightRepository
import com.alarmcontrol.core.insights.InsightsAnalyticsRepository
import com.alarmcontrol.core.insights.InsightsDateRange
import com.alarmcontrol.core.insights.InsightsSummaryRepository
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.core.result.asDataResult
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.ml.NotificationCategories
import com.alarmcontrol.ui.UiText
import com.alarmcontrol.ui.app.AppIdentityResolver
import com.alarmcontrol.ui.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class InsightsViewModel
    @Inject
    constructor(
        private val eventRepository: NotificationEventRepository,
        private val feedbackRepository: FeedbackRepository,
        private val adFeedbackRepository: AdFeedbackRepository,
        insightsSummaryRepository: InsightsSummaryRepository,
        dailyInsightRepository: DailyInsightRepository,
        private val notificationHistoryRepository: NotificationHistoryRepository,
        insightsAnalyticsRepository: InsightsAnalyticsRepository,
        ruleRepository: RuleRepository,
        private val ruleSuggestionRepository: RuleSuggestionRepository,
        categories: NotificationCategories,
        private val appIdentityResolver: AppIdentityResolver,
        private val clock: Clock,
        @Dispatcher(AppDispatcher.Default) private val dispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val availableCategories: List<String> = categories.labels
        private val messages = MutableStateFlow<UiText?>(null)
        private val activityQuery = MutableStateFlow("")
        private val activityFilter = MutableStateFlow(ActivityActionFilter.ALL)
        private val selectedTab = MutableStateFlow(InsightsTab.OVERVIEW)
        private val historyActionFilter = MutableStateFlow(HistoryActionFilterUi.ALL)
        private val historyPackageName = MutableStateFlow<String?>(null)
        private val historyChannelId = MutableStateFlow<String?>(null)
        private val historyLimit = MutableStateFlow(HISTORY_PAGE_SIZE)
        private val selectedEventDetail = MutableStateFlow<NotificationDetailUi?>(null)
        private val analysisPreset = MutableStateFlow(AnalysisRangePreset.LAST_7_DAYS)
        private val customRangeStart = MutableStateFlow("")
        private val customRangeEnd = MutableStateFlow("")
        private val initialAnalysisEnd = LocalDate.now(clock).minusDays(1).toEpochDay()
        private val analysisRange =
            MutableStateFlow(InsightsDateRange(initialAnalysisEnd - DEFAULT_ANALYSIS_DAYS + 1, initialAnalysisEnd))

        private val startOfDayMillis = MutableStateFlow(clock.todayStartMillis())

        private val rules =
            ruleRepository
                .observeRules()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        private val metrics: Flow<InsightsMetrics> =
            startOfDayMillis.flatMapLatest { start ->
                eventRepository.observeActionBreakdownSince(start).map { breakdown ->
                    InsightsMetrics(
                        cancelled = breakdown.cancelled,
                        snoozed = breakdown.snoozed,
                        loggedOnly = breakdown.loggedOnly,
                        kept = breakdown.kept,
                    )
                }
            }

        private val content: Flow<DataResult<Content>> =
            combine(
                eventRepository.observeRecent(RECENT_LIMIT),
                metrics,
                feedbackRepository.observeEventCorrections(),
                adFeedbackRepository.observeByEvent(),
                rules,
            ) { events, metrics, corrected, adObservations, currentRules ->
                val ruleNames = currentRules.associate { it.id to it.name }
                Content(
                    events =
                        events.map {
                            it
                                .toListItem(
                                    correctedCategory = corrected[it.id],
                                    identity = appIdentityResolver.resolve(it.packageName),
                                    ruleNames = ruleNames,
                                ).copy(adObservation = adObservations[it.id]?.toUiModel())
                        },
                    metrics = metrics,
                )
            }.flowOn(dispatcher)
                .asDataResult()

        // Resolve each rollup's rule ids to names from the live rules, so the cards show "Mute promos"
        // instead of "Rule #1"; ids missing from the current set fall back gracefully (deleted rules).
        private val dailyInsights: Flow<List<DailyInsightUi>> =
            combine(
                dailyInsightRepository.observeRecent(DAILY_LIMIT),
                rules,
            ) { days, rules ->
                val ruleNames = rules.associate { it.id to it.name }
                val mapped = days.map { it.toUiModel(ruleNames, appIdentityResolver) }
                mapped.mapIndexed { index, day ->
                    day.copy(
                        mutedDelta =
                            mapped.getOrNull(index + 1)?.let { previous ->
                                day.mutedCount - previous.mutedCount
                            },
                    )
                }
            }

        private val summaryUi: Flow<InsightsSummaryUi?> =
            insightsSummaryRepository.summary
                .map { it?.toUiModel(appIdentityResolver) }
                .flowOn(dispatcher)

        private val suggestions: Flow<List<RuleSuggestionUi>> =
            ruleSuggestionRepository
                .observeSuggestions(clock.millis() - SUGGESTION_WINDOW_MILLIS)
                .map { rows ->
                    rows.map { suggestion ->
                        suggestion.toUiModel(
                            appIdentityResolver.resolve(suggestion.packageName()).label,
                        )
                    }
                }.flowOn(dispatcher)

        private val availableRange =
            insightsAnalyticsRepository
                .observeAvailableRange()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        private val analysis: Flow<InsightsAnalysisUi> =
            selectedTab.flatMapLatest { tab ->
                if (tab != InsightsTab.ANALYSIS) {
                    flowOf(InsightsAnalysisUi())
                } else {
                    combine(
                        analysisRange.flatMapLatest(insightsAnalyticsRepository::observe),
                        rules,
                    ) { analytics, currentRules ->
                        analytics.toUiModel(
                            ruleNames = currentRules.associate { it.id to it.name },
                            appIdentityResolver = appIdentityResolver,
                        )
                    }
                }
            }.flowOn(dispatcher)

        init {
            viewModelScope.launch {
                val available = availableRange.filterNotNull().first()
                if (
                    analysisPreset.value == AnalysisRangePreset.LAST_7_DAYS &&
                    analysisRange.value.endEpochDay == initialAnalysisEnd
                ) {
                    analysisRange.value =
                        InsightsDateRange(
                            maxOf(available.startEpochDay, available.endEpochDay - DEFAULT_ANALYSIS_DAYS + 1),
                            available.endEpochDay,
                        )
                }
            }
        }

        private val historyQuery: Flow<NotificationHistoryQuery> =
            combine(
                activityQuery.debounce(HISTORY_QUERY_DEBOUNCE_MILLIS).distinctUntilChanged(),
                historyActionFilter,
                historyPackageName,
                historyChannelId,
                historyLimit,
            ) { query, action, packageName, channelId, limit ->
                NotificationHistoryQuery(
                    startMillis = 0,
                    endMillis = Long.MAX_VALUE,
                    search = query,
                    packageName = packageName,
                    channelId = channelId,
                    action = action.toHistoryFilter(),
                    includeExcluded = true,
                    limit = limit,
                )
            }

        private val historyRecords: Flow<HistoryRecords> =
            selectedTab.flatMapLatest { tab ->
                if (tab != InsightsTab.RECORDS) {
                    flowOf(HistoryRecords(emptyList(), 0))
                } else {
                    combine(
                        historyQuery.flatMapLatest(notificationHistoryRepository::observeHistory),
                        feedbackRepository.observeEventCorrections(),
                        adFeedbackRepository.observeByEvent(),
                        rules,
                    ) { page, corrected, adObservations, currentRules ->
                        val ruleNames = currentRules.associate { it.id to it.name }
                        HistoryRecords(
                            events =
                                page.items.map { event ->
                                    event
                                        .toListItem(
                                            correctedCategory = corrected[event.id],
                                            identity = appIdentityResolver.resolve(event.packageName),
                                            ruleNames = ruleNames,
                                        ).copy(adObservation = adObservations[event.id]?.toUiModel())
                                },
                            totalCount = page.totalCount,
                        )
                    }
                }
            }.flowOn(dispatcher)

        private val historySources: Flow<List<HistorySourceUi>> =
            selectedTab.flatMapLatest { tab ->
                if (tab != InsightsTab.RECORDS) {
                    flowOf(emptyList())
                } else {
                    notificationHistoryRepository
                        .observeSources(HISTORY_SOURCE_LIMIT)
                        .map { sources ->
                            sources.map {
                                HistorySourceUi(
                                    packageName = it.packageName,
                                    appName = appIdentityResolver.resolve(it.packageName).label,
                                    channelId = it.channelId,
                                    channelName = it.channelName,
                                    eventCount = it.eventCount,
                                )
                            }
                        }
                }
            }.flowOn(dispatcher)

        private val historyCoverage: Flow<NotificationHistoryCoverageUi?> =
            selectedTab.flatMapLatest { tab ->
                if (tab != InsightsTab.RECORDS) {
                    flowOf(null)
                } else {
                    notificationHistoryRepository.observeCoverage().map { coverage ->
                        NotificationHistoryCoverageUi(
                            totalEvents = coverage.totalEvents,
                            oldestPostedAtMillis = coverage.oldestPostedAtMillis,
                            newestPostedAtMillis = coverage.newestPostedAtMillis,
                            eventLimitReached = coverage.eventLimitReached,
                            traceCoveragePartial = coverage.traceCoveragePartial,
                        )
                    }
                }
            }

        private val history =
            combine(summaryUi, dailyInsights, suggestions, ::HistoryContent)

        private val activityControls: Flow<ActivityControls> =
            combine(activityQuery, activityFilter, ::ActivityControls)

        private val analysisControls: Flow<AnalysisControls> =
            combine(analysisPreset, customRangeStart, customRangeEnd, ::AnalysisControls)

        private val advancedContent =
            combine(
                selectedTab,
                analysis,
                availableRange,
                combine(historyRecords, historySources, historyCoverage, ::HistoryAndSources),
                combine(selectedEventDetail, analysisControls, ::DetailAndAnalysisControls),
            ) { tab, analysis, range, historyAndSources, detailAndControls ->
                AdvancedContent(
                    tab,
                    analysis,
                    range,
                    historyAndSources.records,
                    historyAndSources.sources,
                    historyAndSources.coverage,
                    detailAndControls.detail,
                    detailAndControls.controls,
                )
            }

        val uiState: StateFlow<InsightsUiState> =
            combine(
                content,
                messages,
                history,
                activityControls,
                advancedContent,
            ) { result, message, history, controls, advanced ->
                when (result) {
                    DataResult.Loading ->
                        InsightsUiState(
                            isLoading = true,
                            availableCategories = availableCategories,
                            summary = history.summary,
                            dailyInsights = history.daily,
                            suggestions = history.suggestions,
                            activityQuery = controls.query,
                            activityFilter = controls.filter,
                            userMessage = message,
                            selectedTab = advanced.tab,
                            analysis = advanced.analysis,
                            analysisPreset = advanced.analysisControls.preset,
                            customRangeStart = advanced.analysisControls.start,
                            customRangeEnd = advanced.analysisControls.end,
                            availableRange = advanced.availableRange?.toUiModel(),
                            historyEvents = advanced.history.events,
                            historyTotalCount = advanced.history.totalCount,
                            historyActionFilter = historyActionFilter.value,
                            historySources = advanced.sources,
                            historyPackageName = historyPackageName.value,
                            historyChannelId = historyChannelId.value,
                            historyCoverage = advanced.historyCoverage,
                            selectedEventDetail = advanced.detail,
                        )
                    is DataResult.Success ->
                        InsightsUiState(
                            isLoading = false,
                            events = result.data.events.filtered(controls),
                            activityTotalCount = result.data.events.size,
                            activityQuery = controls.query,
                            activityFilter = controls.filter,
                            metrics = result.data.metrics,
                            availableCategories = availableCategories,
                            summary = history.summary,
                            dailyInsights = history.daily,
                            suggestions = history.suggestions,
                            userMessage = message,
                            selectedTab = advanced.tab,
                            analysis = advanced.analysis,
                            analysisPreset = advanced.analysisControls.preset,
                            customRangeStart = advanced.analysisControls.start,
                            customRangeEnd = advanced.analysisControls.end,
                            availableRange = advanced.availableRange?.toUiModel(),
                            historyEvents = advanced.history.events,
                            historyTotalCount = advanced.history.totalCount,
                            historyActionFilter = historyActionFilter.value,
                            historySources = advanced.sources,
                            historyPackageName = historyPackageName.value,
                            historyChannelId = historyChannelId.value,
                            historyCoverage = advanced.historyCoverage,
                            selectedEventDetail = advanced.detail,
                        )
                    is DataResult.Failure ->
                        InsightsUiState(
                            isLoading = false,
                            errorMessage = uiText(R.string.message_insights_load_failed),
                            availableCategories = availableCategories,
                            summary = history.summary,
                            dailyInsights = history.daily,
                            suggestions = history.suggestions,
                            activityQuery = controls.query,
                            activityFilter = controls.filter,
                            userMessage = message,
                            selectedTab = advanced.tab,
                            analysis = advanced.analysis,
                            analysisPreset = advanced.analysisControls.preset,
                            customRangeStart = advanced.analysisControls.start,
                            customRangeEnd = advanced.analysisControls.end,
                            availableRange = advanced.availableRange?.toUiModel(),
                            historyEvents = advanced.history.events,
                            historyTotalCount = advanced.history.totalCount,
                            historyActionFilter = historyActionFilter.value,
                            historySources = advanced.sources,
                            historyPackageName = historyPackageName.value,
                            historyChannelId = historyChannelId.value,
                            historyCoverage = advanced.historyCoverage,
                            selectedEventDetail = advanced.detail,
                        )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), InsightsUiState())

        fun onUndo(eventId: String) {
            viewModelScope.launch(dispatcher) {
                runCatchingPreservingCancellation { eventRepository.undo(eventId) }
                    .onSuccess { messages.value = uiText(R.string.message_insights_excluded) }
                    .onFailure { messages.value = uiText(R.string.message_insights_update_failed) }
            }
        }

        fun onActivityQueryChange(query: String) {
            activityQuery.value = query.take(MAX_QUERY_CHARS)
            historyLimit.value = HISTORY_PAGE_SIZE
        }

        fun onActivityFilterChange(filter: ActivityActionFilter) {
            activityFilter.value = filter
        }

        fun onTabSelected(tab: InsightsTab) {
            selectedTab.value = tab
        }

        fun onHistorySourceSelected(source: HistorySourceUi?) {
            historyPackageName.value = source?.packageName
            historyChannelId.value = source?.channelId
            historyLimit.value = HISTORY_PAGE_SIZE
        }

        fun onHistoryActionFilterChange(filter: HistoryActionFilterUi) {
            historyActionFilter.value = filter
            historyLimit.value = HISTORY_PAGE_SIZE
        }

        fun onLoadMoreHistory() {
            historyLimit.value = (historyLimit.value + HISTORY_PAGE_SIZE).coerceAtMost(HISTORY_MAX_LIMIT)
        }

        fun onAnalysisPresetSelected(preset: AnalysisRangePreset) {
            analysisPreset.value = preset
            if (preset == AnalysisRangePreset.CUSTOM) {
                if (customRangeStart.value.isBlank()) {
                    customRangeStart.value = LocalDate.ofEpochDay(analysisRange.value.startEpochDay).toString()
                }
                if (customRangeEnd.value.isBlank()) {
                    customRangeEnd.value = LocalDate.ofEpochDay(analysisRange.value.endEpochDay).toString()
                }
                return
            }
            val available = availableRange.value
            val end = available?.endEpochDay ?: initialAnalysisEnd
            val start =
                when (preset) {
                    AnalysisRangePreset.LAST_7_DAYS -> end - LAST_7_DAYS_OFFSET
                    AnalysisRangePreset.LAST_30_DAYS -> end - LAST_30_DAYS_OFFSET
                    AnalysisRangePreset.LAST_90_DAYS -> end - LAST_90_DAYS_OFFSET
                    AnalysisRangePreset.ALL -> available?.startEpochDay ?: end
                    AnalysisRangePreset.CUSTOM -> return
                }.let { candidate -> available?.startEpochDay?.let { maxOf(candidate, it) } ?: candidate }
            analysisRange.value = InsightsDateRange(start, end)
        }

        fun onCustomRangeStartChange(value: String) {
            customRangeStart.value = value.take(DATE_TEXT_LENGTH)
        }

        fun onCustomRangeEndChange(value: String) {
            customRangeEnd.value = value.take(DATE_TEXT_LENGTH)
        }

        fun applyCustomRange() {
            val start =
                try {
                    LocalDate.parse(customRangeStart.value).toEpochDay()
                } catch (_: DateTimeParseException) {
                    null
                }
            val end =
                try {
                    LocalDate.parse(customRangeEnd.value).toEpochDay()
                } catch (_: DateTimeParseException) {
                    null
                }
            if (start == null || end == null || start > end) {
                messages.value = uiText(R.string.message_invalid_analysis_range)
                return
            }
            val available = availableRange.value
            val clippedStart = available?.startEpochDay?.let { maxOf(start, it) } ?: start
            val clippedEnd = available?.endEpochDay?.let { minOf(end, it) } ?: end
            if (clippedStart > clippedEnd) {
                messages.value = uiText(R.string.message_analysis_range_empty)
                return
            }
            analysisPreset.value = AnalysisRangePreset.CUSTOM
            analysisRange.value = InsightsDateRange(clippedStart, clippedEnd)
        }

        fun openEventDetail(eventId: String) {
            viewModelScope.launch(dispatcher) {
                runCatchingPreservingCancellation {
                    notificationHistoryRepository.getDetail(eventId)
                }.onSuccess { detail ->
                    selectedEventDetail.value =
                        detail?.toUiModel(appIdentityResolver.resolve(detail.event.packageName))
                }.onFailure {
                    messages.value = uiText(R.string.message_notification_detail_failed)
                }
            }
        }

        fun closeEventDetail() {
            selectedEventDetail.value = null
        }

        /**
         * Records the user's correction of the ML category for [packageName] and reflects it in the feed
         * (CLAUDE.md §5). Labels are the model's category strings (not a UI enum) so they stay in sync
         * with the bundled model. [predictedLabel] is the category previously shown, for the audit record.
         */
        fun correctCategory(
            eventId: String,
            packageName: String,
            predictedLabel: String?,
            correctedLabel: String,
        ) {
            viewModelScope.launch(dispatcher) {
                runCatchingPreservingCancellation {
                    feedbackRepository.recordCorrection(
                        CategoryFeedback(
                            packageName = packageName,
                            notificationEventId = eventId,
                            predictedLabel = predictedLabel,
                            correctedLabel = correctedLabel,
                            recordedAtMillis = clock.millis(),
                        ),
                    )
                }.onSuccess {
                    messages.value = uiText(R.string.message_recategorized, correctedLabel)
                }.onFailure { messages.value = uiText(R.string.message_recategorize_failed) }
            }
        }

        fun correctAdvertisement(
            eventId: String,
            correctedIsAdvertisement: Boolean,
        ) {
            viewModelScope.launch(dispatcher) {
                runCatchingPreservingCancellation {
                    adFeedbackRepository.recordCorrection(eventId, correctedIsAdvertisement)
                }.onSuccess {
                    messages.value =
                        uiText(
                            if (correctedIsAdvertisement) {
                                R.string.message_marked_ad
                            } else {
                                R.string.message_marked_transactional
                            },
                        )
                }.onFailure { messages.value = uiText(R.string.message_ad_feedback_failed) }
            }
        }

        fun correctSemanticIntent(
            eventId: String,
            correctedIntent: SemanticIntent,
        ) {
            viewModelScope.launch(dispatcher) {
                runCatchingPreservingCancellation {
                    adFeedbackRepository.recordCorrection(eventId, correctedIntent)
                }.onSuccess {
                    messages.value = uiText(R.string.message_semantic_corrected, correctedIntent.name)
                }.onFailure { messages.value = uiText(R.string.message_ad_feedback_failed) }
            }
        }

        fun dismissSuggestion(key: String) {
            viewModelScope.launch(dispatcher) {
                runCatchingPreservingCancellation {
                    ruleSuggestionRepository.dismiss(key, clock.millis())
                }.onFailure { messages.value = uiText(R.string.message_suggestion_failed) }
            }
        }

        /** Re-subscribes today's SQL counters when the screen resumes after a date boundary. */
        fun refreshDayBoundary() {
            startOfDayMillis.value = clock.todayStartMillis()
        }

        fun onUserMessageShown() {
            messages.value = null
        }

        private data class Content(
            val events: List<EventListItem>,
            val metrics: InsightsMetrics,
        )

        private data class ActivityControls(
            val query: String,
            val filter: ActivityActionFilter,
        )

        private data class HistoryContent(
            val summary: InsightsSummaryUi?,
            val daily: List<DailyInsightUi>,
            val suggestions: List<RuleSuggestionUi>,
        )

        private data class HistoryRecords(
            val events: List<EventListItem>,
            val totalCount: Int,
        )

        private data class HistoryAndSources(
            val records: HistoryRecords,
            val sources: List<HistorySourceUi>,
            val coverage: NotificationHistoryCoverageUi?,
        )

        private data class AnalysisControls(
            val preset: AnalysisRangePreset,
            val start: String,
            val end: String,
        )

        private data class DetailAndAnalysisControls(
            val detail: NotificationDetailUi?,
            val controls: AnalysisControls,
        )

        private data class AdvancedContent(
            val tab: InsightsTab,
            val analysis: InsightsAnalysisUi,
            val availableRange: InsightsDateRange?,
            val history: HistoryRecords,
            val sources: List<HistorySourceUi>,
            val historyCoverage: NotificationHistoryCoverageUi?,
            val detail: NotificationDetailUi?,
            val analysisControls: AnalysisControls,
        )

        private fun List<EventListItem>.filtered(controls: ActivityControls): List<EventListItem> {
            val query = controls.query.trim()
            return filter { event ->
                val actionMatches =
                    when (controls.filter) {
                        ActivityActionFilter.ALL -> true
                        ActivityActionFilter.CANCELLED -> event.action == EventActionUi.CANCELLED
                        ActivityActionFilter.SNOOZED -> event.action == EventActionUi.SNOOZED
                        ActivityActionFilter.OTHER ->
                            event.action == EventActionUi.LOGGED ||
                                event.action == EventActionUi.KEPT ||
                                event.action == EventActionUi.OTHER
                    }
                actionMatches &&
                    (
                        query.isEmpty() ||
                            event.appName.contains(query, ignoreCase = true) ||
                            event.packageName.contains(query, ignoreCase = true) ||
                            event.category?.contains(query, ignoreCase = true) == true ||
                            event.correctedCategory?.contains(query, ignoreCase = true) == true ||
                            event.channelId?.contains(query, ignoreCase = true) == true ||
                            event.matchedRuleName?.let { it is UiText.Dynamic && it.value.contains(query, true) } ==
                            true
                    )
            }
        }

        private companion object {
            const val RECENT_LIMIT = 100
            const val DAILY_LIMIT = 14
            const val HISTORY_PAGE_SIZE = 100
            const val HISTORY_MAX_LIMIT = 1_000
            const val HISTORY_QUERY_DEBOUNCE_MILLIS = 300L
            const val HISTORY_SOURCE_LIMIT = 200
            const val DEFAULT_ANALYSIS_DAYS = 7L
            const val DATE_TEXT_LENGTH = 10
            const val STOP_TIMEOUT_MS = 5_000L
            const val MAX_QUERY_CHARS = 100
            const val SUGGESTION_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1_000
            const val LAST_7_DAYS_OFFSET = 6L
            const val LAST_30_DAYS_OFFSET = 29L
            const val LAST_90_DAYS_OFFSET = 89L
        }
    }

private fun Clock.todayStartMillis(): Long =
    LocalDate
        .now(this)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

private fun HistoryActionFilterUi.toHistoryFilter(): HistoryActionFilter =
    when (this) {
        HistoryActionFilterUi.ALL -> HistoryActionFilter.ALL
        HistoryActionFilterUi.CANCELLED -> HistoryActionFilter.CANCELLED
        HistoryActionFilterUi.SNOOZED -> HistoryActionFilter.SNOOZED
        HistoryActionFilterUi.LOGGED -> HistoryActionFilter.LOGGED
        HistoryActionFilterUi.KEPT -> HistoryActionFilter.KEPT
    }

private fun InsightsDateRange.toUiModel(): AvailableRangeUi = AvailableRangeUi(startEpochDay, endEpochDay)

private fun AdObservation.toUiModel(): AdObservationUi =
    AdObservationUi(
        predictedIntent = predictedIntent,
        confidencePercent = (confidenceScore * 100).toInt().coerceIn(0, 100),
        correctedIntent = correctedIntent,
    )

private fun RuleSuggestion.toUiModel(appName: String): RuleSuggestionUi =
    when (this) {
        is RuleSuggestion.QuietChannel ->
            RuleSuggestionUi(
                key = key,
                type = RuleSuggestionTypeUi.QUIET_CHANNEL,
                appName = appName,
                packageName = packageName,
                channelId = channelId,
                numerator = silencedCount,
                denominator = totalCount,
            )
        is RuleSuggestion.MarketingRuleDraft ->
            RuleSuggestionUi(
                key = key,
                type = RuleSuggestionTypeUi.MARKETING_RULE,
                appName = appName,
                packageName = packageName,
                numerator = marketingCorrections,
                denominator = totalCorrections,
            )
    }

private fun RuleSuggestion.packageName(): String =
    when (this) {
        is RuleSuggestion.QuietChannel -> packageName
        is RuleSuggestion.MarketingRuleDraft -> packageName
    }
