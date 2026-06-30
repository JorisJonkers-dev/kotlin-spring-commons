package com.jorisjonkers.personalstack.common.sync.application.service

import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncEntityCommand
import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncEntityUseCase
import com.jorisjonkers.personalstack.common.sync.domain.BaselineSnapshot
import com.jorisjonkers.personalstack.common.sync.domain.Reconciliation
import com.jorisjonkers.personalstack.common.sync.domain.RemoteFetch
import com.jorisjonkers.personalstack.common.sync.domain.RemoteRecord
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
import com.jorisjonkers.personalstack.common.sync.domain.RequeueDecision
import com.jorisjonkers.personalstack.common.sync.domain.RunId
import com.jorisjonkers.personalstack.common.sync.domain.port.out.IdempotencyClaim
import com.jorisjonkers.personalstack.common.sync.domain.port.out.LockResult
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncTransactionContext
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

    override fun sync(command: SyncEntityCommand<RID>): SyncReport {
        val startedAt = Instant.now(clock)
        val context =
            SyncContext<SCOPE>(
                runId = RunId.new(),
                syncName = syncName,
                correlationId = command.correlationId,
                source = command.source,
                scope = null,
                startedAt = startedAt,
                dryRun = command.dryRun,
            )

        ports.observer.onRunStarted(context)
        ports.auditTrail.recordRunStarted(context)

        // Idempotency: replay completed reports, reject in-progress claims.
        command.idempotencyKey?.let { key ->
            when (val claim = ports.idempotencyStore.claim(key, IDEMPOTENCY_TTL, context)) {
                is IdempotencyClaim.Acquired -> Unit
                is IdempotencyClaim.Completed -> {
                    ports.observer.onRunCompleted(claim.report)
                    return claim.report
                }
                is IdempotencyClaim.InProgress -> {
                    val failure =
                        SyncFailure(
                            kind = SyncFailureKind.IDEMPOTENCY_IN_PROGRESS,
                            message = claim.message,
                            retryable = true,
                        )
                    return finishFailed(context, startedAt, failure, command.idempotencyKey)
                }
            }
        }

        // Fetch remote OUTSIDE the transaction.
        val fetch = ports.remoteCatalog.fetchOne(command.externalId, context)
        val remoteRecord: RemoteRecord<R, RID, KEY>? =
            when (fetch) {
                is RemoteFetch.Found -> fetch.value
                // NOTE: a Partial single-record fetch carries a usable value; we sync it but never delete from it.
                is RemoteFetch.Partial -> fetch.value
                is RemoteFetch.Missing -> null // authoritative absence -> reconcile handles delete/unlink
                is RemoteFetch.Failed -> {
                    // A failed remote fetch must NEVER become a delete; report and requeue.
                    return finishFailed(context, startedAt, fetch.failure, command.idempotencyKey)
                }
            }
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

            val decision: SyncDecision<A, R, RID> =
                reconciliation.reconcileOne(localAggregate, remoteRecord?.record, observedAt)

            executeDecision(context, decision, this)
        }

    /**
     * Applies one decision through the mapper/repository and records its outcome. Honours dry-run by
     * skipping all writes (save, baseline, effects, audit-outcome, relay registration).
     */
    @Suppress("UNCHECKED_CAST")
    private fun executeDecision(
        context: SyncContext<SCOPE>,
        decision: SyncDecision<A, R, RID>,
        tx: SyncTransactionContext,
    ): SyncOutcome<RID> {
        val start = Instant.now(clock)
        val mapper = definition.mapper
        val dryRun = context.dryRun

        fun durationNow(): Duration = Duration.between(start, Instant.now(clock))

        fun succeeded(): SyncOutcome<RID> =
            SyncOutcome.Succeeded(decision.subject, decision.action, durationNow())

        fun skipped(): SyncOutcome<RID> =
            SyncOutcome.Skipped(decision.subject, decision.action, durationNow(), decision.reason)

        // Non-executable decisions never write.
        if (!decision.executable) {
            val outcome =
                if (decision is SyncDecision.Retry) {
                    SyncOutcome.Failed(decision.subject, decision.action, durationNow(), decision.failure)
                } else {
                    skipped()
                }
            recordOutcome(context, outcome, tx)
            return outcome
        }

        if (dryRun) {
            // Reconciliation already ran; report the planned decision without mutation.
            val outcome = skipped()
            // No audit-outcome write, no effects, no relay registration in dry-run.
            ports.observer.onOutcome(context, outcome)
            return outcome
        }

        val saved: A? =
            when (decision) {
                is SyncDecision.Import<*, *, *> -> {
                    val d = decision as SyncDecision.Import<R, RID, KEY>
                    mapper.create(d.remote.record, context).let { ports.localRepository.save(it, context) }
                }
                is SyncDecision.Update<*, *, *, *> -> {
                    val d = decision as SyncDecision.Update<A, R, RID, KEY>
                    mapper.update(d.local.aggregate, d.remote.record, d.changes, context)
                        .let { ports.localRepository.save(it, context) }
                }
                is SyncDecision.Restore<*, *, *, *> -> {
                    val d = decision as SyncDecision.Restore<A, R, RID, KEY>
                    mapper.restore(d.local.aggregate, d.remote.record, d.changes, context)
                        .let { ports.localRepository.save(it, context) }
                }
                is SyncDecision.Relink<*, *, *, *> -> {
                    val d = decision as SyncDecision.Relink<A, R, RID, KEY>
                    mapper.relink(d.local.aggregate, d.remote.record, d.changes, context)
                        .let { ports.localRepository.save(it, context) }
                }
                is SyncDecision.Delete<*, *, *> -> {
                    val d = decision as SyncDecision.Delete<A, RID, KEY>
                    mapper.delete(d.local.aggregate, d.signal, context)
                        .let { ports.localRepository.save(it, context) }
                }
                is SyncDecision.Unlink<*, *, *> -> {
                    val d = decision as SyncDecision.Unlink<A, RID, KEY>
                    mapper.unlink(d.local.aggregate, d.unlinkReason, context)
                        .let { ports.localRepository.save(it, context) }
                }
                else -> null
            }

        // Save baseline for record-mutating actions (not Unlink, which has no remote baseline).
        if (saved != null) {
            val remoteForBaseline: RemoteRecord<R, RID, KEY>? =
                when (decision) {
                    is SyncDecision.Import<*, *, *> -> (decision as SyncDecision.Import<R, RID, KEY>).remote
                    is SyncDecision.Update<*, *, *, *> -> (decision as SyncDecision.Update<A, R, RID, KEY>).remote
                    is SyncDecision.Restore<*, *, *, *> -> (decision as SyncDecision.Restore<A, R, RID, KEY>).remote
                    is SyncDecision.Relink<*, *, *, *> -> (decision as SyncDecision.Relink<A, R, RID, KEY>).remote
                    else -> null
                }
            if (remoteForBaseline != null) {
                ports.checkpointStore.saveBaseline(
                    syncName,
                    decision.subject,
                    BaselineSnapshot(
                        version = remoteForBaseline.version,
                        fingerprint = null,
                        capturedAt = Instant.now(clock),
                    ),
                )
            }
        }

        // Persist pure effects atomically inside the transaction.
        if (decision.effects.isNotEmpty()) {
            ports.effectOutbox.append(context, decision.effects)
        }

        val outcome = succeeded()
        recordOutcome(context, outcome, tx)

        // Relay durable effects only AFTER commit.
        if (decision.effects.isNotEmpty()) {
            tx.afterCommit { ports.effectOutbox.requestRelay(context) }
        }
        return outcome
    }

    private fun recordOutcome(
        context: SyncContext<SCOPE>,
        outcome: SyncOutcome<RID>,
        @Suppress("UNUSED_PARAMETER") tx: SyncTransactionContext,
    ) {
        ports.auditTrail.recordOutcome(context, outcome)
        ports.observer.onOutcome(context, outcome)
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

    private fun finalizeIdempotency(
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
                    outcomes.filterIsInstance<SyncOutcome.Failed<RID>>()
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
}
