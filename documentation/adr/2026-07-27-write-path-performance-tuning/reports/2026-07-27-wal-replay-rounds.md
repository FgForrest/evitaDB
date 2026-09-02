# Production-catalog WAL replay — the full measurement line (rounds 0–7)

Consolidated from seven working documents written 2026-07-20 → 07-27. The workload: real production
transactions from a production catalog's write-ahead log, replayed against an embedded evitaDB instance
through the real session/commit API — exactly the path a production write node runs.

**Staleness note.** Status language carried over from the source documents is a historical snapshot.
All work described here has since merged (PR #1317). Check `git log` for what actually shipped.

**The dataset is gone.** The production-catalog snapshot and WAL, and the raw profiles measured against them,
were deleted once tuning finished and the conclusions below were captured — byte-for-byte samples of
a dataset that no longer exists have no future comparison value. Re-measuring against a fresh export
uses the same harness, since generalized and committed: see the `wal-replay-profiling` Claude Code
skill (`.claude/skills/wal-replay-profiling/`) for how to run it again.

---

## Headline

| round | date | change | result |
|---|---|---|---|
| 1 | 07-20 | collation cache 8192 → 1 M slots (config only) | **2.02×** (537 → 266 s) |
| 1 | 07-20 | `removePrice` O(N) tree materialization removed | **1.34×** (266 → 198 s) |
| 2 | 07-21 | heap-derived cache default | 197 s with **no configuration** |
| 3 | 07-23 | B+ tree cursor reuse, striped collator pool, ThreadLocal removal | −4 % wall, **−20 % allocation** |
| 4 | 07-24 | commit-merge clean-subtree prune | small-tx floor **1.7–1.9 s → ~330 ms (9×)** |
| 5 | 07-24 | prune extended to the index-map-diff case | serialized wall **−24 %**, big-tx median **−43 %** |
| 6 | 07-25 | attach-time GLOBAL resolution | latency intercept **−12.5 %** |
| 7 | 07-26 | three allocation sites deleted | **−34.7 % bytes/op**, visible median **−42.9 %** |

End to end on the same 300-transaction slice: **537 s → 198 s** on the throughput axis (round 1), and
separately **1257 s → ~412 s serialized** with the small-transaction visibility floor going
**~2 998 ms → ~301 ms** on the latency axis (rounds 4–6).

---

## Round 0 (tmpfs) — why its numbers appear nowhere else

The first investigation ran on **tmpfs**, where `fsync` is nearly free, against an older WAL slice
(69 mutations/tx vs the later 141) and without a mid-run compaction. Its figures — 736 tx / 50 649
mutations in ~15 s, 154 GB of garbage, 24 % of wall in GC — are **not comparable** with anything
after it, and the apparent "49 tx/s → 0.6 tx/s" is an artifact of changing three axes at once, not a
regression.

What carried over is the **allocation-share** analysis, which does not care what disk you are on:

| share | bucket |
|---|---|
| **68.9 %** | `TrunkIncorporationTransactionStage` — the commit-side trunk merge |
| ├ 50.4 % | `getStateCopyWithCommittedChanges` / `createCopyWithMergedTransactionalMemory` |
| └ 17.0 % | `EntityCollection.applyMutations` (WAL mutations re-applied into the new layer) |
| **23.2 %** | `EntityCollection.applyMutations` on the **session** side |
| **4.1 %** | conflict scan + WAL append |

Two structural facts fell out and shaped everything after. **Every mutation is applied twice** — once
into the session's transactional-memory layer, then again when the trunk stage replays it (~40 % of
allocation combined); that is by design. And `ReducedEntityIndex.createCopyWithMergedTransactionalMemory`
alone was **44.8 %** of profiled allocation — one full index copy per touched index per commit. That
single line is what rounds 4–6 eventually attacked.

---

## Round 1 (2026-07-20) — collation, then an O(N) defect hiding behind it

300 transactions / 42 268 mutations, real disk, `syncWrites=true`. Baseline **537.2 s**
(reproducible to 1.4 %).

**It was not GC** — 31.5 s of pause across a ~630 s lifetime (~5 %), zero evacuation failures, zero
full GCs. The stalls were real work.

**It was string collation**: 58.7 % of the replay thread's wall time and 32.2 % of the trunk's, all
of it `SortIndex`'s order-statistic B+ tree re-deriving JDK collation keys on every node comparison
of every descent.

The cache was a 2-way direct-mapped **8192-slot** per-locale structure. During a descent the *probe*
value hits, but the values it is compared against are **leaf separators drawn from the whole corpus** —
for a ~1 M-product localized catalog the pivot working set is effectively the entire corpus, so
nearly every comparison recomputed a full key.

