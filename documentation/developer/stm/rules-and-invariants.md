# Rules and Invariants for STM-Aware Code

This document is a reference for developers writing or testing transactional data structures and
indexes. Every rule below is an invariant that must hold for correctness of the STM layer.

---

## Invariants for TransactionalLayerCreator implementations

### INV-1: Stable, unique ID

Every instance of `TransactionalLayerCreator` must return a stable `long` from `getId()` that is unique
among all instances of the same class within a JVM.

**How to satisfy:** Use an instance field initialised from `TransactionalObjectVersion.SEQUENCE.nextId()`.

**Why it matters:** The ID is the primary component of the `TransactionalLayerCreatorKey` used to
locate diff layers. A duplicate ID within the same class would cause two objects to share a single diff
layer, leading to silent corruption.

### INV-2: Read-dispatch pattern

Every read method on a transactional object must first check for a transactional layer:

```java
T layer = Transaction.getTransactionalMemoryLayerIfExists(this);
if (layer == null) {
    // read from immutable delegate
} else {
    // read through diff (merge on the fly)
}
```

**Why it matters:** If the read bypasses the diff layer, the transaction sees stale data from the
baseline.

### INV-3: Write-dispatch pattern

Every write method on a transactional object must first check for a transactional layer:

```java
T layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
if (layer == null) {
    // no transaction -> mutate delegate directly
} else {
    // record mutation in diff layer
}
```

**Why it matters:** If the write modifies the delegate directly during a transaction, the change
is visible to all threads immediately, violating isolation. It also won't participate in the commit
cycle, so it cannot be rolled back.

### INV-4: Immutable baseline

The delegate (baseline) of a transactional object must never be mutated while a transaction is in
progress. Mutations must go exclusively to the diff layer.

**Exception:** When no transaction is active (`Transaction.getOrCreateTransactionalMemoryLayer` returns
null), direct mutation of the delegate is permitted. This is used during trunk incorporation where
the system operates in a single-writer mode.

### INV-5: removeLayer must clean up children

If a transactional object contains nested transactional children, its `removeLayer(maintainer)` method
must also remove the children's layers:

```java
public void removeLayer(TransactionalLayerMaintainer maintainer) {
    maintainer.removeTransactionalMemoryLayerIfExists(this);
    // also clean up nested children
    this.innerMap.removeLayer(maintainer);
    this.innerBitmap.removeLayer(maintainer);
}
```

**Why it matters:** Orphaned child layers would be detected as stale by `verifyLayerWasFullySwept`.

---

## Invariants for TransactionalLayerProducer implementations

### INV-6: Deep-wise merge

`createCopyWithMergedTransactionalMemory` must call `getStateCopyWithCommittedChanges` on every nested
`TransactionalLayerProducer` it contains:

```java
public MyIndex createCopyWithMergedTransactionalMemory(
    Void layer, TransactionalLayerMaintainer maintainer
) {
    return new MyIndex(
        maintainer.getStateCopyWithCommittedChanges(this.attributeIndex),
        maintainer.getStateCopyWithCommittedChanges(this.priceIndex),
        maintainer.getStateCopyWithCommittedChanges(this.facetIndex)
    );
}
```

**Why it matters:** Each `getStateCopyWithCommittedChanges` call both merges the child and marks its
layer as DISCARDED. If a child is skipped, its layer remains ALIVE and triggers
`StaleTransactionMemoryException`.

### INV-7: Never mutate the original

`createCopyWithMergedTransactionalMemory` must return a new instance. It must not modify `this`.

**Why it matters:** The original is still visible to other transactions and non-transactional readers.

### INV-8: Handle null layer

When `layer` is null (no direct changes to this object), the method must still check nested producers:

```java
if (layer == null) {
    // No direct changes, but children may have changed.
    // Walk nested producers and create a new envelope if any changed.
}
```

For simple leaf objects (e.g. `TransactionalBoolean`), returning the delegate when layer is null is
acceptable because there are no nested producers.

### INV-9: VoidTransactionMemoryProducer has no layer

Objects implementing `VoidTransactionMemoryProducer` must never call
`Transaction.getOrCreateTransactionalMemoryLayer(this)`. Their `createLayer()` throws
`UnsupportedOperationException` and their `getId()` returns a hardcoded value.

---

## Invariants for the commit process

### INV-10: All ALIVE layers must be DISCARDED after commit

After the finalizer's `commit()` returns, `verifyLayerWasFullySwept()` asserts that no layers remain
in the ALIVE state. Every layer ever created must have been consumed by `getStateCopyWithCommittedChanges`
or explicitly removed via `removeTransactionalMemoryLayerIfExists`.

### INV-11: No layer creation after commit/rollback

Once `allowTransactionalLayerCreation` is set to `false` (at the start of commit/rollback), any attempt
to create a new layer throws an assertion error. This prevents accidental modifications during the
commit cascade.

### INV-12: Created-then-removed items must be cleaned

When a transactional container (e.g. a map) creates a child producer and then removes it within the
same transaction, the child's layer must be cleaned up. Use `TransactionalContainerChanges.clean()`
to handle this.

### INV-13: AtomicReference swap for catalog

