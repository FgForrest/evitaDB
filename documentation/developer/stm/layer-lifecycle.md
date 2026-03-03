# Diff Layer Lifecycle

This document describes how transactional diff layers are created, stored, consumed during commit, and
verified for completeness.

---

## TransactionalLayerMaintainer -- the diff registry

`TransactionalLayerMaintainer` is the central registry that stores all diff layers for a single
transaction. It is **not thread-safe** (`@NotThreadSafe`) and must only be accessed from the thread
that owns the transaction.

### Internal storage

```java
private final Map<TransactionalLayerCreatorKey, TransactionalLayerWrapper<?>> transactionalLayer;
```

The map is pre-allocated with capacity 4096. Keys are `TransactionalLayerCreatorKey` records that
combine:

- The runtime class of the `TransactionalLayerCreator` instance
- The `long` value returned by `TransactionalLayerCreator.getId()`

Two objects produce the same key only if they are instances of the **same class** AND return the
**same `getId()`**. This allows different classes to reuse ID values without collision.

### TransactionalLayerWrapper\<T\>

Each diff layer is stored in a `TransactionalLayerWrapper` that tracks the layer's lifecycle state:

| State       | Meaning                                                   |
|-------------|-----------------------------------------------------------|
| `ALIVE`     | Layer was created and is accepting changes.               |
| `DISCARDED` | Layer was consumed during commit via `getStateCopyWithCommittedChanges`. |

The transition `ALIVE -> DISCARDED` is one-way. Attempting to discard a layer twice throws an
assertion error.

### Flags and guards

| Field                           | Purpose                                                 |
|---------------------------------|---------------------------------------------------------|
| `allowTransactionalLayerCreation` | Set to `false` during commit/rollback. Any attempt to create a new layer after this point throws. |
| `avoidDiscardingState`          | Temporary flag set by `getStateCopyWithCommittedChangesWithoutDiscardingState` to keep layer ALIVE after merge. Used when the merged state is needed mid-transaction (e.g. for reading back a committed snapshot without ending the transaction). |
| `parent`                        | Optional reference to a parent maintainer (for future nested transaction support). Layer lookup traverses to the parent if a layer is not found locally. |

---

## Layer creation

When a transactional data structure is written to for the first time in a transaction, the following
call chain executes:

```
TransactionalIntArray.addRecordId(value)
  └─ Transaction.getOrCreateTransactionalMemoryLayer(this)
       └─ Transaction.CURRENT_TRANSACTION.get()
            └─ transaction.transactionalMemory
                 .getOrCreateTransactionalMemoryLayer(this)
                      └─ TransactionalLayerMaintainer
                           .getOrCreateTransactionalMemoryLayer(this)
```

Inside the maintainer:

1. Look up `TransactionalLayerCreatorKey(this)` in the HashMap.
2. If found, return the existing layer.
3. If not found and `parent != null`, delegate to `parent.getTransactionalMemoryLayerIfExists(this)`.
4. If still not found:
   - Assert `allowTransactionalLayerCreation` is true.
   - Call `this.createLayer()` to create a fresh diff.
   - If the result is non-null, wrap it in `TransactionalLayerWrapper` and register it.
   - Return the layer.

For `VoidTransactionMemoryProducer` objects, `createLayer()` is never called (it throws). These objects
are never registered in the maintainer.

---

## Layer consumption during commit

When the transaction commits, the finalizer invokes
`TransactionalLayerMaintainer.getStateCopyWithCommittedChanges(rootProducer)`:

```java
public <S, T> S getStateCopyWithCommittedChanges(TransactionalLayerProducer<T, S> producer) {
    TransactionalLayerWrapper<T> wrapper = getTransactionalMemoryLayerItemWrapperIfExists(producer);
    S copy = producer.createCopyWithMergedTransactionalMemory(
        wrapper != null ? wrapper.getItem() : null,
        this
    );
    if (!avoidDiscardingState.get() && wrapper != null) {
        wrapper.discard();   // ALIVE → DISCARDED
    }
    return copy;
}
```

Key points:

1. The producer receives its own diff layer (or null if it was never written to).
2. Inside `createCopyWithMergedTransactionalMemory`, the producer recursively calls
   `transactionalLayer.getStateCopyWithCommittedChanges(childProducer)` for each nested producer.
3. After the producer returns, the wrapper is discarded.
4. The recursion means the commit cascades through the entire object graph starting from the root.

### Order of processing

