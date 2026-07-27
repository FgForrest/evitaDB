# Tier A design advisory — boundary-mutation checks (answers to the implementation questions)

Advisory from the spec-owner session (2026-07-15) to the implementing session, answering the four
design questions about the Tier A plain-insert/removal boundary check. This document REFINES the
Tier A extension paragraph in `CHANGE_PROPOSAL.md`; where they differ, this document wins.

## Headline answers

1. **Yes, the same-parent-only (partial) check breaks the induction** — and "closes 1.4" does
   require op-time completeness, because in WARM-UP mode there is no Tier B and no Tier C: the only
   thing between a corrupt in-memory tree and a corrupt on-disk page list is Tier A itself. But
   completeness does NOT require cross-parent leaf navigation on any hot path — see the design
   below. Counterexample pinning the hole: leaf A `[1..5]` is the RIGHTMOST child of parent P1;
   leaf B `[8..12]` is the LEFTMOST child of P2; a mis-routed key 9 tail-appended to A gives
   `[1..5,9]` overlapping B. `ci == peek` → skipped under the partial design → corrupt page list
   reaches flush; in warm-up the first detector is the load-time walk, AFTER disk. That is exactly
   the 1.4 mandate violated.

2. **The updateParentKeys parent-key-order assert is NOT a substitute for the head check.**
   Counterexample: leaves `[1..5]` and `[8..12]` under ONE parent, separators `(…,1,8,…)`. Mis-routed
   key 3 head-inserted into `[8..12]` → firstKey 8→3 → propagation rewrites the separator 8→3 →
   parent key order `1 < 3 < …` is PERFECTLY VALID, yet the leaves are now `[1..5]` and `[3..12]` —
   overlapping. Separator order is necessary but far from sufficient; the missing conjunct is
   "predecessor leaf's LAST key < new first key", and no ancestor stores subtree maxima (separators
   store MINIMA — that asymmetry is fundamental, not an implementation artifact). Keep a local
   separator-order assert inside `updateParentKeys` as a cheap belt (it catches stale/corrupt
   INTERNAL nodes), but do not count it as the head-side check.

