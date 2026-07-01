package com.jorisjonkers.personalstack.common.sync.application.service

import com.jorisjonkers.personalstack.common.sync.domain.BaselineSnapshot
import com.jorisjonkers.personalstack.common.sync.domain.RemoteRecord
import com.jorisjonkers.personalstack.common.sync.domain.SyncAction
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

    fun execute(
        context: SyncContext<SCOPE>,
        decision: SyncDecision<A, R, RID, KEY>,
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

    private fun applyDecision(
        context: SyncContext<SCOPE>,
        decision: SyncDecision<A, R, RID, KEY>,
        start: Instant,
    ): SyncOutcome<RID> =
        when (decision.action) {
            SyncAction.IMPORT -> importOutcome(context, decision, start)
            SyncAction.UPDATE -> updateOutcome(context, decision, start)
            SyncAction.RESTORE -> restoreOutcome(context, decision, start)
            SyncAction.RELINK -> relinkOutcome(context, decision, start)
            SyncAction.DELETE -> deleteOutcome(context, decision, start)
            SyncAction.UNLINK -> unlinkOutcome(context, decision, start)
            SyncAction.RETRY ->
                SyncOutcome.Failed(
                    decision.subject,
                    decision.action,
                    Duration.between(start, Instant.now(clock)),
                    requireNotNull(decision.failure) { "retry decision must carry a failure" },
                )
            SyncAction.EQUAL,
            SyncAction.IGNORE,
            SyncAction.CONFLICT,
            ->
                SyncOutcome.Skipped(
                    decision.subject,
                    decision.action,
                    Duration.between(start, Instant.now(clock)),
                    decision.reason,
                )
        }

    private fun importOutcome(
        context: SyncContext<SCOPE>,
        decision: SyncDecision<A, R, RID, KEY>,
        start: Instant,
    ): SyncOutcome<RID> {
        val remote = requireNotNull(decision.remoteRecord) { "import decision must carry a remote record" }
        ports.localRepository.save(mapper.create(remote.record, context), context)
        saveBaseline(decision.subject, remote)
        return SyncOutcome.Succeeded(decision.subject, decision.action, Duration.between(start, Instant.now(clock)))
    }

    private fun updateOutcome(
        context: SyncContext<SCOPE>,
        decision: SyncDecision<A, R, RID, KEY>,
        start: Instant,
    ): SyncOutcome<RID> {
        val local = requireNotNull(decision.localRecord) { "update decision must carry a local record" }
        val remote = requireNotNull(decision.remoteRecord) { "update decision must carry a remote record" }
        val changes = requireNotNull(decision.changes) { "update decision must carry changes" }
        ports.localRepository.save(
            mapper.update(local.aggregate, remote.record, changes, context),
            context,
        )
        saveBaseline(decision.subject, remote)
        return SyncOutcome.Succeeded(decision.subject, decision.action, Duration.between(start, Instant.now(clock)))
    }

    private fun restoreOutcome(
        context: SyncContext<SCOPE>,
        decision: SyncDecision<A, R, RID, KEY>,
        start: Instant,
    ): SyncOutcome<RID> {
        val local = requireNotNull(decision.localRecord) { "restore decision must carry a local record" }
        val remote = requireNotNull(decision.remoteRecord) { "restore decision must carry a remote record" }
        val changes = requireNotNull(decision.changes) { "restore decision must carry changes" }
        ports.localRepository.save(
            mapper.restore(local.aggregate, remote.record, changes, context),
            context,
        )
        saveBaseline(decision.subject, remote)
        return SyncOutcome.Succeeded(decision.subject, decision.action, Duration.between(start, Instant.now(clock)))
    }

    private fun relinkOutcome(
        context: SyncContext<SCOPE>,
        decision: SyncDecision<A, R, RID, KEY>,
        start: Instant,
    ): SyncOutcome<RID> {
        val local = requireNotNull(decision.localRecord) { "relink decision must carry a local record" }
        val remote = requireNotNull(decision.remoteRecord) { "relink decision must carry a remote record" }
        val changes = requireNotNull(decision.changes) { "relink decision must carry changes" }
        ports.localRepository.save(
            mapper.relink(local.aggregate, remote.record, changes, context),
            context,
        )
        saveBaseline(decision.subject, remote)
        return SyncOutcome.Succeeded(decision.subject, decision.action, Duration.between(start, Instant.now(clock)))
    }

    private fun deleteOutcome(
        context: SyncContext<SCOPE>,
        decision: SyncDecision<A, R, RID, KEY>,
        start: Instant,
    ): SyncOutcome<RID> {
        val local = requireNotNull(decision.localRecord) { "delete decision must carry a local record" }
        val signal = requireNotNull(decision.deleteSignal) { "delete decision must carry a signal" }
        ports.localRepository.save(mapper.delete(local.aggregate, signal, context), context)
        return SyncOutcome.Succeeded(decision.subject, decision.action, Duration.between(start, Instant.now(clock)))
    }

    private fun unlinkOutcome(
        context: SyncContext<SCOPE>,
        decision: SyncDecision<A, R, RID, KEY>,
        start: Instant,
    ): SyncOutcome<RID> {
        val local = requireNotNull(decision.localRecord) { "unlink decision must carry a local record" }
        val reason = requireNotNull(decision.unlinkReason) { "unlink decision must carry a reason" }
        ports.localRepository.save(mapper.unlink(local.aggregate, reason, context), context)
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
