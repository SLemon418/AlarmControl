package com.alarmcontrol.service

import com.alarmcontrol.core.automation.AutomationAuditRepository
import com.alarmcontrol.core.filtering.NotificationEventRepository
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.insights.DailyInsightRepository
import com.alarmcontrol.core.profile.ProfileRepository
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.data.db.dao.NotificationEventDao
import com.alarmcontrol.data.testing.DeviceValidationDataAccess
import com.alarmcontrol.ml.llm.OnDeviceLlmManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Debug-only access to production repositories for Android-runtime validation.
 *
 * This entry point is absent from release builds. Instrumented tests use it to exercise the real
 * notification-listener path without exposing test controls in the shipped application.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DeviceValidationEntryPoint {
    fun ruleRepository(): RuleRepository

    fun eventRepository(): NotificationEventRepository

    fun notificationEventDao(): NotificationEventDao

    fun dailyInsightRepository(): DailyInsightRepository

    fun settingsRepository(): SettingsRepository

    fun profileRepository(): ProfileRepository

    fun automationAuditRepository(): AutomationAuditRepository

    fun deviceValidationDataAccess(): DeviceValidationDataAccess

    fun onDeviceLlmManager(): OnDeviceLlmManager
}
