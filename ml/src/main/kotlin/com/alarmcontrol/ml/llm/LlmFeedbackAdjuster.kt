package com.alarmcontrol.ml.llm

import com.alarmcontrol.core.feedback.AdFeedbackCounts
import com.alarmcontrol.core.feedback.AdFeedbackRepository
import com.alarmcontrol.core.filtering.SemanticIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

internal fun interface LlmFeedbackAdjuster {
    fun adjust(
        packageName: String,
        result: LlmAnalysisResult,
    ): LlmAnalysisResult
}

/** Lightweight seven-class shrinkage prior over explicit local feedback; no runtime retraining. */
internal class RepositoryLlmFeedbackAdjuster(
    private val countsByPackage: StateFlow<Map<String, AdFeedbackCounts>>,
    private val priorStrength: Float = DEFAULT_PRIOR_STRENGTH,
) : LlmFeedbackAdjuster {
    override fun adjust(
        packageName: String,
        result: LlmAnalysisResult,
    ): LlmAnalysisResult {
        if (result.confidenceScore <= 0f) return result
        val counts = countsByPackage.value[packageName] ?: return result
        if (counts.total == 0) return result

        val beta = counts.total / (counts.total + priorStrength)
        val remainingProbability =
            (1f - result.confidenceScore) / (SemanticIntent.entries.size - 1)
        val blended =
            SemanticIntent.entries.associateWith { intent ->
                val raw = if (intent == result.intent) result.confidenceScore else remainingProbability
                val localPrior = (counts.byIntent[intent] ?: 0) / counts.total.toFloat()
                (1f - beta) * raw + beta * localPrior
            }
        val intent = blended.maxBy { it.value }.key
        return LlmAnalysisResult.of(
            intent = intent,
            confidenceScore = blended.getValue(intent),
            reasoning = result.reasoning,
        )
    }

    companion object {
        private const val DEFAULT_PRIOR_STRENGTH = 3f

        fun from(
            repository: AdFeedbackRepository,
            scope: CoroutineScope,
        ): RepositoryLlmFeedbackAdjuster =
            RepositoryLlmFeedbackAdjuster(
                repository
                    .observeAllFeedbackCounts()
                    .stateIn(scope, SharingStarted.Eagerly, emptyMap()),
            )
    }
}
