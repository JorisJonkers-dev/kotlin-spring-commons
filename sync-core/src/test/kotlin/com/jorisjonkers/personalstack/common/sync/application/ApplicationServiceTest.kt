package com.jorisjonkers.personalstack.common.sync.application

import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SpawnSyncCommand
import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncEntityCommand
import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncListCommand
import com.jorisjonkers.personalstack.common.sync.application.service.SpawnSyncService
import com.jorisjonkers.personalstack.common.sync.application.service.SyncEntityService
import com.jorisjonkers.personalstack.common.sync.application.service.SyncListService
import com.jorisjonkers.personalstack.common.sync.application.service.SyncReportStatuses
import com.jorisjonkers.personalstack.common.sync.application.service.SyncUseCaseFactory
import com.jorisjonkers.personalstack.common.sync.domain.ChangeSet
import com.jorisjonkers.personalstack.common.sync.domain.CursorCheckpoint
import com.jorisjonkers.personalstack.common.sync.domain.IdempotencyKey
import com.jorisjonkers.personalstack.common.sync.domain.ListTransactionMode
import com.jorisjonkers.personalstack.common.sync.domain.MissingReason
import com.jorisjonkers.personalstack.common.sync.domain.MissingRemotePolicy
import com.jorisjonkers.personalstack.common.sync.domain.RemoteChange
import com.jorisjonkers.personalstack.common.sync.domain.RemoteDeleteSignal
import com.jorisjonkers.personalstack.common.sync.domain.RemoteFetch
import com.jorisjonkers.personalstack.common.sync.domain.RemotePage
import com.jorisjonkers.personalstack.common.sync.domain.RemoteRecord
import com.jorisjonkers.personalstack.common.sync.domain.RemoteRecordLifecycle
import com.jorisjonkers.personalstack.common.sync.domain.RequeueDecision
import com.jorisjonkers.personalstack.common.sync.domain.SyncAction
import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncCursor
import com.jorisjonkers.personalstack.common.sync.domain.SyncDecision
import com.jorisjonkers.personalstack.common.sync.domain.SyncEffect
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailure
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailureKind
import com.jorisjonkers.personalstack.common.sync.domain.SyncMapper
import com.jorisjonkers.personalstack.common.sync.domain.SyncOutcome
import com.jorisjonkers.personalstack.common.sync.domain.SyncReason
import com.jorisjonkers.personalstack.common.sync.domain.SyncRegistrationLifecycle
import com.jorisjonkers.personalstack.common.sync.domain.SyncReport
import com.jorisjonkers.personalstack.common.sync.domain.SyncReportStatus
import com.jorisjonkers.personalstack.common.sync.domain.SyncSubject
import com.jorisjonkers.personalstack.common.sync.domain.SyncTriggerSource
import com.jorisjonkers.personalstack.common.sync.domain.UnlinkReason
import com.jorisjonkers.personalstack.common.sync.domain.VersionStamp
import com.jorisjonkers.personalstack.common.sync.testsupport.RemoteWidget
import com.jorisjonkers.personalstack.common.sync.testsupport.SyncFixtures
import com.jorisjonkers.personalstack.common.sync.testsupport.Widget
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetHarness
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetId
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetKey
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetMapper
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetMatchPass
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

// Structural threshold: this suite keeps application-service scenarios together to share the sync harness.
@Suppress("LargeClass")
class ApplicationServiceTest {
    @Test
    fun `entity command covers defaults and generated members`() {
        val command = SyncEntityCommand(externalId = WidgetId("entity-default"), source = SyncTriggerSource.TEST)

        assertThat(command.component1()).isEqualTo(WidgetId("entity-default"))
        assertThat(command.component2()).isEqualTo(SyncTriggerSource.TEST)
        assertThat(command.component3().value).isNotBlank()
        assertThat(command.component4()).isNull()
        assertThat(command.component5()).isFalse()

        val copy = command.copy(dryRun = true)
        assertThat(command.copy()).isEqualTo(command)
        assertThat(command.copy().hashCode()).isEqualTo(command.hashCode())
        assertThat(command.toString()).contains("SyncEntityCommand")
        assertThat(copy).isNotEqualTo(command)
        assertThat(copy.dryRun).isTrue()
    }

    @Test
    fun `list command covers defaults and generated members`() {
        val scope = WidgetScope("list-default")
        val command = SyncListCommand(scope = scope, source = SyncTriggerSource.TEST)

        assertThat(command.component1()).isEqualTo(scope)
        assertThat(command.component2()).isEqualTo(SyncTriggerSource.TEST)
        assertThat(command.component3().value).isNotBlank()
        assertThat(command.component4()).isNull()
        assertThat(command.component5()).isFalse()

        val copy = command.copy(dryRun = true)
        assertThat(command.copy()).isEqualTo(command)
        assertThat(command.copy().hashCode()).isEqualTo(command.hashCode())
        assertThat(command.toString()).contains("SyncListCommand")
        assertThat(copy).isNotEqualTo(command)
        assertThat(copy.dryRun).isTrue()
    }

    @Test
    fun `spawn command covers defaults and generated members`() {
        val command = SpawnSyncCommand<WidgetScope>(scope = null, source = SyncTriggerSource.TEST)

        assertThat(command.component1()).isNull()
        assertThat(command.component2()).isNull()
        assertThat(command.component3()).isNull()
        assertThat(command.component4()).isFalse()
        assertThat(command.component5()).isEqualTo(SyncTriggerSource.TEST)
        assertThat(command.component6().value).isNotBlank()
        assertThat(command.component7()).isNull()
        assertThat(command.component8()).isFalse()

        val copy = command.copy(cursor = SyncCursor("explicit"), pageSize = 25, fullSync = true)
        assertThat(command.copy()).isEqualTo(command)
        assertThat(command.copy().hashCode()).isEqualTo(command.hashCode())
        assertThat(command.toString()).contains("SpawnSyncCommand")
        assertThat(copy).isNotEqualTo(command)
        assertThat(copy.cursor).isEqualTo(SyncCursor("explicit"))
        assertThat(copy.pageSize).isEqualTo(25)
        assertThat(copy.fullSync).isTrue()
    }

    @Test
    fun `factory creates typed use case implementations`() {
        val harness = WidgetHarness.build()
        val factory = SyncUseCaseFactory()

        assertThat(factory.entity(harness.definition)).isInstanceOf(SyncEntityService::class.java)
        assertThat(factory.list(harness.definition)).isInstanceOf(SyncListService::class.java)
        assertThat(factory.spawn(harness.definition)).isInstanceOf(SpawnSyncService::class.java)
    }

