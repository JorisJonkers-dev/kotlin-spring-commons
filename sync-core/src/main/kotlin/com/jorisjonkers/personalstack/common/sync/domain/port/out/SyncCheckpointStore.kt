package com.jorisjonkers.personalstack.common.sync.domain.port.out

import com.jorisjonkers.personalstack.common.sync.domain.BaselineSnapshot
import com.jorisjonkers.personalstack.common.sync.domain.CursorCheckpoint
import com.jorisjonkers.personalstack.common.sync.domain.SyncName
import com.jorisjonkers.personalstack.common.sync.domain.SyncSubject

/**
 * OPTIONAL-BY-MODE outbound port. REQUIRED for [com.jorisjonkers.personalstack.common.sync.application.service.SpawnSyncService],
 * paged backfills, and `AuthorityMode.MULTI_WRITER`. The Spring default is an "unsupported" store
 * that fails when a spawn/backfill mode actually calls it, rather than silently dropping cursors.
 *
 * Cursor advancement uses compare-and-swap via [saveCursorIfCurrent] so concurrent spawns cannot
 * clobber each other's progress.
 */
interface SyncCheckpointStore {
    fun loadCursor(syncName: SyncName, scope: Any?): CursorCheckpoint?

    /**
     * Compare-and-swap the cursor: persists [next] only if the stored checkpoint still equals
     * [previous]. Returns `true` on success, `false` when the stored checkpoint is stale.
     */
    fun saveCursorIfCurrent(previous: CursorCheckpoint?, next: CursorCheckpoint): Boolean

    fun loadBaseline(syncName: SyncName, subject: SyncSubject<*>): BaselineSnapshot?

    fun saveBaseline(syncName: SyncName, subject: SyncSubject<*>, baseline: BaselineSnapshot)
}
