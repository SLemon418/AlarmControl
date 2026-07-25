package com.alarmcontrol.ui.settings

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads a UTF-8 backup while bounding memory use before JSON parsing. */
internal fun InputStream.readBackupText(maxBytes: Int = MAX_BACKUP_BYTES): String {
    require(maxBytes > 0) { "Backup size limit must be positive" }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
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
    return output.toByteArray().toString(Charsets.UTF_8)
}

private const val BUFFER_BYTES = 8 * 1_024
private const val MAX_BACKUP_BYTES = 32 * 1_024 * 1_024