    @Test
    fun `status helper derives every report status`() {
        val succeeded = succeeded(SyncAction.IMPORT, "ok")
        val failed = failed(SyncAction.UPDATE, retryable = false, id = "failed")
        val conflict =
            SyncOutcome.Skipped(
                subject = SyncSubject.Remote(WidgetId("conflict")),
                action = SyncAction.CONFLICT,
                duration = Duration.ZERO,
                reason = SyncReason.Policy("conflict"),
            )
        val skipped =
            SyncOutcome.Skipped<WidgetId>(
                subject = SyncSubject.Unknown,
                action = SyncAction.IGNORE,
                duration = Duration.ZERO,
                reason = SyncReason.Policy("skip"),
            )

        assertThat(SyncReportStatuses.of(emptyList())).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(SyncReportStatuses.of(listOf(succeeded))).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(SyncReportStatuses.of(listOf(succeeded, failed))).isEqualTo(SyncReportStatus.PARTIAL)
        assertThat(SyncReportStatuses.of(listOf(failed))).isEqualTo(SyncReportStatus.FAILED)
        assertThat(SyncReportStatuses.of(listOf(conflict))).isEqualTo(SyncReportStatus.CONFLICTED)
        assertThat(SyncReportStatuses.of(listOf(conflict, succeeded))).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(SyncReportStatuses.of(listOf(skipped))).isEqualTo(SyncReportStatus.SKIPPED)
    }

