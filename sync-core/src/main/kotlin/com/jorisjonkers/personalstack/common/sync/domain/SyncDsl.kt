package com.jorisjonkers.personalstack.common.sync.domain

import com.jorisjonkers.personalstack.common.sync.domain.port.out.AuditTrail
import com.jorisjonkers.personalstack.common.sync.domain.port.out.IdempotencyStore
import com.jorisjonkers.personalstack.common.sync.domain.port.out.LocalSyncRepository
import com.jorisjonkers.personalstack.common.sync.domain.port.out.LockManager
import com.jorisjonkers.personalstack.common.sync.domain.port.out.RemoteCatalog
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncCheckpointStore
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncEffectOutbox
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncObserver
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncUnitOfWork
import java.time.Instant

/**
 * Restricts implicit receivers inside the [syncResource] DSL so a nested block cannot accidentally
 * call a method on an outer builder.
 */
@DslMarker
annotation class SyncDslMarker

/**
 * Entry point of the consumer-facing DSL. Builds a fully-typed [SyncDefinition] for one resource
 * from pure strategies (projectors, differ, mapper, match passes, policies) plus the outbound
 * ports it is wired to. The framework owns correlation, matching, lifecycle and reporting; the
 * consumer owns aggregate invariants, field mapping and persistence.
 *
 * The DSL is framework-free: it lives in the domain layer and references only domain types and
 * domain port interfaces, so it composes with any adapter set (Spring or test fakes).
 *
 * ```kotlin
 * val definition = syncResource<Employee, HrEmployeeDto, HrEmployeeId, EmployeeSyncKey, DepartmentId>("employee") {
 *     localRepository(repo); remoteCatalog(catalog); lockManager(locks); unitOfWork(uow)
 *     localProjector { e -> LocalRecord(...) }
 *     remoteProjector { r -> RemoteRecord(...) }
 *     differ(EmployeeDiffer::diff)
 *     mapper(EmployeeMapper)
 *     matching {
 *         pass(name = "remote-id", confidence = MatchConfidence.HARD, localKeys = { ... }, remoteKeys = { ... })
 *     }
 *     policies { missingRemotePolicy(MissingRemotePolicy { local, at -> ... }) }
 *     execution { listTransactionMode = ListTransactionMode.PER_RECORD; pageSize = 500 }
 * }
 * ```
 */
fun <A : Any, R : Any, RID : Any, KEY : Any, SCOPE : Any> syncResource(
    name: String,
    block: SyncResourceBuilder<A, R, RID, KEY, SCOPE>.() -> Unit,
): SyncDefinition<A, R, RID, KEY, SCOPE> =
    SyncResourceBuilder<A, R, RID, KEY, SCOPE>(SyncName(name)).apply(block).build()

/**
 * Mutable builder backing [syncResource]. Not thread-safe; build once at wiring time.
 *
 * Intentionally exposes one setter per port/strategy so call sites stay explicit.
 */
