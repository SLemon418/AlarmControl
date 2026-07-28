package com.alarmcontrol.service

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.DecisionTraceLane
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.ml.SemanticClassificationResult
import com.alarmcontrol.ml.SemanticInferenceUrgency
import com.alarmcontrol.ml.SemanticNotificationClassifier
import com.alarmcontrol.ml.llm.LlmAnalysisResult
import com.alarmcontrol.ml.llm.LlmBackgroundAnalysisEligibility
import com.alarmcontrol.ml.llm.OnDeviceLlmManager
import com.alarmcontrol.notifications.Matcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeSemanticRuleResolverTest {
    private val matcher = Matcher()
    private val classifier = mockk<SemanticNotificationClassifier>()
    private val resolver = RealtimeSemanticRuleResolver(matcher, classifier)
    private val snapshot =
        NotificationSnapshot(
            packageName = "com.example.local",
            title = "Limited offer",
            text = "Save on your next order",
            category = "alarm",
            channelId = "general",
            postedAtMillis = 0L,
            isOngoing = false,
        )

    @Test
    fun `stable current match does not call semantic encoder`() =
        runTest {
            val stable = rule("stable", Condition.CategoryEquals("alarm"), RuleAction.Cancel, priority = 100)
            val lowerSemantic =
                rule(
                    "lower-semantic",
                    Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                    RuleAction.Cancel,
                    priority = 10,
                )

            val result = resolver.resolve(snapshot, matcher.compile(listOf(lowerSemantic, stable)))

            assertEquals(RuleAction.Cancel, result.action)
            assertEquals("stable", result.matchedRuleId)
            assertFalse(result.needsDelayedObservation)
            assertNull(result.classification)
            coVerify(exactly = 0) { classifier.classify(any(), any()) }
        }

    @Test
    fun `monitor-only semantic inference starts after the active action is ready to commit`() =
        runTest {
            val classification = semanticResult(isConfident = true)
            val classificationStarted = CompletableDeferred<Unit>()
            val releaseClassification = CompletableDeferred<Unit>()
            coEvery { classifier.classify(any(), SemanticInferenceUrgency.BACKGROUND) } coAnswers {
                classificationStarted.complete(Unit)
                releaseClassification.await()
                classification
            }
            val active =
                rule("active", Condition.CategoryEquals("alarm"), RuleAction.Cancel, priority = 100)
            val monitorSemantic =
                rule(
                    "monitor-semantic",
                    Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                    RuleAction.Cancel,
                    priority = 100,
                    mode = RuleExecutionMode.MONITOR,
                )
            val monitorFallback =
                rule(
                    "monitor-fallback",
                    Condition.CategoryEquals("alarm"),
                    RuleAction.Keep,
                    priority = 10,
                    mode = RuleExecutionMode.MONITOR,
                )
            val compiled = matcher.compile(listOf(active, monitorSemantic, monitorFallback))

            val beforeCommit = resolver.resolve(snapshot, compiled)

            assertEquals(RuleAction.Cancel, beforeCommit.action)
            assertEquals("active", beforeCommit.matchedRuleId)
            assertEquals(RuleAction.Keep, beforeCommit.monitoredAction)
            assertTrue(beforeCommit.monitorNeedsPostCommitSemantic)
            assertFalse(beforeCommit.needsDelayedObservation)
            coVerify(exactly = 0) { classifier.classify(any(), any()) }

            val order = mutableListOf("active-commit")
            val enrichment =
                async {
                    resolver.resolveMonitorAfterCommit(snapshot, compiled).also {
                        order += "monitor-enrichment"
                    }
                }
            runCurrent()
            classificationStarted.await()

            assertEquals(listOf("active-commit"), order)
            assertFalse(enrichment.isCompleted)

            releaseClassification.complete(Unit)
            val afterCommit = enrichment.await()

            assertEquals(listOf("active-commit", "monitor-enrichment"), order)
            assertEquals(RuleAction.Cancel, afterCommit.monitoredAction)
            assertEquals("monitor-semantic", afterCommit.monitoredRuleId)
            assertSame(classification, afterCommit.classification)
            assertFalse(afterCommit.needsDelayedObservation)
            coVerify(exactly = 1) {
                classifier.classify(any(), SemanticInferenceUrgency.BACKGROUND)
            }
        }

    @Test
    fun `monitor-only delayed observation starts only after an untrusted encoder result`() =
        runTest {
            val classification = semanticResult(isConfident = false)
            coEvery {
                classifier.classify(any(), SemanticInferenceUrgency.BACKGROUND)
            } returns classification
            val active =
                rule("active", Condition.CategoryEquals("alarm"), RuleAction.Cancel, priority = 100)
            val monitorSemantic =
                rule(
                    "monitor-semantic",
                    Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                    RuleAction.Cancel,
                    priority = 100,
                    mode = RuleExecutionMode.MONITOR,
                )
            val compiled = matcher.compile(listOf(active, monitorSemantic))

            val beforeCommit = resolver.resolve(snapshot, compiled)

            assertFalse(beforeCommit.needsDelayedObservation)
            assertTrue(beforeCommit.monitorNeedsPostCommitSemantic)
            coVerify(exactly = 0) { classifier.classify(any(), any()) }

            val afterCommit = resolver.resolveMonitorAfterCommit(snapshot, compiled)

            assertSame(classification, afterCommit.classification)
            assertTrue(afterCommit.needsDelayedObservation)
            coVerify(exactly = 1) {
                classifier.classify(any(), SemanticInferenceUrgency.BACKGROUND)
            }
        }

    @Test
    fun `post-commit category can reveal a monitor semantic requirement`() =
        runTest {
            val classification = semanticResult(isConfident = true)
            coEvery {
                classifier.classify(any(), SemanticInferenceUrgency.BACKGROUND)
            } returns classification
            val active =
                rule(
                    "active",
                    Condition.CategoryEquals("alarm"),
                    RuleAction.Cancel,
                    priority = 100,
                )
            val combinedMonitor =
                rule(
                    "combined-monitor",
                    Condition.AllOf(
                        listOf(
                            Condition.MlCategoryEquals("promotion"),
                            Condition.SemanticIntentEquals(
                                SemanticIntent.MARKETING,
                            ),
                        ),
                    ),
                    RuleAction.Cancel,
                    priority = 100,
                    mode = RuleExecutionMode.MONITOR,
                )
            val compiled = matcher.compile(listOf(active, combinedMonitor))

            val beforeCommit = resolver.resolve(snapshot, compiled)

            assertFalse(beforeCommit.monitorNeedsPostCommitSemantic)
            coVerify(exactly = 0) { classifier.classify(any(), any()) }

            val categoryEnriched = snapshot.copy(mlCategory = "promotion")
            val postCommitNeedsSemantic =
                beforeCommit.monitorNeedsPostCommitSemantic ||
                    matcher
                        .semanticResolutionRequirements(
                            categoryEnriched,
                            compiled,
                        ).monitorNeedsSemantic
            assertTrue(postCommitNeedsSemantic)

            val afterCommit =
                resolver.resolveMonitorAfterCommit(
                    snapshot = categoryEnriched,
                    compiled = compiled,
                    classifySemantic = postCommitNeedsSemantic,
                )

            assertEquals(RuleAction.Cancel, afterCommit.monitoredAction)
            assertEquals("combined-monitor", afterCommit.monitoredRuleId)
            assertSame(classification, afterCommit.classification)
        }

    @Test
    fun `post-commit monitor category preserves the active semantic trace`() =
        runTest {
            val classification = semanticResult(isConfident = true)
            coEvery {
                classifier.classify(any(), SemanticInferenceUrgency.REALTIME)
            } returns classification
            val activeSemantic =
                rule(
                    "active-semantic",
                    Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                    RuleAction.Cancel,
                    priority = 100,
                )
            val monitorCategory =
                rule(
                    "monitor-category",
                    Condition.MlCategoryEquals("promotion"),
                    RuleAction.Keep,
                    priority = 100,
                    mode = RuleExecutionMode.MONITOR,
                )
            val compiled = matcher.compile(listOf(activeSemantic, monitorCategory))

            val beforeCommit = resolver.resolve(snapshot, compiled)
            val activeTrace =
                beforeCommit.decisionTrace.filter { node ->
                    node.lane == DecisionTraceLane.ACTIVE
                }
            assertTrue(activeTrace.isNotEmpty())

            val afterCommit =
                resolver.resolveMonitorAfterCommit(
                    snapshot = snapshot.copy(mlCategory = "promotion"),
                    compiled = compiled,
                    existingClassification = beforeCommit.classification,
                    classifySemantic = false,
                    existingDecisionTrace = beforeCommit.decisionTrace,
                )

            assertEquals(activeTrace, afterCommit.decisionTrace.filter { it.lane == DecisionTraceLane.ACTIVE })
            assertTrue(afterCommit.decisionTrace.any { it.lane == DecisionTraceLane.MONITOR })
            assertEquals(RuleAction.Keep, afterCommit.monitoredAction)
            assertEquals("monitor-category", afterCommit.monitoredRuleId)
            coVerify(exactly = 1) {
                classifier.classify(any(), SemanticInferenceUrgency.REALTIME)
            }
            coVerify(exactly = 0) {
                classifier.classify(any(), SemanticInferenceUrgency.BACKGROUND)
            }
        }

    @Test
    fun `trusted semantic result can select the higher priority action`() =
        runTest {
            val classification = semanticResult(isConfident = true)
            coEvery {
                classifier.classify(any(), SemanticInferenceUrgency.REALTIME)
            } returns classification
            val semantic =
                rule(
                    "semantic",
                    Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                    RuleAction.Cancel,
                    priority = 100,
                )
            val fallback = rule("fallback", Condition.CategoryEquals("alarm"), RuleAction.Keep, priority = 10)

            val result = resolver.resolve(snapshot, matcher.compile(listOf(fallback, semantic)))

            assertEquals(RuleAction.Cancel, result.action)
            assertEquals("semantic", result.matchedRuleId)
            assertSame(classification, result.classification)
            assertFalse(result.needsDelayedObservation)
            coVerify(exactly = 1) {
                classifier.classify(any(), SemanticInferenceUrgency.REALTIME)
            }
        }

    @Test
    fun `low confidence active resolution fails open while monitor stays record only`() =
        runTest {
            val classification = semanticResult(isConfident = false)
            coEvery {
                classifier.classify(any(), SemanticInferenceUrgency.REALTIME)
            } returns classification
            val activeSemantic =
                rule(
                    "active-semantic",
                    Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                    RuleAction.Cancel,
                    priority = 100,
                )
            val activeFallback =
                rule("active-fallback", Condition.CategoryEquals("alarm"), RuleAction.Cancel, priority = 10)
            val monitor =
                rule(
                    "monitor",
                    Condition.CategoryEquals("alarm"),
                    RuleAction.Cancel,
                    priority = 10,
                    mode = RuleExecutionMode.MONITOR,
                )

            val result =
                resolver.resolve(
                    snapshot,
                    matcher.compile(listOf(activeFallback, monitor, activeSemantic)),
                )

            assertEquals(RuleAction.Keep, result.action)
            assertNull(result.matchedRuleId)
            assertEquals(RuleAction.Cancel, result.monitoredAction)
            assertEquals("monitor", result.monitoredRuleId)
            assertSame(classification, result.classification)
            assertTrue(result.needsDelayedObservation)
            assertTrue(result.decisionTrace.all { it.lane == DecisionTraceLane.MONITOR })
        }

    @Test
    fun `missing semantic encoder result fails open without an active trace`() =
        runTest {
            coEvery {
                classifier.classify(any(), SemanticInferenceUrgency.REALTIME)
            } returns null
            val semantic =
                rule(
                    "semantic",
                    Condition.IsAdvertisement(true),
                    RuleAction.Cancel,
                    priority = 100,
                )
            val fallback = rule("fallback", Condition.CategoryEquals("alarm"), RuleAction.Cancel, priority = 10)

            val result = resolver.resolve(snapshot, matcher.compile(listOf(fallback, semantic)))

            assertEquals(RuleAction.Keep, result.action)
            assertNull(result.matchedRuleId)
            assertNull(result.classification)
            assertTrue(result.decisionTrace.isEmpty())
            assertTrue(result.needsDelayedObservation)
        }

    @Test
    fun `semantic encoder timeout fails open`() =
        runTest {
            val timedResolver = RealtimeSemanticRuleResolver(matcher, classifier, timeoutMillis = 350L)
            coEvery {
                classifier.classify(any(), SemanticInferenceUrgency.REALTIME)
            } coAnswers {
                delay(351L)
                semanticResult(isConfident = true)
            }
            val semantic =
                rule(
                    "semantic",
                    Condition.SemanticIntentEquals(SemanticIntent.MARKETING),
                    RuleAction.Cancel,
                    priority = 100,
                )
            val fallback = rule("fallback", Condition.CategoryEquals("alarm"), RuleAction.Cancel, priority = 10)

            val result = timedResolver.resolve(snapshot, matcher.compile(listOf(fallback, semantic)))

            assertEquals(RuleAction.Keep, result.action)
            assertNull(result.matchedRuleId)
            assertNull(result.classification)
            assertTrue(result.needsDelayedObservation)
        }

    @Test
    fun `absent or timed out LLM yields no delayed observation`() =
        runTest {
            val llmManager = mockk<OnDeviceLlmManager>()
            every {
                llmManager.backgroundAnalysisEligibility
            } returns LlmBackgroundAnalysisEligibility.VERIFIED_COMPATIBLE
            coEvery { llmManager.analyze(any(), any()) } returns null
            assertNull(analyzeDelayedSemanticObservation(snapshot, llmManager, timeoutMillis = 10L))

            coEvery { llmManager.analyze(any(), any()) } coAnswers {
                delay(11L)
                LlmAnalysisResult.of(SemanticIntent.MARKETING, 0.9f, "late")
            }
            assertNull(analyzeDelayedSemanticObservation(snapshot, llmManager, timeoutMillis = 10L))
        }

    @Test
    fun `delayed LLM result is returned only to the observation path`() =
        runTest {
            val llmManager = mockk<OnDeviceLlmManager>()
            every {
                llmManager.backgroundAnalysisEligibility
            } returns LlmBackgroundAnalysisEligibility.VERIFIED_COMPATIBLE
            val observation = LlmAnalysisResult.of(SemanticIntent.SECURITY, 0.8f, "local observation")
            coEvery { llmManager.analyze(any(), any()) } returns observation

            val result = analyzeDelayedSemanticObservation(snapshot, llmManager, timeoutMillis = 10L)

            assertSame(observation, result)
        }

    @Test
    fun `unverified imported LLM is never used by the background observation path`() =
        runTest {
            val llmManager = mockk<OnDeviceLlmManager>()
            every {
                llmManager.backgroundAnalysisEligibility
            } returns LlmBackgroundAnalysisEligibility.UNVERIFIED

            assertNull(
                analyzeDelayedSemanticObservation(
                    snapshot,
                    llmManager,
                    timeoutMillis = 10L,
                ),
            )
            coVerify(exactly = 0) { llmManager.analyze(any(), any()) }
        }

    private fun rule(
        id: String,
        condition: Condition,
        action: RuleAction,
        priority: Int,
        mode: RuleExecutionMode = RuleExecutionMode.ACTIVE,
    ) = Rule(
        id = id,
        name = id,
        priority = priority,
        condition = condition,
        action = action,
        executionMode = mode,
    )

    private fun semanticResult(isConfident: Boolean) =
        SemanticClassificationResult(
            intent = SemanticIntent.MARKETING,
            logits = SemanticIntent.entries.associateWith { if (it == SemanticIntent.MARKETING) 1f else 0f },
            confidence = if (isConfident) 0.9f else 0.5f,
            isConfident = isConfident,
        )
}
