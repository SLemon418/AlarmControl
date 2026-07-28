package com.alarmcontrol.ml.semantic

import com.alarmcontrol.ml.SemanticInferenceUrgency
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs blocking LiteRT work off the caller while bounding it to one running and one waiting task. */
internal fun interface SemanticInferenceRunner {
    suspend fun run(inference: () -> FloatArray?): FloatArray?

    /** Runs [inference] with an optional admission priority understood by supporting runners. */
    suspend fun run(
        urgency: SemanticInferenceUrgency,
        inference: () -> FloatArray?,
    ): FloatArray? = run(inference)
}

/**
 * Keeps the notification pipeline timeout meaningful even when native LiteRT ignores coroutine
 * cancellation. One native invocation runs at a time and one more may wait. Realtime work may
 * replace only a background task that is still physically present in the executor queue.
 */
internal class BoundedSemanticInferenceRunner : SemanticInferenceRunner {
    private val stateLock = Any()
    private var admittedCount = 0
    private var queuedTask: InferenceTask? = null
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            SemanticThreadFactory,
            ThreadPoolExecutor.AbortPolicy(),
        )

    override suspend fun run(inference: () -> FloatArray?): FloatArray? =
        run(SemanticInferenceUrgency.REALTIME, inference)

    override suspend fun run(
        urgency: SemanticInferenceUrgency,
        inference: () -> FloatArray?,
    ): FloatArray? =
        suspendCancellableCoroutine { continuation ->
            var displacedDelivery: ContinuationDelivery? = null
            var callerDelivery: ContinuationDelivery? = null
            synchronized(stateLock) {
                if (!continuation.isActive) return@synchronized
                if (admittedCount == CAPACITY) {
                    val displaced = queuedTask
                    if (urgency != SemanticInferenceUrgency.REALTIME ||
                        displaced?.urgency != SemanticInferenceUrgency.BACKGROUND ||
                        !executor.remove(displaced)
                    ) {
                        callerDelivery = ContinuationDelivery.nullResult(continuation)
                        return@synchronized
                    }
                    displacedDelivery = displaced.completePendingLocked(Result.success(null))
                }

                val task = InferenceTask(urgency, continuation, inference)
                admittedCount += 1
                continuation.invokeOnCancellation { cancelPending(task) }
                if (task.state == TaskState.PENDING) {
                    try {
                        executor.execute(task)
                        queuedTask = task
                    } catch (_: RejectedExecutionException) {
                        callerDelivery = task.completePendingLocked(Result.success(null))
                    }
                }
            }
            displacedDelivery?.deliver()
            callerDelivery?.deliver()
        }

    private fun cancelPending(task: InferenceTask) {
        synchronized(stateLock) {
            if (task.state != TaskState.PENDING) return
            executor.remove(task)
            task.completePendingLocked(delivery = null)
        }
    }

    private inner class InferenceTask(
        val urgency: SemanticInferenceUrgency,
        private val continuation: CancellableContinuation<FloatArray?>,
        private val inference: () -> FloatArray?,
    ) : Runnable {
        var state = TaskState.PENDING
            private set

        override fun run() {
            synchronized(stateLock) {
                if (state != TaskState.PENDING) return
                state = TaskState.RUNNING
                if (queuedTask === this) queuedTask = null
            }
            val result = runCatching { inference() }
            val delivery =
                synchronized(stateLock) {
                    check(state == TaskState.RUNNING)
                    state = TaskState.COMPLETED
                    releaseAdmissionLocked()
                    ContinuationDelivery(continuation, result)
                }
            delivery.deliver()
        }

        fun completePendingLocked(delivery: Result<FloatArray?>?): ContinuationDelivery? {
            check(state == TaskState.PENDING)
            state = TaskState.COMPLETED
            if (queuedTask === this) queuedTask = null
            releaseAdmissionLocked()
            return delivery?.let { ContinuationDelivery(continuation, it) }
        }
    }

    private fun releaseAdmissionLocked() {
        check(admittedCount in 1..CAPACITY)
        admittedCount -= 1
    }

    private class ContinuationDelivery(
        private val continuation: CancellableContinuation<FloatArray?>,
        private val result: Result<FloatArray?>,
    ) {
        private val delivered = AtomicBoolean()

        fun deliver() {
            if (delivered.compareAndSet(false, true)) {
                continuation.resumeWith(result)
            }
        }

        companion object {
            fun nullResult(continuation: CancellableContinuation<FloatArray?>): ContinuationDelivery =
                ContinuationDelivery(
                    continuation = continuation,
                    result = Result.success(null),
                )
        }
    }

    private enum class TaskState {
        PENDING,
        RUNNING,
        COMPLETED,
    }

    private object SemanticThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, THREAD_NAME).apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
    }

    private companion object {
        const val CAPACITY = 2
        const val THREAD_NAME = "AlarmControl-Semantic"
    }
}
