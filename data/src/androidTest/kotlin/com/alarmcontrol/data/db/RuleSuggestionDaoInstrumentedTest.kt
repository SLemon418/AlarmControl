package com.alarmcontrol.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alarmcontrol.data.db.model.StoredRuleAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuleSuggestionDaoInstrumentedTest {
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
    fun thresholdsRequireTenAtEightyPercentAndThreeAtSeventyFivePercent() =
        runBlocking {
            val sql = database.openHelper.writableDatabase
            repeat(10) { index ->
                insertEvent(sql, id = index + 1, action = if (index < 8) "CANCEL" else "KEEP")
            }
            repeat(4) { index ->
                sql.execSQL(
                    "INSERT INTO category_feedback " +
                        "(package_name, predicted_label, corrected_label, recorded_at_millis) " +
                        "VALUES ('com.shop', NULL, ?, 100)",
                    arrayOf(if (index < 3) "promotion" else "social"),
                )
            }
            repeat(3) { index ->
                sql.execSQL(
                    "INSERT INTO local_semantic_feedback " +
                        "(source_event_id, package_name, corrected_intent, recorded_at_millis) " +
                        "VALUES (?, 'com.local', 'MARKETING', 100)",
                    arrayOf(100 + index),
                )
            }
            sql.execSQL(
                "INSERT INTO semantic_feedback_priors (package_name, intent, count) " +
                    "VALUES ('com.imported', 'MARKETING', 100)",
            )

            val dao = database.ruleSuggestionDao()
            val channels =
                dao
                    .observeChannelCandidates(
                        0,
                        10,
                        80,
                        StoredRuleAction.CANCEL,
                        StoredRuleAction.SNOOZE,
                    ).first()
            val marketing = dao.observeMarketingCandidates(0, 3, 75).first()

            assertEquals(1, channels.size)
            assertEquals(8, channels.single().silencedCount)
            assertEquals(setOf("com.shop", "com.local"), marketing.mapTo(mutableSetOf()) { it.packageName })
            assertEquals(3, marketing.first { it.packageName == "com.shop" }.marketingCount)
        }

    private fun insertEvent(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Int,
        action: String,
    ) {
        db.execSQL(
            "INSERT INTO notification_events " +
                "(id, package_name, channel_id, category, posted_at_millis, action, matched_rule_id, " +
                "recorded_at_millis, undone) VALUES (?, 'com.shop', 'offers', NULL, 100, ?, NULL, 100, 0)",
            arrayOf<Any>(id, action),
        )
    }
}
