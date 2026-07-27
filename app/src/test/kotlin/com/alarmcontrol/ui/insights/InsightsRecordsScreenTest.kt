package com.alarmcontrol.ui.insights

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class InsightsRecordsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun recordsActionFiltersWrapAtNarrowWidth() {
        composeRule.setContent {
            InsightsScreen(
                state = InsightsUiState(isLoading = false, selectedTab = InsightsTab.RECORDS),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
            )
        }

        val list = composeRule.onNodeWithTag(INSIGHTS_RECORDS_TEST_TAG)
        list.performScrollToNode(hasText("Kept"))
        composeRule.onNodeWithText("Kept").assertIsDisplayed()
    }

    @Test
    fun recordsSourceSelectorShowsLongSourcesAndDelegatesSelection() {
        val source =
            HistorySourceUi(
                packageName = "android",
                appName = "Android System",
                channelId = "alert_window_notification",
                channelName = "Black Screen is displaying over other apps",
                eventCount = 2,
            )
        var selected: HistorySourceUi? = null
        composeRule.setContent {
            InsightsScreen(
                state =
                    InsightsUiState(
                        isLoading = false,
                        selectedTab = InsightsTab.RECORDS,
                        historySources = listOf(source),
                    ),
                onUndo = {},
                onRecategorize = { _, _, _, _ -> },
                onUserMessageShown = {},
                onHistorySourceSelected = { selected = it },
            )
        }

        val list = composeRule.onNodeWithTag(INSIGHTS_RECORDS_TEST_TAG)
        list.performScrollToNode(hasText("All apps"))
        composeRule.onNodeWithText("All apps").performClick()
        composeRule
            .onNodeWithText("Android System · Black Screen is displaying over other apps")
            .assertIsDisplayed()
            .performClick()

        assertEquals(source, selected)
    }
}
