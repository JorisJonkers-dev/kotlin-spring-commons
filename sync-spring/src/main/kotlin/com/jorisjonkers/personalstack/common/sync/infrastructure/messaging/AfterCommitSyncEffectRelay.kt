package com.jorisjonkers.personalstack.common.sync.infrastructure.messaging

import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncEffectOutbox

/**
 * Adapter that relays durable sync effects after the aggregate transaction has
 * committed.
 *
 * The flow is:
 *  1. The application appends pure [com.jorisjonkers.personalstack.common.sync.domain.SyncEffect]
 *     data via [SyncEffectOutbox.append] inside the transaction.
 *  2. The application registers `afterCommit { relay.requestRelay(context) }`
 *     through the [com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncUnitOfWork].
 *  3. After commit, Spring invokes [requestRelay], which asks the outbox to
 *     dispatch the durable effects for this run.
 *
 * No side effect is sent directly from the domain or before the aggregate
 * transaction commits. This adapter only triggers dispatch of effects that are
 * already durable; it never produces effects of its own.
 */
class AfterCommitSyncEffectRelay(
    private val outbox: SyncEffectOutbox,
) {
    fun requestRelay(context: SyncContext<*>) {
        outbox.requestRelay(context)
    }
}
