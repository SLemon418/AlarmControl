package com.alarmcontrol.data.security

import com.alarmcontrol.data.db.dao.NotificationRateStateDao
import java.time.Clock
import javax.inject.Inject

internal interface RateOccurrenceDataCleaner {
    /** Called inside the app-wide Room clear transaction and rate-occurrence lifecycle gate. */
    suspend fun clearDatabaseState()

    /** Called only after the database clear commits, while the lifecycle gate remains held. */
    fun deleteHmacKey()
}

internal class RoomRateOccurrenceDataCleaner
    @Inject
    constructor(
        private val dao: NotificationRateStateDao,
        private val hmacProvider: RateListenerKeyHmacProvider,
        private val clock: Clock,
    ) : RateOccurrenceDataCleaner {
        override suspend fun clearDatabaseState() {
            dao.clearAllRateData(clock.millis())
        }

        override fun deleteHmacKey() {
            hmacProvider.deleteKey()
        }
    }
