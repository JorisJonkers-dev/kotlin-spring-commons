package com.jorisjonkers.personalstack.common.sync.domain

import com.jorisjonkers.personalstack.common.sync.testsupport.RemoteWidget
import com.jorisjonkers.personalstack.common.sync.testsupport.SyncFixtures
import com.jorisjonkers.personalstack.common.sync.testsupport.Widget
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetHarness
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetId
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetKey
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetLocalProjector
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetRemoteProjector
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

// Structural threshold: reconciliation scenarios share one fixture-heavy suite for branch coverage.
@Suppress("LargeClass")
class ReconciliationTest {
    private val observedAt: Instant = Instant.parse("2026-06-30T12:00:00Z")

    @Test
    fun `reconcileOne returns equal for unchanged active pair and update when differ reports changes`() {
        val reconciliation = reconciliation()

        val equal =
            reconciliation.reconcileOne(
                local = Widget.linked("local-equal", id("w-equal"), "SKU-EQUAL", "same"),
                remote = remote("w-equal", "SKU-EQUAL", "same"),
                observedAt = observedAt,
            )
        val update =
            reconciliation.reconcileOne(
                local = Widget.linked("local-update", id("w-update"), "SKU-UPDATE", "old"),
                remote = remote("w-update", "SKU-UPDATE", "new"),
                observedAt = observedAt,
            )

        assertThat(equal.action).isEqualTo(SyncAction.EQUAL)
        assertThat(equal.reason).isEqualTo(SyncReason.AlreadyEqual)
        assertThat(equal.executable).isFalse()
        exerciseGeneratedMembers(equal)

        assertThat(update.action).isEqualTo(SyncAction.UPDATE)
        assertThat(update.reason).isEqualTo(SyncReason.RemoteChanged)
        assertThat(update.executable).isTrue()
        assertThat(
            (update as SyncDecision.Update<Widget, RemoteWidget, WidgetId, WidgetKey>).changes.fields.map {
                it.path.value
            },
        ).containsExactly("name")
        exerciseGeneratedMembers(update)
    }

    @Test
    // Structural threshold: tombstone and already-deleted assertions must stay in one scenario.
    @Suppress("LongMethod")
    fun `reconcileOne deletes on remote tombstone and ignores tombstones already recorded locally`() {
        val reconciliation = reconciliation()
        val version = VersionStamp.Token("delete-version")
        val remoteObservedAt = Instant.parse("2026-06-30T10:15:30Z")

        val delete =
            reconciliation.reconcileOne(
                local = Widget.linked("local-delete", id("w-delete"), "SKU-DELETE", "old"),
                remote =
                    remote(
                        id = "w-delete",
                        sku = "SKU-DELETE",
                        name = "old",
                        lifecycle = RemoteRecordLifecycle.DELETED,
                        version = version,
                        observedAt = remoteObservedAt,
                    ),
                observedAt = observedAt,
            )
        val deleteWithFallbackObservedAt =
            reconciliation.reconcileOne(
                local = Widget.linked("local-delete-fallback", id("w-delete-fallback"), "SKU-DELETE-FALLBACK", "old"),
                remote =
                    remote(
                        id = "w-delete-fallback",
                        sku = "SKU-DELETE-FALLBACK",
                        name = "old",
                        lifecycle = RemoteRecordLifecycle.DELETED,
                    ),
                observedAt = observedAt,
            )
        val alreadyDeleted =
            reconciliation.reconcileOne(
                local = Widget.remotelyDeleted("local-already-deleted", id("w-already-deleted"), "SKU-DELETED", "old"),
                remote =
                    remote(
                        id = "w-already-deleted",
                        sku = "SKU-DELETED",
                        name = "old",
                        lifecycle = RemoteRecordLifecycle.DELETED,
                    ),
                observedAt = observedAt,
            )

        assertThat(delete.action).isEqualTo(SyncAction.DELETE)
        val tombstone =
            (delete as SyncDecision.Delete<Widget, WidgetId, WidgetKey>).signal
                as RemoteDeleteSignal.Tombstone<WidgetId>
        assertThat(tombstone.remoteId).isEqualTo(id("w-delete"))
        assertThat(tombstone.observedAt).isEqualTo(remoteObservedAt)
        assertThat(tombstone.version).isEqualTo(version)
        exerciseGeneratedMembers(delete)

        assertThat((deleteWithFallbackObservedAt as SyncDecision.Delete<Widget, WidgetId, WidgetKey>).signal)
            .isEqualTo(
                RemoteDeleteSignal.Tombstone(
                    remoteId = id("w-delete-fallback"),
                    observedAt = observedAt,
                    version = null,
                ),
            )

        assertThat(alreadyDeleted.action).isEqualTo(SyncAction.IGNORE)
        assertThat(alreadyDeleted.reason).isEqualTo(SyncReason.RemoteDeleted)
        exerciseGeneratedMembers(alreadyDeleted)
    }

