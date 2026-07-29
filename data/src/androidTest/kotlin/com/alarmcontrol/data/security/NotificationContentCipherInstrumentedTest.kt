package com.alarmcontrol.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationContentCipherInstrumentedTest {
    private val cipher = AndroidKeystoreNotificationContentCipher()

    @Before
    fun clearKeyBeforeTest() {
        cipher.deleteKey()
    }

    @After
    fun clearKeyAfterTest() {
        cipher.deleteKey()
    }

    @Test
    fun encryptRoundTripUsesProviderGeneratedUniqueNonce() {
        val plaintext = "private notification text".toByteArray()

        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)

        assertEquals(12, first.nonce.size)
        assertEquals(12, second.nonce.size)
        assertFalse(first.nonce.contentEquals(second.nonce))
        assertArrayEquals(plaintext, cipher.decrypt(first))
        assertArrayEquals(plaintext, cipher.decrypt(second))
    }

    @Test
    fun decryptRejectsModifiedCiphertext() {
        val encrypted = cipher.encrypt("private notification text".toByteArray())
        val modified =
            encrypted.copy(
                ciphertext =
                    encrypted.ciphertext.copyOf().also { bytes ->
                        bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
                    },
            )

        assertThrows(Exception::class.java) {
            cipher.decrypt(modified)
        }
    }
}
