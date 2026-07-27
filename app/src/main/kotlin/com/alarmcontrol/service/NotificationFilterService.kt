package com.alarmcontrol.service

import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import com.alarmcontrol.core.coroutines.AppDispatcher
import com.alarmcontrol.core.coroutines.ApplicationScope
import com.alarmcontrol.core.coroutines.Dispatcher
import com.alarmcontrol.core.feedback.AdFeedbackRepository
import com.alarmcontrol.core.feedback.AdObservation
import com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.NotificationContent
import com.alarmcontrol.core.filtering.NotificationContentVisibility
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.NotificationEventRepository
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.core.settings.SemanticAnalysisScope
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.ml.NotificationClassifier
import com.alarmcontrol.ml.llm.LlmAnalysisResult
import com.alarmcontrol.ml.llm.OnDeviceLlmManager
import com.alarmcontrol.notifications.CompiledRuleSet
import com.alarmcontrol.notifications.MatchDecision
import com.alarmcontrol.notifications.Matcher
import com.alarmcontrol.notifications.NotificationRateTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * The app's `NotificationListenerService` entry point (CLAUDE.md §4). It is a thin shell: every
 * callback translates the framework notification to a pure [NotificationSnapshot], asks the
 * on-device [NotificationClassifier] for an optional category, delegates the decision to the
 * framework-free [Matcher], performs the platform side-effect, and records the outcome (§6). No
 * filtering logic lives here — the classifier and matcher are the only deciders.
 *
 * This is the "processing pipeline" that populates `mlCategory` (§0): the classifier lives in `:ml`
 * and the matcher in `:notifications`, so enriching here keeps `:notifications` pure and free of any
 * `:ml` dependency (§4).
 */
@AndroidEntryPoint
@Suppress("TooManyFunctions")
class NotificationFilterService : NotificationListenerService() {
    @Inject lateinit var matcher: Matcher

    @Inject lateinit var classifier: NotificationClassifier

    @Inject lateinit var llmManager: OnDeviceLlmManager

    @Inject lateinit var ruleRepository: RuleRepository

    @Inject lateinit var eventRepository: NotificationEventRepository

    @Inject lateinit var adFeedbackRepository: AdFeedbackRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var clock: Clock

    @Inject
    @Dispatcher(AppDispatcher.Default)
    lateinit var dispatcher: CoroutineDispatcher

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    // Service-scoped structured concurrency (§8): cancelled in onDestroy, never GlobalScope.
    private val scope by lazy { CoroutineScope(SupervisorJob() + dispatcher) }
    private val processingCoordinator by lazy { NotificationProcessingCoordinator(scope) }
    private val rateTracker = NotificationRateTracker()
    private val semanticObservationQueue by lazy {
        SemanticObservationQueue(
            scope = scope,
            onFailure = { Log.w(TAG, "Semantic observation failed") },
            handler = ::processSemanticObservation,
        )
    }

