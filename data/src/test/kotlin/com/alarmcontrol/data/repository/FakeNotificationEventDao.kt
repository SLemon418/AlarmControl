package com.alarmcontrol.data.repository

import com.alarmcontrol.data.db.dao.ActionCountRow
import com.alarmcontrol.data.db.dao.EventTimeBoundsRow
import com.alarmcontrol.data.db.dao.HistoryCoverageRow
import com.alarmcontrol.data.db.dao.NotificationEventDao
import com.alarmcontrol.data.db.dao.NotificationSourceRow
import com.alarmcontrol.data.db.dao.PackageCount
import com.alarmcontrol.data.db.dao.RateHistoryRow
import com.alarmcontrol.data.db.entity.EncryptedNotificationContentEntity
import com.alarmcontrol.data.db.entity.NotificationDecisionTraceEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.db.relation.NotificationEventDetailRelation
import com.alarmcontrol.data.db.relation.NotificationEventWithTrace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [NotificationEventDao] for JVM unit tests. Reactive: writes bump [revision] so observed
 * queries re-emit, mirroring Room. [inserted] is exposed for assertions.
 */
class FakeNotificationEventDao : NotificationEventDao {
    private val events = mutableListOf<NotificationEventEntity>()
    private val traces = mutableListOf<NotificationDecisionTraceEntity>()
    private val encryptedContents = mutableListOf<EncryptedNotificationContentEntity>()
    val inserted: List<NotificationEventEntity> get() = events
    private var nextId = 1L
    private val revision = MutableStateFlow(0)

    override suspend fun countAll(): Int = events.size

    override suspend fun insert(event: NotificationEventEntity): Long {
        val id = nextId++
        events += event.copy(id = id)
        revision.value++
        return id
    }

    override suspend fun insertTrace(rows: List<NotificationDecisionTraceEntity>) {
        traces += rows
        revision.value++
    }

    override suspend fun insertEncryptedContent(content: EncryptedNotificationContentEntity) {
        encryptedContents.removeAll { it.eventId == content.eventId }
        encryptedContents += content
        revision.value++
    }

    override fun observeRecent(limit: Int): Flow<List<NotificationEventWithTrace>> =
        revision.map {
            events.sortedByDescending { it.recordedAtMillis }.take(limit).map { event ->
                NotificationEventWithTrace(event, traces.filter { it.eventId == event.id })
            }
        }

    override fun observeHistory(
        startMillis: Long,
        endMillis: Long,
        search: String,
        packageName: String?,
        channelId: String?,
        category: String?,
        ruleId: Long?,
        action: StoredRuleAction?,
        includeExcluded: Boolean,
        limit: Int,
    ): Flow<List<NotificationEventWithTrace>> =
        revision.map {
            filteredHistory(
                startMillis,
                endMillis,
                search,
                packageName,
                channelId,
                category,
                ruleId,
                action,
                includeExcluded,
            ).take(limit)
                .map(::withTrace)
        }

    override fun observeHistoryCount(
        startMillis: Long,
        endMillis: Long,
        search: String,
        packageName: String?,
        channelId: String?,
        category: String?,
        ruleId: Long?,
        action: StoredRuleAction?,
        includeExcluded: Boolean,
    ): Flow<Int> =
        revision.map {
            filteredHistory(
                startMillis,
                endMillis,
                search,
                packageName,
                channelId,
                category,
                ruleId,
                action,
                includeExcluded,
            ).size
        }

    override suspend fun getDetail(id: Long): NotificationEventDetailRelation? =
        events.firstOrNull { it.id == id }?.let(::withDetail)

    override suspend fun getSimulationSamples(
        packageName: String?,
        limit: Int,
    ): List<NotificationEventDetailRelation> =
        events
            .filter { packageName == null || it.packageName == packageName }
            .sortedByDescending(NotificationEventEntity::recordedAtMillis)
            .take(limit)
            .map(::withDetail)

    override fun observeSources(limit: Int): Flow<List<NotificationSourceRow>> =
        revision.map {
            events
                .groupBy { it.packageName to it.channelId }
                .map { (key, rows) ->
                    NotificationSourceRow(
                        packageName = key.first,
                        channelId = key.second,
                        channelName = rows.mapNotNull { it.channelName }.lastOrNull(),
                        eventCount = rows.size,
                        lastSeenMillis = rows.maxOf(NotificationEventEntity::recordedAtMillis),
                    )
                }.sortedByDescending(NotificationSourceRow::lastSeenMillis)
                .take(limit)
        }

