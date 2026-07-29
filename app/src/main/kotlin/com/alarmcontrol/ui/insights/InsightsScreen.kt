package com.alarmcontrol.ui.insights

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alarmcontrol.R
import com.alarmcontrol.core.filtering.DecisionTraceLane
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.service.NotificationAccess
import com.alarmcontrol.ui.asString
import com.alarmcontrol.ui.designsystem.ExpressiveHeroCard
import com.alarmcontrol.ui.designsystem.MaxWidthContent
import com.alarmcontrol.ui.privacy.ProtectSensitiveWindow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun InsightsRoute(
    viewModel: InsightsViewModel = hiltViewModel(),
    onCreateRule: (packageName: String, category: String?) -> Unit = { _, _ -> },
    onCreateKeepRule: (packageName: String, channelId: String?) -> Unit = { _, _ -> },
    onCreateMarketingMonitor: (packageName: String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> viewModel.refreshDayBoundary()
                    Lifecycle.Event.ON_STOP -> viewModel.closeEventDetail()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.closeEventDetail()
        }
    }
    InsightsScreen(
        state = state,
        onUndo = viewModel::onUndo,
        onRecategorize = viewModel::correctCategory,
        onSemanticCorrection = viewModel::correctSemanticIntent,
        onActivityQueryChange = viewModel::onActivityQueryChange,
        onActivityFilterChange = viewModel::onActivityFilterChange,
        onTabSelected = viewModel::onTabSelected,
        onAnalysisPresetSelected = viewModel::onAnalysisPresetSelected,
        onCustomRangeStartChange = viewModel::onCustomRangeStartChange,
        onCustomRangeEndChange = viewModel::onCustomRangeEndChange,
        onApplyCustomRange = viewModel::applyCustomRange,
        onHistoryActionFilterChange = viewModel::onHistoryActionFilterChange,
        onHistorySourceSelected = viewModel::onHistorySourceSelected,
        onLoadMoreHistory = viewModel::onLoadMoreHistory,
        onOpenEventDetail = viewModel::openEventDetail,
        onCloseEventDetail = viewModel::closeEventDetail,
        onUserMessageShown = viewModel::onUserMessageShown,
        onCreateRule = onCreateRule,
        onCreateKeepRule = onCreateKeepRule,
        onCreateMarketingMonitor = onCreateMarketingMonitor,
        onDismissSuggestion = viewModel::dismissSuggestion,
        onOpenNotificationSettings = { packageName, channelId ->
            NotificationAccess.openNotificationSettings(context, packageName, channelId)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    state: InsightsUiState,
    onUndo: (String) -> Unit,
    onRecategorize: (eventId: String, packageName: String, predictedLabel: String?, correctedLabel: String) -> Unit,
    onUserMessageShown: () -> Unit,
    onSemanticCorrection: (eventId: String, correctedIntent: SemanticIntent) -> Unit = { _, _ -> },
    onActivityQueryChange: (String) -> Unit = {},
    onActivityFilterChange: (ActivityActionFilter) -> Unit = {},
    onTabSelected: (InsightsTab) -> Unit = {},
    onAnalysisPresetSelected: (AnalysisRangePreset) -> Unit = {},
    onCustomRangeStartChange: (String) -> Unit = {},
    onCustomRangeEndChange: (String) -> Unit = {},
    onApplyCustomRange: () -> Unit = {},
    onHistoryActionFilterChange: (HistoryActionFilterUi) -> Unit = {},
    onHistorySourceSelected: (HistorySourceUi?) -> Unit = {},
    onLoadMoreHistory: () -> Unit = {},
    onOpenEventDetail: (String) -> Unit = {},
    onCloseEventDetail: () -> Unit = {},
    onCreateRule: (packageName: String, category: String?) -> Unit = { _, _ -> },
    onCreateKeepRule: (packageName: String, channelId: String?) -> Unit = { _, _ -> },
    onCreateMarketingMonitor: (packageName: String) -> Unit = {},
    onDismissSuggestion: (suggestionKey: String) -> Unit = {},
    onOpenNotificationSettings: (packageName: String, channelId: String?) -> Unit = { _, _ -> },
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val userMessage = state.userMessage?.asString()
    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbarHostState.showSnackbar(it)
            onUserMessageShown()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_insights)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.errorMessage != null ->
                    Text(
                        text = state.errorMessage.asString(),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                else ->
                    MaxWidthContent(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            InsightsTabSelector(
                                selected = state.selectedTab,
                                onSelected = onTabSelected,
                            )
                            when (state.selectedTab) {
                                InsightsTab.OVERVIEW ->
                                    InsightsContent(
                                        state = state,
                                        onUndo = onUndo,
                                        onRecategorize = onRecategorize,
                                        onActivityQueryChange = onActivityQueryChange,
                                        onActivityFilterChange = onActivityFilterChange,
                                        onCreateRule = onCreateRule,
                                        onCreateKeepRule = onCreateKeepRule,
                                        onCreateMarketingMonitor = onCreateMarketingMonitor,
                                        onDismissSuggestion = onDismissSuggestion,
                                        onOpenNotificationSettings = onOpenNotificationSettings,
                                        onSemanticCorrection = onSemanticCorrection,
                                        onOpenEventDetail = onOpenEventDetail,
                                    )
                                InsightsTab.ANALYSIS ->
                                    InsightsAnalysisContent(
                                        state = state,
                                        onPresetSelected = onAnalysisPresetSelected,
                                        onCustomRangeStartChange = onCustomRangeStartChange,
                                        onCustomRangeEndChange = onCustomRangeEndChange,
                                        onApplyCustomRange = onApplyCustomRange,
                                        onOpenNotificationSettings = onOpenNotificationSettings,
                                    )
                                InsightsTab.RECORDS ->
                                    NotificationRecordsContent(
                                        state = state,
                                        onQueryChange = onActivityQueryChange,
                                        onFilterChange = onHistoryActionFilterChange,
                                        onSourceSelected = onHistorySourceSelected,
                                        onLoadMore = onLoadMoreHistory,
                                        onUndo = onUndo,
                                        onRecategorize = onRecategorize,
                                        onCreateRule = onCreateRule,
                                        onCreateKeepRule = onCreateKeepRule,
                                        onOpenNotificationSettings = onOpenNotificationSettings,
                                        onSemanticCorrection = onSemanticCorrection,
                                        onOpenEventDetail = onOpenEventDetail,
                                    )
                            }
                        }
                    }
            }
        }
    }
    state.selectedEventDetail?.let { detail ->
        ProtectSensitiveWindow()
        NotificationDetailDialog(detail = detail, onDismiss = onCloseEventDetail)
    }
}

