# SortIndex @ 10M+ — storage-churn ideation brief

**Status:** ideation / research brief (NOT a plan, NOT approved). Shared context for multiple research
agents. Goal: eliminate the full-content rewrite of `SortIndexStoragePart` (registry byte 23) on small
deltas, at 10M+ records, **without losing the "presorted linear scan beats sorting" advantage** that the
current shape exists to provide. Replacing the SortIndex shape is on the table if a JMH spike proves no
read-path regression.

> Grounding: every file:line below was read from the working tree on 2026-06-29 (branch
> `760-more-optimized-data-structures-...`). `[VERIFY]` marks a claim an agent should re-confirm.

---

## 1. Scope and the one-sentence problem

`SortIndex.createStoragePart` rewrites the **entire** part whenever the index is dirty
(`SortIndex.java:630-647`). One add/remove → `dirty=true` → next flush materialises the whole
`sortedRecords` array + whole value column into **one** `SortIndexStoragePart` → persistence writes it as
a single record. At 10M records that is **~47 MB rewritten for a one-record change** (decodoma gate
extrapolation: Product byte-23 part max 89 KB @18.7K products ⇒ ~4.7 MB @1M, ~47 MB @10M;
`partb-remeasure-gate-decodoma`). This is the **only** storage-side 10M problem. There is a *separate*
heap problem (Section 4) that most options here deliberately do not touch.

The fix is constrained by one structural fact (Section 5): the persisted form is **position-indexed**, so
naïve position-block paging cascade-dirties every downstream page on an early insert. The escape is that
the *live* tree is **order-key-indexed and insert-local**.

---

## 2. Current data structures — three layers

Keep these three layers separate; the churn lives only in Layer 3.

### Layer 1 — live, resident, transactional (the write side)

`SortIndex` is `abstract sealed permits OwnerSortIndex, SortIndexView` (`SortIndex.java:106-109`).

- **`sortedRecords : TransactionalUnorderedIntArray`** — the presorted record-id sequence, **value
  order**, blocked per distinct value; within a block, record ids ascending by id. It is itself **two
  trees** (`TransactionalUnorderedIntArray.java:82-86`):
  - **`positionTree : UnorderedLookupTree`** — order-statistic tree keyed by a **gapped order-key** (not
    by position). `getRecordAt(pos)`, `getArray()` (materialises the full N-int logical array),
    `insertAfter`, `removeByOrderKey`. **Order-keys are never renumbered on insert** — the steal/merge
    balancing keeps equal depth with zero order-key reassignment (`cumulative-weight-tree-and-ult-balancing`).
    A mid-sequence insert touches **one leaf**, not the downstream nodes. Internal nodes carry subtree
    counts; absolute position is derived by summing them during descent (`UnorderedLookupTree.java`
    `getRecordAt`/`findPositionByOrderKey`).
  - **`valueIndex : TransactionalIntToLongBPlusTree`** — inverse map `recordId → orderKey`
    (for `indexOf`/remove/insert-after). **This is a THIRD resident structure, ~N entries @10M; any paging
    design must account for it, not just the two "obvious" trees.**
- **`sortedValues : TransactionalObjectBPlusTree<Comparable value, Integer cardinality>`** — *owner mode
  only* (`OwnerSortIndex.java:96`). Distinct value (ordered by the index `comparator`) → cardinality (≥1).
  Leaf block size **256** (read-tuned, `OwnerSortIndex.VALUE_BLOCK_SIZE`). Source of truth for value
  ordering + cardinality in owner mode.
- **View mode** (`SortIndexView`, a both-filterable-and-sortable single attr) owns **no** value tree — it
  sources ordered values + cardinality + comparator + normaliser from the **shared `InvertedIndex`** (the
  FilterIndex), which is **already §3-granular-paged**. View mode still owns and commits its own
  `sortedRecords`.
- **`SortIndexChanges.valueLocationTree : CumulativeWeightBPlusTree`** (`SortIndexChanges.java:77`) —
  transient order-statistic tree `value → weight(=cardinality)`; `rankOf(value)` → block-start offset in
  O(log V). Rebuilt from the index on demand, **never persisted**.

### Layer 2 — query-time derived flat arrays (materialised, cached per `transactionalId`)

