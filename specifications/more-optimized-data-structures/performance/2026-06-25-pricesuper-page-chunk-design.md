# #760 Part B — PriceSuper.priceRecords page-chunk: DESIGN / DE-RISK

Gate item **#1** (live B2B churn wall). Goal: a price edit rewrites **one ~32-40 KB page**, not the whole
~50 MiB dominant `priceRecords[]` array. Design mirrors the committed §3 page infrastructure (commit
`e5f57f7a0`); this doc records the structure, the one genuine difference from §3, and the de-risk verdict.

## What `priceRecords` actually is (measured facts, not assumptions)

- Field: `AbstractPriceListAndCurrencyPriceIndex.priceRecords` = `TransactionalObjArray<PriceRecordContract>`,
  natural-order comparator → sorted ascending by **`internalPriceId`** (identity = `internalPriceId`;
  `PRICE_RECORD_COMPARATOR` keys on it alone).
- `internalPriceId` is a **per-collection monotonic sequence** (`EntityCollection` price sequence,
  `SequenceType` internal-price-id). Assigned once per newly-encountered price, stable thereafter.
- **Mutation profile** therefore is:
  - **New price** → largest-so-far `internalPriceId` → **appends at the array tail.**
  - **Price value change** → same `internalPriceId` → `remove`+`add` at the **same rank** → in-place 1-slot rewrite.
  - **Price removal** → drop one element; in-memory array shifts left (positions after it), but the *set of ids*
    in every other id-range is unchanged.
- **Hot read path is POSITIONAL and coupled** (`AbstractPriceListAndCurrencyPriceIndex.getPriceRecord`):
  ```
  position = indexedPriceIds.indexOf(internalPriceId);   // rank in the sorted id bitmap
  return priceRecords[position];                          // i-th record == i-th smallest internalPriceId
  ```
  `indexedPriceIds` (RoaringBitmap) and `priceRecords` (sorted array) are maintained in lockstep, both ordered
  by `internalPriceId`. **Invariant: `priceRecords[i]` is the record of the i-th smallest `internalPriceId`.**
- `internalPriceId` is a **per-collection** sequence shared across ALL `(priceList, currency)` super indexes →
  a single super index holds a **sparse subset** of the global id space.

## Consequence for the chunk key (the one real decision)

Three candidate page keys; the sparsity + removal facts decide it:

