---
title: The engine-level go-live drains its catalog's sessions itself, and publishes the ALIVE bootstrap only behind that drain
date: 2026-09-06
updated: 2026-09-08 13:10
status: accepted
kind: fix
issues: [1495]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/core/session, evita_engine/src/main/java/io/evitadb/core/catalog, evita_engine/src/main/java/io/evitadb/core/transaction/engine/operators]
supersedes: []
superseded-by: []
relates: [2026-09-03-content-sized-value-tree-columns, 2026-09-08-warm-up-invalid-schema-refuses-to-publish]
---

# The go-live operator owns the catalog's quiescence, and `Catalog#goLive()` is the boundary it must beat

`MakeCatalogAliveMutationOperator` now obtains the catalog's `SessionRegistry` before anything can fail, drains it
with `REJECT`, and only then flushes and publishes the ALIVE bootstrap record. It carries an undo that restores the
warm-up catalog behind its name and lifts the suspension when the transition fails. Before this, only the
session-driven route (`EvitaSession#goLiveAndCloseWithProgress`) quiesced anything; the engine-level route applied
the mutation directly, so a session opened *before* the transition kept writing in place to the `Catalog` instance
the go-live was about to supersede.

## Why

A warm-up write racing the engine-level go-live was not merely lost. Measured on the unfixed build: the racing
write **returned normally**, the running ALIVE catalog **served the entity** — it carries the index objects across
by reference — and a reload of the same storage **did not have it**. An in-memory catalog disagreeing with its own
published state is worse than the "writes can be lost" the issue and the preceding record both claimed, because
nothing observable tells the client anything is wrong until the next restart.

Nothing on disk is ever damaged by this: no bootstrap record names the missing write, so the stored state is
simply older (`.claude/rules/durability-model.md`). The defect is entirely in what a running engine believes.

### Previous state

`Evita#makeCatalogAliveWithProgress` went straight to `applyMutation(new MakeCatalogAliveMutation(...))`. The tell
was in the operator itself: it called `evita.discardSuspension(...)` — discarding a suspension it never
established. `closeAllSessionsAndSuspend` had exactly one caller in the main tree, and it was the session-driven
path. New sessions were already refused, because admitting the mutation installs an `UnusableCatalog(GOING_ALIVE)`
placeholder synchronously under the engine state lock; the incumbent session was the unhandled half.

## Options considered

### Option A — the operator drains, synchronously, before `Catalog#flush()` (chosen)

The operator takes ownership of the registry through `Evita#obtainCatalogSessionRegistry` (the half that cannot
fail), drains with `REJECT`, then builds the flush future and calls `goLive()`. It cannot use
`Evita#suspendCatalogSessions`, because an operation that must be able to lift its own suspension has to hold the
registry instance it suspended.

- **Pros:** one drain covers every route into a go-live. Closing the incumbent flushes its writes, so they land in
  storage rather than being discarded. The operator can carry an undo, which the session-driven path never had.
- **Cons:** the drain runs while `engineStateLock` is held, so a pathological close delays every other engine
  mutation — which is what forced the drain's wait to be bounded end to end (see *Key technical details*).
  **That lock is engine-wide, not per catalog**: one `EngineTransactionManager` per `Evita`
  (`Evita.java:564`), so the delay is paid by catalog lifecycle mutations on *every* catalog in the engine.
  What bounds the cost is what contends for it — only engine-level mutations (create, drop, rename,
  deactivate, go-live) take that lock at all, never queries, entity writes or session creation — and the
  budget they wait against: `transactionTimeoutInMilliseconds`, 300 s by default, sixty times the drain's
  worst case. An engine configured with a timeout near or below five seconds would turn this delay into a
  `TransactionTimedOutException` on an unrelated catalog, and is the shape to watch for.

### Option B — drain inside the completion lambda, as deactivation does (declined)

`SetCatalogStateMutationOperator` quiesces inside its `ProgressingFuture` body rather than on the calling thread.

