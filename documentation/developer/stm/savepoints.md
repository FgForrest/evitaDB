# Savepoints -- Partial Rollback Within a Transaction

A transaction normally commits or rolls back as a whole (see [layer-lifecycle.md](layer-lifecycle.md)).
A **savepoint** is a finer-grained bracket *inside* a running transaction: it captures the diff layers
touched between opening and closing the savepoint and can revert exactly those changes -- while the
surrounding transaction keeps running.

This document describes the **snapshot / restore** lifecycle introduced for savepoints, the
`Snapshotable` SPI a diff layer implements to participate, and the two memento strategies used in
practice.

---

## Why savepoints exist

Client batch upserts apply many entity mutations in one transaction. One entity may legitimately fail
(e.g. a uniqueness conflict) and the caller wants to **skip it and keep going** rather than abort the
whole batch. Reverting a single failed entity mutation is non-trivial: a root mutation cascades through
the index executor and any reflected-reference / index-trigger cross-collection writes, all touching
many diff layers via the same maintainer.

The savepoint is the single, authoritative mechanism that reverts exactly that entity's changes in one
shot. It replaced the earlier hand-written per-executor undo actions.

`LocalMutationExecutorCollector` brackets each root entity mutation with a savepoint:

```
execute(rootMutation)
  ├─ maintainer.openSavepoint()                 // only when atomicRollback && a transaction is active
  ├─ apply mutation (cascades through index + cross-collection writes)
  ├─ success → commitSavepoint(savepoint)       // keep the changes, drop bookkeeping
  └─ failure → rollbackSavepoint(savepoint)     // revert this entity's diff-layer changes, keep the tx
```

A savepoint is only opened on the **atomic, transaction-bound** path. The two contexts that opt out:

- **Warm-up (non-transactional) writes** go in place to the index delegate -- there is no diff layer to
  snapshot, so a failed entity is left partially applied and must be retried by rebuilding.
- **WAL replay** (`atomicRollback == false`) discards the whole in-memory transaction on failure rather
  than recovering per-entity.

---

## Design rationale -- why snapshot/restore ("Approach D")

Reverting one failed entity mutation mid-transaction was designed against six candidate approaches.
The chosen one -- **snapshot/restore diff-layer savepoints** -- was picked deliberately over the
alternatives:

| Approach | Idea | Why rejected |
|----------|------|--------------|
| A -- nested sub-layer merge | Give each entity its own sub-layer and merge it into the parent on success | Per-structure op is a **binary merge + positional rebase**; needs a two-level read-through on all ~12 transactional structures and maintainer rewiring; worst of all, a merge bug silently corrupts the **committed** snapshot (the 99% success path). |
| B -- deferred buffer | Buffer all writes and apply only on success | Duplicates the whole write model; every structure needs a buffered mirror. |
| C -- validate first | Pre-validate the entity so writes never fail | Cannot cover every failure mode (some only surface mid-write); does not generalise. |
| E -- replay twice | Dry-run, then replay for real | Doubles write cost on the hot path; dry-run and real run can diverge. |
| F -- reliable inverse | Keep the hand-written inverse `Runnable`s but make them exhaustive | This is the old `undoActions` mechanism (~158 scattered inverses) -- brittle, easy to miss a case, the thing #569 set out to delete. |
| **D -- snapshot/restore** *(chosen)* | Each diff layer can snapshot and restore itself; a savepoint brackets the entity mutation | Per-structure op is a **unary copy**; changes **zero** read/write paths; needs no maintainer rewiring; a bug can only affect the ~1% rollback path, never the committed snapshot. |

The deciding factor is **blast radius**: approaches that touch the merge/read paths (A in particular)
put the common success path at risk, whereas snapshot/restore isolates all new complexity in the rare
rollback path. This is why D was chosen even though A was the original instinct.

`Snapshotable` also *replaced* the old `undoActions` mechanism (approach F) entirely -- the savepoint
is now the sole per-entity rollback path (`Ref: #569`).

---

## The `Snapshotable<M>` SPI

**Package:** `io.evitadb.core.transaction.memory`

A diff layer (a `*Changes` instance produced by `createLayer()`) opts in to savepoint support by
implementing `Snapshotable<M>`, where `M` is a per-implementation, immutable **memento** carrying that
layer's mutable state. It is the rollback counterpart of
`TransactionalLayerProducer.createCopyWithMergedTransactionalMemory` (the commit-side merge):

| Method                        | Purpose                                                                            |
|-------------------------------|------------------------------------------------------------------------------------|
| `M snapshot()`                | Captures the layer's current diff state into an opaque memento.                    |
| `void restore(M memento)`     | Resets the layer back to exactly that captured state, undoing every change since.  |
| `void releaseMemento(M memento)` | Called when the savepoint **closes** (committed *or* rolled back). Default no-op; drains any per-savepoint scratch state the layer kept. |

