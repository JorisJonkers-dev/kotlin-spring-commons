# Sync Test Harness — API Reference

This is the shared, in-memory test harness for the `sync-core` framework. Use it to write unit
tests against the application services (`SyncEntityService`, `SyncListService`, `SpawnSyncService`)
without any Spring, DB, or threading. Everything lives in:

    sync-core/src/test/kotlin/com/jorisjonkers/personalstack/common/sync/testsupport/

All types below are in package
`com.jorisjonkers.personalstack.common.sync.testsupport`.

Test libs already on the classpath: JUnit 5 (`junit-jupiter`), AssertJ (`assertj-core`), MockK
(`mockk`).

---

## 1. The fast path: `WidgetHarness`

`WidgetHarness.build(...)` returns a fully-wired setup: all nine in-memory ports plus a built
`SyncDefinition<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope>`. The generic tuple is
aliased as `WidgetDefinition`.

```kotlin
val harness = WidgetHarness.build()                 // sensible defaults
val factory = SyncUseCaseFactory(harness.clock)      // pass the harness clock for determinism
val entity = factory.entity(harness.definition)      // SyncEntityUseCase<WidgetId>
val list   = factory.list(harness.definition)        // SyncListUseCase<WidgetScope>
val spawn  = factory.spawn(harness.definition)        // SpawnSyncUseCase<WidgetScope>
```

### `WidgetHarness.build` parameters (all optional, with defaults)

| Param | Type | Default | Purpose |
|---|---|---|---|
| `name` | `String` | `"widget"` | Sync resource name. |
| `missingRemotePolicy` | `MissingRemotePolicy<Widget, WidgetId, WidgetKey>` | `DELETE_MISSING` | What to do with a local that has no remote. |
| `importPolicy` | `ImportPolicy<RemoteWidget>` | import everything | Eligibility of remote-only records. |
| `conflictPolicy` | `ConflictPolicy<Widget, RemoteWidget, WidgetId, WidgetKey>` | `ConflictPolicy.failClosed()` | How conflicts surface. |
| `listTransactionMode` | `ListTransactionMode` | `PER_RECORD` | List execution granularity. |
| `authorityMode` | `AuthorityMode` | `REMOTE_AUTHORITATIVE` | Execution option. |
| `pageSize` | `Int` | `500` | Page size for list/spawn. |
| `matchPasses` | `List<WidgetMatchPass>` | `[REMOTE_ID, SKU]` | Ordered match plan. |
| `fixedInstant` | `Instant` | `2026-06-30T00:00:00Z` | Backs the fixed `Clock`. |

Two ready-made missing-remote policies are exposed on the companion:
`WidgetHarness.DELETE_MISSING` (soft-delete via `RemoteDeleteSignal.MissingFromAuthoritativeList`,
falling back to unlink when no remembered id) and `WidgetHarness.UNLINK_MISSING`.

### Exposed fields (mutate these to drive branches, inspect them to assert)

`harness.repository`, `.remoteCatalog`, `.lockManager`, `.unitOfWork`, `.effectOutbox`,
`.auditTrail`, `.observer`, `.idempotencyStore`, `.checkpointStore`, `.definition`, `.clock`.

To vary policies/passes you cannot mutate after build — rebuild with the right `build(...)` args.
To vary remote responses, lock grants, UoW mode, idempotency state, you mutate the adapter fields
**before** calling the use case.

---

## 2. The test domain

Generic tuple: `A=Widget, R=RemoteWidget, RID=WidgetId, KEY=WidgetKey, SCOPE=WidgetScope`.

### Value types
- `WidgetId(val value: String)` — remote id (RID).
- `WidgetScope(val value: String)` — scope (SCOPE).
- `WidgetKey` — sealed (KEY):
  - `WidgetKey.Remote(val id: WidgetId)` — hard key.
  - `WidgetKey.Sku(val sku: String)` — natural key.

### `Widget` (aggregate, A)
Fields: `localId: LocalId?`, `sku: String`, `name: String`, `scope: WidgetScope`,
`registration: SyncRegistration<WidgetId>`, `deleted: Boolean = false`.

Factory constructors on `Widget.Companion`:
- `Widget.neverLinked(localId, sku, name, scope = WidgetScope("default"))`
- `Widget.linked(localId, remoteId, sku, name, scope = …, at = Instant.EPOCH)`
- `Widget.remotelyDeleted(localId, remoteId, sku, name, scope = …, at = Instant.EPOCH)` — soft-deleted, remembers `remoteId` (so Restore is reachable).

### `RemoteWidget` (DTO, R)
`RemoteWidget(id: WidgetId, sku: String, name: String, lifecycle = ACTIVE, importable = true, version: VersionStamp? = null, observedAt: Instant? = null)`.
Set `lifecycle = RemoteRecordLifecycle.DELETED` to drive Delete/Ignore; `importable = false` to
drive a not-importable Ignore.

