package com.alarmcontrol.data.security

import com.alarmcontrol.core.filtering.NotificationContent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal object NotificationContentCodec {
    const val MAX_TITLE_CHARS = 512
    const val MAX_TEXT_CHARS = 4_096

    fun encode(content: NotificationContent): ByteArray {
        val title = content.title?.take(MAX_TITLE_CHARS)
        val text = content.text?.take(MAX_TEXT_CHARS)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(PAYLOAD_VERSION)
                output.writeNullableString(title)
                output.writeNullableString(text)
            }
            bytes.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): NotificationContent =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PAYLOAD_VERSION) { "Unsupported notification content payload" }
            NotificationContent(
                title = input.readNullableString(MAX_TITLE_BYTES),
                text = input.readNullableString(MAX_TEXT_BYTES),
            )
        }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) {
            writeInt(NULL_LENGTH)
            return
        }
        val encoded = value.toByteArray(Charsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readNullableString(maxBytes: Int): String? {
        val size = readInt()
        if (size == NULL_LENGTH) return null
        require(size in 0..maxBytes) { "Invalid notification content length" }
        val encoded = ByteArray(size).also(::readFully)
        return try {
            encoded.toString(Charsets.UTF_8)
        } finally {
            encoded.fill(0)
        }
    }

    private const val PAYLOAD_VERSION = 1
    private const val NULL_LENGTH = -1
    private const val MAX_TITLE_BYTES = MAX_TITLE_CHARS * 4
    private const val MAX_TEXT_BYTES = MAX_TEXT_CHARS * 4
}
