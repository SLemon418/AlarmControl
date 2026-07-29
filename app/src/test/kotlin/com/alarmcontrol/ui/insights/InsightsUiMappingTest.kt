package com.alarmcontrol.ui.insights

import com.alarmcontrol.R
import com.alarmcontrol.core.filtering.ConditionResult
import com.alarmcontrol.core.filtering.DecisionConditionKind
import com.alarmcontrol.core.filtering.DecisionTraceLane
import com.alarmcontrol.core.filtering.DecisionTraceNode
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.ui.uiText
import org.junit.Assert.assertEquals
import org.junit.Test

class InsightsUiMappingTest {
    @Test
    fun `boolean trace kinds use neutral condition labels`() {
        val kinds =
            listOf(
                DecisionConditionKind.ONGOING,
                DecisionConditionKind.ADVERTISEMENT,
                DecisionConditionKind.CONVERSATION,
                DecisionConditionKind.FOREGROUND_SERVICE,
            )
        val event =
            NotificationEvent(
                packageName = "com.example",
                category = null,
                postedAtMillis = 1L,
                action = RuleAction.Keep,
                matchedRuleId = null,
                recordedAtMillis = 2L,
                decisionTrace =
                    kinds.mapIndexed { position, kind ->
                        DecisionTraceNode(
                            lane = DecisionTraceLane.ACTIVE,
                            position = position,
                            depth = 0,
                            kind = kind,
                            result = ConditionResult.MATCH,
                        )
                    },
            )

        assertEquals(
            listOf(
                uiText(R.string.condition_trace_ongoing),
                uiText(R.string.condition_trace_advertisement),
                uiText(R.string.condition_trace_conversation),
                uiText(R.string.condition_trace_foreground_service),
            ),
            event.toListItem().decisionTrace.map { it.conditionLabel },
        )
    }
}
