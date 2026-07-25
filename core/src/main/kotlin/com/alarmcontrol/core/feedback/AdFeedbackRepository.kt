package com.alarmcontrol.core.feedback

import com.alarmcontrol.core.filtering.SemanticIntent
import kotlinx.coroutines.flow.Flow

/**
 * Local, content-free LLM ad observations and explicit user verdicts. Neither prompts, notification
 * text, nor model reasoning are persisted; only package-level metadata and numeric output remain.
 */
interface AdFeedbackRepository {
    suspend fun recordObservation(observation: AdObservation)

    suspend fun recordCorrection(
        notificationEventId: String,
        correctedIntent: SemanticIntent,
    )

    suspend fun recordCorrection(
        notificationEventId: String,
        correctedIsAdvertisement: Boolean,
    ) = recordCorrection(
        notificationEventId,
        if (correctedIsAdvertisement) SemanticIntent.MARKETING else SemanticIntent.TRANSACTIONAL,
    )

    /** Latest observation keyed by activity event id, for the local activity feed. */
    fun observeByEvent(): Flow<Map<String, AdObservation>>

    /** Package-level explicit verdict counts, consumed as a hot local ML prior. */
    fun observeAllFeedbackCounts(): Flow<Map<String, AdFeedbackCounts>>
}

data class AdObservation(
    val notificationEventId: String,
    val packageName: String,
    val predictedIsAdvertisement: Boolean,
    val predictedIntent: SemanticIntent =
        if (predictedIsAdvertisement) SemanticIntent.MARKETING else SemanticIntent.TRANSACTIONAL,
    val confidenceScore: Float,
    val correctedIsAdvertisement: Boolean? = null,
    val correctedIntent: SemanticIntent? =
        correctedIsAdvertisement?.let {
            if (it) SemanticIntent.MARKETING else SemanticIntent.TRANSACTIONAL
        },
    val analyzedAtMillis: Long,
)

data class AdFeedbackCounts(
    val advertisement: Int = 0,
    val transactional: Int = 0,
    val byIntent: Map<SemanticIntent, Int> =
        mapOf(
            SemanticIntent.MARKETING to advertisement,
            SemanticIntent.TRANSACTIONAL to transactional,
        ),
) {
    val total: Int get() = byIntent.values.sum()
}
