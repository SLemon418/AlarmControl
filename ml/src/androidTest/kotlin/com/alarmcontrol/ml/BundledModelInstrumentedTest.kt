package com.alarmcontrol.ml

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.ml.asset.ModelAssets
import com.alarmcontrol.ml.classifier.LiteRTNotificationClassifier
import com.alarmcontrol.ml.feature.BagOfWordsFeatureExtractor
import com.alarmcontrol.ml.inference.BundledTfLiteBackend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification that the bundled `.tflite` actually loads and runs under the real Android
 * official LiteRT runtime (`com.google.ai.edge.litert:litert`). The JVM unit tests cover the
 * classifier's decision logic with a fake backend; only this suite can catch a converter/runtime
 * version mismatch between the model (converted with a newer TF) and the bundled Android runtime.
 *
 * The pipeline assembled here mirrors what [com.alarmcontrol.ml.di.MlModule] wires in production,
 * built directly so the test needs no Hilt graph. Fixtures mirror those verified by
 * `ml/training/train.py` (CLAUDE.md §5/§9).
 *
 * Requires a device/emulator:  `./gradlew :ml:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class BundledModelInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var vocabulary: List<String>
    private lateinit var labels: List<String>

    @Before
    fun loadBundledAssets() {
        vocabulary = ModelAssets.readLines(context, MlConfig.VOCAB_ASSET)
        labels = ModelAssets.readLines(context, MlConfig.LABELS_ASSET)
        assertTrue("vocab.txt must be bundled in :ml assets", vocabulary.isNotEmpty())
        assertEquals(
            "labels.txt must match the model's output order",
            listOf("promotion", "social", "news", "alarm"),
            labels,
        )
    }

    /** The core compatibility check: the real runtime loads the model and returns a valid distribution. */
    @Test
    fun realRuntimeLoadsAndRunsBundledModel() {
        val backend = BundledTfLiteBackend(context, MlConfig.MODEL_ASSET, labels.size)
        val features = BagOfWordsFeatureExtractor(vocabulary).extract("flash sale 50% off")

        val scores = backend.run(features)

        assertNotNull("Real TFLite runtime should load and run the bundled model", scores)
        assertEquals("One score per label", labels.size, scores!!.size)
        assertEquals("Softmax outputs should sum to 1", 1f, scores.sum(), 1e-3f)
    }

    /** Deterministic exact-label check across all four categories (§5/§9). */
    @Test
    fun classifiesHeldOutFixtures() =
        runTest {
            val classifier = newClassifier(MlConfig.MODEL_ASSET)
            for ((text, expected) in HELD_OUT_FIXTURES) {
                val result = classifier.classify(snapshot(text))
                assertNotNull("Expected a categorization for: $text", result)
                assertEquals("Wrong category for: $text", expected, result!!.category)
                assertTrue(
                    "Confidence ${result.confidence} below threshold for: $text",
                    result.confidence >= MlConfig.CONFIDENCE_THRESHOLD,
                )
            }
        }

    /** Graceful degradation (§5): a missing model asset must yield null, not a crash. */
    @Test
    fun missingModelDegradesToNull() =
        runTest {
            val classifier = newClassifier(modelAsset = "missing_model.tflite")
            assertNull(classifier.classify(snapshot("Huge weekend sale, 40% off everything")))
        }

    /**
     * Graceful degradation (§5): pointing the backend at a non-model asset (vocab.txt is not a
     * loadable `.tflite`) forces a load failure. The backend swallows it and classification falls
     * back to rule-only filtering — `classify` returns null instead of throwing.
     */
    @Test
    fun unloadableModelDegradesToNull() =
        runTest {
            val classifier = newClassifier(modelAsset = MlConfig.VOCAB_ASSET)
            assertNull(classifier.classify(snapshot("Huge weekend sale, 40% off everything")))
        }

    private fun newClassifier(modelAsset: String) =
        LiteRTNotificationClassifier(
            featureExtractor = BagOfWordsFeatureExtractor(vocabulary),
            backend = BundledTfLiteBackend(context, modelAsset, labels.size),
            labels = labels,
            confidenceThreshold = MlConfig.CONFIDENCE_THRESHOLD,
        )

    private fun snapshot(text: String) =
        NotificationSnapshot(
            packageName = "com.example.app",
            title = null,
            text = text,
            category = null,
            channelId = null,
            postedAtMillis = 0L,
            isOngoing = false,
        )

    private companion object {
        /** Held out from training; must match the fixtures verified in `ml/training/train.py`. */
        val HELD_OUT_FIXTURES =
            listOf(
                "Huge weekend sale, 40% off everything" to "promotion",
                "Your coupon for free shipping expires soon" to "promotion",
                "Casey liked your photo and left a comment" to "social",
                "New message from your friend Jordan" to "social",
                "Breaking news: live election update tonight" to "news",
                "Top headlines and market update today" to "news",
                "Alarm ringing, time to wake up now" to "alarm",
                "Snooze your morning alarm reminder" to "alarm",
                "오늘만 특별 할인 쿠폰이 곧 만료됩니다" to "promotion",
                "친구가 내 사진에 댓글을 남겼습니다" to "social",
                "속보 주요 뉴스와 날씨 소식입니다" to "news",
                "아침 기상 알람이 울리고 있습니다" to "alarm",
            )
    }
}
