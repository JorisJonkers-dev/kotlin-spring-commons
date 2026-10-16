package com.jorisjonkers.personalstack.common.sync.domain

import com.jorisjonkers.personalstack.common.sync.testsupport.RemoteWidget
import com.jorisjonkers.personalstack.common.sync.testsupport.SyncFixtures
import com.jorisjonkers.personalstack.common.sync.testsupport.Widget
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetDiffer
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetId
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetKey
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetLocalProjector
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetRemoteProjector
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

private typealias WidgetDecision = SyncDecision<Widget, RemoteWidget, WidgetId, WidgetKey>

class DecisionPlanReportTest {
    private val remoteId = WidgetId("w-1")
    private val otherRemoteId = WidgetId("w-2")
    private val changedAt = Instant.parse("2026-06-30T12:00:00Z")

    @Test
    fun `each decision carries the action it stands for`() {
        assertThat(decisionVariants().ordered.map { it.action }).containsExactly(
            SyncAction.IMPORT,
            SyncAction.UPDATE,
            SyncAction.EQUAL,
            SyncAction.DELETE,
            SyncAction.UNLINK,
            SyncAction.UNLINK,
            SyncAction.UNLINK,
            SyncAction.RESTORE,
            SyncAction.RELINK,
            SyncAction.IGNORE,
            SyncAction.CONFLICT,
            SyncAction.RETRY,
        )
    }

    @Test
    fun `an import is an executable decision about the remote record alone`() {
        val variants = decisionVariants()
        val import = variants.importDecision

        assertThat(import.subject).isEqualTo(SyncSubject.Remote(remoteId))
        assertThat(import.reason).isEqualTo(SyncReason.RemoteOnly)
        assertThat(import.executable).isTrue()
        assertThat(import.remoteRecord).isSameAs(variants.inputs.remote)
        assertThat(import.localRecord).isNull()
        assertThat(import.changes).isNull()
        assertThat(import.deleteSignal).isNull()
        assertThat(import.unlinkReason).isNull()
        assertThat(import.failure).isNull()
    }

    @Test
    fun `an update names the linked pair, its changes and its effects`() {
        val variants = decisionVariants()
        val update = variants.update

        assertThat(update.subject).isEqualTo(SyncSubject.Pair(variants.inputs.local.localId, remoteId))
        assertThat(update.reason).isEqualTo(SyncReason.RemoteChanged)
        assertThat(update.effects).containsExactly(variants.inputs.effect)
        assertThat(update.localRecord).isSameAs(variants.inputs.local)
        assertThat(update.remoteRecord).isSameAs(variants.inputs.remote)
        assertThat(update.changes).isSameAs(variants.inputs.changes)
    }

    @Test
    fun `an equal, ignored or retried decision is not executed`() {
        val variants = decisionVariants()

        assertThat(variants.equal.reason).isEqualTo(SyncReason.AlreadyEqual)
        assertThat(variants.equal.localRecord).isSameAs(variants.inputs.local)
        assertThat(listOf(variants.equal, variants.ignore, variants.retry).map { it.executable }).containsOnly(false)
    }

    @Test
    fun `a delete names the signal that deleted the record and no remote record`() {
        val delete = decisionVariants().delete

        assertThat(delete.reason).isEqualTo(SyncReason.RemoteDeleted)
        assertThat(delete.deleteSignal).isEqualTo(RemoteDeleteSignal.MissingFromAuthoritativeList(remoteId, changedAt))
        assertThat(delete.remoteRecord).isNull()
    }

    @Test
    fun `an unlink is about the pair it remembers, the local record alone, or nobody known`() {
        val variants = decisionVariants()

        assertThat(
            variants.unlinkRemembered.subject,
        ).isEqualTo(SyncSubject.Pair(variants.inputs.local.localId, remoteId))
        assertThat(variants.unlinkRemembered.unlinkReason).isEqualTo(UnlinkReason.Policy("remote missing"))
        assertThat(variants.unlinkLocalOnly.subject).isEqualTo(SyncSubject.Local(LocalId("local-2")))
        assertThat(variants.unlinkUnknown.subject).isEqualTo(SyncSubject.Unknown)
    }

    @Test
    fun `a restore, relink, conflict or retry gives the reason it was decided`() {
        val variants = decisionVariants()

        assertThat(variants.restore.reason).isEqualTo(SyncReason.RestoreLinkedRecord)
        assertThat(variants.relink.reason).isEqualTo(SyncReason.RelinkByNaturalKey)
        assertThat(variants.conflictDecision.reason).isEqualTo(SyncReason.Conflict(variants.inputs.conflict))
        assertThat(variants.retry.reason).isEqualTo(SyncReason.RetryLater(variants.inputs.failure))
    }

