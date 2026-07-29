package com.alarmcontrol.ui.settings

/**
 * Holds one in-memory SAF backup operation across Activity recreation without persisting secrets
 * into saved state. A process death intentionally loses the operation so the result fails closed.
 */
internal class PendingBackupSelectionStore {
    private val lock = Any()
    private var pending: PendingBackupSelection? = null

    fun prepareExport(
        passphrase: CharArray?,
        includeLearningFeedback: Boolean,
    ) {
        replace(PendingBackupSelection.Export(passphrase.nonEmptyOrNull(), includeLearningFeedback))
    }

    fun prepareImport(passphrase: CharArray?) {
        replace(PendingBackupSelection.Import(passphrase.nonEmptyOrNull()))
    }

    fun takeExport(): PendingBackupSelection.Export? =
        synchronized(lock) {
            (pending as? PendingBackupSelection.Export)?.also { pending = null }
        }

    fun takeImport(): PendingBackupSelection.Import? =
        synchronized(lock) {
            (pending as? PendingBackupSelection.Import)?.also { pending = null }
        }

    fun clear() {
        synchronized(lock) {
            pending?.clear()
            pending = null
        }
    }

    private fun replace(selection: PendingBackupSelection) {
        synchronized(lock) {
            pending?.clear()
            pending = selection
        }
    }
}

internal sealed class PendingBackupSelection {
    abstract val passphrase: CharArray?

    class Export(
        override val passphrase: CharArray?,
        val includeLearningFeedback: Boolean,
    ) : PendingBackupSelection()

    class Import(
        override val passphrase: CharArray?,
    ) : PendingBackupSelection()

    fun clear() {
        passphrase?.fill('\u0000')
    }
}

private fun CharArray?.nonEmptyOrNull(): CharArray? {
    if (this == null) return null
    if (isNotEmpty()) return this
    fill('\u0000')
    return null
}
