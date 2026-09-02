# evitaDB storage engine — map for the embedded-fulltext-index design question

Scratchpad. All paths relative to repo root `/www/oss/evita/evitaDB-dev`.
Everything below is grounded in source read at the cited `file:line`. Claims are marked **(F)** for fact read
in source and **(I)** for my own inference; prose sections say "**Fact.**" where the whole paragraph is read
from source.

Status: COMPLETE. Line numbers spot-verified against the files after writing.

---

## 1. WAL (write-ahead log)

### 1.1 Where it lives

- `evita_store/evita_store_server/src/main/java/io/evitadb/store/wal/AbstractMutationLog.java` — the base class,
  2604 lines, holds all format/durability/rotation/recovery logic. Class JavaDoc at :105-137.
- Two concrete subclasses:
  - `CatalogWriteAheadLog` (`.../wal/CatalogWriteAheadLog.java:85`) — per-catalog WAL,
    typed `AbstractMutationLog<CatalogBoundMutation>`.
  - `EngineMutationLog` (`.../wal/EngineMutationLog.java`) — engine-level (catalog create/rename/drop) log.
  Discriminated by `WalKind.CATALOG` / engine — see `CatalogWriteAheadLog.java:169`.
- Per-transaction *isolated* WAL: `.../store/catalog/DefaultIsolatedWalService.java:39`
  (`IsolatedWalPersistenceService`), one instance per `TransactionContract`/UUID (:34).
  Created from `Catalog.createIsolatedWalService` —
  `evita_engine/src/main/java/io/evitadb/core/catalog/Catalog.java:1991`.

### 1.2 Two-level WAL: isolated → shared

**Fact.** A transaction first writes its mutations into a *private* buffer (off-heap, spilling to a file when big):
`DefaultIsolatedWalService` holds a `WriteOnlyOffHeapWithFileBackupHandle` (`DefaultIsolatedWalService.java:70-73`)
and Kryo-serializes each `Mutation` into it. At commit, that whole blob is copied into the shared catalog WAL
in one shot — `AbstractMutationLog.doAppend` :1396-1417 (`walReference.getBuffer()` → `walFileChannel.write`,
or `readChannel.transferTo` when it spilled to a file).

**Consequence for a fulltext index:** the WAL carries **logical mutations** (`EntityUpsertMutation`,
`EntityRemoveMutation`, schema mutations — see the type dispatch in
`CatalogWriteAheadLog.getWriteAheadLogVersionDescriptor` :252-271), *not* index deltas. Nothing in the WAL
describes index structure.

### 1.3 On-disk record format

From `AbstractMutationLog` constants (:140-171) and `doAppend` (:1314-1482), per transaction:

```
[ int32 contentLength ]                      TRANSACTION_PREFIX_SIZE = 4      (:144)
[ StorageRecord<TransactionMutation> ]       TRANSACTION_MUTATION_SIZE        (:148)
[ raw bytes of the isolated WAL blob ]       = transactionMutation.getWalSizeInBytes()
[ int64 cumulative CRC32C, little-endian ]   CUMULATIVE_CRC32_SIZE = 8        (:160)
```

A **finalized** (rotated-out) WAL file additionally ends with a tail of 3 longs — first CV in file, last CV in
file, final cumulative checksum: `WAL_TAIL_LENGTH = 8 + 8 + 8` (:171), written by `writeWalTailStatic` (:833-861).
The active file has **no** tail (see the `minimumRemainder` branch in `getFirstNonProcessedTransaction` :1604-1607).

Nesting note (**verified in source**, not inferred from the import): the individual mutations inside the blob
are themselves `StorageRecord`s. `DefaultIsolatedWalService.write(long catalogVersion, Mutation mutation)`
:169-214 wraps each mutation in `new StorageRecord<Mutation>(output, catalogVersion, false, theOutput ->
{ writeKryo.writeClassAndObject(output, mutationToWrite); ... })` (:207-213) — i.e. the same record framing,
per-record CRC and per-record compression as the data files, and each carries the catalog version as its
`generationId`. The same method also collects the transaction's conflict keys on the way through (:192-205).

A transaction **cannot span WAL files**: `doAppend` :1319-1329 throws `TransactionTooBigException` when
`walSizeInBytes > maxWalFileSizeBytes`. Rotation happens *before* the append if it would not fit (:1338-1343).

### 1.4 Checksums — narrower than the name suggests

**Fact, and this is unusually well documented in-source.** `AbstractMutationLog.checksum` JavaDoc :176-204:

- Each stored checksum is CRC32C of *every byte preceding it in the file*.
- Because that coverage includes the previous stored checksums, and `CRC(M ‖ CRC(M))` is the CRC **residue**
  (a constant), the running value **resets to that same constant at every stored checksum**.
- Detected: damage *inside* a transaction — torn write, hole from out-of-order writeback, bit rot.
- **Not** detected: whole transactions exchanged / duplicated / dropped / spliced from another WAL file.
- Ordering damage is caught instead by the **catalog-version continuity assert during replay** —
  `TransactionManager.java:1456-1464` (`transactionMutation.getVersion() == nextExpectedCatalogVersion`),
  with a comment at :1451-1455 explicitly saying this is the check that guards against reorder/dup/drop.

Verified the cited pinning test exists:
`evita_test/evita_functional_tests/src/test/java/io/evitadb/store/wal/CatalogWriteAheadLogTest.java:949`
`shouldNotDetectReorderedTransactions`.

### 1.5 fsync / durability policy

**Fact.** WAL channels are deliberately **not** opened with `DSYNC` by default — `openWalChannel` :791-802.
Instead one explicit `force(true)` per append: `forceDurable` :758-760, called from `doAppend` :1450-1452.
JavaDoc :728-757 gives the reasoning: an append issues three writes (head, content, checksum), so DSYNC cost
three device syncs; one explicit force cuts it to a third (measured on LUKS+xfs).

