# Bug: `forgetPageStream()` on ChainIndex PAGED→SINGLE collapse throws during async commit-flush

**Component:** `evita_engine` — transactional memory / granular storage of `ChainIndex`
**Affected versions:** `2026.2-SNAPSHOT` and `2026.2.RC1-SNAPSHOT` (repo HEAD `005d291e6`, version bumped to RC1 at `fd2cde048`). Not fixed by RC1.
**Severity:** High — a *successfully applied* transaction is silently discarded on the background trunk-incorporation thread. The client-side `session.upsertEntity(...)` / `deleteEntity(...)` calls return without error; the loss only surfaces later as a `GenericEvitaInternalError` on an Evita worker thread, after which the catalog state is wrong (removed entities reappear / stale, and follow-up transactions cascade into `ConflictingCatalogMutationException` / `InvalidMutationException` / `UniqueValueViolationException`).

---

## 1. Symptom — the exception

Thrown on an `Evita-transaction-*` worker thread (the async trunk-incorporation stage), **not** the thread that called commit:

```
ERROR io.evitadb.core.transaction.TransactionManager: Error while committing transaction: 3.
io.evitadb.exception.GenericEvitaInternalError: Transaction is already committed / rolled back, no new transactional memory layer may be created at this time!
	at io.evitadb.utils.Assert.isPremiseValid(Assert.java:90)
	at io.evitadb.core.transaction.memory.TransactionalLayerMaintainer.getOrCreateTransactionalMemoryLayer(TransactionalLayerMaintainer.java:160)
	at io.evitadb.core.transaction.memory.TransactionalMemory.getOrCreateTransactionalMemoryLayer(TransactionalMemory.java:124)
	at io.evitadb.core.transaction.Transaction.getOrCreateTransactionalMemoryLayer(Transaction.java:250)
	at io.evitadb.index.array.UnorderedLookupTree$LeafNode.setPageSequence(UnorderedLookupTree.java:2734)
	at io.evitadb.index.array.UnorderedLookupTree.forgetPageStream(UnorderedLookupTree.java:1026)
	at io.evitadb.index.array.TransactionalUnorderedIntArray.forgetPageStream(TransactionalUnorderedIntArray.java:623)
	at io.evitadb.index.attribute.ChainIndex.doAppendStorageParts(ChainIndex.java:896)
	at io.evitadb.index.attribute.ChainIndex.appendStorageParts(ChainIndex.java:814)
	at io.evitadb.index.attribute.AttributeIndex.getModifiedStorageParts(AttributeIndex.java:1314)
	at io.evitadb.index.component.AttributeIndexComponent.collectModifiedStorageParts(AttributeIndexComponent.java:77)
	at io.evitadb.index.EntityIndex.getModifiedStorageParts(EntityIndex.java:859)
	at io.evitadb.core.buffer.DataStoreChanges.popTrappedUpdates(DataStoreChanges.java:217)
	at io.evitadb.core.buffer.TransactionalDataStoreMemoryBuffer.popTrappedChanges(TransactionalDataStoreMemoryBuffer.java:233)
	at io.evitadb.core.collection.EntityCollection.flush(EntityCollection.java:1890)
	at io.evitadb.core.catalog.Catalog.flush(Catalog.java:1863)
	at io.evitadb.core.transaction.TransactionTrunkFinalizer.commitCatalogChanges(TransactionTrunkFinalizer.java:107)
	at io.evitadb.core.transaction.TransactionManager.lambda$commitChangesToSharedCatalog$0(TransactionManager.java:301)
	at io.evitadb.core.transaction.Transaction.executeInTransactionIfProvided(Transaction.java:168)
	at io.evitadb.core.transaction.TransactionManager.commitChangesToSharedCatalog(TransactionManager.java:296)
	at io.evitadb.core.transaction.TransactionManager.processTransactions(TransactionManager.java:1161)
	at io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.handleNext(TrunkIncorporationTransactionStage.java:100)
	at io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.handleNext(TrunkIncorporationTransactionStage.java:57)
	at io.evitadb.core.transaction.stage.AbstractTransactionStage.onNext(AbstractTransactionStage.java:103)
	... SubmissionPublisher$BufferedSubscription / ObservableThreadExecutor worker thread ...
```

