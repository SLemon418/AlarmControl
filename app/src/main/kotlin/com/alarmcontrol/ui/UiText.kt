package com.alarmcontrol.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Localizable presentation text that can safely live in immutable UI state. ViewModels and mappers
 * select resource ids while only Composables resolve them for the current locale.
 */
sealed interface UiText {
    data class Resource(
        @StringRes val id: Int,
        val arguments: List<Any> = emptyList(),
    ) : UiText

    /** User-authored or platform-provided text that must be shown verbatim. */
    data class Dynamic(
        val value: String,
    ) : UiText
}

/** Resolves nested [UiText.Resource] arguments using the current Compose locale. */
@Composable
@Suppress("SpreadOperator")
fun UiText.asString(): String =
    when (this) {
        is UiText.Dynamic -> value
        is UiText.Resource -> {
            val resolvedArguments = ArrayList<Any>(arguments.size)
            for (argument in arguments) {
                resolvedArguments += if (argument is UiText) argument.asString() else argument
            }
            stringResource(id, *resolvedArguments.toTypedArray())
        }
    }

internal fun uiText(
    @StringRes id: Int,
    vararg arguments: Any,
): UiText = UiText.Resource(id, arguments.toList())
