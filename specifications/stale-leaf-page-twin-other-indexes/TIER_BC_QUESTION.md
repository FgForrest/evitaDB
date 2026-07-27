# Tier B / Tier C design question — pre-WAL and post-replay dirty-scope B+ tree validation

Self-contained question for a stronger reviewer model with **no access to the repository**. All code
facts below were verified by reading the current tree on 2026-07-15. Please answer the numbered
DECISIONS at the end; the CONTEXT and FINDINGS give you everything needed.

---

## 1. Background

evitaDB is an in-memory NoSQL search index (Java 17). A historical "stale-leaf-page-twin" bug had a
writer race persist two overlapping B+ tree leaf pages (two pages whose key ranges overlap), which
corrupts the on-disk page list. The chosen remediation is **fail-fast detection at every layer** (no
silent healing — the paged format never shipped in a release, so no production catalog can carry a
twin; a twin can now only originate from a live bug, and defensive design says surface it loudly).

Three sibling transactional B+ tree implementations exist, each a hand-written generic node hierarchy:

- `TransactionalLongBPlusTree<V>` — natural `long` keys; `extends AbstractTransactionalBPlusTree`.
- `TransactionalElementBPlusTree<E>` — `int` keys via `keyExtractor.applyAsInt(value)`, no parallel
  key array on leaves; `extends AbstractIntKeyedBPlusTree extends AbstractTransactionalBPlusTree`.
- `TransactionalBucketBPlusTree<K extends Comparable<K>>` — comparator-`K` keys, `key → record-set`
  buckets; **standalone** (does NOT extend the abstract base; its own `Cursor<K>`/node family).

(There are also `TransactionalObjectBPlusTree` and `TransactionalIntToLongBPlusTree`, which are OUT
of scope for this change — the twin bug only affects the three paged index trees above.)

### What already shipped (Phase 1 + Phase 2 Tier A), green:

- **Phase 1 (load-time):** a per-tree `assertCrossLeafBoundaries(leaves, desc)` runs when a tree is
  assembled from persisted single-leaf pages: adjacent leaves must not overlap (last key of leaf *i*
  sorts strictly before first key of leaf *i+1*), and each leaf's interior keys strictly increase.
  Throws `GenericEvitaInternalError` with a remediation hint. This catches a corrupt page list at
  LOAD, i.e. after corruption became durable.
- **Phase 2 Tier A (op-time, always on):** on every leaf-level key mutation through the public
  insert/upsert primitive, three O(1) zero-alloc asserts fire — **Check T** (tail: when a leaf's last
  key rises, assert it stays below the fence separator = the successor leaf's first key, located by
  walking the insert cursor up to the nearest non-rightmost descent level — complete across parent
  boundaries), **Check H** (head: when a leaf's first key lowers, assert the predecessor leaf's last
  key is strictly below it — same-parent left sibling O(1), or a rare O(height) right-spine walk when
  the leaf is its parent's leftmost child), and **Check S** (a separator-order belt inside
  `updateParentKeys`). Plus a split assert. This gives a **complete op-time induction**: a tree that
  only ever mutated through the asserted primitive cannot emit an overlapping page list at flush.
  Tier A is the ONLY line of defense in warm-up (bulk-load) mode, which has no WAL and no
  transaction commit. Tier A is mirrored across all three trees and verified (churn/acceptance
  suites green, no false positives incl. mid-rebalance).

The design authority for Tier A was an advisory doc; its closing note on Tier B/C reads verbatim:

> Tier B's dirty-leaf validation independently re-derives both half-invariants for leaves a
> transaction touched (it reads actual neighbors, so it is complete by construction on its scope);
> Tier C likewise on replay. Tier A completeness matters because warm-up mode has neither.

## 2. What Tier B and Tier C must do (from the change proposal, quoted)

