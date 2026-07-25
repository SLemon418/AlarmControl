package com.alarmcontrol.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LimitedInputTest {
    @Test
    fun `reads UTF-8 backup text`() {
        val text = "{\"rule\":\"야간 알림\"}"

        assertEquals(text, text.byteInputStream().readBackupText())
    }

    @Test
    fun `rejects an oversized backup before parsing`() {
        assertThrows(IllegalArgumentException::class.java) {
            "1234".byteInputStream().readBackupText(maxBytes = 3)
        }
    }
}
