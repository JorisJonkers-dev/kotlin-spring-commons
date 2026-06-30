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
 *     matching { pass(name = "remote-id", confidence = MatchConfidence.HARD, localKeys = { ... }, remoteKeys = { ... }) }
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

/** Mutable builder backing [syncResource]. Not thread-safe; build once at wiring time. */
@SyncDslMarker
class SyncResourceBuilder<A : Any, R : Any, RID : Any, KEY : Any, SCOPE : Any>(
    private val name: SyncName,
) {
    private var localRepository: LocalSyncRepository<A, RID, SCOPE>? = null
    private var remoteCatalog: RemoteCatalog<R, RID, KEY, SCOPE>? = null
    private var lockManager: LockManager? = null
    private var unitOfWork: SyncUnitOfWork? = null
    private var effectOutbox: SyncEffectOutbox? = null
    private var auditTrail: AuditTrail? = null
    private var observer: SyncObserver? = null
    private var idempotencyStore: IdempotencyStore? = null
    private var checkpointStore: SyncCheckpointStore? = null

    private var localProjector: LocalProjector<A, RID, KEY>? = null
    private var remoteProjector: RemoteProjector<R, RID, KEY>? = null
    private var differ: SyncDiffer<A, R>? = null
    private var mapper: SyncMapper<A, R, SCOPE>? = null

    private val matching = MatchingBuilder<A, R, RID, KEY>()
    private val policies = PoliciesBuilder<A, R, RID, KEY>()
    private var execution = SyncExecutionOptions()

    // --- outbound ports (mandatory ones are checked in build()) -----------------------------
    fun localRepository(port: LocalSyncRepository<A, RID, SCOPE>) { localRepository = port }
    fun remoteCatalog(port: RemoteCatalog<R, RID, KEY, SCOPE>) { remoteCatalog = port }
    fun lockManager(port: LockManager) { lockManager = port }
    fun unitOfWork(port: SyncUnitOfWork) { unitOfWork = port }
    fun effectOutbox(port: SyncEffectOutbox) { effectOutbox = port }
    fun auditTrail(port: AuditTrail) { auditTrail = port }
    fun observer(port: SyncObserver) { observer = port }
    fun idempotencyStore(port: IdempotencyStore) { idempotencyStore = port }
    fun checkpointStore(port: SyncCheckpointStore) { checkpointStore = port }

    // --- pure strategies --------------------------------------------------------------------
    fun localProjector(project: (A) -> LocalRecord<A, RID, KEY>) {
        localProjector = LocalProjector { project(it) }
    }

    fun remoteProjector(project: (R) -> RemoteRecord<R, RID, KEY>) {
        remoteProjector = RemoteProjector { project(it) }
    }

    fun differ(diff: (A, R) -> ChangeSet) {
        differ = SyncDiffer { local, remote -> diff(local, remote) }
    }

    fun mapper(mapper: SyncMapper<A, R, SCOPE>) { this.mapper = mapper }

    fun matching(block: MatchingBuilder<A, R, RID, KEY>.() -> Unit) { matching.apply(block) }

    fun policies(block: PoliciesBuilder<A, R, RID, KEY>.() -> Unit) { policies.apply(block) }

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

    private fun <T> requireConfigured(value: T?, what: String): T =
        requireNonNull(value) { "syncResource(\"${name.value}\"): $what must be configured" }

    // Local helper to keep the message lazy without pulling in extra imports.
    private inline fun <T> requireNonNull(value: T?, message: () -> String): T =
        value ?: throw IllegalStateException(message())
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
    private var missingRemotePolicy: MissingRemotePolicy<A, RID, KEY>? = null

    fun conflictPolicy(policy: ConflictPolicy<A, R, RID, KEY>) { conflictPolicy = policy }

    fun importPolicy(policy: ImportPolicy<R>) { importPolicy = policy }

    fun missingRemotePolicy(policy: MissingRemotePolicy<A, RID, KEY>) { missingRemotePolicy = policy }

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
class ExecutionBuilder(initial: SyncExecutionOptions) {
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
