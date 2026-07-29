package com.alarmcontrol.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

internal data class EncryptedContent(
    val formatVersion: Int,
    val aadId: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

internal interface NotificationContentCipher {
    fun encrypt(plaintext: ByteArray): EncryptedContent

    fun decrypt(payload: EncryptedContent): ByteArray

    fun deleteKey()
}

/** Android-Keystore AES-GCM implementation; the key is non-exportable and never backed up. */
@Singleton
internal class AndroidKeystoreNotificationContentCipher
    @Inject
    constructor() : NotificationContentCipher {
        override fun encrypt(plaintext: ByteArray): EncryptedContent {
            val aadId = UUID.randomUUID().toString()
            val cipher =
                Cipher.getInstance(TRANSFORMATION).apply {
                    // Android Keystore keys with randomized encryption enabled reject a
                    // caller-supplied GCM IV. Let the provider generate it, then persist that IV
                    // alongside the ciphertext for decryption.
                    init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                    updateAAD(aadId.toByteArray(Charsets.UTF_8))
                }
            val ciphertext = cipher.doFinal(plaintext)
            val nonce = requireNotNull(cipher.iv).copyOf()
            require(nonce.size == NONCE_BYTES) { "Unexpected AES-GCM nonce length" }
            return EncryptedContent(
                formatVersion = FORMAT_VERSION,
                aadId = aadId,
                nonce = nonce,
                ciphertext = ciphertext,
            )
        }

        override fun decrypt(payload: EncryptedContent): ByteArray {
            require(payload.formatVersion == FORMAT_VERSION) { "Unsupported encrypted content format" }
            val cipher =
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, requireKey(), GCMParameterSpec(TAG_BITS, payload.nonce))
                    updateAAD(payload.aadId.toByteArray(Charsets.UTF_8))
                }
            return cipher.doFinal(payload.ciphertext)
        }

        override fun deleteKey() {
            keyStore().deleteEntry(KEY_ALIAS)
        }

        private fun getOrCreateKey(): SecretKey =
            keyStore().getKey(KEY_ALIAS, null) as? SecretKey
                ?: KeyGenerator
                    .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                    .apply {
                        init(
                            KeyGenParameterSpec
                                .Builder(
                                    KEY_ALIAS,
                                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setKeySize(KEY_BITS)
                                .setRandomizedEncryptionRequired(true)
                                .build(),
                        )
                    }.generateKey()

        private fun requireKey(): SecretKey =
            keyStore().getKey(KEY_ALIAS, null) as? SecretKey
                ?: error("Encrypted notification content key is unavailable")

        private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

        private companion object {
            const val ANDROID_KEY_STORE = "AndroidKeyStore"
            const val KEY_ALIAS = "alarmcontrol.notification-content.v1"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
            const val FORMAT_VERSION = 1
            const val KEY_BITS = 256
            const val TAG_BITS = 128
            const val NONCE_BYTES = 12
        }
    }
