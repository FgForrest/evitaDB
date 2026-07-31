---
title: Take the four contained bulk-ingest wins, reject the two that trade an invariant or add complexity, and defer the one worth more than all of them
date: 2026-07-31
updated: 2026-07-31 22:15
status: accepted
kind: optimization
issues: [1342]
prs: [1348]
areas: [evita_engine/index/bPlusTree, evita_engine/index/invertedIndex, evita_store/evita_store_key_value/store/compression]
supersedes: []
superseded-by: []
relates: [2026-07-10-more-optimized-data-structures, 2026-07-27-write-path-performance-tuning]
---

# Bulk-ingest write path — four wins taken, two refused, one deferred

Six contained optimizations for the WARM_UP bulk-ingest path were triaged against a profile of a
972 611-article corpus. Four landed. **Two were refused** — not because they were unspecified, but
because one buys its saving by changing an invariant and the other adds complexity for a payoff that
halves under inspection. A seventh item, deliberately out of scope, is worth more than the six
combined and is the standing next move.

## Why

Bulk ingest of the 972 611-article reference corpus (5 731 MB of source payload) ran **366 s wall, of
which the ingest thread is 345 s CPU — 94 % saturated, and 78 % of all process CPU on 1.18 of 24
cores.** Wall time is essentially that one thread.

That single fact set the whole selection rule: **nothing helps unless it removes work from that
thread.** GC (0.8 % of wall) and the transaction thread already run concurrently and cost no wall
time, so ordinary "reduce allocation" instincts do not apply here. The two dominant blocks on the
saturated thread were **deflate at 107 s (29 %)** and **`FrontCodedStringColumn` at 78 s (22 %
inclusive)**.

The constraint that made the selection non-obvious: several candidates *do less work* and are still
not free, because they pay for it in a weakened invariant. Distinguishing those from strict
subtractions is what the triage below exists to do.

## Decisions taken

Ordered by implementation ease, which is how the source triage presented them — deliberately not by
payoff, so a reader can draw a line and take a prefix.

| Decision | Why | Status |
|---|---|---|
| **Lower the storage deflate level 6 → 3**, hardcoded in `ZipCompressionFactory` | Measured: +1.4–3.2 % size on serialized entity records for 1.46–1.79× faster; deflate is 107 s of the 366 s baseline | landed |
| **Do not make the level configurable** | One level won on every data shape tested, so no deployment would want a different value — and a knob advertises a tuning axis whose correct value is already known while adding a configuration surface to maintain | landed as a constant |
| **Pass the decode scratch into `encode(...)`** instead of re-fetching the same thread-local | Strict subtraction of work, semantics unchanged. Worth ~1–2 s — taken because it is free, not because it matters alone | landed |
| **Replace `compareUnsignedBytes`'s byte loop with `Arrays.compareUnsigned`** (change 3B) | A true drop-in: the JDK returns the range-length difference when one range is a prefix, exactly matching the old `aLen - bLen`. On the **write** path via six `findKeyPosition` call sites, each leaf insert paying ~8 comparisons | landed |
| **Replace `commonPrefix`'s byte loop with `Arrays.mismatch`** (change 3A) | Gated on the shared-prefix length distribution rather than on whether the intrinsic fires; the gate was measured and cleared | landed |
| **Answer the insert-boundary asserts from the insertion index** instead of decoding keys | `assertInsertBoundaries` did two full front-coded decodes, two `String` materializations and two comparator invocations — on localized attributes, two collator calls — to ask "was this a head/tail insert?", which an integer already answers | landed |

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| **Item 4 — incremental `bmpSafe` flag** (compute `newFlag = oldFlag && isBmpSafe(newKey)` instead of re-deriving by scanning) | **It buys its saving by changing an invariant, and does not qualify as "take it because it does less work."** Two concrete costs: (a) a **one-way latch** — `removeKeyAt`/`clearAt`/`fillEmpty`/`copyRangeTo` cannot cheaply know the last supplementary character just left, so a column that *ever* held one permanently loses the fast path, a lasting degradation on a long-lived leaf; (b) `encode` is the single funnel for every mutator and today *derives* the flag, which is why it cannot be wrong — accepting a precomputed flag turns it into "trusts its caller", and a future mutator silently inherits a stale one. This file already carries one unenforced prose contract (the `duplicate()` structural-sharing warning); a second was refused | Weighed on measured numbers rather than principle, **and** the caller contract is *enforced* rather than documented. Suggested shape if revisited: keep the deriving overload as default, add an explicit `encode(..., boolean knownBmpSafe)` only `insertKeyAt` may call |
| **Item 6 — re-encode only from the enclosing restart point** (memcpy untouched front-coded blocks) | **The payoff halves under inspection and the complexity does not.** Inserting one entry shifts every subsequent entry's *index*, so entry 16 (a restart point, stored in full) becomes entry 17 (a delta) — every downstream block's **encoding** changes, not merely its position. "Memcpy the tail" is wrong; the real scope is memcpy before the enclosing restart point and re-encode from there, averaging ~50 % rather than ~94 %, i.e. ~5 s not ~20 s. Against that: the class currently has a single, simple, obviously-correct encode path. It also **breaks `bmpSafe` derivation in the unsafe direction** — memcpy'd bytes are never scanned, so the flag goes stale `true`, a correctness bug rather than a slowdown | The ~50 % estimate is confirmed by measurement *and* item 4 lands first (or both together). Any attempt must preserve the MVCC contract: `data`/`restartOffsets` are structurally shared across transaction layers and sound only because every mutator replaces them by whole reference — editing `data` in place to reuse slack would silently corrupt other layers |
| **Deflate level 1** (faster still, up to 3.1×) | **+20.4 % size on 64 KB prose-like records** — and text-heavy attribute bodies are exactly the case the deflate change exists to help | Never for this corpus shape |
| **Deflate level 9** | Strictly bad: 1–6 % size saved for 1.3×–5.5× *slower* | Never |
| **A `compressionLevel` option on `StorageOptions`** | See *Decisions taken* — one level wins everywhere measured, so the knob would only add surface | A data shape appears where level 3 loses |

