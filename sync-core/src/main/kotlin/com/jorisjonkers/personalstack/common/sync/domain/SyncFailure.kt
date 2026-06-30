package com.jorisjonkers.personalstack.common.sync.domain

import java.time.Duration

/**
 * Closed taxonomy of why a sync step could not complete cleanly. Distinguishes transient
 * remote problems (retryable) from structural data problems (conflicts) so callers can decide
 * between requeue and manual review. A remote outage must surface here, never as a delete.
 */
enum class SyncFailureKind {
    REMOTE_UNAVAILABLE,
    REMOTE_TIMEOUT,
    REMOTE_RATE_LIMITED,
    REMOTE_PARTIAL,
    CIRCUIT_OPEN,
    LOCK_UNAVAILABLE,
    DUPLICATE_LOCAL_REMOTE_ID,
    DUPLICATE_REMOTE_ID,
    AMBIGUOUS_MATCH,
    LINK_MISMATCH,
    VERSION_CONFLICT,
    MAPPING_REJECTED,
    LOCAL_VALIDATION_FAILED,
    CHECKPOINT_CONFLICT,
    IDEMPOTENCY_IN_PROGRESS,
    UNKNOWN,
}

/**
 * Pure value describing a failure. [retryable] and [retryAfter] inform requeue decisions;
 * [causeClass] carries the originating exception's class name for diagnostics without
 * dragging a framework/throwable into the domain.
 */
data class SyncFailure(
    val kind: SyncFailureKind,
    val message: String,
    val retryable: Boolean,
    val retryAfter: Duration? = null,
    val causeClass: String? = null,
)
