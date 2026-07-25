package com.alarmcontrol.core.result

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Outcome of an observed data load (CLAUDE.md §4 "Result types", §8 "errors via sealed types, not
 * exceptions across layers"). A reactive stream is wrapped so a thrown exception becomes a
 * [Failure] value the UI can render, instead of crashing the collecting coroutine.
 */
sealed interface DataResult<out T> {
    data object Loading : DataResult<Nothing>

    data class Success<T>(
        val data: T,
    ) : DataResult<T>

    data class Failure(
        val throwable: Throwable,
    ) : DataResult<Nothing>
}

/**
 * Lifts a stream into [DataResult]: emits [DataResult.Loading] first, each value as [Success], and
 * any upstream error as [Failure] (terminating the stream without throwing).
 */
fun <T> Flow<T>.asDataResult(): Flow<DataResult<T>> =
    map<T, DataResult<T>> { DataResult.Success(it) }
        .onStart { emit(DataResult.Loading) }
        .catch { emit(DataResult.Failure(it)) }

/**
 * Equivalent to [runCatching], except structured-concurrency cancellation is always rethrown.
 * Suspend call sites must not turn cancellation into a user-visible failure or WorkManager retry.
 */
@Suppress("TooGenericExceptionCaught")
inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
