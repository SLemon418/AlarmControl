package com.alarmcontrol.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alarmcontrol.data.db.entity.CategoryFeedbackEntity
import com.alarmcontrol.data.db.entity.DailyInsightEntity
import com.alarmcontrol.data.db.entity.EncryptedNotificationContentEntity
import com.alarmcontrol.data.db.entity.LlmObservationEntity
import com.alarmcontrol.data.db.entity.LocalSemanticFeedbackEntity
import com.alarmcontrol.data.db.entity.NotificationDecisionTraceEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.repository.DailyInsightRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class NotificationHistoryDaoInstrumentedTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun categoryFilterAndSearchUseTheLatestUserCorrection() =
        runBlocking {
            val eventId =
                database.notificationEventDao().insert(
                    NotificationEventEntity(
                        packageName = "com.example.shop",
                        channelId = "offers",
                        mlCategory = "promotion",
                        category = null,
                        postedAtMillis = 100,
                        action = StoredRuleAction.KEEP,
                        matchedRuleId = null,
                        recordedAtMillis = 100,
                    ),
                )
            database.categoryFeedbackDao().insert(
                CategoryFeedbackEntity(
                    packageName = "com.example.shop",
                    notificationEventId = eventId,
                    predictedLabel = "promotion",
                    correctedLabel = "social",
                    recordedAtMillis = 200,
                ),
            )

            assertEquals(1, history(category = "social").size)
            assertEquals(0, history(category = "promotion").size)
            assertEquals(1, history(search = "social").size)
            assertEquals(0, history(search = "promotion").size)
        }

    @Test
    fun channelNamesComeFromTheLatestEventInEachQueryScope() =
        runBlocking {
            val eventDao = database.notificationEventDao()
            eventDao.insert(
                event(
                    packageName = "com.example.shop",
                    postedAtMillis = 100,
                    epochDay = 5,
                ).copy(channelId = "offers", channelName = "Zulu"),
            )
            eventDao.insert(
                event(
                    packageName = "com.example.shop",
                    postedAtMillis = 200,
                    epochDay = 5,
                ).copy(channelId = "offers", channelName = "Alerts"),
            )
            eventDao.insert(
                event(
                    packageName = "com.example.shop",
                    postedAtMillis = 300,
                    epochDay = 6,
                ).copy(channelId = "offers", channelName = "Future"),
            )

            assertEquals(
                "Future",
                eventDao
                    .observeSources(limit = 10)
                    .first()
                    .single()
                    .channelName,
            )
            assertEquals(
                "Alerts",
                database
                    .dailyInsightDao()
                    .channelBreakdownBetween(
                        epochDay = 5,
                        startMillis = 0,
                        endMillis = 1_000,
                        limit = 10,
                    ).single()
                    .channelName,
            )
        }

    @Test
    fun categoryFeedbackTrimVictimsCanInvalidateTheirCompletedRollupBeforeDeletion() =
        runBlocking {
            val eventId =
                database.notificationEventDao().insert(
                    event(
                        packageName = "com.example.linked",
                        postedAtMillis = 100,
                        epochDay = 5,
                    ),
                )
            val feedbackDao = database.categoryFeedbackDao()
            val dailyDao = database.dailyInsightDao()
            feedbackDao.insert(
                CategoryFeedbackEntity(
                    packageName = "com.example.linked",
                    notificationEventId = eventId,
                    predictedLabel = "promotion",
                    correctedLabel = "social",
                    recordedAtMillis = 100,
                ),
            )
            feedbackDao.insert(
                CategoryFeedbackEntity(
                    packageName = "com.example.detached",
                    predictedLabel = "promotion",
                    correctedLabel = "news",
                    recordedAtMillis = 200,
                ),
            )
            dailyDao.upsertInsight(rollup(epochDay = 5, startMillis = 0, endMillis = 1_000))

            val victims =
                RoomTransactionRunner(database).run {
                    val eventIds = feedbackDao.getLinkedTrimVictimEventIds(max = 1)
                    eventIds.forEach { dailyDao.deleteContainingEvent(it) }
                    feedbackDao.trimToMostRecent(max = 1)
                    eventIds
                }

            assertEquals(listOf(eventId), victims)
            assertEquals(1, feedbackDao.countAll())
            assertEquals(emptyList<Long>(), dailyDao.getEpochDaysBetween(0, 10))
        }

    @Test
    fun rawHistoryTrimCascadesLiveObservationButPreservesDetachedLocalVote() =
        runBlocking {
            val eventId =
                database.notificationEventDao().insert(
                    NotificationEventEntity(
                        packageName = "com.example.shop",
                        category = null,
                        postedAtMillis = 100,
                        postedEpochDay = 5,
                        action = StoredRuleAction.KEEP,
                        matchedRuleId = null,
                        recordedAtMillis = 100,
                    ),
                )
            database.llmObservationDao().upsertIfEventExists(
                LlmObservationEntity(
                    notificationEventId = eventId,
                    packageName = "com.example.shop",
                    predictedIsAdvertisement = true,
                    predictedIntent = "MARKETING",
                    confidenceScore = 0.9f,
                    correctedIsAdvertisement = true,
                    correctedIntent = "MARKETING",
                    analyzedAtMillis = 100,
                ),
            )
            database.llmObservationDao().upsertLocalSemanticFeedback(
                LocalSemanticFeedbackEntity(eventId, "com.example.shop", "MARKETING", 200),
            )

            database.notificationEventDao().deleteOverLimitWithSourceGaps(0, ZoneOffset.UTC)

            assertEquals(0, database.llmObservationDao().countAll())
            assertEquals(1, database.llmObservationDao().countLocalSemanticFeedback())
            assertEquals(
                listOf(5L),
                database
                    .dailyInsightDao()
                    .observeSourceGapDaysBetween(0, 10)
                    .first(),
            )
        }

    @Test
    fun futureDatedEventCannotEvictCurrentHistoryAfterClockRollback() =
        runBlocking {
            val eventDao = database.notificationEventDao()
            val futureId =
                eventDao.insert(
                    event(
                        packageName = "com.example.future",
                        postedAtMillis = 2_000,
                        epochDay = 6,
                    ),
                )
            val currentId =
                eventDao.insert(
                    event(
                        packageName = "com.example.current",
                        postedAtMillis = 1_000,
                        epochDay = 5,
                    ),
                )

            val deleted =
                eventDao.deleteOverLimitWithSourceGaps(
                    max = 1,
                    legacyZoneId = ZoneOffset.UTC,
                )

            assertEquals(1, deleted)
            assertEquals(null, eventDao.getDetail(futureId))
            assertEquals(currentId, eventDao.getDetail(currentId)?.event?.id)
        }

    @Test
    fun encryptedContentRetentionUsesPayloadCreationTimeAfterDelayedPromotion() =
        runBlocking {
            val eventDao = database.notificationEventDao()
            val eventId =
                eventDao.insertWithTraceAndTrim(
                    event =
                        NotificationEventEntity(
                            packageName = "com.example.delayed",
                            category = null,
                            postedAtMillis = 100,
                            action = StoredRuleAction.KEEP,
                            matchedRuleId = null,
                            // Promotion normalizes the durable row time, while the encrypted
                            // payload retains when the notification was originally processed.
                            recordedAtMillis = 1_000,
                            hadEncryptedContent = true,
                        ),
                    trace = emptyList(),
                    encryptedContent =
                        EncryptedNotificationContentEntity(
                            formatVersion = 1,
                            aadId = "delayed",
                            nonce = byteArrayOf(1),
                            ciphertext = byteArrayOf(2),
                            createdAtMillis = 100,
                        ),
                    max = 10,
                    maxTraceEvents = 10,
                    legacyZoneId = ZoneOffset.UTC,
                )

            val deleted =
                eventDao.deleteEncryptedContentsOlderThan(
                    cutoffMillis = 500,
                    nowMillis = 1_000,
                )

            assertEquals(1, deleted)
            assertEquals(1, eventDao.countAll())
            assertEquals(0, eventDao.countEncryptedContents())
            assertEquals(null, eventDao.getDetail(eventId)?.encryptedContent)
            assertEquals(true, eventDao.getDetail(eventId)?.event?.hadEncryptedContent)
        }

    @Test
    fun sourceLossSurvivesRollupInvalidationReaggregationAndDailyInsightClear() =
        runBlocking {
            val eventDao = database.notificationEventDao()
            val dailyDao = database.dailyInsightDao()
            val repository = DailyInsightRepositoryImpl(dailyDao, RoomTransactionRunner(database))
            eventDao.insert(
                event(
                    packageName = "com.example.old",
                    postedAtMillis = 100,
                    epochDay = 5,
                ),
            )
            val retainedId =
                eventDao.insert(
                    event(
                        packageName = "com.example.retained",
                        postedAtMillis = 200,
                        epochDay = 5,
                    ),
                )
            val complete =
                repository.aggregateAndStore(
                    epochDay = 5,
                    startMillis = 0,
                    endMillis = 1_000,
                    generatedAtMillis = 1_000,
                    topRules = 5,
                )
            assertEquals(true, complete.sourceComplete)
            assertEquals(2, complete.totalNotifications)

            eventDao.deleteOlderThanWithSourceGaps(150, ZoneOffset.UTC)
            assertEquals(
                true,
                repository
                    .observeRecent(1)
                    .first()
                    .single()
                    .sourceComplete,
            )
            eventDao.markUndone(retainedId)
            dailyDao.deleteContainingEvent(retainedId)
            val rebuilt =
                repository.aggregateAndStore(
                    epochDay = 5,
                    startMillis = 0,
                    endMillis = 1_000,
                    generatedAtMillis = 2_000,
                    topRules = 5,
                )

            assertEquals(false, rebuilt.sourceComplete)
            assertEquals(0, rebuilt.totalNotifications)
            dailyDao.deleteAll()
            assertEquals(
                listOf(5L),
                dailyDao.observeSourceGapDaysBetween(0, 10).first(),
            )
        }

    @Test
    fun atomicInsertInvalidatesModernEpochAndLegacyWindowRollupsWithoutCreatingSourceGaps() =
        runBlocking {
            val eventDao = database.notificationEventDao()
            val dailyDao = database.dailyInsightDao()
            dailyDao.upsertInsight(rollup(epochDay = 5, startMillis = 0, endMillis = 1_000))
            dailyDao.upsertInsight(rollup(epochDay = 6, startMillis = 1_000, endMillis = 2_000))
            dailyDao.upsertInsight(rollup(epochDay = 7, startMillis = 2_000, endMillis = 3_000))

            eventDao.insertWithTraceAndTrim(
                event =
                    event(
                        packageName = "com.example.modern",
                        postedAtMillis = 1_500,
                        epochDay = 5,
                    ),
                trace = emptyList(),
                encryptedContent = null,
                max = 10,
                maxTraceEvents = 10,
                legacyZoneId = ZoneOffset.UTC,
            )

            assertEquals(listOf(6L, 7L), dailyDao.getEpochDaysBetween(0, 10))

            eventDao.insertWithTraceAndTrim(
                event =
                    NotificationEventEntity(
                        packageName = "com.example.legacy",
                        category = null,
                        postedAtMillis = 1_500,
                        postedEpochDay = null,
                        action = StoredRuleAction.KEEP,
                        matchedRuleId = null,
                        recordedAtMillis = 1_500,
                    ),
                trace = emptyList(),
                encryptedContent = null,
                max = 10,
                maxTraceEvents = 10,
                legacyZoneId = ZoneOffset.UTC,
            )

            assertEquals(listOf(7L), dailyDao.getEpochDaysBetween(0, 10))
            assertEquals(
                emptyList<Long>(),
                dailyDao.observeSourceGapDaysBetween(0, 10).first(),
            )
        }

    @Test
    fun delayedEnrichmentInvalidatesRollupsRebuiltAfterInsertAndMissingEventIsNoOp() =
        runBlocking {
            val eventDao = database.notificationEventDao()
            val dailyDao = database.dailyInsightDao()
            val modernEventId =
                eventDao.insertWithTraceAndTrim(
                    event =
                        event(
                            packageName = "com.example.modern",
                            postedAtMillis = 1_500,
                            epochDay = 5,
                        ),
                    trace = emptyList(),
                    encryptedContent = null,
                    max = 10,
                    maxTraceEvents = 10,
                    legacyZoneId = ZoneOffset.UTC,
                )
            val legacyEventId =
                eventDao.insertWithTraceAndTrim(
                    event =
                        NotificationEventEntity(
                            packageName = "com.example.legacy",
                            category = null,
                            postedAtMillis = 1_500,
                            postedEpochDay = null,
                            action = StoredRuleAction.KEEP,
                            matchedRuleId = null,
                            recordedAtMillis = 1_500,
                        ),
                    trace = emptyList(),
                    encryptedContent = null,
                    max = 10,
                    maxTraceEvents = 10,
                    legacyZoneId = ZoneOffset.UTC,
                )
            // Simulates housekeeping rebuilding past-day rollups between record and delayed enrich.
            dailyDao.upsertInsight(rollup(epochDay = 5, startMillis = 0, endMillis = 1_000))
            dailyDao.upsertInsight(rollup(epochDay = 6, startMillis = 1_000, endMillis = 2_000))
            dailyDao.upsertInsight(rollup(epochDay = 7, startMillis = 2_000, endMillis = 3_000))

            eventDao.updatePostCommitEnrichmentWithTrace(
                eventId = modernEventId,
                mlCategory = "SOCIAL",
                mlConfidence = 0.9f,
                monitoredRuleId = null,
                monitoredAction = null,
                trace = emptyList(),
                maxTraceEvents = 10,
            )
            assertEquals(listOf(6L, 7L), dailyDao.getEpochDaysBetween(0, 10))

            eventDao.updatePostCommitEnrichmentWithTrace(
                eventId = legacyEventId,
                mlCategory = "DELIVERY",
                mlConfidence = 0.8f,
                monitoredRuleId = null,
                monitoredAction = null,
                trace = emptyList(),
                maxTraceEvents = 10,
            )
            assertEquals(listOf(7L), dailyDao.getEpochDaysBetween(0, 10))

            eventDao.updatePostCommitEnrichmentWithTrace(
                eventId = Long.MAX_VALUE,
                mlCategory = "OTHER",
                mlConfidence = 0.7f,
                monitoredRuleId = null,
                monitoredAction = null,
                trace = listOf(trace()),
                maxTraceEvents = 10,
            )

            assertEquals(listOf(7L), dailyDao.getEpochDaysBetween(0, 10))
            assertEquals(
                emptyList<Long>(),
                dailyDao.observeSourceGapDaysBetween(0, 10).first(),
            )
        }

    @Test
    fun linkedFeedbackInvalidationTargetsOnlyCorrectedEventRollups() =
        runBlocking {
            val eventDao = database.notificationEventDao()
            val dailyDao = database.dailyInsightDao()
            val categoryEventId =
                eventDao.insert(
                    event(
                        packageName = "com.example.category",
                        postedAtMillis = 100,
                        epochDay = 5,
                    ),
                )
            val semanticEventId =
                eventDao.insert(
                    NotificationEventEntity(
                        packageName = "com.example.semantic",
                        category = null,
                        postedAtMillis = 1_500,
                        postedEpochDay = null,
                        action = StoredRuleAction.KEEP,
                        matchedRuleId = null,
                        recordedAtMillis = 1_500,
                    ),
                )
            val untouchedEventId =
                eventDao.insert(
                    event(
                        packageName = "com.example.untouched",
                        postedAtMillis = 2_500,
                        epochDay = 7,
                    ),
                )
            database.categoryFeedbackDao().insert(
                CategoryFeedbackEntity(
                    packageName = "com.example.category",
                    notificationEventId = categoryEventId,
                    predictedLabel = "promotion",
                    correctedLabel = "social",
                    recordedAtMillis = 100,
                ),
            )
            database.categoryFeedbackDao().insert(
                CategoryFeedbackEntity(
                    packageName = "com.example.unlinked",
                    notificationEventId = null,
                    predictedLabel = "promotion",
                    correctedLabel = "social",
                    recordedAtMillis = 200,
                ),
            )
            database.llmObservationDao().upsertIfEventExists(
                LlmObservationEntity(
                    notificationEventId = semanticEventId,
                    packageName = "com.example.semantic",
                    predictedIsAdvertisement = false,
                    predictedIntent = "OTHER",
                    confidenceScore = 0.8f,
                    correctedIsAdvertisement = false,
                    correctedIntent = "DELIVERY",
                    analyzedAtMillis = 1_500,
                ),
            )
            database.llmObservationDao().upsertIfEventExists(
                LlmObservationEntity(
                    notificationEventId = untouchedEventId,
                    packageName = "com.example.untouched",
                    predictedIsAdvertisement = false,
                    predictedIntent = "OTHER",
                    confidenceScore = 0.8f,
                    analyzedAtMillis = 2_500,
                ),
            )
            dailyDao.upsertInsight(rollup(epochDay = 5, startMillis = 0, endMillis = 1_000))
            dailyDao.upsertInsight(rollup(epochDay = 6, startMillis = 1_000, endMillis = 2_000))
            dailyDao.upsertInsight(rollup(epochDay = 7, startMillis = 2_000, endMillis = 3_000))

            val invalidated = dailyDao.deleteRollupsAffectedByLinkedFeedback()

            assertEquals(2, invalidated)
            assertEquals(listOf(7L), dailyDao.getEpochDaysBetween(0, 10))
            assertEquals(
                emptyList<Long>(),
                dailyDao.observeSourceGapDaysBetween(0, 10).first(),
            )
        }

    @Test
    fun traceRetentionIsEnforcedForInitialAndDelayedWrites() =
        runBlocking {
            val dao = database.notificationEventDao()
            repeat(3) { index ->
                dao.insertWithTraceAndTrim(
                    event =
                        event(
                            packageName = "com.example.$index",
                            postedAtMillis = index.toLong(),
                            epochDay = 5,
                        ),
                    trace = listOf(trace()),
                    encryptedContent = null,
                    max = 10,
                    maxTraceEvents = 2,
                    legacyZoneId = ZoneOffset.UTC,
                )
            }
            assertEquals(2, dao.observeCoverage().first().traceEventCount)
            assertEquals(emptyList<NotificationDecisionTraceEntity>(), dao.getDetail(1)?.trace)

            dao.updatePostCommitEnrichmentWithTrace(
                eventId = 1,
                mlCategory = "OTHER",
                mlConfidence = 0.8f,
                monitoredRuleId = null,
                monitoredAction = null,
                trace = listOf(trace()),
                maxTraceEvents = 2,
            )

            assertEquals("OTHER", dao.getDetail(1)?.event?.mlCategory)
            assertEquals(emptyList<NotificationDecisionTraceEntity>(), dao.getDetail(1)?.trace)
        }

    @Test
    fun atomicInsertKeepsTheNewestDatabaseRowAcrossClockRollback() =
        runBlocking {
            val dao = database.notificationEventDao()
            val futureId =
                dao.insert(
                    NotificationEventEntity(
                        packageName = "com.example.newer",
                        category = null,
                        postedAtMillis = 200,
                        postedEpochDay = 2,
                        action = StoredRuleAction.KEEP,
                        matchedRuleId = null,
                        recordedAtMillis = 200,
                    ),
                )

            val retainedId =
                dao.insertWithTraceAndTrim(
                    event =
                        NotificationEventEntity(
                            packageName = "com.example.old",
                            category = null,
                            postedAtMillis = 100,
                            postedEpochDay = 1,
                            action = StoredRuleAction.KEEP,
                            matchedRuleId = null,
                            recordedAtMillis = 100,
                            hadEncryptedContent = true,
                        ),
                    trace =
                        listOf(
                            NotificationDecisionTraceEntity(
                                eventId = 0,
                                lane = "ACTIVE",
                                position = 0,
                                depth = 0,
                                conditionKind = "PACKAGE",
                                result = "MATCH",
                            ),
                        ),
                    encryptedContent =
                        EncryptedNotificationContentEntity(
                            formatVersion = 1,
                            aadId = "test",
                            nonce = byteArrayOf(1),
                            ciphertext = byteArrayOf(2),
                            createdAtMillis = 100,
                        ),
                    max = 1,
                    maxTraceEvents = 1_000,
                    legacyZoneId = ZoneOffset.UTC,
                )

            assertEquals(2L, retainedId)
            assertEquals(1, dao.countAll())
            assertEquals(null, dao.getDetail(futureId))
            assertEquals(retainedId, dao.getDetail(retainedId)?.event?.id)
            assertEquals(1, dao.countEncryptedContents())
            assertEquals(1, dao.observeCoverage().first().traceEventCount)
            assertEquals(
                true,
                database.llmObservationDao().upsertIfEventExists(
                    LlmObservationEntity(
                        notificationEventId = retainedId,
                        packageName = "com.example.old",
                        predictedIsAdvertisement = false,
                        predictedIntent = "OTHER",
                        confidenceScore = 0.9f,
                        analyzedAtMillis = 300,
                    ),
                ),
            )
            assertEquals(1, database.llmObservationDao().countAll())
            assertEquals(
                listOf(2L),
                database
                    .dailyInsightDao()
                    .observeSourceGapDaysBetween(0, 10)
                    .first(),
            )
        }

    private suspend fun history(
        search: String = "",
        category: String? = null,
    ) = database
        .notificationEventDao()
        .observeHistory(
            startMillis = 0,
            endMillis = Long.MAX_VALUE,
            search = search,
            packageName = null,
            channelId = null,
            category = category,
            ruleId = null,
            action = null,
            includeExcluded = true,
            limit = 100,
        ).first()

    private fun event(
        packageName: String,
        postedAtMillis: Long,
        epochDay: Long,
    ) = NotificationEventEntity(
        packageName = packageName,
        category = null,
        postedAtMillis = postedAtMillis,
        postedEpochDay = epochDay,
        postedMinuteOfDay = 0,
        action = StoredRuleAction.KEEP,
        matchedRuleId = null,
        recordedAtMillis = postedAtMillis,
    )

    private fun trace() =
        NotificationDecisionTraceEntity(
            eventId = 0,
            lane = "ACTIVE",
            position = 0,
            depth = 0,
            conditionKind = "PACKAGE",
            result = "MATCH",
        )

    private fun rollup(
        epochDay: Long,
        startMillis: Long,
        endMillis: Long,
    ) = DailyInsightEntity(
        epochDay = epochDay,
        windowStartMillis = startMillis,
        windowEndMillis = endMillis,
        totalNotifications = 1,
        mutedCount = 0,
        generatedAtMillis = endMillis,
    )
}
