package com.alarmcontrol.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostCommitEnrichmentGateTest {
    @Test
    fun `settings generation change revokes accepted optional enrichment`() {
        assertFalse(
            isPostCommitOptionalEnrichmentCurrent(
                capturedGeneration = 4,
                currentGeneration = 5,
            ),
        )
    }

    @Test
    fun `semantic classifier requires both current generation and current opt in`() {
        assertTrue(isPostCommitSemanticClassifierEnabled(4, 4, currentEnabled = true))
        assertFalse(isPostCommitSemanticClassifierEnabled(4, 5, currentEnabled = true))
        assertFalse(isPostCommitSemanticClassifierEnabled(4, 4, currentEnabled = false))
        assertFalse(isPostCommitSemanticClassifierEnabled(4, 4, currentEnabled = null))
    }
}
