package com.alarmcontrol.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptorTest {
    @Test
    fun `encrypted backup round-trips unicode content`() {
        val plaintext = "{\"rule\":\"야간 알림\"}"
        val password = "correct horse battery staple".toCharArray()

        val encrypted = BackupCryptor.encrypt(plaintext, password)

        assertTrue(BackupCryptor.isEncrypted(encrypted))
        assertEquals(plaintext, BackupCryptor.decrypt(encrypted, password))
    }

    @Test
    fun `wrong password cannot authenticate the backup`() {
        val encrypted = BackupCryptor.encrypt("private", "right".toCharArray())

        assertThrows(Exception::class.java) {
            BackupCryptor.decrypt(encrypted, "wrong".toCharArray())
        }
    }
}
