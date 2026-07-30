package com.alarmcontrol.data.security

import com.alarmcontrol.core.privacy.ClearedDataCounts
import com.alarmcontrol.data.db.TransactionRunner
import com.alarmcontrol.data.db.dao.NotificationEventDao
import com.alarmcontrol.data.db.dao.PendingNotificationActionDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes every encrypted notification payload before removing its non-exportable key.
 *
 * Callers must hold [NotificationContentAccessGuard] so no writer can recreate content between
 * deletion and a related policy commit.
 */
@Singleton
internal class StoredNotificationContentCleaner
    @Inject
    constructor(
        private val transactionRunner: TransactionRunner,
        private val eventDao: NotificationEventDao,
        private val pendingActionDao: PendingNotificationActionDao,
        private val contentCipher: NotificationContentCipher,
    ) {
        suspend fun clear(): ClearedDataCounts {
            val count =
                transactionRunner.run {
                    eventDao.deleteAllEncryptedContents() + pendingActionDao.deleteAllContents()
                }
            contentCipher.deleteKey()
            return ClearedDataCounts(encryptedContents = count)
        }

        suspend fun clearForPackage(packageName: String): ClearedDataCounts {
            val count =
                transactionRunner.run {
                    eventDao.deleteEncryptedContentsForPackage(packageName) +
                        pendingActionDao.deleteContentsForPackage(packageName)
                }
            return ClearedDataCounts(encryptedContents = count)
        }

        suspend fun clearOutsideRetention(
            cutoffMillis: Long,
            nowMillis: Long,
        ): ClearedDataCounts {
            val count =
                transactionRunner.run {
                    eventDao.deleteEncryptedContentsOlderThan(cutoffMillis, nowMillis) +
                        pendingActionDao.deleteContentsOutsideRetention(cutoffMillis, nowMillis)
                }
            return ClearedDataCounts(encryptedContents = count)
        }
    }