**The coupling worth knowing:** `requiresWriteOrdering(storageSettings)` :817-819 returns
`!storageSettings.computeCRC32C()`. If CRC32C is switched **off**, the checksum degrades to `Checksum.NO_OP`
whose comparison always answers "matches", so a hole would be replayed as real history — therefore the WAL
falls back to `DSYNC` to make holes impossible instead of detectable (JavaDoc :762-790). CRC32C is thus a
**durability knob**, not merely an integrity/perf knob.

**Group commit.** The append path is split:
- `append(...)` :1242-1247 → durable on return.
- `appendDeferringSync(...)` :1265-1270 → bytes in page cache only; caller owes a `syncWal()`.
- `syncWal()` :1281-1302 forces; **skipped entirely** when `requiresWriteOrdering` (already DSYNC-synced).

The commit stage uses the deferring variant and batches forces — see §2.

### 1.6 Replay after crash

**Fact.** Replay is driven from `TransactionManager.processTransactions` (:1358-1557):

1. `readFromVersion = max(lastFinalizedVersion + 1, 2)` (:1403).
2. Mutation stream obtained from the catalog:
   `getCommittedLiveMutationStream(readFromVersion, getLastDurableCatalogVersion())`
   when ALIVE (:1410-1412), or `getCommittedMutationStream(readFromVersion)` otherwise (:1414-1416).
   The ALIVE bound is the **durable** version, not the written one — comment :1405-1409: otherwise a round could
   checkpoint a version whose WAL bytes are only in page cache.
3. Each transaction is re-applied via `replayMutationsOnCatalog` (:1473-1478) inside a replay `Transaction`
   (:1469) that shares the `TransactionalLayerMaintainer` across the whole round (see `createTransaction` :320-327
   and `TransactionTrunkFinalizer.commit` :73-81 asserting the layer identity).
4. Resume point after a checkpoint: `AbstractMutationLog.getFirstNonProcessedTransaction(walReference)` :1580-1653.
   Notable: the search is **not** confined to the referenced file — it walks forward across rotated files
   (:1594-1600, and the `minimumRemainder` logic :1604-1607 that skips a finalized file holding only its tail).

Stream suppliers live in `evita_store/evita_store_server/src/main/java/io/evitadb/store/wal/supplier/`
(`MutationSupplier`, `ReverseMutationSupplier`, `TransactionLocations`, `TransactionMutationWithLocation`).

**Tail truncation on open.** `checkAndTruncate` (:1760, :1784), `truncateWalFileAndCalculateTail` (:1913),
`scanWalFileForLastCompleteTransaction` (:524), `checkFinalizedWalFile` (:1872). Corruption surfaces as
`WriteAheadLogCorruptedException` with a `WalKind`.

### 1.7 Truncation / purge

**Fact.** `walProcessedUntil(version)` :1674-1680 records the processed version and, if there are pending
removals, schedules `removeWalFileTask`. Rotating a file out calls the abstract `updateFirstVersionKept(long)`
(:2020), implemented in `CatalogWriteAheadLog` :295-310, which delegates to `historyHorizonAdvancer` — a
`LongConsumer` that "trims the bootstrap file and reclaims the data files that fall below the given catalog
version" (JavaDoc :91-98). WAL retention is explicitly **one of two independent floors**; the other is
`timeTravelSizeLimitBytes`, and clamping/ordering/idempotency live in the seam, not in the WAL.

WAL cache of transaction locations is dropped after 5 min inactivity: `CUT_WAL_CACHE_AFTER_INACTIVITY_MS`
:175, `cutWalCache()` :1990.

---

## 2. Commit process & isolation

### 2.1 Pipeline stages

`evita_engine/src/main/java/io/evitadb/core/transaction/stage/` — a `java.util.concurrent.Flow` pipeline:

1. **`ConflictResolutionAndWalAppendingTransactionStage`**
   (`.../stage/ConflictResolutionAndWalAppendingTransactionStage.java:82`)
   — `Flow.Processor<ConflictResolutionAndWalAppendingTransactionTask, TrunkIncorporationTransactionTask>`.
   Per task (`handleNext` :151-222):
   - `resolveConflicts(task, expectedCatalogVersion)` (:179) reserves the **next catalog version**
     (`getLastAssignedCatalogVersion() + 1`, :171) — so **one catalog version per committed transaction**,
     a gapless increasing sequence (class JavaDoc :62-64).
   - `appendToSharedWal(task, commitVersions)` (:187).
   - `updateLastWrittenCatalogVersion(...)` (:197).
   - The client is **deliberately not** notified here (:198-201) — the change is written but not durable.
   - `enqueueForDurability(...)` (:202-204) parks it on `pendingDurability`.
2. **Durability/sync sub-stage (group commit)** — same class. `pendingDurability` deque (:103) + `syncInFlight`
   flag (:118); `syncPendingTransactions` (:262-300) samples the queue tail **before** forcing
   (:268-272 — sampling after would acknowledge un-forced transactions), then `forceAndRelease` (:308-319)
   calls `transactionManager.syncWal()` and `updateLastDurableCatalogVersion(durableUpTo)`.
   `walDurabilityFailure` (:127) fail-stops the stage permanently on a failed force.
3. **`TrunkIncorporationTransactionStage`** (`.../stage/TrunkIncorporationTransactionStage.java:59`).
   `handleNext` :87-144 calls `transactionManager.processTransactions(...)` (:104-110) which **replays the WAL**
   onto the shared catalog (yes — the trunk path reads back from the WAL, it does not reuse in-memory state),
   then `propagateCatalogToSharedView` (:156-196).

### 2.2 Commit-behaviour milestones

`CommitBehavior` values seen in code: `WAIT_FOR_WAL_PERSISTENCE` (referenced in `AbstractMutationLog.doAppend`
comment :1447) and `WAIT_FOR_CHANGES_VISIBLE` (`TrunkIncorporationTransactionStage.java:94, :137, :169`).
So: **durable ≠ visible**, and clients can wait for either.

### 2.3 What "visible" means

**Fact.** `TransactionTrunkFinalizer.commitCatalogChanges` (`.../transaction/TransactionTrunkFinalizer.java:104-133`):

