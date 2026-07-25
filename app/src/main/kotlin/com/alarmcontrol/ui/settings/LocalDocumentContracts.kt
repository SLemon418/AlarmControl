package com.alarmcontrol.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Storage Access Framework contracts that explicitly request local-only document providers. The
 * platform owns the picker; [Intent.EXTRA_LOCAL_ONLY] asks it to exclude cloud-backed sources and
 * destinations, and AlarmControl itself implements no upload path (§1/§3).
 */
internal class CreateLocalDocument(
    private val mimeType: String,
) : ActivityResultContract<String, Uri?>() {
    override fun createIntent(
        context: Context,
        input: String,
    ): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_TITLE, input)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Opens one local document matching any of [createIntent]'s requested MIME types. */
internal class OpenLocalDocument : ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(
        context: Context,
        input: Array<String>,
    ): Intent {
        require(input.isNotEmpty()) { "At least one MIME type is required" }
        return Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (input.size == 1) input.single() else "*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, input)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}
