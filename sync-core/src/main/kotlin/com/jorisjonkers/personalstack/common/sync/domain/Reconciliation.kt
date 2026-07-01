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
 *
 * @param A aggregate, @param R remote record, @param RID remote id, @param KEY match key.
 */
class Reconciliation<A : Any, R : Any, RID : Any, KEY : Any>(
    private val localProjector: LocalProjector<A, RID, KEY>,
    private val remoteProjector: RemoteProjector<R, RID, KEY>,
    differ: SyncDiffer<A, R>,
    matchPlan: MatchPlan<A, R, RID, KEY>,
    policies: SyncPolicies<A, R, RID, KEY>,
) {
    private val decider = SingleReconciliationDecider(differ, policies)
    private val many = MultiRecordReconciler(localProjector, remoteProjector, matchPlan, policies, decider)

    /**
     * Three-way reconciliation of a single pairing. Remote failures must never reach this method;
     * `remote == null` here means authoritative absence only.
     */
    fun reconcileOne(
        local: A?,
        remote: R?,
        observedAt: Instant,
    ): SyncDecision<A, R, RID, KEY> {
        val localRecord = local?.let { localProjector.project(it) }
        val remoteRecord = remote?.let { remoteProjector.project(it) }
        return decider.decide(localRecord, remoteRecord, local, remote, observedAt)
    }

    /**
     * Multi-record reconciliation over whole collections. Projects every record once, then runs
     * the match passes in order (hard id, remembered id, then configured natural-key passes),
     * consuming matched records before later passes. Duplicate hard ids and ambiguous candidates
     * become conflicts scoped to the offending records; unrelated decisions stay executable.
     * The decision list is returned in a deterministic execution order.
     */
    fun reconcileMany(
        locals: Collection<A>,
        remotes: Collection<R>,
        context: SyncContext<*>,
    ): SyncPlan<A, R, RID, KEY> = many.reconcile(locals, remotes, context)
}

