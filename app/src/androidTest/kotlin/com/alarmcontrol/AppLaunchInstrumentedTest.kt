package com.alarmcontrol

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Android-runtime smoke test for the real Activity, Hilt graph, resources, and navigation chrome. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AppLaunchInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesWithAccessibleTopLevelNavigation() {
        val rulesLabel = composeRule.activity.getString(R.string.nav_rules)
        composeRule.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(rulesLabel, useUnmergedTree = true)
            .assertExists()
    }
}