### Strategies (singletons, all pure)
- `WidgetLocalProjector` — keys = remembered/active remote id (`WidgetKey.Remote`) + `WidgetKey.Sku`.
- `WidgetRemoteProjector` — keys = `WidgetKey.Remote(id)` + `WidgetKey.Sku(sku)`; carries lifecycle/importable/version/observedAt through.
- `WidgetDiffer` — reports the `name` field changed when local/remote names differ, else empty `ChangeSet`. (SKU/name both updated by the mapper.)
- `WidgetMapper` — implements all six `SyncMapper` actions (`create/update/restore/relink/delete/unlink`). `create` makes a `LocalId("local-<remoteId>")`, `delete`/`restore` toggle the `deleted` flag.

### Match passes
`enum WidgetMatchPass { REMOTE_ID, SKU }`. `REMOTE_ID` is HARD; `SKU` is `NATURAL_KEY` (drives
relink-by-natural-key). Pass the list you want as `matchPasses`.

---

## 3. Fixture factories — `SyncFixtures`

- `SyncFixtures.runId(value = "...0001")` → `RunId`
- `SyncFixtures.correlationId(value = "corr-1")` → `CorrelationId`
- `SyncFixtures.context(syncName = "widget", scope = null, source = TEST, startedAt = 2026-06-30…, dryRun = false, runId, correlationId)` → `SyncContext<WidgetScope>`
- `SyncFixtures.remoteSubject(id)` / `SyncFixtures.pairSubject(localId, id)` → `SyncSubject<WidgetId>`
- `SyncFixtures.ignoreReason(message)` → `SyncReason.Policy`

These are for directly unit-testing the pure domain (`Reconciliation`, projectors, mapper); the
application services build their own `SyncContext` internally from the command.

---

## 4. The nine in-memory ports — constructors and branch controls

All are plain classes in `InMemoryPorts.kt`. `WidgetHarness.build()` wires them with the
constructor args shown; you normally read them off `harness.*` and only construct directly for
isolated unit tests.

### `InMemoryLocalSyncRepository<A, RID, SCOPE>`
Constructor: `(keyOf: (A) -> RID?, scopeOf: (A) -> SCOPE? = { null }, linkedOf: (A) -> Boolean = { keyOf(it) != null })`.
- `keyOf` extracts the remote id a row is (or was) linked to — return the **remembered** id for soft-deleted rows so Restore/Relink find them.
- Fields: `rows` (current stored aggregates), `saved` (every aggregate passed to `save`, in order).
- `seed(vararg aggregates)` — pre-populate without recording a `save`.
- Holds soft-deleted rows because `findByRemoteIdIncludingDeleted` / `listIncludingDeleted` never filter on `deleted`.
- `listLinkedRemoteIds` returns a single page (cursor ignored) of ids where `linkedOf` is true — used by Spawn full-sync.

In the harness: `keyOf = { remoteId ?: rememberedRemoteId }`, `scopeOf = { it.scope }`.

### `InMemoryRemoteCatalog<R, RID, KEY, SCOPE>`
No-arg constructor. **Default behavior: every fetch is `Missing(NOT_FOUND)` / empty `Found` page.**
Controls:
- `fetchOne`: `onFetchOne(id, RemoteFetch.X(...))` or `foundOne(id, remoteRecord)`. Fallback `defaultOne`.
- `fetchForScope`: `onFetchForScope(RemoteFetch.X(...))` or set `scopeResponse`.
- `fetchPage`: `enqueuePage(RemoteFetch.X(...))` queues ordered responses; `defaultPage` when drained.
- Recorded: `fetchOneCalls: List<RID>`, `fetchPageCursors: List<SyncCursor?>`.

**Driving each `RemoteFetch` branch** — pass the matching variant:
- Found: `RemoteFetch.Found(remoteRecord)` (single) or `RemoteFetch.Found(RemotePage(...))`.
- Missing: `RemoteFetch.Missing(MissingReason.NOT_FOUND)` — authoritative absence; entity sync reconciles to delete/unlink.
- Failed: `RemoteFetch.Failed(SyncFailure(...))` — never becomes a delete; service returns a FAILED/requeue report.
- Partial: `RemoteFetch.Partial(value, SyncFailure(...))` — value is synced but absence is not inferred; list/spawn requeue.

Build `RemoteRecord` directly:
`RemoteRecord(record = RemoteWidget(...), externalId = WidgetId("w-1"), keys = setOf(WidgetKey.Remote(...), WidgetKey.Sku(...)), lifecycle = ..., importable = ..., version = ..., observedAt = ...)`.
Build a page with `RemotePage(changes = listOf(RemoteChange.Upsert(remoteRecord) | RemoteChange.Delete(signal, version)), nextCursor = SyncCursor?, highWatermark = SyncCursor?, retryAfter = Duration?)`.

### `InMemoryLockManager`
Constructor: `(grant: Boolean = true)`. Grants and runs the block by default.
- `denyAll()` (sets `grant = false`) → every `withLock` returns `LockResult.NotAcquired` (drives the LOCK_UNAVAILABLE branch).
- `deny(keyString)` → deny only that key (`SyncLockKey.value`).
- Recorded: `requested: List<SyncLockKey>`.

