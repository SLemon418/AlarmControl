package com.alarmcontrol.service

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.ml.ClassificationResult
import com.alarmcontrol.ml.NotificationClassifier
import com.alarmcontrol.notifications.Matcher
import io.mockk.coEvery
import io.mockk.coVerify
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
class RealtimeCategoryRuleResolverTest {
    private val matcher = Matcher()
    private val classifier = mockk<NotificationClassifier>()
    private val resolver = RealtimeCategoryRuleResolver(classifier)
    private val snapshot =
        NotificationSnapshot(
            packageName = "com.example.local",
            title = "Receipt",
            text = "Your payment was accepted",
            category = "alarm",
            channelId = "general",
            postedAtMillis = 0L,
            isOngoing = false,
        )

    @Test
    fun `monitor-only category inference starts after active action is ready to commit`() =
        runTest {
            val classification = ClassificationResult("promotion", 0.9f)
            val classificationStarted = CompletableDeferred<Unit>()
            val releaseClassification = CompletableDeferred<Unit>()
            coEvery { classifier.classify(any()) } coAnswers {
                classificationStarted.complete(Unit)
                releaseClassification.await()
                classification
            }
            val active =
                rule(
                    id = "active",
                    condition = Condition.CategoryEquals("alarm"),
                    action = RuleAction.Cancel,
                    priority = 100,
                )
            val monitor =
                rule(
                    id = "monitor",
                    condition = Condition.MlCategoryEquals("promotion"),
                    action = RuleAction.Cancel,
                    priority = 100,
                    mode = RuleExecutionMode.MONITOR,
                )
            val compiled = matcher.compile(listOf(active, monitor))

            val beforeCommit = resolver.resolveBeforeActiveCommit(snapshot, compiled)

            assertNull(beforeCommit.classification)
            assertTrue(beforeCommit.monitorNeedsPostCommitClassification)
            coVerify(exactly = 0) { classifier.classify(any()) }

            val order = mutableListOf("active-commit")
            val enrichment =
                async {
                    resolver.resolveMonitorAfterCommit(snapshot).also {
                        order += "monitor-enrichment"
                    }
                }
            runCurrent()
            classificationStarted.await()

            assertEquals(listOf("active-commit"), order)
            assertFalse(enrichment.isCompleted)

            releaseClassification.complete(Unit)
            assertSame(classification, enrichment.await())
            assertEquals(listOf("active-commit", "monitor-enrichment"), order)
        }

    @Test
    fun `active category inference is bounded and fails open`() =
        runTest {
            val timedResolver =
                RealtimeCategoryRuleResolver(
                    classifier = classifier,
                    timeoutMillis = 500L,
                )
            coEvery { classifier.classify(any()) } coAnswers {
                delay(501L)
                ClassificationResult("promotion", 0.9f)
            }
            val active =
                rule(
                    id = "active",
                    condition = Condition.MlCategoryEquals("promotion"),
                    action = RuleAction.Cancel,
                    priority = 100,
                )

            val result =
                timedResolver.resolveBeforeActiveCommit(
                    snapshot,
                    matcher.compile(listOf(active)),
                )

            assertNull(result.classification)
            assertFalse(result.monitorNeedsPostCommitClassification)
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
        enabled = true,
        executionMode = mode,
    )
}