    @Test
    fun `reconcileOne imports active remote-only records and ignores non-importable remote-only records`() {
        val policyReconciliation =
            reconciliation(
                policies =
                    policies(
                        importPolicy = ImportPolicy { candidate -> candidate.sku != "SKU-BLOCKED-BY-POLICY" },
                    ),
            )

        val import = policyReconciliation.reconcileOne(null, remote("w-import", "SKU-IMPORT", "new"), observedAt)
        val deletedRemote =
            policyReconciliation.reconcileOne(
                null,
                remote("w-deleted-only", "SKU-DELETED-ONLY", "gone", lifecycle = RemoteRecordLifecycle.DELETED),
                observedAt,
            )
        val notImportableFlag =
            policyReconciliation.reconcileOne(
                null,
                remote("w-not-importable", "SKU-NOT-IMPORTABLE", "blocked", importable = false),
                observedAt,
            )
        val notImportablePolicy =
            policyReconciliation.reconcileOne(
                null,
                remote("w-policy-blocked", "SKU-BLOCKED-BY-POLICY", "blocked"),
                observedAt,
            )

        assertThat(import.action).isEqualTo(SyncAction.IMPORT)
        assertThat(import.reason).isEqualTo(SyncReason.RemoteOnly)
        assertThat(import.executable).isTrue()
        exerciseGeneratedMembers(import)

        assertThat(deletedRemote.action).isEqualTo(SyncAction.IGNORE)
        assertThat(deletedRemote.reason).isEqualTo(SyncReason.RemoteDeleted)
        exerciseGeneratedMembers(deletedRemote)

        assertThat(notImportableFlag.action).isEqualTo(SyncAction.IGNORE)
        assertThat(notImportableFlag.reason).isEqualTo(SyncReason.NotImportable)
        exerciseGeneratedMembers(notImportableFlag)

        assertThat(notImportablePolicy.action).isEqualTo(SyncAction.IGNORE)
        assertThat(notImportablePolicy.reason).isEqualTo(SyncReason.NotImportable)
        exerciseGeneratedMembers(notImportablePolicy)
    }

    @Test
    fun `reconcileOne delegates internal-only absence to delete unlink and conflict policies`() {
        val delete =
            reconciliation(policies = policies(missingRemotePolicy = WidgetHarness.DELETE_MISSING))
                .reconcileOne(
                    Widget.linked("local-missing-delete", id("w-missing-delete"), "SKU-MISSING-DELETE", "old"),
                    null,
                    observedAt,
                )
        val unlink =
            reconciliation(policies = policies(missingRemotePolicy = WidgetHarness.UNLINK_MISSING))
                .reconcileOne(
                    Widget.linked("local-missing-unlink", id("w-missing-unlink"), "SKU-MISSING-UNLINK", "old"),
                    null,
                    observedAt,
                )
        val conflict =
            reconciliation(policies = policies(missingRemotePolicy = conflictMissingRemotePolicy()))
                .reconcileOne(
                    Widget.linked("local-missing-conflict", id("w-missing-conflict"), "SKU-MISSING-CONFLICT", "old"),
                    null,
                    observedAt,
                )
        val deletePolicyUnlinksWhenThereIsNoRememberedId =
            reconciliation(policies = policies(missingRemotePolicy = WidgetHarness.DELETE_MISSING))
                .reconcileOne(
                    Widget.neverLinked("local-never-linked-missing", "SKU-NEVER-MISSING", "orphan"),
                    null,
                    observedAt,
                )

        assertThat(delete.action).isEqualTo(SyncAction.DELETE)
        assertThat((delete as SyncDecision.Delete<Widget, WidgetId, WidgetKey>).signal)
            .isEqualTo(
                RemoteDeleteSignal.MissingFromAuthoritativeList(
                    remoteId = id("w-missing-delete"),
                    observedAt = observedAt,
                ),
            )
        exerciseGeneratedMembers(delete)

        assertThat(unlink.action).isEqualTo(SyncAction.UNLINK)
        assertThat(
            (unlink as SyncDecision.Unlink<Widget, WidgetId, WidgetKey>).unlinkReason,
        ).isEqualTo(UnlinkReason.Policy("absent on remote"))
        exerciseGeneratedMembers(unlink)

        assertThat(conflict.action).isEqualTo(SyncAction.CONFLICT)
        assertThat(
            (conflict as SyncDecision.Conflict<WidgetId>).conflict.kind,
        ).isEqualTo(SyncConflictKind.LINK_MISMATCH)
        exerciseGeneratedMembers(conflict)

        assertThat(deletePolicyUnlinksWhenThereIsNoRememberedId.action).isEqualTo(SyncAction.UNLINK)
        assertThat(
            (deletePolicyUnlinksWhenThereIsNoRememberedId as SyncDecision.Unlink<Widget, WidgetId, WidgetKey>)
                .unlinkReason,
        ).isEqualTo(UnlinkReason.ManualDetach)
    }