The order in which producers are committed is determined entirely by the recursive structure of the
object graph. The root producer (typically `Catalog`) drives the commit; it asks each of its children
to commit, and they ask their children, etc. There is no independent ordering of layers.

### Mid-transaction merged state

Sometimes the merged state is needed inside the transaction (e.g. during a two-phase operation).
The method `getStateCopyWithCommittedChangesWithoutDiscardingState` can be used:

```java
transactionalLayer.getStateCopyWithCommittedChangesWithoutDiscardingState(producer);
```

This temporarily sets the `avoidDiscardingState` flag so that the layer remains ALIVE after merge.
This must not be called in a nested fashion.

---

## Layer removal

Diff layers can also be explicitly removed without committing:

| Method                                  | Behaviour                                              |
|-----------------------------------------|--------------------------------------------------------|
| `removeTransactionalMemoryLayer(creator)` | Removes and returns the layer. Throws if not found.   |
| `removeTransactionalMemoryLayerIfExists(creator)` | Removes and returns the layer. Returns null if not found. |

These are used when a transactional object is deleted mid-transaction and its diff should not
participate in the commit. The layer is simply removed from the HashMap; it never transitions to
DISCARDED.

The `TransactionalLayerCreator.removeLayer(maintainer)` method is responsible for also removing layers
of any nested children.

---

## TransactionalContainerChanges -- tracking child lifecycle

When a container (e.g. `TransactionalMap`) creates or removes child `TransactionalLayerProducer`
instances within a transaction, it uses `TransactionalContainerChanges` to track them:

```java
TransactionalContainerChanges<DIFF, COPY, PRODUCER> changes;
changes.addCreatedItem(newProducer);    // new child added in this transaction
changes.addRemovedItem(oldProducer);    // existing child removed in this transaction
```

During commit:

- `clean(maintainer)` removes layers for items that were both created and removed in the same
  transaction (net-zero effect, avoids orphan layers).
- `cleanAll(maintainer)` removes all tracked items' layers (used during rollback or when the entire
  container is discarded).

---

## Layer sweep verification

After the finalizer's `commit()` method returns, the system calls:

```java
transactionalLayerMaintainer.verifyLayerWasFullySwept();
```

This iterates every entry in the `transactionalLayer` HashMap and collects all wrappers that are still
in the `ALIVE` state. If any are found, a `StaleTransactionMemoryException` is thrown.

The exception message lists every uncollected layer with its:

- `@{id}` -- the `TransactionalObjectVersion` ID (from `getId()`)
- `({ClassName})` -- the simple class name of the creator
- `toString()` representation of the creator

The layers are sorted by ID in the exception message, which aids debugging (see
[debugging.md](debugging.md)).

### Why this matters

If a diff layer is not consumed, its changes are lost. This is a critical data integrity violation --
the transaction appears to have committed successfully, but some mutations were silently dropped. The
sweep check is the safety net that catches these bugs.

---

## Suppression mechanism

`TransactionalMemory` maintains a stack of suppressed creator sets:

```java
private final Deque<ObjectIdentityHashSet<TransactionalLayerCreator<?>>> suppressedCreatorStack;
```

When `suppressTransactionalMemoryLayerFor(object, consumer)` is called:

1. A new `ObjectIdentityHashSet` is created and the object is added to it.
2. If the object implements `TransactionalCreatorMaintainer`, all its maintained creators are also
   added.
3. The set is pushed onto the stack.
4. The consumer lambda is invoked.
5. After the lambda completes, the set is popped from the stack.

While the set is on the stack, `getOrCreateTransactionalMemoryLayer` and
`getTransactionalMemoryLayerIfExists` check whether the requested creator is in the top set. If it
is, they return `null` -- effectively making the object non-transactional for the scope of the lambda.

### Preconditions

- The object must implement `TransactionalLayerCreator`.
- There must not already be an existing transactional layer for the object (asserted).

### Use cases

- Reading the immutable base state of an object during a transaction without creating a diff layer.
- Temporarily bypassing transactional tracking for performance (e.g. during storage reads).

---

## Extending a transaction for multiple replays

During trunk incorporation, the system replays multiple committed transactions sequentially on the same
`TransactionalLayerMaintainer`. Between replays, `extendTransaction()` is called:

```java
public void extendTransaction() {
    this.allowTransactionalLayerCreation = true;
}
```

This re-enables layer creation after a previous commit disabled it, allowing the same maintainer to
accumulate changes from the next transaction. This is used by `TransactionTrunkFinalizer` which batches
multiple transaction replays before producing a single committed catalog.
