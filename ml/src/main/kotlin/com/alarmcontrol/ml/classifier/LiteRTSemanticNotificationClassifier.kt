package com.alarmcontrol.ml.classifier

import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.ml.SemanticClassificationResult
import com.alarmcontrol.ml.SemanticInferenceUrgency
import com.alarmcontrol.ml.SemanticNotificationClassifier
import com.alarmcontrol.ml.semantic.BoundedSemanticInferenceRunner
import com.alarmcontrol.ml.semantic.NoOpSemanticFeedbackBlender
import com.alarmcontrol.ml.semantic.SemanticConfidenceThresholds
import com.alarmcontrol.ml.semantic.SemanticEncoder
import com.alarmcontrol.ml.semantic.SemanticFeedbackBlender
import com.alarmcontrol.ml.semantic.SemanticInferenceRunner
import kotlinx.coroutines.CancellationException
import kotlin.math.exp

/**
 * Converts a notification into encoder logits, applies the local shrinkage prior, and exposes only
 * a confident seven-way result to rules. Native/runtime failures return `null`.
 */
internal class LiteRTSemanticNotificationClassifier(
    private val encoder: SemanticEncoder,
    private val labels: List<SemanticIntent>,
    private val confidenceThresholds: SemanticConfidenceThresholds,
    private val feedbackBlender: SemanticFeedbackBlender = NoOpSemanticFeedbackBlender,
    private val inferenceRunner: SemanticInferenceRunner = BoundedSemanticInferenceRunner(),
) : SemanticNotificationClassifier {
    override suspend fun classify(snapshot: NotificationSnapshot): SemanticClassificationResult? =
        classify(snapshot, SemanticInferenceUrgency.REALTIME)

    override suspend fun classify(
        snapshot: NotificationSnapshot,
        urgency: SemanticInferenceUrgency,
    ): SemanticClassificationResult? =
        try {
            classifySafely(snapshot, urgency)
        } catch (error: CancellationException) {
            throw error
        } catch (_: LinkageError) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }

    private suspend fun classifySafely(
        snapshot: NotificationSnapshot,
        urgency: SemanticInferenceUrgency,
    ): SemanticClassificationResult? {
        val content = snapshot.classifiableText() ?: return null
        val rawLogits = inferenceRunner.run(urgency) { encoder.encode(content) } ?: return null
        if (!rawLogits.isCompatibleLogitVector()) return null

        val probabilities = rawLogits.softmax() ?: return null
        val rawBestIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: return null
        val rawIntent = labels.getOrNull(rawBestIndex) ?: return null
        val rawConfidence = probabilities[rawBestIndex]
        val adjusted = feedbackBlender.blend(snapshot.packageName, labels, probabilities)
        if (!adjusted.isCompatibleProbabilityVector()) return null
        val bestIndex = adjusted.indices.maxByOrNull { adjusted[it] } ?: return null
        val predictedIntent = labels.getOrNull(bestIndex) ?: return null
        val confidence = adjusted[bestIndex]
        val requiredThreshold =
            if (predictedIntent == SemanticIntent.MARKETING) {
                confidenceThresholds.marketing
            } else {
                confidenceThresholds.general
            }
        val safeMarketingPrediction =
            predictedIntent != SemanticIntent.MARKETING ||
                (
                    rawIntent == SemanticIntent.MARKETING &&
                        rawConfidence >= confidenceThresholds.marketing
                )
        val confident =
            predictedIntent != SemanticIntent.AMBIGUOUS &&
                confidence >= requiredThreshold &&
                safeMarketingPrediction
        return SemanticClassificationResult(
            intent = if (confident) predictedIntent else SemanticIntent.AMBIGUOUS,
            logits = labels.indices.associate { index -> labels[index] to rawLogits[index] },
            confidence = confidence,
            isConfident = confident,
        )
    }

    private fun FloatArray.isCompatibleLogitVector(): Boolean =
        labels == SemanticIntent.entries.toList() &&
            size == labels.size &&
            all(Float::isFinite)

    private fun FloatArray.isCompatibleProbabilityVector(): Boolean =
        size == labels.size &&
            isNotEmpty() &&
            all { it.isFinite() && it in 0f..1f } &&
            sum() in PROBABILITY_SUM_RANGE

    private fun FloatArray.softmax(): FloatArray? {
        val maximum = maxOrNull() ?: return null
        val exponentials = FloatArray(size) { index -> exp((this[index] - maximum).toDouble()).toFloat() }
        val denominator = exponentials.sum()
        if (!denominator.isFinite() || denominator <= 0f) return null
        return FloatArray(size) { index -> exponentials[index] / denominator }
    }

    private fun NotificationSnapshot.classifiableText(): String? =
        listOfNotNull(title, text).joinToString(separator = " ").trim().ifBlank { null }

    private companion object {
        val PROBABILITY_SUM_RANGE = 0.999f..1.001f
    }
}
