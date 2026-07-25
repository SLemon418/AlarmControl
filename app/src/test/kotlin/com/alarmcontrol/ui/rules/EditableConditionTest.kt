package com.alarmcontrol.ui.rules

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.MAX_CONDITION_VALUE_CHARS
import com.alarmcontrol.core.filtering.NotificationImportance
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.SemanticIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditableConditionTest {
    @Test
    fun `toEditableRoot preserves a deeply nested structure`() {
        val condition =
            Condition.AllOf(
                listOf(
                    Condition.PackageEquals("com.x"),
                    Condition.AnyOf(
                        listOf(Condition.CategoryEquals("alarm"), Condition.TimeWindow(1320, 420)),
                    ),
                ),
            )

        val root = condition.toEditableRoot()

        assertFalse(root.anyOf) // outer AllOf
        assertEquals(2, root.children.size)
        val pkg = root.children[0] as LeafNode
        assertEquals(LeafKind.PACKAGE, pkg.kind)
        assertEquals("com.x", pkg.value)
        val anyGroup = root.children[1] as GroupNode
        assertTrue(anyGroup.anyOf) // inner AnyOf
        assertEquals(LeafKind.CATEGORY, (anyGroup.children[0] as LeafNode).kind)
        val window = anyGroup.children[1] as TimeWindowNode
        assertEquals("22:00", window.start)
        assertEquals("07:00", window.end)
    }

    @Test
    fun `a non-group root is wrapped in an AND group without losing it`() {
        val root = Condition.PackageEquals("com.x").toEditableRoot()

        assertFalse(root.anyOf)
        assertEquals(1, root.children.size)
        assertEquals("com.x", (root.children.single() as LeafNode).value)
    }

    @Test
    fun `toConditionOrNull builds the domain tree`() {
        val root =
            GroupNode(
                key = 0,
                anyOf = false,
                children =
                    listOf(
                        LeafNode(1, LeafKind.PACKAGE, "com.x"),
                        TimeWindowNode(2, "22:00", "07:00"),
                    ),
            )

        assertEquals(
            Condition.AllOf(listOf(Condition.PackageEquals("com.x"), Condition.TimeWindow(1320, 420))),
            root.toConditionOrNull(),
        )
    }

    @Test
    fun `one invalid child rejects the whole group instead of broadening it`() {
        val root =
            GroupNode(
                key = 0,
                anyOf = false,
                children =
                    listOf(
                        LeafNode(1, LeafKind.PACKAGE, "com.x"),
                        LeafNode(2, LeafKind.TEXT, "   "),
                        TimeWindowNode(3, "nope", "07:00"),
                    ),
            )

        assertNull(root.toConditionOrNull())
    }

    @Test
    fun `an empty or all-blank group yields null`() {
        assertNull(GroupNode(0, anyOf = false, children = emptyList()).toConditionOrNull())
        assertNull(
            GroupNode(0, anyOf = false, children = listOf(LeafNode(1, LeafKind.PACKAGE, ""))).toConditionOrNull(),
        )
    }

    @Test
    fun `NOT and OR and ongoing map to the right domain types`() {
        val root =
            GroupNode(
                key = 0,
                anyOf = true,
                children =
                    listOf(
                        NotNode(1, LeafNode(2, LeafKind.CATEGORY, "alarm")),
                        LeafNode(3, LeafKind.ONGOING, "true"),
                    ),
            )

        assertEquals(
            Condition.AnyOf(
                listOf(
                    Condition.Not(Condition.CategoryEquals("alarm")),
                    Condition.Ongoing(true),
                ),
            ),
            root.toConditionOrNull(),
        )
    }

    @Test
    fun `title ignoreCase is preserved across the round trip`() {
        val condition = Condition.AllOf(listOf(Condition.TitleContains("Sale", ignoreCase = false)))
        assertEquals(condition, condition.toEditableRoot().toConditionOrNull())
    }

    @Test
    fun `isAdvertisement condition round-trips through the editable tree`() {
        val condition = Condition.AllOf(listOf(Condition.IsAdvertisement(true), Condition.IsAdvertisement(false)))
        assertEquals(condition, condition.toEditableRoot().toConditionOrNull())
    }

    @Test
    fun `frequency and ranking conditions round-trip without flattening`() {
        val condition =
            Condition.AllOf(
                listOf(
                    Condition.RateAtLeast(RateScope.CHANNEL, 15 * 60_000L, 10),
                    Condition.SemanticIntentEquals(SemanticIntent.SECURITY),
                    Condition.Conversation(true),
                    Condition.ForegroundService(false),
                    Condition.ImportanceAtLeast(NotificationImportance.HIGH),
                ),
            )

        assertEquals(condition, condition.toEditableRoot().toConditionOrNull())
    }

    @Test
    fun `frequency editor rejects unsupported windows and thresholds`() {
        assertNull(RateNode(1, RateScope.PACKAGE, "0", "2").toConditionOrNull())
        assertNull(RateNode(2, RateScope.PACKAGE, "1441", "2").toConditionOrNull())
        assertNull(RateNode(3, RateScope.PACKAGE, "5", "1").toConditionOrNull())
        assertNull(RateNode(4, RateScope.PACKAGE, "5", "1001").toConditionOrNull())
        assertEquals(ConditionValidation.RATE_WINDOW, RateNode(5, RateScope.PACKAGE, "0", "2").validationError())
        assertEquals(
            ConditionValidation.RATE_THRESHOLD,
            RateNode(6, RateScope.PACKAGE, "5", "1001").validationError(),
        )
    }

    @Test
    fun `invalid boolean leaf is rejected instead of defaulting to true`() {
        assertNull(LeafNode(1, LeafKind.ONGOING, "yes").toConditionOrNull())
        assertNull(LeafNode(2, LeafKind.IS_ADVERTISEMENT, "unknown").toConditionOrNull())
    }

    @Test
    fun `overlong leaf value is rejected before persistence`() {
        val node = LeafNode(1, LeafKind.TEXT, "x".repeat(MAX_CONDITION_VALUE_CHARS + 1))

        assertNull(node.toConditionOrNull())
        assertEquals(ConditionValidation.VALUE_TOO_LONG, node.validationError())
    }

    @Test
    fun `movedUp swaps an item with its predecessor and is a no-op at the top`() {
        assertEquals(listOf("b", "a", "c"), listOf("a", "b", "c").movedUp(1))
        assertEquals(listOf("a", "b", "c"), listOf("a", "b", "c").movedUp(0))
    }

    @Test
    fun `movedDown swaps an item with its successor and is a no-op at the bottom`() {
        assertEquals(listOf("a", "c", "b"), listOf("a", "b", "c").movedDown(1))
        assertEquals(listOf("a", "b", "c"), listOf("a", "b", "c").movedDown(2))
    }
}
