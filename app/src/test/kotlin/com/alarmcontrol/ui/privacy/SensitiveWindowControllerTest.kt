package com.alarmcontrol.ui.privacy

import org.junit.Assert.assertEquals
import org.junit.Test

class SensitiveWindowControllerTest {
    @Test
    fun `protection remains active until the final holder closes`() {
        val changes = mutableListOf<Boolean>()
        val controller = SensitiveWindowController(changes::add)

        val first = controller.acquire()
        val second = controller.acquire()
        first.close()
        first.close()

        assertEquals(listOf(true), changes)

        second.close()

        assertEquals(listOf(true, false), changes)
    }
}
