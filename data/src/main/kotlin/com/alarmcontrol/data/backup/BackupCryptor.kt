package com.alarmcontrol.data.backup

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-based AES-256-GCM envelope for user-directed local backup files. */
internal object BackupCryptor {
    private const val ENCRYPTION = "AES-256-GCM"
    private const val KDF = "PBKDF2-HMAC-SHA256"
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun isEncrypted(serialized: String): Boolean =
        runCatching { JSONObject(serialized).optString("encryption") == ENCRYPTION }.getOrDefault(false)

    fun encrypt(
        plaintext: String,
        passphrase: CharArray,
    ): String {
        require(passphrase.isNotEmpty()) { "Backup passphrase cannot be empty" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return JSONObject()
            .put("version", 1)
            .put("encryption", ENCRYPTION)
            .put("kdf", KDF)
            .put("iterations", ITERATIONS)
            .put("salt", salt.base64())
            .put("iv", iv.base64())
            .put("ciphertext", encrypted.base64())
            .toString(2)
    }

    fun decrypt(
        serialized: String,
        passphrase: CharArray,
    ): String {
        require(passphrase.isNotEmpty()) { "Backup passphrase is required" }
        val envelope = JSONObject(serialized)
        require(envelope.getString("encryption") == ENCRYPTION) { "Unsupported backup encryption" }
        require(envelope.getString("kdf") == KDF) { "Unsupported backup key derivation" }
        require(envelope.getInt("iterations") == ITERATIONS) { "Unsupported backup iteration count" }
        val salt = envelope.getString("salt").decodeBase64()
        val iv = envelope.getString("iv").decodeBase64()
        val ciphertext = envelope.getString("ciphertext").decodeBase64()
        require(salt.size == SALT_BYTES && iv.size == IV_BYTES) { "Invalid encrypted backup parameters" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
    ): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                SecretKeySpec(bytes, "AES")
            } finally {
                bytes.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

    private fun String.decodeBase64(): ByteArray = Base64.getDecoder().decode(this)
}
