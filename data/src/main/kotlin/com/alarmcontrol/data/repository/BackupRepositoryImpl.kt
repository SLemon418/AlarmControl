package com.alarmcontrol.data.repository

import com.alarmcontrol.core.backup.BackupCategoryFeedback
import com.alarmcontrol.core.backup.BackupData
import com.alarmcontrol.core.backup.BackupPreview
import com.alarmcontrol.core.backup.BackupRepository
import com.alarmcontrol.core.backup.BackupSemanticFeedback
import com.alarmcontrol.core.backup.BackupSummary
import com.alarmcontrol.core.backup.RestoreMode
import com.alarmcontrol.core.backup.RestoreOptions
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.privacy.DailyInsightWriteFence
import com.alarmcontrol.core.privacy.FeedbackWriteFence
import com.alarmcontrol.core.privacy.LocalDataResetWriteFence
import com.alarmcontrol.core.privacy.StaleLocalDataWriteException
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.core.settings.FilteringActionGate
import com.alarmcontrol.core.settings.SettingsMutationFence
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.core.settings.SettingsSnapshot
import com.alarmcontrol.data.backup.BackupCodec
import com.alarmcontrol.data.backup.BackupCryptor
import com.alarmcontrol.data.backup.BackupValidator
import com.alarmcontrol.data.backup.MAX_BACKUP_SEMANTIC_FEEDBACK_GROUPS
import com.alarmcontrol.data.backup.MAX_BACKUP_SEMANTIC_FEEDBACK_VOTES_PER_GROUP
import com.alarmcontrol.data.backup.requireBackupFileSize
import com.alarmcontrol.data.db.TransactionRunner
import com.alarmcontrol.data.db.dao.CategoryFeedbackDao
import com.alarmcontrol.data.db.dao.DailyInsightDao
import com.alarmcontrol.data.db.dao.LlmObservationDao
import com.alarmcontrol.data.db.dao.ProfileDao
import com.alarmcontrol.data.db.dao.RuleDao
import com.alarmcontrol.data.db.entity.CategoryFeedbackEntity
import com.alarmcontrol.data.db.entity.DailyInsightSourceGapEntity
import com.alarmcontrol.data.db.entity.SemanticFeedbackPriorEntity
import com.alarmcontrol.data.mapper.toDomain
import com.alarmcontrol.data.mapper.toEntity
import com.alarmcontrol.data.mapper.toPendingTree
import com.alarmcontrol.data.mapper.toRuleEntity
import com.alarmcontrol.data.mapper.toWrite
import com.alarmcontrol.data.security.MaintenancePolicyAccessGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject

/**
 * [BackupRepository] backed by the existing repositories/DAO (CLAUDE.md §3). Export reads the current
 * rules, named profiles, and daily history. Restore re-points profile memberships and historical
 * rule references at freshly-assigned ids. REPLACE overwrites selected daily history; MERGE keeps
 * an existing local day and imports only missing days.
 */
