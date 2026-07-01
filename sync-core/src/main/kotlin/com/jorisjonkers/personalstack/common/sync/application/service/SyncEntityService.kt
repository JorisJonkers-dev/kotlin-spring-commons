package com.jorisjonkers.personalstack.common.sync.application.service

import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncEntityCommand
import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncEntityUseCase
import com.jorisjonkers.personalstack.common.sync.domain.Reconciliation
import com.jorisjonkers.personalstack.common.sync.domain.RemoteFetch
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
import com.jorisjonkers.personalstack.common.sync.domain.SyncReportStatus
import com.jorisjonkers.personalstack.common.sync.domain.port.out.IdempotencyClaim
import com.jorisjonkers.personalstack.common.sync.domain.port.out.LockResult
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Application service for single-entity sync. Implements [SyncEntityUseCase].
 *
 * Flow (per spec section 5): build context -> claim idempotency -> fetch remote OUTSIDE the tx ->
 * (Failed never becomes a missing/delete) -> acquire entity lock -> open unit-of-work -> re-read
 * local INSIDE the lock/tx -> pure [Reconciliation.reconcileOne] -> execute one decision through the
 * mapper/repository -> save baseline -> append effects -> audit -> register after-commit relay ->
 * complete idempotency -> return [SyncReport].
 *
 * Pure domain only; constructor injection, no annotations.
 */