    @Test
    fun `a retry carries the failure that caused it`() {
        val variants = decisionVariants()

        assertThat(variants.retry.failure).isSameAs(variants.inputs.failure)
    }

    @Test
    fun `each subject has a stable key built from its ids`() {
        assertThat(SyncSubject.Remote(remoteId).stableKey).isEqualTo("remote:$remoteId")
        assertThat(SyncSubject.Local(LocalId("local-1")).stableKey).isEqualTo("local:local-1")
        assertThat(SyncSubject.Pair(LocalId("local-1"), remoteId).stableKey).isEqualTo("pair:local-1:$remoteId")
        assertThat(SyncSubject.Scope(ScopeId("catalog")).stableKey).isEqualTo("scope:catalog")
        assertThat(SyncSubject.Unknown.stableKey).isEqualTo("unknown")
    }

    @Test
    fun `a pair without a local id has an unknown local part in its key`() {
        assertThat(
            SyncSubject.Pair<WidgetId>(localId = null, externalId = remoteId).stableKey,
        ).isEqualTo("pair:unknown:$remoteId")
    }

    @Test
    fun `a stable key given replaces the one built from the ids`() {
        assertThat(SyncSubject.Remote(remoteId, stableKey = "custom-remote").stableKey).isEqualTo("custom-remote")
        assertThat(
            SyncSubject.Local(LocalId("local-1"), stableKey = "custom-local").stableKey,
        ).isEqualTo("custom-local")
        assertThat(
            SyncSubject.Pair(LocalId("local-1"), remoteId, stableKey = "custom-pair").stableKey,
        ).isEqualTo("custom-pair")
        assertThat(
            SyncSubject.Scope(ScopeId("catalog"), stableKey = "custom-scope").stableKey,
        ).isEqualTo("custom-scope")
    }

    @Test
    fun `a plan without conflicts or changes is executable and has nothing to do`() {
        val local = localRecord()
        val remote = remoteRecord()
        val plan =
            SyncPlan<Widget, RemoteWidget, WidgetId, WidgetKey>(
                context = SyncFixtures.context(),
                decisions =
                    listOf(
                        SyncDecision.Equal(local = local, remote = remote),
                        SyncDecision.Ignore(subject = SyncSubject.Remote(remoteId), reason = SyncReason.NotImportable),
                    ),
            )

        assertThat(plan.conflicts).isEmpty()
        assertThat(plan.executable).isTrue()
        assertThat(plan.substantive).isFalse()
    }

    @Test
    fun `a plan with a conflict is not executable but has something to do`() {
        val local = localRecord()
        val remote = remoteRecord()
        val conflict =
            SyncDecision.Conflict<Widget, RemoteWidget, WidgetId, WidgetKey>(
                SyncConflict(
                    subject = SyncSubject.Pair(local.localId, remoteId),
                    kind = SyncConflictKind.AMBIGUOUS_MATCH,
                    local = local,
                    remote = remote,
                    message = "more than one candidate",
                ),
            )
        val plan =
            SyncPlan(
                context = SyncFixtures.context(),
                decisions =
                    listOf(
                        SyncDecision.Update(local = local, remote = remote, changes = ChangeSet()),
                        conflict,
                    ),
            )

        assertThat(plan.conflicts).containsExactly(conflict)
        assertThat(plan.executable).isFalse()
        assertThat(plan.substantive).isTrue()
    }

    @Test
    fun `a report requeues nothing unless told to`() {
        val context = SyncFixtures.context(scope = WidgetScope("default"))
        val report =
            SyncReport(
                context = context,
                status = SyncReportStatus.SUCCEEDED,
                startedAt = context.startedAt,
                completedAt = context.startedAt.plusSeconds(5),
                outcomes = emptyList(),
            )

        assertThat(report.requeue).isEqualTo(RequeueDecision.Done)
    }

    private fun decisionInputs(): DecisionInputs {
        val local = localRecord()
        val remote = remoteRecord()
        val failure =
            SyncFailure(
                kind = SyncFailureKind.REMOTE_TIMEOUT,
                message = "remote timed out",
                retryable = true,
                retryAfter = Duration.ofSeconds(3),
                causeClass = "TimeoutException",
            )
        val conflict =
            SyncConflict(
                subject = SyncSubject.Pair(local.localId, remoteId),
                kind = SyncConflictKind.LINK_MISMATCH,
                local = local,
                remote = remote,
                message = "linked to a different remote",
            )
        return DecisionInputs(
            local = local,
            remote = remote,
            changes = WidgetDiffer.diff(local.aggregate, remote.record),
            effect =
                SyncEffect.EnqueueEntitySync(
                    key = "enqueue-w-2",
                    syncName = SyncName("widget"),
                    externalId = otherRemoteId,
                ),
            failure = failure,
            conflict = conflict,
        )
    }

