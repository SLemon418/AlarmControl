package com.alarmcontrol.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationContractTest {
    @Test
    fun `generated token shape passes cheap receiver validation`() {
        assertTrue("A".repeat(41).plus("-_").isPlausibleAutomationToken())
    }

    @Test
    fun `missing malformed and non ascii tokens are rejected before dependency lookup`() {
        assertFalse(null.isPlausibleAutomationToken())
        assertFalse("short-token".isPlausibleAutomationToken())
        assertFalse("A".repeat(42).plus("=").isPlausibleAutomationToken())
        assertFalse("가".repeat(43).isPlausibleAutomationToken())
    }
}