    @Test
    fun `entity dry run reports planned import without writes`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("dry-run")
        harness.remoteCatalog.foundOne(id, remoteRecord(id.value, name = "Remote"))

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(
                    externalId = id,
                    source = SyncTriggerSource.TEST,
                    dryRun = true,
                ),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Skipped<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(outcome.action).isEqualTo(SyncAction.IMPORT)
        assertThat(harness.repository.saved).isEmpty()
        assertThat(harness.checkpointStore.savedBaselines).isEmpty()
        assertThat(harness.effectOutbox.appended).isEmpty()
        assertThat(harness.effectOutbox.relayRequests).isZero()
        assertThat(harness.auditTrail.outcomes).isEmpty()
        assertThat(harness.observer.outcomes).containsExactly(outcome)
    }

    @Test
    fun `entity import with a new idempotency claim completes the key`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("idempotent-import")
        val key = IdempotencyKey("entity-key")
        harness.remoteCatalog.foundOne(id, remoteRecord(id.value))

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(
                    externalId = id,
                    source = SyncTriggerSource.TEST,
                    idempotencyKey = key,
                ),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.outcomes.single().action).isEqualTo(SyncAction.IMPORT)
        assertThat(harness.idempotencyStore.claims).containsExactly(key)
        assertThat(harness.idempotencyStore.completed).containsExactly(key)
        assertThat(harness.idempotencyStore.failed).isEmpty()
        assertThat(harness.checkpointStore.savedBaselines).hasSize(1)
    }

    @Test
    fun `entity replays completed idempotency claim without fetching remote`() {
        val harness = WidgetHarness.build()
        val key = IdempotencyKey("replay-key")
        val replay = completedReport(SyncReportStatus.SUCCEEDED)
        harness.idempotencyStore.preComplete(key, replay)

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(
                    externalId = WidgetId("unused"),
                    source = SyncTriggerSource.TEST,
                    idempotencyKey = key,
                ),
            )

        assertThat(report).isSameAs(replay)
        assertThat(harness.remoteCatalog.fetchOneCalls).isEmpty()
        assertThat(harness.lockManager.requested).isEmpty()
        assertThat(harness.idempotencyStore.completed).isEmpty()
        assertThat(harness.auditTrail.runStarted).hasSize(1)
        assertThat(harness.auditTrail.runCompleted).isEmpty()
        assertThat(harness.observer.runCompleted).containsExactly(replay)
    }

    @Test
    fun `entity rejects in-progress idempotency claim and marks key failed`() {
        val harness = WidgetHarness.build()
        val key = IdempotencyKey("busy-key")
        harness.idempotencyStore.preClaimInProgress(key)

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(
                    externalId = WidgetId("busy"),
                    source = SyncTriggerSource.TEST,
                    idempotencyKey = key,
                ),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Failed<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.FAILED)
        assertThat(outcome.failure.kind).isEqualTo(SyncFailureKind.IDEMPOTENCY_IN_PROGRESS)
        assertThat(later(report).reason).isEqualTo("retryable failure")
        assertThat(harness.remoteCatalog.fetchOneCalls).isEmpty()
        assertThat(harness.lockManager.requested).isEmpty()
        assertThat(harness.idempotencyStore.failed).containsExactly(key)
    }

    @Test
    fun `entity lock denial fails before opening a transaction`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("denied")
        harness.lockManager.denyAll()

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Failed<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.FAILED)
        assertThat(outcome.subject).isEqualTo(SyncSubject.Remote(id))
        assertThat(outcome.failure.kind).isEqualTo(SyncFailureKind.LOCK_UNAVAILABLE)
        assertThat(harness.remoteCatalog.fetchOneCalls).containsExactly(id)
        assertThat(harness.unitOfWork.transactionCount).isZero()
        assertThat(harness.repository.saved).isEmpty()
        assertThat(harness.observer.outcomes).isEmpty()
    }

    @Test
    fun `entity remote fetch failure never deletes linked local and requeues`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("remote-down")
        val key = IdempotencyKey("remote-down-key")
        val local = Widget.linked("local-down", id, "SKU-DOWN", "Local")
        val failure = failure(SyncFailureKind.REMOTE_UNAVAILABLE, retryable = true, retryAfter = Duration.ofSeconds(3))
        harness.repository.seed(local)
        harness.remoteCatalog.onFetchOne(id, RemoteFetch.Failed(failure))

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(
                    externalId = id,
                    source = SyncTriggerSource.TEST,
                    idempotencyKey = key,
                ),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Failed<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.FAILED)
        assertThat(outcome.failure).isEqualTo(failure)
        assertThat(later(report).delay).isEqualTo(Duration.ofSeconds(3))
        assertThat(harness.repository.rows).containsExactly(local)
        assertThat(harness.repository.saved).isEmpty()
        assertThat(harness.idempotencyStore.failed).containsExactly(key)
    }

    @Test
    fun `entity partial remote fetch syncs the supplied record`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("partial-one")
        val local = Widget.linked("local-partial", id, "SKU-PARTIAL", "Old")
        harness.repository.seed(local)
        harness.remoteCatalog.onFetchOne(
            id,
            RemoteFetch.Partial(
                remoteRecord(id.value, sku = "SKU-PARTIAL", name = "New", observedAt = FIXED),
                failure(SyncFailureKind.REMOTE_PARTIAL, retryable = true),
            ),
        )

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.requeue).isEqualTo(RequeueDecision.Done)
        assertThat(report.outcomes.single().action).isEqualTo(SyncAction.UPDATE)
        assertThat(
            harness.repository.saved
                .single()
                .name,
        ).isEqualTo("New")
        assertThat(harness.checkpointStore.savedBaselines).hasSize(1)
    }

    @Test
    fun `entity missing remote deletes linked local through policy`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("missing-delete")
        harness.repository.seed(Widget.linked("local-missing", id, "SKU-MISSING", "Local"))
        harness.remoteCatalog.onFetchOne(id, RemoteFetch.Missing(MissingReason.GONE))

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.outcomes.single().action).isEqualTo(SyncAction.DELETE)
        assertThat(
            harness.repository.saved
                .single()
                .deleted,
        ).isTrue()
        assertThat(
            harness.repository.saved
                .single()
                .registration.lifecycle,
        ).isEqualTo(SyncRegistrationLifecycle.REMOTELY_DELETED)
        assertThat(harness.checkpointStore.savedBaselines).isEmpty()
    }

    @Test
    fun `entity missing remote unlinks when policy says unlink`() {
        val harness = WidgetHarness.build(missingRemotePolicy = WidgetHarness.UNLINK_MISSING)
        val id = WidgetId("missing-unlink")
        harness.repository.seed(Widget.linked("local-unlink", id, "SKU-UNLINK", "Local"))
        harness.remoteCatalog.onFetchOne(id, RemoteFetch.Missing(MissingReason.NOT_FOUND))

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.outcomes.single().action).isEqualTo(SyncAction.UNLINK)
        assertThat(
            harness.repository.saved
                .single()
                .registration.lifecycle,
        ).isEqualTo(SyncRegistrationLifecycle.UNLINKED)
        assertThat(harness.checkpointStore.savedBaselines).isEmpty()
    }

    @Test
    fun `entity updates linked local and saves a baseline`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("update")
        harness.repository.seed(Widget.linked("local-update", id, "SKU-UPDATE", "Old"))
        harness.remoteCatalog.foundOne(id, remoteRecord(id.value, sku = "SKU-UPDATE", name = "New"))

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.outcomes.single().action).isEqualTo(SyncAction.UPDATE)
        assertThat(
            harness.repository.saved
                .single()
                .name,
        ).isEqualTo("New")
        assertThat(harness.checkpointStore.savedBaselines).hasSize(1)
    }

    @Test
    fun `entity equal decision is skipped without mutation`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("equal")
        harness.repository.seed(Widget.linked("local-equal", id, "SKU-EQUAL", "Same"))
        harness.remoteCatalog.foundOne(id, remoteRecord(id.value, sku = "SKU-EQUAL", name = "Same"))

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Skipped<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(outcome.action).isEqualTo(SyncAction.EQUAL)
        assertThat(harness.repository.saved).isEmpty()
        assertThat(harness.auditTrail.outcomes).containsExactly(outcome)
    }

    @Test
    fun `entity restores remotely deleted local`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("restore")
        harness.repository.seed(Widget.remotelyDeleted("local-restore", id, "SKU-RESTORE", "Deleted"))
        harness.remoteCatalog.foundOne(id, remoteRecord(id.value, sku = "SKU-RESTORE", name = "Restored"))

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.outcomes.single().action).isEqualTo(SyncAction.RESTORE)
        assertThat(
            harness.repository.saved
                .single()
                .deleted,
        ).isFalse()
        assertThat(
            harness.repository.saved
                .single()
                .registration.lifecycle,
        ).isEqualTo(SyncRegistrationLifecycle.LINKED)
        assertThat(harness.checkpointStore.savedBaselines).hasSize(1)
    }

    @Test
    fun `entity relinks a remembered unlinked local`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("relink")
        harness.repository.seed(unlinkedWidget("local-relink", id, "SKU-RELINK", "Local"))
        harness.remoteCatalog.foundOne(id, remoteRecord(id.value, sku = "SKU-RELINK", name = "Remote"))

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.outcomes.single().action).isEqualTo(SyncAction.RELINK)
        assertThat(
            harness.repository.saved
                .single()
                .registration.remoteId,
        ).isEqualTo(id)
        assertThat(harness.checkpointStore.savedBaselines).hasSize(1)
    }

    @Test
    fun `entity remote tombstone soft deletes local`() {
        val harness = WidgetHarness.build()
        val id = WidgetId("tombstone")
        harness.repository.seed(Widget.linked("local-tombstone", id, "SKU-TOMBSTONE", "Local"))
        harness.remoteCatalog.foundOne(
            id,
            remoteRecord(
                id.value,
                sku = "SKU-TOMBSTONE",
                name = "Local",
                lifecycle = RemoteRecordLifecycle.DELETED,
                version = VersionStamp.Token("deleted-v1"),
            ),
        )

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.outcomes.single().action).isEqualTo(SyncAction.DELETE)
        assertThat(
            harness.repository.saved
                .single()
                .deleted,
        ).isTrue()
        assertThat(
            harness.repository.saved
                .single()
                .registration.version,
        ).isEqualTo(VersionStamp.Token("deleted-v1"))
    }

    @Test
    fun `entity retry decision from policy records a failed outcome`() {
        val retryAfter = Duration.ofSeconds(11)
        val harness = WidgetHarness.build(missingRemotePolicy = retryMissingPolicy(retryAfter))
        val id = WidgetId("retry-policy")
        harness.repository.seed(Widget.linked("local-retry", id, "SKU-RETRY", "Local"))
        harness.remoteCatalog.onFetchOne(id, RemoteFetch.Missing(MissingReason.NOT_FOUND))

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Failed<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.FAILED)
        assertThat(outcome.action).isEqualTo(SyncAction.RETRY)
        assertThat(outcome.failure.kind).isEqualTo(SyncFailureKind.REMOTE_RATE_LIMITED)
        assertThat(later(report).delay).isEqualTo(retryAfter)
        assertThat(harness.repository.saved).isEmpty()
    }

    @Test
    fun `entity decision effects are appended and relayed after commit`() {
        val harness = WidgetHarness.build(missingRemotePolicy = recalculatingUnlinkPolicy())
        val id = WidgetId("effect-unlink")
        harness.repository.seed(Widget.linked("local-effect", id, "SKU-EFFECT", "Local"))

        val report =
            SyncUseCaseFactory(harness.clock).entity(harness.definition).sync(
                SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.outcomes.single().action).isEqualTo(SyncAction.UNLINK)
        assertThat(harness.effectOutbox.appended).hasSize(1)
        assertThat(harness.effectOutbox.appended.single()).isInstanceOf(SyncEffect.Recalculate::class.java)
        assertThat(harness.unitOfWork.afterCommitRun).isEqualTo(1)
        assertThat(harness.effectOutbox.relayRequests).isEqualTo(1)
    }

    @Test
    // Structural threshold: one scenario deliberately exercises every list decision branch in order.
    @Suppress("LongMethod")
    fun `list per record executes import update equal restore relink and delete decisions`() {
        val scope = WidgetScope("list-decisions")
        val harness = WidgetHarness.build(listTransactionMode = ListTransactionMode.PER_RECORD)
        val importRecord = remoteRecord("list-import", sku = "SKU-IMPORT", name = "Imported")
        val updateId = WidgetId("list-update")
        val equalId = WidgetId("list-equal")
        val restoreId = WidgetId("list-restore")
        val relinkId = WidgetId("list-relink")
        val deleteId = WidgetId("list-delete")
        harness.repository.seed(
            Widget.linked("local-update", updateId, "SKU-UPDATE", "Old", scope),
            Widget.linked("local-equal", equalId, "SKU-EQUAL", "Same", scope),
            Widget.remotelyDeleted("local-restore", restoreId, "SKU-RESTORE", "Deleted", scope),
            unlinkedWidget("local-relink", relinkId, "SKU-RELINK", "Relink Local", scope),
            Widget.linked("local-delete", deleteId, "SKU-DELETE", "Delete Local", scope),
        )
        harness.remoteCatalog.onFetchForScope(
            RemoteFetch.Found(
                remotePage(
                    listOf(
                        upsert(importRecord),
                        upsert(remoteRecord(updateId.value, sku = "SKU-UPDATE", name = "New")),
                        upsert(remoteRecord(equalId.value, sku = "SKU-EQUAL", name = "Same")),
                        upsert(remoteRecord(restoreId.value, sku = "SKU-RESTORE", name = "Restored")),
                        upsert(remoteRecord(relinkId.value, sku = "SKU-RELINK", name = "Relink Remote")),
                        upsert(
                            remoteRecord(
                                deleteId.value,
                                sku = "SKU-DELETE",
                                name = "Delete Local",
                                lifecycle = RemoteRecordLifecycle.DELETED,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val report =
            SyncUseCaseFactory(harness.clock).list(harness.definition).sync(
                SyncListCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.outcomes.map { it.action })
            .contains(
                SyncAction.IMPORT,
                SyncAction.UPDATE,
                SyncAction.EQUAL,
                SyncAction.RESTORE,
                SyncAction.RELINK,
                SyncAction.DELETE,
            )
        assertThat(report.outcomes.filterIsInstance<SyncOutcome.Succeeded<*>>()).hasSize(5)
        assertThat(
            report.outcomes
                .filterIsInstance<SyncOutcome.Skipped<*>>()
                .single()
                .action,
        ).isEqualTo(SyncAction.EQUAL)
        assertThat(harness.repository.saved).hasSize(5)
        assertThat(harness.checkpointStore.savedBaselines).hasSize(4)
        assertThat(
            harness.repository.rows
                .single { it.registration.rememberedRemoteId == deleteId }
                .deleted,
        ).isTrue()
        assertThat(harness.unitOfWork.transactionCount).isEqualTo(7)
    }

    @Test
    fun `list unlink decision appends one recalculation effect and relays once`() {
        val scope = WidgetScope("list-recalc")
        val harness =
            WidgetHarness.build(
                missingRemotePolicy = recalculatingUnlinkPolicy(),
                listTransactionMode = ListTransactionMode.PER_RECORD,
            )
        val id = WidgetId("list-recalc-local")
        harness.repository.seed(Widget.linked("local-recalc", id, "SKU-RECALC", "Local", scope))
        harness.remoteCatalog.onFetchForScope(RemoteFetch.Found(remotePage()))

        val report =
            SyncUseCaseFactory(harness.clock).list(harness.definition).sync(
                SyncListCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.outcomes.single().action).isEqualTo(SyncAction.UNLINK)
        assertThat(harness.effectOutbox.appended.filterIsInstance<SyncEffect.Recalculate>()).hasSize(1)
        assertThat(harness.effectOutbox.relayRequests).isEqualTo(1)
        assertThat(harness.unitOfWork.afterCommitRun).isEqualTo(1)
    }

    @Test
    fun `list partial page requeues and processes only present upserts`() {
        val scope = WidgetScope("list-partial")
        val harness = WidgetHarness.build(listTransactionMode = ListTransactionMode.PER_RECORD)
        harness.remoteCatalog.onFetchForScope(
            RemoteFetch.Partial(
                remotePage(listOf(upsert(remoteRecord("partial-present", sku = "SKU-PRESENT", name = "Present")))),
                failure(SyncFailureKind.REMOTE_PARTIAL, retryable = true),
            ),
        )

        val report =
            SyncUseCaseFactory(harness.clock).list(harness.definition).sync(
                SyncListCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.outcomes).hasSize(1)
        assertThat(report.outcomes.single().action).isEqualTo(SyncAction.IMPORT)
        assertThat(
            harness.repository.saved
                .single()
                .registration.remoteId,
        ).isEqualTo(WidgetId("partial-present"))
        assertThat(later(report).reason).isEqualTo("partial remote page")
    }

    @Test
    fun `list transaction modes use their expected unit of work boundaries`() {
        val cases =
            listOf(
                ListTransactionMode.PER_RECORD to 2,
                ListTransactionMode.PER_PAGE to 1,
                ListTransactionMode.WHOLE_SCOPE to 1,
            )

        cases.forEach { (mode, expectedTransactions) ->
            val scope = WidgetScope("mode-$mode")
            val harness = WidgetHarness.build(listTransactionMode = mode)
            harness.remoteCatalog.onFetchForScope(
                RemoteFetch.Found(remotePage(listOf(upsert(remoteRecord("mode-$mode", sku = "SKU-$mode"))))),
            )

            val report =
                SyncUseCaseFactory(harness.clock).list(harness.definition).sync(
                    SyncListCommand(scope = scope, source = SyncTriggerSource.TEST),
                )

            assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
            assertThat(report.outcomes.single().action).isEqualTo(SyncAction.IMPORT)
            assertThat(harness.unitOfWork.transactionCount).isEqualTo(expectedTransactions)
        }
    }

    @Test
    fun `list per record mapper failure returns partial report and continues`() {
        val scope = WidgetScope("list-failure")
        val failId = WidgetId("fail-update")
        val okId = WidgetId("ok-update")
        val harness = WidgetHarness.build(listTransactionMode = ListTransactionMode.PER_RECORD)
        val definition = harness.definition.copy(mapper = FailingUpdateMapper(failId))
        harness.repository.seed(
            Widget.linked("local-fail", failId, "SKU-FAIL", "Old", scope),
            Widget.linked("local-ok", okId, "SKU-OK", "Old", scope),
        )
        harness.remoteCatalog.onFetchForScope(
            RemoteFetch.Found(
                remotePage(
                    listOf(
                        upsert(remoteRecord(failId.value, sku = "SKU-FAIL", name = "New Fail")),
                        upsert(remoteRecord(okId.value, sku = "SKU-OK", name = "New Ok")),
                    ),
                ),
            ),
        )

        val report =
            SyncUseCaseFactory(harness.clock).list(definition).sync(
                SyncListCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        val failure = report.outcomes.filterIsInstance<SyncOutcome.Failed<*>>().single()
        assertThat(report.status).isEqualTo(SyncReportStatus.PARTIAL)
        assertThat(report.outcomes.filterIsInstance<SyncOutcome.Succeeded<*>>()).hasSize(1)
        assertThat(failure.failure.kind).isEqualTo(SyncFailureKind.LOCAL_VALIDATION_FAILED)
        assertThat(failure.failure.causeClass).isEqualTo(IllegalStateException::class.java.name)
        assertThat(
            harness.repository.saved
                .single()
                .registration.remoteId,
        ).isEqualTo(okId)
        assertThat(harness.checkpointStore.savedBaselines).hasSize(1)
    }

    @Test
    fun `list stale plan guard re-reads local by remote id before executing`() {
        val requestedScope = WidgetScope("requested-scope")
        val otherScope = WidgetScope("other-scope")
        val id = WidgetId("stale-import")
        val harness = WidgetHarness.build(listTransactionMode = ListTransactionMode.PER_RECORD)
        harness.repository.seed(Widget.linked("local-stale", id, "SKU-STALE", "Same", otherScope))
        harness.remoteCatalog.onFetchForScope(
            RemoteFetch.Found(remotePage(listOf(upsert(remoteRecord(id.value, sku = "SKU-STALE", name = "Same"))))),
        )

        val report =
            SyncUseCaseFactory(harness.clock).list(harness.definition).sync(
                SyncListCommand(scope = requestedScope, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Skipped<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(outcome.action).isEqualTo(SyncAction.EQUAL)
        assertThat(harness.repository.saved).isEmpty()
        assertThat(harness.unitOfWork.transactionCount).isEqualTo(2)
    }

    @Test
    fun `list scope lock denial reports retryable failure without transaction`() {
        val scope = WidgetScope("lock-denied")
        val harness = WidgetHarness.build()
        harness.remoteCatalog.onFetchForScope(
            RemoteFetch.Found(remotePage(listOf(upsert(remoteRecord("denied-list"))))),
        )
        harness.lockManager.denyAll()

        val report =
            SyncUseCaseFactory(harness.clock).list(harness.definition).sync(
                SyncListCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Failed<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.FAILED)
        assertThat(outcome.failure.kind).isEqualTo(SyncFailureKind.LOCK_UNAVAILABLE)
        assertThat(later(report).reason).isEqualTo("retryable record failure")
        assertThat(harness.unitOfWork.transactionCount).isZero()
        assertThat(harness.repository.saved).isEmpty()
        assertThat(harness.observer.outcomes).isEmpty()
    }

    @Test
    fun `list missing scope fetch skips without treating it as authoritative empty`() {
        val scope = WidgetScope("missing-scope")
        val harness = WidgetHarness.build()
        harness.repository.seed(Widget.linked("local-missing-scope", WidgetId("still-linked"), "SKU", "Local", scope))
        harness.remoteCatalog.onFetchForScope(RemoteFetch.Missing(MissingReason.UNAUTHORIZED_AS_MISSING))

        val report =
            SyncUseCaseFactory(harness.clock).list(harness.definition).sync(
                SyncListCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Skipped<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(outcome.action).isNull()
        assertThat((outcome.reason as SyncReason.Policy).message).contains("not treating as authoritative empty set")
        assertThat(harness.lockManager.requested).isEmpty()
        assertThat(harness.repository.saved).isEmpty()
    }

    @Test
    fun `list failed scope fetch fails and requeues without deleting locals`() {
        val scope = WidgetScope("failed-scope")
        val harness = WidgetHarness.build()
        val local = Widget.linked("local-failed-scope", WidgetId("scope-local"), "SKU", "Local", scope)
        harness.repository.seed(local)
        harness.remoteCatalog.onFetchForScope(
            RemoteFetch.Failed(
                failure(SyncFailureKind.REMOTE_TIMEOUT, retryable = true, retryAfter = Duration.ofSeconds(8)),
            ),
        )

        val report =
            SyncUseCaseFactory(harness.clock).list(harness.definition).sync(
                SyncListCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Failed<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.FAILED)
        assertThat(outcome.failure.kind).isEqualTo(SyncFailureKind.REMOTE_TIMEOUT)
        assertThat(later(report).delay).isEqualTo(Duration.ofSeconds(8))
        assertThat(harness.lockManager.requested).isEmpty()
        assertThat(harness.repository.rows).containsExactly(local)
        assertThat(harness.repository.saved).isEmpty()
    }

    @Test
    fun `list whole scope conflict reports conflicted without executing decisions`() {
        val scope = WidgetScope("conflict-scope")
        val harness =
            WidgetHarness.build(
                listTransactionMode = ListTransactionMode.WHOLE_SCOPE,
                matchPasses = listOf(WidgetMatchPass.SKU),
            )
        harness.repository.seed(Widget.neverLinked("local-conflict", "SKU-CONFLICT", "Local", scope))
        harness.remoteCatalog.onFetchForScope(
            RemoteFetch.Found(
                remotePage(
                    listOf(
                        upsert(remoteRecord("conflict-a", sku = "SKU-CONFLICT", name = "A")),
                        upsert(remoteRecord("conflict-b", sku = "SKU-CONFLICT", name = "B")),
                    ),
                ),
            ),
        )

        val report =
            SyncUseCaseFactory(harness.clock).list(harness.definition).sync(
                SyncListCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Skipped<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.CONFLICTED)
        assertThat(outcome.action).isEqualTo(SyncAction.CONFLICT)
        assertThat(harness.repository.saved).isEmpty()
        assertThat(harness.auditTrail.outcomes).containsExactly(outcome)
    }

    @Test
    fun `list retry decision from policy records failed outcome and requeues`() {
        val scope = WidgetScope("list-retry")
        val retryAfter = Duration.ofSeconds(13)
        val harness =
            WidgetHarness.build(
                missingRemotePolicy = retryMissingPolicy(retryAfter),
                listTransactionMode = ListTransactionMode.PER_RECORD,
            )
        harness.repository.seed(
            Widget.linked("local-list-retry", WidgetId("list-retry-id"), "SKU-RETRY", "Local", scope),
        )
        harness.remoteCatalog.onFetchForScope(RemoteFetch.Found(remotePage()))

        val report =
            SyncUseCaseFactory(harness.clock).list(harness.definition).sync(
                SyncListCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Failed<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.FAILED)
        assertThat(outcome.action).isEqualTo(SyncAction.RETRY)
        assertThat(outcome.failure.kind).isEqualTo(SyncFailureKind.REMOTE_RATE_LIMITED)
        assertThat(later(report).delay).isEqualTo(retryAfter)
        assertThat(harness.repository.saved).isEmpty()
    }

    @Test
    fun `list dry run reports planned import without writes or audit outcome`() {
        val scope = WidgetScope("list-dry-run")
        val harness = WidgetHarness.build()
        harness.remoteCatalog.onFetchForScope(
            RemoteFetch.Found(remotePage(listOf(upsert(remoteRecord("list-dry-run"))))),
        )

        val report =
            SyncUseCaseFactory(harness.clock).list(harness.definition).sync(
                SyncListCommand(scope = scope, source = SyncTriggerSource.TEST, dryRun = true),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Skipped<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(outcome.action).isEqualTo(SyncAction.IMPORT)
        assertThat(harness.repository.saved).isEmpty()
        assertThat(harness.checkpointStore.savedBaselines).isEmpty()
        assertThat(harness.auditTrail.outcomes).isEmpty()
        assertThat(harness.observer.outcomes).containsExactly(outcome)
    }

    @Test
    fun `spawn uses stored cursor enqueues page changes advances cursor and relays`() {
        val scope = WidgetScope("spawn-stored")
        val harness = WidgetHarness.build()
        val stored =
            CursorCheckpoint(
                syncName = harness.definition.name,
                scope = scope,
                cursor = SyncCursor("stored"),
                highWatermark = SyncCursor("old-high"),
                updatedAt = FIXED,
                runId = SyncFixtures.runId(),
            )
        harness.checkpointStore.seedCursor(stored)
        harness.remoteCatalog.enqueuePage(
            RemoteFetch.Found(
                remotePage(
                    changes =
                        listOf(
                            upsert(remoteRecord("spawn-upsert")),
                            deleteChange("spawn-delete"),
                        ),
                    nextCursor = SyncCursor("next"),
                    highWatermark = null,
                ),
            ),
        )

        val report =
            SyncUseCaseFactory(harness.clock).spawn(harness.definition).spawn(
                SpawnSyncCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(harness.remoteCatalog.fetchPageCursors).containsExactly(SyncCursor("stored"))
        assertThat(enqueuedIds(harness.effectOutbox.appended))
            .containsExactly(WidgetId("spawn-upsert"), WidgetId("spawn-delete"))
        assertThat(harness.checkpointStore.casCalls).hasSize(1)
        assertThat(
            harness.checkpointStore.casCalls
                .single()
                .cursor,
        ).isEqualTo(SyncCursor("next"))
        assertThat(
            harness.checkpointStore.casCalls
                .single()
                .highWatermark,
        ).isEqualTo(SyncCursor("old-high"))
        assertThat(harness.unitOfWork.transactionCount).isEqualTo(1)
        assertThat(harness.unitOfWork.afterCommitRun).isEqualTo(1)
        assertThat(harness.effectOutbox.relayRequests).isEqualTo(1)
        assertThat(later(report).reason).isEqualTo("more pages remain")
    }

    @Test
    fun `spawn explicit cursor overrides checkpoint and full sync adds linked local ids`() {
        val scope = WidgetScope("spawn-full")
        val harness = WidgetHarness.build()
        harness.checkpointStore.seedCursor(
            CursorCheckpoint(
                syncName = harness.definition.name,
                scope = scope,
                cursor = SyncCursor("stored"),
                highWatermark = null,
                updatedAt = FIXED,
                runId = SyncFixtures.runId(),
            ),
        )
        harness.repository.seed(
            Widget.linked("local-page", WidgetId("spawn-page"), "SKU-PAGE", "Page", scope),
            Widget.linked("local-extra", WidgetId("spawn-extra"), "SKU-EXTRA", "Extra", scope),
        )
        harness.remoteCatalog.enqueuePage(
            RemoteFetch.Found(remotePage(listOf(upsert(remoteRecord("spawn-page", sku = "SKU-PAGE"))))),
        )

        val report =
            SyncUseCaseFactory(harness.clock).spawn(harness.definition).spawn(
                SpawnSyncCommand(
                    scope = scope,
                    cursor = SyncCursor("explicit"),
                    pageSize = 25,
                    fullSync = true,
                    source = SyncTriggerSource.TEST,
                ),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(harness.remoteCatalog.fetchPageCursors).containsExactly(SyncCursor("explicit"))
        assertThat(enqueuedIds(harness.effectOutbox.appended))
            .containsExactly(WidgetId("spawn-page"), WidgetId("spawn-extra"))
        assertThat(policyMessage(report.outcomes.single() as SyncOutcome.Skipped<*>))
            .contains("enqueued=2", "fullSync=true")
    }

    @Test
    fun `spawn dry run counts effects without appending or advancing cursor`() {
        val scope = WidgetScope("spawn-dry")
        val harness = WidgetHarness.build()
        harness.remoteCatalog.enqueuePage(
            RemoteFetch.Found(remotePage(listOf(upsert(remoteRecord("spawn-dry"))))),
        )

        val report =
            SyncUseCaseFactory(harness.clock).spawn(harness.definition).spawn(
                SpawnSyncCommand(scope = scope, source = SyncTriggerSource.TEST, dryRun = true),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Skipped<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(policyMessage(outcome)).contains("enqueued=1", "executed=false", "cursorAdvanced=false")
        assertThat(harness.lockManager.requested).isEmpty()
        assertThat(harness.unitOfWork.transactionCount).isZero()
        assertThat(harness.effectOutbox.appended).isEmpty()
        assertThat(harness.checkpointStore.casCalls).isEmpty()
        assertThat(harness.auditTrail.outcomes).isEmpty()
    }

    @Test
    fun `spawn partial page appends effects but does not advance cursor and requeues`() {
        val scope = WidgetScope("spawn-partial")
        val harness = WidgetHarness.build()
        harness.remoteCatalog.enqueuePage(
            RemoteFetch.Partial(
                remotePage(listOf(upsert(remoteRecord("spawn-partial"))), nextCursor = SyncCursor("retry-same")),
                failure(SyncFailureKind.REMOTE_PARTIAL, retryable = true),
            ),
        )

        val report =
            SyncUseCaseFactory(harness.clock).spawn(harness.definition).spawn(
                SpawnSyncCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Skipped<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(enqueuedIds(harness.effectOutbox.appended)).containsExactly(WidgetId("spawn-partial"))
        assertThat(harness.checkpointStore.casCalls).isEmpty()
        assertThat(policyMessage(outcome)).contains("cursorAdvanced=false")
        assertThat(later(report).reason).isEqualTo("partial remote page")
        assertThat(harness.effectOutbox.relayRequests).isEqualTo(1)
    }

    @Test
    fun `spawn retry hint requeues even when the page is otherwise exhausted`() {
        val scope = WidgetScope("spawn-retry")
        val retryAfter = Duration.ofSeconds(9)
        val harness = WidgetHarness.build()
        harness.remoteCatalog.enqueuePage(
            RemoteFetch.Found(remotePage(changes = emptyList(), retryAfter = retryAfter)),
        )

        val report =
            SyncUseCaseFactory(harness.clock).spawn(harness.definition).spawn(
                SpawnSyncCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(harness.effectOutbox.appended).isEmpty()
        assertThat(harness.effectOutbox.relayRequests).isZero()
        assertThat(harness.checkpointStore.casCalls).hasSize(1)
        assertThat(later(report).delay).isEqualTo(retryAfter)
        assertThat(later(report).reason).isEqualTo("remote retry hint")
    }

    @Test
    fun `spawn missing page with null scope skips with unknown subject`() {
        val harness = WidgetHarness.build()
        harness.remoteCatalog.enqueuePage(RemoteFetch.Missing(MissingReason.NOT_FOUND))

        val report =
            SyncUseCaseFactory(harness.clock).spawn(harness.definition).spawn(
                SpawnSyncCommand<WidgetScope>(scope = null, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Skipped<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(outcome.subject).isEqualTo(SyncSubject.Unknown)
        assertThat(outcome.action).isNull()
        assertThat(policyMessage(outcome)).contains("remote page missing")
        assertThat(harness.unitOfWork.transactionCount).isZero()
        assertThat(harness.effectOutbox.appended).isEmpty()
    }

    @Test
    fun `spawn failed page requeues without effects`() {
        val scope = WidgetScope("spawn-failed")
        val harness = WidgetHarness.build()
        harness.remoteCatalog.enqueuePage(
            RemoteFetch.Failed(
                failure(SyncFailureKind.CIRCUIT_OPEN, retryable = true, retryAfter = Duration.ofSeconds(4)),
            ),
        )

        val report =
            SyncUseCaseFactory(harness.clock).spawn(harness.definition).spawn(
                SpawnSyncCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Failed<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.FAILED)
        assertThat(outcome.failure.kind).isEqualTo(SyncFailureKind.CIRCUIT_OPEN)
        assertThat(later(report).delay).isEqualTo(Duration.ofSeconds(4))
        assertThat(harness.lockManager.requested).isEmpty()
        assertThat(harness.effectOutbox.appended).isEmpty()
        assertThat(harness.checkpointStore.casCalls).isEmpty()
    }

    @Test
    fun `spawn lock denial fails without appending effects or advancing cursor`() {
        val scope = WidgetScope("spawn-lock-denied")
        val harness = WidgetHarness.build()
        harness.lockManager.denyAll()
        harness.remoteCatalog.enqueuePage(
            RemoteFetch.Found(remotePage(listOf(upsert(remoteRecord("spawn-lock-denied"))))),
        )

        val report =
            SyncUseCaseFactory(harness.clock).spawn(harness.definition).spawn(
                SpawnSyncCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Failed<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.FAILED)
        assertThat(outcome.failure.kind).isEqualTo(SyncFailureKind.LOCK_UNAVAILABLE)
        assertThat(later(report).reason).isEqualTo("spawn could not progress")
        assertThat(harness.unitOfWork.transactionCount).isZero()
        assertThat(harness.effectOutbox.appended).isEmpty()
        assertThat(harness.checkpointStore.casCalls).isEmpty()
        assertThat(harness.observer.outcomes).isEmpty()
    }

    @Test
    fun `spawn cursor cas conflict leaves cursor advanced false after durable effects`() {
        val scope = WidgetScope("spawn-cas")
        val harness = WidgetHarness.build()
        harness.checkpointStore.casAlwaysFails = true
        harness.remoteCatalog.enqueuePage(
            RemoteFetch.Found(remotePage(listOf(upsert(remoteRecord("spawn-cas"))))),
        )

        val report =
            SyncUseCaseFactory(harness.clock).spawn(harness.definition).spawn(
                SpawnSyncCommand(scope = scope, source = SyncTriggerSource.TEST),
            )

        val outcome = report.outcomes.single() as SyncOutcome.Skipped<*>
        assertThat(report.status).isEqualTo(SyncReportStatus.SKIPPED)
        assertThat(enqueuedIds(harness.effectOutbox.appended)).containsExactly(WidgetId("spawn-cas"))
        assertThat(harness.checkpointStore.casCalls).hasSize(1)
        assertThat(policyMessage(outcome)).contains("cursorAdvanced=false")
        assertThat(harness.effectOutbox.relayRequests).isEqualTo(1)
    }

    private class FailingUpdateMapper(
        private val failedId: WidgetId,
    ) : SyncMapper<Widget, RemoteWidget, WidgetScope> {
        override fun create(
            remote: RemoteWidget,
            context: SyncContext<WidgetScope>,
        ): Widget = WidgetMapper.create(remote, context)

        override fun update(
            local: Widget,
            remote: RemoteWidget,
            changes: ChangeSet,
            context: SyncContext<WidgetScope>,
        ): Widget {
            if (remote.id == failedId) {
                throw IllegalStateException("rejecting ${remote.id.value}")
            }
            return WidgetMapper.update(local, remote, changes, context)
        }

        override fun restore(
            local: Widget,
            remote: RemoteWidget,
            changes: ChangeSet,
            context: SyncContext<WidgetScope>,
        ): Widget = WidgetMapper.restore(local, remote, changes, context)

        override fun relink(
            local: Widget,
            remote: RemoteWidget,
            changes: ChangeSet,
            context: SyncContext<WidgetScope>,
        ): Widget = WidgetMapper.relink(local, remote, changes, context)

        override fun delete(
            local: Widget,
            signal: RemoteDeleteSignal<*>,
            context: SyncContext<WidgetScope>,
        ): Widget = WidgetMapper.delete(local, signal, context)

        override fun unlink(
            local: Widget,
            reason: UnlinkReason,
            context: SyncContext<WidgetScope>,
        ): Widget = WidgetMapper.unlink(local, reason, context)
    }

    private companion object {
        val FIXED: Instant = Instant.parse("2026-06-30T00:00:00Z")

        // Private service data classes cannot be directly instantiated; their generated members are
        // only reachable through service execution.
        // SyncEntityService.finalizeIdempotency's failed+partial path is unreachable with one entity outcome.

        fun remoteRecord(
            id: String,
            sku: String = "SKU-$id",
            name: String = "Widget $id",
            lifecycle: RemoteRecordLifecycle = RemoteRecordLifecycle.ACTIVE,
            importable: Boolean = true,
            version: VersionStamp? = null,
            observedAt: Instant? = null,
        ): RemoteRecord<RemoteWidget, WidgetId, WidgetKey> {
            val widgetId = WidgetId(id)
            val remote =
                RemoteWidget(
                    id = widgetId,
                    sku = sku,
                    name = name,
                    lifecycle = lifecycle,
                    importable = importable,
                    version = version,
                    observedAt = observedAt,
                )
            return RemoteRecord(
                record = remote,
                externalId = widgetId,
                keys = setOf(WidgetKey.Remote(widgetId), WidgetKey.Sku(sku)),
                lifecycle = lifecycle,
                importable = importable,
                version = version,
                observedAt = observedAt,
            )
        }

        fun remotePage(
            changes: List<RemoteChange<RemoteWidget, WidgetId, WidgetKey>> = emptyList(),
            nextCursor: SyncCursor? = null,
            highWatermark: SyncCursor? = null,
            retryAfter: Duration? = null,
        ): RemotePage<RemoteWidget, WidgetId, WidgetKey> =
            RemotePage(
                changes = changes,
                nextCursor = nextCursor,
                highWatermark = highWatermark,
                retryAfter = retryAfter,
            )

        fun upsert(
            record: RemoteRecord<RemoteWidget, WidgetId, WidgetKey>,
        ): RemoteChange<RemoteWidget, WidgetId, WidgetKey> = RemoteChange.Upsert(record)

        fun deleteChange(id: String): RemoteChange<RemoteWidget, WidgetId, WidgetKey> =
            RemoteChange.Delete(
                signal =
                    RemoteDeleteSignal.ExplicitDelete(
                        remoteId = WidgetId(id),
                        deletedAt = null,
                        version = null,
                    ),
                version = null,
            )

        fun unlinkedWidget(
            localId: String,
            remoteId: WidgetId,
            sku: String,
            name: String,
            scope: WidgetScope = WidgetScope("default"),
        ): Widget {
            val linked = Widget.linked(localId, remoteId, sku, name, scope, FIXED)
            return linked.copy(registration = linked.registration.unlink(UnlinkReason.ManualDetach, FIXED))
        }

        fun failure(
            kind: SyncFailureKind,
            retryable: Boolean,
            retryAfter: Duration? = null,
            message: String = kind.name,
        ): SyncFailure = SyncFailure(kind = kind, message = message, retryable = retryable, retryAfter = retryAfter)

        fun retryMissingPolicy(retryAfter: Duration): MissingRemotePolicy<Widget, WidgetId, WidgetKey> =
            MissingRemotePolicy { local, _ ->
                SyncDecision.Retry(
                    subject =
                        local.registration.rememberedRemoteId
                            ?.let { SyncSubject.Pair(local.localId, it) }
                            ?: SyncSubject.Unknown,
                    delay = retryAfter,
                    failure =
                        failure(
                            kind = SyncFailureKind.REMOTE_RATE_LIMITED,
                            retryable = true,
                            retryAfter = retryAfter,
                        ),
                )
            }

        fun recalculatingUnlinkPolicy(): MissingRemotePolicy<Widget, WidgetId, WidgetKey> =
            MissingRemotePolicy { local, _ ->
                val subject = local.localId?.let { SyncSubject.Local(it) } ?: SyncSubject.Unknown
                SyncDecision.Unlink(
                    local = local,
                    unlinkReason = UnlinkReason.Policy("local-only"),
                    effects =
                        listOf(
                            SyncEffect.Recalculate(
                                key = "recalculate:${local.localId?.value ?: "unknown"}",
                                subject = subject,
                                reason = "local-only",
                            ),
                        ),
                )
            }

        fun completedReport(status: SyncReportStatus): SyncReport =
            SyncReport(
                context = SyncFixtures.context(),
                status = status,
                startedAt = FIXED,
                completedAt = FIXED,
                outcomes = emptyList(),
            )

        fun succeeded(
            action: SyncAction,
            id: String,
        ): SyncOutcome.Succeeded<WidgetId> =
            SyncOutcome.Succeeded(
                subject = SyncSubject.Remote(WidgetId(id)),
                action = action,
                duration = Duration.ZERO,
            )

        fun failed(
            action: SyncAction,
            retryable: Boolean,
            id: String,
        ): SyncOutcome.Failed<WidgetId> =
            SyncOutcome.Failed(
                subject = SyncSubject.Remote(WidgetId(id)),
                action = action,
                duration = Duration.ZERO,
                failure = failure(SyncFailureKind.UNKNOWN, retryable = retryable),
            )

        fun later(report: SyncReport): RequeueDecision.Later = report.requeue as RequeueDecision.Later

        fun policyMessage(outcome: SyncOutcome.Skipped<*>): String = (outcome.reason as SyncReason.Policy).message

        fun enqueuedIds(effects: List<SyncEffect>): List<Any?> =
            effects.filterIsInstance<SyncEffect.EnqueueEntitySync<*>>().map { it.externalId }
    }
}
