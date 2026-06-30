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
    val repository: InMemoryLocalSyncRepository<Widget, WidgetId, WidgetScope>,
    val remoteCatalog: InMemoryRemoteCatalog<RemoteWidget, WidgetId, WidgetKey, WidgetScope>,
    val lockManager: InMemoryLockManager,
    val unitOfWork: InMemoryUnitOfWork,
    val effectOutbox: InMemoryEffectOutbox,
    val auditTrail: InMemoryAuditTrail,
    val observer: InMemoryObserver,
    val idempotencyStore: InMemoryIdempotencyStore,
    val checkpointStore: InMemoryCheckpointStore,
    val definition: WidgetDefinition,
    /** Fixed clock used by services built from this harness; advance via [clockInstant]. */
    val clock: Clock,
) {
    companion object {
        /** A deterministic missing-remote policy that DELETES (soft) the absent local. */
        val DELETE_MISSING: MissingRemotePolicy<Widget, WidgetId, WidgetKey> =
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
        val UNLINK_MISSING: MissingRemotePolicy<Widget, WidgetId, WidgetKey> =
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
            missingRemotePolicy: MissingRemotePolicy<Widget, WidgetId, WidgetKey> = DELETE_MISSING,
            importPolicy: ImportPolicy<RemoteWidget> = ImportPolicy { true },
            conflictPolicy: ConflictPolicy<Widget, RemoteWidget, WidgetId, WidgetKey> = ConflictPolicy.failClosed(),
            listTransactionMode: ListTransactionMode = ListTransactionMode.PER_RECORD,
            authorityMode: AuthorityMode = AuthorityMode.REMOTE_AUTHORITATIVE,
            pageSize: Int = 500,
            matchPasses: List<WidgetMatchPass> = listOf(WidgetMatchPass.REMOTE_ID, WidgetMatchPass.SKU),
            fixedInstant: Instant = Instant.parse("2026-06-30T00:00:00Z"),
        ): WidgetHarness {
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

            val definition =
                syncResource<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope>(name) {
                    localRepository(repository)
                    remoteCatalog(remoteCatalog)
                    lockManager(lockManager)
                    unitOfWork(unitOfWork)
                    effectOutbox(effectOutbox)
                    auditTrail(auditTrail)
                    observer(observer)
                    idempotencyStore(idempotencyStore)
                    checkpointStore(checkpointStore)

                    localProjector { WidgetLocalProjector.project(it) }
                    remoteProjector { WidgetRemoteProjector.project(it) }
                    differ(WidgetDiffer::diff)
                    mapper(WidgetMapper)

                    matching {
                        matchPasses.forEach { p -> p.applyTo(this) }
                    }
                    policies {
                        missingRemotePolicy(missingRemotePolicy)
                        importPolicy(importPolicy)
                        conflictPolicy(conflictPolicy)
                    }
                    execution {
                        this.listTransactionMode = listTransactionMode
                        this.authorityMode = authorityMode
                        this.pageSize = pageSize
                    }
                }

            return WidgetHarness(
                repository = repository,
                remoteCatalog = remoteCatalog,
                lockManager = lockManager,
                unitOfWork = unitOfWork,
                effectOutbox = effectOutbox,
                auditTrail = auditTrail,
                observer = observer,
                idempotencyStore = idempotencyStore,
                checkpointStore = checkpointStore,
                definition = definition,
                clock = clock,
            )
        }
    }
}

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
        startedAt: Instant = Instant.parse("2026-06-30T00:00:00Z"),
        dryRun: Boolean = false,
        runId: RunId = runId(),
        correlationId: CorrelationId = correlationId(),
    ): SyncContext<WidgetScope> =
        SyncContext(
            runId = runId,
            syncName = SyncName(syncName),
            correlationId = correlationId,
            source = source,
            scope = scope,
            startedAt = startedAt,
            dryRun = dryRun,
        )

    fun remoteSubject(id: WidgetId): SyncSubject<WidgetId> = SyncSubject.Remote(id)

    fun pairSubject(
        localId: String?,
        id: WidgetId,
    ): SyncSubject<WidgetId> = SyncSubject.Pair(localId?.let { LocalId(it) }, id)

    fun ignoreReason(message: String): SyncReason = SyncReason.Policy(message)
}
