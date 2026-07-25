package com.alarmcontrol.service

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reads local platform state used by onboarding and diagnostics; it performs no I/O or network work. */
@Singleton
class AppHealthProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun snapshot(): AppHealthSnapshot {
            val powerManager = context.getSystemService(PowerManager::class.java)
            return AppHealthSnapshot(
                notificationAccessGranted = NotificationAccess.isGranted(context),
                batteryOptimizationExempt =
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true,
            )
        }
    }

data class AppHealthSnapshot(
    val notificationAccessGranted: Boolean,
    val batteryOptimizationExempt: Boolean,
)
