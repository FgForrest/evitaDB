---
title: A catalog rename or replace waits for the commit pipeline on the caller's thread, then acts on the instance that wait found
date: 2026-11-06
updated: 2026-11-06 06:51
status: accepted
kind: fix
issues: [1889]
prs: [1890]
areas: [evita_engine/src/main/java/io/evitadb/core/transaction, evita_engine/src/main/java/io/evitadb/core/transaction/engine/operators, evita_engine/src/main/java/io/evitadb/core/engine, evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog]
supersedes: []
superseded-by: []
relates: [2026-08-06-catalog-folder-decoupling, 2026-09-06-go-live-session-drain]
---

# A rename or replace outlasts the commits in flight, under a 5 s budget, and never publishes across them

`ModifyCatalogSchemaNameMutationOperator` now waits, after both session drains and on the caller's thread, until every
assigned catalog version has reached the live view and the callback that published it has ended. It then re-reads the
catalog by name and acts on that instance. A pipeline still behind after 5 s refuses the operation, and a suspended one
refuses it at once. `DefaultCatalogPersistenceService#replaceWith` writes its bootstrap record under `checkpointLock`, a
trunk round no longer lifts another operation's session suspension, and a round of a replaced catalog can no longer
overwrite the source under the target's name. Before this, a rename or replace right after a commit could leave a
catalog that no restart could load.

## Why

A rename consumes no version and appends nothing to the WAL (`2026-08-06-catalog-folder-decoupling`), so it runs beside
the commit pipeline rather than in it. Nothing ordered the two, and a client that commits with
`WAIT_FOR_WAL_PERSISTENCE` or `WAIT_FOR_CONFLICT_RESOLUTION` and renames next reaches `replaceWith` with its own trunk
round still running. Two routes were measured, one found after the other.

**The ticker route.** `replaceWith` wrote its bootstrap record without `checkpointLock`, so a deferred checkpoint's older
record could land behind it. The bootstrap file is read back by position, so the reopen read the old name. Under a 5 to
60 ms checkpoint interval it failed 65 of 844 attempts, against 0 of 2,137 without deferred checkpoints and 0 of 1,562
with the lock. The rate at the default 1 s is unknown. A restart recovers everything and a retried replace succeeds, 7 of
7. Found by chance in a full functional run.

**The round route, which is worse.** Five causes combine:

1. Nothing waits for the trunk: a session leaves the registry at the stage it committed with.
2. The operator acts on an instance captured before the drain, so its version, index and schema can predate a round that
   finished during the drain.
3. A round's `storeHeader` writes the old service's `this.catalogName`. A round landing after the rename's record and
   before `close()` leaves an old-name header at V+1 above a new-name schema part, and `close()` publishes it.
   `reconcileStoredCatalogIdentity` compares only the header, so it cannot notice.
4. A landing round called `Evita#discardSuspension`, an unconditional resume, so it admitted sessions while the operation
   was still draining: 8 of 8 admitted, against 0 of 8 without a landing.
5. `ExpandedEngineState#replaceCatalogReference` compared only the name and `version >=`, so a round of the replaced
   target could overwrite the source under the target's name, in memory, until a restart: forced 5 of 5, control 0 of 5,
   one hold then a free race 1 of 251, plain stress 0 of 765.

Measured at engine level with a stress probe: eight engines, each committing a batch of 1, 20 or 200 entities at the
behaviour under test and renaming or replacing at once, then restarting and reading every acknowledged key. This is
behaviour under stress, not a natural rate, which is unknown:

| Commit behaviour | Operations failed |
|---|---|
| `WAIT_FOR_WAL_PERSISTENCE` | 368 of 369 |
| `WAIT_FOR_CONFLICT_RESOLUTION` | 96 of 96 |
| default, the client waits for its own commit | 0 of 1,314 |
| default, nobody waits, which stands for another client's commit | 96 of 96 |

