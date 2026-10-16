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
import com.jorisjonkers.personalstack.common.sync.domain.SyncDefinition
import com.jorisjonkers.personalstack.common.sync.domain.SyncExecutionOptions
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailure
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailureKind
import com.jorisjonkers.personalstack.common.sync.domain.SyncOutcome
import com.jorisjonkers.personalstack.common.sync.domain.SyncReport
import com.jorisjonkers.personalstack.common.sync.domain.SyncReportStatus
import com.jorisjonkers.personalstack.common.sync.domain.SyncSubject
import com.jorisjonkers.personalstack.common.sync.domain.SyncTriggerSource
import com.jorisjonkers.personalstack.common.sync.testsupport.RemoteWidget
import com.jorisjonkers.personalstack.common.sync.testsupport.SyncFixtures
import com.jorisjonkers.personalstack.common.sync.testsupport.Widget
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetHarness
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetId
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetKey
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SyncServiceEdgeCasesTest {
    @Test
    fun `an entity sync built without a clock still runs`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("default-clock-entity")
        harness.remoteCatalog.foundOne(id, remoteRecord(id.value))

        val report =
            SyncEntityService(harness.definition).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
    }

    @Test
    fun `a list sync built without a clock still runs`() {
        val harness = WidgetHarness.build()
        harness.remoteCatalog.onFetchForScope(
            RemoteFetch.Found(remotePage(listOf(upsert(remoteRecord("default-clock-list"))))),
        )

        val report =
            SyncListService(harness.definition).sync(
                SyncListCommand(scope = WidgetScope("default"), source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
    }

    @Test
    fun `a spawn built without a clock still runs`() {
        val harness = WidgetHarness.build()
        harness.remoteCatalog.enqueuePage(
            RemoteFetch.Found(remotePage(listOf(upsert(remoteRecord("default-clock-spawn"))))),
        )

        val report =
            SpawnSyncService(harness.definition).spawn(
                SpawnSyncCommand(scope = WidgetScope("default"), source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
    }

    @Test
    fun `an entity sync leaves a local that equals the remote untouched`() {
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
    fun `a failed entity sync marks its idempotency key failed`() {
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
    fun `a successful entity import completes its idempotency key`() {
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
    fun `a list sync ignores explicit deletes in the page and syncs its upserts`() {
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
        assertThat(
            harness.repository.saved
                .single()
                .registration.remoteId,
        ).isEqualTo(WidgetId("remote-upsert"))
    }

    @Test
    fun `a list sync leaves a local that equals the remote untouched`() {
        val scope = WidgetScope("list-equal")
        val harness = WidgetHarness.build()
        val id = WidgetId("list-equal-else")
        harness.repository.seed(Widget.linked("local-list-equal", id, "SKU-LIST-EQUAL", "Same", scope))
        harness.remoteCatalog.onFetchForScope(
            RemoteFetch.Found(
                remotePage(listOf(upsert(remoteRecord(id.value, sku = "SKU-LIST-EQUAL", name = "Same")))),
            ),
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
    fun `a scoped spawn reports its outcome against the scope`() {
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
                com.jorisjonkers.personalstack.common.sync.domain
                    .ScopeId(scope.toString()),
            ),
        )
    }

    @Test
    fun `a sync definition uses the default execution options unless given`() {
        val definition = WidgetHarness.build().definition

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
    fun `an entity sync denied its lock marks its idempotency key failed`() {
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
    fun `a failed outcome under a report that did not fail still completes the idempotency key`() {
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

        fun upsert(
            record: RemoteRecord<RemoteWidget, WidgetId, WidgetKey>,
        ): RemoteChange<RemoteWidget, WidgetId, WidgetKey> = RemoteChange.Upsert(record)

        fun deleteChange(id: String): RemoteChange<RemoteWidget, WidgetId, WidgetKey> =
            RemoteChange.Delete(
                signal = RemoteDeleteSignal.ExplicitDelete(remoteId = WidgetId(id), deletedAt = null, version = null),
                version = null,
            )

        fun failure(
            kind: SyncFailureKind,
            retryable: Boolean,
        ): SyncFailure = SyncFailure(kind = kind, message = kind.name, retryable = retryable)
    }
}
