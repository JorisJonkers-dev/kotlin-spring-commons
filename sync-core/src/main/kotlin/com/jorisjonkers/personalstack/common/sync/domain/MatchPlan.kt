package com.jorisjonkers.personalstack.common.sync.domain

/**
 * Ordered multi-pass matching strategy. Earlier passes have higher confidence (hard remote id),
 * later passes are softer (remembered id, then natural keys). Reconciliation runs passes in
 * order and removes matched records before later passes.
 */
data class MatchPlan<A : Any, R : Any, RID : Any, KEY : Any>(
    val passes: List<MatchPass<A, R, RID, KEY>>,
)

/**
 * A single matching pass: extract a set of candidate keys from each side and join on equality.
 * Key extraction must be pure.
 */
data class MatchPass<A : Any, R : Any, RID : Any, KEY : Any>(
    val name: String,
    val localKeys: (LocalRecord<A, RID, KEY>) -> Set<KEY>,
    val remoteKeys: (RemoteRecord<R, RID, KEY>) -> Set<KEY>,
    val confidence: MatchConfidence = MatchConfidence.HARD,
)

/** Confidence tier of a match pass; drives which decision (restore/relink/update) a match implies. */
enum class MatchConfidence {
    HARD,
    REMEMBERED_REMOTE_ID,
    NATURAL_KEY,
}

/** A local/remote pairing produced by a pass, retaining the pass and the joining key for audit. */
data class MatchCandidate<A : Any, R : Any, RID : Any, KEY : Any>(
    val pass: MatchPass<A, R, RID, KEY>,
    val local: LocalRecord<A, RID, KEY>,
    val remote: RemoteRecord<R, RID, KEY>,
    val key: KEY,
)

/**
 * A structural data problem that prevents a safe automatic decision. Carries the offending
 * sides for diagnosis and is surfaced as a non-executable [SyncDecision.Conflict].
 */
data class SyncConflict<A : Any, R : Any, RID : Any, KEY : Any>(
    val subject: SyncSubject<RID>,
    val kind: SyncConflictKind,
    val local: LocalRecord<A, RID, KEY>?,
    val remote: RemoteRecord<R, RID, KEY>?,
    val message: String,
)

/** Closed taxonomy of conflict shapes detected by reconciliation. */
enum class SyncConflictKind {
    DUPLICATE_LOCAL_REMOTE_ID,
    DUPLICATE_REMOTE_ID,
    AMBIGUOUS_MATCH,
    LINK_MISMATCH,
    VERSION_CONFLICT,
    UNSUPPORTED_MERGE,
}
