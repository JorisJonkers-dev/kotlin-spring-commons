package com.jorisjonkers.personalstack.common.sync.domain

import com.jorisjonkers.personalstack.common.sync.testsupport.InMemoryAuditTrail
import com.jorisjonkers.personalstack.common.sync.testsupport.InMemoryCheckpointStore
import com.jorisjonkers.personalstack.common.sync.testsupport.InMemoryEffectOutbox
import com.jorisjonkers.personalstack.common.sync.testsupport.InMemoryIdempotencyStore
import com.jorisjonkers.personalstack.common.sync.testsupport.InMemoryLocalSyncRepository
import com.jorisjonkers.personalstack.common.sync.testsupport.InMemoryLockManager
import com.jorisjonkers.personalstack.common.sync.testsupport.InMemoryObserver
import com.jorisjonkers.personalstack.common.sync.testsupport.InMemoryRemoteCatalog
import com.jorisjonkers.personalstack.common.sync.testsupport.InMemoryUnitOfWork
import com.jorisjonkers.personalstack.common.sync.testsupport.RemoteWidget
import com.jorisjonkers.personalstack.common.sync.testsupport.SyncFixtures
import com.jorisjonkers.personalstack.common.sync.testsupport.Widget
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetDiffer
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetHarness
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetId
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetKey
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetLocalProjector
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetMapper
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetRemoteProjector
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

private typealias DslMissingRemotePolicy = MissingRemotePolicy<Widget, RemoteWidget, WidgetId, WidgetKey>

class SyncDslTest {
    // Structural threshold: this DSL smoke test intentionally exercises every setter in one definition.
    @Test
    fun `syncResource wires every setter and sub-builder override`() {
        val fixture = dslWiringFixture()
        val definition = buildDslDefinition(fixture)

        assertDslWiring(definition, fixture)
        assertDslStrategies(definition)
    }

    private fun dslWiringFixture(): DslWiringFixture =
        DslWiringFixture(
            repository = widgetRepository(),
            remoteCatalog = InMemoryRemoteCatalog(),
            lockManager = InMemoryLockManager(),
            unitOfWork = InMemoryUnitOfWork(),
            effectOutbox = InMemoryEffectOutbox(),
            auditTrail = InMemoryAuditTrail(),
            observer = InMemoryObserver(),
            idempotencyStore = InMemoryIdempotencyStore(),
            checkpointStore = InMemoryCheckpointStore(),
            missingRemotePolicy =
                MissingRemotePolicy { local, _ ->
                    SyncDecision.Unlink(local, UnlinkReason.Policy("custom missing"))
                },
            importPolicy = ImportPolicy { remote -> remote.importable && remote.sku.startsWith("SKU") },
            conflictPolicy = ConflictPolicy { conflict -> SyncDecision.Conflict(conflict) },
        )

    private fun buildDslDefinition(
        fixture: DslWiringFixture,
    ): SyncDefinition<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope> =
        syncResource<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope>("widget-dsl") {
            localRepository(fixture.repository)
            remoteCatalog(fixture.remoteCatalog)
            lockManager(fixture.lockManager)
            unitOfWork(fixture.unitOfWork)
            effectOutbox(fixture.effectOutbox)
            auditTrail(fixture.auditTrail)
            observer(fixture.observer)
            idempotencyStore(fixture.idempotencyStore)
            checkpointStore(fixture.checkpointStore)
            localProjector { WidgetLocalProjector.project(it) }
            remoteProjector { WidgetRemoteProjector.project(it) }
            differ(WidgetDiffer::diff)
            mapper(WidgetMapper)
            matching {
                pass(
                    name = "remote-id",
                    localKeys = { record -> record.keys.filterIsInstance<WidgetKey.Remote>().toSet() },
                    remoteKeys = { record -> record.keys.filterIsInstance<WidgetKey.Remote>().toSet() },
                )
                pass(
                    name = "sku",
                    confidence = MatchConfidence.NATURAL_KEY,
                    localKeys = { record -> record.keys.filterIsInstance<WidgetKey.Sku>().toSet() },
                    remoteKeys = { record -> record.keys.filterIsInstance<WidgetKey.Sku>().toSet() },
                )
            }
            policies {
                missingRemotePolicy(fixture.missingRemotePolicy)
                importPolicy(fixture.importPolicy)
                conflictPolicy(fixture.conflictPolicy)
            }
            execution {
                listTransactionMode = ListTransactionMode.WHOLE_SCOPE
                pageSize = 42
                lockTimeout = Duration.ofMillis(250)
                authorityMode = AuthorityMode.MULTI_WRITER
            }
        }