@SyncDslMarker
class SyncResourceBuilder<A : Any, R : Any, RID : Any, KEY : Any, SCOPE : Any>(
    private val name: SyncName,
) : SyncResourcePortBuilder<A, R, RID, KEY, SCOPE>() {
    private var localProjector: LocalProjector<A, RID, KEY>? = null
    private var remoteProjector: RemoteProjector<R, RID, KEY>? = null
    private var differ: SyncDiffer<A, R>? = null
    private var mapper: SyncMapper<A, R, RID, SCOPE>? = null

    private val matching = MatchingBuilder<A, R, RID, KEY>()
    private val policies = PoliciesBuilder<A, R, RID, KEY>()
    private var execution = SyncExecutionOptions()

    // --- pure strategies --------------------------------------------------------------------

    /** Set both projectors from one named [SyncProjection] object (equivalent to the two setters). */
    fun projection(projection: SyncProjection<A, R, RID, KEY>) {
        localProjector { projection.local(it) }
        remoteProjector { projection.remote(it) }
    }

    fun localProjector(project: (A) -> LocalRecord<A, RID, KEY>) {
        localProjector = LocalProjector { project(it) }
    }

    fun remoteProjector(project: (R) -> RemoteRecord<R, RID, KEY>) {
        remoteProjector = RemoteProjector { project(it) }
    }

    fun differ(diff: (A, R) -> ChangeSet) {
        differ = SyncDiffer { local, remote -> diff(local, remote) }
    }

    fun mapper(mapper: SyncMapper<A, R, RID, SCOPE>) {
        this.mapper = mapper
    }

    fun matching(block: MatchingBuilder<A, R, RID, KEY>.() -> Unit) {
        matching.apply(block)
    }

    fun policies(block: PoliciesBuilder<A, R, RID, KEY>.() -> Unit) {
        policies.apply(block)
    }

    fun execution(block: ExecutionBuilder.() -> Unit) {
        execution = ExecutionBuilder(execution).apply(block).build()
    }

    fun build(): SyncDefinition<A, R, RID, KEY, SCOPE> =
        SyncDefinition(
            name = name,
            localProjector = requireConfigured(localProjector, "localProjector"),
            remoteProjector = requireConfigured(remoteProjector, "remoteProjector"),
            differ = requireConfigured(differ, "differ"),
            mapper = requireConfigured(mapper, "mapper"),
            matchPlan = matching.build(),
            policies = policies.build(),
            execution = execution,
            ports =
                SyncPorts(
                    localRepository = requireConfigured(localRepository, "localRepository"),
                    remoteCatalog = requireConfigured(remoteCatalog, "remoteCatalog"),
                    lockManager = requireConfigured(lockManager, "lockManager"),
                    unitOfWork = requireConfigured(unitOfWork, "unitOfWork"),
                    effectOutbox = requireConfigured(effectOutbox, "effectOutbox"),
                    auditTrail = requireConfigured(auditTrail, "auditTrail"),
                    observer = requireConfigured(observer, "observer"),
                    idempotencyStore = requireConfigured(idempotencyStore, "idempotencyStore"),
                    checkpointStore = requireConfigured(checkpointStore, "checkpointStore"),
                ),
        )

    private fun <T> requireConfigured(
        value: T?,
        what: String,
    ): T = value ?: throw IllegalStateException("syncResource(\"${name.value}\"): $what must be configured")
}

/** Outbound port wiring for [SyncResourceBuilder]. */
@SyncDslMarker
open class SyncResourcePortBuilder<A : Any, R : Any, RID : Any, KEY : Any, SCOPE : Any> {
    protected var localRepository: LocalSyncRepository<A, RID, SCOPE>? = null
    protected var remoteCatalog: RemoteCatalog<R, RID, KEY, SCOPE>? = null
    protected var lockManager: LockManager? = null
    protected var unitOfWork: SyncUnitOfWork? = null
    protected var effectOutbox: SyncEffectOutbox? = null
    protected var auditTrail: AuditTrail? = null
    protected var observer: SyncObserver? = null
    protected var idempotencyStore: IdempotencyStore? = null
    protected var checkpointStore: SyncCheckpointStore? = null

    fun localRepository(port: LocalSyncRepository<A, RID, SCOPE>) {
        localRepository = port
    }

    fun remoteCatalog(port: RemoteCatalog<R, RID, KEY, SCOPE>) {
        remoteCatalog = port
    }

    fun lockManager(port: LockManager) {
        lockManager = port
    }

    fun unitOfWork(port: SyncUnitOfWork) {
        unitOfWork = port
    }

    fun effectOutbox(port: SyncEffectOutbox) {
        effectOutbox = port
    }

    fun auditTrail(port: AuditTrail) {
        auditTrail = port
    }

    fun observer(port: SyncObserver) {
        observer = port
    }

    fun idempotencyStore(port: IdempotencyStore) {
        idempotencyStore = port
    }

