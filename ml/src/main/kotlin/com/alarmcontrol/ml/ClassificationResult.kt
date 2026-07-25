package com.alarmcontrol.ml

/**
 * A confident on-device categorization of a notification (CLAUDE.md §5).
 *
 * @property category the predicted label (e.g. `"promotion"`), fed into rules as `mlCategory`.
 * @property confidence the model's score in `[0, 1]` for [category].
 */
data class ClassificationResult(
    val category: String,
    val confidence: Float,
)