@Suppress("TooManyFunctions") // Keeps restore phases and rollback helpers inside one transactional repository.
class BackupRepositoryImpl
    @Inject
    internal constructor(
        private val transactionRunner: TransactionRunner,
        private val ruleDao: RuleDao,
        private val dailyInsightDao: DailyInsightDao,
        private val profileDao: ProfileDao,
        private val categoryFeedbackDao: CategoryFeedbackDao,
        private val llmObservationDao: LlmObservationDao,
        private val settingsRepository: SettingsRepository,
        private val filteringActionGate: FilteringActionGate = FilteringActionGate(),
        private val clock: Clock = Clock.systemUTC(),
        private val localDataResetWriteFence: LocalDataResetWriteFence = LocalDataResetWriteFence(),
        private val feedbackWriteFence: FeedbackWriteFence = FeedbackWriteFence(),
        private val dailyInsightWriteFence: DailyInsightWriteFence = DailyInsightWriteFence(),
        private val settingsMutationFence: SettingsMutationFence = SettingsMutationFence(),
        private val maintenancePolicyAccessGuard: MaintenancePolicyAccessGuard =
            MaintenancePolicyAccessGuard(),
    ) : BackupRepository {
        override suspend fun export(
            passphrase: CharArray?,
            includeLearningFeedback: Boolean,
        ): String {
            require(passphrase == null || passphrase.isEmpty() || passphrase.size >= MIN_NEW_PASSPHRASE_CHARS) {
                "New encrypted backups require at least 8 password characters"
            }
            require(!includeLearningFeedback || (passphrase != null && passphrase.isNotEmpty())) {
                "Learning feedback requires an encrypted backup"
            }
            val settings = settingsRepository.snapshot()
            val plaintext =
                BackupCodec.encode(
                    transactionRunner.run {
                        BackupData(
                            rules = ruleDao.getRulesWithConditions().map { it.toDomain() },
                            dailyInsights = dailyInsightDao.getRecent(MAX_INSIGHTS).map { it.toDomain() },
                            profiles = profileDao.getProfiles().map { it.toDomain() },
                            settings = settings,
                            categoryFeedback =
                                if (includeLearningFeedback) {
                                    categoryFeedbackDao.getEffectiveFeedback().map { it.toBackup() }
                                } else {
                                    emptyList()
                                },
                            semanticFeedback =
                                if (includeLearningFeedback) {
                                    boundedSemanticFeedback()
                                } else {
                                    emptyList()
                                },
                        )
                    },
                )
            return if (passphrase == null || passphrase.isEmpty()) {
                plaintext.requireBackupFileSize()
            } else {
                BackupCryptor.encrypt(plaintext, passphrase)
            }
        }

        override suspend fun preview(
            serialized: String,
            passphrase: CharArray?,
        ): DataResult<BackupPreview> =
            runCatchingPreservingCancellation {
                val (data, encrypted) = decodeAndValidate(serialized, passphrase)
                BackupPreview(
                    encrypted = encrypted,
                    rules = data.rules.size,
                    profiles = data.profiles.size,
                    dailyInsights = data.dailyInsights.size,
                    hasSettings = data.settings != null,
                    categoryFeedback = data.categoryFeedback.size,
                    adFeedbackVotes = data.semanticFeedback.saturatedVoteCount(),
                )
            }.fold(
                onSuccess = { DataResult.Success(it) },
                onFailure = { DataResult.Failure(it) },
            )

        // Room and DataStore expose different exception families. This boundary intentionally
        // catches every non-cancellation persistence failure so settings can be rolled back.
        @Suppress("TooGenericExceptionCaught")
        override suspend fun restore(
            serialized: String,
            passphrase: CharArray?,
            options: RestoreOptions,
        ): DataResult<BackupSummary> {
            val resetEpoch = localDataResetWriteFence.captureEpoch()
            val feedbackEpoch = feedbackWriteFence.captureEpoch()
            val dailyInsightEpoch = dailyInsightWriteFence.captureEpoch()
            return runCatchingPreservingCancellation {
                val (data, encrypted) = decodeAndValidate(serialized, passphrase)
                settingsMutationFence.withLock {
                    maintenancePolicyAccessGuard.withLock {
                        restoreDecoded(
                            data,
                            encrypted,
                            options,
                            resetEpoch,
                            feedbackEpoch,
                            dailyInsightEpoch,
                        )
                    }
                }
            }.fold(
                onSuccess = { DataResult.Success(it) },
                onFailure = { DataResult.Failure(it) },
            )
        }

        @Suppress("TooGenericExceptionCaught") // Room and DataStore persistence failures share recovery.
        private suspend fun restoreDecoded(
            data: BackupData,
            encrypted: Boolean,
            options: RestoreOptions,
            resetEpoch: LocalDataResetWriteFence.Epoch,
            feedbackEpoch: com.alarmcontrol.core.privacy.ScopedDataWriteFence.Epoch,
            dailyInsightEpoch: com.alarmcontrol.core.privacy.ScopedDataWriteFence.Epoch,
        ): BackupSummary {
            require(!options.learningFeedback || encrypted) { "Learning feedback requires encryption" }
            val priorSettings = settingsRepository.snapshotWhileMutationLocked()
            val databaseSectionsSelected =
                options.rulesAndProfiles ||
                    options.dailyInsights ||
                    options.learningFeedback
            val pauseSideEffects = databaseSectionsSelected
            val desiredSettings = data.settings
            var databaseCommitted = false
            var databaseResult = DatabaseRestoreResult()
            var settingsReviewRequired = false
            try {
                if (pauseSideEffects) {
                    disableSideEffectingSettings(priorSettings, resetEpoch)
                }
                databaseResult =
                    runDatabaseRestore(
                        data,
                        options,
                        resetEpoch,
                        feedbackEpoch,
                        dailyInsightEpoch,
                    )
                databaseCommitted = true
                when {
                    options.settings && desiredSettings != null ->
                        settingsRepository.restoreIfCurrentWhileMutationAndMaintenanceLocked(
                            desiredSettings,
                            resetEpoch,
                        )
                    pauseSideEffects ->
                        settingsRepository.restoreIfCurrentWhileMutationAndMaintenanceLocked(
                            priorSettings,
                            resetEpoch,
                        )
                }
            } catch (error: StaleLocalDataWriteException) {
                throw error
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    recoverSettingsAndThrow(
                        priorSettings = priorSettings,
                        databaseChangesCommitted = databaseCommitted && databaseSectionsSelected,
                        resetEpoch = resetEpoch,
                        error = error,
                    )
                }
            } catch (error: Exception) {
                if (databaseCommitted && databaseSectionsSelected) {
                    recoverSettingsAfterFailure(
                        priorSettings = priorSettings,
                        keepSideEffectsDisabled = true,
                        resetEpoch = resetEpoch,
                    )
                    settingsReviewRequired = true
                } else {
                    recoverSettingsAndThrow(
                        priorSettings = priorSettings,
                        databaseChangesCommitted = false,
                        resetEpoch = resetEpoch,
                        error = error,
                    )
                }
            }

            return BackupSummary(
                rulesRestored = if (options.rulesAndProfiles) data.rules.size else 0,
                insightsRestored = databaseResult.restoredInsightCount,
                profilesRestored = if (options.rulesAndProfiles) data.profiles.size else 0,
                settingsRestored =
                    options.settings &&
                        data.settings != null &&
                        !settingsReviewRequired,
                feedbackRestored =
                    if (options.learningFeedback) {
                        databaseResult.restoredFeedbackCount
                    } else {
                        0
                    },
                settingsReviewRequired = settingsReviewRequired,
                insightConflictsSkipped = databaseResult.skippedInsightCount,
            )
        }

        private fun List<BackupSemanticFeedback>.saturatedVoteCount(): Int =
            fold(0L) { total, feedback -> total + feedback.count }
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

        private suspend fun boundedSemanticFeedback(): List<BackupSemanticFeedback> {
            val localFeedback = llmObservationDao.getLocalSemanticFeedback()
            val localGroups =
                localFeedback.mapTo(mutableSetOf()) { it.packageName to it.correctedIntent }
            val counts = mutableMapOf<Pair<String, String>, Long>()
            localFeedback.forEach {
                val key = it.packageName to it.correctedIntent
                counts[key] = (counts[key] ?: 0L) + 1L
            }
            llmObservationDao.getSemanticImportedPriors().forEach {
                val key = it.packageName to it.intent
                counts[key] = (counts[key] ?: 0L) + it.count.toLong()
            }
            return counts
                .entries
                .sortedWith(
                    compareByDescending<Map.Entry<Pair<String, String>, Long>> {
                        it.key in localGroups
                    }.thenByDescending { it.value }
                        .thenBy { it.key.first }
                        .thenBy { it.key.second },
                ).take(MAX_BACKUP_SEMANTIC_FEEDBACK_GROUPS)
                .map {
                    BackupSemanticFeedback(
                        packageName = it.key.first,
                        intent =
                            com.alarmcontrol.core.filtering.SemanticIntent
                                .valueOf(it.key.second),
                        count =
                            it.value
                                .coerceAtMost(
                                    MAX_BACKUP_SEMANTIC_FEEDBACK_VOTES_PER_GROUP.toLong(),
                                ).toInt(),
                    )
                }
        }

        private suspend fun disableSideEffectingSettings(
            priorSettings: SettingsSnapshot,
            resetEpoch: LocalDataResetWriteFence.Epoch,
        ) = settingsRepository.restoreIfCurrentWhileMutationAndMaintenanceLocked(
            priorSettings.copy(
                filteringEnabled = false,
                externalAutomationEnabled = false,
                llmAutoActionsEnabled = false,
            ),
            resetEpoch,
        )

        private suspend fun recoverSettingsAndThrow(
            priorSettings: SettingsSnapshot,
            databaseChangesCommitted: Boolean,
            resetEpoch: LocalDataResetWriteFence.Epoch,
            error: Throwable,
        ): Nothing {
            recoverSettingsAfterFailure(priorSettings, databaseChangesCommitted, resetEpoch)
            throw error
        }

        private suspend fun recoverSettingsAfterFailure(
            priorSettings: SettingsSnapshot,
            keepSideEffectsDisabled: Boolean,
            resetEpoch: LocalDataResetWriteFence.Epoch,
        ) {
            val safeSettings =
                if (keepSideEffectsDisabled) {
                    priorSettings.copy(
                        filteringEnabled = false,
                        externalAutomationEnabled = false,
                        llmAutoActionsEnabled = false,
                    )
                } else {
                    priorSettings
                }
            runCatchingPreservingCancellation {
                settingsRepository.restoreIfCurrentWhileMutationAndMaintenanceLocked(
                    safeSettings,
                    resetEpoch,
                )
            }
        }

        private suspend fun runDatabaseRestore(
            data: BackupData,
            options: RestoreOptions,
            resetEpoch: LocalDataResetWriteFence.Epoch,
            feedbackEpoch: com.alarmcontrol.core.privacy.ScopedDataWriteFence.Epoch,
            dailyInsightEpoch: com.alarmcontrol.core.privacy.ScopedDataWriteFence.Epoch,
        ): DatabaseRestoreResult {
            if (!options.rulesAndProfiles && !options.dailyInsights && !options.learningFeedback) {
                return DatabaseRestoreResult()
            }
            return localDataResetWriteFence.writeIfCurrent(resetEpoch) {
                feedbackWriteFence.writeIfCurrent(feedbackEpoch) {
                    dailyInsightWriteFence.writeIfCurrent(dailyInsightEpoch) {
                        if (options.rulesAndProfiles) {
                            filteringActionGate.withRuleMutation {
                                transactionRunner.run { restoreDatabase(data, options) }
                            }
                        } else {
                            transactionRunner.run { restoreDatabase(data, options) }
                        }
                    } ?: staleRestoreWrite()
                } ?: staleRestoreWrite()
            } ?: staleRestoreWrite()
        }

        private suspend fun restoreDatabase(
            data: BackupData,
            options: RestoreOptions,
        ): DatabaseRestoreResult {
            val now = clock.millis()
            if (options.mode == RestoreMode.REPLACE && options.learningFeedback) {
                // Invalidate while linked local corrections still exist. Imported rollups written
                // below have no portable event identity and must not be deleted afterward.
                dailyInsightDao.deleteRollupsAffectedByLinkedFeedback()
            }
            val idRemap =
                if (options.rulesAndProfiles) {
                    restoreRulesAndProfiles(data, options.mode, now)
                } else {
                    emptyMap()
                }

            var restoredInsightCount = 0
            var skippedInsightCount = 0
            if (options.dailyInsights) {
                if (options.mode == RestoreMode.REPLACE) {
                    dailyInsightDao.deleteAll()
                }
                val existingDays =
                    if (options.mode == RestoreMode.MERGE && data.dailyInsights.isNotEmpty()) {
                        dailyInsightDao
                            .getEpochDaysBetween(
                                data.dailyInsights.minOf { it.epochDay },
                                data.dailyInsights.maxOf { it.epochDay },
                            ).toSet()
                    } else {
                        emptySet()
                    }
                data.dailyInsights.forEach { insight ->
                    // Rollups have no source-event identity across backups. Preserving the local
                    // day avoids both destructive overwrite and unverifiable double counting.
                    if (insight.epochDay in existingDays) {
                        skippedInsightCount += 1
                        return@forEach
                    }
                    val remapped =
                        insight.copy(
                            topRules =
                                insight.topRules.map { count ->
                                    count.copy(ruleId = count.ruleId.remapOrMarkDeleted(idRemap))
                                },
                            topMonitoredRules =
                                insight.topMonitoredRules.map { count ->
                                    count.copy(ruleId = count.ruleId.remapOrMarkDeleted(idRemap))
                                },
                        )
                    dailyInsightDao.store(remapped.toWrite())
                    dailyInsightDao.insertSourceGaps(
                        listOf(DailyInsightSourceGapEntity(remapped.epochDay)),
                    )
                    restoredInsightCount += 1
                }
            }

            val restoredFeedbackCount =
                if (options.learningFeedback) {
                    restoreFeedback(data, options.mode)
                } else {
                    0
                }
            return DatabaseRestoreResult(
                restoredInsightCount,
                skippedInsightCount,
                restoredFeedbackCount,
            )
        }

        private suspend fun restoreRulesAndProfiles(
            data: BackupData,
            mode: RestoreMode,
            now: Long,
        ): Map<String, String> {
            val existingRules = ruleDao.getRulesWithConditions().map { it.toDomain() }
            val existingProfiles = profileDao.getProfiles().map { it.toDomain() }
            if (mode == RestoreMode.REPLACE) {
                profileDao.deleteAll()
                ruleDao.deleteAllRules()
            }

            val idRemap = mutableMapOf<String, String>()
            data.rules.forEach { rule ->
                val reusable =
                    if (mode == RestoreMode.MERGE) existingRules.firstOrNull { it.sameDefinitionAs(rule) } else null
                val localId =
                    reusable?.id ?: ruleDao
                        .storeRuleWithConditions(
                            rule.toRuleEntity(id = 0, createdAtMillis = now, updatedAtMillis = now),
                            rule.condition.toPendingTree(),
                        ).toString()
                idRemap[rule.id] = localId
            }

            data.profiles.forEach { profile ->
                val remappedRuleIds = profile.ruleIds.mapTo(mutableSetOf()) { idRemap.getValue(it) }
                val reusable =
                    if (mode == RestoreMode.MERGE) {
                        existingProfiles.firstOrNull { it.name.equals(profile.name, ignoreCase = true) }
                    } else {
                        null
                    }
                profileDao.store(
                    profile.toEntity(id = reusable?.id?.toLongOrNull() ?: 0, nowMillis = now),
                    (remappedRuleIds + reusable.orEmptyRuleIds()).mapTo(mutableSetOf(), String::toLong),
                )
            }
            return idRemap
        }

        private suspend fun restoreFeedback(
            data: BackupData,
            mode: RestoreMode,
        ): Int {
            if (mode == RestoreMode.REPLACE) {
                categoryFeedbackDao.deleteAll()
                llmObservationDao.deleteLocalSemanticFeedback()
                llmObservationDao.deleteImportedPriors()
                llmObservationDao.clearSemanticCorrections()
                llmObservationDao.deleteSemanticImportedPriors()
            }
            val incomingSemanticFeedback =
                data.semanticFeedback
                    .groupingBy { it.packageName to it.intent.name }
                    .fold(0L) { total, row -> Math.addExact(total, row.count.toLong()) }
            val finalSemanticFeedback =
                llmObservationDao
                    .getSemanticFeedbackCounts()
                    .associate { (it.packageName to it.intent) to it.count.toLong() }
                    .toMutableMap()
                    .apply {
                        incomingSemanticFeedback.forEach { (key, count) ->
                            this[key] = Math.addExact(this[key] ?: 0L, count)
                        }
                    }.map { (key, count) ->
                        require(count in 1L..Int.MAX_VALUE.toLong()) {
                            "Semantic feedback count is invalid"
                        }
                        BackupSemanticFeedback(
                            packageName = key.first,
                            intent =
                                com.alarmcontrol.core.filtering.SemanticIntent
                                    .valueOf(key.second),
                            count = count.toInt(),
                        )
                    }
            BackupValidator.requireValidSemanticFeedback(finalSemanticFeedback)

            val incomingCategoryFeedback = data.categoryFeedback.map { it.toEntity() }
            val categoryFeedbackToInsert =
                if (mode == RestoreMode.MERGE) {
                    val available =
                        (CategoryFeedbackDao.MAX_RETAINED_ROWS - categoryFeedbackDao.countAll())
                            .coerceAtLeast(0)
                    incomingCategoryFeedback
                        .sortedByDescending(CategoryFeedbackEntity::recordedAtMillis)
                        .take(available)
                } else {
                    incomingCategoryFeedback
                }
            categoryFeedbackDao.insertAll(categoryFeedbackToInsert)
            categoryFeedbackDao.trimToMostRecent(CategoryFeedbackDao.MAX_RETAINED_ROWS)

            val existing =
                if (mode == RestoreMode.MERGE) {
                    llmObservationDao.getSemanticImportedPriors().associateBy { it.packageName to it.intent }
                } else {
                    emptyMap()
                }
            val imported =
                incomingSemanticFeedback.map { (key, count) ->
                    val mergedCount =
                        Math.addExact(
                            count,
                            existing[key]?.count?.toLong() ?: 0L,
                        )
                    require(mergedCount <= Int.MAX_VALUE) { "Imported feedback count is too large" }
                    SemanticFeedbackPriorEntity(
                        packageName = key.first,
                        intent = key.second,
                        count = mergedCount.toInt(),
                    )
                }
            llmObservationDao.upsertSemanticImportedPriors(imported)
            return (
                categoryFeedbackToInsert.size.toLong() +
                    data.semanticFeedback.saturatedVoteCount()
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private fun decodeAndValidate(
            serialized: String,
            passphrase: CharArray?,
        ): Pair<BackupData, Boolean> {
            serialized.requireBackupFileSize()
            val encrypted = BackupCryptor.isEncrypted(serialized)
            val plaintext =
                if (encrypted) {
                    BackupCryptor.decrypt(serialized, requireNotNull(passphrase) { "Backup password required" })
                } else {
                    serialized
                }
            val data = BackupValidator.validate(BackupCodec.decode(plaintext), clock.millis())
            require(
                encrypted ||
                    (data.categoryFeedback.isEmpty() && data.adFeedback.isEmpty() && data.semanticFeedback.isEmpty()),
            ) {
                "Learning feedback is only accepted from an encrypted backup"
            }
            return data to encrypted
        }

        private companion object {
            // Wide enough to capture the full retained history (decades of daily rows).
            const val MAX_INSIGHTS = 10_000
            const val DELETED_RULE_PREFIX = "deleted:"
            const val MIN_NEW_PASSPHRASE_CHARS = 8
        }

        private data class DatabaseRestoreResult(
            val restoredInsightCount: Int = 0,
            val skippedInsightCount: Int = 0,
            val restoredFeedbackCount: Int = 0,
        )

        private fun staleRestoreWrite(): Nothing = throw StaleLocalDataWriteException()

        private fun String.remapOrMarkDeleted(idRemap: Map<String, String>): String =
            idRemap[this] ?: if (startsWith(DELETED_RULE_PREFIX)) this else "${DELETED_RULE_PREFIX}$this"

        private fun Rule.sameDefinitionAs(other: Rule): Boolean =
            name == other.name &&
                enabled == other.enabled &&
                priority == other.priority &&
                condition == other.condition &&
                action == other.action &&
                executionMode == other.executionMode

        private fun com.alarmcontrol.core.profile.FilteringProfile?.orEmptyRuleIds(): Set<String> =
            this?.ruleIds.orEmpty()

        private fun CategoryFeedbackEntity.toBackup(): BackupCategoryFeedback =
            BackupCategoryFeedback(packageName, predictedLabel, correctedLabel, recordedAtMillis)

        private fun BackupCategoryFeedback.toEntity(): CategoryFeedbackEntity =
            CategoryFeedbackEntity(
                packageName = packageName,
                notificationEventId = null,
                predictedLabel = predictedLabel,
                correctedLabel = correctedLabel,
                recordedAtMillis = recordedAtMillis,
            )
    }
