package com.alarmcontrol.ui.rules

import androidx.annotation.StringRes
import com.alarmcontrol.R
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.NotificationImportance
import com.alarmcontrol.core.filtering.RuleExecutionMode

/** Safe, unsaved starting points. The user must review and explicitly save every template. */
enum class RuleTemplate(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
) {
    ONE_APP_AT_NIGHT(R.string.template_night_app, R.string.template_night_app_summary),
    PROMOTIONS(R.string.template_promotions, R.string.template_promotions_summary),
    OBSERVE_ADS(R.string.template_observe_ads, R.string.template_observe_ads_summary),
    KEEP_ALARMS(R.string.template_keep_alarms, R.string.template_keep_alarms_summary),
    KEEP_CALLS_AND_CONVERSATIONS(
        R.string.template_keep_conversations,
        R.string.template_keep_conversations_summary,
    ),
    KEEP_FOREGROUND_SERVICES(
        R.string.template_keep_foreground,
        R.string.template_keep_foreground_summary,
    ),
    KEEP_HIGH_IMPORTANCE(
        R.string.template_keep_high_importance,
        R.string.template_keep_high_importance_summary,
    ),
}

internal fun RuleTemplate.toEditorState(protectionPriority: Int = 100): RuleEditorState =
    when (this) {
        RuleTemplate.ONE_APP_AT_NIGHT ->
            RuleEditorState(
                editorMode = RuleEditorMode.ADVANCED,
                action = EditorAction.SNOOZE,
                snoozeMinutes = "60",
                root =
                    Condition
                        .AllOf(
                            listOf(
                                Condition.PackageEquals(""),
                                Condition.TimeWindow(startMinuteOfDay = 22 * 60, endMinuteOfDay = 7 * 60),
                            ),
                        ).toEditableRoot(),
            )
        RuleTemplate.PROMOTIONS ->
            RuleEditorState(
                editorMode = RuleEditorMode.ADVANCED,
                action = EditorAction.CANCEL,
                root = Condition.MlCategoryEquals("promotion").toEditableRoot(),
            )
        RuleTemplate.OBSERVE_ADS ->
            RuleEditorState(
                editorMode = RuleEditorMode.ADVANCED,
                action = EditorAction.CANCEL,
                executionMode = RuleExecutionMode.MONITOR,
                root = Condition.IsAdvertisement(true).toEditableRoot(),
            )
        RuleTemplate.KEEP_ALARMS ->
            RuleEditorState(
                editorMode = RuleEditorMode.ADVANCED,
                executionMode = RuleExecutionMode.ACTIVE,
                priority = protectionPriority.toString(),
                action = EditorAction.KEEP,
                root = Condition.CategoryEquals("alarm").toEditableRoot(),
            )
        RuleTemplate.KEEP_CALLS_AND_CONVERSATIONS ->
            RuleEditorState(
                editorMode = RuleEditorMode.ADVANCED,
                executionMode = RuleExecutionMode.ACTIVE,
                priority = protectionPriority.toString(),
                action = EditorAction.KEEP,
                root =
                    Condition
                        .AnyOf(
                            listOf(
                                Condition.CategoryEquals("call"),
                                Condition.Conversation(true),
                            ),
                        ).toEditableRoot(),
            )
        RuleTemplate.KEEP_FOREGROUND_SERVICES ->
            RuleEditorState(
                editorMode = RuleEditorMode.ADVANCED,
                executionMode = RuleExecutionMode.ACTIVE,
                priority = protectionPriority.toString(),
                action = EditorAction.KEEP,
                root = Condition.ForegroundService(true).toEditableRoot(),
            )
        RuleTemplate.KEEP_HIGH_IMPORTANCE ->
            RuleEditorState(
                editorMode = RuleEditorMode.ADVANCED,
                executionMode = RuleExecutionMode.ACTIVE,
                priority = protectionPriority.toString(),
                action = EditorAction.KEEP,
                root = Condition.ImportanceAtLeast(NotificationImportance.HIGH).toEditableRoot(),
            )
    }