    private fun assertDslWiring(
        definition: SyncDefinition<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope>,
        fixture: DslWiringFixture,
    ) {
        assertThat(definition.name).isEqualTo(SyncName("widget-dsl"))
        assertThat(definition.ports.localRepository).isSameAs(fixture.repository)
        assertThat(definition.ports.remoteCatalog).isSameAs(fixture.remoteCatalog)
        assertThat(definition.ports.lockManager).isSameAs(fixture.lockManager)
        assertThat(definition.ports.unitOfWork).isSameAs(fixture.unitOfWork)
        assertThat(definition.ports.effectOutbox).isSameAs(fixture.effectOutbox)
        assertThat(definition.ports.auditTrail).isSameAs(fixture.auditTrail)
        assertThat(definition.ports.observer).isSameAs(fixture.observer)
        assertThat(definition.ports.idempotencyStore).isSameAs(fixture.idempotencyStore)
        assertThat(definition.ports.checkpointStore).isSameAs(fixture.checkpointStore)
        assertThat(definition.policies.missingRemotePolicy).isSameAs(fixture.missingRemotePolicy)
        assertThat(definition.policies.importPolicy).isSameAs(fixture.importPolicy)
        assertThat(definition.policies.conflictPolicy).isSameAs(fixture.conflictPolicy)
        assertThat(definition.execution)
            .isEqualTo(
                SyncExecutionOptions(
                    listTransactionMode = ListTransactionMode.WHOLE_SCOPE,
                    pageSize = 42,
                    lockTimeout = Duration.ofMillis(250),
                    authorityMode = AuthorityMode.MULTI_WRITER,
                ),
            )
    }

    private fun assertDslStrategies(
        definition: SyncDefinition<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope>,
    ) {
        val local = Widget.linked("local-1", WidgetId("w-1"), "SKU-1", "Local")
        val remote = RemoteWidget(WidgetId("w-1"), "SKU-1", "Remote")
        val localRecord = definition.localProjector.project(local)
        val remoteRecord = definition.remoteProjector.project(remote)

        assertThat(localRecord).isEqualTo(WidgetLocalProjector.project(local))
        assertThat(remoteRecord).isEqualTo(WidgetRemoteProjector.project(remote))
        assertThat(definition.differ.diff(local, remote)).isEqualTo(WidgetDiffer.diff(local, remote))
        assertThat(definition.mapper.create(remote, SyncFixtures.context(scope = WidgetScope("scope-1"))).scope)
            .isEqualTo(WidgetScope("scope-1"))
        assertThat(definition.matchPlan.passes.map { it.name }).containsExactly("remote-id", "sku")
        assertThat(definition.matchPlan.passes[0].confidence).isEqualTo(MatchConfidence.HARD)
        assertThat(definition.matchPlan.passes[0].localKeys(localRecord))
            .containsExactly(WidgetKey.Remote(WidgetId("w-1")))
        assertThat(definition.matchPlan.passes[0].remoteKeys(remoteRecord))
            .containsExactly(WidgetKey.Remote(WidgetId("w-1")))
        assertThat(definition.matchPlan.passes[1].confidence).isEqualTo(MatchConfidence.NATURAL_KEY)
        assertThat(definition.matchPlan.passes[1].localKeys(localRecord)).containsExactly(WidgetKey.Sku("SKU-1"))
        assertThat(definition.matchPlan.passes[1].remoteKeys(remoteRecord)).containsExactly(WidgetKey.Sku("SKU-1"))
    }

