package com.jorisjonkers.personalstack.common.sync.testsupport

import com.jorisjonkers.personalstack.common.sync.domain.AuthorityMode
import com.jorisjonkers.personalstack.common.sync.domain.ConflictPolicy
import com.jorisjonkers.personalstack.common.sync.domain.CorrelationId
import com.jorisjonkers.personalstack.common.sync.domain.ImportPolicy
import com.jorisjonkers.personalstack.common.sync.domain.ListTransactionMode
import com.jorisjonkers.personalstack.common.sync.domain.LocalId
import com.jorisjonkers.personalstack.common.sync.domain.MatchConfidence
import com.jorisjonkers.personalstack.common.sync.domain.MatchingBuilder
import com.jorisjonkers.personalstack.common.sync.domain.MissingRemotePolicy
import com.jorisjonkers.personalstack.common.sync.domain.RunId
import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncDecision
import com.jorisjonkers.personalstack.common.sync.domain.SyncDefinition
import com.jorisjonkers.personalstack.common.sync.domain.SyncName
import com.jorisjonkers.personalstack.common.sync.domain.SyncPorts
import com.jorisjonkers.personalstack.common.sync.domain.SyncReason
import com.jorisjonkers.personalstack.common.sync.domain.SyncSubject
import com.jorisjonkers.personalstack.common.sync.domain.SyncTriggerSource
import com.jorisjonkers.personalstack.common.sync.domain.UnlinkReason
import com.jorisjonkers.personalstack.common.sync.domain.syncResource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Convenience aliases for the long generic tuple used across Widget sync tests.
 */
internal typealias WidgetDefinition =
    SyncDefinition<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope>

/**
 * A complete, in-memory Widget sync setup: all nine fakes plus a built [SyncDefinition]. Tests
 * mutate the public adapter fields to drive branches, then build use cases off [definition] (or via
 * [com.jorisjonkers.personalstack.common.sync.application.service.SyncUseCaseFactory]).
 *
 * Defaults give a remote-authoritative, per-record, import-everything resource whose
 * missing-remote policy soft-deletes locals (delete) — a sensible, fully-wired baseline. Override
 * any of these through [WidgetHarness.build]'s parameters.
 */
