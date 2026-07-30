package com.alarmcontrol.data.repository

import com.alarmcontrol.core.filtering.NotificationHistoryWriteFence
import com.alarmcontrol.core.filtering.RateOccurrenceLifecycleGate
import com.alarmcontrol.core.privacy.ClearedDataCounts
import com.alarmcontrol.core.privacy.DailyInsightWriteFence
import com.alarmcontrol.core.privacy.FeedbackWriteFence
import com.alarmcontrol.core.privacy.LocalDataRepository
import com.alarmcontrol.core.privacy.LocalDataResetWriteFence
import com.alarmcontrol.core.settings.ExternalAutomationAuthorizationFence
import com.alarmcontrol.core.settings.FilteringActionGate
import com.alarmcontrol.core.settings.MaintenanceSettingsSnapshot
import com.alarmcontrol.core.settings.SettingsMutationFence
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.data.db.TransactionRunner
import com.alarmcontrol.data.db.dao.AutomationAuditDao
import com.alarmcontrol.data.db.dao.CategoryFeedbackDao
import com.alarmcontrol.data.db.dao.DailyInsightDao
import com.alarmcontrol.data.db.dao.LlmObservationDao
import com.alarmcontrol.data.db.dao.NotificationEventDao
import com.alarmcontrol.data.db.dao.PendingNotificationActionDao
import com.alarmcontrol.data.db.dao.ProfileDao
import com.alarmcontrol.data.db.dao.RuleDao
import com.alarmcontrol.data.db.dao.RuleSuggestionDao
import com.alarmcontrol.data.db.entity.DailyInsightSourceGapEntity
import com.alarmcontrol.data.security.MaintenancePolicyAccessGuard
import com.alarmcontrol.data.security.NotificationContentAccessGuard
import com.alarmcontrol.data.security.NotificationContentCipher
import com.alarmcontrol.data.security.RateOccurrenceDataCleaner
import com.alarmcontrol.data.security.StoredNotificationContentCleaner
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

