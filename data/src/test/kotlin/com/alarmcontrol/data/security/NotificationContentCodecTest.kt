package com.alarmcontrol.data.security

import com.alarmcontrol.core.filtering.MAX_NOTIFICATION_TEXT_CHARS
import com.alarmcontrol.core.filtering.MAX_NOTIFICATION_TITLE_CHARS
import com.alarmcontrol.core.filtering.NotificationContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationContentCodecTest {
    @Test
    fun `unicode notification content round-trips`() {
        val content = NotificationContent(title = "결제 완료", text = "12,000원이 출금되었습니다")

        assertEquals(content, NotificationContentCodec.decode(NotificationContentCodec.encode(content)))
    }

    @Test
    fun `encoding enforces ingestion bounds`() {
        val content =
            NotificationContent(
                title = "t".repeat(MAX_NOTIFICATION_TITLE_CHARS + 10),
                text = "b".repeat(MAX_NOTIFICATION_TEXT_CHARS + 10),
            )

        val decoded = NotificationContentCodec.decode(NotificationContentCodec.encode(content))

        assertEquals(MAX_NOTIFICATION_TITLE_CHARS, decoded.title?.length)
        assertEquals(MAX_NOTIFICATION_TEXT_CHARS, decoded.text?.length)
    }

    @Test
    fun `decoder rejects unexpected trailing payload data`() {
        val encoded = NotificationContentCodec.encode(NotificationContent("title", "body")) + byteArrayOf(1)

        assertThrows(IllegalArgumentException::class.java) {
            NotificationContentCodec.decode(encoded)
        }
    }
}
