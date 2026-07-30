package com.alarmcontrol.ml.asset

import android.content.Context
import android.content.res.AssetManager
import com.alarmcontrol.ml.MlConfig
import com.alarmcontrol.ml.di.MlModule
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

class ModelAssetsTest {
    @Test
    fun `complete committed generation is selected as one asset set`() {
        val generation = "a".repeat(32)
        val context =
            contextWithAssets(
                mapOf(
                    MlConfig.CLASSIFIER_GENERATION_POINTER_ASSET to "$generation\n",
                    "${MlConfig.CLASSIFIER_GENERATIONS_ASSET_DIRECTORY}/$generation/${MlConfig.MODEL_ASSET}" to
                        "model",
                    "${MlConfig.CLASSIFIER_GENERATIONS_ASSET_DIRECTORY}/$generation/${MlConfig.VOCAB_ASSET}" to
                        "vocab",
                    "${MlConfig.CLASSIFIER_GENERATIONS_ASSET_DIRECTORY}/$generation/${MlConfig.LABELS_ASSET}" to
                        "labels",
                ),
            )

        assertEquals(
            ModelAssets.ClassifierAssetSet(
                model = "classifier_generations/$generation/notification_classifier.tflite",
                vocabulary = "classifier_generations/$generation/vocab.txt",
                labels = "classifier_generations/$generation/labels.txt",
            ),
            ModelAssets.classifierAssetSet(context),
        )
    }

    @Test
    fun `incomplete or malformed generation falls back to the legacy root set`() {
        val generation = "b".repeat(32)
        val incomplete =
            contextWithAssets(
                mapOf(
                    MlConfig.CLASSIFIER_GENERATION_POINTER_ASSET to generation,
                    "${MlConfig.CLASSIFIER_GENERATIONS_ASSET_DIRECTORY}/$generation/${MlConfig.MODEL_ASSET}" to
                        "model",
                ),
            )
        val malformed =
            contextWithAssets(
                mapOf(MlConfig.CLASSIFIER_GENERATION_POINTER_ASSET to "../outside"),
            )
        val fallback =
            ModelAssets.ClassifierAssetSet(
                model = MlConfig.MODEL_ASSET,
                vocabulary = MlConfig.VOCAB_ASSET,
                labels = MlConfig.LABELS_ASSET,
            )

        assertEquals(fallback, ModelAssets.classifierAssetSet(incomplete))
        assertEquals(fallback, ModelAssets.classifierAssetSet(malformed))
    }

    @Test
    fun `category provider reads labels from the committed generation`() {
        val generation = "c".repeat(32)
        val context =
            contextWithAssets(
                mapOf(
                    MlConfig.LABELS_ASSET to "LEGACY",
                    MlConfig.CLASSIFIER_GENERATION_POINTER_ASSET to generation,
                    "${MlConfig.CLASSIFIER_GENERATIONS_ASSET_DIRECTORY}/$generation/${MlConfig.MODEL_ASSET}" to
                        "model",
                    "${MlConfig.CLASSIFIER_GENERATIONS_ASSET_DIRECTORY}/$generation/${MlConfig.VOCAB_ASSET}" to
                        "vocab",
                    "${MlConfig.CLASSIFIER_GENERATIONS_ASSET_DIRECTORY}/$generation/${MlConfig.LABELS_ASSET}" to
                        "CURRENT\nSECOND",
                ),
            )

        assertEquals(
            listOf("CURRENT", "SECOND"),
            MlModule.provideNotificationCategories(context).labels,
        )
    }

    private fun contextWithAssets(files: Map<String, String>): Context {
        val assets = mockk<AssetManager>()
        every { assets.open(any()) } answers {
            val name = firstArg<String>()
            val content = files[name] ?: throw FileNotFoundException(name)
            ByteArrayInputStream(content.toByteArray())
        }
        return mockk {
            every { this@mockk.assets } returns assets
        }
    }
}