> ### Tier B — pre-WAL dirty-scope validation (kill-switchable, default on)
> BEFORE the transaction's mutations are appended to the shared WAL: for every B+ tree carrying a
> diff layer in the transaction's transactional memory, validate the MODIFIED leaves against their
> immediate neighbors and bracketing separators — O(dirty leaves), two comparisons per leaf the
> transaction already rewrote; full-tree walks are forbidden here. On failure the commit is rejected
> (commit progress completes exceptionally), and the shared WAL verifiably does NOT contain the
> transaction.
>
> **Hook-placement caution (verified against the current tree):** the stage
> `ConflictResolutionAndWalAppendingTransactionStage` exists, but by this proposal's own rationale
> the isolated diff may already be discarded when that asynchronous stage runs — the stage may carry
> only the logical mutation stream, not the live diff layers. If so, hook the validation at
> TRANSACTION CLOSE (commit initiation), while the transactional memory is still alive; that still
> runs strictly before the shared-WAL append. The GUARANTEE ("the WAL verifiably does not contain the
> transaction"), not the stage, is normative; discovery latitude granted on the exact location.
>
> **Savepoint interaction:** with the atomic-upsert savepoint architecture an object may carry a
> STACK of diff layers, not a single one; enumerate the dirty leaves from the EFFECTIVE (merged)
> state of the stack, not from one layer.
>
> Kill switch: `evita.bPlusTree.preCommitValidation=false`, default enabled.
>
> ### Tier C — coordinator post-replay validation (always on)
> In trunk incorporation (`TrunkIncorporationTransactionStage`), after a transaction's mutations are
> replayed and the merged tree versions are produced, BEFORE the new catalog version propagates to
> the live view: run the same dirty-scope validation on the trees modified by the replay (the replay
> must track which trees it touched — discovery latitude granted). On failure complete the commit
> progress exceptionally with a message that states the poison-pill caveat explicitly: the
> transaction is already durable in the WAL, a restart will replay it, remediation is restore/rebuild
> plus a bug report. This tier is the authoritative backstop for shape-dependent bugs that pass Tier
> B (the isolated run mutates the session's snapshot; the replay mutates the possibly-different trunk
> shape).

Acceptance: Tier B latency must show no measurable single-shot commit regression; only Tier B is
gated; Tier A/C are unconditional.

## 3. Transaction commit architecture (verified in code)

evitaDB's write path during a transaction applies mutations to STM (software-transactional-memory)
copies of the indexes — every mutated node produces a **diff layer** so the session reads its own
writes. Each B+ tree node (`BPlusInternalTreeNode`, `BPlusLeafTreeNode`) implements
`TransactionalLayerProducer` and has its own `createLayer()` / `createCopyWithMergedTransactionalMemory`.
Leaf diff layers carry a `boolean dirty` flag.

`TransactionalLayerMaintainer` holds a single **private** `HashMap<TransactionalLayerCreatorKey,
TransactionalLayerWrapper<?>>`. The key is `(creator.getClass(), creator.getId())`. So the map
contains one entry per touched producer — **including every individual dirty leaf and internal
node** — but they are NOT grouped by owning tree, and the map is not publicly iterable.

There are (at least) three commit finalizers:

**(a) `TransactionWalFinalizer` — the isolated session commit (this is the Tier B window).**
Its `commit(TransactionalLayerMaintainer)`:
```java
closeRegisteredCloseables();
if (this.walPersistenceService != null) {
    // ASYNC: copy the already-recorded isolated WAL mutation stream into the shared WAL
    this.catalog.commitWal(catalogVersionAtStart, transactionId, schemaVersionAtStart,
                           walPersistenceService, commitProgress);
} else {
    this.commitProgress.complete(new CommitVersions(...));
}
```
It **never merges the trees** — the mutations were already recorded to the isolated WAL via
`registerMutation` during the session; commit just hands that stream off. The STM diff layers are
then discarded (the shared catalog is NOT advanced here — it is advanced later by replaying the WAL).
So at isolated commit there is **no merged tree** and the only dirty-leaf enumeration is the
node-level maintainer map. The maintainer IS passed in, so I do have it here.

**(b) `TransactionTrunkFinalizer` — trunk incorporation replay (this is the Tier C window).**
Its `commitCatalogChanges(...)`:
```java
this.catalogToUpdate.flush(catalogVersion, lastProcessedTransaction);          // (1) DISK FLUSH first
final Catalog newCatalog =
    this.lastTransactionLayer.getStateCopyWithCommittedChanges(this.catalogToUpdate); // (2) MERGE
this.lastTransactionLayer.verifyLayerWasFullySwept();
this.committedCatalog = newCatalog;                                            // (3) then propagate
```
`getStateCopyWithCommittedChanges(catalog)` recurses catalog → collections → indexes → trees →
`tree.createCopyWithMergedTransactionalMemory(...)`, which recurses **only into changed subtrees**
(an unchanged node returns itself without recursion). So the merge naturally visits O(dirty nodes).
Note (2) MERGE happens AFTER (1) DISK FLUSH — consistent with Tier C's poison-pill framing (already
durable). `TrunkIncorporationTransactionStage` calls this inside `processTransactions(...)` and then
`propagateCatalogToSharedView(...)`; exceptions surface via `commitProgress.completeExceptionally(ex)`.

