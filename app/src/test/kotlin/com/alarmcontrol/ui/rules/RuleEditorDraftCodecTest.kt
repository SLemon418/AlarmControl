package com.alarmcontrol.ui.rules

import com.alarmcontrol.core.filtering.MAX_RULE_CONDITION_NODES
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.RuleExecutionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class RuleEditorDraftCodecTest {
    @Test
    fun `round trips the complete editable definition and regenerates node keys`() {
        val root =
            GroupNode(
                key = 10,
                anyOf = false,
                children =
                    listOf(
                        LeafNode(11, LeafKind.PACKAGE, "com.example.shop"),
                        NotNode(
                            12,
                            GroupNode(
                                key = 13,
                                anyOf = true,
                                children =
                                    listOf(
                                        TimeWindowNode(14, "22:00", "07:00"),
                                        RateNode(15, RateScope.CHANNEL, "15", "8"),
                                    ),
                            ),
                        ),
                    ),
            )
        val original =
            RuleEditorState(
                id = "rule-id",
                name = "Quiet offers",
                enabled = false,
                priority = "42",
                action = EditorAction.SNOOZE,
                executionMode = RuleExecutionMode.ACTIVE,
                snoozeMinutes = "90",
                root = root,
                hasUnsavedChanges = true,
                showDiscardConfirmation = true,
                editorMode = RuleEditorMode.ADVANCED,
                guidedPackageName = "com.example.shop",
                guidedAppName = "Shop",
                guidedChannelId = "offers",
                guidedChannelName = "Offers",
                guidedScope = GuidedRuleScope.CHANNEL,
                guidedTimeEnabled = true,
                guidedStartTime = "21:30",
                guidedEndTime = "06:45",
                guidedFrequencyEnabled = true,
                guidedFrequencyMinutes = "15",
                guidedFrequencyThreshold = "8",
                simulation = RuleSimulationState(title = "must not persist", text = "private"),
            )

        val encodedDraft = requireNotNull(RuleEditorDraftCodec.encode(original))
        val restored = requireNotNull(RuleEditorDraftCodec.decode(encodedDraft))

        assertEquals(original.copy(root = restored.root, simulation = RuleSimulationState()), restored)
        assertEquals(original.root.toConditionOrNull(), restored.root.toConditionOrNull())
        assertNotEquals(original.root.key, restored.root.key)
        val keys = restored.root.keys()
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `rejects malformed or unsupported state`() {
        assertNull(RuleEditorDraftCodec.decode("not-base64!"))

        val encoded = requireNotNull(RuleEditorDraftCodec.encode(RuleEditorState()))
        val bytes = Base64.getUrlDecoder().decode(encoded)
        bytes[7] = 2
        val unsupported = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        bytes.fill(0)

        assertNull(RuleEditorDraftCodec.decode(unsupported))
    }

    @Test
    fun `refuses a tree beyond the shared node budget`() {
        val oversized =
            RuleEditorState(
                root =
                    GroupNode(
                        key = 0,
                        anyOf = false,
                        children =
                            List(MAX_RULE_CONDITION_NODES) { index ->
                                LeafNode(index.toLong() + 1, LeafKind.CATEGORY, "message")
                            },
                    ),
            )

        assertNull(RuleEditorDraftCodec.encode(oversized))
    }

    private fun ConditionNode.keys(): List<Long> =
        buildList {
            add(key)
            when (this@keys) {
                is GroupNode -> children.forEach { addAll(it.keys()) }
                is NotNode -> addAll(child.keys())
                is LeafNode,
                is TimeWindowNode,
                is RateNode,
                -> Unit
            }
        }
}
