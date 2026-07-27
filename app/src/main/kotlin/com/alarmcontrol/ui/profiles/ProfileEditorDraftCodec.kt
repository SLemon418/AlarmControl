package com.alarmcontrol.ui.profiles

import com.alarmcontrol.core.profile.MAX_PROFILE_RULE_IDS

internal const val PROFILE_EDITOR_DRAFT_SAVED_STATE_KEY = "profile_editor_draft_v1"

/** Bounded process-death state for the profile editor, stored atomically as one supported value. */
internal object ProfileEditorDraftCodec {
    fun encode(state: ProfileEditorState): ArrayList<String>? =
        safely {
            require(state.id.length <= MAX_ID_CHARS)
            require(state.name.length <= MAX_DRAFT_NAME_CHARS)
            require(state.selectedRuleIds.size <= MAX_PROFILE_RULE_IDS)
            require(state.selectedRuleIds.all { it.length <= MAX_ID_CHARS })
            arrayListOf(
                VERSION,
                state.id,
                state.name,
                state.nameConflict.encoded(),
                state.hasUnsavedChanges.encoded(),
                state.showDiscardConfirmation.encoded(),
                state.selectedRuleIds.size.toString(),
            ).apply {
                addAll(state.selectedRuleIds.sorted())
            }
        }

    fun decode(values: ArrayList<String>): ProfileEditorState? =
        safely {
            require(values.size >= HEADER_SIZE)
            require(values[VERSION_INDEX] == VERSION)
            val id = values[ID_INDEX].also { require(it.length <= MAX_ID_CHARS) }
            val name = values[NAME_INDEX].also { require(it.length <= MAX_DRAFT_NAME_CHARS) }
            val ruleCount = values[RULE_COUNT_INDEX].toInt()
            require(ruleCount in 0..MAX_PROFILE_RULE_IDS)
            require(values.size == HEADER_SIZE + ruleCount)
            val ruleIds =
                values
                    .subList(HEADER_SIZE, values.size)
                    .onEach { require(it.length <= MAX_ID_CHARS) }
                    .toSet()
            require(ruleIds.size == ruleCount)
            ProfileEditorState(
                id = id,
                name = name,
                selectedRuleIds = ruleIds,
                nameConflict = values[CONFLICT_INDEX].decodedBoolean(),
                hasUnsavedChanges = values[UNSAVED_INDEX].decodedBoolean(),
                showDiscardConfirmation = values[DISCARD_INDEX].decodedBoolean(),
            )
        }

    private fun Boolean.encoded(): String = if (this) TRUE else FALSE

    private fun String.decodedBoolean(): Boolean =
        when (this) {
            TRUE -> true
            FALSE -> false
            else -> throw IllegalArgumentException("Invalid boolean")
        }

    private inline fun <T> safely(block: () -> T): T? =
        try {
            block()
        } catch (_: IllegalArgumentException) {
            null
        }

    private const val VERSION = "1"
    private const val TRUE = "1"
    private const val FALSE = "0"
    private const val MAX_ID_CHARS = 256
    private const val MAX_DRAFT_NAME_CHARS = 1_024

    private const val VERSION_INDEX = 0
    private const val ID_INDEX = 1
    private const val NAME_INDEX = 2
    private const val CONFLICT_INDEX = 3
    private const val UNSAVED_INDEX = 4
    private const val DISCARD_INDEX = 5
    private const val RULE_COUNT_INDEX = 6
    private const val HEADER_SIZE = 7
}
