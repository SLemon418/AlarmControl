package com.alarmcontrol.ui.insights

import com.alarmcontrol.R
import com.alarmcontrol.core.filtering.ConditionResult
import com.alarmcontrol.core.filtering.DecisionConditionKind
import com.alarmcontrol.core.filtering.DecisionTraceNode
import com.alarmcontrol.core.filtering.NotificationContentState
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.NotificationEventDetail
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.insights.DailyInsight
import com.alarmcontrol.core.insights.InsightsAnalytics
import com.alarmcontrol.core.insights.InsightsBucket
import com.alarmcontrol.core.insights.InsightsSummary
import com.alarmcontrol.ui.UiText
import com.alarmcontrol.ui.app.AppIdentityResolver
import com.alarmcontrol.ui.app.AppIdentityUi
import com.alarmcontrol.ui.uiText
import java.util.Locale

/** Domain decision record -> activity-log row. Past-tense labels; snooze duration isn't logged. */
internal fun NotificationEvent.toListItem(
    correctedCategory: String? = null,
    identity: AppIdentityUi = AppIdentityUi(packageName, null),
    ruleNames: Map<String, String> = emptyMap(),
): EventListItem =
    EventListItem(
        id = id,
        packageName = packageName,
        appName = identity.label,
        appIcon = identity.icon,
        predictedCategory = mlCategory,
        category = correctedCategory ?: mlCategory ?: category,
        actionLabel = action.eventLabel(),
        action = action.toEventAction(),
        recordedAtMillis = recordedAtMillis,
        postedAtMillis = postedAtMillis,
        postedEpochDay = postedEpochDay,
        undone = undone,
        // A kept notification doesn't affect mute statistics, and an excluded row needs no repeat action.
        canUndo = !undone && action != RuleAction.Keep,
        correctedCategory = correctedCategory,
        channelId = channelId,
        channelName = channelName,
        mlConfidencePercent = mlConfidence?.times(100)?.toInt()?.coerceIn(0, 100),
        matchedRuleName = matchedRuleId?.resolvedRuleName(ruleNames),
        monitoredActionLabel = monitoredAction?.eventLabel(),
        monitoredRuleName = monitoredRuleId?.resolvedRuleName(ruleNames),
        decisionTrace = decisionTrace.map(DecisionTraceNode::toUiModel),
        hadEncryptedContent = hadEncryptedContent,
    )

/** Domain insights headline -> stable UI model (no domain type crosses into Compose). */
internal fun InsightsSummary.toUiModel(appIdentityResolver: AppIdentityResolver): InsightsSummaryUi =
    InsightsSummaryUi(
        mostMutedPackage = mostMutedPackage,
        mostMutedAppName = mostMutedPackage?.let { appIdentityResolver.resolve(it).label },
        mostMutedCount = mostMutedCount,
        anomalyCount = anomalyCount,
        generatedAtMillis = generatedAtMillis,
    )

private fun RuleAction.eventLabel(): UiText =
    when (this) {
        RuleAction.Cancel -> uiText(R.string.insights_action_cancelled)
        is RuleAction.Snooze -> uiText(R.string.insights_action_snoozed)
        RuleAction.MarkRead -> uiText(R.string.insights_action_logged)
        RuleAction.Keep -> uiText(R.string.insights_action_kept)
    }

private fun RuleAction.toEventAction(): EventActionUi =
    when (this) {
        RuleAction.Cancel -> EventActionUi.CANCELLED
        is RuleAction.Snooze -> EventActionUi.SNOOZED
        RuleAction.MarkRead -> EventActionUi.LOGGED
        RuleAction.Keep -> EventActionUi.KEPT
    }

/**
 * Domain per-day rollup -> stable UI model, with rule/category labels pre-formatted for display.
 * [ruleNames] maps rule id -> current display name; ids absent from it (deleted rules) fall back to a
 * generic label so the card never shows a raw id.
 */
