# #760 Part B — RE-MEASURE GATE (gate catalog, post-Step-4)

Probe: `GateCatalogSizeProbe` (pure on-disk size distribution; no part deserialized — only `RecordKey`
type byte + `FileLocation.recordLength`). Catalog opened with inline v4→v6 upgrade on a temp copy, so
numbers reflect the **POST-Step-4 v6 on-disk state** (manifest eviction + Filter/Sort NFD rekey applied).
Raw: `2026-06-25-gate-catalog-size-distribution.txt`.

## Scale (extrapolation base)
- **Product = 18,762 entities.** Total 106,830 across 17 collections (Media 47,388, PickupPoint 32,911
  next-largest). The gate catalog is "not the largest dataset" (Johnny); the price-dense B2B catalog was excluded
  (older SNAPSHOT histogram, unloadable).
- Linear factors used below: **1M products = ×53.3**, **10M = ×533**. Linear scaling holds for the
  map/cardinality/bitmap structures here (all are value→entity-set, size ∝ #entities carrying the value).

## Headline result: Step 4 + §3 validated, two structures fail the scale gate

| Structure (byte) | parts | total | p99 | max @18.7K | >64K | →1M (×53) | →10M (×533) | Verdict |
|---|---|---|---|---|---|---|---|---|
| **GlobalUnique (31)** | 4 | 0.71M | 249K | **249K** | **4/4** | ~13 MB | **~133 MB** | **Step 6 — CRITICAL** |
| **RefTypeCardinality (32)** | 28 | 0.79M | 270K | **270K** | 4 | ~14 MB | **~144 MB** | **Step 5/6 — CRITICAL** |
| UniqueIndex (21) | 4,970 | 5.57M | 14K | 249K | 8 | (per-coll) | shard | Step 6 (same lever) |
| SortIndex (23) | 70,116 | 10.55M | 1.6K | 367K (Media) | 9 | Product 4.7MB | Product **47 MB** | Phase-3 order-stat — 10M |
| FilterIndex (22) | 77,438 | 17.30M | 2.2K | 425K (Media) | 18 | — | — | **§3 DONE** (pages at runtime) |
| EntityIds (37) | 73,632 | 3.96M | 207 | 16.8K | 0 | ~0.4 MB | ~4 MB | Roaring-chunk — defer (10M only) |
| PriceSuper (26) | 120 | 0.09M | 27K | 47.9K | 0 | 2.5 MB | 25 MB | flat-chunk — defer (shallow here) |
| **EntityIndex manifest (20)** | 74,149 | 6.01M | 209 | **436** | **0** | — | — | **Step 4 DONE — flat, no tail** |

## Findings

1. **Step 4 (committed) validated at scale.** Manifest (byte 20): 74,149 parts, p99=209B, **max=436B, zero
   fat tail**. Eviction is uniform — the membership bitmaps are gone from the manifest. The evicted
   siblings (byte 37) are tiny (max 16.8KB, the Media global-index bitmap; Product 14KB) because
   RoaringBitmap over dense contiguous PKs is ~0.4 B/entity.

2. **§3 (committed) was the right call.** Every part >256KB in the catalog is a FilterIndex or SortIndex in
   a large collection (Media 425K/367K, PickupPoint 371K/311K, Product Filter 197K). The migration rewrote
   them monolithic (no byte-35/36 pages appeared — paging is a runtime-commit path, not a migration path),
   but the §3 page-tree pages exactly these on the next real write. Filter is fully covered.

3. **GlobalUnique (byte 31) is the #1 unsolved churn wall.** 4 catalog-wide parts, each 167–249KB at only
   18.7K products, ALL >64K. One entry per unique value across the catalog ⇒ ~13 B/entry ⇒ **~133 MB single
   part at 10M**, **fully rewritten on every single-entity unique-attribute insert/update**. This is the
   worst-case part in the entire system at scale and the clearest Step-6 (CHAMP Tier-1 shard) justification —
   sharding by key range turns a 133 MB rewrite into one ~8–32 KB shard.

4. **ReferenceTypeCardinality (byte 32) is the #2 churn wall.** Product 16 parts, max 270K (1 >256K) at
   18.7K products ⇒ **~144 MB at 10M**. Scales with references-per-product. Step 5 (References-per-name) or a
   Step-6 shard.

5. **SortIndex large parts (byte 23) remain monolithic** — §3 covered only Filter+Range. Product max 89K ⇒
   ~47 MB at 10M. Justifies the Phase-3 order-statistic page plugin at the 10M end; marginal at 1M. The §3
   page infrastructure already exists, so this is incremental.

6. **PriceSuper.priceRecords (byte 26) — CORRECTED to a top lever for B2B (Johnny).** The gate catalog is
   price-shallow (~1 price/product, Prices byte-5 = 18.5K parts / 1 MiB), so its raw PriceSuper sizes
   (max 48K) badly under-represent a B2B catalog. Real B2B density (measured on the price-dense B2B
   catalog): **7.6M prices / 130k products ≈ 58 prices/product**. Measured on the gate catalog:
   PriceSuper.priceRecords = **10.4 B/record**, and price
   records concentrate hard — the single dominant (priceList,currency) part holds **5,949 of 8,695 records
   (68%)**. Extrapolated: **7.6M prices → ~75 MiB total flat array; ~50 MiB in the dominant price-list part
   alone, rewritten on EVERY price edit in that list.** (1M→9.9 MiB, 20M→198 MiB.) This is a **live churn
   wall at current B2B product counts**, not a future-scale one. → flat-ordered-array chunk JUSTIFIED, high
   priority for price-heavy/B2B. PriceRef.priceIds = 6.6 B/id (per-entity-reference, bounded — secondary).

7. **Defer: EntityIds Roaring-chunk** (bitmap already compact, ~4 MB at 10M products — only matters under
   heavy insert churn).

## Two scaling axes — pick order by customer profile
- **Price axis (B2B density, live TODAY at ~130k products):** PriceSuper.priceRecords ~50–75 MiB dominant-list
  part, rewritten per price edit. This is the B2B reality now.
- **Product axis (catalog grows to 1–10M products):** GlobalUnique (~133 MB) + RefTypeCardinality (~144 MB)
  at 10M; both also full-rewrite-per-edit.

## Recommended order (gated work, post-gate)
1. **PriceSuper.priceRecords flat-ordered-array chunk** (Phase-3) — live B2B churn wall (~50–75 MiB @7.6M
   prices, concentrated). Optionally PriceRef.priceIds.
2. **Step 6 — CHAMP Tier-1 shard** (GlobalUnique + per-collection UniqueIndex) — product-axis wall + live
   catalog-wide unique-edit churn.
3. **Step 5 — References-per-name** (RefTypeCardinality + reference parts).
4. **Phase-3 SortIndex order-statistic page plugin** (reuses §3 infra; 10M-scale).
5. Defer EntityIds Roaring-chunk (revisit under insert-churn profiling).