1. `this.catalogToUpdate.flush(catalogVersion, lastProcessedTransaction)` — **flush precedes merge** (:108).
2. `newCatalog = this.lastTransactionLayer.getStateCopyWithCommittedChanges(this.catalogToUpdate)` (:114)
   — produces a **brand-new immutable `Catalog` instance** from the transactional diff layer.
3. `verifyLayerWasFullySwept()` (:128) — asserts every diff layer was consumed.

Then `TransactionManager.propagateCatalogSnapshot(newCatalogVersion)` (:1576-1607) publishes it via
`newCatalogVersionConsumer`, under `catalogPropagationLock`. `livingCatalog` is an
`AtomicReference<Catalog>` (`TransactionManager.java:145`), set in `notifyCatalogPresentInLiveView` (:894-939).
Readers observe a version by `getLivingCatalog().getVersion()` — `waitUntilLiveVersionReaches` :1620-1627.

**So the isolation model is: one immutable `Catalog` object graph per catalog version, published by an atomic
reference swap.** That is snapshot isolation implemented as persistent/copy-on-write data structures, not
as per-record version chains.

### 2.4 The transactional-memory contract (this is the load-bearing one)

`evita_engine/src/main/java/io/evitadb/core/transaction/memory/`:

- `TransactionalLayerCreator<T>` (`TransactionalLayerCreator.java`) — `long getId()` + `T createLayer()`.
  The id must be globally unique across **all** live creators and must be drawn from
  `TransactionalObjectVersion.SEQUENCE` (JavaDoc on `getId()`); it is the sole key of the diff-layer registry.
- `TransactionalLayerProducer<DIFF_PIECE, COPY>` (`TransactionalLayerProducer.java`) extends
  `TransactionalLayerCreator<DIFF_PIECE>` + `TransactionalStateProducer<COPY>`, and requires
  `COPY createCopyWithMergedTransactionalMemory(DIFF_PIECE layer, TransactionalLayerMaintainer)`.
  The JavaDoc requires the merge be **deep** — nested producers must be resolved via
  `transactionalLayer.getStateCopyWithCommittedChanges(...)`.
- `VoidTransactionMemoryProducer<S>` (`VoidTransactionMemoryProducer.java`) extends only
  `TransactionalStateProducer<S>`: **owns no diff piece**, but must still implement
  `createCopyWithMergedTransactionalMemory` so it can rebuild itself out of its merged internals. Its JavaDoc
  is explicit that such objects are *deliberately* not `TransactionalLayerCreator`s — having no id they cannot
  be looked up in the registry at all, so they can never be handed another object's layer, and they skip the
  registry lookup during the merge cascade.
- Others in the package: `TransactionalMemory`, `TransactionalLayerMaintainer` (32.6 KB — the registry/driver),
  `UndoJournal`, `Snapshotable`, `DirtyScopeValidator`, `TransactionalStateProducer`, `TransactionalLayerEntry`,
  `TransactionalLayerState`, `TransactionalContainerChanges`, `TransactionalCreatorMaintainer`.

**Which level each participant sits at — verified, and the distinction matters:**

- `Catalog` — `TransactionalLayerProducer<DataStoreChanges, Catalog>` (`Catalog.java:193`, `createLayer()`
  :1640). **Owns a layer.**
- `AttributeIndex` — `TransactionalLayerProducer<AttributeIndexChanges, AttributeIndex>`
  (`.../index/attribute/AttributeIndex.java:115-117`). **Owns a layer.**
- `GlobalEntityIndex` — `VoidTransactionMemoryProducer<GlobalEntityIndex>`
  (`.../index/GlobalEntityIndex.java:79-80`). **Owns no layer** — it only rebuilds itself from merged internals.
- `EntityIndex` (abstract base) — `Index<EntityIndexKey>`, `AttributeIndexEditorContract`, `PriceIndexContract`,
  `Versioned`, `IndexDataStructure` (`.../index/EntityIndex.java:107-113`). Not a producer at all.
- `FilterIndex` — `IndexDataStructure, Serializable`, sealed and permitting `OwnerFilterIndex` / `FilterIndexView`
  (`.../index/attribute/FilterIndex.java:106-107`). Participation goes through the owner/view split.

`IndexDataStructure` (`.../index/IndexDataStructure.java:31-38`) is tiny — a single `resetDirty()`.

So an `EntityIndex` is a **container**: it does not own a diff layer itself; its mutable *fields* do
(`TransactionalBitmap entityIds`, `TransactionalMap<Locale, TransactionalBitmap> entityIdsByLanguage`,
`AttributeIndex attributeIndex` — `EntityIndex.java:122-139`), and the index merely rebuilds itself from their
merged forms.

**Read/write pattern** that every layer-owning structure must follow (JavaDoc of `TransactionalLayerCreator`):
`Transaction.getTransactionalMemoryLayerIfExists(this)` on read, `getOrCreateTransactionalMemoryLayer(this)` on
write — i.e. **every mutable structure must be able to answer queries against `base state + diff layer`
without materializing the merge**.

### 2.5 Dirty-part collection

`evita_engine/src/main/java/io/evitadb/core/buffer/` — `DataStoreChanges`, `TrappedChanges`,
`TransactionalDataStoreMemoryBuffer`, `WarmUpDataStoreMemoryBuffer`, `DataStoreReader`, `RingBuffer`.

`TrappedChanges.addChangeToStore(StoragePart)` (`TrappedChanges.java:103`) / `getTrappedChangesIterator()` (:120)
/ `addIterator(Iterator<StoragePart>, int)` (:165). Indexes emit their dirty parts into it:
`EntityIndex.getModifiedStorageParts(TrappedChanges)` —
`evita_engine/src/main/java/io/evitadb/index/EntityIndex.java:799`,
which "walks every registered component in deterministic order — each emits its own dirty storage part" (:801).

**The enrolment seam — this is the hook a new index would use.** It is a *pull* model with two halves:

