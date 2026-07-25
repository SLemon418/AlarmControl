package com.alarmcontrol.core.filtering

import kotlinx.coroutines.flow.Flow

/** Local SQL-derived suggestions. Nothing is saved or activated without an explicit user action. */
interface RuleSuggestionRepository {
    fun observeSuggestions(sinceMillis: Long): Flow<List<RuleSuggestion>>

    suspend fun dismiss(
        suggestionKey: String,
        dismissedAtMillis: Long,
    )
}

sealed interface RuleSuggestion {
    val key: String

    /** Suggests opening Android's channel settings; AlarmControl never changes another app's channel. */
    data class QuietChannel(
        override val key: String,
        val packageName: String,
        val channelId: String,
        val totalCount: Int,
        val silencedCount: Int,
    ) : RuleSuggestion

    /** Unsaved MONITOR+CANCEL draft based on repeated explicit local promotion corrections. */
    data class MarketingRuleDraft(
        override val key: String,
        val packageName: String,
        val marketingCorrections: Int,
        val totalCorrections: Int,
        val draft: Rule,
    ) : RuleSuggestion
}
