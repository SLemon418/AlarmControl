package com.alarmcontrol.service

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import androidx.annotation.ChecksSdkIntAtLeast
import com.alarmcontrol.core.filtering.MAX_NOTIFICATION_METADATA_CHARS
import com.alarmcontrol.core.filtering.MAX_NOTIFICATION_TEXT_CHARS
import com.alarmcontrol.core.filtering.MAX_NOTIFICATION_TITLE_CHARS
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
            extras.boundedNotificationExtra(
                MAX_NOTIFICATION_TITLE_CHARS,
                Notification.EXTRA_TITLE_BIG,
                Notification.EXTRA_TITLE,
            ),
        text =
            extras.boundedNotificationExtra(
                MAX_NOTIFICATION_TEXT_CHARS,
                Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_TEXT,
            ),
        category = notification.category.boundedNotificationText(MAX_NOTIFICATION_METADATA_CHARS),
        channelId = notification.channelId.boundedNotificationText(MAX_NOTIFICATION_METADATA_CHARS),
        channelName = ranking?.channel?.name.boundedNotificationText(MAX_NOTIFICATION_METADATA_CHARS),
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

/**
 * Copies only the bounded prefix at the Android boundary. Avoiding `toString()` on the full
 * CharSequence keeps one oversized notification from being multiplied across the processing queue,
 * ML tokenization, and optional semantic analysis.
 */
internal fun CharSequence?.boundedNotificationText(maxChars: Int): String? {
    if (this == null) return null
    require(maxChars >= 0)
    return try {
        var end = minOf(length, maxChars)
        if (end > 0 && Character.isHighSurrogate(this[end - 1])) end -= 1
        subSequence(0, end).toString()
    } catch (_: RuntimeException) {
        // Notification extras are controlled by another app. Ignore only the malformed field so
        // package/category/channel rules can still evaluate and the listener process stays alive.
        null
    }
}

internal fun Bundle?.boundedNotificationExtra(
    maxChars: Int,
    vararg keys: String,
): String? {
    val extras = this ?: return null
    keys.forEach { key ->
        val value =
            try {
                extras.getCharSequence(key)
            } catch (_: RuntimeException) {
                null
            }
        value
            .boundedNotificationText(maxChars)
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
    }
    return null
}

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