| cache size | wall | vs baseline |
|---|---|---|
| disabled | aborted after 20 min | ~3–10× slower (direction unambiguous, factor not measured) |
| 8192 (default) | 537.2 s | — |
| 262 144 | 329.4 s | 1.63× |
| **1 048 576** | **266.1 s** | **2.02×** |
| 4 194 304 | 271.6 s | 1.98× — **the knee; the hard cap is fine, only the default was wrong** |

Verified by profile, not inferred: the collation subtree fell 58.7 % → 10.8 % (replay) and
32.2 % → 3.3 % (trunk). RAM cost is ~100–150 MB **per locale** at 1 M slots, which is why the fix
became heap-derived rather than a raised constant (round 2).

### The defect collation was hiding

With the cache sized, `Array.newInstance` jumped to ~11 % of *both* threads, with one caller at
99.2 % of samples: `PriceListAndCurrencyPriceRefIndex.removePrice` materialized the **entire** price
B+ tree into a fresh array on **every price removal**, to answer one boolean —
`entityPrices.containsAnyOf(this.priceRecords.toArray())`. O(N) time *and* O(N) allocation per removed
price, to decide a question about the entity's own handful of prices.

Fixed by inverting the containment probe (`priceRecords.search(id)` per the entity's own internal
price ids — O(k·log N), zero allocation). Semantics are identical because the removed price is
already out of the tree three lines above. **1.34× further → 198.5 s; combined 2.71×.**

Because the change is semantics-preserving, a fail-first test is impossible, so the three new
regression tests were validated two ways instead: they **pass against the pre-fix implementation**
(proving semantics are preserved) and they **fail against a plausible wrong optimization**
(`getInternalPriceIds().length > 1`) while all four pre-existing tests still pass — demonstrating the
coverage gap was real. The gap: eviction must be decided against *this index's* content, not the
entity's total price count, since a ref index holds only a subset.

### Speeding up apply just moves the queue

| cache | apply mean | changes-visible mean |
|---|---|---|
| 8192 | 1702 ms | 22.3 s |
| 1 048 576 | 773 ms | **33.9 s** |

Tempting and wrong to call trunk incorporation "the throughput ceiling" — throughput went
0.6 → 1.5 tx/s from apply-side fixes alone with trunk untouched. Trunk is the **visibility-latency**
ceiling, unbounded under sustained write load; it becomes a throughput ceiling only with concurrent
writers or `WAIT_FOR_CHANGES_VISIBLE` clients.

---

## Round 2 (2026-07-21) — heap-derived default, and a census that corrected itself

The collation-cache default became **heap-derived** (`maxMemory/50/256`, clamped to
`[8192, 1<<20]`): a 24 GB server heap gets the measured 1 M-slot optimum automatically, a 128 MB
embedded heap keeps 8192. Validated at **196.963 s with no configuration at all**.

A merge-cascade copy census was added. It showed the cascade performs **~506 000 copies per
transaction while only 0.97 % are for producers that actually changed**.

**An earlier draft claimed "61.8 % avoidable" — that was a classifier artifact.** The defensible
upper bound is ≈15 %. Recorded because the corrected number is what later rounds were sized against.

---

## Round 3 (2026-07-23) — the phase split, and why "the numbers didn't move"

The committed round-2 recommendations together moved the 300-tx replay only **197.5 → 189.5 s (~4 %)**.
That impression was correct, and this round explained it: **single-writer throughput is bound by the
replay thread's session-apply phase, while visibility latency is bound by the trunk thread — which is
100 % CPU-saturated and never parks.**

| phase | cost |
|---|---|
| session apply (thrown away at commit) | ~530 ms mean, 5 ms median |
| conflict resolution | ~13 ms |
| WAL persistence incl. fsync | ~31 ms (fsync ≈ 7 ms/tx) |
| **trunk incorporation → visible** | **mean 3.72 s / median 2.96 s** — **6–7× the session-apply cost for the same mutations** |

**Every storage/transaction knob is a no-op for this workload** — `syncWrites=false`,
`flushFrequencyInMillis=10000`, `minimalActiveRecordShare=0.05`, and swapping G1 for ParallelGC all
landed within noise. Disk I/O is ~1 % of both hot threads. This workload is CPU-bound.

**GC consumed 64.3 % of all CPU cycles** but did not bind wall-clock — the concurrent work rode on
22 idle cores.

