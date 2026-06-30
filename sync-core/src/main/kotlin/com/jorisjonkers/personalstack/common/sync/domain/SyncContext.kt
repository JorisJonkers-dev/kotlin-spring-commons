package com.jorisjonkers.personalstack.common.sync.domain

import java.time.Instant

/**
 * Ambient information about a single sync run, threaded through ports and the mapper.
 * It is pure data: identity, correlation, trigger origin, optional scope and timing.
 *
 * @param SCOPE the consumer's scope type (e.g. a department id); `null` for unscoped runs.
 */
data class SyncContext<SCOPE : Any>(
    val runId: RunId,
    val syncName: SyncName,
    val correlationId: CorrelationId,
    val source: SyncTriggerSource,
    val scope: SCOPE?,
    val startedAt: Instant,
    val dryRun: Boolean = false,
)

/** What triggered a sync run; bounded so it can be safely used as an observability tag. */
enum class SyncTriggerSource {
    ADMIN,
    MESSAGE,
    SCHEDULE,
    TEST,
    DIRECT,
}
