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
import com.alarmcontrol.core.filtering.DecisionTraceLane
import com.alarmcontrol.core.filtering.DecisionTraceNode
import com.alarmcontrol.core.filtering.MAX_PERSISTED_TRACE_NODES
import com.alarmcontrol.core.filtering.NotificationActionOutbox
import com.alarmcontrol.core.filtering.NotificationContent
import com.alarmcontrol.core.filtering.NotificationContentVisibility
import com.alarmcontrol.core.filtering.NotificationDecisionEnrichment
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.NotificationEventRepository
import com.alarmcontrol.core.filtering.NotificationHistoryWriteFence
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.RateListenerKeyHasher
import com.alarmcontrol.core.filtering.RateOccurrenceLifecycleGate
import com.alarmcontrol.core.filtering.RateOccurrenceRepository
import com.alarmcontrol.core.filtering.RateSignal
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.core.filtering.StagedNotificationAction
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.core.settings.FilteringActionGate
import com.alarmcontrol.core.settings.SemanticAnalysisScope
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.ml.ClassificationResult
import com.alarmcontrol.ml.NotificationClassifier
import com.alarmcontrol.ml.SemanticClassificationResult
import com.alarmcontrol.ml.SemanticInferenceUrgency
import com.alarmcontrol.ml.SemanticNotificationClassifier
import com.alarmcontrol.ml.llm.LlmAnalysisResult
import com.alarmcontrol.ml.llm.LlmBackgroundAnalysisEligibility
import com.alarmcontrol.ml.llm.OnDeviceLlmManager
import com.alarmcontrol.notifications.CompiledRuleSet
import com.alarmcontrol.notifications.MatchDecision
import com.alarmcontrol.notifications.Matcher
import com.alarmcontrol.notifications.NotificationRateTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * The app's `NotificationListenerService` entry point (CLAUDE.md §4). It is a thin shell: every
 * callback translates the framework notification to a pure [NotificationSnapshot], asks the
 * on-device classifiers for optional category and semantic signals, delegates the decision to the
 * framework-free [Matcher], performs the platform side-effect, and records the outcome (§6).
 *
 * This is the "processing pipeline" that populates `mlCategory` (§0): the classifier lives in `:ml`
 * and the matcher in `:notifications`, so enriching here keeps `:notifications` pure and free of any
 * `:ml` dependency (§4).
 *
 * The Android listener lifecycle keeps orchestration state in one owner while inference and queues
 * remain separate collaborators, so this boundary intentionally exceeds detekt's class-size metric.
 */
@AndroidEntryPoint
@Suppress("LargeClass", "TooManyFunctions")
class NotificationFilterService : NotificationListenerService() {
    @Inject lateinit var matcher: Matcher

    @Inject lateinit var classifier: NotificationClassifier

    @Inject lateinit var semanticClassifier: SemanticNotificationClassifier

    @Inject lateinit var llmManager: OnDeviceLlmManager

    @Inject lateinit var ruleRepository: RuleRepository

    @Inject lateinit var eventRepository: NotificationEventRepository

    @Inject lateinit var notificationActionOutbox: NotificationActionOutbox

    @Inject lateinit var notificationHistoryWriteFence: NotificationHistoryWriteFence

    @Inject lateinit var adFeedbackRepository: AdFeedbackRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var filteringActionGate: FilteringActionGate

    @Inject lateinit var rateOccurrenceRepository: RateOccurrenceRepository

    @Inject lateinit var rateListenerKeyHasher: RateListenerKeyHasher

    @Inject lateinit var rateOccurrenceLifecycleGate: RateOccurrenceLifecycleGate

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
    private val semanticRuleResolver by lazy {
        RealtimeSemanticRuleResolver(matcher, semanticClassifier)
    }
    private val llmLifecycle by lazy { SemanticLlmLifecycle(llmManager) }
    private val categoryRuleResolver by lazy {
        RealtimeCategoryRuleResolver(matcher, classifier)
    }
    private val rateTracker = NotificationRateTracker()
    private val rateLifecycleActor by lazy {
        NotificationRateLifecycleActor(
            scope = scope,
            repository = rateOccurrenceRepository,
            hasher = rateListenerKeyHasher,
            lifecycleGate = rateOccurrenceLifecycleGate,
            tracker = rateTracker,
            clock = clock,
            onFailure = { Log.w(TAG, "Rate occurrence processing failed") },
        )
    }
    private val semanticObservationQueue by lazy {
        SemanticObservationQueue(
            scope = scope,
            onFailure = { Log.w(TAG, "Semantic observation failed") },
            handler = ::processSemanticObservation,
        )
    }
    private val postCommitWorkDispatcher by lazy {
        PostCommitWorkDispatcher(
            persistenceScope = applicationScope,
            enrichmentScope = applicationScope,
            persist = ::persistCommittedEvaluation,
            onPersistenceFailure = {
                processingCoordinator.invalidateAll()
                startActionOutboxRecovery(discardUnarmed = false)
                Log.w(TAG, "Post-commit notification persistence failed")
            },
            onEnrichmentFailure = { Log.w(TAG, "Post-commit notification enrichment failed") },
            enrich = ::processPostCommitEvaluation,
        )
    }

