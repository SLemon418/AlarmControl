package com.alarmcontrol.ui.profiles

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
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel
    @Inject
    constructor(
        private val profileRepository: ProfileRepository,
        private val ruleRepository: RuleRepository,
        private val profileController: ProfileController,
        @Dispatcher(AppDispatcher.Default) private val dispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val editor = MutableStateFlow<ProfileEditorState?>(null)
        private val pendingDeleteId = MutableStateFlow<String?>(null)
        private val messages = MutableStateFlow<UiText?>(null)

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
            editor.value = ProfileEditorState()
        }

        fun onEditProfile(profileId: String) {
            currentContent()?.profiles?.firstOrNull { it.id == profileId }?.let { profile ->
                editor.value =
                    ProfileEditorState(
                        id = profile.id,
                        name = profile.name,
                        selectedRuleIds = profile.ruleIds,
                    )
            }
        }

        fun onEditorChange(state: ProfileEditorState) {
            editor.value = state.copy(hasUnsavedChanges = true, showDiscardConfirmation = false)
        }

        fun onDismissEditor() {
            val current = editor.value ?: return
            editor.value =
                if (current.hasUnsavedChanges) {
                    current.copy(showDiscardConfirmation = true)
                } else {
                    null
                }
        }

        fun onCancelDiscardEditor() {
            editor.value = editor.value?.copy(showDiscardConfirmation = false)
        }

        fun onConfirmDiscardEditor() {
            editor.value = null
        }

        fun onSaveProfile() {
            val state = editor.value ?: return
            if (!state.canSave) {
                messages.value = uiText(R.string.message_profile_required)
                return
            }
            launchOp(onSuccess = { editor.value = null }) {
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

        private fun currentContent(): ProfileContent? = (content.value as? DataResult.Success)?.data

        private fun launchOp(
            onSuccess: () -> Unit = {},
            block: suspend () -> Unit,
        ) {
            viewModelScope.launch(dispatcher) {
                runCatchingPreservingCancellation { block() }
                    .onSuccess { onSuccess() }
                    .onFailure { messages.value = uiText(R.string.message_generic_error) }
            }
        }

        private data class ProfileContent(
            val profiles: List<FilteringProfile>,
            val rules: List<Rule>,
        ) {
            fun toListItems(): List<ProfileListItem> {
                val rulesById = rules.associateBy(Rule::id)
                return profiles.map { profile ->
                    val existingRules = profile.ruleIds.mapNotNull(rulesById::get)
                    ProfileListItem(
                        id = profile.id,
                        name = profile.name,
                        memberCount = existingRules.size,
                        enabledCount = existingRules.count(Rule::enabled),
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
