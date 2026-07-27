package com.alarmcontrol.ml.classifier

import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.ml.feature.BagOfWordsFeatureExtractor
import com.alarmcontrol.ml.inference.InferenceBackend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiteRTNotificationClassifierTest {
    private val labels = listOf("promotion", "social", "news")

    private fun classifier(
        backend: InferenceBackend,
        threshold: Float = 0.6f,
    ) = LiteRTNotificationClassifier(
        featureExtractor = BagOfWordsFeatureExtractor(listOf("sale", "off", "friend")),
        backend = backend,
        labels = labels,
        confidenceThreshold = threshold,
    )

    private fun snapshot(
        title: String? = "Flash sale",
        text: String? = "50% off today",
    ) = NotificationSnapshot(
        packageName = "com.example.shop",
        title = title,
        text = text,
        category = null,
        channelId = null,
        postedAtMillis = 0L,
        isOngoing = false,
    )

    @Test
    fun `high confidence returns the argmax label`() =
        runTest {
            val result = classifier(FakeInferenceBackend(floatArrayOf(0.8f, 0.15f, 0.05f))).classify(snapshot())
            assertEquals(ClassificationResultOf("promotion", 0.8f), result.asPair())
        }

    @Test
    fun `confidence at the threshold is accepted`() =
        runTest {
            val result =
                classifier(FakeInferenceBackend(floatArrayOf(0.6f, 0.3f, 0.1f)), threshold = 0.6f)
                    .classify(snapshot())
            assertEquals("promotion", result?.category)
        }

    @Test
    fun `below-threshold confidence degrades to null`() =
        runTest {
            val result =
                classifier(FakeInferenceBackend(floatArrayOf(0.5f, 0.3f, 0.2f)), threshold = 0.6f)
                    .classify(snapshot())
            assertNull(result)
        }

    @Test
    fun `unavailable model degrades to null`() =
        runTest {
            assertNull(classifier(FakeInferenceBackend(scores = null)).classify(snapshot()))
        }

    @Test
    fun `backend failure degrades to null`() =
        runTest {
            val backend = FakeInferenceBackend(scores = null, error = IllegalStateException("boom"))
            assertNull(classifier(backend).classify(snapshot()))
        }

    @Test
    fun `native backend linkage failure degrades to null`() =
        runTest {
            val backend = FakeInferenceBackend(scores = null, error = UnsatisfiedLinkError("unsupported ABI"))
            assertNull(classifier(backend).classify(snapshot()))
        }

    @Test
    fun `native backend memory failure degrades to null`() =
        runTest {
            val backend = FakeInferenceBackend(scores = null, error = OutOfMemoryError("native allocation"))
            assertNull(classifier(backend).classify(snapshot()))
        }

    @Test
    fun `non-finite model scores degrade to null`() =
        runTest {
            assertNull(classifier(FakeInferenceBackend(floatArrayOf(Float.NaN, 0.8f, 0.2f))).classify(snapshot()))
            assertNull(
                classifier(FakeInferenceBackend(floatArrayOf(Float.POSITIVE_INFINITY, 0.1f, 0f))).classify(snapshot()),
            )
        }

    @Test
    fun `incompatible output shape or probability range degrades to null`() =
        runTest {
            assertNull(classifier(FakeInferenceBackend(floatArrayOf(0.8f, 0.2f))).classify(snapshot()))
            assertNull(classifier(FakeInferenceBackend(floatArrayOf(1.2f, -0.1f, -0.1f))).classify(snapshot()))
        }

    @Test
    fun `empty text is not classified and never calls the backend`() =
        runTest {
            val backend = FakeInferenceBackend(floatArrayOf(0.9f, 0.05f, 0.05f))
            val result = classifier(backend).classify(snapshot(title = null, text = "   "))
            assertNull(result)
            assertEquals(0, backend.calls)
        }

    // Small helpers to assert label+confidence without leaning on float formatting.
    private data class ClassificationResultOf(
        val category: String,
        val confidence: Float,
    )

    private fun com.alarmcontrol.ml.ClassificationResult?.asPair() =
        this?.let { ClassificationResultOf(it.category, it.confidence) }
}
