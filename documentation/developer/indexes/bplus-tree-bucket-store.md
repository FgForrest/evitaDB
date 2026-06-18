# Transactional B+ Trees and the Columnar Bucket Store

This page documents the in-memory data-structure engine that backs the attribute search indexes:
the family of transactional B+ trees, the columnar bucket store that decomposes inverted-index
buckets into structure-of-arrays, and the supporting machinery (value columns, scaled-int decimal
keys, order-statistic ranking, persistent maps).

It is the in-memory counterpart of [offset-index.md](offset-index.md) (the on-disk persistence
engine). Where [data-structures.md](data-structures.md) describes **what** each `EntityIndex`
sub-index stores, this page describes the **shared low-level structures** those sub-indexes are
built from and **why** they are shaped the way they are.

> **Scope.** Everything here lives in `io.evitadb.index.bPlusTree` (engine),
> `io.evitadb.dataType.bPlusTree` / `io.evitadb.dataType.champ` (common), and the attribute index
> classes that consume them. None of it changes the on-disk format -- serializers adapt, the indexes
> rebuild their trees by ordered inserts at load. See [Persistence Contract](#persistence-contract).

---

## Goals

An attribute index holds one entry per distinct attribute value, so its footprint scales with
cardinality. The data-structure engine on this page is built to keep that footprint as small as
possible while keeping reads allocation-free. Four goals shape it:

- **Memory tracks cardinality, not datatype.** High-cardinality string, temporal, and decimal
  attributes are where footprint matters most, so the layout is optimized to shrink exactly those;
  low-cardinality attributes stay neutral.
- **No per-value boxing.** A leaf stores its values **column-by-column** in a packed primitive
  representation (structure of arrays), so a distinct value costs a primitive slot rather than a
  boxed wrapper object plus its overhead.
- **Each value stored once.** An attribute that is both filterable and sortable keeps a single
  shared value tree read by both its filter and sort indexes, instead of one copy per index.
- **Zero-allocation reads.** A bucket is materialized into a transient flyweight only on read; the
  leaf itself holds no wrapper objects.

These goals are achieved by backing the indexes with a family of transactional B+ trees whose leaves
store values column-by-column. The rest of this page describes that family and the columnar layout.

---

## The transactional B+ tree family

Five B+ tree specializations exist. They are **not** mergeable into one generic tree: the primitive
variants exist precisely to avoid boxing the key and/or value, which is the whole point of the
memory optimization.

| Tree | Key | Value | Why it exists | Backs (production) |
|------|-----|-------|---------------|--------------------|
| `TransactionalBucketBPlusTree<K>` | `K extends Comparable<K>` | record set (single `int` or `Bitmap`) | columnar inverted-index bucket store; pluggable `ValueColumn` for the key half | `InvertedIndex` |
| `TransactionalLongBPlusTree<V>` | `long` (primitive) | `V` | primitive long key, no key boxing | `RangeIndex` (`<TransactionalRangePoint>`) |
| `TransactionalObjectBPlusTree<K,V>` | `K extends Comparable<K>` | `V` | generic key/value with optional custom `Comparator` | `OwnerSortIndex` (value→cardinality), `TrafficRecordingIndex` |
| `TransactionalIntToLongBPlusTree` | `int` (primitive) | `long` (primitive) | zero-boxing both sides | `TransactionalUnorderedIntArray` value index (via `UnorderedLookupTree`) |
| `CumulativeWeightBPlusTree<K>` | `K` (Comparator-ordered) | `int` weight | order-statistic / rank tree; **not transactional** (ephemeral diff helper) | `SortIndexChanges.valueLocationTree` |

### Shared transactional strategy

The four *transactional* trees (`Bucket`, `Long`, `Object`, `IntToLong`) all use the **same** diff
strategy, which differs from the diff-layer pattern of `TransactionalMap`/`TransactionalBitmap`:

- Their `TransactionalLayerProducer` diff type is `Void` -- they own **no** `Changes` layer.
- Mutable state lives behind two `TransactionalReference` fields:

  ```java
  private final TransactionalReference<Integer> size;
  private final TransactionalReference<BPlusTreeNode<K, ?>> root;
  ```

- Nodes are mutable and `@NotThreadSafe`; transactional isolation is achieved by **path-copying**
  (copy-on-write): a write clones the nodes on the root-to-leaf path and publishes a new `root`
  through the `TransactionalReference`. Readers outside the transaction keep the old root.
- On commit, the tree produces a copy of itself; the `TransactionalReference` fields produce their
  committed values as part of the recursive merge.

`CumulativeWeightBPlusTree` is deliberately **non-transactional**: it is a transient helper rebuilt
inside `SortIndexChanges` and never persisted, so it holds plain `root`/`size`/`totalWeight` fields
and skips rebalancing on delete (it is discarded, not maintained).

### Leaf-array caching and block-size tuning

All transactional trees cache the materialized leaf array for cursor iteration (forward and reverse)
and accept explicit block-size constructors. The block sizes were de-guessed with JMH benchmarks
(`documentation/performance/individual/<benchmark>`):

| Tree usage | Value block size |
|------------|------------------|
| `InvertedIndex` buckets | 256 |
| `OwnerSortIndex` value→cardinality | 256 |
| `RangeIndex` points | 512 |

---

## The columnar bucket store

`TransactionalBucketBPlusTree` is the keystone. A *bucket* is one `(value, recordIds)` pair. Instead
of storing a leaf as an array of boxed bucket objects, the leaf holds **parallel columns**:

- a **key column** (`ValueColumn`, see below) holding the bucket values in physical order,
- a **single-record column** (`int[]`) for buckets that map to exactly one record,
- a sparse **overflow column** (`TransactionalBitmap[]`) for buckets that map to many records.

A bucket is materialized into a transient flyweight only when read; the leaf itself stores no wrapper
objects.

### `ValueColumn` -- the pluggable key half

```java
sealed interface ValueColumn<M extends Comparable<M>>
    permits BoxedObjectColumn, LongValueColumn, InstantValueColumn, IntValueColumn, FrontCodedStringColumn
```

A `ValueColumn` behaves like a fixed-slot array (insert / remove / `copyRangeTo` / `fillEmpty` /
`clearAt`) but is free to store the slots in a packed primitive representation. Implementations:

| Implementation | Physical storage | Routes which values |
|----------------|------------------|---------------------|
| `BoxedObjectColumn` | boxed `Object[]` (universal fallback) | any `Comparable` with a non-natural comparator, or types no specialized column handles |
| `LongValueColumn` | primitive `long[]` via an order-preserving `LongKeyCodec` | integral / temporal types under natural order (`Byte`…`Long`, etc.) |
| `IntValueColumn` | primitive `int[]` (4 bytes) | `Integer`, and `BigDecimal` normalized to a scaled `int` |
| `InstantValueColumn` | parallel `long[] seconds` + `int[] nanos` | `Instant` (lossless, lexicographic order == natural order) |
| `FrontCodedStringColumn` | prefix-compressed variable-length `byte[]` blob + restart-offset `int[]` | every `String` (localized **and** non-localized) |

#### Factory selection order

`ValueColumnFactory.forKey(plainType, comparator)` chooses the column with this precedence:

1. **`String`** → `FrontCodedStringColumn` -- always, regardless of comparator (front-coding is
   orthogonal to ordering; see below).
2. else, **natural order** (comparator is `null` or `Comparator.naturalOrder()`):
   - `Instant` → `InstantValueColumn`
   - `BigDecimal` → `IntValueColumn` (scaled int)
   - type with a `LongKeyCodec` → `LongValueColumn`
3. else → `BoxedObjectColumn`.

#### Front-coded strings are orthogonal to the comparator

`FrontCodedStringColumn` stores keys in whatever physical order the tree imposes -- natural codepoint
order **or** locale collation order -- and `findKeyPosition` **decodes each candidate back to a real
`String` and compares it via the supplied comparator**. Because comparison happens in `String` space,
the UTF-8 byte layout of the blob never participates in ordering, so one implementation serves both
localized and non-localized attributes correctly. (A naive "compare raw UTF-8 bytes" shortcut would
be unsafe: UTF-8 byte order equals Unicode codepoint order, but `String.compareTo` is UTF-16
code-unit order, and the two diverge for supplementary characters.)

Layout per entry: `varint(sharedPrefixLen) varint(suffixLen) suffixBytes(UTF-8)`, with a full
restart entry every 16 slots (random access decodes ≤15 forward steps from the nearest restart).

---

## Inverted-index bucket compaction

`InvertedIndex` is backed by a `TransactionalBucketBPlusTree` keyed by the normalized bucket value
and ordered by the index's `Comparator`. A bucket is read through one of two transient projections of
the `ValueToRecord` interface:

| Type | Role |
|------|------|
| `ValueToRecord` (interface) | read projection of one bucket: `getValue()`, `getRecordIds() → Bitmap`, `size()`, `recordSetEquals()`, `recordSetHashCode()` |
| `ValueToRecordPrimitive` | **single-record** bucket: holds `value` + a bare `int recordId`, zero allocation; `getRecordIds()` returns a `SingleRecordBitmap` |
| `ValueToRecordBitmap` | **multi-record** bucket: holds `value` + the live `TransactionalBitmap` (shared with the leaf, not copied) |
| `SingleRecordBitmap` | leanest read-only `Bitmap`: one `int`, no backing array / `RoaringBitmap` |

A bucket starts single-record (stored in the leaf's `int[]` column). On the **second distinct** record
id the leaf promotes it in place to a `TransactionalBitmap` in the overflow column; there is no
demotion back to single-record. This means the common "value → exactly one entity" case costs one
`int` slot instead of a boxed wrapper plus a `RoaringBitmap`.

---

## Scaled-int decimal keys and frozen `indexedDecimalPlaces`

`BigDecimal` filter/sort/histogram keys are stored as an order-preserving **scaled `int`** rather
than a boxed `BigDecimal`. `FilterIndex.getNormalizer(type, indexedDecimalPlaces)` maps a `BigDecimal`
to `NumberUtils.convertToInt(bd, indexedDecimalPlaces)`; the result lands in an `IntValueColumn`. The
normalizer is **idempotent** -- an already-scaled `Integer` passes through unchanged, so values can be
normalized more than once without error.

The scale (`indexedDecimalPlaces`) is **frozen at write time**, not re-derived at load:

1. **Persisted** into `FilterIndexStoragePart`, `SortIndexStoragePart`, and `HistogramIndexStoragePart`
   -- each carries an `indexedDecimalPlaces` field that its serializer writes.
2. **Restored verbatim** at load -- `AttributeIndexLoader` reads `part.getIndexedDecimalPlaces()` and
   feeds it into the normalizer. The live schema is **not** consulted, so the persisted scaled-int
   keys always decode at exactly the scale they were written.
3. **Drift-guarded on modification** via `FilterIndex.assertIndexedDecimalPlacesUnchanged` (called from
   `AttributeIndex` and `HistogramIndexOperations`). A schema scale change throws
   `GenericEvitaInternalError` rather than silently mis-scaling.

Freezing the scale at write time keeps the persisted scaled-int keys self-describing: they always
decode at exactly the scale they were written with, independent of any later schema edit.

> `UniqueIndex` is the exception: it keeps the **exact, unscaled** value (uniqueness is enforced on the
> canonical value), so it is not affected by `indexedDecimalPlaces` scaling.

---

## Order-statistic ranking for `SortIndexChanges`

`SortIndex` keeps its record ids blocked by value in a `TransactionalUnorderedIntArray`. To place a
record at the right offset on insert/remove, it needs the **rank** of a value -- the cumulative count
of all records with strictly-smaller values. `SortIndexChanges` answers that with a
`CumulativeWeightBPlusTree<Serializable>`: an order-statistic tree that augments each internal node
with the summed weight of its children, so `rankOf(value)` is `O(log V)` instead of an `O(V)` scan
over the value array. Weights (cardinalities) are pulled lazily from the owning `SortIndex` value
cursor.

---

## Owner / View split

`FilterIndex`, `SortIndex`, and `UniqueIndex` are each an **abstract sealed base** permitting an
`Owner*` producer subclass and a `*View` read subclass. The owner holds and commits the backing
structures; the view is a lightweight flyweight that carries forward the shared committed tree by
identity (`O(Δ)` per transaction) and delegates reads to it. For attributes that are **both filterable
and sortable**, the `SortIndexView` binds a direct reference to the filter `InvertedIndex` (the
"shared value tree") so the value/cardinality data is stored once and read by both indexes. The detail
of each split and its bind/rebind points is in
[data-structures.md](data-structures.md#attributeindex).

---

## Persistent maps for plain-valued and producer-valued index maps

The `AttributeIndex` sub-index maps and the cardinality/price side-maps are backed by
`PersistentTransactionalMap` (and its producer-valued subclass), which commits in `O(Δ·log₃₂ N)` by
path-copying only the changed keys into an immutable `ChampMap`, instead of the `O(N)` full-`HashMap`
rebuild a plain `TransactionalMap` performs. The producer-valued
`PersistentTransactionalProducerMap` additionally tracks a **dirty-key set** (via `ProducerMapChanges`
and `markValueMutated`) so that in-place mutations of producer values (which are reads from the map's
perspective) are still swept at commit. See [stm/data-structures.md](../stm/data-structures.md) and
[stm/champ-persistent-map.md](../stm/champ-persistent-map.md) for the STM mechanics.

---

## Persistence contract

The in-memory structures above are independent of the on-disk catalog format:

- The trees serialize through the canonical `(value, recordIds)` / range-point forms; on load the
  index rebuilds its tree (and its leaf columns) by **ordered inserts**.
- `indexedDecimalPlaces` is persisted into three storage parts (`FilterIndexStoragePart`,
  `SortIndexStoragePart`, `HistogramIndexStoragePart`) and read back verbatim at load.
- `SingleRecordBitmap` / `ValueToRecordPrimitive` are runtime-only compaction representations; the
  serialized bucket form does not depend on them.

---

## Test Blueprint Hints

1. **No-boxing column round-trip.** For every specialized `ValueColumn`, inserting a value and reading
   it back via `keyAt` / `asBoxedArray` must reproduce the original value (including `BigDecimal` at the
   frozen scale, `Instant` nanos, and `String` collation order).

2. **Single ↔ multi compaction.** A bucket with one record must materialize as `ValueToRecordPrimitive`
   (record set backed by `SingleRecordBitmap`); adding a second distinct id must promote it to a
   `TransactionalBitmap`-backed `ValueToRecordBitmap`; removing back down to one id must **not** demote.

3. **Front-coding orthogonal to order.** A `FrontCodedStringColumn` under a localized collator must
   keep byte-distinct but collation-equal keys in one bucket (both record ids present), and either
   spelling must locate and remove from that bucket.

4. **Path-copy isolation.** A write inside a transaction must publish a new `root` while a concurrent
   reader on the pre-transaction snapshot still sees the old tree; on rollback the new nodes are
   discarded and the old `root` is retained.

5. **Frozen scale survives schema drift.** Persist a `BigDecimal` filter index at scale `2`, change the
   schema scale, reload: the index must decode at scale `2` (from the storage part), and a subsequent
   modification must throw `GenericEvitaInternalError` via `assertIndexedDecimalPlacesUnchanged`.

6. **Rank correctness.** `CumulativeWeightBPlusTree.rankOf(value)` must equal the count of all records
   with strictly-smaller values, after arbitrary interleaved inserts/removals.

7. **Producer dirty-key sweep.** Mutating a producer value in a `PersistentTransactionalProducerMap`
   without calling `markValueMutated` (and without a key-level put/remove) must surface a
   `StaleTransactionMemoryException` at commit -- never silent staleness.