package com.alarmcontrol.ml.classifier

import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.ml.ClassificationResult
import com.alarmcontrol.ml.NotificationClassifier
import com.alarmcontrol.ml.feature.FeatureExtractor
import com.alarmcontrol.ml.feedback.FeedbackBlender
import com.alarmcontrol.ml.feedback.NoOpFeedbackBlender
import com.alarmcontrol.ml.inference.InferenceBackend
import com.alarmcontrol.ml.semantic.BoundedSemanticInferenceRunner
import com.alarmcontrol.ml.semantic.SemanticInferenceRunner
import kotlinx.coroutines.CancellationException

/**
 * Combines feature extraction, on-device inference, and a confidence gate into a
 * [NotificationClassifier]. Every failure mode returns `null` so the caller degrades to rule-only
 * filtering (CLAUDE.md §5): no text, unavailable model, backend error, or below-threshold score.
 *
 * The decision logic is pure and deterministic given an [InferenceBackend] and [FeedbackBlender], so
 * it is unit-tested with a fake backend across the confidence boundary (§9). Raw model scores pass
 * through [feedbackBlender] before argmax so the user's stored corrections can bias the result (§5);
 * the default blender is a no-op, preserving model-only behavior.
 */
internal class LiteRTNotificationClassifier(
    private val featureExtractor: FeatureExtractor,
    private val backend: InferenceBackend,
    private val labels: List<String>,
    private val confidenceThreshold: Float,
    private val feedbackBlender: FeedbackBlender = NoOpFeedbackBlender,
    private val inferenceRunner: SemanticInferenceRunner = BoundedSemanticInferenceRunner(),
) : NotificationClassifier {
    override suspend fun classify(snapshot: NotificationSnapshot): ClassificationResult? {
        val content = snapshot.classifiableText() ?: return null
        val rawScores =
            try {
                inferenceRunner.run {
                    backend.run(featureExtractor.extract(content))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: LinkageError) {
                null
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: Exception) {
                null
            } ?: return null
        if (!rawScores.isCompatibleProbabilityVector()) return null
        // Fold in the user's local corrections for this package (no-op when there is none, §5).
        val scores = feedbackBlender.blend(snapshot.packageName, labels, rawScores)
        if (!scores.isCompatibleProbabilityVector()) return null

        val bestIndex = scores.indices.maxByOrNull { scores[it] } ?: return null
        val confidence = scores[bestIndex]
        if (!confidence.isFinite() || confidence < confidenceThreshold) return null

        val label = labels.getOrNull(bestIndex) ?: return null
        return ClassificationResult(category = label, confidence = confidence)
    }

    private fun FloatArray.isCompatibleProbabilityVector(): Boolean =
        size == labels.size && isNotEmpty() && all { it.isFinite() && it in 0f..1f }

    private fun NotificationSnapshot.classifiableText(): String? =
        listOfNotNull(title, text).joinToString(separator = " ").trim().ifBlank { null }
}