- **Pros:** consistent with the sibling operator; the drain would not hold `engineStateLock`.
- **Rejected because:** it is one flush more expensive and it makes an incumbent's writes depend on the forced
  close's own flush succeeding, rather than on the operator's flush that the go-live already performs. **Not**
  rejected for the reason originally recorded — see *the invariant we got wrong* below.

### Option C — leave the operator alone; require every caller to drain first (declined)

Make `makeCatalogAliveWithProgress` do what `goLiveAndCloseWithProgress` does.

- **Pros:** smallest diff; no new ownership rules.
- **Rejected because:** a raw `Evita#applyMutation` from the wire bypasses the facade entirely, so the invariant
  would hold only for callers that remembered it. The operator is the one place every route passes through.

## Decision

**Chosen: Option A.** The rule the codebase now states is that **the safety boundary is `Catalog#goLive()`, not
`Catalog#flush()`**: what must not happen is publishing the ALIVE bootstrap record while a session is still writing
to the instance it supersedes. Running the drain ahead of `flush()` as well is a deliberate preference, worth one
flush and worth not depending on a forced close's own flush — but it is not the safety property, and a future
reader must not re-derive a guarantee from the placement that the placement does not carry.

Option B becomes worth revisiting if holding `engineStateLock` across the drain ever shows up as a real
availability problem; the bounded wait and the park between passes were added to make that unlikely, and the
engine-wide scope of that lock is stated in Option A's cons so the trigger is judged against the right blast
radius rather than a per-catalog one.

### The invariant we got wrong, and how it was settled

The original plan, the first commit, and three review rounds all asserted that a write landing after
`Catalog#flush()` pops the trapped changes **would be lost** — that this was the defect. It is false. Moving the
drain into the completion lambda but still ahead of `goLive()` leaves the whole suite green, because a forced close
performs its **own** warm-up flush and persists whatever the operator's pop missed. Only moving the drain past
`goLive()` reproduces the defect. This was established by moving the line and re-running, twice, in separate turns
with byte-identical restores verified by `cmp` — not by argument, which had failed to catch it through an external
adversarial review as well.

## Key technical details

- **Ownership before failure.** `Evita#obtainCatalogSessionRegistry` installs a registry when the catalog has none
  and never touches the sessions inside it; the drain publishes its suspension *before* it waits, so a drain that
  gives up throws with the suspension standing. The caller therefore has to own the instance, not the name — a
  rename or replace re-registers the registry under a different name while sharing one suspension by reference.
- **The undo has two branches**, and which one runs is decided by whether `goLive()` returned. Before publication
  it restores the warm-up catalog; after publication it installs the alive catalog anyway, because the bootstrap
  record is durable and a reload would say so. A committed transition is never rolled back.
- **The commit boundary is drawn once.** Everything after `completionEngineStateUpdater.accept(...)` is best-effort
  and locally caught; the `CommitVersions` read sits *before* `aliveCatalog.set(...)`, so a throwing accessor lands
  on the side where reaching the undo is correct.
- **The drain is bounded end to end** by `SessionRegistry#DRAIN_GIVE_UP_TIMEOUT_MILLIS`, on a monotonic clock, and
  parks between passes rather than spinning. Expiry is a failure, not a partial success: the premise reports the
  sessions still standing and throws, **with the suspension published**, so a caller without an undo leaks it —
  which is why deactivate and drop are issue #1497.
- **A drained warm-up session's writes are kept, not rolled back.** `setRollbackOnly()` applies only to an open
  transaction; the close is `closeNow(WAIT_FOR_WAL_PERSISTENCE)`. This is the property the whole fix turns on, and
  `SessionRegistry`'s javadoc claimed the opposite until this work corrected it.

## Verification

Every claim below was measured, and each guard's counterfactual was observed rather than assumed.

