package com.alarmcontrol.core.backup

import com.alarmcontrol.core.result.DataResult

/** Maximum UTF-8 size of a portable backup accepted by both export and import. */
const val MAX_BACKUP_FILE_BYTES = 32 * 1_024 * 1_024

/**
 * Exports and restores local rules, profiles, and daily-history data (CLAUDE.md §3). The interface
 * lives in `:core`; the `:data` implementation serializes a structured string and reads/writes Room.
 * The `:app` caller handles file I/O through the Storage Access Framework, keeping this contract
 * free of Android storage types and fully unit-testable.
 */
interface BackupRepository {
    /**
     * Serializes rules and history. A non-empty [passphrase] wraps the JSON in a local AES-GCM
     * envelope; `null` keeps backward-compatible structured JSON. Every successful result is at
     * most [MAX_BACKUP_FILE_BYTES] when encoded as UTF-8, so the app's bounded importer can read it.
     */
    suspend fun export(
        passphrase: CharArray? = null,
        includeLearningFeedback: Boolean = false,
    ): String

    /** Decrypts, parses, and validates a backup without mutating any local state. */
    suspend fun preview(
        serialized: String,
        passphrase: CharArray? = null,
    ): DataResult<BackupPreview>

    /**
     * Parses [serialized] and restores selected Room-backed sections in one transaction: rules are
     * assigned fresh local ids, historical references are remapped, and MERGE preserves an existing
     * local daily rollup when the backup contains the same day. Settings are finalized separately
     * after the Room commit because Room and DataStore cannot share a transaction.
     *
     * A pre-commit or settings-only failure returns [DataResult.Failure]. If Room commits but
     * settings finalization fails, the committed data is preserved and [DataResult.Success] reports
     * [BackupSummary.settingsReviewRequired] while side-effecting settings remain disabled.
     * Malformed input also fails as [DataResult.Failure] rather than throwing.
     */
    suspend fun restore(
        serialized: String,
        passphrase: CharArray? = null,
        options: RestoreOptions = RestoreOptions(mode = RestoreMode.REPLACE),
    ): DataResult<BackupSummary>
}
