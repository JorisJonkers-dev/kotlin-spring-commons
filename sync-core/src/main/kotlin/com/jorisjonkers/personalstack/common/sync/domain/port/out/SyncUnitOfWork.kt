package com.jorisjonkers.personalstack.common.sync.domain.port.out

/**
 * MANDATORY outbound port for application services. Owns transaction boundaries.
 * `@Transactional` appears only in the `sync-spring` adapter; the domain stays framework-free.
 *
 * Runs [block] inside a transaction and returns its result. On exception the transaction must roll
 * back. After-commit callbacks registered via [SyncTransactionContext.afterCommit] run only after a
 * successful commit, and never after a rollback.
 */
interface SyncUnitOfWork {
    fun <T> transaction(block: SyncTransactionContext.() -> T): T
}

interface SyncTransactionContext {
    /** Registers an action to run after the surrounding transaction commits successfully. */
    fun afterCommit(action: () -> Unit)
}
