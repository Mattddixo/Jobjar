package com.mattdixon.jobjar.data

/** Runs writes as one transaction. Tests swap in a pass-through. */
interface TransactionRunner {
    suspend fun <R> run(block: suspend () -> R): R
}
