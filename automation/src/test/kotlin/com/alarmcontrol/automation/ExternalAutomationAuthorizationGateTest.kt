package com.alarmcontrol.automation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

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
