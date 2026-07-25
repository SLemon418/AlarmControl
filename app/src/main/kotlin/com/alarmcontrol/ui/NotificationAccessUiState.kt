package com.alarmcontrol.ui

/** Explicit startup state prevents briefly presenting notification access as granted before it is read. */
enum class NotificationAccessUiState {
    CHECKING,
    GRANTED,
    DENIED,
}
