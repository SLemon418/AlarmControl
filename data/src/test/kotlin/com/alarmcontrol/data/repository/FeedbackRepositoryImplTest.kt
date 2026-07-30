package com.alarmcontrol.data.repository

import app.cash.turbine.test
import com.alarmcontrol.core.feedback.CategoryFeedback
import com.alarmcontrol.data.db.dao.CategoryFeedbackDao
import com.alarmcontrol.data.db.entity.CategoryFeedbackEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackRepositoryImplTest {
    private val dao = FakeCategoryFeedbackDao()
    private val dailyInsightDao = FakeDailyInsightDao()
    private val repository = FeedbackRepositoryImpl(dao, dailyInsightDao, ImmediateTransactionRunner())

    private fun correction(
        label: String,
        pkg: String = "com.example.shop",
        eventId: String? = null,
    ) = CategoryFeedback(
        packageName = pkg,
        notificationEventId = eventId,
        predictedLabel = "promotion",
        correctedLabel = label,
        recordedAtMillis = 1_000L,
    )

    @Test
    fun `recordCorrection maps the domain object and inserts one row`() =
        runTest {
            repository.recordCorrection(correction("social"))

            assertEquals(1, dao.inserted.size)
            val entity = dao.inserted.single()
            assertEquals("com.example.shop", entity.packageName)
            assertEquals("promotion", entity.predictedLabel)
            assertEquals("social", entity.correctedLabel)
            assertEquals(1_000L, entity.recordedAtMillis)
            assertEquals(CategoryFeedbackDao.MAX_RETAINED_ROWS, dao.lastTrimMaximum)
        }

    @Test
    fun `observeLabelCounts aggregates corrections per label and updates live`() =
        runTest {
            repository.observeLabelCounts("com.example.shop").test {
                assertEquals(emptyMap<String, Int>(), awaitItem())

                repository.recordCorrection(correction("social"))
                assertEquals(mapOf("social" to 1), awaitItem())

                repository.recordCorrection(correction("social"))
                assertEquals(mapOf("social" to 2), awaitItem())

                repository.recordCorrection(correction("news"))
                assertEquals(mapOf("social" to 2, "news" to 1), awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `counts are scoped per package`() =
        runTest {
            repository.recordCorrection(correction("social", pkg = "com.a"))
            repository.recordCorrection(correction("news", pkg = "com.b"))

            repository.observeLabelCounts("com.a").test {
                assertEquals(mapOf("social" to 1), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `observeAllLabelCounts provides one reactive cache payload for every package`() =
        runTest {
            repository.observeAllLabelCounts().test {
                assertEquals(emptyMap<String, Map<String, Int>>(), awaitItem())

                repository.recordCorrection(correction("social", pkg = "com.a"))
                assertEquals(mapOf("com.a" to mapOf("social" to 1)), awaitItem())

                repository.recordCorrection(correction("news", pkg = "com.b"))
                assertEquals(
                    mapOf("com.a" to mapOf("social" to 1), "com.b" to mapOf("news" to 1)),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `observeEventCorrections keeps only the latest label for each event`() =
        runTest {
            repository.observeEventCorrections().test {
                assertEquals(emptyMap<String, String>(), awaitItem())

                repository.recordCorrection(correction("social", eventId = "7"))
                assertEquals(mapOf("7" to "social"), awaitItem())

                repository.recordCorrection(correction("news", eventId = "7"))
                assertEquals(mapOf("7" to "news"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `learning counts use only the latest correction for one event`() =
        runTest {
            repository.recordCorrection(correction("social", eventId = "7"))
            repository.recordCorrection(correction("news", eventId = "7"))

            repository.observeAllLabelCounts().test {
                assertEquals(mapOf("com.example.shop" to mapOf("news" to 1)), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(listOf("news"), dao.inserted.map { it.correctedLabel })
        }

    @Test
    fun `linked correction invalidates its completed daily rollup`() =
        runTest {
            repository.recordCorrection(correction("social", eventId = "7"))

            assertEquals(listOf(7L), dailyInsightDao.invalidatedEventIds)
        }

    @Test
    fun `feedback cap invalidates linked victim before trimming it`() =
        runTest {
            dao.seedRows(
                List(CategoryFeedbackDao.MAX_RETAINED_ROWS) { index ->
                    CategoryFeedbackEntity(
                        id = index + 1L,
                        packageName = "com.example.$index",
                        notificationEventId = if (index == 0) 77L else null,
                        predictedLabel = "promotion",
                        correctedLabel = "social",
                        recordedAtMillis = index.toLong(),
                    )
                },
            )

            repository.recordCorrection(correction("news"))

            assertEquals(listOf(77L), dailyInsightDao.invalidatedEventIds)
            assertEquals(CategoryFeedbackDao.MAX_RETAINED_ROWS, dao.countAll())
            assertEquals(emptyList<Long>(), dao.inserted.mapNotNull { it.notificationEventId })
        }

    @Test
    fun `clock rollback cannot make future corrections evict a new local vote`() =
        runTest {
            val rollbackRepository =
                FeedbackRepositoryImpl(
                    dao,
                    dailyInsightDao,
                    ImmediateTransactionRunner(),
                )
            dao.seedRows(
                List(CategoryFeedbackDao.MAX_RETAINED_ROWS) { index ->
                    CategoryFeedbackEntity(
                        id = index + 1L,
                        packageName = "com.future.$index",
                        notificationEventId = if (index == 0) 77L else null,
                        predictedLabel = "promotion",
                        correctedLabel = "social",
                        recordedAtMillis = 1_000L + index,
                    )
                },
            )

            rollbackRepository.recordCorrection(
                CategoryFeedback(
                    packageName = "com.current",
                    predictedLabel = "promotion",
                    correctedLabel = "news",
                    recordedAtMillis = 500,
                ),
            )

            assertEquals(CategoryFeedbackDao.MAX_RETAINED_ROWS, dao.countAll())
            assertEquals(1, dao.inserted.count { it.packageName == "com.current" })
            assertEquals(listOf(77L), dailyInsightDao.invalidatedEventIds)
        }
}
