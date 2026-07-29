package com.alarmcontrol.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

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

    @Test
    fun `rejects malformed UTF-8 instead of silently changing backup data`() {
        val malformed = byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte())

        assertThrows(IllegalArgumentException::class.java) {
            malformed.inputStream().readBackupText()
        }
    }

    @Test
    fun `writes UTF-8 backup text`() {
        val output = ByteArrayOutputStream()

        output.writeBackupText("{\"name\":\"집중\"}")

        assertEquals("{\"name\":\"집중\"}", output.toString(Charsets.UTF_8))
    }

    @Test
    fun `rejects output whose UTF-8 bytes exceed the import limit`() {
        val output = ByteArrayOutputStream()

        assertThrows(IllegalArgumentException::class.java) {
            output.writeBackupText("한", maxBytes = 2)
        }

        assertEquals(0, output.size())
    }
}