1. **Enrolment.** `DataStoreChanges.getOrCreateIndexForModification(IK indexKey, Function<IK,I>
   accessorWhenMissing)` (`.../core/buffer/DataStoreChanges.java:529-568`) puts the index into the
   `dirtyEntityIndexes` map (:563) — and into `dirtyEntityIndexesByPk` when it is an `EntityIndex` (:564-566)
   — the *first time a mutation asks for it for modification*. Enrolment is undo-journaled (:541-561) so a
   savepoint rollback un-enrols it. Call sites: `EntityCollection.java:3092, :3158` and
   `Catalog.java:2688, :2700`, reached through the `DataStoreMemoryBuffer` interface (:53).
2. **Collection.** `DataStoreChanges.popTrappedUpdates()` (:279-…) resets the dirty maps (:289-290), then for
   every enrolled index calls `index.getModifiedStorageParts(trappedChanges)` (:296) followed by
   `index.notifyFlushed()` (:300), and finally folds in the directly-trapped parts (:302-307) and any
   deferred index-removal parts (:310-314).

The contract an index must satisfy is therefore just `io.evitadb.index.Index<T extends IndexKey>`
(`evita_engine/src/main/java/io/evitadb/index/Index.java:37-64`) — three members:
`T getIndexKey()`, `void getModifiedStorageParts(TrappedChanges)`, and a defaulted `void notifyFlushed()`
("the hook where an index may advance its change-detection baseline … invoked once per flush, right after
`getModifiedStorageParts`, so `getModifiedStorageParts` can stay a pure, idempotent read").

`Catalog.flush(catalogVersion, lastProcessedTransaction)` (`Catalog.java:1952-1982`):
- per collection `entityCollection.flush(catalogVersion)` (:1961),
- `persistenceService.flushTrappedUpdates(catalogVersion, dataStoreBuffer.popTrappedChanges(), ...)` (:1966-1970),
- `persistenceService.storeHeader(...)` (:1971-1979).
Skipped entirely when nothing changed (:1956-1965).

---

## 3. OffsetIndex & file storage

### 3.1 What it is

`evita_store/evita_store_key_value/src/main/java/io/evitadb/store/offsetIndex/OffsetIndex.java:142`
(`@ThreadSafe`). Class JavaDoc :105-138: an **append-only key-value store**. Nothing is ever overwritten;
dead data accumulates and is removed by compaction ("vacuuming").

- WRITE: append record at end of file, store returned `FileLocation` under the key in the root map (:122-125).
- READ: look up `FileLocation` by key, `RandomAccessFile` seek + read. Performance depends on **OS page
  cache** — the JavaDoc says so explicitly (:130-132).
- DELETE: remove the key from the root map; the removal is also recorded in the fragment (:133-137).

The key→location map itself is persisted as a chain of *fragments* at the end of the file, each pointing at
its predecessor (:113-120). Fragment size is bounded by `StorageOptions.outputBufferSize()`
(field at :165, default 2 MB per its JavaDoc).

### 3.2 MVCC by catalog version — this is real, at the storage layer

**Fact.** `OffsetIndex` keeps a **per-version registry of root maps**: `private record Roots(long
currentVersion, long[] versions, OffsetLocationChampMap[] locationRoots, Map<Byte,Integer>[] histograms,
long[] timestamps)` — `OffsetIndex.java:1833-1839`. Roots are CHAMP persistent maps and "each appended root
structurally shares the bulk of its predecessor, [so] retaining the whole per-version history is cheap"
(:1892-1893).

`get(long catalogVersion, long primaryKey, Class<T>)` :722-760 resolves against
`this.roots.floorRoot(catalogVersion)` — "the greatest retained version not exceeding catalogVersion, so its
entries already conform to that version … no separate historical reconstruction or generation filter is
needed" (:753-756).

Unflushed writes live in `VolatileValues` (:248, class at :2215) and are consulted first (:730-751);
reading one forces a `doSoftFlush()` (:739-741) or throws `RecordNotYetWrittenException`.
`forgetVolatileData()` :1247-1249 discards them (used when a trunk round fails —
`TransactionManager.java:1526`).

### 3.3 StoragePart kinds (the complete registry)

**Fact.** Types are registered by `byte` id through a `ServiceLoader` of `StoragePartRegistry` —
`.../offsetIndex/model/OffsetIndexRecordTypeRegistry.java` (constructor: `ServiceLoader.load(...)`,
`registerFileOffsetIndexType(byte id, Class<? extends StoragePart>)`; duplicate id or duplicate type is a
premise violation).

Three registries exist today:

- `evita_store/evita_store_entity/.../store/entity/service/EntityStoragePartRegistry.java` — ids **1-6**:
  `EntitySchemaStoragePart`(1), `EntityBodyStoragePart`(2), `AttributesStoragePart`(3),
  `AssociatedDataStoragePart`(4), `PricesStoragePart`(5), `ReferencesStoragePart`(6).
- `evita_store/evita_store_server/.../store/index/service/IndexStoragePartRegistry.java` — ids **20-46**:
  `EntityIndexStoragePart`(20), `UniqueIndexStoragePart`(21), `FilterIndexStoragePart`(22),
  `SortIndexStoragePart`(23), `ChainIndexStoragePart`(24), `AttributeCardinalityIndexStoragePart`(25),
  `PriceListAndCurrencySuperIndexStoragePart`(26), `PriceListAndCurrencyRefIndexStoragePart`(27),
  `HierarchyIndexStoragePart`(28), `FacetIndexStoragePart`(29), `CatalogIndexStoragePart`(30),
  `GlobalUniqueIndexStoragePart`(31), `ReferenceTypeCardinalityIndexStoragePart`(32),
  `GroupCardinalityIndexStoragePart`(33), `HistogramIndexStoragePart`(34), and **leaf-page parts**
  35-45 (`FilterIndexLeafPagePart`, `RangeIndexLeafPagePart`, `EntityIdsStoragePart`(37),
  `PriceListAndCurrencySuperIndexLeafPagePart`, `GlobalUniqueIndexLeafPagePart`, `UniqueIndexLeafPagePart`,
  `ReferenceTypeCardinalityIndexLeafPagePart`, `SortIndexLeafPagePart`, `ChainIndexLeafPagePart`,
  `HistogramIndexLeafPagePart`, `HistogramRangeIndexLeafPagePart`), `HistogramCardinalityStoragePart`(46).
- `evita_store/evita_store_server/.../store/catalog/service/CatalogStoragePartRegistry.java` — ids **50-52**:
  `CatalogHeader`(50), `EntityCollectionFileHeader`(51), `CatalogSchemaStoragePart`(52).

**Note the "LeafPagePart" family** — index B+ trees are persisted **page-granularly**, not as one blob per
index. That is the existing precedent for a large index structure that is written incrementally.

### 3.4 Record framing

`.../offsetIndex/model/StorageRecord.java` (a `record`, :64-69). Constants:

- `RECORD_LENGTH_CONTROL_SIZE = 4 + 1` (length int + control byte).
- `CRC_NOT_COVERED_HEAD = 5 + 8` (plus generationId long).
- `OVERHEAD_SIZE = 22 B` — length(int), control(byte), generationId(long), crc(long).
- Control-byte bits: `GENERATION_CLOSING_BIT=1`, `CONTINUATION_BIT=2` (record spans to the next record),
  `CRC32_BIT=3`, `COMPRESSION_BIT=4`.

So records may **span** (continuation bit) and are individually CRC'd and individually compressed.
`generationId` is the catalog version the record belongs to; `closesGeneration` marks the last record of a
generation. Unknown record types are treated as dead and skipped (`read(...)`, `payloadType == null` branch).

### 3.5 Serialization & compression

- **Kryo**, versioned. Per-collection factory:
  `DefaultEntityCollectionPersistenceService.VERSIONED_KRYO_FACTORY` (:170-180) chains
  `SchemaKryoConfigurer` → `SharedClassesConfigurer` → `IndexStoragePartConfigurer(keyCompressor)` →
  `EntityStoragePartConfigurer(keyCompressor)`. A `KeyCompressor` interns repeated keys.
- **Compression**: `StorageSettings` (`.../store/settings/StorageSettings.java`) picks
  `ZipCompressionFactory` when `StorageOptions.compress()` is true, else `CompressionFactory.NO_COMPRESSION`;
  and `Crc32CChecksumFactory` when `computeCRC32C()`, else `ChecksumFactory.NO_OP`.
- **DEFLATE level is 3, hard-coded and deliberately not configurable** —
  `.../store/compression/ZipCompressionFactory.java`, `COMPRESSION_LEVEL` field with a measurement table in
  its JavaDoc. Raw DEFLATE, NOWRAP. **Compression is per record** — the deflater is reset, fed one record's
  payload and finished in one `deflate()`, so no dictionary carries across records. Compression is applied
  only when it actually shrinks the record.
- Bootstrap file is special-cased: `StorageSettings.modifyForBootstrapFile()` forces `compress(false)` and
  `computeCRC32(true)` so records stay fixed-size.

### 3.6 The flush / checkpoint moment

Two distinct things, and conflating them is the classic mistake here:

**(a) Flush (per trunk round).** `Catalog.flush(catalogVersion, lastProcessedTransaction)`
(`Catalog.java:1952-1982`) → per-collection `flush` + `persistenceService.flushTrappedUpdates(catalogVersion,
dataStoreBuffer.popTrappedChanges(), ...)` + `storeHeader(...)`. This *writes bytes* for every dirty
StoragePart. Called from `TransactionTrunkFinalizer.commitCatalogChanges` :108, i.e. **before** the
transactional-memory merge (:114).

**(b) Checkpoint (interval-driven).** `.../store/catalog/CheckpointCoordinator.java:81`. Class JavaDoc
:45-77 is the authoritative statement:

> Decouples the cadence at which a trunk round makes its changes **visible** from the cadence at which they
> are made **durable on the data files**. … An acknowledged commit is durable because it is in the
> write-ahead log, not because the data files were checkpointed: the bootstrap record is a *checkpoint
> pointer*, and anything written after the last one is replayed from the WAL on restart.

Without it every round paid `N_changed + 2` device flushes (:49-53; measured at 57 % of the round with two
concurrent writers). Interval: `TransactionOptions.checkpointIntervalInMillis()`. Handles self-register via
`PendingSyncRegistry` (:66-67) so the force set cannot drift from the written set. A **ticker** covers an
idle catalog that would otherwise never checkpoint its last round (:72-76); it is a self-arming one-shot.
`Catalog.getLastPersistedCatalogVersion()` (:1944-1946) lags `getVersion()` by up to a whole interval.

So the durability chain is: **WAL force → (later) data-file force → bootstrap record**. The bootstrap record
is the pointer that says "everything below here is in the data files; replay the WAL from here".

---

## 4. Compaction

### 4.1 Trigger

**Fact.** Single shared decision function `DefaultCatalogPersistenceService.shouldCompact(...)`
(`.../store/catalog/DefaultCatalogPersistenceService.java:1196-1207`), deliberately shared by both trigger
sites (entity-collection flush :2312-2330 and catalog-file bootstrap :3864) so they cannot drift (JavaDoc
:1173-1176):

```java
fileBigEnough && (
    activeRecordShare < maxWasteActiveShare ||
    (activeRecordShare < minimalActiveRecordShare && minCompactionIntervalElapsed)
)
```

`fileBigEnough` = file size > `StorageOptions.fileSizeCompactionThresholdBytes()`.
`activeRecordShare` = `getTotalActiveSize() / fileSize` — `OffsetIndex.getActiveRecordShare(long)` :1237-1241.
Defaults (`minCompactionIntervalMilliseconds = 0`, `maxWasteActiveShare = minimalActiveRecordShare`) collapse
it to the original `fileBigEnough && activeRecordShare < minimalActiveRecordShare` (:1184-1186).

### 4.2 How it rewrites

`OffsetIndex.compact(Path newFilePath)` :1261-1271 → `copySnapshotTo(fos, null, roots.currentVersion())`.
JavaDoc :1251-1255: creates a **new file containing only records reachable from the latest root**; the
original file is **locked for writing but still readable**, remains unchanged, and "must be removed later
manually when the history is no longer needed".

So compaction is a **file-index increment**: `entityCollectionFileReference.incrementAndGet()`
(`DefaultEntityCollectionPersistenceService.java:997`) → new path → new
`EntityCollectionFileHeader`. Old file lingers until reclaimed. A reusable scratch buffer held through a
`SoftReference` avoids reallocating the 2 MB copy buffer per compaction
(`OffsetIndex.compactionScratchBuffer`, field at :174).

### 4.3 Dropping history / time travel

**Fact — time travel is a real, supported feature at the storage layer.**

- `.../store/catalog/TimeTravelRetention.java` — the survivor rules. Key invariant (JavaDoc): every index a
  record pins is monotonically non-decreasing, so "deleting the lowest-index file of *any* component kills a
  **prefix** of records, the reachable set is always a **suffix**, and history has exactly one horizon".
  `isCatalogDataFileObsolete(fileIndex, horizonCatalogFileIndex)` = `fileIndex < horizonCatalogFileIndex`.
  `isEntityCollectionFileObsolete(...)` handles the dropped-vs-not-yet-created ambiguity via the
  `lastEntityCollectionPrimaryKey` watermark.
- `.../store/catalog/ObsoleteFileMaintainer.java:77` (`implements CatalogConsumersListener, Closeable`).
  When time travel is enabled files are **not** removed immediately but kept until the WAL history is purged
  (:79); the async `purgeTask` (:89) is not even created in that mode (:183-185). An active-reader floor
  clamps the WAL-rotation purge so a file an active reader still needs is never deleted (:106-112).
- The horizon is advanced from two independent floors: WAL retention (`CatalogWriteAheadLog`'s
  `historyHorizonAdvancer`, §1.7) and a `timeTravelSizeLimitBytes` guard; `TimeTravelRetention.resolveHorizon`
  binary-searches the horizon because retained size is monotone non-increasing in it.

---

## 5. Entity body storage

### 5.1 One entity = several StorageParts

**Fact.** Reading one entity assembles it from separate parts —
`DefaultEntityCollectionPersistenceService` :239-330:

| Part | Id | Granularity |
|---|---|---|
| `EntityBodyStoragePart` | 2 | one per entity (PK, scope, parent, locales, associated-data keys, `sizeInBytes`) |
| `AttributesStoragePart` | 3 | **one per (entity, locale)** — see note below |
| `AssociatedDataStoragePart` | 4 | **one per (entity, associated-data key)** |
| `PricesStoragePart` | 5 | one per entity |
| `ReferencesStoragePart` | 6 | one per entity |

Note on `AttributesStoragePart`: the key is `EntityAttributesSetKey(entityPrimaryKey, locale)` and a `null`
locale denotes the global (non-localized) attribute set — see `AttributesStoragePart.java:264-273`.

Each is fetched **lazily and independently** through `dataStoreReader.fetch(catalogVersion, key, ...Class,
...::computeUniquePartId)` (:250, :274, :292, :310, :328) — driven by what the query's `EntityFetch`
requirement actually asked for. Parts already supplied by the caller (`storageParts` array) short-circuit
the fetch.

### 5.2 Bodies are on disk, not in RAM

**Fact.** `countEntities` / `isEmpty` count `EntityBodyStoragePart` records **in the OffsetIndex**
(:823-840), not in a heap collection. Reads go through `OffsetIndex.get(...)` → `RandomAccessFile` seek
(§3.1). There is no eager body load at boot (§6).

**Caching is a formula/entity result cache, not a body store.**
`evita_engine/src/main/java/io/evitadb/core/cache/CacheSupervisor.java:67`:
- `analyse(session, entityType, Formula)` — memoized *computation results*, keyed by
  `Formula.computeHash(...)` (xxHash3, :75-78).
- `analyse(session, primaryKey, entityType, ..., EntityFetch, Supplier<ServerEntityDecorator>, enricher)`
  :116-125 — an **entity-level** cache that avoids the physical fetch, but only after enough requests target
  that entity ("cooling"/eviction, class JavaDoc :46-63).
- Invalidation is by **transactional bitmap ids**: cached formulas keep `gatherTransactionalIds()` and are
  marked obsolete when an underlying bitmap is discarded; a cached result is used only when the session's
  transaction id falls inside the memoized validity span (:53-63). Implementations:
  `HeapMemoryCacheSupervisor`, `NoCacheSupervisor`.

---

## 6. Startup / recovery

### 6.1 Catalog load

**Fact.** `evita_engine/src/main/java/io/evitadb/core/catalog/Catalog.java` :440-548. For each entity type in
`catalogHeader.getEntityTypeFileIndexes()`:

1. Build the `EntityCollection` (:470-482), sized by `entityHeader.usedEntityIndexPrimaryKeys().size()`.
2. Load the global index by `entityHeader.globalEntityIndexPrimaryKey()` (:491-497).
3. Load **every** index in `entityHeader.usedEntityIndexPrimaryKeys()` (:498-520), each via
   `entityCollectionPersistenceService.readEntityIndex(catalogVersion, eid, schema)`.
4. Global indexes are attached first because "other indexes might look up in these indexes for data"
   (:522-536).

So: **all entity indexes are loaded eagerly and fully at catalog boot; entity bodies are not.** The load is
parallel/progress-reported (`ProgressingFuture`).

### 6.2 Indexes are DESERIALIZED, not re-indexed

**Fact — this is the answer to the brief's "verify".**
`DefaultEntityCollectionPersistenceService.readEntityIndex(long, int, EntitySchema)` :859-913:

1. Fetch the manifest `EntityIndexStoragePart` by the index PK (:860-866); a missing manifest is a premise
   violation, not a rebuild trigger.
2. Fetch the sibling `EntityIdsStoragePart` for the membership bitmaps; fall back to the manifest's legacy
   inline carrier for pre-2026.2 formats; reconcile version by `max` (:886-904).
3. Build a `LoadContext` record (:906-910) and run `resolvePlanFor(entityIndexKey.type()).run(context)`
   (:912) — `IndexReloadPlan` per subclass (:924-933).

The loaders live in `evita_engine/src/main/java/io/evitadb/index/component/loader/`:
`ComponentLoader`, `IndexReloadPlan`, `LoadContext`, `LoadedComponentBundle`, `AttributeIndexLoader`,
`HistogramIndexMapLoader`, `PriceSuperIndexLoader`, `PriceRefIndexLoader`, `FacetIndexLoader`,
`HierarchyIndexLoader`, `GroupCardinalityLoader`, `ReferenceTypeCardinalityLoader`,
`AttributeCardinalityIndexMapLoader`. `LoadContext` JavaDoc confirms it is "everything a `ComponentLoader`
needs to **reload one sub-index from persistent storage**" and that "the reload path is **not** on the hot
query path (catalog boot / restart only)".

**There is no re-indexing path in the engine.** Searched: no bulk reindex entry point exists; the only place
"reindex" appears as an action is the operator-facing remediation text in
`TransactionTrunkFinalizer.wrapPostReplayCorruption` (:150-158) — "restore the catalog from a backup, or
fully rebuild / reindex it" — i.e. rebuilding is something a human does by re-feeding data, not an engine
capability.

### 6.3 Recovery = load checkpoint + replay WAL

`Catalog.getLastPersistedCatalogVersion()` (:1944-1946) is the checkpointed version. Everything above it is
replayed from the WAL via `TransactionManager.processTransactions` (§1.6/§2.1). The replay applies the
**same logical mutations** through the **same mutation executors** as a live commit — see
`evita_engine/src/main/java/io/evitadb/index/mutation/local/EntityIndexLocalMutationExecutor.java` — so index
state after replay is produced by the ordinary indexing code, not by a special recovery path.

### 6.4 Index instance lifetime

Index instances live for the lifetime of the `Catalog` object they belong to, but a **new immutable `Catalog`
graph is produced per catalog version** by `getStateCopyWithCommittedChanges` (§2.3). Because the underlying
structures are persistent (CHAMP maps, transactional B+ trees in
`evita_engine/src/main/java/io/evitadb/index/bPlusTree/`), unchanged subtrees are shared between versions
rather than copied.

### 6.5 WARM_UP vs ALIVE

`Catalog.flush` asserts `CatalogState.ALIVE` (:1953). Two buffer implementations:
- `WarmUpDataStoreMemoryBuffer` — bulk load; flushes at session close "to avoid persistence of large indexes
  with each update (which would drastically slow initial bulk database setup)"; the persistence service may
  be **swapped** on compaction, which is unique to warm-up.
- `TransactionalDataStoreMemoryBuffer` — reads/writes target the transactional memory layer first, falling
  back to `StoragePartPersistenceService.getStoragePart(...)`; the whole buffer instance is exchanged on
  commit.

---

## Verdicts

Hard invariants any embedded fulltext index would have to obey. **F** = fact read in source,
**I** = my inference.

1. **(F) Every mutation reaches the WAL before it is applied to the shared catalog — and the WAL carries
   logical mutations only.** Stage 1 appends, stage 3 *reads the WAL back* to apply
   (`ConflictResolutionAndWalAppendingTransactionStage.handleNext` :151-222 →
   `TrunkIncorporationTransactionStage.handleNext` :87-144 → `TransactionManager.processTransactions`
   :1358-1557 which iterates `getCommittedLiveMutationStream(...)`). The WAL record body is the isolated
   WAL blob of Kryo-serialized `Mutation`s (`DefaultIsolatedWalService`, `AbstractMutationLog.doAppend`
   :1396-1417). *Nothing index-shaped is ever written to the WAL.*

2. **(F) A committed transaction is acknowledged as durable on the WAL force, not on any data-file write.**
   `CheckpointCoordinator` JavaDoc :59-62. Consequently the data files (and therefore any index artifact
   living beside them) are allowed to lag the acknowledged version by up to
   `checkpointIntervalInMillis`, and the gap is closed by replay.

3. **(F) Exactly one catalog version per committed transaction, gapless and strictly +1.** Assigned at
   `ConflictResolutionAndWalAppendingTransactionStage.java:171`; enforced on replay by
   `TransactionManager.java:1456-1464`. That assert — not the WAL checksum — is what detects reordered,
   duplicated or dropped transactions (`AbstractMutationLog.checksum` JavaDoc :176-204).

4. **(F) Readers see immutable snapshots keyed by catalog version, published by one atomic reference swap.**
   `TransactionTrunkFinalizer.commitCatalogChanges` :114 builds a new `Catalog`;
   `TransactionManager.propagateCatalogSnapshot` :1576-1607 publishes it;
   `livingCatalog` is an `AtomicReference<Catalog>` (:145). Storage-layer reads are equally version-keyed:
   `OffsetIndex.get(catalogVersion, ...)` resolves against `roots.floorRoot(catalogVersion)` (:753-760).
   **An index that cannot answer "as of version V" cannot be read consistently by an older still-open
   session.**

5. **(F) To participate in a transaction, a structure must sit at one of exactly two levels, and both end in
   producing a new immutable copy per commit.** Either it owns a diff layer —
   `TransactionalLayerProducer<DIFF, COPY>`, requiring a globally unique `getId()` drawn from
   `TransactionalObjectVersion.SEQUENCE`, a `createLayer()` diff piece and a **deep**
   `createCopyWithMergedTransactionalMemory(...)` — or it owns none and merely rebuilds itself from its
   merged internals (`VoidTransactionMemoryProducer<S>`). Reads on a layer-owning structure must answer from
   *base + diff layer* without materializing the merge (`TransactionalLayerCreator` JavaDoc pattern).
   The merge is verified exhaustive by `verifyLayerWasFullySwept()` (`TransactionTrunkFinalizer.java:128`)
   and by merge-time `DirtyScopeValidator` checks (`TransactionTrunkFinalizer.java:112-122`).
   See §2.4 for which class sits where — note that `GlobalEntityIndex` is a *Void* producer, so the
   natural home for a new index's diff layer is the sub-structure level (`AttributeIndex` is the model to
   copy), not the `EntityIndex` container.
   **(I)** A Lucene `IndexWriter` fits neither level: it is a single writer with its own commit point, its
   own `DirectoryReader` lifecycle and its own segment-visibility rules; there is no diff piece it could
   hand to `TransactionalLayerMaintainer`, and no way to produce a "copy with merged memory" cheap enough to
   do per commit. Its natural unit of isolation (a reopened reader after an `IndexWriter.commit()`) is also
   coarser than one catalog version and is not addressable *by* catalog version.

