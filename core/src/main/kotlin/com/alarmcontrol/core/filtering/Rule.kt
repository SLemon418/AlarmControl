package com.alarmcontrol.core.filtering

/**
 * What the engine should do with a notification that matches a [Rule]. Limited to the actions a
 * `NotificationListenerService` can actually perform (CLAUDE.md §0/§6) — we never block or
 * pre-empt another app's notification.
 */
sealed interface RuleAction {
    /** Cancel/dismiss the notification (`cancelNotification`). */
    data object Cancel : RuleAction

    /** Snooze the notification for [durationMillis] (`snoozeNotification`). */
    data class Snooze(
        val durationMillis: Long,
    ) : RuleAction

    /** Legacy record-only action. It has no listener-side platform effect and does not mark another app as read. */
    data object MarkRead : RuleAction

    /** Explicitly leave the notification untouched (an allow-list outcome). */
    data object Keep : RuleAction
}

/** Maximum rule-name length accepted by editors, persistence, and portable backups. */
const val MAX_RULE_NAME_CHARS = 200
const val MIN_SNOOZE_DURATION_MILLIS = 60_000L
const val MAX_SNOOZE_DURATION_MILLIS = 7L * 24 * 60 * 60 * 1_000

/**
 * A user-authored filtering rule: a single [condition] (which may be a composite) plus the
 * [action] to take when it matches. Rules are evaluated by the notification matching engine.
 *
 * @property priority higher wins when several enabled rules match the same notification; ties keep
 *   declaration order.
 */
data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val condition: Condition,
    val action: RuleAction,
    val executionMode: RuleExecutionMode = RuleExecutionMode.ACTIVE,
)
