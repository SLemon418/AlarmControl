package com.alarmcontrol.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic checks of the [OfflineGuard] logic (injected inputs) plus the real enforcement that
 * no networking library is on the app's classpath (CLAUDE.md §3).
 */
class OfflineGuardTest {
    // --- Permission logic (injected) ---

    @Test
    fun `flags the INTERNET permission`() {
        val requested = listOf("android.permission.INTERNET", "android.permission.WAKE_LOCK")
        assertEquals(listOf("android.permission.INTERNET"), OfflineGuard.forbiddenPermissions(requested))
    }

    @Test
    fun `allows ACCESS_NETWORK_STATE and other non-egress permissions`() {
        val requested =
            listOf(
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.WAKE_LOCK",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.FOREGROUND_SERVICE",
            )
        assertTrue(OfflineGuard.forbiddenPermissions(requested).isEmpty())
    }

    // --- Dependency logic (injected class presence) ---

    @Test
    fun `flags networking libraries whose classes are present`() {
        val present = setOf("okhttp3.OkHttpClient", "retrofit2.Retrofit")
        val found = OfflineGuard.forbiddenNetworkLibraries { it in present }
        assertEquals(setOf("OkHttp", "Retrofit"), found.toSet())
    }

    @Test
    fun `flags every known networking library when all are present`() {
        val found = OfflineGuard.forbiddenNetworkLibraries { true }
        assertEquals(OfflineGuard.FORBIDDEN_NETWORK_CLASSES.values.toSet(), found.toSet())
    }

    @Test
    fun `reports nothing when no networking library is present`() {
        assertTrue(OfflineGuard.forbiddenNetworkLibraries { false }.isEmpty())
    }

    @Test
    fun `okio is not treated as a networking library`() {
        // okio ships with DataStore for file I/O; it must not be flagged as a networking client.
        assertTrue(OfflineGuard.forbiddenNetworkLibraries { it.startsWith("okio.") }.isEmpty())
    }

    // --- Real enforcement ---

    @Test
    fun `the app classpath carries no networking library`() {
        val found = OfflineGuard.forbiddenNetworkLibraries(OfflineGuard::classExists)
        assertTrue("Forbidden networking libraries on the classpath: $found", found.isEmpty())
    }
}
