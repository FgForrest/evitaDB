# Core STM Interfaces

This document describes the two central interfaces that every STM-aware data structure must implement,
plus two supplementary interfaces for special scenarios.

---

## TransactionalLayerCreator\<T\>

**Package:** `io.evitadb.core.transaction.memory`

The base contract for any object that participates in transactional memory. The type parameter `T`
represents the diff layer -- a mutable object that captures all changes against the immutable baseline.

### Methods

| Method                                                     | Purpose                                               |
|------------------------------------------------------------|-------------------------------------------------------|
| `long getId()`                                             | Returns a stable, unique identifier for this instance. |
| `T createLayer()`                                          | Creates a fresh, empty diff layer.                    |
| `void removeLayer()`                                       | Convenience; delegates to `removeLayer(maintainer)`.  |
| `void removeLayer(TransactionalLayerMaintainer maintainer)` | Removes this object's diff from the transaction and must also remove any nested children's diffs. |

### Contract for `getId()`

- Must be unique across all instances of the same class within a single JVM.
- Must be stable for the entire lifetime of the object (assigned once, never changed).
- The recommended implementation is a field initialised from `TransactionalObjectVersion.SEQUENCE.nextId()`:
  ```java
  @Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
  ```
- The ID is used as part of the composite key (`TransactionalLayerCreatorKey`) that maps this object to
  its diff layer inside `TransactionalLayerMaintainer`. The key combines the creator's runtime class
  with the ID, so the same numeric ID may exist in two different classes without collision.

### Required read/write pattern

Every getter and setter on a `TransactionalLayerCreator` must follow this dispatch pattern:

```java
// READ path
T layer = Transaction.getTransactionalMemoryLayerIfExists(this);
if (layer == null) {
    // no transaction or no layer yet -> read directly from immutable delegate
} else {
    // read through the diff layer (merge on the fly)
}

// WRITE path
T layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
if (layer == null) {
    // no transaction -> mutate the delegate directly
    // (safe only during trunk incorporation or single-threaded setup)
} else {
    // record the mutation in the diff layer
}
```

**Important:** `getOrCreateTransactionalMemoryLayer` will call `createLayer()` on the first write access
in a given transaction, and register the resulting diff in the `TransactionalLayerMaintainer`. From this
point on, the layer is ALIVE and **must** be consumed during commit.

---

## TransactionalLayerProducer\<DIFF_PIECE, COPY\>

**Package:** `io.evitadb.core.transaction.memory`

Extends `TransactionalLayerCreator<DIFF_PIECE>` with the ability to produce a committed snapshot.

### Methods

| Method | Purpose |
|--------|---------|
| `COPY createCopyWithMergedTransactionalMemory(DIFF_PIECE layer, TransactionalLayerMaintainer maintainer)` | Merges the diff into a new instance of the object. |

### Contract for `createCopyWithMergedTransactionalMemory`

1. **Never mutate the original object.** The method must return a **new instance** that combines the
   baseline state with the diff. The original remains valid for other readers.

2. **Handle `null` layer.** If `layer` is `null`, the object itself was not modified during the
   transaction. However, the object may contain nested producers that *were* modified. In that case the
   method must still walk nested producers and create a new envelope if any nested state changed.

3. **Merge deep-wise.** For every field that is itself a `TransactionalLayerProducer`, the method must
   call:
   ```java
   S nestedCopy = transactionalLayer.getStateCopyWithCommittedChanges(nestedProducer);
   ```
   This recursively merges the nested producer's diff and marks its layer as DISCARDED.

4. **Return `@Nonnull`.** The return value is always non-null. Even if nothing changed, return the
   existing baseline (or a reference to it).

### When is this method called?

During commit, the `TransactionalLayerMaintainerFinalizer` invokes
`TransactionalLayerMaintainer.getStateCopyWithCommittedChanges(producer)` for the root producer (e.g.
`Catalog`). This method:

1. Retrieves the diff layer wrapper for the producer.
2. Calls `producer.createCopyWithMergedTransactionalMemory(layer, this)`.
3. Unless `avoidDiscardingState` is set, calls `wrapper.discard()` to mark the layer DISCARDED.

The recursive deep-wise contract means the root call cascades through the entire object graph.

---

## VoidTransactionMemoryProducer\<S\>

**Package:** `io.evitadb.core.transaction.memory`

A convenience interface for objects that do not have their own diff layer but still need to produce a
committed copy because they contain nested transactional children. The diff type is `Void` and
`createLayer()` throws `UnsupportedOperationException` (it must never be called).

### Default method implementations

| Method          | Default implementation                             |
|-----------------|----------------------------------------------------|
| `getId()`       | Returns `1L` (the object is never registered as a diff-owning creator). |
| `createLayer()` | Throws `UnsupportedOperationException`.            |

### Usage example

A `VoidTransactionMemoryProducer` is typically a container (e.g. an index) that holds
`TransactionalMap`, `TransactionalBitmap`, etc. as fields. It does not track changes itself -- its
children do. But it must implement `createCopyWithMergedTransactionalMemory` to create a new container
instance whose children are the committed versions:

```java
public MyIndex createCopyWithMergedTransactionalMemory(
    Void layer,
    TransactionalLayerMaintainer transactionalLayer
) {
    return new MyIndex(
        transactionalLayer.getStateCopyWithCommittedChanges(this.innerMap),
        transactionalLayer.getStateCopyWithCommittedChanges(this.innerBitmap)
    );
}
```

Because `getId()` returns `1L` and `createLayer()` is never called, the
`TransactionalLayerMaintainer` will never contain a diff entry for this object. The object participates
in commit only when its parent calls `getStateCopyWithCommittedChanges` on it.

---

## TransactionalCreatorMaintainer

**Package:** `io.evitadb.core.transaction.memory`

Declares that an object maintains inner `TransactionalLayerCreator` fields.

### Methods

| Method | Purpose |
|--------|---------|
| `Collection<TransactionalLayerCreator<?>> getMaintainedTransactionalCreators()` | Returns all inner transactional creators managed by this object. |

### Purpose

Used exclusively by `TransactionalMemory.suppressTransactionalMemoryLayerFor(...)`. When an object is
suppressed, the suppression mechanism also needs to suppress all its nested creators. If the object
implements `TransactionalCreatorMaintainer`, the method iterates the returned collection and adds each
nested creator to the suppressed set.

Example implementors: `TransactionalRangePoint` (maintains `TransactionalBitmap` fields for starts/ends
and a `TransactionalBoolean` for dirty flag).

---

## TransactionalObject\<T, DIFF_LAYER\>

**Package:** `io.evitadb.index.array`

A marker interface required for objects placed inside `TransactionalComplexObjArray`. It extends
`TransactionalLayerCreator<DIFF_LAYER>` and adds a single method:

```java
T makeClone();
```

This method creates a deep clone of the object, including all transactionally active inner objects.
It is used during commit of `TransactionalComplexObjArray` to produce copies of nested elements.

---

## Interface hierarchy summary

```
TransactionalLayerCreator<T>
├── TransactionalLayerProducer<DIFF_PIECE, COPY>
│   └── VoidTransactionMemoryProducer<S>  (DIFF_PIECE = Void)
└── TransactionalObject<T, DIFF_LAYER>    (for ComplexObjArray items)

TransactionalCreatorMaintainer            (orthogonal; any object may implement it)
```
