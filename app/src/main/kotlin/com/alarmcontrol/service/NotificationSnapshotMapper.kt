package com.alarmcontrol.service

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import androidx.annotation.ChecksSdkIntAtLeast
import com.alarmcontrol.core.filtering.NotificationContentVisibility
import com.alarmcontrol.core.filtering.NotificationImportance
import com.alarmcontrol.core.filtering.NotificationSnapshot
import java.time.Instant
import java.time.ZoneId

/**
 * Translates a framework [StatusBarNotification] into the pure [NotificationSnapshot] the engine
 * understands (CLAUDE.md §6). This is the single boundary where Android notification types are read;
 * everything downstream is framework-free.
 *
 * `channelId` is always available here (minSdk 26 = notification channels). `mlCategory` starts
 * `null`; the service pipeline fills it from the on-device classifier (`:ml`) before matching, and
 * leaves it `null` when there's no confident result (§5). `postedMinuteOfDay` resolves the post time
 * to local wall-clock minutes in [zoneId] so [com.alarmcontrol.core.filtering.Condition.TimeWindow]
 * can evaluate without a clock.
 */
fun StatusBarNotification.toSnapshot(
    zoneId: ZoneId,
    ranking: Ranking? = null,
): NotificationSnapshot {
    val extras = notification.extras
    return NotificationSnapshot(
        packageName = packageName,
        title =
            (
                extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            )?.toString(),
        text =
            (
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString(),
        category = notification.category,
        channelId = notification.channelId,
        channelName = ranking?.channel?.name?.toString(),
        postedAtMillis = postTime,
        isOngoing = isOngoing,
        importance = ranking?.importance?.toNotificationImportance(),
        isConversation = ranking.conversationSignal(),
        isForegroundService = notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
        mlCategory = null,
        postedMinuteOfDay = minuteOfDay(postTime, zoneId),
        postedEpochDay = epochDay(postTime, zoneId),
        contentVisibility = notification.visibility.toContentVisibility(),
    )
}

/** Conversation ranking was added in API 31; older releases expose an unknown signal. */
internal fun Ranking?.conversationSignal(): Boolean? =
    if (this != null && supportsConversationRanking()) isConversation else null

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
private fun supportsConversationRanking(): Boolean = supportsConversationRanking(Build.VERSION.SDK_INT)

internal fun supportsConversationRanking(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S

private fun Int.toNotificationImportance(): NotificationImportance? =
    when {
        this == NotificationManager.IMPORTANCE_UNSPECIFIED -> null
        this <= NotificationManager.IMPORTANCE_MIN -> NotificationImportance.MIN
        this == NotificationManager.IMPORTANCE_LOW -> NotificationImportance.LOW
        this == NotificationManager.IMPORTANCE_DEFAULT -> NotificationImportance.DEFAULT
        this == NotificationManager.IMPORTANCE_HIGH -> NotificationImportance.HIGH
        this >= NotificationManager.IMPORTANCE_MAX -> NotificationImportance.MAX
        else -> null
    }

/** Local wall-clock minute of day (0..1439) for [epochMillis] in [zoneId]. Pure — unit-tested. */
internal fun minuteOfDay(
    epochMillis: Long,
    zoneId: ZoneId,
): Int {
    val time = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalTime()
    return time.hour * MINUTES_PER_HOUR + time.minute
}

internal fun epochDay(
    epochMillis: Long,
    zoneId: ZoneId,
): Long =
    Instant
        .ofEpochMilli(epochMillis)
        .atZone(zoneId)
        .toLocalDate()
        .toEpochDay()

private fun Int.toContentVisibility(): NotificationContentVisibility =
    when (this) {
        Notification.VISIBILITY_PUBLIC -> NotificationContentVisibility.PUBLIC
        Notification.VISIBILITY_PRIVATE -> NotificationContentVisibility.PRIVATE
        Notification.VISIBILITY_SECRET -> NotificationContentVisibility.SECRET
        else -> NotificationContentVisibility.UNKNOWN
    }

private const val MINUTES_PER_HOUR = 60
