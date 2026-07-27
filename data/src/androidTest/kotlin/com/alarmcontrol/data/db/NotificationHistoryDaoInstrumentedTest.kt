package com.alarmcontrol.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alarmcontrol.data.db.entity.CategoryFeedbackEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
}
