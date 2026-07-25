package com.alarmcontrol.core.filtering

const val MAX_PERSISTED_TRACE_NODES = 128

/** Which independently evaluated rule lane produced a diagnostic node. */
enum class DecisionTraceLane {
    ACTIVE,
    MONITOR,
}

/** Condition kind only; comparison values and notification content are intentionally omitted. */
enum class DecisionConditionKind {
    PACKAGE,
    TITLE,
    TEXT,
    CATEGORY,
    CHANNEL,
    ONGOING,
    ML_CATEGORY,
    ADVERTISEMENT,
    SEMANTIC_INTENT,
    TIME_WINDOW,
    RATE,
    CONVERSATION,
    FOREGROUND_SERVICE,
    IMPORTANCE,
    ALL_OF,
    ANY_OF,
    NOT,
    TRUNCATED,
}

/** One content-free node from the condition tree evaluated for a real notification. */
data class DecisionTraceNode(
    val lane: DecisionTraceLane,
    val position: Int,
    val depth: Int,
    val kind: DecisionConditionKind,
    val result: ConditionResult,
)
