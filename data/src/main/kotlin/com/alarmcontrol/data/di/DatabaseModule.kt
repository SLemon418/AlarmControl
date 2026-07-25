package com.alarmcontrol.data.di

import android.content.Context
import androidx.room.Room
import com.alarmcontrol.data.db.AppDatabase
import com.alarmcontrol.data.db.dao.AutomationAuditDao
import com.alarmcontrol.data.db.dao.CategoryFeedbackDao
import com.alarmcontrol.data.db.dao.DailyInsightDao
import com.alarmcontrol.data.db.dao.LlmObservationDao
import com.alarmcontrol.data.db.dao.NotificationEventDao
import com.alarmcontrol.data.db.dao.ProfileDao
import com.alarmcontrol.data.db.dao.RuleDao
import com.alarmcontrol.data.db.dao.RuleSuggestionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase =
        Room
            .databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(
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
            ).build()

    @Provides
    fun provideRuleDao(database: AppDatabase): RuleDao = database.ruleDao()

    @Provides
    fun provideNotificationEventDao(database: AppDatabase): NotificationEventDao = database.notificationEventDao()

    @Provides
    fun provideCategoryFeedbackDao(database: AppDatabase): CategoryFeedbackDao = database.categoryFeedbackDao()

    @Provides
    fun provideDailyInsightDao(database: AppDatabase): DailyInsightDao = database.dailyInsightDao()

    @Provides
    fun provideProfileDao(database: AppDatabase): ProfileDao = database.profileDao()

    @Provides
    fun provideLlmObservationDao(database: AppDatabase): LlmObservationDao = database.llmObservationDao()

    @Provides
    fun provideAutomationAuditDao(database: AppDatabase): AutomationAuditDao = database.automationAuditDao()

    @Provides
    fun provideRuleSuggestionDao(database: AppDatabase): RuleSuggestionDao = database.ruleSuggestionDao()
}
