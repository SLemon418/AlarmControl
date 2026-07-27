package com.alarmcontrol.automation

import android.app.Application
import android.content.Intent
import android.os.BadParcelableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AutomationIntentTest {
    @Test
    fun `parses a valid authenticated request`() {
        val token = "A".repeat(43)
        val request =
            Intent(AutomationContract.ACTION_ENABLE_PROFILE)
                .setPackage(APP_PACKAGE)
                .putExtra(AutomationContract.EXTRA_PROFILE_ID, "Focus")
                .putExtra(AutomationContract.EXTRA_AUTH_TOKEN, token)
                .toExternalAutomationRequestOrNull(APP_PACKAGE)

        assertEquals(ExternalAutomationRequest(true, "Focus", token), request)
    }

    @Test
    fun `rejects an implicit request so the token cannot be broadcast to other apps`() {
        val request =
            Intent(AutomationContract.ACTION_ENABLE_PROFILE)
                .putExtra(AutomationContract.EXTRA_AUTH_TOKEN, "A".repeat(43))
                .toExternalAutomationRequestOrNull(APP_PACKAGE)

        assertNull(request)
    }

    @Test
    fun `rejects a wrong-typed token extra`() {
        val request =
            Intent(AutomationContract.ACTION_ENABLE_PROFILE)
                .setPackage(APP_PACKAGE)
                .putExtra(AutomationContract.EXTRA_AUTH_TOKEN, 42)
                .toExternalAutomationRequestOrNull(APP_PACKAGE)

        assertNull(request)
    }

    @Test
    fun `hostile extras unparcelling is ignored`() {
        val intent =
            object : Intent(AutomationContract.ACTION_ENABLE_PROFILE) {
                override fun getStringExtra(name: String?): String? = throw BadParcelableException("malformed extras")
            }.setPackage(APP_PACKAGE)

        assertNull(intent.toExternalAutomationRequestOrNull(APP_PACKAGE))
    }

    private companion object {
        const val APP_PACKAGE = "com.alarmcontrol"
    }
}
