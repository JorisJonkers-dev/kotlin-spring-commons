package com.jorisjonkers.personalstack.common.sync

import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SpawnSyncCommand
import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncEntityCommand
import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncListCommand
import com.jorisjonkers.personalstack.common.sync.application.service.SpawnSyncService
import com.jorisjonkers.personalstack.common.sync.application.service.SyncEntityService
import com.jorisjonkers.personalstack.common.sync.application.service.SyncListService
import com.jorisjonkers.personalstack.common.sync.domain.IdempotencyKey
import com.jorisjonkers.personalstack.common.sync.domain.RemoteChange
import com.jorisjonkers.personalstack.common.sync.domain.RemoteDeleteSignal
import com.jorisjonkers.personalstack.common.sync.domain.RemoteFetch
import com.jorisjonkers.personalstack.common.sync.domain.RemotePage
import com.jorisjonkers.personalstack.common.sync.domain.RemoteRecord
import com.jorisjonkers.personalstack.common.sync.domain.RequeueDecision
import com.jorisjonkers.personalstack.common.sync.domain.SyncAction
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailure
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailureKind
import com.jorisjonkers.personalstack.common.sync.domain.SyncName
import com.jorisjonkers.personalstack.common.sync.domain.SyncOutcome
import com.jorisjonkers.personalstack.common.sync.domain.SyncDefinition
import com.jorisjonkers.personalstack.common.sync.domain.SyncExecutionOptions
import com.jorisjonkers.personalstack.common.sync.domain.SyncReport
import com.jorisjonkers.personalstack.common.sync.domain.SyncReportStatus
import com.jorisjonkers.personalstack.common.sync.domain.SyncSubject
import com.jorisjonkers.personalstack.common.sync.domain.SyncTriggerSource
import com.jorisjonkers.personalstack.common.sync.testsupport.Widget
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetHarness
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetId
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetKey
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetScope
import com.jorisjonkers.personalstack.common.sync.testsupport.RemoteWidget
import com.jorisjonkers.personalstack.common.sync.testsupport.SyncFixtures
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoverageGapTest {
    @Test
    fun `constructs services without clock and executes one sync each`() {
        val entityHarness = WidgetHarness.build()
        val entityId = WidgetId("default-clock-entity")
        entityHarness.remoteCatalog.foundOne(entityId, remoteRecord(entityId.value))

        val entityReport =
            SyncEntityService(entityHarness.definition).sync(
                SyncEntityCommand(externalId = entityId, source = SyncTriggerSource.TEST),
            )

        val listHarness = WidgetHarness.build()
        listHarness.remoteCatalog.onFetchForScope(
            RemoteFetch.Found(remotePage(listOf(upsert(remoteRecord("default-clock-list"))))),
        )

        val listReport =
            SyncListService(listHarness.definition).sync(
                SyncListCommand(scope = WidgetScope("default"), source = SyncTriggerSource.TEST),
            )

        val spawnHarness = WidgetHarness.build()
        spawnHarness.remoteCatalog.enqueuePage(
            RemoteFetch.Found(remotePage(listOf(upsert(remoteRecord("default-clock-spawn"))))),
        )

        val spawnReport =
            SpawnSyncService(spawnHarness.definition).spawn(
                SpawnSyncCommand(scope = WidgetScope("default"), source = SyncTriggerSource.TEST),
            )

        assertThat(entityReport.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(listReport.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(spawnReport.status).isEqualTo(SyncReportStatus.SKIPPED)
    }

    @Test
    fun `entity equal decision executes non mutating apply branch`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("equal-apply-else")
        harness.repository.seed(Widget.linked("local-equal-apply", id, "SKU-EQUAL", "Same"))
        harness.remoteCatalog.foundOne(id, remoteRecord(id.value, sku = "SKU-EQUAL", name = "Same"))

        val report =
            SyncEntityService(harness.definition, harness.clock).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Skipped<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(outcome.action).isEqualTo(SyncAction.EQUAL)
        assertThat(harness.repository.saved).isEmpty()
        assertThat(harness.checkpointStore.savedBaselines).isEmpty()
    }

    @Test
    fun `entity idempotency fails a failed report`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("idempotency-fail")
        val key = IdempotencyKey("failed-key")
        val failure = failure(SyncFailureKind.REMOTE_UNAVAILABLE, retryable = true)
        harness.remoteCatalog.onFetchOne(id, RemoteFetch.Failed(failure))

        val report =
            SyncEntityService(harness.definition, harness.clock).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST, idempotencyKey = key),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.FAILED)
        assertThat(harness.idempotencyStore.failed).containsExactly(key)
        assertThat(harness.idempotencyStore.completed).isEmpty()
    }

    @Test
    fun `entity idempotency completes successful import through public api`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("idempotency-complete")
        val key = IdempotencyKey("completed-key")
        harness.remoteCatalog.foundOne(id, remoteRecord(id.value))

        val report =
            SyncEntityService(harness.definition, harness.clock).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST, idempotencyKey = key),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.outcomes.single().action).isEqualTo(SyncAction.IMPORT)
        assertThat(harness.idempotencyStore.completed).containsExactly(key)
        assertThat(harness.idempotencyStore.failed).isEmpty()
    }

    @Test
    fun `list page with explicit delete change ignores delete while syncing upserts`() {
        val harness = WidgetHarness.build()
        val scope = WidgetScope("delete-change")
        harness.remoteCatalog.onFetchForScope(
            RemoteFetch.Found(
                remotePage(
                    listOf(
                        deleteChange("remote-delete"),
                        upsert(remoteRecord("remote-upsert")),
                    ),
                ),
            ),
        )

        val report =
            SyncListService(harness.definition, harness.clock).sync(
                SyncListCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.outcomes).hasSize(1)
        assertThat(report.outcomes.single().action).isEqualTo(SyncAction.IMPORT)
        assertThat(harness.repository.saved.single().registration.remoteId).isEqualTo(WidgetId("remote-upsert"))
    }

    @Test
    fun `list equal decision executes non mutating decision branch`() {
        val scope = WidgetScope("list-equal")
        val harness = WidgetHarness.build()
        val id = WidgetId("list-equal-else")
        harness.repository.seed(Widget.linked("local-list-equal", id, "SKU-LIST-EQUAL", "Same", scope))
        harness.remoteCatalog.onFetchForScope(
            RemoteFetch.Found(remotePage(listOf(upsert(remoteRecord(id.value, sku = "SKU-LIST-EQUAL", name = "Same"))))),
        )

        val report =
            SyncListService(harness.definition, harness.clock).sync(
                SyncListCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Skipped<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(outcome.action).isEqualTo(SyncAction.EQUAL)
        assertThat(harness.repository.saved).isEmpty()
        assertThat(harness.checkpointStore.savedBaselines).isEmpty()
    }

    @Test
    fun `spawn with non null scope uses scope subject branch`() {
        val scope = WidgetScope("scoped-spawn")
        val harness = WidgetHarness.build()
        harness.remoteCatalog.enqueuePage(RemoteFetch.Found(remotePage()))

        val report =
            SpawnSyncService(harness.definition, harness.clock).spawn(
                SpawnSyncCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Skipped<*>
        assertThat(outcome.subject).isEqualTo(
            SyncSubject.Scope(
                com.jorisjonkers.personalstack.common.sync.domain.ScopeId(scope.toString()),
            ),
        )
    }

    @Test
    fun `sync definition generated copy is exercised`() {
        val definition = WidgetHarness.build().definition

        val copy = definition.copy(name = SyncName("copied-widget"))
        val sameCopy = definition.copy()

        assertThat(copy.name).isEqualTo(SyncName("copied-widget"))
        assertThat(copy).isNotEqualTo(definition)
        // Exercise the remaining generated data-class members (equals, hashCode, toString, componentN).
        assertThat(sameCopy).isEqualTo(definition)
        assertThat(sameCopy.hashCode()).isEqualTo(definition.hashCode())
        assertThat(definition.toString()).contains("SyncDefinition")
        val (name, localProjector, remoteProjector, differ, mapper, matchPlan, policies, execution, ports) = definition
        assertThat(name).isEqualTo(definition.name)
        assertThat(localProjector).isSameAs(definition.localProjector)
        assertThat(remoteProjector).isSameAs(definition.remoteProjector)
        assertThat(differ).isSameAs(definition.differ)
        assertThat(mapper).isSameAs(definition.mapper)
        assertThat(matchPlan).isEqualTo(definition.matchPlan)
        assertThat(policies).isEqualTo(definition.policies)
        assertThat(execution).isEqualTo(definition.execution)
        assertThat(ports).isEqualTo(definition.ports)

        // Construct without `execution` to exercise the default-parameter constructor.
        val viaDefaultExecution =
            SyncDefinition(
                name = definition.name,
                localProjector = definition.localProjector,
                remoteProjector = definition.remoteProjector,
                differ = definition.differ,
                mapper = definition.mapper,
                matchPlan = definition.matchPlan,
                policies = definition.policies,
                ports = definition.ports,
            )
        assertThat(viaDefaultExecution.execution).isEqualTo(SyncExecutionOptions())
    }

    @Test
    fun `entity sync with denied lock fails idempotency through public api`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("lock-denied")
        val key = IdempotencyKey("lock-denied-key")
        harness.remoteCatalog.foundOne(id, remoteRecord(id.value))
        harness.lockManager.denyAll()

        val report =
            SyncEntityService(harness.definition, harness.clock).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST, idempotencyKey = key),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.FAILED)
        assertThat(harness.idempotencyStore.failed).containsExactly(key)
        assertThat(harness.idempotencyStore.completed).isEmpty()
    }

    @Test
    fun `finalize idempotency completes a defensive failed-outcome under non-failed report`() {
        // A single-outcome entity sync cannot itself produce a Failed outcome under a
        // SUCCEEDED/PARTIAL report, so this defensive branch is exercised directly.
        val harness = WidgetHarness.build()
        val service = SyncEntityService(harness.definition, harness.clock)
        val key = IdempotencyKey("defensive-partial")
        val failed =
            SyncOutcome.Failed<WidgetId>(
                subject = SyncSubject.Unknown,
                action = null,
                duration = Duration.ZERO,
                failure = failure(SyncFailureKind.REMOTE_UNAVAILABLE, retryable = true),
            )
        val report =
            SyncReport(
                context = SyncFixtures.context(),
                status = SyncReportStatus.PARTIAL,
                startedAt = Instant.parse("2026-06-30T00:00:00Z"),
                completedAt = Instant.parse("2026-06-30T00:00:01Z"),
                outcomes = listOf(failed),
                requeue = RequeueDecision.Done,
            )

        service.finalizeIdempotency(key, report, failed)

        assertThat(harness.idempotencyStore.completed).containsExactly(key)
        assertThat(harness.idempotencyStore.failed).isEmpty()
    }

    private companion object {
        fun remoteRecord(
            id: String,
            sku: String = "SKU-$id",
            name: String = "Widget $id",
        ): RemoteRecord<RemoteWidget, WidgetId, WidgetKey> {
            val widgetId = WidgetId(id)
            return RemoteRecord(
                record = RemoteWidget(id = widgetId, sku = sku, name = name),
                externalId = widgetId,
                keys = setOf(WidgetKey.Remote(widgetId), WidgetKey.Sku(sku)),
            )
        }

        fun remotePage(
            changes: List<RemoteChange<RemoteWidget, WidgetId, WidgetKey>> = emptyList(),
        ): RemotePage<RemoteWidget, WidgetId, WidgetKey> =
            RemotePage(changes = changes, nextCursor = null, highWatermark = null)

        fun upsert(record: RemoteRecord<RemoteWidget, WidgetId, WidgetKey>): RemoteChange<RemoteWidget, WidgetId, WidgetKey> =
            RemoteChange.Upsert(record)

        fun deleteChange(id: String): RemoteChange<RemoteWidget, WidgetId, WidgetKey> =
            RemoteChange.Delete(
                signal = RemoteDeleteSignal.ExplicitDelete(remoteId = WidgetId(id), deletedAt = null, version = null),
                version = null,
            )

        fun failure(kind: SyncFailureKind, retryable: Boolean): SyncFailure =
            SyncFailure(kind = kind, message = kind.name, retryable = retryable)
    }
}
