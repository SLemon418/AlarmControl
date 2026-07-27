package com.alarmcontrol.data.security

import com.alarmcontrol.core.filtering.MAX_NOTIFICATION_TEXT_CHARS
import com.alarmcontrol.core.filtering.MAX_NOTIFICATION_TITLE_CHARS
import com.alarmcontrol.core.filtering.NotificationContent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal object NotificationContentCodec {
    fun encode(content: NotificationContent): ByteArray {
        val title = content.title?.take(MAX_NOTIFICATION_TITLE_CHARS)
        val text = content.text?.take(MAX_NOTIFICATION_TEXT_CHARS)
        val bytes = WipingByteArrayOutputStream()
        return try {
            DataOutputStream(bytes).use { output ->
                output.writeInt(PAYLOAD_VERSION)
                output.writeNullableString(title)
                output.writeNullableString(text)
            }
            bytes.toByteArray()
        } finally {
            bytes.wipe()
        }
    }

    fun decode(bytes: ByteArray): NotificationContent =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PAYLOAD_VERSION) { "Unsupported notification content payload" }
            val content =
                NotificationContent(
                    title = input.readNullableString(MAX_TITLE_BYTES),
                    text = input.readNullableString(MAX_TEXT_BYTES),
                )
            require(input.read() == END_OF_STREAM) { "Unexpected notification content payload data" }
            content
        }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) {
            writeInt(NULL_LENGTH)
            return
        }
        val encoded = value.toByteArray(Charsets.UTF_8)
        try {
            writeInt(encoded.size)
            write(encoded)
        } finally {
            encoded.fill(0)
        }
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
    private const val END_OF_STREAM = -1
    private const val MAX_TITLE_BYTES = MAX_NOTIFICATION_TITLE_CHARS * 4
    private const val MAX_TEXT_BYTES = MAX_NOTIFICATION_TEXT_CHARS * 4

    private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }
}
