package com.alarmcontrol.data.db

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Instrumented Room migration test (CLAUDE.md §9). Runs on a device/emulator via
 * `./gradlew :data:connectedDebugAndroidTest`. It seeds realistic user data in the v3 schema, then
 * upgrades sequentially v3 -> ... -> v10 -> v11 -> v12 -> v13 -> v14 -> v15 with the real migrations.
 * It asserts old data survives while insight, feedback, profile, and audit tables remain usable.
 *
 * Schemas are read from the test APK assets (wired via `room.schemaLocation` + the androidTest assets
 * srcDir in this module's build).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    @Test
    @Throws(IOException::class)
    fun migratesFromV3ToV15PreservingDataAndSemanticFeedback() {
        helper.createDatabase(TEST_DB, version = 3).use(::seedVersion3)

        helper
            .runMigrationsAndValidate(
                TEST_DB,
                10,
                true,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
            ).use(::seedVersion10BinaryFeedback)

        val migrationStartedAtMillis = System.currentTimeMillis()
        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                15,
                true,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
            )

        assertVersion3DataSurvived(db)
        assertDailyHistoryAndMlColumnsWork(db)
        assertProfileTablesWork(db)
        assertLlmObservationTableWorks(db)
        assertBackupPriorsAndAutomationAuditTablesWork(db)
        assertMilestone5TablesAndDefaultsWork(db)
        assertMilestone6TablesAndDefaultsWork(db)
        assertStabilizationDefaultsAndIndexesWork(db)
        val incompleteUntilMillis = db.conservativeRateIncompleteUntil(migrationStartedAtMillis)
        assertRateOccurrenceFoundationWork(db, expectedIncompleteUntilMillis = incompleteUntilMillis)
    }

    @Test
    @Throws(IOException::class)
    fun migratesFromV1ToV15PreservingRulesAndEvents() {
        helper.createDatabase(V1_TEST_DB, version = 1).use { db ->
            seedLegacyCore(db, includeUndone = false)
        }

        helper
            .runMigrationsAndValidate(
                V1_TEST_DB,
                15,
                true,
                *migrationsFrom(1),
            ).use { db ->
                assertLegacyCoreSurvived(db, expectedUndone = 0)
            }
    }

    @Test
    @Throws(IOException::class)
    fun migratesFromV2ToV15PreservingExcludedStatisticsState() {
        helper.createDatabase(V2_TEST_DB, version = 2).use { db ->
            seedLegacyCore(db, includeUndone = true)
        }

        helper
            .runMigrationsAndValidate(
                V2_TEST_DB,
                15,
                true,
                *migrationsFrom(2),
            ).use { db ->
                assertLegacyCoreSurvived(db, expectedUndone = 1)
            }
    }

    @Test
    @Throws(IOException::class)
    fun migratesFromV10ToV15ConvertingBinaryAdFeedback() {
        helper.createDatabase(V10_TEST_DB, version = 10).use { db ->
            seedVersion10CoreAndBinaryFeedback(db)
        }

        helper
            .runMigrationsAndValidate(
                V10_TEST_DB,
                15,
                true,
                *migrationsFrom(10),
            ).use { db ->
                assertLlmObservationTableWorks(db)
                assertBackupPriorsAndAutomationAuditTablesWork(db)
            }
    }

    @Test
    @Throws(IOException::class)
    fun migratesFromV12ToV15PreservingDailyHistoryAndSemanticPrior() {
        helper.createDatabase(V12_TEST_DB, version = 12).use { db ->
            db.execSQL(
                "INSERT INTO daily_insights " +
                    "(epoch_day, window_start_millis, window_end_millis, total_notifications, " +
                    "muted_count, generated_at_millis) VALUES (20000, 0, 1, 4, 3, 5)",
            )
            db.execSQL(
                "INSERT INTO semantic_feedback_priors (package_name, intent, count) " +
                    "VALUES ('com.example.bank', 'SECURITY', 4)",
            )
        }
        val migrationStartedAtMillis = System.currentTimeMillis()

        helper
            .runMigrationsAndValidate(
                V12_TEST_DB,
                15,
                true,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
            ).use { db ->
                db
                    .query(
                        "SELECT total_notifications, muted_count, rule_breakdown_complete, " +
                            "monitor_rule_breakdown_complete, app_breakdown_complete, " +
                            "channel_breakdown_complete, source_complete FROM daily_insights " +
                            "WHERE epoch_day = 20000",
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(4, cursor.getInt(0))
                        assertEquals(3, cursor.getInt(1))
                        repeat(5) { index -> assertEquals(0, cursor.getInt(index + 2)) }
                    }
                db
                    .query(
                        "SELECT count FROM semantic_feedback_priors " +
                            "WHERE package_name = 'com.example.bank' AND intent = 'SECURITY'",
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(4, cursor.getInt(0))
                    }
                val incompleteUntilMillis = db.rateIncompleteUntilMillis()
                assertTrue(
                    incompleteUntilMillis >= incompleteUntilAfter(migrationStartedAtMillis),
                )
                assertRateOccurrenceFoundationWork(
                    db,
                    expectedIncompleteUntilMillis = incompleteUntilMillis,
                )
            }
    }

    @Test
    @Throws(IOException::class)
    fun migratesFromV14BackfillingValidFeedbackAndMarkingLegacyRollupsIncomplete() {
        helper.createDatabase(V14_TEST_DB, version = 14).use { db ->
            db.execSQL(
                "INSERT INTO daily_insights " +
                    "(epoch_day, window_start_millis, window_end_millis, total_notifications, " +
                    "muted_count, generated_at_millis) VALUES (20000, 0, 1, 4, 3, 5)",
            )
            db.execSQL(
                "INSERT INTO notification_events " +
                    "(id, package_name, posted_at_millis, action, recorded_at_millis, undone) VALUES " +
                    "(1, 'com.example.valid', 100, 'KEEP', 101, 0), " +
                    "(2, 'com.example.invalid', 200, 'KEEP', 201, 0)",
            )
            db.execSQL(
                "INSERT INTO llm_observations " +
                    "(notification_event_id, package_name, predicted_is_ad, predicted_intent, " +
                    "confidence_score, corrected_is_ad, corrected_intent, analyzed_at_millis) VALUES " +
                    "(1, 'com.example.valid', 0, 'OTHER', 0.8, 0, 'DELIVERY', 1234), " +
                    "(2, 'com.example.invalid', 0, 'OTHER', 0.8, 0, 'NOT_A_REAL_INTENT', 2345)",
            )
        }

        helper
            .runMigrationsAndValidate(
                V14_TEST_DB,
                15,
                true,
                AppDatabase.MIGRATION_14_15,
            ).use { db ->
                db
                    .query(
                        "SELECT source_event_id, package_name, corrected_intent, recorded_at_millis " +
                            "FROM local_semantic_feedback",
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(1L, cursor.getLong(0))
                        assertEquals("com.example.valid", cursor.getString(1))
                        assertEquals("DELIVERY", cursor.getString(2))
                        assertEquals(1234L, cursor.getLong(3))
                        assertTrue(!cursor.moveToNext())
                    }
                db.query("SELECT COUNT(*) FROM daily_insight_source_gaps").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                db
                    .query(
                        "SELECT source_complete FROM daily_insights WHERE epoch_day = 20000",
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        // v14 did not retain source-loss provenance, so completeness is unknowable.
                        assertEquals(0, cursor.getInt(0))
                    }
            }
    }

    @Test
    @Throws(IOException::class)
    fun migratesFromV13ToV14WithoutGuessingLegacyOccurrences() {
        helper.createDatabase(V13_TEST_DB, version = 13).use { db ->
            db.execSQL(
                "INSERT INTO notification_events " +
                    "(id, package_name, posted_at_millis, action, recorded_at_millis, undone) " +
                    "VALUES (1, 'com.example.first', 100, 'KEEP', 101, 0)",
            )
            db.execSQL(
                "INSERT INTO notification_events " +
                    "(id, package_name, posted_at_millis, action, recorded_at_millis, undone) " +
                    "VALUES (2, 'com.example.second', 500, 'KEEP', 501, 0)",
            )
        }

        val migrationStartedAtMillis = System.currentTimeMillis()
        helper
            .runMigrationsAndValidate(
                V13_TEST_DB,
                14,
                true,
                AppDatabase.MIGRATION_13_14,
            ).use { db ->
                db
                    .query(
                        "SELECT package_name, posted_at_millis " +
                            "FROM notification_events ORDER BY id",
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("com.example.first", cursor.getString(0))
                        assertEquals(100L, cursor.getLong(1))
                        assertTrue(cursor.moveToNext())
                        assertEquals("com.example.second", cursor.getString(0))
                        assertEquals(500L, cursor.getLong(1))
                    }
                assertRateOccurrenceFoundationWork(
                    db,
                    expectedIncompleteUntilMillis =
                        db.conservativeRateIncompleteUntil(migrationStartedAtMillis),
                )
            }
    }

    @Test
    @Throws(IOException::class)
    fun migratesFutureDatedV13WithSaturatedIncompleteRateState() {
        helper.createDatabase(V13_FUTURE_TEST_DB, version = 13).use { db ->
            db.execSQL(
                "INSERT INTO notification_events " +
                    "(id, package_name, posted_at_millis, action, recorded_at_millis, undone) " +
                    "VALUES (1, 'com.example.future', ?, 'KEEP', ?, 0)",
                arrayOf(Long.MAX_VALUE, Long.MAX_VALUE),
            )
        }

        helper
            .runMigrationsAndValidate(
                V13_FUTURE_TEST_DB,
                14,
                true,
                AppDatabase.MIGRATION_13_14,
            ).use { db ->
                assertRateOccurrenceFoundationWork(
                    db,
                    expectedIncompleteUntilMillis = Long.MAX_VALUE,
                )
            }
    }

    @Test
    @Throws(IOException::class)
    fun migratesEmptyV13DatabaseWithConservativeIncompleteRateState() {
        helper.createDatabase(V13_EMPTY_TEST_DB, version = 13).close()
        val migrationStartedAtMillis = System.currentTimeMillis()

        helper
            .runMigrationsAndValidate(
                V13_EMPTY_TEST_DB,
                14,
                true,
                AppDatabase.MIGRATION_13_14,
            ).use { db ->
                val migrationFinishedAtMillis = System.currentTimeMillis()
                val incompleteUntilMillis = db.rateIncompleteUntilMillis()
                assertTrue(
                    incompleteUntilMillis >= incompleteUntilAfter(migrationStartedAtMillis),
                )
                assertTrue(
                    incompleteUntilMillis <= incompleteUntilAfter(migrationFinishedAtMillis),
                )
                assertRateOccurrenceFoundationWork(
                    db,
                    expectedIncompleteUntilMillis = incompleteUntilMillis,
                )
            }
    }

    private fun seedLegacyCore(
        db: SupportSQLiteDatabase,
        includeUndone: Boolean,
    ) {
        db.execSQL(
            "INSERT INTO rules " +
                "(id, name, enabled, priority, action, snooze_duration_millis, " +
                "created_at_millis, updated_at_millis) " +
                "VALUES (1, 'Legacy rule', 1, 7, 'CANCEL', NULL, 100, 100)",
        )
        db.execSQL(
            "INSERT INTO rule_conditions (id, rule_id, type, value, ignore_case, negate) " +
                "VALUES (1, 1, 'PACKAGE', 'com.example.legacy', 1, 0)",
        )
        val undoneColumn = if (includeUndone) ", undone" else ""
        val undoneValue = if (includeUndone) ", 1" else ""
        db.execSQL(
            "INSERT INTO notification_events " +
                "(id, package_name, category, posted_at_millis, action, matched_rule_id, " +
                "recorded_at_millis$undoneColumn) " +
                "VALUES (1, 'com.example.legacy', 'status', 10, 'CANCEL', 1, 20$undoneValue)",
        )
    }

    private fun assertLegacyCoreSurvived(
        db: SupportSQLiteDatabase,
        expectedUndone: Int,
    ) {
        db.query("SELECT name, execution_mode FROM rules WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Legacy rule", cursor.getString(0))
            assertEquals("ACTIVE", cursor.getString(1))
        }
        db
            .query(
                "SELECT package_name, posted_at_millis, undone FROM notification_events WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("com.example.legacy", cursor.getString(0))
                assertEquals(10L, cursor.getLong(1))
                assertEquals(expectedUndone, cursor.getInt(2))
            }
    }

    private fun seedVersion10CoreAndBinaryFeedback(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO rules " +
                "(id, name, enabled, priority, action, snooze_duration_millis, " +
                "created_at_millis, updated_at_millis) " +
                "VALUES (1, 'Ads', 1, 5, 'CANCEL', NULL, 100, 100)",
        )
        db.execSQL(
            "INSERT INTO notification_events " +
                "(id, package_name, ml_category, category, posted_at_millis, action, " +
                "matched_rule_id, recorded_at_millis, undone) " +
                "VALUES (1, 'com.example.shop', 'promotion', 'promo', 10, 'CANCEL', 1, 20, 0)",
        )
        seedVersion10BinaryFeedback(db)
    }

    private fun seedVersion10BinaryFeedback(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO llm_observations " +
                "(notification_event_id, package_name, predicted_is_ad, confidence_score, " +
                "corrected_is_ad, analyzed_at_millis) " +
                "VALUES (1, 'com.example.shop', 1, 0.8, 0, 40)",
        )
        db.execSQL(
            "INSERT INTO ad_feedback_priors (package_name, is_ad, count) " +
                "VALUES ('com.example.shop', 1, 3)",
        )
    }

    private fun seedVersion3(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO rules " +
                "(id, name, enabled, priority, action, snooze_duration_millis, " +
                "created_at_millis, updated_at_millis) " +
                "VALUES (1, 'Mute promos', 1, 5, 'CANCEL', NULL, 100, 100)",
        )
        db.execSQL(
            "INSERT INTO rule_conditions (id, rule_id, type, value, ignore_case, negate) " +
                "VALUES (1, 1, 'PACKAGE', 'com.example.shop', 1, 0)",
        )
        db.execSQL(
            "INSERT INTO notification_events " +
                "(id, package_name, category, posted_at_millis, action, matched_rule_id, " +
                "recorded_at_millis, undone) " +
                "VALUES (1, 'com.example.shop', 'promo', 10, 'CANCEL', 1, 20, 0)",
        )
        db.execSQL(
            "INSERT INTO category_feedback " +
                "(id, package_name, predicted_label, corrected_label, recorded_at_millis) " +
                "VALUES (1, 'com.example.shop', 'promotion', 'social', 30)",
        )
    }

    private fun assertVersion3DataSurvived(db: SupportSQLiteDatabase) {
        db.query("SELECT name, priority, execution_mode FROM rules WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Mute promos", c.getString(0))
            assertEquals(5, c.getInt(1))
            assertEquals("ACTIVE", c.getString(2))
        }
        db.query("SELECT value, parent_id, position FROM rule_conditions WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("com.example.shop", c.getString(0))
            assertTrue("parent_id should default to NULL", c.isNull(1))
            assertEquals(0, c.getInt(2))
        }
        db.query("SELECT package_name, ml_category FROM notification_events WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("com.example.shop", c.getString(0))
            assertTrue(c.isNull(1))
        }

        db.query("SELECT corrected_label, notification_event_id FROM category_feedback WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("social", c.getString(0))
            assertTrue(c.isNull(1))
        }
    }

    private fun assertDailyHistoryAndMlColumnsWork(db: SupportSQLiteDatabase) {
        db.query("SELECT count(*) FROM daily_insights").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.execSQL(
            "INSERT INTO daily_insights " +
                "(epoch_day, window_start_millis, window_end_millis, total_notifications, " +
                "muted_count, generated_at_millis) VALUES (20000, 0, 1, 3, 2, 5)",
        )
        db
            .query(
                "SELECT cancelled_count, snoozed_count, logged_count, kept_count " +
                    "FROM daily_insights WHERE epoch_day = 20000",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
                assertEquals(0, c.getInt(1))
                assertEquals(0, c.getInt(2))
                assertEquals(0, c.getInt(3))
            }
        db.execSQL(
            "UPDATE daily_insights SET cancelled_count = 1, snoozed_count = 1, " +
                "logged_count = 1, kept_count = 0 WHERE epoch_day = 20000",
        )
        db.execSQL(
            "INSERT INTO daily_insight_rule_counts (epoch_day, rule_id, count) VALUES (20000, '1', 2)",
        )
        db.query("SELECT count FROM daily_insight_rule_counts WHERE epoch_day = 20000").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
        db.execSQL("UPDATE notification_events SET ml_category = 'promotion' WHERE id = 1")
        db.execSQL("UPDATE category_feedback SET notification_event_id = 1 WHERE id = 1")
        db
            .query(
                "SELECT e.ml_category, f.notification_event_id " +
                    "FROM notification_events e JOIN category_feedback f ON f.id = 1 WHERE e.id = 1",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("promotion", c.getString(0))
                assertEquals(1L, c.getLong(1))
            }
    }

    private fun assertProfileTablesWork(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO filtering_profiles " +
                "(id, name, created_at_millis, updated_at_millis) VALUES (1, 'Focus', 100, 100)",
        )
        db.execSQL("INSERT INTO profile_rules (profile_id, rule_id) VALUES (1, 1)")
        db
            .query(
                "SELECT p.name, pr.rule_id FROM filtering_profiles p " +
                    "JOIN profile_rules pr ON pr.profile_id = p.id WHERE p.id = 1",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Focus", c.getString(0))
                assertEquals(1L, c.getLong(1))
            }
    }

    private fun assertLlmObservationTableWorks(db: SupportSQLiteDatabase) {
        db
            .query(
                "SELECT predicted_is_ad, corrected_is_ad, predicted_intent, corrected_intent " +
                    "FROM llm_observations WHERE notification_event_id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals("MARKETING", cursor.getString(2))
                assertEquals("TRANSACTIONAL", cursor.getString(3))
            }
    }

    private fun assertBackupPriorsAndAutomationAuditTablesWork(db: SupportSQLiteDatabase) {
        db.query("SELECT count FROM ad_feedback_priors WHERE package_name = 'com.example.shop'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
        }
        db
            .query(
                "SELECT intent, count FROM semantic_feedback_priors " +
                    "WHERE package_name = 'com.example.shop'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("MARKETING", cursor.getString(0))
                assertEquals(3, cursor.getInt(1))
            }
        db.execSQL(
            "INSERT INTO automation_audit " +
                "(requested_at_millis, source, operation, target_type, outcome, changed_count) " +
                "VALUES (50, 'EXTERNAL', 'ENABLE', 'PROFILE', 'APPLIED', 1)",
        )
        db.query("SELECT outcome, changed_count FROM automation_audit").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("APPLIED", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
    }

    private fun assertMilestone5TablesAndDefaultsWork(db: SupportSQLiteDatabase) {
        db
            .query("SELECT channel_id, ml_confidence, monitored_rule_id, monitored_action FROM notification_events")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
            }
        db.execSQL(
            "INSERT INTO notification_decision_traces " +
                "(event_id, lane, position, depth, condition_kind, result) " +
                "VALUES (1, 'ACTIVE', 0, 0, 'PACKAGE', 'MATCH')",
        )
        db.execSQL(
            "INSERT INTO daily_insight_channel_counts " +
                "(epoch_day, package_name, channel_id, count) " +
                "VALUES (20000, 'com.example.shop', 'offers', 3)",
        )
        db.execSQL(
            "INSERT INTO rule_suggestion_dismissals (suggestion_key, dismissed_at_millis) " +
                "VALUES ('channel:com.example.shop:offers', 50)",
        )
        db.query("SELECT COUNT(*) FROM notification_decision_traces").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT monitored_cancelled_count FROM daily_insights WHERE epoch_day = 20000").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    private fun assertMilestone6TablesAndDefaultsWork(db: SupportSQLiteDatabase) {
        db
            .query(
                "SELECT channel_name, posted_epoch_day, posted_minute_of_day, importance, " +
                    "is_conversation, is_foreground_service, had_encrypted_content " +
                    "FROM notification_events WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                repeat(6) { index -> assertTrue(cursor.isNull(index)) }
                assertEquals(0, cursor.getInt(6))
            }
        db.execSQL(
            "INSERT INTO encrypted_notification_contents " +
                "(event_id, format_version, aad_id, nonce, ciphertext, created_at_millis) " +
                "VALUES (1, 1, 'aad', X'010203', X'040506', 60)",
        )
        db.execSQL(
            "INSERT INTO daily_insight_app_counts " +
                "(epoch_day, package_name, total_count, silenced_count) " +
                "VALUES (20000, 'com.example.shop', 3, 2)",
        )
        db.execSQL(
            "INSERT INTO daily_insight_hour_counts " +
                "(epoch_day, hour, total_count, silenced_count) VALUES (20000, 9, 3, 2)",
        )
        db.execSQL(
            "INSERT INTO daily_insight_semantic_counts " +
                "(epoch_day, intent, count) VALUES (20000, 'MARKETING', 2)",
        )
        db.execSQL(
            "INSERT INTO daily_insight_monitor_rule_counts " +
                "(epoch_day, rule_id, count) VALUES (20000, '1', 2)",
        )
        db.query("SELECT COUNT(*) FROM encrypted_notification_contents").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db
            .query(
                "SELECT ml_classified_count, category_correction_count, " +
                    "semantic_correction_count, breakdown_version FROM daily_insights " +
                    "WHERE epoch_day = 20000",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                repeat(4) { index -> assertEquals(0, cursor.getInt(index)) }
            }
    }

    private fun assertStabilizationDefaultsAndIndexesWork(db: SupportSQLiteDatabase) {
        db
            .query(
                "SELECT rule_breakdown_complete, monitor_rule_breakdown_complete, " +
                    "app_breakdown_complete, channel_breakdown_complete, source_complete " +
                    "FROM daily_insights WHERE epoch_day = 20000",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                repeat(4) { index -> assertEquals(0, cursor.getInt(index)) }
                // Every pre-v15 rollup must remain conservative until it is rebuilt.
                assertEquals(0, cursor.getInt(4))
            }
        val indexNames = mutableSetOf<String>()
        db.query("PRAGMA index_list('notification_events')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                indexNames += cursor.getString(nameIndex)
            }
        }
        assertTrue("posted-at retention index is missing", "index_notification_events_posted_at_millis" in indexNames)
        assertTrue(
            "posted-day insight index is missing",
            "index_notification_events_posted_epoch_day_undone" in indexNames,
        )
    }

    private fun assertRateOccurrenceFoundationWork(
        db: SupportSQLiteDatabase,
        expectedIncompleteUntilMillis: Long,
    ) {
        db.query("SELECT COUNT(*) FROM active_notification_rate_occurrences").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM notification_rate_occurrence_history").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db
            .query(
                "SELECT singleton_id, incomplete_until_millis FROM notification_rate_state",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(expectedIncompleteUntilMillis, cursor.getLong(1))
            }

        val activeIndexes = db.indexesFor("active_notification_rate_occurrences")
        assertEquals(
            1,
            activeIndexes["index_active_notification_rate_occurrences_occurrence_id"],
        )
        assertEquals(
            0,
            activeIndexes[
                "index_active_notification_rate_occurrences_last_posted_at_millis_occurrence_id",
            ],
        )
        val historyIndexes = db.indexesFor("notification_rate_occurrence_history")
        assertEquals(
            0,
            historyIndexes[
                "index_notification_rate_occurrence_history_latest_posted_at_millis_occurrence_id",
            ],
        )
    }

    private fun SupportSQLiteDatabase.indexesFor(tableName: String): Map<String, Int> =
        buildMap {
            query("PRAGMA index_list('$tableName')").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
                while (cursor.moveToNext()) {
                    put(cursor.getString(nameIndex), cursor.getInt(uniqueIndex))
                }
            }
        }

    private fun SupportSQLiteDatabase.rateIncompleteUntilMillis(): Long =
        query("SELECT incomplete_until_millis FROM notification_rate_state WHERE singleton_id = 0").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.conservativeRateIncompleteUntil(migrationStartedAtMillis: Long): Long =
        rateIncompleteUntilMillis().also { incompleteUntilMillis ->
            assertTrue(
                incompleteUntilMillis >= incompleteUntilAfter(migrationStartedAtMillis),
            )
        }

    private fun incompleteUntilAfter(anchorMillis: Long): Long =
        if (anchorMillis > Long.MAX_VALUE - RATE_WINDOW_PLUS_ONE) {
            Long.MAX_VALUE
        } else {
            anchorMillis + RATE_WINDOW_PLUS_ONE
        }

    private companion object {
        const val TEST_DB = "migration-test"
        const val V1_TEST_DB = "migration-v1-test"
        const val V2_TEST_DB = "migration-v2-test"
        const val V10_TEST_DB = "migration-v10-test"
        const val V12_TEST_DB = "migration-v12-test"
        const val V13_TEST_DB = "migration-v13-test"
        const val V13_EMPTY_TEST_DB = "migration-v13-empty-test"
        const val V13_FUTURE_TEST_DB = "migration-v13-future-test"
        const val V14_TEST_DB = "migration-v14-test"
        const val RATE_WINDOW_PLUS_ONE = MAX_RATE_WINDOW_MILLIS + 1L

        fun migrationsFrom(version: Int): Array<Migration> =
            listOf(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
            ).filter { migration -> migration.startVersion >= version }
                .toTypedArray()
    }
}
