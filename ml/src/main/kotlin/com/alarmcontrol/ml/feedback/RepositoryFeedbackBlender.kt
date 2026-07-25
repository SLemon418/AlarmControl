package com.alarmcontrol.ml.feedback

import com.alarmcontrol.core.feedback.FeedbackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Blends the model's scores with a per-package prior built from the user's correction history — a
 * lightweight, incremental, on-device weight map, not retraining (CLAUDE.md §5). No gradients, no
 * backprop, nothing leaves the device (§1): it reads SQL-aggregated counts and mixes them in.
 *
 * Influence grows with the amount of feedback via shrinkage `β = n / (n + priorStrength)` where `n`
 * is the total number of corrections for the package. So one stray correction barely moves a
 * confident model, while consistent feedback eventually dominates:
 *
 *     blended[i] = (1 - β) * score[i] + β * (corrections_for_label[i] / n)
 *
 * With no corrections (`n == 0`) the scores are returned untouched.
 */
internal class RepositoryFeedbackBlender(
    private val countsByPackage: StateFlow<Map<String, Map<String, Int>>>,
    private val priorStrength: Float = DEFAULT_PRIOR_STRENGTH,
) : FeedbackBlender {
    override suspend fun blend(
        packageName: String,
        labels: List<String>,
        scores: FloatArray,
    ): FloatArray {
        // A model whose output width doesn't match the labels asset can't be blended by label —
        // skip rather than index out of bounds, so the classifier still degrades gracefully (§5).
        if (labels.size != scores.size) return scores

        val counts = countsByPackage.value[packageName].orEmpty()
        val total = counts.values.sum()
        if (total == 0) return scores

        val beta = total / (total + priorStrength)
        return FloatArray(scores.size) { i ->
            val prior = (counts[labels[i]] ?: 0) / total.toFloat()
            (1f - beta) * scores[i] + beta * prior
        }
    }

    companion object {
        private const val DEFAULT_PRIOR_STRENGTH = 3f

        fun from(
            feedbackRepository: FeedbackRepository,
            scope: CoroutineScope,
        ): RepositoryFeedbackBlender =
            RepositoryFeedbackBlender(
                feedbackRepository
                    .observeAllLabelCounts()
                    .stateIn(scope, SharingStarted.Eagerly, emptyMap()),
            )
    }
}
