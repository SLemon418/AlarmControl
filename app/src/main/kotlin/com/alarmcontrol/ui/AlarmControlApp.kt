package com.alarmcontrol.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alarmcontrol.ui.theme.AlarmControlTheme
import com.alarmcontrol.ui.theme.AppThemeViewModel

/** Application-level Compose host that applies the locally persisted appearance preference. */
@Composable
fun AlarmControlApp(viewModel: AppThemeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AlarmControlTheme(dynamicColor = state.dynamicColorEnabled) {
        AppRoot()
    }
}
