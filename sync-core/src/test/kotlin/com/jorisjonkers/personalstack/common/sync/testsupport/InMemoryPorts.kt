package com.jorisjonkers.personalstack.common.sync.testsupport

import com.jorisjonkers.personalstack.common.sync.domain.BaselineSnapshot
import com.jorisjonkers.personalstack.common.sync.domain.CursorCheckpoint
import com.jorisjonkers.personalstack.common.sync.domain.IdempotencyKey
import com.jorisjonkers.personalstack.common.sync.domain.RemoteFetch
import com.jorisjonkers.personalstack.common.sync.domain.RemotePage
import com.jorisjonkers.personalstack.common.sync.domain.RemoteRecord
import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncCursor
import com.jorisjonkers.personalstack.common.sync.domain.SyncEffect
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailure
import com.jorisjonkers.personalstack.common.sync.domain.SyncLockKey
import com.jorisjonkers.personalstack.common.sync.domain.SyncName
import com.jorisjonkers.personalstack.common.sync.domain.SyncOutcome
import com.jorisjonkers.personalstack.common.sync.domain.SyncReport
import com.jorisjonkers.personalstack.common.sync.domain.SyncSubject
import com.jorisjonkers.personalstack.common.sync.domain.port.out.AuditTrail
import com.jorisjonkers.personalstack.common.sync.domain.port.out.IdempotencyClaim
import com.jorisjonkers.personalstack.common.sync.domain.port.out.IdempotencyStore
import com.jorisjonkers.personalstack.common.sync.domain.port.out.LocalIdPage
import com.jorisjonkers.personalstack.common.sync.domain.port.out.LocalSyncRepository
import com.jorisjonkers.personalstack.common.sync.domain.port.out.LockManager
import com.jorisjonkers.personalstack.common.sync.domain.port.out.LockResult
import com.jorisjonkers.personalstack.common.sync.domain.port.out.RemoteCatalog
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncCheckpointStore
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncEffectOutbox
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncObserver
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncTransactionContext
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncUnitOfWork
import java.time.Duration

/*
 * Reusable, deterministic, in-memory implementations of all nine outbound sync ports.
 *
 * Every adapter is a plain map/list-backed fake with explicit, test-controllable knobs so a test
 * can drive every branch of the application services. None of them perform real I/O or spin
 * threads; all state is held in-process and is inspectable after a run.
 *
 * See HARNESS-API.md (same directory) for the contract other test-writers rely on.
 */

// ---------------------------------------------------------------------------------------------
// LocalSyncRepository
// ---------------------------------------------------------------------------------------------

/**
 * In-memory [LocalSyncRepository]. Holds aggregates keyed by their remote id (including
 * soft-deleted / unlinked rows, so Restore and Relink are reachable).
 *
 * @param keyOf extracts the remote id an aggregate is (or was) linked to, or `null` when the
 *   aggregate has no remote linkage. Soft-deleted rows should still return their remembered id.
 * @param scopeOf extracts the scope an aggregate belongs to; used by [listIncludingDeleted].
 * @param linkedOf whether the aggregate counts as currently *linked* for [listLinkedRemoteIds]
 *   (full-sync fan-out). Defaults to "linked when keyOf returns non-null".
 */
class InMemoryLocalSyncRepository<A : Any, RID : Any, SCOPE : Any>(
    private val keyOf: (A) -> RID?,
    private val scopeOf: (A) -> SCOPE? = { null },
    private val linkedOf: (A) -> Boolean = { keyOf(it) != null },
) : LocalSyncRepository<A, RID, SCOPE> {
    /** Stored aggregates in insertion/save order. Mutated by [save] and [seed]. */
    val rows: MutableList<A> = mutableListOf()

    /** Every aggregate passed to [save], in order, for assertions. */
    val saved: MutableList<A> = mutableListOf()

    /** Pre-populate the repository without going through [save] (no [saved] entry recorded). */
    fun seed(vararg aggregates: A): InMemoryLocalSyncRepository<A, RID, SCOPE> {
        rows.addAll(aggregates)
        return this
    }

    override fun findByRemoteIdIncludingDeleted(
        remoteId: RID,
        scope: SCOPE?,
        context: SyncContext<SCOPE>,
    ): A? = rows.lastOrNull { keyOf(it) == remoteId }

    override fun listIncludingDeleted(
        scope: SCOPE,
        context: SyncContext<SCOPE>,
    ): List<A> = rows.filter { scopeOf(it) == null || scopeOf(it) == scope }

    override fun save(
        aggregate: A,
        context: SyncContext<SCOPE>,
    ): A {
        saved.add(aggregate)
        // Replace an existing row sharing the same remote id, else append.
        val rid = keyOf(aggregate)
        val idx = if (rid == null) -1 else rows.indexOfFirst { keyOf(it) == rid }
        if (idx >= 0) rows[idx] = aggregate else rows.add(aggregate)
        return aggregate
    }

    override fun listLinkedRemoteIds(
        scope: SCOPE?,
        cursor: SyncCursor?,
        limit: Int,
        context: SyncContext<SCOPE>,
    ): LocalIdPage<RID> {
        val all =
            rows
                .filter { linkedOf(it) && (scope == null || scopeOf(it) == null || scopeOf(it) == scope) }
                .mapNotNull { keyOf(it) }
        // Single-page fake: ignore the cursor, return everything once with no next cursor.
        return LocalIdPage(ids = all.take(limit), nextCursor = null)
    }
}

