package com.alarmcontrol.core.filtering

/**
 * Immutable, framework-free view of a single posted notification — the sole input the matching
 * engine evaluates (CLAUDE.md §4/§6).
 *
 * The `NotificationListenerService` shell in `:app` is responsible for translating an
 * `android.service.notification.StatusBarNotification` into one of these. Keeping this a plain
 * `data class` (no Android types) is what lets the engine be unit-tested without Robolectric.
 *
 * @property mlCategory optional on-device classifier signal (CLAUDE.md §5/§6). The caller sets this
 *   only when the model is available *and* confident; otherwise it stays `null` and rules degrade
 *   gracefully to their non-ML conditions. The engine never runs inference itself.
 */
data class NotificationSnapshot(
    val packageName: String,
    val title: String?,
    val text: String?,
    /** Android `Notification.category` (e.g. `"alarm"`); `null` when the source set none. */
    val category: String?,
    val channelId: String?,
    /** Human-readable channel label supplied by Android ranking, when available. */
    val channelName: String? = null,
    val postedAtMillis: Long,
    val isOngoing: Boolean,
    val mlCategory: String? = null,
    /**
     * Local wall-clock minute of day (0..1439) the notification was posted, resolved by the caller
     * (the `:app` service has the device time zone). `null` when unknown, so [Condition.TimeWindow]
     * degrades to a non-match rather than guessing (CLAUDE.md §5).
     */
    val postedMinuteOfDay: Int? = null,
    /** Local calendar day at post time, for timezone-correct local analytics. */
    val postedEpochDay: Long? = null,
    /** Lockscreen visibility controls whether optional encrypted content capture is permitted. */
    val contentVisibility: NotificationContentVisibility = NotificationContentVisibility.UNKNOWN,
    /**
     * On-device LLM ad-detection verdict (Milestone 4): `true`/`false` when the model is available and
     * confident, `null` otherwise. The caller (the `:app` service) sets it; the engine never runs
     * inference itself, so [Condition.IsAdvertisement] degrades gracefully when it's `null` (§5).
     */
    val isAdvertisement: Boolean? = null,
    /** Seven-way local LLM verdict; absent when the optional model is unavailable or not requested. */
    val semanticIntent: SemanticIntent? = null,
    /** Android ranking importance mapped at the framework boundary; absent when ranking is unavailable. */
    val importance: NotificationImportance? = null,
    /** Whether Android ranks this as a person-to-person conversation; absent when unsupported. */
    val isConversation: Boolean? = null,
    /** Whether the source notification represents a foreground service; absent outside Android mapping. */
    val isForegroundService: Boolean? = null,
    /** Frequency counts prepared by the service for exactly the windows requested by active rules. */
    val rateCounts: Map<RateSignal, Int> = emptyMap(),
)

/** Framework-free equivalent of Android notification lockscreen visibility. */
enum class NotificationContentVisibility {
    PUBLIC,
    PRIVATE,
    SECRET,
    UNKNOWN,
}
