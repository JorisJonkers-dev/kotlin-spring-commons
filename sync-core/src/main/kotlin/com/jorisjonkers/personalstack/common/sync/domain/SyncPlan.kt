package com.jorisjonkers.personalstack.common.sync.domain

/**
 * The pure output of multi-record reconciliation: the run context plus an ordered list of
 * decisions. Carries derived views over the decisions so the application can gate execution.
 */
data class SyncPlan<A : Any, R : Any, RID : Any>(
    val context: SyncContext<*>,
    val decisions: List<SyncDecision<A, R, RID>>,
) {
    /** All conflict decisions in the plan; these are never executed. */
    val conflicts: List<SyncDecision.Conflict<RID>>
        get() = decisions.filterIsInstance<SyncDecision.Conflict<RID>>()

    /** A plan is executable (in whole-scope/atomic mode) only when it has no conflicts. */
    val executable: Boolean
        get() = conflicts.isEmpty()

    /** Whether the plan contains any change beyond no-op equal/ignore decisions. */
    val substantive: Boolean
        get() = decisions.any { it.action !in setOf(SyncAction.EQUAL, SyncAction.IGNORE) }
}
