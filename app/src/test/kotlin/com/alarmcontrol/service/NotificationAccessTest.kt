package com.alarmcontrol.service

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NotificationAccessTest {
    @Test
    fun `channel settings intent carries the exact package and channel`() {
        val intent = NotificationAccess.notificationSettingsIntent("com.example.shop", "offers")

        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals("com.example.shop", intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals("offers", intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `missing channel builds package notification settings intent`() {
        val intent = NotificationAccess.notificationSettingsIntent("com.example.shop", null)

        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals("com.example.shop", intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `unavailable OEM channel screen falls back to package settings`() {
        val context = RecordingContext(ApplicationProvider.getApplicationContext())

        assertTrue(NotificationAccess.openNotificationSettings(context, "com.example.shop", "offers"))
        assertEquals(
            listOf(
                Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            ),
            context.started.map(Intent::getAction),
        )
    }

    private class RecordingContext(
        base: Context,
    ) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            started += intent
            if (intent.action == Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS) {
                throw ActivityNotFoundException("No OEM channel activity")
            }
        }

        override fun startActivity(
            intent: Intent,
            options: android.os.Bundle?,
        ) = startActivity(intent)
    }
}