class LocalDataRepositoryImpl
    @Inject
    internal constructor(
        private val transactionRunner: TransactionRunner,
        private val ruleDao: RuleDao,
        private val eventDao: NotificationEventDao,
        private val pendingActionDao: PendingNotificationActionDao,
        private val feedbackDao: CategoryFeedbackDao,
        private val dailyInsightDao: DailyInsightDao,
        private val profileDao: ProfileDao,
        private val llmObservationDao: LlmObservationDao,
        private val automationAuditDao: AutomationAuditDao,
        private val ruleSuggestionDao: RuleSuggestionDao,
        private val settingsRepository: SettingsRepository,
        private val contentCipher: NotificationContentCipher,
        private val storedNotificationContentCleaner: StoredNotificationContentCleaner,
        private val contentAccessGuard: NotificationContentAccessGuard,
        private val maintenancePolicyAccessGuard: MaintenancePolicyAccessGuard,
        private val filteringActionGate: FilteringActionGate = FilteringActionGate(),
        private val rateOccurrenceLifecycleGate: RateOccurrenceLifecycleGate = RateOccurrenceLifecycleGate(),
        private val rateOccurrenceDataCleaner: RateOccurrenceDataCleaner,
        private val notificationHistoryWriteFence: NotificationHistoryWriteFence =
            NotificationHistoryWriteFence(),
        private val localDataResetWriteFence: LocalDataResetWriteFence = LocalDataResetWriteFence(),
        private val feedbackWriteFence: FeedbackWriteFence = FeedbackWriteFence(),
        private val dailyInsightWriteFence: DailyInsightWriteFence = DailyInsightWriteFence(),
        private val clock: Clock = Clock.systemDefaultZone(),
        private val externalAutomationAuthorizationFence: ExternalAutomationAuthorizationFence =
            ExternalAutomationAuthorizationFence(),
        private val settingsMutationFence: SettingsMutationFence = SettingsMutationFence(),
    ) : LocalDataRepository {
        override suspend fun clearActivityHistory(): ClearedDataCounts =
            notificationHistoryWriteFence.deleteAndAdvanceOnCommit { onCommitted ->
                feedbackWriteFence.clearAndAdvanceOnCommit { onFeedbackCommitted ->
                    dailyInsightWriteFence.clearAndAdvanceOnCommit { onDailyInsightCommitted ->
                        contentAccessGuard.withLock {
                            filteringActionGate.withRuleMutation {
                                rateOccurrenceLifecycleGate.withOperation {
                                    val rateResetAtMillis = clock.millis()
                                    val result =
                                        transactionRunner.runAndNotifyCommit(
                                            onCommitted = {
                                                onCommitted()
                                                onFeedbackCommitted()
                                                onDailyInsightCommitted()
                                                rateOccurrenceLifecycleGate.markStateCleared(rateResetAtMillis)
                                            },
                                        ) {
                                            val feedback =
                                                feedbackDao.deleteLinkedToEvents() +
                                                    llmObservationDao.deleteLocalSemanticFeedback()
                                            llmObservationDao.deleteAll()
                                            rateOccurrenceDataCleaner.clearDatabaseState(rateResetAtMillis)
                                            val pendingSourceGapDays =
                                                pendingActionDao
                                                    .getArmedSourceGapCandidates()
                                                    .map { candidate ->
                                                        candidate.postedEpochDay
                                                            ?: Instant
                                                                .ofEpochMilli(candidate.postedAtMillis)
                                                                .atZone(clock.zone)
                                                                .toLocalDate()
                                                                .toEpochDay()
                                                    }.distinct()
                                            eventDao.insertInsightSourceGaps(
                                                pendingSourceGapDays.map(::DailyInsightSourceGapEntity),
                                            )
                                            pendingActionDao.deleteAll()
                                            val events = eventDao.deleteAllWithSourceGaps(clock.zone)
                                            ClearedDataCounts(events = events, feedback = feedback)
                                        }
                                    rateOccurrenceDataCleaner.deleteHmacKey()
                                    result
                                }
                            }
                        }
                    }
                }
            }

        override suspend fun clearFeedback(): ClearedDataCounts =
            feedbackWriteFence.clearAndAdvanceOnCommit { onCommitted ->
                dailyInsightWriteFence.clearAndAdvanceOnCommit { onDailyInsightCommitted ->
                    transactionRunner.runAndNotifyCommit(
                        onCommitted = {
                            onCommitted()
                            onDailyInsightCommitted()
                        },
                    ) {
                        dailyInsightDao.deleteRollupsAffectedByLinkedFeedback()
                        val corrections = llmObservationDao.countCorrections()
                        val importedVotes =
                            llmObservationDao.countImportedPriorVotes() +
                                llmObservationDao.countSemanticImportedPriorVotes()
                        val categoryFeedback = feedbackDao.deleteAll()
                        llmObservationDao.deleteLocalSemanticFeedback()
                        llmObservationDao.clearSemanticCorrections()
                        llmObservationDao.deleteImportedPriors()
                        llmObservationDao.deleteSemanticImportedPriors()
                        ClearedDataCounts(
                            feedback = saturatedCount(categoryFeedback.toLong(), corrections.toLong(), importedVotes),
                        )
                    }
                }
            }

        override suspend fun clearDailyInsights(): ClearedDataCounts =
            dailyInsightWriteFence.clearAndAdvanceOnCommit { onCommitted ->
                transactionRunner.runAndNotifyCommit(onCommitted) {
                    val count = dailyInsightDao.deleteAll()
                    ClearedDataCounts(insightDays = count)
                }
            }

        override suspend fun clearStoredNotificationContent(): ClearedDataCounts =
            contentAccessGuard.withLock {
                storedNotificationContentCleaner.clear()
            }

        override suspend fun clearStoredNotificationContentForPackage(packageName: String): ClearedDataCounts {
            require(packageName.isNotBlank()) { "Package name is blank" }
            return contentAccessGuard.withLock {
                storedNotificationContentCleaner.clearForPackage(packageName)
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
                                deleted +=
                                    eventDao.deleteEncryptedContentsForPackage(packageName) +
                                    pendingActionDao.deleteContentsForPackage(packageName)
                            }
                            deleted
                        }
                    ClearedDataCounts(encryptedContents = count)
                } else {
                    storedNotificationContentCleaner.clear()
                }
            }

        override suspend fun clearAllDatabaseData(): ClearedDataCounts =
            settingsMutationFence.withLock {
                maintenancePolicyAccessGuard.withLock {
                    externalAutomationAuthorizationFence.withLock {
                        localDataResetWriteFence.resetAndAdvanceOnCommit { onResetCommitted ->
                            notificationHistoryWriteFence.deleteAndAdvanceOnCommit { onHistoryCommitted ->
                                feedbackWriteFence.clearAndAdvanceOnCommit { onFeedbackCommitted ->
                                    dailyInsightWriteFence.clearAndAdvanceOnCommit { onDailyInsightCommitted ->
                                        val postCommit =
                                            contentAccessGuard.withLock {
                                                filteringActionGate.withRuleMutation {
                                                    rateOccurrenceLifecycleGate.withOperation {
                                                        val rateResetAtMillis = clock.millis()
                                                        var preparedCounts: ClearedDataCounts? = null
                                                        var committed = false
                                                        val failures = mutableListOf<Throwable>()
                                                        val transactionResult =
                                                            runCatching {
                                                                transactionRunner.runAndNotifyCommit(
                                                                    onCommitted = {
                                                                        onResetCommitted()
                                                                        onHistoryCommitted()
                                                                        onFeedbackCommitted()
                                                                        onDailyInsightCommitted()
                                                                        rateOccurrenceLifecycleGate
                                                                            .markStateCleared(rateResetAtMillis)
                                                                        committed = true
                                                                    },
                                                                ) {
                                                                    clearAllRoomData(rateResetAtMillis).also {
                                                                        preparedCounts = it
                                                                    }
                                                                }
                                                            }
                                                        if (!committed) {
                                                            transactionResult.getOrThrow()
                                                            error("Room clear returned without reporting its commit")
                                                        }
                                                        transactionResult.exceptionOrNull()?.let(failures::add)
                                                        failures.captureCleanupFailure(contentCipher::deleteKey)
                                                        failures.captureCleanupFailure(
                                                            rateOccurrenceDataCleaner::deleteHmacKey,
                                                        )
                                                        PostCommitClearResult(
                                                            counts = requireNotNull(preparedCounts),
                                                            failures = failures,
                                                        )
                                                    }
                                                }
                                            }
                                        withContext(NonCancellable) {
                                            postCommit.failures.captureCleanupFailure {
                                                settingsRepository.resetWhileMaintenanceLocked()
                                            }
                                        }
                                        postCommit.failures.throwFirstWithSuppressed()
                                        postCommit.counts
                                    }
                                }
                            }
                        }
                    }
                }
            }

        private suspend fun clearAllRoomData(rateResetAtMillis: Long): ClearedDataCounts {
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
                    encryptedContents =
                        eventDao.countEncryptedContents() +
                            pendingActionDao.countContents(),
                )
            feedbackDao.deleteAll()
            llmObservationDao.deleteLocalSemanticFeedback()
            llmObservationDao.deleteAll()
            llmObservationDao.deleteImportedPriors()
            llmObservationDao.deleteSemanticImportedPriors()
            ruleSuggestionDao.deleteAllDismissals()
            rateOccurrenceDataCleaner.clearDatabaseState(rateResetAtMillis)
            pendingActionDao.deleteAll()
            eventDao.deleteAll()
            dailyInsightDao.deleteAll()
            dailyInsightDao.deleteAllSourceGaps()
            profileDao.deleteAll()
            ruleDao.deleteAllRules()
            automationAuditDao.deleteAll()
            return counts
        }
    }

private fun saturatedCount(vararg counts: Long): Int =
    counts
        .fold(0L) { total, count -> (total + count).coerceAtMost(Int.MAX_VALUE.toLong()) }
        .toInt()

private data class PostCommitClearResult(
    val counts: ClearedDataCounts,
    val failures: MutableList<Throwable>,
)

private inline fun MutableList<Throwable>.captureCleanupFailure(cleanup: () -> Unit) {
    runCatching(cleanup).exceptionOrNull()?.let(::add)
}

private fun List<Throwable>.throwFirstWithSuppressed() {
    val first = firstOrNull() ?: return
    drop(1).forEach(first::addSuppressed)
    throw first
}
