package com.mattdixon.jobjar.data

/** Runs a block of database writes all-or-nothing. An interface so the repository's tests can
 * swap in a plain pass-through instead of needing a real database; the app's implementation is
 * [RoomTransactionRunner]. */
interface TransactionRunner {
    suspend fun <R> run(block: suspend () -> R): R
}