@Composable
private fun InsightsContent(
    state: InsightsUiState,
    onUndo: (String) -> Unit,
    onRecategorize: (String, String, String?, String) -> Unit,
    onActivityQueryChange: (String) -> Unit,
    onActivityFilterChange: (ActivityActionFilter) -> Unit,
    onCreateRule: (String, String?) -> Unit,
    onCreateKeepRule: (String, String?) -> Unit,
    onCreateMarketingMonitor: (String) -> Unit,
    onDismissSuggestion: (String) -> Unit,
    onOpenNotificationSettings: (String, String?) -> Unit,
    onSemanticCorrection: (String, SemanticIntent) -> Unit,
    onOpenEventDetail: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(INSIGHTS_LIST_TEST_TAG),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { InsightsSummaryCard(state.summary, state.metrics) }
        item { MetricsRow(state.metrics) }
        if (!state.overviewSourceComplete || state.dailyInsights.any { !it.sourceComplete }) {
            item { SourceIncompleteNotice() }
        }
        if (state.suggestions.isNotEmpty()) {
            item { SectionHeading(R.string.insights_suggestions) }
            items(state.suggestions, key = { it.key }) { suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    onOpenChannel = {
                        onOpenNotificationSettings(suggestion.packageName, suggestion.channelId)
                    },
                    onOpenDraft = { onCreateMarketingMonitor(suggestion.packageName) },
                    onDismiss = { onDismissSuggestion(suggestion.key) },
                )
            }
        }
        item { SectionHeading(R.string.insights_daily_history) }
        if (state.dailyInsights.size >= 2) {
            item { DailyMutedTrend(state.dailyInsights) }
        }
        if (state.dailyInsights.isEmpty()) {
            item { Text(stringResource(R.string.insights_daily_empty)) }
        }
        itemsIndexed(state.dailyInsights, key = { _, day -> day.epochDay }) { index, day ->
            DailyInsightCard(
                day = day,
                initiallyExpanded = index == 0,
                onOpenNotificationSettings = onOpenNotificationSettings,
            )
        }
        item { SectionHeading(R.string.insights_recent_activity) }
        item {
            ActivityFilters(
                query = state.activityQuery,
                selected = state.activityFilter,
                onQueryChange = onActivityQueryChange,
                onFilterChange = onActivityFilterChange,
            )
        }
        if (state.events.isEmpty()) {
            item {
                val message =
                    if (state.activityTotalCount > 0) {
                        R.string.insights_activity_no_matches
                    } else {
                        R.string.insights_activity_empty
                    }
                Text(stringResource(message))
            }
        }
        items(state.events, key = { it.id }) { event ->
            EventRow(
                event = event,
                availableCategories = state.availableCategories,
                onUndo = { onUndo(event.id) },
                onRecategorize = { corrected ->
                    onRecategorize(event.id, event.packageName, event.predictedCategory, corrected)
                },
                onCreateRule = { onCreateRule(event.packageName, event.category) },
                onCreateKeepRule = { channelId -> onCreateKeepRule(event.packageName, channelId) },
                onOpenNotificationSettings = {
                    onOpenNotificationSettings(event.packageName, event.channelId)
                },
                onSemanticCorrection = { corrected -> onSemanticCorrection(event.id, corrected) },
                onOpenDetail = { onOpenEventDetail(event.id) },
            )
        }
    }
}

