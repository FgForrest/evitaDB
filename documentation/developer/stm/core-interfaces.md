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

Extends both `TransactionalLayerCreator<DIFF_PIECE>` and `TransactionalStateProducer<COPY>` to produce
a committed snapshot from its own diff and any nested changes.

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
`Catalog`). For a producer with its own diff layer, this delegates to `copyWithOwnLayer`, which:

1. Looks up the diff layer entry for the producer.
2. Calls `producer.createCopyWithMergedTransactionalMemory(layer, this)`.
3. Marks the entry DISCARDED if one exists.

The recursive deep-wise contract means the root call cascades through the entire object graph.

---

## VoidTransactionMemoryProducer\<S\>

**Package:** `io.evitadb.core.transaction.memory`

Extends `TransactionalStateProducer<S>` for containers with no diff layer of their own. The container
implements `createCopyWithMergedTransactionalMemory` to collect committed child state and
`removeLayer(TransactionalLayerMaintainer)` to remove nested layers.

### Usage example

A container can collect the committed state of its transactional fields through the maintainer.

```java
public MyIndex createCopyWithMergedTransactionalMemory(
    TransactionalLayerMaintainer transactionalLayer
) {
    return new MyIndex(
        transactionalLayer.getStateCopyWithCommittedChanges(this.innerMap),
        transactionalLayer.getStateCopyWithCommittedChanges(this.innerBitmap)
    );
}
```

The maintainer dispatches directly to the container's copy method without looking up a diff for the container.

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

## Snapshotable\<M\> (opt-in)

**Package:** `io.evitadb.core.transaction.memory`

An **opt-in** SPI -- *not* a fourth mandatory interface -- implemented by a diff layer that wants to
support savepoints (partial rollback within a transaction). `snapshot()` captures the layer's mutable
state into an opaque memento and `restore(memento)` rewinds it. It is deliberately independent of
`TransactionalLayerProducer` because not every layer is a genuine accumulating diff worth snapshotting.

A layer that accumulates a diff and can be mutated inside a savepoint **must** implement it, or the
maintainer throws `GenericEvitaInternalError` on first write-touch. The full contract, the memento
invariants, and the two implementation strategies (deep-copy vs. `UndoJournal`) are covered in
[savepoints.md](savepoints.md).

---

## Interface hierarchy summary

```
TransactionalLayerCreator<T>
└── TransactionalLayerProducer<DIFF_PIECE, COPY>  (T = DIFF_PIECE)

TransactionalStateProducer<COPY>
├── TransactionalLayerProducer<DIFF_PIECE, COPY>
└── VoidTransactionMemoryProducer<S>             (COPY = S)

TransactionalCreatorMaintainer            (orthogonal; any object may implement it)
Snapshotable<M>                           (orthogonal, opt-in; a diff layer implements it for savepoints)
```
