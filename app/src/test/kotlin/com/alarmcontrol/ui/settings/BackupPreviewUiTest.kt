package com.alarmcontrol.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPreviewUiTest {
    @Test
    fun `plaintext backup cannot offer learning feedback even with legacy votes`() {
        val preview = preview(encrypted = false, adFeedbackVotes = 3)

        assertFalse(preview.canRestoreLearningFeedback)
    }

    @Test
    fun `encrypted backup offers either supported feedback type`() {
        assertTrue(preview(encrypted = true, categoryFeedback = 1).canRestoreLearningFeedback)
        assertTrue(preview(encrypted = true, adFeedbackVotes = 1).canRestoreLearningFeedback)
    }

    @Test
    fun `encrypted backup without feedback keeps the option disabled`() {
        assertFalse(preview(encrypted = true).canRestoreLearningFeedback)
    }

    private fun preview(
        encrypted: Boolean,
        categoryFeedback: Int = 0,
        adFeedbackVotes: Int = 0,
    ) = BackupPreviewUi(
        encrypted = encrypted,
        rules = 0,
        profiles = 0,
        dailyInsights = 0,
        hasSettings = false,
        categoryFeedback = categoryFeedback,
        adFeedbackVotes = adFeedbackVotes,
    )
}
