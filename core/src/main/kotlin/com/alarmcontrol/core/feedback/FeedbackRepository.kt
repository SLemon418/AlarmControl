package com.alarmcontrol.core.feedback

import kotlinx.coroutines.flow.Flow

/**
 * Local store of user category corrections (CLAUDE.md §5). The interface lives in `:core` so the
 * Room-backed implementation (`:data`) and the classifier that consumes the signal (`:ml`) can both
 * depend on it without depending on each other.
 *
 * The learning signal is a pure SQL aggregation — per-label correction counts — not a trained model
 * (§5). Nothing here is exported off the device (§1).
 */
interface FeedbackRepository {
    /** Persists one user correction. */
    suspend fun recordCorrection(feedback: CategoryFeedback)

    /**
     * Streams effective label assignments for [packageName]. Re-correcting one linked activity row
     * replaces its previous learning vote; unlinked legacy feedback remains additive. Empty until
     * corrections exist, so consumers degrade gracefully to model-only behavior (§5).
     */
    fun observeLabelCounts(packageName: String): Flow<Map<String, Int>>

    /**
     * Streams the complete package -> label-count map for the process-lifetime inference cache.
     * This prevents one Room query per incoming notification.
     */
    fun observeAllLabelCounts(): Flow<Map<String, Map<String, Int>>>

    /** Latest persisted corrected label for each activity-log event. */
    fun observeEventCorrections(): Flow<Map<String, String>>
}
