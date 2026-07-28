package com.alarmcontrol.ml

import com.alarmcontrol.core.filtering.SemanticIntent

/**
 * Non-generative output of the bundled seven-way semantic encoder.
 *
 * [logits] are the model's raw outputs, keyed by the stable [SemanticIntent] contract.
 * [confidence] is the top probability after the local package-feedback prior is applied. A result
 * may still be returned with [isConfident] false so callers can queue best-effort background
 * analysis without treating an uncertain encoder prediction as a rule signal.
 */
data class SemanticClassificationResult(
    val intent: SemanticIntent,
    val logits: Map<SemanticIntent, Float>,
    val confidence: Float,
    val isConfident: Boolean,
) {
    /** Intent safe to expose to the rule engine, or `null` when the encoder is uncertain. */
    val trustedIntent: SemanticIntent?
        get() = intent.takeIf { isConfident && it != SemanticIntent.AMBIGUOUS }
}
