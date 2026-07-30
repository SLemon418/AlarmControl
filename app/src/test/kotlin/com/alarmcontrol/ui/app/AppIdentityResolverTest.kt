package com.alarmcontrol.ui.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppIdentityResolverTest {
    @Test
    fun `display label removes control and bidirectional override characters`() {
        val label = "Bank\u202Etxt.exe\nSecure"

        val sanitized = label.safeAppLabel("com.example")

        assertEquals("Banktxt.exeSecure", sanitized)
        assertFalse(sanitized.any(Char::isISOControl))
    }

    @Test
    fun `blank unsafe label falls back to package name`() {
        assertEquals("com.example", "\u202E\n".safeAppLabel("com.example"))
    }

    @Test
    fun `display label is bounded without splitting a surrogate pair`() {
        val label = "a".repeat(99) + "\uD83D\uDE00" + "tail"

        val sanitized = label.safeAppLabel("com.example")

        assertEquals(99, sanitized.length)
        assertFalse(Character.isHighSurrogate(sanitized.last()))
    }

    @Test
    fun `missing package identity is marked as a package fallback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val identity = AndroidAppIdentityResolver(context).resolve("com.example.definitely.missing")

        assertEquals("com.example.definitely.missing", identity.label)
        assertTrue(identity.isPackageFallback)
    }
}
