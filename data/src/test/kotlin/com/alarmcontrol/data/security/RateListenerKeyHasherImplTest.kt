package com.alarmcontrol.data.security

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.RateListenerKeyHashFailure
import com.alarmcontrol.core.filtering.RateListenerKeyHashResult
import com.alarmcontrol.data.db.AppDatabase
import com.alarmcontrol.data.db.entity.ActiveNotificationRateOccurrenceEntity
import com.alarmcontrol.data.db.entity.NotificationRateOccurrenceHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.UnrecoverableKeyException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RateListenerKeyHasherImplTest {
    private lateinit var database: AppDatabase
    private lateinit var provider: FakeRateListenerKeyHmacProvider
    private lateinit var hasher: RateListenerKeyHasherImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        provider = FakeRateListenerKeyHmacProvider()
        hasher =
            RateListenerKeyHasherImpl(
                provider,
                database.notificationRateStateDao(),
                Clock.fixed(Instant.ofEpochMilli(NOW_MILLIS), ZoneOffset.UTC),
                Dispatchers.Unconfined,
            )
    }

    @After
    fun tearDown() {
        if (database.isOpen) {
            database.close()
        }
    }

    @Test
    fun firstHashCreatesKeyAndReturnsUrlSafeDigest() =
        runTest {
            val result = hasher.hash("listener|key") as RateListenerKeyHashResult.Success

            assertTrue(provider.hasKey())
            assertEquals(1, provider.createCount)
            assertEquals(
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                result.digest.value,
            )
            assertEquals(
                NOW_MILLIS + MAX_RATE_WINDOW_MILLIS + 1,
                result.recoveryIncompleteUntilMillis,
            )
            assertEquals(
                result.recoveryIncompleteUntilMillis,
                database.notificationRateStateDao().rateState()?.incompleteUntilMillis,
            )
        }

    @Test
    fun missingKeyWithActiveMappingRecoversAndMarksHistoryIncomplete() =
        runTest {
            insertActive()

            val result = hasher.hash("listener|key") as RateListenerKeyHashResult.Success

            assertEquals(
                NOW_MILLIS + MAX_RATE_WINDOW_MILLIS + 1,
                result.recoveryIncompleteUntilMillis,
            )
            assertEquals(0, database.notificationRateStateDao().activeOccurrenceCount())
            assertEquals(0, database.notificationRateStateDao().historyCount())
            assertEquals(
                result.recoveryIncompleteUntilMillis,
                database.notificationRateStateDao().rateState()?.incompleteUntilMillis,
            )
            assertEquals(1, provider.createCount)
        }

    @Test
    fun invalidatedKeyRecoversOnceAndReportsIncompleteWindow() =
        runTest {
            provider.createKey()
            insertActive()
            provider.hmacFailures.add(KeyPermanentlyInvalidatedException())

            val result = hasher.hash("listener|key") as RateListenerKeyHashResult.Success

            assertEquals(
                NOW_MILLIS + MAX_RATE_WINDOW_MILLIS + 1,
                result.recoveryIncompleteUntilMillis,
            )
            assertEquals(0, database.notificationRateStateDao().activeOccurrenceCount())
            assertEquals(2, provider.createCount)
            assertEquals(1, provider.deleteCount)
        }

    @Test
    fun recoveryClearsHistoryAndAnchorsAfterNewestStoredFutureTimestamp() =
        runTest {
            val futurePostedAtMillis = NOW_MILLIS + 2 * MAX_RATE_WINDOW_MILLIS
            insertActive()
            database.notificationRateStateDao().upsertHistoryOccurrence(
                NotificationRateOccurrenceHistoryEntity(
                    occurrenceId = TEST_OCCURRENCE_ID,
                    packageName = "com.example",
                    channelId = null,
                    latestPostedAtMillis = futurePostedAtMillis,
                ),
            )

            val result = hasher.hash("listener|key") as RateListenerKeyHashResult.Success

            assertEquals(
                futurePostedAtMillis + MAX_RATE_WINDOW_MILLIS + 1,
                result.recoveryIncompleteUntilMillis,
            )
            assertEquals(0, database.notificationRateStateDao().activeOccurrenceCount())
            assertEquals(0, database.notificationRateStateDao().historyCount())
            assertEquals(
                result.recoveryIncompleteUntilMillis,
                database.notificationRateStateDao().rateState()?.incompleteUntilMillis,
            )
        }

    @Test
    fun repeatedFailureAfterRecoveryRemainsTypedUnknown() =
        runTest {
            provider.createKey()
            insertActive()
            provider.hmacFailures.add(KeyPermanentlyInvalidatedException())
            provider.hmacFailures.add(UnrecoverableKeyException("still unavailable"))

            val result = hasher.hash("listener|key")

            assertEquals(
                RateListenerKeyHashResult.Unavailable(RateListenerKeyHashFailure.KEY_LOST),
                result,
            )
            assertEquals(0, database.notificationRateStateDao().activeOccurrenceCount())
            assertEquals(
                NOW_MILLIS + MAX_RATE_WINDOW_MILLIS + 1,
                database.notificationRateStateDao().rateState()?.incompleteUntilMillis,
            )
        }

    @Test
    fun emptyListenerKeyFailsBeforeKeystoreAccess() =
        runTest {
            assertEquals(
                RateListenerKeyHashResult.Unavailable(
                    RateListenerKeyHashFailure.EMPTY_LISTENER_KEY,
                ),
                hasher.hash(""),
            )
            assertEquals(0, provider.createCount)
        }

    @Test
    fun keystoreLookupAndCreationFailuresAreTypedUnavailable() =
        runTest {
            provider.hasKeyFailure = IllegalStateException("keystore unavailable")
            assertEquals(
                RateListenerKeyHashResult.Unavailable(
                    RateListenerKeyHashFailure.KEYSTORE_UNAVAILABLE,
                ),
                hasher.hash("listener|key"),
            )

            provider.hasKeyFailure = null
            provider.createFailure = IllegalStateException("cannot create")
            assertEquals(
                RateListenerKeyHashResult.Unavailable(
                    RateListenerKeyHashFailure.KEYSTORE_UNAVAILABLE,
                ),
                hasher.hash("listener|key"),
            )
        }

    @Test
    fun databaseFailureDuringRecoveryIsTypedUnavailable() =
        runTest {
            database.openHelper.writableDatabase.execSQL("DROP TABLE notification_rate_state")

            assertEquals(
                RateListenerKeyHashResult.Unavailable(
                    RateListenerKeyHashFailure.PERSISTENCE_UNAVAILABLE,
                ),
                hasher.hash("listener|key"),
            )
        }

    @Test
    fun genericHashFailureDoesNotResetValidKey() =
        runTest {
            provider.createKey()
            provider.hmacFailures.add(IllegalStateException("hash failed"))

            assertEquals(
                RateListenerKeyHashResult.Unavailable(RateListenerKeyHashFailure.HASH_FAILED),
                hasher.hash("listener|key"),
            )
            assertEquals(0, provider.deleteCount)
            assertTrue(provider.hmacFailures.isEmpty())
        }

    private suspend fun insertActive() {
        database.notificationRateStateDao().upsertActiveOccurrence(
            ActiveNotificationRateOccurrenceEntity(
                listenerKeyHmac = "a".repeat(43),
                occurrenceId = TEST_OCCURRENCE_ID,
                packageName = "com.example",
                lastPostedAtMillis = 100,
            ),
        )
    }

    private companion object {
        const val NOW_MILLIS = 1_000L
        const val TEST_OCCURRENCE_ID = "00000000-0000-4000-8000-000000000001"
    }
}

private class FakeRateListenerKeyHmacProvider : RateListenerKeyHmacProvider {
    private var keyExists = false
    val hmacFailures = ArrayDeque<Exception>()
    var hasKeyFailure: Exception? = null
    var createFailure: Exception? = null
    var createCount = 0
        private set
    var deleteCount = 0
        private set

    override fun hasKey(): Boolean {
        hasKeyFailure?.let { throw it }
        return keyExists
    }

    override fun createKey() {
        createFailure?.let { throw it }
        keyExists = true
        createCount++
    }

    override fun hmac(input: ByteArray): ByteArray {
        if (hmacFailures.isNotEmpty()) throw hmacFailures.removeFirst()
        return ByteArray(32) { it.toByte() }
    }

    override fun deleteKey() {
        keyExists = false
        deleteCount++
    }
}