Built when a `SortedRecordsSupplier` is created (`SortIndexChanges.java:92-141`):
- `sortedRecordIds[]` = `sortedRecords.getArray()` — N ints, value order (the presorted sequence).
- `recordPositions[]` = `sortedRecords.getPositions()` — inverse permutation rank-in-id-order →
  position-in-value-order (`TransactionalUnorderedIntArray.java:149-162`; builds an `IntIntHashMap` + sort
  → O(N)).
- `allRecords : Bitmap` = `sortedRecords.getRecordIds()` — record ids **ascending by id**, RoaringBitmap
  (`:167-171`).

### Layer 3 — on-disk `SortIndexStoragePart` (registry byte 23, one part per (entityIndex, attributeKey))

Fields: `comparatorBase`, `sortedRecords:int[]`, `sortedRecordsValues:Serializable[]`, sparse
`cardinalityValues/cardinalities` (cardinality>1 only), `indexedDecimalPlaces`
(`SortIndexStoragePart.java`). Serializer (`SortIndexStoragePartSerializer.java`):
- **owner mode** delta-varint-encodes each per-value block of `sortedRecords` (1–2 bytes/id via
  `SortedIntArrayCodec`), writes distinct values self-describingly (`kryo.writeClassAndObject`), sparse
  cardinalities. A migration-collapsed non-ascending block falls back to raw `writeInts`
  (`blockDeltaEncoded=false`).
- **view mode** writes `sortedRecords` **raw** (4 bytes/id) and **omits** values/cardinalities (re-derived
  from the shared FilterIndex part on load). **So view mode still churns the full positional int[] today.**

| structure | holds | sorted by | where | size @10M (one Product attr) |
|---|---|---|---|---|
| `positionTree` | record ids, logical order | order-key (≈ value order) | heap, txnal | tree over ~10M ints |
| `valueIndex` | recordId→orderKey | record id | heap, txnal | ~10M entries |
| `sortedValues` (owner) | value→cardinality | comparator(value) | heap, txnal | V entries |
| `sortedRecordIds[]` | record ids | value order | heap, per-query | ~40 MB |
| `recordPositions[]` | id-rank→value-pos | record id | heap, per-query | ~40 MB |
| `allRecords` | record ids | record id | heap, per-query | few MB (Roaring) |
| **`SortIndexStoragePart`** | the above, packed | value order | **disk, byte 23** | **~47 MB delta-encoded** |

---

## 3. How ORDER BY executes — the presort principle (why the shape exists)

ORDER BY **never sorts at query time** (`MergedSortedRecordsSupplierSorter`):

1. **`getMask`** (`:198-252`) — merge-join the filter result (RoaringBitmap, ascending by id) against
   `allRecords` (ascending by id) via batch iterators. Each match looks up `recordPositions[...]` → its
   **position in value order**, added to a `mask` bitmap; non-matches go to a `notFound` set. O(F + N),
   integer-only, on dense ascending arrays — **no value comparison, no collation**.
2. **`fetchSlice`** (`:130-191`) — walk `mask` positions ascending (= value order), emit
   `sortedRecordIds[position]`, honouring skip/limit + dedup across multiple providers.

Sorting F filtered rows therefore costs **O(F + N) cache-friendly int scans** vs O(F·log F) value
comparisons (each a cache-miss + possible collator call). This wins for medium/large result sets. It has
an **O(N) floor** that loses on *very selective* sorts — see `sortindex-filterindex-shared-tree-plan`
(§9.11.I merge-join measurements: 1% selectivity, mask-sorter 188µs vs merge-join 31,826µs). That settled
finding is **why `sortedRecords` was KEPT** and not replaced by a value→records merge-join.

**Read-path contract any redesign must keep cheap:** (a) record-id→sorted-position (the merge-join +
`recordPositions`), (b) sorted-position→record-id (`sortedRecordIds`), (c) the ascending record-id set
(`allRecords`) for the merge-join, (d) the value-at-position seeker for predecessor/ORDER BY without a
prefilter (`SortedComparableForwardSeeker`, `SortIndex.java:1183+`).

---

## 4. Two distinct 10M problems (don't conflate)

- **Storage churn (the target):** full-part rewrite on small deltas (Section 1). Lives in Layer 3.
- **Heap blow-up (secondary, out of scope for most options):** Layer 2 materialises ~3×N int arrays
  (~120 MB transient per sorted-attr query @10M). Only the columnar/DocValues family (Option II-B) attacks
  both at once; every "keep the presort, page Layer 3" option leaves the heap problem untouched. Agents
  should state explicitly which problem each option solves.