    fun checkpointStore(port: SyncCheckpointStore) {
        checkpointStore = port
    }

    /**
     * Wire all nine outbound ports at once from a prepared [SyncPorts] bag. Sugar for the
     * individual setters (last call wins), letting a consumer declare ports as a separate bean.
     */
    fun ports(ports: SyncPorts<A, R, RID, KEY, SCOPE>) {
        localRepository(ports.localRepository)
        remoteCatalog(ports.remoteCatalog)
        lockManager(ports.lockManager)
        unitOfWork(ports.unitOfWork)
        effectOutbox(ports.effectOutbox)
        auditTrail(ports.auditTrail)
        observer(ports.observer)
        idempotencyStore(ports.idempotencyStore)
        checkpointStore(ports.checkpointStore)
    }
}

/** Collects the ordered list of [MatchPass]es for a resource. */
@SyncDslMarker
class MatchingBuilder<A : Any, R : Any, RID : Any, KEY : Any> {
    private val passes = mutableListOf<MatchPass<A, R, RID, KEY>>()

    fun pass(
        name: String,
        confidence: MatchConfidence = MatchConfidence.HARD,
        localKeys: (LocalRecord<A, RID, KEY>) -> Set<KEY>,
        remoteKeys: (RemoteRecord<R, RID, KEY>) -> Set<KEY>,
    ) {
        passes += MatchPass(name = name, localKeys = localKeys, remoteKeys = remoteKeys, confidence = confidence)
    }

    /** Hard pass: the local *active* remote id against the remote external id. */
    fun remoteId(
        keyOf: (RID) -> KEY,
        name: String = "remote-id",
    ) {
        pass(
            name = name,
            confidence = MatchConfidence.HARD,
            localKeys = { local -> local.registration.remoteId?.let { setOf(keyOf(it)) } ?: emptySet() },
            remoteKeys = { remote -> setOf(keyOf(remote.externalId)) },
        )
    }

    /** Soft pass: the local *remembered* remote id against the remote external id (re-link). */
    fun rememberedRemoteId(
        keyOf: (RID) -> KEY,
        name: String = "remembered-remote-id",
    ) {
        pass(
            name = name,
            confidence = MatchConfidence.REMEMBERED_REMOTE_ID,
            localKeys = { local -> local.registration.rememberedRemoteId?.let { setOf(keyOf(it)) } ?: emptySet() },
            remoteKeys = { remote -> setOf(keyOf(remote.externalId)) },
        )
    }

    /**
     * Natural-key pass over the projected [LocalRecord.keys]/[RemoteRecord.keys] of subtype [keyClass].
     * Filtering is by erased runtime class, so the key must be a concrete subtype, not itself generic.
     * [name] defaults to the key class's simple name (e.g. `natural(Email::class.java)` -> "Email").
     */
    fun <K : KEY> natural(
        keyClass: Class<K>,
        name: String = keyClass.simpleName,
        confidence: MatchConfidence = MatchConfidence.NATURAL_KEY,
    ) {
        pass(
            name = name,
            confidence = confidence,
            localKeys = { local -> local.keys.filterIsInstance(keyClass).toSet() },
            remoteKeys = { remote -> remote.keys.filterIsInstance(keyClass).toSet() },
        )
    }

    fun build(): MatchPlan<A, R, RID, KEY> = MatchPlan(passes.toList())
}

/**
 * Collects decision policies. [missingRemotePolicy] is mandatory (absence handling is
 * business-specific); the others default safely (fail-closed conflicts, import everything).
 */
@SyncDslMarker
class PoliciesBuilder<A : Any, R : Any, RID : Any, KEY : Any> {
    private var conflictPolicy: ConflictPolicy<A, R, RID, KEY> = ConflictPolicy.failClosed()
    private var importPolicy: ImportPolicy<R> = ImportPolicy { true }
    private var missingRemotePolicy: MissingRemotePolicy<A, R, RID, KEY>? = null

