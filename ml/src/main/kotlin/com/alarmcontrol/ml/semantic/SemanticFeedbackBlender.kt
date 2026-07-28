package com.alarmcontrol.ml.semantic

import com.alarmcontrol.core.feedback.AdFeedbackCounts
import com.alarmcontrol.core.feedback.AdFeedbackRepository
import com.alarmcontrol.core.filtering.SemanticIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Applies an optional local package-level prior to encoder probabilities. */
internal fun interface SemanticFeedbackBlender {
    fun blend(
        packageName: String,
        labels: List<SemanticIntent>,
        probabilities: FloatArray,
    ): FloatArray
}

/** Identity behavior used when no feedback source is configured. */
internal object NoOpSemanticFeedbackBlender : SemanticFeedbackBlender {
    override fun blend(
        packageName: String,
        labels: List<SemanticIntent>,
        probabilities: FloatArray,
    ): FloatArray = probabilities
}

/** Seven-way shrinkage prior built only from content-free, on-device user corrections. */
internal class RepositorySemanticFeedbackBlender(
    private val countsByPackage: StateFlow<Map<String, AdFeedbackCounts>>,
    private val priorStrength: Float = DEFAULT_PRIOR_STRENGTH,
) : SemanticFeedbackBlender {
    override fun blend(
        packageName: String,
        labels: List<SemanticIntent>,
        probabilities: FloatArray,
    ): FloatArray {
        if (labels.size != probabilities.size) return probabilities
        val counts = countsByPackage.value[packageName] ?: return probabilities
        if (counts.total <= 0) return probabilities

        val beta = counts.total / (counts.total + priorStrength)
        return FloatArray(probabilities.size) { index ->
            val prior = (counts.byIntent[labels[index]] ?: 0) / counts.total.toFloat()
            (1f - beta) * probabilities[index] + beta * prior
        }
    }

    companion object {
        private const val DEFAULT_PRIOR_STRENGTH = 3f

        fun from(
            repository: AdFeedbackRepository,
            scope: CoroutineScope,
        ): RepositorySemanticFeedbackBlender =
            RepositorySemanticFeedbackBlender(
                repository
                    .observeAllFeedbackCounts()
                    .stateIn(scope, SharingStarted.Eagerly, emptyMap()),
            )
    }
}
