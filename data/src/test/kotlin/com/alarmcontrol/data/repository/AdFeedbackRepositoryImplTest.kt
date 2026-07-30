package com.alarmcontrol.data.repository

import app.cash.turbine.test
import com.alarmcontrol.core.feedback.AdFeedbackCounts
import com.alarmcontrol.core.feedback.AdObservation
import com.alarmcontrol.core.filtering.SemanticIntent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AdFeedbackRepositoryImplTest {
    private val dao = FakeLlmObservationDao()
    private val dailyInsightDao = FakeDailyInsightDao()
    private val clock = Clock.fixed(Instant.ofEpochMilli(500), ZoneOffset.UTC)
    private val repository =
        AdFeedbackRepositoryImpl(dao, dailyInsightDao, ImmediateTransactionRunner(), clock)

    @Test
    fun `stores content-free observations keyed by activity event`() =
        runTest {
            repository.recordObservation(observation("1", "com.shop", isAd = true))

            repository.observeByEvent().test {
                assertEquals(true, awaitItem().getValue("1").predictedIsAdvertisement)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(listOf(1L), dailyInsightDao.invalidatedEventIds)
        }

    @Test
    fun `late observation for a deleted activity event is ignored`() =
        runTest {
            repository.recordObservation(observation("1", "com.shop", isAd = true))
            dao.deleteNotificationEvent(1)
            dailyInsightDao.invalidatedEventIds.clear()

            repository.recordObservation(observation("1", "com.shop", isAd = true))

            assertEquals(0, dao.countAll())
            assertEquals(emptyList<Long>(), dailyInsightDao.invalidatedEventIds)
        }

    @Test
    fun `invalid activity event id remains an error`() =
        runTest {
            val failure =
                runCatching {
                    repository.recordObservation(
                        AdObservation(
                            notificationEventId = "not-an-id",
                            packageName = "com.shop",
                            predictedIsAdvertisement = false,
                            confidenceScore = 0.8f,
                            analyzedAtMillis = 1,
                        ),
                    )
                }.exceptionOrNull()

            assertTrue(failure is NumberFormatException)
        }

    @Test
    fun `explicit verdicts aggregate by package`() =
        runTest {
            repository.recordObservation(observation("1", "com.shop", isAd = true))
            repository.recordObservation(observation("2", "com.shop", isAd = false))
            repository.recordObservation(observation("3", "com.bank", isAd = false))
            repository.recordCorrection("1", true)
            repository.recordCorrection("2", true)
            repository.recordCorrection("3", false)

            repository.observeAllFeedbackCounts().test {
                assertEquals(
                    mapOf(
                        "com.shop" to AdFeedbackCounts(advertisement = 2),
                        "com.bank" to AdFeedbackCounts(transactional = 1),
                    ),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `seven semantic intents aggregate independently for shrinkage`() =
        runTest {
            SemanticIntent.entries.forEachIndexed { index, intent ->
                repository.recordObservation(
                    AdObservation(
                        notificationEventId = (index + 1).toString(),
                        packageName = "com.example",
                        predictedIsAdvertisement = intent.isAdvertisement,
                        predictedIntent = intent,
                        confidenceScore = 0.8f,
                        analyzedAtMillis = index.toLong(),
                    ),
                )
                repository.recordCorrection((index + 1).toString(), intent)
            }

            repository.observeAllFeedbackCounts().test {
                val counts = awaitItem().getValue("com.example")
                assertEquals(SemanticIntent.entries.associateWith { 1 }, counts.byIntent)
                assertEquals(SemanticIntent.entries.size, counts.total)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `semantic correction invalidates its completed daily rollup`() =
        runTest {
            repository.recordObservation(observation("9", "com.shop", isAd = true))
            dailyInsightDao.invalidatedEventIds.clear()

            repository.recordCorrection("9", SemanticIntent.TRANSACTIONAL)

            assertEquals(listOf(9L), dailyInsightDao.invalidatedEventIds)
        }

    @Test
    fun `recorrection replaces exactly one time-bearing local vote`() =
        runTest {
            repository.recordObservation(observation("9", "com.shop", isAd = true))

            repository.recordCorrection("9", SemanticIntent.MARKETING)
            repository.recordCorrection("9", SemanticIntent.DELIVERY)

            assertEquals(
                listOf(
                    com.alarmcontrol.data.db.entity.LocalSemanticFeedbackEntity(
                        sourceEventId = 9,
                        packageName = "com.shop",
                        correctedIntent = "DELIVERY",
                        recordedAtMillis = 500,
                    ),
                ),
                dao.getLocalSemanticFeedback(),
            )
        }

    @Test
    fun `stale correction creates no orphan learning row`() =
        runTest {
            repository.recordCorrection("404", SemanticIntent.MARKETING)

            assertEquals(emptyList<Any>(), dao.getLocalSemanticFeedback())
            assertEquals(emptyList<Long>(), dailyInsightDao.invalidatedEventIds)
        }

    @Test
    fun `history cascade removing the live observation preserves its local vote`() =
        runTest {
            repository.recordObservation(observation("12", "com.shop", isAd = true))
            repository.recordCorrection("12", SemanticIntent.MARKETING)

            dao.deleteAll()

            assertEquals(1, dao.countLocalSemanticFeedback())
            assertEquals(1, dao.getSemanticFeedbackCounts().single().count)
        }

    @Test
    fun `local semantic learning is capped at the newest twenty five thousand votes`() =
        runTest {
            dao.seedLocalSemanticFeedback(
                List(25_001) { index ->
                    com.alarmcontrol.data.db.entity.LocalSemanticFeedbackEntity(
                        sourceEventId = index.toLong(),
                        packageName = "com.example",
                        correctedIntent = "OTHER",
                        recordedAtMillis = index.toLong(),
                    )
                },
            )

            assertEquals(1, dao.trimLocalSemanticFeedback(25_000))
            assertEquals(25_000, dao.countLocalSemanticFeedback())
            assertEquals(25_000L, dao.getLocalSemanticFeedback().first().sourceEventId)
            assertEquals(1L, dao.getLocalSemanticFeedback().last().sourceEventId)
        }

    @Test
    fun `clock rollback cannot make future semantic votes evict a new correction`() =
        runTest {
            dao.seedLocalSemanticFeedback(
                List(25_000) { index ->
                    com.alarmcontrol.data.db.entity.LocalSemanticFeedbackEntity(
                        sourceEventId = index.toLong() + 1,
                        packageName = "com.future",
                        correctedIntent = "OTHER",
                        recordedAtMillis = 1_000L + index,
                    )
                },
            )
            repository.recordObservation(observation("30000", "com.current", isAd = false))

            assertTrue(repository.recordCorrection("30000", SemanticIntent.SECURITY))

            val retained = dao.getLocalSemanticFeedback()
            assertEquals(25_000, retained.size)
            assertTrue(retained.any { it.sourceEventId == 30_000L && it.recordedAtMillis == 500L })
            assertTrue(retained.none { it.sourceEventId == 1L })
        }

    @Test
    fun `delayed prediction update preserves an existing semantic correction`() =
        runTest {
            repository.recordObservation(observation("10", "com.shop", isAd = true))
            repository.recordCorrection("10", SemanticIntent.SECURITY)

            repository.recordObservation(
                AdObservation(
                    notificationEventId = "10",
                    packageName = "com.shop",
                    predictedIsAdvertisement = false,
                    predictedIntent = SemanticIntent.DELIVERY,
                    confidenceScore = 0.95f,
                    analyzedAtMillis = 20L,
                ),
            )

            repository.observeByEvent().test {
                val updated = awaitItem().getValue("10")
                assertEquals(SemanticIntent.DELIVERY, updated.predictedIntent)
                assertEquals(0.95f, updated.confidenceScore)
                assertEquals(SemanticIntent.SECURITY, updated.correctedIntent)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun observation(
        eventId: String,
        packageName: String,
        isAd: Boolean,
    ) = AdObservation(
        notificationEventId = eventId,
        packageName = packageName,
        predictedIsAdvertisement = isAd,
        confidenceScore = 0.8f,
        analyzedAtMillis = eventId.toLong(),
    )
}
