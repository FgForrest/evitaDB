# Trunk merge cascade — the design decisions behind the prune

Consolidated from five working documents (2026-07-21 → 07-25). The *measurements* live in
`2026-07-27-senesi-wal-replay-rounds.md`; this file records the **verdicts** — what was ruled out and
why, so none of it is re-litigated.

**Staleness note.** Status language is a historical snapshot; the prune shipped in PR #1317.

---

## The problem

On every commit evitaDB materializes the new committed state by walking down from the catalog through
every container to every leaf index (`createCopyWithMergedTransactionalMemory`). On senesi (~1 M
products) that walk **visits 140 000 – 1 100 000 producers per transaction, and only ~1.0 % of them
have any change at all**.

| bucket | share |
|---|---|
| `changed` — a diff layer existed, rebuild genuinely required | **1.0 %** |
| `carriedForward` — no diff, producer returned itself | 37.3 % |
| `reclaimable` — no diff, same committed type, new instance | 15.2 % |
| `converted` — committed form is a different type | 46.5 % |

The walk exists purely to *discover* which producers changed — yet the transaction already knows:
`TransactionalLayerMaintainer.transactionalLayer` is keyed by exactly the producers that have a diff
layer (~4 600 per transaction).

**The waste is the traversal, not predominantly the copying.** `TransactionalMap` already returns its
existing delegate when nothing changed, and `RangeIndex` and the B+ tree internal nodes do the same.
Any framing as "stop rebuilding 460 000 objects" is wrong; it is "stop *visiting* 460 000 objects to
find 4 600". Per visit the cost is one `TransactionalLayerCreatorKey` allocation plus one `HashMap`
lookup — ~99 % of the time to learn "nothing here" — and that bookkeeping measured **~25 % of the
trunk thread's wall time**.

---

## Verdict 1 — full bottom-up inversion: **NO. Do not reopen.**

Answered by a four-agent audit (2026-07-21). Two facts kill it:

- **There are no parent links.** The cascade is driven by containers iterating their children; nothing
  maps a producer to its container. Bottom-up propagation requires child → parent, which does not
  exist. Adding it means registering a parent reference at attach time — reintroducing exactly the
  back-reference lifecycle hazard that the attach-retirement work removed.
- **All ~35 composite `createCopyWithMergedTransactionalMemory` implementations are bespoke monolithic
  constructors.** There is no shared substitution seam to invert; every one would have to change.

Secondary obstacles that would each need answering anyway: a producer may be reachable from **more
than one parent** (views, shared value/range indexes, `PriceRefIndex` ↔ super index), making "the
parent" ill-defined; **savepoints** hook layer access through the same keying; and merge **ordering**
between parents and children may be load-bearing.

### The estimate that motivated it was wrong

The originating figure — *"~460 000 lookups → ~4 600, on the order of 100× less traversal"* — **must
not be carried forward**. An inverted cascade still rebuilds every **ancestor** of every changed
producer. With ~4 600 dirty producers at a tree depth of ~5–8, the realistic visit count is
~23 000–37 000: a **12–20× reduction, not 100×**.

### One piece of good news that de-risked everything after

`verifyLayerWasFullySwept` throws `StaleTransactionMemoryException` if *any* diff layer goes
unconsumed — silent loss of committed data is already guarded. **The engine fails loudly if a prune
misses something.** Every experiment kept it enabled, and it is why the prune could be trusted on
loud backstops rather than exhaustive inspection.

---

## Verdict 2 — clean-subtree prune: **YES**

The cheaper middle path: give the walk a way to skip clean subtrees wholesale, preserving the existing
top-down control flow and needing no parent links. The pattern **already ships twice in-tree**, so it
was a rollout rather than an invention.

What shipped is described in the rounds report (rounds 4–6). The design points worth keeping:

- The dirty-set signal is `DataStoreChanges.popTrappedUpdates`, which snapshots the dirty index-key
  set before draining it — the same set the flush persists, i.e. ground truth. Flush runs strictly
  before the merge, so the snapshot is fresh for the batch.
- `MapChanges.createMergedMap` gained a `ValueMerger` hook consulted for **every surviving key**, so
  there is no unpruned route left and `EntityCollection` has one merge call site.