internal fun DailyInsight.toUiModel(
    ruleNames: Map<String, String>,
    appIdentityResolver: AppIdentityResolver? = null,
): DailyInsightUi =
    DailyInsightUi(
        epochDay = epochDay,
        totalNotifications = totalNotifications,
        mutedCount = mutedCount,
        topRules =
            topRules.map { RuleTriggerUi(it.ruleId.resolvedRuleName(ruleNames), it.count) },
        topMonitoredRules =
            topMonitoredRules.map { RuleTriggerUi(it.ruleId.resolvedRuleName(ruleNames), it.count) },
        categories =
            categoryBreakdown.map {
                CategoryShareUi(
                    label = it.category.categoryLabel(),
                    count = it.count,
                )
            },
        actions =
            ActionBreakdownUi(
                cancelled = actionBreakdown.cancelled,
                snoozed = actionBreakdown.snoozed,
                loggedOnly = actionBreakdown.loggedOnly,
                kept = actionBreakdown.kept,
            ),
        monitoredActions =
            ActionBreakdownUi(
                cancelled = monitoredActionBreakdown.cancelled,
                snoozed = monitoredActionBreakdown.snoozed,
                loggedOnly = monitoredActionBreakdown.loggedOnly,
                kept = monitoredActionBreakdown.kept,
            ),
        breakdownComplete =
            ruleBreakdownComplete &&
                monitorRuleBreakdownComplete &&
                appBreakdownComplete &&
                channelBreakdownComplete,
        channels =
            channelBreakdown.map {
                ChannelShareUi(
                    packageName = it.packageName,
                    appName = appIdentityResolver?.resolve(it.packageName)?.label ?: it.packageName,
                    channelId = it.channelId,
                    count = it.count,
                    channelName = it.channelName,
                )
            },
        apps =
            appBreakdown.map {
                AppAnalysisUi(
                    packageName = it.packageName,
                    appName = appIdentityResolver?.resolve(it.packageName)?.label ?: it.packageName,
                    totalCount = it.totalCount,
                    silencedCount = it.silencedCount,
                )
            },
        hours = hourBreakdown.map { HourAnalysisUi(it.hour, it.totalCount, it.silencedCount) },
        semanticIntents =
            semanticBreakdown.map {
                SemanticAnalysisUi(it.intent.toLabelUiText(), it.count)
            },
        mlClassifiedCount = mlClassifiedCount,
        categoryCorrectionCount = categoryCorrectionCount,
        semanticCorrectionCount = semanticCorrectionCount,
        sourceComplete = sourceComplete,
    )

internal fun InsightsAnalytics.toUiModel(
    ruleNames: Map<String, String>,
    appIdentityResolver: AppIdentityResolver,
): InsightsAnalysisUi =
    InsightsAnalysisUi(
        startEpochDay = range.startEpochDay,
        endEpochDay = range.endEpochDay,
        totalNotifications = totalNotifications,
        silencedCount = silencedCount,
        silencedPercent = silencedPercent,
        actions = actionBreakdown.toUiModel(),
        monitoredActions = monitoredActionBreakdown.toUiModel(),
        apps =
            apps.map {
                AppAnalysisUi(
                    packageName = it.packageName,
                    appName = appIdentityResolver.resolve(it.packageName).label,
                    totalCount = it.totalCount,
                    silencedCount = it.silencedCount,
                )
            },
        rules =
            rules.map {
                RuleAnalysisUi(
                    label = it.ruleId.resolvedRuleName(ruleNames),
                    actualCount = it.actualCount,
                    monitoredCount = it.monitoredCount,
                )
            },
        categories = categories.map { CategoryShareUi(it.category.categoryLabel(), it.count) },
        channels =
            channels.map {
                ChannelShareUi(
                    packageName = it.packageName,
                    appName = appIdentityResolver.resolve(it.packageName).label,
                    channelId = it.channelId,
                    count = it.count,
                    channelName = it.channelName,
                )
            },
        hours = hours.map { HourAnalysisUi(it.hour, it.totalCount, it.silencedCount) },
        semanticIntents = semanticIntents.map { SemanticAnalysisUi(it.intent.toLabelUiText(), it.count) },
        trend =
            trend.map {
                TrendPointUi(it.startEpochDay, it.endEpochDay, it.totalCount, it.silencedCount)
            },
        bucketLabel =
            uiText(
                when (bucket) {
                    InsightsBucket.DAY -> R.string.insights_bucket_day
                    InsightsBucket.WEEK -> R.string.insights_bucket_week
                    InsightsBucket.MONTH -> R.string.insights_bucket_month
                },
            ),
        mlClassifiedCount = mlClassifiedCount,
        categoryCorrectionCount = categoryCorrectionCount,
        semanticCorrectionCount = semanticCorrectionCount,
        sourceComplete = sourceComplete,
        coverageStartEpochDay = breakdownCoverageStartEpochDay,
    )

