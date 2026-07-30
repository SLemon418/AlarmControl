package com.alarmcontrol.ml.llm

import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Parses the LLM's raw text into a structured [LlmAnalysisResult] (Milestone 4). The model is prompted
 * to emit exactly one JSON object `{"intent": enum, "confidence": 0..1, "reason": "..."}` with no
 * prose or Markdown wrapper. Anything malformed or contradictory yields
 * [LlmAnalysisResult.UNAVAILABLE] so a confused model can't break the caller (§5).
 *
 * Pure and deterministic — unit-tested with sample model outputs. Uses only the platform `org.json`.
 */
object LlmResponseParser {
    private const val MAX_REASONING_CHARS = 280

    fun parse(raw: String): LlmAnalysisResult =
        runCatchingPreservingCancellation {
            val parser = JSONTokener(raw.trim())
            val obj = parser.nextValue() as? JSONObject ?: error("Expected one JSON object")
            require(parser.nextClean() == END_OF_INPUT) { "Unexpected content after JSON object" }
            val fields = obj.keys().asSequence().toSet()
            require(fields == REQUIRED_FIELDS || fields == REQUIRED_FIELDS_WITH_AD) {
                "Unexpected response fields"
            }
            val rawIntent = obj.get("intent") as? String ?: error("Missing intent")
            val intent = SemanticIntent.entries.singleOrNull { it.name == rawIntent } ?: error("Unknown intent")
            val confidence = (obj.get("confidence") as? Number)?.toDouble() ?: error("Missing confidence")
            val reasoning = obj.get("reason") as? String ?: error("Missing reason")
            require(confidence.isFinite() && confidence in 0.0..1.0) { "Invalid confidence" }
            if (obj.has("ad")) {
                val advertisement = obj.get("ad") as? Boolean ?: error("Invalid ad compatibility field")
                require(advertisement == intent.isAdvertisement) { "Contradictory verdict" }
            }
            LlmAnalysisResult.of(
                intent = intent,
                confidenceScore = confidence.toFloat(),
                reasoning = reasoning.take(MAX_REASONING_CHARS),
            )
        }.getOrDefault(LlmAnalysisResult.UNAVAILABLE)

    private const val END_OF_INPUT = '\u0000'
    private val REQUIRED_FIELDS = setOf("intent", "confidence", "reason")
    private val REQUIRED_FIELDS_WITH_AD = REQUIRED_FIELDS + "ad"
}
