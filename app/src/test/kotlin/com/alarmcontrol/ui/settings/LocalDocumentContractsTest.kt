package com.alarmcontrol.ui.settings

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalDocumentContractsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `create contract requests a local openable destination`() {
        val intent = CreateLocalDocument("application/json").createIntent(context, "backup.json")

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/json", intent.type)
        assertEquals("backup.json", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
    }

    @Test
    fun `open contract requests only local documents for every MIME type`() {
        val types = arrayOf("application/octet-stream", "application/zip")
        val intent = OpenLocalDocument().createIntent(context, types)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertArrayEquals(types, intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
    }

    @Test
    fun `result parser returns data only for a successful picker result`() {
        val contract = OpenLocalDocument()
        val uri = Uri.parse("content://local/model")

        assertEquals(uri, contract.parseResult(Activity.RESULT_OK, Intent().setData(uri)))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(uri)))
    }
}
