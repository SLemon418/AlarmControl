package com.alarmcontrol.data.mapper

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.NotificationImportance
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.data.db.entity.RuleConditionEntity
import com.alarmcontrol.data.db.entity.RuleEntity
import com.alarmcontrol.data.db.model.StoredConditionType
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.db.relation.RuleWithConditions
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleMappersTest {
    private fun rule(
        condition: Condition,
        action: RuleAction = RuleAction.Cancel,
    ) = Rule(
        id = "42",
        name = "rule",
        enabled = true,
        priority = 3,
        condition = condition,
        action = action,
    )

    /** Simulates the repository's depth-first insert so the mapper can round-trip without a DB. */
    private fun Condition.toRows(ruleId: Long): List<RuleConditionEntity> {
        val rows = mutableListOf<RuleConditionEntity>()
        var nextId = 1L

        fun insert(
            parentId: Long?,
            position: Int,
            node: PendingConditionNode,
        ): Long {
            val id = nextId++
            rows +=
                RuleConditionEntity(
                    id = id,
                    ruleId = ruleId,
                    parentId = parentId,
                    position = position,
                    type = node.type,
                    value = node.value,
                    ignoreCase = node.ignoreCase,
                )
            node.children.forEachIndexed { i, child -> insert(id, i, child) }
            return id
        }
        insert(parentId = null, position = 0, node = toPendingTree())
        return rows
    }

    private fun assertRoundTrips(rule: Rule) {
        val entity = rule.toRuleEntity(id = 42, createdAtMillis = 1, updatedAtMillis = 2)
        val back = RuleWithConditions(entity, rule.condition.toRows(ruleId = 42)).toDomain()
        assertEquals(rule, back)
    }

    @Test
    fun `single leaf condition round-trips for every leaf type`() {
        assertRoundTrips(rule(Condition.PackageEquals("com.example.app")))
        assertRoundTrips(rule(Condition.TitleContains("sale", ignoreCase = true)))
        assertRoundTrips(rule(Condition.TitleContains("Sale", ignoreCase = false)))
        assertRoundTrips(rule(Condition.TextContains("50% off")))
        assertRoundTrips(rule(Condition.CategoryEquals("alarm")))
        assertRoundTrips(rule(Condition.ChannelEquals("promos")))
        assertRoundTrips(rule(Condition.Ongoing(true)))
        assertRoundTrips(rule(Condition.Ongoing(false)))
        assertRoundTrips(rule(Condition.MlCategoryEquals("promotion")))
        assertRoundTrips(rule(Condition.IsAdvertisement(true)))
        assertRoundTrips(rule(Condition.IsAdvertisement(false)))
        assertRoundTrips(rule(Condition.SemanticIntentEquals(SemanticIntent.DELIVERY)))
        assertRoundTrips(rule(Condition.Conversation(true)))
        assertRoundTrips(rule(Condition.ForegroundService(false)))
        assertRoundTrips(rule(Condition.ImportanceAtLeast(NotificationImportance.HIGH)))
        assertRoundTrips(rule(Condition.RateAtLeast(RateScope.CHANNEL, 15 * 60_000L, 10)))
    }

    @Test
    fun `monitor execution mode round-trips with the rule entity`() {
        val original =
            rule(Condition.PackageEquals("com.example")).copy(executionMode = RuleExecutionMode.MONITOR)

        assertRoundTrips(original)
    }

    @Test
    fun `time window round-trips`() {
        assertRoundTrips(rule(Condition.TimeWindow(startMinuteOfDay = 1320, endMinuteOfDay = 420)))
    }

    @Test
    fun `multi-condition AllOf round-trips preserving order`() {
        assertRoundTrips(
            rule(
                Condition.AllOf(
                    listOf(
                        Condition.PackageEquals("com.example.shop"),
                        Condition.MlCategoryEquals("promotion"),
                        Condition.TextContains("coupon"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `AnyOf round-trips`() {
        assertRoundTrips(
            rule(Condition.AnyOf(listOf(Condition.CategoryEquals("alarm"), Condition.CategoryEquals("event")))),
        )
    }

    @Test
    fun `nested A AND (B OR C) round-trips`() {
        assertRoundTrips(
            rule(
                Condition.AllOf(
                    listOf(
                        Condition.PackageEquals("com.example.shop"),
                        Condition.AnyOf(
                            listOf(
                                Condition.CategoryEquals("promo"),
                                Condition.TimeWindow(1320, 420),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `Not composite round-trips around a nested condition`() {
        assertRoundTrips(
            rule(
                Condition.Not(
                    Condition.AnyOf(listOf(Condition.CategoryEquals("alarm"), Condition.PackageEquals("com.x"))),
                ),
            ),
        )
    }

    @Test
    fun `Not is stored as a NOT node wrapping its child`() {
        val tree = Condition.Not(Condition.PackageEquals("com.x")).toPendingTree()
        assertEquals(StoredConditionType.NOT, tree.type)
        assertEquals(StoredConditionType.PACKAGE, tree.children.single().type)
    }

    @Test
    fun `every action type round-trips including snooze duration`() {
        val condition = Condition.CategoryEquals("alarm")
        assertRoundTrips(rule(condition, RuleAction.Cancel))
        assertRoundTrips(rule(condition, RuleAction.MarkRead))
        assertRoundTrips(rule(condition, RuleAction.Keep))
        assertRoundTrips(rule(condition, RuleAction.Snooze(90_000L)))
    }

    @Test
    fun `entity id is exposed as the domain string id`() {
        val r = rule(Condition.CategoryEquals("alarm"))
        val entity = r.toRuleEntity(id = 7, createdAtMillis = 0, updatedAtMillis = 0)
        assertEquals("7", RuleWithConditions(entity, r.condition.toRows(ruleId = 7)).toDomain().id)
    }

    @Test
    fun `legacy flat rows rebuild as AllOf and honor leaf-level negate`() {
        val entity =
            RuleEntity(
                id = 1,
                name = "legacy",
                enabled = true,
                priority = 0,
                action = StoredRuleAction.CANCEL,
                snoozeDurationMillis = null,
                createdAtMillis = 0,
                updatedAtMillis = 0,
            )
        // Old schema: parent-less leaf rows, one negated.
        val rows =
            listOf(
                RuleConditionEntity(
                    id = 1,
                    ruleId = 1,
                    parentId = null,
                    position = 0,
                    type = StoredConditionType.PACKAGE,
                    value = "com.x",
                ),
                RuleConditionEntity(
                    id = 2,
                    ruleId = 1,
                    parentId = null,
                    position = 0,
                    type = StoredConditionType.CATEGORY,
                    value = "alarm",
                    negate = true,
                ),
            )

        val condition = RuleWithConditions(entity, rows).toDomain().condition

        assertEquals(
            Condition.AllOf(
                listOf(
                    Condition.PackageEquals("com.x"),
                    Condition.Not(Condition.CategoryEquals("alarm")),
                ),
            ),
            condition,
        )
    }
}
