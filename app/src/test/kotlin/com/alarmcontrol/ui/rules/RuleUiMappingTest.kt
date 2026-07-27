package com.alarmcontrol.ui.rules

import com.alarmcontrol.R
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.MAX_RULE_NAME_CHARS
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.ui.uiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleUiMappingTest {
    private fun rule(condition: Condition) =
        Rule(id = "5", name = "r", condition = condition, action = RuleAction.Cancel)

    @Test
    fun `summary renders compound AND-OR structure with parentheses`() {
        val r =
            rule(
                Condition.AllOf(
                    listOf(
                        Condition.PackageEquals("com.x"),
                        Condition.AnyOf(listOf(Condition.TextContains("a"), Condition.TextContains("b"))),
                    ),
                ),
            )

        assertEquals(
            uiText(
                R.string.rule_summary_and,
                uiText(R.string.rule_summary_package, "com.x"),
                uiText(
                    R.string.rule_summary_parenthesized,
                    uiText(
                        R.string.rule_summary_or,
                        uiText(R.string.rule_summary_text, "a"),
                        uiText(R.string.rule_summary_text, "b"),
                    ),
                ),
            ),
            r.toListItem().summary,
        )
    }

    @Test
    fun `summary renders a time window`() {
        assertEquals(
            uiText(R.string.rule_summary_time, "22:00", "07:00"),
            rule(Condition.TimeWindow(22 * 60, 7 * 60)).toListItem().summary,
        )
    }

    @Test
    fun `a compound rule round-trips through the editor without flattening`() {
        val original =
            rule(
                Condition.AllOf(
                    listOf(
                        Condition.PackageEquals("com.x"),
                        Condition.AnyOf(
                            listOf(Condition.TextContains("a"), Condition.TimeWindow(1320, 420)),
                        ),
                    ),
                ),
            )

        assertEquals(original, original.toEditorState().toRuleOrNull())
    }

    @Test
    fun `editing a disabled priority keep rule preserves every non-condition field`() {
        val original =
            Rule(
                id = "9",
                name = "allow bank",
                enabled = false,
                priority = 42,
                condition = Condition.AllOf(listOf(Condition.PackageEquals("com.bank"))),
                action = RuleAction.Keep,
                executionMode = RuleExecutionMode.MONITOR,
            )

        assertEquals(original, original.toEditorState().toRuleOrNull())
    }

    @Test
    fun `invalid or overflowing snooze minutes cannot build a rule`() {
        val validRoot = Condition.PackageEquals("com.x").toEditableRoot()

        assertNull(
            RuleEditorState(action = EditorAction.SNOOZE, snoozeMinutes = "0", root = validRoot).toRuleOrNull(),
        )
        assertNull(
            RuleEditorState(action = EditorAction.SNOOZE, snoozeMinutes = Long.MAX_VALUE.toString(), root = validRoot)
                .toRuleOrNull(),
        )
    }

    @Test
    fun `an empty editor builds no rule`() {
        assertNull(RuleEditorState().toRuleOrNull())
    }

    @Test
    fun `an overlong rule name cannot build a rule`() {
        val root = Condition.PackageEquals("com.example").toEditableRoot()

        assertNull(RuleEditorState(name = "x".repeat(MAX_RULE_NAME_CHARS + 1), root = root).toRuleOrNull())
    }

    @Test
    fun `guided invalid optional time cannot silently widen to package only`() {
        val root = Condition.PackageEquals("com.example").toEditableRoot()
        val editor =
            RuleEditorState(
                name = "Night",
                root = root,
                guidedPackageName = "com.example",
                guidedTimeEnabled = true,
                guidedStartTime = "25:00",
                guidedEndTime = "07:00",
            )

        assertEquals(false, editor.canSave)
        assertNull(editor.toRuleOrNull())
    }

    @Test
    fun `guided invalid frequency cannot silently widen to package only`() {
        val root = Condition.PackageEquals("com.example").toEditableRoot()
        val editor =
            RuleEditorState(
                name = "Burst",
                root = root,
                guidedPackageName = "com.example",
                guidedFrequencyEnabled = true,
                guidedFrequencyMinutes = "0",
                guidedFrequencyThreshold = "1",
            )

        assertEquals(false, editor.canSave)
        assertNull(editor.toRuleOrNull())
    }

    @Test
    fun `guided channel scope requires a concrete channel`() {
        val root = Condition.PackageEquals("com.example").toEditableRoot()
        val editor =
            RuleEditorState(
                name = "Channel",
                root = root,
                guidedPackageName = "com.example",
                guidedScope = GuidedRuleScope.CHANNEL,
                guidedChannelId = null,
            )

        assertEquals(false, editor.canSave)
        assertNull(editor.toRuleOrNull())
    }
}
