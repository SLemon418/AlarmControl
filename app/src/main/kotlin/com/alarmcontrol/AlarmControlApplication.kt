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
import com.alarmcontrol.automation.ProfileShortcuts
import com.alarmcontrol.core.coroutines.ApplicationScope
import com.alarmcontrol.core.profile.ProfileRepository
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.work.NotificationInsightsWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
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
            runCatchingPreservingCancellation {
                schedulePeriodicInsightsWork()
            }.onFailure { Log.w(TAG, "Couldn't schedule periodic insights") }
            runCatchingPreservingCancellation {
                scheduleBootstrapInsightsWork()
            }.onFailure { Log.w(TAG, "Couldn't schedule insights bootstrap") }
        }
        // Publish dynamic launcher shortcuts off the main thread (light disk I/O).
        applicationScope.launch {
            profileRepository
                .observeProfiles()
                .catch {
                    Log.w(TAG, "Couldn't load launcher shortcut profiles")
                    emit(emptyList())
                }.collectLatest { profiles ->
                    runCatchingPreservingCancellation {
                        ProfileShortcuts.publish(this@AlarmControlApplication, profiles)
                    }.onFailure { Log.w(TAG, "Couldn't publish launcher shortcuts") }
                }
        }
    }

    /** Enqueues or updates the daily local insights job without resetting its original enqueue time. */
    private fun schedulePeriodicInsightsWork() {
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

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            INSIGHTS_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Re-submits the idempotent per-version bootstrap on every process start. WorkManager's unique
     * work policy prevents duplication while still allowing a failed enqueue to recover later.
     */
    private fun scheduleBootstrapInsightsWork() {
        WorkManager.getInstance(this).enqueueUniqueWork(
            insightsBootstrapWorkName(BuildConfig.VERSION_CODE),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<NotificationInsightsWorker>()
                .setConstraints(insightsConstraints())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    INSIGHTS_BACKOFF_MINUTES,
                    TimeUnit.MINUTES,
                ).build(),
        )
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

internal fun insightsBootstrapWorkName(versionCode: Int): String = "$INSIGHTS_BOOTSTRAP_WORK_NAME-$versionCode"

private const val INSIGHTS_BOOTSTRAP_WORK_NAME = "notification-insights-bootstrap"
