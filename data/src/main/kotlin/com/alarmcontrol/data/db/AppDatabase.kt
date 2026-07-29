package com.alarmcontrol.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.alarmcontrol.data.db.dao.AutomationAuditDao
import com.alarmcontrol.data.db.dao.CategoryFeedbackDao
import com.alarmcontrol.data.db.dao.DailyInsightDao
import com.alarmcontrol.data.db.dao.LlmObservationDao
import com.alarmcontrol.data.db.dao.NotificationEventDao
import com.alarmcontrol.data.db.dao.NotificationRateStateDao
import com.alarmcontrol.data.db.dao.ProfileDao
import com.alarmcontrol.data.db.dao.RuleDao
import com.alarmcontrol.data.db.dao.RuleSuggestionDao
import com.alarmcontrol.data.db.entity.ActiveNotificationRateOccurrenceEntity
import com.alarmcontrol.data.db.entity.AdFeedbackPriorEntity
import com.alarmcontrol.data.db.entity.AutomationAuditEntity
import com.alarmcontrol.data.db.entity.CategoryFeedbackEntity
import com.alarmcontrol.data.db.entity.DailyInsightAppCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightCategoryCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightChannelCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightEntity
import com.alarmcontrol.data.db.entity.DailyInsightHourCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightMonitorRuleCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightRuleCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightSemanticCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightSourceGapEntity
import com.alarmcontrol.data.db.entity.EncryptedNotificationContentEntity
import com.alarmcontrol.data.db.entity.FilteringProfileEntity
import com.alarmcontrol.data.db.entity.LlmObservationEntity
import com.alarmcontrol.data.db.entity.LocalSemanticFeedbackEntity
import com.alarmcontrol.data.db.entity.NotificationDecisionTraceEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity
import com.alarmcontrol.data.db.entity.NotificationRateOccurrenceHistoryEntity
import com.alarmcontrol.data.db.entity.NotificationRateStateEntity
import com.alarmcontrol.data.db.entity.ProfileRuleEntity
import com.alarmcontrol.data.db.entity.RuleConditionEntity
import com.alarmcontrol.data.db.entity.RuleEntity
import com.alarmcontrol.data.db.entity.RuleSuggestionDismissalEntity
import com.alarmcontrol.data.db.entity.SemanticFeedbackPriorEntity

/**
 * The single on-device Room database (CLAUDE.md §4 — `:data` is the only module that persists).
 * Schemas are exported (see `room.schemaLocation` in the module build) so migrations are reviewable.
 */