    private fun decisionVariants(inputs: DecisionInputs = decisionInputs()): DecisionVariants {
        val unlinkUnknownLocal: LocalRecord<Widget, WidgetId, WidgetKey> =
            LocalRecord(
                aggregate = Widget.neverLinked("local-unknown", "SKU-UNKNOWN", "Detached").copy(localId = null),
                localId = null,
                registration = SyncRegistration.inferred<WidgetId>(remoteId = null),
                keys = emptySet(),
            )
        val deleteSignal = RemoteDeleteSignal.MissingFromAuthoritativeList(remoteId, changedAt)
        return DecisionVariants(
            inputs = inputs,
            importDecision = SyncDecision.Import(remote = inputs.remote),
            update =
                SyncDecision.Update(
                    local = inputs.local,
                    remote = inputs.remote,
                    changes = inputs.changes,
                    effects = listOf(inputs.effect),
                ),
            equal = SyncDecision.Equal(local = inputs.local, remote = inputs.remote),
            delete = SyncDecision.Delete(local = inputs.local, signal = deleteSignal),
            unlinkRemembered = SyncDecision.Unlink(inputs.local, UnlinkReason.Policy("remote missing")),
            unlinkLocalOnly =
                SyncDecision.Unlink(
                    localRecord(Widget.neverLinked("local-2", "SKU-2", "Detached")),
                    UnlinkReason.ManualDetach,
                ),
            unlinkUnknown =
                SyncDecision.Unlink<Widget, RemoteWidget, WidgetId, WidgetKey>(
                    unlinkUnknownLocal,
                    UnlinkReason.Policy("unknown local"),
                ),
            restore = SyncDecision.Restore(inputs.local, inputs.remote, inputs.changes),
            relink = SyncDecision.Relink(inputs.local, inputs.remote, inputs.changes),
            ignore = SyncDecision.Ignore(SyncSubject.Remote(remoteId), SyncReason.NotImportable),
            conflictDecision = SyncDecision.Conflict(inputs.conflict),
            retry = SyncDecision.Retry(SyncSubject.Remote(remoteId), Duration.ofSeconds(3), inputs.failure),
        )
    }

    private fun localRecord(
        widget: Widget = Widget.linked("local-1", remoteId, "SKU-1", "Old"),
    ): LocalRecord<Widget, WidgetId, WidgetKey> = WidgetLocalProjector.project(widget)

    private fun remoteRecord(
        remote: RemoteWidget = RemoteWidget(id = remoteId, sku = "SKU-1", name = "New"),
    ): RemoteRecord<RemoteWidget, WidgetId, WidgetKey> = WidgetRemoteProjector.project(remote)

    private data class DecisionVariants(
        val inputs: DecisionInputs,
        val importDecision: SyncDecision.Import<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val update: SyncDecision.Update<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val equal: SyncDecision.Equal<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val delete: SyncDecision.Delete<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val unlinkRemembered: SyncDecision.Unlink<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val unlinkLocalOnly: SyncDecision.Unlink<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val unlinkUnknown: SyncDecision.Unlink<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val restore: SyncDecision.Restore<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val relink: SyncDecision.Relink<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val ignore: SyncDecision.Ignore<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val conflictDecision: SyncDecision.Conflict<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val retry: SyncDecision.Retry<Widget, RemoteWidget, WidgetId, WidgetKey>,
    ) {
        val ordered: List<WidgetDecision> =
            listOf(
                importDecision,
                update,
                equal,
                delete,
                unlinkRemembered,
                unlinkLocalOnly,
                unlinkUnknown,
                restore,
                relink,
                ignore,
                conflictDecision,
                retry,
            )
    }

    private data class DecisionInputs(
        val local: LocalRecord<Widget, WidgetId, WidgetKey>,
        val remote: RemoteRecord<RemoteWidget, WidgetId, WidgetKey>,
        val changes: ChangeSet,
        val effect: SyncEffect.EnqueueEntitySync<WidgetId>,
        val failure: SyncFailure,
        val conflict: SyncConflict<Widget, RemoteWidget, WidgetId, WidgetKey>,
    )
}