3. **Zero allocation is a hard requirement on the HAPPY path only** (the proposal's own wording).
   The design below is zero-alloc and O(1)-ish everywhere except one deliberately rare branch —
   the cross-parent head check — which never fires on sequential bulk append and fires on a random
   workload roughly once per (fanout × P(key < leaf minimum)) mutations. O(height) node reads
   there, even if transactional-layer child resolution allocates, is acceptable; document why at
   the call site. Do not contort the design to make that branch allocation-free.

4. **Missing failure modes — three real ones, one non-issue:**
   - **Removals are a NON-issue for cross-leaf order** — prove it and then don't check them.
     Removing the first key RAISES firstKey (`pred.lastKey < oldFirst <= newFirst` still holds);
     removing the last key LOWERS lastKey (a fortiori below the upper fence). No order assert
     needed on any removal; structural merges keep their base Tier A asserts. (The
     CHANGE_PROPOSAL's "insert or removal changes the first/last key" over-specifies; the
     directional rule below replaces it.)
   - **0→1 leaves:** an insert into an EMPTY leaf (they exist post-removal; the Phase 1 walk skips
     them) changes BOTH boundaries at once — it must run BOTH checks (H and T below). Symmetrically
     1→0 (removal of the only key) needs none.
   - **Leaf splits:** the split creates a brand-new adjacent pair `L1|L2` and a new separator. The
     structural assert must pin `lastKey(L1) < firstKey(L2)` and that the new separator lands
     strictly between its parent-slot neighbors — this is the base Tier A scope, but state it
     explicitly because the induction below relies on it.
   - **Bypass paths:** the checks must live in the LEAF-level mutation primitive of each tree (the
     single method that inserts a key into a leaf node), NOT in the public API entry — so warm-up
     bulk load, transactional ops and trunk replay all inherit them. Audit for any batch leaf-fill
     or in-place key-mutation path (element/bucket payload whose comparator fields could change in
     place) that bypasses the primitive; if one exists it needs the same assert.

## The recommended design (complete induction, no hot-path allocation)

Cross-leaf order for every adjacent leaf pair `(L, R)` with the separator `S` between them (living
at their nearest common ancestor, maintained `S == firstKey(R)`) factors into two half-invariants:

- **(T)** `lastKey(L) < S` — checked from L's side whenever L's last key INCREASES;
- **(H)** every DOWNWARD move of `firstKey(R)` (head insert) satisfies
  `newFirst > lastKey(predecessor leaf)` — checked from R's side; propagation then lowers `S` to
  `newFirst`, which preserves (T) for L precisely BECAUSE (H) compared against L's actual last key.

Upward moves of `firstKey` (head removal) can only RAISE `S` — (T) is preserved a fortiori.
Downward moves of `lastKey` (tail removal) preserve (T) a fortiori. Splits/merges re-establish the
invariants via the structural asserts. That is the complete induction; with it, a tree that only
ever mutated through asserted ops cannot emit an overlapping page list at flush — item 1.4 stays
closed.

### Check T — tail (complete, zero-alloc, no cross-parent navigation)

The upper bound of ANY leaf is the separator at the NEAREST ancestor level where the descent was
not rightmost (`idx < peek`); if the descent is rightmost at every level, the leaf is the tree's
last leaf and has NO successor — nothing to check. This is the classic fence-key observation, and
it is exactly the pair separator `S` for `(thisLeaf, successor)` — even when the successor lives
under a different parent, grandparent, etc. The cursor already materializes every level's node and
descended index, so locating the fence is pure index arithmetic over arrays that already exist.

On any leaf mutation that increases the leaf's last key (tail insert; includes the 0→1 case):
walk the cursor upward to the nearest level with `idx < peek`; if none → pass; else assert
`newLast < separatorAt(idx + 1)` and throw `GenericEvitaInternalError` (structure description,
page sequences, both keys, remediation hint — Phase 1 message style) on violation.

Cost on sequential bulk append (the hottest path): the walk inspects `idx < peek` per level
(~2–4 compares), finds none, passes — zero allocation, no fence exists to compare. If the mutating
descent code makes it convenient, the fence may instead be THREADED during descent (update a single
local `upperFence` + `hasUpperFence` whenever `idx < peek`), reducing the append-path check to one
flag test; both variants are acceptable — this is noise-level cost either way, pick whichever
keeps the code clearer. For the Long tree the fence is a primitive `long`; for Element/Bucket it
is a reference to an EXISTING key object — no allocation in either representation.

### Check H — head (complete; O(1) common case, O(height) rare case)

On any leaf mutation that decreases the leaf's first key (head insert; includes the 0→1 case):

- **`ci > 0` (common):** assert `parent.children[ci - 1].lastKey < newFirst`. The cursor holds the
  parent's `children[]` and `ci`; reading the same-parent left sibling's peek key is O(1),
  zero-alloc. (Checking against `parent.separator[ci]` instead is CIRCULAR — the maintained
  invariant makes it equal the leaf's own first key — which is why the sibling's actual last key
  is the only meaningful operand.)
- **`ci == 0` (rare):** walk the cursor upward to the nearest ancestor with `idx > 0` (the clamp
  ancestor). If none → this is the tree's leftmost leaf, no predecessor → pass. Else resolve
  `children[idx - 1]` at that ancestor and follow its RIGHT SPINE down to the predecessor leaf;
  assert `predecessorLeaf.lastKey < newFirst`. O(height) node reads; never taken by sequential
  append (append never lowers a first key); taken by a random workload only when the key undercuts
  the leaf minimum at a parent edge. If transactional-layer child resolution allocates here, that
  is accepted — this branch is off the happy path by construction; say so in a code comment
  (constraint-comment, not history).

### Check S — separator hygiene inside updateParentKeys (belt, not a substitute)

After `updateParentKeys` rewrites slot `j`, assert local order around the touched slot
(`keys[j-1] < keys[j] < keys[j+1]` where the neighbors exist), at every level it ascends. Two
comparisons per rewritten level, zero allocation. This catches stale/aliased INTERNAL node state
(the historical twin bugs were stale-object aliasing — a mismatch between a separator and the live
leaf it fronts is exactly the signature such a bug shows first). It does NOT catch the Q2
counterexample; H does.

### What NOT to implement

- No order checks on removals (proof above); no check on interior inserts (`oldFirst < k <
  oldLast` cannot violate cross-leaf order in a sound tree — that is what the induction gives you).
- No cross-parent LEAF navigation on the tail side — the fence subsumes it entirely.
- No attempt to make the `ci == 0` head branch allocation-free at the cost of design contortion.

## Test additions (beyond the CHANGE_PROPOSAL's Tier A bullets)

Per tree (the three trees mirror the harness, as Phase 1 did):

1. Mis-routed TAIL insert at a parent edge (the Q1 counterexample: rightmost child of P1
   overlapping leftmost child of P2) → must throw via Check T. This is the test that proves the
   partial design would have been wrong — keep its JavaDoc explicit about the cross-parent shape.
2. Mis-routed HEAD insert, same parent (the Q2 counterexample: key 3 into `[8..12]` beside
   `[1..5]`) → must throw via Check H — and assert the separator-order belt would NOT have fired
   (documents why H exists).
3. Mis-routed HEAD insert at `ci == 0` (cross-parent predecessor) → must throw via the right-spine
   branch of Check H.
4. Insert into an EMPTY leaf violating either side → both H and T run on the 0→1 transition.
5. Happy-path pin: sequential bulk append through the public API — no throw, and (if cheap to
   assert) no fence found at any level; this is the perf-shape guard for the hottest path.

Corruption induction uses package-private hooks (mis-wire a `children[]` slot / pre-seed a leaf
with out-of-range keys), consistent with the CHANGE_PROPOSAL's Phase 2 tests section.

## Interaction with Tier B / Tier C

Unchanged. Tier B's dirty-leaf validation independently re-derives both half-invariants for leaves
a transaction touched (it reads actual neighbors, so it is complete by construction on its scope);
Tier C likewise on replay. Tier A completeness matters because warm-up mode has neither.
