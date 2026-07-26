package com.alarmcontrol.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alarmcontrol.R
import com.alarmcontrol.core.profile.MAX_PROFILE_NAME_CHARS
import com.alarmcontrol.ui.asString
import com.alarmcontrol.ui.designsystem.EmptyState
import com.alarmcontrol.ui.designsystem.MaxWidthContent
import com.alarmcontrol.ui.designsystem.StatusPill

@Composable
fun ProfilesRoute(
    viewModel: ProfilesViewModel = hiltViewModel(),
    onOpenEditor: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProfilesScreen(
        state = state,
        onAddProfile = {
            viewModel.onAddProfile()
            onOpenEditor()
        },
        onEditProfile = { id ->
            viewModel.onEditProfile(id)
            onOpenEditor()
        },
        onToggleProfile = viewModel::onToggleProfile,
        onRequestDeleteProfile = viewModel::onRequestDeleteProfile,
        onConfirmDeleteProfile = viewModel::onConfirmDeleteProfile,
        onDismissDeleteProfile = viewModel::onDismissDeleteProfile,
        onUserMessageShown = viewModel::onUserMessageShown,
    )
}

@Composable
fun ProfileEditorRoute(
    viewModel: ProfilesViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor = state.editor
    LaunchedEffect(editor) {
        if (editor == null) onClose()
    }
    editor?.let {
        ProfileEditorScreen(
            state = it,
            rules = state.availableRules,
            onChange = viewModel::onEditorChange,
            onSave = viewModel::onSaveProfile,
            onRequestClose = viewModel::onDismissEditor,
            onConfirmDiscard = viewModel::onConfirmDiscardEditor,
            onCancelDiscard = viewModel::onCancelDiscardEditor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    state: ProfilesUiState,
    onAddProfile: () -> Unit,
    onEditProfile: (String) -> Unit,
    onToggleProfile: (String) -> Unit,
    onRequestDeleteProfile: (String) -> Unit,
    onConfirmDeleteProfile: () -> Unit,
    onDismissDeleteProfile: () -> Unit,
    onUserMessageShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val userMessage = state.userMessage?.asString()
    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbarHostState.showSnackbar(it)
            onUserMessageShown()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_profiles)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddProfile,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.profiles_add)) },
            )
        },
    ) { padding ->
        when {
            state.isLoading ->
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            state.errorMessage != null ->
                Text(
                    state.errorMessage.asString(),
                    modifier = Modifier.padding(padding).padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            state.profiles.isEmpty() ->
                MaxWidthContent(Modifier.fillMaxSize().padding(padding)) {
                    EmptyState(
                        icon = Icons.Default.Add,
                        title = stringResource(R.string.profiles_empty_title),
                        body = stringResource(R.string.profiles_empty),
                        actionLabel = stringResource(R.string.profiles_add),
                        onAction = onAddProfile,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            else ->
                MaxWidthContent(Modifier.fillMaxSize().padding(padding)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.profiles, key = ProfileListItem::id) { profile ->
                            ProfileCard(profile, onEditProfile, onToggleProfile, onRequestDeleteProfile)
                        }
                    }
                }
        }
    }

    state.pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = onDismissDeleteProfile,
            title = { Text(stringResource(R.string.profiles_delete_title)) },
            text = { Text(stringResource(R.string.profiles_delete_message, profile.name)) },
            confirmButton = {
                TextButton(onClick = onConfirmDeleteProfile) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteProfile) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: ProfileListItem,
    onEdit: (String) -> Unit,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val toggleDescription = stringResource(R.string.profiles_toggle, profile.name)
    var menuExpanded by remember { mutableStateOf(false) }
    Card(onClick = { onEdit(profile.id) }, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    pluralStringResource(R.plurals.profiles_rule_count, profile.memberCount, profile.memberCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                StatusPill(
                    text =
                        stringResource(
                            when {
                                profile.isActive -> R.string.profiles_active
                                profile.isPartial -> R.string.profiles_partial
                                else -> R.string.profiles_inactive
                            },
                        ),
                    positive = profile.isActive,
                )
                if (profile.hasDuplicateName) {
                    Text(
                        stringResource(R.string.profiles_duplicate_name),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Switch(
                checked = profile.isActive,
                onCheckedChange = { onToggle(profile.id) },
                modifier =
                    Modifier.semantics {
                        contentDescription = toggleDescription
                    },
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profiles_delete)) },
                        onClick = {
                            menuExpanded = false
                            onDelete(profile.id)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    state: ProfileEditorState,
    rules: List<ProfileRuleOption>,
    onChange: (ProfileEditorState) -> Unit,
    onSave: () -> Unit,
    onRequestClose: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onCancelDiscard: () -> Unit,
) {
    BackHandler(onBack = onRequestClose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isEditing) R.string.profiles_edit else R.string.profiles_new,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onRequestClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = state.canSave) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        MaxWidthContent(Modifier.fillMaxSize().padding(padding)) {
            ProfileEditorContent(
                state = state,
                rules = rules,
                onChange = onChange,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    if (state.showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelDiscard,
            title = { Text(stringResource(R.string.discard_changes_title)) },
            text = { Text(stringResource(R.string.discard_changes_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmDiscard) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelDiscard) { Text(stringResource(R.string.keep_editing)) }
            },
        )
    }
}

@Composable
private fun ProfileEditorContent(
    state: ProfileEditorState,
    rules: List<ProfileRuleOption>,
    onChange: (ProfileEditorState) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            val nameError =
                when {
                    state.name.isBlank() -> stringResource(R.string.validation_profile_name)
                    state.name.length > MAX_PROFILE_NAME_CHARS ->
                        stringResource(R.string.validation_name_too_long, MAX_PROFILE_NAME_CHARS)
                    state.nameConflict -> stringResource(R.string.validation_profile_duplicate)
                    else -> null
                }
            OutlinedTextField(
                value = state.name,
                onValueChange = { onChange(state.copy(name = it.take(MAX_PROFILE_NAME_CHARS))) },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                isError = nameError != null,
                supportingText = { nameError?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.profiles_choose_rules),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (rules.isEmpty()) {
            item { Text(stringResource(R.string.profiles_no_rules)) }
        } else {
            items(rules, key = ProfileRuleOption::id) { rule ->
                ProfileRuleSelectionRow(
                    rule = rule,
                    selected = rule.id in state.selectedRuleIds,
                    onToggle = {
                        val selected = rule.id in state.selectedRuleIds
                        onChange(
                            state.copy(
                                selectedRuleIds =
                                    if (selected) {
                                        state.selectedRuleIds - rule.id
                                    } else {
                                        state.selectedRuleIds + rule.id
                                    },
                            ),
                        )
                    },
                )
            }
        }
        if (state.selectedRuleIds.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.validation_profile_rule),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ProfileRuleSelectionRow(
    rule: ProfileRuleOption,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val ruleName = if (rule.name.isBlank()) stringResource(R.string.rule_untitled) else rule.name
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Text(ruleName)
    }
}
