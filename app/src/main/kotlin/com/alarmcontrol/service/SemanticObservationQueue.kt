package com.alarmcontrol.service

import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One running plus one pending best-effort semantic observation. Overflow drops stale analytics
 * work and never delays the notification action path.
 */
internal class SemanticObservationQueue<T>(
    scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit = {},
    handler: suspend (T) -> Unit,
) : AutoCloseable {
    private val requests =
        Channel<T>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    init {
        scope.launch {
            for (request in requests) {
                try {
                    runCatchingPreservingCancellation {
                        handler(request)
                    }.onFailure(onFailure)
                } catch (error: LinkageError) {
                    onFailure(error)
                } catch (error: OutOfMemoryError) {
                    onFailure(error)
                }
            }
        }
    }

    fun offer(request: T): Boolean = requests.trySend(request).isSuccess

    fun clearPending() {
        while (requests.tryReceive().isSuccess) {
            // Content stays in memory only until this bounded queue entry is discarded.
        }
    }

    override fun close() {
        requests.close()
    }
}

/**
 * Transfers committed persistence and decision enrichment to application-owned work.
 *
 * [submit] normally waits so the listener's existing evaluation limit remains the backpressure
 * boundary. If that caller is cancelled after handoff, the application-owned write still completes.
 * [tryReserve] is called before the platform action, so every accepted action owns one of exactly
 * [maxRetainedEnrichment] slots through persistence and enrichment. Closing rejects new reservations
 * but drains every accepted handoff so a destroyed listener service is not retained after the
 * bounded queue finishes.
 */
