package com.alarmcontrol.core.filtering

/** Maximum persisted text length for a single rule predicate. */
const val MAX_CONDITION_VALUE_CHARS = 4_096

/**
 * A pure predicate over a [NotificationSnapshot]. Conditions are the building blocks of a [Rule];
 * ML categorization is exposed as *one* condition ([MlCategoryEquals]) among many, never as a
 * replacement for rule logic (CLAUDE.md §6).
 *
 * Implementations are immutable and side-effect free, so evaluation is deterministic and testable.
 */
sealed interface Condition {
    /** Three-state result prevents missing optional signals from being mistaken for `false`. */
    fun evaluate(snapshot: NotificationSnapshot): ConditionResult

    /** Compatibility/fast-path helper: only an explicit [ConditionResult.MATCH] is actionable. */
    fun matches(snapshot: NotificationSnapshot): Boolean = evaluate(snapshot) == ConditionResult.MATCH

    /** Matches when the notification was posted by exactly this package. */
    data class PackageEquals(
        val packageName: String,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult =
            ConditionResult.from(snapshot.packageName == packageName)
    }

    /** Matches when the title contains [text]; a `null` title never matches. */
    data class TitleContains(
        val text: String,
        val ignoreCase: Boolean = true,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult =
            ConditionResult.from(snapshot.title?.contains(text, ignoreCase) == true)
    }

    /** Matches when the body text contains [text]; a `null` body never matches. */
    data class TextContains(
        val text: String,
        val ignoreCase: Boolean = true,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult =
            ConditionResult.from(snapshot.text?.contains(text, ignoreCase) == true)
    }

    /** Matches the Android `Notification.category` (e.g. `"alarm"`). */
    data class CategoryEquals(
        val category: String,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult =
            ConditionResult.from(snapshot.category == category)
    }

    /** Matches the originating notification channel id. */
    data class ChannelEquals(
        val channelId: String,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult =
            ConditionResult.from(snapshot.channelId == channelId)
    }

    /** Matches the ongoing/non-clearable flag. */
    data class Ongoing(
        val value: Boolean,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult =
            ConditionResult.from(snapshot.isOngoing == value)
    }

    /**
     * Matches the on-device classifier's label (CLAUDE.md §5/§6). When no ML signal is present
     * ([NotificationSnapshot.mlCategory] is `null`) this never matches, so rules fall back to their
     * other conditions instead of breaking.
     */
    data class MlCategoryEquals(
        val category: String,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult =
            snapshot.mlCategory?.let { ConditionResult.from(it == category) } ?: ConditionResult.UNKNOWN
    }

    /**
     * Matches the on-device LLM's semantic ad-detection verdict (Milestone 4). When no LLM signal is
     * present ([NotificationSnapshot.isAdvertisement] is `null` — model unavailable or low-confidence)
     * this never matches, so rules fall back to their other conditions instead of breaking (§5).
     */
    data class IsAdvertisement(
        val value: Boolean,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult =
            snapshot.isAdvertisement?.let { ConditionResult.from(it == value) } ?: ConditionResult.UNKNOWN
    }

    /** Matches the seven-way semantic verdict from the optional local LLM. */
    data class SemanticIntentEquals(
        val intent: SemanticIntent,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult =
            snapshot.semanticIntent?.let { ConditionResult.from(it == intent) } ?: ConditionResult.UNKNOWN
    }

    /** Matches Android's person-to-person conversation ranking signal. */
    data class Conversation(
        val value: Boolean,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult =
            snapshot.isConversation?.let { ConditionResult.from(it == value) } ?: ConditionResult.UNKNOWN
    }

    /** Matches the framework foreground-service notification flag. */
    data class ForegroundService(
        val value: Boolean,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult =
            snapshot.isForegroundService?.let { ConditionResult.from(it == value) } ?: ConditionResult.UNKNOWN
    }

    /** Matches when Android's ranking importance is at least [minimum]. */
    data class ImportanceAtLeast(
        val minimum: NotificationImportance,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult =
            snapshot.importance?.let { ConditionResult.from(it.ordinal >= minimum.ordinal) }
                ?: ConditionResult.UNKNOWN
    }