class WidgetHarness internal constructor(
    private val stores: WidgetHarnessStores,
    private val runtime: WidgetHarnessRuntime,
    val definition: WidgetDefinition,
    /** Fixed clock used by services built from this harness; advance via [clockInstant]. */
    val clock: Clock,
) {
    val repository: InMemoryLocalSyncRepository<Widget, WidgetId, WidgetScope>
        get() = stores.repository
    val remoteCatalog: InMemoryRemoteCatalog<RemoteWidget, WidgetId, WidgetKey, WidgetScope>
        get() = stores.remoteCatalog
    val checkpointStore: InMemoryCheckpointStore
        get() = stores.checkpointStore
    val lockManager: InMemoryLockManager
        get() = runtime.lockManager
    val unitOfWork: InMemoryUnitOfWork
        get() = runtime.unitOfWork
    val effectOutbox: InMemoryEffectOutbox
        get() = runtime.effectOutbox
    val auditTrail: InMemoryAuditTrail
        get() = runtime.auditTrail
    val observer: InMemoryObserver
        get() = runtime.observer
    val idempotencyStore: InMemoryIdempotencyStore
        get() = runtime.idempotencyStore

    companion object {
        /** A deterministic missing-remote policy that DELETES (soft) the absent local. */
        val DELETE_MISSING: MissingRemotePolicy<Widget, RemoteWidget, WidgetId, WidgetKey> =
            MissingRemotePolicy { local, observedAt ->
                val rememberedId = local.registration.rememberedRemoteId
                if (rememberedId != null) {
                    SyncDecision.Delete(
                        local = local,
                        signal =
                            com.jorisjonkers.personalstack.common.sync.domain.RemoteDeleteSignal
                                .MissingFromAuthoritativeList(
                                    remoteId = rememberedId,
                                    observedAt = observedAt,
                                ),
                    )
                } else {
                    SyncDecision.Unlink(local = local, unlinkReason = UnlinkReason.ManualDetach)
                }
            }

        /** A deterministic missing-remote policy that UNLINKS the absent local. */
        val UNLINK_MISSING: MissingRemotePolicy<Widget, RemoteWidget, WidgetId, WidgetKey> =
            MissingRemotePolicy { local, _ ->
                SyncDecision.Unlink(local = local, unlinkReason = UnlinkReason.Policy("absent on remote"))
            }

        /**
         * Build a fully-wired Widget harness. Every strategy/option has a default; override to vary
         * behavior. The match plan is built from [matchPasses] (defaults: a HARD remote-id pass plus
         * a NATURAL_KEY SKU pass).
         */
        fun build(
            name: String = "widget",
            policySet: WidgetHarnessPolicies = WidgetHarnessPolicies(),
            execution: WidgetHarnessExecution = WidgetHarnessExecution(),
            matchPasses: List<WidgetMatchPass> = listOf(WidgetMatchPass.REMOTE_ID, WidgetMatchPass.SKU),
            fixedInstant: Instant = Instant.parse("2026-06-30T00:00:00Z"),
        ): WidgetHarness {
            val parts = createHarnessParts(fixedInstant)
            val definition =
                definition(
                    name = name,
                    policySet = policySet,
                    execution = execution,
                    matchPasses = matchPasses,
                    ports = parts.ports,
                )
            return WidgetHarness(
                stores = parts.stores,
                runtime = parts.runtime,
                definition = definition,
                clock = parts.clock,
            )
        }

        private fun createHarnessParts(fixedInstant: Instant): WidgetHarnessParts {
            val repository =
                InMemoryLocalSyncRepository<Widget, WidgetId, WidgetScope>(
                    keyOf = { it.registration.remoteId ?: it.registration.rememberedRemoteId },
                    scopeOf = { it.scope },
                )
            val remoteCatalog = InMemoryRemoteCatalog<RemoteWidget, WidgetId, WidgetKey, WidgetScope>()
            val lockManager = InMemoryLockManager()
            val unitOfWork = InMemoryUnitOfWork()
            val effectOutbox = InMemoryEffectOutbox()
            val auditTrail = InMemoryAuditTrail()
            val observer = InMemoryObserver()
            val idempotencyStore = InMemoryIdempotencyStore()
            val checkpointStore = InMemoryCheckpointStore()
            val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
            val stores = WidgetHarnessStores(repository, remoteCatalog, checkpointStore)
            val runtime =
                WidgetHarnessRuntime(
                    lockManager = lockManager,
                    unitOfWork = unitOfWork,
                    effectOutbox = effectOutbox,
                    auditTrail = auditTrail,
                    observer = observer,
                    idempotencyStore = idempotencyStore,
                )
            val ports =
                SyncPorts(
                    localRepository = repository,
                    remoteCatalog = remoteCatalog,
                    lockManager = lockManager,
                    unitOfWork = unitOfWork,
                    effectOutbox = effectOutbox,
                    auditTrail = auditTrail,
                    observer = observer,
                    idempotencyStore = idempotencyStore,
                    checkpointStore = checkpointStore,
                )
            return WidgetHarnessParts(
                stores = stores,
                runtime = runtime,
                ports = ports,
                clock = clock,
            )
        }

        private fun definition(
            name: String,
            policySet: WidgetHarnessPolicies,
            execution: WidgetHarnessExecution,
            matchPasses: List<WidgetMatchPass>,
            ports: SyncPorts<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope>,
        ): WidgetDefinition =
            syncResource<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope>(name) {
                ports(ports)

                localProjector { WidgetLocalProjector.project(it) }
                remoteProjector { WidgetRemoteProjector.project(it) }
                differ(WidgetDiffer::diff)
                mapper(WidgetMapper)

                matching {
                    matchPasses.forEach { p -> p.applyTo(this) }
                }
                policies {
                    missingRemotePolicy(policySet.missingRemotePolicy)
                    importPolicy(policySet.importPolicy)
                    conflictPolicy(policySet.conflictPolicy)
                }
                execution {
                    this.listTransactionMode = execution.listTransactionMode
                    this.authorityMode = execution.authorityMode
                    this.pageSize = execution.pageSize
                }
            }
    }
}

internal data class WidgetHarnessParts(
    val stores: WidgetHarnessStores,
    val runtime: WidgetHarnessRuntime,
    val ports: SyncPorts<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope>,
    val clock: Clock,
)

