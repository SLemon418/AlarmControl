package com.alarmcontrol.ui.settings

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingBackupSelectionStoreTest {
    @Test
    fun `clearing the store wipes its pending passphrase`() {
        val passphrase = "private-passphrase".toCharArray()
        val store = PendingBackupSelectionStore()
        store.prepareExport(passphrase, includeLearningFeedback = true)

        store.clear()

        assertTrue(passphrase.all { it == '\u0000' })
        assertNull(store.takeExport())
    }

    @Test
    fun `preparing another operation wipes the replaced passphrase`() {
        val replaced = "first-passphrase".toCharArray()
        val retained = "second-passphrase".toCharArray()
        val store = PendingBackupSelectionStore()
        store.prepareExport(replaced, includeLearningFeedback = true)

        store.prepareImport(retained)

        assertTrue(replaced.all { it == '\u0000' })
        assertNull(store.takeExport())
        store.takeImport()?.clear()
        assertTrue(retained.all { it == '\u0000' })
    }

    @Test
    fun `taking a matching operation transfers ownership until the caller clears it`() {
        val passphrase = "private-passphrase".toCharArray()
        val store = PendingBackupSelectionStore()
        store.prepareExport(passphrase, includeLearningFeedback = true)

        val pending = store.takeExport()

        assertTrue(passphrase.any { it != '\u0000' })
        assertTrue(pending?.includeLearningFeedback == true)
        assertNull(store.takeExport())
        pending?.clear()
        assertTrue(passphrase.all { it == '\u0000' })
    }
}