---

## 5. The structural blocker, stated precisely

The persisted `sortedRecords` is a **flat positional concatenation**. Position-block paging (records
0–9999 = page 0, …) cascade-dirties every page after an early insert, because the insert shifts all
downstream positions. **Escape:** page on **stable identity** — the live `positionTree`'s order-key / leaf
identity, or **value range**, never absolute position. The order-key gaps already guarantee insert
locality in memory; persistence must inherit that property.

---

## 6. Existing infra to REUSE (do not reinvent)

This branch already shipped the granular-paging machinery for four other indexes (Steps 4/§3/5). Map any
"page identity / atomic root / per-leaf parts" proposal onto **this existing infra** rather than building
anew:

- **`PageStreamRegistry`** (`io.evitadb.index.page`) — owner-resident page-sequence bookkeeping carried
  by-ref through STM commit. `allocate`, `highWater`, `restore` / `restoredFrom(streamId, highWater,
  List<PagedLeafHandle>)` (read-path), `collectChangedPages(streamId, handles, builder)` (write-path),
  `stage`/`publishStaged`/`discardStaged`, `livePageSequences`/`freedPageSequences`. This **already is** a
  stable-logical-page-id indirection with an ordered live-page directory.
- **`PagedLeafHandle`** (`io.evitadb.index.bPlusTree`) — value-agnostic per-leaf SPI: `getPageSequence`,
  `isDirty`/`clearDirty`, `setPageSequence`, `UNASSIGNED_PAGE_SEQUENCE`.
- **PAGED/SINGLE shape** — small index stays inline (SINGLE), large index emits one `*LeafPagePart` per
  changed leaf + a removal per freed leaf + a PAGED root holding high-water + ordered live page-sequence
  list. Precedents: `InvertedIndex` (bucket tree), `RangeIndex` (long tree), `PriceSuperIndex` (element
  tree), Global/Owner `UniqueIndex` + `ReferenceTypeCardinalityIndex` (LongPayload bucket tree).
- **Serializer evolution** — `SerialVersionBasedSerializer` + `addBackwardCompatibleSerializer(oldUid,
  reader)`. **serialVersionUID policy:** NO bump / NO bwc reader for an intra-dev (2026.2) format change;
  bump + dedicated reader ONLY across a RELEASED minor (`serialversionuid-bump-policy`). The byte-23 SORT
  format last changed intra-dev, so a redesign here is likely **intra-dev → no bwc reader needed** `[VERIFY]`
  it has not shipped in a released minor since.

The FilterIndex (InvertedIndex) is **already §3-paged**, and `InvertedIndex.getValueIterator()` yields
buckets in value order, each `ValueToRecord` carrying the ascending record bitmap. `SortIndexView`
already reconstructs ordered values from it (`SortIndexView.java:200-214`). This makes Option I-B
**confirmed feasible**, not conditional.

---

## 7. The design space

Grouped by whether the presort is kept (lower risk, churn-only) or replaced (needs a JMH gate). Each option
notes which 10M problem it solves and the spike that would validate it.

### Group I — keep the presort, kill the rewrite

**I-A. Leaf-paged resident trees.** Persist `positionTree`, `valueIndex` *(do not forget this one)*, and
(owner) `sortedValues` as **per-leaf page records** keyed by leaf page-sequence, via `PageStreamRegistry`.
Order-key stability ⇒ one insert → one dirty leaf → one page rewrite. Internal nodes persist **subtree
counts + fence keys**, never absolute offsets; position derived by summation on descent. *Solves:* churn.
*Cost:* three never-paged structures get the SPI; owner+view modes; a high-cardinality value column is
itself big and also needs paging. *Effort:* medium-high, zero read-model change.

**I-B. View mode: stop persisting `sortedRecords`; rebuild from FilterIndex.** For both-flagged attrs
(~41% of indexed attrs, `sortindex-filterindex-shared-tree-plan`), `sortedRecords` is **100% derivable**
by concatenating FilterIndex buckets (ascending ids) in value order — and the FilterIndex is already
§3-paged, so granularity is inherited *for free*. A view-mode part shrinks to comparator/config metadata.
*Solves:* churn, for the both-flagged subset, at near-zero cost. *Cost:* O(N) load reconstruction (off the
churn path). **Highest value/risk; composes with I-A for the owner-only remainder.**

