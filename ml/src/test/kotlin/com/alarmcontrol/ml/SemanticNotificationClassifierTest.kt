package com.alarmcontrol.ml

import com.alarmcontrol.core.filtering.NotificationSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SemanticNotificationClassifierTest {
    @Test
    fun `urgency overload preserves one-argument implementation compatibility`() =
        runTest {
            val classifier = OneArgumentClassifier()

            assertNull(
                classifier.classify(
                    snapshot = snapshot(),
                    urgency = SemanticInferenceUrgency.REALTIME,
                ),
            )

            assertEquals(1, classifier.calls)
        }

    private fun snapshot() =
        NotificationSnapshot(
            packageName = "com.example",
            title = "title",
            text = "text",
            category = null,
            channelId = null,
            postedAtMillis = 0L,
            isOngoing = false,
        )

    private class OneArgumentClassifier : SemanticNotificationClassifier {
        var calls = 0
            private set

        override suspend fun classify(snapshot: NotificationSnapshot): SemanticClassificationResult? {
            calls += 1
            return null
        }
    }
}