    override fun observeCoverage(): Flow<HistoryCoverageRow> =
        revision.map {
            HistoryCoverageRow(
                totalEventCount = events.size,
                oldestPostedAtMillis = events.minOfOrNull(NotificationEventEntity::postedAtMillis),
                newestPostedAtMillis = events.maxOfOrNull(NotificationEventEntity::postedAtMillis),
                traceEventCount = traces.map(NotificationDecisionTraceEntity::eventId).distinct().size,
                traceEligibleEventCount =
                    events.count { it.matchedRuleId != null || it.monitoredRuleId != null },
            )
        }

    override fun countByActionSince(
        action: StoredRuleAction,
        sinceMillis: Long,
    ): Flow<Int> =
        revision.map {
            events.count { it.action == action && it.postedAtMillis >= sinceMillis && !it.undone }
        }

    override fun observeActionCountsSince(sinceMillis: Long): Flow<List<ActionCountRow>> =
        revision.map {
            events
                .filter { event -> event.postedAtMillis >= sinceMillis && !event.undone }
                .groupingBy(NotificationEventEntity::action)
                .eachCount()
                .map { (action, count) -> ActionCountRow(action, count) }
        }

    override fun observeActionCountsForDay(
        epochDay: Long,
        legacyStartMillis: Long,
        legacyEndMillis: Long,
    ): Flow<List<ActionCountRow>> =
        revision.map {
            events
                .filter { event ->
                    !event.undone &&
                        if (event.postedEpochDay != null) {
                            event.postedEpochDay == epochDay
                        } else {
                            event.postedAtMillis >= legacyStartMillis &&
                                event.postedAtMillis < legacyEndMillis
                        }
                }.groupingBy(NotificationEventEntity::action)
                .eachCount()
                .map { (action, count) -> ActionCountRow(action, count) }
        }

    override suspend fun markUndone(id: Long) {
        val index = events.indexOfFirst { it.id == id }
        if (index >= 0) events[index] = events[index].copy(undone = true)
        revision.value++
    }

    override suspend fun deleteOlderThan(cutoffMillis: Long): Int {
        val before = events.size
        events.removeAll { it.postedAtMillis < cutoffMillis }
        traces.removeAll { row -> events.none { it.id == row.eventId } }
        encryptedContents.removeAll { row -> events.none { it.id == row.eventId } }
        revision.value++
        return before - events.size
    }

    override suspend fun deleteAll(): Int {
        val count = events.size
        events.clear()
        traces.clear()
        encryptedContents.clear()
        revision.value++
        return count
    }

    override suspend fun deleteForPackageChannels(
        packageName: String,
        channelIds: List<String>,
    ): Int {
        val removedIds =
            events
                .filter { it.packageName == packageName && it.channelId in channelIds }
                .mapTo(mutableSetOf(), NotificationEventEntity::id)
        events.removeAll { it.id in removedIds }
        traces.removeAll { it.eventId in removedIds }
        encryptedContents.removeAll { it.eventId in removedIds }
        revision.value++
        return removedIds.size
    }

    override suspend fun countEncryptedContents(): Int = encryptedContents.size

    override suspend fun deleteEncryptedContentsOlderThan(cutoffMillis: Long): Int {
        val oldIds = events.filter { it.recordedAtMillis < cutoffMillis }.mapTo(mutableSetOf()) { it.id }
        val before = encryptedContents.size
        encryptedContents.removeAll { it.eventId in oldIds }
        events.indices.forEach { index ->
            if (events[index].id in oldIds) events[index] = events[index].copy(hadEncryptedContent = true)
        }
        revision.value++
        return before - encryptedContents.size
    }

    override suspend fun deleteAllEncryptedContents(): Int {
        val count = encryptedContents.size
        encryptedContents.clear()
        revision.value++
        return count
    }

    override suspend fun deleteEncryptedContentsForPackage(packageName: String): Int {
        val packageEventIds =
            events.filter { it.packageName == packageName }.mapTo(mutableSetOf(), NotificationEventEntity::id)
        val count = encryptedContents.count { it.eventId in packageEventIds }
        encryptedContents.removeAll { it.eventId in packageEventIds }
        revision.value++
        return count
    }

