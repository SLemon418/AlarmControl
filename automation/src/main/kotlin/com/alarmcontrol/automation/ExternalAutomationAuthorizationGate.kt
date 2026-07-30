package com.alarmcontrol.automation

import com.alarmcontrol.core.coroutines.ApplicationScope
import com.alarmcontrol.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a process-local authorization snapshot so likely-hostile broadcasts use a separate,
 * tightly bounded receiver lane instead of consuming capacity reserved for authenticated
 * automation.
 *
 * The controller still rechecks DataStore before applying an operation. This cache is therefore an
 * ingress capacity optimization, never the authority for accepting or rejecting a request.
 */
@Singleton
internal class ExternalAutomationAuthorizationGate internal constructor(
    enabled: Flow<Boolean>,
    token: Flow<String>,
    scope: CoroutineScope,
) {
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        enabled = settingsRepository.externalAutomationEnabled,
        token = settingsRepository.externalAutomationToken,
        scope = scope,
    )

    private val snapshot = AtomicReference<AuthorizationSnapshot?>()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(
                enabled.recoveringSettingsFlow(),
                token.recoveringSettingsFlow(),
                ::AuthorizationSnapshot,
            ).collect(snapshot::set)
        }
    }

    internal fun authorize(suppliedToken: String): ExternalAuthorizationDecision {
        val current = snapshot.get() ?: return ExternalAuthorizationDecision.CACHE_UNAVAILABLE
        if (!current.enabled) return ExternalAuthorizationDecision.DISABLED
        return if (suppliedToken.isAuthorizedAutomationToken(current.token)) {
            ExternalAuthorizationDecision.AUTHORIZED
        } else {
            ExternalAuthorizationDecision.UNAUTHORIZED
        }
    }

    private data class AuthorizationSnapshot(
        val enabled: Boolean,
        val token: String,
    )
}

internal enum class ExternalAuthorizationDecision {
    AUTHORIZED,
    DISABLED,
    UNAUTHORIZED,
    CACHE_UNAVAILABLE,
}

internal enum class ExternalAuthorizationLane {
    AUTHENTICATED,
    AUTHORITATIVE_CHECK,
}

internal fun ExternalAuthorizationDecision.receiverLane(): ExternalAuthorizationLane =
    when (this) {
        ExternalAuthorizationDecision.AUTHORIZED -> ExternalAuthorizationLane.AUTHENTICATED
        ExternalAuthorizationDecision.DISABLED,
        ExternalAuthorizationDecision.UNAUTHORIZED,
        ExternalAuthorizationDecision.CACHE_UNAVAILABLE,
        -> ExternalAuthorizationLane.AUTHORITATIVE_CHECK
    }

internal fun String?.isAuthorizedAutomationToken(expected: String): Boolean {
    val supplied = this ?: return false
    if (supplied.isEmpty() || supplied.length > MAX_AUTOMATION_TOKEN_CHARS) return false
    if (expected.isEmpty()) return false
    val suppliedBytes = supplied.toByteArray(Charsets.UTF_8)
    val expectedBytes = expected.toByteArray(Charsets.UTF_8)
    return try {
        MessageDigest.isEqual(suppliedBytes, expectedBytes)
    } finally {
        suppliedBytes.fill(0)
        expectedBytes.fill(0)
    }
}

private fun <T> Flow<T>.recoveringSettingsFlow(): Flow<T> =
    flow {
        do {
            emitAll(
                this@recoveringSettingsFlow.retryWhen { cause, _ ->
                    when (cause) {
                        is CancellationException -> false
                        is Exception -> {
                            delay(SETTINGS_RESUBSCRIBE_DELAY_MILLIS)
                            true
                        }
                        else -> false
                    }
                },
            )
            delay(SETTINGS_RESUBSCRIBE_DELAY_MILLIS)
        } while (currentCoroutineContext().isActive)
    }

private const val SETTINGS_RESUBSCRIBE_DELAY_MILLIS = 1_000L
private const val MAX_AUTOMATION_TOKEN_CHARS = 128
