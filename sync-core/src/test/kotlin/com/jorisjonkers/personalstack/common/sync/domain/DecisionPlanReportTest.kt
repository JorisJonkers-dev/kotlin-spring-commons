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

class DecisionPlanReportTest {
    private val remoteId = WidgetId("w-1")
    private val otherRemoteId = WidgetId("w-2")
    private val changedAt = Instant.parse("2026-06-30T12:00:00Z")

    @Test
    // Structural threshold: this generated-contract test enumerates every decision variant together.
    @Suppress("LongMethod")
    fun `sync actions reasons subjects and decisions expose their default contract`() {
        val local = localRecord()
        val remote = remoteRecord()
        val changes = WidgetDiffer.diff(local.aggregate, remote.record)
        val deleteSignal =
            RemoteDeleteSignal.MissingFromAuthoritativeList(remoteId = remoteId, observedAt = changedAt)
        val effect =
            SyncEffect.EnqueueEntitySync(
                key = "enqueue-w-2",
                syncName = SyncName("widget"),
                externalId = otherRemoteId,
            )
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

        val import = SyncDecision.Import(remote = remote)
        val update = SyncDecision.Update(local = local, remote = remote, changes = changes, effects = listOf(effect))
        val equal = SyncDecision.Equal(local = local, remote = remote)
        val delete = SyncDecision.Delete(local = local, signal = deleteSignal)
        val unlinkRemembered = SyncDecision.Unlink(local = local, unlinkReason = UnlinkReason.Policy("remote absent"))
        val unlinkLocalOnly =
            SyncDecision.Unlink(
                local = localRecord(Widget.neverLinked("local-new", "SKU-NEW", "New")),
                unlinkReason = UnlinkReason.ManualDetach,
            )
        val unlinkUnknown =
            SyncDecision.Unlink(
                local =
                    LocalRecord<Widget, WidgetId, WidgetKey>(
                        aggregate = Widget.neverLinked("local-unknown", "SKU-UNKNOWN", "Unknown"),
                        localId = null,
                        registration =
                            SyncRegistration<WidgetId>(
                                remoteId = null,
                                rememberedRemoteId = null,
                                lifecycle = SyncRegistrationLifecycle.NEVER_LINKED,
                                changedAt = null,
                                version = null,
                            ),
                        keys = emptySet<WidgetKey>(),
                    ),
                unlinkReason = UnlinkReason.ReplacedByRelink,
            )
        val restore =
            SyncDecision.Restore(
                local = localRecord(Widget.remotelyDeleted("local-1", remoteId, "SKU-1", "Old")),
                remote = remote,
                changes = changes,
            )
        val relink =
            SyncDecision.Relink(
                local = localRecord(Widget.linked("local-1", otherRemoteId, "SKU-1", "Old")),
                remote = remote,
                changes = changes,
            )
        val ignore =
            SyncDecision.Ignore<WidgetId>(
                subject = SyncSubject.Remote(remoteId),
                reason = SyncReason.NotImportable,
            )
        val conflictDecision = SyncDecision.Conflict(conflict = conflict)
        val retry =
            SyncDecision.Retry(
                subject = SyncSubject.Remote(remoteId),
                delay = Duration.ofSeconds(3),
                failure = failure,
            )

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
        assertThat(
            listOf(import, update, equal, delete, unlinkRemembered, restore, relink, ignore, conflictDecision, retry)
                .map { it.action },
        ).containsExactly(
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
        assertThat(import.subject).isEqualTo(SyncSubject.Remote(remoteId))
        assertThat(import.reason).isEqualTo(SyncReason.RemoteOnly)
        assertThat(update.subject).isEqualTo(SyncSubject.Pair(local.localId, remoteId))
        assertThat(update.reason).isEqualTo(SyncReason.RemoteChanged)
        assertThat(update.effects).containsExactly(effect)
        assertThat(equal.executable).isFalse()
        assertThat(equal.reason).isEqualTo(SyncReason.AlreadyEqual)
        assertThat(delete.subject).isEqualTo(SyncSubject.Pair(local.localId, remoteId))
        assertThat(delete.reason).isEqualTo(SyncReason.RemoteDeleted)
        assertThat(unlinkRemembered.subject).isEqualTo(SyncSubject.Pair(local.localId, remoteId))
        assertThat(unlinkLocalOnly.subject).isEqualTo(SyncSubject.Local(LocalId("local-new")))
        assertThat(unlinkUnknown.subject).isEqualTo(SyncSubject.Unknown)
        assertThat(unlinkRemembered.reason).isEqualTo(SyncReason.LocalOnlyUnlinked)
        assertThat(restore.reason).isEqualTo(SyncReason.RestoreLinkedRecord)
        assertThat(relink.reason).isEqualTo(SyncReason.RelinkByNaturalKey)
        assertThat(ignore.action).isEqualTo(SyncAction.IGNORE)
        assertThat(ignore.executable).isFalse()
        assertThat(conflictDecision.subject).isEqualTo(conflict.subject)
        assertThat(conflictDecision.reason).isEqualTo(SyncReason.Conflict(conflict))
        assertThat(retry.reason).isEqualTo(SyncReason.RetryLater(failure))
        assertThat(retry.executable).isFalse()

        assertThat(SyncReason.Policy("manual policy").message).isEqualTo("manual policy")
        assertThat(SyncReason.Conflict(conflict).conflict).isSameAs(conflict)
        assertThat(SyncReason.RetryLater(failure).failure).isSameAs(failure)
        assertThat(SyncReason.RemoteDeleted.toString()).isEqualTo("RemoteDeleted")
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
        val conflictDecision = SyncDecision.Conflict(conflict)
        val quietPlan =
            SyncPlan<Widget, RemoteWidget, WidgetId>(
                context = context,
                decisions =
                    listOf(
                        SyncDecision.Equal(local = local, remote = remote),
                        SyncDecision.Ignore(subject = SyncSubject.Remote(remoteId), reason = SyncReason.NotImportable),
                    ),
            )
        val conflictedPlan =
            quietPlan.copy(
                decisions =
                    listOf(
                        SyncDecision.Update(local = local, remote = remote, changes = ChangeSet()),
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
            SyncDecision.Update(local = local, remote = remote, changes = ChangeSet()),
            conflictDecision,
        )
        assertThat(conflictedPlan.toString()).contains("decisions")
        assertThat(conflictedPlan.hashCode()).isEqualTo(conflictedPlan.copy().hashCode())
    }

    @Test
    // Structural threshold: status/report generated-member coverage is intentionally table-like.
    @Suppress("LongMethod")
    fun `sync outcomes reports and requeue decisions cover statuses actions defaults and generated members`() {
        val context = SyncFixtures.context(scope = WidgetScope("default"))
        val startedAt = context.startedAt
        val completedAt = startedAt.plusSeconds(5)
        val subject = SyncSubject.Remote(remoteId)
        val failure =
            SyncFailure(
                kind = SyncFailureKind.REMOTE_RATE_LIMITED,
                message = "rate limited",
                retryable = true,
                retryAfter = Duration.ofMinutes(1),
            )
        val succeeded =
            SyncOutcome.Succeeded(
                subject = subject,
                action = SyncAction.IMPORT,
                duration = Duration.ofMillis(10),
            )
        val skipped =
            SyncOutcome.Skipped(
                subject = subject,
                action = null,
                duration = Duration.ZERO,
                reason = SyncReason.Policy("dry run"),
            )
        val failed =
            SyncOutcome.Failed(
                subject = subject,
                action = SyncAction.RETRY,
                duration = Duration.ofMillis(20),
                failure = failure,
            )
        val later = RequeueDecision.Later(delay = Duration.ofMinutes(1), reason = "retry page")
        val defaultReport =
            SyncReport(
                context = context,
                status = SyncReportStatus.SUCCEEDED,
                startedAt = startedAt,
                completedAt = completedAt,
                outcomes = listOf(succeeded),
            )
        val partialReport =
            defaultReport.copy(
                status = SyncReportStatus.PARTIAL,
                outcomes = listOf(succeeded, skipped, failed),
                requeue = later,
            )

        assertThat(SyncReportStatus.entries).containsExactly(
            SyncReportStatus.SUCCEEDED,
            SyncReportStatus.PARTIAL,
            SyncReportStatus.FAILED,
            SyncReportStatus.CONFLICTED,
            SyncReportStatus.SKIPPED,
        )
        assertThat(defaultReport.requeue).isEqualTo(RequeueDecision.Done)
        assertThat(RequeueDecision.Done.toString()).isEqualTo("Done")
        assertThat(later.component1()).isEqualTo(Duration.ofMinutes(1))
        assertThat(later.component2()).isEqualTo("retry page")
        assertThat(later.copy(reason = "retry entity")).isEqualTo(
            RequeueDecision.Later(Duration.ofMinutes(1), "retry entity"),
        )
        assertThat(listOf(succeeded, skipped, failed).map { it.action })
            .containsExactly(SyncAction.IMPORT, null, SyncAction.RETRY)
        assertThat(succeeded.component1()).isEqualTo(subject)
        assertThat(succeeded.component2()).isEqualTo(SyncAction.IMPORT)
        assertThat(succeeded.component3()).isEqualTo(Duration.ofMillis(10))
        assertThat(skipped.component4()).isEqualTo(SyncReason.Policy("dry run"))
        assertThat(failed.component4()).isEqualTo(failure)
        assertThat(partialReport.component1()).isEqualTo(context)
        assertThat(partialReport.component2()).isEqualTo(SyncReportStatus.PARTIAL)
        assertThat(partialReport.component3()).isEqualTo(startedAt)
        assertThat(partialReport.component4()).isEqualTo(completedAt)
        assertThat(partialReport.component5()).containsExactly(succeeded, skipped, failed)
        assertThat(partialReport.component6()).isEqualTo(later)
        assertThat(partialReport.toString()).contains("PARTIAL")
        assertThat(partialReport.hashCode()).isEqualTo(partialReport.copy().hashCode())
    }

    @Test
    // Structural threshold: match/conflict enum coverage is one generated-contract matrix.
    @Suppress("LongMethod")
    fun `match plan candidates conflicts and enums expose all fields and generated members`() {
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
        assertThat(hardPass.confidence).isEqualTo(MatchConfidence.HARD)
        assertThat(hardPass.localKeys(local)).containsExactly(WidgetKey.Remote(remoteId))
        assertThat(hardPass.remoteKeys(remote)).containsExactly(WidgetKey.Remote(remoteId))
        assertThat(naturalPass.localKeys(local)).containsExactly(WidgetKey.Sku("SKU-1"))
        assertThat(naturalPass.remoteKeys(remote)).containsExactly(WidgetKey.Sku("SKU-1"))
        assertThat(plan.component1()).containsExactly(hardPass, rememberedPass, naturalPass)
        assertThat(plan.copy().hashCode()).isEqualTo(plan.hashCode())
        assertThat(plan.toString()).contains("remote-id")
        assertThat(naturalPass.component1()).isEqualTo("sku")
        assertThat(naturalPass.component4()).isEqualTo(MatchConfidence.NATURAL_KEY)
        assertThat(candidate.component1()).isSameAs(naturalPass)
        assertThat(candidate.component2()).isEqualTo(local)
        assertThat(candidate.component3()).isEqualTo(remote)
        assertThat(candidate.component4()).isEqualTo(WidgetKey.Sku("SKU-1"))
        assertThat(candidate.copy(key = WidgetKey.Sku("SKU-COPY")).key).isEqualTo(WidgetKey.Sku("SKU-COPY"))
        assertThat(candidate.toString()).contains("SKU-1")
        assertThat(conflict.component1()).isEqualTo(SyncSubject.Pair(local.localId, remoteId))
        assertThat(conflict.component2()).isEqualTo(SyncConflictKind.VERSION_CONFLICT)
        assertThat(conflict.component3()).isEqualTo(local)
        assertThat(conflict.component4()).isEqualTo(remote)
        assertThat(conflict.component5()).isEqualTo("version differs")
        assertThat(conflict.copy(message = "unsupported merge").kind).isEqualTo(SyncConflictKind.VERSION_CONFLICT)
        assertThat(conflict.toString()).contains("version differs")
    }

    private fun localRecord(
        widget: Widget = Widget.linked("local-1", remoteId, "SKU-1", "Old"),
    ): LocalRecord<Widget, WidgetId, WidgetKey> = WidgetLocalProjector.project(widget)

    private fun remoteRecord(
        remote: RemoteWidget = RemoteWidget(id = remoteId, sku = "SKU-1", name = "New"),
    ): RemoteRecord<RemoteWidget, WidgetId, WidgetKey> = WidgetRemoteProjector.project(remote)
}
