package com.alarmcontrol.ui.theme

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
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
class AlarmControlThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `brand palette is deterministic in light mode`() {
        var primary = Color.Unspecified
        composeRule.setContent {
            AlarmControlTheme(darkTheme = false, dynamicColor = false) {
                val resolved = MaterialTheme.colorScheme.primary
                SideEffect { primary = resolved }
            }
        }

        composeRule.runOnIdle {
            assertEquals(Color(0xFF315DA8), primary)
        }
    }

    @Test
    fun `dark mode uses the calm high contrast palette`() {
        var primary = Color.Unspecified
        var background = Color.Unspecified
        composeRule.setContent {
            AlarmControlTheme(darkTheme = true, dynamicColor = false) {
                val resolvedPrimary = MaterialTheme.colorScheme.primary
                val resolvedBackground = MaterialTheme.colorScheme.background
                SideEffect {
                    primary = resolvedPrimary
                    background = resolvedBackground
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(Color(0xFFADC6FF), primary)
            assertEquals(Color(0xFF111318), background)
        }
    }
}
