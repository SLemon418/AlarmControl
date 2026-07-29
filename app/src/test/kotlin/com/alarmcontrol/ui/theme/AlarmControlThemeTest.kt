package com.alarmcontrol.ui.theme

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `insights comparison colors clear three-to-one contrast in light mode`() {
        assertInsightsContrast(darkTheme = false)
    }

    @Test
    fun `insights comparison colors clear three-to-one contrast in dark mode`() {
        assertInsightsContrast(darkTheme = true)
    }

    private fun assertInsightsContrast(darkTheme: Boolean) {
        var primary = Color.Unspecified
        var total = Color.Unspecified
        var surface = Color.Unspecified
        composeRule.setContent {
            AlarmControlTheme(darkTheme = darkTheme, dynamicColor = false) {
                val colors = MaterialTheme.colorScheme
                SideEffect {
                    primary = colors.primary
                    total = colors.outline
                    surface = colors.surfaceContainerLow
                }
            }
        }

        composeRule.runOnIdle {
            assertTrue(contrastRatio(primary, surface) >= MIN_GRAPHICS_CONTRAST)
            assertTrue(contrastRatio(total, surface) >= MIN_GRAPHICS_CONTRAST)
        }
    }

    private fun contrastRatio(
        first: Color,
        second: Color,
    ): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + CONTRAST_OFFSET) / (darker + CONTRAST_OFFSET)
    }

    private companion object {
        const val MIN_GRAPHICS_CONTRAST = 3f
        const val CONTRAST_OFFSET = 0.05f
    }
}
