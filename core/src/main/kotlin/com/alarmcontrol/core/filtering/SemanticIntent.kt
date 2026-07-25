package com.alarmcontrol.core.filtering

/** Privacy-safe semantic intent emitted by the optional local LLM. */
enum class SemanticIntent {
    MARKETING,
    TRANSACTIONAL,
    SECURITY,
    DELIVERY,
    SOCIAL,
    OTHER,
    AMBIGUOUS,
    ;

    val isAdvertisement: Boolean get() = this == MARKETING
}
