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
                runCatchingPreservingCancellation {
                    handler(request)
                }.onFailure(onFailure)
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
 * Transfers committed persistence to [persistenceScope] while keeping enrichment service-owned and
 * bounded.
 *
 * [submit] normally waits so the listener's existing evaluation limit remains the backpressure
 * boundary. If that caller is cancelled after handoff, the application-owned write still completes.
 * At most [maxConcurrentPersistence] writes can outlive their callers; no unbounded persistence
 * queue or fire-and-forget job list is created.
 */
internal class PostCommitWorkDispatcher<T, R>(
    private val persistenceScope: CoroutineScope,
    enrichmentScope: CoroutineScope,
    private val persist: suspend (T) -> R?,
    private val onPersistenceFailure: (Throwable) -> Unit = {},
    onEnrichmentFailure: (Throwable) -> Unit = {},
    enrich: suspend (R) -> Unit,
    maxConcurrentPersistence: Int = DEFAULT_MAX_CONCURRENT_PERSISTENCE,
) : AutoCloseable {
    init {
        require(maxConcurrentPersistence > 0)
    }

    private val persistencePermits = Semaphore(maxConcurrentPersistence)
    private val enrichmentQueue =
        SemanticObservationQueue(
            scope = enrichmentScope,
            onFailure = onEnrichmentFailure,
            handler = enrich,
        )

    suspend fun submit(request: T) {
        val completion = CompletableDeferred<Unit>()
        withContext(NonCancellable) {
            persistencePermits.acquire()
            val persistenceJob =
                persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        runCatchingPreservingCancellation {
                            persist(request)?.let(enrichmentQueue::offer)
                        }.fold(
                            onSuccess = { completion.complete(Unit) },
                            onFailure = { error ->
                                completePersistenceFailure(completion, error)
                            },
                        )
                    } catch (error: LinkageError) {
                        completePersistenceFailure(completion, error)
                    } catch (error: OutOfMemoryError) {
                        completePersistenceFailure(completion, error)
                    } catch (error: CancellationException) {
                        completion.completeExceptionally(error)
                        throw error
                    }
                }
            persistenceJob.invokeOnCompletion { error ->
                persistencePermits.release()
                if (error != null && !completion.isCompleted) {
                    completion.completeExceptionally(error)
                }
            }
        }
        completion.await()
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

    fun clearPending() {
        enrichmentQueue.clearPending()
    }

    override fun close() {
        // Application-owned writes are deliberately not cancelled here.
        enrichmentQueue.close()
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_PERSISTENCE = 4
    }
}
