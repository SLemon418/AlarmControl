package com.alarmcontrol.data.repository

import app.cash.turbine.test
import com.alarmcontrol.core.feedback.AdFeedbackCounts
import com.alarmcontrol.core.feedback.AdObservation
import com.alarmcontrol.core.filtering.SemanticIntent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AdFeedbackRepositoryImplTest {
    private val dao = FakeLlmObservationDao()
    private val dailyInsightDao = FakeDailyInsightDao()
    private val repository = AdFeedbackRepositoryImpl(dao, dailyInsightDao, ImmediateTransactionRunner())

    @Test
    fun `stores content-free observations keyed by activity event`() =
        runTest {
            repository.recordObservation(observation("1", "com.shop", isAd = true))

            repository.observeByEvent().test {
                assertEquals(true, awaitItem().getValue("1").predictedIsAdvertisement)
                cancelAndIgnoreRemainingEvents()
            }
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

            repository.recordCorrection("9", SemanticIntent.TRANSACTIONAL)

            assertEquals(listOf(9L), dailyInsightDao.invalidatedEventIds)
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
