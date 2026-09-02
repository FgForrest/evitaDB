# Tier B / Tier C design advisory — answers to TIER_BC_QUESTION.md

Advisory from the spec-owner session (2026-07-15) to the implementing session, answering the six
DECISIONS in `TIER_BC_QUESTION.md`. This document REFINES the Tier B and Tier C sections of
`CHANGE_PROPOSAL.md`; where they differ, this document wins. All code facts below were re-verified
against the working tree on 2026-07-15 (this reviewer DOES have repository access).

## Additional verified code facts (load-bearing, beyond the question's own)

1. **The transaction is still thread-bound during `TransactionWalFinalizer.commit(...)`.**
   `Transaction.close()` calls `this.transactionalMemory.commit()` (which calls
   `maintainer.commit()` → `finalizer.commit(maintainer)`) and removes `CURRENT_TRANSACTION` only in
   the `finally` AFTER that call returns. So a read-path cursor descent executed inside the
   finalizer's `commit` still resolves diff layers through the static context — the transactional
   view is fully readable at the Tier B hook. This settles the question's biggest implicit unknown.
2. **A leaf's diff layer IS a `BPlusLeafTreeNode` instance.** `createLayer()` returns a full node
   copy of the same class (`transactionalLayer=false` to break recursion). Consequence: "stamp the
   layer with a tree back-reference" means adding a field to the NODE class itself — present on
   every node in every tree, transactional or not. That option is off the table.
3. **The maintainer map cannot lead back to a tree.** `TransactionalLayerCreatorKey` is
   `(creator.getClass(), creator.getId())` and `TransactionalLayerWrapper` holds only the layer
   item. Even if dirty trees have map entries, neither the key nor the wrapper references the
   creator object. Iterating the map can yield dirty leaf LAYERS (by instanceof + dirty flag) but
   never their owning trees. This kills maintainer-map iteration as the enumeration mechanism.
4. **A raw throw out of `TransactionWalFinalizer.commit` is a hang + a leak.** Nothing between the
   finalizer and the client completes `commitProgress` on an unexpected exception from `commit(...)`
   (`Transaction.close()` has no catch), and `commit`'s `finally` nulls `walPersistenceService`
   WITHOUT closing it (deliberately — the pipeline normally takes ownership), so the isolated WAL's
   off-heap region / temp file would leak. `rollback(...)` shows the correct failure shape: close
   the WAL persistence service (deleting the isolated WAL) and `completeExceptionally` on
   `commitProgress`, then return normally.
5. **Layer creation is already forbidden at the hook.** `maintainer.commit()` sets
   `allowTransactionalLayerCreation = false` BEFORE calling `finalizer.commit(...)`. Tier B
   validation must therefore use strictly read-path descents (`getTransactionalMemoryLayerIfExists`
   semantics); an accidental `getOrCreate...` will fail loudly — which is correct, but design for
   read-only from the start.

## Headline answers to the six DECISIONS

1. **Enumeration/navigation: B-relocate — but the enumeration comes from a new maintainer-hosted
   dirty-leaf REGISTRY populated at mutation time, not from the maintainer map, not from node
   back-references, and not from a tree walk.** The "O(dirty leaves), no full-tree walk" wording is
   a hard requirement: B-walk is O(touched-tree-size) per dirty tree, a commit touches dozens of
   trees at production scale (380k+ entries ≈ 1.5–3k leaves per large tree), so B-walk is 10⁴–10⁵ leaf
   visits per commit — a measurable single-shot regression, which the acceptance criteria forbid.
   O(dirty × height) relocation descents are compliant: the proposal's "two comparisons per leaf"
   prices the per-leaf CHECK; locating the leaf via a root descent is the navigation cost the
   architecture imposes and is fine. B-walk survives only as a TEST-mode oracle (fuzzer
   verification), never as shipped Tier B. B-parentref and layer-stamping are rejected on fact 2.

