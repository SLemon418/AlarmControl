package com.alarmcontrol.ml.asset

import android.content.Context
import com.alarmcontrol.core.result.runCatchingPreservingCancellation

/**
 * Reads the bundled text sidecars (vocab, labels) that accompany the `.tflite` model. These are
 * generated together by `ml/training/train.py`, so they are the single source of truth for the
 * model's feature and label order (§5).
 */
internal object ModelAssets {
    /**
     * Returns the trimmed, non-blank lines of [assetName], or an empty list if the asset is missing
     * or unreadable — keeping classification gracefully degradable to rule-only filtering (§5).
     */
    fun readLines(
        context: Context,
        assetName: String,
    ): List<String> =
        runCatchingPreservingCancellation {
            context.assets.open(assetName).bufferedReader().use { reader ->
                reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            }
        }.getOrElse { emptyList() }
}
