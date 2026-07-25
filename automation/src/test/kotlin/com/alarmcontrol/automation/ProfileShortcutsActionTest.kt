package com.alarmcontrol.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileShortcutsActionTest {
    @Test
    fun `enable action enables filtering`() {
        assertEquals(true, ProfileShortcuts.enabledForAction(ProfileShortcuts.ACTION_ENABLE_FILTERING))
    }

    @Test
    fun `pause action disables filtering`() {
        assertEquals(false, ProfileShortcuts.enabledForAction(ProfileShortcuts.ACTION_PAUSE_FILTERING))
    }

    @Test
    fun `unknown or null action is ignored`() {
        assertNull(ProfileShortcuts.enabledForAction("com.other.ACTION"))
        assertNull(ProfileShortcuts.enabledForAction(null))
    }
}