class SyncEntityService<A : Any, R : Any, RID : Any, KEY : Any, SCOPE : Any>(
    private val definition: SyncDefinition<A, R, RID, KEY, SCOPE>,
    private val clock: Clock = Clock.systemUTC(),
) : SyncEntityUseCase<RID> {
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
    private val executor = SyncDecisionExecutor(definition, clock, syncName)

    override fun sync(command: SyncEntityCommand<RID>): SyncReport {
        val startedAt = Instant.now(clock)
        val context = context(command, startedAt)

        ports.observer.onRunStarted(context)
        ports.auditTrail.recordRunStarted(context)

        val report =
            claimIdempotency(command, context, startedAt)
                ?: fetchRemote(command, context, startedAt).let { read ->
                    read.failureReport ?: executeRemoteRead(command, context, startedAt, read.remoteRecord)
                }
        return report
    }

    private fun context(
        command: SyncEntityCommand<RID>,
        startedAt: Instant,
    ): SyncContext<SCOPE> =
        SyncContext(
            runId = RunId.new(),
            syncName = syncName,
            correlationId = command.correlationId,
            source = command.source,
            scope = null,
            startedAt = startedAt,
            dryRun = command.dryRun,
        )

    private fun claimIdempotency(
        command: SyncEntityCommand<RID>,
        context: SyncContext<SCOPE>,
        startedAt: Instant,
    ): SyncReport? =
        command.idempotencyKey?.let { key ->
            when (val claim = ports.idempotencyStore.claim(key, IDEMPOTENCY_TTL, context)) {
                is IdempotencyClaim.Acquired -> null
                is IdempotencyClaim.Completed -> {
                    ports.observer.onRunCompleted(claim.report)
                    claim.report
                }
                is IdempotencyClaim.InProgress ->
                    finishFailed(
                        context,
                        startedAt,
                        SyncFailure(
                            kind = SyncFailureKind.IDEMPOTENCY_IN_PROGRESS,
                            message = claim.message,
                            retryable = true,
                        ),
                        key,
                    )
            }
        }

    private fun fetchRemote(
        command: SyncEntityCommand<RID>,
        context: SyncContext<SCOPE>,
        startedAt: Instant,
    ): EntityRemoteRead<R, RID, KEY> =
        when (val fetch = ports.remoteCatalog.fetchOne(command.externalId, context)) {
            is RemoteFetch.Found -> EntityRemoteRead(fetch.value)
            // NOTE: a Partial single-record fetch carries a usable value; we sync it but never delete from it.
            is RemoteFetch.Partial -> EntityRemoteRead(fetch.value)
            is RemoteFetch.Missing -> EntityRemoteRead(null)
            is RemoteFetch.Failed ->
                EntityRemoteRead(
                    failureReport = finishFailed(context, startedAt, fetch.failure, command.idempotencyKey),
                )
        }

    private fun executeRemoteRead(
        command: SyncEntityCommand<RID>,
        context: SyncContext<SCOPE>,
        startedAt: Instant,
        remoteRecord: RemoteRecord<R, RID, KEY>?,
    ): SyncReport {
        val observedAt = remoteRecord?.observedAt ?: Instant.now(clock)

        val lockKey = SyncLockKey("${syncName.value}:entity:${command.externalId}")
        val lockResult =
            ports.lockManager.withLock(lockKey, definition.execution.lockTimeout) {
                executeInTransaction(context, command, remoteRecord, observedAt)
            }

        val outcome: SyncOutcome<RID> =
            when (lockResult) {
                is LockResult.Acquired -> lockResult.value
                is LockResult.NotAcquired ->
                    SyncOutcome.Failed(
                        subject = subjectFor(command, remoteRecord),
                        action = null,
                        duration = Duration.between(startedAt, Instant.now(clock)),
                        failure =
                            SyncFailure(
                                kind = SyncFailureKind.LOCK_UNAVAILABLE,
                                message = "could not acquire lock $lockKey within ${definition.execution.lockTimeout}",
                                retryable = true,
                                retryAfter = definition.execution.lockTimeout,
                            ),
                    )
            }

        val report = report(context, startedAt, listOf(outcome))
        finalizeIdempotency(command.idempotencyKey, report, outcome)
        ports.observer.onRunCompleted(report)
        ports.auditTrail.recordRunCompleted(report)
        return report
    }

    private fun executeInTransaction(
        context: SyncContext<SCOPE>,
        command: SyncEntityCommand<RID>,
        remoteRecord: RemoteRecord<R, RID, KEY>?,
        observedAt: Instant,
    ): SyncOutcome<RID> =
        ports.unitOfWork.transaction {
            // Re-read local state inside the lock/transaction (stale-plan / TOCTOU guard).
            val localAggregate =
                ports.localRepository.findByRemoteIdIncludingDeleted(command.externalId, context.scope, context)

            val decision: SyncDecision<A, R, RID, KEY> =
                reconciliation.reconcileOne(localAggregate, remoteRecord?.record, observedAt)

            executor.execute(context, decision, this)
        }

    private fun subjectFor(
        command: SyncEntityCommand<RID>,
        remoteRecord: RemoteRecord<R, RID, KEY>?,
    ) = com.jorisjonkers.personalstack.common.sync.domain.SyncSubject.Remote(
        remoteRecord?.externalId ?: command.externalId,
    )

    private fun finishFailed(
        context: SyncContext<SCOPE>,
        startedAt: Instant,
        failure: SyncFailure,
        idempotencyKey: com.jorisjonkers.personalstack.common.sync.domain.IdempotencyKey?,
    ): SyncReport {
        val outcome =
            SyncOutcome.Failed<RID>(
                subject = com.jorisjonkers.personalstack.common.sync.domain.SyncSubject.Unknown,
                action = null,
                duration = Duration.between(startedAt, Instant.now(clock)),
                failure = failure,
            )
        ports.observer.onOutcome(context, outcome)
        val report = report(context, startedAt, listOf(outcome))
        idempotencyKey?.let { ports.idempotencyStore.fail(it, failure) }
        ports.observer.onRunCompleted(report)
        ports.auditTrail.recordRunCompleted(report)
        return report
    }

    // internal (not private) so the defensive Failed-outcome-with-non-failed-report branch,
    // which the single-outcome entity flow cannot itself produce, is directly unit-testable.
    internal fun finalizeIdempotency(
        key: com.jorisjonkers.personalstack.common.sync.domain.IdempotencyKey?,
        report: SyncReport,
        outcome: SyncOutcome<RID>,
    ) {
        if (key == null) return
        when (outcome) {
            is SyncOutcome.Failed ->
                if (report.status == SyncReportStatus.SUCCEEDED || report.status == SyncReportStatus.PARTIAL) {
                    ports.idempotencyStore.complete(key, report)
                } else {
                    ports.idempotencyStore.fail(key, outcome.failure)
                }
            else -> ports.idempotencyStore.complete(key, report)
        }
    }

    private fun report(
        context: SyncContext<SCOPE>,
        startedAt: Instant,
        outcomes: List<SyncOutcome<RID>>,
    ): SyncReport {
        val status = SyncReportStatuses.of(outcomes)
        val requeue =
            if (outcomes.any { it is SyncOutcome.Failed && (it.failure.retryable) }) {
                val retryAfter =
                    outcomes
                        .filterIsInstance<SyncOutcome.Failed<RID>>()
                        .firstNotNullOfOrNull { it.failure.retryAfter }
                        ?: DEFAULT_REQUEUE_DELAY
                RequeueDecision.Later(retryAfter, "retryable failure")
            } else {
                RequeueDecision.Done
            }
        return SyncReport(
            context = context,
            status = status,
            startedAt = startedAt,
            completedAt = Instant.now(clock),
            outcomes = outcomes,
            requeue = requeue,
        )
    }

    companion object {
        private val IDEMPOTENCY_TTL: Duration = Duration.ofHours(1)
        private val DEFAULT_REQUEUE_DELAY: Duration = Duration.ofMinutes(1)
    }

    private data class EntityRemoteRead<R : Any, RID : Any, KEY : Any>(
        val remoteRecord: RemoteRecord<R, RID, KEY>? = null,
        val failureReport: SyncReport? = null,
    )
}