internal fun NotificationEventDetail.toUiModel(identity: AppIdentityUi): NotificationDetailUi {
    val available = content as? NotificationContentState.Available
    return NotificationDetailUi(
        eventId = event.id,
        appName = identity.label,
        packageName = event.packageName,
        title = available?.title,
        text = available?.text,
        contentState =
            when (content) {
                is NotificationContentState.Available -> NotificationDetailContentUi.AVAILABLE
                NotificationContentState.NotStored -> NotificationDetailContentUi.NOT_STORED
                NotificationContentState.Expired -> NotificationDetailContentUi.EXPIRED
                NotificationContentState.Unreadable -> NotificationDetailContentUi.UNREADABLE
            },
    )
}

private fun com.alarmcontrol.core.insights.ActionBreakdown.toUiModel(): ActionBreakdownUi =
    ActionBreakdownUi(cancelled, snoozed, loggedOnly, kept)

internal fun com.alarmcontrol.core.filtering.SemanticIntent.toLabelUiText(): UiText =
    uiText(
        when (this) {
            com.alarmcontrol.core.filtering.SemanticIntent.MARKETING -> R.string.semantic_marketing
            com.alarmcontrol.core.filtering.SemanticIntent.TRANSACTIONAL -> R.string.semantic_transactional
            com.alarmcontrol.core.filtering.SemanticIntent.SECURITY -> R.string.semantic_security
            com.alarmcontrol.core.filtering.SemanticIntent.DELIVERY -> R.string.semantic_delivery
            com.alarmcontrol.core.filtering.SemanticIntent.SOCIAL -> R.string.semantic_social
            com.alarmcontrol.core.filtering.SemanticIntent.OTHER -> R.string.semantic_other
            com.alarmcontrol.core.filtering.SemanticIntent.AMBIGUOUS -> R.string.semantic_ambiguous
        },
    )

private fun String.resolvedRuleName(ruleNames: Map<String, String>): UiText {
    val name = ruleNames[this]
    return when {
        name == null -> uiText(R.string.insights_deleted_rule)
        name.isBlank() -> uiText(R.string.rule_untitled)
        else -> UiText.Dynamic(name)
    }
}

private fun DecisionTraceNode.toUiModel(): DecisionTraceUi =
    DecisionTraceUi(
        lane = lane,
        depth = depth,
        conditionLabel = kind.toLabelUiText(),
        resultLabel =
            uiText(
                when (result) {
                    ConditionResult.MATCH -> R.string.simulator_trace_match
                    ConditionResult.NO_MATCH -> R.string.simulator_trace_no_match
                    ConditionResult.UNKNOWN -> R.string.simulator_trace_unknown
                },
            ),
    )

private fun DecisionConditionKind.toLabelUiText(): UiText =
    uiText(
        when (this) {
            DecisionConditionKind.PACKAGE -> R.string.condition_package
            DecisionConditionKind.TITLE -> R.string.condition_title
            DecisionConditionKind.TEXT -> R.string.condition_text
            DecisionConditionKind.CATEGORY -> R.string.condition_category
            DecisionConditionKind.CHANNEL -> R.string.condition_channel
            DecisionConditionKind.ONGOING -> R.string.condition_ongoing
            DecisionConditionKind.ML_CATEGORY -> R.string.condition_ml_category
            DecisionConditionKind.ADVERTISEMENT -> R.string.condition_advertisement
            DecisionConditionKind.SEMANTIC_INTENT -> R.string.condition_semantic_intent
            DecisionConditionKind.TIME_WINDOW -> R.string.condition_time_window
            DecisionConditionKind.RATE -> R.string.condition_rate
            DecisionConditionKind.CONVERSATION -> R.string.condition_conversation
            DecisionConditionKind.FOREGROUND_SERVICE -> R.string.condition_foreground_service
            DecisionConditionKind.IMPORTANCE -> R.string.condition_importance
            DecisionConditionKind.ALL_OF -> R.string.match_all
            DecisionConditionKind.ANY_OF -> R.string.match_any
            DecisionConditionKind.NOT -> R.string.not_operator
            DecisionConditionKind.TRUNCATED -> R.string.condition_trace_truncated
        },
    )

private fun String?.categoryLabel(): UiText =
    when (this?.lowercase(Locale.ROOT)) {
        "promotion" -> uiText(R.string.category_promotion)
        "social" -> uiText(R.string.category_social)
        "news" -> uiText(R.string.category_news)
        "alarm" -> uiText(R.string.category_alarm)
        null -> uiText(R.string.insights_uncategorized)
        else -> UiText.Dynamic(replaceFirstChar { it.uppercase() })
    }
