package com.alarmcontrol.data.repository

import com.alarmcontrol.data.db.TransactionRunner

/** JVM fake; Room's rollback semantics are covered by instrumented database tests. */
class ImmediateTransactionRunner : TransactionRunner {
    var invocations = 0
        private set

    override suspend fun <T> run(block: suspend () -> T): T {
        invocations++
        return block()
    }
}
