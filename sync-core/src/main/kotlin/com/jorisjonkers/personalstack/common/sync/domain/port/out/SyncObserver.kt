package com.jorisjonkers.personalstack.common.sync.domain.port.out

import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncOutcome
import com.jorisjonkers.personalstack.common.sync.domain.SyncReport

/**
 * OPTIONAL-WITH-DEFAULT outbound port. The default is a safe no-op. Production scheduled/message
 * triggers should provide a real implementation (e.g. a Micrometer adapter in `sync-spring`).
 *
 * Side-band observability hook. Unlike [AuditTrail], callbacks fire outside the transaction and must
 * not mutate domain state. Implementations must not throw in a way that fails the sync.
 */
interface SyncObserver {
    fun onRunStarted(context: SyncContext<*>)

    fun onOutcome(
        context: SyncContext<*>,
        outcome: SyncOutcome<*>,
    )

    fun onRunCompleted(report: SyncReport)
}
