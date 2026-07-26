package com.alarmcontrol.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Notification-listener access is the permission this whole app hinges on: without it the system
 * never binds [NotificationFilterService], so nothing is filtered and the insights stay empty. These
 * helpers let the UI detect that and deep-link the user to the system grant screen. All local — no
 * network (§3).
 */
object NotificationAccess {
    /** Whether the user has granted notification access to this app. */
    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** The system settings screen where the user grants (or revokes) notification access. */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Package-level notification controls for an app shown in the local activity feed. */
    fun appNotificationSettingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Generic application details fallback supported even by heavily customized OEM settings. */
    fun appDetailsSettingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Exact channel controls when [channelId] exists; otherwise package-level controls. */
    fun notificationSettingsIntent(
        packageName: String,
        channelId: String?,
    ): Intent =
        if (channelId.isNullOrBlank()) {
            appNotificationSettingsIntent(packageName)
        } else {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Opens exact channel settings and falls back to the app-level screen on OEM incompatibility. */
    fun openNotificationSettings(
        context: Context,
        packageName: String,
        channelId: String?,
    ): Boolean {
        if (!channelId.isNullOrBlank()) {
            try {
                context.startActivity(notificationSettingsIntent(packageName, channelId))
                return true
            } catch (_: ActivityNotFoundException) {
                // Some OEMs omit the channel settings activity; package settings remain safe.
            } catch (_: SecurityException) {
                // Fall back when an OEM protects the channel activity unexpectedly.
            }
        }
        val appNotificationOpened =
            try {
                context.startActivity(appNotificationSettingsIntent(packageName))
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        return appNotificationOpened || openWithFallback(context, appDetailsSettingsIntent(packageName), null)
    }

    /** System battery-policy screen; no exemption permission is requested by AlarmControl. */
    fun batteryOptimizationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens [primary], then application details if that OEM screen is absent or protected. */
    fun openWithAppDetailsFallback(
        context: Context,
        primary: Intent,
    ): Boolean =
        openWithFallback(
            context = context,
            primary = primary,
            fallback = appDetailsSettingsIntent(context.packageName),
        )

    private fun openWithFallback(
        context: Context,
        primary: Intent,
        fallback: Intent?,
    ): Boolean {
        val opened =
            try {
                context.startActivity(primary)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        if (opened || fallback == null) return opened
        return try {
            context.startActivity(fallback)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
