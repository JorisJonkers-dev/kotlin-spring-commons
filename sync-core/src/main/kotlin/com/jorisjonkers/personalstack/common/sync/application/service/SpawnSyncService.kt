package com.jorisjonkers.personalstack.common.sync.application.service

import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SpawnSyncCommand
import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SpawnSyncUseCase
import com.jorisjonkers.personalstack.common.sync.domain.CursorCheckpoint
import com.jorisjonkers.personalstack.common.sync.domain.RemoteChange
import com.jorisjonkers.personalstack.common.sync.domain.RemoteFetch
import com.jorisjonkers.personalstack.common.sync.domain.RemotePage
import com.jorisjonkers.personalstack.common.sync.domain.RequeueDecision
import com.jorisjonkers.personalstack.common.sync.domain.RunId
import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncCursor
import com.jorisjonkers.personalstack.common.sync.domain.SyncDefinition
import com.jorisjonkers.personalstack.common.sync.domain.SyncEffect
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
 * Application service for paged backfill / incremental discovery. Implements [SpawnSyncUseCase].
 *
 * Coordinates discovery only: it converts each remote page change into a
 * [SyncEffect.EnqueueEntitySync] effect (and, when `fullSync`, also enqueues linked local remote ids
 * the page did not mention), persists those enqueue effects together with cursor advancement
 * atomically, and advances the cursor (compare-and-swap) only after the enqueue effects are durable.
 *
 * It does NOT call [com.jorisjonkers.personalstack.common.sync.application.port.in.SyncEntityUseCase]
 * directly inside the transaction; per-entity work is dispatched via the relayed effects after commit.
 * Returns a [SyncReport] with [RequeueDecision.Later] when a next cursor, retry hint, or partial
 * failure requires continuation.
 *
 * Keeps named helpers for each discovery phase so cursor, lock, transaction and relay boundaries
 * stay explicit.
 */
