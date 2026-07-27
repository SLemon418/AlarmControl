package com.alarmcontrol.ui.profiles

import com.alarmcontrol.core.profile.MAX_PROFILE_RULE_IDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileEditorDraftCodecTest {
    @Test
    fun `round trips profile editor state deterministically`() {
        val original =
            ProfileEditorState(
                id = "7",
                name = "Night focus",
                selectedRuleIds = linkedSetOf("3", "1", "2"),
                nameConflict = true,
                hasUnsavedChanges = true,
                showDiscardConfirmation = true,
            )

        val encoded = requireNotNull(ProfileEditorDraftCodec.encode(original))
        val restored = requireNotNull(ProfileEditorDraftCodec.decode(encoded))

        assertEquals(original, restored)
        assertEquals(listOf("1", "2", "3"), encoded.drop(7))
    }

    @Test
    fun `rejects malformed and oversized profile state`() {
        assertNull(ProfileEditorDraftCodec.decode(arrayListOf("2")))
        assertNull(
            ProfileEditorDraftCodec.encode(
                ProfileEditorState(
                    selectedRuleIds = List(MAX_PROFILE_RULE_IDS + 1) { it.toString() }.toSet(),
                ),
            ),
        )
    }
}
