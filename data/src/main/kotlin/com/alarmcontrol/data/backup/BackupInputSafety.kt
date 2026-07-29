package com.alarmcontrol.data.backup

import com.alarmcontrol.core.backup.MAX_BACKUP_FILE_BYTES
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Rejects pathological JSON nesting before `org.json` recursively materializes attacker-controlled
 * input. The limit still accommodates the supported 32-level condition tree.
 */
internal fun String.requireSafeJsonNesting(maxDepth: Int = MAX_BACKUP_JSON_DEPTH) {
    require(maxDepth > 0) { "JSON depth limit must be positive" }
    var depth = 0
    var inString = false
    var escaped = false
    for (character in this) {
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    require(depth <= maxDepth) { "Backup JSON is too deeply nested" }
                }
                '}', ']' -> {
                    depth -= 1
                    require(depth >= 0) { "Backup JSON structure is invalid" }
                }
            }
        }
    }
    require(!inString && depth == 0) { "Backup JSON structure is invalid" }
}

/** Converts authenticated backup plaintext without silently replacing malformed UTF-8 bytes. */
internal fun ByteArray.decodeUtf8Strict(): String =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("Backup plaintext is not valid UTF-8", error)
    }

/** Enforces the portable-file limit without allocating another full UTF-8 copy of the backup. */
internal fun String.requireBackupFileSize(
    maxBytes: Int = MAX_BACKUP_FILE_BYTES,
    tooLargeMessage: String = "Backup file is too large",
): String {
    require(maxBytes > 0) { "Backup size limit must be positive" }
    var utf8Bytes = 0L
    var index = 0
    while (index < length) {
        val character = this[index]
        val encodedBytes =
            when {
                character.code <= MAX_ONE_BYTE_UTF8_CODE_POINT -> 1
                character.code <= MAX_TWO_BYTE_UTF8_CODE_POINT -> 2
                Character.isHighSurrogate(character) &&
                    index + 1 < length &&
                    Character.isLowSurrogate(this[index + 1]) -> {
                    index += 1
                    UTF8_SURROGATE_PAIR_BYTES
                }
                Character.isSurrogate(character) -> 1
                else -> UTF8_BMP_BYTES
            }
        utf8Bytes += encodedBytes
        require(utf8Bytes <= maxBytes) { tooLargeMessage }
        index += 1
    }
    return this
}

private const val MAX_BACKUP_JSON_DEPTH = 80
private const val MAX_ONE_BYTE_UTF8_CODE_POINT = 0x7f
private const val MAX_TWO_BYTE_UTF8_CODE_POINT = 0x7ff
private const val UTF8_BMP_BYTES = 3
private const val UTF8_SURROGATE_PAIR_BYTES = 4