| Key | Append | Value-change | Removal | Verdict |
|---|---|---|---|---|
| **Fixed id-range** `page = id / S` | ok | 1 page | 1 page | **NO** — ids sparse per super index → page-count blow-up, tiny pages |
| **Position-range** `page = pos / S` | tail page only | 1 page | **shifts → rewrites ALL downstream pages** | **NO** — front/mid removal defeats the purpose |
| **Explicit id-range run** (page owns a contiguous `[minId..maxId]` run, boundaries persisted) | tail page (split at 2S) | 1 page | **1 page** (other runs' id-sets unchanged → byte-identical → skipped) | **YES** |

**Decision: explicit id-range runs — exactly §3's leaf model with `internalPriceId` as the leaf key.** Paging is
**on-disk only**; the live super index keeps the whole `TransactionalObjArray` and the positional read path is
**untouched** (identical to §3, where the in-memory tree is whole and only persistence is paged).

Why removal costs one page under id-range keying: a removed id changes only the content of the run that owned it.
Every other run holds the *same id set* as last commit (only their array *positions* shifted, which is not
persisted) → those leaf pages serialize byte-identical → the dirty check skips them.

## Structure (parallels §3 verbatim)

- **`PriceSuperLeafPagePart`** (new storage-part, **byte 38**) — carries a contiguous `PriceRecordContract[]` run
  (one id-range) + write-path identity / read-path resolved `streamId`+PK, PK = `join(streamId, pageSequence)`.
  Direct analogue of `FilterIndexLeafPagePart` (byte 35).
- **`PriceLeafStreamKey`** (new, `KeyCompressor`-registered) — folds the super-index identity
  `(entityIndexPrimaryKey, PriceIndexKey)` into one `int` `streamId`. Analogue of `LeafStreamKey`. One stream per
  super index (no second `RANGE` stream — the validity `RangeIndex` is tiny, stays in the root).
- **Root part** = the existing **`PriceListAndCurrencySuperIndexStoragePart` (byte 26)** in `PAGED` shape: keeps
  `priceIndexKey` + `validityIndex`, drops the inline `priceRecords[]`, gains the persisted per-stream
  high-water + live-page-sequence list (assembled-from-leaves, §3 **Option A** — no separators stored).
- **`PriceSuperLeafPageRemoval`** (new `DeferredRemovalStoragePart`) — frees a page whose run emptied. Analogue of
  `FilterIndexLeafPageRemoval`.
- **`PageStreamRegistry`** — **reused as-is** (owner-resident on the super index; carried by reference through
  `createCopyWithMergedTransactionalMemory`).
- **SINGLE ↔ PAGED threshold** — small price lists stay monolithic inline (current byte-26 with `priceRecords`),
  only lists past the threshold (≈ the §3 threshold, ~1-2 leaf pages' worth) flip to PAGED. Avoids per-page
  overhead swamping the 99% of `(priceList,currency)` combos that are small (decodoma: 120 super parts, p50 ~tiny).

## The ONE genuine difference from §3 → the de-risk focus

§3's dirty signal is the B+ tree leaf's own `BPlusLeafTreeNode.isDirty()` — an exact per-leaf flag. **Prices have
no tree**, so the dirty-page signal must be **derived** from the commit diff. Source available:
`ObjArrayChanges` already tracks `insertions[]` / `removals[]` (delegate positions + inserted/removed values) and
is reachable via the same `TransactionalLayerMaintainer` path `createCopyWithMergedTransactionalMemory` already
uses for `priceRecords`. Mapping each changed `internalPriceId` → owning run via binary search over the persisted
`minId` boundaries is `O(Δ·log P)`. Appends (id > last boundary) → tail run; split tail when it exceeds 2S.

De-risk verdict: **tractable and the only novel surface.** It is also the thing to TDD hard — a missed dirty page
= silent stale persistence. Mitigation mirrors §3's stance (exact signal, never a content hash): drive page
dirtiness off the authoritative `ObjArrayChanges` diff, and add a round-trip test that mutates each position class
(front / mid / tail / append-causing-split / removal-causing-empty-page) and asserts (a) exactly the expected
pages were rewritten and (b) the reloaded flat array is identical to the in-memory one.

## Scope / ordering

- **In scope (item #1):** super-index `priceRecords[]` only (byte 26 → byte 26 PAGED + byte 38 leaves + removals).
- **Secondary, same machinery, DEFER within #1:** `PriceListAndCurrencyRefIndexStoragePart.priceIds` (byte 27,
  `int[]`, 6.6 B/id) — same monotonic-id run structure but per-entity-ref bounded; revisit after the super path lands.
- **Migration:** none forced. Intra-2026.2-dev: existing byte-26 monolithic parts load as `SINGLE`; they flip to
  `PAGED` on the next write that crosses the threshold (exactly as §3 monolithic Filter/Sort parts page on next
  write — no migration path wrote pages). No `@Serial` bump, no bwc reader (released-minor rule).

## ALTERNATIVE evaluated (Johnny): back `priceRecords` with an existing paged B+ tree

The flat-array run model above hand-rolls the dirty-page signal. The B+ tree family **already** carries the §3
leaf-page machinery (`leafPageHandles()`, per-leaf `isDirty()`, single-leaf-inline predicate, advance-only
`pageSequence`), so backing `priceRecords` with a tree gets that signal **for free** and reuses tested
split/merge/reclaim. Findings from the code:

- **`TransactionalBucketBPlusTree`** — columnar-leaf, **bucket-store-specific** (ValueColumn / value→record-ids).
  Wrong abstraction for an object payload. Reject.
- **`TransactionalObjectBPlusTree<K,V>`** — **no** `leafPageHandles()` / leaf-page persistence. Would need a port.
- **`TransactionalLongBPlusTree<V>`** — **HAS** the full §3 leaf-page API (`leafPageHandles()`,
  `LeafPageHandle<V>`, `isDirty()`). `RangeIndex` already backs onto it and persists its leaves as
  **`RangeIndexLeafPagePart` (byte 36)** — a near-verbatim **template** for a price leaf part. So
  `TransactionalLongBPlusTree<PriceRecordContract>` keyed by `internalPriceId` is the real reuse candidate.

**What changes if we adopt the tree** (it's an IN-MEMORY swap, not persistence-only):
- `getPriceRecord(internalPriceId)` → `tree.get(internalPriceId)` — simpler; drops the `indexedPriceIds`
  positional-rank coupling.
- **HOT-PATH REWRITE:** `PriceListAndCurrencyPriceIndex` (`getPriceRecordsByPriceIds`, line ~102) currently
  merge-walks the filtered `priceIds` bitmap against the **whole** `priceRecords[]` using **O(1) positional
  access** (`priceRecords[priceIndex]`, lockstep with `indexedPriceIds` rank). With a tree this must become an
  ordered tree-iterator zipped with the bitmap (both sorted by `internalPriceId`) — a clean merge, but a rewrite
  of a hot price-filter loop. (`ResolvedFilteredPriceRecords.getPriceRecords()` and friends are query-local
  result arrays — unaffected.)
- **LIVE-HEAP COST:** a `long` key per record duplicates `internalPriceId` (already a field of every
  `PriceRecordContract`) → **+8 B/record + ~10-15% node overhead ≈ +60-90 MiB at 7.6M prices** on a ~290 MiB
  record base. Pure key duplication — at odds with #760's memory goal.

**Three viable paths (decision for Johnny):**
1. **`TransactionalLongBPlusTree<PriceRecordContract>`** — max reuse, free exact dirty signal, byte-36 template;
   costs the +60-90 MiB duplicate-key heap and the hot merge-loop rewrite. Fastest to a correct result.
2. **Flat-array run model** (this doc's main body) — zero in-memory change, no added heap, no query rewrite;
   costs a bespoke (but bounded, TDD-able) dirty-page derivation from `ObjArrayChanges`.
3. **Element-keyed object tree with ported leaf pages** — port the §3 leaf-page machinery onto an object tree
   whose leaves hold `PriceRecordContract[]` sorted by natural order (key derived from the record, **no
   duplicate key**). Best end state (free dirty signal **and** zero key overhead); most upfront work (the port),
   plus the same hot merge-loop rewrite as #1.

Recommendation: **#3 if we're optimizing for the right end state** (it's the only one that gets the free
correctness of a tree *without* paying #760-hostile duplicate-key memory), **#1 if time-to-correct dominates**,
**#2 if minimal blast radius / zero added heap is paramount.**

## SPIKE RESULTS (measured 2026-06-25 — `PriceRecordStructureSpike`, uncommitted)

Built all three backings over identical `PriceRecord` instances; measured memory via JOL `GraphLayout` (structural
overhead = total graph − records-only footprint) and per-op latency (warmed, p50/p90/p99). Both trees built by the
**same one-by-one insert** so leaf fill factors match (~50% after sequential splits); flat array built directly. A
cross-store correctness gate (identical `toArray` / `getById` / `filteredLookup`) passes before any numbers.
Per-record overhead is **identical at 1M and 7.6M → perfectly linear.** Single `PriceRecord` = 32 B.

**Memory (structural overhead):**

| backing | B/record | overhead @7.6M | vs flat |
|---|---|---|---|
| flat-array (opt A) | **4.00** | 29.0 MiB | — |
| long-bplustree (opt 2) | **24.41** | 176.9 MiB | **+148 MiB** |
| element-tree (opt 3) | **8.22** | 59.6 MiB | +31 MiB |

**Latency @7.6M (ns/op, p50):**

| op | flat | long-tree | element-tree |
|---|---|---|---|
| getById | 755 | 568 | **506** |
| scan (ns/elem) | **1** | 7 | 6 |
| filtered@1% (ns/id) | **101** | 199 | 172 |
| filtered@10% (ns/id) | **15** | 26 | 23 |
| append | **7,386,187** | 161 | **60** |
| remove | **7,056,015** | 642 | **231** |

**Reading the numbers:**
- **Flat array is ruled out for a churning structure.** Cheapest memory (4 B/record) and marginally fastest reads,
  but append/remove are **O(n) → ~7 ms each at 7.6M** (the in-memory face of the write-amplification wall; note the
  production `TransactionalObjArray` batches this to one O(n) materialization per *commit*, but the persisted array
  is still fully rewritten — the gate's core problem).
- **The element-tree strictly dominates the long-tree** — better on memory (8.22 vs 24.41 B/record) AND on *every*
  latency op. The long-tree's extra 16.2 B/record is exactly the duplicate `long` key (8 B) doubled by the ~50%
  fill factor — dead weight, since `internalPriceId` already lives inside every record.
- **Element-tree vs flat:** trades +4.2 B/record (+31 MiB @7.6M for the tree spine) and slightly slower reads for
  **~120,000× faster mutation** (60 ns vs 7.4 ms append) — and gets the free per-leaf dirty signal for §3 paging.

**VERDICT: option 3 (element-keyed B+ tree) is the data-backed choice.** It is not a memory-vs-speed trade against
option 2 — it wins both. The only thing option 2 buys is time-to-ship (it reuses `TransactionalLongBPlusTree` +
the byte-36 template verbatim); option 3 costs the port (add leaf-page persistence + transactional-layer support to
an element-keyed object tree). Given #760's mandate is optimized data structures + memory, the ~117 MiB saving over
option 2 at B2B scale and the strict latency dominance justify the port. The spike's bare prototype proves the
structure works, the memory claim holds, and the hot-path merge-walk/lookup are sound.

## Build steps (await Johnny's go before coding)

1. `PriceLeafStreamKey` + register byte 38 `PriceSuperLeafPagePart` and its removal in `IndexStoragePartRegistry`
   / `IndexStoragePartConfigurer`; serializers (clone the Filter leaf-page serializer, swap element codec to
   `PriceRecordContract`).
2. Super-index: owner-resident `PageStreamRegistry`; PAGED emit/flush mapping `ObjArrayChanges` → dirty id-range
   pages (+ tail split / empty-page reclaim); root part SINGLE↔PAGED.
3. Persistence service: PAGED write (root + dirty leaves + removals) and PAGED load (root + leaves →
   `assembleFromLeaves` → existing super-index ctor).
4. Tests: round-trip + dirty-page-set assertions for every position class; SINGLE↔PAGED transition both ways;
   coverage ≥70%.
