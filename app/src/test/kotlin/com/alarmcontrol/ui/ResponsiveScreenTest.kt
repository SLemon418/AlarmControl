package com.alarmcontrol.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import com.alarmcontrol.ui.rules.RULE_ADD_FAB_TEST_TAG
import com.alarmcontrol.ui.rules.RulesScreen
import com.alarmcontrol.ui.rules.RulesUiState
import com.alarmcontrol.ui.theme.AlarmControlTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w360dp-h800dp")
class ResponsiveScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `rules primary action remains visible at supported font scales`() {
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides
                    Density(density, fontScale),
            ) {
                AlarmControlTheme(dynamicColor = false) {
                    RulesScreen(
                        state = RulesUiState(isLoading = false),
                        onAddRule = {},
                        onEditRule = {},
                        onToggleRule = { _, _ -> },
                        onDeleteRule = {},
                        onUserMessageShown = {},
                    )
                }
            }
        }

        listOf(1f, 1.5f, 2f).forEach { scale ->
            composeRule.runOnIdle { fontScale = scale }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(RULE_ADD_FAB_TEST_TAG).assertIsDisplayed()
        }
    }
}
