# Inverted-index bucket-store memory spike — results (#760)

**Question.** The radix-trie spike proved the *key structure* is the wrong layer to optimize (the B+ tree is
structurally cheap; the memory is in the per-value *objects*). This spike asks the follow-up: how much heap do
three independent optimizations of the **bucket store** (the `value → records` map the `InvertedIndex` keeps as
a `TransactionalObjectBPlusTree` of `ValueToRecord`) reclaim, measured on the **real** evitaDB demo `Product`
data?

- **① primitive key tree** — store temporal values as an epoch key in the existing `TransactionalLongBPlusTree`
  instead of a boxed `Instant` in an object tree.
- **② front-coded value column** — store sorted distinct string values prefix-compressed in a contiguous
  `byte[]` block (the trie's prefix-sharing idea, but pointer-free and cache-local).
- **③ `ValueToRecord` decomposition** — replace the array-of-`ValueToRecord`-objects with structure-of-arrays:
  a primitive `int[]` record column (the lone record id for the single-record long tail) + a compact bitmap
  overflow for the few multi-record buckets, with the value held *once* (as the key column) instead of
  duplicated into a per-bucket wrapper.

**Verdict: GO — large, real savings, opposite of the trie.** The baseline is built from the *real* engine
classes (`ValueToRecordPrimitive` / `ValueToRecordBitmap` / `TransactionalBitmap`) and the candidate stores hold
the *same* bitmap objects for multi-record buckets, so the comparison isolates bucket *structure*. Front-coding
is verified order-preserving (every key round-trips) before its footprint is trusted.

## Method

- Spike: `evita_test/evita_performance_tests/.../spike/radixtrie/BucketStoreMemorySpike.java`.
- Real buckets reconstructed from the GraphQL download (`*.buckets.tsv`: `value TAB csvPks`). Single/multi split:
  `code` 4 161 buckets **100 % single**; `url_en` 4 161 **100 % single**; `published` 4 161 **100 % single**;
  `changed` 781 buckets, **75 % multi** (avg 5.3, max 20 records/bucket).
- Footprint = JOL deep retained size, delta vs the empty structure of the same type. B+ tree geometry mirrors
  `InvertedIndex` (256/127/127/63) and uses the same `ValueToRecord.class::cast` transactional-layer wrapper.

## Results (B/bucket, ratio vs PROD)

| attribute | PROD | ③ SoA | ②+③ (string) / ①+③ (temporal) | ①+③+FOR |
|---|--:|--:|--:|--:|
| `code` (string, 100 % single) | 113.4 | 73.0 (**0.64×**) | **10.2 (0.09× — 11×)** | — |
| `url_en` (string, 100 % single) | 117.3 | 77.0 (**0.66×**) | **10.3 (0.09× — 11×)** | — |
| `published` (temporal, 100 % single) | 72.4 | — | 12.0 (**0.17× — 6×**) | **8.0 (0.11× — 9×)** |
| `changed` (temporal, 75 % multi) | 199.2 | — | 156.0 (**0.78×**) | 152.0 (**0.76×**) |

`① long-tree alone` (temporal, ValueToRecord payload unchanged): `published` **1.11× (worse)**, `changed`
**1.04× (worse)** — see below.

The B/bucket numbers match first-principles to the byte: `①+③ published` = 12.0 = one `long` (8) + one `int`
(4); `①+③+FOR` = 8.0 = two `int`s; `②+③` strings ≈ 10 = ~6–7 B front-coded value + 4 B record. That exactness
is strong evidence the measurement is faithful.

## Across all value-tree datatypes (real demo data)

Measured the same way across the datatype spread (the schema is dominated by ~40 `BigDecimal` + 14 `String`
attributes, plus `Int`, `Boolean`, `OffsetDateTime`). Best representation per attribute:

| attribute | type | buckets | single% | PROD B/bkt | best rep | best B/bkt | ratio |
|---|---|--:|--:|--:|---|--:|--:|
| `code` | String | 4 161 | 100 % | 113.4 | ②+③ | 10.2 | **0.09×** |
| `url_en` | String (loc) | 4 161 | 100 % | 117.3 | ②+③ | 10.3 | **0.09×** |
| `ean` | String | 4 157 | 100 % | 96.4 | ②+③ | 10.6 | **0.11×** |
| `stockItemPrimaryKey` | Int | 4 161 | 100 % | 64.4 | ①+③ | 8.0 | **0.12×** |
| `published` | OffsetDateTime | 4 161 | 100 % | 72.4 | ①+③+FOR | 8.0 | **0.11×** |
| `name` | String (loc) | 1 022 | 41 % | 208.7 | ②+③ | 125.5 | 0.60× |
| `changed` | OffsetDateTime | 781 | 25 % | 199.2 | ①+③+FOR | 152.0 | 0.76× |
| `weight` | BigDecimal | 196 | 25 % | 213.1 | ①+③ | 175.3 | 0.82× |
| `displaySize` | BigDecimal | 61 | 0 % | 350.4 | ①+③ | 331.4 | 0.95× |
| `status` | String | 2 | 0 % | 4 384 | ②+③ | 4 340 | 0.99× |

**The gain tracks cardinality, not datatype.** High-cardinality, mostly-single-record attributes (codes, EANs,
URLs, primary keys, fine-grained timestamps) win **8–11×** — and they are exactly the attributes that hold the
most buckets, i.e. the memory hogs. Mixed-cardinality attributes (names, coarse timestamps, weights) win 1.3–2.5×.
Low-cardinality, multi-record attributes (booleans, enums, `status`, coarse measurements) are **bitmap-bound →
~neutral**, and ③-*alone* can be marginally **worse** (`displaySize` ③-alone = 1.05×) because an all-multi int
record column is pure overhead; the value column recovers it to 0.95×. These attributes are tiny in absolute
terms (`status` 8.8 KB, `displaySize` 21 KB) so the regression is immaterial.

**Aggregate over the 10 measured attributes:** PROD ≈ 2.37 MB → best ≈ 0.51 MB, **~4.7× (79 % reduction)** of the
value-store heap — dominated, correctly, by the high-cardinality attributes. (Excludes the unchanged multi-record
bitmap payload and the `getRecordSetId` identity column discussed in the plan; with a stored id column rather than
a derived one the single-record wins roughly halve, so the realistic catalog range is **~2.5–4.7×**.)

**Datatype applicability:**
- **③ decomposition — every datatype** (value-type-agnostic; the universal lever).
- **② front-coding — `String`** (and any variable-length byte value with shared prefixes).
- **① primitive column — `Int`/`Long`/`Short`/`Byte`/`Boolean`/`Character`/temporal** (epoch long), **`BigDecimal`**
  (unscaled `long` + `byte` scale), `UUID` (two longs), `Currency`/`Locale`/enum (dictionary id). Anything without
  a primitive form falls back to a boxed `Object` value column — still gets ③.

## Findings

1. **③ (ValueToRecord decomposition) is the keystone.** Alone it cuts single-record string buckets ~34–36 %
   (the `ValueToRecordPrimitive` wrapper, ~40 B, plus the duplicated value reference, vanish into a 4 B `int`
   column). More importantly it is the *prerequisite* that lets ① and ② pay off.

2. **① (primitive key tree) alone is a regression (+4–11 %).** Counter-intuitive but confirmed: today the
   normalized value is stored *twice* — once as the tree key and once inside the `ValueToRecord`. Swapping the
   key to a `long` does not remove the boxed `Instant` that still lives inside the bucket, and it adds the long
   tree's own structure, so it loses until the bucket is *also* decomposed (③), at which point the `Instant`
   object disappears entirely and the value lives only in the `long[]` column.

3. **②+③ for strings ≈ 11× (91 % reduction)** — 113 → 10 B/bucket. This captures the same prefix sharing the
   radix trie targeted (87–89 % on this data) but pointer-free and cache-local, so reads stay fast.

4. **①+③(+FOR) for temporal ≈ 6–9× (83–89 %)** on single-record attributes (72 → 8 B/bucket). FOR-delta in
   milliseconds is lossless for this data (verified distinct + monotone) and packs each value into a 4 B int.

5. **Wins scale with the single-record fraction.** Real e-commerce filterable/unique attributes (`url`, `code`,
   `published`) are ~100 % single-record → maximal benefit. The multi-record `changed` still saves ~24 % (the
   value/wrapper shrink), but its footprint is now **dominated by the record bitmaps**: a `RoaringBitmap` for a
   2–20-element set is hugely over-provisioned. That points at a **fourth lever** — store small multi-record
   sets as a packed/varint `int[]` inline instead of a `RoaringBitmap` — which is the "payload side" lever and
   is orthogonal to ①/②/③.

## Recommended sequencing (cheap → architectural)

- **③ first** — the keystone and the biggest single, broadly-applicable win. Replace the generic
  `TransactionalObjectBPlusTree<value, ValueToRecord>` with a specialized columnar bucket store (value key
  column + `int[]` single-record column + compact bitmap overflow). Main friction to design carefully:
  `getRecordSetId()` cache identity (today carried by the per-bucket object — needs a derived/parallel id so
  the formula cache invalidates exactly as before), the transactional copy-on-write/commit of the columns, the
  primitive→bitmap promotion as a column mutation, and Kryo serialization.
- **②** (front-coded value column) for string attributes and **①+FOR** (long/delta value column) for temporal
  attributes — both are value-column representations *inside* that decomposed store, additive once ③ exists.
- **Fourth lever** (small-set inline records vs RoaringBitmap) for multi-record-heavy attributes like `changed`.

## Performance note (same as the trie write-up, restated)

Reads stay O(log N) to the block then a primitive binary search (faster than chasing `ValueToRecord`/`String`
pointers) or a short front-coded scan from a restart point; range scans improve (contiguous). The cost is on the
**mutation/commit** path — columnar leaves re-encode on insert and structure-share less under path-copying MVCC,
bounded to one block per touched key. The decomposition (③) has essentially no read downside; front-coding (②)
trades cheap reads for pricier commits, best for read-mostly high-cardinality string attributes.
