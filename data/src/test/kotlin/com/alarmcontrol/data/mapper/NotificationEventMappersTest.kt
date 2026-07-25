package com.alarmcontrol.data.mapper

import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.data.db.model.StoredRuleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationEventMappersTest {
    private fun event(
        action: RuleAction,
        matchedRuleId: String?,
        category: String? = "alarm",
        mlCategory: String? = null,
    ) = NotificationEvent(
        packageName = "com.example.clock",
        mlCategory = mlCategory,
        category = category,
        postedAtMillis = 1_000L,
        action = action,
        matchedRuleId = matchedRuleId,
        recordedAtMillis = 2_000L,
    )

    @Test
    fun `maps metadata fields verbatim`() {
        val entity = event(RuleAction.Cancel, matchedRuleId = "7", mlCategory = "alarm").toEntity()
        assertEquals("com.example.clock", entity.packageName)
        assertEquals("alarm", entity.category)
        assertEquals("alarm", entity.mlCategory)
        assertEquals(1_000L, entity.postedAtMillis)
        assertEquals(2_000L, entity.recordedAtMillis)
        assertEquals(7L, entity.matchedRuleId)
    }

    @Test
    fun `every action maps to its stored type`() {
        assertEquals(StoredRuleAction.CANCEL, event(RuleAction.Cancel, "1").toEntity().action)
        assertEquals(StoredRuleAction.MARK_READ, event(RuleAction.MarkRead, "1").toEntity().action)
        assertEquals(StoredRuleAction.KEEP, event(RuleAction.Keep, null).toEntity().action)
        assertEquals(StoredRuleAction.SNOOZE, event(RuleAction.Snooze(60_000L), "1").toEntity().action)
    }

    @Test
    fun `no-match keep event has null rule id and null category survives`() {
        val entity = event(RuleAction.Keep, matchedRuleId = null, category = null).toEntity()
        assertNull(entity.matchedRuleId)
        assertNull(entity.category)
        assertEquals(StoredRuleAction.KEEP, entity.action)
    }
}
