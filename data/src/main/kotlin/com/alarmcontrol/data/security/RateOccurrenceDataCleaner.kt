package com.alarmcontrol.data.security

import com.alarmcontrol.data.db.dao.NotificationRateStateDao
import javax.inject.Inject

internal interface RateOccurrenceDataCleaner {
    /** Called inside the app-wide Room clear transaction and rate-occurrence lifecycle gate. */
    suspend fun clearDatabaseState(resetAtMillis: Long)

    /** Called only after the database clear commits, while the lifecycle gate remains held. */
    fun deleteHmacKey()
}

internal class RoomRateOccurrenceDataCleaner
    @Inject
    constructor(
        private val dao: NotificationRateStateDao,
        private val hmacProvider: RateListenerKeyHmacProvider,
    ) : RateOccurrenceDataCleaner {
        override suspend fun clearDatabaseState(resetAtMillis: Long) {
            dao.clearAllRateData(resetAtMillis)
        }

        override fun deleteHmacKey() {
            hmacProvider.deleteKey()
        }
    }
