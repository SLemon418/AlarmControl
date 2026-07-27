package com.alarmcontrol.data.backup

import org.junit.Assert.assertThrows
import org.junit.Test

class BackupInputSafetyTest {
    @Test
    fun `rejects pathological nesting before JSON parsing`() {
        val nested = "[".repeat(81) + "]".repeat(81)

        assertThrows(IllegalArgumentException::class.java) {
            nested.requireSafeJsonNesting()
        }
    }

    @Test
    fun `ignores structural characters inside JSON strings`() {
        """{"value":"[{\\\"still text\\\"}]"}""".requireSafeJsonNesting()
    }

    @Test
    fun `rejects malformed UTF-8 plaintext`() {
        val malformed = byteArrayOf(0xc3.toByte(), 0x28)

        assertThrows(IllegalArgumentException::class.java) {
            malformed.decodeUtf8Strict()
        }
    }
}
