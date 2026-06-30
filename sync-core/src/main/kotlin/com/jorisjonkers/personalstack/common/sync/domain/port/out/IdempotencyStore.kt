package com.jorisjonkers.personalstack.common.sync.domain.port.out

import com.jorisjonkers.personalstack.common.sync.domain.IdempotencyKey
import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailure
import com.jorisjonkers.personalstack.common.sync.domain.SyncReport
import java.time.Duration

/**
 * OPTIONAL-WITH-DEFAULT for direct in-process calls, but REQUIRED by policy for message, scheduler,
 * and admin-triggered production sync. The default is an explicit `NoopIdempotencyStore` that must
 * be opt-in via Spring properties (`extratoast.sync.idempotency=disabled`) and is never silently
 * auto-installed.
 *
 * [claim] is a compare-and-set: the first caller for a key gets [IdempotencyClaim.Acquired]; a
 * concurrent in-flight caller gets [IdempotencyClaim.InProgress]; a caller for an already-finished
 * key gets [IdempotencyClaim.Completed] with the stored report (replay).
 */
interface IdempotencyStore {
    fun claim(
        key: IdempotencyKey,
        ttl: Duration,
        context: SyncContext<*>,
    ): IdempotencyClaim

    fun complete(key: IdempotencyKey, report: SyncReport)

    fun fail(key: IdempotencyKey, failure: SyncFailure)
}

sealed interface IdempotencyClaim {
    data object Acquired : IdempotencyClaim
    data class InProgress(val message: String) : IdempotencyClaim
    data class Completed(val report: SyncReport) : IdempotencyClaim
}
