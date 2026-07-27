package com.alarmcontrol.data.backup

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

private const val MAX_BACKUP_JSON_DEPTH = 80