    private data class DslWiringFixture(
        val repository: InMemoryLocalSyncRepository<Widget, WidgetId, WidgetScope>,
        val remoteCatalog: InMemoryRemoteCatalog<RemoteWidget, WidgetId, WidgetKey, WidgetScope>,
        val lockManager: InMemoryLockManager,
        val unitOfWork: InMemoryUnitOfWork,
        val effectOutbox: InMemoryEffectOutbox,
        val auditTrail: InMemoryAuditTrail,
        val observer: InMemoryObserver,
        val idempotencyStore: InMemoryIdempotencyStore,
        val checkpointStore: InMemoryCheckpointStore,
        val missingRemotePolicy: DslMissingRemotePolicy,
        val importPolicy: ImportPolicy<RemoteWidget>,
        val conflictPolicy: ConflictPolicy<Widget, RemoteWidget, WidgetId, WidgetKey>,
    )

    @Test
    fun `policies builder keeps safe defaults when only missing remote policy is supplied`() {
        val definition = minimalDefinition()
        val remote = RemoteWidget(WidgetId("w-1"), "ignored", "Remote", importable = false)
        val conflict = conflict()

        assertThat(definition.policies.importPolicy.importable(remote)).isTrue()
        assertThat(definition.policies.conflictPolicy.decide(conflict))
            .isEqualTo(SyncDecision.Conflict<Widget, RemoteWidget, WidgetId, WidgetKey>(conflict))
    }

    @Test
    fun `import and missing remote policies execute supplied lambdas`() {
        val observedAt = Instant.parse("2026-06-30T12:00:00Z")
        val definition =
            minimalDefinition(
                importPolicy = ImportPolicy { remote -> remote.importable },
                missingRemotePolicy =
                    MissingRemotePolicy { local, at ->
                        SyncDecision.Delete(
                            local = local,
                            signal =
                                RemoteDeleteSignal.MissingFromAuthoritativeList(
                                    remoteId = local.registration.rememberedRemoteId ?: WidgetId("fallback"),
                                    observedAt = at,
                                ),
                        )
                    },
            )
        val localRecord = WidgetLocalProjector.project(Widget.linked("local-1", WidgetId("w-1"), "SKU-1", "Local"))

        assertThat(definition.policies.importPolicy.importable(RemoteWidget(WidgetId("w-1"), "SKU-1", "Remote")))
            .isTrue()
        assertThat(
            definition.policies.importPolicy.importable(
                RemoteWidget(WidgetId("w-2"), "SKU-2", "Remote", importable = false),
            ),
        ).isFalse()

        val decision = definition.policies.missingRemotePolicy.decide(localRecord, observedAt)

        assertThat(decision).isInstanceOf(SyncDecision.Delete::class.java)
        val delete = decision as SyncDecision.Delete<Widget, RemoteWidget, WidgetId, WidgetKey>
        assertThat(delete.local).isSameAs(localRecord)
        assertThat(delete.signal)
            .isEqualTo(RemoteDeleteSignal.MissingFromAuthoritativeList(WidgetId("w-1"), observedAt))
    }

    @Test
    fun `conflict policy failClosed returns non executable conflict decision`() {
        val conflict = conflict()
        val decision = ConflictPolicy.failClosed<Widget, RemoteWidget, WidgetId, WidgetKey>().decide(conflict)

        assertThat(decision.conflict).isSameAs(conflict)
        assertThat(decision.action).isEqualTo(SyncAction.CONFLICT)
        assertThat(decision.subject).isEqualTo(conflict.subject)
        assertThat(decision.reason).isEqualTo(SyncReason.Conflict(conflict))
        assertThat(decision.effects).isEmpty()
        assertThat(decision.executable).isFalse()
    }

