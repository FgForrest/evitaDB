# Savepoints -- Partial Rollback Within a Transaction

A transaction normally commits or rolls back as a whole (see [layer-lifecycle.md](layer-lifecycle.md)).
A **savepoint** is a finer-grained bracket *inside* a running transaction: it captures the diff layers
touched between opening and closing the savepoint and can revert exactly those changes -- while the
surrounding transaction keeps running.

This document describes the **snapshot / restore** lifecycle introduced for savepoints, the
`Snapshotable` SPI a diff layer implements to participate, the two memento strategies used in practice,
and the [warm-up counterpart](#the-warm-up-counterpart) that provides the same per-entity guarantee on
the non-transactional bulk-indexing write path, where there are no diff layers to snapshot.

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
  ├─ open a savepoint                    // only when atomicRollback -- which kind follows the write path
  │    ├─ transaction active → maintainer.openSavepoint()
  │    └─ warm-up            → WarmUpSavepoint.open()
  ├─ apply mutation (cascades through index + cross-collection writes)
  ├─ success → commit the savepoint      // keep the changes, drop bookkeeping
  └─ failure → roll the savepoint back   // revert this entity's changes, keep going
```

A savepoint is only opened when **atomic rollback is requested**. **WAL replay** opts out
(`atomicRollback == false`): it discards the whole in-memory transaction on failure rather than
recovering per-entity.

The rest of this document describes the transaction-bound savepoint. Warm-up (non-transactional) writes
go in place to the index delegate and have no diff layer to snapshot, so they are reverted by a
different mechanism built on the same journal strategy -- see
[The warm-up counterpart](#the-warm-up-counterpart).

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

## The warm-up counterpart

**Primary class:** `WarmUpSavepoint` (`io.evitadb.core.transaction.memory`). Its type JavaDoc is the
authoritative design summary; this section is the map.

Everything above rests on the maintainer: it owns the diff layers, it is the write hook that captures
them, and it is the choke point that refuses a layer it cannot revert. In WARM_UP (bulk indexing) there
is no transaction and therefore no maintainer at all -- `Transaction.getTransactionalLayerMaintainer()`
is `null` and every structure takes its **delegate branch**, writing its real state in place. A
mid-write failure used to leave the indexes half-mutated with no way back, which is the orphan-key gap
this mechanism closes.

`WarmUpSavepoint` supplies exactly what the maintainer supplied, and nothing else:

| The maintainer provides | Warm-up replacement |
|-------------------------|---------------------|
| Ownership -- "a savepoint is open" | A `ThreadLocal` holding the open savepoint; `getIfOpen()` returns `null` when none is. |
| The write hook that captures on first touch | Each participant calls the savepoint itself, at the entry of its own mutator. |
| Per-layer memento bookkeeping | One `IdentityHashMap` of write-touched participants, plus a single shared `UndoJournal`. |
| The defensive throw on an unrevertible layer | `verifyRollbackSupported(...)`, driven by a declaration each structure makes. |

`rollback()` replays the journal in strict reverse and then releases every captured memento;
`commit()` discards the journal entries without running them and releases the mementos. Both **detach
from the thread first**, so the restore / release work -- which runs through the participants' ordinary
mutators -- cannot re-record into the savepoint that is closing.

### Why one journal is enough for every participant

Each recorded inverse is an **absolute restore of the state its own operation touched**. Between two
participants the interleaving therefore carries no meaning, which is what lets first-touch mementos and
per-operation inverses share a single journal. *Within* one participant the order does carry meaning,
and it is what makes per-operation inverses correct: replayed newest-first, the earliest-pushed inverse
for a given slot runs last and wins, so the pre-savepoint value survives however many times that slot
was rewritten inside the savepoint. This is the same absolute-inverse rule the transactional
`UndoJournal` already requires.

### The four recording APIs

| API | For a participant that... | Dedup |
|-----|---------------------------|-------|
| `recordFirstTouch(Snapshotable)` | already implements `Snapshotable` -- the memento mechanism the maintainer drives is reused verbatim | first touch only |
| `claimFirstTouch(Object)` + `push(Runnable)` | is **not** a `Snapshotable` and captures its own `O(1)` pre-image | first touch only |
| `push(Runnable)` | must capture per operation, because its whole-state pre-image is too expensive | none |
| `writeLayer(creator, baseNode)` | is its **own** diff layer -- the B+ tree nodes | first touch only |

The two first-touch APIs are **mutually exclusive per participant**: `claimFirstTouch` throws
`GenericEvitaInternalError` when handed a `Snapshotable`, because routing one through the self-capture
path would skip the activation its own memento mechanism performs (for a journal-backed `snapshot()`,
the snapshot is what *arms* the journal) and would leave `releaseMemento` with nothing to hand back.

Every recording call must happen **before** the forward mutation, and every inverse must be **total** --
it may never throw for a benign reason. See [Rollback failure is fatal](#rollback-failure-is-fatal).

`writeLayer` is the packaged form of the first row, for the roughly hundred B+ tree node mutation sites
that repeat `layer = getOrCreateTransactionalMemoryLayer(this); if (layer == null) { mutate own fields }
else { mutate the layer }`. It resolves the layer exactly as before and folds the first-touch record into the
layer-null branch, so the mutator reaches the savepoint without naming it. Its `baseNode` flag is not a
transaction test -- it says whether the instance may own a diff layer at all.

### Which granularity a participant picks

It follows from the **cost of the pre-image**, not from the participant's shape:

- **First touch**, when the participant's *entire* mutable state has an `O(1)` pre-image -- a scalar, or
  a wrapper whose writes replace an array *reference* rather than mutating the array in place. One
  capture covers every write in the savepoint, and the journal stays bounded by participants rather than
  by writes.
- **Per operation**, when it does not. The collection wrappers mutate a large delegate `HashMap` /
  `HashSet` / `ArrayList` in place, so a whole-state pre-image is a deep copy of the accumulated base
  structure -- the same `O(N²)` cliff the journal strategy exists to avoid. They capture the one slot
  each operation overwrites instead.

The cost that decides it is the **total** cost, not the cost at the moment of capture, and
`TransactionalBitmap` is the case that taught us the difference. It first captured a copy-on-write
`PersistentRoaringBitmap.clone()`, which is only pointer work -- proportional to containers, not to
cardinality -- and so read as an `O(1)`-ish pre-image. What the clone really does is *freeze* every
container on both sides, so each subsequent write to a shared container has to copy it out first (up to
a `long[1024]`, 8 KB), and bulk ingest re-clones and re-defrosts once per entity. On the 972k-article
reference corpus that deferred copying was 13.2 % of all allocation on the bracketed warm-up write
path. The bitmap now journals per operation like the collection wrappers.

### Per-family strategies

| Family | Strategy | Pre-image |
|--------|----------|-----------|
| `TransactionalReference`, `TransactionalBoolean` | first touch, self-captured | the single held value |
| `TransactionalIntArray`, `TransactionalObjArray`, `TransactionalComplexObjArray` | first touch, self-captured | the whole delegate array **reference** (writes always allocate a fresh array, so the outgoing reference is already an immutable snapshot) |
| `TransactionalMap`, `PersistentTransactionalMap`, `TransactionalSet`, `TransactionalList` | per operation | the one slot / membership each operation overwrites (`WarmUpMapJournal` for the map pair). `clear()` is the exception -- it copies the whole delegate, being a whole-structure operation anyway. Iterators and views are swapped for journalling wrappers while a savepoint is open. |
| `TransactionalBitmap` | per operation | the membership each operation actually changes -- one inverse per single-record write, one inverse per bulk write covering exactly the ids whose membership that call flipped |
| B+ tree nodes and `UnorderedLookupTree` nodes | first touch, via `writeLayer` | each node's own `Snapshotable` memento, bounded by block size |
| Composite index layers -- `CatalogIndex`, `AttributeIndex`, `ChainIndex`, the facet and price index layers | *(nothing to record)* | their diff layer is pure in-transaction bookkeeping that only a commit-merge consumes; outside a transaction there is no state of their own to rewind, and the real state sits in contained structures that journal their own writes |
| Memoized caches -- `FilterIndex`, `SortIndex`, `OwnerUniqueIndex`, `HierarchyIndex`, `RangeIndex`, `ReferenceTypeCardinalityIndex`, `UnorderedLookupTree`, the price indexes | first touch | *none* -- the inverse is a re-invalidation, see [Accepted residues](#accepted-residues). Where the cache lives in a helper (`SortIndexChanges`, `ChainIndexChanges`) the helper is the `Snapshotable` that registers itself. |
| Index population counters (`IndexPopulation`) | first touch, self-captured | a clone of the fixed-size per-`(EntityIndexType, Scope)` count array |
| `DataStoreChanges` | first touch, `Snapshotable` **plus** per-write record inverses | a **journal position** for its in-memory state, a **stored record's pre-image** for each direct write -- see below |

`DataStoreChanges` is worth singling out because it is what makes the entity *body* atomic with its
indexes, and because it is the one participant that needs **both** granularities at once.

Its memento is `DataStoreChangesMemento(int mark)`, an `O(1)` position in its own internal journal, and
its `snapshot()` is what lazily allocates that journal -- which is precisely why `recordFirstTouch` must
run at the *entry* of a mutating method rather than after the fact. That mark rewinds the layer's
**in-memory** state: the dirty-index bookkeeping and the trapped storage-part cache.

It does **not** rewind a write that reached the persistence service, and root entity mutations issue
exactly those: they run with `trapChanges == false`, so `ContainerizedLocalMutationExecutor#commit`
calls `DataStoreChanges#putStoragePart` / `#removeStoragePart`, which write the record through. That
loop can fail part-way through an entity whose body spans several parts -- an attributes part written,
the references part throwing -- and the parts already written would otherwise stay changed in the trunk
while the indexes rolled back cleanly, i.e. a half-updated, fetchable entity body. Note that the rollback
would *report success* in that case: the poison backstop only fires when the rollback itself throws.

So each direct write additionally reads the record's pre-image before overwriting it and pushes an
absolute restore into the open savepoint -- re-put what was there, remove what was not (and for a part
whose primary key is assigned inside the write, remove whatever key the write ended up filing it under).
The restores are absolute, so strict-reverse replay makes the earliest capture for a record win, and the
record ends at its pre-savepoint value however many times the entity rewrote it. When no savepoint is
open this costs nothing beyond the `ThreadLocal` read the first-touch record already needed; with one
open it is one storage read per direct write, which for a newly inserted entity misses in the offset
index without deserializing anything.

### The single-open-site invariant

**`WarmUpSavepoint.open()` has exactly one call site**, the mutation bracket in
`LocalMutationExecutorCollector`, and it is taken on the branch where the transactional maintainer is
absent. Since `Transaction#getTransactionalLayerMaintainer()` is `null` exactly when
`Transaction#isTransactionAvailable()` is `false`, it follows that:

> While a warm-up savepoint is open, `!isTransactionAvailable()` is **unconditionally true**.

A great deal rests on this. Every index mutator whose journalling sits behind an
`if (!isTransactionAvailable())` gate is correct only because that gate is always taken while a
savepoint is open, and the delegate-branch backstop below guards only the no-transaction path for the
same reason. A second opening site would invalidate all of it at once, so the invariant is **asserted
rather than assumed**: `WarmUpRollbackConformanceTest` scans the engine sources and pins the single
opening site, the maintainer-absence guard on it, and an allowlist of the `io.evitadb.index` sources
permitted to branch on transaction availability at all -- each allowlist entry carrying the reason its
gates are not a rollback hole.

### Enforcement -- declared, not inferred

Journalling is a **per-structure obligation** and warm-up has no maintainer to enforce it centrally.
The backstop is `TransactionalLayerCreator#supportsWarmUpRollback()`, which **defaults to `false`**:

- `WarmUpSavepoint.verifyRollbackSupported(...)` is called from
  `Transaction#getOrCreateTransactionalMemoryLayer(...)` at the moment it is about to hand back `null`
  -- i.e. exactly when a structure takes its delegate branch.
- With a savepoint open and the declaration absent, the mutation **fails immediately**.

Exactly one of two conditions earns a `true`: the delegate branch **journals what it writes**, or it
**writes nothing of its own** (the composite index layers in the table above). The declaration is
*honoured, not verified* -- returning `true` without meeting one of them silently reintroduces the gap.
The `false` default is what makes the mechanism safe by construction: a structure ported to the warm-up
write path without journalling is caught the first time a bracketed mutation reaches it, rather than
discovered later as an index a rollback quietly failed to rewind.

> **What the check costs.** The two short-circuits in `verifyRollbackSupported` widen in cost: one
> `ThreadLocal` read (`null` exactly when no root entity mutation is in flight), then an interface call
> on a handful of small final implementations. This sits on the bulk-ingest write path, so outside a
> bracket the check is the single predicted-null read that every delegate write branch pays anyway.

### Thread confinement

The savepoint lives in a `ThreadLocal` rather than being passed down the call chain. The warm-up write
path fans out through the whole index-mutation machinery, and plumbing a context parameter through it
is the structural scattering that made the historical hand-written undo actions unmaintainable. It is
sound because `CatalogState.WARMING_UP` is contractually single-threaded -- a catalog being bulk loaded
has exactly one writer. Concurrent warm-up writers remain unsupported; the savepoint does not newly
defend against them, and the type is deliberately not thread-safe.

### Accepted residues

A warm-up rollback rewinds index and storage state. Four things it deliberately does **not** restore:

1. **Sequences are not rewound.** The primary-key, index-key and internal-price-id sequences guarantee
   uniqueness and monotonicity, not contiguity -- an `AtomicInteger` cannot be un-consumed. A value drawn
   for an entity whose mutation is then reverted leaves a harmless gap. See the field JavaDoc on
   `EntityCollection#pkSequence`.
2. **Memoized caches are invalidated, not restored.** Every derived cache -- filter formulas, sort-array
   caches, memoized cardinalities, enveloping-range caches -- has its inverse push a *re-invalidation*
   rather than the captured value. The underlying structures are restored absolutely from their own
   mementos, so a dropped memo costs one recomputation, whereas a restored one would have to be *trusted*
   to have been valid, which nothing at the restore site can establish.
3. **Handles taken before the rollback go stale.** Because several restores are reference swaps, a caller
   holding e.g. an array obtained from `TransactionalIntArray#getArray()` before the rollback keeps the
   rewound-away instance. This is the contract of every wrapper whose pre-image is a bare reference;
   warm-up readers fetch through the accessor per call. (`TransactionalBitmap` is *not* one of them any
   more -- it journals per operation and restores in place, so a bitmap handle stays live across a
   rollback.)
4. **Storage is rewound by content, not by bytes.** The store is append-only, so putting a record's
   pre-image back appends another record rather than un-writing the failed one, and dropping a record the
   mutation created marks it removed rather than reclaiming its bytes. What a reader sees is exactly the
   pre-savepoint state; what the file holds is the failed writes *and* their inverses, until the next
   compaction. The same applies to the write `KeyCompressor`: a key first seen by a write that is then
   reverted keeps its assigned id, because the compressor guarantees ids are unique, not that every id is
   in use -- the same harmless gap as an un-consumed sequence value.

Lazily created cache helpers (`SortIndexChanges`, `ChainIndexChanges`) installed inside a rolled-back
mutation are also left in place. They hold nothing but rebuildable caches -- which journal themselves --
so an installed instance is indistinguishable from the `null` slot it replaced.

### Rollback failure is fatal

A transactional savepoint whose rollback fails is survivable: the diff layers are thrown away with the
transaction anyway. A warm-up rollback that fails is not, because the writes went **in place** -- there
is no layer to discard, and the live indexes are left half-mutated with no second chance.

`LocalMutationExecutorCollector` therefore **poisons the data store buffers** before recording the
failure: the catalog-level buffer and the buffer of every entity collection that took part in the
(possibly cross-collection) mutation refuse every future flush, so no later flush can persist state that
could not be rewound. The original mutation failure stays the exception thrown to the caller; the
rollback failure is attached to it as a suppressed cause. This is why every journalled inverse must be
total.

### Always on -- there is no switch

The bracket is unconditional. It is opened for every root entity mutation that requests atomic rollback
and finds no transactional maintainer -- which is every warm-up entity upsert and removal. The only
caller that opts out is **WAL replay**, and it opts out of the transactional savepoint too
(`atomicRollback == false`), because it discards the whole in-memory transaction on failure rather than
recovering per-entity.

The mechanism was developed behind an internal system property while its cost was being measured. The
measurement settled the question -- roughly **+2 % bulk-ingest CPU and +1.8 % wall clock** on the
972k-entity reference corpus -- and the property, the test-only setter that went with it, and the JUnit
fencing that the setter's process-wide mutability forced onto the fuzz suites were all deleted together.
Nothing on the warm-up write path is conditional at runtime any more, and the mechanism holds no mutable
static state.

The user-visible contract follows directly: an entity upsert or removal that fails during bulk indexing
is reverted completely -- indexes, storage parts, everything -- the session stays usable, and subsequent
writes and the transition to ALIVE proceed normally. See
[Bulk vs. incremental indexing](../../user/en/deep-dive/bulk-vs-incremental-indexing.md#atomicity-of-individual-writes)
for how that is stated for users.

---

## Testing

Savepoint-capable layers are exercised by the `LongRunningSavepoint*` fuzz tests under
`evita_long_running_tests` (per data structure -- map, price indexes, facet, hierarchy, ...) and the
`TransactionalLayerMaintainerSavepointTest` unit test. The fuzz framework opens a savepoint, applies a
random burst of mutations, then either commits or rolls back and compares the layer against a reference
model -- catching memento-independence and nested-boundary violations that only surface after many
generations.

Those suites extend `AbstractSavepointFuzzTest`, a **mode-parametrized** harness: each suite declares
one generation factory and inherits four tests -- transactional savepoint rollback and commit, plus the
warm-up savepoint rollback and commit driven against the same reference model. The two exceptions carry
the reason in their JavaDoc (a diff layer with no warm-up counterpart, and the framework's own
self-validation suite, which must not be built on the helpers it validates).

Every generation also asserts a **mid-savepoint read**: after the mutations and before the rollback or
commit, the structure is read through its public views and must differ from the pre-savepoint oracle.
This is what catches a memoized cache the rollback forgot to invalidate -- a read taken only *after* the
rollback repopulates that cache from correct state and never notices.

The warm-up mechanism additionally has:

| Test | Pins |
|------|------|
| `WarmUpSavepoint*RollbackTest` (per structure family) | the journalling itself: a savepoint opened directly, mutations applied, rollback compared against the pre-image |
| `WarmUpSavepointTest` | the savepoint's own contract -- nesting rejected, detach-before-work, the `Snapshotable` / self-capture exclusivity |
| `WarmUpRollbackBackstopTest` | that an undeclared structure mutated inside a savepoint fails loudly |
| `WarmUpRollbackConformanceTest` | the source-level invariants: the single opening site, its maintainer-absence guard, the `supportsWarmUpRollback()` declarations, and the transaction-availability allowlist |
| `EntityAtomicMutationRollbackWarmUpFunctionalTest` | the end-to-end behaviour -- a failed entity in a bulk load leaves neither index entries nor a storage body behind |

The warm-up fuzz methods run on their own budget (`-DwarmUpFuzz.seconds`) rather than the
minute-bounded budget the transactional methods use; raise it for a deep sweep.

See [testing.md](testing.md) for the general generational / property-based testing pattern these build
on.
