---
title: Make every warm-up entity write atomic through a thread-local savepoint whose participants journal their own absolute inverses, unconditionally
date: 2026-08-26
updated: 2026-08-26 20:20
status: accepted
kind: feature
issues: [1432]
prs: []
areas: [evita_engine/core/transaction/memory, evita_engine/core/collection, evita_engine/core/buffer, evita_engine/index]
supersedes: []
superseded-by: []
relates: [2026-07-10-more-optimized-data-structures, 2026-07-31-bulk-ingest-write-path]
---

# Per-entity mutation atomicity in WARM_UP — a savepoint the structures journal into

Since #569 an entity mutation inside an ALIVE-phase transaction is atomic on its own: the executor
brackets it with a savepoint over the transactional diff layers, and a mid-write failure is reverted
surgically while the transaction keeps running. WARM_UP (bulk indexing) had no such guarantee —
writes go in place to the index delegates, so a failed entity left half-mutated indexes destined for
the next flush and a queryable primary key with no storage body behind it. This line of work ported
the savepoint *principle* to the warm-up write path: a thread-local `WarmUpSavepoint` holding one
`UndoJournal` of absolute-restore inverses, driven by the **same** bracket in
`LocalMutationExecutorCollector`, with every participating structure recording the inverse of its own
delegate-branch writes. It shipped unconditionally after a measurement campaign took its cost from
+14.4 % of bulk-ingest CPU down to +2.17 %.

## Why

The gap was a user-visible consistency hole with no recovery inside the database. `DataStoreChanges`
collects the dirty indexes and the session close flushes them, but the entity body is written by
`ContainerizedLocalMutationExecutor#commit`, which a failure short-circuits — so the indexes keep the
failed entity and the store never gets its body. The documented advice was to compensate on the
client or rebuild the catalog.

Two constraints made the design non-obvious, and between them they shaped every decision below.

The first is the **saturated ingest thread**. `2026-07-31-bulk-ingest-write-path` measured the bulk
loader at 94 % CPU saturation on one thread carrying 78 % of all process CPU: *nothing helps unless
it removes work from that thread*. This mechanism does the opposite — it adds bookkeeping to exactly
that thread — so it is only shippable if the addition is small, and "small" had to be measured rather
than argued. The first honest measurement came back at **+14.4 %**, which is not shippable, and the
larger half of this record is the campaign that took it apart.

The second is the **`O(N²)` per-entity rollback cliff** that `#1252` already hit on the transactional
side (`2026-07-10-more-optimized-data-structures`). Capture-on-first-touch is only cheap when the
participant's whole pre-image is cheap; where the pre-image is the accumulated base structure, one
memento per entity multiplied by a growing index is the cliff the `UndoJournal` strategy exists to
avoid. Warm-up has *more* structures in that shape than the transactional path does, because it has
no diff layers at all — it writes the real thing.

### Previous state

Every warm-up write took the delegate branch of the same wrapper classes the transactional path uses
(`TransactionalMap.put` → `mapDelegate.put`, and so on). The bracket in
`LocalMutationExecutorCollector` already existed and every warm-up-reachable call site already passed
`atomicRollback = true`; the branch simply did nothing when the transactional maintainer was absent,
and `rollback()` was a silent no-op. Recovery was documented as the caller's problem in
`documentation/user/en/deep-dive/bulk-vs-incremental-indexing.md`.

## Decisions taken