6. **(F) Index state must be reconstructible from StorageParts persisted at checkpoint — and only from
   those.** All 27 index part kinds are registered ids 20-46
   (`IndexStoragePartRegistry`); catalog boot loads every index in
   `usedEntityIndexPrimaryKeys` via `readEntityIndex` (`Catalog.java:493, :505`), which **deserializes**
   through `IndexReloadPlan`/`ComponentLoader` (`DefaultEntityCollectionPersistenceService.java:859-913`).
   **There is no re-indexing path.** Therefore an embedded fulltext index has exactly two options: be
   expressible as StorageParts inside the OffsetIndex, or be a **side artifact** with its own
   persistence, its own crash recovery and its own version pinning.

7. **(I, strong) A Lucene-format index cannot be replay-derived state.** Replay reconstructs everything
   above the checkpoint by re-applying the mutation stream
   (`TransactionManager.processTransactions` :1443-1500). Any structure whose content is not a
   *deterministic function of that stream* cannot be part of replay-derived state. Lucene segment layout,
   merge scheduling and internal doc ids are not deterministic across replays (nor across a
   `ConcurrentMergeScheduler` re-run). Evidence pointer is the replay path itself; the non-determinism claim
   is mine, from Lucene's design, not from this repo. **Consequence:** a Lucene index would have to be a
   side artifact keyed by catalog version, with its own commit point reconciled against evitaDB's — which
   is a second durability protocol, not an extension of the existing one.

