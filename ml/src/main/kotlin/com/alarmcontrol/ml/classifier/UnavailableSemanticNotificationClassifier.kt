package com.alarmcontrol.ml.classifier

import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.ml.SemanticClassificationResult
import com.alarmcontrol.ml.SemanticNotificationClassifier

/** Rule-only fallback used when the complete, verified semantic payload is unavailable. */
internal object UnavailableSemanticNotificationClassifier : SemanticNotificationClassifier {
    override suspend fun classify(snapshot: NotificationSnapshot): SemanticClassificationResult? = null
}