The exception is caught by `AbstractTransactionStage` and logged, so the caller only observes that the whole page/transaction "failed to commit"; the mutations that had already been applied in-memory are thrown away.

---

## 2. Root cause

### The invariant
`TransactionalLayerMaintainer.commit()` sets, as its **first** action:

```java
// TransactionalLayerMaintainer.java:302-309
void commit() {
    // no new transactional memories may happen
    this.allowTransactionalLayerCreation = false;   // line 304
    this.finalizer.commit(this);
}
```

From that point on, `getOrCreateTransactionalMemoryLayer` must never be asked to *create* a layer that does not already exist — the guard at line 160:

```java
// TransactionalLayerMaintainer.java:149-173
public <T> T getOrCreateTransactionalMemoryLayer(@Nonnull TransactionalLayerCreator<T> layerCreator) {
    ...
    if (transactionalMemoryWrapper != null) {
        ...
        return transactionalMemoryWrapper.getItem();   // OK: layer already exists → allowed
    }
    Assert.isPremiseValid(
        this.allowTransactionalLayerCreation,          // line 160-163: THROWS when false
        "Transaction is already committed / rolled back, no new transactional memory layer may be created at this time!"
    );
    transactionalMemory = layerCreator.createLayer();  // creating a NEW layer
    ...
}
```

### The violation
The commit path calls `Catalog.flush()` **after** `commit()` has flipped the flag (via `TransactionTrunkFinalizer.commitCatalogChanges` → `Catalog.flush` → `EntityCollection.flush` → `...getModifiedStorageParts` → `ChainIndex.appendStorageParts`).

When a `ChainIndex`'s element tree has grown to the **PAGED** shape and this transaction shrinks it back below the single-leaf threshold, `doAppendStorageParts` takes the **PAGED→SINGLE collapse** branch:

```java
// ChainIndex.java:888-897  (doAppendStorageParts, SINGLE branch)
for (final int freedPageSequence : this.pageStreamRegistry.livePageSequences(ELEMENTS_PAGE_STREAM)) {
    sink.addChangeToStore(new ChainIndexLeafPageRemoval(entityIndexPrimaryKey, streamKey, freedPageSequence));
}
this.pageStreamRegistry.forget(ELEMENTS_PAGE_STREAM);
this.elements.forgetPageStream();                       // line 896  ← trigger
sink.addChangeToStore(buildSingleStoragePart(entityIndexPrimaryKey));
```

`forgetPageStream()` iterates **every leaf** — not only the leaves this transaction touched — and mutates each one:

```java
// UnorderedLookupTree.java:1023-1029
public void forgetPageStream() {
    requirePaged();
    for (final LeafNode leaf : collectLeaves()) {
        leaf.setPageSequence(PagedLeafHandle.UNASSIGNED_PAGE_SEQUENCE);   // line 1026
        leaf.clearDirty();
    }
}
```

Both `LeafNode.setPageSequence` and `LeafNode.clearDirty` route their write through the transactional layer, **lazily creating one on first touch**:

```java
// UnorderedLookupTree.java:2732-2740
void setPageSequence(int pageSequence) {
    final LeafNode layer = this.transactionalLayer ?
        Transaction.getOrCreateTransactionalMemoryLayer(this) : null;   // line 2734: create-on-first-touch
    if (layer == null) { this.pageSequence = pageSequence; }
    else { layer.pageSequence = pageSequence; }
}
// clearDirty() at 2755-2763 does the same via getOrCreateTransactionalMemoryLayer(this).
```

