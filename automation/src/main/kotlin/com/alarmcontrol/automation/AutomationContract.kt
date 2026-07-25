package com.alarmcontrol.automation

/**
 * The public, stable contract for controlling AlarmControl from external automation (CLAUDE.md §7).
 * Treat these strings like a public API — Samsung Modes & Routines (via Good Lock RoutinePlus) and
 * similar tools depend on them, so do not rename or repurpose them casually.
 *
 * Send a broadcast with one of the actions below and the required [EXTRA_AUTH_TOKEN] shown in the
 * app's Settings. Optionally include [EXTRA_PROFILE_ID] to target a
 * named profile by id or name. Legacy rule ids/names remain supported when no profile matches.
 * Omit it to change the independent filtering master switch. Pausing that switch preserves every
 * rule's individual enabled state.
 *
 * adb smoke test:
 * ```
 * adb shell am broadcast \
 *   -a com.alarmcontrol.automation.action.DISABLE_PROFILE \
 *   --es com.alarmcontrol.automation.extra.AUTH_TOKEN "<token-from-settings>" \
 *   -n com.alarmcontrol/com.alarmcontrol.automation.ProfileToggleReceiver
 * ```
 */
object AutomationContract {
    /** Enables the targeted rule, or the filtering master switch when no profile id is supplied. */
    const val ACTION_ENABLE_PROFILE = "com.alarmcontrol.automation.action.ENABLE_PROFILE"

    /** Disables the targeted rule, or the filtering master switch when no profile id is supplied. */
    const val ACTION_DISABLE_PROFILE = "com.alarmcontrol.automation.action.DISABLE_PROFILE"

    /**
     * Optional `String` extra naming a profile by id or case-insensitive name. When no profile
     * matches, legacy rule id/name resolution is used. When absent or blank, it targets the master.
     */
    const val EXTRA_PROFILE_ID = "com.alarmcontrol.automation.extra.PROFILE_ID"

    /** Required per-install `String` secret for exported broadcasts. Rotate it from Settings. */
    const val EXTRA_AUTH_TOKEN = "com.alarmcontrol.automation.extra.AUTH_TOKEN"
}
