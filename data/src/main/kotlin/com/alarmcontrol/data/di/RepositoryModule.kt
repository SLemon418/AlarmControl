package com.alarmcontrol.data.di

import com.alarmcontrol.core.automation.AutomationAuditRepository
import com.alarmcontrol.core.backup.BackupRepository
import com.alarmcontrol.core.feedback.AdFeedbackRepository
import com.alarmcontrol.core.feedback.FeedbackRepository
import com.alarmcontrol.core.filtering.NotificationActionOutbox
import com.alarmcontrol.core.filtering.NotificationEventRepository
import com.alarmcontrol.core.filtering.NotificationHistoryRepository
import com.alarmcontrol.core.filtering.RateOccurrenceRepository
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.filtering.RuleSuggestionRepository
import com.alarmcontrol.core.insights.DailyInsightRepository
import com.alarmcontrol.core.insights.InsightsAnalyticsRepository
import com.alarmcontrol.core.insights.InsightsSummaryRepository
import com.alarmcontrol.core.privacy.LocalDataRepository
import com.alarmcontrol.core.profile.ProfileRepository
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.data.db.RoomTransactionRunner
import com.alarmcontrol.data.db.TransactionRunner
import com.alarmcontrol.data.repository.AdFeedbackRepositoryImpl
import com.alarmcontrol.data.repository.AutomationAuditRepositoryImpl
import com.alarmcontrol.data.repository.BackupRepositoryImpl
import com.alarmcontrol.data.repository.DailyInsightRepositoryImpl
import com.alarmcontrol.data.repository.FeedbackRepositoryImpl
import com.alarmcontrol.data.repository.InsightsAnalyticsRepositoryImpl
import com.alarmcontrol.data.repository.InsightsSummaryRepositoryImpl
import com.alarmcontrol.data.repository.LocalDataRepositoryImpl
import com.alarmcontrol.data.repository.NotificationActionOutboxImpl
import com.alarmcontrol.data.repository.NotificationEventRepositoryImpl
import com.alarmcontrol.data.repository.ProfileRepositoryImpl
import com.alarmcontrol.data.repository.RateOccurrenceRepositoryImpl
import com.alarmcontrol.data.repository.RuleRepositoryImpl
import com.alarmcontrol.data.repository.RuleSuggestionRepositoryImpl
import com.alarmcontrol.data.repository.SettingsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindAutomationAuditRepository(impl: AutomationAuditRepositoryImpl): AutomationAuditRepository

    @Binds
    @Singleton
    abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner

    @Binds
    @Singleton
    abstract fun bindRuleRepository(impl: RuleRepositoryImpl): RuleRepository

    @Binds
    @Singleton
    abstract fun bindNotificationEventRepository(impl: NotificationEventRepositoryImpl): NotificationEventRepository

    @Binds
    @Singleton
    abstract fun bindNotificationActionOutbox(impl: NotificationActionOutboxImpl): NotificationActionOutbox

    @Binds
    @Singleton
    abstract fun bindNotificationHistoryRepository(impl: NotificationEventRepositoryImpl): NotificationHistoryRepository

    @Binds
    @Singleton
    abstract fun bindRateOccurrenceRepository(impl: RateOccurrenceRepositoryImpl): RateOccurrenceRepository

    @Binds
    @Singleton
    abstract fun bindFeedbackRepository(impl: FeedbackRepositoryImpl): FeedbackRepository

    @Binds
    @Singleton
    abstract fun bindAdFeedbackRepository(impl: AdFeedbackRepositoryImpl): AdFeedbackRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindInsightsSummaryRepository(impl: InsightsSummaryRepositoryImpl): InsightsSummaryRepository

    @Binds
    @Singleton
    abstract fun bindDailyInsightRepository(impl: DailyInsightRepositoryImpl): DailyInsightRepository

    @Binds
    @Singleton
    abstract fun bindInsightsAnalyticsRepository(impl: InsightsAnalyticsRepositoryImpl): InsightsAnalyticsRepository

    @Binds
    @Singleton
    abstract fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository

    @Binds
    @Singleton
    abstract fun bindLocalDataRepository(impl: LocalDataRepositoryImpl): LocalDataRepository

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds
    @Singleton
    abstract fun bindRuleSuggestionRepository(impl: RuleSuggestionRepositoryImpl): RuleSuggestionRepository
}