The new committed `Catalog` instance must be published to readers via a single
`AtomicReference.compareAndSet` call. This ensures that all readers see either the old or the new
version, never a partial state.

---

## Invariants for thread safety

### INV-14: One transaction per thread

A thread may have at most one active transaction. Attempting to bind a second, different transaction
throws an assertion error.

### INV-15: TransactionalLayerMaintainer is not thread-safe

All access to the maintainer must come from a single thread. The `@NotThreadSafe` annotation is a
hard requirement, not a suggestion.

### INV-16: Diff layers are thread-local

The diff layer for any given transactional object is accessible only through the `ThreadLocal`-stored
`Transaction`. Other threads calling `getTransactionalMemoryLayerIfExists(this)` with their own (or
no) transaction will see null and read from the immutable baseline.

---

## Invariants for Snapshotable diff layers (savepoints)

See [savepoints.md](savepoints.md) for the full lifecycle. These invariants apply to any diff layer that
implements `Snapshotable<M>`.

### INV-17: Snapshotable for savepoint-touchable layers

A diff layer that accumulates a diff and can be mutated inside an open savepoint **must** implement
`Snapshotable<M>`.

**Why it matters:** When a savepoint is open, the maintainer captures each layer's pre-mutation state on
first write-touch. A touched (or removed) layer that does not implement `Snapshotable` cannot be
reverted, so the maintainer throws `GenericEvitaInternalError` rather than silently leaving a
partial-rollback gap. Layers that are degenerate single-value diffs or rebuildable derived caches are
legitimately exempt (they are never mutated inside a savepoint, or their memento is a cheap
invalidation).

### INV-18: Memento independence

`snapshot()` must copy the layer's mutable containers deeply enough that a later mutation of the layer
cannot mutate the memento, and `restore()` must copy state *out of* the memento (or the memento must be
single-use). Primitive / immutable-value fields need no defensive cloning.

**Why it matters:** The same memento may be restored more than once; a shared-reference memento would be
corrupted by post-snapshot mutations and produce an incorrect rollback.

### INV-19: Nested-layer boundary

A layer's memento captures only *its own* diff. Nested `TransactionalLayerProducer` values the layer
holds (map values, array elements) are captured **by reference only** -- never deep-copied, never reached
into on restore.

**Why it matters:** Each nested layer's own `Snapshotable` is snapshotted independently by the
maintainer-level savepoint. Deep-copying nested values would double-capture their state and desynchronise
the two copies on restore.

---

## Rules for testing transactional data structures

### RULE-T1: Use assertStateAfterCommit / assertStateAfterRollback

All transactional behaviour tests should use these helpers. They handle transaction lifecycle, thread
binding, and the critical `verifyLayerWasFullySwept()` check.

### RULE-T2: Test the original is unchanged after commit

The verification lambda must assert that the original object's state has not been affected by the
transaction.

### RULE-T3: Test against a reference implementation

For every transactional data structure, maintain a test double using a well-known JDK collection (e.g.
`HashMap` for `TransactionalMap`, `HashSet` for `TransactionalSet`). Apply the same operations to both
and compare after commit.

### RULE-T4: Include a generational test

Every transactional data structure should have a `@Tag(LONG_RUNNING_TEST)` generational test that:
- Runs thousands of random operations per generation.
- Chains generations (committed output becomes next input).
- Compares against a test double after each generation.
- Prints the random seed for reproducibility.

### RULE-T5: Test deep-wise atomicity

If a structure supports nested producers, test that changes to nested children are committed
atomically with the parent.

### RULE-T6: Test stale-layer detection

Include a test that intentionally creates an orphaned layer and asserts that
`StaleTransactionMemoryException` is thrown.

### RULE-T7: Test rollback isolation

Verify that after rollback, the original is completely unchanged and no committed copy is produced.

### RULE-T8: Test non-transactional mode

Verify that mutations work correctly when no transaction is active (direct delegate mutation).

### RULE-T9: Test iterator transactional consistency

Iterators obtained during a transaction must reflect the transactional state (including insertions and
removals made in the transaction).

### RULE-T10: Test suppress mechanism

If a structure is used with `suppressTransactionalMemoryLayerFor`, test that reads during suppression
return baseline values and no layer is created.

---

## Quick reference: what to check when adding a new transactional field

When adding a new `TransactionalLayerProducer` field to an existing index or container:

1. Initialise the field in the constructor.
2. Ensure `getId()` on the field returns a unique ID (use `TransactionalObjectVersion.SEQUENCE.nextId()`).
3. Update `createCopyWithMergedTransactionalMemory` to call
   `transactionalLayer.getStateCopyWithCommittedChanges(newField)`.
4. Update `removeLayer(maintainer)` to clean up the new field's layer.
5. If the container uses `TransactionalContainerChanges`, register created/removed instances.
6. If the field's diff layer accumulates changes and can be touched inside a savepoint, implement
   `Snapshotable<M>` on it (see [INV-17](#inv-17-snapshotable-for-savepoint-touchable-layers)) --
   otherwise the maintainer throws on first write-touch inside a savepoint.
7. Write a unit test verifying commit/rollback for the new field.
8. Run the generational test to catch any accumulated errors.
9. Verify `verifyLayerWasFullySwept()` passes (handled by `assertStateAfterCommit`).
