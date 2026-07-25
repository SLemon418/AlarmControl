package com.alarmcontrol.data.mapper

import com.alarmcontrol.core.feedback.CategoryFeedback
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryFeedbackMappersTest {
    @Test
    fun `toEntity copies fields and leaves id default for Room`() {
        val entity =
            CategoryFeedback(
                packageName = "com.example.shop",
                notificationEventId = "42",
                predictedLabel = "promotion",
                correctedLabel = "social",
                recordedAtMillis = 1_234L,
            ).toEntity()

        assertEquals(0L, entity.id)
        assertEquals("com.example.shop", entity.packageName)
        assertEquals(42L, entity.notificationEventId)
        assertEquals("promotion", entity.predictedLabel)
        assertEquals("social", entity.correctedLabel)
        assertEquals(1_234L, entity.recordedAtMillis)
    }
}
