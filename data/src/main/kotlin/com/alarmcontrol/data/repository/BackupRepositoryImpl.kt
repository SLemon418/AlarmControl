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
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.core.settings.SettingsSnapshot
import com.alarmcontrol.data.backup.BackupCodec
import com.alarmcontrol.data.backup.BackupCryptor
import com.alarmcontrol.data.backup.BackupValidator
import com.alarmcontrol.data.db.TransactionRunner
import com.alarmcontrol.data.db.dao.CategoryFeedbackDao
import com.alarmcontrol.data.db.dao.DailyInsightDao
import com.alarmcontrol.data.db.dao.LlmObservationDao
import com.alarmcontrol.data.db.dao.ProfileDao
import com.alarmcontrol.data.db.dao.RuleDao
import com.alarmcontrol.data.db.entity.CategoryFeedbackEntity
import com.alarmcontrol.data.db.entity.SemanticFeedbackPriorEntity
import com.alarmcontrol.data.mapper.toDomain
import com.alarmcontrol.data.mapper.toEntity
import com.alarmcontrol.data.mapper.toPendingTree
import com.alarmcontrol.data.mapper.toRuleEntity
import com.alarmcontrol.data.mapper.toWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * [BackupRepository] backed by the existing repositories/DAO (CLAUDE.md §3). Export reads the current
 * rules, named profiles, and daily history. Restore replaces rules/profiles wholesale, re-points
 * profile memberships and historical rule references at freshly-assigned ids, then upserts each
 * day's rollup.
 */
class BackupRepositoryImpl
    @Inject
    constructor(
        private val transactionRunner: TransactionRunner,
        private val ruleDao: RuleDao,
        private val dailyInsightDao: DailyInsightDao,
        private val profileDao: ProfileDao,
        private val categoryFeedbackDao: CategoryFeedbackDao,
        private val llmObservationDao: LlmObservationDao,
        private val settingsRepository: SettingsRepository,
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
                                    llmObservationDao.getSemanticFeedbackCounts().map {
                                        BackupSemanticFeedback(
                                            it.packageName,
                                            com.alarmcontrol.core.filtering.SemanticIntent
                                                .valueOf(it.intent),
                                            it.count,
                                        )
                                    }
                                } else {
                                    emptyList()
                                },
                        )
                    },
                )
            return if (passphrase == null || passphrase.isEmpty()) {
                plaintext
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
        ): DataResult<BackupSummary> =
            runCatchingPreservingCancellation {
                val (data, encrypted) = decodeAndValidate(serialized, passphrase)
                require(!options.learningFeedback || encrypted) { "Learning feedback requires encryption" }
                val priorSettings = settingsRepository.snapshot()
                val pauseSideEffects = options.rulesAndProfiles
                val desiredSettings = data.settings
                var databaseCommitted = false
                try {
                    if (pauseSideEffects) disableSideEffectingSettings()
                    transactionRunner.run { restoreDatabase(data, options) }
                    databaseCommitted = true
                    when {
                        options.settings && desiredSettings != null -> settingsRepository.restore(desiredSettings)
                        pauseSideEffects -> settingsRepository.restore(priorSettings)
                    }
                } catch (error: CancellationException) {
                    withContext(NonCancellable) {
                        recoverSettingsAndThrow(
                            priorSettings = priorSettings,
                            databaseCommitted = databaseCommitted,
                            sideEffectsWerePaused = pauseSideEffects,
                            error = error,
                        )
                    }
                } catch (error: Exception) {
                    recoverSettingsAndThrow(
                        priorSettings = priorSettings,
                        databaseCommitted = databaseCommitted,
                        sideEffectsWerePaused = pauseSideEffects,
                        error = error,
                    )
                }

                BackupSummary(
                    rulesRestored = if (options.rulesAndProfiles) data.rules.size else 0,
                    insightsRestored = if (options.dailyInsights) data.dailyInsights.size else 0,
                    profilesRestored = if (options.rulesAndProfiles) data.profiles.size else 0,
                    settingsRestored = options.settings && data.settings != null,
                    feedbackRestored =
                        if (options.learningFeedback) {
                            (
                                data.categoryFeedback.size.toLong() +
                                    data.semanticFeedback.saturatedVoteCount()
                            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        } else {
                            0
                        },
                )
            }.fold(
                onSuccess = { DataResult.Success(it) },
                onFailure = { DataResult.Failure(it) },
            )

        private fun List<BackupSemanticFeedback>.saturatedVoteCount(): Int =
            fold(0L) { total, feedback -> total + feedback.count }
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

        private suspend fun disableSideEffectingSettings() {
            settingsRepository.setFilteringEnabled(false)
            settingsRepository.setExternalAutomationEnabled(false)
            settingsRepository.setLlmAutoActionsEnabled(false)
        }

        private suspend fun recoverSettingsAndThrow(
            priorSettings: SettingsSnapshot,
            databaseCommitted: Boolean,
            sideEffectsWerePaused: Boolean,
            error: Throwable,
        ): Nothing {
            if (!databaseCommitted || !sideEffectsWerePaused) {
                runCatchingPreservingCancellation { settingsRepository.restore(priorSettings) }
            } else {
                // The Room restore cannot be rolled back after commit. Keep destructive entry
                // points off rather than applying either old settings or a partially restored set
                // to the new rules.
                runCatchingPreservingCancellation { settingsRepository.setFilteringEnabled(false) }
                runCatchingPreservingCancellation { settingsRepository.setExternalAutomationEnabled(false) }
                runCatchingPreservingCancellation { settingsRepository.setLlmAutoActionsEnabled(false) }
            }
            throw error
        }

        private suspend fun restoreDatabase(
            data: BackupData,
            options: RestoreOptions,
        ) {
            val now = System.currentTimeMillis()
            val idRemap =
                if (options.rulesAndProfiles) {
                    restoreRulesAndProfiles(data, options.mode, now)
                } else {
                    emptyMap()
                }

            if (options.dailyInsights) {
                if (options.mode == RestoreMode.REPLACE) dailyInsightDao.deleteAll()
                data.dailyInsights.forEach { insight ->
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
                }
            }

            if (options.learningFeedback) restoreFeedback(data, options.mode)
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
        ) {
            if (mode == RestoreMode.REPLACE) {
                categoryFeedbackDao.deleteAll()
                llmObservationDao.deleteImportedPriors()
                llmObservationDao.clearSemanticCorrections()
                llmObservationDao.deleteSemanticImportedPriors()
            }
            categoryFeedbackDao.insertAll(data.categoryFeedback.map { it.toEntity() })
            categoryFeedbackDao.trimToMostRecent(CategoryFeedbackDao.MAX_RETAINED_ROWS)

            val existing =
                if (mode == RestoreMode.MERGE) {
                    llmObservationDao.getSemanticImportedPriors().associateBy { it.packageName to it.intent }
                } else {
                    emptyMap()
                }
            val imported =
                data.semanticFeedback
                    .groupingBy { it.packageName to it.intent.name }
                    .fold(0L) { total, row -> Math.addExact(total, row.count.toLong()) }
                    .map { (key, count) ->
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
        }

        private fun decodeAndValidate(
            serialized: String,
            passphrase: CharArray?,
        ): Pair<BackupData, Boolean> {
            val encrypted = BackupCryptor.isEncrypted(serialized)
            val plaintext =
                if (encrypted) {
                    BackupCryptor.decrypt(serialized, requireNotNull(passphrase) { "Backup password required" })
                } else {
                    serialized
                }
            val data = BackupValidator.validate(BackupCodec.decode(plaintext))
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