internal const val INSIGHTS_LIST_TEST_TAG = "insights_list"

@Composable
private fun SuggestionCard(
    suggestion: RuleSuggestionUi,
    onOpenChannel: () -> Unit,
    onOpenDraft: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    if (suggestion.type == RuleSuggestionTypeUi.QUIET_CHANNEL) {
                        R.string.suggestion_quiet_channel_title
                    } else {
                        R.string.suggestion_marketing_title
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (suggestion.type == RuleSuggestionTypeUi.QUIET_CHANNEL) {
                    stringResource(
                        R.string.suggestion_quiet_channel_body,
                        suggestion.appName,
                        suggestion.channelId.orEmpty(),
                        suggestion.numerator,
                        suggestion.denominator,
                    )
                } else {
                    stringResource(
                        R.string.suggestion_marketing_body,
                        suggestion.appName,
                        suggestion.numerator,
                        suggestion.denominator,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick =
                        if (suggestion.type == RuleSuggestionTypeUi.QUIET_CHANNEL) {
                            onOpenChannel
                        } else {
                            onOpenDraft
                        },
                ) {
                    Text(
                        stringResource(
                            if (suggestion.type == RuleSuggestionTypeUi.QUIET_CHANNEL) {
                                R.string.open_settings
                            } else {
                                R.string.suggestion_review_draft
                            },
                        ),
                    )
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
}

@Composable
private fun SectionHeading(
    @androidx.annotation.StringRes textRes: Int,
) {
    Text(
        stringResource(textRes),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun MetricsRow(metrics: InsightsMetrics) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard(stringResource(R.string.insights_cancelled), metrics.cancelled, Modifier.weight(1f))
            MetricCard(stringResource(R.string.insights_snoozed), metrics.snoozed, Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.insights_recorded_today, metrics.totalRecorded),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Compact native-Canvas trend, oldest day on the left and newest on the right. */
@Composable
private fun DailyMutedTrend(days: List<DailyInsightUi>) {
    val chronological = days.asReversed()
    val maximum = chronological.maxOfOrNull(DailyInsightUi::mutedCount)?.coerceAtLeast(1) ?: 1
    val barColor = MaterialTheme.colorScheme.tertiary
    val description = stringResource(R.string.insights_trend_description, chronological.size)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.insights_muted_trend), style = MaterialTheme.typography.titleSmall)
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .semantics { contentDescription = description },
            ) {
                val slotWidth = size.width / chronological.size
                val barWidth = slotWidth * TREND_BAR_WIDTH_FRACTION
                chronological.forEachIndexed { index, day ->
                    val height = size.height * (day.mutedCount / maximum.toFloat())
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(index * slotWidth + (slotWidth - barWidth) / 2f, size.height - height),
                        size = Size(barWidth, height),
                        cornerRadius = CornerRadius(barWidth / TREND_BAR_CORNER_DIVISOR),
                    )
                }
            }
        }
    }
}