**I-C. Owner mode persisted as a paged value→postings bucket tree.** A sort index *is* an inverted index:
value → ascending record postings; the flat `sortedRecords` is just the concatenation. Persist owner mode
as `value → {cardinality, postingChunks}` (the **same bucket-tree shape FilterIndex uses**, reusing §3
paging directly), and **reconstruct the flat positional array + `valueIndex` in memory at load** (within-
value order is deterministic ascending id). Pages split by **encoded byte size**, many low-cardinality
values per leaf, **overflow posting chunks (by record-id range) for high-cardinality values**. A value
change touches only the old + new value pages. *Solves:* churn; also unifies owner persistence with the
filter shape. *Cost:* load reconstruction; the position tree is no longer persisted node-for-node (rebuilt).
This is the persistence-shape twin of the long-rejected *memory*-sharing plan — re-read that plan so we do
not re-derive its "merge-join loses on selective sorts" conclusion; **I-C keeps the materialised array, so
it does not regress reads.**

### Group II — replace the presort (JMH gate required)

**II-A. Segmented sorted runs (LSM, Lucene/RocksDB-style).** Many immutable sorted runs + background
merge; write appends a tiny run, never rewrites old ones; ORDER BY = **K-way merge of sorted streams**
(still linear, +log K, plus a cross-run merge-join with the filter). Prefer **range-partitioned** compaction
(per order-key interval) over leveled, to bound write amplification. *Solves:* churn. *JMH must prove:*
K-way-merge read latency (K bounded by merge policy) stays within the single-array-scan budget at 1M/10M
across selectivities. *Caution:* full ordered scans are a first-class op here; general LSM write-amp; you
still need the `recordId→orderKey` map persisted regardless.

**II-B. Columnar DocValues + sort-on-read (most radical, highest upside).** Drop the global presort.
Persist `recordId → valueOrd` as a dense column **paged by record-id range** (record-id ranges are
ascending and stable → pages never cascade; a new record appends to the tail page, a changed record dirties
one page) + the `ord → value` dictionary (FilterIndex already is this). ORDER BY = read ords of the
**filtered** set and **counting/radix-sort over dense small ords** → O(F + K) for top-K, over F not N. This
is Lucene `SortedNumericDocValues` / ClickHouse MergeTree. *Solves:* **both** churn and the Layer-2 heap
blow-up. *JMH must prove:* counting-sort-over-ord-column ≥ current presorted merge-join across selectivity
0.1%–100% at 1M/10M (latency + allocation). If it holds, it is the cleanest long-term shape.

### Group III — transitional / advanced (write-side already knows the ops)

**III-A. Base snapshot + edit journal (quickest to ship).** Keep the current flat base **immutable**;
append tiny `SortIndexDeltaPart`s of *logical ops* (`REMOVE(recordId,oldOrderKey)`,
`INSERT(recordId,newOrderKey)`, `VALUE_CARDINALITY_DELTA(value,±1)`) — the write side already knows these
exactly. Load = base + replay deltas in commit order. Checkpoint a fresh base on byte/op/replay-time
thresholds. *Solves:* per-commit rewrite immediately, minimal serializer change. *Limit:* postpones rather
than removes the big rewrite — the eventual checkpoint still writes ~47 MB (mitigated once the base is
range-paged: compact only affected order-key ranges).

**III-B. Order-key range-shard directory (lower-complexity I-A).** Instead of serialising every internal
ULT node, keep a small directory `[-∞,kA)→part17,count …; [kA,kB)→part44 …` of order-key ranges → parts;
each part holds records sorted by order key; split a too-big part (rewrites only that part + directory);
rebuild the tree at load. Retains the essential property (membership by order-key range, not position)
without mirroring the tree node-for-node. The directory can itself be a small B+ tree. (Note: evitaDB's
`PageStreamRegistry` ordered live-page list is already close to such a directory.)