// ---------------------------------------------------------------------------------------------
// RemoteCatalog
// ---------------------------------------------------------------------------------------------

/**
 * In-memory [RemoteCatalog]. By default every fetch returns [RemoteFetch.Missing] (NOT_FOUND); a
 * test installs canned responses via the `onX` setters. All three methods are independently
 * controllable so Found / Missing / Failed / Partial and paged cursors can each be exercised.
 */
class InMemoryRemoteCatalog<R : Any, RID : Any, KEY : Any, SCOPE : Any> : RemoteCatalog<R, RID, KEY, SCOPE> {
    /** Per-id canned single-record responses for [fetchOne]. */
    val oneResponses: MutableMap<RID, RemoteFetch<RemoteRecord<R, RID, KEY>>> = mutableMapOf()

    /** Fallback for [fetchOne] when no per-id entry exists. */
    var defaultOne: RemoteFetch<RemoteRecord<R, RID, KEY>> =
        RemoteFetch.Missing(com.jorisjonkers.personalstack.common.sync.domain.MissingReason.NOT_FOUND)

    /** Canned response for [fetchForScope]. */
    var scopeResponse: RemoteFetch<RemotePage<R, RID, KEY>> =
        RemoteFetch.Found(RemotePage(changes = emptyList(), nextCursor = null, highWatermark = null))

    /** Ordered queue of [fetchPage] responses; once exhausted, [defaultPage] is returned. */
    val pageResponses: ArrayDeque<RemoteFetch<RemotePage<R, RID, KEY>>> = ArrayDeque()
    var defaultPage: RemoteFetch<RemotePage<R, RID, KEY>> =
        RemoteFetch.Found(RemotePage(changes = emptyList(), nextCursor = null, highWatermark = null))

    /** Recorded calls, for assertions. */
    val fetchOneCalls: MutableList<RID> = mutableListOf()
    val fetchPageCursors: MutableList<SyncCursor?> = mutableListOf()

    fun onFetchOne(
        remoteId: RID,
        response: RemoteFetch<RemoteRecord<R, RID, KEY>>,
    ) = apply {
        oneResponses[remoteId] = response
    }

    fun foundOne(
        remoteId: RID,
        record: RemoteRecord<R, RID, KEY>,
    ) = onFetchOne(remoteId, RemoteFetch.Found(record))

    fun onFetchForScope(response: RemoteFetch<RemotePage<R, RID, KEY>>) = apply { scopeResponse = response }

    fun enqueuePage(response: RemoteFetch<RemotePage<R, RID, KEY>>) = apply { pageResponses.addLast(response) }

    override fun fetchOne(
        remoteId: RID,
        context: SyncContext<SCOPE>,
    ): RemoteFetch<RemoteRecord<R, RID, KEY>> {
        fetchOneCalls.add(remoteId)
        return oneResponses[remoteId] ?: defaultOne
    }

    override fun fetchForScope(
        scope: SCOPE,
        context: SyncContext<SCOPE>,
    ): RemoteFetch<RemotePage<R, RID, KEY>> = scopeResponse

    override fun fetchPage(
        scope: SCOPE?,
        cursor: SyncCursor?,
        pageSize: Int,
        context: SyncContext<SCOPE>,
    ): RemoteFetch<RemotePage<R, RID, KEY>> {
        fetchPageCursors.add(cursor)
        return if (pageResponses.isNotEmpty()) pageResponses.removeFirst() else defaultPage
    }
}

// ---------------------------------------------------------------------------------------------
// LockManager
// ---------------------------------------------------------------------------------------------

/**
 * In-memory [LockManager]. By default grants every lock and runs the block. Set [grant] to `false`
 * to deny all locks, or use [denyKeys] to deny specific keys. Records every requested key.
 */
