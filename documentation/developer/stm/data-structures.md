# Transactional Data Structures

All transactional data structures in evitaDB follow the same diff-layer pattern described in
[core-interfaces.md](core-interfaces.md). This document catalogues every concrete implementation, its
diff strategy, and its commit behaviour.

---

## Classification

### By diff ownership

| Category           | Interface                              | Description                                          |
|--------------------|----------------------------------------|------------------------------------------------------|
| **Self-diffing**   | `TransactionalLayerProducer<D, C>`     | Owns a diff layer and produces a committed copy.     |
| **Container-only** | `VoidTransactionMemoryProducer<S>`     | No own diff. Produces committed copy by delegating to children. |

### By transactionality depth

| Depth       | Meaning                                                                           | Examples                                                   |
|-------------|-----------------------------------------------------------------------------------|------------------------------------------------------------|
| **Shallow** | The diff captures changes to the structure itself (inserts, removals, reorderings). On commit, a new container is created with the merged data. Contained elements are plain values (primitives, strings, etc.) and are not recursively merged. | `TransactionalIntArray`, `TransactionalBitmap`, `TransactionalBoolean`, `TransactionalReference`, `TransactionalSet` (when elements are plain values) |
| **Deep**    | The structure may contain nested `TransactionalLayerProducer` values. On commit, the container iterates its entries and calls `getStateCopyWithCommittedChanges` on each nested producer. The committed copy contains the recursively merged children. | `TransactionalMap` (when configured with a producer value type), `TransactionalComplexObjArray`, `TransactionalList` (when elements are producers) |

---

## Primitive-level data structures

### TransactionalIntArray

**Implements:** `TransactionalLayerProducer<IntArrayChanges, int[]>`

Ordered (sorted, ascending) array of unique `int` values.

**Diff layer (`IntArrayChanges`):**
- Tracks insertions as positional arrays: positions in the original + values to insert at each position.
- Tracks removals as an array of positions to skip.
- Maintains a memoized merged `int[]` that is recomputed lazily on the first read after a write.

**Optimised read-through methods** (operate on the diff without materialising the merged array):
- `indexOf(int)`, `contains(int)`, `length()`

**Commit:** Returns `layer.getMergedArray()` -- a new `int[]` combining original and diff.

**Non-transactional fallback:** When no transaction is active, mutations (e.g. `addRecordId`) modify
the delegate `int[]` directly.

---

### TransactionalObjArray\<T extends Comparable\<T\>\>

**Implements:** `TransactionalLayerProducer<ObjArrayChanges<T>, T[]>`

Generic version of `TransactionalIntArray` for `Comparable` objects. Uses a `Comparator<T>` to maintain
sorted order.

**Diff layer (`ObjArrayChanges<T>`):** Same positional insert/remove strategy as `IntArrayChanges`.

---

### TransactionalUnorderedIntArray

**Implements:** `TransactionalLayerProducer<Void, TransactionalUnorderedIntArray>`

Unordered `int` array that allows duplicate values and position-based insertion.

**Key operations:**
- `add(previousRecordId, recordId)` -- insert after a specific element.
- `addOnIndex(index, recordId)` -- positional insertion.
- `removeRange(startIndex, endIndex)`.
- Optimised read-through: `indexOf`, `contains`, `length`.

**No own diff layer (composite façade).** The array is a thin façade over two transactional B+ trees,
each of which carries its own transactional layer (so the façade itself produces a `Void` diff and
delegates). This keeps every operation -- positional insert, removal range, and `indexOf` -- at
`O(log N)` without a hand-maintained positional diff:

- a **position tree** (`UnorderedLookupTree`, an order-statistic tree) holding the elements in
  insertion-defined order and answering rank/positional queries in `O(log N)`;
- a **value index** (`TransactionalIntToLongBPlusTree`) mapping each record id to its order-key in the
  position tree, so `indexOf(recordId)` is `O(log N)` rather than a scan.

