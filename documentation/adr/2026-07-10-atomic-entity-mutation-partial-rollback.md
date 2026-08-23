---
title: Revert a failed entity mutation by snapshot/restore savepoints over the diff layers, not by replaying inverse actions
date: 2026-07-10
updated: 2026-08-23 15:50
status: accepted
kind: feature
issues: [569, 1252]
prs: [1256, 1267, 1268]
areas: [evita_engine/core/transaction/memory, evita_engine/core/collection, evita_engine/index]
supersedes: []
superseded-by: []
relates: [2026-07-10-more-optimized-data-structures, 2026-07-27-write-path-performance-tuning, 2026-07-18-paged-index-corruption-and-flush-failure-boundary]
---

# Per-entity partial rollback via diff-layer savepoints, replacing hand-written inverse undo actions

Within a transaction, a single `upsertEntity` / `deleteEntity` call is atomic on its own: if applying it
fails part-way through the index updates, the engine reverts exactly that entity's changes and the
surrounding transaction keeps running. This is implemented as a **savepoint over the transactional diff
layers** — state is *restored*, never *un-computed* — and it **replaced** an earlier mechanism that tried
to undo a failed mutation by replaying hand-written inverse operations in reverse order. That mechanism
demonstrably failed to return the indexes to their pre-mutation shape, and understanding *why* it failed
is the core of this record.

> This record was reconstructed on 2026-08-23; the feature predates the ADR convention. The mechanism
> itself is documented in `documentation/developer/stm/savepoints.md`, which is authoritative for the
> lifecycle and SPI details; this record carries the decision and the rejected alternatives.

## Why

Client batch upserts apply many entity mutations in one transaction. One entity may legitimately fail —
a uniqueness conflict, a consistency-rule violation that only surfaces after some index entries have
already been written — and the caller wants to catch the exception, skip that entity and keep going,
rather than abort the whole batch. A root entity mutation cascades through the index executor and
reflected-reference / index-trigger cross-collection writes, touching many data structures; reverting
exactly that cascade, and nothing else, is the problem.

### Previous state — `undoActions`, and why reverse replay of inverses failed

