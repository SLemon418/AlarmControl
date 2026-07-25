package com.alarmcontrol.data.db.model

/*
 * Persistence-level enums for the rules schema. These mirror the pure domain types in
 * `:notifications` but are deliberately defined here so `:data` stays a sibling layer and does not
 * depend on `:notifications` (CLAUDE.md §4). The entity↔domain mapping lives with whoever wires the
 * two together (a repository), not in the schema.
 */

/** Storage form of the action a rule takes when it matches. */
enum class StoredRuleAction {
    CANCEL,
    SNOOZE,
    MARK_READ,
    KEEP,
}

enum class StoredRuleExecutionMode {
    ACTIVE,
    MONITOR,
}

/**
 * Storage form of a condition node's kind. Leaves carry a `value` (interpreted per type; a time
 * window is stored as `"startMinute,endMinute"`); composites ([ALL_OF]/[ANY_OF]/[NOT]) have child
 * rows instead and ignore `value`.
 */
enum class StoredConditionType {
    PACKAGE,
    TITLE_CONTAINS,
    TEXT_CONTAINS,
    CATEGORY,
    CHANNEL,
    ONGOING,
    ML_CATEGORY,
    IS_ADVERTISEMENT,
    SEMANTIC_INTENT,
    RATE_AT_LEAST,
    CONVERSATION,
    FOREGROUND_SERVICE,
    IMPORTANCE_AT_LEAST,
    TIME_WINDOW,
    ALL_OF,
    ANY_OF,
    NOT,
}
