package com.alarmcontrol.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.alarmcontrol.ui.theme.AlarmControlTheme
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
class AppRootTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactLayoutUsesBottomNavigationAndHoistsSelection() {
        var selectedRoute: String? = null
        setNavigation(useRail = false, onNavigate = { selectedRoute = it })

        composeRule.onNodeWithTag(BOTTOM_NAVIGATION_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(NAVIGATION_RAIL_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Settings").performClick()

        assertEquals("settings", selectedRoute)
    }

    @Test
    fun expandedLayoutUsesNavigationRailWithAccessibleLabels() {
        setNavigation(useRail = true)

        composeRule.onNodeWithTag(NAVIGATION_RAIL_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(BOTTOM_NAVIGATION_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Rules", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Insights", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Profiles", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Settings", useUnmergedTree = true).assertExists()
    }

    @Test
    fun editorDestinationsHideTopLevelNavigation() {
        setNavigation(useRail = false, showNavigation = false)

        composeRule.onNodeWithTag(BOTTOM_NAVIGATION_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(NAVIGATION_RAIL_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Content").assertIsDisplayed()
    }

    private fun setNavigation(
        useRail: Boolean,
        onNavigate: (String) -> Unit = {},
        showNavigation: Boolean = true,
    ) {
        composeRule.setContent {
            AlarmControlTheme(dynamicColor = false) {
                AppNavigationScaffold(
                    useNavigationRail = useRail,
                    currentRoute = "rules",
                    onNavigate = onNavigate,
                    showNavigation = showNavigation,
                ) { Text("Content", modifier = it) }
            }
        }
    }
}