    override suspend fun deleteOverLimit(max: Int): Int {
        val keep =
            events
                .sortedWith(
                    compareByDescending<NotificationEventEntity> { it.postedAtMillis }
                        .thenByDescending { it.id },
                ).take(max)
                .toSet()
        val before = events.size
        events.retainAll(keep)
        traces.removeAll { row -> events.none { it.id == row.eventId } }
        encryptedContents.removeAll { row -> events.none { it.id == row.eventId } }
        revision.value++
        return before - events.size
    }

    override suspend fun deleteTracesOutsideMostRecent(max: Int): Int {
        val retainedIds =
            events
                .sortedWith(
                    compareByDescending<NotificationEventEntity> { it.postedAtMillis }
                        .thenByDescending { it.id },
                ).take(max)
                .mapTo(mutableSetOf(), NotificationEventEntity::id)
        val before = traces.size
        traces.removeAll { it.eventId !in retainedIds }
        revision.value++
        return before - traces.size
    }

    override suspend fun getPostedAtBounds(): EventTimeBoundsRow =
        EventTimeBoundsRow(
            oldestPostedAtMillis = events.minOfOrNull(NotificationEventEntity::postedAtMillis),
            newestPostedAtMillis = events.maxOfOrNull(NotificationEventEntity::postedAtMillis),
            oldestPostedEpochDay = events.mapNotNull(NotificationEventEntity::postedEpochDay).minOrNull(),
            newestPostedEpochDay = events.mapNotNull(NotificationEventEntity::postedEpochDay).maxOrNull(),
        )

    override suspend fun countByPackageBetween(
        startMillis: Long,
        endMillis: Long,
        cancelAction: StoredRuleAction,
        snoozeAction: StoredRuleAction,
    ): List<PackageCount> =
        events
            .filter {
                it.recordedAtMillis >= startMillis &&
                    it.recordedAtMillis < endMillis &&
                    !it.undone &&
                    it.action in setOf(cancelAction, snoozeAction)
            }.groupingBy { it.packageName }
            .eachCount()
            .map { (packageName, count) -> PackageCount(packageName, count) }

    override suspend fun rateHistorySince(sinceMillis: Long): List<RateHistoryRow> =
        events
            .filter { it.postedAtMillis >= sinceMillis }
            .sortedWith(compareByDescending<NotificationEventEntity> { it.postedAtMillis }.thenByDescending { it.id })
            .take(10_000)
            .map { RateHistoryRow(it.packageName, it.channelId, it.postedAtMillis) }

    private fun filteredHistory(
        startMillis: Long,
        endMillis: Long,
        search: String,
        packageName: String?,
        channelId: String?,
        category: String?,
        ruleId: Long?,
        action: StoredRuleAction?,
        includeExcluded: Boolean,
    ): List<NotificationEventEntity> {
        val needle = search.trim().unescapeLike()
        return events
            .asSequence()
            .filter { it.postedAtMillis >= startMillis && it.postedAtMillis < endMillis }
            .filter { includeExcluded || !it.undone }
            .filter { packageName == null || it.packageName == packageName }
            .filter { channelId == null || it.channelId == channelId }
            .filter { category == null || (it.mlCategory ?: it.category) == category }
            .filter { ruleId == null || it.matchedRuleId == ruleId }
            .filter { action == null || it.action == action }
            .filter {
                needle.isEmpty() ||
                    listOf(it.packageName, it.channelName, it.channelId, it.mlCategory ?: it.category)
                        .any { value -> value?.contains(needle, ignoreCase = true) == true }
            }.sortedByDescending(NotificationEventEntity::postedAtMillis)
            .toList()
    }

    private fun withTrace(event: NotificationEventEntity): NotificationEventWithTrace =
        NotificationEventWithTrace(event, traces.filter { it.eventId == event.id })

    private fun withDetail(event: NotificationEventEntity): NotificationEventDetailRelation =
        NotificationEventDetailRelation(
            event = event,
            trace = traces.filter { it.eventId == event.id },
            encryptedContent = encryptedContents.firstOrNull { it.eventId == event.id },
        )
}

private fun String.unescapeLike(): String =
    buildString {
        var index = 0
        while (index < this@unescapeLike.length) {
            val current = this@unescapeLike[index]
            if (current == '\\' && index + 1 < this@unescapeLike.length) {
                index += 1
                append(this@unescapeLike[index])
            } else {
                append(current)
            }
            index += 1
        }
    }
