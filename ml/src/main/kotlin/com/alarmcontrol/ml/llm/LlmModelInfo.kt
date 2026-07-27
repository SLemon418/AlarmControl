package com.alarmcontrol.ml.llm

/**
 * Content fingerprint recorded while a user-selected model is copied into app-private storage.
 *
 * The fingerprint proves that the local file has not changed since import; it does not certify who
 * created the model. No model bytes or user data are exposed.
 */
data class LlmModelInfo(
    val sha256: String,
    val sizeBytes: Long,
)