8. **(F) New StoragePart kinds are cheap to add; the id space is a `byte` and is ~75 % free.** Add a
   `StoragePartRegistry` service implementation with unused ids (used: 1-6, 20-46, 50-52), plus a Kryo
   serializer registered in the collection's configurer chain
   (`DefaultEntityCollectionPersistenceService.java:170-180`). Not a blocker — but see the
   `serialVersionUID`/BWC-reader discipline the project applies to every persisted format change.

9. **(F) Records are individually compressed and individually CRC'd, at DEFLATE level 3, with no
   cross-record dictionary.** `StorageRecord` control bits `CRC32_BIT`/`COMPRESSION_BIT`;
   `ZipCompressionFactory.COMPRESSION_LEVEL = 3` with per-record reset. **(I)** For a postings-list-shaped
   payload this is materially worse than what Lucene achieves with its own block codecs, and there is no
   seam here for a custom per-part codec — the compressor is chosen once by `StorageSettings`, globally.

10. **(F) Reads of persisted parts are `RandomAccessFile` seeks served by the OS page cache, and the file
    is append-only with periodic full-file compaction.** `OffsetIndex` JavaDoc :105-138; compaction rewrites
    into a new file index and leaves the old one for the retention horizon
    (`OffsetIndex.compact` :1261-1271, `TimeTravelRetention`, `ObsoleteFileMaintainer`). **(I)** An index
    that mmaps its own files (Lucene's `MMapDirectory`) would sit entirely outside this lifecycle: nothing
    in `TimeTravelRetention`'s prefix/suffix reachability argument would cover it, so its file reclamation
    would need to be wired into the horizon seam explicitly or it would leak.

11. **(F) There is a narrow, well-defined seam for adding an index, and index B+ trees already use it
    page-granularly** (leaf-page StorageParts, ids 35-45). The whole contract is
    `io.evitadb.index.Index<T extends IndexKey>` (`.../index/Index.java:37-64`): `getIndexKey()`,
    `getModifiedStorageParts(TrappedChanges)`, `notifyFlushed()`. An index enrols itself lazily on first
    modification via `DataStoreChanges.getOrCreateIndexForModification`
    (`.../core/buffer/DataStoreChanges.java:529-568`, enrolment undo-journaled at :541-561) and is drained
    per commit by `popTrappedUpdates()` (:296, :300),
    which `Catalog.flush` :1966-1970 hands to `flushTrappedUpdates`. See §2.5 for the full chain.
    **(I)** This is the shape an in-house fulltext index would naturally take: a transactional B+ tree /
    CHAMP structure whose dirty pages become StorageParts, enrolled through the same seam. It fits every
    invariant above with no new machinery — which is the strongest argument for in-house over embedding a
    foreign index engine, because Lucene can use *none* of it (verdicts 5, 7, 10).

12. **(F) A failure between flush and merge is treated as unrecoverable-by-retry.**
    `TransactionManager.processTransactions` :1524-1538 suspends rather than retries once `collectingVersion
    >= 0`, because the retry would diff the next flush against baselines a failed flush left behind.
    `TransactionTrunkFinalizer.wrapPostReplayCorruption` :150-158 is the poison-pill message.
    **(I)** Any fulltext index participating in the flush must therefore make its own write step
    idempotent-or-fatal in the same way; a "best effort, fix it next round" index write would violate the
    engine's existing failure contract.

### Open questions I did not resolve

- Exactly how `flushTrappedUpdates` orders writes across collections vs the catalog file, and whether a new
  part kind can be written outside a collection's OffsetIndex. (The *producer* side of this chain — enrolment
  and drain — is now traced in §2.5; it is only the store-side write ordering that remains open.)
- Whether `CatalogIndexStoragePart` (id 30, catalog-scope index) offers a natural home for a catalog-wide
  fulltext structure — I saw the id and the class but did not read its contents.
- The `evita_traffic_engine` module (untouched) and whether it shares the OffsetIndex machinery.
