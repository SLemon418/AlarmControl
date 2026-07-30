package com.alarmcontrol.data.db

import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Runs a group of local persistence operations in one Room transaction. */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T

    /**
     * Runs [block] transactionally and invokes [onCommitted] immediately after a successful commit,
     * before a post-commit result-delivery failure can escape to the caller. Production shields the
     * commit and callback from cancellation so a committed deletion cannot miss its in-memory fence.
     */
    suspend fun <T> runAndNotifyCommit(
        onCommitted: () -> Unit,
        block: suspend () -> T,
    ): T = run(block).also { onCommitted() }
}

class RoomTransactionRunner
    @Inject
    constructor(
        private val database: AppDatabase,
    ) : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction(block)

        override suspend fun <T> runAndNotifyCommit(
            onCommitted: () -> Unit,
            block: suspend () -> T,
        ): T =
            // The commit and history-generation update form one non-cancellable observation boundary.
            withContext(NonCancellable) {
                database.withTransaction(block).also { onCommitted() }
            }
    }
