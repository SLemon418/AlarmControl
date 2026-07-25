package com.alarmcontrol.core.filtering

/**
 * The kind of a [RuleAction] without its payload (e.g. snooze duration). Used to key insight
 * aggregations so callers no longer pass a dummy action like `Snooze(0)` (CLAUDE.md §5).
 */
enum class ActionKind { CANCEL, SNOOZE, MARK_READ, KEEP }

/** The [ActionKind] of this action. */
val RuleAction.kind: ActionKind
    get() =
        when (this) {
            RuleAction.Cancel -> ActionKind.CANCEL
            is RuleAction.Snooze -> ActionKind.SNOOZE
            RuleAction.MarkRead -> ActionKind.MARK_READ
            RuleAction.Keep -> ActionKind.KEEP
        }