private class SingleReconciliationDecider<A : Any, R : Any, RID : Any, KEY : Any>(
    private val differ: SyncDiffer<A, R>,
    private val policies: SyncPolicies<A, R, RID, KEY>,
) {
    fun decide(
        localRecord: LocalRecord<A, RID, KEY>?,
        remoteRecord: RemoteRecord<R, RID, KEY>?,
        localRaw: A?,
        remoteRaw: R?,
        observedAt: Instant,
    ): SyncDecision<A, R, RID, KEY> =
        when {
            localRecord != null && remoteRecord != null ->
                pairedDecision(
                    match =
                        MatchedRecords(
                            pass = null,
                            localRaw = requireNotNull(localRaw),
                            localRecord = localRecord,
                            remoteRaw = requireNotNull(remoteRaw),
                            remoteRecord = remoteRecord,
                        ),
                    observedAt = observedAt,
                )

            localRecord == null && remoteRecord != null ->
                remoteOnlyDecision(remoteRecord, requireNotNull(remoteRaw))

            localRecord != null && remoteRecord == null ->
                policies.missingRemotePolicy.decide(localRecord, observedAt)

            else ->
                SyncDecision.Ignore<A, R, RID, KEY>(
                    subject = SyncSubject.Unknown,
                    reason = SyncReason.Policy("nothing to reconcile"),
                )
        }

    fun pairedDecision(
        match: MatchedRecords<A, R, RID, KEY>,
        observedAt: Instant,
    ): SyncDecision<A, R, RID, KEY> =
        if (match.remoteRecord.lifecycle == RemoteRecordLifecycle.DELETED) {
            remoteDeletedDecision(match.localRecord, match.remoteRecord, observedAt)
        } else {
            activeRemoteDecision(match)
        }

    fun remoteOnlyDecision(
        remoteRecord: RemoteRecord<R, RID, KEY>,
        remoteRaw: R,
    ): SyncDecision<A, R, RID, KEY> =
        when {
            remoteRecord.lifecycle == RemoteRecordLifecycle.DELETED ->
                SyncDecision.Ignore<A, R, RID, KEY>(
                    subject = SyncSubject.Remote(remoteRecord.externalId),
                    reason = SyncReason.RemoteDeleted,
                )

            !remoteRecord.importable || !policies.importPolicy.importable(remoteRaw) ->
                SyncDecision.Ignore<A, R, RID, KEY>(
                    subject = SyncSubject.Remote(remoteRecord.externalId),
                    reason = SyncReason.NotImportable,
                )

            else -> SyncDecision.Import<A, R, RID, KEY>(remote = remoteRecord)
        }

    fun conflict(
        kind: SyncConflictKind,
        local: LocalRecord<A, RID, KEY>?,
        remote: RemoteRecord<R, RID, KEY>?,
        message: String,
    ): SyncDecision.Conflict<A, R, RID, KEY> {
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

    private fun remoteDeletedDecision(
        localRecord: LocalRecord<A, RID, KEY>,
        remoteRecord: RemoteRecord<R, RID, KEY>,
        observedAt: Instant,
    ): SyncDecision<A, R, RID, KEY> =
        if (localRecord.registration.lifecycle == SyncRegistrationLifecycle.REMOTELY_DELETED) {
            SyncDecision.Ignore<A, R, RID, KEY>(
                subject = SyncSubject.Pair(localRecord.localId, remoteRecord.externalId),
                reason = SyncReason.RemoteDeleted,
            )
        } else {
            SyncDecision.Delete<A, R, RID, KEY>(
                local = localRecord,
                signal =
                    RemoteDeleteSignal.Tombstone(
                        remoteId = remoteRecord.externalId,
                        observedAt = remoteRecord.observedAt ?: observedAt,
                        version = remoteRecord.version,
                    ),
            )
        }

    private fun activeRemoteDecision(match: MatchedRecords<A, R, RID, KEY>): SyncDecision<A, R, RID, KEY> {
        val changes = differ.diff(match.localRaw, match.remoteRaw)
        return when {
            match.localRecord.registration.lifecycle == SyncRegistrationLifecycle.REMOTELY_DELETED ->
                SyncDecision.Restore<A, R, RID, KEY>(
                    local = match.localRecord,
                    remote = match.remoteRecord,
                    changes = changes,
                )
            needsRelink(match.localRecord.registration, match.remoteRecord.externalId, match.pass) ->
                SyncDecision.Relink<A, R, RID, KEY>(
                    local = match.localRecord,
                    remote = match.remoteRecord,
                    changes = changes,
                )
            changes.isEmpty ->
                SyncDecision.Equal<A, R, RID, KEY>(local = match.localRecord, remote = match.remoteRecord)
            else ->
                SyncDecision.Update<A, R, RID, KEY>(
                    local = match.localRecord,
                    remote = match.remoteRecord,
                    changes = changes,
                )
        }
    }

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
}

private class MultiRecordReconciler<A : Any, R : Any, RID : Any, KEY : Any>(
    private val localProjector: LocalProjector<A, RID, KEY>,
    private val remoteProjector: RemoteProjector<R, RID, KEY>,
    private val matchPlan: MatchPlan<A, R, RID, KEY>,
    private val policies: SyncPolicies<A, R, RID, KEY>,
    private val decider: SingleReconciliationDecider<A, R, RID, KEY>,
) {
    fun reconcile(
        locals: Collection<A>,
        remotes: Collection<R>,
        context: SyncContext<*>,
    ): SyncPlan<A, R, RID, KEY> {
        val state =
            ReconciliationState(
                remainingLocals = locals.map { it to localProjector.project(it) }.toMutableList(),
                remainingRemotes = remotes.map { it to remoteProjector.project(it) }.toMutableList(),
                decisions = mutableListOf(),
            )

        for (pass in matchPlan.passes) {
            if (state.remainingLocals.isEmpty() || state.remainingRemotes.isEmpty()) break
            runPass(state, pass, context.startedAt)
        }

        addRemainingRemotes(state)
        addRemainingLocals(state, context.startedAt)
        return SyncPlan(context = context, decisions = orderDecisions(state.decisions))
    }

    private fun runPass(
        state: ReconciliationState<A, R, RID, KEY>,
        pass: MatchPass<A, R, RID, KEY>,
        observedAt: Instant,
    ) {
        val passState =
            MatchPassState(
                state = state,
                pass = pass,
                remoteByKey = indexRemotesByKey(state.remainingRemotes, pass),
                consumedLocals = HashSet(),
                consumedRemotes = HashSet(),
                observedAt = observedAt,
            )

        matchLocals(passState)
        if (pass.confidence == MatchConfidence.HARD) {
            detectDuplicateLocals(state.remainingLocals, passState.consumedLocals, pass).forEach { duplicate ->
                state.decisions += duplicate.decision
                passState.consumedLocals += duplicate.localIndex
            }
        }

        removeConsumed(state.remainingLocals, passState.consumedLocals)
        removeConsumed(state.remainingRemotes, passState.consumedRemotes)
    }

    private fun matchLocals(passState: MatchPassState<A, R, RID, KEY>) {
        passState.state.remainingLocals.forEachIndexed { localIndex, localEntry ->
            if (localIndex in passState.consumedLocals) return@forEachIndexed
            val candidates =
                candidatesFor(
                    localEntry.second,
                    passState.pass,
                    passState.remoteByKey,
                    passState.consumedRemotes,
                )
            when (candidates.size) {
                0 -> Unit
                1 ->
                    consumePair(
                        passState,
                        localIndex,
                        localEntry,
                        candidates.first(),
                    )
                else ->
                    consumeAmbiguous(
                        passState,
                        localIndex,
                        localEntry,
                        candidates,
                    )
            }
        }
    }

    private fun candidatesFor(
        local: LocalRecord<A, RID, KEY>,
        pass: MatchPass<A, R, RID, KEY>,
        remoteByKey: Map<KEY, List<IndexedRemote<R, RID, KEY>>>,
        consumedRemotes: Set<Int>,
    ): List<RemoteCandidate<R, RID, KEY>> {
        val candidates = LinkedHashMap<Int, RemoteCandidate<R, RID, KEY>>()
        for (key in pass.localKeys(local)) {
            for (remote in remoteByKey[key].orEmpty()) {
                if (remote.index !in consumedRemotes) {
                    candidates.putIfAbsent(remote.index, RemoteCandidate(remote))
                }
            }
        }
        return candidates.values.toList()
    }

    private fun consumePair(
        passState: MatchPassState<A, R, RID, KEY>,
        localIndex: Int,
        localEntry: Pair<A, LocalRecord<A, RID, KEY>>,
        candidate: RemoteCandidate<R, RID, KEY>,
    ) {
        passState.consumedLocals += localIndex
        passState.consumedRemotes += candidate.remote.index
        passState.state.decisions +=
            decider.pairedDecision(
                match =
                    MatchedRecords(
                        pass = passState.pass,
                        localRaw = localEntry.first,
                        localRecord = localEntry.second,
                        remoteRaw = candidate.remote.raw,
                        remoteRecord = candidate.remote.record,
                    ),
                observedAt = passState.observedAt,
            )
    }

    private fun consumeAmbiguous(
        passState: MatchPassState<A, R, RID, KEY>,
        localIndex: Int,
        localEntry: Pair<A, LocalRecord<A, RID, KEY>>,
        candidates: List<RemoteCandidate<R, RID, KEY>>,
    ) {
        passState.consumedLocals += localIndex
        candidates.forEach { passState.consumedRemotes += it.remote.index }
        passState.state.decisions +=
            decider.conflict(
                kind =
                    if (passState.pass.confidence == MatchConfidence.HARD) {
                        SyncConflictKind.DUPLICATE_REMOTE_ID
                    } else {
                        SyncConflictKind.AMBIGUOUS_MATCH
                    },
                local = localEntry.second,
                remote = candidates.first().remote.record,
                message =
                    "local matched ${candidates.size} remotes in pass '${passState.pass.name}' " +
                        "(confidence=${passState.pass.confidence})",
            )
    }

    private fun detectDuplicateLocals(
        remainingLocals: List<Pair<A, LocalRecord<A, RID, KEY>>>,
        alreadyConsumed: Set<Int>,
        pass: MatchPass<A, R, RID, KEY>,
    ): List<DuplicateLocal<A, R, RID, KEY>> {
        val localsByKey = HashMap<KEY, MutableList<Int>>()
        remainingLocals.forEachIndexed { localIndex, entry ->
            if (localIndex !in alreadyConsumed) {
                pass.localKeys(entry.second).forEach { key ->
                    localsByKey.getOrPut(key) { mutableListOf() } += localIndex
                }
            }
        }
        return localsByKey
            .filterValues { it.size > 1 }
            .flatMap { (_, indices) -> duplicateLocalDecisions(indices, remainingLocals, pass) }
    }

    private fun duplicateLocalDecisions(
        indices: List<Int>,
        remainingLocals: List<Pair<A, LocalRecord<A, RID, KEY>>>,
        pass: MatchPass<A, R, RID, KEY>,
    ): List<DuplicateLocal<A, R, RID, KEY>> =
        indices.distinct().map { localIndex ->
            DuplicateLocal(
                localIndex = localIndex,
                decision =
                    decider.conflict(
                        kind = SyncConflictKind.DUPLICATE_LOCAL_REMOTE_ID,
                        local = remainingLocals[localIndex].second,
                        remote = null,
                        message = "duplicate hard key across local records in pass '${pass.name}'",
                    ),
            )
        }

    private fun indexRemotesByKey(
        remainingRemotes: List<Pair<R, RemoteRecord<R, RID, KEY>>>,
        pass: MatchPass<A, R, RID, KEY>,
    ): Map<KEY, List<IndexedRemote<R, RID, KEY>>> {
        val remoteByKey = HashMap<KEY, MutableList<IndexedRemote<R, RID, KEY>>>()
        remainingRemotes.forEachIndexed { remoteIndex, entry ->
            val remote = IndexedRemote(remoteIndex, entry.first, entry.second)
            pass.remoteKeys(entry.second).forEach { key ->
                remoteByKey.getOrPut(key) { mutableListOf() } += remote
            }
        }
        return remoteByKey
    }

    private fun addRemainingRemotes(state: ReconciliationState<A, R, RID, KEY>) {
        for ((raw, record) in state.remainingRemotes) {
            state.decisions += decider.remoteOnlyDecision(record, raw)
        }
    }

    private fun addRemainingLocals(
        state: ReconciliationState<A, R, RID, KEY>,
        observedAt: Instant,
    ) {
        for ((_, record) in state.remainingLocals) {
            state.decisions += policies.missingRemotePolicy.decide(record, observedAt)
        }
    }

    private data class ReconciliationState<A : Any, R : Any, RID : Any, KEY : Any>(
        val remainingLocals: MutableList<Pair<A, LocalRecord<A, RID, KEY>>>,
        val remainingRemotes: MutableList<Pair<R, RemoteRecord<R, RID, KEY>>>,
        val decisions: MutableList<SyncDecision<A, R, RID, KEY>>,
    )

    private data class MatchPassState<A : Any, R : Any, RID : Any, KEY : Any>(
        val state: ReconciliationState<A, R, RID, KEY>,
        val pass: MatchPass<A, R, RID, KEY>,
        val remoteByKey: Map<KEY, List<IndexedRemote<R, RID, KEY>>>,
        val consumedLocals: MutableSet<Int>,
        val consumedRemotes: MutableSet<Int>,
        val observedAt: Instant,
    )

    private data class IndexedRemote<R : Any, RID : Any, KEY : Any>(
        val index: Int,
        val raw: R,
        val record: RemoteRecord<R, RID, KEY>,
    )

    private data class RemoteCandidate<R : Any, RID : Any, KEY : Any>(
        val remote: IndexedRemote<R, RID, KEY>,
    )

    private data class DuplicateLocal<A : Any, R : Any, RID : Any, KEY : Any>(
        val localIndex: Int,
        val decision: SyncDecision.Conflict<A, R, RID, KEY>,
    )
}

private data class MatchedRecords<A : Any, R : Any, RID : Any, KEY : Any>(
    val pass: MatchPass<A, R, RID, KEY>?,
    val localRaw: A,
    val localRecord: LocalRecord<A, RID, KEY>,
    val remoteRaw: R,
    val remoteRecord: RemoteRecord<R, RID, KEY>,
)

private fun <T> removeConsumed(
    records: MutableList<T>,
    consumedIndices: Set<Int>,
) {
    consumedIndices.sortedDescending().forEach { records.removeAt(it) }
}

private fun <A : Any, R : Any, RID : Any, KEY : Any> orderDecisions(
    decisions: List<SyncDecision<A, R, RID, KEY>>,
): List<SyncDecision<A, R, RID, KEY>> =
    decisions.sortedWith(
        compareBy<SyncDecision<A, R, RID, KEY>> { executionRank(it.action) }
            .thenBy { it.subject.stableKey },
    )

private fun executionRank(action: SyncAction): Int = EXECUTION_ORDER.indexOf(action)

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
