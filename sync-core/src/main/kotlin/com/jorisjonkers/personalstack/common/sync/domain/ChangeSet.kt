package com.jorisjonkers.personalstack.common.sync.domain

import java.time.Instant

/** Optimistic-concurrency stamp used to detect remote/local version skew. */
sealed interface VersionStamp {
    data class Token(val value: String) : VersionStamp
    data class Timestamp(val value: Instant) : VersionStamp
    data class Number(val value: Long) : VersionStamp
    data object Unknown : VersionStamp
}

/** Opaque, comparable digest of a record (or field) used to detect change without retaining payloads. */
data class Fingerprint(val value: String)

/**
 * The diff between a local aggregate and a remote record, expressed as a set of changed fields.
 * Produced by a [SyncDiffer]; an empty set means the two sides are equal.
 */
data class ChangeSet(
    val fields: Set<FieldChange> = emptySet(),
) {
    val isEmpty: Boolean
        get() = fields.isEmpty()
}

/**
 * A single differing field. Values are carried as fingerprints (not raw payloads); [redaction]
 * marks whether the underlying value may be logged in plain text.
 */
data class FieldChange(
    val path: FieldPath,
    val local: Fingerprint?,
    val remote: Fingerprint?,
    val redaction: Redaction = Redaction.REDACTED,
)

/** Whether a field value is safe to render in plain text or must be redacted in audit/logs. */
enum class Redaction {
    PLAIN,
    REDACTED,
}

/**
 * Compare-and-swap cursor checkpoint for paged/incremental traversal.
 * [scope] is left as `Any?` because checkpoints are persisted generically across resources.
 */
data class CursorCheckpoint(
    val syncName: SyncName,
    val scope: Any?,
    val cursor: SyncCursor?,
    val highWatermark: SyncCursor?,
    val updatedAt: Instant,
    val runId: RunId,
)

/** Per-subject baseline used to detect drift and support three-way reconciliation over time. */
data class BaselineSnapshot(
    val version: VersionStamp?,
    val fingerprint: Fingerprint?,
    val capturedAt: Instant,
)