    @Test
    fun `reconcileOne restores remotely deleted locals and relinks locals without the active remote id`() {
        val reconciliation = reconciliation()

        val restore =
            reconciliation.reconcileOne(
                Widget.remotelyDeleted("local-restore", id("w-restore"), "SKU-RESTORE", "old"),
                remote("w-restore", "SKU-RESTORE", "new"),
                observedAt,
            )
        val relinkNeverLinked =
            reconciliation.reconcileOne(
                Widget.neverLinked("local-relink-never", "SKU-RELINK-NEVER", "local"),
                remote("w-relink-never", "SKU-RELINK-NEVER", "remote"),
                observedAt,
            )
        val relinkDifferentActiveId =
            reconciliation.reconcileOne(
                Widget.linked("local-relink-different", id("w-old"), "SKU-RELINK-DIFFERENT", "local"),
                remote("w-new", "SKU-RELINK-DIFFERENT", "remote"),
                observedAt,
            )

        assertThat(restore.action).isEqualTo(SyncAction.RESTORE)
        assertThat(
            (restore as SyncDecision.Restore<Widget, RemoteWidget, WidgetId, WidgetKey>).changes.fields,
        ).hasSize(1)
        exerciseGeneratedMembers(restore)

        assertThat(relinkNeverLinked.action).isEqualTo(SyncAction.RELINK)
        assertThat(
            (relinkNeverLinked as SyncDecision.Relink<Widget, RemoteWidget, WidgetId, WidgetKey>).remote.externalId,
        ).isEqualTo(id("w-relink-never"))
        exerciseGeneratedMembers(relinkNeverLinked)

        assertThat(relinkDifferentActiveId.action).isEqualTo(SyncAction.RELINK)
        assertThat(
            (relinkDifferentActiveId as SyncDecision.Relink<Widget, RemoteWidget, WidgetId, WidgetKey>)
                .remote.externalId,
        ).isEqualTo(id("w-new"))
        exerciseGeneratedMembers(relinkDifferentActiveId)
    }

    @Test
    fun `reconcileOne ignores both-null input`() {
        val decision = reconciliation().reconcileOne(null, null, observedAt)

        assertThat(decision.action).isEqualTo(SyncAction.IGNORE)
        assertThat(decision.subject).isEqualTo(SyncSubject.Unknown)
        assertThat(decision.reason).isEqualTo(SyncReason.Policy("nothing to reconcile"))
        assertThat(decision.executable).isFalse()
        exerciseGeneratedMembers(decision)
    }

    @Test
    fun `reconcileMany matches by hard id remembered id and natural key in later passes`() {
        val reconciliation =
            reconciliation(
                matchPlan = MatchPlan(listOf(hardRemoteIdPass(), rememberedRemoteIdPass(), skuPass())),
            )
        val localHard = Widget.linked("local-hard", id("w-hard"), "SKU-HARD", "same")
        val localRemembered = Widget.remotelyDeleted("local-remembered", id("w-remembered"), "SKU-REMEMBERED", "old")
        val localNatural = Widget.neverLinked("local-natural", "SKU-NATURAL", "old")

        val plan =
            reconciliation.reconcileMany(
                locals = listOf(localNatural, localRemembered, localHard),
                remotes =
                    listOf(
                        remote("w-natural", "SKU-NATURAL", "new natural"),
                        remote("w-hard", "SKU-HARD", "same"),
                        remote("w-remembered", "SKU-OTHER", "new remembered"),
                    ),
                context = context(),
            )

        assertThat(plan.decisions.map { it.action })
            .containsExactly(SyncAction.RESTORE, SyncAction.RELINK, SyncAction.EQUAL)
        assertThat((plan.decisions[0] as SyncDecision.Restore<Widget, RemoteWidget, WidgetId, WidgetKey>).subject)
            .isEqualTo(SyncFixtures.pairSubject("local-remembered", id("w-remembered")))
        assertThat((plan.decisions[1] as SyncDecision.Relink<Widget, RemoteWidget, WidgetId, WidgetKey>).subject)
            .isEqualTo(SyncFixtures.pairSubject("local-natural", id("w-natural")))
        assertThat((plan.decisions[2] as SyncDecision.Equal<Widget, RemoteWidget, WidgetId, WidgetKey>).subject)
            .isEqualTo(SyncFixtures.pairSubject("local-hard", id("w-hard")))
        assertThat(plan.executable).isTrue()
        assertThat(plan.substantive).isTrue()
    }

