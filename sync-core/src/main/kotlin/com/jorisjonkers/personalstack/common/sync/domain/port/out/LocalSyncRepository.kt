package com.jorisjonkers.personalstack.common.sync.domain.port.out

import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncCursor

/**
 * MANDATORY outbound port. The consumer must supply a real implementation.
 *
 * Owns local persistence of the synced aggregate. Lookup/list methods MUST include
 * soft-deleted and unlinked rows so that [com.jorisjonkers.personalstack.common.sync.domain.SyncDecision.Restore]
 * and [com.jorisjonkers.personalstack.common.sync.domain.SyncDecision.Relink] are possible.
 *
 * Persistence adapters must enforce a unique remote-id (or remembered-remote-id) constraint
 * where the consumer data model supports it; that uniqueness is the correctness backstop behind
 * the [LockManager].
 */
interface LocalSyncRepository<A : Any, RID : Any, SCOPE : Any> {
    /**
     * Returns the aggregate currently linked (or formerly linked / soft-deleted) to [remoteId],
     * or `null` when no local row references that remote id. MUST include soft-deleted and
     * unlinked rows.
     */
    fun findByRemoteIdIncludingDeleted(
        remoteId: RID,
        scope: SCOPE?,
        context: SyncContext<SCOPE>,
    ): A?

    /**
     * Returns all local aggregates within [scope], including soft-deleted and unlinked rows.
     * Used by list reconciliation to detect absence-driven deletes/unlinks.
     */
    fun listIncludingDeleted(
        scope: SCOPE,
        context: SyncContext<SCOPE>,
    ): List<A>

    /** Persists [aggregate] and returns the stored state. Called only inside a [SyncUnitOfWork] transaction. */
    fun save(
        aggregate: A,
        context: SyncContext<SCOPE>,
    ): A

    /**
     * Returns a page of currently linked remote ids for [scope], used by spawn/backfill full-sync
     * to enqueue per-entity work for locals the remote page did not mention.
     */
    fun listLinkedRemoteIds(
        scope: SCOPE?,
        cursor: SyncCursor?,
        limit: Int,
        context: SyncContext<SCOPE>,
    ): LocalIdPage<RID>
}

data class LocalIdPage<RID : Any>(
    val ids: List<RID>,
    val nextCursor: SyncCursor?,
)