class InMemoryLockManager(
    var grant: Boolean = true,
) : LockManager {
    val denyKeys: MutableSet<String> = mutableSetOf()
    val requested: MutableList<SyncLockKey> = mutableListOf()

    fun denyAll() = apply { grant = false }

    fun deny(key: String) = apply { denyKeys.add(key) }

    override fun <T> withLock(
        key: SyncLockKey,
        timeout: Duration,
        block: () -> T,
    ): LockResult<T> {
        requested.add(key)
        val denied = !grant || key.value in denyKeys
        return if (denied) LockResult.NotAcquired else LockResult.Acquired(block())
    }
}

// ---------------------------------------------------------------------------------------------
// SyncUnitOfWork
// ---------------------------------------------------------------------------------------------

/**
 * In-memory [SyncUnitOfWork]. Runs the body, collecting any after-commit callbacks. In the default
 * [commit] mode the callbacks are invoked after the body returns (simulating a successful commit).
 *
 * Set [mode] to:
 *  - [Mode.COMMIT] (default): run body, then run after-commit callbacks.
 *  - [Mode.ROLLBACK_SKIP_AFTER_COMMIT]: run body, but DROP the after-commit callbacks (simulating a
 *    rollback / a commit that never fires afterCommit). The body's return value is still returned.
 *
 * To simulate a body that throws (real rollback), have the body itself throw — the exception
 * propagates and after-commit callbacks are never run regardless of [mode].
 */
class InMemoryUnitOfWork(
    var mode: Mode = Mode.COMMIT,
) : SyncUnitOfWork {
    enum class Mode { COMMIT, ROLLBACK_SKIP_AFTER_COMMIT }

    var transactionCount: Int = 0
        private set
    var afterCommitRun: Int = 0
        private set

    override fun <T> transaction(block: SyncTransactionContext.() -> T): T {
        transactionCount++
        val callbacks = mutableListOf<() -> Unit>()
        val ctx =
            object : SyncTransactionContext {
                override fun afterCommit(action: () -> Unit) {
                    callbacks.add(action)
                }
            }
        val result = ctx.block()
        if (mode == Mode.COMMIT) {
            callbacks.forEach {
                it()
                afterCommitRun++
            }
        }
        return result
    }
}

// ---------------------------------------------------------------------------------------------
// SyncEffectOutbox
// ---------------------------------------------------------------------------------------------

/** In-memory [SyncEffectOutbox]. Records appended effects and relay requests for assertions. */
class InMemoryEffectOutbox : SyncEffectOutbox {
    val appended: MutableList<SyncEffect> = mutableListOf()
    var relayRequests: Int = 0
        private set

    override fun append(
        context: SyncContext<*>,
        effects: List<SyncEffect>,
    ) {
        appended.addAll(effects)
    }

    override fun requestRelay(context: SyncContext<*>) {
        relayRequests++
    }
}

// ---------------------------------------------------------------------------------------------
// AuditTrail
// ---------------------------------------------------------------------------------------------

/** In-memory [AuditTrail]. Records every callback for assertions. */
class InMemoryAuditTrail : AuditTrail {
    val runStarted: MutableList<SyncContext<*>> = mutableListOf()
    val outcomes: MutableList<SyncOutcome<*>> = mutableListOf()
    val runCompleted: MutableList<SyncReport> = mutableListOf()

    override fun recordRunStarted(context: SyncContext<*>) {
        runStarted.add(context)
    }

    override fun recordOutcome(
        context: SyncContext<*>,
        outcome: SyncOutcome<*>,
    ) {
        outcomes.add(outcome)
    }

    override fun recordRunCompleted(report: SyncReport) {
        runCompleted.add(report)
    }
}

// ---------------------------------------------------------------------------------------------
// SyncObserver
// ---------------------------------------------------------------------------------------------

/** In-memory [SyncObserver]. Records every callback for assertions. */
class InMemoryObserver : SyncObserver {
    val runStarted: MutableList<SyncContext<*>> = mutableListOf()
    val outcomes: MutableList<SyncOutcome<*>> = mutableListOf()
    val runCompleted: MutableList<SyncReport> = mutableListOf()

    override fun onRunStarted(context: SyncContext<*>) {
        runStarted.add(context)
    }

    override fun onOutcome(
        context: SyncContext<*>,
        outcome: SyncOutcome<*>,
    ) {
        outcomes.add(outcome)
    }

    override fun onRunCompleted(report: SyncReport) {
        runCompleted.add(report)
    }
}