    @Test
    fun `execution options defaults overrides data members and enums are covered`() {
        val defaults = SyncExecutionOptions()
        val overridden =
            defaults.copy(
                listTransactionMode = ListTransactionMode.PER_PAGE,
                pageSize = 100,
                lockTimeout = Duration.ofSeconds(3),
                authorityMode = AuthorityMode.LOCAL_AUTHORITATIVE,
            )

        assertThat(defaults.listTransactionMode).isEqualTo(ListTransactionMode.PER_RECORD)
        assertThat(defaults.pageSize).isEqualTo(500)
        assertThat(defaults.lockTimeout).isEqualTo(Duration.ofSeconds(10))
        assertThat(defaults.authorityMode).isEqualTo(AuthorityMode.REMOTE_AUTHORITATIVE)
        assertThat(overridden.component1()).isEqualTo(ListTransactionMode.PER_PAGE)
        assertThat(overridden.component2()).isEqualTo(100)
        assertThat(overridden.component3()).isEqualTo(Duration.ofSeconds(3))
        assertThat(overridden.component4()).isEqualTo(AuthorityMode.LOCAL_AUTHORITATIVE)
        assertThat(overridden).isEqualTo(overridden.copy())
        assertThat(overridden.hashCode()).isEqualTo(overridden.copy().hashCode())
        assertThat(overridden.toString()).contains("pageSize=100")
        assertThat(AuthorityMode.entries).containsExactly(
            AuthorityMode.REMOTE_AUTHORITATIVE,
            AuthorityMode.LOCAL_AUTHORITATIVE,
            AuthorityMode.MULTI_WRITER,
        )
        assertThat(ListTransactionMode.entries).containsExactly(
            ListTransactionMode.PER_RECORD,
            ListTransactionMode.PER_PAGE,
            ListTransactionMode.WHOLE_SCOPE,
        )
    }

    @Test
    fun `sync policies data members use supplied and default policies`() {
        val missingRemotePolicy = WidgetHarness.UNLINK_MISSING
        val importPolicy = ImportPolicy<RemoteWidget> { remote -> remote.importable }
        val policies =
            SyncPolicies<Widget, RemoteWidget, WidgetId, WidgetKey>(
                missingRemotePolicy = missingRemotePolicy,
                importPolicy = importPolicy,
            )

        assertThat(policies.component1().decide(conflict()).action).isEqualTo(SyncAction.CONFLICT)
        assertThat(policies.component2()).isSameAs(missingRemotePolicy)
        assertThat(policies.component3()).isSameAs(importPolicy)
        assertThat(policies.copy()).isEqualTo(policies)
        assertThat(policies.copy().hashCode()).isEqualTo(policies.hashCode())
        assertThat(policies.toString()).contains("SyncPolicies")
    }

    @Test
    fun `sync definition and ports data members are covered`() {
        val definition = minimalDefinition()
        val ports = definition.ports

        assertThat(definition.component1()).isEqualTo(SyncName("widget"))
        assertThat(definition.component2()).isSameAs(definition.localProjector)
        assertThat(definition.component3()).isSameAs(definition.remoteProjector)
        assertThat(definition.component4()).isSameAs(definition.differ)
        assertThat(definition.component5()).isSameAs(definition.mapper)
        assertThat(definition.component6()).isSameAs(definition.matchPlan)
        assertThat(definition.component7()).isSameAs(definition.policies)
        assertThat(definition.component8()).isSameAs(definition.execution)
        assertThat(definition.component9()).isSameAs(ports)
        assertThat(definition.copy()).isEqualTo(definition)
        assertThat(definition.copy().hashCode()).isEqualTo(definition.hashCode())
        assertThat(definition.toString()).contains("SyncDefinition")

        assertThat(ports.component1()).isSameAs(ports.localRepository)
        assertThat(ports.component2()).isSameAs(ports.remoteCatalog)
        assertThat(ports.component3()).isSameAs(ports.lockManager)
        assertThat(ports.component4()).isSameAs(ports.unitOfWork)
        assertThat(ports.component5()).isSameAs(ports.effectOutbox)
        assertThat(ports.component6()).isSameAs(ports.auditTrail)
        assertThat(ports.component7()).isSameAs(ports.observer)
        assertThat(ports.component8()).isSameAs(ports.idempotencyStore)
        assertThat(ports.component9()).isSameAs(ports.checkpointStore)
        assertThat(ports.copy()).isEqualTo(ports)
        assertThat(ports.copy().hashCode()).isEqualTo(ports.hashCode())
        assertThat(ports.toString()).contains("SyncPorts")
    }

