package com.alarmcontrol.ml.asset

import android.content.Context
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.ml.MlConfig

/**
 * Reads the bundled text sidecars (vocab, labels) that accompany the `.tflite` model. These are
 * generated together by `ml/training/train.py`, so they are the single source of truth for the
 * model's feature and label order (§5).
 */
internal object ModelAssets {
    data class ClassifierAssetSet(
        val model: String,
        val vocabulary: String,
        val labels: String,
    )

    /**
     * Resolves the immutable classifier generation selected by the trainer's atomic pointer.
     * Repositories that predate generation publishing keep using the original root asset names.
     */
    fun classifierAssetSet(context: Context): ClassifierAssetSet {
        val fallback =
            ClassifierAssetSet(
                model = MlConfig.MODEL_ASSET,
                vocabulary = MlConfig.VOCAB_ASSET,
                labels = MlConfig.LABELS_ASSET,
            )
        val generation =
            runCatchingPreservingCancellation {
                context.assets
                    .open(MlConfig.CLASSIFIER_GENERATION_POINTER_ASSET)
                    .bufferedReader()
                    .use { it.readText().trim() }
            }.getOrNull()
                ?: return fallback
        if (!CLASSIFIER_GENERATION.matches(generation)) return fallback
        val prefix = "${MlConfig.CLASSIFIER_GENERATIONS_ASSET_DIRECTORY}/$generation"
        val candidate =
            ClassifierAssetSet(
                model = "$prefix/${MlConfig.MODEL_ASSET}",
                vocabulary = "$prefix/${MlConfig.VOCAB_ASSET}",
                labels = "$prefix/${MlConfig.LABELS_ASSET}",
            )
        return candidate.takeIf { assetSet ->
            listOf(assetSet.model, assetSet.vocabulary, assetSet.labels).all { asset ->
                runCatchingPreservingCancellation {
                    context.assets.open(asset).use { Unit }
                }.isSuccess
            }
        } ?: fallback
    }

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

    private val CLASSIFIER_GENERATION = Regex("[0-9a-f]{32}")
}
