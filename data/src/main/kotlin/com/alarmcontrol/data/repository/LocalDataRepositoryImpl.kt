package com.alarmcontrol.data.repository

import com.alarmcontrol.core.privacy.ClearedDataCounts
import com.alarmcontrol.core.privacy.LocalDataRepository
import com.alarmcontrol.data.db.TransactionRunner
import com.alarmcontrol.data.db.dao.AutomationAuditDao
import com.alarmcontrol.data.db.dao.CategoryFeedbackDao
import com.alarmcontrol.data.db.dao.DailyInsightDao
import com.alarmcontrol.data.db.dao.LlmObservationDao
import com.alarmcontrol.data.db.dao.NotificationEventDao
import com.alarmcontrol.data.db.dao.ProfileDao
import com.alarmcontrol.data.db.dao.RuleDao
import com.alarmcontrol.data.db.dao.RuleSuggestionDao
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
        private val contentCipher: NotificationContentCipher,
    ) : LocalDataRepository {
        override suspend fun clearActivityHistory(): ClearedDataCounts =
            transactionRunner.run {
                val feedback = feedbackDao.deleteLinkedToEvents() + llmObservationDao.countCorrections()
                llmObservationDao.deleteAll()
                val events = eventDao.deleteAll()
                ClearedDataCounts(events = events, feedback = feedback)
            }

        override suspend fun clearFeedback(): ClearedDataCounts =
            transactionRunner.run {
                val importedVotes = llmObservationDao.countSemanticImportedPriorVotes()
                ClearedDataCounts(
                    feedback = feedbackDao.deleteAll() + llmObservationDao.clearCorrections() + importedVotes,
                ).also {
                    llmObservationDao.clearSemanticCorrections()
                    llmObservationDao.deleteImportedPriors()
                    llmObservationDao.deleteSemanticImportedPriors()
                }
            }

        override suspend fun clearDailyInsights(): ClearedDataCounts =
            transactionRunner.run { ClearedDataCounts(insightDays = dailyInsightDao.deleteAll()) }

        override suspend fun clearStoredNotificationContent(): ClearedDataCounts {
            val count = transactionRunner.run { eventDao.deleteAllEncryptedContents() }
            contentCipher.deleteKey()
            return ClearedDataCounts(encryptedContents = count)
        }

        override suspend fun clearStoredNotificationContentForPackage(packageName: String): ClearedDataCounts {
            require(packageName.isNotBlank()) { "Package name is blank" }
            val count = transactionRunner.run { eventDao.deleteEncryptedContentsForPackage(packageName) }
            return ClearedDataCounts(encryptedContents = count)
        }

        override suspend fun clearAllDatabaseData(): ClearedDataCounts {
            val result =
                transactionRunner.run {
                    val counts =
                        ClearedDataCounts(
                            rules = ruleDao.countAll(),
                            profiles = profileDao.countAll(),
                            events = eventDao.countAll(),
                            feedback =
                                feedbackDao.countAll() +
                                    llmObservationDao.countCorrections() +
                                    llmObservationDao.countSemanticImportedPriorVotes(),
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
            return result
        }
    }
