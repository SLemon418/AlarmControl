package com.alarmcontrol.ml

/** Scheduling priority for the bounded on-device semantic encoder. */
enum class SemanticInferenceUrgency {
    /** Active-rule work that may replace one queued background inference. */
    REALTIME,

    /** Best-effort observation work that never displaces another request. */
    BACKGROUND,
}
