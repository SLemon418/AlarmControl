package com.alarmcontrol

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import com.alarmcontrol.automation.ProfileShortcuts
import com.alarmcontrol.core.coroutines.ApplicationScope
import com.alarmcontrol.core.profile.ProfileRepository
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.work.NotificationInsightsWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class AlarmControlApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject lateinit var profileRepository: ProfileRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    // On-demand WorkManager initialization so Hilt can inject worker dependencies (the default
    // startup initializer is removed in the manifest).
    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            retryIdempotentOperation(
                onFailure = { Log.w(TAG, "Couldn't initialize notification-detail policy; retrying") },
                operation = settingsRepository::initializeNotificationContentStorageDefault,
            )
        }
        applicationScope.launch {
            retryWorkEnqueue(
                onFailure = { Log.w(TAG, "Couldn't schedule periodic insights; retrying") },
                enqueue = ::schedulePeriodicInsightsWork,
            )
        }
        applicationScope.launch {
            retryWorkEnqueue(
                onFailure = { Log.w(TAG, "Couldn't schedule insights bootstrap; retrying") },
                enqueue = ::scheduleBootstrapInsightsWork,
            )
        }
        // Publish dynamic launcher shortcuts off the main thread (light disk I/O).
        applicationScope.launch {
            profileRepository
                .observeProfiles()
                .retryWithBackoff(
                    onFailure = {
                        Log.w(TAG, "Couldn't load launcher shortcut profiles; retrying")
                    },
                ).collectLatest { profiles ->
                    retryIdempotentOperation(
                        onFailure = {
                            Log.w(TAG, "Couldn't publish launcher shortcuts; retrying")
                        },
                    ) {
                        check(ProfileShortcuts.publish(this@AlarmControlApplication, profiles)) {
                            "Shortcut manager rejected the update"
                        }
                    }
                }
        }
    }

    /** Enqueues or updates the daily local insights job without resetting its original enqueue time. */
    private suspend fun schedulePeriodicInsightsWork() {
        val request =
            PeriodicWorkRequestBuilder<NotificationInsightsWorker>(
                repeatInterval = INSIGHTS_REPEAT_HOURS,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            ).setConstraints(insightsConstraints())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    INSIGHTS_BACKOFF_MINUTES,
                    TimeUnit.MINUTES,
                ).build()

        WorkManager
            .getInstance(this)
            .enqueueUniquePeriodicWork(
                INSIGHTS_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            ).await()
    }

    /**
     * Re-submits the idempotent per-version bootstrap on every process start. WorkManager's unique
     * work policy prevents duplication while still allowing a failed enqueue to recover later.
     */
    private suspend fun scheduleBootstrapInsightsWork() {
        WorkManager
            .getInstance(this)
            .enqueueUniqueWork(
                insightsBootstrapWorkName(BuildConfig.VERSION_CODE),
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<NotificationInsightsWorker>()
                    .setConstraints(insightsConstraints())
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        INSIGHTS_BACKOFF_MINUTES,
                        TimeUnit.MINUTES,
                    ).build(),
            ).await()
    }

    private fun insightsConstraints(): Constraints =
        Constraints
            .Builder()
            .setRequiresBatteryNotLow(true)
            .build()

    private companion object {
        const val TAG = "AlarmControl"
        const val INSIGHTS_WORK_NAME = "notification-insights"
        const val INSIGHTS_REPEAT_HOURS = 24L
        const val INSIGHTS_BACKOFF_MINUTES = 30L
    }
}

/**
 * Waits for WorkManager's asynchronous enqueue result and keeps retrying inside the process after
 * transient database or executor failures. Unique work policies make every retry idempotent.
 */
internal suspend fun retryWorkEnqueue(
    initialRetryMillis: Long = WORK_ENQUEUE_INITIAL_RETRY_MILLIS,
    maxRetryMillis: Long = WORK_ENQUEUE_MAX_RETRY_MILLIS,
    onFailure: (Throwable) -> Unit = {},
    enqueue: suspend () -> Unit,
) = retryIdempotentOperation(
    initialRetryMillis = initialRetryMillis,
    maxRetryMillis = maxRetryMillis,
    onFailure = onFailure,
    operation = enqueue,
)

/** Retries an idempotent local operation with bounded exponential backoff until it succeeds. */
internal suspend fun retryIdempotentOperation(
    initialRetryMillis: Long = LOCAL_RETRY_INITIAL_MILLIS,
    maxRetryMillis: Long = LOCAL_RETRY_MAX_MILLIS,
    onFailure: (Throwable) -> Unit = {},
    operation: suspend () -> Unit,
) {
    require(initialRetryMillis > 0L) { "Initial retry delay must be positive" }
    require(maxRetryMillis >= initialRetryMillis) {
        "Maximum retry delay must cover the initial delay"
    }
    var retryMillis = initialRetryMillis
    while (true) {
        val failure = runCatchingPreservingCancellation { operation() }.exceptionOrNull()
        if (failure == null) return
        onFailure(failure)
        delay(retryMillis)
        retryMillis = nextRetryMillis(retryMillis, maxRetryMillis)
    }
}

/** Re-subscribes after an upstream local-storage failure instead of terminating for this process. */
internal fun <T> Flow<T>.retryWithBackoff(
    initialRetryMillis: Long = LOCAL_RETRY_INITIAL_MILLIS,
    maxRetryMillis: Long = LOCAL_RETRY_MAX_MILLIS,
    onFailure: (Throwable) -> Unit = {},
): Flow<T> {
    require(initialRetryMillis > 0L) { "Initial retry delay must be positive" }
    require(maxRetryMillis >= initialRetryMillis) {
        "Maximum retry delay must cover the initial delay"
    }
    return retryWhen { cause, attempt ->
        if (cause is CancellationException) return@retryWhen false
        onFailure(cause)
        var retryMillis = initialRetryMillis
        repeat(attempt.coerceAtMost(MAX_BACKOFF_STEPS).toInt()) {
            retryMillis = nextRetryMillis(retryMillis, maxRetryMillis)
        }
        delay(retryMillis)
        true
    }
}

private fun nextRetryMillis(
    current: Long,
    maximum: Long,
): Long =
    if (current >= maximum / 2L) {
        maximum
    } else {
        current * 2L
    }

internal fun insightsBootstrapWorkName(versionCode: Int): String = "$INSIGHTS_BOOTSTRAP_WORK_NAME-$versionCode"

private const val WORK_ENQUEUE_INITIAL_RETRY_MILLIS = 1_000L
private const val WORK_ENQUEUE_MAX_RETRY_MILLIS = 30 * 60_000L
private const val LOCAL_RETRY_INITIAL_MILLIS = 1_000L
private const val LOCAL_RETRY_MAX_MILLIS = 30 * 60_000L
private const val MAX_BACKOFF_STEPS = 63L
private const val INSIGHTS_BOOTSTRAP_WORK_NAME = "notification-insights-bootstrap"
