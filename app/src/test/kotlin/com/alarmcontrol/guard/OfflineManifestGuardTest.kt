package com.alarmcontrol.guard

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Enforces §3 against the REAL merged manifest: the app must request no `INTERNET` permission. Runs on
 * the JVM via Robolectric (no emulator). A plain [android.app.Application] is used so Hilt isn't booted;
 * `ACCESS_NETWORK_STATE` (contributed by WorkManager) is expected and explicitly allowed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class OfflineManifestGuardTest {
    @Test
    fun `merged manifest declares no forbidden permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requested =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.toList()
                .orEmpty()

        // Sanity: confirm we actually read merged-manifest permissions (WorkManager adds this one),
        // which also documents that ACCESS_NETWORK_STATE is present yet allowed.
        assertTrue(
            "Expected to read merged-manifest permissions",
            requested.contains("android.permission.ACCESS_NETWORK_STATE"),
        )
        assertEquals(emptyList<String>(), OfflineGuard.forbiddenPermissions(requested))
    }
}
