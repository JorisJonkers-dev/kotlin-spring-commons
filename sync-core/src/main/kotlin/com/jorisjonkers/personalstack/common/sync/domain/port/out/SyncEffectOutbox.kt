package com.jorisjonkers.personalstack.common.sync.domain.port.out

import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncEffect

/**
 * OPTIONAL-WITH-DEFAULT outbound port. The safe default accepts empty effect lists and FAILS if
 * non-empty effects would otherwise be silently dropped. Consumers that emit effects must provide a
 * real, transactional outbox.
 *
 * [append] persists pure [SyncEffect] data atomically within the surrounding [SyncUnitOfWork]
 * transaction. [requestRelay] is invoked only after commit (via
 * [SyncTransactionContext.afterCommit]) to dispatch durable effects.
 */
interface SyncEffectOutbox {
    /** Persists [effects] in the current transaction. Must be a no-op for an empty list. */
    fun append(
        context: SyncContext<*>,
        effects: List<SyncEffect>,
    )

    /** Triggers relay of durable effects. Called after commit, never before. */
    fun requestRelay(context: SyncContext<*>)
}
