package com.jorisjonkers.personalstack.common.sync.application.service

import com.jorisjonkers.personalstack.common.sync.domain.BaselineSnapshot
import com.jorisjonkers.personalstack.common.sync.domain.RemoteRecord
import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncDecision
import com.jorisjonkers.personalstack.common.sync.domain.SyncDefinition
import com.jorisjonkers.personalstack.common.sync.domain.SyncName
import com.jorisjonkers.personalstack.common.sync.domain.SyncOutcome
import com.jorisjonkers.personalstack.common.sync.domain.SyncSubject
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncTransactionContext
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal class SyncDecisionExecutor<A : Any, R : Any, RID : Any, KEY : Any, SCOPE : Any>(
    private val definition: SyncDefinition<A, R, RID, KEY, SCOPE>,
    private val clock: Clock,
    private val syncName: SyncName,
) {
    private val ports = definition.ports
    private val mapper = definition.mapper

    @Suppress("UNCHECKED_CAST")
    fun execute(
        context: SyncContext<SCOPE>,
        decision: SyncDecision<A, R, RID>,
        tx: SyncTransactionContext,
    ): SyncOutcome<RID> {
        val start = Instant.now(clock)
        val outcome =
            if (context.dryRun) {
                SyncOutcome.Skipped(
                    decision.subject,
                    decision.action,
                    Duration.between(start, Instant.now(clock)),
                    decision.reason,
                )
            } else {
                applyDecision(context, decision, start)
            }

        if (!context.dryRun) {
            if (decision.effects.isNotEmpty()) {
                ports.effectOutbox.append(context, decision.effects)
                tx.afterCommit { ports.effectOutbox.requestRelay(context) }
            }
            recordOutcome(context, outcome)
        } else {
            ports.observer.onOutcome(context, outcome)
        }
        return outcome
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyDecision(
        context: SyncContext<SCOPE>,
        decision: SyncDecision<A, R, RID>,
        start: Instant,
    ): SyncOutcome<RID> =
        when (decision) {
            is SyncDecision.Import<*, *, *> ->
                importOutcome(
                    context,
                    decision as SyncDecision.Import<R, RID, KEY>,
                    start,
                )
            is SyncDecision.Update<*, *, *, *> ->
                updateOutcome(context, decision as SyncDecision.Update<A, R, RID, KEY>, start)
            is SyncDecision.Restore<*, *, *, *> ->
                restoreOutcome(context, decision as SyncDecision.Restore<A, R, RID, KEY>, start)
            is SyncDecision.Relink<*, *, *, *> ->
                relinkOutcome(context, decision as SyncDecision.Relink<A, R, RID, KEY>, start)
            is SyncDecision.Delete<*, *, *> ->
                deleteOutcome(context, decision as SyncDecision.Delete<A, RID, KEY>, start)
            is SyncDecision.Unlink<*, *, *> ->
                unlinkOutcome(context, decision as SyncDecision.Unlink<A, RID, KEY>, start)
            is SyncDecision.Retry ->
                SyncOutcome.Failed(
                    decision.subject,
                    decision.action,
                    Duration.between(start, Instant.now(clock)),
                    decision.failure,
                )
            else ->
                SyncOutcome.Skipped(
                    decision.subject,
                    decision.action,
                    Duration.between(start, Instant.now(clock)),
                    decision.reason,
                )
        }

    private fun importOutcome(
        context: SyncContext<SCOPE>,
        decision: SyncDecision.Import<R, RID, KEY>,
        start: Instant,
    ): SyncOutcome<RID> {
        ports.localRepository.save(mapper.create(decision.remote.record, context), context)
        saveBaseline(decision.subject, decision.remote)
        return SyncOutcome.Succeeded(decision.subject, decision.action, Duration.between(start, Instant.now(clock)))
    }

    private fun updateOutcome(
        context: SyncContext<SCOPE>,
        decision: SyncDecision.Update<A, R, RID, KEY>,
        start: Instant,
    ): SyncOutcome<RID> {
        ports.localRepository.save(
            mapper.update(decision.local.aggregate, decision.remote.record, decision.changes, context),
            context,
        )
        saveBaseline(decision.subject, decision.remote)
        return SyncOutcome.Succeeded(decision.subject, decision.action, Duration.between(start, Instant.now(clock)))
    }

    private fun restoreOutcome(
        context: SyncContext<SCOPE>,
        decision: SyncDecision.Restore<A, R, RID, KEY>,
        start: Instant,
    ): SyncOutcome<RID> {
        ports.localRepository.save(
            mapper.restore(decision.local.aggregate, decision.remote.record, decision.changes, context),
            context,
        )
        saveBaseline(decision.subject, decision.remote)
        return SyncOutcome.Succeeded(decision.subject, decision.action, Duration.between(start, Instant.now(clock)))
    }

    private fun relinkOutcome(
        context: SyncContext<SCOPE>,
        decision: SyncDecision.Relink<A, R, RID, KEY>,
        start: Instant,
    ): SyncOutcome<RID> {
        ports.localRepository.save(
            mapper.relink(decision.local.aggregate, decision.remote.record, decision.changes, context),
            context,
        )
        saveBaseline(decision.subject, decision.remote)
        return SyncOutcome.Succeeded(decision.subject, decision.action, Duration.between(start, Instant.now(clock)))
    }

    private fun deleteOutcome(
        context: SyncContext<SCOPE>,
        decision: SyncDecision.Delete<A, RID, KEY>,
        start: Instant,
    ): SyncOutcome<RID> {
        ports.localRepository.save(mapper.delete(decision.local.aggregate, decision.signal, context), context)
        return SyncOutcome.Succeeded(decision.subject, decision.action, Duration.between(start, Instant.now(clock)))
    }

    private fun unlinkOutcome(
        context: SyncContext<SCOPE>,
        decision: SyncDecision.Unlink<A, RID, KEY>,
        start: Instant,
    ): SyncOutcome<RID> {
        ports.localRepository.save(mapper.unlink(decision.local.aggregate, decision.unlinkReason, context), context)
        return SyncOutcome.Succeeded(decision.subject, decision.action, Duration.between(start, Instant.now(clock)))
    }

    private fun saveBaseline(
        subject: SyncSubject<RID>,
        remote: RemoteRecord<R, RID, KEY>,
    ) {
        ports.checkpointStore.saveBaseline(
            syncName,
            subject,
            BaselineSnapshot(version = remote.version, fingerprint = null, capturedAt = Instant.now(clock)),
        )
    }

    private fun recordOutcome(
        context: SyncContext<SCOPE>,
        outcome: SyncOutcome<RID>,
    ) {
        ports.auditTrail.recordOutcome(context, outcome)
        ports.observer.onOutcome(context, outcome)
    }
}