    /**
     * Matches when the caller supplied at least [threshold] posts in [windowMillis] for [scope].
     * The current post is included. Missing state is unknown so destructive rules fail safely.
     */
    data class RateAtLeast(
        val scope: RateScope,
        val windowMillis: Long,
        val threshold: Int,
    ) : Condition {
        init {
            require(windowMillis in MIN_RATE_WINDOW_MILLIS..MAX_RATE_WINDOW_MILLIS) {
                "Rate window is out of range"
            }
            require(threshold in MIN_RATE_THRESHOLD..MAX_RATE_THRESHOLD) {
                "Rate threshold is out of range"
            }
        }

        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult =
            snapshot.rateCounts[RateSignal(scope, windowMillis)]
                ?.let { ConditionResult.from(it >= threshold) }
                ?: ConditionResult.UNKNOWN
    }

    /**
     * Matches when the notification's local post time falls within the daily window
     * `[startMinuteOfDay, endMinuteOfDay]` (minutes from midnight, both inclusive). The window may
     * wrap past midnight (e.g. 22:00–07:00 is start `1320`, end `420`). Never matches when
     * [NotificationSnapshot.postedMinuteOfDay] is `null` (no local time available), so the rule
     * degrades gracefully (§5) rather than guessing.
     */
    data class TimeWindow(
        val startMinuteOfDay: Int,
        val endMinuteOfDay: Int,
    ) : Condition {
        init {
            require(startMinuteOfDay in MINUTE_OF_DAY_RANGE && endMinuteOfDay in MINUTE_OF_DAY_RANGE) {
                "Time window is out of range"
            }
        }

        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult {
            val minute = snapshot.postedMinuteOfDay ?: return ConditionResult.UNKNOWN
            val matches =
                if (startMinuteOfDay <= endMinuteOfDay) {
                    minute in startMinuteOfDay..endMinuteOfDay
                } else {
                    minute >= startMinuteOfDay || minute <= endMinuteOfDay
                }
            return ConditionResult.from(matches)
        }
    }

    /** True only if every child matches. Empty is rejected as non-matching for destructive safety. */
    data class AllOf(
        val conditions: List<Condition>,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult {
            if (conditions.isEmpty()) return ConditionResult.NO_MATCH
            var sawUnknown = false
            for (condition in conditions) {
                when (condition.evaluate(snapshot)) {
                    ConditionResult.NO_MATCH -> return ConditionResult.NO_MATCH
                    ConditionResult.UNKNOWN -> sawUnknown = true
                    ConditionResult.MATCH -> Unit
                }
            }
            return if (sawUnknown) ConditionResult.UNKNOWN else ConditionResult.MATCH
        }
    }

    /** True if any child condition matches. An empty list is false. */
    data class AnyOf(
        val conditions: List<Condition>,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult {
            if (conditions.isEmpty()) return ConditionResult.NO_MATCH
            var sawUnknown = false
            for (condition in conditions) {
                when (condition.evaluate(snapshot)) {
                    ConditionResult.MATCH -> return ConditionResult.MATCH
                    ConditionResult.UNKNOWN -> sawUnknown = true
                    ConditionResult.NO_MATCH -> Unit
                }
            }
            return if (sawUnknown) ConditionResult.UNKNOWN else ConditionResult.NO_MATCH
        }
    }

    /** Inverts the wrapped condition. */
    data class Not(
        val condition: Condition,
    ) : Condition {
        override fun evaluate(snapshot: NotificationSnapshot): ConditionResult = condition.evaluate(snapshot).not()
    }
}

private val MINUTE_OF_DAY_RANGE = 0..1_439

/** Pure three-valued logic used by both the hot matcher and its diagnostic trace. */
enum class ConditionResult {
    MATCH,
    NO_MATCH,
    UNKNOWN,
    ;

    fun not(): ConditionResult =
        when (this) {
            MATCH -> NO_MATCH
            NO_MATCH -> MATCH
            UNKNOWN -> UNKNOWN
        }

    companion object {
        fun from(matches: Boolean): ConditionResult = if (matches) MATCH else NO_MATCH
    }
}
