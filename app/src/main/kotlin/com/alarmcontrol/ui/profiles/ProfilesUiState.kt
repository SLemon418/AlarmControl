package com.alarmcontrol.ui.profiles

import com.alarmcontrol.core.profile.MAX_PROFILE_NAME_CHARS
import com.alarmcontrol.ui.UiText

data class ProfilesUiState(
    val isLoading: Boolean = true,
    val profiles: List<ProfileListItem> = emptyList(),
    val availableRules: List<ProfileRuleOption> = emptyList(),
    val editor: ProfileEditorState? = null,
    val pendingDelete: ProfileListItem? = null,
    val errorMessage: UiText? = null,
    val userMessage: UiText? = null,
)

data class ProfileListItem(
    val id: String,
    val name: String,
    val memberCount: Int,
    val enabledCount: Int,
    val hasDuplicateName: Boolean = false,
) {
    val isActive: Boolean get() = memberCount > 0 && enabledCount == memberCount
    val isPartial: Boolean get() = enabledCount in 1 until memberCount
}

data class ProfileRuleOption(
    val id: String,
    val name: String,
    val enabled: Boolean,
)

data class ProfileEditorState(
    val id: String = "",
    val name: String = "",
    val selectedRuleIds: Set<String> = emptySet(),
    val nameConflict: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val showDiscardConfirmation: Boolean = false,
) {
    val isEditing: Boolean get() = id.isNotBlank()
    val canSave: Boolean
        get() =
            name.isNotBlank() &&
                name.length <= MAX_PROFILE_NAME_CHARS &&
                !nameConflict &&
                selectedRuleIds.isNotEmpty()
}
