package com.alarmcontrol.ui.theme

import app.cash.turbine.test
import com.alarmcontrol.testsupport.MainDispatcherRule
import com.alarmcontrol.testsupport.awaitUntil
import com.alarmcontrol.ui.settings.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppThemeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `dynamic color is disabled by default and reacts to the local preference`() =
        runTest {
            val repository = FakeSettingsRepository(dynamicColor = false)
            val viewModel = AppThemeViewModel(repository)

            viewModel.uiState.test {
                assertFalse(awaitUntil { !it.dynamicColorEnabled }.dynamicColorEnabled)

                repository.setDynamicColorEnabled(true)

                assertTrue(awaitUntil { it.dynamicColorEnabled }.dynamicColorEnabled)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