    @Test
    fun `reconcileMany consumes hard-id matches before natural-key passes`() {
        val reconciliation =
            reconciliation(
                matchPlan = MatchPlan(listOf(hardRemoteIdPass(), skuPass())),
            )

        val plan =
            reconciliation.reconcileMany(
                locals = listOf(Widget.linked("local-consumed", id("w-consumed"), "SKU-SHARED", "same")),
                remotes =
                    listOf(
                        remote("w-consumed", "SKU-SHARED", "same"),
                        remote("w-import-after-consumption", "SKU-SHARED", "import"),
                    ),
                context = context(),
            )

        assertThat(plan.decisions.map { it.action }).containsExactly(SyncAction.IMPORT, SyncAction.EQUAL)
        assertThat((plan.decisions[0] as SyncDecision.Import<RemoteWidget, WidgetId, WidgetKey>).remote.externalId)
            .isEqualTo(id("w-import-after-consumption"))
        assertThat(plan.conflicts).isEmpty()
    }

    @Test
    fun `reconcileMany scopes duplicate local hard ids to offenders while unrelated decisions remain executable`() {
        val reconciliation =
            reconciliation(
                matchPlan = MatchPlan(listOf(hardRemoteIdPass(), skuPass())),
            )

        val plan =
            reconciliation.reconcileMany(
                locals =
                    listOf(
                        Widget.linked("local-dup-a", id("w-duplicate"), "SKU-DUP-A", "old"),
                        Widget.linked("local-dup-b", id("w-duplicate"), "SKU-DUP-B", "old"),
                        Widget.linked(
                            "local-update-unrelated",
                            id("w-update-unrelated"),
                            "SKU-UPDATE-UNRELATED",
                            "old",
                        ),
                    ),
                remotes =
                    listOf(
                        remote("w-import-unrelated", "SKU-IMPORT-UNRELATED", "import"),
                        remote("w-update-unrelated", "SKU-UPDATE-UNRELATED", "new"),
                    ),
                context = context(),
            )

        assertThat(plan.decisions.map { it.action })
            .containsExactly(SyncAction.IMPORT, SyncAction.UPDATE, SyncAction.CONFLICT, SyncAction.CONFLICT)
        assertThat(plan.decisions.take(2).map { it.executable }).containsOnly(true)
        assertThat(plan.conflicts).hasSize(2)
        assertThat(plan.conflicts.map { it.conflict.kind })
            .containsOnly(SyncConflictKind.DUPLICATE_LOCAL_REMOTE_ID)
        assertThat(plan.conflicts.map { it.subject.stableKey })
            .containsExactly("local:local-dup-a", "local:local-dup-b")
        assertThat(plan.executable).isFalse()
    }

    @Test
    fun `reconcileMany scopes duplicate remote ids and ambiguous natural-key candidates to offenders`() {
        val reconciliation =
            reconciliation(
                matchPlan = MatchPlan(listOf(hardRemoteIdPass(), skuPass())),
            )

        val plan =
            reconciliation.reconcileMany(
                locals =
                    listOf(
                        Widget.linked("local-remote-dup", id("w-remote-dup"), "SKU-REMOTE-DUP", "old"),
                        Widget.neverLinked("local-ambiguous", "SKU-AMBIGUOUS", "old"),
                        Widget.linked("local-safe-update", id("w-safe-update"), "SKU-SAFE-UPDATE", "old"),
                    ),
                remotes =
                    listOf(
                        remote("w-remote-dup", "SKU-REMOTE-DUP-A", "one"),
                        remote("w-remote-dup", "SKU-REMOTE-DUP-B", "two"),
                        remote("w-natural-one", "SKU-AMBIGUOUS", "one"),
                        remote("w-natural-two", "SKU-AMBIGUOUS", "two"),
                        remote("w-safe-update", "SKU-SAFE-UPDATE", "new"),
                        remote("w-safe-import", "SKU-SAFE-IMPORT", "import"),
                    ),
                context = context(),
            )

        assertThat(plan.decisions.map { it.action })
            .containsExactly(SyncAction.IMPORT, SyncAction.UPDATE, SyncAction.CONFLICT, SyncAction.CONFLICT)
        assertThat(plan.conflicts.map { it.conflict.kind })
            .containsExactly(SyncConflictKind.AMBIGUOUS_MATCH, SyncConflictKind.DUPLICATE_REMOTE_ID)
        assertThat(plan.conflicts.map { it.subject.stableKey })
            .containsExactly(
                "pair:local-ambiguous:WidgetId(value=w-natural-one)",
                "pair:local-remote-dup:WidgetId(value=w-remote-dup)",
            )
        assertThat(plan.decisions.first().executable).isTrue()
        assertThat(plan.decisions[1].executable).isTrue()
        assertThat(plan.conflicts.map { it.executable }).containsOnly(false)
    }

