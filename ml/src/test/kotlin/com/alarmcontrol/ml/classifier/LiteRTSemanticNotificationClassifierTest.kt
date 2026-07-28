package com.alarmcontrol.ml.classifier

import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.ml.SemanticInferenceUrgency
import com.alarmcontrol.ml.semantic.BoundedSemanticInferenceRunner
import com.alarmcontrol.ml.semantic.SemanticConfidenceThresholds
import com.alarmcontrol.ml.semantic.SemanticEncoder
import com.alarmcontrol.ml.semantic.SemanticFeedbackBlender
import com.alarmcontrol.ml.semantic.SemanticInferenceRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LiteRTSemanticNotificationClassifierTest {
    private val labels = SemanticIntent.entries.toList()

    @Test
    fun `high confidence exposes intent logits and trusted signal`() =
        runTest {
            val logits = floatArrayOf(5f, 0f, -1f, -1f, -1f, -1f, -2f)

            val result = classifier(FakeEncoder(logits)).classify(snapshot())

            assertEquals(SemanticIntent.MARKETING, result?.intent)
            assertEquals(SemanticIntent.MARKETING, result?.trustedIntent)
            assertTrue(result?.isConfident == true)
            assertTrue(requireNotNull(result).confidence > 0.9f)
            assertEquals(5f, result.logits.getValue(SemanticIntent.MARKETING))
            assertEquals(labels.toSet(), result.logits.keys)
        }

    @Test
    fun `low confidence becomes ambiguous but remains available for delayed analysis`() =
        runTest {
            val result =
                classifier(FakeEncoder(floatArrayOf(0.2f, 0.1f, 0f, 0f, 0f, 0f, 0f)))
                    .classify(snapshot())

            assertEquals(SemanticIntent.AMBIGUOUS, result?.intent)
            assertNull(result?.trustedIntent)
            assertFalse(requireNotNull(result).isConfident)
            assertTrue(result.confidence in 0f..1f)
        }

    @Test
    fun `explicit ambiguous argmax is never a trusted rule signal`() =
        runTest {
            val result =
                classifier(FakeEncoder(floatArrayOf(-2f, -2f, -2f, -2f, -2f, -2f, 5f)))
                    .classify(snapshot())

            assertEquals(SemanticIntent.AMBIGUOUS, result?.intent)
            assertNull(result?.trustedIntent)
            assertFalse(requireNotNull(result).isConfident)
        }

    @Test
    fun `marketing uses its stricter threshold while other intents use general threshold`() =
        runTest {
            val thresholds = SemanticConfidenceThresholds(general = 0.95f, marketing = 0.99f)
            val marketing =
                classifier(
                    FakeEncoder(floatArrayOf(5f, 0f, -1f, -1f, -1f, -1f, -2f)),
                    confidenceThresholds = thresholds,
                ).classify(snapshot())
            val transactional =
                classifier(
                    FakeEncoder(floatArrayOf(0f, 5f, -1f, -1f, -1f, -1f, -2f)),
                    confidenceThresholds = thresholds,
                ).classify(snapshot())

            assertEquals(SemanticIntent.AMBIGUOUS, marketing?.intent)
            assertNull(marketing?.trustedIntent)
            assertEquals(SemanticIntent.TRANSACTIONAL, transactional?.trustedIntent)
        }

    @Test
    fun `feedback cannot promote a raw nonmarketing prediction into marketing`() =
        runTest {
            val blender =
                SemanticFeedbackBlender { _, _, probabilities ->
                    FloatArray(probabilities.size).also {
                        it[SemanticIntent.MARKETING.ordinal] = 1f
                    }
                }

            val result =
                classifier(
                    FakeEncoder(floatArrayOf(0f, 0f, 5f, -1f, -1f, -1f, -2f)),
                    feedbackBlender = blender,
                ).classify(snapshot())

            assertEquals(SemanticIntent.AMBIGUOUS, result?.intent)
            assertNull(result?.trustedIntent)
            assertFalse(requireNotNull(result).isConfident)
        }

    @Test
    fun `feedback cannot lift raw marketing below the marketing threshold`() =
        runTest {
            val blender =
                SemanticFeedbackBlender { _, _, probabilities ->
                    FloatArray(probabilities.size).also {
                        it[SemanticIntent.MARKETING.ordinal] = 1f
                    }
                }

            val result =
                classifier(
                    FakeEncoder(floatArrayOf(5f, 0f, -1f, -1f, -1f, -1f, -2f)),
                    confidenceThresholds =
                        SemanticConfidenceThresholds(
                            general = 0.95f,
                            marketing = 0.99f,
                        ),
                    feedbackBlender = blender,
                ).classify(snapshot())

            assertEquals(SemanticIntent.AMBIGUOUS, result?.intent)
            assertNull(result?.trustedIntent)
        }

    @Test
    fun `feedback is applied after softmax and can change the semantic intent`() =
        runTest {
            val blender =
                SemanticFeedbackBlender { _, _, probabilities ->
                    FloatArray(probabilities.size).also {
                        it[SemanticIntent.MARKETING.ordinal] = 0.05f
                        it[SemanticIntent.SOCIAL.ordinal] = 0.95f
                    }
                }
            val result =
                classifier(
                    FakeEncoder(floatArrayOf(5f, 0f, -1f, -1f, -1f, -1f, -2f)),
                    feedbackBlender = blender,
                ).classify(snapshot())

            assertEquals(SemanticIntent.SOCIAL, result?.trustedIntent)
            assertEquals(0.95f, result?.confidence)
        }

    @Test
    fun `feedback may change one nonmarketing intent into another`() =
        runTest {
            val blender =
                SemanticFeedbackBlender { _, _, probabilities ->
                    FloatArray(probabilities.size).also {
                        it[SemanticIntent.DELIVERY.ordinal] = 0.04f
                        it[SemanticIntent.SOCIAL.ordinal] = 0.96f
                    }
                }

            val result =
                classifier(
                    FakeEncoder(floatArrayOf(0f, 0f, 5f, -1f, -1f, -1f, -2f)),
                    feedbackBlender = blender,
                ).classify(snapshot())

            assertEquals(SemanticIntent.SOCIAL, result?.trustedIntent)
        }

    @Test
    fun `feedback may veto a confident prediction as ambiguous`() =
        runTest {
            val blender =
                SemanticFeedbackBlender { _, _, probabilities ->
                    FloatArray(probabilities.size).also {
                        it[SemanticIntent.AMBIGUOUS.ordinal] = 1f
                    }
                }

            val result =
                classifier(
                    FakeEncoder(floatArrayOf(5f, 0f, -1f, -1f, -1f, -1f, -2f)),
                    feedbackBlender = blender,
                ).classify(snapshot())

            assertEquals(SemanticIntent.AMBIGUOUS, result?.intent)
            assertNull(result?.trustedIntent)
            assertFalse(requireNotNull(result).isConfident)
        }

    @Test
    fun `invalid model output or label contract fails open`() =
        runTest {
            assertNull(classifier(FakeEncoder(floatArrayOf(1f, 0f))).classify(snapshot()))
            assertNull(
                classifier(
                    FakeEncoder(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, Float.NaN)),
                ).classify(snapshot()),
            )
            assertNull(
                classifier(
                    FakeEncoder(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f)),
                    labels = labels.dropLast(1),
                ).classify(snapshot()),
            )
            assertNull(
                classifier(
                    FakeEncoder(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f)),
                    labels = labels.reversed(),
                ).classify(snapshot()),
            )
        }

    @Test
    fun `backend failures fail open while cancellation propagates`() =
        runTest {
            assertNull(classifier(FakeEncoder(error = IllegalStateException("failed"))).classify(snapshot()))
            assertNull(classifier(FakeEncoder(error = UnsatisfiedLinkError("ABI"))).classify(snapshot()))
            assertNull(classifier(FakeEncoder(error = OutOfMemoryError("native"))).classify(snapshot()))

            val cancellation = CancellationException("cancelled")
            try {
                classifier(FakeEncoder(error = cancellation)).classify(snapshot())
                throw AssertionError("Expected cancellation")
            } catch (actual: CancellationException) {
                assertEquals(cancellation.message, actual.message)
            }
        }

    @Test
    fun `empty content avoids encoder work`() =
        runTest {
            val encoder = FakeEncoder(floatArrayOf(5f, 0f, 0f, 0f, 0f, 0f, 0f))

            assertNull(classifier(encoder).classify(snapshot(title = null, text = "  ")))
            assertEquals(0, encoder.calls)
        }

    @Test
    fun `classifier forwards explicit urgency and keeps one-argument calls realtime`() =
        runTest {
            val runner = RecordingInferenceRunner()
            val classifier =
                classifier(
                    encoder = FakeEncoder(floatArrayOf(5f, 0f, -1f, -1f, -1f, -1f, -2f)),
                    inferenceRunner = runner,
                )

            classifier.classify(snapshot(), SemanticInferenceUrgency.BACKGROUND)
            classifier.classify(snapshot())

            assertEquals(
                listOf(
                    SemanticInferenceUrgency.BACKGROUND,
                    SemanticInferenceUrgency.REALTIME,
                ),
                runner.urgencies,
            )
        }

    @Test
    fun `caller timeout does not wait for a blocking native encoder`() =
        runTest {
            val encoder = BlockingEncoder()
            val classifier =
                classifier(
                    encoder = encoder,
                    inferenceRunner = BoundedSemanticInferenceRunner(),
                )
            val result =
                async {
                    withTimeoutOrNull(350L) {
                        classifier.classify(snapshot())
                    }
                }
            runCurrent()
            assertTrue(encoder.started.await(1, TimeUnit.SECONDS))

            advanceTimeBy(351L)
            runCurrent()

            assertNull(result.await())
            encoder.release.countDown()
            assertTrue(encoder.finished.await(1, TimeUnit.SECONDS))
        }

    @Test
    fun `inference runner keeps one running and one waiting then fails open`() =
        runTest {
            val encoder = BlockingEncoder()
            val runner = BoundedSemanticInferenceRunner()
            val classifier = classifier(encoder, inferenceRunner = runner)
            val first = async { classifier.classify(snapshot(text = "first")) }
            runCurrent()
            assertTrue(encoder.started.await(1, TimeUnit.SECONDS))
            val second = async { classifier.classify(snapshot(text = "second")) }
            runCurrent()

            assertNull(classifier.classify(snapshot(text = "overflow")))

            encoder.release.countDown()
            first.await()
            second.await()
            assertEquals(2, encoder.calls)
        }

    @Test
    fun `cancelling queued inference returns its bounded slot`() =
        runTest {
            val encoder = BlockingEncoder()
            val runner = BoundedSemanticInferenceRunner()
            val classifier = classifier(encoder, inferenceRunner = runner)
            val first = async { classifier.classify(snapshot(text = "first")) }
            runCurrent()
            assertTrue(encoder.started.await(1, TimeUnit.SECONDS))
            val cancelled = async { classifier.classify(snapshot(text = "cancelled")) }
            runCurrent()

            cancelled.cancelAndJoin()
            val replacement = async { classifier.classify(snapshot(text = "replacement")) }
            runCurrent()
            assertNull(classifier.classify(snapshot(text = "overflow")))

            encoder.release.countDown()
            first.await()
            replacement.await()
            assertEquals(2, encoder.calls)
        }

    private fun classifier(
        encoder: SemanticEncoder,
        labels: List<SemanticIntent> = this.labels,
        feedbackBlender: SemanticFeedbackBlender =
            SemanticFeedbackBlender { _, _, probabilities -> probabilities },
        confidenceThresholds: SemanticConfidenceThresholds =
            SemanticConfidenceThresholds(general = 0.95f, marketing = 0.95f),
        inferenceRunner: SemanticInferenceRunner = BoundedSemanticInferenceRunner(),
    ) = LiteRTSemanticNotificationClassifier(
        encoder = encoder,
        labels = labels,
        confidenceThresholds = confidenceThresholds,
        feedbackBlender = feedbackBlender,
        inferenceRunner = inferenceRunner,
    )

    private fun snapshot(
        title: String? = "Weekend sale",
        text: String? = "Save 30 percent today",
    ) = NotificationSnapshot(
        packageName = "com.example",
        title = title,
        text = text,
        category = null,
        channelId = null,
        postedAtMillis = 0L,
        isOngoing = false,
    )

    private class FakeEncoder(
        private val logits: FloatArray? = null,
        private val error: Throwable? = null,
    ) : SemanticEncoder {
        var calls = 0
            private set

        override fun encode(text: String): FloatArray? {
            calls += 1
            error?.let { throw it }
            return logits
        }
    }

    private class RecordingInferenceRunner : SemanticInferenceRunner {
        val urgencies = mutableListOf<SemanticInferenceUrgency>()

        override suspend fun run(inference: () -> FloatArray?): FloatArray? =
            error("Urgency-aware overload was not used")

        override suspend fun run(
            urgency: SemanticInferenceUrgency,
            inference: () -> FloatArray?,
        ): FloatArray? {
            urgencies += urgency
            return inference()
        }
    }

    private class BlockingEncoder : SemanticEncoder {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var calls = 0
            private set

        override fun encode(text: String): FloatArray? {
            calls += 1
            started.countDown()
            try {
                release.await(1, TimeUnit.SECONDS)
                return floatArrayOf(5f, 0f, -1f, -1f, -1f, -1f, -2f)
            } finally {
                finished.countDown()
            }
        }
    }
}