private const val TREND_BAR_WIDTH_FRACTION = 0.55f
private const val TREND_BAR_CORNER_DIVISOR = 3f
private const val DAILY_APP_DISPLAY_LIMIT = 5

@Composable
private fun MetricCard(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.semantics { contentDescription = "$label: $value" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The periodic-insights headline (CLAUDE.md §5); a placeholder until the first daily run completes. */
@Composable
private fun InsightsSummaryCard(
    summary: InsightsSummaryUi?,
    metrics: InsightsMetrics,
) {
    ExpressiveHeroCard {
        Text(stringResource(R.string.insights_today), style = MaterialTheme.typography.titleMedium)
        Text(
            text = metrics.total.toString(),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.insights_silenced_today),
            style = MaterialTheme.typography.labelLarge,
        )
        if (summary == null) {
            Text(
                text = stringResource(R.string.insights_analyzing),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            val headline =
                if (summary.mostMutedPackage == null) {
                    stringResource(R.string.insights_none_muted)
                } else {
                    stringResource(
                        R.string.insights_most_muted,
                        summary.mostMutedAppName ?: summary.mostMutedPackage,
                        summary.mostMutedCount,
                    )
                }
            Text(headline, style = MaterialTheme.typography.bodyMedium)
            if (summary.anomalyCount > 0) {
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.insights_anomaly_spikes,
                            summary.anomalyCount,
                            summary.anomalyCount,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text =
                    stringResource(
                        R.string.insights_updated,
                        DateUtils.getRelativeTimeSpanString(summary.generatedAtMillis),
                    ),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** One day's background rollup: header counts, a category bar chart, and the top-rules list (§5). */
@Composable
private fun DailyInsightCard(
    day: DailyInsightUi,
    initiallyExpanded: Boolean,
    onOpenNotificationSettings: (String, String?) -> Unit,
) {
    var expanded by remember(day.epochDay) { mutableStateOf(initiallyExpanded) }
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatDay(day.epochDay), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(R.string.insights_daily_counts, day.mutedCount, day.totalNotifications),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            day.mutedDelta?.let { delta ->
                Text(
                    text =
                        when {
                            delta > 0 -> stringResource(R.string.insights_delta_up, delta)
                            delta < 0 -> stringResource(R.string.insights_delta_down, -delta)
                            else -> stringResource(R.string.insights_delta_same)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                stringResource(
                    R.string.insights_action_breakdown,
                    day.actions.cancelled,
                    day.actions.snoozed,
                    day.actions.loggedOnly,
                    day.actions.kept,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!day.breakdownComplete && day.totalNotifications > 0) {
                Text(
                    stringResource(R.string.insights_breakdown_partial),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                DailyInsightDetails(day, onOpenNotificationSettings)
            }
        }
    }
}

@Composable
internal fun SourceIncompleteNotice() {
    Text(
        text = stringResource(R.string.insights_source_incomplete),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun DailyInsightDetails(
    day: DailyInsightUi,
    onOpenNotificationSettings: (String, String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (day.monitoredActions.cancelled + day.monitoredActions.snoozed > 0) {
            Text(
                stringResource(
                    R.string.insights_monitored_breakdown,
                    day.monitoredActions.cancelled,
                    day.monitoredActions.snoozed,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (!day.hasDetailedBreakdown()) {
            Text(
                stringResource(R.string.insights_no_notifications),
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        DailyInsightRuleDetails(day)
        DailyInsightSignalDetails(day)
        DailyInsightChannelDetails(day.channels, onOpenNotificationSettings)
        DailyInsightLearningDetails(day)
    }
}

private fun DailyInsightUi.hasDetailedBreakdown(): Boolean =
    categories.isNotEmpty() ||
        topRules.isNotEmpty() ||
        topMonitoredRules.isNotEmpty() ||
        channels.isNotEmpty() ||
        apps.isNotEmpty() ||
        hours.isNotEmpty() ||
        semanticIntents.isNotEmpty() ||
        mlClassifiedCount + categoryCorrectionCount + semanticCorrectionCount > 0

@Composable
private fun DailyInsightRuleDetails(day: DailyInsightUi) {
    if (day.categories.isNotEmpty()) {
        Text(stringResource(R.string.insights_categories), style = MaterialTheme.typography.labelLarge)
        CategoryBars(day.categories)
    }
    if (day.topRules.isNotEmpty()) {
        Text(stringResource(R.string.insights_top_rules), style = MaterialTheme.typography.labelLarge)
        TopRulesList(day.topRules)
    }
    if (day.topMonitoredRules.isNotEmpty()) {
        Text(
            stringResource(R.string.insights_top_monitored_rules),
            style = MaterialTheme.typography.labelLarge,
        )
        TopRulesList(day.topMonitoredRules)
    }
}

@Composable
private fun DailyInsightSignalDetails(day: DailyInsightUi) {
    if (day.apps.isNotEmpty()) {
        Text(stringResource(R.string.insights_analysis_apps), style = MaterialTheme.typography.labelLarge)
        day.apps.take(DAILY_APP_DISPLAY_LIMIT).forEach { app ->
            Text(
                stringResource(
                    R.string.insights_daily_app_count,
                    app.appName,
                    app.totalCount,
                    app.silencedCount,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (day.semanticIntents.isNotEmpty()) {
        Text(stringResource(R.string.insights_analysis_semantics), style = MaterialTheme.typography.labelLarge)
        NamedBars(day.semanticIntents.map { it.label.asString() to it.count })
    }
    if (day.hours.isNotEmpty()) {
        Text(stringResource(R.string.insights_analysis_hours), style = MaterialTheme.typography.labelLarge)
        HourDistribution(day.hours)
    }
}

@Composable
private fun DailyInsightChannelDetails(
    channels: List<ChannelShareUi>,
    onOpenNotificationSettings: (String, String?) -> Unit,
) {
    if (channels.isEmpty()) return
    Text(stringResource(R.string.insights_top_channels), style = MaterialTheme.typography.labelLarge)
    channels.forEach { channel ->
        TextButton(
            onClick = { onOpenNotificationSettings(channel.packageName, channel.channelId) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    R.string.insights_channel_count,
                    channel.appName,
                    channel.channelName ?: channel.channelId,
                    channel.count,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DailyInsightLearningDetails(day: DailyInsightUi) {
    if (day.mlClassifiedCount + day.categoryCorrectionCount + day.semanticCorrectionCount > 0) {
        Text(
            stringResource(
                R.string.insights_learning_counts,
                day.mlClassifiedCount,
                day.categoryCorrectionCount,
                day.semanticCorrectionCount,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Horizontal bar chart of category counts, drawn with a native [Canvas] (no chart library, §2). */
@Composable
private fun CategoryBars(categories: List<CategoryShareUi>) {
    val max = (categories.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        categories.forEach { category ->
            val label = category.label.asString()
            val description = stringResource(R.string.insights_category_count, label, category.count)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(label, style = MaterialTheme.typography.bodySmall)
                    Text(category.count.toString(), style = MaterialTheme.typography.bodySmall)
                }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .semantics {
                            contentDescription = description
                            progressBarRangeInfo =
                                ProgressBarRangeInfo(category.count.toFloat(), 0f..max.toFloat())
                        },
                ) {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(color = trackColor, cornerRadius = radius)
                    drawRoundRect(
                        color = barColor,
                        cornerRadius = radius,
                        size = size.copy(width = size.width * (category.count / max.toFloat())),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityFilters(
    query: String,
    selected: ActivityActionFilter,
    onQueryChange: (String) -> Unit,
    onFilterChange: (ActivityActionFilter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.insights_search)) },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.insights_clear_search))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ActivityActionFilter.entries.size) { index ->
                val filter = ActivityActionFilter.entries[index]
                FilterChip(
                    selected = selected == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(stringResource(filter.labelRes)) },
                )
            }
        }
    }
}

private val ActivityActionFilter.labelRes: Int
    get() =
        when (this) {
            ActivityActionFilter.ALL -> R.string.insights_filter_all
            ActivityActionFilter.CANCELLED -> R.string.insights_cancelled
            ActivityActionFilter.SNOOZED -> R.string.insights_snoozed
            ActivityActionFilter.OTHER -> R.string.insights_filter_other
        }

/** Stylized list of the day's most-triggered rules, each with a count badge. */
@Composable
private fun TopRulesList(rules: List<RuleTriggerUi>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rules.forEach { rule ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = rule.label.asString(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                CountBadge(rule.count)
            }
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun formatDay(epochDay: Long): String =
    LocalDate
        .ofEpochDay(epochDay)
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))

@Composable
internal fun EventRow(
    event: EventListItem,
    availableCategories: List<String>,
    onUndo: () -> Unit,
    onRecategorize: (correctedLabel: String) -> Unit,
    onCreateRule: () -> Unit,
    onCreateKeepRule: (channelId: String?) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSemanticCorrection: (SemanticIntent) -> Unit,
    onOpenDetail: () -> Unit = {},
) {
    var explanationExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EventIdentity(event)
                EventDetails(event, Modifier.weight(1f))
                EventActionsMenu(
                    onCreateRule = onCreateRule,
                    onCreateKeepRule = onCreateKeepRule,
                    channelId = event.channelId,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    adObservation = event.adObservation,
                    onSemanticCorrection = onSemanticCorrection,
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (availableCategories.isNotEmpty()) {
                    item {
                        RecategorizeMenu(categories = availableCategories, onSelect = onRecategorize)
                    }
                }
                item {
                    TextButton(onClick = { explanationExpanded = !explanationExpanded }) {
                        Text(
                            stringResource(
                                if (explanationExpanded) {
                                    R.string.insights_hide_explanation
                                } else {
                                    R.string.insights_show_explanation
                                },
                            ),
                        )
                    }
                }
                item {
                    TextButton(onClick = onOpenDetail) {
                        Text(stringResource(R.string.insights_view_details))
                    }
                }
                if (event.undone) {
                    item {
                        Text(
                            stringResource(R.string.insights_undone),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                } else if (event.canUndo) {
                    item {
                        TextButton(onClick = onUndo) { Text(stringResource(R.string.insights_undo)) }
                    }
                }
            }
            AnimatedVisibility(explanationExpanded) { DecisionExplanation(event) }
        }
    }
}

@Composable
private fun EventIdentity(event: EventListItem) {
    Box(
        modifier = Modifier.padding(end = 12.dp).size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        val icon = event.appIcon
        if (icon == null) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(event.appName.firstOrNull()?.uppercase() ?: "?")
                }
            }
        } else {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun EventDetails(
    event: EventListItem,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = event.appName,
            style = MaterialTheme.typography.titleSmall,
            textDecoration = if (event.undone) TextDecoration.LineThrough else null,
        )
        if (event.appName != event.packageName) {
            Text(
                text = event.packageName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(event.subtitle(), style = MaterialTheme.typography.bodySmall)
        event.channelId?.let { channelId ->
            Text(
                stringResource(R.string.insights_channel, channelId),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        event.correctedCategory?.let { corrected ->
            Text(
                text = stringResource(R.string.insights_recategorized, categoryDisplayName(corrected)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        event.adObservation?.let { observation -> AdObservationLabel(observation) }
    }
}

@Composable
private fun EventListItem.subtitle(): String =
    buildString {
        append(actionLabel.asString())
        category?.let { append(" · ").append(categoryDisplayName(it)) }
        append(" · ").append(DateUtils.getRelativeTimeSpanString(recordedAtMillis).toString())
    }

@Composable
private fun EventActionsMenu(
    onCreateRule: () -> Unit,
    onCreateKeepRule: (String?) -> Unit,
    channelId: String?,
    onOpenNotificationSettings: () -> Unit,
    adObservation: AdObservationUi?,
    onSemanticCorrection: (SemanticIntent) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.insights_more_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.insights_create_rule)) },
                onClick = {
                    expanded = false
                    onCreateRule()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.insights_always_keep_app)) },
                onClick = {
                    expanded = false
                    onCreateKeepRule(null)
                },
            )
            if (channelId != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.insights_always_keep_channel)) },
                    onClick = {
                        expanded = false
                        onCreateKeepRule(channelId)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.insights_app_notification_settings)) },
                onClick = {
                    expanded = false
                    onOpenNotificationSettings()
                },
            )
            if (adObservation != null) {
                SemanticIntent.entries.forEach { intent ->
                    DropdownMenuItem(
                        text = { Text(semanticCorrectionLabel(intent)) },
                        enabled = adObservation.correctedIntent != intent,
                        onClick = {
                            expanded = false
                            onSemanticCorrection(intent)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AdObservationLabel(observation: AdObservationUi) {
    val verdict =
        observation.correctedIntent?.let {
            stringResource(R.string.insights_semantic_corrected, semanticIntentName(it))
        } ?: stringResource(
            R.string.insights_semantic_prediction,
            semanticIntentName(observation.predictedIntent),
            observation.confidencePercent,
        )
    Text(
        text = verdict,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

@Composable
private fun DecisionExplanation(event: EventListItem) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(
                R.string.insights_actual_decision,
                event.actionLabel.asString(),
                event.matchedRuleName?.asString() ?: stringResource(R.string.insights_no_matching_rule),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (event.monitoredActionLabel != null) {
            Text(
                stringResource(
                    R.string.insights_monitor_decision,
                    event.monitoredActionLabel.asString(),
                    event.monitoredRuleName?.asString() ?: stringResource(R.string.insights_deleted_rule),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        event.mlConfidencePercent?.let { confidence ->
            Text(
                stringResource(R.string.insights_ml_confidence, confidence),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (event.decisionTrace.isEmpty()) {
            Text(stringResource(R.string.insights_trace_unavailable), style = MaterialTheme.typography.bodySmall)
        } else {
            event.decisionTrace.forEach { trace ->
                val lane =
                    stringResource(
                        if (trace.lane == DecisionTraceLane.ACTIVE) {
                            R.string.rule_mode_active
                        } else {
                            R.string.rule_mode_monitor
                        },
                    )
                Text(
                    stringResource(
                        R.string.insights_trace_line,
                        lane,
                        trace.conditionLabel.asString(),
                        trace.resultLabel.asString(),
                    ),
                    modifier = Modifier.padding(start = (trace.depth * 10).dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun semanticCorrectionLabel(intent: SemanticIntent): String =
    when (intent) {
        SemanticIntent.MARKETING -> stringResource(R.string.insights_mark_ad)
        SemanticIntent.TRANSACTIONAL -> stringResource(R.string.insights_mark_transactional)
        else -> stringResource(R.string.insights_mark_semantic, semanticIntentName(intent))
    }

@Composable
private fun semanticIntentName(intent: SemanticIntent): String =
    stringResource(
        when (intent) {
            SemanticIntent.MARKETING -> R.string.semantic_marketing
            SemanticIntent.TRANSACTIONAL -> R.string.semantic_transactional
            SemanticIntent.SECURITY -> R.string.semantic_security
            SemanticIntent.DELIVERY -> R.string.semantic_delivery
            SemanticIntent.SOCIAL -> R.string.semantic_social
            SemanticIntent.OTHER -> R.string.semantic_other
            SemanticIntent.AMBIGUOUS -> R.string.semantic_ambiguous
        },
    )

/** "Fix label" affordance: a Material 3 dropdown of the model's categories (CLAUDE.md §5). */
@Composable
private fun RecategorizeMenu(
    categories: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.insights_fix_label)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(categoryDisplayName(category)) },
                    onClick = {
                        expanded = false
                        onSelect(category)
                    },
                )
            }
        }
    }
}

@Composable
private fun categoryDisplayName(category: String): String =
    when (category.lowercase(Locale.ROOT)) {
        "promotion" -> stringResource(R.string.category_promotion)
        "social" -> stringResource(R.string.category_social)
        "news" -> stringResource(R.string.category_news)
        "alarm" -> stringResource(R.string.category_alarm)
        else -> category.replaceFirstChar { it.uppercase() }
    }