internal data class WidgetHarnessStores(
    val repository: InMemoryLocalSyncRepository<Widget, WidgetId, WidgetScope>,
    val remoteCatalog: InMemoryRemoteCatalog<RemoteWidget, WidgetId, WidgetKey, WidgetScope>,
    val checkpointStore: InMemoryCheckpointStore,
)

internal data class WidgetHarnessRuntime(
    val lockManager: InMemoryLockManager,
    val unitOfWork: InMemoryUnitOfWork,
    val effectOutbox: InMemoryEffectOutbox,
    val auditTrail: InMemoryAuditTrail,
    val observer: InMemoryObserver,
    val idempotencyStore: InMemoryIdempotencyStore,
)

data class WidgetHarnessPolicies(
    val missingRemotePolicy: MissingRemotePolicy<Widget, RemoteWidget, WidgetId, WidgetKey> =
        WidgetHarness.DELETE_MISSING,
    val importPolicy: ImportPolicy<RemoteWidget> = ImportPolicy { true },
    val conflictPolicy: ConflictPolicy<Widget, RemoteWidget, WidgetId, WidgetKey> = ConflictPolicy.failClosed(),
)

data class WidgetHarnessExecution(
    val listTransactionMode: ListTransactionMode = ListTransactionMode.PER_RECORD,
    val authorityMode: AuthorityMode = AuthorityMode.REMOTE_AUTHORITATIVE,
    val pageSize: Int = 500,
)

/**
 * Pre-built match passes for the Widget match plan. Use them in [WidgetHarness.build]'s
 * `matchPasses` list to vary matching behavior.
 */
enum class WidgetMatchPass {
    /** Hard remote-id pass. */
    REMOTE_ID,

    /** Soft natural-key pass on SKU (drives relink-by-natural-key). */
    SKU,
    ;

    internal fun applyTo(builder: MatchingBuilder<Widget, RemoteWidget, WidgetId, WidgetKey>) {
        when (this) {
            REMOTE_ID ->
                builder.pass(
                    name = "remote-id",
                    confidence = MatchConfidence.HARD,
                    localKeys = { lr -> lr.keys.filterIsInstance<WidgetKey.Remote>().toSet() },
                    remoteKeys = { rr -> rr.keys.filterIsInstance<WidgetKey.Remote>().toSet() },
                )
            SKU ->
                builder.pass(
                    name = "sku",
                    confidence = MatchConfidence.NATURAL_KEY,
                    localKeys = { lr -> lr.keys.filterIsInstance<WidgetKey.Sku>().toSet() },
                    remoteKeys = { rr -> rr.keys.filterIsInstance<WidgetKey.Sku>().toSet() },
                )
        }
    }
}

/**
 * Fixture factories for ambient sync values, so tests do not hand-roll [SyncContext]/[RunId] etc.
 */
object SyncFixtures {
    fun runId(value: String = "00000000-0000-0000-0000-000000000001"): RunId = RunId(java.util.UUID.fromString(value))

    fun correlationId(value: String = "corr-1"): CorrelationId = CorrelationId(value)

    fun context(
        syncName: String = "widget",
        scope: WidgetScope? = null,
        source: SyncTriggerSource = SyncTriggerSource.TEST,
        options: SyncContextFixtureOptions = SyncContextFixtureOptions(),
    ): SyncContext<WidgetScope> =
        SyncContext(
            runId = options.runId,
            syncName = SyncName(syncName),
            correlationId = options.correlationId,
            source = source,
            scope = scope,
            startedAt = options.startedAt,
            dryRun = options.dryRun,
        )

    fun remoteSubject(id: WidgetId): SyncSubject<WidgetId> = SyncSubject.Remote(id)

    fun pairSubject(
        localId: String?,
        id: WidgetId,
    ): SyncSubject<WidgetId> = SyncSubject.Pair(localId?.let { LocalId(it) }, id)

    fun ignoreReason(message: String): SyncReason = SyncReason.Policy(message)
}

data class SyncContextFixtureOptions(
    val startedAt: Instant = Instant.parse("2026-06-30T00:00:00Z"),
    val dryRun: Boolean = false,
    val runId: RunId = SyncFixtures.runId(),
    val correlationId: CorrelationId = SyncFixtures.correlationId(),
)
