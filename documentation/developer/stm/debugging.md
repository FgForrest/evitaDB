# Debugging STM Issues

The most common STM bug is a **stale layer** -- a diff layer that was created during a transaction but
never consumed during commit. This means changes recorded in that layer are silently lost. The system
guards against this with `StaleTransactionMemoryException`, but understanding how to diagnose and fix
the root cause requires familiarity with `TransactionalObjectVersion` and the layer tracking mechanism.

---

## TransactionalObjectVersion

**Class:** `io.evitadb.core.transaction.memory.TransactionalObjectVersion`

A global, JVM-wide sequence generator that assigns a unique `long` ID to every transactional object
instance.

### How it works

```java
public class TransactionalObjectVersion {
    public static final TransactionalObjectVersion SEQUENCE = new TransactionalObjectVersion();
    private final AtomicLong version = new AtomicLong(Long.MIN_VALUE);
    private boolean positiveDomain = false;

    public long nextId() {
        final long id = this.version.incrementAndGet();
        if (!this.positiveDomain && id >= 0) {
            this.positiveDomain = true;
        }
        if (id == Long.MAX_VALUE || (id < 0 && this.positiveDomain)) {
            throw new IdentifierOverflowException(...);
        }
        return id;
    }
}
```

The sequence starts at `Long.MIN_VALUE` and increments by 1 on each call. It uses the entire `long`
range (negative domain first, then positive). Overflow is detected and throws
`IdentifierOverflowException`, which halts all writes and requires a JVM restart.

### Why every transactional object has an ID

Every class that implements `TransactionalLayerCreator` stores an instance-level ID:

```java
@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
```

This ID serves two purposes:

1. **Layer registry key:** Combined with the creator's runtime class, the ID forms the
   `TransactionalLayerCreatorKey` used to look up diff layers in `TransactionalLayerMaintainer`.

2. **Debugging identifier:** When `StaleTransactionMemoryException` is thrown, the exception message
   lists every stale layer by its ID, class name, and `toString()`. Because IDs are assigned
   sequentially at construction time, the ID tells you the approximate creation order of the object.

---

## Diagnosing a StaleTransactionMemoryException

### Step 1: Read the exception message

The exception lists all ALIVE (unprocessed) layers sorted by ID:

```
Failed to propagate all memory changes in transaction:
@-9223372036854775701 (TransactionalBitmap): TransactionalBitmap{size=42},
@-9223372036854775698 (TransactionalMap): TransactionalMap{size=3}
```

Each entry shows:
- `@{id}` -- the `TransactionalObjectVersion` ID
- `({ClassName})` -- the concrete class
- The `toString()` output of the creator instance

### Step 2: Identify the stale object

The class name tells you what type of data structure was modified but not consumed. The key question:
**which parent should have called `getStateCopyWithCommittedChanges` on this object but didn't?**

Common causes:
- A new transactional field was added to an index but `createCopyWithMergedTransactionalMemory` was
  not updated to merge it.
- A container (e.g. `TransactionalMap`) had an item added to it, and that item's layer was created,
  but the container was not included in the commit tree.
- A `removeLayer()` call is missing for a created-then-removed child.

### Step 3: Reproduce and set a conditional breakpoint

Because `TransactionalObjectVersion` is deterministic within a test run (IDs increment from
`Long.MIN_VALUE`), the problematic object gets the **same ID on every run** of the same test. Use this
to locate the exact moment the object is created:

1. Note the stale ID from the exception (e.g. `-9223372036854775701`).
2. Set a **conditional breakpoint** in `TransactionalObjectVersion.nextId()`:
   ```
   id == -9223372036854775701L
   ```
3. Run the test again. The breakpoint hits at the exact moment the problematic transactional object is
   constructed.
4. Inspect the call stack to see which index or container created this object.
5. Follow the code path to understand why it was not consumed during commit.

### Step 4: Fix the commit path

Once you know which object is orphaned and who should consume it, update the parent's
`createCopyWithMergedTransactionalMemory` to include:

```java
SomeType committedChild = transactionalLayer.getStateCopyWithCommittedChanges(this.childField);
```

Or, if the child was created and then removed in the same transaction, ensure the container's
`TransactionalContainerChanges.clean(maintainer)` is called during commit.

---

## Common debugging scenarios

### Scenario: New field added to an index

**Symptom:** `StaleTransactionMemoryException` with the class name matching the new field's type.

**Root cause:** The index's `createCopyWithMergedTransactionalMemory` was not updated to process the
new field.

**Fix:** Add `transactionalLayer.getStateCopyWithCommittedChanges(newField)` to the commit method and
pass the result to the new instance constructor.

### Scenario: Dynamic child added to a map

**Symptom:** `StaleTransactionMemoryException` with an ID matching a child object stored in a
`TransactionalMap`.

**Root cause:** The child was added to the map and created its own diff layer, but the map was not
configured as "deep" (with value-type awareness) so it did not recursively merge children.

**Fix:** Configure the map with producer value type:
```java
new TransactionalMap<>(delegate, ChildProducer.class, wrappingFunction)
```

### Scenario: Object created and removed in the same transaction

**Symptom:** `StaleTransactionMemoryException` for an object that should have been cleaned up.

**Root cause:** `TransactionalContainerChanges.clean(maintainer)` was not called, or the item was not
tracked in the container changes.

**Fix:** Ensure the container registers created/removed items in `TransactionalContainerChanges` and
calls `clean()` during commit.

### Scenario: Suppressed object written to unexpectedly

**Symptom:** An assertion failure in `suppressTransactionalMemoryLayerForWithResult` stating "There
already exists transactional memory for passed creator!"

**Root cause:** The object was written to before it was passed to the suppress mechanism. Suppression
requires that no diff layer exists for the object.

**Fix:** Reorder operations so that suppression happens before any writes, or investigate why the
object was written to unexpectedly.

---

## Overflow handling

If `TransactionalObjectVersion.nextId()` detects an overflow (the sequence wraps around after
exhausting all ~2^64 values), it:

1. Logs an ERROR: "Transactional object version sequence overflowed..."
2. Throws `IdentifierOverflowException`.

This halts all database writes. The database must be restarted to reset the sequence. In practice,
overflow is extremely unlikely -- at 1 billion IDs per second, it would take ~585 years to exhaust
the full `long` range.
