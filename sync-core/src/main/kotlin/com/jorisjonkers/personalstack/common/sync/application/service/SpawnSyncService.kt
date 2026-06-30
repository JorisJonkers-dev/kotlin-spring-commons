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
 */
class SpawnSyncService<R : Any, RID : Any, KEY : Any, SCOPE : Any>(
    private val definition: SyncDefinition<*, R, RID, KEY, SCOPE>,
    private val clock: Clock = Clock.systemUTC(),
) : SpawnSyncUseCase<SCOPE> {
    private val ports = definition.ports
    private val syncName: SyncName = definition.name

    override fun spawn(command: SpawnSyncCommand<SCOPE>): SyncReport {
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

        val pageSize = command.pageSize ?: definition.execution.pageSize

        // Load cursor checkpoint; an explicit command cursor overrides the stored one.
        val checkpoint: CursorCheckpoint? = ports.checkpointStore.loadCursor(syncName, command.scope)
        val fromCursor: SyncCursor? = command.cursor ?: checkpoint?.cursor

        // Fetch the page OUTSIDE the transaction.
        val fetch = ports.remoteCatalog.fetchPage(command.scope, fromCursor, pageSize, context)
        val pageView: PageView<R, RID, KEY> =
            when (fetch) {
                is RemoteFetch.Found -> PageView(fetch.value, partialFailure = null)
                is RemoteFetch.Partial -> PageView(fetch.value, partialFailure = fetch.failure)
                is RemoteFetch.Missing ->
                    return finishSkipped(context, startedAt, "remote page missing (${fetch.reason})")
                is RemoteFetch.Failed ->
                    return finishFailed(context, startedAt, fetch.failure)
            }
        val page = pageView.page

        // Build enqueue effects from page changes.
        val effects = mutableListOf<SyncEffect>()
        page.changes.forEach { change ->
            val externalId =
                when (change) {
                    is RemoteChange.Upsert<R, RID, KEY> -> change.remote.externalId
                    is RemoteChange.Delete<RID> -> change.signal.remoteId
                }
            effects.add(
                SyncEffect.EnqueueEntitySync(
                    key = "enqueue:${syncName.value}:$externalId",
                    syncName = syncName,
                    externalId = externalId,
                ),
            )
        }

        // Full sync: also enqueue linked local remote ids not present on the page.
        val seen: Set<String> = page.changes.map { change ->
            when (change) {
                is RemoteChange.Upsert<R, RID, KEY> -> change.remote.externalId.toString()
                is RemoteChange.Delete<RID> -> change.signal.remoteId.toString()
            }
        }.toSet()

        if (command.fullSync) {
            var localCursor: SyncCursor? = null
            do {
                val localPage = ports.localRepository.listLinkedRemoteIds(command.scope, localCursor, pageSize, context)
                localPage.ids.forEach { rid ->
                    if (rid.toString() !in seen) {
                        effects.add(
                            SyncEffect.EnqueueEntitySync(
                                key = "enqueue:${syncName.value}:$rid",
                                syncName = syncName,
                                externalId = rid,
                            ),
                        )
                    }
                }
                localCursor = localPage.nextCursor
            } while (localCursor != null)
        }

        // Dry run: no effects, no cursor advance.
        if (command.dryRun) {
            val outcome = spawnOutcome(context, command, effects.size, startedAt, executed = false)
            ports.observer.onOutcome(context, outcome)
            val report = report(context, startedAt, listOf(outcome), requeueFor(pageView))
            ports.observer.onRunCompleted(report)
            ports.auditTrail.recordRunCompleted(report)
            return report
        }

        // Persist enqueue effects AND advance the cursor atomically.
        val nextCheckpoint =
            CursorCheckpoint(
                syncName = syncName,
                scope = command.scope,
                cursor = page.nextCursor,
                highWatermark = page.highWatermark ?: checkpoint?.highWatermark,
                updatedAt = Instant.now(clock),
                runId = context.runId,
            )

        val lockKey = SyncLockKey("${syncName.value}:spawn:${command.scope}")
        val lockResult =
            ports.lockManager.withLock(lockKey, definition.execution.lockTimeout) {
                ports.unitOfWork.transaction {
                    if (effects.isNotEmpty()) {
                        ports.effectOutbox.append(context, effects)
                    }
                    // Advance cursor only after enqueue effects are appended (same tx -> durable together).
                    // Partial pages do not advance the cursor (we must re-fetch the same window).
                    val advanced =
                        if (pageView.partialFailure == null) {
                            ports.checkpointStore.saveCursorIfCurrent(checkpoint, nextCheckpoint)
                        } else {
                            false
                        }
                    val outcome = spawnOutcome(context, command, effects.size, startedAt, executed = true, advanced = advanced)
                    ports.auditTrail.recordOutcome(context, outcome)
                    ports.observer.onOutcome(context, outcome)
                    if (effects.isNotEmpty()) {
                        afterCommit { ports.effectOutbox.requestRelay(context) }
                    }
                    SpawnTxResult(outcome, advanced)
                }
            }

        val txResult =
            when (lockResult) {
                is LockResult.Acquired -> lockResult.value
                is LockResult.NotAcquired -> {
                    val outcome =
                        SyncOutcome.Failed<RID>(
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
                    SpawnTxResult(outcome, advanced = false)
                }
            }

        val requeue =
            when {
                txResult.outcome is SyncOutcome.Failed -> RequeueDecision.Later(
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
            reason = com.jorisjonkers.personalstack.common.sync.domain.SyncReason.Policy(
                "spawn enqueued=$enqueued executed=$executed cursorAdvanced=$advanced fullSync=${command.fullSync}",
            ),
        )
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
                RequeueDecision.Later(failure.retryAfter ?: DEFAULT_REQUEUE_DELAY, "remote page fetch failed"),
            )
        ports.observer.onRunCompleted(report)
        ports.auditTrail.recordRunCompleted(report)
        return report
    }

    private fun finishSkipped(context: SyncContext<SCOPE>, startedAt: Instant, message: String): SyncReport {
        val outcome =
            SyncOutcome.Skipped<RID>(
                subject = scopeSubject(context),
                action = null,
                duration = Duration.between(startedAt, Instant.now(clock)),
                reason = com.jorisjonkers.personalstack.common.sync.domain.SyncReason.Policy(message),
            )
        ports.observer.onOutcome(context, outcome)
        val report = report(context, startedAt, listOf(outcome), RequeueDecision.Done)
        ports.observer.onRunCompleted(report)
        ports.auditTrail.recordRunCompleted(report)
        return report
    }

    private fun scopeSubject(context: SyncContext<SCOPE>? = null): SyncSubject<RID> {
        val scope = context?.scope
        return if (scope != null) {
            SyncSubject.Scope(com.jorisjonkers.personalstack.common.sync.domain.ScopeId(scope.toString()))
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

    private data class SpawnTxResult<RID : Any>(
        val outcome: SyncOutcome<RID>,
        val advanced: Boolean,
    )

    companion object {
        private val DEFAULT_REQUEUE_DELAY: Duration = Duration.ofMinutes(1)
    }
}
