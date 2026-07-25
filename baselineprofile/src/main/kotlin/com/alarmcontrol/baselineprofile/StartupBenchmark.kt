package com.alarmcontrol.baselineprofile

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures profile-installed cold startup without opening a socket.
 *
 * AndroidX's trace-processor timing metric communicates with an on-device localhost HTTP server and
 * therefore requires `android.permission.INTERNET` in the test APK. AlarmControl deliberately
 * forbids that permission in every manifest, including benchmark manifests. The connected Gradle
 * task still installs the release-like app together with its generated `.dm` profile; this test then
 * records Android ActivityManager's `TotalTime` for ten force-stopped launches.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @Test
    fun coldStartupWithBaselineProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val enabledRules = InstrumentationRegistry.getArguments().getString(ENABLED_RULES_ARGUMENT)
        assumeFalse(
            "Startup timing runs separately from Baseline Profile collection",
            enabledRules?.contains(BASELINE_PROFILE_RULE, ignoreCase = true) == true,
        )
        val device = UiDevice.getInstance(instrumentation)

        val durations =
            List(ITERATIONS) {
                device.pressHome()
                device.executeShellCommand("am force-stop $PACKAGE_NAME")
                val output = device.executeShellCommand("am start -W -n $COMPONENT_NAME")
                requireNotNull(
                    TOTAL_TIME
                        .find(output)
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull(),
                ) {
                    "ActivityManager did not report a startup TotalTime"
                }
            }

        assertEquals(ITERATIONS, durations.size)
        assertTrue("Every cold-start duration must be positive", durations.all { it > 0L })
        instrumentation.sendStatus(
            STATUS_CODE,
            Bundle().apply { putString(RESULT_KEY, durations.joinToString(separator = ",")) },
        )
    }

    private companion object {
        const val PACKAGE_NAME = "com.alarmcontrol"
        const val COMPONENT_NAME = "$PACKAGE_NAME/.MainActivity"
        const val ITERATIONS = 10
        const val ENABLED_RULES_ARGUMENT = "androidx.benchmark.enabledRules"
        const val BASELINE_PROFILE_RULE = "BaselineProfile"
        const val STATUS_CODE = 2
        const val RESULT_KEY = "alarmcontrol.startup.totalTimeMs"
        val TOTAL_TIME = Regex("""TotalTime:\s*(\d+)""")
    }
}
