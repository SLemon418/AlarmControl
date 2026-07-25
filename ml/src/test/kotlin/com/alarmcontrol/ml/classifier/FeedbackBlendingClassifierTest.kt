package com.alarmcontrol.ml.classifier

import com.alarmcontrol.core.feedback.CategoryFeedback
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.ml.feature.BagOfWordsFeatureExtractor
import com.alarmcontrol.ml.feedback.FakeFeedbackRepository
import com.alarmcontrol.ml.feedback.RepositoryFeedbackBlender
import com.alarmcontrol.ml.inference.InferenceBackend
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** End-to-end within :ml: the same notification re-categorizes after the user corrects it (§5). */
class FeedbackBlendingClassifierTest {
    private val labels = listOf("promotion", "social", "news", "alarm")
    private val packageName = "com.example.shop"

    private fun snapshot() =
        NotificationSnapshot(
            packageName = packageName,
            title = "Flash sale",
            text = "50% off today",
            category = null,
            channelId = null,
            postedAtMillis = 0L,
            isOngoing = false,
        )

    @Test
    fun `category adapts to the user's corrections after feedback`() =
        runTest {
            val backend = mockk<InferenceBackend>()
            every { backend.run(any()) } returns floatArrayOf(0.7f, 0.2f, 0.1f, 0f) // model says promotion
            val feedback = FakeFeedbackRepository()
            val classifier =
                LiteRTNotificationClassifier(
                    featureExtractor = BagOfWordsFeatureExtractor(listOf("sale", "off")),
                    backend = backend,
                    labels = labels,
                    confidenceThreshold = 0.6f,
                    feedbackBlender = RepositoryFeedbackBlender(feedback.countsByPackage),
                )

            // Before any feedback, the model decision stands.
            assertEquals("promotion", classifier.classify(snapshot())?.category)

            // The user repeatedly recategorizes notifications from this package as "social".
            repeat(20) {
                feedback.recordCorrection(
                    CategoryFeedback(
                        packageName = packageName,
                        predictedLabel = "promotion",
                        correctedLabel = "social",
                        recordedAtMillis = 0L,
                    ),
                )
            }

            // The same notification now adapts to the learned preference.
            assertEquals("social", classifier.classify(snapshot())?.category)
        }
}
