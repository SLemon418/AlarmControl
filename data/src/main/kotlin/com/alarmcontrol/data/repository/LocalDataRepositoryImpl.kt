package com.alarmcontrol.data.repository

import com.alarmcontrol.core.privacy.ClearedDataCounts
import com.alarmcontrol.core.privacy.LocalDataRepository
import com.alarmcontrol.core.settings.MaintenanceSettingsSnapshot
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.data.db.TransactionRunner
import com.alarmcontrol.data.db.dao.AutomationAuditDao
import com.alarmcontrol.data.db.dao.CategoryFeedbackDao
import com.alarmcontrol.data.db.dao.DailyInsightDao
import com.alarmcontrol.data.db.dao.LlmObservationDao
import com.alarmcontrol.data.db.dao.NotificationEventDao
import com.alarmcontrol.data.db.dao.ProfileDao
import com.alarmcontrol.data.db.dao.RuleDao
import com.alarmcontrol.data.db.dao.RuleSuggestionDao
import com.alarmcontrol.data.security.MaintenancePolicyAccessGuard
import com.alarmcontrol.data.security.NotificationContentAccessGuard
import com.alarmcontrol.data.security.NotificationContentCipher
import javax.inject.Inject

class LocalDataRepositoryImpl
    @Inject
    internal constructor(
        private val transactionRunner: TransactionRunner,
        private val ruleDao: RuleDao,
        private val eventDao: NotificationEventDao,
        private val feedbackDao: CategoryFeedbackDao,
        private val dailyInsightDao: DailyInsightDao,
        private val profileDao: ProfileDao,
        private val llmObservationDao: LlmObservationDao,
        private val automationAuditDao: AutomationAuditDao,
        private val ruleSuggestionDao: RuleSuggestionDao,
        private val settingsRepository: SettingsRepository,
        private val contentCipher: NotificationContentCipher,
        private val contentAccessGuard: NotificationContentAccessGuard,
        private val maintenancePolicyAccessGuard: MaintenancePolicyAccessGuard,
    ) : LocalDataRepository {
        override suspend fun clearActivityHistory(): ClearedDataCounts =
            contentAccessGuard.withLock {
                transactionRunner.run {
                    val feedback = feedbackDao.deleteLinkedToEvents() + llmObservationDao.countCorrections()
                    llmObservationDao.deleteAll()
                    val events = eventDao.deleteAll()
                    ClearedDataCounts(events = events, feedback = feedback)
                }
            }

        override suspend fun clearFeedback(): ClearedDataCounts =
            transactionRunner.run {
                val corrections = llmObservationDao.countCorrections()
                val importedVotes =
                    llmObservationDao.countImportedPriorVotes() +
                        llmObservationDao.countSemanticImportedPriorVotes()
                val categoryFeedback = feedbackDao.deleteAll()
                llmObservationDao.clearSemanticCorrections()
                llmObservationDao.deleteImportedPriors()
                llmObservationDao.deleteSemanticImportedPriors()
                ClearedDataCounts(
                    feedback = saturatedCount(categoryFeedback.toLong(), corrections.toLong(), importedVotes),
                )
            }

        override suspend fun clearDailyInsights(): ClearedDataCounts =
            transactionRunner.run { ClearedDataCounts(insightDays = dailyInsightDao.deleteAll()) }

        override suspend fun clearStoredNotificationContent(): ClearedDataCounts =
            contentAccessGuard.withLock {
                val count = transactionRunner.run { eventDao.deleteAllEncryptedContents() }
                contentCipher.deleteKey()
                ClearedDataCounts(encryptedContents = count)
            }

        override suspend fun clearStoredNotificationContentForPackage(packageName: String): ClearedDataCounts {
            require(packageName.isNotBlank()) { "Package name is blank" }
            return contentAccessGuard.withLock {
                val count = transactionRunner.run { eventDao.deleteEncryptedContentsForPackage(packageName) }
                ClearedDataCounts(encryptedContents = count)
            }
        }

        override suspend fun reconcileStoredNotificationContentPolicy(): ClearedDataCounts =
            maintenancePolicyAccessGuard.withLock {
                reconcileStoredNotificationContentPolicy(settingsRepository.maintenanceSnapshot())
            }

        override suspend fun reconcileStoredNotificationContentPolicy(
            policy: MaintenanceSettingsSnapshot,
        ): ClearedDataCounts =
            contentAccessGuard.withLock {
                if (policy.notificationContentStorageEnabled) {
                    val count =
                        transactionRunner.run {
                            var deleted = 0
                            policy.contentExcludedPackages.forEach { packageName ->
                                deleted += eventDao.deleteEncryptedContentsForPackage(packageName)
                            }
                            deleted
                        }
                    ClearedDataCounts(encryptedContents = count)
                } else {
                    val count = transactionRunner.run { eventDao.deleteAllEncryptedContents() }
                    contentCipher.deleteKey()
                    ClearedDataCounts(encryptedContents = count)
                }
            }

        override suspend fun clearAllDatabaseData(): ClearedDataCounts =
            contentAccessGuard.withLock {
                val result =
                    transactionRunner.run {
                        val counts =
                            ClearedDataCounts(
                                rules = ruleDao.countAll(),
                                profiles = profileDao.countAll(),
                                events = eventDao.countAll(),
                                feedback =
                                    saturatedCount(
                                        feedbackDao.countAll().toLong(),
                                        llmObservationDao.countCorrections().toLong(),
                                        llmObservationDao.countImportedPriorVotes(),
                                        llmObservationDao.countSemanticImportedPriorVotes(),
                                    ),
                                insightDays = dailyInsightDao.countAll(),
                                encryptedContents = eventDao.countEncryptedContents(),
                            )
                        feedbackDao.deleteAll()
                        llmObservationDao.deleteAll()
                        llmObservationDao.deleteImportedPriors()
                        llmObservationDao.deleteSemanticImportedPriors()
                        ruleSuggestionDao.deleteAllDismissals()
                        eventDao.deleteAll()
                        dailyInsightDao.deleteAll()
                        profileDao.deleteAll()
                        ruleDao.deleteAllRules()
                        automationAuditDao.deleteAll()
                        counts
                    }
                contentCipher.deleteKey()
                result
            }
    }

private fun saturatedCount(vararg counts: Long): Int =
    counts
        .fold(0L) { total, count -> (total + count).coerceAtMost(Int.MAX_VALUE.toLong()) }
        .toInt()
