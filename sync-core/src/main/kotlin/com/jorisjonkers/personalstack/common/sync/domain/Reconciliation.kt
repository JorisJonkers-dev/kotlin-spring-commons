package com.jorisjonkers.personalstack.common.sync.domain

import java.time.Instant

/**
 * The pure reconciliation domain service. Given projections of local and remote state plus the
 * configured match plan and policies, it produces [SyncDecision]s and [SyncPlan]s.
 *
 * It is a total function: every input combination yields a decision/plan, and ordinary ambiguity
 * (duplicate ids, multiple match candidates) is represented as a non-executable
 * [SyncDecision.Conflict], never a thrown exception. It performs no I/O and touches no port; the
 * application layer is responsible for fetching, locking, persisting and emitting effects.
 * Keeps named helpers for each matching/decision branch so the core algorithm remains auditable.
 *
 * @param A aggregate, @param R remote record, @param RID remote id, @param KEY match key.
 */
@Suppress("TooManyFunctions")
class Reconciliation<A : Any, R : Any, RID : Any, KEY : Any>(
    private val localProjector: LocalProjector<A, RID, KEY>,
    private val remoteProjector: RemoteProjector<R, RID, KEY>,
    private val differ: SyncDiffer<A, R>,
    private val matchPlan: MatchPlan<A, R, RID, KEY>,
    private val policies: SyncPolicies<A, R, RID, KEY>,
) {
    /**
     * Three-way reconciliation of a single pairing. Remote failures must never reach this method;
     * `remote == null` here means authoritative absence only.
     */
    fun reconcileOne(
        local: A?,
        remote: R?,
        observedAt: Instant,
    ): SyncDecision<A, R, RID> {
        val localRecord = local?.let { localProjector.project(it) }
        val remoteRecord = remote?.let { remoteProjector.project(it) }
        return decide(localRecord, remoteRecord, local, remote, observedAt)
    }

    /**
     * Multi-record reconciliation over whole collections. Projects every record once, then runs
     * the match passes in order (hard id, remembered id, then configured natural-key passes),
     * consuming matched records before later passes. Duplicate hard ids and ambiguous candidates
     * become conflicts scoped to the offending records; unrelated decisions stay executable.
     * The decision list is returned in a deterministic execution order.
     *
     * Kept as one loop because splitting the core multi-pass matching algorithm obscures the
     * consume/remove invariants more than it reduces risk.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun reconcileMany(
        locals: Collection<A>,
        remotes: Collection<R>,
        context: SyncContext<*>,
    ): SyncPlan<A, R, RID> {
        val localRecords = locals.map { it to localProjector.project(it) }
        val remoteRecords = remotes.map { it to remoteProjector.project(it) }

        val decisions = mutableListOf<SyncDecision<A, R, RID>>()

        // Remaining (unmatched) records, keyed by a stable index so removal is deterministic.
        val remainingLocals = localRecords.toMutableList()
        val remainingRemotes = remoteRecords.toMutableList()

        for (pass in matchPlan.passes) {
            if (remainingLocals.isEmpty() || remainingRemotes.isEmpty()) break

            // Index remotes for this pass by their candidate keys, detecting duplicate keys.
            val remoteByKey = HashMap<KEY, MutableList<Pair<R, RemoteRecord<R, RID, KEY>>>>()
            for (entry in remainingRemotes) {
                for (key in pass.remoteKeys(entry.second)) {
                    remoteByKey.getOrPut(key) { mutableListOf() }.add(entry)
                }
            }

            val consumedLocals = HashSet<Int>()
            val consumedRemotes = HashSet<Int>()

            remainingLocals.forEachIndexed { li, localEntry ->
                if (li in consumedLocals) return@forEachIndexed
                val localKeys = pass.localKeys(localEntry.second)
                if (localKeys.isEmpty()) return@forEachIndexed

                // Gather distinct remote candidates across all keys this local matches on.
                val candidates = LinkedHashMap<Int, Pair<KEY, Pair<R, RemoteRecord<R, RID, KEY>>>>()
                for (key in localKeys) {
                    val matches = remoteByKey[key] ?: continue
                    for (match in matches) {
                        val ri = indexOfRemote(remainingRemotes, match)
                        if (ri >= 0 && ri !in consumedRemotes) {
                            candidates.putIfAbsent(ri, key to match)
                        }
                    }
                }

                when (candidates.size) {
                    0 -> Unit
                    1 -> {
                        val (ri, keyed) = candidates.entries.first().let { it.key to it.value }
                        val remoteMatch = keyed.second
                        consumedLocals.add(li)
                        consumedRemotes.add(ri)
                        decisions +=
                            pairedDecision(
                                pass = pass,
                                localRaw = localEntry.first,
                                localRecord = localEntry.second,
                                remoteRaw = remoteMatch.first,
                                remoteRecord = remoteMatch.second,
                                observedAt = context.startedAt,
                            )
                    }
                    else -> {
                        // Ambiguous: one local matched several remotes in the same pass.
                        consumedLocals.add(li)
                        candidates.keys.forEach { consumedRemotes.add(it) }
                        decisions +=
                            conflict(
                                kind =
                                    if (pass.confidence == MatchConfidence.HARD) {
                                        SyncConflictKind.DUPLICATE_REMOTE_ID
                                    } else {
                                        SyncConflictKind.AMBIGUOUS_MATCH
                                    },
                                local = localEntry.second,
                                remote =
                                    candidates.values
                                        .first()
                                        .second.second,
                                message =
                                    "local matched ${candidates.size} remotes in pass '${pass.name}' " +
                                        "(confidence=${pass.confidence})",
                            )
                    }
                }
            }

            // Detect remotes that matched more than one local in this pass (duplicate hard ids etc.).
            if (pass.confidence == MatchConfidence.HARD) {
                detectDuplicateRemotes(remainingLocals, consumedLocals, pass)?.let { dup ->
                    decisions += dup.decisions
                    consumedLocals.addAll(dup.consumedLocals)
                    consumedRemotes.addAll(dup.consumedRemotes)
                }
            }

            // Remove consumed records before the next pass (descending so indices stay valid).
            consumedLocals.sortedDescending().forEach { remainingLocals.removeAt(it) }
            consumedRemotes.sortedDescending().forEach { remainingRemotes.removeAt(it) }
        }

        // Leftover remotes with no local: import or ignore.
        for ((raw, record) in remainingRemotes) {
            decisions += remoteOnlyDecision(record, raw)
        }

        // Leftover locals with no remote: delegate to the missing-remote policy (authoritative absence).
        for ((_, record) in remainingLocals) {
            decisions += policies.missingRemotePolicy.decide(record, context.startedAt)
        }

        return SyncPlan(context = context, decisions = orderDeterministically(decisions))
    }

    // --- single-pairing core ----------------------------------------------------------------

    private fun decide(
        localRecord: LocalRecord<A, RID, KEY>?,
        remoteRecord: RemoteRecord<R, RID, KEY>?,
        localRaw: A?,
        remoteRaw: R?,
        observedAt: Instant,
    ): SyncDecision<A, R, RID> =
        when {
            localRecord != null && remoteRecord != null ->
                pairedDecision(
                    pass = null,
                    localRaw = localRaw!!,
                    localRecord = localRecord,
                    remoteRaw = remoteRaw!!,
                    remoteRecord = remoteRecord,
                    observedAt = observedAt,
                )

            localRecord == null && remoteRecord != null ->
                remoteOnlyDecision(remoteRecord, remoteRaw!!)

            localRecord != null && remoteRecord == null ->
                policies.missingRemotePolicy.decide(localRecord, observedAt)

            else ->
                SyncDecision.Ignore(
                    subject = SyncSubject.Unknown,
                    reason = SyncReason.Policy("nothing to reconcile"),
                )
        }

    private fun pairedDecision(
        pass: MatchPass<A, R, RID, KEY>?,
        localRaw: A,
        localRecord: LocalRecord<A, RID, KEY>,
        remoteRaw: R,
        remoteRecord: RemoteRecord<R, RID, KEY>,
        observedAt: Instant,
    ): SyncDecision<A, R, RID> =
        if (remoteRecord.lifecycle == RemoteRecordLifecycle.DELETED) {
            remoteDeletedDecision(localRecord, remoteRecord, observedAt)
        } else {
            activeRemoteDecision(pass, localRaw, localRecord, remoteRaw, remoteRecord)
        }

    private fun remoteDeletedDecision(
        localRecord: LocalRecord<A, RID, KEY>,
        remoteRecord: RemoteRecord<R, RID, KEY>,
        observedAt: Instant,
    ): SyncDecision<A, R, RID> =
        if (localRecord.registration.lifecycle == SyncRegistrationLifecycle.REMOTELY_DELETED) {
            SyncDecision.Ignore(
                subject = SyncSubject.Pair(localRecord.localId, remoteRecord.externalId),
                reason = SyncReason.RemoteDeleted,
            )
        } else {
            SyncDecision.Delete(
                local = localRecord,
                signal =
                    RemoteDeleteSignal.Tombstone(
                        remoteId = remoteRecord.externalId,
                        observedAt = remoteRecord.observedAt ?: observedAt,
                        version = remoteRecord.version,
                    ),
            )
        }

    private fun activeRemoteDecision(
        pass: MatchPass<A, R, RID, KEY>?,
        localRaw: A,
        localRecord: LocalRecord<A, RID, KEY>,
        remoteRaw: R,
        remoteRecord: RemoteRecord<R, RID, KEY>,
    ): SyncDecision<A, R, RID> {
        val changes = differ.diff(localRaw, remoteRaw)
        return when {
            localRecord.registration.lifecycle == SyncRegistrationLifecycle.REMOTELY_DELETED ->
                SyncDecision.Restore(local = localRecord, remote = remoteRecord, changes = changes)
            needsRelink(localRecord.registration, remoteRecord.externalId, pass) ->
                SyncDecision.Relink(local = localRecord, remote = remoteRecord, changes = changes)
            changes.isEmpty -> SyncDecision.Equal(local = localRecord, remote = remoteRecord)
            else -> SyncDecision.Update(local = localRecord, remote = remoteRecord, changes = changes)
        }
    }

    /**
     * Relink is needed when the local side has no active remote id, or when the match was made by
     * a soft/remembered pass against an id that is not the local's current active link.
     */
    private fun needsRelink(
        registration: SyncRegistration<RID>,
        externalId: RID,
        pass: MatchPass<A, R, RID, KEY>?,
    ): Boolean =
        registration.remoteId?.let { activeId ->
            activeId != externalId ||
                (
                    pass != null &&
                        pass.confidence != MatchConfidence.HARD &&
                        registration.lifecycle != SyncRegistrationLifecycle.LINKED
                )
        } ?: true

    private fun remoteOnlyDecision(
        remoteRecord: RemoteRecord<R, RID, KEY>,
        remoteRaw: R,
    ): SyncDecision<A, R, RID> =
        when {
            remoteRecord.lifecycle == RemoteRecordLifecycle.DELETED ->
                SyncDecision.Ignore(
                    subject = SyncSubject.Remote(remoteRecord.externalId),
                    reason = SyncReason.RemoteDeleted,
                )

            !remoteRecord.importable || !policies.importPolicy.importable(remoteRaw) ->
                SyncDecision.Ignore(
                    subject = SyncSubject.Remote(remoteRecord.externalId),
                    reason = SyncReason.NotImportable,
                )

            else -> SyncDecision.Import(remote = remoteRecord)
        }

    private fun conflict(
        kind: SyncConflictKind,
        local: LocalRecord<A, RID, KEY>?,
        remote: RemoteRecord<R, RID, KEY>?,
        message: String,
    ): SyncDecision.Conflict<RID> {
        val subject: SyncSubject<RID> =
            when {
                local != null && remote != null -> SyncSubject.Pair(local.localId, remote.externalId)
                remote != null -> SyncSubject.Remote(remote.externalId)
                local?.localId != null -> SyncSubject.Local(local.localId)
                else -> SyncSubject.Unknown
            }
        return policies.conflictPolicy.decide(
            SyncConflict(
                subject = subject,
                kind = kind,
                local = local,
                remote = remote,
                message = message,
            ),
        )
    }

    private fun detectDuplicateRemotes(
        remainingLocals: List<Pair<A, LocalRecord<A, RID, KEY>>>,
        alreadyConsumed: Set<Int>,
        pass: MatchPass<A, R, RID, KEY>,
    ): DuplicateScan? {
        // Group not-yet-consumed locals by their hard keys; any key shared by >1 local is a conflict.
        val localsByKey = HashMap<KEY, MutableList<Int>>()
        remainingLocals.forEachIndexed { li, entry ->
            if (li in alreadyConsumed) return@forEachIndexed
            for (key in pass.localKeys(entry.second)) {
                localsByKey.getOrPut(key) { mutableListOf() }.add(li)
            }
        }
        val dupKeys = localsByKey.filterValues { it.size > 1 }
        if (dupKeys.isEmpty()) return null

        val decisions = mutableListOf<SyncDecision<A, R, RID>>()
        val consumedLocals = HashSet<Int>()
        for ((_, indices) in dupKeys) {
            for (li in indices) {
                if (consumedLocals.add(li)) {
                    decisions +=
                        conflict(
                            kind = SyncConflictKind.DUPLICATE_LOCAL_REMOTE_ID,
                            local = remainingLocals[li].second,
                            remote = null,
                            message = "duplicate hard key across local records in pass '${pass.name}'",
                        )
                }
            }
        }
        return DuplicateScan(decisions, consumedLocals, emptySet())
    }

    private fun indexOfRemote(
        remaining: List<Pair<R, RemoteRecord<R, RID, KEY>>>,
        target: Pair<R, RemoteRecord<R, RID, KEY>>,
    ): Int = remaining.indexOfFirst { it === target }

    private fun orderDeterministically(decisions: List<SyncDecision<A, R, RID>>): List<SyncDecision<A, R, RID>> =
        decisions.sortedWith(
            compareBy<SyncDecision<A, R, RID>> { executionRank(it.action) }
                .thenBy { it.subject.stableKey },
        )

    private fun executionRank(action: SyncAction): Int = EXECUTION_ORDER.indexOf(action)

    private inner class DuplicateScan(
        val decisions: List<SyncDecision<A, R, RID>>,
        val consumedLocals: Set<Int>,
        val consumedRemotes: Set<Int>,
    )

    private companion object {
        private val EXECUTION_ORDER =
            listOf(
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
    }
}
