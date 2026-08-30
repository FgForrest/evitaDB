# PriceRecordBackingBenchmark — element tree vs flat array for `PriceSuper.priceRecords`

Gate for #760 Part B item #1 (granular paged persistence for `PriceListAndCurrencyPriceSuperIndex.priceRecords`). The
backing changed from a contiguous, naturally-sorted `TransactionalObjArray<PriceRecordContract>` to the element-keyed
`TransactionalElementBPlusTree<PriceRecordContract>` (keyed on `internalPriceId`). The migration's **reason** is
persistence write-amplification (one changed leaf page per commit instead of one monolithic record-array rewrite); its
**risk** is the in-heap read hot path (a cache-friendly contiguous array → a pointer-chasing tree). This benchmark
measures that risk; the write-amp win is analysed separately below.

Source: `evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/PriceRecordBackingBenchmark.java`.

Run:
```
mvn -pl evita_test/evita_performance_tests -P full package -DskipTests
java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main \
  'io\.evitadb\.spike\.PriceRecordBackingBenchmark' -p distinctValues=100000
```

## Results (100k records, avg time/op — lower is better; JMH `-wi 3 -w 1 -i 4 -r 1 -f 1`)

| Op                                   | Array (previous) | Tree (new)     | Tree vs array      |
|--------------------------------------|------------------|----------------|--------------------|
| **mutate** (insert + delete, net-0)  | 33.8 µs          | **0.143 µs**   | **≈ 236× FASTER**  |
| getById (point lookup)               | 0.041 µs         | 0.062 µs       | 1.5× slower        |
| filteredLookup, 1000 scattered ids — merge-join (initial) | 16.1 µs | 152 µs | 9.4× slower |
| filteredLookup, 1000 scattered ids — **per-id (shipped)** | 16.1 µs | **51 µs**  | **3.2× slower**    |
| toArray (full materialization)       | 0.001 µs         | 184 µs         | array returns the backing ref; tree allocates O(n) |

## Reading the numbers

- **Mutation is the in-heap headline: ≈ 236× faster.** `insert`/`delete` are `O(log n)` tree descents vs the array's
  `O(n)` shift. This is the steady-state write win that complements the persistence win below — a price upsert/delete no
  longer rewrites the whole 100k-record array in memory either.
- **Point lookup (`getPriceRecord`) regresses 1.5×**, sub-microsecond either way — the inherent cost of one root-to-leaf
  descent vs a binary search over a contiguous array. (The array baseline here is *optimistic*: the previous production
  path resolved the id through `indexedPriceIds.indexOf` — a RoaringBitmap rank — not a plain `binarySearch`, so the
  real-world delta is smaller.)
- **Filtered lookup (`getPriceRecords(Bitmap)`) — the fix this benchmark drove.** The initial 6a rewrite used a single
  forward merge-join (`O(n + m)`). For the typical price filter the matched `internalPriceId`s are *scattered* (price
  value order is unrelated to internal-price-id order), so the merge-join walks **all** n records to find only m of them
  → 152 µs, a 9.4× regression. The shipped override is **selectivity-aware**: a sparse filter (`m · log n < n`) resolves
  each id by a direct `O(log n)` search (51 µs, 3.2× — driven by the irreducible cost of m pointer-chasing descents), a
  dense filter still uses the merge-join (`O(n + m)`, optimal). This restores the previous adaptive complexity
  (`O(m log n)` sparse / `O(n)` dense).
- **Full materialization (`getPriceRecords()` no-arg) costs `O(n)`** where the array returned its backing reference for
  free — but a call-site audit (every receiver of the no-arg index method) found this path has **no hot consumer**:
  - the point and filtered query paths use the iterator-based override above (`search` / `greaterOrEqualValueIterator`),
    never materializing;
  - the price-histogram and resolved-filtered-price consumers operate on formula-collected *filtered* subsets, not the
    index's full scan;
  - the array-based interface default that would call it (`PriceListAndCurrencyPriceIndex#getPriceRecords(Bitmap, …)`)
    is unreachable — both concrete indexes extend the overriding abstract base.

  The only live `toArray()` calls are cold persistence — single-leaf `createStoragePart` (≤ block size) and the
  `SINGLE`-shape fallback of `appendStorageParts` (a large super index always takes the paged branch, which
  materializes only the changed leaf). No consumer conversion is therefore warranted.

## The persistence write-amplification win (the actual goal — not a JMH op)

The re-measure gate flagged `PriceSuper.priceRecords` as the **dominant rewritten storage part** (~50–75 MiB on the
B2B 7.6M-price axis), because the previous flat array re-serialised **every** record on **every** commit. With the
element tree under the granular layout a single-price commit rewrites only:

- the **one leaf page** the mutation touched (≤ block-size = 64 records, ~0.6 KB), plus
- the **PAGED root manifest** — the ordered live leaf-page-sequence list, `O(n / blockSize)` var-ints.

Per-commit write volume therefore drops from `O(n)` (the whole record array, ~13 B/record) to
`O(n / blockSize) + O(blockSize)`:

| Records | Old: full array rewrite | New: 1 leaf + root manifest | Reduction |
|---------|-------------------------|-----------------------------|-----------|
| 100k    | ~1.3 MB                 | ~3.7 KB                     | **~350×** |
| 7.6M    | ~99 MB                  | ~0.36 MB                    | **~275×** |

The root manifest (`O(n / blockSize)`) is the residual floor and grows with the leaf count; it dominates the new
per-commit cost at scale (a delta-encoded manifest is a possible future reduction). Even so the reduction is **~300×**.
The `PriceListAndCurrencyPriceSuperIndexPagingTest.shouldRewriteOnlyChangedLeafAfterReload` test pins the qualitative
guarantee: after a boundary-stable reload, a single-record mutation re-emits only the changed leaf, never the whole
index.

## Verdict

PASS. The migration trades a bounded read regression (point lookup 1.5×, filtered lookup 3.2× sparse) for a `~236×`
in-heap mutation speed-up and a `~300×` per-commit persistence write-amplification reduction — the orders-of-magnitude
wins the granular page layout exists for. The one merge-join regression this benchmark surfaced was fixed
(selectivity-aware override) before shipping; a call-site audit confirmed the no-arg `O(n)` materialization has no hot
consumer (cold persistence only), so no further consumer conversion was needed.
