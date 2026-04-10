# Software Transactional Memory (STM)

evitaDB uses Software Transactional Memory to isolate concurrent mutations to in-memory indexes. Instead
of locking data structures, each transaction operates on a diff overlay that captures changes against an
immutable baseline. Reads outside the transaction see only the committed baseline; reads inside the
transaction merge the baseline with the overlay on-the-fly. On commit, a new immutable snapshot is
produced by applying the diff; on rollback, the diff is simply discarded.

This document covers the internal design, key classes, invariants, and debugging techniques of the STM
layer. For the user-facing transaction lifecycle (WAL, conflict resolution, catalog propagation) see
[transactions.md](../../user/en/deep-dive/transactions.md).

## Table of contents

| Document                                       | Content                                                             |
|------------------------------------------------|---------------------------------------------------------------------|
| [overview.md](overview.md) (this file)         | High-level architecture and design principles                       |
| [core-interfaces.md](core-interfaces.md)       | `TransactionalLayerCreator`, `TransactionalLayerProducer` contracts |
| [layer-lifecycle.md](layer-lifecycle.md)        | How diff layers are created, consumed, and verified                 |
| [data-structures.md](data-structures.md)       | Concrete transactional data structures and their diff strategies    |
| [debugging.md](debugging.md)                   | `TransactionalObjectVersion`, stale-layer diagnosis, debugging tips |
| [testing.md](testing.md)                       | Generational / property-based testing patterns                      |
| [rules-and-invariants.md](rules-and-invariants.md) | Formal rules for writing and testing STM-aware code             |

---

## Design principles

1. **Immutable baseline** -- the data visible to readers is never mutated in place. Writers work against
   a thread-local diff layer; the immutable baseline is shared freely across threads without
   synchronisation.

2. **Thread-local isolation** -- the active transaction is stored in a `ThreadLocal<Transaction>`
   (`Transaction.CURRENT_TRANSACTION`). A single thread may have at most one active transaction at a
   time. Because the diff layer is reachable only through the thread-local, no two threads can see
   each other's uncommitted changes.

3. **Copy-on-write with structural sharing (path copying)** -- when a transaction is committed the
   system does not copy the entire data-structure tree. It creates new instances only for the modified
   nodes and all their ancestors up to the root (the `Catalog` object). Unmodified subtrees are shared
   between the old and the new version. The root is then swapped atomically via `AtomicReference`.

4. **Double-entry bookkeeping** -- every diff layer that is created during a transaction must be
   consumed (merged into a new snapshot) during the commit phase. After commit the
   `TransactionalLayerMaintainer.verifyLayerWasFullySwept()` method checks that no ALIVE layers remain.
   If any do, a `StaleTransactionMemoryException` is thrown, preventing silent data loss.

5. **Deep-wise merge** -- when an object's `createCopyWithMergedTransactionalMemory` is called, it must
   recursively invoke the same method on any nested `TransactionalLayerProducer` instances it contains.
   This ensures the entire object graph is merged atomically.

---

## Key classes at a glance

```
io.evitadb.core.transaction
  Transaction                         -- thread-local transaction container
  TransactionHandler                  -- interface for mutation registration + finalizer
  TransactionWalFinalizer             -- isolated-WAL-based transaction handler
  TransactionTrunkFinalizer           -- trunk incorporation handler

io.evitadb.core.transaction.memory
  TransactionalMemory                 -- suppressed-creator-aware facade over the maintainer
  TransactionalLayerMaintainer        -- central diff-layer registry (HashMap-based)
  TransactionalLayerMaintainerFinalizer -- pluggable commit/rollback strategy
  TransactionalLayerCreator<T>        -- contract for objects that create diff layers
  TransactionalLayerProducer<D, C>    -- extends Creator; also merges diff into a copy
  VoidTransactionMemoryProducer<S>    -- convenience for objects with no own diff
  TransactionalCreatorMaintainer      -- declares nested transactional children
  TransactionalContainerChanges       -- helper tracking created/removed child producers
  TransactionalLayerWrapper<T>        -- envelope tracking ALIVE/DISCARDED state
  TransactionalLayerState             -- enum {ALIVE, DISCARDED}
  TransactionalObjectVersion          -- global sequence for unique creator IDs

io.evitadb.core.exception
  StaleTransactionMemoryException     -- thrown when layers are not fully swept
```

---

## How a transaction binds to the current thread

```
Transaction.executeInTransactionIfProvided(transaction, () -> {
    // 1. transaction.bindTransactionToThread() sets CURRENT_TRANSACTION ThreadLocal
    // 2. lambda executes; any STM-aware object consults CURRENT_TRANSACTION
    // 3. on exception: transaction.setRollbackOnlyWithException(ex)
    // 4. finally: transaction.unbindTransactionFromThread() clears ThreadLocal
});
```

All static helpers on `Transaction` (e.g. `getOrCreateTransactionalMemoryLayer`,
`getTransactionalMemoryLayerIfExists`) delegate to the `CURRENT_TRANSACTION` thread-local and then
to the inner `TransactionalMemory` / `TransactionalLayerMaintainer`.

---

## How the commit produces a new snapshot

```
Transaction.close()
  ├─ if rollbackOnly → transactionalMemory.rollback(cause)
  └─ else            → transactionalMemory.commit()
                           └─ TransactionalLayerMaintainer.commit()
                                ├─ allowTransactionalLayerCreation = false
                                └─ finalizer.commit(this)
                                     └─ getStateCopyWithCommittedChanges(rootProducer)
                                          ├─ producer.createCopyWithMergedTransactionalMemory(layer, this)
                                          │    └─ recursively merges nested producers
                                          └─ layer.discard()  (ALIVE → DISCARDED)
```

After the finalizer returns, `verifyLayerWasFullySwept()` iterates all registered wrappers and asserts
none remain in the ALIVE state. If any do, `StaleTransactionMemoryException` is raised listing every
uncollected layer's class and `getId()`.
