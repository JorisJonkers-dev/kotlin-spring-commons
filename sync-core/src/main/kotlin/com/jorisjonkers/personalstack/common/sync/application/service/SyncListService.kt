package com.jorisjonkers.personalstack.common.sync.application.service

import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncListCommand
import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncListUseCase
import com.jorisjonkers.personalstack.common.sync.domain.BaselineSnapshot
import com.jorisjonkers.personalstack.common.sync.domain.ListTransactionMode
import com.jorisjonkers.personalstack.common.sync.domain.Reconciliation
import com.jorisjonkers.personalstack.common.sync.domain.RemoteChange
import com.jorisjonkers.personalstack.common.sync.domain.RemoteFetch
import com.jorisjonkers.personalstack.common.sync.domain.RemotePage
import com.jorisjonkers.personalstack.common.sync.domain.RemoteRecord
import com.jorisjonkers.personalstack.common.sync.domain.RequeueDecision
import com.jorisjonkers.personalstack.common.sync.domain.RunId
import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncDecision
import com.jorisjonkers.personalstack.common.sync.domain.SyncDefinition
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailure
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailureKind
import com.jorisjonkers.personalstack.common.sync.domain.SyncLockKey
import com.jorisjonkers.personalstack.common.sync.domain.SyncName
import com.jorisjonkers.personalstack.common.sync.domain.SyncOutcome
import com.jorisjonkers.personalstack.common.sync.domain.SyncReport
import com.jorisjonkers.personalstack.common.sync.domain.SyncSubject
import com.jorisjonkers.personalstack.common.sync.domain.port.out.LockResult
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncTransactionContext
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Application service for list / authoritative-set sync. Implements [SyncListUseCase].
 *
 * Flow (per spec section 5): fetch remote page OUTSIDE the tx -> [RemoteFetch.Partial] plans only the
 * records present and requeues, never inferring deletes from a partial page -> acquire scope lock ->
 * re-read locals inside the lock -> build a pure [com.jorisjonkers.personalstack.common.sync.domain.SyncPlan]
 * via multi-pass matching -> execute.
 *
 * Default [ListTransactionMode.PER_RECORD] commits each record in its own transaction (partial report
 * on per-record failure). [ListTransactionMode.WHOLE_SCOPE] (and PER_PAGE here, treated as whole-scope
 * for a single fetched page) executes the whole plan atomically and rolls back on any executable failure.
 */
