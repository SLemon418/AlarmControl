package com.alarmcontrol.ui.privacy

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SensitiveClipboardTest {
    @Test
    fun `marks token sensitive and clears it after the delay`() =
        runTest {
            val clipboard =
                ApplicationProvider
                    .getApplicationContext<Application>()
                    .getSystemService(ClipboardManager::class.java)

            copySensitiveText(clipboard, "Token", "secret-token", this, clearDelayMillis = 1_000)

            assertTrue(
                clipboard.primaryClip!!
                    .description.extras!!
                    .getBoolean(ClipDescription.EXTRA_IS_SENSITIVE),
            )
            advanceTimeBy(1_001)

            assertFalse(clipboard.hasPrimaryClip())
        }

    @Test
    fun `does not clear a clipboard value the user replaced`() =
        runTest {
            val clipboard =
                ApplicationProvider
                    .getApplicationContext<Application>()
                    .getSystemService(ClipboardManager::class.java)
            copySensitiveText(clipboard, "Token", "secret-token", this, clearDelayMillis = 1_000)
            clipboard.setPrimaryClip(ClipData.newPlainText("Other", "keep me"))

            advanceTimeBy(1_001)

            assertEquals(
                "keep me",
                clipboard.primaryClip!!
                    .getItemAt(0)
                    .text
                    .toString(),
            )
        }

    @Test
    fun `does not clear an identical value copied by another owner`() =
        runTest {
            val clipboard =
                ApplicationProvider
                    .getApplicationContext<Application>()
                    .getSystemService(ClipboardManager::class.java)
            copySensitiveText(clipboard, "Token", "secret-token", this, clearDelayMillis = 1_000)
            clipboard.setPrimaryClip(ClipData.newPlainText("Other", "secret-token"))

            advanceTimeBy(1_001)

            assertTrue(clipboard.hasPrimaryClip())
            assertEquals(
                "Other",
                clipboard.primaryClip!!
                    .description.label
                    .toString(),
            )
        }

    @Test
    fun `returns false when the clipboard rejects the initial write`() =
        runTest {
            val clipboard = mockk<ClipboardManager>()
            every { clipboard.setPrimaryClip(any()) } throws SecurityException("denied")

            assertFalse(copySensitiveText(clipboard, "Token", "secret-token", this))
        }

    @Test
    fun `a revoked clipboard does not crash delayed cleanup`() =
        runTest {
            val clipboard = mockk<ClipboardManager>(relaxed = true)
            every { clipboard.primaryClip } throws SecurityException("background access denied")

            assertTrue(copySensitiveText(clipboard, "Token", "secret-token", this, clearDelayMillis = 1_000))
            advanceTimeBy(1_001)
        }
}
