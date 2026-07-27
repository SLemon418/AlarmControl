package com.alarmcontrol.ml.llm

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/** Content and sidecar validation kept separate from model-file lifecycle operations. */
internal object LocalModelIntegrity {
    fun metadataBytes(modelInfo: LlmModelInfo): ByteArray =
        "$METADATA_VERSION:${modelInfo.sizeBytes}:${modelInfo.sha256}\n"
            .toByteArray(Charsets.US_ASCII)

    fun verifyOrNull(
        model: File,
        metadata: File,
    ): LlmModelInfo? =
        try {
            verify(model, metadata)
        } catch (_: ModelIntegrityException) {
            null
        }

    fun verify(
        model: File,
        metadata: File,
    ): LlmModelInfo {
        requireIntegrity(model.isFile && model.length() > 0, "Local model is missing or invalid")
        val recorded = readMetadata(metadata)
        requireIntegrity(
            recorded.sizeBytes == model.length(),
            "Local model size no longer matches its import record",
        )
        requireIntegrity(
            hashFile(model) == recorded.sha256,
            "Local model fingerprint no longer matches its import record",
        )
        return recorded
    }

    fun digestToHex(bytes: ByteArray): String =
        try {
            bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }
        } finally {
            bytes.fill(0)
        }

    private fun readMetadata(source: File): LlmModelInfo {
        requireIntegrity(
            source.isFile && source.length() in 1..MAX_METADATA_BYTES,
            "Local model integrity record is missing or invalid",
        )
        val bytes = source.readBytes()
        return try {
            val parts = bytes.toString(Charsets.US_ASCII).trim().split(':')
            requireIntegrity(
                parts.size == METADATA_PARTS && parts[0] == METADATA_VERSION,
                "Local model integrity record is invalid",
            )
            LlmModelInfo(
                sha256 = validateFingerprint(parts[2]),
                sizeBytes = validateModelSize(parts[1]),
            )
        } finally {
            bytes.fill(0)
        }
    }

    private fun validateModelSize(value: String): Long {
        val size = value.toLongOrNull()
        requireIntegrity(size != null && size in 1..MAX_MODEL_BYTES, "Local model integrity size is invalid")
        return requireNotNull(size)
    }

    private fun validateFingerprint(value: String): String {
        requireIntegrity(
            value.length == SHA_256_HEX_CHARS && value.all { it in LOWER_HEX_CHARS },
            "Local model fingerprint is invalid",
        )
        return value
    }

    private fun hashFile(source: File): String {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        try {
            FileInputStream(source).use { input -> updateDigest(input, digest, buffer) }
            return digestToHex(digest.digest())
        } finally {
            buffer.fill(0)
        }
    }

    private fun updateDigest(
        input: InputStream,
        digest: MessageDigest,
        buffer: ByteArray,
    ) {
        var count = input.read(buffer)
        while (count >= 0) {
            if (count > 0) digest.update(buffer, 0, count)
            count = input.read(buffer)
        }
    }

    private fun requireIntegrity(
        valid: Boolean,
        message: String,
    ) {
        if (!valid) throw ModelIntegrityException(message)
    }

    private const val HASH_ALGORITHM = "SHA-256"
    private const val METADATA_VERSION = "1"
    private const val METADATA_PARTS = 3
    private const val SHA_256_HEX_CHARS = 64
    private const val MAX_METADATA_BYTES = 128L
    private const val LOWER_HEX_CHARS = "0123456789abcdef"
    private const val BYTE_MASK = 0xff
    private const val HASH_BUFFER_BYTES = 64 * 1_024
    private const val MAX_MODEL_BYTES = 4L * 1_024 * 1_024 * 1_024
}
