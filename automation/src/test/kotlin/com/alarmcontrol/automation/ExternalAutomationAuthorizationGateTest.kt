package com.alarmcontrol.automation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalAutomationAuthorizationGateTest {
    @Test
    fun `current opt-in and token reject hostile requests before receiver admission`() =
        runTest {
            val enabled = MutableStateFlow(true)
            val token = MutableStateFlow("first-token")
            val gate = ExternalAutomationAuthorizationGate(enabled, token, backgroundScope)
            runCurrent()

            assertEquals(ExternalAuthorizationDecision.AUTHORIZED, gate.authorize("first-token"))
            assertEquals(ExternalAuthorizationDecision.UNAUTHORIZED, gate.authorize("wrong-token"))

            token.value = "rotated-token"
            runCurrent()
            assertEquals(ExternalAuthorizationDecision.UNAUTHORIZED, gate.authorize("first-token"))
            assertEquals(ExternalAuthorizationDecision.AUTHORIZED, gate.authorize("rotated-token"))

            enabled.value = false
            runCurrent()
            assertEquals(ExternalAuthorizationDecision.DISABLED, gate.authorize("rotated-token"))
        }

    @Test
    fun `missing initial settings snapshot is isolated to the cold-start lane`() =
        runTest {
            val gate =
                ExternalAutomationAuthorizationGate(
                    enabled = emptyFlow(),
                    token = flowOf("test-token"),
                    scope = backgroundScope,
                )

            assertEquals(ExternalAuthorizationDecision.CACHE_UNAVAILABLE, gate.authorize("test-token"))
        }

    @Test
    fun `cache recovers when safe settings fallbacks complete after transient IO`() =
        runTest {
            var enabledSubscriptions = 0
            var tokenSubscriptions = 0
            val enabled =
                flow {
                    enabledSubscriptions += 1
                    emit(enabledSubscriptions > 1)
                    if (enabledSubscriptions > 1) awaitCancellation()
                }
            val token =
                flow {
                    tokenSubscriptions += 1
                    emit(if (tokenSubscriptions > 1) "recovered-token" else "")
                    if (tokenSubscriptions > 1) awaitCancellation()
                }
            val gate = ExternalAutomationAuthorizationGate(enabled, token, backgroundScope)
            runCurrent()

            assertEquals(ExternalAuthorizationDecision.DISABLED, gate.authorize("recovered-token"))

            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(2, enabledSubscriptions)
            assertEquals(2, tokenSubscriptions)
            assertEquals(ExternalAuthorizationDecision.AUTHORIZED, gate.authorize("recovered-token"))
        }

    @Test
    fun `cache collection retries a transient settings IO failure`() =
        runTest {
            var tokenSubscriptions = 0
            val token =
                flow {
                    tokenSubscriptions += 1
                    if (tokenSubscriptions == 1) throw IOException("transient read failure")
                    emit("recovered-token")
                    awaitCancellation()
                }
            val gate =
                ExternalAutomationAuthorizationGate(
                    enabled = MutableStateFlow(true),
                    token = token,
                    scope = backgroundScope,
                )
            runCurrent()

            assertEquals(ExternalAuthorizationDecision.CACHE_UNAVAILABLE, gate.authorize("recovered-token"))

            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(2, tokenSubscriptions)
            assertEquals(ExternalAuthorizationDecision.AUTHORIZED, gate.authorize("recovered-token"))
        }

    @Test
    fun `cache collection preserves settings flow cancellation`() =
        runTest {
            var tokenSubscriptions = 0
            val token =
                flow<String> {
                    tokenSubscriptions += 1
                    throw CancellationException("stop collection")
                }
            ExternalAutomationAuthorizationGate(
                enabled = MutableStateFlow(true),
                token = token,
                scope = backgroundScope,
            )
            runCurrent()

            advanceTimeBy(5_000L)
            runCurrent()

            assertEquals(1, tokenSubscriptions)
        }

    @Test
    fun `stale cache rejection is sent to the authoritative controller lane`() {
        assertEquals(
            ExternalAuthorizationLane.AUTHORITATIVE_CHECK,
            ExternalAuthorizationDecision.DISABLED.receiverLane(),
        )
        assertEquals(
            ExternalAuthorizationLane.AUTHORITATIVE_CHECK,
            ExternalAuthorizationDecision.UNAUTHORIZED.receiverLane(),
        )
        assertEquals(
            ExternalAuthorizationLane.AUTHORITATIVE_CHECK,
            ExternalAuthorizationDecision.CACHE_UNAVAILABLE.receiverLane(),
        )
        assertEquals(
            ExternalAuthorizationLane.AUTHENTICATED,
            ExternalAuthorizationDecision.AUTHORIZED.receiverLane(),
        )
    }
}
