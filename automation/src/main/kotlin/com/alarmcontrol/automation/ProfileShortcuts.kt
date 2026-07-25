package com.alarmcontrol.automation

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.alarmcontrol.core.profile.FilteringProfile

/**
 * Dynamic launcher shortcuts (long-press the app icon) for the first-party master-switch actions
 * (CLAUDE.md §7), complementing the Quick Settings tile and the exported automation intents. Local
 * only — tapping one toggles filtering via [ProfileController] (not gated by the external-automation
 * opt-in, since the user invoked it directly).
 */
object ProfileShortcuts {
    const val ACTION_ENABLE_FILTERING = "com.alarmcontrol.automation.shortcut.ENABLE_FILTERING"
    const val ACTION_PAUSE_FILTERING = "com.alarmcontrol.automation.shortcut.PAUSE_FILTERING"
    const val ACTION_TOGGLE_PROFILE = "com.alarmcontrol.automation.shortcut.TOGGLE_PROFILE"
    const val EXTRA_PROFILE_ID = "com.alarmcontrol.automation.shortcut.extra.PROFILE_ID"

    /** Legacy actions retained so shortcuts published by an older app version still work. */
    @Deprecated("Use ACTION_ENABLE_FILTERING")
    const val ACTION_MUTE_ALL = "com.alarmcontrol.automation.shortcut.MUTE_ALL"

    @Deprecated("Use ACTION_PAUSE_FILTERING")
    const val ACTION_RESUME_ALL = "com.alarmcontrol.automation.shortcut.RESUME_ALL"

    private const val ID_ENABLE_FILTERING = "enable_filtering"
    private const val ID_PAUSE_FILTERING = "pause_filtering"

    /** Maps a shortcut action to the master filtering state it applies, or `null` if unknown. */
    @Suppress("DEPRECATION")
    fun enabledForAction(action: String?): Boolean? =
        when (action) {
            ACTION_ENABLE_FILTERING, ACTION_MUTE_ALL -> true
            ACTION_PAUSE_FILTERING, ACTION_RESUME_ALL -> false
            else -> null
        }

    /**
     * Builds and publishes the dynamic shortcuts. `ShortcutManagerCompat` no-ops below API 25, so this
     * is safe on any supported device. Does light disk I/O — call off the main thread.
     */
    fun publish(
        context: Context,
        profiles: List<FilteringProfile> = emptyList(),
    ) {
        val reportedMax = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        val maxCount = if (reportedMax > 0) reportedMax else DEFAULT_MAX_SHORTCUT_COUNT
        val shortcuts =
            buildList {
                add(
                    shortcut(
                        context,
                        id = ID_ENABLE_FILTERING,
                        action = ACTION_ENABLE_FILTERING,
                        shortLabel = context.getString(R.string.shortcut_enable_filtering_short),
                        longLabel = context.getString(R.string.shortcut_enable_filtering_long),
                        iconRes = R.drawable.ic_qs_filter,
                    ),
                )
                add(
                    shortcut(
                        context,
                        id = ID_PAUSE_FILTERING,
                        action = ACTION_PAUSE_FILTERING,
                        shortLabel = context.getString(R.string.shortcut_pause_filtering_short),
                        longLabel = context.getString(R.string.shortcut_pause_filtering_long),
                        iconRes = R.drawable.ic_shortcut_pause,
                    ),
                )
                profiles.take((maxCount - MASTER_SHORTCUT_COUNT).coerceAtLeast(0)).forEach { profile ->
                    add(profileShortcut(context, profile))
                }
            }.take(maxCount)
        ShortcutManagerCompat.setDynamicShortcuts(
            context,
            shortcuts,
        )
    }

    private fun profileShortcut(
        context: Context,
        profile: FilteringProfile,
    ): ShortcutInfoCompat =
        ShortcutInfoCompat
            .Builder(context, "profile_${profile.id}")
            .setShortLabel(profile.name.take(MAX_LABEL_CHARS))
            .setLongLabel(context.getString(R.string.shortcut_toggle_profile_long, profile.name))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_qs_filter))
            .setIntent(
                Intent(context, ProfileShortcutActivity::class.java)
                    .setAction(ACTION_TOGGLE_PROFILE)
                    .putExtra(EXTRA_PROFILE_ID, profile.id),
            ).build()

    private fun shortcut(
        context: Context,
        id: String,
        action: String,
        shortLabel: String,
        longLabel: String,
        iconRes: Int,
    ): ShortcutInfoCompat =
        ShortcutInfoCompat
            .Builder(context, id)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(IconCompat.createWithResource(context, iconRes))
            // Explicit, action-tagged intent targeting our trampoline.
            .setIntent(Intent(context, ProfileShortcutActivity::class.java).setAction(action))
            .build()

    private const val MASTER_SHORTCUT_COUNT = 2
    private const val DEFAULT_MAX_SHORTCUT_COUNT = 4
    private const val MAX_LABEL_CHARS = 40
}