**(c) `IsolatedTransactionalLayerMaintainerFinalizer` (nested in `Transaction.java`)** — merges a
single `txRoot` via `getStateCopyWithCommittedChanges(txRoot)`; used for standalone transactional
objects, not the catalog WAL path.

The top-level tree's own `createCopyWithMergedTransactionalMemory` looks like (Long tree):
```java
final BPlusTreeNode<?> theRoot = transactionalLayer.getStateCopyWithCommittedChanges(this.root).orElseThrow();
if (theRoot instanceof BPlusLeafTreeNode<?> leafNode) {
    return new TransactionalLongBPlusTree<>(..., transactionalLayer.getStateCopyWithCommittedChanges(theLeafNode),
                                            transactionalLayer.getStateCopyWithCommittedChanges(this.size)...);
} else if (theRoot instanceof BPlusInternalTreeNode internalNode) {
    return new TransactionalLongBPlusTree<>(..., transactionalLayer.getStateCopyWithCommittedChanges(internalNode), ...);
}
```

### Neighbor navigation facts
- A bare leaf node has **no parent pointer, no sibling/next-leaf link, and no back-reference to its
  owning tree.** Leaves are located only by descending a cursor from the root.
- The abstract base has `CursorWithLevel` with `getCursorForPreviousNode()` / `getCursorForNextNode()`
  (used by rebalancing) — generic sibling-leaf navigation exists, but only from a cursor you already
  hold, i.e. you must first descend to the leaf from the root.
- `leafPageHandles()` (base) returns ALL leaves via a full `enumerateLeaves()` walk and is defined
  only for paging variants — it is O(tree), not O(dirty), and not available on all three trees.
- To check a dirty leaf L's two half-invariants I need `predecessor(L).lastKey` (for the head side)
  and either `successor(L).firstKey` or the fence separator (for the tail side). None is reachable
  from a bare L.

## 4. My proposed design (for critique)

### Tier C — embed in the merge primitive
Put the dirty-leaf boundary re-validation **inside each tree's
`createCopyWithMergedTransactionalMemory`**: as the merged copy is produced, for each leaf that was
dirty, re-derive both half-invariants against its neighbors in the just-merged structure and throw a
poison-pill `GenericEvitaInternalError` on violation. Rationale: it is O(dirty) by construction (merge
already recurses only into changed subtrees), always-on (fires on every trunk merge), needs no new
stage plumbing, and the throw propagates through `commitCatalogChanges` → `processTransactions` →
`completeExceptionally`. It also fires on finalizer (c)'s isolated merge — I read that as bonus
coverage, not a regression.
- Open worry: is embedding in the merge method (vs an explicit hook in
  `TrunkIncorporationTransactionStage` after the merge, before propagation) acceptable given the
  proposal names the stage? Both run at the same logical point; the merge-embedded version is
  strictly narrower in blast radius (localized to the three tree classes I already own).