The original mechanism (`d5738774f`, issue #16) kept a `LinkedList<Runnable> undoActions` on
`EntityIndexLocalMutationExecutor`, threaded an `undoActionsAppender` through the index mutators
(~150 call sites across `ReferenceIndexMutator`, `PriceIndexMutator`, `HierarchyPlacementMutator`, …),
and on failure replayed the list in reverse order. Each pushed action was a **semantic
counter-operation through the public index API**: `() -> index.removeFacet(...)` to undo `addFacet`,
`() -> referenceTypeIndex.insertPrimaryKeyIfMissing(...)` to undo a removal, and so on.

In practice the replay did not restore the pre-mutation state — it left the indexes in a *different*
inconsistent state. The failure was not one implementation bug but a property of the approach; four
independent mechanisms each break it:

1. **A counter-operation is not a state restore.** `removePrimaryKey` as an inverse of an insert runs
   the *full forward removal logic*, with cascades of its own — empty-index detection and teardown,
   cardinality bookkeeping, memoized-state invalidation. Those cascades do not exactly cancel the
   original insert's cascades. Worst case: the removal drops a whole per-reference index once it
   becomes empty, and the "inverse" that later re-inserts a primary key creates a *fresh* index missing
   the attribute/sort data that lived in the dropped one.
2. **The half-done step at the failure point has no inverse.** Undo actions were pushed per *completed*
   call-site operation. When the failing operation died mid-way inside a compound index update (two of
   three internal structures updated), the interior partial work was invisible to the undo list — and
   the recorded inverses for the earlier steps then executed against a state their assumptions no
   longer described. Counter-operations run through the public API can themselves throw or branch
   differently against such a state ("record not found"), which is precisely how "rollback left a
   different broken state" happens.
3. **Coverage was a per-call-site discipline with no enforcement.** Every new mutator path had to
   remember to thread the appender and hand-derive a correct inverse. A missed site leaked silently;
   nothing at runtime or review time could tell a covered path from an uncovered one.
4. **Relative, non-idempotent inverses are ordering-fragile.** "Remove what I added" is only correct if
   nothing between the forward operation and its inverse re-keyed, coalesced or restructured the
   underlying state. Derived structures (sort re-keying, chain reordering, histograms) violate that
   constantly.

## Options considered

Six approaches were weighed (the full table also lives in `savepoints.md`):

### Approach D — snapshot/restore diff-layer savepoints (chosen)

Every mutation inside a transaction already lands in a per-structure diff layer (`MapChanges`,
`BitmapChanges`, B+ tree layers, …). A savepoint brackets the root entity mutation: the maintainer
lazily captures each layer's state on first write-touch, and rollback *restores* the captured state.

- **Pros:** per-structure operation is a unary copy (or an `O(1)` journal mark); changes **zero**
  read/write paths; restore is state replacement, so it cannot take a "different branch"; the partial
  work of the failing operation itself is captured too, because *all* writes go through the layers; a
  bug can only affect the ~1 % rollback path, never the committed snapshot.
- **Cons:** every accumulating diff layer must implement the `Snapshotable` SPI (~31 types); inherently
  tied to the transactional layer existing, so it cannot serve the WARM_UP path (see Consequences).

### Approach A — nested sub-layer merge (declined)

Give each entity its own sub-layer, merge into the parent on success.

- **Pros:** conceptually the "obvious" nesting model; was the original instinct.
- **Rejected because:** per-structure operation is a binary merge + positional rebase across ~12
  transactional structures, needing a two-level read-through and maintainer rewiring — and a merge bug
  silently corrupts the **committed** snapshot, i.e. the 99 % success path. Blast radius decided this.

### Approach B — deferred write buffer (declined)

- **Rejected because:** duplicates the whole write model; every structure needs a buffered mirror.

### Approach C — validate first, so writes never fail (declined)

- **Rejected because:** some failure modes only surface mid-write; does not generalise.

### Approach E — dry-run replay, then real replay (declined)

- **Rejected because:** doubles write cost on the hot path; dry-run and real run can diverge.

### Approach F — keep `undoActions` but make the inverses exhaustive (declined)

- **Rejected because:** this is the mechanism described under *Previous state*, and its failure was
  structural, not a matter of missing cases — points 1, 2 and 4 above hold even for a "complete" set of
  semantic inverses, and point 3 makes completeness unverifiable anyway. It was deleted outright by
  `a5770aa82` rather than repaired.

### Sub-fork (#1252): memento strategy for large accumulating layers

The first implementation used deep-copy mementos everywhere. Because capture is lazy (a mutation always
immediately follows `snapshot()`), a deep copy pays `O(accumulated-delta)` per savepoint — a measured
`O(N²)` cliff (≈10.6 s of pure memento copying at 32k entities for the shared-map case). Three fixes
were weighed: **an undo journal**, **CHAMP-backed mementos**, and **targeted snapshotting**. The
**undo journal** (`UndoJournal`) won: `snapshot()` becomes an `O(1)` mark, `restore()` reverse-replays
only the intra-savepoint delta, and the layer's steady-state representation is unchanged, so the common
commit path pays nothing.

The journal is *not* a return to Approach F, and the distinction is load-bearing:

| | `undoActions` (F) | `UndoJournal` (D's journal strategy) |
|---|---|---|
| Pushed from | executor call sites, hand-derived | inside the layer's own mutator, next to the state change |
| Inverse is | a semantic counter-operation via the public index API, with its own cascades | an absolute restore of the touched slot's captured pre-image, written directly into the layer's private containers |
| Idempotency | not required, not held | required by contract — earliest-pushed inverse wins under reverse replay |
| Failing op's partial work | invisible, unrecoverable | journaled like any other write |
| Coverage gap | silent leak | `GenericEvitaInternalError` from the maintainer's write hook |

## Decision

**Chosen: Approach D**, with per-layer choice between deep-copy mementos (small bounded deltas) and the
undo journal (large accumulating deltas). The deciding factor was **blast radius**: D isolates all new
complexity in the rare rollback path and provably cannot disturb the committed snapshot, whereas A puts
the success path at risk and F had already failed in production use. D would lose only if the
transactional diff layers themselves were removed or restructured — any such change supersedes this
record.

## Key technical details

- Gate: `LocalMutationExecutorCollector.execute` opens the savepoint only when `atomicRollback` is set
  **and** `Transaction.getTransactionalLayerMaintainer()` is non-null. WAL replay opts out via
  `ServerEntityMutation.shouldRollbackOnError() == false` (the whole in-memory transaction is discarded
  on failure instead); WARM_UP has no transaction, so the gate is never satisfied there.
- Savepoint machinery: `TransactionalLayerMaintainer` (`openSavepoint` / `commitSavepoint` /
  `rollbackSavepoint`), one savepoint at a time, lazy capture on first write-touch, three per-layer
  outcomes (mutated / created-in-savepoint / removed). SPI: `Snapshotable<M>` with the memento-
  independence and nested-layer-boundary invariants (INV-17/18 in
  `documentation/developer/stm/rules-and-invariants.md`).
- Defensive design: a layer write-touched inside a savepoint that does not implement `Snapshotable`
  throws `GenericEvitaInternalError` — a coverage gap is a loud programming error, never a silent leak.
- There is deliberately **no per-executor `rollback()`** anymore; the savepoint is the sole per-entity
  rollback path.
- gRPC parity (`92c18c28f`): `SessionFlags.TRANSACTION_CONTROLLED_EXTERNALLY` pins the session nest
  level so a caught remote mutation failure is reverted by the savepoint without poisoning the
  transaction — identical semantics to embedded.
- Known, accepted residue (documented at the sites): drawn primary-key / price-key sequence values are
  not un-consumed (harmless gaps); `IndexActivity` timestamps and capability-usage counters count work
  performed, not work that survived.

## Verification

- `TransactionalLayerMaintainerSavepointTest` covers the maintainer API.
- 31 `LongRunningSavepoint*` generational fuzz suites (one per data-structure family, under
  `evita_long_running_tests`) open a savepoint, apply a random mutation burst, commit or roll back, and
  compare against a reference model — asserting exactly the property `undoActions` failed on: post-
  rollback state equality, not merely "no exception".
- gRPC driver tests cover caught-and-continue (survivors commit, no orphan facet/price residue),
  uncaught escape, and close-with-rollback.
- The #1252 cliff: ≈10.6 s of pure memento copying at 32k entities (shared growing map, per-entity
  savepoints) before the journal; `O(1)` mark after.

## Consequences & open follow-ups

- **Per-write atomicity exists only in the ALIVE phase.** The user contract for WARM_UP ("no per-write
  rollback; compensate or rebuild") is documented in
  `documentation/user/en/deep-dive/bulk-vs-incremental-indexing.md`. Today a failed warm-up entity is
  worse than "partially indexed": its half-mutated indexes are flushed at session close while its
  entity body is never written — a queryable primary key with no storage part.
- **Porting to WARM_UP (assessed 2026-08-23, tracked as issue #1432):** the deep-copy mementos cannot be
  ported (the base structures are the large, structurally shared thing — a memento would be a deep copy
  per entity write), but the **undo-journal strategy is transaction-independent** and the warm-up
  writes flow through the *same* wrapper classes' delegate branch, so journal pushes could live at the
  same code sites. The two open risks: coverage enforcement (the maintainer's choke-point throw has
  only a partial analogue on the non-transactional path) and throughput (the bulk-ingest thread is
  94 % CPU-saturated; see `2026-07-31-bulk-ingest-write-path`). A cheaper correctness floor —
  extending the `WarmUpDataStoreMemoryBuffer.poison()` pattern from flush failures to mutation
  failures — is available independently.
- Nested savepoints are rejected by design (`openSavepoint` asserts); relax only with a LIFO mark
  design, which `UndoJournal`'s position-based API already permits.

## Related work

- `2026-07-10-more-optimized-data-structures` — the #760 umbrella; records the undo-journal decision at
  commit `e15657865`, and its PR #1268 carried this line's gRPC-parity commit.
- `2026-07-27-write-path-performance-tuning` — its transactional-layer key refactoring analysed the
  `Savepoint` memento map's conversion to a `long`-keyed structure.
- `2026-07-18-paged-index-corruption-and-flush-failure-boundary` — two of its corruption scenarios
  investigated savepoint rollback as a suspect and exonerated it; its flush-failure poison pattern is
  the precedent for the warm-up correctness floor above.

## Timeline

- **2026-06-25** — `a5770aa82`: savepoint mechanism, `Snapshotable` SPI, `undoActions` deleted (#569)
- **2026-06-26** — PR #1256 merged
- **2026-07-05** — `e15657865`: undo-journal memento strategy (#1252)
- **2026-07-07** — PR #1267 merged; `92c18c28f`: gRPC 1:1 parity
- **2026-07-10** — PR #1268 merged, carrying the gRPC-parity commit — line of work complete
- **2026-08-23** — record reconstructed retroactively (feature predates the ADR convention)