internal class PostCommitWorkDispatcher<T, R>(
    private val persistenceScope: CoroutineScope,
    enrichmentScope: CoroutineScope,
    private val persist: suspend (T) -> R?,
    private val onPersistenceFailure: (Throwable) -> Unit = {},
    private val onEnrichmentFailure: (Throwable) -> Unit = {},
    enrich: suspend (R) -> Unit,
    maxConcurrentPersistence: Int = DEFAULT_MAX_CONCURRENT_PERSISTENCE,
    maxRetainedEnrichment: Int = DEFAULT_MAX_RETAINED_ENRICHMENT,
) : AutoCloseable {
    init {
        require(maxConcurrentPersistence > 0)
        require(maxRetainedEnrichment > 0)
    }

    private val persistencePermits = Semaphore(maxConcurrentPersistence)
    private val lifecycleLock = Any()
    private val reservationOwner = Any()
    private var closing = false
    private var retainedRequests = 0
    private val enrichmentQueue =
        NonDroppingEnrichmentQueue(
            scope = enrichmentScope,
            capacity = maxRetainedEnrichment,
            onFailure = onEnrichmentFailure,
            handler = { reserved: ReservedEnrichment<R> ->
                try {
                    enrich(reserved.value)
                } finally {
                    finishSubmitted(reserved.reservation)
                }
            },
        )
    private val retainedLimit = maxRetainedEnrichment

    class Reservation
        internal constructor(
            internal val owner: Any,
        ) {
            internal var state = ReservationState.OPEN
        }

    fun tryReserve(): Reservation? =
        synchronized(lifecycleLock) {
            if (closing || retainedRequests >= retainedLimit) {
                null
            } else {
                retainedRequests += 1
                Reservation(reservationOwner)
            }
        }

    fun release(reservation: Reservation) {
        val closeQueue =
            synchronized(lifecycleLock) {
                require(reservation.owner === reservationOwner) { "Reservation belongs to another dispatcher" }
                if (reservation.state != ReservationState.OPEN) {
                    false
                } else {
                    reservation.state = ReservationState.RELEASED
                    retainedRequests -= 1
                    check(retainedRequests >= 0)
                    closing && retainedRequests == 0
                }
            }
        if (closeQueue) enrichmentQueue.close()
    }

    suspend fun submit(
        reservation: Reservation,
        request: T,
    ) {
        claim(reservation)
        val completion = CompletableDeferred<Unit>()
        withContext(NonCancellable) {
            persistencePermits.acquire()
            val enrichmentAccepted = AtomicBoolean()
            val persistenceJob =
                persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        runCatchingPreservingCancellation { persist(request) }.fold(
                            onSuccess = { persisted ->
                                completion.complete(Unit)
                                persisted?.let { value ->
                                    runCatchingPreservingCancellation {
                                        enrichmentQueue.enqueue(
                                            ReservedEnrichment(reservation, value),
                                        )
                                        enrichmentAccepted.set(true)
                                    }.onFailure(onEnrichmentFailure)
                                }
                            },
                            onFailure = { error ->
                                completePersistenceFailure(completion, error)
                            },
                        )
                    } catch (error: LinkageError) {
                        completeWorkFailure(completion, error)
                    } catch (error: OutOfMemoryError) {
                        completeWorkFailure(completion, error)
                    } catch (error: CancellationException) {
                        if (!completion.isCompleted) completion.completeExceptionally(error)
                        throw error
                    }
                }
            persistenceJob.invokeOnCompletion { error ->
                persistencePermits.release()
                if (!enrichmentAccepted.get()) finishSubmitted(reservation)
                if (error != null && !completion.isCompleted) {
                    completion.completeExceptionally(error)
                }
            }
        }
        completion.await()
    }

    private fun claim(reservation: Reservation) {
        synchronized(lifecycleLock) {
            require(reservation.owner === reservationOwner) { "Reservation belongs to another dispatcher" }
            check(reservation.state == ReservationState.OPEN) { "Post-commit reservation is not open" }
            reservation.state = ReservationState.SUBMITTED
        }
    }

    private fun completeWorkFailure(
        completion: CompletableDeferred<Unit>,
        error: Throwable,
    ) {
        if (completion.isCompleted) {
            onEnrichmentFailure(error)
        } else {
            completePersistenceFailure(completion, error)
        }
    }

    private fun completePersistenceFailure(
        completion: CompletableDeferred<Unit>,
        error: Throwable,
    ) {
        try {
            onPersistenceFailure(error)
        } finally {
            completion.completeExceptionally(error)
        }
    }

    private fun finishSubmitted(reservation: Reservation) {
        val closeQueue =
            synchronized(lifecycleLock) {
                require(reservation.owner === reservationOwner) { "Reservation belongs to another dispatcher" }
                if (reservation.state != ReservationState.SUBMITTED) {
                    false
                } else {
                    reservation.state = ReservationState.RELEASED
                    retainedRequests -= 1
                    check(retainedRequests >= 0)
                    closing && retainedRequests == 0
                }
            }
        if (closeQueue) enrichmentQueue.close()
    }

    override fun close() {
        val closeQueue =
            synchronized(lifecycleLock) {
                closing = true
                retainedRequests == 0
            }
        if (closeQueue) enrichmentQueue.close()
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_PERSISTENCE = 4
        const val DEFAULT_MAX_RETAINED_ENRICHMENT = 64
    }

    internal enum class ReservationState {
        OPEN,
        SUBMITTED,
        RELEASED,
    }
}

private data class ReservedEnrichment<T>(
    val reservation: PostCommitWorkDispatcher.Reservation,
    val value: T,
)

/** Application-owned FIFO used only for non-droppable decision enrichment. */
private class NonDroppingEnrichmentQueue<T>(
    scope: CoroutineScope,
    capacity: Int,
    private val onFailure: (Throwable) -> Unit,
    handler: suspend (T) -> Unit,
) : AutoCloseable {
    private val requests = Channel<T>(capacity)

    init {
        scope.launch {
            for (request in requests) {
                try {
                    runCatchingPreservingCancellation {
                        handler(request)
                    }.onFailure(onFailure)
                } catch (error: LinkageError) {
                    onFailure(error)
                } catch (error: OutOfMemoryError) {
                    onFailure(error)
                }
            }
        }
    }

    suspend fun enqueue(request: T) {
        requests.send(request)
    }

    override fun close() {
        requests.close()
    }
}
