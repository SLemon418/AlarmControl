package com.alarmcontrol.ui.insights

import androidx.compose.ui.graphics.ImageBitmap
import com.alarmcontrol.R
import com.alarmcontrol.core.filtering.ConditionResult
import com.alarmcontrol.core.filtering.DecisionConditionKind
import com.alarmcontrol.core.filtering.DecisionTraceLane
import com.alarmcontrol.core.filtering.DecisionTraceNode
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.insights.InsightsSummary
import com.alarmcontrol.ui.app.AppIdentityResolver
import com.alarmcontrol.ui.app.AppIdentityUi
import com.alarmcontrol.ui.uiText
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test
    fun `app identity label icon and fallback state survive insights mappings`() {
        val icon = mockk<ImageBitmap>()
        val identity = AppIdentityUi(label = "Example", icon = icon, isPackageFallback = false)
        val resolver = AppIdentityResolver { identity }
        val event =
            NotificationEvent(
                packageName = "com.example",
                category = null,
                postedAtMillis = 1L,
                action = RuleAction.Keep,
                matchedRuleId = null,
                recordedAtMillis = 2L,
            )

        val row = event.toListItem(identity = identity)
        val summary =
            InsightsSummary(
                generatedAtMillis = 3L,
                mostMutedPackage = "com.example",
                mostMutedCount = 4,
                anomalyCount = 0,
            ).toUiModel(resolver)

        assertEquals("Example", row.appName)
        assertSame(icon, row.appIcon)
        assertFalse(row.appNameIsPackageFallback)
        assertEquals("Example", summary.mostMutedAppName)
        assertSame(icon, summary.mostMutedAppIcon)
        assertFalse(summary.mostMutedAppNameIsPackageFallback)
    }
}
