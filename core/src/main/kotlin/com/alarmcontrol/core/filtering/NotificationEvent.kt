package com.alarmcontrol.core.filtering

/**
 * A record of one engine decision, kept locally for insights and optional statistics exclusion
 * (CLAUDE.md §6).
 *
 * Privacy (HARD RULE §3/§6): this carries **no notification content** — no title or body, only the
 * metadata those features need. Do not add content fields here.
 *
 * @property action the action that was applied ([RuleAction.Keep] when no rule matched).
 * @property matchedRuleId the rule that produced the decision, or `null` when none matched.
 * @property id persisted row id; blank for a not-yet-recorded event (the engine never sets it).
 * @property undone legacy storage name for whether the user excluded this entry from statistics.
 *   The notification itself cannot be restored or re-posted.
 */
data class NotificationEvent(
    val packageName: String,
    val channelId: String? = null,
    val channelName: String? = null,
    /** On-device classifier label at decision time; no notification content is retained. */
    val mlCategory: String? = null,
    val mlConfidence: Float? = null,
    val category: String?,
    val postedAtMillis: Long,
    val postedEpochDay: Long? = null,
    val postedMinuteOfDay: Int? = null,
    val importance: NotificationImportance? = null,
    val isConversation: Boolean? = null,
    val isForegroundService: Boolean? = null,
    val action: RuleAction,
    val matchedRuleId: String?,
    val recordedAtMillis: Long,
    val id: String = "",
    val undone: Boolean = false,
    /** First matching monitor rule; it never changes [action]. */
    val monitoredRuleId: String? = null,
    val monitoredAction: RuleAction? = null,
    val decisionTrace: List<DecisionTraceNode> = emptyList(),
    /** True when an encrypted child payload was created; the content itself never lives here. */
    val hadEncryptedContent: Boolean = false,
)