@Suppress("TooManyFunctions")
class SpawnSyncService<R : Any, RID : Any, KEY : Any, SCOPE : Any>(
    private val definition: SyncDefinition<*, R, RID, KEY, SCOPE>,
    private val clock: Clock = Clock.systemUTC(),
) : SpawnSyncUseCase<SCOPE> {
    private val ports = definition.ports
    private val syncName: SyncName = definition.name

    override fun spawn(command: SpawnSyncCommand<SCOPE>): SyncReport {
        val startedAt = Instant.now(clock)
        val context = context(command, startedAt)

        ports.observer.onRunStarted(context)
        ports.auditTrail.recordRunStarted(context)

        val pageSize = command.pageSize ?: definition.execution.pageSize
        val checkpoint: CursorCheckpoint? = ports.checkpointStore.loadCursor(syncName, command.scope)
        val fromCursor: SyncCursor? = command.cursor ?: checkpoint?.cursor
        val report =
            fetchPage(command, context, startedAt, fromCursor, pageSize).let { read ->
                read.failureReport
                    ?: executePage(command, context, startedAt, pageSize, checkpoint, requireNotNull(read.pageView))
            }
        return report
    }

    private fun context(
        command: SpawnSyncCommand<SCOPE>,
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
        command: SpawnSyncCommand<SCOPE>,
        context: SyncContext<SCOPE>,
        startedAt: Instant,
        fromCursor: SyncCursor?,
        pageSize: Int,
    ): SpawnPageRead<R, RID, KEY> =
        when (val fetch = ports.remoteCatalog.fetchPage(command.scope, fromCursor, pageSize, context)) {
            is RemoteFetch.Found -> SpawnPageRead(PageView(fetch.value, partialFailure = null))
            is RemoteFetch.Partial -> SpawnPageRead(PageView(fetch.value, partialFailure = fetch.failure))
            is RemoteFetch.Missing ->
                SpawnPageRead(
                    failureReport = finishSkipped(context, startedAt, "remote page missing (${fetch.reason})"),
                )
            is RemoteFetch.Failed -> SpawnPageRead(failureReport = finishFailed(context, startedAt, fetch.failure))
        }

    private fun executePage(
        command: SpawnSyncCommand<SCOPE>,
        context: SyncContext<SCOPE>,
        startedAt: Instant,
        pageSize: Int,
        checkpoint: CursorCheckpoint?,
        pageView: PageView<R, RID, KEY>,
    ): SyncReport {
        val effects = enqueueEffects(pageView.page)
        if (command.fullSync) {
            appendUnseenLocalEffects(command, context, pageSize, pageView.page, effects)
        }
        return if (command.dryRun) {
            finishDryRun(context, command, startedAt, effects.size, pageView)
        } else {
            persistPage(context, command, startedAt, checkpoint, pageView, effects)
        }
    }

    private fun enqueueEffects(page: RemotePage<R, RID, KEY>): MutableList<SyncEffect> =
        page.changes
            .mapTo(mutableListOf()) { change ->
                val externalId = externalIdOf(change)
                SyncEffect.EnqueueEntitySync(
                    key = "enqueue:${syncName.value}:$externalId",
                    syncName = syncName,
                    externalId = externalId,
                )
            }

    private fun appendUnseenLocalEffects(
        command: SpawnSyncCommand<SCOPE>,
        context: SyncContext<SCOPE>,
        pageSize: Int,
        page: RemotePage<R, RID, KEY>,
        effects: MutableList<SyncEffect>,
    ) {
        val seen = page.changes.map { externalIdOf(it).toString() }.toSet()
        var localCursor: SyncCursor? = null
        do {
            val localPage = ports.localRepository.listLinkedRemoteIds(command.scope, localCursor, pageSize, context)
            localPage.ids
                .filter { it.toString() !in seen }
                .mapTo(effects) { rid ->
                    SyncEffect.EnqueueEntitySync(
                        key = "enqueue:${syncName.value}:$rid",
                        syncName = syncName,
                        externalId = rid,
                    )
                }
            localCursor = localPage.nextCursor
        } while (localCursor != null)
    }

    private fun externalIdOf(change: RemoteChange<R, RID, KEY>): RID =
        when (change) {
            is RemoteChange.Upsert<R, RID, KEY> -> change.remote.externalId
            is RemoteChange.Delete<RID> -> change.signal.remoteId
        }

    private fun finishDryRun(
        context: SyncContext<SCOPE>,
        command: SpawnSyncCommand<SCOPE>,
        startedAt: Instant,
        enqueued: Int,
        pageView: PageView<R, RID, KEY>,
    ): SyncReport {
        val outcome = spawnOutcome(context, command, enqueued, startedAt, executed = false)
        ports.observer.onOutcome(context, outcome)
        val report = report(context, startedAt, listOf(outcome), requeueFor(pageView))
        ports.observer.onRunCompleted(report)
        ports.auditTrail.recordRunCompleted(report)
        return report
    }

    private fun persistPage(
        context: SyncContext<SCOPE>,
        command: SpawnSyncCommand<SCOPE>,
        startedAt: Instant,
        checkpoint: CursorCheckpoint?,
        pageView: PageView<R, RID, KEY>,
        effects: List<SyncEffect>,
    ): SyncReport {
        val nextCheckpoint =
            CursorCheckpoint(
                syncName = syncName,
                scope = command.scope,
                cursor = pageView.page.nextCursor,
                highWatermark = pageView.page.highWatermark ?: checkpoint?.highWatermark,
                updatedAt = Instant.now(clock),
                runId = context.runId,
            )
        val lockKey = SyncLockKey("${syncName.value}:spawn:${command.scope}")
        val lockResult =
            ports.lockManager.withLock(lockKey, definition.execution.lockTimeout) {
                runTransaction(context, command, startedAt, checkpoint, nextCheckpoint, pageView, effects)
            }
        val txResult =
            when (lockResult) {
                is LockResult.Acquired -> lockResult.value
                is LockResult.NotAcquired -> SpawnTxResult(lockUnavailableOutcome(context, startedAt, lockKey))
            }
        val requeue =
            when {
                txResult.outcome is SyncOutcome.Failed ->
                    RequeueDecision.Later(
                        definition.execution.lockTimeout,
                        "spawn could not progress",
                    )
                else -> requeueFor(pageView)
            }
        val report = report(context, startedAt, listOf(txResult.outcome), requeue)
        ports.observer.onRunCompleted(report)
        ports.auditTrail.recordRunCompleted(report)
        return report
    }

    private fun runTransaction(
        context: SyncContext<SCOPE>,
        command: SpawnSyncCommand<SCOPE>,
        startedAt: Instant,
        checkpoint: CursorCheckpoint?,
        nextCheckpoint: CursorCheckpoint,
        pageView: PageView<R, RID, KEY>,
        effects: List<SyncEffect>,
    ): SpawnTxResult<RID> =
        ports.unitOfWork.transaction {
            if (effects.isNotEmpty()) {
                ports.effectOutbox.append(context, effects)
            }
            val advanced =
                pageView.partialFailure == null &&
                    ports.checkpointStore.saveCursorIfCurrent(checkpoint, nextCheckpoint)
            val outcome = spawnOutcome(context, command, effects.size, startedAt, executed = true, advanced = advanced)
            ports.auditTrail.recordOutcome(context, outcome)
            ports.observer.onOutcome(context, outcome)
            if (effects.isNotEmpty()) {
                afterCommit { ports.effectOutbox.requestRelay(context) }
            }
            SpawnTxResult(outcome, advanced)
        }

    private fun lockUnavailableOutcome(
        context: SyncContext<SCOPE>,
        startedAt: Instant,
        lockKey: SyncLockKey,
    ): SyncOutcome<RID> =
        SyncOutcome.Failed(
            subject = scopeSubject(context),
            action = null,
            duration = Duration.between(startedAt, Instant.now(clock)),
            failure =
                SyncFailure(
                    kind = SyncFailureKind.LOCK_UNAVAILABLE,
                    message = "could not acquire spawn lock $lockKey",
                    retryable = true,
                    retryAfter = definition.execution.lockTimeout,
                ),
        )

    private fun requeueFor(pageView: PageView<R, RID, KEY>): RequeueDecision {
        val page = pageView.page
        val needsContinuation = page.nextCursor != null || page.retryAfter != null || pageView.partialFailure != null
        return if (needsContinuation) {
            RequeueDecision.Later(
                page.retryAfter ?: DEFAULT_REQUEUE_DELAY,
                when {
                    pageView.partialFailure != null -> "partial remote page"
                    page.nextCursor != null -> "more pages remain"
                    else -> "remote retry hint"
                },
            )
        } else {
            RequeueDecision.Done
        }
    }

    private fun spawnOutcome(
        context: SyncContext<SCOPE>,
        command: SpawnSyncCommand<SCOPE>,
        enqueued: Int,
        startedAt: Instant,
        executed: Boolean,
        advanced: Boolean = false,
    ): SyncOutcome<RID> {
        val action = com.jorisjonkers.personalstack.common.sync.domain.SyncAction.IGNORE
        return SyncOutcome.Skipped(
            subject = scopeSubject(context),
            action = action,
            duration = Duration.between(startedAt, Instant.now(clock)),
            reason =
                com.jorisjonkers.personalstack.common.sync.domain.SyncReason.Policy(
                    "spawn enqueued=$enqueued executed=$executed cursorAdvanced=$advanced fullSync=${command.fullSync}",
                ),
        )
    }

    private fun finishFailed(
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
                RequeueDecision.Later(failure.retryAfter ?: DEFAULT_REQUEUE_DELAY, "remote page fetch failed"),
            )
        ports.observer.onRunCompleted(report)
        ports.auditTrail.recordRunCompleted(report)
        return report
    }

    private fun finishSkipped(
        context: SyncContext<SCOPE>,
        startedAt: Instant,
        message: String,
    ): SyncReport {
        val outcome =
            SyncOutcome.Skipped<RID>(
                subject = scopeSubject(context),
                action = null,
                duration = Duration.between(startedAt, Instant.now(clock)),
                reason =
                    com.jorisjonkers.personalstack.common.sync.domain.SyncReason
                        .Policy(message),
            )
        ports.observer.onOutcome(context, outcome)
        val report = report(context, startedAt, listOf(outcome), RequeueDecision.Done)
        ports.observer.onRunCompleted(report)
        ports.auditTrail.recordRunCompleted(report)
        return report
    }

    private fun scopeSubject(context: SyncContext<SCOPE>?): SyncSubject<RID> {
        val scope = context?.scope
        return if (scope != null) {
            SyncSubject.Scope(
                com.jorisjonkers.personalstack.common.sync.domain
                    .ScopeId(scope.toString()),
            )
        } else {
            SyncSubject.Unknown
        }
    }

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
    )

    private data class SpawnPageRead<R : Any, RID : Any, KEY : Any>(
        val pageView: PageView<R, RID, KEY>? = null,
        val failureReport: SyncReport? = null,
    )

    private data class SpawnTxResult<RID : Any>(
        val outcome: SyncOutcome<RID>,
        val advanced: Boolean = false,
    )

    companion object {
        private val DEFAULT_REQUEUE_DELAY: Duration = Duration.ofMinutes(1)
    }
}