2. **Hook location: yes — first thing in `TransactionWalFinalizer.commit(...)`, before
   `closeRegisteredCloseables()` and before `catalog.commitWal(...)`.** Verified viable by fact 1
   (diff layers readable) and it trivially satisfies the normative guarantee (nothing has touched
   the shared WAL yet). There is no better seam: nothing at transaction close knows the set of
   dirty trees — the registry makes that knowledge travel with the transaction, so the seam
   question disappears. The failure path is NOT a throw: mirror `rollback(...)` — close+delete the
   isolated `walPersistenceService`, `commitProgress.completeExceptionally(new
   GenericEvitaInternalError(...))` with the structural detail, and return normally (fact 4).
   The client observes the exceptional commit future; the shared WAL verifiably does not contain
   the transaction; the catalog remains healthy for subsequent commits.

3. **How much Tier B: the full independent check on its honest scope — do NOT build the lighter
   version.** The justification is concrete to this codebase, not abstract defense-in-depth: the
   recurring REAL bug class here is STM layer lifecycle, which op-time Tier A structurally cannot
   see — a layer that reverts or aliases AFTER the op's assert passed (the historical
   UnorderedLookupTree MapChanges-revert gotcha, forgotten dirty marks, ChainIndex's
   post-commit-layer-creation bug), and most acutely the NEW savepoint snapshot/restore machinery
   (#569-era), where a buggy `restore` rewrites leaf arrays in place at a point no Tier A assert
   observes. Tier B at commit re-derives boundaries from the EFFECTIVE final state and rejects with
   the WAL still clean — recoverable, client-facing, no poison pill. That property is unique to
   Tier B and worth the registry. Honest bound to document: Tier B's scope is the registered dirty
   leaves (everything mutated through the primitives), not a full-tree oracle; mis-routing is Tier
   A's job, merge bugs are Tier C's.

4. **Tier C placement: merge-embedded is right, with two precise corrections.** (a) Validate
   POST-merge — as the LAST step of each tree's top-level `createCopyWithMergedTransactionalMemory`,
   after the merged root is fully built and immediately before returning — never per-leaf DURING
   the merge (see trap 1). (b) The tree-level error message stays NEUTRAL (structural facts +
   remediation); the poison-pill caveat is added by wrapping in the trunk path
   (`TransactionTrunkFinalizer.commitCatalogChanges` / `TrunkIncorporationTransactionStage`'s
   exception handling), because the same merge code also fires on the isolated finalizer (c) path
   where "already durable in the WAL" would be false. Firing on path (c) is bonus coverage, keep
   it. This honors the proposal's own principle: the GUARANTEE (validated before propagation), not
   the stage, is normative.

5. **Shared vs mirrored: mirror per tree, as Tier A did.** Share ONLY the type-agnostic pieces: the
   registry class + maintainer field, and (optionally) a small non-generic interface the three
   trees implement (e.g. `validateDirtyScope(...)` taking the registered objects). All key-typed
   validation logic (descents, fence/sibling comparisons) mirrors Tier A's per-tree pattern —
   an `AbstractTransactionalBPlusTree` helper covers only 2 of 3 trees (Bucket is standalone) and
   would force boxing/generics into deliberately primitive-specialized code. Keep the three
   implementations textually parallel for reviewability, as Phase 1 and Tier A did.

6. **Correctness traps: see the dedicated section below — the question's own Tier C sketch
   ("as the merged copy is produced ... validate against its neighbors in the just-merged
   structure") contains trap 1 and must not be implemented as sketched.**

## The recommended design — one enumeration mechanism feeding both tiers

### The dirty-leaf registry

A small per-transaction structure hosted on `TransactionalLayerMaintainer` (its lifetime and
ownership are exactly right, and it is the object both finalizers already receive):

- Shape: identity map `owner tree → IdentityHashSet<Object>` of **writable leaf objects** — the
  write layer for a baseline node, or the node itself when it was created inside the transaction
  (split-born). Lazily instantiated on first registration.
- Population: in each tree's leaf-level mutation seams — the SAME seams Tier A instrumented (the
  point where the primitive obtains the leaf's write representation, plus the structural ops:
  split/merge/rotate register every leaf whose boundary keys they touch). The tree has `this` in
  hand there, so grouping is free. Registration is one `computeIfAbsent` + `Set.add` per
  leaf-dirtying op — same cost class as the already-accepted Tier A asserts.
- Register on EVERY write-layer acquisition, including pure removals. Removals cannot create
  overlap themselves (the Tier A a-fortiori argument), but a removal-dirtied leaf is exactly the
  kind whose LAYER a lifecycle bug can revert to its pre-transaction (wider) key range — which DOES
  overlap a neighbor that split during the transaction. Uniform registration is also simpler than
  a directional filter.
- Registration is UNCONDITIONAL (not kill-switch gated), because it feeds always-on Tier C; the
  kill switch gates only the Tier B validation pass.
- **Registered objects are KEY SOURCES ONLY — never validate them directly.** After a savepoint
  rollback a registered layer may hold reverted or abandoned state; after an in-transaction merge
  the leaf may not exist anymore. The invariant that makes the registry rollback-proof and
  merge-away-proof: validation always reads one key from the registered object (its current first
  key; skip if empty), DESCENDS from the tree root in the appropriate view, and validates the leaf
  the descent actually lands on. Validating whatever leaf currently covers the key is always a
  sound check; at worst it is redundant. This is also the answer to the proposal's savepoint
  caution — relocation-by-key against the effective state sidesteps the layer-stack question
  entirely.

### Tier B consumption — `TransactionWalFinalizer.commit`, pre-WAL

Gated on `evita.bPlusTree.preCommitValidation` (default on). For each registered (tree, leaf
objects): descend by each leaf's current first key THROUGH THE TRANSACTIONAL VIEW (fact 1 makes
this work; fact 5 makes it read-only by force) and validate BOTH boundaries of the found leaf
against actual neighbors — reuse Tier A's machinery: tail side via the descent-cursor fence
separator, head side via the same-parent left sibling's last key or the rare `ci == 0` right-spine
walk. Cost: O(distinct dirty leaves × height) once per commit — for a typical atomic upsert this
is hundreds of node hops, microseconds; for a 100k-key bulk transaction into one tree, low
thousands — no measurable single-shot regression. On violation: the mirror-rollback failure path
from headline answer 2. Do not run Tier B for the trunk-replay transaction (its finalizer is
`TransactionTrunkFinalizer`, and pre-WAL rejection is meaningless post-WAL — Tier C owns that
window).