### Tier B — the crux, three candidate approaches, none clean
At isolated commit there is no merged tree; the maintainer map is node-level with no tree/neighbor
context; a bare dirty leaf cannot validate itself.
- **(B-walk)** A per-dirty-tree method that walks the tree once and checks only dirty leaves.
  Simple, reuses cursor navigation. But it is O(touched-tree-size), which the proposal explicitly
  forbids ("full-tree walks are forbidden here") and which regresses commit latency on a huge tree
  that a transaction barely touched (senesi-scale trees hold 380k+ entries).
- **(B-relocate)** Iterate the maintainer's dirty leaves; for each, re-locate it in its tree via a
  root cursor (using the leaf's first key) to obtain neighbors. O(dirty × height). Requires a
  leaf → tree back-reference (or a maintainer accessor that yields (tree, dirty-leaf) pairs), which
  is invasive to the COW node model and to the maintainer's encapsulation.
- **(B-parentref)** Give leaves a transient parent/tree back-reference so a dirty leaf can find its
  neighbors directly. Most invasive to the immutable-node/COW design.

### The deeper question behind Tier B's value
Tier A already gives a **complete op-time induction** on the isolated session (every leaf mutation
was asserted as it happened), and Tier C independently re-validates the **authoritative** trunk
state that actually gets flushed and propagated. Tier B's marginal value is therefore: (i) catch a
bug in Tier A itself or a leaf-mutation path that bypassed the asserted primitive, and (ii) reject to
the client **synchronously with the WAL still clean** (recoverable, no poison pill), whereas Tier C
is post-durability. That client-facing clean-rejection property is the real reason Tier B must exist
as a distinct tier — but it only has teeth if it can actually enumerate + validate the dirty leaves
cheaply, which is exactly what the architecture makes awkward.

## 5. DECISIONS I need from you

1. **Tier B enumeration + neighbor navigation.** Which approach (B-walk / B-relocate / B-parentref /
   something else) is the right one given the constraints? Specifically: is the "O(dirty leaves), no
   full-tree walk" wording a hard requirement, or is an O(touched-tree) walk over only the trees a
   transaction actually dirtied acceptable in practice? If B-relocate, what is the least-invasive way
   to get from a dirty leaf (or the maintainer map) to (its tree + its neighbors) without breaking
   the COW node model or the maintainer's encapsulation?

2. **Tier B hook location.** Is `TransactionWalFinalizer.commit(...)` (before `catalog.commitWal`,
   while the maintainer is live) the correct choke point? It has the maintainer but no tree handles.
   Is there a better transaction-close seam that already knows the set of dirty indexes/trees?

3. **Given Tier A's complete op-time induction + Tier C's authoritative re-validation, how much Tier
   B is warranted?** Is a lighter Tier B acceptable — e.g. validate only the leaves the maintainer
   already lists as dirty, via whatever navigation is cheapest, accepting that it is defense-in-depth
   over Tier A rather than an independent full guarantee — or must Tier B be a fully independent
   complete check on the touched scope (as the advisory's wording implies)?

4. **Tier C placement.** Is embedding the validation inside each tree's
   `createCopyWithMergedTransactionalMemory` the right call, or should it be an explicit hook in
   `TrunkIncorporationTransactionStage` between merge and propagation? Any problem with it also
   firing on the isolated `IsolatedTransactionalLayerMaintainerFinalizer` merge path?

5. **Shared vs mirrored code.** Tier A was mirrored across the three trees (no shared base seam
   because Bucket is standalone and the trees are deliberate primitive specializations). Should
   Tier B/C validation follow the same mirror-per-tree pattern, or is there enough non-key-specific
   structure (cursor navigation, dirty-leaf iteration) to justify a shared helper on
   `AbstractTransactionalBPlusTree` this time (which Bucket still could not use)?

6. **Any correctness trap** in the merge-embedded Tier C — e.g. validating a leaf against a neighbor
   whose own merge has not yet run, or a dirty leaf whose neighbor is unchanged (not visited during
   merge) and must be reached through the merged structure.