    @Test
    fun `reconcileMany orders every decision action deterministically`() {
        val reconciliation =
            reconciliation(
                policies = policies(missingRemotePolicy = actionRankingMissingRemotePolicy()),
            )

        val plan =
            reconciliation.reconcileMany(
                locals =
                    listOf(
                        Widget.linked("local-update", id("w-update"), "SKU-UPDATE", "old"),
                        Widget.linked("local-delete", id("w-delete"), "SKU-DELETE", "old"),
                        Widget.neverLinked("local-relink", "SKU-RELINK", "old"),
                        Widget.linked("local-equal", id("w-equal"), "SKU-EQUAL", "same"),
                        Widget.remotelyDeleted("local-restore", id("w-restore"), "SKU-RESTORE", "old"),
                        Widget.neverLinked("local-unlink", "SKU-MISSING-UNLINK", "missing"),
                        Widget.neverLinked("local-retry", "SKU-MISSING-RETRY", "missing"),
                        Widget.neverLinked("local-conflict", "SKU-MISSING-CONFLICT", "missing"),
                    ),
                remotes =
                    listOf(
                        remote("w-ignore", "SKU-IGNORE", "gone", lifecycle = RemoteRecordLifecycle.DELETED),
                        remote("w-import", "SKU-IMPORT", "import"),
                        remote("w-update", "SKU-UPDATE", "new"),
                        remote("w-delete", "SKU-DELETE", "old", lifecycle = RemoteRecordLifecycle.DELETED),
                        remote("w-relink", "SKU-RELINK", "new"),
                        remote("w-equal", "SKU-EQUAL", "same"),
                        remote("w-restore", "SKU-RESTORE", "restored"),
                    ),
                context = context(),
            )

        assertThat(plan.decisions.map { it.action })
            .containsExactly(
                SyncAction.IMPORT,
                SyncAction.RESTORE,
                SyncAction.RELINK,
                SyncAction.UPDATE,
                SyncAction.EQUAL,
                SyncAction.IGNORE,
                SyncAction.DELETE,
                SyncAction.UNLINK,
                SyncAction.RETRY,
                SyncAction.CONFLICT,
            )
        assertThat(plan.decisions.map { it.subject.stableKey })
            .containsExactly(
                "remote:WidgetId(value=w-import)",
                "pair:local-restore:WidgetId(value=w-restore)",
                "pair:local-relink:WidgetId(value=w-relink)",
                "pair:local-update:WidgetId(value=w-update)",
                "pair:local-equal:WidgetId(value=w-equal)",
                "remote:WidgetId(value=w-ignore)",
                "pair:local-delete:WidgetId(value=w-delete)",
                "local:local-unlink",
                "local:local-retry",
                "local:local-conflict",
            )
        plan.decisions.forEach(::exerciseGeneratedMembers)
    }

    @Test
    fun `reconcileMany handles empty sides through leftover import and missing policy paths`() {
        val importOnlyPlan =
            reconciliation()
                .reconcileMany(
                    locals = emptyList(),
                    remotes = listOf(remote("w-only-remote", "SKU-ONLY-REMOTE", "remote")),
                    context = context(),
                )
        val missingOnlyPlan =
            reconciliation(policies = policies(missingRemotePolicy = WidgetHarness.UNLINK_MISSING))
                .reconcileMany(
                    locals = listOf(Widget.linked("local-only", id("w-only-local"), "SKU-ONLY-LOCAL", "local")),
                    remotes = emptyList(),
                    context = context(),
                )

        assertThat(importOnlyPlan.decisions.map { it.action }).containsExactly(SyncAction.IMPORT)
        assertThat(missingOnlyPlan.decisions.map { it.action }).containsExactly(SyncAction.UNLINK)
    }

    @Test
    fun `reconcileMany duplicate local conflict can fall back to unknown subject when local id is absent`() {
        val localWithoutIdA = Widget.linked("local-no-id-a", id("w-no-id"), "SKU-NO-ID-A", "old").copy(localId = null)
        val localWithoutIdB = Widget.linked("local-no-id-b", id("w-no-id"), "SKU-NO-ID-B", "old").copy(localId = null)

        val plan =
            reconciliation(matchPlan = MatchPlan(listOf(hardRemoteIdPass())))
                .reconcileMany(
                    locals = listOf(localWithoutIdA, localWithoutIdB),
                    remotes = listOf(remote("w-unrelated", "SKU-UNRELATED", "unrelated")),
                    context = context(),
                )

        assertThat(plan.conflicts).hasSize(2)
        assertThat(plan.conflicts.map { it.subject }).containsOnly(SyncSubject.Unknown)
        assertThat(plan.conflicts.map { it.conflict.kind }).containsOnly(SyncConflictKind.DUPLICATE_LOCAL_REMOTE_ID)
    }