| Failure text | After a restart, where recorded |
|---|---|
| `Directory ... contains data of <source> catalog. Cannot load catalog <target> from this directory!` | **not readable**, 162 of 162 |
| `Catalog version is expected to be at least N!` | **not readable**, 3 of 3, all renames |
| `Unexpected catalog version 3 vs. 4!` | readable under the old name, 132 of 132 |
| `Appended versions must not precede the retained ones!` | readable under the old name, 98 of 98 |
| `You're trying to write to an already completed version` | stays `ALIVE`, readable, 1 of 1 |

"Not readable" means through two restarts. A rename then fails the engine start with `Catalog ... is being staged without
a folder binding!`, and a replace stays `BEING_ACTIVATED`. In the terms of `.claude/rules/durability-model.md` this is the
row that does justify refusing work: **a bootstrap record published from a state that cannot be loaded.** On the
recoverable routes, 329 of 329 retries succeeded. No silent loss was seen: no operation reported success and then failed
to serve an acknowledged commit.

### Previous state

The operator drained sessions and handed the instance it had captured to `Catalog#replace`. That was safe only if no round
was in flight, which nothing guaranteed. `Evita#discardSuspension` had been added in 2025 for go-live over gRPC and became
unnecessary when `2026-09-06-go-live-session-drain` gave go-live its own drain and resume. It stayed behind, called for
every propagated round.

## Options considered

The options differ in where the operation meets the pipeline. The fork went through two rounds of external review and an
independent consultation of two further models, each of which wrote its own proposal before seeing this one.

### Option A — bounded wait in the synchronous half, on a completion marker (chosen)

After the drains, wait up to 5 s until `TransactionManager#lastVisibleCatalogVersion` reaches the re-read last assigned
version, refuse at once if the pipeline is suspended while behind, then resolve the instance by name.

- **Pros:** the caller's thread, so no pool starvation. The drain already waits here on the same lock and the same budget,
  and `2026-09-06-go-live-session-drain` chose the same shape. It takes no lock the round holds. Under stress 2,179 of 2,179
  operations succeeded with 2,178 in flight at the call, and none was refused.
- **Cons:** holds `engineStateLock`, which is engine-wide, for up to 5 s more. A round over 5 s gets the operation refused,
  and the open sessions were already closed by then.

### Option B — refuse at once, no wait (declined)

Refuse the operation while the pipeline is behind. One consultant's preference: about 3 lines and no added lock hold.

- **Pros:** the smallest change, and no budget to spend.
- **Rejected because:** nearly every rename right after an early-acknowledged commit would be refused until the client
  polls. That is an inference from 1,254 of 1,256 attempts having a round in flight at the call, not a measurement of this
  option. **It is the fallback** if the lock hold or refusals ever show up in production.

### Option C — pass through `catalogPropagationLock` as a barrier (declined)

The propagation that published a version holds this lock across the live-view callback, so getting it means it finished.

- **Pros:** needs no new state.
- **Rejected because:** it is a lock-order inversion. A round holds this lock (`TransactionManager#propagateCatalogSnapshot`)
  while waiting for `engineStateLock`, which the operator already holds, so every schema-changing round would stall the
  engine for the full 5 s, resolved only by the timeout. Two review rounds of the design missed this, and the independent
  consultation found it. Hence the marker.

### Option D — block in the asynchronous half (declined)

- **Pros:** does not hold `engineStateLock`.
- **Rejected because:** the engine executor **is** the transaction pool (`EngineTransactionManager` is handed
  `transactionExecutor` in `Evita`, even in tests), and the commit pipeline runs on it, so the wait can starve the round it
  waits for.

### Option E — a non-blocking asynchronous continuation (declined)

- **Pros:** no lock hold and no budget, so rounds over 5 s succeed.
- **Rejected because:** estimated at 350 to 650 lines, and it has to settle who owns the conflict key while the continuation
  is parked, only to serve rounds longer than 5 s, of which the stress run produced none. **Revisit if** refusals show up.

### Option F — route the rename through the trunk, or give it a WAL record (declined)

- **Pros:** no race by construction.
- **Rejected because:** `2026-08-06-catalog-folder-decoupling` already rejected a WAL transaction for a rename: it puts a
  non-transactional engine operation into the catalog's transaction stream, where every consumer has to recognise and skip
  it. Nothing here changes that.

