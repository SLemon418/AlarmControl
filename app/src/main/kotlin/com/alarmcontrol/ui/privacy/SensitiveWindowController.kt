package com.alarmcontrol.ui.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.Closeable

/**
 * Reference-counted window protection shared by independently composed sensitive surfaces.
 * The first holder enables capture protection and the final holder disables it.
 */
class SensitiveWindowController(
    private val onProtectionChanged: (Boolean) -> Unit,
) {
    private var holderCount = 0

    @Synchronized
    fun acquire(): Closeable {
        holderCount += 1
        if (holderCount == 1) onProtectionChanged(true)
        return ProtectionHandle(::release)
    }

    @Synchronized
    private fun release() {
        check(holderCount > 0) { "Sensitive window protection released without a holder" }
        holderCount -= 1
        if (holderCount == 0) onProtectionChanged(false)
    }

    private class ProtectionHandle(
        private val onClose: () -> Unit,
    ) : Closeable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            onClose()
        }
    }
}

val LocalSensitiveWindowController =
    staticCompositionLocalOf<SensitiveWindowController?> { null }

/** Keeps `FLAG_SECURE` active only while [active] sensitive UI is visible. */
@Composable
fun ProtectSensitiveWindow(active: Boolean = true) {
    val controller = LocalSensitiveWindowController.current
    DisposableEffect(active, controller) {
        val handle = controller?.takeIf { active }?.acquire()
        onDispose { handle?.close() }
    }
}
