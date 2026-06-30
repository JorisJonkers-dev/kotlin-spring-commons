package com.jorisjonkers.personalstack.common.sync.domain.port.out

import com.jorisjonkers.personalstack.common.sync.domain.RemoteFetch
import com.jorisjonkers.personalstack.common.sync.domain.RemotePage
import com.jorisjonkers.personalstack.common.sync.domain.RemoteRecord
import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncCursor

/**
 * MANDATORY outbound port. The consumer must supply a real implementation (in `sync-spring` it may
 * be wrapped by a resilience4j adapter).
 *
 * Reads authoritative remote state. Every method returns a typed [RemoteFetch] result and MUST NEVER
 * use `null` or a thrown remote exception to signal ordinary absence:
 *  - [RemoteFetch.Found] / [RemoteFetch.Missing] / [RemoteFetch.Failed] / [RemoteFetch.Partial] are distinct.
 *  - Only [RemoteFetch.Missing] from an authoritative source may lead to delete/unlink planning.
 *  - [RemoteFetch.Failed] (outage/timeout/rate-limit/circuit-open) MUST NOT become a delete.
 *  - [RemoteFetch.Partial] indicates an incomplete page; callers must requeue and never infer deletes.
 */
interface RemoteCatalog<R : Any, RID : Any, KEY : Any, SCOPE : Any> {
    /** Fetches a single remote record by its external id. */
    fun fetchOne(
        remoteId: RID,
        context: SyncContext<SCOPE>,
    ): RemoteFetch<RemoteRecord<R, RID, KEY>>

    /**
     * Fetches the authoritative set of remote records for [scope] as a (possibly single) page.
     * A [RemoteFetch.Found] page is treated as authoritative for absence-driven deletes;
     * a [RemoteFetch.Partial] page is not.
     */
    fun fetchForScope(
        scope: SCOPE,
        context: SyncContext<SCOPE>,
    ): RemoteFetch<RemotePage<R, RID, KEY>>

    /**
     * Fetches one cursor-bounded page for paged backfill / incremental discovery. The returned
     * [RemotePage] carries the next cursor, high-watermark, and optional retry hint.
     */
    fun fetchPage(
        scope: SCOPE?,
        cursor: SyncCursor?,
        pageSize: Int,
        context: SyncContext<SCOPE>,
    ): RemoteFetch<RemotePage<R, RID, KEY>>
}