@Database(
    entities = [
        RuleEntity::class,
        RuleConditionEntity::class,
        NotificationEventEntity::class,
        CategoryFeedbackEntity::class,
        DailyInsightEntity::class,
        DailyInsightRuleCountEntity::class,
        DailyInsightCategoryCountEntity::class,
        FilteringProfileEntity::class,
        ProfileRuleEntity::class,
        LlmObservationEntity::class,
        AdFeedbackPriorEntity::class,
        AutomationAuditEntity::class,
        NotificationDecisionTraceEntity::class,
        DailyInsightChannelCountEntity::class,
        RuleSuggestionDismissalEntity::class,
        SemanticFeedbackPriorEntity::class,
        EncryptedNotificationContentEntity::class,
        DailyInsightAppCountEntity::class,
        DailyInsightHourCountEntity::class,
        DailyInsightSemanticCountEntity::class,
        DailyInsightMonitorRuleCountEntity::class,
        ActiveNotificationRateOccurrenceEntity::class,
        NotificationRateOccurrenceHistoryEntity::class,
        NotificationRateStateEntity::class,
        LocalSemanticFeedbackEntity::class,
        DailyInsightSourceGapEntity::class,
    ],
    version = 15,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao

    abstract fun notificationEventDao(): NotificationEventDao

    abstract fun notificationRateStateDao(): NotificationRateStateDao

    abstract fun categoryFeedbackDao(): CategoryFeedbackDao

    abstract fun dailyInsightDao(): DailyInsightDao

    abstract fun profileDao(): ProfileDao

    abstract fun llmObservationDao(): LlmObservationDao

    abstract fun automationAuditDao(): AutomationAuditDao

    abstract fun ruleSuggestionDao(): RuleSuggestionDao

    companion object {
        const val NAME = "alarm_control.db"

        /** v2 adds the legacy `undone` flag, now presented as statistics exclusion (§6). */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE notification_events ADD COLUMN undone INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }

        /**
         * v4 turns `rule_conditions` into a self-referential tree (nested compound rules + time
         * windows, §6). Existing flat rows get `parent_id = NULL` (roots, AND-ed) and `position = 0`,
         * so old rules keep working unchanged.
         */
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE rule_conditions ADD COLUMN parent_id INTEGER")
                    db.execSQL("ALTER TABLE rule_conditions ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                }
            }

        /**
         * v5 adds the per-day insight history (§5): a `daily_insights` rollup with two cascade-linked
         * breakdown tables. Additive only — existing data is untouched; the first worker run fills it.
         */
        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `daily_insights` (" +
                            "`epoch_day` INTEGER NOT NULL, " +
                            "`window_start_millis` INTEGER NOT NULL, " +
                            "`window_end_millis` INTEGER NOT NULL, " +
                            "`total_notifications` INTEGER NOT NULL, " +
                            "`muted_count` INTEGER NOT NULL, " +
                            "`generated_at_millis` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`epoch_day`))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `daily_insight_rule_counts` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`epoch_day` INTEGER NOT NULL, " +
                            "`rule_id` TEXT NOT NULL, " +
                            "`count` INTEGER NOT NULL, " +
                            "FOREIGN KEY(`epoch_day`) REFERENCES `daily_insights`(`epoch_day`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_daily_insight_rule_counts_epoch_day` " +
                            "ON `daily_insight_rule_counts` (`epoch_day`)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `daily_insight_category_counts` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`epoch_day` INTEGER NOT NULL, " +
                            "`category` TEXT, " +
                            "`count` INTEGER NOT NULL, " +
                            "FOREIGN KEY(`epoch_day`) REFERENCES `daily_insights`(`epoch_day`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_daily_insight_category_counts_epoch_day` " +
                            "ON `daily_insight_category_counts` (`epoch_day`)",
                    )
                }
            }

        /** v3 adds the on-device feedback table for incremental learning (§5). */
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `category_feedback` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`package_name` TEXT NOT NULL, " +
                            "`predicted_label` TEXT, " +
                            "`corrected_label` TEXT NOT NULL, " +
                            "`recorded_at_millis` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_category_feedback_package_name` " +
                            "ON `category_feedback` (`package_name`)",
                    )
                }
            }

        /** v6 persists ML prediction metadata and links corrections to individual activity rows. */
        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE notification_events ADD COLUMN ml_category TEXT")
                    db.execSQL("ALTER TABLE category_feedback ADD COLUMN notification_event_id INTEGER")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_category_feedback_notification_event_id` " +
                            "ON `category_feedback` (`notification_event_id`)",
                    )
                }
            }

        /** v7 adds named profiles and their many-to-many rule membership. */
        val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `filtering_profiles` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`created_at_millis` INTEGER NOT NULL, " +
                            "`updated_at_millis` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `profile_rules` (" +
                            "`profile_id` INTEGER NOT NULL, " +
                            "`rule_id` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`profile_id`, `rule_id`), " +
                            "FOREIGN KEY(`profile_id`) REFERENCES `filtering_profiles`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                            "FOREIGN KEY(`rule_id`) REFERENCES `rules`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_profile_rules_rule_id` " +
                            "ON `profile_rules` (`rule_id`)",
                    )
                }
            }

        /** v8 stores content-free local LLM verdicts and optional user ad corrections. */
        val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `llm_observations` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`notification_event_id` INTEGER NOT NULL, " +
                            "`package_name` TEXT NOT NULL, " +
                            "`predicted_is_ad` INTEGER NOT NULL, " +
                            "`confidence_score` REAL NOT NULL, " +
                            "`corrected_is_ad` INTEGER, " +
                            "`analyzed_at_millis` INTEGER NOT NULL, " +
                            "FOREIGN KEY(`notification_event_id`) REFERENCES `notification_events`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_llm_observations_notification_event_id` " +
                            "ON `llm_observations` (`notification_event_id`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_llm_observations_package_name` " +
                            "ON `llm_observations` (`package_name`)",
                    )
                }
            }

        /** v9 adds action-level daily trend counts; old rollups remain valid with zeroed details. */
        val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE daily_insights ADD COLUMN cancelled_count INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE daily_insights ADD COLUMN snoozed_count INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE daily_insights ADD COLUMN logged_count INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE daily_insights ADD COLUMN kept_count INTEGER NOT NULL DEFAULT 0")
                }
            }

        /** v10 stores imported ad-learning priors plus privacy-safe automation outcomes. */
        val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `ad_feedback_priors` (" +
                            "`package_name` TEXT NOT NULL, `is_ad` INTEGER NOT NULL, " +
                            "`count` INTEGER NOT NULL, PRIMARY KEY(`package_name`, `is_ad`))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `automation_audit` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`requested_at_millis` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                            "`operation` TEXT NOT NULL, `target_type` TEXT NOT NULL, " +
                            "`outcome` TEXT NOT NULL, `changed_count` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_automation_audit_requested_at_millis` " +
                            "ON `automation_audit` (`requested_at_millis`)",
                    )
                }
            }

        /** Milestone 5: monitor rules, stateful metadata, channel analytics, and semantic feedback. */
        val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE rules ADD COLUMN execution_mode TEXT NOT NULL DEFAULT 'ACTIVE'")
                    db.execSQL("ALTER TABLE notification_events ADD COLUMN channel_id TEXT")
                    db.execSQL("ALTER TABLE notification_events ADD COLUMN ml_confidence REAL")
                    db.execSQL("ALTER TABLE notification_events ADD COLUMN monitored_rule_id INTEGER")
                    db.execSQL("ALTER TABLE notification_events ADD COLUMN monitored_action TEXT")
                    db.execSQL(
                        "ALTER TABLE llm_observations ADD COLUMN predicted_intent TEXT NOT NULL " +
                            "DEFAULT 'AMBIGUOUS'",
                    )
                    db.execSQL("ALTER TABLE llm_observations ADD COLUMN corrected_intent TEXT")
                    db.execSQL(
                        "UPDATE llm_observations SET predicted_intent = " +
                            "CASE WHEN predicted_is_ad = 1 THEN 'MARKETING' ELSE 'TRANSACTIONAL' END",
                    )
                    db.execSQL(
                        "UPDATE llm_observations SET corrected_intent = CASE " +
                            "WHEN corrected_is_ad IS NULL THEN NULL " +
                            "WHEN corrected_is_ad = 1 THEN 'MARKETING' ELSE 'TRANSACTIONAL' END",
                    )
                    db.execSQL(
                        "ALTER TABLE daily_insights ADD COLUMN monitored_cancelled_count " +
                            "INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "ALTER TABLE daily_insights ADD COLUMN monitored_snoozed_count " +
                            "INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "ALTER TABLE daily_insights ADD COLUMN monitored_logged_count INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "ALTER TABLE daily_insights ADD COLUMN monitored_kept_count INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS notification_decision_traces (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, event_id INTEGER NOT NULL, " +
                            "lane TEXT NOT NULL, position INTEGER NOT NULL, depth INTEGER NOT NULL, " +
                            "condition_kind TEXT NOT NULL, result TEXT NOT NULL, " +
                            "FOREIGN KEY(event_id) REFERENCES notification_events(id) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_notification_decision_traces_event_id " +
                            "ON notification_decision_traces (event_id)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS daily_insight_channel_counts (" +
                            "epoch_day INTEGER NOT NULL, package_name TEXT NOT NULL, channel_id TEXT NOT NULL, " +
                            "count INTEGER NOT NULL, PRIMARY KEY(epoch_day, package_name, channel_id), " +
                            "FOREIGN KEY(epoch_day) REFERENCES daily_insights(epoch_day) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_daily_insight_channel_counts_epoch_day " +
                            "ON daily_insight_channel_counts (epoch_day)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS rule_suggestion_dismissals (" +
                            "suggestion_key TEXT NOT NULL, dismissed_at_millis INTEGER NOT NULL, " +
                            "PRIMARY KEY(suggestion_key))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS semantic_feedback_priors (" +
                            "package_name TEXT NOT NULL, intent TEXT NOT NULL, count INTEGER NOT NULL, " +
                            "PRIMARY KEY(package_name, intent))",
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO semantic_feedback_priors(package_name, intent, count) " +
                            "SELECT package_name, CASE WHEN is_ad = 1 THEN 'MARKETING' " +
                            "ELSE 'TRANSACTIONAL' END, SUM(count) FROM ad_feedback_priors " +
                            "GROUP BY package_name, is_ad",
                    )
                }
            }

        /** Encrypted opt-in content history plus richer content-free daily analytics. */
        val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    migrateEventHistoryToV12(db)
                    migrateDailyInsightsToV12(db)
                }
            }

        /** Marks bounded daily breakdowns honestly and indexes post-time analytics. */
        val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE daily_insights ADD COLUMN rule_breakdown_complete " +
                            "INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "ALTER TABLE daily_insights ADD COLUMN monitor_rule_breakdown_complete " +
                            "INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "ALTER TABLE daily_insights ADD COLUMN app_breakdown_complete " +
                            "INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "ALTER TABLE daily_insights ADD COLUMN channel_breakdown_complete " +
                            "INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_notification_events_posted_at_millis " +
                            "ON notification_events (posted_at_millis)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_notification_events_posted_epoch_day_undone " +
                            "ON notification_events (posted_epoch_day, undone)",
                    )
                }
            }

        /** Adds dedicated, completeness-aware occurrence storage for restart-safe frequency rules. */
        val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS active_notification_rate_occurrences (" +
                            "listener_key_hmac TEXT NOT NULL, occurrence_id TEXT NOT NULL, " +
                            "package_name TEXT NOT NULL, channel_id TEXT, " +
                            "last_posted_at_millis INTEGER NOT NULL, " +
                            "PRIMARY KEY(listener_key_hmac))",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "index_active_notification_rate_occurrences_occurrence_id " +
                            "ON active_notification_rate_occurrences (occurrence_id)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "index_active_notification_rate_occurrences_last_posted_at_millis_occurrence_id " +
                            "ON active_notification_rate_occurrences " +
                            "(last_posted_at_millis, occurrence_id)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS notification_rate_occurrence_history (" +
                            "occurrence_id TEXT NOT NULL, package_name TEXT NOT NULL, " +
                            "channel_id TEXT, latest_posted_at_millis INTEGER NOT NULL, " +
                            "PRIMARY KEY(occurrence_id))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "index_notification_rate_occurrence_history_latest_posted_at_millis_occurrence_id " +
                            "ON notification_rate_occurrence_history " +
                            "(latest_posted_at_millis, occurrence_id)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS notification_rate_state (" +
                            "singleton_id INTEGER NOT NULL, incomplete_until_millis INTEGER NOT NULL, " +
                            "PRIMARY KEY(singleton_id))",
                    )
                    db.execSQL(
                        "INSERT INTO notification_rate_state " +
                            "(singleton_id, incomplete_until_millis) VALUES (0, ?)",
                        arrayOf(legacyRateIncompleteUntilMillis(db)),
                    )
                }
            }

        /**
         * Preserves bounded local semantic learning independently of raw history, snapshots each
         * rollup's completeness, and records durable provenance for later source loss. Legacy
         * rollups default to incomplete because their original source coverage cannot be proven.
         */
        val MIGRATION_14_15 =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS local_semantic_feedback (" +
                            "source_event_id INTEGER NOT NULL, package_name TEXT NOT NULL, " +
                            "corrected_intent TEXT NOT NULL, recorded_at_millis INTEGER NOT NULL, " +
                            "PRIMARY KEY(source_event_id))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "index_local_semantic_feedback_package_name_corrected_intent " +
                            "ON local_semantic_feedback (package_name, corrected_intent)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "index_local_semantic_feedback_recorded_at_millis_source_event_id " +
                            "ON local_semantic_feedback (recorded_at_millis, source_event_id)",
                    )
                    db.execSQL(
                        "INSERT INTO local_semantic_feedback " +
                            "(source_event_id, package_name, corrected_intent, recorded_at_millis) " +
                            "SELECT notification_event_id, package_name, corrected_intent, analyzed_at_millis " +
                            "FROM llm_observations WHERE corrected_intent IN " +
                            "('MARKETING', 'TRANSACTIONAL', 'SECURITY', 'DELIVERY', " +
                            "'SOCIAL', 'OTHER', 'AMBIGUOUS') " +
                            "ORDER BY analyzed_at_millis DESC, notification_event_id DESC LIMIT 25000",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS daily_insight_source_gaps (" +
                            "epoch_day INTEGER NOT NULL, PRIMARY KEY(epoch_day))",
                    )
                    db.execSQL(
                        "ALTER TABLE daily_insights ADD COLUMN source_complete " +
                            "INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }

        private fun migrateEventHistoryToV12(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notification_events ADD COLUMN channel_name TEXT")
            db.execSQL("ALTER TABLE notification_events ADD COLUMN posted_epoch_day INTEGER")
            db.execSQL("ALTER TABLE notification_events ADD COLUMN posted_minute_of_day INTEGER")
            db.execSQL("ALTER TABLE notification_events ADD COLUMN importance TEXT")
            db.execSQL("ALTER TABLE notification_events ADD COLUMN is_conversation INTEGER")
            db.execSQL("ALTER TABLE notification_events ADD COLUMN is_foreground_service INTEGER")
            db.execSQL(
                "ALTER TABLE notification_events ADD COLUMN had_encrypted_content " +
                    "INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_notification_events_channel_id " +
                    "ON notification_events (channel_id)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_notification_events_matched_rule_id " +
                    "ON notification_events (matched_rule_id)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_notification_events_recorded_at_millis_undone " +
                    "ON notification_events (recorded_at_millis, undone)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS encrypted_notification_contents (" +
                    "event_id INTEGER NOT NULL, format_version INTEGER NOT NULL, " +
                    "aad_id TEXT NOT NULL, nonce BLOB NOT NULL, ciphertext BLOB NOT NULL, " +
                    "created_at_millis INTEGER NOT NULL, PRIMARY KEY(event_id), " +
                    "FOREIGN KEY(event_id) REFERENCES notification_events(id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_encrypted_notification_contents_created_at_millis " +
                    "ON encrypted_notification_contents (created_at_millis)",
            )
        }

        private fun legacyRateIncompleteUntilMillis(db: SupportSQLiteDatabase): Long {
            val maxPostedAtMillis =
                db.query("SELECT MAX(posted_at_millis) FROM notification_events").use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }
            val anchorMillis =
                maxOf(
                    System.currentTimeMillis(),
                    maxPostedAtMillis ?: Long.MIN_VALUE,
                )
            val incompleteWindow = com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS + 1L
            return if (anchorMillis > Long.MAX_VALUE - incompleteWindow) {
                Long.MAX_VALUE
            } else {
                anchorMillis + incompleteWindow
            }
        }

        private fun migrateDailyInsightsToV12(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE daily_insights ADD COLUMN ml_classified_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "ALTER TABLE daily_insights ADD COLUMN category_correction_count INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE daily_insights ADD COLUMN semantic_correction_count INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL("ALTER TABLE daily_insights ADD COLUMN breakdown_version INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE daily_insight_channel_counts ADD COLUMN channel_name TEXT")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS daily_insight_app_counts (" +
                    "epoch_day INTEGER NOT NULL, package_name TEXT NOT NULL, " +
                    "total_count INTEGER NOT NULL, silenced_count INTEGER NOT NULL, " +
                    "PRIMARY KEY(epoch_day, package_name), " +
                    "FOREIGN KEY(epoch_day) REFERENCES daily_insights(epoch_day) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_daily_insight_app_counts_epoch_day " +
                    "ON daily_insight_app_counts (epoch_day)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS daily_insight_hour_counts (" +
                    "epoch_day INTEGER NOT NULL, hour INTEGER NOT NULL, total_count INTEGER NOT NULL, " +
                    "silenced_count INTEGER NOT NULL, PRIMARY KEY(epoch_day, hour), " +
                    "FOREIGN KEY(epoch_day) REFERENCES daily_insights(epoch_day) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_daily_insight_hour_counts_epoch_day " +
                    "ON daily_insight_hour_counts (epoch_day)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS daily_insight_semantic_counts (" +
                    "epoch_day INTEGER NOT NULL, intent TEXT NOT NULL, count INTEGER NOT NULL, " +
                    "PRIMARY KEY(epoch_day, intent), " +
                    "FOREIGN KEY(epoch_day) REFERENCES daily_insights(epoch_day) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_daily_insight_semantic_counts_epoch_day " +
                    "ON daily_insight_semantic_counts (epoch_day)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS daily_insight_monitor_rule_counts (" +
                    "epoch_day INTEGER NOT NULL, rule_id TEXT NOT NULL, count INTEGER NOT NULL, " +
                    "PRIMARY KEY(epoch_day, rule_id), " +
                    "FOREIGN KEY(epoch_day) REFERENCES daily_insights(epoch_day) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_daily_insight_monitor_rule_counts_epoch_day " +
                    "ON daily_insight_monitor_rule_counts (epoch_day)",
            )
        }
    }
}
