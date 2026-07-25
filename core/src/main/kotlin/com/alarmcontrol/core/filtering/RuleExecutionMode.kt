package com.alarmcontrol.core.filtering

/** Whether a rule applies its action or only records what it would have done. */
enum class RuleExecutionMode {
    ACTIVE,
    MONITOR,
}
