package com.alarmcontrol.ui.settings

import com.alarmcontrol.core.backup.MAX_BACKUP_FILE_BYTES
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Reads a UTF-8 backup while bounding memory use before JSON parsing. */
internal fun InputStream.readBackupText(maxBytes: Int = MAX_BACKUP_FILE_BYTES): String {
    require(maxBytes > 0) { "Backup size limit must be positive" }
    val output = WipingByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    return try {
        var total = 0
        var count = read(buffer)
        while (count >= 0) {
            if (count > 0) {
                total += count
                require(total <= maxBytes) { "Backup file is too large" }
                output.write(buffer, 0, count)
            }
            count = read(buffer)
        }
        val bytes = output.toByteArray()
        try {
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (error: CharacterCodingException) {
                throw IllegalArgumentException("Backup is not valid UTF-8", error)
            }
        } finally {
            bytes.fill(0)
        }
    } finally {
        buffer.fill(0)
        output.wipe()
    }
}

/** Writes a backup without retaining an additional immutable UTF-8 byte array. */
internal fun OutputStream.writeBackupText(
    text: String,
    maxBytes: Int = MAX_BACKUP_FILE_BYTES,
) {
    require(maxBytes > 0) { "Backup size limit must be positive" }
    val bytes = text.toByteArray(Charsets.UTF_8)
    try {
        require(bytes.size <= maxBytes) { "Backup file is too large" }
        write(bytes)
    } finally {
        bytes.fill(0)
    }
}

private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
    fun wipe() {
        buf.fill(0)
        reset()
    }
}

private const val BUFFER_BYTES = 8 * 1_024
