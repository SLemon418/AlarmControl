package com.alarmcontrol.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.alarmcontrol.core.filtering.MAX_RATE_WINDOW_MILLIS
import com.alarmcontrol.core.filtering.RateListenerKeyDigest
import com.alarmcontrol.core.filtering.RateOccurrenceId
import com.alarmcontrol.core.filtering.RateOccurrenceIncompleteReason
import com.alarmcontrol.core.filtering.RateOccurrencePersistenceResult
import com.alarmcontrol.core.filtering.RateOccurrenceSeed
import com.alarmcontrol.data.db.AppDatabase
import com.alarmcontrol.data.db.entity.ActiveNotificationRateOccurrenceEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity
import com.alarmcontrol.data.db.entity.NotificationRateStateEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RateOccurrenceRepositoryImplTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RateOccurrenceRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            RateOccurrenceRepositoryImpl(
                database.notificationRateStateDao(),
                Dispatchers.Unconfined,
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun recordPostAtomicallyReusesOccurrenceAndSeedIgnoresDecisionLog() =
        runTest {
            insertLegacyDecisionRow()

            val first = recordPost('a', "occurrence-a", postedAtMillis = 100)
            val update =
                recordPost(
                    digestCharacter = 'a',
                    candidateOccurrenceId = "must-not-be-used",
                    postedAtMillis = 200,
                    channelId = "updated",
                )
            val seed =
                repository.loadSeed(
                    sinceMillis = 0,
                    nowMillis = 200,
                ) as RateOccurrenceSeed.Available

            assertTrue(first.accepted)
            assertNull(first.incompleteUntilMillis)
            assertEquals(occurrence("occurrence-a"), update.activeOccurrence.occurrenceId)
            assertEquals("updated", update.activeOccurrence.channelId)
            assertEquals(
                listOf(occurrence("occurrence-a")),
                seed.occurrences.map { it.occurrenceId },
            )
            assertEquals(200L, seed.occurrences.single().postedAtMillis)
        }

    @Test
    fun sameMillisecondDistinctOccurrencesAreBothRetained() =
        runTest {
            recordPost('a', "occurrence-a", postedAtMillis = 100)
            recordPost('b', "occurrence-b", postedAtMillis = 100)

            val seed = repository.loadSeed(0, 100) as RateOccurrenceSeed.Available

            assertEquals(
                setOf(occurrence("occurrence-a"), occurrence("occurrence-b")),
                seed.occurrences.map { it.occurrenceId }.toSet(),
            )
        }

    @Test
    fun futurePersistedOccurrenceKeepsSeedIncompleteUntilClockCatchesUp() =
        runTest {
            recordPost('a', "occurrence-a", postedAtMillis = 150)
            recordPost('b', "occurrence-b", postedAtMillis = 200)

            assertEquals(
                RateOccurrenceSeed.Incomplete(
                    RateOccurrenceIncompleteReason.FUTURE_OCCURRENCE,
                    retryAtMillis = 200,
                ),
                repository.loadSeed(sinceMillis = 0, nowMillis = 100),
            )
            assertEquals(
                RateOccurrenceSeed.Incomplete(
                    RateOccurrenceIncompleteReason.FUTURE_OCCURRENCE,
                    retryAtMillis = 200,
                ),
                repository.loadSeed(sinceMillis = 0, nowMillis = 150),
            )

            val recovered =
                repository.loadSeed(
                    sinceMillis = 0,
                    nowMillis = 200,
                ) as RateOccurrenceSeed.Available
            assertEquals(
                setOf(occurrence("occurrence-a"), occurrence("occurrence-b")),
                recovered.occurrences.map { it.occurrenceId }.toSet(),
            )
        }

    @Test
    fun stalePostCannotMoveActiveOrHistoryBackward() =
        runTest {
            recordPost('a', "occurrence-a", postedAtMillis = 200, channelId = "new")

            val stale =
                recordPost(
                    digestCharacter = 'a',
                    candidateOccurrenceId = "ignored",
                    postedAtMillis = 100,
                    channelId = "old",
                )
            val history =
                database
                    .notificationRateStateDao()
                    .historyOccurrence(occurrence("occurrence-a").value)

            assertFalse(stale.accepted)
            assertEquals(
                200L + MAX_RATE_WINDOW_MILLIS + 1,
                stale.incompleteUntilMillis,
            )
            assertEquals(200L, stale.activeOccurrence.lastPostedAtMillis)
            assertEquals("new", stale.activeOccurrence.channelId)
            assertEquals(200L, history?.latestPostedAtMillis)
            assertEquals("new", history?.channelId)
            val partial = repository.loadSeed(0, 200) as RateOccurrenceSeed.Available
            assertEquals(201L, partial.coverageStartMillis)
            assertTrue(partial.occurrences.isEmpty())
        }

    @Test
    fun missingRemovalGetsNewOccurrenceAfterMaximumWindow() =
        runTest {
            recordPost('a', "occurrence-old", postedAtMillis = 0)
            val boundary =
                recordPost(
                    digestCharacter = 'a',
                    candidateOccurrenceId = "still-old",
                    postedAtMillis = MAX_RATE_WINDOW_MILLIS,
                )
            val repost =
                recordPost(
                    digestCharacter = 'a',
                    candidateOccurrenceId = "occurrence-new",
                    postedAtMillis = 2 * MAX_RATE_WINDOW_MILLIS + 1,
                )

            assertEquals(occurrence("occurrence-old"), boundary.activeOccurrence.occurrenceId)
            assertEquals(occurrence("occurrence-new"), repost.activeOccurrence.occurrenceId)
            assertFalse(
                repository
                    .deleteActiveOccurrence(
                        listenerKeyDigest = digest('a'),
                        occurrenceId = boundary.activeOccurrence.occurrenceId,
                        removedPostTimeMillis = boundary.activeOccurrence.lastPostedAtMillis,
                    ).successValue(),
            )
            assertEquals(
                repost.activeOccurrence,
                repository.activeOccurrence(digest('a')).successValue(),
            )
            assertEquals(
                setOf(occurrence("occurrence-old"), occurrence("occurrence-new")),
                (
                    repository.loadSeed(
                        sinceMillis = 0,
                        nowMillis = 2 * MAX_RATE_WINDOW_MILLIS + 1,
                    ) as RateOccurrenceSeed.Available
                ).occurrences.map { it.occurrenceId }.toSet(),
            )
        }

    @Test
    fun conditionalRemoveCannotDeleteNewerRepostMapping() =
        runTest {
            val old = recordPost('a', "occurrence-old", postedAtMillis = 100).activeOccurrence
            assertEquals(
                old,
                repository.activeOccurrence(digest('a')).successValue(),
            )
            assertTrue(
                repository
                    .deleteActiveOccurrence(
                        listenerKeyDigest = digest('a'),
                        occurrenceId = old.occurrenceId,
                        removedPostTimeMillis = old.lastPostedAtMillis,
                    ).successValue(),
            )
            val repost = recordPost('a', "occurrence-new", postedAtMillis = 101).activeOccurrence

            assertFalse(
                repository
                    .deleteActiveOccurrence(
                        listenerKeyDigest = digest('a'),
                        occurrenceId = old.occurrenceId,
                        removedPostTimeMillis = old.lastPostedAtMillis,
                    ).successValue(),
            )
            assertEquals(
                repost,
                repository.activeOccurrence(digest('a')).successValue(),
            )
        }

    @Test
    fun staleRemoveCannotDeleteNewerUpdateOfTheSameOccurrence() =
        runTest {
            val first = recordPost('a', "occurrence-a", postedAtMillis = 100).activeOccurrence
            val updated =
                recordPost(
                    digestCharacter = 'a',
                    candidateOccurrenceId = "ignored",
                    postedAtMillis = 200,
                ).activeOccurrence

            assertEquals(first.occurrenceId, updated.occurrenceId)
            assertFalse(
                repository
                    .deleteActiveOccurrence(
                        listenerKeyDigest = digest('a'),
                        occurrenceId = first.occurrenceId,
                        removedPostTimeMillis = first.lastPostedAtMillis,
                    ).successValue(),
            )
            assertEquals(
                updated,
                repository.activeOccurrence(digest('a')).successValue(),
            )
        }

    @Test
    fun persistedIncompleteWindowReturnsOnlyHistoryAfterItsSafeCoverageBoundary() =
        runTest {
            val gapAnchorMillis = 500L
            database
                .notificationRateStateDao()
                .upsertRateState(
                    NotificationRateStateEntity(
                        incompleteUntilMillis = gapAnchorMillis + MAX_RATE_WINDOW_MILLIS + 1,
                    ),
                )
            recordPost('a', "occurrence-a", postedAtMillis = 900)

            val partial =
                repository.loadSeed(
                    sinceMillis = 0,
                    nowMillis = 1_000,
                ) as RateOccurrenceSeed.Available

            assertEquals(gapAnchorMillis + 1, partial.coverageStartMillis)
            assertEquals(listOf(occurrence("occurrence-a")), partial.occurrences.map { it.occurrenceId })
        }

    @Test
    fun historyOverflowIsCappedAtTenThousandWithIncompleteWindow() =
        runTest {
            insertManyHistory(count = 10_000)
            assertEquals(
                10_000,
                (repository.loadSeed(0, 10_000) as RateOccurrenceSeed.Available).occurrences.size,
            )

            val capped = recordPost('z', "occurrence-10000", postedAtMillis = 10_000)

            val dao = database.notificationRateStateDao()
            assertEquals(10_000, dao.historyCount())
            assertNull(dao.historyOccurrence(occurrence("occurrence-0").value))
            assertEquals(
                MAX_RATE_WINDOW_MILLIS + 1,
                dao.rateState()?.incompleteUntilMillis,
            )
            assertEquals(
                MAX_RATE_WINDOW_MILLIS + 1,
                capped.incompleteUntilMillis,
            )
            val partial = repository.loadSeed(0, 10_000) as RateOccurrenceSeed.Available
            assertEquals(1L, partial.coverageStartMillis)
            assertEquals(10_000, partial.occurrences.size)
        }

    @Test
    fun seedDetectsPreexistingHistoryAboveTheDurableLimit() =
        runTest {
            insertManyHistory(count = 10_000)
            insertHistory(index = 10_000)

            assertEquals(
                RateOccurrenceSeed.Incomplete(
                    RateOccurrenceIncompleteReason.HISTORY_LIMIT_EXCEEDED,
                    retryAtMillis = null,
                ),
                repository.loadSeed(0, 10_000),
            )
        }

    @Test
    fun activeMappingsAreCappedAndOverflowMarksHistoryIncomplete() =
        runTest {
            insertManyActiveOccurrences(count = 10_000)

            val recorded =
                recordPost(
                    digestCharacter = 'z',
                    candidateOccurrenceId = "occurrence-active-new",
                    postedAtMillis = 10_001,
                )

            val dao = database.notificationRateStateDao()
            assertEquals(10_000, dao.activeOccurrenceCount())
            assertNull(dao.activeOccurrence(listenerDigestForIndex(0)))
            assertEquals(
                recorded.activeOccurrence,
                repository.activeOccurrence(digest('z')).successValue(),
            )
            assertEquals(
                MAX_RATE_WINDOW_MILLIS + 1,
                dao.rateState()?.incompleteUntilMillis,
            )
            assertEquals(
                MAX_RATE_WINDOW_MILLIS + 1,
                recorded.incompleteUntilMillis,
            )
        }

    @Test
    fun purgeDeletesOnlyRowsAndMappingsOutsideMaximumWindow() =
        runTest {
            recordPost('a', "occurrence-old", postedAtMillis = 0)
            val current =
                recordPost(
                    digestCharacter = 'b',
                    candidateOccurrenceId = "occurrence-current",
                    postedAtMillis = MAX_RATE_WINDOW_MILLIS + 1,
                )

            assertEquals(
                1,
                repository.purgeExpiredHistory(MAX_RATE_WINDOW_MILLIS + 1).successValue(),
            )
            assertNull(repository.activeOccurrence(digest('a')).successValue())
            assertEquals(
                current.activeOccurrence,
                repository.activeOccurrence(digest('b')).successValue(),
            )
            assertEquals(
                RateOccurrenceSeed.Incomplete(
                    RateOccurrenceIncompleteReason.FUTURE_OCCURRENCE,
                    retryAtMillis = MAX_RATE_WINDOW_MILLIS + 1,
                ),
                repository.loadSeed(
                    sinceMillis = 0,
                    nowMillis = MAX_RATE_WINDOW_MILLIS,
                ),
            )
            assertTrue(
                repository.loadSeed(
                    sinceMillis = 1,
                    nowMillis = MAX_RATE_WINDOW_MILLIS + 1,
                ) is RateOccurrenceSeed.Available,
            )
        }

    @Test
    fun incompleteWindowExtensionSaturatesAndNeverMovesBackward() =
        runTest {
            val saturated =
                repository
                    .extendIncompleteWindowFrom(Long.MAX_VALUE - MAX_RATE_WINDOW_MILLIS)
                    .successValue()
            val afterEarlierExtension = repository.extendIncompleteWindowFrom(0).successValue()

            assertEquals(Long.MAX_VALUE, saturated)
            assertEquals(Long.MAX_VALUE, afterEarlierExtension)
            assertEquals(
                RateOccurrenceSeed.Incomplete(
                    RateOccurrenceIncompleteReason.PERSISTED_GAP,
                    retryAtMillis = Long.MAX_VALUE,
                ),
                repository.loadSeed(
                    sinceMillis = Long.MAX_VALUE - MAX_RATE_WINDOW_MILLIS,
                    nowMillis = Long.MAX_VALUE - 1,
                ),
            )
            assertTrue(
                repository.loadSeed(
                    sinceMillis = Long.MAX_VALUE - MAX_RATE_WINDOW_MILLIS,
                    nowMillis = Long.MAX_VALUE,
                ) is RateOccurrenceSeed.Incomplete,
            )
        }

    @Test
    fun clearAllRateDataRemovesRowsAndStartsFreshIncompleteWindow() =
        runTest {
            recordPost('a', "occurrence-a", postedAtMillis = 100)
            repository.extendIncompleteWindowFrom(100).successValue()

            database.notificationRateStateDao().clearAllRateData(anchorMillis = 100)

            assertTrue(repository.activeOccurrences().successValue().isEmpty())
            assertEquals(0, database.notificationRateStateDao().historyCount())
            assertEquals(
                100 + MAX_RATE_WINDOW_MILLIS + 1,
                database.notificationRateStateDao().rateState()?.incompleteUntilMillis,
            )
        }

    @Test
    fun activeEntityRejectsRawListenerKey() {
        assertThrows(IllegalArgumentException::class.java) {
            ActiveNotificationRateOccurrenceEntity(
                listenerKeyHmac = "com.example|notification|42",
                occurrenceId = occurrence("occurrence").value,
                packageName = "com.example",
                lastPostedAtMillis = 100,
            )
        }
    }

    @Test
    fun activeEntityRejectsRawListenerKeyAsOccurrenceId() {
        assertThrows(IllegalArgumentException::class.java) {
            ActiveNotificationRateOccurrenceEntity(
                listenerKeyHmac = digest('a').value,
                occurrenceId = "0|com.example|42|null|1000",
                packageName = "com.example",
                lastPostedAtMillis = 100,
            )
        }
    }

    private suspend fun recordPost(
        digestCharacter: Char,
        candidateOccurrenceId: String,
        postedAtMillis: Long,
        channelId: String? = null,
    ) = repository
        .recordPost(
            listenerKeyDigest = digest(digestCharacter),
            candidateOccurrenceId = occurrence(candidateOccurrenceId),
            packageName = "com.example",
            channelId = channelId,
            postedAtMillis = postedAtMillis,
        ).successValue()

    private suspend fun insertLegacyDecisionRow() {
        database.notificationEventDao().insert(
            NotificationEventEntity(
                packageName = "com.legacy",
                category = null,
                postedAtMillis = 150,
                action = StoredRuleAction.KEEP,
                matchedRuleId = null,
                recordedAtMillis = 150,
            ),
        )
    }

    private fun insertManyHistory(count: Int) {
        val sqlite = database.openHelper.writableDatabase
        val statement =
            sqlite.compileStatement(
                "INSERT INTO notification_rate_occurrence_history " +
                    "(occurrence_id, package_name, channel_id, latest_posted_at_millis) " +
                    "VALUES (?, 'com.example', NULL, ?)",
            )
        sqlite.beginTransaction()
        try {
            repeat(count) { index ->
                statement.clearBindings()
                statement.bindString(1, occurrence("occurrence-$index").value)
                statement.bindLong(2, index.toLong())
                statement.executeInsert()
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
    }

    private fun insertHistory(index: Int) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO notification_rate_occurrence_history " +
                "(occurrence_id, package_name, channel_id, latest_posted_at_millis) " +
                "VALUES (?, 'com.example', NULL, ?)",
            arrayOf<Any>(occurrence("occurrence-$index").value, index.toLong()),
        )
    }

    private fun insertManyActiveOccurrences(count: Int) {
        val sqlite = database.openHelper.writableDatabase
        val statement =
            sqlite.compileStatement(
                "INSERT INTO active_notification_rate_occurrences " +
                    "(listener_key_hmac, occurrence_id, package_name, channel_id, " +
                    "last_posted_at_millis) VALUES (?, ?, 'com.example', NULL, ?)",
            )
        sqlite.beginTransaction()
        try {
            repeat(count) { index ->
                statement.clearBindings()
                statement.bindString(1, listenerDigestForIndex(index))
                statement.bindString(2, occurrence("active-occurrence-$index").value)
                statement.bindLong(3, index.toLong())
                statement.executeInsert()
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
    }

    private fun listenerDigestForIndex(index: Int): String =
        index
            .toString(radix = 36)
            .padStart(length = 43, padChar = '_')

    private fun occurrence(label: String): RateOccurrenceId {
        val uuid = UUID.nameUUIDFromBytes(label.toByteArray()).toString().toCharArray()
        uuid[UUID_VERSION_INDEX] = '4'
        uuid[UUID_VARIANT_INDEX] = '8'
        return RateOccurrenceId(uuid.concatToString())
    }

    private fun digest(character: Char): RateListenerKeyDigest = RateListenerKeyDigest(character.toString().repeat(43))

    private companion object {
        const val UUID_VERSION_INDEX = 14
        const val UUID_VARIANT_INDEX = 19
    }
}

private fun <T> RateOccurrencePersistenceResult<T>.successValue(): T {
    assertTrue(this is RateOccurrencePersistenceResult.Success)
    return (this as RateOccurrencePersistenceResult.Success).value
}