For any leaf that was **not** already touched earlier in the same transaction, this is its *first* touch — and it happens after `allowTransactionalLayerCreation` is already `false`. `getOrCreateTransactionalMemoryLayer` therefore trips the premise assertion at line 160 and aborts the flush.

### One-line summary
> A commit-time / flush-time bookkeeping operation (`forgetPageStream` on a PAGED→SINGLE chain-index collapse) creates new per-leaf transactional memory layers **after** `commit()` has forbidden layer creation, because it walks *every* leaf (including untouched ones) through the write path `getOrCreateTransactionalMemoryLayer` instead of a create-free path.

---

## 3. Why `Predecessor` / ordered attributes are the tell

`ChainIndex` backs **chain-ordered / `Predecessor` attributes** (e.g. an entity's sortable `order` attribute with `OrderBehaviour` predecessor semantics). Only mutations that grow a chain index to PAGED and then collapse it hit this path — which is why the failing scenarios are all *remove-and-recreate* / *remove-variant* operations on entities carrying an `order`/predecessor attribute. Collections without a chain-indexed attribute never reach `forgetPageStream`.

---

## 4. Live confirmation (JDWP)

Reproduced under a debugger against `2026.2.RC1-SNAPSHOT` built from source:

- Caught a background thread **`Evita-transaction-26`** suspended exactly at `ChainIndex.doAppendStorageParts (ChainIndex.java:896)`, inside the chain
  `TransactionTrunkFinalizer.commitCatalogChanges → Catalog.flush → EntityCollection.flush → DataStoreChanges.popTrappedUpdates → AttributeIndex.getModifiedStorageParts → ChainIndex.appendStorageParts` — i.e. layer mutation running on the async trunk-incorporation stage, after commit.
- Confirmed `TransactionalLayerMaintainer.getOrCreateTransactionalMemoryLayer` reaches line 160 with `transactionalMemoryWrapper == null` (no pre-existing layer for the leaf → it would create one).
- The `allowTransactionalLayerCreation` field is **private**, so debugger expression conditions on it must use reflection (`getDeclaredField("allowTransactionalLayerCreation").setAccessible(true)`), not a bare field reference.

---

## 5. How to reproduce

### 5a. Minimal evita-core reproduction (preferred — write this as the regression test)
Construct a collection with a **chain-ordered / `Predecessor` sortable attribute**, then:

1. Open the catalog in transactional (alive) mode.
2. In one or more committed transactions, insert enough entities so that the attribute's `ChainIndex` element tree grows to the **PAGED** shape (more than one leaf page — i.e. it crosses the single-leaf capacity so `elements.isRootInternal()` becomes true). Make sure there are **multiple leaves**, at least one of which will remain *untouched* by the collapsing transaction below.
3. In a **single new transaction**, remove enough of those entities (or their ordered attribute) that the chain index shrinks back below the single-leaf threshold → on commit, `doAppendStorageParts` takes the SINGLE branch and calls `elements.forgetPageStream()`.
4. Commit and let the trunk-incorporation stage run the flush.

**Expected (correct):** the transaction commits; the chain index persists as SINGLE.
**Actual (bug):** `GenericEvitaInternalError: "Transaction is already committed / rolled back..."` on the `Evita-transaction-*` worker thread; the transaction's changes are discarded.

The key stressor is that `forgetPageStream` walks **every** leaf via `collectLeaves()`, so the repro must ensure at least one leaf whose page-sequence/dirty bookkeeping was **not** already decoupled into a layer earlier in the same transaction (otherwise the `wrapper != null` fast path at line 152 hides the bug).

### 5b. End-to-end reproduction via EdeeShop (the environment where it was found)
Runs today, reproduces on both `dev` and this branch, against the local `2026.2.RC1-SNAPSHOT`:

```bash
# infra
docker compose -f development/docker/junit/docker-compose.yml up -d   # MySQL 127.0.0.5:3307 + Redis
export JAVA_17_HOME="$JAVA_HOME"                                       # Surefire forks from it

cd /www/edee/eshop
mvn -o test -pl lib_eshop_integration_tests \
    -DrunSuite='**/ProductVariantServiceWithPublishingTest.java'
```

Result: `Tests run: 4, Failures: 2, Errors: 1`. The failing methods —
`shouldRemoveVariant`, `shouldRemoveVariantAndCreateNewOnItsFoundations`, `shouldPublishMasterWithoutPriceAndVariantWithPrice` — all remove/recreate product **variants**, whose `order` (`Predecessor`) attribute is chain-indexed. The removed variant stays present / stale in Evita because the collapsing transaction was discarded.

(`ProductAliasVariantTest` and `EvitaUrlUniquenessSchemaChurnTest` show the same underlying error via the downstream cascade.)

To attach a debugger to the embedded Evita inside the forked test JVM, add `-Dmaven.surefire.debug` (opens `:5005`, `suspend=y`) and attach.

---

## 6. Suggested fix direction (for the implementing agent to evaluate)

The flush-time `forgetPageStream` needs to reset per-leaf page bookkeeping **without** creating new transactional layers, since by flush time the transaction is committed and (a) untouched leaves have no diff to record and (b) the committed baseline is what will be persisted anyway. Options to weigh:

1. **Create-free write path for flush-time bookkeeping.** Give `LeafNode.setPageSequence` / `clearDirty` (and `forgetPageStream`) a variant that, when no layer exists, writes the baseline field directly instead of calling `getOrCreateTransactionalMemoryLayer` — mirroring how `getTransactionalMemoryLayerIfExists` (read path) already tolerates a missing layer. A leaf with no layer has no pending diff, so writing its baseline `pageSequence = UNASSIGNED` / `dirty = false` in place is safe at this point.
2. **Skip untouched leaves.** `forgetPageStream` only needs to reset leaves that actually carry page bookkeeping this commit; leaves with no layer and an already-`UNASSIGNED` page sequence / clear dirty flag can be skipped entirely, avoiding the create.
3. **Re-extend the layer for the flush window.** There is precedent: `TransactionalLayerMaintainer.extendTransaction()` (line 289) re-enables `allowTransactionalLayerCreation`. If layer creation *is* legitimately needed during this flush step, the flush must run inside such an extended window — but option 1/2 (not creating at all) is likely the cleaner intent, since the other index families' `getModifiedStorageParts` do not create layers at flush time.

Whichever path: the invariant "no layer creation after `commit()`" is correct and should stay; the bug is that a flush-time reset is using a create-on-write accessor for a read-mostly reset over the full leaf set. Confirm the same issue does not exist in the sibling reset/emit paths of the SORT/FILTER indexes (they appear not to `forgetPageStream` over all leaves, but verify).

---

## 7. Key source locations

| File | Line(s) | Role |
|------|---------|------|
| `TransactionalLayerMaintainer.java` | 302-309 (`commit()`), 304 | sets `allowTransactionalLayerCreation = false` |
| `TransactionalLayerMaintainer.java` | 149-173, **160** | throw site (`Assert.isPremiseValid`) |
| `TransactionalLayerMaintainer.java` | 289 (`extendTransaction`) | re-enables creation (precedent for a fix) |
| `TransactionTrunkFinalizer.java` | 103-120, **107** | `catalogToUpdate.flush(...)` after commit |
| `ChainIndex.java` | 848-898, **896** | `doAppendStorageParts` SINGLE branch → `elements.forgetPageStream()` |
| `UnorderedLookupTree.java` | 1023-1029 | `forgetPageStream()` walks **all** leaves |
| `UnorderedLookupTree.java` | 2732-2740 (`setPageSequence`), 2755-2763 (`clearDirty`) | create-on-first-touch layer writes |
| `TransactionalUnorderedIntArray.java` | 623 | `forgetPageStream()` delegate |
