package com.alarmcontrol.core.filtering

import kotlinx.coroutines.flow.Flow

/** Optional notification text supplied separately from the content-free decision record. */
data class NotificationContent(
    val title: String?,
    val text: String?,
)

/** Result of loading one locally encrypted notification payload. */
sealed interface NotificationContentState {
    data class Available(
        val title: String?,
        val text: String?,
    ) : NotificationContentState

    data object NotStored : NotificationContentState

    data object Expired : NotificationContentState

    data object Unreadable : NotificationContentState
}

/** One history row plus content that is decrypted only for the detail screen. */
data class NotificationEventDetail(
    val event: NotificationEvent,
    val content: NotificationContentState,
)

/** A locally observed app/channel offered by the guided rule editor. */
data class NotificationSource(
    val packageName: String,
    val channelId: String?,
    val channelName: String?,
    val eventCount: Int,
    val lastSeenMillis: Long,
)

enum class HistoryActionFilter {
    ALL,
    CANCELLED,
    SNOOZED,
    LOGGED,
    KEPT,
}

/** SQL-backed filters for the bounded local activity history. */
data class NotificationHistoryQuery(
    val startMillis: Long,
    val endMillis: Long,
    val search: String = "",
    val packageName: String? = null,
    val channelId: String? = null,
    val category: String? = null,
    val ruleId: String? = null,
    val action: HistoryActionFilter = HistoryActionFilter.ALL,
    val includeExcluded: Boolean = true,
    val limit: Int = 100,
)

data class NotificationHistoryPage(
    val items: List<NotificationEvent>,
    val totalCount: Int,
)

/** Actual locally retained coverage so the UI never implies unlimited raw history. */
data class NotificationHistoryCoverage(
    val totalEvents: Int,
    val oldestPostedAtMillis: Long?,
    val newestPostedAtMillis: Long?,
    val eventsWithTrace: Int,
    val eventLimit: Int = 10_000,
    val traceEventLimit: Int = 1_000,
) {
    val eventLimitReached: Boolean get() = totalEvents >= eventLimit
    val traceCoveragePartial: Boolean get() = totalEvents > eventsWithTrace
}

/**
 * Read-only history surface separated from the listener's hot write contract. Implementations must
 * decrypt content only for [getDetail], never while producing list pages.
 */
interface NotificationHistoryRepository {
    fun observeHistory(query: NotificationHistoryQuery): Flow<NotificationHistoryPage>

    fun observeSources(limit: Int = 100): Flow<List<NotificationSource>>

    fun observeCoverage(): Flow<NotificationHistoryCoverage>

    suspend fun getDetail(eventId: String): NotificationEventDetail?

    suspend fun recentSimulationSamples(
        packageName: String?,
        limit: Int = 20,
    ): List<NotificationEventDetail>
}