    @Test
    fun `build fails with exact messages for every missing mandatory setting`() {
        listOf(
            "localProjector",
            "remoteProjector",
            "differ",
            "mapper",
            "localRepository",
            "remoteCatalog",
            "lockManager",
            "unitOfWork",
            "effectOutbox",
            "auditTrail",
            "observer",
            "idempotencyStore",
            "checkpointStore",
        ).forEach { omitted ->
            assertThatThrownBy { minimalDefinition(omit = omitted) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("syncResource(\"widget\"): $omitted must be configured")
        }
    }

    @Test
    fun `build fails when missing remote policy is omitted`() {
        assertThatThrownBy { minimalDefinition(omit = "missingRemotePolicy") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("policies { } must declare a missingRemotePolicy — absence handling is business-specific")
    }

    // Structural threshold: omission branches mirror the DSL's required fields one-for-one.
    private fun minimalDefinition(
        omit: String? = null,
        importPolicy: ImportPolicy<RemoteWidget>? = null,
        missingRemotePolicy: DslMissingRemotePolicy = WidgetHarness.UNLINK_MISSING,
    ): SyncDefinition<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope> {
        val repository = widgetRepository()
        val remoteCatalog = InMemoryRemoteCatalog<RemoteWidget, WidgetId, WidgetKey, WidgetScope>()
        val lockManager = InMemoryLockManager()
        val unitOfWork = InMemoryUnitOfWork()
        val effectOutbox = InMemoryEffectOutbox()
        val auditTrail = InMemoryAuditTrail()
        val observer = InMemoryObserver()
        val idempotencyStore = InMemoryIdempotencyStore()
        val checkpointStore = InMemoryCheckpointStore()

        return syncResource("widget") {
            configureUnless(omit, "localRepository") { localRepository(repository) }
            configureUnless(omit, "remoteCatalog") { remoteCatalog(remoteCatalog) }
            configureUnless(omit, "lockManager") { lockManager(lockManager) }
            configureUnless(omit, "unitOfWork") { unitOfWork(unitOfWork) }
            configureUnless(omit, "effectOutbox") { effectOutbox(effectOutbox) }
            configureUnless(omit, "auditTrail") { auditTrail(auditTrail) }
            configureUnless(omit, "observer") { observer(observer) }
            configureUnless(omit, "idempotencyStore") { idempotencyStore(idempotencyStore) }
            configureUnless(omit, "checkpointStore") { checkpointStore(checkpointStore) }
            configureUnless(omit, "localProjector") { localProjector { WidgetLocalProjector.project(it) } }
            configureUnless(omit, "remoteProjector") { remoteProjector { WidgetRemoteProjector.project(it) } }
            configureUnless(omit, "differ") { differ(WidgetDiffer::diff) }
            configureUnless(omit, "mapper") { mapper(WidgetMapper) }
            matching {
                pass(
                    name = "remote-id",
                    localKeys = { record -> record.keys.filterIsInstance<WidgetKey.Remote>().toSet() },
                    remoteKeys = { record -> record.keys.filterIsInstance<WidgetKey.Remote>().toSet() },
                )
            }
            policies {
                if (omit != "missingRemotePolicy") missingRemotePolicy(missingRemotePolicy)
                importPolicy?.let { importPolicy(it) }
            }
        }
    }

    private fun SyncResourceBuilder<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope>.configureUnless(
        omitted: String?,
        name: String,
        block: SyncResourceBuilder<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope>.() -> Unit,
    ) {
        if (omitted != name) {
            block()
        }
    }

    private fun widgetRepository(): InMemoryLocalSyncRepository<Widget, WidgetId, WidgetScope> =
        InMemoryLocalSyncRepository(
            keyOf = { widget -> widget.registration.remoteId ?: widget.registration.rememberedRemoteId },
            scopeOf = { widget -> widget.scope },
        )

    private fun conflict(): SyncConflict<Widget, RemoteWidget, WidgetId, WidgetKey> {
        val id = WidgetId("w-conflict")
        return SyncConflict(
            subject = SyncSubject.Remote(id),
            kind = SyncConflictKind.AMBIGUOUS_MATCH,
            local = WidgetLocalProjector.project(Widget.linked("local-conflict", id, "SKU-C", "Local")),
            remote = WidgetRemoteProjector.project(RemoteWidget(id, "SKU-C", "Remote")),
            message = "ambiguous",
        )
    }
}