- Layer disposal uses `removeTransactionalMemoryLayerIfExists`, never `removeLayer` — the latter
  descends into every value, the trap already documented for the by-PK twin.

---

## Verdict 3 — the C1 blocker: **Plan A**, and Plan B is dead

The first prune attempt broke the functional build by violating a deliberate architectural invariant.

**Root cause.** Reduced indexes hold a `PriceRefIndex` whose `SuperIndexResolver` captures a
**specific `GlobalEntityIndex` instance by reference**. `wireSuperIndexes` is single-assign and
cascades a super-index pointer into every combo child; `wireOrVerifySuperIndexes` then either wires
fresh (resolver `null`) or **verifies identity** and throws "stale GLOBAL" on mismatch. The GLOBAL is
rebuilt on nearly every write commit.

The full walk stayed correct because `ReducedEntityIndex.createCopyWithMergedTransactionalMemory`
*always* built a fresh `PriceRefIndex` with a null resolver, re-wired every commit. Carrying a clean
reduced index by reference keeps the **old** resolver pointing at the **retired** GLOBAL — correctly
rejected.

### The two realizations that sized the prize honestly

**H1 — visit-count ≠ waste-count.** The full walk *already shares* the deep leaf data of a clean
reduced index: each clean child's `getStateCopyWithCommittedChanges` returns the **same instance**.
So millions of "reclaimable" visits were never deep copies. The genuinely recoverable work is only
(a) the dispatch calls and (b) the **wrapper allocations** per clean reduced index per commit — not
deep-structure copies.

**H2 — the wrapper allocation is the only real win, and it is exactly what the invariant blocks.**
Eliminating it requires *sharing* the reduced index, but sharing keeps the stale resolver, and
re-wiring a shared instance is illegal (it is aliased by the previous catalog version still serving
reads). Pure sharing is sound only when the GLOBAL is *also* clean — the rare case. When the GLOBAL is
dirty (the common case), correctness forces a re-shell + re-wire, reintroducing the allocation.

### The plans

- **Plan A (chosen)** — scoped re-shell + re-wire in the dirty branch: share attribute / hierarchy /
  facet / cardinality / histogram children by reference, rebuild only the thin price spine, re-wire to
  the new GLOBAL. Saves the deep-child dispatch; still pays a wrapper allocation per reduced index.
  The decisive refinement was a dedicated shell constructor sharing the immutable baseline too —
  ~4 µs → ~0.3 µs per index (see the rounds report).
- **Plan B — indirect GLOBAL resolution: DEAD.** It is the cleanest route (true sharing, eliminating
  both dispatch and allocation) but it **reverses the deliberate attach-retirement Phase-4 decision**
  and removes the `wireOrVerifySuperIndexes` identity check that guards stale super pointers from
  reaching queries. Large blast radius in the corruption-prone transactional seam. Later rounds
  restate this as "dead on MVCC grounds". Do not revisit without explicit owner approval.
- **Plan C — pivot away entirely:** redirect to unambiguous wins (typed-plain `TransactionalMap` fast
  path for large plain-valued maps; pruning clean price combos *inside* a dirty reduced index, which
  has no GLOBAL-identity hazard). Not needed once the ceiling was re-established, but the typed-plain
  idea remains open.

