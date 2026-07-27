package com.alarmcontrol.ui.privacy

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private const val CLIPBOARD_CLEAR_DELAY_MILLIS = 60_000L
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
private const val EXTRA_OWNER_ID = "com.alarmcontrol.extra.CLIP_OWNER_ID"

/** Copies a secret with Android's sensitive marker and removes only that unchanged value later. */
fun copySensitiveText(
    clipboard: ClipboardManager,
    label: String,
    value: String,
    scope: CoroutineScope,
    clearDelayMillis: Long = CLIPBOARD_CLEAR_DELAY_MILLIS,
): Boolean {
    val ownerId = UUID.randomUUID().toString()
    val clip = ClipData.newPlainText(label, value)
    clip.description.extras =
        PersistableBundle().apply {
            // API 33 published the field, but this stable Bundle key is safe on every supported API.
            putBoolean(EXTRA_IS_SENSITIVE, true)
            putString(EXTRA_OWNER_ID, ownerId)
        }
    try {
        clipboard.setPrimaryClip(clip)
    } catch (_: RuntimeException) {
        return false
    }
    scope.launch {
        delay(clearDelayMillis)
        try {
            if (clipboard.ownsPrimaryClip(ownerId = ownerId, expectedText = value)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        } catch (_: RuntimeException) {
            // Clipboard access can be revoked once the app loses focus; delayed cleanup is best-effort.
        }
    }
    return true
}

private fun ClipboardManager.ownsPrimaryClip(
    ownerId: String,
    expectedText: String,
): Boolean {
    val clip = primaryClip ?: return false
    return clip.description.extras?.getString(EXTRA_OWNER_ID) == ownerId &&
        clip.itemCount > 0 &&
        clip.getItemAt(0).text?.toString() == expectedText
}