Implementing the interface is **opt-in** -- it is intentionally independent of
`TransactionalLayerProducer` because not every producer's layer is a genuine accumulating diff (some are
degenerate single-value layers, others are rebuildable derived caches whose memento is a cheap
invalidation rather than a copy). A layer that *is* an accumulating diff and can be mutated inside a
savepoint **must** implement it (see [INV-17](rules-and-invariants.md#inv-17-snapshotable-for-savepoint-touchable-layers)).

### Two invariants every implementation must uphold

1. **Memento independence.** `snapshot()` must copy the layer's mutable containers deeply enough that a
   later mutation of the layer cannot mutate the memento, and `restore()` must copy *out of* the memento
   (or the memento must be single-use), so the same memento can be restored more than once. Primitive /
   immutable-value fields are copied by value and need no defensive cloning.

2. **Nested-layer boundary.** A layer's memento captures only *its own* diff. Producer or element
   *values* the layer holds (e.g. nested `TransactionalLayerProducer` instances stored as map values or
   array elements) are captured **by reference only** -- their internal mutable state is the
   responsibility of *their own* `Snapshotable`, coordinated by the maintainer-level savepoint that
   snapshots the entire reachable layer forest. An implementation must therefore never deep-copy such
   values, and never reach into their internal state on restore.

---

## The maintainer's savepoint API

`TransactionalLayerMaintainer` exposes three methods and an opaque `Savepoint` handle:

| Method                                | Behaviour                                                                       |
|---------------------------------------|---------------------------------------------------------------------------------|
| `Savepoint openSavepoint()`           | Opens a savepoint. Only **one** may be open at a time -- nested savepoints are rejected. |
| `void commitSavepoint(Savepoint)`     | Accepts the savepoint: changes stay, bookkeeping is dropped, `releaseMemento` is called on every touched layer. |
| `void rollbackSavepoint(Savepoint)`   | Reverts every touched layer to its captured state (via `restore`), then `releaseMemento`. |

A single active savepoint is sufficient because a savepoint brackets exactly one root entity mutation
(including its nested cross-entity mutations), and entity mutations are processed one at a time on the
single-threaded transaction.

### Lazy capture on first write-touch

The memento is **not** taken when the savepoint opens -- that would snapshot every layer whether or not
it is touched. Instead, while a savepoint is open, the write hook
(`getOrCreateTransactionalMemoryLayer`) records each layer's pre-mutation state **the first time it is
touched for writing**. Reads never trigger a snapshot (the same shared diff layer backs a data
structure's read methods, so snapshotting on every read would capture no-op mementos).

Each touched layer ends the savepoint in exactly one of three states, tracked in the `Savepoint`
handle's per-layer memento map:

| Outcome                | Recorded as        | On `rollbackSavepoint`                                              |
|------------------------|--------------------|--------------------------------------------------------------------|
| Existed, then mutated  | a `snapshot()` memento | `restore(memento)` rewinds it to the pre-savepoint state.       |
| Created inside savepoint | `CREATED_IN_SAVEPOINT` sentinel | removed entirely -- it did not exist when the savepoint opened. |
| Existed, then removed  | wrapper + pre-savepoint memento | the wrapper is re-attached and its item `restore`d (e.g. a B+ tree node dropped during a split/merge). |

On `commitSavepoint` all three outcomes simply drop their bookkeeping and call `releaseMemento` on the
still-attached layers; no diff layer is modified.

> **Defensive design.** A layer mutated (or removed) inside a savepoint that does **not** implement
> `Snapshotable` cannot be reverted. The maintainer treats this as a programming error and throws
> `GenericEvitaInternalError` rather than silently leaving a partial-rollback gap.

---

## Two memento strategies

Because capture is lazy and a mutation *always* immediately follows `snapshot()`, a naive
"deep-copy the whole accumulated delta" memento pays `O(accumulated-delta)` per savepoint -- the
**per-entity rollback cliff**. Two strategies exist; pick per layer:

### 1. Deep-copy memento -- for small / bounded deltas

The memento is an immutable record holding defensive copies of the layer's mutable containers.
`restore()` copies state back out of it. Straightforward and used where the diff is naturally small.

Example: `MapChanges.BaseMapChangesMemento` (see `io.evitadb.index.map.MapChanges`).

### 2. Undo journal -- for large accumulating deltas

`UndoJournal` (`io.evitadb.core.transaction.memory.UndoJournal`) is an append-only log of **inverse
operations** that makes `snapshot()` / `restore()` **delta-bounded** instead of proportional to the
whole accumulated diff:

- `snapshot()` captures only a `mark()` -- an `int` position -- which is `O(1)`.
- Every mutator `push`es a small inverse operation (a `Runnable`) while the journal is active.
- `restore()` (`rollbackTo(mark)`) pops entries down to the mark and runs each inverse in strict
  **reverse** order. Cost scales with the number of *intra-savepoint* mutations (one entity's delta),
  not the accumulated transaction delta.
- `releaseMemento()` (`releaseFrom(mark)`) discards entries at/above the mark **without** running them,
  used when the savepoint commits (the changes are kept, so their inverses are never needed).

Each inverse should be an **absolute** restore of the touched state, so that under reverse replay the
earliest-pushed inverse for a given key runs last and wins -- restoring the pre-savepoint value
regardless of how many times that key was touched. This is why layers keeping such scratch state
override `releaseMemento` to drain the journal when the savepoint closes.

**Why the journal (rather than a faster copy).** Deep-copy mementos have a measured `O(N²)` cliff for a
shared growing index under per-entity savepoints (≈10.6 s of pure memento-copy at 32k entities for the
map case). Three candidates were weighed for `#1252`: **journaling**, **CHAMP-backed mementos**, and
**targeted snapshotting**. The undo journal won because it makes `snapshot()` truly `O(1)` and
`restore()` proportional to one entity's delta -- with no change to the layer's steady-state
representation -- so the common commit path pays nothing.

---

## Testing

Savepoint-capable layers are exercised by the `LongRunningSavepoint*` fuzz tests under
`evita_long_running_tests` (per data structure -- map, price indexes, facet, hierarchy, ...) and the
`TransactionalLayerMaintainerSavepointTest` unit test. The fuzz framework opens a savepoint, applies a
random burst of mutations, then either commits or rolls back and compares the layer against a reference
model -- catching memento-independence and nested-boundary violations that only surface after many
generations.

See [testing.md](testing.md) for the general generational / property-based testing pattern these build
on.
