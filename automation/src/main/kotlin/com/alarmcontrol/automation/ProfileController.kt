package com.alarmcontrol.automation

import com.alarmcontrol.core.automation.AutomationAuditEntry
import com.alarmcontrol.core.automation.AutomationAuditRepository
import com.alarmcontrol.core.automation.AutomationOperation
import com.alarmcontrol.core.automation.AutomationOutcome
import com.alarmcontrol.core.automation.AutomationSource
import com.alarmcontrol.core.automation.AutomationTarget
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.profile.ProfileRepository
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.core.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies an automation enable/disable to the global filtering gate or a targeted named profile
 * (CLAUDE.md §7). It lives in `:automation` but speaks only `:core` contracts; their `:data`
 * implementations persist each change. Pure orchestration, so it is unit-tested without Android.
 */
@Singleton
class ProfileController
    @Inject
    constructor(
        private val ruleRepository: RuleRepository,
        private val profileRepository: ProfileRepository,
        private val settingsRepository: SettingsRepository,
        private val auditRepository: AutomationAuditRepository = NoOpAutomationAuditRepository,
        private val clock: Clock = Clock.systemUTC(),
        private val rateLimiter: AutomationRateLimiter = AutomationRateLimiter(),
    ) {
        private val operationMutex = Mutex()

        /**
         * Sets [enabled] on every member of an id/name-matched profile. For compatibility with
         * automation configured before named profiles existed, an unmatched value falls back to an
         * id-matched rule or exactly one case-insensitive rule-name match. Duplicate names are
         * rejected as ambiguous. A blank value changes the master gate.
         *
         * @param profileId a profile id/name, or legacy rule id/name; blank targets the master gate.
         */
        suspend fun setEnabled(
            profileId: String?,
            enabled: Boolean,
            source: AutomationSource = AutomationSource.IN_APP,
        ): Int =
            operationMutex.withLock {
                val target = profileId.normalizedTarget()
                val result =
                    if (profileId.isInvalidTarget(target)) {
                        ApplyResult(AutomationOutcome.INVALID, 0)
                    } else {
                        applyEnabled(target, enabled)
                    }
                record(
                    source,
                    if (enabled) AutomationOperation.ENABLE else AutomationOperation.DISABLE,
                    profileId,
                    result,
                )
                result.changedCount
            }

        /** Toggles a profile (or the master gate when [profileId] is blank) for first-party actions. */
        suspend fun toggle(
            profileId: String?,
            source: AutomationSource = AutomationSource.IN_APP,
        ): Int =
            operationMutex.withLock {
                val target = profileId.normalizedTarget()
                val result =
                    if (profileId.isInvalidTarget(target)) {
                        ApplyResult(AutomationOutcome.INVALID, 0)
                    } else if (target == null) {
                        applyEnabled(null, enabled = !settingsRepository.filteringEnabled.first())
                    } else {
                        when (val resolution = resolveRuleIds(target)) {
                            TargetResolution.Invalid -> ApplyResult(AutomationOutcome.INVALID, 0)
                            TargetResolution.NotFound -> ApplyResult(AutomationOutcome.NOT_FOUND, 0)
                            is TargetResolution.Resolved -> {
                                val currentRules =
                                    ruleRepository.observeRules().first().filter { it.id in resolution.ruleIds }
                                if (currentRules.isEmpty()) {
                                    ApplyResult(AutomationOutcome.NOT_FOUND, 0)
                                } else {
                                    applyResolved(
                                        resolution.ruleIds,
                                        enabled = currentRules.any { !it.enabled },
                                    )
                                }
                            }
                        }
                    }
                record(source, AutomationOperation.TOGGLE, profileId, result)
                result.changedCount
            }

        /**
         * The **external** path used by the exported receiver. Applies [setEnabled] only when the user
         * has opted into external automation and [token] matches the per-install secret. Requests are
         * rate-limited and reduced to a content-free audit outcome. Rejected requests return 0.
         */
        suspend fun setEnabledFromExternalAutomation(
            profileId: String?,
            enabled: Boolean,
            token: String?,
        ): Int =
            operationMutex.withLock {
                val operation = if (enabled) AutomationOperation.ENABLE else AutomationOperation.DISABLE
                val target = profileId.normalizedTarget()
                val now = clock.millis()
                var shouldRecord = true
                val result =
                    when {
                        !settingsRepository.externalAutomationEnabled.first() ->
                            ApplyResult(AutomationOutcome.DISABLED, 0).also {
                                shouldRecord = rateLimiter.tryAcquireRejected(now)
                            }
                        !token.isAuthorized(settingsRepository.externalAutomationToken.first()) ->
                            ApplyResult(AutomationOutcome.UNAUTHORIZED, 0).also {
                                shouldRecord = rateLimiter.tryAcquireRejected(now)
                            }
                        profileId.isInvalidTarget(target) ->
                            ApplyResult(AutomationOutcome.INVALID, 0).also {
                                shouldRecord = rateLimiter.tryAcquireRejected(now)
                            }
                        !rateLimiter.tryAcquire(now) ->
                            ApplyResult(AutomationOutcome.THROTTLED, 0).also {
                                shouldRecord = rateLimiter.tryAcquireRejected(now)
                            }
                        else -> applyEnabled(target, enabled)
                    }
                if (shouldRecord) {
                    record(AutomationSource.EXTERNAL, operation, profileId, result)
                }
                result.changedCount
            }

        private suspend fun applyEnabled(
            profileId: String?,
            enabled: Boolean,
        ): ApplyResult {
            if (profileId == null) {
                if (settingsRepository.filteringEnabled.first() == enabled) {
                    return ApplyResult(AutomationOutcome.NO_CHANGE, 0)
                }
                settingsRepository.setFilteringEnabled(enabled)
                return ApplyResult(AutomationOutcome.APPLIED, 1)
            }
            return when (val resolution = resolveRuleIds(profileId)) {
                TargetResolution.Invalid -> ApplyResult(AutomationOutcome.INVALID, 0)
                TargetResolution.NotFound -> ApplyResult(AutomationOutcome.NOT_FOUND, 0)
                is TargetResolution.Resolved -> applyResolved(resolution.ruleIds, enabled)
            }
        }

        private suspend fun applyResolved(
            ruleIds: Set<String>,
            enabled: Boolean,
        ): ApplyResult {
            val changed = ruleRepository.setRulesEnabled(ruleIds, enabled)
            return ApplyResult(
                outcome = if (changed > 0) AutomationOutcome.APPLIED else AutomationOutcome.NO_CHANGE,
                changedCount = changed,
            )
        }

        private suspend fun record(
            source: AutomationSource,
            operation: AutomationOperation,
            profileId: String?,
            result: ApplyResult,
        ) {
            runCatchingPreservingCancellation {
                auditRepository.record(
                    AutomationAuditEntry(
                        requestedAtMillis = clock.millis(),
                        source = source,
                        operation = operation,
                        target = if (profileId.isNullOrBlank()) AutomationTarget.MASTER else AutomationTarget.PROFILE,
                        outcome = result.outcome,
                        changedCount = result.changedCount,
                    ),
                )
            }
        }

        private suspend fun resolveRuleIds(profileId: String): TargetResolution {
            val profiles = profileRepository.observeProfiles().first()
            val exactProfile = profiles.firstOrNull { it.id == profileId }
            if (exactProfile != null) return TargetResolution.Resolved(exactProfile.ruleIds)

            val namedProfiles = profiles.filter { it.name.equals(profileId, ignoreCase = true) }
            if (namedProfiles.size > 1) return TargetResolution.Invalid
            if (namedProfiles.size == 1) return TargetResolution.Resolved(namedProfiles.single().ruleIds)

            val rules = ruleRepository.observeRules().first()
            val exactRule = rules.firstOrNull { it.id == profileId }
            if (exactRule != null) return TargetResolution.Resolved(setOf(exactRule.id))

            val namedRules = rules.filter { it.name.equals(profileId, ignoreCase = true) }
            return when (namedRules.size) {
                0 -> TargetResolution.NotFound
                1 -> TargetResolution.Resolved(setOf(namedRules.single().id))
                else -> TargetResolution.Invalid
            }
        }

        private data class ApplyResult(
            val outcome: AutomationOutcome,
            val changedCount: Int,
        )

        private sealed interface TargetResolution {
            data object Invalid : TargetResolution

            data object NotFound : TargetResolution

            data class Resolved(
                val ruleIds: Set<String>,
            ) : TargetResolution
        }

        private companion object {
            const val MAX_TARGET_CHARS = 200
            const val MAX_TOKEN_CHARS = 128
        }

        private fun String?.normalizedTarget(): String? {
            if (this == null || isBlank()) return null
            val trimmed = trim()
            if (trimmed.length > MAX_TARGET_CHARS) return null
            if (trimmed.any(Char::isISOControl)) return null
            return trimmed
        }

        private fun String?.isInvalidTarget(normalized: String?): Boolean =
            this != null && isNotBlank() && normalized == null

        private fun String?.isAuthorized(expected: String): Boolean {
            if (this == null) return false
            if (isEmpty()) return false
            if (length > MAX_TOKEN_CHARS) return false
            if (expected.isEmpty()) return false
            val suppliedBytes = toByteArray(Charsets.UTF_8)
            val expectedBytes = expected.toByteArray(Charsets.UTF_8)
            return try {
                MessageDigest.isEqual(suppliedBytes, expectedBytes)
            } finally {
                suppliedBytes.fill(0)
                expectedBytes.fill(0)
            }
        }
    }

private data object NoOpAutomationAuditRepository : AutomationAuditRepository {
    override suspend fun record(entry: AutomationAuditEntry) = Unit

    override fun observeRecent(limit: Int): Flow<List<AutomationAuditEntry>> = emptyFlow()
}
