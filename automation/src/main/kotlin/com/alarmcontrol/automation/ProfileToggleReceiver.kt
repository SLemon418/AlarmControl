package com.alarmcontrol.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.alarmcontrol.core.coroutines.AppDispatcher
import com.alarmcontrol.core.coroutines.ApplicationScope
import com.alarmcontrol.core.coroutines.Dispatcher
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exported automation entry point (CLAUDE.md §7). Lets Tasker, MacroDroid, and compatible tools
 * enable/disable filtering by broadcasting an [AutomationContract] action. Samsung Modes & Routines
 * uses the separate first-party App Shortcut path. This receiver is a thin shell: it validates the
 * intent and delegates to [ProfileController], which updates rules through `:core`'s repository
 * (`:data` persists), but only when the user has opted in and the broadcast carries the current
 * per-install token (§7). The controller rate-limits requests and stores only a content-free outcome.
 * Unknown actions are ignored and non-cancellation failures are swallowed: a malformed request must
 * never crash the receiver, while cancellation still propagates.
 *
 * Dependencies are pulled via a Hilt [EntryPoint] rather than `@AndroidEntryPoint` field injection —
 * the robust pattern for a `BroadcastReceiver` (no `super.onReceive`, no lifecycle assumptions).
 */
@Suppress("TooGenericExceptionCaught")
class ProfileToggleReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface AutomationEntryPoint {
        fun profileController(): ProfileController

        @Dispatcher(AppDispatcher.IO)
        fun ioDispatcher(): CoroutineDispatcher

        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val request = intent.toExternalAutomationRequestOrNull(context.packageName) ?: return
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT_REQUESTS) {
            inFlight.decrementAndGet()
            return
        }

        // The DB write is async, so keep the broadcast alive until it finishes (goAsync) and never
        // let a failure escape onReceive.
        val pending = goAsync()
        val dependencies =
            try {
                val entryPoint =
                    EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        AutomationEntryPoint::class.java,
                    )
                ReceiverDependencies(
                    controller = entryPoint.profileController(),
                    dispatcher = entryPoint.ioDispatcher(),
                    applicationScope = entryPoint.applicationScope(),
                )
            } catch (_: Exception) {
                inFlight.decrementAndGet()
                pending.finish()
                Log.w(TAG, "Automation dependencies unavailable")
                return
            }
        try {
            dependencies.applicationScope.launch(dependencies.dispatcher) {
                try {
                    withTimeout(RECEIVER_TIMEOUT_MILLIS) {
                        dependencies.controller.setEnabledFromExternalAutomation(
                            request.profileId,
                            request.enabled,
                            request.token,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    Log.w(TAG, "Failed to apply automation toggle")
                } finally {
                    inFlight.decrementAndGet()
                    pending.finish()
                }
            }
        } catch (_: Exception) {
            inFlight.decrementAndGet()
            pending.finish()
            Log.w(TAG, "Couldn't start automation request")
        }
    }

    private data class ReceiverDependencies(
        val controller: ProfileController,
        val dispatcher: CoroutineDispatcher,
        val applicationScope: CoroutineScope,
    )

    private companion object {
        const val TAG = "ProfileToggleReceiver"
        const val RECEIVER_TIMEOUT_MILLIS = 9_000L
        const val MAX_IN_FLIGHT_REQUESTS = 4
        val inFlight = AtomicInteger()
    }
}

internal data class ExternalAutomationRequest(
    val enabled: Boolean,
    val profileId: String?,
    val token: String,
)

/** Parses the exported intent without allowing hostile extras unparcelling to crash the process. */
internal fun Intent.toExternalAutomationRequestOrNull(expectedPackageName: String): ExternalAutomationRequest? {
    val explicitlyTargeted =
        component?.packageName == expectedPackageName ||
            `package` == expectedPackageName
    if (!explicitlyTargeted) return null
    val enabled =
        when (action) {
            AutomationContract.ACTION_ENABLE_PROFILE -> true
            AutomationContract.ACTION_DISABLE_PROFILE -> false
            else -> return null
        }
    return try {
        val token = getStringExtra(AutomationContract.EXTRA_AUTH_TOKEN) ?: return null
        if (!token.isPlausibleAutomationToken()) return null
        ExternalAutomationRequest(
            enabled = enabled,
            profileId = getStringExtra(AutomationContract.EXTRA_PROFILE_ID),
            token = token,
        )
    } catch (_: RuntimeException) {
        null
    }
}