### Option G — fence `storeHeader` against the old name (declined)

- **Pros:** addresses the one write that does the lasting damage.
- **Rejected because:** a failing round suspends the shared transaction manager, so the fence turns a late header into a
  suspended pipeline on a healthy catalog. It also leaves causes 1 and 2 standing, which by reading produce the other four
  failure kinds.

### Option H — repair on load in `reconcileStoredCatalogIdentity` (declined)

- **Pros:** would also recover catalogs already stuck by the defect.
- **Rejected because:** it is load-path repair for a shape that cannot recur after this fix, and such logic belongs in
  migrations rather than in the core load path. `release_2026-2` has none of this load code (an older `replaceWith`, no
  `reconcileStoredCatalogIdentity`); whether that release has the race itself was not checked. **Revisit if** a stuck catalog
  is ever reported.

### Option I — wait for the replace target's rounds too (declined)

- **Pros:** symmetrical with the source.
- **Rejected because:** one `catalogId` comparison closes cause 5 with no new refusal and no wait for rounds whose data the
  replace is about to discard, and a target with a stalled pipeline stays replaceable. Such a round still runs across
  `terminate()` and the folder delete: measured harmless, it logs and stops, and the folder was gone in all 1,031 attempts.

## Decision

**Chosen: Option A.** The drivers were that an acknowledged commit must survive an immediate rename, that nearly every such
rename has a round in flight, and that the wait must not take anything the round needs. Option B wins if the lock hold or
refusals turn out to matter, Option E if rounds over 5 s turn out to be common.

## Key technical details

- **The marker is the last statement of `TransactionManager#notifyCatalogPresentInLiveView`**, in a `finally` behind the
  change observer. It has to cover `catalogName` and `lastFinalizedCatalog` as well: a stale `catalogName` write gives
  `Catalog name mismatch!`, and the live version alone reaching V does not mean that callback has ended. It proves the
  callback ended, not that observers succeeded.
- **Never acquire `catalogPropagationLock` while holding `engineStateLock`.** See Option C.
- **`Catalog#replace` acts on `this`**, so handing it an instance from before the wait undoes the wait. Counterfactual 4
  below shows the wait alone does not suffice. Its JavaDoc states the premise.
- **`waitUntilPipelineDrained` re-reads the last assigned version on every pass**, because `notifyCatalogVersionDropped` can
  lower it.
- **A refusal arrives after the open sessions were already closed.** Names and data stay and the operation can be retried,
  but "nothing changed" would be false, and `EvitaContract` deliberately does not say it. It does not promise one exception
  type either: which wait gave up decides that.
- **Without sessions the wait is terminal.** No session means no new commit, the weakest commit behaviour leaves the registry
  only after conflict resolution assigned its version, and greedy batching and WAL draining assign none.
- **The wait may return with a round still in its schema notification**, parked on `engineStateLock`. That tail writes no
  storage. Its notification is refused by the operation's conflict keys, or by the old name being gone.
- **`propagateCatalogSnapshot` reads the name before it publishes, and drops queued notifications naming anything else.** A
  refused notification ends its round and leaves the rest of that round's queue behind. Read in the loop instead, the name
  would already be the new one, because `Catalog#replace` rewrites it on the instance the round published, and the round
  would silently drop its own notification.
- **REST and GraphQL run the synchronous half on the transaction pool**, so there the wait occupies a pool thread. With no
  free worker the round cannot run and the caller is refused at the bound. Exhaustion was not tested.
- **`publishRelabelledHeader` holds `checkpointLock` for the bootstrap write only.** `close()` and the reopen stay outside
  it, because no checkpoint has anything left to publish by then.

## Verification

- `CatalogHandoverTrunkRoundRaceTest`, 10 tests, forces the interleavings through public API only: a held `onWalAppended`
  listener and a progress observer, no reflection and no production seam. Its first two tests were red on the baseline, 5 of
  5 runs, with the rename failing 5 assertions and the replace 4 of 5 in each.
- `CatalogHandoverCheckpointRaceTest`: red 8 of 8 on `dev`, green 6 of 6 with the lock.
- Counterfactuals, each removing one mechanism from the green implementation, restored byte for byte, all red:

| Mechanism removed | Observed |
|---|---|
| suspension preserved (`discardSuspension` restored) | `shouldPreserveSuspensionWhenRoundLands`: `expected: <true> but was: <false>` |
| `catalogId` check | `shouldRefuseLateRoundOfReplacedTarget`: the old target replaced the source under the name, 1 of 1 |
| the wait | both overtaking tests: `Directory ... contains data of ...`, 2 of 2 |
| the re-read, wait kept | both overtaking tests: `Appended versions must not precede the retained ones!`, 2 of 2 |
| the name check on queued notifications | `shouldNotFailLaterCommitWhenRenameRefusedBatchedSchemaNotifications`: `InvalidMutationException: Catalog ... doesn't exist!` from the later, innocent commit; with the check, 25 of 25 green and the batch formed every time |

- The stress probe on the fix, same machine as the baseline: 2,179 of 2,179 operations succeeded, 2,178 in flight at the
  call, 0 refusals, 0 handover failures, corrupt catalogs or stuck activations, slowest 360 ms, 0 of 157,412 acknowledged keys
  missing before or after restart. Target arms: 0 of 655 overwritten, including the forced pin with both holds reached 4 of 4.
- The full functional module on the final tree: 33,221 tests, 0 failures, 184 errors. 183 are release-upgrade tests missing their
  fixtures, because the run skipped generating them. The other is a GraphQL subscription timeout under parallel load, whose class
  passes alone, 13 of 13.
- **Not verified by running:** the marker's own callback window. No clean public route holds a thread inside the observer, so
  its position and `finally` are covered by inspection only. The retry after a refusal was not exercised under stress, which
  produced no refusal; the functional tests cover it.

## Consequences & open follow-ups

- **A schema-changing commit followed at once by a rename fails `onChangesVisible` with its data visible: 442 of 442 under
  stress**, with `ConflictingEngineMutationException`, because `ServerModifyCatalogSchemaMutation` inherits the catalog
  conflict key. Schema and data were readable before and after restart every time. That commit's engine WAL and system CDC
  record is lost, and so is that of any notification dropped behind it. Accepted: the alternative is a 5 s stall under the lock
  on every such rename.
- **Up to 10 s (rename) or 15 s (replace) of `engineStateLock` in the worst case, drains included.** That lock is engine-wide;
  what others wait against is `transactionTimeoutInMilliseconds`, see `2026-09-06-go-live-session-drain`.
- **A delayed old-name schema notification, with that name reused in the meantime, can record misleading engine WAL and system
  CDC history.** It does not change the new catalog's schema. Inferred by review, not measured.
- **Catalogs already stuck by a `dev` build stay stuck.** Hand recovery was not tried and the folders were not inspected. An
  untested idea, from reading only: the rename's own record is still in the bootstrap file below the round's.
- **Deactivate and drop with a round in flight were not examined.** They terminate a catalog too, see #1497 for their missing
  undo. Any new operator that terminates a catalog needs to ask the same question.
- **Suspected, not proven:** `TransactionManager#drainWal` discards its `ProcessResult`.
- REST and GraphQL pool exhaustion during the wait, realistic-load refusal frequency, other platforms and the natural rate of
  any of this are unmeasured.

## Related work

- `2026-08-06-catalog-folder-decoupling` — made rename a pointer swap that consumes no version, which is what put it beside the
  pipeline. It also recorded the first checkpoint-ordering defect and the rejected WAL transaction this record keeps rejected.
- `2026-09-06-go-live-session-drain` — the same synchronous-half shape and 5 s budget. It named `discardSuspension` as the tell
  in the go-live operator; this work deletes the method.

## Timeline

- **2026-11-05** — the ticker route found in a full functional run, reproduced and fixed
- **2026-11-05** — the round route reproduced from a code review's suspicion, measured and folded into the same issue
- **2026-11-05** — design revised twice after external review, then consulted independently; marker design approved
- **2026-11-05** — implemented, stress-tested, reviewed
- **2026-11-06** — merged as #1890
