# WARM_UP bulk upsert and the ICU collation problem

Consolidated from seven working documents. This line ran ahead of the WAL-replay line and fed it: the
collation-key cache built here is the same cache the replay rounds later resized for a 2.02× win.

**Staleness note.** Status language is a historical snapshot. PR #1280 and the follow-ups have merged.

**Workload:** a single-threaded WARM_UP bulk copy of the production catalog (380 016 entities across 18
collections) driven over gRPC by `WarmupCopyCatalogBenchmark` — see `../SPIKE_BENCHMARKS.md`. Baseline
copy **2 020 s (33.7 min)**, of which **`Product` is 90.3 %** (113 136 entities at 16.1 ms each).

---

## Round 1 — the copy is GC-bound, not write-bound

Profiling the server over several independent captures: **55–63 % of all CPU inside G1** (concurrent
marking + remembered-set rebuild), driven by the per-entity mutation path. The write thread is ~100 %
compute/GC-stalled and only **~3 % disk I/O** — faster disks and bigger batches cannot help.

One method dominated: **`ContainerizedLocalMutationExecutor.collectAttributeKeys` — ~41 % of all
stream-pipeline allocation and ~21 % of all `HashMap` allocation.**

Shipped as PR #1280: the mutation path was de-streamed.

---

## Round 2 — with the heap right, the real allocator appears

At **`-Xmx56g`** the G1 death-spiral is gone (GC CPU **63 % → ~12 %**, old-gen stable ~80 %,
concurrent cycles frozen at 12). That is the precondition for measuring anything else — at a
too-small heap the profile is dominated by the spiral rather than by the work.

Re-profiling the `Product` churn (445 905 allocation samples, window entirely within `Product`):

> **`ContainerizedLocalMutationExecutor.verifyReferenceAttributes` = 51.3 % of all sampled
> allocation**, and inside it **`collectAttributeKeys(reference)` ≈ 39 %** — a `HashSet` materialized
> *per reference*, purely to test whether a handful of schema-mandated attribute keys are present.

**The fix: delete the `HashSet`** and probe the reference's attribute map directly, plus hoist the
per-reference capturing lambda to one per call. Order-equivalent by construction. Worth ~46 % of
`Product`-phase allocation and a **~10 % full-migration speedup**.

---

## Round 3 — ICU collation becomes the wall

With the `HashSet` gone, the dominant remaining cost of a WARM_UP `Product` upsert is **ICU string
collation** — re-collating the same strings over and over during sort/filter index insertion.

`LocalizedStringComparator.compare(a, b)` was a bare `java.text.Collator.compare(a, b)`, which
**re-decomposes both operands through ICU on every call** (NFD normalize → `CollationElementIterator`
→ `StringBuilder`/`int[]` buffers). It is invoked O(log N) times per inserted value from the binary
search in `FrontCodedStringColumn.findKeyPosition` and the `SortIndex` ordered insert — and **the
search key is fixed across the whole binary search but re-collated at every hop**, while every stored
candidate touched is decoded to a `String` and re-collated too.

Measured after the round-2 fix: `LocalizedStringComparator`/`Collator` = **33 % / 26 % of CPU** across
two windows, with `StringUTF16.codePointAt` alone **16–20 % self**. Kryo `byte[]` (~25 % of
allocation) is the other big item and is irreducible.

---

## The options, and how they were settled

A dossier proposed four options; an independent review verified the profiler numbers against the raw
collapsed files (**confirmed exactly**) and settled the open empirical questions with a JDK 17 probe.

| option | proposed as | verdict |
|---|---|---|
| **D** — fix the collator configuration (decomposition mode) | "likely cheapest, evaluate first" | **VOID — empirically refuted.** JDK 17's default decomposition for `cs`/`en`/`de`/`fr` is *already* `NO_DECOMPOSITION`; flipping the mode changes neither time nor allocation. |
| **C** — memoize the probe key per search | "believed wrong" | **Dead, confirmed.** One `getCollationKey` costs **2.3× a full compare**, so memoizing only the probe loses. |
| **B** — per-column transient key-byte cache | the original pick | **Mechanism valid** (the byte-key contract was proven) but **superseded** — same win for ~10 % of the code surface and no MVCC hazard. |
| **A** — persist collation-key bytes | "biggest steady-state win" | **HOLD.** Works, but ~5 key-bytes per character ⇒ ~2.5× string bytes on disk, plus a new `_2026_x` serializer and a BWC reader. Revisit only if query-time collation later dominates. |
| **P2** — shared per-locale collation-key cache inside `LocalizedStringComparator` | new | **Recommended and implemented.** Covers both hot trees through the one class they already share; bounded ~1–2 MB/locale; no on-disk format change. |

The decisive measurement: comparing cached collation-key bytes via `Arrays.compareUnsigned` is
**60×–3000× cheaper per compare, with zero allocation**, than re-collating. That is why the answer was
never a config flip.

---

## P2 results

Measured on a 90 s JFR window during single-thread WARM_UP `Product` copy, cache at its then-default
8192 slots:

| collation share of `Product` copy | baseline | P2 | change |
|---|---|---|---|
| **allocation** (methodology-clean) | 38.89 % | **7.29 %** | **−31.6 pp, −81 % relative** |
| CPU | 26.33 % | 12.09 % | −14.2 pp, −54 % relative |

**Allocation is the decisive axis** — both sides use allocation-site sampling and the workload is
GC-bound. The hit path (`Arrays.compareUnsigned`) allocates nothing, so the residual 7.29 % is
entirely cache **misses** still computing keys.

Two methodology notes that must travel with these numbers:

- **The CPU comparison is not like-for-like** and is *conservative*: the baseline is async-profiler
  (samples all threads including ~9.8 % GC), the new figure is JFR `ExecutionSample` (application
  threads only, GC not sampled). Renormalizing the baseline to app-threads-only gives 29.2 %, so the
  fair drop is **29.2 % → 12.1 % (−59 %)**.
- **Do NOT report "GC 9.8 % → 0 %".** That is a profiler-coverage artifact, not a measured GC win.

### The residual pointed straight at the next round

Post-P2 CPU frame counts (6003 total): `LocalizedStringComparator.compare` 714, `keyFor` 690,
`getCollationKey` 477, `CollationElementIterator` 374, `compareUnsigned` **15**. Hits are so cheap
they barely sample; **~7.9 % of CPU was still in `getCollationKey`, i.e. misses**.

The diagnosis — *"at 8192 slots (2-way direct-mapped) Product's localized-value working set
thrashes"* — is exactly what the WAL-replay round 1 then confirmed and fixed by sizing the cache to
1 M slots for a **2.02×** win. See `2026-07-27-wal-replay-rounds.md`.

---

## What carries forward

- **Heap sizing is a precondition, not an optimization.** At the wrong heap the profile shows the GC
  death-spiral instead of the workload. Establish a heap where GC is not the wall *before* ranking
  anything else.
- **A cache probe that allocates nothing changes the shape of the measurement.** Once hits became
  free, the profile stopped being about collation *arithmetic* and became about cache *misses* — a
  different problem with a different fix (sizing, then structure).
- **Verify a "cheapest option" empirically before ranking it first.** Option D was the dossier's
  leading hypothesis and was void — the JDK already did what it proposed.
- The remaining structural idea, still open: **store the collation key alongside the value in the sort
  index** so the compare path never consults a cache at all. It touches Kryo format and is
  correctness-critical (a collation bug silently corrupts sort order → wrong query results), so it
  needs real sort-order and BWC coverage. This is Option A/round-3's goal, promoted to the only
  remaining collation lever now that cache sizing is exhausted.