    // Active rules cached in-memory and recompiled only when they change, so each notification
    // evaluates against this hot snapshot instead of re-reading the DB per event (M3 performance).
    private val compiledRules = MutableStateFlow<CompiledRuleSet?>(null)
    private val filteringEnabled = MutableStateFlow<Boolean?>(null)
    private val semanticClassifierEnabled = MutableStateFlow<Boolean?>(null)
    private val llmAnalysisEnabled = MutableStateFlow<Boolean?>(null)
    private val semanticAnalysisScope = MutableStateFlow<SemanticAnalysisScope?>(null)
    private val contentStorageEnabled = MutableStateFlow<Boolean?>(null)
    private val contentExcludedPackages = MutableStateFlow<Set<String>?>(null)
    private var rulesJob: Job? = null
    private var settingsJob: Job? = null
    private val actionOutboxRecovered = MutableStateFlow(false)
    private val semanticGeneration = AtomicLong(0)
    private val actionOutboxRecoveryCoordinator by lazy {
        ActionOutboxRecoveryCoordinator(
            scope = scope,
            recoverStartup = notificationActionOutbox::recover,
            recoverArmed = notificationActionOutbox::recoverArmed,
            publishReady = { ready -> actionOutboxRecovered.value = ready },
            onFailure = {
                Log.w(TAG, "Notification action outbox recovery failed; retrying")
            },
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        startActionOutboxRecovery()
        rateLifecycleActor.connected()
        observeRulesIfNeeded()
        observeSettingsIfNeeded()
    }

    private fun startActionOutboxRecovery(discardUnarmed: Boolean = true) {
        actionOutboxRecoveryCoordinator.request(discardUnarmed)
    }

    private fun observeRulesIfNeeded() {
        // Every explicit refresh re-subscribes so its first Room query is ordered after the commit
        // that requested it, even when that transaction's invalidation emission already raced past.
        if (rulesJob?.isActive != true) {
            rulesJob =
                scope.launch {
                    collectCompiledRuleRefreshes(
                        refreshRequests = filteringActionGate.ruleRefreshRequests,
                        observeRules = ruleRepository::observeRules,
                        matcher = matcher,
                        publish = ::publishCompiledRules,
                        onFailure = ::handleRuleRefreshFailure,
                    )
                }
        }
    }

    private fun handleRuleRefreshFailure(requestId: Long) {
        // Keep the current id pending; emitting another id here would bypass retry backoff.
        filteringActionGate.rejectRuleRefresh(requestId)
        processingCoordinator.invalidateAllAndUpdate {
            semanticGeneration.incrementAndGet()
            compiledRules.value = CompiledRuleSet.EMPTY
        }
        semanticObservationQueue.clearPending()
        Log.w(TAG, "Rule cache refresh failed; retrying")
    }

    private fun publishCompiledRules(
        requestId: Long,
        compiled: CompiledRuleSet,
    ) {
        rateLifecycleActor.requestReseed()
        publishRuleSnapshot(
            coordinator = processingCoordinator,
            filteringActionGate = filteringActionGate,
            requestId = requestId,
        ) {
            semanticGeneration.incrementAndGet()
            compiledRules.value = compiled
        }
        semanticObservationQueue.clearPending()
    }

    private fun observeSettingsIfNeeded() {
        if (settingsJob?.isActive != true) {
            settingsJob =
                scope.launch {
                    var retryAttempt = 0
                    while (true) {
                        val result =
                            runCatchingPreservingCancellation {
                                val llmSettings =
                                    combine(
                                        settingsRepository.llmAnalysisEnabled,
                                        settingsRepository.semanticAnalysisScope,
                                        ::ListenerLlmSettings,
                                    )
                                combine(
                                    settingsRepository.filteringEnabled,
                                    settingsRepository.semanticClassifierEnabled,
                                    llmSettings,
                                    settingsRepository.notificationContentStorageEnabled,
                                    settingsRepository.contentExcludedPackages,
                                ) { filtering, classifierEnabled, llm, storeContent, excludedPackages ->
                                    ListenerSettings(
                                        filtering,
                                        classifierEnabled,
                                        llm.enabled,
                                        llm.scope,
                                        storeContent,
                                        excludedPackages,
                                    )
                                }.collect { settings ->
                                    retryAttempt = 0
                                    applySettings(settings)
                                }
                            }
                        runCatchingPreservingCancellation { resetSettingsCache() }
                        Log.w(
                            TAG,
                            if (result.isFailure) {
                                "Settings cache failed; retrying"
                            } else {
                                "Settings cache completed; retrying"
                            },
                        )
                        delay(ruleRefreshRetryDelayMillis(retryAttempt))
                        retryAttempt = (retryAttempt + 1).coerceAtMost(MAX_CACHE_RETRY_BACKOFF_STEP)
                    }
                }
        }
    }

    private suspend fun applySettings(settings: ListenerSettings) {
        val previous = currentSettings()
        if (previous == null) {
            updateFilteringGate(previousFiltering = null, filtering = settings.filtering)
            publishSettings(settings)
        } else if (previous != settings) {
            // Includes privacy reductions such as new content exclusions.
            updateFilteringGate(previousFiltering = previous.filtering, filtering = settings.filtering)
            processingCoordinator.invalidateAllAndUpdate {
                semanticGeneration.incrementAndGet()
                publishSettings(settings)
            }
            semanticObservationQueue.clearPending()
        }
        if (!settings.llmEnabled && previous?.llmEnabled == true) {
            semanticObservationQueue.clearPending()
            llmLifecycle.close()
        }
    }

    private suspend fun updateFilteringGate(
        previousFiltering: Boolean?,
        filtering: Boolean,
    ) = reconcileListenerFilteringState(filteringActionGate, previousFiltering, filtering)

    private fun publishSettings(settings: ListenerSettings) {
        filteringEnabled.value = settings.filtering
        semanticClassifierEnabled.value = settings.semanticClassifierEnabled
        llmAnalysisEnabled.value = settings.llmEnabled
        semanticAnalysisScope.value = settings.semanticScope
        contentStorageEnabled.value = settings.storeContent
        contentExcludedPackages.value = settings.excludedPackages
    }

    private fun currentSettings(): ListenerSettings? =
        filteringEnabled.value?.let { filtering ->
            ListenerSettings(
                filtering = filtering,
                semanticClassifierEnabled = semanticClassifierEnabled.value ?: false,
                llmEnabled = llmAnalysisEnabled.value ?: false,
                semanticScope = semanticAnalysisScope.value ?: SemanticAnalysisScope.RULES_ONLY,
                storeContent = contentStorageEnabled.value ?: false,
                excludedPackages = contentExcludedPackages.value.orEmpty(),
            )
        }

    private suspend fun resetSettingsCache() {
        // Fail closed: unknown settings must never activate destructive filtering.
        filteringActionGate.blockActions()
        processingCoordinator.invalidateAllAndUpdate {
            semanticGeneration.incrementAndGet()
            publishSettings(
                ListenerSettings(
                    filtering = false,
                    semanticClassifierEnabled = false,
                    llmEnabled = false,
                    semanticScope = SemanticAnalysisScope.RULES_ONLY,
                    storeContent = false,
                    excludedPackages = emptySet(),
                ),
            )
        }
        semanticObservationQueue.clearPending()
        llmLifecycle.close()
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
        val rateGeneration = rateLifecycleActor.currentLifecycleGeneration
        val rateOutcome = CompletableDeferred<RatePostOutcome>(parent = null)
        val postCommitReservation = postCommitWorkDispatcher.tryReserve()
        val processingJob =
            postCommitReservation?.let { reservation ->
                processingCoordinator.submit(key, freshness = snapshot.postedAtMillis) { token ->
                    runCatchingPreservingCancellation {
                        when (rateOutcome.await()) {
                            RatePostOutcome.Proceed ->
                                evaluateAndApply(
                                    key = key,
                                    snapshot = snapshot,
                                    token = token,
                                    postCommitReservation = reservation,
                                )
                            RatePostOutcome.Stale -> Unit
                        }
                    }
                        // Fixed message only: notification content must never enter logs (§1/§3).
                        .onFailure { Log.w(TAG, "Notification processing failed") }
                }
            }
        if (processingJob == null) {
            // A newer post must still invalidate older work when capacity is full, while a late
            // callback must not evict fresher same-key work.
            processingCoordinator.invalidateIfAtLeastAsFresh(key, snapshot.postedAtMillis)
        }
        postCommitReservation?.let { reservation ->
            processingJob?.invokeOnCompletion {
                postCommitWorkDispatcher.release(reservation)
            }
        }
        // Submit first: a newer post invalidates older work even while this actor is in Room I/O.
        rateLifecycleActor.tryPost(
            generation = rateGeneration,
            rawListenerKey = key,
            packageName = snapshot.packageName,
            channelId = snapshot.channelId,
            postedAtMillis = snapshot.postedAtMillis,
            outcome = rateOutcome,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val rateGeneration = rateLifecycleActor.currentLifecycleGeneration
        processingCoordinator.invalidate(sbn.key)
        rateLifecycleActor.tryRemove(
            generation = rateGeneration,
            rawListenerKey = sbn.key,
            removedPostTimeMillis = sbn.postTime,
        )
    }

    override fun onListenerDisconnected() {
        actionOutboxRecoveryCoordinator.stop()
        processingCoordinator.invalidateAll()
        rateLifecycleActor.disconnected()
        semanticGeneration.incrementAndGet()
        releaseLlmAsync()
        super.onListenerDisconnected()
    }

    // One state machine intentionally keeps stage, arm, platform commit, and durable handoff together.
    @Suppress("LongMethod", "TooGenericExceptionCaught")
    private suspend fun evaluateAndApply(
        key: String,
        snapshot: NotificationSnapshot,
        token: NotificationProcessingCoordinator.ProcessingToken,
        postCommitReservation: PostCommitWorkDispatcher.Reservation,
    ) {
        // Startup cache failures must fail open rather than hold listener work indefinitely.
        val runtime =
            withTimeoutOrNull(CACHE_READY_TIMEOUT_MILLIS) {
                actionOutboxRecovered.filter { it }.first()
                ListenerRuntime(
                    filtering = filteringEnabled.filterNotNull().first(),
                    semanticClassifierEnabled = semanticClassifierEnabled.filterNotNull().first(),
                    llmEnabled = llmAnalysisEnabled.filterNotNull().first(),
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
                runtime.semanticGeneration,
                runtime.semanticClassifierEnabled,
                token,
            ) ?: return
        val historyEpoch = notificationHistoryWriteFence.captureEpoch()
        val prepared = prepareBasePersistedEvaluation(evaluated, runtime)
        if (!token.isCurrent()) return
        var stagedAction: StagedNotificationAction? = null
        var armed = false
        var committed = false
        try {
            stagedAction =
                withContext(NonCancellable) {
                    notificationHistoryWriteFence.writeIfCurrent(historyEpoch) {
                        notificationActionOutbox.stage(
                            event = prepared.toEvent(evaluated),
                            content = prepared.content,
                        )
                    }
                }
            if (stagedAction == null) return
            if (!token.isCurrent()) return
            val durableStage = stagedAction
            committed =
                commitFilteringAction(
                    token = token,
                    filteringActionGate = filteringActionGate,
                    tokenCommit = { action ->
                        rateLifecycleActor.commitIfRateCountsCurrent(
                            snapshot = evaluated.common,
                            expectation = evaluated.rateCountExpectation,
                        ) {
                            token.commit {
                                armed =
                                    armStagedActionAndRun(
                                        outbox = notificationActionOutbox,
                                        staged = durableStage,
                                        onArmed = { armed = true },
                                        action = action,
                                    )
                            } &&
                                armed
                        }
                    },
                ) {
                    applyAction(key, evaluated.action)
                }
        } catch (error: Throwable) {
            if (armed) {
                processingCoordinator.invalidateAll()
                startActionOutboxRecovery(discardUnarmed = false)
            }
            throw error
        } finally {
            discardUnarmedStagedAction(
                outbox = notificationActionOutbox,
                staged = stagedAction,
                armed = armed,
                onFailure = {
                    Log.w(TAG, "Uncommitted notification action cleanup failed")
                },
            )
        }
        if (!committed) {
            return
        }
        try {
            withContext(NonCancellable) {
                postCommitWorkDispatcher.submit(
                    reservation = postCommitReservation,
                    request =
                        PostCommitEvaluationRequest(
                            evaluated = evaluated,
                            runtime = runtime,
                            historyEpoch = historyEpoch,
                            stagedAction = requireNotNull(stagedAction),
                            prepared = prepared,
                        ),
                )
            }
        } catch (error: Throwable) {
            startActionOutboxRecovery(discardUnarmed = false)
            throw error
        }
    }

    private suspend fun processPostCommitEvaluation(request: PersistedPostCommitEvaluationRequest) {
        val runtime = request.original.runtime
        val originalEvaluation = request.original.evaluated
        var evaluated = originalEvaluation
        val optionalEnrichmentCurrent =
            isPostCommitOptionalEnrichmentCurrent(
                capturedGeneration = runtime.semanticGeneration,
                currentGeneration = semanticGeneration.get(),
            )
        val categoryClassification =
            if (optionalEnrichmentCurrent && evaluated.common.mlCategory == null) {
                categoryRuleResolver.resolveMonitorAfterCommit(evaluated.common)
            } else {
                null
            }
        val enrichedCommon =
            categoryClassification?.let { classification ->
                evaluated.common.copy(mlCategory = classification.category)
            } ?: evaluated.common
        val postCommitNeedsSemantic =
            evaluated.monitorNeedsPostCommitSemantic ||
                matcher
                    .semanticResolutionRequirements(
                        enrichedCommon,
                        runtime.compiledRules,
                    ).monitorNeedsSemantic
        val monitorEvaluation =
            semanticRuleResolver.resolveMonitorAfterCommit(
                snapshot = enrichedCommon,
                compiled = runtime.compiledRules,
                existingClassification = evaluated.semanticClassification,
                classifySemantic = postCommitNeedsSemantic,
                classifierEnabled =
                    isPostCommitSemanticClassifierEnabled(
                        capturedGeneration = runtime.semanticGeneration,
                        currentGeneration = semanticGeneration.get(),
                        currentEnabled = semanticClassifierEnabled.value,
                    ),
                existingDecisionTrace = evaluated.decisionTrace,
            )
        evaluated =
            evaluated.copy(
                common = enrichedCommon,
                monitoredAction = monitorEvaluation.monitoredAction,
                monitoredRuleId = monitorEvaluation.monitoredRuleId,
                mlConfidence =
                    categoryClassification?.confidence
                        ?: evaluated.mlConfidence,
                semanticClassification = monitorEvaluation.classification,
                needsDelayedSemanticObservation =
                    evaluated.needsDelayedSemanticObservation ||
                        monitorEvaluation.needsDelayedObservation,
                decisionTrace = monitorEvaluation.decisionTrace,
                monitorNeedsPostCommitMlCategory = false,
                monitorNeedsPostCommitSemantic = false,
            )
        completePostCommitEnrichment(
            request = request,
            evaluated = evaluated,
        )
    }

    private suspend fun completePostCommitEnrichment(
        request: PersistedPostCommitEvaluationRequest,
        evaluated: EvaluatedNotification,
    ) {
        val runtime = request.original.runtime
        eventRepository.enrichRecordedDecision(
            eventId = request.eventId,
            enrichment =
                NotificationDecisionEnrichment(
                    mlCategory = evaluated.common.mlCategory,
                    mlConfidence = evaluated.mlConfidence,
                    monitoredRuleId = evaluated.monitoredRuleId,
                    monitoredAction = evaluated.monitoredAction,
                    decisionTrace = evaluated.decisionTrace,
                ),
        )
        evaluated.semanticClassification
            ?.let { result ->
                recordSemanticObservation(
                    eventId = request.eventId,
                    packageName = request.persistedSnapshot.packageName,
                    intent = result.intent,
                    confidenceScore = result.confidence,
                )
            }
        enqueueSemanticObservationIfEligible(
            eventId = request.eventId,
            snapshot = request.persistedSnapshot,
            evaluated = evaluated,
            runtime = runtime,
        )
    }

    private suspend fun prepareEvaluation(
        snapshot: NotificationSnapshot,
        compiled: CompiledRuleSet,
        semanticGeneration: Long,
        semanticClassifierEnabled: Boolean,
        token: NotificationProcessingCoordinator.ProcessingToken,
    ): EvaluatedNotification? {
        if (!token.isCurrent()) return null
        val requirements = compiled.requiredSignals
        val rateState =
            rateLifecycleActor.captureCounts(
                snapshot,
                requirements.rateSignals,
            )
        val categoryEvaluation =
            categoryRuleResolver.resolveBeforeActiveCommit(
                snapshot.copy(rateCounts = rateState.counts),
                compiled,
            )
        if (!token.isCurrent()) return null
        val common =
            snapshot.copy(
                mlCategory = categoryEvaluation.classification?.category,
                rateCounts = rateState.counts,
            )
        if (categoryEvaluation.activeResolutionFailed) {
            val monitorDecision = matcher.evaluateMonitor(common, compiled)
            val monitor = monitorDecision.resolve(null)
            return EvaluatedNotification(
                common = common,
                action = RuleAction.Keep,
                matchedRuleId = null,
                monitoredAction = monitor.action,
                monitoredRuleId = monitor.ruleId,
                mlConfidence = null,
                semanticClassification = null,
                needsDelayedSemanticObservation = false,
                decisionTrace =
                    matcher.decisionTrace(
                        common,
                        monitorDecision,
                        DecisionTraceLane.MONITOR,
                    ),
                monitorNeedsPostCommitMlCategory =
                    categoryEvaluation.monitorNeedsPostCommitClassification,
                monitorNeedsPostCommitSemantic =
                    matcher
                        .semanticResolutionRequirements(common, compiled)
                        .monitorNeedsSemantic,
                semanticGeneration = semanticGeneration,
                rateCountExpectation = null,
            )
        }
        val semanticEvaluation =
            semanticRuleResolver.resolve(
                snapshot = common,
                compiled = compiled,
                classifierEnabled = semanticClassifierEnabled,
            )
        if (!token.isCurrent()) return null
        return EvaluatedNotification(
            common = common,
            action = semanticEvaluation.action,
            matchedRuleId = semanticEvaluation.matchedRuleId,
            monitoredAction = semanticEvaluation.monitoredAction,
            monitoredRuleId = semanticEvaluation.monitoredRuleId,
            mlConfidence = categoryEvaluation.classification?.confidence,
            semanticClassification = semanticEvaluation.classification,
            needsDelayedSemanticObservation = semanticEvaluation.needsDelayedObservation,
            decisionTrace = semanticEvaluation.decisionTrace,
            monitorNeedsPostCommitMlCategory =
                categoryEvaluation.monitorNeedsPostCommitClassification,
            monitorNeedsPostCommitSemantic = semanticEvaluation.monitorNeedsPostCommitSemantic,
            semanticGeneration = semanticGeneration,
            rateCountExpectation =
                destructiveRateCountExpectation(
                    action = semanticEvaluation.action,
                    activeRateSignals = compiled.activeRequiredSignals.rateSignals,
                    capturedCounts = rateState.counts,
                ),
        )
    }

    private suspend fun persistCommittedEvaluation(
        request: PostCommitEvaluationRequest,
    ): PersistedPostCommitEvaluationRequest? {
        val prepared = request.prepared
        val persistedSnapshot = prepared.snapshot
        val eventId =
            notificationHistoryWriteFence.writeIfCurrent(request.historyEpoch) {
                checkNotNull(
                    promoteStagedActionWithRetry(request.stagedAction) {
                        notificationActionOutbox.promote(it)
                    },
                ) { "Armed notification action was missing during promotion" }
            } ?: return null
        return PersistedPostCommitEvaluationRequest(
            original = request,
            eventId = eventId,
            persistedSnapshot = persistedSnapshot,
        )
    }

    private fun prepareBasePersistedEvaluation(
        evaluated: EvaluatedNotification,
        runtime: ListenerRuntime,
    ): PreparedPersistedEvaluation {
        val persistedSnapshot = evaluated.common
        val content =
            persistedSnapshot
                .takeIf {
                    runtime.storeContent &&
                        it.contentVisibility != NotificationContentVisibility.SECRET &&
                        it.packageName !in runtime.excludedPackages
                }?.let { NotificationContent(it.title, it.text) }
        return PreparedPersistedEvaluation(
            snapshot = persistedSnapshot,
            mlConfidence = evaluated.mlConfidence,
            content = content,
            recordedAtMillis = clock.millis(),
        )
    }

    private fun enqueueSemanticObservationIfEligible(
        eventId: String,
        snapshot: NotificationSnapshot,
        evaluated: EvaluatedNotification,
        runtime: ListenerRuntime,
    ) {
        val requireAllNotifications = !evaluated.needsDelayedSemanticObservation
        val scopeAllowsObservation =
            evaluated.needsDelayedSemanticObservation ||
                runtime.semanticScope == SemanticAnalysisScope.ALL_NOTIFICATIONS
        val modelAllowsObservation =
            runtime.llmEnabled &&
                llmManager.backgroundAnalysisEligibility ==
                LlmBackgroundAnalysisEligibility.VERIFIED_COMPATIBLE &&
                snapshot.hasClassifiableText()
        if (!scopeAllowsObservation || !modelAllowsObservation) return
        if (
            !isSemanticAnalysisCurrent(
                evaluated.semanticGeneration,
                requireAllNotifications = requireAllNotifications,
            )
        ) {
            return
        }
        semanticObservationQueue.offer(
            SemanticObservationRequest(
                eventId = eventId,
                snapshot = snapshot,
                semanticGeneration = evaluated.semanticGeneration,
                requireAllNotifications = requireAllNotifications,
            ),
        )
    }

    private suspend fun processSemanticObservation(request: SemanticObservationRequest) {
        if (
            llmManager.backgroundAnalysisEligibility !=
            LlmBackgroundAnalysisEligibility.VERIFIED_COMPATIBLE ||
            !request.snapshot.hasClassifiableText() ||
            !isSemanticAnalysisCurrent(
                request.semanticGeneration,
                requireAllNotifications = request.requireAllNotifications,
            )
        ) {
            return
        }
        if (!initializeLlmForObservation(request)) return
        if (
            !isSemanticAnalysisCurrent(
                request.semanticGeneration,
                requireAllNotifications = request.requireAllNotifications,
            )
        ) {
            return
        }
        analyzeAdvertisement(request.snapshot)?.let { result ->
            if (
                isSemanticAnalysisCurrent(
                    request.semanticGeneration,
                    requireAllNotifications = request.requireAllNotifications,
                )
            ) {
                recordSemanticObservation(
                    eventId = request.eventId,
                    packageName = request.snapshot.packageName,
                    intent = result.intent,
                    confidenceScore = result.confidenceScore,
                )
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
        intent: SemanticIntent,
        confidenceScore: Float,
    ) {
        if (!confidenceScore.isFinite() || confidenceScore <= 0f) return
        adFeedbackRepository.recordObservation(
            AdObservation(
                notificationEventId = eventId,
                packageName = packageName,
                predictedIsAdvertisement = intent.isAdvertisement,
                predictedIntent = intent,
                confidenceScore = confidenceScore,
                analyzedAtMillis = clock.millis(),
            ),
        )
    }

    /**
     * Runs optional local semantic analysis with a hard caller deadline. The manager retains native
     * ownership after a timeout, so a late result is discarded and cannot apply an action.
     */
    private suspend fun analyzeAdvertisement(snapshot: NotificationSnapshot): LlmAnalysisResult? =
        analyzeDelayedSemanticObservation(
            snapshot = snapshot,
            llmManager = llmManager,
            timeoutMillis = LLM_ANALYSIS_TIMEOUT_MILLIS,
        )

    private suspend fun initializeLlmForObservation(request: SemanticObservationRequest): Boolean =
        llmLifecycle.initializeIfCurrent {
            isSemanticAnalysisCurrent(
                request.semanticGeneration,
                requireAllNotifications = request.requireAllNotifications,
            )
        }

    private fun releaseLlmAsync() {
        semanticObservationQueue.clearPending()
        applicationScope.launch {
            llmLifecycle.close()
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
        actionOutboxRecoveryCoordinator.stop()
        processingCoordinator.invalidateAll()
        rateLifecycleActor.close()
        semanticGeneration.incrementAndGet()
        releaseLlmAsync()
        postCommitWorkDispatcher.close()
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
        val semanticClassification: SemanticClassificationResult?,
        val needsDelayedSemanticObservation: Boolean,
        val decisionTrace: List<DecisionTraceNode>,
        val monitorNeedsPostCommitMlCategory: Boolean,
        val monitorNeedsPostCommitSemantic: Boolean,
        val semanticGeneration: Long,
        val rateCountExpectation: RateCountExpectation?,
    )

    private companion object {
        const val TAG = "NotificationFilter"

        const val LLM_ANALYSIS_TIMEOUT_MILLIS = 10_000L
        const val CACHE_READY_TIMEOUT_MILLIS = 2_000L
        const val MAX_CACHE_RETRY_BACKOFF_STEP = 6
    }

    private data class ListenerSettings(
        val filtering: Boolean,
        val semanticClassifierEnabled: Boolean,
        val llmEnabled: Boolean,
        val semanticScope: SemanticAnalysisScope,
        val storeContent: Boolean,
        val excludedPackages: Set<String>,
    )

    private data class ListenerRuntime(
        val filtering: Boolean,
        val semanticClassifierEnabled: Boolean,
        val llmEnabled: Boolean,
        val storeContent: Boolean,
        val excludedPackages: Set<String>,
        val semanticScope: SemanticAnalysisScope,
        val compiledRules: CompiledRuleSet,
        val semanticGeneration: Long,
    )

    private data class ListenerLlmSettings(
        val enabled: Boolean,
        val scope: SemanticAnalysisScope,
    )

    private data class SemanticObservationRequest(
        val eventId: String,
        val snapshot: NotificationSnapshot,
        val semanticGeneration: Long,
        val requireAllNotifications: Boolean,
    )

    private data class PostCommitEvaluationRequest(
        val evaluated: EvaluatedNotification,
        val runtime: ListenerRuntime,
        val historyEpoch: NotificationHistoryWriteFence.Epoch,
        val stagedAction: StagedNotificationAction,
        val prepared: PreparedPersistedEvaluation,
    )

    private data class PreparedPersistedEvaluation(
        val snapshot: NotificationSnapshot,
        val mlConfidence: Float?,
        val content: NotificationContent?,
        val recordedAtMillis: Long,
    ) {
        fun toEvent(evaluated: EvaluatedNotification): NotificationEvent =
            NotificationEvent(
                packageName = snapshot.packageName,
                channelId = snapshot.channelId,
                channelName = snapshot.channelName,
                mlCategory = snapshot.mlCategory,
                mlConfidence = mlConfidence,
                category = snapshot.category,
                postedAtMillis = snapshot.postedAtMillis,
                postedEpochDay = snapshot.postedEpochDay,
                postedMinuteOfDay = snapshot.postedMinuteOfDay,
                importance = snapshot.importance,
                isConversation = snapshot.isConversation,
                isForegroundService = snapshot.isForegroundService,
                action = evaluated.action,
                matchedRuleId = evaluated.matchedRuleId,
                monitoredRuleId = evaluated.monitoredRuleId,
                monitoredAction = evaluated.monitoredAction,
                decisionTrace = evaluated.decisionTrace,
                recordedAtMillis = recordedAtMillis,
            )
    }

    private data class PersistedPostCommitEvaluationRequest(
        val original: PostCommitEvaluationRequest,
        val eventId: String,
        val persistedSnapshot: NotificationSnapshot,
    )
}

internal class ActionOutboxRecoveryCoordinator(
    private val scope: CoroutineScope,
    private val recoverStartup: suspend () -> Int,
    private val recoverArmed: suspend () -> Int,
    private val publishReady: (Boolean) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val installationLock = Any()
    private val recoveryMutex = Mutex()
    private val generation = AtomicLong()
    private var recoveryJob: Job? = null

    fun request(discardUnarmed: Boolean): Long =
        synchronized(installationLock) {
            val requestGeneration = generation.incrementAndGet()
            publishReady(false)
            recoveryJob?.cancel()
            recoveryJob =
                scope.launch {
                    recoveryMutex.withLock {
                        val recovered =
                            recoverActionOutboxUntilSuccess(
                                isCurrent = { generation.get() == requestGeneration },
                                recover = if (discardUnarmed) recoverStartup else recoverArmed,
                                onFailure = onFailure,
                            )
                        if (recovered) {
                            synchronized(installationLock) {
                                if (generation.get() == requestGeneration) {
                                    publishReady(true)
                                }
                            }
                        }
                    }
                }
            requestGeneration
        }

    fun stop() {
        synchronized(installationLock) {
            generation.incrementAndGet()
            publishReady(false)
            recoveryJob?.cancel()
            recoveryJob = null
        }
    }
}

internal suspend fun reconcileListenerFilteringState(
    filteringActionGate: FilteringActionGate,
    previousFiltering: Boolean?,
    filtering: Boolean,
) {
    when {
        !filtering -> filteringActionGate.blockActions()
        previousFiltering == null -> filteringActionGate.initializeFromPersistedState(true)
        !previousFiltering -> filteringActionGate.requestRuleRefresh()
    }
}

internal suspend fun <T> retryActionOutboxOperation(
    maxAttempts: Int = 3,
    initialDelayMillis: Long = 25L,
    operation: suspend () -> T,
): Result<T> {
    require(maxAttempts > 0) { "Action outbox attempts must be positive" }
    require(initialDelayMillis >= 0) { "Action outbox retry delay must not be negative" }
    var lastFailure: Throwable? = null
    repeat(maxAttempts) { attempt ->
        val result = runCatchingPreservingCancellation { operation() }
        if (result.isSuccess) return result
        lastFailure = result.exceptionOrNull()
        if (attempt + 1 < maxAttempts && initialDelayMillis > 0) {
            delay(initialDelayMillis * (1L shl attempt))
        }
    }
    return Result.failure(requireNotNull(lastFailure))
}

internal suspend fun promoteStagedActionWithRetry(
    staged: StagedNotificationAction,
    promote: suspend (StagedNotificationAction) -> String?,
): String? =
    retryActionOutboxOperation {
        promote(staged)
    }.getOrThrow()

internal fun actionOutboxRecoveryDelayMillis(attempt: Int): Long {
    require(attempt >= 0) { "Action outbox recovery attempt must not be negative" }
    return (
        INITIAL_ACTION_OUTBOX_RECOVERY_DELAY_MILLIS *
            (1L shl attempt.coerceAtMost(MAX_ACTION_OUTBOX_RECOVERY_BACKOFF_EXPONENT))
    ).coerceAtMost(MAX_ACTION_OUTBOX_RECOVERY_DELAY_MILLIS)
}

internal suspend fun recoverActionOutboxUntilSuccess(
    isCurrent: () -> Boolean,
    recover: suspend () -> Int,
    onFailure: (Throwable) -> Unit = {},
): Boolean {
    var attempt = 0
    while (isCurrent()) {
        val result = runCatchingPreservingCancellation { recover() }
        if (result.isSuccess) return true
        result.exceptionOrNull()?.let(onFailure)
        delay(actionOutboxRecoveryDelayMillis(attempt))
        attempt = (attempt + 1).coerceAtMost(MAX_ACTION_OUTBOX_RECOVERY_BACKOFF_EXPONENT)
    }
    return false
}

private const val INITIAL_ACTION_OUTBOX_RECOVERY_DELAY_MILLIS = 100L
private const val MAX_ACTION_OUTBOX_RECOVERY_DELAY_MILLIS = 5_000L
private const val MAX_ACTION_OUTBOX_RECOVERY_BACKOFF_EXPONENT = 6

internal fun armStagedActionAndRun(
    outbox: NotificationActionOutbox,
    staged: StagedNotificationAction?,
    onArmed: () -> Unit = {},
    action: () -> Unit,
): Boolean {
    val armed = staged == null || runCatching { outbox.arm(staged) }.getOrDefault(false)
    if (armed) {
        onArmed()
        action()
    }
    return armed
}

internal suspend fun discardUnarmedStagedAction(
    outbox: NotificationActionOutbox,
    staged: StagedNotificationAction?,
    armed: Boolean,
    onFailure: (Throwable) -> Unit = {},
) {
    if (staged == null || armed) return
    withContext(NonCancellable) {
        runCatchingPreservingCancellation {
            outbox.discard(staged)
        }.onFailure(onFailure)
    }
}

internal fun destructiveRateCountExpectation(
    action: RuleAction,
    activeRateSignals: Set<RateSignal>,
    capturedCounts: Map<RateSignal, Int>,
): RateCountExpectation? {
    if (action != RuleAction.Cancel && action !is RuleAction.Snooze) return null
    if (activeRateSignals.isEmpty()) return null
    val requestedSignals = activeRateSignals.toSet()
    return RateCountExpectation(
        requestedSignals = requestedSignals,
        expectedCounts = capturedCounts.filterKeys(requestedSignals::contains),
    )
}

internal class RealtimeCategoryRuleResolver(
    private val matcher: Matcher,
    private val classifier: NotificationClassifier,
    private val timeoutMillis: Long = CATEGORY_CLASSIFICATION_TIMEOUT_MILLIS,
) {
    suspend fun resolveBeforeActiveCommit(
        snapshot: NotificationSnapshot,
        compiled: CompiledRuleSet,
    ): RealtimeCategoryRuleEvaluation {
        val requirements = matcher.categoryResolutionRequirements(snapshot, compiled)
        val activeNeedsClassification = requirements.activeNeedsCategory
        val classification =
            if (activeNeedsClassification) {
                classify(snapshot)
            } else {
                null
            }
        return RealtimeCategoryRuleEvaluation(
            classification = classification,
            activeResolutionFailed = activeNeedsClassification && classification == null,
            monitorNeedsPostCommitClassification =
                requirements.monitorNeedsCategory &&
                    classification == null,
        )
    }

    suspend fun resolveMonitorAfterCommit(snapshot: NotificationSnapshot): ClassificationResult? = classify(snapshot)

    private suspend fun classify(snapshot: NotificationSnapshot): ClassificationResult? =
        runCatchingPreservingCancellation {
            withTimeoutOrNull(timeoutMillis) {
                classifier.classify(snapshot)
            }
        }.getOrNull()

    private companion object {
        const val CATEGORY_CLASSIFICATION_TIMEOUT_MILLIS = 500L
    }
}

internal data class RealtimeCategoryRuleEvaluation(
    val classification: ClassificationResult?,
    val activeResolutionFailed: Boolean,
    val monitorNeedsPostCommitClassification: Boolean,
)

internal class RealtimeSemanticRuleResolver(
    private val matcher: Matcher,
    private val classifier: SemanticNotificationClassifier,
    private val timeoutMillis: Long = SEMANTIC_CLASSIFICATION_TIMEOUT_MILLIS,
) {
    suspend fun resolve(
        snapshot: NotificationSnapshot,
        compiled: CompiledRuleSet,
        classifierEnabled: Boolean = true,
    ): RealtimeSemanticRuleEvaluation {
        val requirements = matcher.semanticResolutionRequirements(snapshot, compiled)
        val classification =
            if (requirements.activeNeedsSemantic && classifierEnabled) {
                classify(snapshot, SemanticInferenceUrgency.REALTIME)
            } else {
                null
            }
        val trustedIntent = classification?.trustedIntent
        val needsDelayedObservation =
            requirements.activeNeedsSemantic &&
                trustedIntent == null
        if (requirements.activeNeedsSemantic && trustedIntent == null) {
            val evaluation =
                matcher.evaluateAfterSemanticFailureWithTraces(
                    snapshot,
                    compiled,
                )
            val active = evaluation.activeDecision.resolve(RuleAction.Keep)
            val monitor = evaluation.monitorDecision.resolve(null)
            return RealtimeSemanticRuleEvaluation(
                action = requireNotNull(active.action),
                matchedRuleId = active.ruleId,
                monitoredAction = monitor.action,
                monitoredRuleId = monitor.ruleId,
                classification = classification,
                needsDelayedObservation = true,
                decisionTrace = evaluation.decisionTrace,
                monitorNeedsPostCommitSemantic = false,
            )
        }
        val resolvedSnapshot =
            trustedIntent?.let { intent ->
                snapshot.copy(
                    semanticIntent = intent,
                    isAdvertisement = intent.isAdvertisement,
                )
            } ?: snapshot
        val evaluation =
            matcher.evaluateWithTraces(
                activeSnapshot = resolvedSnapshot,
                monitorSnapshot = resolvedSnapshot,
                compiled = compiled,
            )
        val active = evaluation.activeDecision.resolve(RuleAction.Keep)
        val monitor = evaluation.monitorDecision.resolve(null)
        return RealtimeSemanticRuleEvaluation(
            action = requireNotNull(active.action),
            matchedRuleId = active.ruleId,
            monitoredAction = monitor.action,
            monitoredRuleId = monitor.ruleId,
            classification = classification,
            needsDelayedObservation = needsDelayedObservation,
            decisionTrace = evaluation.decisionTrace,
            monitorNeedsPostCommitSemantic =
                !requirements.activeNeedsSemantic && requirements.monitorNeedsSemantic,
        )
    }

    /**
     * Enriches only the record-only monitor lane after the caller has committed the active action.
     * The return type deliberately carries no active action, so late inference cannot change it.
     */
    suspend fun resolveMonitorAfterCommit(
        snapshot: NotificationSnapshot,
        compiled: CompiledRuleSet,
        existingClassification: SemanticClassificationResult? = null,
        classifySemantic: Boolean = true,
        classifierEnabled: Boolean = true,
        existingDecisionTrace: List<DecisionTraceNode> = emptyList(),
    ): PostCommitMonitorSemanticEvaluation {
        val classification =
            existingClassification
                ?: if (classifySemantic && classifierEnabled) {
                    classify(snapshot, SemanticInferenceUrgency.BACKGROUND)
                } else {
                    null
                }
        val trustedIntent = classification?.trustedIntent
        val monitorSnapshot =
            trustedIntent?.let { intent ->
                snapshot.copy(
                    semanticIntent = intent,
                    isAdvertisement = intent.isAdvertisement,
                )
            } ?: snapshot
        val monitorDecision = matcher.evaluateMonitor(monitorSnapshot, compiled)
        val monitor = monitorDecision.resolve(null)
        val activeTrace =
            existingDecisionTrace.filter { node ->
                node.lane == DecisionTraceLane.ACTIVE
            }
        val monitorTrace =
            matcher.decisionTrace(
                snapshot = monitorSnapshot,
                decision = monitorDecision,
                lane = DecisionTraceLane.MONITOR,
                maxNodes = (MAX_PERSISTED_TRACE_NODES - activeTrace.size).coerceAtLeast(0),
            )
        return PostCommitMonitorSemanticEvaluation(
            monitoredAction = monitor.action,
            monitoredRuleId = monitor.ruleId,
            classification = classification,
            needsDelayedObservation = classifySemantic && trustedIntent == null,
            decisionTrace = activeTrace + monitorTrace,
        )
    }

    private suspend fun classify(
        snapshot: NotificationSnapshot,
        urgency: SemanticInferenceUrgency,
    ): SemanticClassificationResult? =
        runCatchingPreservingCancellation {
            withTimeoutOrNull(timeoutMillis) {
                classifier.classify(snapshot, urgency)
            }
        }.getOrNull()

    private companion object {
        const val SEMANTIC_CLASSIFICATION_TIMEOUT_MILLIS = 350L
    }
}

internal data class RealtimeSemanticRuleEvaluation(
    val action: RuleAction,
    val matchedRuleId: String?,
    val monitoredAction: RuleAction?,
    val monitoredRuleId: String?,
    val classification: SemanticClassificationResult?,
    val needsDelayedObservation: Boolean,
    val decisionTrace: List<DecisionTraceNode>,
    val monitorNeedsPostCommitSemantic: Boolean,
)

internal data class PostCommitMonitorSemanticEvaluation(
    val monitoredAction: RuleAction?,
    val monitoredRuleId: String?,
    val classification: SemanticClassificationResult?,
    val needsDelayedObservation: Boolean,
    val decisionTrace: List<DecisionTraceNode>,
)

private fun MatchDecision.resolve(fallbackAction: RuleAction?): ResolvedDecision =
    when (this) {
        is MatchDecision.Matched -> ResolvedDecision(action, rule.id)
        MatchDecision.NoMatch -> ResolvedDecision(fallbackAction, null)
    }

private data class ResolvedDecision(
    val action: RuleAction?,
    val ruleId: String?,
)

internal fun isPostCommitOptionalEnrichmentCurrent(
    capturedGeneration: Long,
    currentGeneration: Long,
): Boolean = capturedGeneration == currentGeneration

internal fun isPostCommitSemanticClassifierEnabled(
    capturedGeneration: Long,
    currentGeneration: Long,
    currentEnabled: Boolean?,
): Boolean =
    isPostCommitOptionalEnrichmentCurrent(capturedGeneration, currentGeneration) &&
        currentEnabled == true

internal suspend fun analyzeDelayedSemanticObservation(
    snapshot: NotificationSnapshot,
    llmManager: OnDeviceLlmManager,
    timeoutMillis: Long,
): LlmAnalysisResult? {
    if (
        llmManager.backgroundAnalysisEligibility !=
        LlmBackgroundAnalysisEligibility.VERIFIED_COMPATIBLE
    ) {
        return null
    }
    val text = listOfNotNull(snapshot.title, snapshot.text).joinToString(separator = " ").ifBlank { return null }
    return withTimeoutOrNull(timeoutMillis) {
        llmManager.analyze(text, snapshot.packageName)
    }
}

private fun NotificationSnapshot.hasClassifiableText(): Boolean = !title.isNullOrBlank() || !text.isNullOrBlank()