Three fixes landed: a **reusable B+ tree descent cursor** (`CumulativeWeightBPlusTree$Cursor.<init>`
was **19.1 % of all sampled allocation** — two 64-slot arrays per insert/remove/updateWeight), a
**striped collator pool** replacing a per-miss `ThreadLocal.get`, and a plain field replacing a
per-instance `ThreadLocal` in `LocalizedHistogramIndex`. Net **189.5 → 182.0 s**, allocation
**−20.2 %**, cursor site 19.1 % → 0.01 %.

**An honest caveat that changed the collation story.** `getEntryAfterMiss` fell 10.7 % → 0.0 %, but
the *collation subtree share held at ~23 %* — the cost the ThreadLocal probe carried now shows as
`keyFor` self-time. A diagnostic counted only 483 `ThreadLocalMap` entries JVM-wide across 97 threads
(no pollution), so a large part of the "ThreadLocal" attribution was really **memory latency of
probing the 1 M-slot cache** (two dependent random reads across a ~4 MB slot array), which the
profiler pinned on adjacent map-walk frames.

### The per-transaction-size decomposition that set the next target

`visible_ms ≈ 2771 + 6.98 × mutations` — a **fixed per-pass cost of ≈2.77 s** and a marginal cost of
≈7 ms/mutation. A one-mutation commit took 2.9 s to become visible; **99.9 % of that is
size-independent trunk-pass overhead**. The marginal 7 ms/mutation is unremarkable. The fixed
intercept was the anomaly.

---

## Rounds 4–6 (2026-07-24 → 07-25) — killing the intercept

**Goal: small-transaction visibility well under 500 ms.**

Instrumenting the finalize window showed the merge cascade was **~97 %** of it (~1600–1900 ms vs
35–47 ms of flush), and ~99 % of the merge was **clean-discovery**: a small transaction dirties a
handful of indexes but the walk rebuilt all of them (`gDirty=1, redDirty=0`, yet the full forest
rebuilt).

### Round 4 — the prune

Rebuild only what changed; carry the rest across the catalog version by reference. The signal is
`DataStoreChanges.popTrappedUpdates`, which snapshots the dirty index-key set before draining it —
the same set the flush persists, i.e. ground truth. Flush runs strictly before the merge, so the
snapshot is fresh.

A clean reduced index whose GLOBAL was rebuilt must be **re-shelled** rather than carried, because
its price chain captures its scope's GLOBAL through a `SuperIndexResolver` and would otherwise point
at a retired GLOBAL. Only the thin price spine is rebuilt; attribute/hierarchy/facet sub-trees are
shared by reference.

**The decisive optimization was the shell constructor.** The first working version re-shelled through
the persisted-state reconstruction constructor, which clones entity-id bitmaps and re-walks every
component — at **179 086 reduced indexes re-shelled per small tx**, ~4 µs each ⇒ **510–740 ms**. A
dedicated carry-by-reference shell constructor sharing every sub-structure *and the immutable
baseline* runs at **~0.3 µs each ⇒ ~55 ms**.

| small steady-state tx | before | after |
|---|---|---|
| merge | ~1600–1900 ms | **~283–293 ms** |
| finalize window | ~1.7–1.9 s | **~324–340 ms** |

Small-tx median **330 ms**, ~91 % under 500 ms, against a baseline tail median of ~2 998 ms — a **9×**
cut. Serialized 300-tx wall **1257 → 545 s**. Fit `255 + 7.9 × mut`: the size-independent floor
dropped ~91 % while the marginal cost held — the exact signature of a clean prune.

Correctness rests on loud backstops rather than inspection: a mis-identified clean index orphans a
transactional layer → `StaleTransactionMemoryException` at commit; a stale super pointer → the
`wireOrVerifySuperIndexes` premise assertion; an unexpected index kind → an explicit premise assert.
None fired across the suite or a 300-tx replay, and the re-shell path was confirmed exercised by a
temporary counter — so the green is coverage, not absence.

### Round 5 — the fallback was a third of trunk work