    @Test
    fun `soft pass against same active id only relinks when lifecycle is not linked`() {
        val softOnly = reconciliation(matchPlan = MatchPlan(listOf(skuPass())))
        val sameLinked =
            softOnly.reconcileMany(
                locals = listOf(Widget.linked("local-soft-linked", id("w-soft"), "SKU-SOFT", "same")),
                remotes = listOf(remote("w-soft", "SKU-SOFT", "same")),
                context = context(),
            )
        val sameIdButUnlinkedLifecycle =
            softOnly.reconcileMany(
                locals =
                    listOf(
                        activeIdWithUnlinkedLifecycle(
                            "local-soft-unlinked",
                            id("w-soft-unlinked"),
                            "SKU-SOFT-UNLINKED",
                        ),
                    ),
                remotes = listOf(remote("w-soft-unlinked", "SKU-SOFT-UNLINKED", "same")),
                context = context(),
            )

        assertThat(sameLinked.decisions.map { it.action }).containsExactly(SyncAction.EQUAL)
        assertThat(sameIdButUnlinkedLifecycle.decisions.map { it.action }).containsExactly(SyncAction.RELINK)
    }

    @Test
    // Structural threshold: generated members for related policy types are asserted as one matrix.
    @Suppress("LongMethod")
    fun `match plan and policy data classes expose generated members and defaults`() {
        val hard = hardRemoteIdPass()
        val defaultConfidencePass =
            MatchPass<Widget, RemoteWidget, WidgetId, WidgetKey>(
                name = "default-hard",
                localKeys = { emptySet() },
                remoteKeys = { emptySet() },
            )
        val plan = MatchPlan(listOf(hard, defaultConfidencePass))
        val localRecord =
            WidgetLocalProjector.project(
                Widget.linked("local-candidate", id("w-candidate"), "SKU-CANDIDATE", "same"),
            )
        val remoteRecord = WidgetRemoteProjector.project(remote("w-candidate", "SKU-CANDIDATE", "same"))
        val candidate = MatchCandidate(skuPass(), localRecord, remoteRecord, WidgetKey.Sku("SKU-CANDIDATE"))
        val conflict =
            SyncConflict(
                subject = SyncFixtures.pairSubject("local-candidate", id("w-candidate")),
                kind = SyncConflictKind.VERSION_CONFLICT,
                local = localRecord,
                remote = remoteRecord,
                message = "version skew",
            )
        val syncPolicies =
            SyncPolicies<Widget, RemoteWidget, WidgetId, WidgetKey>(missingRemotePolicy = WidgetHarness.UNLINK_MISSING)
        val options = SyncExecutionOptions()

        assertThat(defaultConfidencePass.confidence).isEqualTo(MatchConfidence.HARD)
        assertDataClassGeneratedMembers(plan, plan.copy(), plan.component1())
        assertDataClassGeneratedMembers(
            hard,
            hard.copy(),
            hard.component1(),
            hard.component2(),
            hard.component3(),
            hard.component4(),
        )
        assertDataClassGeneratedMembers(
            candidate,
            candidate.copy(),
            candidate.component1(),
            candidate.component2(),
            candidate.component3(),
            candidate.component4(),
        )
        assertDataClassGeneratedMembers(
            conflict,
            conflict.copy(),
            conflict.component1(),
            conflict.component2(),
            conflict.component3(),
            conflict.component4(),
            conflict.component5(),
        )
        assertDataClassGeneratedMembers(
            syncPolicies,
            syncPolicies.copy(),
            syncPolicies.component1(),
            syncPolicies.component2(),
            syncPolicies.component3(),
        )
        assertThat(syncPolicies.conflictPolicy.decide(conflict).action).isEqualTo(SyncAction.CONFLICT)
        assertThat(syncPolicies.importPolicy.importable(remote("w-default-import", "SKU-DEFAULT", "default"))).isTrue()
        assertDataClassGeneratedMembers(
            options,
            options.copy(),
            options.component1(),
            options.component2(),
            options.component3(),
            options.component4(),
        )
        assertThat(options.listTransactionMode).isEqualTo(ListTransactionMode.PER_RECORD)
        assertThat(options.pageSize).isEqualTo(500)
        assertThat(options.lockTimeout).isEqualTo(Duration.ofSeconds(10))
        assertThat(options.authorityMode).isEqualTo(AuthorityMode.REMOTE_AUTHORITATIVE)

        // A conflict with only a remote side is not constructible through Reconciliation's public API.
    }

    private fun reconciliation(
        matchPlan: MatchPlan<Widget, RemoteWidget, WidgetId, WidgetKey>? = null,
        policies: SyncPolicies<Widget, RemoteWidget, WidgetId, WidgetKey>? = null,
    ): Reconciliation<Widget, RemoteWidget, WidgetId, WidgetKey> {
        val definition = WidgetHarness.build().definition
        return Reconciliation(
            localProjector = definition.localProjector,
            remoteProjector = definition.remoteProjector,
            differ = definition.differ,
            matchPlan = matchPlan ?: definition.matchPlan,
            policies = policies ?: definition.policies,
        )
    }

    private fun policies(
        missingRemotePolicy: MissingRemotePolicy<Widget, WidgetId, WidgetKey> = WidgetHarness.DELETE_MISSING,
        importPolicy: ImportPolicy<RemoteWidget> = ImportPolicy { true },
    ): SyncPolicies<Widget, RemoteWidget, WidgetId, WidgetKey> =
        SyncPolicies(
            conflictPolicy = ConflictPolicy.failClosed(),
            missingRemotePolicy = missingRemotePolicy,
            importPolicy = importPolicy,
        )