### `InMemoryUnitOfWork`
Constructor: `(mode: Mode = Mode.COMMIT)`.
- `Mode.COMMIT`: runs the body, then runs all `afterCommit` callbacks. `afterCommitRun` counts them.
- `Mode.ROLLBACK_SKIP_AFTER_COMMIT`: runs the body, returns its value, but **drops** the `afterCommit` callbacks (simulates rollback / commit that never relays effects).
- To simulate a body that throws (true rollback), make the **body** throw — `SyncListService` per-record mode catches `RuntimeException` and produces a Failed outcome.
- Recorded: `transactionCount`, `afterCommitRun`.

### `InMemoryEffectOutbox`
No-arg. `append` records into `appended: List<SyncEffect>`; `requestRelay` increments `relayRequests`.
Assert effects + relay-after-commit ordering with these.

### `InMemoryAuditTrail`
No-arg. Records `runStarted: List<SyncContext<*>>`, `outcomes: List<SyncOutcome<*>>`,
`runCompleted: List<SyncReport>`.

### `InMemoryObserver`
No-arg. Same three lists as the audit trail (`runStarted`, `outcomes`, `runCompleted`). Note: the
services call the observer on dry-run/non-executable outcomes even when the audit trail is skipped.

### `InMemoryIdempotencyStore`
No-arg. First `claim(key)` → `Acquired`; subsequent claim of an in-progress key → `InProgress`; after
`complete` → `Completed(report)` (replay). Controls to force the first claim:
- `preClaimInProgress(key)` → next `claim` returns `IdempotencyClaim.InProgress` (drives the IDEMPOTENCY_IN_PROGRESS branch).
- `preComplete(key, report)` → next `claim` returns `IdempotencyClaim.Completed(report)` (drives the replay branch).
- A `fail`ed key is re-claimable (next claim → `Acquired`).
- Recorded: `claims`, `completed`, `failed` (all `List<IdempotencyKey>`).

Pass an `IdempotencyKey` on the command (`SyncEntityCommand(idempotencyKey = IdempotencyKey("k"))`)
to engage idempotency at all.

### `InMemoryCheckpointStore`
No-arg.
- `seedCursor(CursorCheckpoint(...))` — pre-store a cursor for `(syncName, scope)`.
- `loadCursor` / `saveCursorIfCurrent` honour compare-and-swap: returns `false` if the stored value != `previous`.
- `casAlwaysFails = true` → every `saveCursorIfCurrent` returns `false` (drives the stale/clobbered-cursor branch in Spawn).
- `saveBaseline` records into `savedBaselines: List<BaselineSnapshot>`; `casCalls: List<CursorCheckpoint>`.

---

## 5. Commands (inbound)

- `SyncEntityCommand(externalId: RID, source: SyncTriggerSource, correlationId = new(), idempotencyKey: IdempotencyKey? = null, dryRun = false)`
- `SyncListCommand(scope: SCOPE, source, correlationId, idempotencyKey?, dryRun)`
- `SpawnSyncCommand(scope: SCOPE?, cursor: SyncCursor? = null, pageSize: Int? = null, fullSync = false, source, correlationId, idempotencyKey?, dryRun)`

`SyncTriggerSource` values: `ADMIN, MESSAGE, SCHEDULE, TEST, DIRECT` (use `TEST`).

---

## 6. Worked example (the smoke test)

```kotlin
val harness = WidgetHarness.build()
val useCase = SyncUseCaseFactory(harness.clock).entity(harness.definition)

val id = WidgetId("w-1")
harness.remoteCatalog.foundOne(
    id,
    RemoteRecord(
        record = RemoteWidget(id = id, sku = "SKU-1", name = "Sprocket"),
        externalId = id,
        keys = setOf(WidgetKey.Remote(id), WidgetKey.Sku("SKU-1")),
    ),
)

val report = useCase.sync(SyncEntityCommand(externalId = id, source = SyncTriggerSource.TEST))

assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
assertThat(report.outcomes.single().action).isEqualTo(SyncAction.IMPORT)
assertThat(harness.repository.rows.single().registration.remoteId).isEqualTo(id)
```

### Cheat sheet — driving each decision in an entity sync
- **Import**: empty repo + `foundOne(id, activeRemote)`.
- **Update**: `repository.seed(Widget.linked(...))` + `foundOne` with a different `name`.
- **Equal**: linked local + remote with the same `name`.
- **Restore**: `repository.seed(Widget.remotelyDeleted(...))` + active remote.
- **Relink**: linked local under id X, build with `matchPasses = listOf(SKU)`, remote with the same SKU but different id.
- **Delete**: linked local + remote with `lifecycle = DELETED`.
- **Delete/Unlink via absence**: linked local + `fetchOne` returns `Missing` (policy decides).
- **Conflict**: list sync with two locals sharing one hard key (use `reconcileMany` / list use case).
- **Failed / requeue**: `fetchOne` returns `RemoteFetch.Failed`.
- **Lock unavailable**: `harness.lockManager.denyAll()`.
- **Idempotency replay / in-progress**: `idempotencyStore.preComplete` / `preClaimInProgress`, plus an `idempotencyKey` on the command.
- **Dry-run** (plans without writing): `SyncEntityCommand(dryRun = true)`.
```