See [B+ Trees](#b-trees) below and
[indexes/bplus-tree-bucket-store.md](../indexes/bplus-tree-bucket-store.md).

---

### TransactionalBoolean

**Implements:** `TransactionalLayerProducer<BooleanChanges, Boolean>`

Transactional wrapper for a single `boolean` value.

**Diff layer (`BooleanChanges`):** Stores whether `setToTrue()` or `setToFalse()` was called.

**Commit:** Returns `layer.isTrue()`.

---

### TransactionalReference\<T\>

**Implements:** `TransactionalLayerProducer<ReferenceChanges<T>, Optional<T>>`

Transactional wrapper for a single object reference (backed by `AtomicReference<T>`).

**Diff layer (`ReferenceChanges<T>`):** Stores the current in-transaction value.

**Commit:** Returns `Optional.ofNullable(layer.get())`.

**Usage:** Widely used for schema references, version counters, and root pointers in B+ trees.

---

### TransactionalBitmap

**Implements:** `TransactionalLayerProducer<BitmapChanges, Bitmap>`

Transactional wrapper for `RoaringBitmap` -- a compressed bitset of unique integers.

**Diff layer (`BitmapChanges`):**
- `insertions: RoaringBitmap` -- bits added in the transaction.
- `removals: RoaringBitmap` -- bits removed in the transaction.
- `memoizedMergedBitmap` -- cached merge result, invalidated on each write.

**Merge formula:**
```
merged = (original OR insertions) AND_NOT removals
```

**Performance note:** Merging clones the original `RoaringBitmap` internally, which is suboptimal.
The `RoaringBitmap` library does not provide an immutable variant, so the entire bitmap is cloned
during merge. Results are cached until the next write operation.

**Commit:** Returns a new `BaseBitmap` wrapping the merged `RoaringBitmap`.

---

## Collection-level data structures

### TransactionalMap\<K, V\>

**Implements:** `TransactionalLayerCreator<MapChanges<K, V>>` AND `TransactionalLayerProducer<MapChanges<K, V>, Map<K, V>>`

Transactional wrapper for `java.util.Map`. The most commonly used transactional container.

**Diff layer (`MapChanges<K, V>`):**
- Created/modified entries: stored in an internal map.
- Removed keys: stored in a set.

**Read dispatch:**
1. Check if key was removed in the diff -> return null.
2. Check if key was created/modified in the diff -> return diff value.
3. Fall through to the original map.

**Iterator:** Two-phase iteration: first yields all created/modified entries, then yields original
entries that are not in the removed set. Entry `setValue()` operations go through the diff layer.

**Deep transactionality (optional):**

When constructed with a `valueType` and `transactionalLayerWrapper`:

```java
new TransactionalMap<>(delegate, MyProducer.class, wrapper)
```

the commit process iterates all values in the original map. For each value that is a
`TransactionalLayerProducer`, it calls `getStateCopyWithCommittedChanges` to recursively merge the
child's diff. This makes the map "deep" -- it not only tracks its own inserts/removes but also
recursively commits modified values.

When `valueType` is `null`, the map is "shallow" -- values are treated as plain objects.

---

### TransactionalSet\<K\>

**Implements:** `TransactionalLayerCreator<SetChanges<K>>` AND `TransactionalLayerProducer<SetChanges<K>, Set<K>>`

Transactional wrapper for `java.util.Set`.

**Diff layer (`SetChanges<K>`):**
- Added keys (newly inserted).
- Removed keys (deleted from original).

**Iterator:** Merges created keys with original keys minus removed keys.

---

### TransactionalList\<V\>

**Implements:** `TransactionalLayerCreator<ListChanges<V>>` AND `TransactionalLayerProducer<ListChanges<V>, List<V>>`

Transactional wrapper for `java.util.List`.

**Diff layer (`ListChanges<V>`):**
- `removedItems: TreeSet<Integer>` -- removed indices.
- `addedItems: TreeMap<Integer, V>` -- inserted items by adjusted index.

**Index adjustment:** When an item is inserted or removed, all subsequent indices in the diff are
incremented or decremented. Add/remove-first is O(N) due to this adjustment.

**Deep transactionality:** During commit, if a value is a `TransactionalLayerProducer`, it is
recursively merged.

---

## Complex array

### TransactionalComplexObjArray\<T\>

**Implements:** `TransactionalLayerProducer<ComplexObjArrayChanges<T>, T[]>`

Where `T extends TransactionalObject<T, ?> & Comparable<T>`.

This array stores objects that are themselves transactional and supports partial updates via
producer/reducer callbacks.

**Construction parameters:**
- `BiConsumer<T, T> producer` -- merges two containers into one (e.g. combines record-id sets).
- `BiConsumer<T, T> reducer` -- subtracts one container from another.
- `Predicate<T> obsoleteChecker` -- returns true if a container is empty after reduction (should be
  removed).

**Example:** Inserting two items with the same key `"a"` merges their payloads:

```
insert("a", [1, 2])
insert("a", [3, 4])
→ result: "a" → [1, 2, 3, 4]
```

**Nested commit:** If contained objects are `TransactionalLayerProducer` instances, the commit
recursively calls `createCopyWithMergedTransactionalMemory` on each.

**Performance caveat:** Unlike simpler arrays, `indexOf` and `length` require materialising the merged
array because the producer/reducer logic makes positional reasoning impossible on the diff alone.

---

## B+ Trees

evitaDB maintains a family of transactional B+ trees. They are **not** mergeable into one generic
tree -- the primitive variants exist to avoid boxing the key and/or value, which is the point of the
memory optimization. Their structure and production roles are documented in
[indexes/bplus-tree-bucket-store.md](../indexes/bplus-tree-bucket-store.md); this section covers only
their **transactional** behaviour.

| Tree | Key / Value | Backs |
|------|-------------|-------|
| `TransactionalBucketBPlusTree<K>` | `K` / record set (columnar bucket store) | `InvertedIndex` |
| `TransactionalLongBPlusTree<V>` | `long` / `V` | `RangeIndex` |
| `TransactionalObjectBPlusTree<K,V>` | `K` / `V` (optional `Comparator`) | `OwnerSortIndex`, `TrafficRecordingIndex` |
| `TransactionalIntToLongBPlusTree` | `int` / `long` | `TransactionalUnorderedIntArray` value index |

**Implements:** each is a `TransactionalLayerProducer<Void, Self>` -- the diff type is `Void`; they
own **no** `Changes` layer. Mutable state lives behind two `TransactionalReference` fields:

```java
private final TransactionalReference<Integer> size;
private final TransactionalReference<BPlusTreeNode<K, ?>> root;
```

Tree nodes are mutable and marked `@NotThreadSafe`. Transactional isolation is achieved by
**path-copying** (copy-on-write): a write clones the nodes on the root-to-leaf path and publishes a
new `root` through the `TransactionalReference`; readers outside the transaction keep the old root, and
a rollback simply discards the new nodes.

**Commit:** returns a copy of the tree itself. The `TransactionalReference` fields produce their own
committed values as part of the recursive merge. Leaf and internal nodes are themselves
`TransactionalLayerProducer` instances with their own `TransactionalObjectVersion` IDs, creating a
fine-grained transactional structure within the tree.

> **`CumulativeWeightBPlusTree<K>` is the exception** -- it is a non-transactional order-statistic
> tree used as a transient helper inside `SortIndexChanges` (never persisted, discarded after use), so
> it holds plain `root`/`size`/`totalWeight` fields, no `TransactionalReference`, and skips
> rebalancing on delete.

---

## Summary table

| Class                          | Diff layer type          | Deep? | Commit produces            |
|--------------------------------|--------------------------|-------|----------------------------|
| `TransactionalIntArray`        | `IntArrayChanges`        | No    | `int[]`                    |
| `TransactionalObjArray<T>`     | `ObjArrayChanges<T>`     | No    | `T[]`                      |
| `TransactionalUnorderedIntArray` | `Void` (composite façade) | Yes (via trees) | `TransactionalUnorderedIntArray` |
| `TransactionalBoolean`         | `BooleanChanges`         | No    | `Boolean`                  |
| `TransactionalReference<T>`    | `ReferenceChanges<T>`    | No    | `Optional<T>`              |
| `TransactionalBitmap`          | `BitmapChanges`          | No    | `Bitmap`                   |
| `TransactionalMap<K,V>`        | `MapChanges<K,V>`        | Conditional | `Map<K,V>`            |
| `PersistentTransactionalMap<K,V>` | `MapChanges<K,V>`     | Conditional | `Map<K,V>` (ChampMap-backed) |
| `TransactionalSet<K>`          | `SetChanges<K>`          | No    | `Set<K>`                   |
| `TransactionalList<V>`         | `ListChanges<V>`         | Conditional | `List<V>`             |
| `TransactionalComplexObjArray<T>` | `ComplexObjArrayChanges<T>` | Yes | `T[]`                  |
| `TransactionalBucketBPlusTree<K>` | `Void`                | Yes (via Ref) | `TransactionalBucketBPlusTree<K>` |
| `TransactionalLongBPlusTree<V>` | `Void`                  | Yes (via Ref) | `TransactionalLongBPlusTree<V>` |
| `TransactionalObjectBPlusTree<K,V>` | `Void`              | Yes (via Ref) | `TransactionalObjectBPlusTree<K,V>` |
| `TransactionalIntToLongBPlusTree` | `Void`                | Yes (via Ref) | `TransactionalIntToLongBPlusTree` |
| `CumulativeWeightBPlusTree<K>` | *(none -- non-transactional)* | No | *(transient helper)* |

---

## Higher-level transactional objects (indexes)

These are domain-specific objects that implement `TransactionalLayerProducer` (often via
`VoidTransactionMemoryProducer`) and compose the primitive data structures above:

| Class                            | Transactional fields (examples)                          |
|----------------------------------|----------------------------------------------------------|
| `Catalog`                        | `TransactionalReference<Long>`, `TransactionalMap<String, EntityCollection>` |
| `EntityCollection`               | `TransactionalReference<EntitySchema>`, etc.             |
| `EntityIndex`                    | `TransactionalBoolean`, `TransactionalBitmap`, `TransactionalMap<Locale, TransactionalBitmap>` |
| `AttributeIndex`                 | `PersistentTransactionalProducerMap<AttributeIndexKey, InvertedIndex>` (and `RangeIndex`/`UniqueIndex`/`SortIndex`/`ChainIndex`); derived `TransactionalMap` view caches |
| `OwnerUniqueIndex`               | `PersistentTransactionalMap<Serializable, Integer>`, `TransactionalBitmap`, `TransactionalBoolean` |
| `OwnerSortIndex`                 | `TransactionalUnorderedIntArray`, `TransactionalObjectBPlusTree` (value→cardinality) |
| `OwnerFilterIndex` / `InvertedIndex` | `TransactionalBucketBPlusTree` (columnar bucket store), optional `RangeIndex` |
| `FacetIndex`                     | `TransactionalMap<EntityReference, FacetReferenceIndex>` |
| `HierarchyIndex`                 | `TransactionalMap`, `TransactionalBitmap`                |
| `PriceSuperIndex`                | `TransactionalMap<PriceKey, PriceListAndCurrencyPriceSuperIndex>` |

All follow the same pattern: they hold transactional primitive data structures as fields, and their
`createCopyWithMergedTransactionalMemory` creates a new index instance by calling
`getStateCopyWithCommittedChanges` on each field.

---

## Persistent structures and ChampMap-backed transactional maps

Not every structurally-shared data structure is a diff-layer `TransactionalLayerProducer`. The
[CHAMP persistent hash map](champ-persistent-map.md) (`ChampMap`) achieves the same copy-on-write
structural sharing but exposes it directly: each `updated`/`removed` returns a new immutable map instead
of staging a thread-local diff. `ChampMap` is used in two ways:

1. **Directly** by the persistence layer (`OffsetIndex`) -- see
   [champ-persistent-map.md](champ-persistent-map.md).
2. **As the committed backing of `PersistentTransactionalMap`** for in-memory index maps. This is a
   drop-in `TransactionalMap` replacement that still stages a `MapChanges` diff during the transaction,
   but on commit folds the diff onto an immutable `ChampMap` snapshot by path-copying **only the changed
   keys** (`O(Δ·log₃₂ N)`), rather than rebuilding the entire delegate `HashMap` (`O(N)`) the way a
   plain `TransactionalMap` does.

### PersistentTransactionalMap\<K, V\>

**Implements:** the same `TransactionalLayerProducer<MapChanges<K,V>, Map<K,V>>` contract as
`TransactionalMap`, so it is interchangeable at call sites.

Internally it holds its state as either a **thawed** `HashMap` (non-transactional warm-up) or a
**sealed** `ChampMap` (transactional steady state). It is sealed lazily on first transactional touch;
on commit the diff is folded onto the sealed snapshot. Constraints: no null keys/values, unordered
(hash-trie) iteration.

### PersistentTransactionalProducerMap\<K, V\>

A variant for maps whose **values are themselves `TransactionalLayerProducer`s** (e.g. `InvertedIndex`,
`RangeIndex`, `UniqueIndex`, `SortIndex`, `ChainIndex` inside `AttributeIndex`). Because a producer
value mutates through its own diff layer -- which is a *read* from the map's perspective -- such in-place
mutations leave no trace in the map's put/remove tracking. The producer map therefore adds a
**dirty-key set** (`ProducerMapChanges.valueMutatedKeys`): callers declare in-place value mutations with
`markValueMutated(key)`, and commit sweeps `removedKeys ∪ modifiedKeys ∪ valueMutatedKeys`. A forgotten
`markValueMutated` surfaces as a `StaleTransactionMemoryException` at commit -- a hard failure, never
silent staleness.

These are documented from the index side in
[indexes/bplus-tree-bucket-store.md](../indexes/bplus-tree-bucket-store.md#persistent-maps-for-plain-valued-and-producer-valued-index-maps).