class SyncListService<A : Any, R : Any, RID : Any, KEY : Any, SCOPE : Any>(
    private val definition: SyncDefinition<A, R, RID, KEY, SCOPE>,
    private val clock: Clock = Clock.systemUTC(),
) : SyncListUseCase<SCOPE> {
    private val ports = definition.ports
    private val syncName: SyncName = definition.name

    private val reconciliation =
        Reconciliation(
            localProjector = definition.localProjector,
            remoteProjector = definition.remoteProjector,
            differ = definition.differ,
            matchPlan = definition.matchPlan,
            policies = definition.policies,
        )

    override fun sync(command: SyncListCommand<SCOPE>): SyncReport {
        val startedAt = Instant.now(clock)
        val context =
            SyncContext(
                runId = RunId.new(),
                syncName = syncName,
                correlationId = command.correlationId,
                source = command.source,
                scope = command.scope,
                startedAt = startedAt,
                dryRun = command.dryRun,
            )

        ports.observer.onRunStarted(context)
        ports.auditTrail.recordRunStarted(context)

        // Fetch the remote page OUTSIDE the transaction.
        val fetch = ports.remoteCatalog.fetchForScope(command.scope, context)
        val pageData: PageView<R, RID, KEY> =
            when (fetch) {
                is RemoteFetch.Found -> PageView(fetch.value, partialFailure = null, authoritative = true)
                // Partial: plan only present records, requeue, NEVER infer deletes from a partial page.
                is RemoteFetch.Partial -> PageView(fetch.value, partialFailure = fetch.failure, authoritative = false)
                is RemoteFetch.Missing ->
                    // A scope with no remote source-of-truth: treat as empty authoritative set is unsafe,
                    // so report skipped rather than mass-deleting. NOTE: Missing on a list fetch is rare.
                    return finishEmpty(context, startedAt, fetch)
                is RemoteFetch.Failed ->
                    // Failed remote fetch must NEVER become deletes; report and requeue.
                    return finishFailed(context, startedAt, fetch.failure)
            }

        val remotes: List<R> = pageData.page.changes.mapNotNull { change ->
            when (change) {
                is RemoteChange.Upsert<R, RID, KEY> -> change.remote.record
                is RemoteChange.Delete<RID> -> null // explicit deletes handled by reconcile via remote lifecycle
            }
        }

        val lockKey = SyncLockKey("${syncName.value}:scope:${command.scope}")
        val lockResult =
            ports.lockManager.withLock(lockKey, definition.execution.lockTimeout) {
                when (definition.execution.listTransactionMode) {
                    ListTransactionMode.PER_RECORD -> runPerRecord(context, command, remotes)
                    ListTransactionMode.PER_PAGE,
                    ListTransactionMode.WHOLE_SCOPE,
                    -> runWholeScope(context, command, remotes, pageData.authoritative)
                }
            }

        val outcomes: List<SyncOutcome<RID>> =
            when (lockResult) {
                is LockResult.Acquired -> lockResult.value
                is LockResult.NotAcquired ->
                    listOf(
                        SyncOutcome.Failed(
                            subject = SyncSubject.Scope(
                                com.jorisjonkers.personalstack.common.sync.domain.ScopeId(command.scope.toString()),
                            ),
                            action = null,
                            duration = Duration.between(startedAt, Instant.now(clock)),
                            failure =
                                SyncFailure(
                                    kind = SyncFailureKind.LOCK_UNAVAILABLE,
                                    message = "could not acquire scope lock $lockKey",
                                    retryable = true,
                                    retryAfter = definition.execution.lockTimeout,
                                ),
                        ),
                    )
            }

        val requeue = requeueFor(pageData, outcomes)
        val report = report(context, startedAt, outcomes, requeue)
        ports.observer.onRunCompleted(report)
        ports.auditTrail.recordRunCompleted(report)
        return report
    }

    /** Per-record default: re-read locals once inside the lock, plan, then commit each decision separately. */
    private fun runPerRecord(
        context: SyncContext<SCOPE>,
        command: SyncListCommand<SCOPE>,
        remotes: List<R>,
    ): List<SyncOutcome<RID>> {
        val locals =
            ports.unitOfWork.transaction {
                ports.localRepository.listIncludingDeleted(command.scope, context)
            }
        val plan = reconciliation.reconcileMany(locals, remotes, context)

        return plan.decisions.map { decision ->
            try {
                ports.unitOfWork.transaction {
                    // Stale-plan guard: when the decision references a remote id, re-read and re-reconcile.
                    val refreshed = refreshDecision(context, decision, command)
                    executeDecision(context, refreshed, this)
                }
            } catch (ex: RuntimeException) {
                val failure = failureFromException(ex)
                val outcome =
                    SyncOutcome.Failed(decision.subject, decision.action, Duration.ZERO, failure)
                ports.observer.onOutcome(context, outcome)
                outcome
            }
        }
    }

    /** Whole-scope atomic mode: one transaction. Plan-level conflict reports without executing. */
    private fun runWholeScope(
        context: SyncContext<SCOPE>,
        command: SyncListCommand<SCOPE>,
        remotes: List<R>,
        authoritative: Boolean,
    ): List<SyncOutcome<RID>> =
        ports.unitOfWork.transaction {
            val locals = ports.localRepository.listIncludingDeleted(command.scope, context)
            val plan = reconciliation.reconcileMany(locals, remotes, context)

            if (!plan.executable) {
                // Plan-level conflict: audit/report only, execute nothing.
                plan.decisions.map { decision ->
                    val outcome =
                        SyncOutcome.Skipped(decision.subject, decision.action, Duration.ZERO, decision.reason)
                    recordOutcome(context, outcome, this)
                    outcome
                }
            } else {
                // NOTE: in non-authoritative (partial) pages we still execute only present records;
                // absence-driven deletes are produced by reconcileMany only against authoritative locals.
                plan.decisions.map { decision -> executeDecision(context, decision, this) }
            }
        }

    /** Re-read the subject's local aggregate by remote id and re-reconcile to guard against staleness. */
    private fun refreshDecision(
        context: SyncContext<SCOPE>,
        decision: SyncDecision<A, R, RID>,
        command: SyncListCommand<SCOPE>,
    ): SyncDecision<A, R, RID> {
        val remoteId = remoteIdOf(decision) ?: return decision
        val remoteRecord = remoteRecordOf(decision)
        val local =
            ports.localRepository.findByRemoteIdIncludingDeleted(remoteId, command.scope, context)
        val observedAt = remoteRecord?.observedAt ?: Instant.now(clock)
        return reconciliation.reconcileOne(local, remoteRecord?.record, observedAt)
    }

    @Suppress("UNCHECKED_CAST")
    private fun executeDecision(
        context: SyncContext<SCOPE>,
        decision: SyncDecision<A, R, RID>,
        tx: SyncTransactionContext,
    ): SyncOutcome<RID> {
        val start = Instant.now(clock)
        val mapper = definition.mapper
        fun durationNow() = Duration.between(start, Instant.now(clock))

        if (!decision.executable) {
            val outcome =
                if (decision is SyncDecision.Retry) {
                    SyncOutcome.Failed(decision.subject, decision.action, durationNow(), decision.failure)
                } else {
                    SyncOutcome.Skipped(decision.subject, decision.action, durationNow(), decision.reason)
                }
            recordOutcome(context, outcome, tx)
            return outcome
        }

        if (context.dryRun) {
            val outcome = SyncOutcome.Skipped(decision.subject, decision.action, durationNow(), decision.reason)
            ports.observer.onOutcome(context, outcome)
            return outcome
        }

        when (decision) {
            is SyncDecision.Import<*, *, *> -> {
                val d = decision as SyncDecision.Import<R, RID, KEY>
                ports.localRepository.save(mapper.create(d.remote.record, context), context)
                saveBaseline(d.subject, d.remote)
            }
            is SyncDecision.Update<*, *, *, *> -> {
                val d = decision as SyncDecision.Update<A, R, RID, KEY>
                ports.localRepository.save(mapper.update(d.local.aggregate, d.remote.record, d.changes, context), context)
                saveBaseline(d.subject, d.remote)
            }
            is SyncDecision.Restore<*, *, *, *> -> {
                val d = decision as SyncDecision.Restore<A, R, RID, KEY>
                ports.localRepository.save(mapper.restore(d.local.aggregate, d.remote.record, d.changes, context), context)
                saveBaseline(d.subject, d.remote)
            }
            is SyncDecision.Relink<*, *, *, *> -> {
                val d = decision as SyncDecision.Relink<A, R, RID, KEY>
                ports.localRepository.save(mapper.relink(d.local.aggregate, d.remote.record, d.changes, context), context)
                saveBaseline(d.subject, d.remote)
            }
            is SyncDecision.Delete<*, *, *> -> {
                val d = decision as SyncDecision.Delete<A, RID, KEY>
                ports.localRepository.save(mapper.delete(d.local.aggregate, d.signal, context), context)
            }
            is SyncDecision.Unlink<*, *, *> -> {
                val d = decision as SyncDecision.Unlink<A, RID, KEY>
                ports.localRepository.save(mapper.unlink(d.local.aggregate, d.unlinkReason, context), context)
            }
            else -> Unit
        }

        if (decision.effects.isNotEmpty()) {
            ports.effectOutbox.append(context, decision.effects)
        }

        val outcome = SyncOutcome.Succeeded(decision.subject, decision.action, durationNow())
        recordOutcome(context, outcome, tx)

        if (decision.effects.isNotEmpty()) {
            tx.afterCommit { ports.effectOutbox.requestRelay(context) }
        }
        return outcome
    }

    private fun saveBaseline(subject: SyncSubject<RID>, remote: RemoteRecord<R, RID, KEY>) {
        ports.checkpointStore.saveBaseline(
            syncName,
            subject,
            BaselineSnapshot(version = remote.version, fingerprint = null, capturedAt = Instant.now(clock)),
        )
    }

    private fun recordOutcome(
        context: SyncContext<SCOPE>,
        outcome: SyncOutcome<RID>,
        @Suppress("UNUSED_PARAMETER") tx: SyncTransactionContext,
    ) {
        ports.auditTrail.recordOutcome(context, outcome)
        ports.observer.onOutcome(context, outcome)
    }

    private fun remoteIdOf(decision: SyncDecision<A, R, RID>): RID? =
        when (decision) {
            is SyncDecision.Import<*, *, *> -> (decision as SyncDecision.Import<R, RID, KEY>).remote.externalId
            is SyncDecision.Update<*, *, *, *> -> (decision as SyncDecision.Update<A, R, RID, KEY>).remote.externalId
            is SyncDecision.Restore<*, *, *, *> -> (decision as SyncDecision.Restore<A, R, RID, KEY>).remote.externalId
            is SyncDecision.Relink<*, *, *, *> -> (decision as SyncDecision.Relink<A, R, RID, KEY>).remote.externalId
            is SyncDecision.Delete<*, *, *> -> (decision as SyncDecision.Delete<A, RID, KEY>).signal.remoteId
            else -> null
        }

    @Suppress("UNCHECKED_CAST")
    private fun remoteRecordOf(decision: SyncDecision<A, R, RID>): RemoteRecord<R, RID, KEY>? =
        when (decision) {
            is SyncDecision.Import<*, *, *> -> (decision as SyncDecision.Import<R, RID, KEY>).remote
            is SyncDecision.Update<*, *, *, *> -> (decision as SyncDecision.Update<A, R, RID, KEY>).remote
            is SyncDecision.Restore<*, *, *, *> -> (decision as SyncDecision.Restore<A, R, RID, KEY>).remote
            is SyncDecision.Relink<*, *, *, *> -> (decision as SyncDecision.Relink<A, R, RID, KEY>).remote
            else -> null
        }

    private fun failureFromException(ex: RuntimeException): SyncFailure =
        SyncFailure(
            kind = SyncFailureKind.LOCAL_VALIDATION_FAILED,
            message = ex.message ?: ex.javaClass.simpleName,
            retryable = false,
            causeClass = ex.javaClass.name,
        )

    private fun requeueFor(pageData: PageView<R, RID, KEY>, outcomes: List<SyncOutcome<RID>>): RequeueDecision {
        val retryAfter =
            pageData.page.retryAfter
                ?: outcomes.filterIsInstance<SyncOutcome.Failed<RID>>()
                    .firstNotNullOfOrNull { it.failure.retryAfter }
        return when {
            pageData.partialFailure != null ->
                RequeueDecision.Later(retryAfter ?: DEFAULT_REQUEUE_DELAY, "partial remote page")
            outcomes.any { it is SyncOutcome.Failed && it.failure.retryable } ->
                RequeueDecision.Later(retryAfter ?: DEFAULT_REQUEUE_DELAY, "retryable record failure")
            else -> RequeueDecision.Done
        }
    }

    private fun finishFailed(context: SyncContext<SCOPE>, startedAt: Instant, failure: SyncFailure): SyncReport {
        val outcome =
            SyncOutcome.Failed<RID>(
                subject = scopeSubject(context),
                action = null,
                duration = Duration.between(startedAt, Instant.now(clock)),
                failure = failure,
            )
        ports.observer.onOutcome(context, outcome)
        val report =
            report(
                context,
                startedAt,
                listOf(outcome),
                RequeueDecision.Later(failure.retryAfter ?: DEFAULT_REQUEUE_DELAY, "remote fetch failed"),
            )
        ports.observer.onRunCompleted(report)
        ports.auditTrail.recordRunCompleted(report)
        return report
    }

    private fun finishEmpty(
        context: SyncContext<SCOPE>,
        startedAt: Instant,
        fetch: RemoteFetch.Missing,
    ): SyncReport {
        val outcome =
            SyncOutcome.Skipped<RID>(
                subject = scopeSubject(context),
                action = null,
                duration = Duration.between(startedAt, Instant.now(clock)),
                reason = com.jorisjonkers.personalstack.common.sync.domain.SyncReason.Policy(
                    "remote scope missing (${fetch.reason}); not treating as authoritative empty set",
                ),
            )
        ports.observer.onOutcome(context, outcome)
        val report = report(context, startedAt, listOf(outcome), RequeueDecision.Done)
        ports.observer.onRunCompleted(report)
        ports.auditTrail.recordRunCompleted(report)
        return report
    }

    private fun scopeSubject(context: SyncContext<SCOPE>): SyncSubject<RID> =
        context.scope?.let {
            SyncSubject.Scope(com.jorisjonkers.personalstack.common.sync.domain.ScopeId(it.toString()))
        } ?: SyncSubject.Unknown

    private fun report(
        context: SyncContext<SCOPE>,
        startedAt: Instant,
        outcomes: List<SyncOutcome<RID>>,
        requeue: RequeueDecision,
    ): SyncReport =
        SyncReport(
            context = context,
            status = SyncReportStatuses.of(outcomes),
            startedAt = startedAt,
            completedAt = Instant.now(clock),
            outcomes = outcomes,
            requeue = requeue,
        )

    private data class PageView<R : Any, RID : Any, KEY : Any>(
        val page: RemotePage<R, RID, KEY>,
        val partialFailure: SyncFailure?,
        val authoritative: Boolean,
    )

    companion object {
        private val DEFAULT_REQUEUE_DELAY: Duration = Duration.ofMinutes(1)
    }
}
