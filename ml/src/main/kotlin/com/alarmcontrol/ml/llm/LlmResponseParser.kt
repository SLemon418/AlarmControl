package com.alarmcontrol.ml.llm

import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import org.json.JSONObject

/**
 * Parses the LLM's raw text into a structured [LlmAnalysisResult] (Milestone 4). The model is prompted
 * to emit JSON `{"intent": enum, "confidence": 0..1, "reason": "..."}`; this extracts the first JSON object
 * (tolerating any prose the model wraps around it) and reads the fields defensively. Anything
 * malformed yields [LlmAnalysisResult.UNAVAILABLE] so a confused model can't break the caller (§5).
 *
 * Pure and deterministic — unit-tested with sample model outputs. Uses only the platform `org.json`.
 */
object LlmResponseParser {
    private const val MAX_REASONING_CHARS = 280

    fun parse(raw: String): LlmAnalysisResult {
        val json = extractJsonObject(raw) ?: return LlmAnalysisResult.UNAVAILABLE
        return runCatchingPreservingCancellation {
            val obj = JSONObject(json)
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
    }

    /** The first `{...}` block in [raw], or `null` if there isn't a plausible one. */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1) else null
    }
}
