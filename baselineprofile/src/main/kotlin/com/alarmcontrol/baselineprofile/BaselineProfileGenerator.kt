package com.alarmcontrol.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Generates startup and primary-navigation profile rules on a connected API 33+ device. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() =
        baselineProfileRule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()

            // Exercise stable top-level journeys. Both shipped locales are accepted so generation
            // does not depend on the benchmark device's current language.
            openDestination("Insights", "통계")
            openDestination("Profiles", "프로필")
            openDestination("Settings", "설정")
            openDestination("Rules", "규칙")
        }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.openDestination(
        english: String,
        korean: String,
    ) {
        val label = Pattern.compile("${Pattern.quote(english)}|${Pattern.quote(korean)}")
        val destination =
            checkNotNull(device.wait(Until.findObject(By.text(label)), UI_TIMEOUT_MILLIS)) {
                "Top-level destination was not visible: $english"
            }
        destination.click()
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE_NAME = "com.alarmcontrol"
        const val UI_TIMEOUT_MILLIS = 5_000L
    }
}