| Date | Decision | Why | Detail |
|------|----------|-----|--------|
| 2026-08-24 | **Own the savepoint in a thread-local `WarmUpSavepoint`**, not in a parameter and not in a degenerate maintainer | The write path fans out through the whole index-mutation machinery; `CatalogState.WARMING_UP` is contractually single-threaded, so a thread-bound context is sound and costs one predicted-null `ThreadLocal` read outside a bracket | `WarmUpSavepoint` in `evita_engine/.../core/transaction/memory` |
| 2026-08-24 | **One bracket for both savepoint kinds** — the collector opens the transactional savepoint when a maintainer exists and the warm-up savepoint otherwise | A second orchestration point would be a second rollback implementation; WAL replay opts out of both (`atomicRollback == false`), because whole-transaction discard is its recovery model | `LocalMutationExecutorCollector` (`609093b0a`) |
| 2026-08-24 | **Granularity is chosen per structure family by the cost of the pre-image**, not by the structure's shape | First-touch memento where the pre-image is an `O(1)` reference grab (array-reference wrappers, scalars, `DataStoreChanges`); per-operation inverse where a first-touch copy would be the #1252 cliff (`Map`/`Set`/`List`) | Per-family table in `documentation/developer/stm/savepoints.md`; `cba1af9bb`, `53d7a376b` |
| 2026-08-25 | **Reverse the bitmap to per-operation journaling** after the allocation profile refuted the first-touch clone | The copy-on-write `clone()` looked `O(1)` and deferred its real cost into every subsequent write — 13.2 % of all ingest allocation | [The bitmap reversal](#the-bitmap-reversal--a-measured-about-face) |
| 2026-08-26 | **Answer "already captured?" from a per-participant generation stamp**, not from a per-savepoint `IdentityHashMap` | 461 ms per 100k entities of hashing and probing, for a question each participant can answer about itself in eight bytes | [First-touch dedup by stamp](#first-touch-dedup-by-stamp-not-by-map) |
| 2026-08-24 / 2026-08-26 | **B+ trees: per-node first-touch mementos, then per-slot inverses for the measured hotspot** | The nodes already implement `Snapshotable`, so the memento machinery was reused rather than duplicated; measurement then showed whole-node capture costing 551 ms per 100k entities for writes touching one or two slots | [B+ trees](#b-trees-per-node-mementos-then-per-slot-inverses) |
| 2026-08-24 | **Enforcement is a declaration honoured, not a property verified** — `TransactionalLayerCreator#supportsWarmUpRollback()` defaults to `false`, with a runtime backstop at the layer-resolution choke point and a source-scan conformance test | Warm-up has no maintainer to enforce journalling centrally; the false default makes an unported structure fail the first time a bracketed mutation reaches it, instead of being discovered as an index a rollback quietly skipped | `4c7fb4c55`; `WarmUpRollbackBackstopTest`, `WarmUpRollbackConformanceTest` |
| 2026-08-25 | **`open()` goes last in its branch, with nothing throwable between it and the `try`/`finally`** | Traffic-recording activation can throw, and an exception thrown after the open escapes with no `finally` to detach the savepoint — leaking it onto the thread so the *next* entity fails its own `open()` as a nested one | Found by adversarial review; pinned by `WarmUpRollbackConformanceTest` (`4e5a306c1`) |
| 2026-08-25 | **Journal record-level inverses for direct storage-part writes** | Root mutations run with `trapChanges == false` and write parts through the persistence service; those writes escaped the rollback entirely, so a multi-part entity could roll its indexes back cleanly and leave a half-updated, fetchable body — while *reporting success* | `DataStoreChanges#journalPersistedChange` (`4e5a306c1`) |
| 2026-08-24 | **Keep poisoning the data-store buffers as the last-resort backstop** | Warm-up writes went in place, so a rollback that itself throws has no layer to discard; the buffers then refuse every future flush and the rollback failure is attached to the original exception as a suppressed cause | `LocalMutationExecutorCollector#rollbackOpenWarmUpSavepoint` |
| 2026-08-26 | **Ship it always on, with no switch at all** — the internal flag, its system property, the test setter, the fencing annotation and the `@Isolated` markers were all deleted | At ~2 % of ingest CPU the consistency is worth more than the knob, and a kill switch would have kept a process-wide mutable static that no longer has any reason to exist | [Always on](#always-on--and-what-that-cost-to-earn) |

### The bitmap reversal — a measured about-face

The original family table **rejected** per-operation capture for `TransactionalBitmap` on the grounds
that `addAll(Bitmap)` would allocate `O(args)` on the hottest path, and chose a first-touch
`PersistentRoaringBitmap.clone()` instead — sound-looking, because the clone is two-level
copy-on-write and copies only pointers, proportional to containers rather than to cardinality.

The allocation profile refuted it. What the clone really does is **freeze** every container on both
sides, so the next write to any shared container must defrost it — copying up to a `long[1024]`,
8 KB — and bulk ingest adds one bit to dozens of bitmaps per entity, then opens a new savepoint for
the next entity and re-clones, re-freezes and re-defrosts. That deferred copying measured **13.2 % of
all allocation** on the bracketed ingest path.

So the bitmap now journals per operation, like the collection wrappers: single-bit writes push one
inverse behind the `contains()` short-circuit they already performed, and bulk writes capture a delta
of exactly the ids whose membership that call flipped. Three details are load-bearing and easy to
undo by accident:

- **The delta reserves its slot before the flip**, which is why `checkedAdd`/`checkedRemove` are
  deliberately not used — the buffer must be grown before the membership change, never after.
- **The inverse is pushed at the first delta entry, before the first flip.** This is the
  push-before-first-flip invariant, and it carries both strict-reverse ordering under reentrant
  mutation and failure atomicity if the write dies mid-way (e.g. on OOM). It supersedes an earlier
  push-in-a-`finally` formulation, which got the ordering wrong.
- **`aliasesDelegate()` guards self-aliasing**: `removeAll(self)` has to be materialized, because a
  per-id walk over a self-mutating delegate skips members.

Result: `BitmapContainer.clone` went from 13.33 % of allocation to 0.009 %, and total ingest
allocation fell 23.1 %.

### First-touch dedup by stamp, not by map

Dedup used to be `IdentityHashMap#putIfAbsent` per write-touch, and one entity write-touches roughly
sixty participants. `WarmUpTouchStamped` replaces it with a transient `long` on every participant
holding the stamp of the savepoint that last captured it, so the question is one field compare. Cost:
+8 bytes per instance, JOL-verified to add no alignment padding on any of the classes involved. Gain:
461 ms per 100k entities down to about 10 ms, i.e. into the noise floor.

The stamp sequence is **one process-wide `AtomicLong`**. A per-catalog counter was rejected because
two catalogs warming up on different threads would hand out colliding values, and an `int` was
rejected because a wrapped sequence eventually hands out a value some stale mark still holds. Both
failure modes are the same and are why the invariant is worth this much prose: a false match makes
the savepoint **skip** a capture and then report a *successful* rollback over state it never rewound.
For the same reason the constructor **fails closed** on the wrapped `0` stamp rather than letting the
reused values circulate.

The memento bookkeeping moved off map iteration at the same time, onto two parallel indexed lists —
an `IdentityHashMap` iterator walks the whole table and allocates an `Entry` per step, which was
0.87 % of the ON pass's allocation on its own.

### B+ trees: per-node mementos, then per-slot inverses

The trees were wired first at **node granularity**, reusing each node's existing transactional
`Snapshotable.snapshot()`/`restore()`. The wiring itself was the interesting part: roughly ninety
mutator sites repeated `isTransactionAvailable() ? getOrCreate… : null`, and normalizing that idiom
into `WarmUpSavepoint.writeLayer` is what let the mutators reach the savepoint without naming it. A
"re-insert the deleted key" semantic counter-op was forbidden from the outset — the issue's own
property is that inverses are **absolute restores**, never compensating operations.

Measurement then priced whole-node capture at **551 ms per 100k entities**, because a typical ingest
write touches one or two slots of a leaf and the memento duplicates the full key and record columns.
Non-structural bucket-leaf mutators therefore moved to **per-slot, key-addressed** inverses, under a
**granularity-exclusivity invariant**: once a node's whole-node memento has been captured in this
savepoint, nothing finer is journaled for that node — the memento replays last and wins, and a
per-slot inverse replayed against a structurally changed node could misbehave. Multi-bucket adds
journal nothing at leaf level at all, because the bitmap in the bucket journals itself.

Two invariants were bought the hard way and must survive future changes:

1. **Inverses must be absolute, never partial.** A deletion-and-promotion pair for the same key that
   each relied on the other's residue was a real defect, caught by the targeted tests before the
   commit landed.
2. **The journal may only ever be replayed completely, to position zero.** Per-slot suppression means
   any partial replay boundary leaves suppressed writes applied while reporting success. This is
   pinned in the `undoJournal` field JavaDoc; a future partial-rollback feature must build its own
   journal rather than reuse this one.

The same per-slot treatment went to `UnorderedLookupTree`'s spine count adjustments, where 200 of its
290 ms were whole-node captures taken for a single `int` bump. Its leaf and scalar snapshots (~90 ms)
deliberately **stay** whole-node: below the measurement noise floor at 200k entities, so converting
them would be a change justified by nothing. Revisit only if a future profile shows them.

### Always on — and what that cost to earn

The mechanism was developed behind an internal system property so its price could be measured against
a byte-identical OFF path. When the final full-corpus pair came back at **+2.17 % ingest-pool CPU and
+1.80 % wall**, the decision was made in one sentence — *"below 5 % means always-on, because it makes
[the] database consistent in ACID sense much more than [the] current state"* — and the switch was
removed entirely rather than being flipped to default-on.

That is a stronger decision than it looks, and it was taken over two live alternatives. A **public
opt-out in `ServerOptions`** was rejected because a ~2 % cost does not warrant a configuration surface
whose only use is silently trading away per-entity consistency. An **internal kill switch** was
rejected because the mechanism's correctness rests on the fuzz matrix and the conformance backstop
rather than on a retreat path, and because keeping it would have kept the process-wide mutable static
that forced JUnit resource-lock fencing across seventeen test classes. The user contract in
`bulk-vs-incremental-indexing.md` now simply states that every entity write is atomic in both phases.

The one thing it costs: **A/B measurement of the mechanism is now a cross-revision exercise** rather
than a flag flip within one build. The protocol for doing it is documented on
`WarmUpAtomicityIngestBenchmark`.

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Drive `TransactionalLayerMaintainer` in a degenerate no-transaction mode | It forces diff-layer allocation onto the ~94 %-saturated ingest thread — the exact profile warm-up exists to avoid (`2026-07-31-bulk-ingest-write-path`) | Never for warm-up; the whole point of the delegate branch is that it does not allocate layers |
| Plumb a savepoint context parameter down the call chain | The warm-up write path fans out through the entire index-mutation machinery; this is the structural scattering that made the pre-#569 hand-written `undoActions` unmaintainable | Warm-up ever stops being single-threaded, which would invalidate the `ThreadLocal` instead |
| First-touch `clone()` for `TransactionalBitmap` (the original choice) | Measured: the copy-on-write clone freezes containers and bulk ingest re-defrosts per entity — 13.2 % of all ingest allocation | Superseded, not merely declined — see the reversal above |
| Hybrid: keep the clone for bulk ops, journal single bits per operation | Mixed absolute restores do replay correctly, but a mid-savepoint clone re-freezes every container and brings the defrost cost back for all subsequent single-bit writes | Delta bookkeeping itself shows hot in a future profile |
| Per-bit first-touch dedup map inside the bitmap | A bit is rarely touched twice within one entity mutation, so the bookkeeping costs more than the redundant journal entries it saves; strict-reverse replay keeps the redundant entries correct anyway | Only with a profile showing repeated writes to the same bit |
| `CompositeIntArray` for the bulk delta buffer | Its 50-int chunks plus the backing `ArrayList` allocate more than a locally doubled `int[]` capped at the argument size | Deltas ever get large enough that the doubling copies dominate |
| Per-catalog stamp counters | Two catalogs warming up on different threads would collide, and a collision silently skips a capture and reports a successful rollback | Never — the failure is silent and unrecoverable |
| A 32-bit stamp | Wraparound eventually hands out a value some stale mark still holds; same silent-skip failure | Never |
| Keep the `IdentityHashMap` as a fallback for un-stamped strays | It preserves a map lookup on every miss path for a population of zero, and silently re-admits the drift the typed signatures exclude — the compiler now proves coverage | Never; a stray would be a compile error today |
| Semantic counter-op inverses for tree nodes ("re-insert the deleted key") | Violates the mechanism's defining property that inverses are absolute restores of captured state; two such inverses relying on each other's residue was an actual defect found in testing | Never |
| Per-slot inverses for `UnorderedLookupTree`'s leaf and scalar snapshots | ~90 ms per 100k entities, below the noise floor of the 200k-entity A/B — a conversion with no measurable payoff and real invariant surface | A future profile shows them above the floor |
| Extend the `DataStoreChanges` memento to cover direct storage-part writes | Its memento is an `O(1)` journal position; making it cover the parts would recapture the large trapped buffer once per entity | Never — record-level inverses are strictly cheaper |
| Bytecode instrumentation to verify journalling | Disproportionate for a per-structure obligation that a default-`false` declaration plus one runtime choke point already catches on first use | The declaration is ever found to have drifted in practice |
| A per-savepoint deep-compare oracle in production | Reintroduces the `O(N)` cliff the whole design avoids, in the guise of a safety check | Never in production; this is what the fuzz suites do offline |
| Public opt-out in `ServerOptions` | A ~2 % ingest cost does not justify a supported way to silently give up per-entity consistency | The cost regresses materially on a workload shape not covered by the two measured corpora |
| Internal kill-switch system property | A dead configuration surface: correctness rests on the fuzz matrix and the conformance backstop, and keeping it would keep the process-wide mutable static that forced JUnit fencing across 17 test classes | Never; use a revision pair to measure instead |

## Key technical details

- **The living design document is `documentation/developer/stm/savepoints.md`** — its *warm-up
  counterpart* section carries the per-family strategy table, the four recording APIs, the accepted
  residues and the testing map. This record is the *reasoning*; that document is the *description*,
  and it is the one to update when the mechanism changes. `documentation/developer/indexes/mutation-flow.md`
  was refreshed at the same time (it still described the deleted `undoActions`).
- **Entry points:** `WarmUpSavepoint` and `WarmUpTouchStamped`
  (`evita_engine/src/main/java/io/evitadb/core/transaction/memory/`); the bracket in
  `LocalMutationExecutorCollector`; `TransactionalLayerCreator#supportsWarmUpRollback()` and the
  backstop call from `Transaction#getOrCreateTransactionalMemoryLayer`;
  `DataStoreChanges#journalPersistedChange` for the storage side.
- **`WarmUpSavepoint.open()` has exactly one call site, and a great deal rests on it.** Because
  `Transaction#getTransactionalLayerMaintainer()` is `null` exactly when
  `isTransactionAvailable()` is `false`, every index mutator whose journalling sits behind an
  `if (!isTransactionAvailable())` gate is correct *only* because that gate is unconditionally taken
  while a savepoint is open. A second opening site invalidates all of them at once, which is why
  `WarmUpRollbackConformanceTest` asserts the single site, its maintainer-absence guard, and an
  allowlist of the sources permitted to branch on transaction availability at all.
- **Invariants a future change must preserve:** inverses are absolute restores, pushed *before* the
  first in-place write of their operation; the journal is replayed completely to zero, never to a
  mark; per-slot journalling is mutually exclusive with a node's whole-node memento; the stamp
  sequence never reuses a value and fails closed when it would.
- **`TransactionalBitmap#getRoaringBitmap()` stays a no-clone accessor** on the hot read path, with a
  contractual "do not mutate" JavaDoc rather than a defensive copy. Mutating through it bypasses the
  journalling and is a rollback hole.
- **Rollback failure is fatal by design.** Every journalled inverse must be *total* — it may never
  throw for a benign reason — because the only thing behind it is poisoning the buffers.

## Verification

**Measurement conditions**, identical for every figure below: alternating ON/OFF passes, a fresh JVM
per pass, the first pair discarded, "ingest" being the `Evita-request-*` pool summed, on a quiet
machine. Raw profiler artifacts were archived off-repo on the developer's machine and are not
reproducible from this repository; the conclusions are what this record carries.

| Round | Ingest CPU vs OFF | What moved |
|---|---|---|
| Pre-optimization, full 972k-article corpus | **+14.4 %** (+11.9 % server-wide, +11.0 % wall — 595 → 655 s) | first honest price of the mechanism |
| Pre-optimization, price/facet-rich demo corpus | **+13.4 %** (+11.4 % server-wide) | consistent across corpus shapes |
| After bitmap per-operation journalling (D1) | **+10.17 %** (capped 200k) | allocation −23.1 %; the mechanism's own cost 8.09 → 6.59 s per 202k entities |
| After stamp dedup + per-slot B+ leaves (D2) | **+2.2 %** (capped median, 4 pairs) | attribution tax 3,266 → 1,315 ms |
| After per-slot count adjustments (D3) | indistinguishable from D2 | its ~200 ms sits below the 200k noise floor; the profile attribution is its evidence |
| **Final, full uncapped corpus, 975,006 entities/pass** | **+2.17 %** (+2.04 % server-wide CPU, +1.80 % copy wall) | warm-up pair agreed: +2.99 % / +2.04 % / +1.59 % |

The synthetic 50k check that first exposed the allocation problem: ingest-thread allocation
18.6 → 53.5 GB, about 2.9×.

**How the CPU cost was attributed**, since the A/B delta alone does not say *what* to fix: same-build
OFF and ON profiling passes compared per frame in absolute milliseconds. The method was validated by
reproducing the A/B delta from the profile alone (+9.98 % derived against +10.17 % measured). The tax
per ~100k entities decomposed as **3,266 ms** = 551 (B+ leaf mementos) + 461 (`IdentityHashMap`
dedup) + ~1,100 (storage-serialization growth, i.e. memento heap pressure — confirmed by its
collapsing when the allocations fell) + 210–310 (bracketing) + noise. After D2 the same decomposition
read **1,315 ms**: B+ leaf 551 → 100, dedup 461 → 10, storage slice 1,100 → 354.

**Tests.** The 32 savepoint fuzz suites are parameterized over *both* savepoint kinds — 38 scenarios,
the full matrix passing 177/177 in about 27 minutes. Every generation asserts a **mid-savepoint read**
before rolling back, which is what stops the oracle from being vacuous: a read taken only after the
rollback repopulates memoized caches from correct state and never notices a cache the rollback forgot
to invalidate. That step caught five such cases. The functional sweep ran 146/146; the post-D2 sweep
396 tests, 0 failures, including the JOL heap-layout pins for the stamp field; after the switch was
removed the targeted re-run was 34/0. Key classes:
`EntityAtomicMutationRollbackWarmUpFunctionalTest` (end-to-end: a failed entity in a bulk load leaves
neither index entries nor a storage body), `AbstractSavepointFuzzTest` (the mode-parametrized
harness), `WarmUpRollbackConformanceTest` (the source-level invariants) and `WarmUpRollbackBackstopTest`
(an undeclared structure mutated inside a savepoint fails loudly).

The one HIGH-severity defect of the campaign was found by adversarial review rather than by tests:
direct storage-part writes escaped the rollback entirely, and the phase-3 test that should have
caught it was **vacuous** — it asserted only against the trapped cache, which those writes bypass.

## Consequences & open follow-ups

**Accepted residues** — a warm-up rollback rewinds index and storage state, and deliberately not these:

- Primary-key, index-key and internal-price-id **sequences are not rewound**; a reverted entity leaves
  a harmless gap (parity with the ALIVE savepoints).
- **Memoized caches are invalidated, not restored** — one recomputation after a rollback, in exchange
  for never having to *trust* a restored cache.
- **`IndexActivity` timestamps and usage counters count attempted work**, not surviving work (parity).
- **WAL replay keeps `atomicRollback == false`** and opts out of both savepoint kinds.
- **`WARMING_UP` remains contractually single-threaded** — the thread-confined savepoint relies on it
  and does not newly defend against concurrent warm-up writers.

**Open follow-ups:**

- Measuring the mechanism against its absence is now a **cross-revision** exercise; the protocol lives
  in `WarmUpAtomicityIngestBenchmark`'s JavaDoc.
- `UnorderedLookupTree`'s leaf and scalar snapshots keep whole-node capture (~90 ms per 100k
  entities). Deliberate, and only worth revisiting if a profile lifts them above the noise floor.
- The journal is **not** reusable for a partial rollback to a mark; a future feature needing one must
  build its own.

## Related work

- **`2026-07-10-more-optimized-data-structures`** — the `#1252` record that made savepoint
  `snapshot()`/`restore()` delta-bounded through the `UndoJournal`. That strategy is exactly what this
  work ports to a path with no diff layers, and its `O(N²)` cliff is the reason the granularity rule
  exists.
- **`2026-07-31-bulk-ingest-write-path`** — the same WARM_UP write path, measured. Its
  94 %-saturated-ingest-thread finding is the cost constraint every decision here was taken against,
  and it is why the degenerate-maintainer ownership option never had a chance.
- The **#569 / #1252 ALIVE-phase savepoint record**, which this mechanism is the warm-up counterpart
  of, is not on `dev`: it lives only on the unmerged branch `origin/569-atomic-partial-rollback-adr`
  (commit `5fc2b84b4`), so it cannot be linked from here yet. Cross-link it in both directions when
  that branch merges.

## Timeline

- **2026-08-24** — phases 1–4 in one day: context and bracket (`609093b0a`); scalar, array and
  collection journalling (`cba1af9bb`); bitmap, caches and population counters (`53d7a376b`); B+ trees
  per node (`abf00a4d4`); enforcement (`4c7fb4c55`); the fuzz suites parameterized over both modes
  (`d98af3907`)
- **2026-08-25** — first measurement comes back at **+14.4 %**; bookkeeping optimizations
  (`9010915d2`), benchmark harness (`1a1a61553`), the storage-inverse HIGH fix and the `open()`
  ordering invariant from adversarial review (`4e5a306c1`), documentation (`9c34fd0d9`), bitmap
  per-operation journalling (`59a2e497a`)
- **2026-08-26** — three defects closed in the bitmap journalling (`cbfcacbeb`); stamp dedup and
  per-slot B+ leaves (`3b4f9e316`); per-slot count adjustments (`19f09e924`); stamp fail-closed and
  the full-replay invariant pinned (`cc3bf17c1`); final full-scale measurement at **+2.17 %**; the
  always-on decision, and the switch removed
