package com.alarmcontrol.core.filtering

/** One identified notification occurrence retained for frequency-condition seeding. */
data class PersistedRateOccurrence(
    val occurrenceId: RateOccurrenceId,
    val packageName: String,
    val channelId: String?,
    val postedAtMillis: Long,
)

/** Content-free association between an active listener notification and its occurrence. */
data class ActiveRateOccurrence(
    /** URL-safe HMAC digest. The raw listener key must never cross this repository boundary. */
    val listenerKeyDigest: RateListenerKeyDigest,
    val occurrenceId: RateOccurrenceId,
    val packageName: String,
    val channelId: String?,
    val lastPostedAtMillis: Long,
)

/** Result of atomically resolving an active identity and durably recording its latest post. */
data class RecordedRateOccurrence(
    val activeOccurrence: ActiveRateOccurrence,
    /** False when an older callback was ignored instead of moving durable state backward. */
    val accepted: Boolean,
    /** Raw durable marker captured in the same transaction; callers compare it with current time. */
    val incompleteUntilMillis: Long?,
)

/**
 * Completeness-aware occurrence seed.
 *
 * [Available] may cover less than the maximum supported window. Callers must omit a rate signal
 * unless its inclusive cutoff is at or after [Available.coverageStartMillis].
 */
sealed interface RateOccurrenceSeed {
    data class Available(
        val occurrences: List<PersistedRateOccurrence>,
        /** Earliest timestamp whose occurrence history is known to be complete, inclusive. */
        val coverageStartMillis: Long,
    ) : RateOccurrenceSeed

    data class Incomplete(
        val reason: RateOccurrenceIncompleteReason,
        /** Earliest known time a retry may become complete; null when no time can guarantee it. */
        val retryAtMillis: Long?,
    ) : RateOccurrenceSeed

    data class Unavailable(
        val reason: RateOccurrencePersistenceFailure,
    ) : RateOccurrenceSeed
}

enum class RateOccurrenceIncompleteReason {
    PERSISTED_GAP,
    HISTORY_LIMIT_EXCEEDED,
}

/** Deliberately coarse so persistence errors do not expose local database details across layers. */
enum class RateOccurrencePersistenceFailure {
    PERSISTENCE_UNAVAILABLE,
}

sealed interface RateOccurrencePersistenceResult<out T> {
    data class Success<T>(
        val value: T,
    ) : RateOccurrencePersistenceResult<T>

    data class Unavailable(
        val reason: RateOccurrencePersistenceFailure,
    ) : RateOccurrencePersistenceResult<Nothing>
}

/**
 * Restart-safe storage used by the notification-frequency pipeline.
 *
 * Raw Android listener keys never enter this contract. Callers must hash them with
 * [RateListenerKeyHasher] first.
 */
interface RateOccurrenceRepository {
    suspend fun loadSeed(
        sinceMillis: Long,
        nowMillis: Long,
    ): RateOccurrenceSeed

    suspend fun activeOccurrences(): RateOccurrencePersistenceResult<List<ActiveRateOccurrence>>

    suspend fun activeOccurrence(
        listenerKeyDigest: RateListenerKeyDigest,
    ): RateOccurrencePersistenceResult<ActiveRateOccurrence?>

    /**
     * Resolves [listenerKeyDigest] to its active occurrence (or [candidateOccurrenceId] for a new
     * lifecycle) and commits both active mapping and durable history in one transaction.
     */
    suspend fun recordPost(
        listenerKeyDigest: RateListenerKeyDigest,
        candidateOccurrenceId: RateOccurrenceId,
        packageName: String,
        channelId: String?,
        postedAtMillis: Long,
    ): RateOccurrencePersistenceResult<RecordedRateOccurrence>

    suspend fun deleteActiveOccurrence(
        listenerKeyDigest: RateListenerKeyDigest,
        occurrenceId: RateOccurrenceId,
        removedPostTimeMillis: Long,
    ): RateOccurrencePersistenceResult<Boolean>

    /** Deletes only history that is outside every supported rate window at [nowMillis]. */
    suspend fun purgeExpiredHistory(nowMillis: Long): RateOccurrencePersistenceResult<Int>

    /**
     * Extends the known-incomplete interval through one maximum rate window after [anchorMillis].
     * Implementations must saturate at [Long.MAX_VALUE].
     */
    suspend fun extendIncompleteWindowFrom(anchorMillis: Long): RateOccurrencePersistenceResult<Long>
}

/** Result of deriving a non-reversible database key from a transient Android listener key. */
sealed interface RateListenerKeyHashResult {
    data class Success(
        val digest: RateListenerKeyDigest,
        /** Non-null when key creation or recovery requires rate signals to remain incomplete. */
        val recoveryIncompleteUntilMillis: Long? = null,
    ) : RateListenerKeyHashResult

    data class Unavailable(
        val reason: RateListenerKeyHashFailure,
    ) : RateListenerKeyHashResult
}

/** Validated 256-bit HMAC encoded as unpadded Base64URL; raw listener keys cannot use this type. */
@JvmInline
value class RateListenerKeyDigest(
    val value: String,
) {
    init {
        require(value.length == SHA256_BASE64_URL_LENGTH && value.all(Char::isBase64UrlCharacter)) {
            "Listener-key digest must be an unpadded Base64URL SHA-256 value"
        }
    }
}

enum class RateListenerKeyHashFailure {
    EMPTY_LISTENER_KEY,
    KEY_LOST,
    KEY_INVALIDATED,
    KEYSTORE_UNAVAILABLE,
    PERSISTENCE_UNAVAILABLE,
    HASH_FAILED,
}

/** Hashes a transient listener key without persisting or logging the raw value. */
interface RateListenerKeyHasher {
    suspend fun hash(rawListenerKey: String): RateListenerKeyHashResult
}

/** Canonical locally-generated UUID-v4 identity; listener keys cannot accidentally cross as ids. */
@JvmInline
value class RateOccurrenceId(
    val value: String,
) {
    init {
        require(rateOccurrenceIdPattern.matches(value)) {
            "Rate occurrence id must be a canonical UUID-v4 value"
        }
    }
}

private fun Char.isBase64UrlCharacter(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '_'

private val rateOccurrenceIdPattern =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private const val SHA256_BASE64_URL_LENGTH = 43
