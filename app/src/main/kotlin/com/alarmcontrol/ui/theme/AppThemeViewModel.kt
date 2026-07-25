package com.alarmcontrol.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alarmcontrol.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Stable app-level appearance state; persistence stays behind the core settings contract. */
data class AppThemeUiState(
    val dynamicColorEnabled: Boolean = false,
)

@HiltViewModel
class AppThemeViewModel
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
    ) : ViewModel() {
        val uiState: StateFlow<AppThemeUiState> =
            settingsRepository.dynamicColorEnabled
                .map(::AppThemeUiState)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = AppThemeUiState(),
                )

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
