package com.alarmcontrol.automation

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.alarmcontrol.core.profile.FilteringProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Local JVM test (Robolectric) that the dynamic shortcuts publish with the right ids and actions —
 * no emulator needed. SDK 34 is Robolectric 4.11.1's max while the module compiles against 35.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileShortcutsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `publish exposes filtering master-switch shortcuts`() {
        ProfileShortcuts.publish(context)

        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertEquals(setOf("enable_filtering", "pause_filtering"), shortcuts.map { it.id }.toSet())

        assertEquals(
            ProfileShortcuts.ACTION_ENABLE_FILTERING,
            shortcuts.single { it.id == "enable_filtering" }.intent.action,
        )
        assertEquals(
            ProfileShortcuts.ACTION_PAUSE_FILTERING,
            shortcuts.single { it.id == "pause_filtering" }.intent.action,
        )
    }

    @Test
    fun `publish adds a stored profile toggle with an explicit local intent`() {
        ProfileShortcuts.publish(
            context,
            listOf(FilteringProfile(id = "7", name = "Focus", ruleIds = setOf("1"))),
        )

        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == "profile_7" }
        assertEquals(ProfileShortcuts.ACTION_TOGGLE_PROFILE, shortcut.intent.action)
        assertEquals("7", shortcut.intent.getStringExtra(ProfileShortcuts.EXTRA_PROFILE_ID))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy shortcut actions remain compatible`() {
        assertEquals(true, ProfileShortcuts.enabledForAction(ProfileShortcuts.ACTION_MUTE_ALL))
        assertEquals(false, ProfileShortcuts.enabledForAction(ProfileShortcuts.ACTION_RESUME_ALL))
    }
}
