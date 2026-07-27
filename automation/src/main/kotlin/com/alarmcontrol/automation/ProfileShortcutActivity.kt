package com.alarmcontrol.automation

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.alarmcontrol.core.automation.AutomationSource
import com.alarmcontrol.core.coroutines.ApplicationScope
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Invisible trampoline launched by the dynamic launcher shortcuts (CLAUDE.md §7). It maps the
 * shortcut action to a master-switch toggle and applies it via [ProfileController], then finishes
 * with no UI. The toggle runs on the application scope so it completes even though this activity
 * finishes immediately — no work happens on the main thread.
 *
 * This is a first-party user action, so it uses the controller's direct set/toggle path (the same
 * ungated path as the Quick Settings tile), not the external-automation opt-in path.
 *
 * Deps are pulled via a Hilt [EntryPoint] (a plain `Activity`, no `@AndroidEntryPoint`), matching
 * [ProfileToggleReceiver].
 */
class ProfileShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val enabled = ProfileShortcuts.enabledForAction(intent?.action)
        val profileId =
            intent
                ?.takeIf { it.action == ProfileShortcuts.ACTION_TOGGLE_PROFILE }
                ?.getStringExtra(ProfileShortcuts.EXTRA_PROFILE_ID)
        if (enabled != null || !profileId.isNullOrBlank()) {
            val entryPoint =
                try {
                    EntryPointAccessors.fromApplication(
                        applicationContext,
                        ShortcutEntryPoint::class.java,
                    )
                } catch (_: RuntimeException) {
                    Log.w(TAG, "Launcher shortcut dependencies unavailable")
                    finish()
                    return
                }
            try {
                entryPoint.applicationScope().launch {
                    runCatchingPreservingCancellation {
                        if (enabled != null) {
                            entryPoint
                                .profileController()
                                .setEnabled(
                                    profileId = null,
                                    enabled = enabled,
                                    source = AutomationSource.SHORTCUT,
                                )
                        } else {
                            entryPoint.profileController().toggle(profileId, source = AutomationSource.SHORTCUT)
                        }
                    }.onFailure { Log.w(TAG, "Launcher shortcut action failed") }
                }
            } catch (_: RuntimeException) {
                Log.w(TAG, "Couldn't start launcher shortcut action")
            }
        }
        finish()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface ShortcutEntryPoint {
        fun profileController(): ProfileController

        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    private companion object {
        const val TAG = "ProfileShortcut"
    }
}
