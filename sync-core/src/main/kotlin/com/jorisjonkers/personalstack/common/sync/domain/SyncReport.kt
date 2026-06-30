package com.jorisjonkers.personalstack.common.sync.domain

import java.time.Duration
import java.time.Instant

/** Overall status of a completed run; the single summary value callers branch on. */
enum class SyncReportStatus {
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CONFLICTED,
    SKIPPED,
}

/** Whether a run wants to be continued, and after how long (e.g. paging, rate-limit, partial). */
sealed interface RequeueDecision {
    data object Done : RequeueDecision
    data class Later(val delay: Duration, val reason: String) : RequeueDecision
}

/** The result of acting on a single subject within a run. */
sealed interface SyncOutcome<out RID : Any> {
    val subject: SyncSubject<RID>
    val action: SyncAction?
    val duration: Duration

    data class Succeeded<RID : Any>(
        override val subject: SyncSubject<RID>,
        override val action: SyncAction,
        override val duration: Duration,
    ) : SyncOutcome<RID>

    data class Skipped<RID : Any>(
        override val subject: SyncSubject<RID>,
        override val action: SyncAction?,
        override val duration: Duration,
        val reason: SyncReason,
    ) : SyncOutcome<RID>

    data class Failed<RID : Any>(
        override val subject: SyncSubject<RID>,
        override val action: SyncAction?,
        override val duration: Duration,
        val failure: SyncFailure,
    ) : SyncOutcome<RID>
}

/**
 * The single return type of every sync use case: the run context, its overall status, timing,
 * per-subject outcomes and a requeue hint. Always returned, even on failure.
 */
data class SyncReport(
    val context: SyncContext<*>,
    val status: SyncReportStatus,
    val startedAt: Instant,
    val completedAt: Instant,
    val outcomes: List<SyncOutcome<*>>,
    val requeue: RequeueDecision = RequeueDecision.Done,
)
