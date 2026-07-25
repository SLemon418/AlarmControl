package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Optional AES-GCM payload for one event. Plain title/body never enter Room; the non-exportable key
 * remains in Android Keystore and this row cascades with its metadata parent.
 */
@Entity(
    tableName = "encrypted_notification_contents",
    primaryKeys = ["event_id"],
    foreignKeys = [
        ForeignKey(
            entity = NotificationEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("created_at_millis")],
)
data class EncryptedNotificationContentEntity(
    @ColumnInfo(name = "event_id") val eventId: Long = 0,
    @ColumnInfo(name = "format_version") val formatVersion: Int,
    @ColumnInfo(name = "aad_id") val aadId: String,
    @ColumnInfo(name = "nonce") val nonce: ByteArray,
    @ColumnInfo(name = "ciphertext") val ciphertext: ByteArray,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is EncryptedNotificationContentEntity &&
            eventId == other.eventId &&
            formatVersion == other.formatVersion &&
            aadId == other.aadId &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext) &&
            createdAtMillis == other.createdAtMillis

    override fun hashCode(): Int {
        var result = eventId.hashCode()
        result = 31 * result + formatVersion
        result = 31 * result + aadId.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return 31 * result + createdAtMillis.hashCode()
    }
}
