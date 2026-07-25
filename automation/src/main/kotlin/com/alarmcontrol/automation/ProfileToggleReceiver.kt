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

/**
 * Exported automation entry point (CLAUDE.md §7). Lets Samsung Modes & Routines (via Good Lock
 * RoutinePlus) and similar tools enable/disable filtering by broadcasting an [AutomationContract]
 * action. A thin shell: it validates the intent and delegates to [ProfileController], which updates
 * rules through `:core`'s repository (`:data` persists), but only when the user has opted in and the
 * broadcast carries the current per-install token (§7). The controller rate-limits requests and
 * stores only a content-free outcome. Unknown actions are ignored and non-cancellation failures are
 * swallowed: a malformed request must never crash the receiver, while cancellation still propagates.
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
        val enabled =
            when (intent.action) {
                AutomationContract.ACTION_ENABLE_PROFILE -> true
                AutomationContract.ACTION_DISABLE_PROFILE -> false
                else -> return // unknown / null action: ignore
            }
        val profileId = intent.getStringExtra(AutomationContract.EXTRA_PROFILE_ID)
        val token = intent.getStringExtra(AutomationContract.EXTRA_AUTH_TOKEN)

        val entryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                AutomationEntryPoint::class.java,
            )
        val controller = entryPoint.profileController()
        val dispatcher = entryPoint.ioDispatcher()
        val applicationScope = entryPoint.applicationScope()

        // The DB write is async, so keep the broadcast alive until it finishes (goAsync) and never
        // let a failure escape onReceive.
        val pending = goAsync()
        applicationScope.launch(dispatcher) {
            try {
                withTimeout(RECEIVER_TIMEOUT_MILLIS) {
                    controller.setEnabledFromExternalAutomation(profileId, enabled, token)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Log.w(TAG, "Failed to apply automation toggle")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ProfileToggleReceiver"
        const val RECEIVER_TIMEOUT_MILLIS = 9_000L
    }
}
