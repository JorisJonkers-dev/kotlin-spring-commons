package com.jorisjonkers.personalstack.common.sync.domain

import java.time.Duration
import java.time.Instant

/** Projects a consumer aggregate into the framework's [LocalRecord]; must be pure. */
fun interface LocalProjector<A : Any, RID : Any, KEY : Any> {
    fun project(local: A): LocalRecord<A, RID, KEY>
}

/** Projects a consumer remote record into the framework's [RemoteRecord]; must be pure. */
fun interface RemoteProjector<R : Any, RID : Any, KEY : Any> {
    fun project(remote: R): RemoteRecord<R, RID, KEY>
}

/** Computes the [ChangeSet] between a local aggregate and a remote record; must be pure. */
fun interface SyncDiffer<A : Any, R : Any> {
    fun diff(
        local: A,
        remote: R,
    ): ChangeSet
}

/**
 * Optional convenience that bundles the local and remote projections into one named, unit-testable
 * object. Equivalent to setting [LocalProjector] and [RemoteProjector] separately; both must be pure.
 */
interface SyncProjection<A : Any, R : Any, RID : Any, KEY : Any> {
    fun local(local: A): LocalRecord<A, RID, KEY>

    fun remote(remote: R): RemoteRecord<R, RID, KEY>
}

/**
 * The consumer's anti-corruption layer: produces new aggregate states for each executable
 * action. Implementations own aggregate invariants and field mapping. They are called inside the
 * transaction; for dry-run they are invoked only if documented pure.
 */
interface SyncMapper<A : Any, R : Any, SCOPE : Any> {
    fun create(
        remote: R,
        context: SyncContext<SCOPE>,
    ): A

    fun update(
        local: A,
        remote: R,
        changes: ChangeSet,
        context: SyncContext<SCOPE>,
    ): A

    fun restore(
        local: A,
        remote: R,
        changes: ChangeSet,
        context: SyncContext<SCOPE>,
    ): A

    fun relink(
        local: A,
        remote: R,
        changes: ChangeSet,
        context: SyncContext<SCOPE>,
    ): A

    fun delete(
        local: A,
        signal: RemoteDeleteSignal<*>,
        context: SyncContext<SCOPE>,
    ): A

    fun unlink(
        local: A,
        reason: UnlinkReason,
        context: SyncContext<SCOPE>,
    ): A
}

/** Decides whether a remote record is eligible to be imported as a new local aggregate. */
fun interface ImportPolicy<R : Any> {
    fun importable(remote: R): Boolean
}

/**
 * Decides what to do with a local record that has no authoritative remote counterpart. Only
 * reached when remote absence is authoritative (never on a [RemoteFetch.Failed]). The fail-closed
 * default a consumer should pick is a conflict; the worked example chooses delete/unlink.
 */
fun interface MissingRemotePolicy<A : Any, RID : Any, KEY : Any> {
    fun decide(
        local: LocalRecord<A, RID, KEY>,
        observedAt: Instant,
    ): SyncDecision<A, Nothing, RID>
}

/** Which side wins on divergence. The framework's reconciliation is remote-authoritative by default. */
enum class AuthorityMode {
    REMOTE_AUTHORITATIVE,
    LOCAL_AUTHORITATIVE,
    MULTI_WRITER,
}

/** Transaction granularity for list/scope sync. Per-record is the safe default. */
enum class ListTransactionMode {
    PER_RECORD,
    PER_PAGE,
    WHOLE_SCOPE,
}

/** Tunable, non-behavioral execution knobs for a sync resource. */
data class SyncExecutionOptions(
    val listTransactionMode: ListTransactionMode = ListTransactionMode.PER_RECORD,
    val pageSize: Int = 500,
    val lockTimeout: Duration = Duration.ofSeconds(DEFAULT_LOCK_TIMEOUT_SECONDS),
    val authorityMode: AuthorityMode = AuthorityMode.REMOTE_AUTHORITATIVE,
) {
    private companion object {
        private const val DEFAULT_LOCK_TIMEOUT_SECONDS = 10L
    }
}

/**
 * The decision policies for a resource. [conflictPolicy] defaults to fail-closed;
 * [missingRemotePolicy] is mandatory because absence handling is business-specific;
 * [importPolicy] defaults to importing everything.
 */
data class SyncPolicies<A : Any, R : Any, RID : Any, KEY : Any>(
    val conflictPolicy: ConflictPolicy<A, R, RID, KEY> = ConflictPolicy.failClosed(),
    val missingRemotePolicy: MissingRemotePolicy<A, RID, KEY>,
    val importPolicy: ImportPolicy<R> = ImportPolicy { true },
)

/**
 * The complete, framework-free bundle describing how to sync one resource: its name, the pure
 * strategies (projectors, differ, mapper, match plan, policies), execution options and the
 * outbound ports it is wired to. Built by the consumer (typically via the DSL).
 */
data class SyncDefinition<A : Any, R : Any, RID : Any, KEY : Any, SCOPE : Any>(
    val name: SyncName,
    val localProjector: LocalProjector<A, RID, KEY>,
    val remoteProjector: RemoteProjector<R, RID, KEY>,
    val differ: SyncDiffer<A, R>,
    val mapper: SyncMapper<A, R, SCOPE>,
    val matchPlan: MatchPlan<A, R, RID, KEY>,
    val policies: SyncPolicies<A, R, RID, KEY>,
    val execution: SyncExecutionOptions = SyncExecutionOptions(),
    val ports: SyncPorts<A, R, RID, KEY, SCOPE>,
)

/** The set of outbound ports a resource is wired to. All references are domain port interfaces. */
data class SyncPorts<A : Any, R : Any, RID : Any, KEY : Any, SCOPE : Any>(
    val localRepository: com.jorisjonkers.personalstack.common.sync.domain.port.out.LocalSyncRepository<A, RID, SCOPE>,
    val remoteCatalog: com.jorisjonkers.personalstack.common.sync.domain.port.out.RemoteCatalog<R, RID, KEY, SCOPE>,
    val lockManager: com.jorisjonkers.personalstack.common.sync.domain.port.out.LockManager,
    val unitOfWork: com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncUnitOfWork,
    val effectOutbox: com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncEffectOutbox,
    val auditTrail: com.jorisjonkers.personalstack.common.sync.domain.port.out.AuditTrail,
    val observer: com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncObserver,
    val idempotencyStore: com.jorisjonkers.personalstack.common.sync.domain.port.out.IdempotencyStore,
    val checkpointStore: com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncCheckpointStore,
)
