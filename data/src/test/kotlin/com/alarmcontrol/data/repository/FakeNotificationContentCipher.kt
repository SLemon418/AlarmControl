package com.alarmcontrol.data.repository

import com.alarmcontrol.data.security.EncryptedContent
import com.alarmcontrol.data.security.NotificationContentCipher

internal class FakeNotificationContentCipher(
    var failEncryption: Boolean = false,
    var failDecryption: Boolean = false,
) : NotificationContentCipher {
    var keyDeleted: Boolean = false
        private set
    var beforeEncryption: (() -> Unit)? = null

    override fun encrypt(plaintext: ByteArray): EncryptedContent {
        beforeEncryption?.invoke()
        check(!failEncryption) { "Encryption failed" }
        keyDeleted = false
        return EncryptedContent(
            formatVersion = 1,
            aadId = "test-aad",
            nonce = byteArrayOf(1, 2, 3),
            ciphertext = plaintext.map { (it.toInt() xor MASK).toByte() }.toByteArray(),
        )
    }

    override fun decrypt(payload: EncryptedContent): ByteArray {
        check(!failDecryption && !keyDeleted) { "Decryption failed" }
        return payload.ciphertext.map { (it.toInt() xor MASK).toByte() }.toByteArray()
    }

    override fun deleteKey() {
        keyDeleted = true
    }

    private companion object {
        const val MASK = 0x5A
    }
}