Collections whose index *set* changed (an index added or removed → map diff layer) bypassed the prune
entirely and paid the full O(#indexes) walk. Direct instrumentation quantified it: **63 of 90 big
transactions (70 %) fell back; 1 of 210 small ones**. Per-merge cost on ≥50 k-entry maps was
**761 ms median (fallback) vs 139 ms (pruned) — 6.8×**, and the fallback was **32.2 % of total
visible latency** — an independent method landing on the profile's ~33 %.

The delta those full merges existed to apply: **median 0.0058 % of the map** (max 0.298 %); 22 of 118
fallback merges were triggered by a **single key**.

`MapChanges.createMergedMap` gained a `ValueMerger` hook consulted for every surviving key, so the
commit-time merge no longer has an unpruned route.

| metric | before | after | delta |
|---|---|---|---|
| serialized wall | 548.7 s | **415.6 s** | **−24.3 %** |
| big-tx median visible | 4433 ms | **2511 ms** | **−43.4 %** |
| total visible (sum) | 413.2 s | 289.6 s | −29.9 % |
| small-tx p90 / p99 | 467 / 659 ms | 367 / 480 ms | −21 % / −27 % |
| fit | `241 + 8.07 × mut` | `272 + 4.92 × mut` | **slope −39 %**, intercept flat |
| GC young pauses / STW | 322 / 39.5 s | 135 / **10.8 s** | **−73 % STW** |

The slope moving while the intercept stayed put is the predicted signature — the fallback was a
big-tx phenomenon, so it lived in the marginal term. **This was pre-registered**: acceptance was
declared as wall and big-tx median *before* the run, explicitly *not* the small-tx floor, so a
correct outcome could not later be read as a miss.

The unpruned bucket went **2463 samples → 0**.

### Round 6 — the mislabelled bucket

Round 5's last cheap lever was listed as "catalog-level merge, 7 %". Classifying by the immediate
descent out of `Catalog.createCopyWithMergedTransactionalMemory` showed that was wrong: **87 % of the
bucket was `EntityCollection` re-attachment wiring** reached *through* the catalog merge, because the
`Catalog` constructor calls `attachToCatalog` on every collection it forwards. There was nothing
catalog-level to prune.

`wireReducedIndexSuperIndexes` called `resolveGlobalIndex(scope)` per reduced index — allocating an
`EntityIndexKey` and doing a map lookup each time, for **every** collection on **every** version bump.
Product alone paid ~251 k allocations and lookups per commit. The GLOBAL is now resolved at most once
per scope, memoized in a `Scope.ordinal()`-indexed array.

`resolveGlobalIndex` went **296 samples → 0**; latency intercept **272 → 238 ms (−12.5 %)**, small-tx
median **345 → 301 ms**, slope flat. Acceptance was again pre-registered as the profile-bucket delta
rather than wall, because per-commit-uniform work lands in the floor and is nearly invisible in a wall
number dominated by big transactions.

**Deliberately not done:** skipping the wiring walk for a wholesale-carried collection.
`PriceRefIndex.wireOrVerifySuperIndexes` is not uniformly a verification — with a null resolver it
performs the *first* wiring, exactly the state a re-shelled index is left in. The loop is load-bearing
on the dirty path, and `attachToCatalog` is shared with disk load and the warm-up copy path.

---

## Round 7 (2026-07-26) — three allocation sites

Detailed in `../../write-path-performance-tuning/reports/` history and summarized here. Each site had
**one dominant caller**, which is why each was a single concrete change rather than a diffuse cost.

1. **`SortedIntArrayCodec`** — `Assert.isPremiseValid(cond, "msg" + i + …)` built the message on
   **every element of every array written**; 99.88 % of the site's 12.08 % allocation was the concat's
   `byte[]`. Became an explicit `if (…) throw`. **This pattern is a bug wherever it appears in a
   loop.**
2. **`Attributes.<init>`** — a `stream().filter().map().collect()` locale pipeline, 13.66 % of
   allocation, replaced by a lazy loop.
3. **`SortIndexChanges.computePreviousRecord`** — called `sortedRecords.getArray()`, flattening the
   **entire** sort index, then `Arrays.copyOfRange` for one value block, **per sort-attribute insert**.
   12.71 % of all allocation. Became a binary search over positional reads.

**Result: `gc.alloc.rate.norm` −34.7 % (387.10 → 252.92 GB/op), wall −15.8 %, changes-visible median
−42.9 %, gc.count −29.6 %.** Predicted 35.2 % by summing three leaf-attributed profile shares;
measured 34.7 %.

The `Attributes` residual was **over-predicted**: ~5 % was reserved as irreducible, the measured
residual is 0.8 % (−96.3 % absolute, not the −76 % predicted). Most reference attribute sets carry no
localized attribute at all, so the lazy path returns `Collections.emptySet()` and never allocates.

---

## Final state (2026-07-27 census)

Full census in `../../PROFILE_2026_07_27.md` history; the durable conclusion:

| subsystem | allocation | app CPU |
|---|---:|---:|
| collation (`CollationKeyCache` + `LocalizedStringComparator`) | 11.3 % | **22.8 %** |
| `FrontCodedStringColumn` family | **20.8 %** | 11.5 % |

Those two are a third of all application work and fail in opposite ways — collation is CPU-bound,
front-coded strings allocation-bound.

**Cache sizing is spent as a collation lever**: the slot count is heap-derived and already pinned at
its 1 048 576 maximum under `-Xmx24g`. The remaining idea is to stop asking per comparison — a sort
index carrying the collation key alongside the value — which is a design change needing its own
measurement.

`TransactionalLayerMaintainer.getEntryIfExists` has receded to 4.7 % of app CPU and is no longer a top
cost; it shrinks only by making *fewer* probes, not faster ones.

### What is left, and why none of it is cheap

1. **Trunk re-apply, ~38 %** — every mutation is applied twice by design. This is the ~5 ms/mutation
   slope. Removing it means carrying the isolated run's index diff forward instead of replaying it:
   large surface, MVCC-sensitive, its own project.
2. **Flush / persistence, ~17 %** — fsync + Kryo + compaction spikes. Only reachable by weakening
   durability or changing serialization; both are policy decisions.
3. **Merge walk / map build, ~14 %** — the residual O(#indexes) map materialization per commit. A
   persistent (CHAMP) index map would only help if most values were carried unchanged, but the GLOBAL
   is dirty nearly every transaction and the price capture forces a re-shell of every reduced index in
   that scope. Capped upside; removing the capture is dead on MVCC grounds.
4. **GC** — now ~10.8 s STW over a 415 s run; further gains are heap tuning, not code.

---

## Durable lessons

These cost the most to learn and are the reason this document exists.

**Size a finding before ranking it.** The first round led with an eager `toString()` on a hot path as
the "standout, highest-confidence" fix and ranked the merge cascade *fourth of six*. The `toString()`
is **0.21 %** of profiled allocation; the cascade is **≈69 %**. The analysis was correct — it was
simply never sized before being ranked. Weighting, not discovery, was the missing step.

**A profile share is not a realizable saving.** The allocation profiler accounted for ~20 GB of the
~154 GB the GC actually processed, so its percentages are shares of an eighth of the truth. Removing
a frame worth "18.9 % of profile" moved the real metric by **−0.2 %** — below a ~3 % noise floor.
TLAB-sampled profiles are excellent at ranking *candidates* and poor at predicting *magnitude*.

**A provably-correct change with an unmeasurable benefit can still be the wrong change.** That −0.2 %
change removed genuinely dead work and was regression-tested green across 6021 tests. It was
**reverted** anyway: it cost +141/−58 lines across 10 files and introduced an invariant that *nothing
enforces* — "`collectManifest` must be side-effect free" — in the baseline/reclaim seam, the same seam
behind the stale-twin bugs and the dirty-scope NPE. A future contributor could put a page-snapshot
refresh inside it and every test would stay green. An unenforced invariant in the most
corruption-prone part of the storage layer is too high a price for a saving that cannot be measured.
If revisited, the contract needs *enforcement*, not documentation.

**Pre-register the acceptance metric, and say which metric will NOT move.** Rounds 5 and 6 both did
this, and in both cases the metric that did not move (small-tx floor in round 5, wall in round 6) would
otherwise have read as failure.

**GC cost tracks what survives, not allocation volume.** Cutting a third of allocation bought only a
seventh off GC CPU, because what was deleted was short-lived young-gen garbage. GC's *share* can rise
while its absolute cost falls, if the application side shrinks faster.

**An `Enum.ordinal`-style leaf in a hot loop is usually the inlined loop body.** Round 6's residual
144 `Enum.ordinal` samples looked like a further ~1.5 % lever. Implemented as a front cache, they did
not vanish — they moved into `wireReducedIndexSuperIndexes` (17 → 201 samples). The change was
reverted. Prove a frame is real before optimizing it.

**Compare absolute sample counts, not percentages, across runs.** Trunk-active totals differed
substantially between runs (6785 / 7891 / 5805), so bucket percentages are not comparable; and when
total allocation falls, every surviving site's share inflates and reads as a regression.

**Structure sharing moves latency; deleting an allocation site moves bytes.** Lead with visible-latency
median for the former and `gc.alloc.rate.norm` for the latter. Predicting the wrong one cost two rounds.

**Stop tuning storage knobs for this workload.** `syncWrites`, `flushFrequency`,
`minimalActiveRecordShare` and collector choice are measured no-ops here; fsync is ~7 ms/tx against a
multi-second trunk phase. They may still matter on I/O-constrained hardware.