### Tier C consumption — tree merge, post-build, always on

At the END of each tree's top-level `createCopyWithMergedTransactionalMemory`: after the merged
root is built, ask the maintainer (a parameter of that method) for this tree's registered leaves;
for each, relocate by key in the MERGED structure (plain reads — merged nodes are fresh objects
with no layers, so no transactional-view subtleties) and validate both boundaries the same way.
This is O(dirty × height), needs no new stage plumbing, and fires on every merge path: trunk
incorporation (the normative Tier C window — the trunk replay populates its own transaction's
registry through the same primitives) and the isolated finalizer (c) path (bonus). The trunk
wrapper adds the poison-pill message; state in it that the transaction is already durable in the
WAL **and possibly in flushed data files** — `commitCatalogChanges` flushes at step (1) BEFORE the
merge at step (2), so a corrupt page list may already be on disk when Tier C fires. A restart will
either replay the WAL or fail at load through the Phase 1 walk — either way loud; remediation is
restore/rebuild plus a bug report. No re-ordering of flush-vs-merge is asked for; the proposal's
poison-pill framing already accepts post-durability detection, but the message must not
understate it.

## Correctness traps (DECISION 6)

1. **Half-built neighbors mid-merge.** During the merge, children merge before their parent, and
   siblings merge in some order — when leaf L's merged copy is constructed, its successor's merged
   copy and the parent that will link them may not exist yet. There is no "just-merged structure"
   to navigate until the tree-level method holds the new root. Hence: post-build validation only.
2. **Positional old/new diff walks break.** Comparing old and new trees children-by-index to find
   changed leaves misaligns as soon as a split or merge shifts arity. Don't diff structurally;
   relocate by key.
3. **Sequence-id thresholds mis-enumerate.** "Merge-born nodes have ids ≥ the sequence value at
   merge start" misses transaction-born nodes the merge returns as-is (created before merge start,
   yet changed relative to the baseline). A transaction-start threshold fixes that but adds id
   semantics the registry makes unnecessary. Don't build enumeration on `TransactionalObjectVersion`
   arithmetic.
