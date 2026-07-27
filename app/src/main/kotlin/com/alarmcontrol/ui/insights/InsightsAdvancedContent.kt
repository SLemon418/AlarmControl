package com.alarmcontrol.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.alarmcontrol.R
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.ui.asString
import com.alarmcontrol.ui.designsystem.ExpressiveHeroCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun InsightsTabSelector(
    selected: InsightsTab,
    onSelected: (InsightsTab) -> Unit,
) {
    TabRow(selectedTabIndex = selected.ordinal) {
        InsightsTab.entries.forEach { tab ->
            Tab(
                selected = selected == tab,
                onClick = { onSelected(tab) },
                text = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

@Composable
internal fun InsightsAnalysisContent(
    state: InsightsUiState,
    onPresetSelected: (AnalysisRangePreset) -> Unit,
    onCustomRangeStartChange: (String) -> Unit,
    onCustomRangeEndChange: (String) -> Unit,
    onApplyCustomRange: () -> Unit,
    onOpenNotificationSettings: (String, String?) -> Unit,
) {
    val analysis = state.analysis
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(INSIGHTS_ANALYSIS_TEST_TAG),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            AnalysisRangePicker(
                state = state,
                onPresetSelected = onPresetSelected,
                onCustomRangeStartChange = onCustomRangeStartChange,
                onCustomRangeEndChange = onCustomRangeEndChange,
                onApplyCustomRange = onApplyCustomRange,
            )
        }
        item { AnalysisHero(analysis) }
        if (analysis.totalNotifications == 0) {
            item {
                EmptyAnalysisCard()
            }
            return@LazyColumn
        }
        if (analysis.trend.isNotEmpty()) {
            item {
                AnalysisSection(R.string.insights_analysis_trend) {
                    TrendBars(analysis.trend, analysis.bucketLabel?.asString().orEmpty())
                }
            }
        }
        if (analysis.apps.isNotEmpty()) {
            item {
                AnalysisSection(R.string.insights_analysis_apps) {
                    AppBreakdown(analysis.apps)
                }
            }
        }
        if (analysis.rules.isNotEmpty()) {
            item {
                AnalysisSection(R.string.insights_analysis_rules) {
                    RuleBreakdown(analysis.rules)
                }
            }
        }
        if (analysis.categories.isNotEmpty()) {
            item {
                AnalysisSection(R.string.insights_categories) {
                    NamedBars(analysis.categories.map { it.label.asString() to it.count })
                }
            }
        }
        if (analysis.semanticIntents.isNotEmpty()) {
            item {
                AnalysisSection(R.string.insights_analysis_semantics) {
                    NamedBars(analysis.semanticIntents.map { it.label.asString() to it.count })
                }
            }
        }
        if (analysis.hours.isNotEmpty()) {
            item {
                AnalysisSection(R.string.insights_analysis_hours) {
                    HourDistribution(analysis.hours)
                }
            }
        }
        if (analysis.channels.isNotEmpty()) {
            item {
                AnalysisSection(R.string.insights_top_channels) {
                    ChannelBreakdown(analysis.channels, onOpenNotificationSettings)
                }
            }
        }
        item {
            LearningCoverageCard(analysis)
        }
    }
}

@Composable
internal fun NotificationRecordsContent(
    state: InsightsUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (HistoryActionFilterUi) -> Unit,
    onSourceSelected: (HistorySourceUi?) -> Unit,
    onLoadMore: () -> Unit = {},
    onUndo: (String) -> Unit,
    onRecategorize: (String, String, String?, String) -> Unit,
    onCreateRule: (String, String?) -> Unit,
    onCreateKeepRule: (String, String?) -> Unit,
    onOpenNotificationSettings: (String, String?) -> Unit,
    onSemanticCorrection: (String, SemanticIntent) -> Unit,
    onOpenEventDetail: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(INSIGHTS_RECORDS_TEST_TAG),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            NotificationRecordsHero(state)
        }
        item {
            RecordsFilters(
                query = state.activityQuery,
                selectedAction = state.historyActionFilter,
                sources = state.historySources,
                selectedPackage = state.historyPackageName,
                selectedChannel = state.historyChannelId,
                onQueryChange = onQueryChange,
                onFilterChange = onFilterChange,
                onSourceSelected = onSourceSelected,
            )
        }
        if (state.historyEvents.isEmpty()) {
            item {
                NotificationRecordsEmptyCard()
            }
        }
        items(state.historyEvents, key = EventListItem::id) { event ->
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
        if (state.historyTotalCount > state.historyEvents.size) {
            item {
                NotificationRecordsLoadMore(
                    visibleCount = state.historyEvents.size,
                    totalCount = state.historyTotalCount,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

@Composable
private fun NotificationRecordsHero(state: InsightsUiState) {
    ExpressiveHeroCard {
        Text(
            stringResource(R.string.insights_records_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.insights_records_count, state.historyTotalCount),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.insights_records_privacy),
            style = MaterialTheme.typography.bodySmall,
        )
        state.historyCoverage?.let { coverage ->
            Text(
                coverage.retentionSummary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (coverage.eventLimitReached) {
                Text(
                    stringResource(R.string.insights_records_retention_limited),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (coverage.traceCoveragePartial) {
                Text(
                    stringResource(R.string.insights_records_trace_partial),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NotificationRecordsEmptyCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.insights_records_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.insights_records_empty_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun NotificationRecordsLoadMore(
    visibleCount: Int,
    totalCount: Int,
    onLoadMore: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.insights_records_limit, visibleCount, totalCount),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = onLoadMore,
            enabled = visibleCount < MAX_HISTORY_EVENTS,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.insights_records_load_more))
        }
    }
}

@Composable
private fun NotificationHistoryCoverageUi.retentionSummary(): String {
    val formatter =
        DateTimeFormatter
            .ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
    val zone = ZoneId.systemDefault()
    val oldest =
        oldestPostedAtMillis
            ?.let {
                Instant
                    .ofEpochMilli(it)
                    .atZone(zone)
                    .toLocalDate()
                    .format(formatter)
            }
            ?: "—"
    val newest =
        newestPostedAtMillis
            ?.let {
                Instant
                    .ofEpochMilli(it)
                    .atZone(zone)
                    .toLocalDate()
                    .format(formatter)
            }
            ?: "—"
    return stringResource(R.string.insights_records_retained_range, oldest, newest, totalEvents)
}

private const val MAX_HISTORY_EVENTS = 1_000

@Composable
internal fun NotificationDetailDialog(
    detail: NotificationDetailUi,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(detail.appName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(detail.packageName, style = MaterialTheme.typography.labelSmall)
                when (detail.contentState) {
                    NotificationDetailContentUi.AVAILABLE -> {
                        detail.title?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium)
                        }
                        detail.text?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            stringResource(R.string.insights_detail_local_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    NotificationDetailContentUi.NOT_STORED ->
                        Text(stringResource(R.string.insights_detail_not_stored))
                    NotificationDetailContentUi.EXPIRED ->
                        Text(stringResource(R.string.insights_detail_expired))
                    NotificationDetailContentUi.UNREADABLE ->
                        Text(stringResource(R.string.insights_detail_unreadable))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun AnalysisRangePicker(
    state: InsightsUiState,
    onPresetSelected: (AnalysisRangePreset) -> Unit,
    onCustomRangeStartChange: (String) -> Unit,
    onCustomRangeEndChange: (String) -> Unit,
    onApplyCustomRange: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AnalysisRangePreset.entries) { preset ->
                FilterChip(
                    selected = state.analysisPreset == preset,
                    onClick = { onPresetSelected(preset) },
                    label = { Text(stringResource(preset.labelRes)) },
                )
            }
        }
        if (state.analysisPreset == AnalysisRangePreset.CUSTOM) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.customRangeStart,
                    onValueChange = onCustomRangeStartChange,
                    label = { Text(stringResource(R.string.insights_range_start)) },
                    placeholder = { Text("2026-01-01") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.customRangeEnd,
                    onValueChange = onCustomRangeEndChange,
                    label = { Text(stringResource(R.string.insights_range_end)) },
                    placeholder = { Text("2026-01-31") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(onClick = onApplyCustomRange, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.insights_range_apply))
            }
        }
        state.availableRange?.let {
            Text(
                stringResource(
                    R.string.insights_available_range,
                    formatAnalysisDay(it.startEpochDay),
                    formatAnalysisDay(it.endEpochDay),
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun AnalysisHero(analysis: InsightsAnalysisUi) {
    ExpressiveHeroCard {
        Text(
            stringResource(R.string.insights_analysis_summary),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        if (analysis.startEpochDay != null && analysis.endEpochDay != null) {
            Text(
                stringResource(
                    R.string.insights_range_value,
                    formatAnalysisDay(analysis.startEpochDay),
                    formatAnalysisDay(analysis.endEpochDay),
                ),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(
            stringResource(
                R.string.insights_analysis_silenced,
                analysis.silencedCount,
                analysis.totalNotifications,
                analysis.silencedPercent,
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(
                R.string.insights_action_breakdown,
                analysis.actions.cancelled,
                analysis.actions.snoozed,
                analysis.actions.loggedOnly,
                analysis.actions.kept,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (analysis.monitoredActions.cancelled + analysis.monitoredActions.snoozed > 0) {
            Text(
                stringResource(
                    R.string.insights_monitored_breakdown,
                    analysis.monitoredActions.cancelled,
                    analysis.monitoredActions.snoozed,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun EmptyAnalysisCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.insights_analysis_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.insights_analysis_empty_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AnalysisSection(
    titleRes: Int,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(titleRes),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            content()
        }
    }
}

@Composable
private fun TrendBars(
    points: List<TrendPointUi>,
    bucketLabel: String,
) {
    val maximum = points.maxOfOrNull(TrendPointUi::totalCount)?.coerceAtLeast(1) ?: 1
    val totalColor = MaterialTheme.colorScheme.secondaryContainer
    val silencedColor = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.insights_analysis_trend_description, points.size, bucketLabel)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .semantics { contentDescription = description },
    ) {
        val slot = size.width / points.size
        val width = slot * TREND_BAR_WIDTH_FRACTION
        points.forEachIndexed { index, point ->
            val totalHeight = size.height * point.totalCount / maximum.toFloat()
            val silencedHeight = size.height * point.silencedCount / maximum.toFloat()
            val left = index * slot + (slot - width) / 2
            val radius = CornerRadius(width / TREND_BAR_CORNER_DIVISOR)
            drawRoundRect(
                color = totalColor,
                topLeft = Offset(left, size.height - totalHeight),
                size = Size(width, totalHeight),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = silencedColor,
                topLeft = Offset(left, size.height - silencedHeight),
                size = Size(width, silencedHeight),
                cornerRadius = radius,
            )
        }
    }
    Text(
        stringResource(R.string.insights_analysis_trend_legend, bucketLabel),
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun AppBreakdown(apps: List<AppAnalysisUi>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        apps.take(DISPLAY_LIMIT).forEach { app ->
            val percent =
                if (app.totalCount <= 0) {
                    0
                } else {
                    ((app.silencedCount.toLong() * 100) / app.totalCount)
                        .coerceIn(0, 100)
                        .toInt()
                }
            Column {
                Text(app.appName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(
                        R.string.insights_app_analysis_count,
                        app.totalCount,
                        app.silencedCount,
                        percent,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun RuleBreakdown(rules: List<RuleAnalysisUi>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rules.take(DISPLAY_LIMIT).forEach { rule ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(rule.label.asString(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(
                        R.string.insights_rule_analysis_count,
                        rule.actualCount,
                        rule.monitoredCount,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
internal fun NamedBars(rows: List<Pair<String, Int>>) {
    val visible = rows.take(DISPLAY_LIMIT)
    val maximum = visible.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visible.forEach { (label, count) ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodySmall)
                    Text(count.toString(), style = MaterialTheme.typography.labelMedium)
                }
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    val radius = CornerRadius(size.height / 2)
                    drawRoundRect(trackColor, cornerRadius = radius)
                    drawRoundRect(
                        barColor,
                        size = size.copy(width = size.width * count / maximum.toFloat()),
                        cornerRadius = radius,
                    )
                }
            }
        }
    }
}

@Composable
internal fun HourDistribution(hours: List<HourAnalysisUi>) {
    val maximum = hours.maxOfOrNull(HourAnalysisUi::totalCount)?.coerceAtLeast(1) ?: 1
    val totalColor = MaterialTheme.colorScheme.secondaryContainer
    val silencedColor = MaterialTheme.colorScheme.tertiary
    val peak = hours.maxByOrNull(HourAnalysisUi::totalCount)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(100.dp)
            .semantics {
                contentDescription =
                    peak
                        ?.let {
                            "${it.hour}:00, ${it.totalCount}"
                        }.orEmpty()
            },
    ) {
        val slot = size.width / HOURS_PER_DAY
        hours.forEach { hour ->
            val width = slot * HOUR_BAR_WIDTH_FRACTION
            val left = hour.hour * slot + (slot - width) / 2
            val totalHeight = size.height * hour.totalCount / maximum.toFloat()
            val mutedHeight = size.height * hour.silencedCount / maximum.toFloat()
            drawRoundRect(
                totalColor,
                Offset(left, size.height - totalHeight),
                Size(width, totalHeight),
                CornerRadius(width / HOUR_BAR_CORNER_DIVISOR),
            )
            drawRoundRect(
                silencedColor,
                Offset(left, size.height - mutedHeight),
                Size(width, mutedHeight),
                CornerRadius(width / HOUR_BAR_CORNER_DIVISOR),
            )
        }
    }
    peak?.let {
        Text(
            stringResource(R.string.insights_peak_hour, it.hour, it.totalCount),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ChannelBreakdown(
    channels: List<ChannelShareUi>,
    onOpenNotificationSettings: (String, String?) -> Unit,
) {
    Column {
        channels.take(DISPLAY_LIMIT).forEach { channel ->
            TextButton(
                onClick = { onOpenNotificationSettings(channel.packageName, channel.channelId) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(channel.appName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        channel.channelName ?: channel.channelId,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(channel.count.toString(), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun LearningCoverageCard(analysis: InsightsAnalysisUi) {
    AnalysisSection(R.string.insights_analysis_learning) {
        Text(
            stringResource(
                R.string.insights_learning_counts,
                analysis.mlClassifiedCount,
                analysis.categoryCorrectionCount,
                analysis.semanticCorrectionCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        val coverage = analysis.coverageStartEpochDay
        val start = analysis.startEpochDay
        if (coverage != null && start != null && coverage > start) {
            Text(
                stringResource(R.string.insights_partial_coverage, formatAnalysisDay(coverage)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordsFilters(
    query: String,
    selectedAction: HistoryActionFilterUi,
    sources: List<HistorySourceUi>,
    selectedPackage: String?,
    selectedChannel: String?,
    onQueryChange: (String) -> Unit,
    onFilterChange: (HistoryActionFilterUi) -> Unit,
    onSourceSelected: (HistorySourceUi?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.insights_records_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HistoryActionFilterUi.entries.forEach { filter ->
                FilterChip(
                    selected = selectedAction == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(stringResource(filter.labelRes)) },
                )
            }
        }
        HistorySourceSelector(
            sources = sources,
            selectedPackage = selectedPackage,
            selectedChannel = selectedChannel,
            onSourceSelected = onSourceSelected,
        )
    }
}

@Composable
private fun HistorySourceSelector(
    sources: List<HistorySourceUi>,
    selectedPackage: String?,
    selectedChannel: String?,
    onSourceSelected: (HistorySourceUi?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedSource =
        sources.firstOrNull { source ->
            selectedPackage == source.packageName && selectedChannel == source.channelId
        }
    val selectedLabel = selectedSource?.displayLabel() ?: stringResource(R.string.insights_all_apps)

    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = selectedLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.insights_all_apps)) },
                onClick = {
                    expanded = false
                    onSourceSelected(null)
                },
            )
            sources.forEach { source ->
                DropdownMenuItem(
                    text = {
                        Text(
                            source.displayLabel(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSourceSelected(source)
                    },
                )
            }
        }
    }
}

private fun HistorySourceUi.displayLabel(): String = channelName?.let { "$appName · $it" } ?: appName

private val InsightsTab.labelRes: Int
    get() =
        when (this) {
            InsightsTab.OVERVIEW -> R.string.insights_tab_overview
            InsightsTab.ANALYSIS -> R.string.insights_tab_analysis
            InsightsTab.RECORDS -> R.string.insights_tab_records
        }

private val AnalysisRangePreset.labelRes: Int
    get() =
        when (this) {
            AnalysisRangePreset.LAST_7_DAYS -> R.string.insights_range_7_days
            AnalysisRangePreset.LAST_30_DAYS -> R.string.insights_range_30_days
            AnalysisRangePreset.LAST_90_DAYS -> R.string.insights_range_90_days
            AnalysisRangePreset.ALL -> R.string.insights_range_all
            AnalysisRangePreset.CUSTOM -> R.string.insights_range_custom
        }

private val HistoryActionFilterUi.labelRes: Int
    get() =
        when (this) {
            HistoryActionFilterUi.ALL -> R.string.insights_filter_all
            HistoryActionFilterUi.CANCELLED -> R.string.insights_cancelled
            HistoryActionFilterUi.SNOOZED -> R.string.insights_snoozed
            HistoryActionFilterUi.LOGGED -> R.string.insights_action_logged
            HistoryActionFilterUi.KEPT -> R.string.insights_action_kept
        }

private fun formatAnalysisDay(epochDay: Long): String =
    LocalDate
        .ofEpochDay(epochDay)
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))

internal const val INSIGHTS_ANALYSIS_TEST_TAG = "insights_analysis"
internal const val INSIGHTS_RECORDS_TEST_TAG = "insights_records"
private const val DISPLAY_LIMIT = 10
private const val HOURS_PER_DAY = 24
private const val TREND_BAR_WIDTH_FRACTION = 0.62f
private const val TREND_BAR_CORNER_DIVISOR = 4f
private const val HOUR_BAR_WIDTH_FRACTION = 0.7f
private const val HOUR_BAR_CORNER_DIVISOR = 3f
