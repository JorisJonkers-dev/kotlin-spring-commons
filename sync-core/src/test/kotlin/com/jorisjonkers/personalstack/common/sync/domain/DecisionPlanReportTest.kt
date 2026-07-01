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
    fun `sync actions reasons subjects and decisions expose their default contract`() {
        val variants = decisionVariants()

        assertActionOrder(variants)
        assertDecisionDefaults(variants)
        assertReasonContracts(variants.inputs)
    }

    @Test
    fun `sync subjects support stable keys custom keys and generated members`() {
        val remote = SyncSubject.Remote(remoteId)
        val customRemote = remote.copy(stableKey = "custom-remote")
        val local = SyncSubject.Local(LocalId("local-1"))
        val customLocal = local.copy(stableKey = "custom-local")
        val pair = SyncSubject.Pair(LocalId("local-1"), remoteId)
        val pairWithoutLocal = SyncSubject.Pair<WidgetId>(localId = null, externalId = remoteId)
        val customPair = pair.copy(stableKey = "custom-pair")
        val scope = SyncSubject.Scope(ScopeId("catalog"))
        val customScope = scope.copy(stableKey = "custom-scope")

        assertThat(remote.stableKey).isEqualTo("remote:$remoteId")
        assertThat(customRemote.component1()).isEqualTo(remoteId)
        assertThat(customRemote.component2()).isEqualTo("custom-remote")
        assertThat(local.stableKey).isEqualTo("local:local-1")
        assertThat(customLocal.component1()).isEqualTo(LocalId("local-1"))
        assertThat(customLocal.component2()).isEqualTo("custom-local")
        assertThat(pair.stableKey).isEqualTo("pair:local-1:$remoteId")
        assertThat(pairWithoutLocal.stableKey).isEqualTo("pair:unknown:$remoteId")
        assertThat(customPair.component1()).isEqualTo(LocalId("local-1"))
        assertThat(customPair.component2()).isEqualTo(remoteId)
        assertThat(customPair.component3()).isEqualTo("custom-pair")
        assertThat(scope.stableKey).isEqualTo("scope:catalog")
        assertThat(customScope.component1()).isEqualTo(ScopeId("catalog"))
        assertThat(customScope.component2()).isEqualTo("custom-scope")
        assertThat(SyncSubject.Unknown.stableKey).isEqualTo("unknown")
        assertThat(SyncSubject.Unknown.toString()).isEqualTo("Unknown")
        assertThat(remote).isEqualTo(SyncSubject.Remote(remoteId))
        assertThat(remote.hashCode()).isEqualTo(SyncSubject.Remote(remoteId).hashCode())
        assertThat(remote.toString()).contains("Remote")
    }

    @Test
    fun `sync plan exposes conflict and substantive derived views`() {
        val context = SyncFixtures.context()
        val local = localRecord()
        val remote = remoteRecord()
        val conflict =
            SyncConflict(
                subject = SyncSubject.Pair(local.localId, remoteId),
                kind = SyncConflictKind.AMBIGUOUS_MATCH,
                local = local,
                remote = remote,
                message = "more than one candidate",
            )
        val conflictDecision = SyncDecision.Conflict<Widget, RemoteWidget, WidgetId, WidgetKey>(conflict)
        val quietPlan =
            SyncPlan<Widget, RemoteWidget, WidgetId, WidgetKey>(
                context = context,
                decisions =
                    listOf(
                        SyncDecision.Equal(local = local, remote = remote),
                        SyncDecision.Ignore<Widget, RemoteWidget, WidgetId, WidgetKey>(
                            subject = SyncSubject.Remote(remoteId),
                            reason = SyncReason.NotImportable,
                        ),
                    ),
            )
        val conflictedPlan =
            quietPlan.copy(
                decisions =
                    listOf(
                        SyncDecision.Update<Widget, RemoteWidget, WidgetId, WidgetKey>(
                            local = local,
                            remote = remote,
                            changes = ChangeSet(),
                        ),
                        conflictDecision,
                    ),
            )

        assertThat(quietPlan.conflicts).isEmpty()
        assertThat(quietPlan.executable).isTrue()
        assertThat(quietPlan.substantive).isFalse()
        assertThat(conflictedPlan.conflicts).containsExactly(conflictDecision)
        assertThat(conflictedPlan.executable).isFalse()
        assertThat(conflictedPlan.substantive).isTrue()
        assertThat(conflictedPlan.component1()).isEqualTo(context)
        assertThat(conflictedPlan.component2()).containsExactly(
            SyncDecision.Update<Widget, RemoteWidget, WidgetId, WidgetKey>(
                local = local,
                remote = remote,
                changes = ChangeSet(),
            ),
            conflictDecision,
        )
        assertThat(conflictedPlan.toString()).contains("decisions")
        assertThat(conflictedPlan.hashCode()).isEqualTo(conflictedPlan.copy().hashCode())
    }

    @Test
    fun `sync outcomes reports and requeue decisions cover statuses actions defaults and generated members`() {
        val fixture = outcomeReportFixture()

        assertReportStatuses()
        assertRequeueDecision(fixture.later)
        assertOutcomeGeneratedMembers(fixture)
        assertReportGeneratedMembers(fixture)
    }

    @Test
    fun `match plan candidates conflicts and enums expose all fields and generated members`() {
        val fixture = matchConflictFixture()

        assertMatchEnums()
        assertMatchPasses(fixture)
        assertMatchGeneratedMembers(fixture)
        assertConflictGeneratedMembers(fixture)
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

    private fun assertActionOrder(variants: DecisionVariants) {
        assertThat(SyncAction.entries).containsExactly(
            SyncAction.IMPORT,
            SyncAction.UPDATE,
            SyncAction.EQUAL,
            SyncAction.DELETE,
            SyncAction.UNLINK,
            SyncAction.RESTORE,
            SyncAction.RELINK,
            SyncAction.IGNORE,
            SyncAction.CONFLICT,
            SyncAction.RETRY,
        )
        assertThat(variants.ordered.map { it.action }).containsExactly(
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

    private fun assertDecisionDefaults(variants: DecisionVariants) {
        assertThat(variants.importDecision.subject).isEqualTo(SyncSubject.Remote(remoteId))
        assertThat(variants.importDecision.reason).isEqualTo(SyncReason.RemoteOnly)
        assertThat(variants.importDecision.executable).isTrue()
        assertThat(variants.importDecision.remoteRecord).isSameAs(variants.inputs.remote)
        assertThat(variants.importDecision.localRecord).isNull()
        assertThat(variants.importDecision.changes).isNull()
        assertThat(variants.importDecision.deleteSignal).isNull()
        assertThat(variants.importDecision.unlinkReason).isNull()
        assertThat(variants.importDecision.failure).isNull()
        assertThat(variants.update.subject).isEqualTo(SyncSubject.Pair(variants.inputs.local.localId, remoteId))
        assertThat(variants.update.reason).isEqualTo(SyncReason.RemoteChanged)
        assertThat(variants.update.effects).containsExactly(variants.inputs.effect)
        assertThat(variants.update.localRecord).isSameAs(variants.inputs.local)
        assertThat(variants.update.remoteRecord).isSameAs(variants.inputs.remote)
        assertThat(variants.update.changes).isSameAs(variants.inputs.changes)
        assertThat(variants.equal.reason).isEqualTo(SyncReason.AlreadyEqual)
        assertThat(variants.equal.executable).isFalse()
        assertThat(variants.equal.localRecord).isSameAs(variants.inputs.local)
        assertThat(variants.delete.reason).isEqualTo(SyncReason.RemoteDeleted)
        assertThat(
            variants.delete.deleteSignal,
        ).isEqualTo(RemoteDeleteSignal.MissingFromAuthoritativeList(remoteId, changedAt))
        assertThat(variants.delete.remoteRecord).isNull()
        assertThat(
            variants.unlinkRemembered.subject,
        ).isEqualTo(SyncSubject.Pair(variants.inputs.local.localId, remoteId))
        assertThat(variants.unlinkLocalOnly.subject).isEqualTo(SyncSubject.Local(LocalId("local-2")))
        assertThat(variants.unlinkUnknown.subject).isEqualTo(SyncSubject.Unknown)
        assertThat(variants.unlinkRemembered.unlinkReason).isEqualTo(UnlinkReason.Policy("remote missing"))
        assertThat(variants.restore.reason).isEqualTo(SyncReason.RestoreLinkedRecord)
        assertThat(variants.relink.reason).isEqualTo(SyncReason.RelinkByNaturalKey)
        assertThat(variants.ignore.executable).isFalse()
        assertThat(variants.conflictDecision.reason).isEqualTo(SyncReason.Conflict(variants.inputs.conflict))
        assertThat(variants.retry.reason).isEqualTo(SyncReason.RetryLater(variants.inputs.failure))
        assertThat(variants.retry.failure).isSameAs(variants.inputs.failure)
        assertThat(variants.retry.executable).isFalse()
    }

    private fun assertReasonContracts(inputs: DecisionInputs) {
        assertThat(SyncReason.Policy("custom").component1()).isEqualTo("custom")
        assertThat(SyncReason.Policy("custom").copy()).isEqualTo(SyncReason.Policy("custom"))
        assertThat(SyncReason.Conflict(inputs.conflict).component1()).isEqualTo(inputs.conflict)
        assertThat(SyncReason.Conflict(inputs.conflict).copy()).isEqualTo(SyncReason.Conflict(inputs.conflict))
        assertThat(SyncReason.RetryLater(inputs.failure).component1()).isEqualTo(inputs.failure)
        assertThat(SyncReason.RetryLater(inputs.failure).copy()).isEqualTo(SyncReason.RetryLater(inputs.failure))
        assertThat(SyncReason.RemoteOnly.toString()).isEqualTo("RemoteOnly")
    }

    private fun outcomeReportFixture(): OutcomeReportFixture {
        val context = SyncFixtures.context(scope = WidgetScope("default"))
        val startedAt = context.startedAt
        val completedAt = startedAt.plusSeconds(5)
        val subject = SyncSubject.Remote(remoteId)
        val outcomes = outcomeFixture(subject)
        val later = RequeueDecision.Later(delay = Duration.ofMinutes(1), reason = "retry page")
        val defaultReport =
            SyncReport(
                context = context,
                status = SyncReportStatus.SUCCEEDED,
                startedAt = startedAt,
                completedAt = completedAt,
                outcomes = listOf(outcomes.succeeded),
            )
        val partialReport =
            defaultReport.copy(
                status = SyncReportStatus.PARTIAL,
                outcomes = listOf(outcomes.succeeded, outcomes.skipped, outcomes.failed),
                requeue = later,
            )

        return OutcomeReportFixture(
            context = context,
            startedAt = startedAt,
            completedAt = completedAt,
            subject = subject,
            failure = outcomes.failure,
            succeeded = outcomes.succeeded,
            skipped = outcomes.skipped,
            failed = outcomes.failed,
            later = later,
            defaultReport = defaultReport,
            partialReport = partialReport,
        )
    }

    private fun outcomeFixture(subject: SyncSubject<WidgetId>): OutcomeFixture {
        val failure =
            SyncFailure(
                kind = SyncFailureKind.REMOTE_RATE_LIMITED,
                message = "rate limited",
                retryable = true,
                retryAfter = Duration.ofMinutes(1),
            )
        return OutcomeFixture(
            failure = failure,
            succeeded =
                SyncOutcome.Succeeded(
                    subject = subject,
                    action = SyncAction.IMPORT,
                    duration = Duration.ofMillis(10),
                ),
            skipped =
                SyncOutcome.Skipped(
                    subject = subject,
                    action = null,
                    duration = Duration.ZERO,
                    reason = SyncReason.Policy("dry run"),
                ),
            failed =
                SyncOutcome.Failed(
                    subject = subject,
                    action = SyncAction.RETRY,
                    duration = Duration.ofMillis(20),
                    failure = failure,
                ),
        )
    }

    private fun assertReportStatuses() {
        assertThat(SyncReportStatus.entries).containsExactly(
            SyncReportStatus.SUCCEEDED,
            SyncReportStatus.PARTIAL,
            SyncReportStatus.FAILED,
            SyncReportStatus.CONFLICTED,
            SyncReportStatus.SKIPPED,
        )
    }

    private fun assertRequeueDecision(later: RequeueDecision.Later) {
        assertThat(RequeueDecision.Done.toString()).isEqualTo("Done")
        assertThat(later.component1()).isEqualTo(Duration.ofMinutes(1))
        assertThat(later.component2()).isEqualTo("retry page")
        assertThat(later.copy(reason = "retry entity")).isEqualTo(
            RequeueDecision.Later(Duration.ofMinutes(1), "retry entity"),
        )
    }

    private fun assertOutcomeGeneratedMembers(fixture: OutcomeReportFixture) {
        assertThat(listOf(fixture.succeeded, fixture.skipped, fixture.failed).map { it.action })
            .containsExactly(SyncAction.IMPORT, null, SyncAction.RETRY)
        assertThat(fixture.succeeded.component1()).isEqualTo(fixture.subject)
        assertThat(fixture.succeeded.component2()).isEqualTo(SyncAction.IMPORT)
        assertThat(fixture.succeeded.component3()).isEqualTo(Duration.ofMillis(10))
        assertThat(fixture.skipped.component4()).isEqualTo(SyncReason.Policy("dry run"))
        assertThat(fixture.failed.component4()).isEqualTo(fixture.failure)
    }

    private fun assertReportGeneratedMembers(fixture: OutcomeReportFixture) {
        assertThat(fixture.defaultReport.requeue).isEqualTo(RequeueDecision.Done)
        assertThat(fixture.partialReport.component1()).isEqualTo(fixture.context)
        assertThat(fixture.partialReport.component2()).isEqualTo(SyncReportStatus.PARTIAL)
        assertThat(fixture.partialReport.component3()).isEqualTo(fixture.startedAt)
        assertThat(fixture.partialReport.component4()).isEqualTo(fixture.completedAt)
        assertThat(fixture.partialReport.component5())
            .containsExactly(fixture.succeeded, fixture.skipped, fixture.failed)
        assertThat(fixture.partialReport.component6()).isEqualTo(fixture.later)
        assertThat(fixture.partialReport.toString()).contains("PARTIAL")
        assertThat(fixture.partialReport.hashCode()).isEqualTo(fixture.partialReport.copy().hashCode())
    }

    private fun matchConflictFixture(): MatchConflictFixture {
        val hardPass =
            MatchPass<Widget, RemoteWidget, WidgetId, WidgetKey>(
                name = "remote-id",
                localKeys = { record -> record.keys.filterIsInstance<WidgetKey.Remote>().toSet() },
                remoteKeys = { record -> record.keys.filterIsInstance<WidgetKey.Remote>().toSet() },
            )
        val naturalPass =
            hardPass.copy(
                name = "sku",
                localKeys = { record -> record.keys.filterIsInstance<WidgetKey.Sku>().toSet() },
                remoteKeys = { record -> record.keys.filterIsInstance<WidgetKey.Sku>().toSet() },
                confidence = MatchConfidence.NATURAL_KEY,
            )
        val rememberedPass = hardPass.copy(name = "remembered", confidence = MatchConfidence.REMEMBERED_REMOTE_ID)
        val plan = MatchPlan(passes = listOf(hardPass, rememberedPass, naturalPass))
        val local = localRecord()
        val remote = remoteRecord()
        val candidate =
            MatchCandidate(
                pass = naturalPass,
                local = local,
                remote = remote,
                key = WidgetKey.Sku("SKU-1"),
            )
        val conflict =
            SyncConflict(
                subject = SyncSubject.Pair(local.localId, remoteId),
                kind = SyncConflictKind.VERSION_CONFLICT,
                local = local,
                remote = remote,
                message = "version differs",
            )

        return MatchConflictFixture(
            hardPass = hardPass,
            naturalPass = naturalPass,
            rememberedPass = rememberedPass,
            plan = plan,
            local = local,
            remote = remote,
            candidate = candidate,
            conflict = conflict,
        )
    }

    private fun assertMatchEnums() {
        assertThat(MatchConfidence.entries).containsExactly(
            MatchConfidence.HARD,
            MatchConfidence.REMEMBERED_REMOTE_ID,
            MatchConfidence.NATURAL_KEY,
        )
        assertThat(SyncConflictKind.entries).containsExactly(
            SyncConflictKind.DUPLICATE_LOCAL_REMOTE_ID,
            SyncConflictKind.DUPLICATE_REMOTE_ID,
            SyncConflictKind.AMBIGUOUS_MATCH,
            SyncConflictKind.LINK_MISMATCH,
            SyncConflictKind.VERSION_CONFLICT,
            SyncConflictKind.UNSUPPORTED_MERGE,
        )
    }

    private fun assertMatchPasses(fixture: MatchConflictFixture) {
        assertThat(fixture.hardPass.confidence).isEqualTo(MatchConfidence.HARD)
        assertThat(fixture.hardPass.localKeys(fixture.local)).containsExactly(WidgetKey.Remote(remoteId))
        assertThat(fixture.hardPass.remoteKeys(fixture.remote)).containsExactly(WidgetKey.Remote(remoteId))
        assertThat(fixture.naturalPass.localKeys(fixture.local)).containsExactly(WidgetKey.Sku("SKU-1"))
        assertThat(fixture.naturalPass.remoteKeys(fixture.remote)).containsExactly(WidgetKey.Sku("SKU-1"))
    }

    private fun assertMatchGeneratedMembers(fixture: MatchConflictFixture) {
        assertThat(fixture.plan.component1())
            .containsExactly(fixture.hardPass, fixture.rememberedPass, fixture.naturalPass)
        assertThat(fixture.plan.copy().hashCode()).isEqualTo(fixture.plan.hashCode())
        assertThat(fixture.plan.toString()).contains("remote-id")
        assertThat(fixture.naturalPass.component1()).isEqualTo("sku")
        assertThat(fixture.naturalPass.component4()).isEqualTo(MatchConfidence.NATURAL_KEY)
        assertThat(fixture.candidate.component1()).isSameAs(fixture.naturalPass)
        assertThat(fixture.candidate.component2()).isEqualTo(fixture.local)
        assertThat(fixture.candidate.component3()).isEqualTo(fixture.remote)
        assertThat(fixture.candidate.component4()).isEqualTo(WidgetKey.Sku("SKU-1"))
        assertThat(fixture.candidate.copy(key = WidgetKey.Sku("SKU-COPY")).key)
            .isEqualTo(WidgetKey.Sku("SKU-COPY"))
        assertThat(fixture.candidate.toString()).contains("SKU-1")
    }

    private fun assertConflictGeneratedMembers(fixture: MatchConflictFixture) {
        assertThat(fixture.conflict.component1()).isEqualTo(SyncSubject.Pair(fixture.local.localId, remoteId))
        assertThat(fixture.conflict.component2()).isEqualTo(SyncConflictKind.VERSION_CONFLICT)
        assertThat(fixture.conflict.component3()).isEqualTo(fixture.local)
        assertThat(fixture.conflict.component4()).isEqualTo(fixture.remote)
        assertThat(fixture.conflict.component5()).isEqualTo("version differs")
        assertThat(fixture.conflict.copy(message = "unsupported merge").kind)
            .isEqualTo(SyncConflictKind.VERSION_CONFLICT)
        assertThat(fixture.conflict.toString()).contains("version differs")
    }

    private fun localRecord(
        widget: Widget = Widget.linked("local-1", remoteId, "SKU-1", "Old"),
    ): LocalRecord<Widget, WidgetId, WidgetKey> = WidgetLocalProjector.project(widget)

    private fun remoteRecord(
        remote: RemoteWidget = RemoteWidget(id = remoteId, sku = "SKU-1", name = "New"),
    ): RemoteRecord<RemoteWidget, WidgetId, WidgetKey> = WidgetRemoteProjector.project(remote)

    private data class DecisionInputs(
        val local: LocalRecord<Widget, WidgetId, WidgetKey>,
        val remote: RemoteRecord<RemoteWidget, WidgetId, WidgetKey>,
        val changes: ChangeSet,
        val effect: SyncEffect.EnqueueEntitySync<WidgetId>,
        val failure: SyncFailure,
        val conflict: SyncConflict<Widget, RemoteWidget, WidgetId, WidgetKey>,
    )

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

    private data class OutcomeReportFixture(
        val context: SyncContext<WidgetScope>,
        val startedAt: Instant,
        val completedAt: Instant,
        val subject: SyncSubject<WidgetId>,
        val failure: SyncFailure,
        val succeeded: SyncOutcome.Succeeded<WidgetId>,
        val skipped: SyncOutcome.Skipped<WidgetId>,
        val failed: SyncOutcome.Failed<WidgetId>,
        val later: RequeueDecision.Later,
        val defaultReport: SyncReport,
        val partialReport: SyncReport,
    )

    private data class OutcomeFixture(
        val failure: SyncFailure,
        val succeeded: SyncOutcome.Succeeded<WidgetId>,
        val skipped: SyncOutcome.Skipped<WidgetId>,
        val failed: SyncOutcome.Failed<WidgetId>,
    )

    private data class MatchConflictFixture(
        val hardPass: MatchPass<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val naturalPass: MatchPass<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val rememberedPass: MatchPass<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val plan: MatchPlan<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val local: LocalRecord<Widget, WidgetId, WidgetKey>,
        val remote: RemoteRecord<RemoteWidget, WidgetId, WidgetKey>,
        val candidate: MatchCandidate<Widget, RemoteWidget, WidgetId, WidgetKey>,
        val conflict: SyncConflict<Widget, RemoteWidget, WidgetId, WidgetKey>,
    )
}
