package com.alarmcontrol.data.db

import androidx.room.withTransaction
import javax.inject.Inject

/** Runs a group of local persistence operations in one Room transaction. */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

class RoomTransactionRunner
    @Inject
    constructor(
        private val database: AppDatabase,
    ) : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction(block)
    }