## Deferred, and worth more than everything above

**Moving deflate off the ingest thread entirely** — parallel compress with strictly in-order append —
is estimated at **90–110 s**, more than every item in this document combined. It was excluded as a
separate piece of work rather than rejected, because it is real engineering rather than a contained
change:

> Record N+1's file offset is arithmetic on record N's **compressed** length, which forbids
> out-of-order completion.

That constraint is the whole problem, and any future attempt starts there. **Neither item 4 nor item 6
is the next thing worth doing** — this is.

Also explicitly out of scope and *not* covered by items 2–6, so it is not mistaken for handled: ~7.3 s
(2 %) in the internal-node descent over boxed `String[]` keys in `TransactionalBucketBPlusTree`. No
design exists for it.

## Key technical details

- `ZipCompressionFactory.COMPRESSION_LEVEL = 3`, used by `createCompressor()`. **This is not a format
  change**: DEFLATE level affects only the encoder, every level emits a stream the same `Inflater`
  reads, so catalogs written at level 6 stay readable and level-3 catalogs are readable by older code.
  No version bump, no BWC handling, nothing migrates.
- **Compression must be measured per record, not as a stream.** `ObservableOutput` does
  `reset()` / `setInput(...)` / `finish()` with a single `deflate()` per storage record, so the
  dictionary never carries across records. Benchmarking the payload as one long stream measures a
  system that does not exist here.
- `Arrays.mismatch` returns a **relative** index and `-1` when no mismatch is found within the shorter
  range, so it needs normalizing to the common length. The existing use at `Art.java:374` does **not**
  carry that normalization and is not a copy-paste source.
- Change 3B does nothing for collated attributes: `compareUnsignedBytes` is reached only when the
  BMP-safe byte fast path is armed, which requires `ValueColumnFactory.isNaturalOrder(comparator)`. A
  localized attribute carries a collator and takes the `String` path. The win is real but confined to
  natural-order columns.
- `RESTART_INTERVAL = 16` resets the shared prefix to zero every 16 entries by construction, so a
  meaningful fraction of `commonPrefix` calls compare *short* runs — which is why 3A needed a
  distribution gate rather than an intrinsic-fired check.

## Verification

Landed on `1342-sort-index-bucket-anchored-inserts`: items 2, 3B and 5 in `8b6c2a2e8`, items 1 and 3A
in `950c803f9`. Merged as PR #1348 (2026-07-30) with the branch merged into `dev` again at
`c27c7bb5f` (2026-07-31), which is the date this record carries.

Item 3A was gated by four new `@Benchmark` methods on `FrontCodedFindKeyBenchmark`
(`commonPrefix_scalar` / `commonPrefix_intrinsic` and the pair for the comparison), measured at the
observed prefix-length distribution rather than assumed.

**Three caveats that must travel with the deflate numbers:**

- **Compression CPU only** — `Inflater` was never run. A lower level means larger payloads, so every
  future *read* inflates more bytes; that cost is unmeasured.
- **No I/O, and fixed 4/16/64 KB chunks** rather than the real variable record-size distribution — and
  the trade improves with record size, so the chunk choice partly determines the answer. If a phase is
  I/O-bound rather than deflate-CPU-bound, the win shrinks or inverts.
- **Hand-rolled `nanoTime` loop, not JMH.** A level-6-against-level-6 control came out at 0.98×–1.06×,
  so run-to-run noise is roughly ±6 %. Size deltas are deterministic and solid; the speed *direction*
  is far outside noise, but individual speedup figures are worth ±6 % at best.

The ~36 s (~10 % of wall) end-to-end figure for the deflate change is **extrapolated from the 107 s
block, not measured**. An end-to-end ingest is the experiment that would settle it and is the
outstanding gap on that item.

A correction worth keeping: the "3–5× less CPU for 10–15 % worse ratio" figure that motivated the
deflate item is **zlib folklore and wrong here** — level 3 buys 1.3–2.4×, and level 1 reaches 3.1×
only where it also breaches the size budget. The direction was right, the magnitude was not.

## Consequences & open follow-ups

- **All figures are from the WARM_UP (bulk) profile.** ALIVE (transactional) ingest was not analysed
  and **none of these estimates transfer to it unexamined.**
- The deflate change's end-to-end effect is unmeasured (above).
- Items 4 and 6 remain available with the reasons above; item 4 must land before or with item 6, since
  item 6 removes the derivation item 4 would have made incremental.
- The deferred deflate-parallelisation item is the standing next move for this path.

## Related work

- **`2026-07-27-write-path-performance-tuning`** — the same `FrontCodedStringColumn` family, attacked
  from the transactional commit path rather than bulk ingest. That record's final census left
  `FrontCodedStringColumn` at 20.8 % of allocation and 11.5 % of app CPU; this line attacks its CPU
  side under a different workload.
- **`2026-07-10-more-optimized-data-structures`** — introduced the front-coded column, the restart-point
  encoding and the paged inverted index this work optimizes.

## Timeline

- **2026-07-30** — PR #1348 merged the `1342-sort-index-bucket-anchored-inserts` line
- **2026-07-31** — items 1 and 3A measured and taken; branch merged into `dev` again at `c27c7bb5f`
- **2026-07-31** — the proposal document retired, replaced by this record