4. **Stale registered objects.** Savepoint rollback can revert or orphan a registered layer;
   an in-transaction structural merge can remove the leaf. Covered by the key-source-only rule;
   never assert on the registered object's own state (beyond reading a key and skipping empties).
5. **Exception discipline at the Tier B hook.** Fact 4: a raw throw hangs every commit future and
   leaks the isolated WAL. Mirror `rollback(...)`: close the WAL persistence service, complete
   exceptionally, return normally. Write a test that the isolated WAL file/region is actually
   released on rejection.
6. **Read-only discipline.** Facts 1+5: Tier B descents run with layer creation disabled — use
   read-path resolution throughout; Tier C descents run on merged plain nodes and must not consult
   layers at all (running before `verifyLayerWasFullySwept` in the trunk finalizer, a stray
   `getOrCreate...` would manufacture an unswept layer and trip `StaleTransactionMemoryException`).
7. **Message truthfulness on the shared merge path.** The tree-level Tier C throw fires on
   finalizer (c) merges too, where no WAL exists — keep WAL/poison-pill wording OUT of the
   tree-level message; the trunk path wraps (trap-free because `commitCatalogChanges` already
   funnels exceptions into `completeExceptionally`).

## What NOT to implement

- No B-walk in shipped code (full-tree walk per dirty tree — forbidden by the proposal and by the
  latency acceptance criterion); keep full-tree validation as a test-only oracle.
- No tree/parent back-references on nodes and no fields added to the node class for layer
  stamping (fact 2 makes stamping equal to polluting every node).
- No maintainer-map iteration API (fact 3: it cannot yield tree grouping; adding creator
  references to keys/wrappers to force it is a bigger encapsulation break than the registry).
- No mid-merge per-leaf validation, no positional old/new diffing, no id-threshold enumeration
  (traps 1–3).
- No Tier B pass on the trunk-replay transaction (Tier C owns post-WAL).

## Test additions (beyond the CHANGE_PROPOSAL's Phase 2 bullets, which stand)

1. **Tier B catches post-assert corruption** (per tree): mutate through the public API inside a
   transaction (all Tier A asserts pass), then corrupt the leaf's WRITE LAYER via a package-private
   hook so its key range overlaps a neighbor; commit → assert exceptional commit progress, the
   shared WAL does NOT contain the transaction, the isolated WAL resources are released, and the
   catalog accepts a subsequent clean commit.
2. **Savepoint interaction**: open savepoint → mutate (leaves get registered) → rollback savepoint
   → commit: no false positive (relocation validates healthy covering leaves). Variant: corrupt a
   layer AFTER the savepoint rollback → still caught.
3. **Kill-switch**: with `evita.bPlusTree.preCommitValidation=false`, the same corruption sails
   past Tier B and is caught by Tier C with the poison-pill message — proving the tiers are
   independent and the switch gates only Tier B.
4. **Tier C merge-scope**: corrupt during trunk replay via a test hook (after the replay's own Tier
   A asserts have passed) → exceptional commit progress whose message contains the poison-pill
   caveat including the flushed-data-files wording.
5. **Registry hygiene**: a transaction that only REMOVES keys still registers its leaves; a
   transaction that splits a leaf registers both halves (assert via a package-private registry
   accessor).
6. **False-positive gates**: the standing acceptance suites (`indexing | filter | order`,
   6993/0/1-skip baseline) and the churn suites stay green with Tier B+C enabled — Tier B/C must
   produce zero throws on healthy workloads including savepoint-heavy atomic upserts.

## Interaction with Tier A

Unchanged, and now precisely delineated: Tier A = op-time induction (catches mis-routing at the
faulting op, with the op's stack in hand); Tier B = commit-time re-derivation of the effective
isolated state (catches STM layer lifecycle bugs; rejects with a clean WAL); Tier C = post-merge
re-derivation of the authoritative trunk state (catches merge machinery bugs and trunk-shape
divergence; poison pill). Each tier sees a state no other tier can observe — that disjointness,
not redundancy, is why all three exist.