    private fun conflictMissingRemotePolicy(): MissingRemotePolicy<Widget, WidgetId, WidgetKey> =
        MissingRemotePolicy { local, _ ->
            SyncDecision.Conflict(
                SyncConflict<Widget, RemoteWidget, WidgetId, WidgetKey>(
                    subject = local.localId?.let { SyncSubject.Local(it) } ?: SyncSubject.Unknown,
                    kind = SyncConflictKind.LINK_MISMATCH,
                    local = local,
                    remote = null,
                    message = "missing remote is unsafe to resolve automatically",
                ),
            )
        }

    private fun actionRankingMissingRemotePolicy(): MissingRemotePolicy<Widget, WidgetId, WidgetKey> =
        MissingRemotePolicy { local, _ ->
            when (local.aggregate.sku) {
                "SKU-MISSING-RETRY" ->
                    SyncDecision.Retry(
                        subject = local.localId?.let { SyncSubject.Local(it) } ?: SyncSubject.Unknown,
                        delay = Duration.ofSeconds(5),
                        failure =
                            SyncFailure(
                                kind = SyncFailureKind.REMOTE_PARTIAL,
                                message = "retry requested by test policy",
                                retryable = true,
                                retryAfter = Duration.ofSeconds(5),
                            ),
                    )
                "SKU-MISSING-CONFLICT" ->
                    SyncDecision.Conflict(
                        SyncConflict<Widget, RemoteWidget, WidgetId, WidgetKey>(
                            subject = local.localId?.let { SyncSubject.Local(it) } ?: SyncSubject.Unknown,
                            kind = SyncConflictKind.VERSION_CONFLICT,
                            local = local,
                            remote = null,
                            message = "conflict requested by test policy",
                        ),
                    )
                else -> SyncDecision.Unlink(local = local, unlinkReason = UnlinkReason.Policy("missing for order test"))
            }
        }

    private fun hardRemoteIdPass(): MatchPass<Widget, RemoteWidget, WidgetId, WidgetKey> =
        MatchPass(
            name = "hard-remote-id",
            confidence = MatchConfidence.HARD,
            localKeys = { local ->
                local.registration.remoteId
                    ?.let { setOf(WidgetKey.Remote(it)) }
                    ?: emptySet()
            },
            remoteKeys = { remote -> setOf(WidgetKey.Remote(remote.externalId)) },
        )

    private fun rememberedRemoteIdPass(): MatchPass<Widget, RemoteWidget, WidgetId, WidgetKey> =
        MatchPass(
            name = "remembered-remote-id",
            confidence = MatchConfidence.REMEMBERED_REMOTE_ID,
            localKeys = { local ->
                if (local.registration.remoteId == null) {
                    local.registration.rememberedRemoteId
                        ?.let { setOf(WidgetKey.Remote(it)) }
                        ?: emptySet()
                } else {
                    emptySet()
                }
            },
            remoteKeys = { remote -> setOf(WidgetKey.Remote(remote.externalId)) },
        )

    private fun skuPass(): MatchPass<Widget, RemoteWidget, WidgetId, WidgetKey> =
        MatchPass(
            name = "sku",
            confidence = MatchConfidence.NATURAL_KEY,
            localKeys = { local -> local.keys.filterIsInstance<WidgetKey.Sku>().toSet() },
            remoteKeys = { remote -> remote.keys.filterIsInstance<WidgetKey.Sku>().toSet() },
        )

    private fun activeIdWithUnlinkedLifecycle(
        localId: String,
        remoteId: WidgetId,
        sku: String,
    ): Widget =
        Widget
            .linked(localId, remoteId, sku, "same")
            .copy(
                registration =
                    Widget
                        .linked(
                            localId,
                            remoteId,
                            sku,
                            "same",
                        ).registration
                        .copy(lifecycle = SyncRegistrationLifecycle.UNLINKED),
            )

    private fun id(value: String): WidgetId = WidgetId(value)

    private fun remote(
        id: String,
        sku: String,
        name: String,
        lifecycle: RemoteRecordLifecycle = RemoteRecordLifecycle.ACTIVE,
        importable: Boolean = true,
        version: VersionStamp? = null,
        observedAt: Instant? = null,
    ): RemoteWidget =
        RemoteWidget(
            id = WidgetId(id),
            sku = sku,
            name = name,
            lifecycle = lifecycle,
            importable = importable,
            version = version,
            observedAt = observedAt,
        )

    private fun context(): SyncContext<WidgetScope> = SyncFixtures.context(startedAt = observedAt)

    private fun assertDataClassGeneratedMembers(
        original: Any,
        copied: Any,
        vararg components: Any?,
    ) {
        assertThat(copied).isEqualTo(original)
        assertThat(copied.hashCode()).isEqualTo(original.hashCode())
        assertThat(original.toString()).isNotBlank()
        assertThat(components.toList()).isNotEmpty()
    }