    fun conflictPolicy(policy: ConflictPolicy<A, R, RID, KEY>) {
        conflictPolicy = policy
    }

    fun importPolicy(policy: ImportPolicy<R>) {
        importPolicy = policy
    }

    fun missingRemotePolicy(policy: MissingRemotePolicy<A, R, RID, KEY>) {
        missingRemotePolicy = policy
    }

    fun build(): SyncPolicies<A, R, RID, KEY> =
        SyncPolicies(
            conflictPolicy = conflictPolicy,
            missingRemotePolicy =
                missingRemotePolicy
                    ?: error("policies { } must declare a missingRemotePolicy — absence handling is business-specific"),
            importPolicy = importPolicy,
        )
}

/** Mutable view over [SyncExecutionOptions] so the DSL can set knobs by assignment. */
@SyncDslMarker
class ExecutionBuilder(
    initial: SyncExecutionOptions,
) {
    var listTransactionMode: ListTransactionMode = initial.listTransactionMode
    var pageSize: Int = initial.pageSize
    var lockTimeout: java.time.Duration = initial.lockTimeout
    var authorityMode: AuthorityMode = initial.authorityMode

    fun build(): SyncExecutionOptions =
        SyncExecutionOptions(
            listTransactionMode = listTransactionMode,
            pageSize = pageSize,
            lockTimeout = lockTimeout,
            authorityMode = authorityMode,
        )
}

// --- consumer projection helpers ------------------------------------------------------------
// Reduce the boilerplate of hand-building LocalRecord/RemoteRecord/SyncRegistration inside a
// projector, while compiling down to the same domain types. The raw constructors stay available.

/** Build a match-key set from possibly-null keys, dropping nulls and collapsing duplicates. */
fun <KEY : Any> syncKeys(vararg keys: KEY?): Set<KEY> = keys.filterNotNull().toSet()

/**
 * Build a [LocalRecord] with an inferred link [SyncRegistration] (see [SyncRegistration.inferred]).
 * [rememberedRemoteId] is NOT defaulted from [remoteId] — pass it explicitly to retain link history.
 */
fun <A : Any, RID : Any, KEY : Any> localRecord(
    aggregate: A,
    localId: LocalId?,
    remoteId: RID?,
    rememberedRemoteId: RID? = null,
    remotelyDeletedAt: Instant? = null,
    changedAt: Instant? = remotelyDeletedAt,
    version: VersionStamp? = null,
    lifecycle: SyncRegistrationLifecycle? = null,
    keys: Iterable<KEY> = emptyList(),
): LocalRecord<A, RID, KEY> =
    LocalRecord(
        aggregate = aggregate,
        localId = localId,
        registration =
            SyncRegistration.inferred(
                remoteId = remoteId,
                rememberedRemoteId = rememberedRemoteId,
                remotelyDeletedAt = remotelyDeletedAt,
                changedAt = changedAt,
                version = version,
                lifecycle = lifecycle,
            ),
        keys = keys.toSet(),
    )

/**
 * Build a [RemoteRecord]. [deleted] drives only the lifecycle (ACTIVE vs DELETED); import
 * eligibility stays an explicit [importable] (default `true`, matching the constructor) so the
 * two never become implicitly coupled.
 */
fun <R : Any, RID : Any, KEY : Any> remoteRecord(
    record: R,
    externalId: RID,
    keys: Iterable<KEY> = emptyList(),
    deleted: Boolean = false,
    importable: Boolean = true,
    version: VersionStamp? = null,
    observedAt: Instant? = null,
): RemoteRecord<R, RID, KEY> =
    RemoteRecord(
        record = record,
        externalId = externalId,
        keys = keys.toSet(),
        lifecycle = if (deleted) RemoteRecordLifecycle.DELETED else RemoteRecordLifecycle.ACTIVE,
        importable = importable,
        version = version,
        observedAt = observedAt,
    )
