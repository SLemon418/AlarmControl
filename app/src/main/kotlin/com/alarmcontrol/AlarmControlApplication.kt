package com.alarmcontrol

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.BackoffPolicy
import com.alarmcontrol.automation.ProfileShortcuts
import com.alarmcontrol.core.coroutines.ApplicationScope
import com.alarmcontrol.core.profile.ProfileRepository
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.work.NotificationInsightsWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class AlarmControlApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject @field:ApplicationScope
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
        scheduleInsightsWork()
        // Publish dynamic launcher shortcuts off the main thread (light disk I/O).
        applicationScope.launch {
            profileRepository.observeProfiles().collectLatest { profiles ->
                runCatchingPreservingCancellation {
                    ProfileShortcuts.publish(this@AlarmControlApplication, profiles)
                }.onFailure { Log.w(TAG, "Couldn't publish launcher shortcuts") }
            }
        }
    }

    /** Enqueues or updates the daily local insights job without resetting its original enqueue time. */
    private fun scheduleInsightsWork() {
        val constraints =
            Constraints
                .Builder()
                .setRequiresBatteryNotLow(true)
                .build()
        val request =
            PeriodicWorkRequestBuilder<NotificationInsightsWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            ).setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniquePeriodicWork(
            INSIGHTS_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        workManager.enqueueUniqueWork(
            INSIGHTS_BOOTSTRAP_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<NotificationInsightsWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build(),
        )
    }

    private companion object {
        const val TAG = "AlarmControl"
        const val INSIGHTS_WORK_NAME = "notification-insights"
        const val INSIGHTS_BOOTSTRAP_WORK_NAME = "notification-insights-bootstrap"
    }
}
