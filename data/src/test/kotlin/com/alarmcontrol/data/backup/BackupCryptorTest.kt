package com.alarmcontrol.data.backup

import org.json.JSONObject
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

    @Test
    fun `unsupported envelope version is rejected before decryption`() {
        val password = "right".toCharArray()
        val encrypted =
            JSONObject(BackupCryptor.encrypt("private", password))
                .put("version", 2)
                .toString()

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                BackupCryptor.decrypt(encrypted, password)
            }

        assertEquals("Unsupported backup encryption version", error.message)
    }

    @Test
    fun `invalid encoded parameters are rejected before key derivation`() {
        val password = "right".toCharArray()
        val encrypted =
            JSONObject(BackupCryptor.encrypt("private", password))
                .put("salt", "A".repeat(25))
                .toString()

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                BackupCryptor.decrypt(encrypted, password)
            }

        assertEquals("Invalid encrypted backup parameters", error.message)
    }

    @Test
    fun `excessively long passphrases are rejected`() {
        val password = CharArray(1_025) { 'x' }

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                BackupCryptor.encrypt("private", password)
            }

        assertEquals("Backup passphrase is too long", error.message)
    }
}