    // Structural threshold: one exhaustive when keeps generated-member coverage aligned to variants.
    @Suppress("UNCHECKED_CAST", "LongMethod")
    private fun exerciseGeneratedMembers(decision: SyncDecision<Widget, RemoteWidget, WidgetId>) {
        assertThat(decision).isEqualTo(decision)
        assertThat(decision.hashCode()).isEqualTo(decision.hashCode())
        assertThat(decision.toString()).isNotBlank()
        when (decision) {
            is SyncDecision.Import<*, *, *> -> {
                val typed = decision as SyncDecision.Import<RemoteWidget, WidgetId, WidgetKey>
                assertDataClassGeneratedMembers(
                    typed,
                    typed.copy(),
                    typed.component1(),
                    typed.component2(),
                    typed.component3(),
                    typed.component4(),
                    typed.component5(),
                    typed.component6(),
                )
            }
            is SyncDecision.Update<*, *, *, *> -> {
                val typed = decision as SyncDecision.Update<Widget, RemoteWidget, WidgetId, WidgetKey>
                assertDataClassGeneratedMembers(
                    typed,
                    typed.copy(),
                    typed.component1(),
                    typed.component2(),
                    typed.component3(),
                    typed.component4(),
                    typed.component5(),
                    typed.component6(),
                    typed.component7(),
                    typed.component8(),
                )
            }
            is SyncDecision.Equal<*, *, *, *> -> {
                val typed = decision as SyncDecision.Equal<Widget, RemoteWidget, WidgetId, WidgetKey>
                assertDataClassGeneratedMembers(
                    typed,
                    typed.copy(),
                    typed.component1(),
                    typed.component2(),
                    typed.component3(),
                    typed.component4(),
                    typed.component5(),
                    typed.component6(),
                    typed.component7(),
                )
            }
            is SyncDecision.Delete<*, *, *> -> {
                val typed = decision as SyncDecision.Delete<Widget, WidgetId, WidgetKey>
                assertDataClassGeneratedMembers(
                    typed,
                    typed.copy(),
                    typed.component1(),
                    typed.component2(),
                    typed.component3(),
                    typed.component4(),
                    typed.component5(),
                    typed.component6(),
                    typed.component7(),
                )
            }
            is SyncDecision.Unlink<*, *, *> -> {
                val typed = decision as SyncDecision.Unlink<Widget, WidgetId, WidgetKey>
                assertDataClassGeneratedMembers(
                    typed,
                    typed.copy(),
                    typed.component1(),
                    typed.component2(),
                    typed.component3(),
                    typed.component4(),
                    typed.component5(),
                    typed.component6(),
                    typed.component7(),
                )
            }
            is SyncDecision.Restore<*, *, *, *> -> {
                val typed = decision as SyncDecision.Restore<Widget, RemoteWidget, WidgetId, WidgetKey>
                assertDataClassGeneratedMembers(
                    typed,
                    typed.copy(),
                    typed.component1(),
                    typed.component2(),
                    typed.component3(),
                    typed.component4(),
                    typed.component5(),
                    typed.component6(),
                    typed.component7(),
                    typed.component8(),
                )
            }
            is SyncDecision.Relink<*, *, *, *> -> {
                val typed = decision as SyncDecision.Relink<Widget, RemoteWidget, WidgetId, WidgetKey>
                assertDataClassGeneratedMembers(
                    typed,
                    typed.copy(),
                    typed.component1(),
                    typed.component2(),
                    typed.component3(),
                    typed.component4(),
                    typed.component5(),
                    typed.component6(),
                    typed.component7(),
                    typed.component8(),
                )
            }
            is SyncDecision.Ignore<*> -> {
                val typed = decision as SyncDecision.Ignore<WidgetId>
                assertDataClassGeneratedMembers(
                    typed,
                    typed.copy(),
                    typed.component1(),
                    typed.component2(),
                    typed.component3(),
                    typed.component4(),
                    typed.component5(),
                )
            }
            is SyncDecision.Conflict<*> -> {
                val typed = decision as SyncDecision.Conflict<WidgetId>
                assertDataClassGeneratedMembers(
                    typed,
                    typed.copy(),
                    typed.component1(),
                    typed.component2(),
                    typed.component3(),
                    typed.component4(),
                    typed.component5(),
                    typed.component6(),
                )
            }
            is SyncDecision.Retry<*> -> {
                val typed = decision as SyncDecision.Retry<WidgetId>
                assertDataClassGeneratedMembers(
                    typed,
                    typed.copy(),
                    typed.component1(),
                    typed.component2(),
                    typed.component3(),
                    typed.component4(),
                    typed.component5(),
                    typed.component6(),
                    typed.component7(),
                )
            }
        }
    }
}
