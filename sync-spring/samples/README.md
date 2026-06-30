# Adopting the SYNC framework

This example shows how a consumer service (an HR module that syncs `Employee`
aggregates from an external HR system) adopts the generic SYNC framework. The
full code is in [`EmployeeSyncExample.kt`](./EmployeeSyncExample.kt).

## 1. Add the dependencies

```kotlin
dependencies {
    implementation("dev.jorisjonkers:kotlin-commons-sync-spring:<version>")
    // sync-spring brings dev.jorisjonkers:kotlin-commons-sync-core transitively (api).
}
```

`sync-core` is framework-free and can be used standalone by non-Spring consumers.
`sync-spring` adds the Boot auto-configuration and the generic adapters
(`SpringTransactionSyncUnitOfWork`, `Resilience4jRemoteCatalog`,
`AfterCommitSyncEffectRelay`) plus safe default beans.

## 2. What the framework gives you for free

On a Spring Boot consumer, `SyncAutoConfiguration` contributes:

| Bean | Default | Note |
| --- | --- | --- |
| `SyncUseCaseFactory` | always | builds typed use cases from your `SyncDefinition` |
| `SpringTransactionSyncUnitOfWork` | when a `TransactionOperations` bean exists | the only place `@Transactional`/Spring tx is used |
| `AuditTrail` / `SyncObserver` | no-op | harmless; `SyncReport` is still returned |
| `SyncEffectOutbox` | rejecting | accepts empty effects, **fails** on non-empty effects so nothing is silently dropped |
| `IdempotencyStore` | fail-on-claim, unless `extratoast.sync.idempotency=disabled` (then no-op) | required for external triggers by default |
| `SyncCheckpointStore` | unsupported (fails when used) | required for spawn/backfill and `MULTI_WRITER` |

Things it deliberately does **not** do:

- It never auto-installs a no-op `LockManager` or a no-op `SyncUnitOfWork` —
  locking and transactions are correctness concerns you must wire deliberately.
- It never scans the classpath to create typed sync resources. You declare one
  `SyncDefinition` bean per resource (see below).

Properties live under `extratoast.sync` (`enabled`, `lockTimeout`, `pageSize`,
`idempotency`, `resilience.enabled`).

## 3. What you implement

1. **Aggregate + remote DTO** — `Employee` and `HrEmployeeDto`.
2. **Match key vocabulary** — a sealed `EmployeeSyncKey` (remote id, email,
   payroll number) so different key kinds never collide during matching.
3. **Two ports**:
   - `EmployeeSyncRepository : LocalSyncRepository<Employee, HrEmployeeId, DepartmentId>`
     — your persistence adapter. It MUST return soft-deleted/unlinked rows so
     `RESTORE`/`RELINK` work, and should enforce a unique remote-id constraint.
   - `HrEmployeeCatalog : RemoteCatalog<HrEmployeeDto, HrEmployeeId, EmployeeSyncKey, DepartmentId>`
     — your HTTP adapter. Return `Found`/`Missing`/`Failed`/`Partial`; a remote
     outage must be `Failed`, never `null`, so it can never become a delete.
4. **A `SyncMapper`** (`EmployeeMapper`) — the anti-corruption layer turning a
   remote record into aggregate create/update/restore/relink/delete/unlink
   mutations.
5. **A differ** (`EmployeeDiffer`) — pure field comparison producing a
   `ChangeSet`.

## 4. Declare the resource with the DSL

You assemble everything in one `SyncDefinition` bean using the
`syncResource("employee") { }` DSL:

- wire the ports (`localRepository`, `remoteCatalog`, `lockManager`,
  `unitOfWork`, `effectOutbox`, `auditTrail`, `observer`, `idempotencyStore`,
  `checkpointStore`);
- supply `localProjector`, `remoteProjector`, `differ`, `mapper`;
- declare ordered `matching { pass(...) }` lines (hard remote id →
  remembered/deleted remote id → natural keys);
- set `policies { conflictPolicy / importPolicy / missingRemotePolicy }`
  (default conflict policy is **fail-closed**);
- tune `execution { listTransactionMode; pageSize; ... }`.

Then turn the definition into typed use cases via the auto-configured
`SyncUseCaseFactory`:

```kotlin
EmployeeSyncUseCases(
    entity = factory.entity(definition), // SyncEntityUseCase<HrEmployeeId>
    list   = factory.list(definition),   // SyncListUseCase<DepartmentId>
    spawn  = factory.spawn(definition),   // SpawnSyncUseCase<DepartmentId>
)
```

## 5. Trigger a sync

- **Single entity** (e.g. from a webhook): `entity.sync(SyncEntityCommand(externalId = ..., source = MESSAGE, idempotencyKey = ...))`.
- **A whole scope/list** (e.g. a department): `list.sync(SyncListCommand(scope = DepartmentId(...), source = SCHEDULE))`.
- **Backfill / incremental discovery**: `spawn.spawn(SpawnSyncCommand(scope = ..., fullSync = true, source = ADMIN))` — emits
  `EnqueueEntitySync` effects and advances the cursor checkpoint.

Every call returns a `SyncReport`. Remote failures surface as failed/requeued
reports; effects are persisted atomically and relayed only after the
transaction commits.

## 6. Production checklist

- Provide a real `LockManager` (DB advisory lock / Redis) — there is no
  production default.
- Provide a real `SyncEffectOutbox` if any decision emits effects.
- Provide a real `IdempotencyStore` for message/scheduler/admin triggers (or set
  `extratoast.sync.idempotency=disabled` for in-process-only use).
- Provide a real `SyncCheckpointStore` if you use `spawn`/backfill or
  `AuthorityMode.MULTI_WRITER`.
- Enforce a unique constraint on remote id / remembered remote id in the
  persistence adapter as the correctness backstop.
