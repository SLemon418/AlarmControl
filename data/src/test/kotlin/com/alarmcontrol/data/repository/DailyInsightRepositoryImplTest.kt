package com.alarmcontrol.data.repository

import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.insights.ActionBreakdown
import com.alarmcontrol.core.insights.AppInsightCount
import com.alarmcontrol.core.insights.CategoryCount
import com.alarmcontrol.core.insights.ChannelCount
import com.alarmcontrol.core.insights.HourInsightCount
import com.alarmcontrol.core.insights.RuleTriggerCount
import com.alarmcontrol.core.insights.SemanticIntentCount
import com.alarmcontrol.core.privacy.DailyInsightWriteFence
import com.alarmcontrol.core.privacy.StaleLocalDataWriteException
import com.alarmcontrol.data.db.entity.NotificationEventEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyInsightRepositoryImplTest {
    private val dao = FakeDailyInsightDao()
    private val repository = DailyInsightRepositoryImpl(dao, ImmediateTransactionRunner())

    private val start = 1_000L
    private val end = 2_000L

    private fun event(
        packageName: String,
        category: String?,
        action: StoredRuleAction,
        ruleId: Long?,
        recordedAtMillis: Long,
        undone: Boolean = false,
        id: Long = 0,
        mlCategory: String? = null,
        channelId: String? = null,
        channelName: String? = null,
        monitoredAction: StoredRuleAction? = null,
        monitoredRuleId: Long? = null,
        postedEpochDay: Long? = null,
        postedMinuteOfDay: Int? = null,
    ) = NotificationEventEntity(
        id = id,
        packageName = packageName,
        mlCategory = mlCategory,
        channelId = channelId,
        channelName = channelName,
        category = category,
        postedAtMillis = recordedAtMillis,
        postedEpochDay = postedEpochDay,
        action = action,
        matchedRuleId = ruleId,
        monitoredAction = monitoredAction,
        monitoredRuleId = monitoredRuleId,
        postedMinuteOfDay = postedMinuteOfDay,
        recordedAtMillis = recordedAtMillis,
        undone = undone,
    )

    @Test
    fun `aggregates totals, muted, top rules and category breakdown for the window`() =
        runTest {
            dao.seedEvents(
                event("com.a", "alarm", StoredRuleAction.CANCEL, ruleId = 1, recordedAtMillis = 1_100),
                event("com.b", "alarm", StoredRuleAction.SNOOZE, ruleId = 1, recordedAtMillis = 1_200),
                event("com.c", "msg", StoredRuleAction.MARK_READ, ruleId = 2, recordedAtMillis = 1_300),
                event("com.d", null, StoredRuleAction.KEEP, ruleId = null, recordedAtMillis = 1_400),
                // Excluded: before the window, and an undone in-window row.
                event("com.x", "alarm", StoredRuleAction.CANCEL, ruleId = 1, recordedAtMillis = 500),
                event("com.y", "alarm", StoredRuleAction.CANCEL, ruleId = 1, recordedAtMillis = 1_500, undone = true),
            )

            val insight =
                repository.aggregateAndStore(
                    epochDay = 5,
                    startMillis = start,
                    endMillis = end,
                    generatedAtMillis = 1_500,
                    topRules = 5,
                )

            assertEquals(4, insight.totalNotifications) // a, b, c, d (kept counts in total)
            assertEquals(2, insight.mutedCount) // only Cancel and Snooze have platform silencing effects
            assertEquals(
                ActionBreakdown(cancelled = 1, snoozed = 1, loggedOnly = 1, kept = 1),
                insight.actionBreakdown,
            )
            assertEquals(listOf(RuleTriggerCount("1", 2), RuleTriggerCount("2", 1)), insight.topRules)
            assertEquals(
                listOf(CategoryCount("alarm", 2), CategoryCount(null, 1), CategoryCount("msg", 1)),
                insight.categoryBreakdown,
            )

            // It was persisted and reads back identically (children re-sorted deterministically).
            assertEquals(listOf(insight), repository.observeRecent(10).first())
        }

    @Test
    fun `aggregates app hour semantic learning and monitor rule breakdowns`() =
        runTest {
            dao.seedEvents(
                event(
                    packageName = "com.shop",
                    category = "msg",
                    action = StoredRuleAction.CANCEL,
                    ruleId = 1,
                    recordedAtMillis = 1_100,
                    id = 10,
                    mlCategory = "promotion",
                    channelId = "offers",
                    channelName = "Offers",
                    monitoredAction = StoredRuleAction.SNOOZE,
                    monitoredRuleId = 9,
                    postedMinuteOfDay = 9 * 60 + 15,
                ),
                event(
                    packageName = "com.shop",
                    category = "msg",
                    action = StoredRuleAction.KEEP,
                    ruleId = null,
                    recordedAtMillis = 1_200,
                    id = 11,
                    channelId = "offers",
                    channelName = "Offers",
                    monitoredAction = StoredRuleAction.CANCEL,
                    monitoredRuleId = 9,
                    postedMinuteOfDay = 9 * 60 + 50,
                ),
            )
            dao.seedCorrection(eventId = 10, label = "social")
            dao.seedSemantic(eventId = 10, predicted = "MARKETING", corrected = "TRANSACTIONAL")
            dao.seedSemantic(eventId = 11, predicted = "MARKETING")

            val insight = repository.aggregateAndStore(5, start, end, 1_500, topRules = 5)

            assertEquals(listOf(RuleTriggerCount("9", 2)), insight.topMonitoredRules)
            assertEquals(listOf(AppInsightCount("com.shop", 2, 1)), insight.appBreakdown)
            assertEquals(listOf(HourInsightCount(9, 2, 1)), insight.hourBreakdown)
            assertEquals(
                listOf(
                    SemanticIntentCount(SemanticIntent.MARKETING, 1),
                    SemanticIntentCount(SemanticIntent.TRANSACTIONAL, 1),
                ),
                insight.semanticBreakdown,
            )
            assertEquals(1, insight.mlClassifiedCount)
            assertEquals(1, insight.categoryCorrectionCount)
            assertEquals(1, insight.semanticCorrectionCount)
            assertEquals(2, insight.breakdownVersion)
            assertEquals(true, insight.ruleBreakdownComplete)
            assertEquals(true, insight.monitorRuleBreakdownComplete)
            assertEquals(true, insight.appBreakdownComplete)
            assertEquals(true, insight.channelBreakdownComplete)
            assertEquals("Offers", insight.channelBreakdown.single().channelName)
        }

    @Test
    fun `legacy event without a posted minute marks an otherwise complete rollup incomplete`() =
        runTest {
            dao.seedEvents(
                event(
                    packageName = "com.legacy",
                    category = "alarm",
                    action = StoredRuleAction.KEEP,
                    ruleId = null,
                    recordedAtMillis = 1_100,
                    postedEpochDay = null,
                    postedMinuteOfDay = null,
                ),
                event(
                    packageName = "com.current",
                    category = "alarm",
                    action = StoredRuleAction.CANCEL,
                    ruleId = 1,
                    recordedAtMillis = 1_200,
                    postedEpochDay = 5,
                    postedMinuteOfDay = 9 * 60,
                ),
            )

            val insight = repository.aggregateAndStore(5, start, end, 1_500, topRules = 5)
            val persisted = repository.observeRecent(10).first().single()

            assertEquals(2, insight.totalNotifications)
            assertEquals(listOf(HourInsightCount(9, 1, 1)), insight.hourBreakdown)
            assertFalse(insight.sourceComplete)
            assertFalse(persisted.sourceComplete)
        }

    @Test
    fun `channel breakdown uses the latest event name instead of lexical maximum`() =
        runTest {
            dao.seedEvents(
                event(
                    packageName = "com.shop",
                    category = null,
                    action = StoredRuleAction.KEEP,
                    ruleId = null,
                    recordedAtMillis = 1_100,
                    id = 10,
                    channelId = "offers",
                    channelName = "Zulu",
                ),
                event(
                    packageName = "com.shop",
                    category = null,
                    action = StoredRuleAction.KEEP,
                    ruleId = null,
                    recordedAtMillis = 1_200,
                    id = 11,
                    channelId = "offers",
                    channelName = "Alerts",
                ),
            )

            val insight = repository.aggregateAndStore(5, start, end, 1_500, topRules = 5)

            assertEquals("Alerts", insight.channelBreakdown.single().channelName)
        }

    @Test
    fun `category breakdown prefers user correction then ML label then Android category`() =
        runTest {
            dao.seedEvents(
                event(
                    packageName = "com.a",
                    category = "msg",
                    action = StoredRuleAction.KEEP,
                    ruleId = null,
                    recordedAtMillis = 1_100,
                    id = 10,
                    mlCategory = "promotion",
                ),
                event(
                    packageName = "com.b",
                    category = "alarm",
                    action = StoredRuleAction.KEEP,
                    ruleId = null,
                    recordedAtMillis = 1_200,
                    id = 11,
                ),
            )
            dao.seedCorrection(eventId = 10, label = "social")

            val insight = repository.aggregateAndStore(5, start, end, 1_500, topRules = 5)

            assertEquals(listOf(CategoryCount("alarm", 1), CategoryCount("social", 1)), insight.categoryBreakdown)
        }

    @Test
    fun `posted epoch day wins while legacy rows fall back to the millisecond window`() =
        runTest {
            dao.seedEvents(
                event(
                    packageName = "com.target",
                    category = "msg",
                    action = StoredRuleAction.KEEP,
                    ruleId = null,
                    recordedAtMillis = 500,
                    postedEpochDay = 5,
                ),
                event(
                    packageName = "com.other",
                    category = "msg",
                    action = StoredRuleAction.KEEP,
                    ruleId = null,
                    recordedAtMillis = 1_100,
                    postedEpochDay = 4,
                ),
                event(
                    packageName = "com.legacy",
                    category = "msg",
                    action = StoredRuleAction.KEEP,
                    ruleId = null,
                    recordedAtMillis = 1_200,
                ),
            )

            val insight = repository.aggregateAndStore(5, start, end, 1_500, topRules = 5)

            assertEquals(2, insight.totalNotifications)
            assertEquals(
                listOf("com.legacy", "com.target"),
                insight.appBreakdown.map { it.packageName }.sorted(),
            )
        }

    @Test
    fun `aggregates monitor predictions and package channel counts separately`() =
        runTest {
            dao.seedEvents(
                event(
                    "com.a",
                    "msg",
                    StoredRuleAction.KEEP,
                    null,
                    1_100,
                    channelId = "offers",
                    monitoredAction = StoredRuleAction.CANCEL,
                ),
                event(
                    "com.a",
                    "msg",
                    StoredRuleAction.CANCEL,
                    1,
                    1_200,
                    channelId = "offers",
                    monitoredAction = StoredRuleAction.SNOOZE,
                ),
                event(
                    "com.a",
                    "msg",
                    StoredRuleAction.KEEP,
                    null,
                    1_300,
                    channelId = "chat",
                ),
            )

            val insight = repository.aggregateAndStore(5, start, end, 1_500, topRules = 5)

            assertEquals(ActionBreakdown(cancelled = 1, snoozed = 1), insight.monitoredActionBreakdown)
            assertEquals(
                listOf(ChannelCount("com.a", "offers", 2), ChannelCount("com.a", "chat", 1)),
                insight.channelBreakdown,
            )
            assertEquals(insight, repository.observeRecent(1).first().single())
        }

    @Test
    fun `caps the top rules at the requested limit`() =
        runTest {
            dao.seedEvents(
                event("com.a", "alarm", StoredRuleAction.CANCEL, ruleId = 1, recordedAtMillis = 1_010),
                event("com.a", "alarm", StoredRuleAction.CANCEL, ruleId = 1, recordedAtMillis = 1_020),
                event("com.a", "alarm", StoredRuleAction.CANCEL, ruleId = 1, recordedAtMillis = 1_030),
                event("com.b", "alarm", StoredRuleAction.CANCEL, ruleId = 2, recordedAtMillis = 1_040),
                event("com.b", "alarm", StoredRuleAction.CANCEL, ruleId = 2, recordedAtMillis = 1_050),
                event("com.c", "alarm", StoredRuleAction.CANCEL, ruleId = 3, recordedAtMillis = 1_060),
            )

            val insight =
                repository.aggregateAndStore(
                    epochDay = 5,
                    startMillis = start,
                    endMillis = end,
                    generatedAtMillis = 1_500,
                    topRules = 2,
                )

            assertEquals(listOf(RuleTriggerCount("1", 3), RuleTriggerCount("2", 2)), insight.topRules)
        }

    @Test
    fun `marks capped breakdowns partial without changing total counts`() =
        runTest {
            dao.seedEvents(
                *(1..51)
                    .map { index ->
                        event(
                            packageName = "com.example.$index",
                            category = "msg",
                            action = StoredRuleAction.CANCEL,
                            ruleId = index.toLong(),
                            recordedAtMillis = 1_000L + index,
                            channelId = "channel-$index",
                        )
                    }.toTypedArray(),
            )

            val insight = repository.aggregateAndStore(5, start, end, 1_500, topRules = 5)

            assertEquals(51, insight.totalNotifications)
            assertEquals(false, insight.ruleBreakdownComplete)
            assertEquals(false, insight.appBreakdownComplete)
            assertEquals(false, insight.channelBreakdownComplete)
            assertEquals(5, insight.topRules.size)
            assertEquals(50, insight.appBreakdown.size)
        }

    @Test
    fun `re-running for the same day replaces the previous rollup without duplicating children`() =
        runTest {
            dao.seedEvents(event("com.a", "alarm", StoredRuleAction.CANCEL, ruleId = 1, recordedAtMillis = 1_100))

            repository.aggregateAndStore(
                epochDay = 5,
                startMillis = start,
                endMillis = end,
                generatedAtMillis = 1_500,
                topRules = 5,
            )
            val second =
                repository.aggregateAndStore(
                    epochDay = 5,
                    startMillis = start,
                    endMillis = end,
                    generatedAtMillis = 1_600,
                    topRules = 5,
                )

            val stored = repository.observeRecent(10).first()
            assertEquals(listOf(second), stored) // exactly one row for the day, latest values, single child each
        }

    @Test
    fun `observeRecent returns days newest first`() =
        runTest {
            dao.seedEvents(event("com.a", "alarm", StoredRuleAction.CANCEL, ruleId = 1, recordedAtMillis = 1_100))

            repository.aggregateAndStore(
                epochDay = 5,
                startMillis = start,
                endMillis = end,
                generatedAtMillis = 1_500,
                topRules = 5,
            )
            repository.aggregateAndStore(
                epochDay = 7,
                startMillis = start,
                endMillis = end,
                generatedAtMillis = 1_600,
                topRules = 5,
            )

            assertEquals(listOf(7L, 5L), repository.observeRecent(10).first().map { it.epochDay })
        }

    @Test
    fun `purgeOlderThan removes earlier days and keeps the rest`() =
        runTest {
            dao.seedEvents(event("com.a", "alarm", StoredRuleAction.CANCEL, ruleId = 1, recordedAtMillis = 1_100))
            listOf(5L, 7L, 9L).forEach { day ->
                repository.aggregateAndStore(day, start, end, generatedAtMillis = 1_500, topRules = 5)
            }

            val removed = repository.purgeOlderThan(epochDay = 7)

            assertEquals(1, removed) // only day 5 is older than 7
            assertEquals(listOf(9L, 7L), repository.observeRecent(10).first().map { it.epochDay })
        }

    @Test
    fun `known source gap survives aggregation and rollup retention`() =
        runTest {
            dao.seedSourceGap(5)
            dao.seedSourceGap(9)
            dao.seedEvents(event("com.a", "alarm", StoredRuleAction.KEEP, ruleId = null, recordedAtMillis = 1_100))

            val insight = repository.aggregateAndStore(5, start, end, 1_500, topRules = 5)
            val persisted = repository.observeRecent(10).first().single()
            repository.purgeOlderThan(7)

            assertEquals(false, insight.sourceComplete)
            assertEquals(false, persisted.sourceComplete)
            assertEquals(listOf(5L, 9L), dao.observeSourceGapDaysBetween(0, 20).first())
        }

    @Test
    fun `aggregation captured before selective clear cannot recreate a deleted rollup`() =
        runTest {
            val fence = DailyInsightWriteFence()
            val target =
                DailyInsightRepositoryImpl(
                    dao = dao,
                    transactionRunner = ImmediateTransactionRunner(),
                    dailyInsightWriteFence = fence,
                )
            val staleEpoch = fence.captureEpoch()
            fence.clearAndAdvanceOnCommit { onCommitted -> onCommitted() }

            val failure =
                runCatching {
                    target.aggregateAndStoreIfCurrent(
                        epochDay = 5,
                        startMillis = start,
                        endMillis = end,
                        generatedAtMillis = 1_500,
                        topRules = 5,
                        dailyInsightEpoch = staleEpoch,
                    )
                }.exceptionOrNull()

            assertTrue(failure is StaleLocalDataWriteException)
            assertTrue(target.observeRecent(10).first().isEmpty())
        }

    @Test
    fun `rejects invalid aggregation and read bounds before querying Room`() =
        runTest {
            assertThrows(IllegalArgumentException::class.java) {
                repository.observeRecent(0)
            }
            assertTrue(
                runCatching {
                    repository.aggregateAndStore(5, end, start, 1_500, topRules = 5)
                }.isFailure,
            )
            assertTrue(
                runCatching {
                    repository.aggregateAndStore(5, start, end, 1_500, topRules = 0)
                }.isFailure,
            )
        }
}