    // Active rules cached in-memory and recompiled only when they change, so each notification
    // evaluates against this hot snapshot instead of re-reading the DB per event (M3 performance).
    private val compiledRules = MutableStateFlow<CompiledRuleSet?>(null)
    private val filteringEnabled = MutableStateFlow<Boolean?>(null)
    private val llmAnalysisEnabled = MutableStateFlow<Boolean?>(null)
    private val llmAutoActionsEnabled = MutableStateFlow<Boolean?>(null)
    private val semanticAnalysisScope = MutableStateFlow<SemanticAnalysisScope?>(null)
    private val contentStorageEnabled = MutableStateFlow<Boolean?>(null)
    private val contentExcludedPackages = MutableStateFlow<Set<String>?>(null)
    private var rulesJob: Job? = null
    private var settingsJob: Job? = null
    private var rateSeedJob: Job? = null
    private val llmInitializationStarted = AtomicBoolean(false)
    private val semanticGeneration = AtomicLong(0)
    private val llmLifecycleLock = Any()
    private var llmCloseJob: Job? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        seedRateCacheIfNeeded()
        observeRulesIfNeeded()
        observeSettingsIfNeeded()
        if (llmAnalysisEnabled.value == true) {
            initializeLlmOnce()
        }
    }

    private fun seedRateCacheIfNeeded() {
        if (rateSeedJob == null || rateSeedJob?.isCompleted == true) {
            rateTracker.markUnavailable()
            rateSeedJob =
                scope.launch {
                    runCatchingPreservingCancellation {
                        val now = clock.millis()
                        rateTracker.seed(
                            eventRepository.rateHistorySince(now - MAX_RATE_WINDOW_MILLIS),
                            now,
                        )
                    }.onFailure {
                        rateTracker.markUnavailable()
                        Log.w(TAG, "Rate cache initialization failed")
                    }
                }
        }
    }

    private fun observeRulesIfNeeded() {
        // Warm and keep the rule cache fresh for the listener's lifetime; recompile on every change.
        if (rulesJob?.isActive != true) {
            rulesJob =
                scope.launch {
                    runCatchingPreservingCancellation {
                        ruleRepository.observeRules().collect { rules ->
                            val compiled = matcher.compile(rules)
                            val hadCompiledRules = compiledRules.value != null
                            compiledRules.value = compiled
                            if (hadCompiledRules) {
                                // A slow ML/LLM result must never act on a deleted or disabled rule.
                                processingCoordinator.invalidateAll()
                            }
                            if (llmAnalysisEnabled.value == true) {
                                initializeLlmOnce()
                            }
                        }
                    }.onFailure {
                        // Never keep evaluating a stale destructive cache after a persistence failure.
                        compiledRules.value = CompiledRuleSet.EMPTY
                        processingCoordinator.invalidateAll()
                        Log.w(TAG, "Rule cache stopped")
                    }
                }
        }
    }

    private fun observeSettingsIfNeeded() {
        if (settingsJob?.isActive != true) {
            settingsJob =
                scope.launch {
                    runCatchingPreservingCancellation {
                        val llmSettings =
                            combine(
                                settingsRepository.llmAnalysisEnabled,
                                settingsRepository.llmAutoActionsEnabled,
                                settingsRepository.semanticAnalysisScope,
                                ::ListenerLlmSettings,
                            )
                        combine(
                            settingsRepository.filteringEnabled,
                            llmSettings,
                            settingsRepository.notificationContentStorageEnabled,
                            settingsRepository.contentExcludedPackages,
                        ) { filtering, llm, storeContent, excludedPackages ->
                            ListenerSettings(
                                filtering,
                                llm.enabled,
                                llm.autoActions,
                                llm.scope,
                                storeContent,
                                excludedPackages,
                            )
                        }.collect { settings ->
                            applySettings(settings)
                        }
                    }.onFailure {
                        resetSettingsCache()
                        Log.w(TAG, "Settings cache stopped")
                    }
                }
        }
    }

    private suspend fun applySettings(settings: ListenerSettings) {
        val previous = currentSettings()
        val wasLlmEnabled = llmAnalysisEnabled.value
        filteringEnabled.value = settings.filtering
        llmAnalysisEnabled.value = settings.llmEnabled
        llmAutoActionsEnabled.value = settings.llmAutoActions
        semanticAnalysisScope.value = settings.semanticScope
        contentStorageEnabled.value = settings.storeContent
        contentExcludedPackages.value = settings.excludedPackages
        if (previous != null && previous != settings) {
            // Includes privacy reductions such as new content exclusions.
            semanticGeneration.incrementAndGet()
            semanticObservationQueue.clearPending()
            processingCoordinator.invalidateAll()
        }
        if (settings.llmEnabled) {
            initializeLlmOnce()
        } else if (wasLlmEnabled == true) {
            semanticObservationQueue.clearPending()
            llmInitializationStarted.set(false)
            llmManager.close()
        }
    }

    private fun currentSettings(): ListenerSettings? =
        filteringEnabled.value?.let { filtering ->
            ListenerSettings(
                filtering = filtering,
                llmEnabled = llmAnalysisEnabled.value ?: false,
                llmAutoActions = llmAutoActionsEnabled.value ?: false,
                semanticScope = semanticAnalysisScope.value ?: SemanticAnalysisScope.RULES_ONLY,
                storeContent = contentStorageEnabled.value ?: false,
                excludedPackages = contentExcludedPackages.value.orEmpty(),
            )
        }

    private suspend fun resetSettingsCache() {
        // Fail closed: unknown settings must never activate destructive filtering.
        filteringEnabled.value = false
        llmAnalysisEnabled.value = false
        llmAutoActionsEnabled.value = false
        semanticAnalysisScope.value = SemanticAnalysisScope.RULES_ONLY
        contentStorageEnabled.value = false
        contentExcludedPackages.value = emptySet()
        llmInitializationStarted.set(false)
        semanticGeneration.incrementAndGet()
        semanticObservationQueue.clearPending()
        processingCoordinator.invalidateAll()
        llmManager.close()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handleNotificationPosted(sbn, null)
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
    ) {
        val ranking = Ranking()
        handleNotificationPosted(sbn, ranking.takeIf { rankingMap.getRanking(sbn.key, it) })
    }

    private fun handleNotificationPosted(
        sbn: StatusBarNotification,
        ranking: Ranking?,
    ) {
        val snapshot = sbn.toSnapshot(clock.zone, ranking)
        val key = sbn.key
        // Frequency state must reflect post time, not completion time after optional inference.
        rateTracker.record(snapshot, key)
        processingCoordinator.submit(key, freshness = snapshot.postedAtMillis) { token ->
            runCatchingPreservingCancellation { evaluateAndApply(key, snapshot, token) }
                // Fixed message only: notification content must never enter logs (§1/§3).
                .onFailure { Log.w(TAG, "Notification processing failed") }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        rateTracker.markRemoved(sbn.key)
        processingCoordinator.invalidate(sbn.key)
    }

    override fun onListenerDisconnected() {
        processingCoordinator.invalidateAll()
        rateSeedJob?.cancel()
        rateSeedJob = null
        rateTracker.markUnavailable()
        semanticGeneration.incrementAndGet()
        releaseLlmAsync()
        super.onListenerDisconnected()
    }

    private suspend fun evaluateAndApply(
        key: String,
        snapshot: NotificationSnapshot,
        token: NotificationProcessingCoordinator.ProcessingToken,
    ) {
        // Startup cache failures must fail open rather than hold listener work indefinitely.
        val runtime =
            withTimeoutOrNull(CACHE_READY_TIMEOUT_MILLIS) {
                ListenerRuntime(
                    filtering = filteringEnabled.filterNotNull().first(),
                    llmEnabled = llmAnalysisEnabled.filterNotNull().first(),
                    llmAutoActions = llmAutoActionsEnabled.filterNotNull().first(),
                    storeContent = contentStorageEnabled.filterNotNull().first(),
                    excludedPackages = contentExcludedPackages.filterNotNull().first(),
                    semanticScope = semanticAnalysisScope.filterNotNull().first(),
                    compiledRules = compiledRules.filterNotNull().first(),
                    semanticGeneration = semanticGeneration.get(),
                )
            } ?: return
        if (!runtime.filtering || !token.isCurrent()) return
        val evaluated =
            prepareEvaluation(
                snapshot,
                runtime.compiledRules,
                runtime.llmEnabled,
                runtime.llmAutoActions,
                runtime.semanticGeneration,
                token,
            ) ?: return
        if (!token.commit { applyAction(key, evaluated.action) }) return
        persistEvaluation(
            evaluated,
            runtime.llmEnabled,
            runtime.storeContent,
            runtime.excludedPackages,
            runtime.semanticScope,
        )
    }

    private suspend fun prepareEvaluation(
        snapshot: NotificationSnapshot,
        compiled: CompiledRuleSet,
        llmEnabled: Boolean,
        llmAutoActions: Boolean,
        semanticGeneration: Long,
        token: NotificationProcessingCoordinator.ProcessingToken,
    ): EvaluatedNotification? {
        if (!token.isCurrent()) return null
        val requirements = compiled.requiredSignals
        val classification = if (requirements.mlCategory) classifier.classify(snapshot) else null
        if (!token.isCurrent()) return null
        val activeNeedsLlm = compiled.activeRequiredSignals.needsLlm()
        val monitorNeedsLlm = compiled.monitorRequiredSignals.needsLlm()
        val useLlmForActive = activeNeedsLlm && llmEnabled && llmAutoActions
        val useLlmForMonitor = monitorNeedsLlm && llmEnabled
        val decisionAnalysis =
            if (useLlmForActive || useLlmForMonitor) analyzeAdvertisement(snapshot) else null
        val trustedIntent =
            decisionAnalysis
                ?.takeIf { it.confidenceScore >= LLM_SEMANTIC_CONFIDENCE }
                ?.intent
                ?.takeUnless { it == SemanticIntent.AMBIGUOUS }
        if (!token.isCurrent()) return null
        val rateCounts =
            rateTracker.counts(
                snapshot,
                requirements.rateSignals,
            )
        val common =
            snapshot.copy(
                mlCategory = classification?.category,
                rateCounts = rateCounts,
            )
        val activeSnapshot =
            common.copy(
                semanticIntent = trustedIntent.takeIf { useLlmForActive },
                isAdvertisement = trustedIntent?.isAdvertisement.takeIf { useLlmForActive },
            )
        val monitorSnapshot =
            common.copy(
                semanticIntent = trustedIntent.takeIf { useLlmForMonitor },
                isAdvertisement = trustedIntent?.isAdvertisement.takeIf { useLlmForMonitor },
            )
        val matchEvaluation =
            matcher.evaluateWithTraces(
                activeSnapshot = activeSnapshot,
                monitorSnapshot = monitorSnapshot,
                compiled = compiled,
            )
        val decision = matchEvaluation.activeDecision
        val monitorDecision = matchEvaluation.monitorDecision
        val active = decision.resolve(RuleAction.Keep)
        val monitor = monitorDecision.resolve(null)
        return EvaluatedNotification(
            common = common,
            action = requireNotNull(active.action),
            matchedRuleId = active.ruleId,
            monitoredAction = monitor.action,
            monitoredRuleId = monitor.ruleId,
            mlConfidence = classification?.confidence,
            analysis = decisionAnalysis,
            decisionTrace = matchEvaluation.decisionTrace,
            semanticGeneration = semanticGeneration,
        )
    }

    private suspend fun persistEvaluation(
        evaluated: EvaluatedNotification,
        llmEnabled: Boolean,
        storeContent: Boolean,
        excludedPackages: Set<String>,
        semanticScope: SemanticAnalysisScope,
    ) {
        val analyticsClassification =
            if (evaluated.common.mlCategory == null) {
                withTimeoutOrNull(ANALYTICS_CLASSIFICATION_TIMEOUT_MILLIS) {
                    classifier.classify(evaluated.common)
                }
            } else {
                null
            }
        val persistedSnapshot =
            evaluated.common.copy(
                mlCategory = evaluated.common.mlCategory ?: analyticsClassification?.category,
            )
        val persistedMlConfidence = evaluated.mlConfidence ?: analyticsClassification?.confidence
        val encryptedContent =
            persistedSnapshot
                .takeIf {
                    storeContent &&
                        it.contentVisibility != NotificationContentVisibility.SECRET &&
                        it.packageName !in excludedPackages
                }?.let { NotificationContent(it.title, it.text) }
        val eventId =
            eventRepository.record(
                NotificationEvent(
                    packageName = persistedSnapshot.packageName,
                    channelId = persistedSnapshot.channelId,
                    channelName = persistedSnapshot.channelName,
                    mlCategory = persistedSnapshot.mlCategory,
                    mlConfidence = persistedMlConfidence,
                    category = persistedSnapshot.category,
                    postedAtMillis = persistedSnapshot.postedAtMillis,
                    postedEpochDay = persistedSnapshot.postedEpochDay,
                    postedMinuteOfDay = persistedSnapshot.postedMinuteOfDay,
                    importance = persistedSnapshot.importance,
                    isConversation = persistedSnapshot.isConversation,
                    isForegroundService = persistedSnapshot.isForegroundService,
                    action = evaluated.action,
                    matchedRuleId = evaluated.matchedRuleId,
                    monitoredRuleId = evaluated.monitoredRuleId,
                    monitoredAction = evaluated.monitoredAction,
                    decisionTrace = evaluated.decisionTrace,
                    recordedAtMillis = clock.millis(),
                ),
                encryptedContent,
            )
        if (evaluated.analysis != null && isSemanticAnalysisCurrent(evaluated.semanticGeneration)) {
            recordSemanticObservation(eventId, persistedSnapshot.packageName, evaluated.analysis)
        } else if (
            llmEnabled &&
            semanticScope == SemanticAnalysisScope.ALL_NOTIFICATIONS &&
            isSemanticAnalysisCurrent(evaluated.semanticGeneration, requireAllNotifications = true)
        ) {
            semanticObservationQueue.offer(
                SemanticObservationRequest(
                    eventId = eventId,
                    snapshot = persistedSnapshot,
                    semanticGeneration = evaluated.semanticGeneration,
                ),
            )
        }
    }

    private suspend fun processSemanticObservation(request: SemanticObservationRequest) {
        if (!isSemanticAnalysisCurrent(request.semanticGeneration, requireAllNotifications = true)) return
        analyzeAdvertisement(request.snapshot)?.let { result ->
            if (isSemanticAnalysisCurrent(request.semanticGeneration, requireAllNotifications = true)) {
                recordSemanticObservation(request.eventId, request.snapshot.packageName, result)
            }
        }
    }

    private fun isSemanticAnalysisCurrent(
        generation: Long,
        requireAllNotifications: Boolean = false,
    ): Boolean =
        semanticGeneration.get() == generation &&
            llmAnalysisEnabled.value == true &&
            (!requireAllNotifications || semanticAnalysisScope.value == SemanticAnalysisScope.ALL_NOTIFICATIONS)

    private suspend fun recordSemanticObservation(
        eventId: String,
        packageName: String,
        result: LlmAnalysisResult,
    ) {
        if (result.confidenceScore <= 0f) return
        adFeedbackRepository.recordObservation(
            AdObservation(
                notificationEventId = eventId,
                packageName = packageName,
                predictedIsAdvertisement = result.isAdvertisement,
                predictedIntent = result.intent,
                confidenceScore = result.confidenceScore,
                analyzedAtMillis = clock.millis(),
            ),
        )
    }

    private fun MatchDecision.resolve(fallbackAction: RuleAction?): ResolvedDecision =
        when (this) {
            is MatchDecision.Matched -> ResolvedDecision(action, rule.id)
            MatchDecision.NoMatch -> ResolvedDecision(fallbackAction, null)
        }

    /**
     * Runs optional local semantic analysis with a hard caller deadline. The manager retains native
     * ownership after a timeout, so a late result is discarded and cannot apply an action.
     */
    private suspend fun analyzeAdvertisement(snapshot: NotificationSnapshot): LlmAnalysisResult? {
        val text = listOfNotNull(snapshot.title, snapshot.text).joinToString(separator = " ").ifBlank { return null }
        return withTimeoutOrNull(LLM_ANALYSIS_TIMEOUT_MILLIS) {
            llmManager.analyze(text, snapshot.packageName)
        }
    }

    private fun initializeLlmOnce() {
        if (!llmInitializationStarted.compareAndSet(false, true)) return
        val pendingClose = synchronized(llmLifecycleLock) { llmCloseJob }
        scope.launch {
            pendingClose?.join()
            if (llmInitializationStarted.get() && llmAnalysisEnabled.value == true) {
                llmManager.initialize()
            }
        }
    }

    private fun releaseLlmAsync() {
        semanticObservationQueue.clearPending()
        llmInitializationStarted.set(false)
        synchronized(llmLifecycleLock) {
            val previousClose = llmCloseJob
            llmCloseJob =
                applicationScope.launch {
                    previousClose?.join()
                    llmManager.close()
                }
        }
    }

    /** Performs the platform side-effect for [action]. We can only act in these ways (§0/§6). */
    private fun applyAction(
        key: String,
        action: RuleAction,
    ) = when (action) {
        RuleAction.Cancel -> cancelNotification(key)
        is RuleAction.Snooze -> snoozeNotification(key, action.durationMillis)
        // MarkRead/Keep have no listener-side platform call; they're recorded for insights only.
        RuleAction.MarkRead, RuleAction.Keep -> Unit
    }

    override fun onDestroy() {
        processingCoordinator.invalidateAll()
        semanticGeneration.incrementAndGet()
        releaseLlmAsync()
        scope.cancel()
        super.onDestroy()
    }

    private data class EvaluatedNotification(
        val common: NotificationSnapshot,
        val action: RuleAction,
        val matchedRuleId: String?,
        val monitoredAction: RuleAction?,
        val monitoredRuleId: String?,
        val mlConfidence: Float?,
        val analysis: LlmAnalysisResult?,
        val decisionTrace: List<com.alarmcontrol.core.filtering.DecisionTraceNode>,
        val semanticGeneration: Long,
    )

    private data class ResolvedDecision(
        val action: RuleAction?,
        val ruleId: String?,
    )

    private companion object {
        const val TAG = "NotificationFilter"

        /** Minimum LLM confidence for its ad verdict to be trusted as a rule signal. */
        const val LLM_SEMANTIC_CONFIDENCE = 0.6f
        const val LLM_ANALYSIS_TIMEOUT_MILLIS = 10_000L
        const val CACHE_READY_TIMEOUT_MILLIS = 2_000L
        const val ANALYTICS_CLASSIFICATION_TIMEOUT_MILLIS = 500L
    }

    private data class ListenerSettings(
        val filtering: Boolean,
        val llmEnabled: Boolean,
        val llmAutoActions: Boolean,
        val semanticScope: SemanticAnalysisScope,
        val storeContent: Boolean,
        val excludedPackages: Set<String>,
    )

    private data class ListenerRuntime(
        val filtering: Boolean,
        val llmEnabled: Boolean,
        val llmAutoActions: Boolean,
        val storeContent: Boolean,
        val excludedPackages: Set<String>,
        val semanticScope: SemanticAnalysisScope,
        val compiledRules: CompiledRuleSet,
        val semanticGeneration: Long,
    )

    private data class ListenerLlmSettings(
        val enabled: Boolean,
        val autoActions: Boolean,
        val scope: SemanticAnalysisScope,
    )

    private data class SemanticObservationRequest(
        val eventId: String,
        val snapshot: NotificationSnapshot,
        val semanticGeneration: Long,
    )
}

private fun com.alarmcontrol.notifications.RuleSignalRequirements.needsLlm(): Boolean = advertisement || semanticIntent