| What | Counterfactual | Observed |
|---|---|---|
| the drain exists | remove the drain line | `CatalogGoLiveSessionDrainTest` tests 1 and 2 fail at the same assertions as on the unfixed build |
| the boundary is `goLive()` | move the drain past `goLive()` | reload assertion red: `expected: <1> but was: <0>` |
| the boundary is *not* `flush()` | move the drain into the completion lambda | **all green** — this is the falsification |
| the widened `CatalogGoingLiveException` catch | narrow it again | `Tests run: 3, Failures: 3`, both on the log-capture assertion |
| the bounded drain wait | restore the unbounded `join()` | `LongRunningSessionRegistryDrainTimeoutTest` **hangs rather than fails** |
| the census is concurrent | restore the plain `HashSet` | `lossy rounds: 436 of 1000, first at 0`, and one run in two hangs with a writer wedged in `HashMap#put` |
| the park between passes | remove it | `4997 ms of processor time over 5000 ms of waiting`; parked: 43 ms |

Full functional module and the four long-running drain classes green; the reload assertion is what proves the
write reached storage rather than merely the running catalog.

## Consequences & open follow-ups

- **Six defects ship with the code fixed but no test**, each needing a production seam that was deliberately not
  invented: a throwing live-view notifier (the undo's `GOING_ALIVE` message branch, and the success/undo
  disagreement about a missed notification), a hook after `persistenceService.goLive(1L)` (the post-publication
  message), an injectable engine executor (a throw building `ProgressRecord` bypassing the undo), and a throwing
  `Catalog` accessor (the commit-boundary read). Adding any of them is a change to production code for a test's
  benefit and should be decided on its own merits.
- **Deactivate and drop have no undo** — a drain throw leaves the placeholder standing and the registry suspended
  until restart. Pre-existing and unchanged here, but this work made it more reachable by converting a silent hang
  into a loud failure. Filed as **#1497** with its design.
- **Declined during adversarial review, recorded so they are not re-proposed:**
  - *A time-of-check/time-of-use race in `Evita#createSessionInternal`* — a session that reads the real catalog and
    then pauses is refused by the registry with `InstanceTerminatedException` rather than the placeholder's
    `CatalogGoingLiveException`. The refusal is correct in every interleaving; only the exception type degrades, in
    a race. Closing it means re-resolving on registry refusal across the session-creation path shared with drop and
    deactivation, changing types existing tests assert on, with no seam to prove it. *Revisit if* a client is ever
    seen to act on the distinction.
  - *A test-configurable drain deadline* — proposed so the fast tests cannot lose a race against the production
    5 s bound under CI load. Declined because it puts a test seam into production code that nothing else needs.
    *Revisit if* those tests ever actually flake; that seam is the answer.
- **One residual on a refuted finding.** A static argument claimed the test's log capture could not see the errors
  it asserts on; the counterfactual refuted it. The *mechanism* was never explained — the capture appears to
  succeed because a one-entity flush completes inline, which is a race rather than an invariant — so the filter was
  hardened with a throwable branch and a catalog-name branch instead of relying on the thread name alone.
- **Pre-existing defects fixed in passing**, because the go-live path made them reachable: the drain's
  cross-thread `ArrayList`, its unbounded `join()` under `engineStateLock`, its busy-spin, the unsynchronised
  forcefully-closed census, and `Catalog#flush()`'s refusal barrier being installed by a `whenComplete` dependent
  that ran *after* the go-live's undo had already resumed the registry.

## Related work

- `2026-09-03-content-sized-value-tree-columns` — its follow-up section is where this defect was first recorded,
  during adversarial review of that work; it was deliberately not folded in, being lifecycle rather than index work.
- **#1497** — the same missing-undo shape in `SetCatalogStateMutationOperator` and
  `RemoveCatalogSchemaMutationOperator`, found by asking which other operators quiesce without an undo.

## Timeline

- **2026-09-05** — reported during adversarial review of #1486; root cause reproduced on the unfixed build
- **2026-09-05** — drain, placeholder exception, undo and the first tests implemented
- **2026-09-06** — external adversarial review; six further defects fixed, one finding refuted by measurement,
  two declined
- **2026-09-06** — the drain-placement invariant falsified by counterfactual and corrected in code and record
- **2026-09-06** — quality pipeline: the census race, the busy-spin and the untested drain arm closed
