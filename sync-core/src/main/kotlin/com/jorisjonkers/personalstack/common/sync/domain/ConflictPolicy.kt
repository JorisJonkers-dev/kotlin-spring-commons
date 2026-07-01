package com.jorisjonkers.personalstack.common.sync.domain

/**
 * Decides what to do with a detected [SyncConflict]. The default is fail-closed: a conflict is
 * surfaced as a non-executable [SyncDecision.Conflict] for manual review, never silently
 * ignored or resolved by arbitrary remote-wins. Consumers may override with a stricter or more
 * lenient policy, but the return type is always a `Conflict` decision.
 */
fun interface ConflictPolicy<A : Any, R : Any, RID : Any, KEY : Any> {
    fun decide(conflict: SyncConflict<A, R, RID, KEY>): SyncDecision.Conflict<A, R, RID, KEY>

    companion object {
        /** The mandatory safe default: turn any conflict into a non-executable conflict decision. */
        fun <A : Any, R : Any, RID : Any, KEY : Any> failClosed(): ConflictPolicy<A, R, RID, KEY> =
            ConflictPolicy { conflict -> SyncDecision.Conflict(conflict) }
    }
}
