package com.alarmcontrol.ui.profiles

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.alarmcontrol.ui.theme.AlarmControlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class ProfilesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `empty state explains how profiles are used`() {
        setContent(ProfilesUiState(isLoading = false))

        composeRule.onNodeWithText("No profiles yet.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `add profile fab has an accessible name`() {
        setContent(ProfilesUiState(isLoading = false))

        composeRule
            .onNodeWithContentDescription("Add profile")
            .assertHasClickAction()
    }

    @Test
    fun `profile card renders status and hoists a toggle`() {
        var toggled = false
        var edited = false
        setContent(
            ProfilesUiState(
                isLoading = false,
                profiles = listOf(ProfileListItem("7", "Focus", memberCount = 2, enabledCount = 1)),
            ),
            onToggle = { toggled = true },
            onEdit = { edited = true },
        )

        composeRule.onNodeWithText("Focus").assertIsDisplayed()
        composeRule.onNodeWithText("Partially active").assertIsDisplayed()
        composeRule.onNodeWithText("2 rules").assertIsDisplayed()
        composeRule.onNodeWithText("Focus").performClick()
        composeRule.onNodeWithContentDescription("Toggle Focus profile").performClick()
        assertTrue(edited)
        assertTrue(toggled)
    }

    @Test
    fun `duplicate profile name shows a repair message`() {
        setContent(
            ProfilesUiState(
                isLoading = false,
                profiles =
                    listOf(
                        ProfileListItem(
                            id = "7",
                            name = "Focus",
                            memberCount = 1,
                            enabledCount = 1,
                            hasDuplicateName = true,
                        ),
                    ),
            ),
        )

        composeRule.onNodeWithText("Duplicate name", substring = true).assertIsDisplayed()
    }

    @Test
    fun `editor validates then saves a selected rule`() {
        val editor = mutableStateOf(ProfileEditorState())
        var saved = false
        composeRule.setContent {
            AlarmControlTheme(dynamicColor = false) {
                ProfileEditorScreen(
                    state = editor.value,
                    rules = listOf(ProfileRuleOption("1", "Mute promos", enabled = true)),
                    onChange = { editor.value = it },
                    onSave = { saved = true },
                    onRequestClose = {},
                    onConfirmDiscard = {},
                    onCancelDiscard = {},
                )
            }
        }

        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithText("Name").performTextInput("Focus")
        composeRule.onNodeWithText("Mute promos").performClick()
        composeRule.onNodeWithText("Save").performClick()
        assertTrue(saved)
    }

    @Test
    fun `delete requires confirmation and hoists the confirmed action`() {
        val profile = ProfileListItem("7", "Focus", memberCount = 2, enabledCount = 1)
        val state = mutableStateOf(ProfilesUiState(isLoading = false, profiles = listOf(profile)))
        var requestedId: String? = null
        var confirmed = false
        composeRule.setContent {
            AlarmControlTheme(dynamicColor = false) {
                ProfilesScreen(
                    state = state.value,
                    onAddProfile = {},
                    onEditProfile = {},
                    onToggleProfile = {},
                    onRequestDeleteProfile = {
                        requestedId = it
                        state.value = state.value.copy(pendingDelete = profile)
                    },
                    onConfirmDeleteProfile = { confirmed = true },
                    onDismissDeleteProfile = {},
                    onUserMessageShown = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Delete profile").performClick()
        assertEquals("7", requestedId)
        composeRule.onNodeWithText("Delete “Focus”? Its rules will be kept.").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        assertTrue(confirmed)
    }

    private fun setContent(
        state: ProfilesUiState,
        onToggle: (String) -> Unit = {},
        onEdit: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            AlarmControlTheme(dynamicColor = false) {
                ProfilesScreen(
                    state = state,
                    onAddProfile = {},
                    onEditProfile = onEdit,
                    onToggleProfile = onToggle,
                    onRequestDeleteProfile = {},
                    onConfirmDeleteProfile = {},
                    onDismissDeleteProfile = {},
                    onUserMessageShown = {},
                )
            }
        }
    }
}
