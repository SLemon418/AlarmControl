package com.alarmcontrol.ui.profiles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alarmcontrol.R
import com.alarmcontrol.automation.ProfileController
import com.alarmcontrol.core.coroutines.AppDispatcher
import com.alarmcontrol.core.coroutines.Dispatcher
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.profile.FilteringProfile
import com.alarmcontrol.core.profile.ProfileRepository
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.core.result.asDataResult
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.ui.UiText
import com.alarmcontrol.ui.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel
    @Inject
    constructor(
        private val profileRepository: ProfileRepository,
        private val ruleRepository: RuleRepository,
        private val profileController: ProfileController,
        private val savedStateHandle: SavedStateHandle,
        @Dispatcher(AppDispatcher.Default) private val dispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val editor =
            MutableStateFlow(
                savedStateHandle
                    .get<ArrayList<String>>(PROFILE_EDITOR_DRAFT_SAVED_STATE_KEY)
                    ?.let(ProfileEditorDraftCodec::decode),
            )
        private val pendingDeleteId = MutableStateFlow<String?>(null)
        private val messages = MutableStateFlow<UiText?>(null)

        init {
            if (editor.value == null) {
                savedStateHandle.remove<ArrayList<String>>(PROFILE_EDITOR_DRAFT_SAVED_STATE_KEY)
            }
        }

        private val content: StateFlow<DataResult<ProfileContent>> =
            combine(profileRepository.observeProfiles(), ruleRepository.observeRules()) { profiles, rules ->
                ProfileContent(profiles, rules)
            }.asDataResult()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DataResult.Loading)

        val uiState: StateFlow<ProfilesUiState> =
            combine(content, editor, pendingDeleteId, messages) { result, editorState, deleteId, message ->
                val loaded = (result as? DataResult.Success)?.data
                val listItems = loaded?.toListItems().orEmpty()
                ProfilesUiState(
                    isLoading = result is DataResult.Loading,
                    profiles = listItems,
                    availableRules = loaded?.rules?.map(Rule::toOption).orEmpty(),
                    editor = editorState,
                    pendingDelete = listItems.firstOrNull { it.id == deleteId },
                    errorMessage =
                        if (result is DataResult.Failure) {
                            uiText(R.string.message_profiles_load_failed)
                        } else {
                            null
                        },
                    userMessage = message,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProfilesUiState())

        fun onAddProfile() {
            setEditor(ProfileEditorState())
        }

        fun onEditProfile(profileId: String) {
            val profiles = currentContent()?.profiles.orEmpty()
            profiles.firstOrNull { it.id == profileId }?.let { profile ->
                setEditor(
                    ProfileEditorState(
                        id = profile.id,
                        name = profile.name,
                        selectedRuleIds = profile.ruleIds,
                        nameConflict = profiles.hasNameConflict(profile.name, profile.id),
                    ),
                )
            }
        }

        fun onEditorChange(state: ProfileEditorState) {
            if (editor.value?.isSaving == true) return
            setEditor(
                state.copy(
                    nameConflict = currentContent()?.profiles.orEmpty().hasNameConflict(state.name, state.id),
                    hasUnsavedChanges = true,
                    showDiscardConfirmation = false,
                ),
            )
        }

        fun onDismissEditor() {
            val current = editor.value ?: return
            if (current.isSaving) return
            setEditor(
                if (current.hasUnsavedChanges) {
                    current.copy(showDiscardConfirmation = true)
                } else {
                    null
                },
            )
        }

        fun onCancelDiscardEditor() {
            setEditor(editor.value?.copy(showDiscardConfirmation = false))
        }

        fun onConfirmDiscardEditor() {
            setEditor(null)
        }

        fun onSaveProfile() {
            val state = editor.value ?: return
            if (state.isSaving) return
            if (!state.canSave) {
                messages.value =
                    uiText(
                        if (state.nameConflict) {
                            R.string.message_profile_duplicate
                        } else {
                            R.string.message_profile_required
                        },
                    )
                return
            }
            setEditor(state.copy(isSaving = true, showDiscardConfirmation = false))
            launchOp(
                onSuccess = { setEditor(null) },
                onComplete = { setEditor(editor.value?.copy(isSaving = false)) },
            ) {
                profileRepository.save(
                    FilteringProfile(
                        id = state.id,
                        name = state.name.trim(),
                        ruleIds = state.selectedRuleIds,
                    ),
                )
            }
        }

        fun onRequestDeleteProfile(profileId: String) {
            pendingDeleteId.value = profileId
        }

        fun onDismissDeleteProfile() {
            pendingDeleteId.value = null
        }

        fun onConfirmDeleteProfile() {
            val profileId = pendingDeleteId.value ?: return
            pendingDeleteId.value = null
            launchOp { profileRepository.delete(profileId) }
        }

        fun onToggleProfile(profileId: String) {
            launchOp { profileController.toggle(profileId) }
        }

        fun onUserMessageShown() {
            messages.value = null
        }

        private fun setEditor(state: ProfileEditorState?) {
            editor.value = state
            val encoded = state?.let(ProfileEditorDraftCodec::encode)
            if (encoded == null) {
                savedStateHandle.remove<ArrayList<String>>(PROFILE_EDITOR_DRAFT_SAVED_STATE_KEY)
            } else {
                savedStateHandle[PROFILE_EDITOR_DRAFT_SAVED_STATE_KEY] = encoded
            }
        }

        private fun currentContent(): ProfileContent? = (content.value as? DataResult.Success)?.data

        private fun launchOp(
            onSuccess: () -> Unit = {},
            onComplete: () -> Unit = {},
            block: suspend () -> Unit,
        ) {
            viewModelScope.launch {
                try {
                    val result = withContext(dispatcher) { runCatchingPreservingCancellation { block() } }
                    result
                        .onSuccess { onSuccess() }
                        .onFailure { messages.value = uiText(R.string.message_generic_error) }
                } finally {
                    onComplete()
                }
            }
        }

        private data class ProfileContent(
            val profiles: List<FilteringProfile>,
            val rules: List<Rule>,
        ) {
            fun toListItems(): List<ProfileListItem> {
                val rulesById = rules.associateBy(Rule::id)
                val duplicateNames =
                    profiles
                        .groupingBy { it.name.normalizedProfileName() }
                        .eachCount()
                        .filterValues { it > 1 }
                        .keys
                return profiles.map { profile ->
                    val existingRules = profile.ruleIds.mapNotNull(rulesById::get)
                    ProfileListItem(
                        id = profile.id,
                        name = profile.name,
                        memberCount = existingRules.size,
                        enabledCount = existingRules.count(Rule::enabled),
                        hasDuplicateName = profile.name.normalizedProfileName() in duplicateNames,
                    )
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

private fun Rule.toOption(): ProfileRuleOption =
    ProfileRuleOption(
        id = id,
        name = name,
        enabled = enabled,
    )

private fun List<FilteringProfile>.hasNameConflict(
    name: String,
    excludingId: String,
): Boolean {
    val normalized = name.normalizedProfileName()
    return normalized.isNotEmpty() &&
        any { profile ->
            profile.id != excludingId && profile.name.normalizedProfileName() == normalized
        }
}

private fun String.normalizedProfileName(): String = trim().lowercase()
