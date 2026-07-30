package com.alarmcontrol.core.filtering

/**
 * Durable two-phase boundary for every committed notification decision.
 *
 * A caller stages the complete privacy-safe record before claiming a listener token, arms it inside
 * that token's non-suspending commit callback, and only then performs cancel/snooze. Promotion is
 * idempotent: an armed row survives process death until it becomes the ordinary event record.
 */
interface NotificationActionOutbox {
    /** Durably stages one decision without authorizing its platform action. */
    suspend fun stage(
        event: NotificationEvent,
        content: NotificationContent? = null,
    ): StagedNotificationAction

    /**
     * Durably authorizes a staged decision. This deliberately does not suspend so it can run inside
     * the listener's atomic freshness callback immediately before the Binder action.
     */
    fun arm(staged: StagedNotificationAction): Boolean

    /** Atomically promotes an armed decision into history, returning the event id. */
    suspend fun promote(staged: StagedNotificationAction): String?

    /** Removes an uncommitted staged decision after its listener token was rejected. */
    suspend fun discard(staged: StagedNotificationAction)

    /**
     * Discards never-armed rows and promotes every armed row left by a prior process. Returns the
     * number of recovered event records.
     */
    suspend fun recover(): Int

    /**
     * Promotes armed rows without touching unarmed live staging. Used after a transient promotion
     * failure while the listener process remains connected.
     */
    suspend fun recoverArmed(): Int
}

/** Opaque identifier for one durable staged decision. */
@JvmInline
value class StagedNotificationAction(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Staged notification action id is blank" }
    }
}
