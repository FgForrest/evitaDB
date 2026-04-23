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

**Implements:** `TransactionalLayerProducer<UnorderedIntArrayChanges, int[]>`

Unordered `int` array that allows duplicate values and position-based insertion.

**Key differences from `TransactionalIntArray`:**
- Supports `add(previousRecordId, recordId)` -- insert after a specific element.
- Supports `addOnIndex(index, recordId)` -- positional insertion.
- Supports `removeRange(startIndex, endIndex)`.

**Diff layer (`UnorderedIntArrayChanges`):** Tracks insert segments by position and removal ranges.
More complex than `IntArrayChanges` because the array is not sorted.

**Optimised read-through:** `indexOf`, `contains`, `length`.

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

### TransactionalIntBPlusTree\<V\> and TransactionalObjectBPlusTree\<K, V\>

**Implements:** `TransactionalLayerProducer<Void, TransactionalIntBPlusTree<V>>` (and similarly for Object variant)

These B+ tree implementations use a **different transactional strategy** from other data structures:
they do not maintain their own diff layer (the diff type is `Void`). Instead, they rely on
`TransactionalReference` wrappers for their mutable state:

```java
private final TransactionalReference<Integer> size;
private final TransactionalReference<BPlusTreeNode<?>> root;
```

Tree nodes are mutable and marked `@NotThreadSafe`. The transactionality is achieved at the reference
level: reads and writes to `size` and `root` go through `TransactionalReference`, which provides
diff-layer isolation.

**Commit:** Returns a copy of the tree itself. The `TransactionalReference` fields produce their own
committed values as part of the recursive merge.

**Note:** The B+ tree leaf nodes and internal nodes also have their own
`TransactionalObjectVersion` IDs and participate as `TransactionalLayerProducer` instances, creating a
fine-grained transactional structure within the tree.

---

## Summary table

| Class                          | Diff layer type          | Deep? | Commit produces            |
|--------------------------------|--------------------------|-------|----------------------------|
| `TransactionalIntArray`        | `IntArrayChanges`        | No    | `int[]`                    |
| `TransactionalObjArray<T>`     | `ObjArrayChanges<T>`     | No    | `T[]`                      |
| `TransactionalUnorderedIntArray` | `UnorderedIntArrayChanges` | No | `int[]`                    |
| `TransactionalBoolean`         | `BooleanChanges`         | No    | `Boolean`                  |
| `TransactionalReference<T>`    | `ReferenceChanges<T>`    | No    | `Optional<T>`              |
| `TransactionalBitmap`          | `BitmapChanges`          | No    | `Bitmap`                   |
| `TransactionalMap<K,V>`        | `MapChanges<K,V>`        | Conditional | `Map<K,V>`            |
| `TransactionalSet<K>`          | `SetChanges<K>`          | No    | `Set<K>`                   |
| `TransactionalList<V>`         | `ListChanges<V>`         | Conditional | `List<V>`             |
| `TransactionalComplexObjArray<T>` | `ComplexObjArrayChanges<T>` | Yes | `T[]`                  |
| `TransactionalIntBPlusTree<V>` | `Void`                   | Yes (via Ref) | `TransactionalIntBPlusTree<V>` |
| `TransactionalObjectBPlusTree<K,V>` | `Void`              | Yes (via Ref) | `TransactionalObjectBPlusTree<K,V>` |

---

## Higher-level transactional objects (indexes)

These are domain-specific objects that implement `TransactionalLayerProducer` (often via
`VoidTransactionMemoryProducer`) and compose the primitive data structures above:

| Class                            | Transactional fields (examples)                          |
|----------------------------------|----------------------------------------------------------|
| `Catalog`                        | `TransactionalReference<Long>`, `TransactionalMap<String, EntityCollection>` |
| `EntityCollection`               | `TransactionalReference<EntitySchema>`, etc.             |
| `EntityIndex`                    | `TransactionalBoolean`, `TransactionalBitmap`, `TransactionalMap<Locale, TransactionalBitmap>` |
| `AttributeIndex`                 | `TransactionalMap<AttributeIndexKey, FilterIndex>`, etc. |
| `UniqueIndex`                    | `TransactionalBoolean`, `TransactionalMap`               |
| `SortIndex`                      | `TransactionalMap` of sorted arrays                      |
| `FilterIndex`                    | `TransactionalMap`, `TransactionalBitmap`                |
| `FacetIndex`                     | `TransactionalMap<EntityReference, FacetReferenceIndex>` |
| `HierarchyIndex`                 | `TransactionalMap`, `TransactionalBitmap`                |
| `PriceSuperIndex`                | `TransactionalMap<PriceKey, PriceListAndCurrencyPriceSuperIndex>` |

All follow the same pattern: they hold transactional primitive data structures as fields, and their
`createCopyWithMergedTransactionalMemory` creates a new index instance by calling
`getStateCopyWithCommittedChanges` on each field.
