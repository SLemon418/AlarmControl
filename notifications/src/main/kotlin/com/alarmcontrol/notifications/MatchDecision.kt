package com.alarmcontrol.notifications

import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction

/**
 * The engine's verdict for a single notification. Either a [Rule] matched and dictates an
 * [action], or nothing matched ([NoMatch]) and the caller should leave the notification alone.
 */
sealed interface MatchDecision {
    data class Matched(
        val rule: Rule,
        val action: RuleAction,
    ) : MatchDecision

    data object NoMatch : MatchDecision
}