// ---------------------------------------------------------------------------------------------
// IdempotencyStore
// ---------------------------------------------------------------------------------------------

/**
 * In-memory [IdempotencyStore]. By default the first [claim] for a key returns
 * [IdempotencyClaim.Acquired], a subsequent [complete] stores the report so a later claim replays
 * via [IdempotencyClaim.Completed]. Use [preClaimInProgress] / [preComplete] to force a branch on
 * the very first claim.
 */
class InMemoryIdempotencyStore : IdempotencyStore {
    private sealed interface State {
        data object InProgress : State

        data class Completed(
            val report: SyncReport,
        ) : State

        data class Failed(
            val failure: SyncFailure,
        ) : State
    }

    private val states: MutableMap<IdempotencyKey, State> = mutableMapOf()

    /** Records, for assertions. */
    val claims: MutableList<IdempotencyKey> = mutableListOf()
    val completed: MutableList<IdempotencyKey> = mutableListOf()
    val failed: MutableList<IdempotencyKey> = mutableListOf()

    /** Force the next claim for [key] to report a concurrent in-flight caller. */
    fun preClaimInProgress(key: IdempotencyKey) = apply { states[key] = State.InProgress }

    /** Force the next claim for [key] to replay a previously completed [report]. */
    fun preComplete(
        key: IdempotencyKey,
        report: SyncReport,
    ) = apply { states[key] = State.Completed(report) }

    override fun claim(
        key: IdempotencyKey,
        ttl: Duration,
        context: SyncContext<*>,
    ): IdempotencyClaim {
        claims.add(key)
        return when (val state = states[key]) {
            null -> {
                states[key] = State.InProgress
                IdempotencyClaim.Acquired
            }
            is State.InProgress -> IdempotencyClaim.InProgress("claim for ${key.value} already in progress")
            is State.Completed -> IdempotencyClaim.Completed(state.report)
            is State.Failed -> {
                // A previously failed key is re-claimable.
                states[key] = State.InProgress
                IdempotencyClaim.Acquired
            }
        }
    }

    override fun complete(
        key: IdempotencyKey,
        report: SyncReport,
    ) {
        completed.add(key)
        states[key] = State.Completed(report)
    }

    override fun fail(
        key: IdempotencyKey,
        failure: SyncFailure,
    ) {
        failed.add(key)
        states[key] = State.Failed(failure)
    }
}

// ---------------------------------------------------------------------------------------------
// SyncCheckpointStore
// ---------------------------------------------------------------------------------------------

/**
 * In-memory [SyncCheckpointStore]. Holds one cursor checkpoint per (syncName, scope) and a baseline
 * per (syncName, subject). [saveCursorIfCurrent] honours compare-and-swap; set [casAlwaysFails] to
 * force the stale-checkpoint branch.
 */
class InMemoryCheckpointStore : SyncCheckpointStore {
    private val cursors: MutableMap<Pair<SyncName, Any?>, CursorCheckpoint> = mutableMapOf()
    private val baselines: MutableMap<Pair<SyncName, String>, BaselineSnapshot> = mutableMapOf()

    /** When true, every [saveCursorIfCurrent] returns false (simulates a concurrent clobber). */
    var casAlwaysFails: Boolean = false

    val savedBaselines: MutableList<BaselineSnapshot> = mutableListOf()
    val casCalls: MutableList<CursorCheckpoint> = mutableListOf()

    /** Pre-populate the stored cursor for a (syncName, scope). */
    fun seedCursor(checkpoint: CursorCheckpoint) =
        apply { cursors[checkpoint.syncName to checkpoint.scope] = checkpoint }

    override fun loadCursor(
        syncName: SyncName,
        scope: Any?,
    ): CursorCheckpoint? = cursors[syncName to scope]

    override fun saveCursorIfCurrent(
        previous: CursorCheckpoint?,
        next: CursorCheckpoint,
    ): Boolean {
        casCalls.add(next)
        val key = next.syncName to next.scope
        val current = cursors[key]
        val shouldSave = !casAlwaysFails && current == previous
        if (shouldSave) {
            cursors[key] = next
        }
        return shouldSave
    }

    override fun loadBaseline(
        syncName: SyncName,
        subject: SyncSubject<*>,
    ): BaselineSnapshot? = baselines[syncName to subject.stableKey]

    override fun saveBaseline(
        syncName: SyncName,
        subject: SyncSubject<*>,
        baseline: BaselineSnapshot,
    ) {
        baselines[syncName to subject.stableKey] = baseline
        savedBaselines.add(baseline)
    }
}
