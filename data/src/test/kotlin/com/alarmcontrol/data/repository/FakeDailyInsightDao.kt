package com.alarmcontrol.data.repository

import com.alarmcontrol.data.db.dao.ActionCountRow
import com.alarmcontrol.data.db.dao.AppCountRow
import com.alarmcontrol.data.db.dao.CategoryCountRow
import com.alarmcontrol.data.db.dao.ChannelCountRow
import com.alarmcontrol.data.db.dao.DailyInsightDao
import com.alarmcontrol.data.db.dao.HourCountRow
import com.alarmcontrol.data.db.dao.InsightBoundsRow
import com.alarmcontrol.data.db.dao.RuleCountRow
import com.alarmcontrol.data.db.dao.SemanticCountRow
import com.alarmcontrol.data.db.entity.DailyInsightAppCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightCategoryCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightChannelCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightEntity
import com.alarmcontrol.data.db.entity.DailyInsightHourCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightMonitorRuleCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightRuleCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightSemanticCountEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.db.relation.DailyInsightWithBreakdown
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [DailyInsightDao] for JVM unit tests. The aggregation queries are reimplemented over a
 * seeded list of decision-log rows (the same fake-DAO pattern as [FakeNotificationEventDao]); the
 * concrete `store()` transaction is inherited from the interface and drives the overridden writes.
 */
class FakeDailyInsightDao : DailyInsightDao {
    private val events = mutableListOf<NotificationEventEntity>()
    private val insights = mutableMapOf<Long, DailyInsightEntity>()
    private val ruleCounts = mutableListOf<DailyInsightRuleCountEntity>()
    private val monitorRuleCounts = mutableListOf<DailyInsightMonitorRuleCountEntity>()
    private val categoryCounts = mutableListOf<DailyInsightCategoryCountEntity>()
    private val channelCounts = mutableListOf<DailyInsightChannelCountEntity>()
    private val appCounts = mutableListOf<DailyInsightAppCountEntity>()
    private val hourCounts = mutableListOf<DailyInsightHourCountEntity>()
    private val semanticCounts = mutableListOf<DailyInsightSemanticCountEntity>()
    private val eventCorrections = mutableMapOf<Long, String>()
    private val semanticByEvent = mutableMapOf<Long, SemanticFixture>()
    private var nextChildId = 1L
    private val revision = MutableStateFlow(0)
    val invalidatedEventIds = mutableListOf<Long>()

    override suspend fun countAll(): Int = insights.size

    /** Seeds decision-log rows the aggregation queries read from. */
    fun seedEvents(vararg rows: NotificationEventEntity) {
        events += rows
    }

    fun seedCorrection(
        eventId: Long,
        label: String,
    ) {
        eventCorrections[eventId] = label
    }

    fun seedSemantic(
        eventId: Long,
        predicted: String,
        corrected: String? = null,
    ) {
        semanticByEvent[eventId] = SemanticFixture(predicted, corrected)
    }

    private fun NotificationEventEntity.inWindow(
        epochDay: Long,
        start: Long,
        end: Long,
    ) = !undone &&
        if (postedEpochDay != null) {
            postedEpochDay == epochDay
        } else {
            postedAtMillis >= start && postedAtMillis < end
        }

    override suspend fun countBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int = events.count { it.inWindow(epochDay, startMillis, endMillis) }

