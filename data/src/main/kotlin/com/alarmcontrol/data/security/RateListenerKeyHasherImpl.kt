package com.alarmcontrol.data.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import com.alarmcontrol.core.coroutines.AppDispatcher
import com.alarmcontrol.core.coroutines.Dispatcher
import com.alarmcontrol.core.filtering.RateListenerKeyDigest
import com.alarmcontrol.core.filtering.RateListenerKeyHashFailure
import com.alarmcontrol.core.filtering.RateListenerKeyHashResult
import com.alarmcontrol.core.filtering.RateListenerKeyHasher
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.data.db.dao.NotificationRateStateDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.UnrecoverableKeyException
import java.time.Clock
import javax.inject.Inject

/** Hashes transient Android listener keys without retaining their raw values. */
internal class RateListenerKeyHasherImpl
    @Inject
    constructor(
        private val provider: RateListenerKeyHmacProvider,
        private val rateStateDao: NotificationRateStateDao,
        private val clock: Clock,
        @Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
    ) : RateListenerKeyHasher {
        private val mutex = Mutex()

        override suspend fun hash(rawListenerKey: String): RateListenerKeyHashResult {
            if (rawListenerKey.isEmpty()) {
                return RateListenerKeyHashResult.Unavailable(
                    RateListenerKeyHashFailure.EMPTY_LISTENER_KEY,
                )
            }
            return withContext(ioDispatcher) {
                mutex.withLock {
                    hashLocked(rawListenerKey)
                }
            }
        }

        private suspend fun hashLocked(rawListenerKey: String): RateListenerKeyHashResult {
            val priorIncompleteUntilMillis =
                when (val preparation = prepareKeyIfMissing()) {
                    is KeyPreparation.Ready -> preparation.incompleteUntilMillis
                    is KeyPreparation.Failed -> return unavailable(preparation.reason)
                }
            val rawBytes = rawListenerKey.toByteArray(Charsets.UTF_8)
            return try {
                when (val first = attemptDigest(rawBytes)) {
                    is DigestAttempt.Success ->
                        RateListenerKeyHashResult.Success(
                            first.digest,
                            priorIncompleteUntilMillis,
                        )

                    is DigestAttempt.Failure ->
                        recoverAndRetry(
                            first = first,
                            rawBytes = rawBytes,
                            priorIncompleteUntilMillis = priorIncompleteUntilMillis,
                        )
                }
            } finally {
                rawBytes.fill(0)
            }
        }

        private suspend fun prepareKeyIfMissing(): KeyPreparation {
            val hasKey =
                runCatchingPreservingCancellation(provider::hasKey).getOrElse {
                    return KeyPreparation.Failed(RateListenerKeyHashFailure.KEYSTORE_UNAVAILABLE)
                }
            if (hasKey) return KeyPreparation.Ready(incompleteUntilMillis = null)
            val recovery =
                when (val prepared = prepareRecovery()) {
                    is RecoveryPreparation.Prepared -> prepared
                    RecoveryPreparation.PersistenceUnavailable ->
                        return KeyPreparation.Failed(
                            RateListenerKeyHashFailure.PERSISTENCE_UNAVAILABLE,
                        )
                }
            return if (runCatchingPreservingCancellation(provider::createKey).isFailure) {
                KeyPreparation.Failed(RateListenerKeyHashFailure.KEYSTORE_UNAVAILABLE)
            } else {
                KeyPreparation.Ready(recovery.incompleteUntilMillis)
            }
        }

        private suspend fun recoverAndRetry(
            first: DigestAttempt.Failure,
            rawBytes: ByteArray,
            priorIncompleteUntilMillis: Long?,
        ): RateListenerKeyHashResult {
            if (!first.reason.isRecoverableKeyFailure()) {
                return unavailable(first.reason)
            }
            val recovery =
                when (val prepared = prepareRecovery()) {
                    is RecoveryPreparation.Prepared -> prepared
                    RecoveryPreparation.PersistenceUnavailable ->
                        return unavailable(RateListenerKeyHashFailure.PERSISTENCE_UNAVAILABLE)
                }
            val incompleteUntilMillis =
                maxOfNullable(
                    priorIncompleteUntilMillis,
                    recovery.incompleteUntilMillis,
                )
            if (
                runCatchingPreservingCancellation {
                    provider.deleteKey()
                    provider.createKey()
                }.isFailure
            ) {
                return unavailable(RateListenerKeyHashFailure.KEYSTORE_UNAVAILABLE)
            }
            return when (val retry = attemptDigest(rawBytes)) {
                is DigestAttempt.Success ->
                    RateListenerKeyHashResult.Success(
                        retry.digest,
                        incompleteUntilMillis,
                    )

                is DigestAttempt.Failure -> unavailable(retry.reason)
            }
        }

        private suspend fun prepareRecovery(): RecoveryPreparation =
            runCatchingPreservingCancellation {
                rateStateDao.prepareForHmacKeyRecovery(clock.millis())
            }.fold(
                onSuccess = { RecoveryPreparation.Prepared(it) },
                onFailure = { RecoveryPreparation.PersistenceUnavailable },
            )

        private fun attemptDigest(rawBytes: ByteArray): DigestAttempt =
            runCatchingPreservingCancellation {
                val digestBytes = provider.hmac(rawBytes)
                try {
                    RateListenerKeyDigest(
                        Base64.encodeToString(
                            digestBytes,
                            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                        ),
                    )
                } finally {
                    digestBytes.fill(0)
                }
            }.fold(
                onSuccess = { DigestAttempt.Success(it) },
                onFailure = { error ->
                    DigestAttempt.Failure(
                        when (error) {
                            is KeyPermanentlyInvalidatedException ->
                                RateListenerKeyHashFailure.KEY_INVALIDATED

                            is UnrecoverableKeyException ->
                                RateListenerKeyHashFailure.KEY_LOST

                            else -> RateListenerKeyHashFailure.HASH_FAILED
                        },
                    )
                },
            )

        private fun unavailable(reason: RateListenerKeyHashFailure): RateListenerKeyHashResult =
            RateListenerKeyHashResult.Unavailable(reason)
    }

private sealed interface KeyPreparation {
    data class Ready(
        val incompleteUntilMillis: Long?,
    ) : KeyPreparation

    data class Failed(
        val reason: RateListenerKeyHashFailure,
    ) : KeyPreparation
}

private sealed interface RecoveryPreparation {
    data class Prepared(
        val incompleteUntilMillis: Long?,
    ) : RecoveryPreparation

    data object PersistenceUnavailable : RecoveryPreparation
}

private sealed interface DigestAttempt {
    data class Success(
        val digest: RateListenerKeyDigest,
    ) : DigestAttempt

    data class Failure(
        val reason: RateListenerKeyHashFailure,
    ) : DigestAttempt
}

private fun maxOfNullable(
    first: Long?,
    second: Long?,
): Long? =
    when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

private fun RateListenerKeyHashFailure.isRecoverableKeyFailure(): Boolean =
    this == RateListenerKeyHashFailure.KEY_LOST ||
        this == RateListenerKeyHashFailure.KEY_INVALIDATED
