package com.jorisjonkers.personalstack.common.sync.infrastructure.persistence

import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncTransactionContext
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncUnitOfWork
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Spring-backed [SyncUnitOfWork]. The only place in the framework where Spring
 * transaction machinery is touched; application services stay free of
 * `@Transactional` and depend on the [SyncUnitOfWork] port instead.
 *
 * The block runs inside [TransactionOperations.execute]. Any
 * [SyncTransactionContext.afterCommit] callback registered by the block is
 * deferred and run by Spring once the surrounding transaction commits, via
 * [TransactionSynchronizationManager]. If the block requests an after-commit
 * callback while no synchronization-capable transaction is active, this adapter
 * fails fast rather than silently running the callback inline (which would
 * break the "relay only after durable commit" guarantee).
 */
class SpringTransactionSyncUnitOfWork(
    private val transactionOperations: TransactionOperations,
) : SyncUnitOfWork {
    override fun <T> transaction(block: SyncTransactionContext.() -> T): T {
        // TransactionOperations.execute is nullable-by-signature in Spring; the
        // block always produces a value, so the non-null assertion is safe.
        return transactionOperations.execute { _ ->
            val context = SpringSyncTransactionContext()
            context.block()
        }!!
    }

    private class SpringSyncTransactionContext : SyncTransactionContext {
        override fun afterCommit(action: () -> Unit) {
            check(TransactionSynchronizationManager.isSynchronizationActive()) {
                "afterCommit requested without an active transaction synchronization; " +
                    "SyncUnitOfWork.transaction must run inside a real transaction so effects " +
                    "are relayed only after a durable commit"
            }
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        action()
                    }
                },
            )
        }
    }
}