    override suspend fun countMutedBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
        cancelAction: StoredRuleAction,
        snoozeAction: StoredRuleAction,
    ): Int =
        events.count {
            it.inWindow(epochDay, startMillis, endMillis) &&
                it.action in setOf(cancelAction, snoozeAction)
        }

    override suspend fun actionCountsBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): List<ActionCountRow> =
        events
            .filter { it.inWindow(epochDay, startMillis, endMillis) }
            .groupingBy(NotificationEventEntity::action)
            .eachCount()
            .map { (action, count) -> ActionCountRow(action, count) }

    override suspend fun monitoredActionCountsBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): List<ActionCountRow> =
        events
            .filter { it.inWindow(epochDay, startMillis, endMillis) && it.monitoredAction != null }
            .groupingBy { requireNotNull(it.monitoredAction) }
            .eachCount()
            .map { (action, count) -> ActionCountRow(action, count) }

    override suspend fun topRulesBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
        limit: Int,
    ): List<RuleCountRow> =
        events
            .filter { it.inWindow(epochDay, startMillis, endMillis) && it.matchedRuleId != null }
            .groupingBy { it.matchedRuleId!! }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { RuleCountRow(it.key, it.value) }

    override suspend fun topMonitoredRulesBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
        limit: Int,
    ): List<RuleCountRow> =
        events
            .filter { it.inWindow(epochDay, startMillis, endMillis) && it.monitoredRuleId != null }
            .groupingBy { requireNotNull(it.monitoredRuleId) }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { RuleCountRow(it.key, it.value) }

    override suspend fun countMatchedRulesBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int =
        events
            .filter { it.inWindow(epochDay, startMillis, endMillis) }
            .mapNotNull(NotificationEventEntity::matchedRuleId)
            .distinct()
            .size

    override suspend fun countMonitoredRulesBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int =
        events
            .filter { it.inWindow(epochDay, startMillis, endMillis) }
            .mapNotNull(NotificationEventEntity::monitoredRuleId)
            .distinct()
            .size

    override suspend fun categoryBreakdownBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): List<CategoryCountRow> =
        events
            .filter { it.inWindow(epochDay, startMillis, endMillis) }
            .groupingBy { eventCorrections[it.id] ?: it.mlCategory ?: it.category }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String?, Int>> { it.value }.thenBy { it.key ?: "" })
            .map { CategoryCountRow(it.key, it.value) }

    override suspend fun channelBreakdownBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
        limit: Int,
    ): List<ChannelCountRow> =
        events
            .filter { it.inWindow(epochDay, startMillis, endMillis) && it.channelId != null }
            .groupingBy { it.packageName to requireNotNull(it.channelId) }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }.thenBy { it.key.first })
            .take(limit)
            .map { entry ->
                val channelName =
                    events
                        .filter {
                            it.packageName == entry.key.first &&
                                it.channelId == entry.key.second &&
                                it.inWindow(epochDay, startMillis, endMillis)
                        }.mapNotNull { it.channelName }
                        .lastOrNull()
                ChannelCountRow(entry.key.first, entry.key.second, channelName, entry.value)
            }

    override suspend fun appBreakdownBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
        cancelAction: StoredRuleAction,
        snoozeAction: StoredRuleAction,
        limit: Int,
    ): List<AppCountRow> =
        events
            .filter { it.inWindow(epochDay, startMillis, endMillis) }
            .groupBy(NotificationEventEntity::packageName)
            .map { (packageName, rows) ->
                AppCountRow(
                    packageName,
                    rows.size,
                    rows.count { it.action == cancelAction || it.action == snoozeAction },
                )
            }.sortedWith(compareByDescending<AppCountRow> { it.totalCount }.thenBy { it.packageName })
            .take(limit)

    override suspend fun countChannelsBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int =
        events
            .filter { it.inWindow(epochDay, startMillis, endMillis) && it.channelId != null }
            .map { it.packageName to it.channelId }
            .distinct()
            .size

    override suspend fun countAppsBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int =
        events
            .filter { it.inWindow(epochDay, startMillis, endMillis) }
            .map(NotificationEventEntity::packageName)
            .distinct()
            .size

    override suspend fun hourBreakdownBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
        cancelAction: StoredRuleAction,
        snoozeAction: StoredRuleAction,
    ): List<HourCountRow> =
        events
            .filter {
                it.inWindow(epochDay, startMillis, endMillis) && it.postedMinuteOfDay != null
            }.groupBy { requireNotNull(it.postedMinuteOfDay) / MINUTES_PER_HOUR }
            .map { (hour, rows) ->
                HourCountRow(
                    hour,
                    rows.size,
                    rows.count { it.action == cancelAction || it.action == snoozeAction },
                )
            }.sortedBy(HourCountRow::hour)

    override suspend fun semanticBreakdownBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): List<SemanticCountRow> =
        events
            .filter {
                it.inWindow(epochDay, startMillis, endMillis) &&
                    semanticByEvent.containsKey(it.id)
            }.groupingBy { event ->
                requireNotNull(semanticByEvent[event.id]).let { it.corrected ?: it.predicted }
            }.eachCount()
            .map { SemanticCountRow(it.key, it.value) }
            .sortedWith(compareByDescending<SemanticCountRow> { it.count }.thenBy { it.intent })

    override suspend fun countMlClassifiedBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int =
        events.count {
            it.inWindow(epochDay, startMillis, endMillis) && it.mlCategory != null
        }

    override suspend fun countCategoryCorrectionsBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int =
        events.count {
            it.inWindow(epochDay, startMillis, endMillis) &&
                eventCorrections.containsKey(it.id)
        }

    override suspend fun countSemanticCorrectionsBetween(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ): Int =
        events.count {
            it.inWindow(epochDay, startMillis, endMillis) &&
                semanticByEvent[it.id]?.corrected != null
        }

    override suspend fun upsertInsight(insight: DailyInsightEntity) {
        insights[insight.epochDay] = insight
        revision.value++
    }

    override suspend fun insertRuleCounts(rows: List<DailyInsightRuleCountEntity>) {
        rows.forEach { ruleCounts += it.copy(id = nextChildId++) }
        revision.value++
    }

    override suspend fun insertMonitorRuleCounts(rows: List<DailyInsightMonitorRuleCountEntity>) {
        monitorRuleCounts += rows
        revision.value++
    }

    override suspend fun insertCategoryCounts(rows: List<DailyInsightCategoryCountEntity>) {
        rows.forEach { categoryCounts += it.copy(id = nextChildId++) }
        revision.value++
    }

    override suspend fun insertChannelCounts(rows: List<DailyInsightChannelCountEntity>) {
        channelCounts += rows
        revision.value++
    }

    override suspend fun insertAppCounts(rows: List<DailyInsightAppCountEntity>) {
        appCounts += rows
        revision.value++
    }

    override suspend fun insertHourCounts(rows: List<DailyInsightHourCountEntity>) {
        hourCounts += rows
        revision.value++
    }

    override suspend fun insertSemanticCounts(rows: List<DailyInsightSemanticCountEntity>) {
        semanticCounts += rows
        revision.value++
    }

    override suspend fun deleteRuleCounts(epochDay: Long) {
        ruleCounts.removeAll { it.epochDay == epochDay }
        revision.value++
    }

    override suspend fun deleteMonitorRuleCounts(epochDay: Long) {
        monitorRuleCounts.removeAll { it.epochDay == epochDay }
        revision.value++
    }

    override suspend fun deleteCategoryCounts(epochDay: Long) {
        categoryCounts.removeAll { it.epochDay == epochDay }
        revision.value++
    }

    override suspend fun deleteChannelCounts(epochDay: Long) {
        channelCounts.removeAll { it.epochDay == epochDay }
        revision.value++
    }

    override suspend fun deleteAppCounts(epochDay: Long) {
        appCounts.removeAll { it.epochDay == epochDay }
        revision.value++
    }

    override suspend fun deleteHourCounts(epochDay: Long) {
        hourCounts.removeAll { it.epochDay == epochDay }
        revision.value++
    }

    override suspend fun deleteSemanticCounts(epochDay: Long) {
        semanticCounts.removeAll { it.epochDay == epochDay }
        revision.value++
    }

    override suspend fun deleteOlderThan(epochDay: Long): Int {
        val removed = insights.keys.count { it < epochDay }
        insights.keys.removeAll { it < epochDay }
        ruleCounts.removeAll { it.epochDay < epochDay } // FK cascade
        monitorRuleCounts.removeAll { it.epochDay < epochDay }
        categoryCounts.removeAll { it.epochDay < epochDay }
        channelCounts.removeAll { it.epochDay < epochDay }
        appCounts.removeAll { it.epochDay < epochDay }
        hourCounts.removeAll { it.epochDay < epochDay }
        semanticCounts.removeAll { it.epochDay < epochDay }
        revision.value++
        return removed
    }

    override suspend fun deleteAll(): Int {
        val count = insights.size
        insights.clear()
        ruleCounts.clear()
        monitorRuleCounts.clear()
        categoryCounts.clear()
        channelCounts.clear()
        appCounts.clear()
        hourCounts.clear()
        semanticCounts.clear()
        revision.value++
        return count
    }

    override suspend fun deleteContainingEvent(eventId: Long): Int {
        invalidatedEventIds += eventId
        val event = events.firstOrNull { it.id == eventId } ?: return 0
        val matchingDays =
            insights.values
                .filter { insight ->
                    event.postedEpochDay?.let { it == insight.epochDay }
                        ?: (
                            event.postedAtMillis >= insight.windowStartMillis &&
                                event.postedAtMillis < insight.windowEndMillis
                        )
                }.map(DailyInsightEntity::epochDay)
                .toSet()
        insights.keys.removeAll(matchingDays)
        ruleCounts.removeAll { it.epochDay in matchingDays }
        monitorRuleCounts.removeAll { it.epochDay in matchingDays }
        categoryCounts.removeAll { it.epochDay in matchingDays }
        channelCounts.removeAll { it.epochDay in matchingDays }
        appCounts.removeAll { it.epochDay in matchingDays }
        hourCounts.removeAll { it.epochDay in matchingDays }
        semanticCounts.removeAll { it.epochDay in matchingDays }
        if (matchingDays.isNotEmpty()) revision.value++
        return matchingDays.size
    }

    override fun observeRecent(limit: Int): Flow<List<DailyInsightWithBreakdown>> =
        revision.map {
            recent(limit)
        }

    override fun observeBetween(
        startEpochDay: Long,
        endEpochDay: Long,
    ): Flow<List<DailyInsightWithBreakdown>> =
        revision.map {
            relations()
                .filter { it.insight.epochDay in startEpochDay..endEpochDay }
                .sortedBy { it.insight.epochDay }
        }

    override fun observeBounds(): Flow<InsightBoundsRow> =
        revision.map {
            InsightBoundsRow(insights.keys.minOrNull(), insights.keys.maxOrNull())
        }

    override suspend fun getEpochDaysBetween(
        startEpochDay: Long,
        endEpochDay: Long,
    ): List<Long> = insights.keys.filter { it in startEpochDay..endEpochDay }.sorted()

    override suspend fun getRecent(limit: Int): List<DailyInsightWithBreakdown> = recent(limit)

    private fun recent(limit: Int): List<DailyInsightWithBreakdown> =
        relations()
            .sortedByDescending { it.insight.epochDay }
            .take(limit)

    private fun relations(): List<DailyInsightWithBreakdown> =
        insights.values.map { insight ->
            DailyInsightWithBreakdown(
                insight = insight,
                ruleCounts = ruleCounts.filter { it.epochDay == insight.epochDay },
                monitorRuleCounts = monitorRuleCounts.filter { it.epochDay == insight.epochDay },
                categoryCounts = categoryCounts.filter { it.epochDay == insight.epochDay },
                channelCounts = channelCounts.filter { it.epochDay == insight.epochDay },
                appCounts = appCounts.filter { it.epochDay == insight.epochDay },
                hourCounts = hourCounts.filter { it.epochDay == insight.epochDay },
                semanticCounts = semanticCounts.filter { it.epochDay == insight.epochDay },
            )
        }

    private data class SemanticFixture(
        val predicted: String,
        val corrected: String?,
    )

    private companion object {
        const val MINUTES_PER_HOUR = 60
    }
}
