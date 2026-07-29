package com.alarmcontrol.ml.llm

import com.alarmcontrol.core.filtering.SemanticIntent

/**
 * Structured output of an on-device LLM context analysis (Milestone 4). [intent] is the seven-way
 * semantic verdict, [confidenceScore] is a bounded model-reported score, and [reasoning] is a short
 * in-memory justification. The score is not a calibrated probability unless the exact model has
 * been evaluated and calibrated separately. Reasoning must never be persisted, backed up, or logged.
 *
 * Pure data — no Android or LLM-runtime types — so the rules engine and UI consume it without
 * depending on any inference library (CLAUDE.md §4/§5).
 */
data class LlmAnalysisResult(
    val intent: SemanticIntent,
    val confidenceScore: Float,
    val reasoning: String,
) {
    /** Compatibility view: advertising is exactly the MARKETING semantic class. */
    val isAdvertisement: Boolean get() = intent.isAdvertisement

    companion object {
        /** A safe, non-committal result for when analysis is unavailable or low-confidence (§5). */
        val UNAVAILABLE =
            LlmAnalysisResult(
                intent = SemanticIntent.AMBIGUOUS,
                confidenceScore = 0f,
                reasoning = "",
            )

        /**
         * Builds a result with [confidenceScore] normalized into `0f‥1f` (NaN -> 0) and [reasoning]
         * trimmed, so a model that emits an out-of-range or non-finite score, or padded text, can't
         * violate the contract.
         */
        fun of(
            intent: SemanticIntent,
            confidenceScore: Float,
            reasoning: String,
        ): LlmAnalysisResult =
            LlmAnalysisResult(
                intent = intent,
                confidenceScore = if (confidenceScore.isFinite()) confidenceScore.coerceIn(0f, 1f) else 0f,
                reasoning = reasoning.trim(),
            )

        /** Source-compatible bridge for the previous binary ad contract. */
        fun of(
            isAdvertisement: Boolean,
            confidenceScore: Float,
            reasoning: String,
        ): LlmAnalysisResult =
            of(
                intent = if (isAdvertisement) SemanticIntent.MARKETING else SemanticIntent.TRANSACTIONAL,
                confidenceScore = confidenceScore,
                reasoning = reasoning,
            )
    }
}