**Consequence for complexity:** the prune does not change the class. Every commit stays O(#indexes)
because the GLOBAL is dirty nearly every transaction and the price capture forces a re-shell of every
reduced index in that scope. It extends the *cheap constant* (~0.3 µs re-shell) to commits that
previously paid the expensive one (~12 µs full merge).

---

## The NO-GO that was wrong — a cautionary profiling record

A profile-driven analysis concluded the prune was **NO-GO**: the floor was supposedly not the merge
cascade but idle "trigger-wait". **That verdict was retracted.** Two independent re-measures on the
same jar established the opposite: the merge cascade is **~76–80 % of the ~1.7–2.8 s single-threaded
finalize window** that constitutes the floor.

Three errors produced the wrong answer, and all three are reusable warnings:

1. **One busy thread was averaged across the whole pool.** One trunk worker runs 100 % busy while
   ~63 pool threads idle — 1/64 ≈ **exactly the 1.6 % "active" figure** the analysis computed and then
   converted into "~45 ms of work". 79 k libc-only unwalkable native stacks reinforced the illusion.
2. **A per-batch measurement was read as per-pass.** The "merge dispatch is 1–2 % of CPU" figure came
   from the *pipelined* run, where the merge runs once per batch and the denominator includes the
   64 %-of-CPU concurrent GC. Per-*pass* merge cost — what the floor metric actually pays — is ~2.5 s.
3. **A correct observation led to a wrong conclusion by omission.** "fsync 13 / write 31 samples ⇒ not
   flush-bound" was right, but "therefore pure wait" skipped the third option: **CPU-bound
   single-threaded merge work**, which is what the window contained.

Also established and worth not re-investigating: **task delivery is instant.** `visible_ms` minus
`wal_ms` minus the trunk span is 0–1 ms on every transaction, and live-view propagation is 0–1 ms.
There is no trigger latency and no queue wait — the commit→visible path is fully event-driven. The
`flushFrequencyInMillis` discriminator is aimed at a non-lever.

---

## CHAMP for the entity index maps

`PersistentTransactionalProducerMap` replaced the plain map for `EntityCollection.indexes` and
`indexesByPrimaryKey`, path-copying only changed keys instead of rebuilding the whole map per commit.
Two increments shipped, worth **−19.9 %** then **−14.9 %** visible median.

**Do not use the `markValueMutated` contract for this map.** `pruneMergeIndexes` already computes the
exact delta at merge time (`dirtyIndexKeys ∪ indexChanges.getModifiedKeys().keySet()`); that set is
already in a local variable, proven correct, and carries no forgotten-mark hazard. The merge entry
point takes the key set explicitly. (The single dirty-key funnel, if the mark route is ever revisited,
is `DataStoreChanges#captureDirtyIndexKeys`.)

### Read-side gate — measured, passes

The concern was CHAMP's read penalty on query-hot maps. Instrumented lookup counts on an artificial
100 k-product dataset:

| benchmark | lookups/query | query cost | predicted penalty @ +50 ns |
|---|---|---|---|
| `facetFiltering` | ~258 | 0.195 ms | **+6.6 %** |
| `hierarchyStatisticsComputation` | ~203 | 0.294 ms | +3.5 % |
| `priceAndHierarchyFiltering` | ~164 | 7.14 ms | +0.11 % |

Worst case ~13 µs per query. `facetFiltering` exceeds the 5 % gate, but it is a *prediction* and sits
inside the ±9 % query-benchmark noise floor, so no end-to-end run can confirm it. Mitigation if ever
needed: memoize resolved indexes per query in `QueryPlanningContext` (~250 lookups across only a
handful of distinct keys ⇒ mostly repeats). Caveats on the numbers: the denominator is derived from
JMH's op rate rather than counted (±25 %), and the artificial index map is far smaller than senesi's
251 k, so the real trie is deeper.

### Two corrections to the earlier record — do not re-adopt the old story

A previous session concluded a cross-scope price tripwire could not be built because a 2-product
fixture lets the planner satisfy queries by prefetching entity bodies. **Wrong on both counts:**

1. `FilterByVisitor#getSuperPriceIndex` **short-circuits on `GlobalEntityIndex`** and returns its own
   price index with no resolution, so a query naming no reference never executed the mutated branch.
2. `IndexSelectionVisitor` marks a reduced index ineligible with `HIGH_CARDINALITY` unless the
   candidates' summed cardinality is `<= mainIndexCardinality / 2`. With one product per scope,
   `1 <= 0` is false — a reduced index was **never eligible**. *That* is why the fixture was too
   small; prefetch was never the masking mechanism.

Fix: narrow the assertion by reference and add filler products so the observed brand owns a minority
of its scope.

**Also retracted:** `hasEntityGlobalIndex()`'s `keySet().stream().anyMatch(...)` is **not** an O(N)
scan — `anyMatch` short-circuits and every key in that map is an `EntityIndexKey`. Not a risk.

---

## A tooling note worth repeating

The census that produced the bucket table (`TransactionalLayerCopyCensus`) was **deliberately removed
from the source tree** once it had answered the question. It costs ~4 % when enabled, so a censused
run must never be read as a performance measurement — and it required hook calls inside
`TransactionalLayerMaintainer`, a production file that *must* be committed. Keeping diagnostic
instrumentation coupled to a committed production file was judged a standing footgun.
