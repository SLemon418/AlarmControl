package com.alarmcontrol.ml.semantic

/** Local text encoder seam; implementations return one raw logit per semantic label. */
internal fun interface SemanticEncoder {
    fun encode(text: String): FloatArray?
}
