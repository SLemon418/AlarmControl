package com.alarmcontrol.ui.privacy

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CLIPBOARD_CLEAR_DELAY_MILLIS = 60_000L

/** Copies a secret with Android's sensitive marker and removes only that unchanged value later. */
fun copySensitiveText(
    clipboard: ClipboardManager,
    label: String,
    value: String,
    scope: CoroutineScope,
    clearDelayMillis: Long = CLIPBOARD_CLEAR_DELAY_MILLIS,
) {
    val clip = ClipData.newPlainText(label, value)
    clip.description.extras =
        PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    clipboard.setPrimaryClip(clip)
    scope.launch {
        delay(clearDelayMillis)
        if (clipboard.primaryClipText() == value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }
}

private fun ClipboardManager.primaryClipText(): String? =
    primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.text
        ?.toString()