**III-C. Per-page delta chains (Bw-tree) / Bε-tree buffered updates.** Bw-tree: prepend update deltas to a
page, consolidate when the chain is long → an update writes tens of bytes; consolidation stays local.
Bε-tree: internal nodes buffer update messages, flush in batches → write-optimised at the cost of read/maintenance
complexity. **Advanced; defer** — only after basic page-level persistence works; they add delta-visibility,
split-with-pending-deltas, recovery, GC, and worst-case-read concerns.
([Bw-tree paper](https://15721.courses.cs.cmu.edu/spring2016/papers/bwtree-icde2013.pdf))

### Illustrative target (back-of-envelope)

At ~47 MB and 128 KiB pages ⇒ ~376 leaf pages; a value-region move rewrites ~2 leaves ≈ 256 KiB — a
**~180× write reduction** per single-record change. Numbers to be confirmed by measurement.

---

## 8. Non-negotiable invariants (verified against the order-statistic model)

1. **Page identity must not depend on logical position** — only order-key, value, or stable logical
   page-id.
2. **Page boundaries use stable order keys / values / logical page IDs.**
3. **Prefix/subtree counts live in ancestors, not as absolute offsets in leaves** (else the cascade moves
   one level up).
4. **A transaction publishes a complete new root/manifest atomically** (matches the PAGED-root model).
5. **Splits and merges stay local** (one page + its root path).
6. **A large single-value posting must be splittable by record-id range** (high-cardinality safety).
7. **Full sequential materialisation reads pages in key order without random lookups** (Layer-2 build must
   stay a linear leaf walk).
8. **A global comparator/normaliser change still forces a full rebuild** — only per-record ordering
   changes can be localised. (A locale change, or a `BigDecimal` `indexedDecimalPlaces` drift, re-keys
   everything; this bounds what incremental persistence can promise.)

---

## 9. Already-settled context — read before re-deriving

- `sortindex-filterindex-shared-tree-plan` — merge-join loses badly on selective full sorts ⇒
  `sortedRecords` is KEPT; value-key **memory** dedup via owner/view split is the banked win. Don't
  re-litigate dropping the materialised array.
- `cumulative-weight-tree-and-ult-balancing` — the ULT is balanced with **zero order-key reassignment**;
  this is the property that makes order-key paging viable.
- `partb-remeasure-gate-decodoma` — SortIndex is the **smallest** of four churn walls (~47 MB @10M,
  marginal @1M); PriceSuper / GlobalUnique / RefTypeCardinality are larger/live. Scope expectations
  accordingly.
- `sortindex-bplustree-plan` — the value→cardinality consolidation + the read-perf fixes (leaf-array
  caching, block 256, software prefetch) already shipped; the ~3.6× floor vs a contiguous array is the
  realistic heap-tree sweep cost.

---

## 10. Open questions / JMH spikes for the research agents

1. **II-B gate:** counting/radix-sort over a dense `ord` column vs the current presorted merge-join, across
   selectivity 0.1%–100% at 1M/10M; measure latency + `gc.alloc.rate.norm`. (First spike — highest upside.)
2. **II-A gate:** K-way merge of K sorted runs vs single-array scan, as a function of K, for full-scan and
   top-N.
3. **I-C feasibility:** can owner-mode persistence reuse the existing bucket-tree leaf-paging unchanged
   (with overflow posting chunks for high-cardinality values), and is load-time reconstruction of
   `positionTree`+`valueIndex` from postings within budget?
4. **I-B confirmation:** measure the both-flagged fraction on a real schema and the FilterIndex-concatenation
   load cost; confirm within-value order matches byte-for-byte.
5. **III-A sizing:** delta-replay load cost vs base size; checkpoint threshold policy.
6. **Heap question:** is the Layer-2 ~120 MB transient the *real* 10M pain, making II-B's dual win decisive,
   or is on-disk churn alone the priority?

---

## 11. External cross-check (one external AI, unverified against impl)

An external review independently re-derived I-A / I-B / II-A / III-A and added useful refinements, folded
into this brief: the **third structure** (`recordId→orderKey` map) needs paging too; the explicit
**order-statistic persistence invariant** (subtree counts in ancestors, never absolute offsets);
**folding the positional sequence into the value tree as postings** for owner mode (= I-C);
**order-key range-shard directory** (= III-B); **invariant #8** (comparator change ⇒ full rebuild); and the
named advanced techniques **Bw-tree delta chains / Bε-tree** (= III-C). Caveats: it did not know evitaDB
**already has** `PageStreamRegistry`/`PagedLeafHandle` (so its page-identity/atomic-root machinery is
already solved here) nor that the **FilterIndex is already §3-paged** (which upgrades its view-mode idea
from "conditional" to confirmed). It also explicitly leaves the Layer-2 heap problem untouched, unlike II-B.
