package com.jorisjonkers.personalstack.common.sync.application.service

import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncListCommand
import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncListUseCase
import com.jorisjonkers.personalstack.common.sync.domain.ListTransactionMode
import com.jorisjonkers.personalstack.common.sync.domain.Reconciliation
import com.jorisjonkers.personalstack.common.sync.domain.RemoteChange
import com.jorisjonkers.personalstack.common.sync.domain.RemoteFetch
import com.jorisjonkers.personalstack.common.sync.domain.RemotePage
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
 *
 * Keeps named helpers for each sync phase so transaction boundaries and callback timing stay explicit.
 */
class SyncListService<A : Any, R : Any, RID : Any, KEY : Any, SCOPE : Any>(
    private val definition: SyncDefinition<A, R, RID, KEY, SCOPE>,
    private val clock: Clock = Clock.systemUTC(),
) : SyncListUseCase<SCOPE> {
    private val ports = definition.ports
    private val syncName: SyncName = definition.name
    private val reporter = Reporter()
    private val refresher = DecisionRefresher()

    private val reconciliation =
        Reconciliation(
            localProjector = definition.localProjector,
            remoteProjector = definition.remoteProjector,
            differ = definition.differ,
            matchPlan = definition.matchPlan,
            policies = definition.policies,
        )
    private val executor = SyncDecisionExecutor(definition, clock, syncName)

    override fun sync(command: SyncListCommand<SCOPE>): SyncReport {
        val startedAt = Instant.now(clock)
        val context = context(command, startedAt)

        ports.observer.onRunStarted(context)
        ports.auditTrail.recordRunStarted(context)

        val report =
            fetchPage(command, context, startedAt).let { read ->
                read.failureReport ?: executePage(command, context, startedAt, requireNotNull(read.pageView))
            }
        return report
    }

    private fun context(
        command: SyncListCommand<SCOPE>,
        startedAt: Instant,
    ): SyncContext<SCOPE> =
        SyncContext(
            runId = RunId.new(),
            syncName = syncName,
            correlationId = command.correlationId,
            source = command.source,
            scope = command.scope,
            startedAt = startedAt,
            dryRun = command.dryRun,
        )

    private fun fetchPage(
        command: SyncListCommand<SCOPE>,
        context: SyncContext<SCOPE>,
        startedAt: Instant,
    ): PageRead<R, RID, KEY> =
        when (val fetch = ports.remoteCatalog.fetchForScope(command.scope, context)) {
            is RemoteFetch.Found -> PageRead(PageView(fetch.value, partialFailure = null))
            is RemoteFetch.Partial -> PageRead(PageView(fetch.value, partialFailure = fetch.failure))
            is RemoteFetch.Missing -> PageRead(failureReport = reporter.finishEmpty(context, startedAt, fetch))
            is RemoteFetch.Failed -> PageRead(failureReport = reporter.finishFailed(context, startedAt, fetch.failure))
        }

    private fun executePage(
        command: SyncListCommand<SCOPE>,
        context: SyncContext<SCOPE>,
        startedAt: Instant,
        pageData: PageView<R, RID, KEY>,
    ): SyncReport {
        val remotes: List<R> =
            pageData.page.changes.mapNotNull { change ->
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
                    -> runWholeScope(context, command, remotes)
                }
            }

        val outcomes: List<SyncOutcome<RID>> =
            when (lockResult) {
                is LockResult.Acquired -> lockResult.value
                is LockResult.NotAcquired ->
                    listOf(
                        reporter.lockUnavailableOutcome(command, startedAt, lockKey),
                    )
            }

        val requeue = reporter.requeueFor(pageData, outcomes)
        val report = reporter.report(context, startedAt, outcomes, requeue)
        ports.observer.onRunCompleted(report)
        ports.auditTrail.recordRunCompleted(report)
        return report
    }

    /**
     * Per-record default: re-read locals once inside the lock, plan, then commit each decision separately.
     * Runtime mapper/repository failures are isolated to one failed outcome.
     */
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
            runCatching {
                ports.unitOfWork.transaction {
                    // Stale-plan guard: when the decision references a remote id, re-read and re-reconcile.
                    val refreshed = refresher.refresh(context, decision, command)
                    executor.execute(context, refreshed, this)
                }
            }.getOrElse { ex ->
                val failure = localValidationFailure(ex)
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
    ): List<SyncOutcome<RID>> =
        ports.unitOfWork.transaction {
            val locals = ports.localRepository.listIncludingDeleted(command.scope, context)
            val plan = reconciliation.reconcileMany(locals, remotes, context)

            if (!plan.executable) {
                // Plan-level conflict: audit/report only, execute nothing.
                plan.decisions.map { decision ->
                    val outcome =
                        SyncOutcome.Skipped(decision.subject, decision.action, Duration.ZERO, decision.reason)
                    ports.auditTrail.recordOutcome(context, outcome)
                    ports.observer.onOutcome(context, outcome)
                    outcome
                }
            } else {
                // NOTE: in non-authoritative (partial) pages we still execute only present records;
                // absence-driven deletes are produced by reconcileMany only against authoritative locals.
                plan.decisions.map { decision -> executor.execute(context, decision, this) }
            }
        }

    private inner class DecisionRefresher {
        /** Re-read the subject's local aggregate by remote id and re-reconcile to guard against staleness. */
        fun refresh(
            context: SyncContext<SCOPE>,
            decision: SyncDecision<A, R, RID, KEY>,
            command: SyncListCommand<SCOPE>,
        ): SyncDecision<A, R, RID, KEY> {
            val remoteRecord = decision.remoteRecord
            val remoteId = remoteRecord?.externalId ?: decision.deleteSignal?.remoteId ?: return decision
            val local =
                ports.localRepository.findByRemoteIdIncludingDeleted(remoteId, command.scope, context)
            val observedAt = remoteRecord?.observedAt ?: Instant.now(clock)
            return reconciliation.reconcileOne(local, remoteRecord?.record, observedAt)
        }
    }

    private inner class Reporter {
        fun lockUnavailableOutcome(
            command: SyncListCommand<SCOPE>,
            startedAt: Instant,
            lockKey: SyncLockKey,
        ): SyncOutcome<RID> =
            SyncOutcome.Failed(
                subject =
                    SyncSubject.Scope(
                        com.jorisjonkers.personalstack.common.sync.domain
                            .ScopeId(command.scope.toString()),
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
            )

        fun requeueFor(
            pageData: PageView<R, RID, KEY>,
            outcomes: List<SyncOutcome<RID>>,
        ): RequeueDecision {
            val retryAfter =
                pageData.page.retryAfter
                    ?: outcomes
                        .filterIsInstance<SyncOutcome.Failed<RID>>()
                        .firstNotNullOfOrNull { it.failure.retryAfter }
            return when {
                pageData.partialFailure != null ->
                    RequeueDecision.Later(retryAfter ?: DEFAULT_REQUEUE_DELAY, "partial remote page")
                outcomes.any { it is SyncOutcome.Failed && it.failure.retryable } ->
                    RequeueDecision.Later(retryAfter ?: DEFAULT_REQUEUE_DELAY, "retryable record failure")
                else -> RequeueDecision.Done
            }
        }

        fun finishFailed(
            context: SyncContext<SCOPE>,
            startedAt: Instant,
            failure: SyncFailure,
        ): SyncReport {
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

        fun finishEmpty(
            context: SyncContext<SCOPE>,
            startedAt: Instant,
            fetch: RemoteFetch.Missing,
        ): SyncReport {
            val outcome =
                SyncOutcome.Skipped<RID>(
                    subject = scopeSubject(context),
                    action = null,
                    duration = Duration.between(startedAt, Instant.now(clock)),
                    reason =
                        com.jorisjonkers.personalstack.common.sync.domain.SyncReason.Policy(
                            "remote scope missing (${fetch.reason}); not treating as authoritative empty set",
                        ),
                )
            ports.observer.onOutcome(context, outcome)
            val report = report(context, startedAt, listOf(outcome), RequeueDecision.Done)
            ports.observer.onRunCompleted(report)
            ports.auditTrail.recordRunCompleted(report)
            return report
        }

        fun report(
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

        private fun scopeSubject(context: SyncContext<SCOPE>): SyncSubject<RID> =
            context.scope?.let {
                SyncSubject.Scope(
                    com.jorisjonkers.personalstack.common.sync.domain
                        .ScopeId(it.toString()),
                )
            } ?: SyncSubject.Unknown
    }

    private data class PageView<R : Any, RID : Any, KEY : Any>(
        val page: RemotePage<R, RID, KEY>,
        val partialFailure: SyncFailure?,
    )

    private data class PageRead<R : Any, RID : Any, KEY : Any>(
        val pageView: PageView<R, RID, KEY>? = null,
        val failureReport: SyncReport? = null,
    )

    companion object {
        private val DEFAULT_REQUEUE_DELAY: Duration = Duration.ofMinutes(1)
    }
}

private fun localValidationFailure(ex: Throwable): SyncFailure =
    SyncFailure(
        kind = SyncFailureKind.LOCAL_VALIDATION_FAILED,
        message = ex.message ?: ex.javaClass.simpleName,
        retryable = false,
        causeClass = ex.javaClass.name,
    )
