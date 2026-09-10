# 06 — Transaction pipeline: what the "transaction executor" really does

Analysis only, against branch `1518-upgrade-to-jdk-21` (worktree `/www/oss/evita/jdk21-upgrade`), 2026-09-08.
Every claim carries a `file:line`. Paths are relative to the worktree root. Abbreviations used below:

- `TM` = `evita_engine/.../core/transaction/TransactionManager.java`
- `Stage1` = `evita_engine/.../core/transaction/stage/ConflictResolutionAndWalAppendingTransactionStage.java`
- `Stage2` = `evita_engine/.../core/transaction/stage/TrunkIncorporationTransactionStage.java`
- `AbstractStage` = `evita_engine/.../core/transaction/stage/AbstractTransactionStage.java`
- `DCPS` = `evita_store/evita_store_server/.../store/catalog/DefaultCatalogPersistenceService.java`
- `AML` = `evita_store/evita_store_server/.../store/wal/AbstractMutationLog.java`
- `CPR` = `evita_api/.../api/CommitProgressRecord.java`
- `OTE` = `evita_engine/.../core/executor/ObservableThreadExecutor.java`

Where a JDK contract is cited, the machine has no `src.zip` and no network, so the wording is the published JDK 21
javadoc as recalled, and the project's own restatement of the same contract is cited next to it.

## Executive summary

- **The commit pipeline is two `Flow` stages plus one self-re-dispatching WAL sync task, all on the transaction
  pool.** `TM.createTransactionalPublisher` wires `txPublisher -> Stage1 -> Stage2` with one `SubmissionPublisher`
  each, `maxBufferCapacity = server.transactionThreadPool.queueSize` (default 100, rounded up to 128 by the JDK) and
  an *unrejectable* wrapper over the transaction executor (`TM:1997-2019`).
- **Each stage is single-consumer and strictly sequential by construction, not by lock.** Every stage subscribes
  with `request(1)` and re-requests one item only after `handleNext` returns (`AbstractStage:90-114`). The
  `Flow.Subscriber` contract invokes a subscriber's methods in strict sequential order per subscription. The TM's
  javadoc says so and calls the fair `walAppendingLock` a timeout gate, not a serialiser (`TM:250-258`).
- **The pipeline occupies at most three transaction-pool threads at once per catalog**: the Stage1 consumer, the
  Stage2 consumer, and the WAL sync task (`Stage1:251-261, 270-308`). Stage1 of tx N+1 does overlap Stage2 of tx N.
  Nothing else in the pipeline fans out. There are no retries onto the pool: `retryTransactionProcessing` only
  schedules a service-pool drain task (`TM:707-711`).
- **`submit()` is never used; commit never parks the caller.** `TM.commit` uses `offer(task, onDrop)` and the drop
  handler fails the commit with "Conflict resolution transaction queue is full!" (`TM:666-691`). The same
  non-blocking `offer` links Stage1 to Stage2 (`AbstractStage:174-191`). Back-pressure is fail-fast, never park.
- **Ordering guarantees (WAL order, catalog-version monotonicity) come from the single-consumer contract plus
  asserted counters, and are executor-independent.** The four `ReentrantLock`s in the TM exist for timeouts,
  cross-thread races with background tasks, and defence; none of them makes the stages serial.
- **Blocking I/O inside the pipeline is modest and well isolated**: WAL append (page-cache write, optional file
  copy) on Stage1, one `force(true)` per *batch* on the sync task, data-file writes plus bootstrap fsync on Stage2
  (often deferred to a 1 s checkpoint ticker on the *service* pool). The ADR measurement says disk I/O was ~1 % of
  both hot threads and fsync ~7 ms per transaction against a multi-second trunk phase; trunk re-apply is ~38 % of
  application CPU (`documentation/adr/2026-07-27-write-path-performance-tuning/README.md:86, 121-135, 170-172`).
- **Thread-bound state is real and the pipeline depends on it**: `Transaction.CURRENT_TRANSACTION` is a
  `ThreadLocal` bound around every replay and merge on the Stage2 thread (`Transaction.java:75, 121-186`;
  `TM:371, 2034`), and B+ tree page emission is *confined by consequence* to the binding thread
  (`AbstractTransactionalBPlusTree.java:711-737`). `WarmUpSavepoint.CURRENT` is a `ThreadLocal` on the thread that
  performs a warm-up upsert (`WarmUpSavepoint.java:101-105, 129`). Kryo and output buffers are pooled or
  single-owner, not thread-local.
- **The pool size encodes no correctness constraint for the pipeline** (three threads suffice); it *does* bound
  how many REST/GraphQL mutations, engine lifecycle operators, warm-up flushes and catalog loads run concurrently,
  and it is shared with tasks that **block waiting for other transaction-pool tasks** (see the executor table).
- **The primary parked platform thread is the session closer**, not a pipeline thread: embedded, REST and GraphQL
  callers `join()` the commit future with no timeout (`EvitaSessionContract.java:346-362`); gRPC does not park at all
  (`EvitaSessionService.java:1058-1090`). The join is bounded only by the dangling-commit sweep,
  `max(60 s, 5 x waitForTransactionAcceptanceInMillis)` (`TM:1725-1727`).
- **Request and transaction pools are separate, but write requests do not uniformly land on the transaction
  pool**: gRPC runs every session call including `upsertEntity` on the *request* pool
  (`EvitaSessionService.java:1825`); REST and GraphQL route mutations to the *transaction* pool
  (`EndpointExecutionContext.java:154-157`, `AsyncDataFetcher.java:140-150`).

## Commit path diagram (ALIVE catalog, default `WAIT_FOR_CHANGES_VISIBLE`)

Thread names are the `Evita-<pool>-N` daemon threads created by `EvitaThreadFactory` (`OTE:1131-1153`);
`[caller]` is whatever thread runs the session. Citations sit on the line below the step they belong to.

```
[caller]  session.close() = closeNow(behaviour).toCompletableFuture().join()
   |         EvitaSessionContract.java:346-362           <-- the parked platform thread (embedded/REST/GraphQL)
   |      EvitaSession.closeInternal                     EvitaSession.java:1884-2002
   |        try (transaction) { validateCatalogSchema }  EvitaSession.java:1968-1977
   |        -> Transaction.close()                       Transaction.java:496-519
   |           -> TransactionalMemory.commit()           TransactionalMemory.java:91
   |              -> TransactionWalFinalizer.commit()    TransactionWalFinalizer.java:126-189
   |                 validateDirtyScopesBeforeCommit     :131   (CPU)
   |                 close per-tx OffsetIndex/off-heap   :159
   |                 catalog.commitWal(...)              Catalog.java:2439-2463
   |                    -> TM.commit(...)                TM:645-692
   |                       suspension gate               TM:655-668
   |                       txPublisher.offer(task,drop)  TM:666-691   (never blocks; drop => TransactionException)
   |        closedFuture = commitProgress.on(behaviour)  EvitaSession.java:1988-1990
   v
   (caller parks on CompletableFuture.join)
                                  |
   ============ SubmissionPublisher buffer #1 (cap = queueSize, pow2) ============
                                  v
[Evita-transaction-A]  Stage1.onNext -> handleNext       AbstractStage:96-114 ; Stage1:151-230
   |  resolveConflicts                                   Stage1:592-647
   |     TM.identifyConflicts                            TM:992-1088   (conflictResolutionLock.tryLock(budget))
   |        ring-buffer scan (CPU)                       TM:1024-1032
   |        fallback: WAL read (BLOCKING-IO)             TM:1036-1043 -> TM:1121-1165
   |     reserve version, registerPendingCommitProgress  Stage1:606, 626-628
   |     complete(WAIT_FOR_CONFLICT_RESOLUTION, reqExec) Stage1:630-635   --> [Evita-request-*]
   |  appendToSharedWal                                  Stage1:661-704
   |     TM.appendWalAndDiscard                          TM:1333-1370   (walAppendingLock.tryLock(budget))
   |        Catalog.appendWalAndDiscardDeferringSync     Catalog.java:2833-2840
   |          DCPS.doAppendWalAndDiscard                 DCPS:2833-2837   (walWriteLock)
   |            AML.doAppend                             AML:1330-1420   (BLOCKING-IO: write/transferTo, no force)
   |  updateLastWrittenCatalogVersion                    Stage1:204
   |  enqueueForDurability + scheduleSync                Stage1:209-211, 237-261
   |     executor.execute(syncPendingTransactions) ----> [Evita-transaction-B]   (only if no sync in flight)
   |  subscription.request(1)                            AbstractStage:113
   v
[Evita-transaction-B]  syncPendingTransactions           Stage1:270-308
   |  sample tail version; TM.syncWal -> AML.syncWal     Stage1:316-321 ; AML:1281-1300, 758-760
   |     (walSyncLock; channel.force(true))              (BLOCKING-IO: fsync, one per batch)
   |  updateLastDurableCatalogVersion                    Stage1:321
   |  releaseDurableTransactions (append order)          Stage1:340-382
   |     complete(WAIT_FOR_WAL_PERSISTENCE, reqExec)     Stage1:355-360   --> [Evita-request-*]
   |     push(TrunkIncorporationTask) = offer(...)       Stage1:367-377 ; AbstractStage:174-191
   |  loop while queue non-empty; clear syncInFlight     Stage1:272-306
   v
   ============ SubmissionPublisher buffer #2 (cap = queueSize, pow2) ============
                                  v
[Evita-transaction-C]  Stage2.onNext -> handleNext       Stage2:87-144
   |  already finalized? waitUntilLiveVersionReaches     Stage2:89-98 ; TM:1670-1696  (spin 4096, park 100 us)
   |  TM.processTransactions(v, flushFreq, alive, wait)  TM:1387-1599
   |     trunkIncorporationLock.lock()                   TM:1403-1405
   |     getCommittedLiveMutationStream(from,lastDurable) TM:1433-1435   (BLOCKING-IO: WAL read + Kryo)
   |     loop: createTransaction(replay=true)            TM:1497
   |           replayMutationsOnCatalog                  TM:1497-1502 -> TM:2028-2100   (CPU; bound tx)
   |           lastTransaction.close()                   TM:1507
   |        greedy while next tx written and             TM:1516-1521 ; TM:391-400
   |               elapsed < flushFrequencyInMillis
   |     commitChangesToSharedCatalog (bound tx)         TM:1537-1538 -> TM:365-388
   |        TransactionTrunkFinalizer.commitCatalogChanges TransactionTrunkFinalizer.java:96-125
   |           Catalog.flush(version, lastTx)            Catalog.java:2772-2800
   |              per collection EntityCollection.flush  EntityCollection.java:2368-2380   (BLOCKING-IO)
   |              DCPS.flushTrappedUpdates (catalog)     DCPS:4069-4118
   |              DCPS.storeHeader (checkpointLock)      DCPS:2325-2460
   |                 build bootstrap; not due -> DEFER   DCPS:2424-2428   (no fsync)
   |                 else writeCatalogBootstrap          DCPS:2431 -> DCPS:5258-5330   (fence + bootstrap fsync)
   |           getStateCopyWithCommittedChanges (merge)  TransactionTrunkFinalizer.java:105   (CPU)
   |     updateLastFinalizedCatalog                      TM:1539-1543
   |     waitUntilLiveVersionReaches(prev finalized)     TM:1584
   |  propagateCatalogToSharedView                       Stage2:156-196
   |     TM.propagateCatalogSnapshot                     TM:1605-1636   (catalogPropagationLock.tryLock(0))
   |        Evita.replaceCatalogReference (memory swap)  Evita.java:1782-1800
   |        queued schema mutations -> Evita.applyMutation TM:1614-1622 ; EngineTransactionManager.java:386-388
   |                                                       (engineStateLock.tryLock(300 s))
   |     complete(WAIT_FOR_CHANGES_VISIBLE, reqExec)     Stage2:168-172   --> [Evita-request-*]
   |     registry.completeChangesVisibleInRange(v, live) Stage2:177-182 ; PendingCommitProgressRegistry.java:142-167
   v
[Evita-request-*]  CPR.enqueueCompletion                 CPR:210-225, 438-467
   |     = completionSequencer.thenRunAsync(stage.complete, requestExecutor)
   |  stage.complete(versions) fires listeners:          EvitaSession.java:379-381 ; 2040-2085
   |     terminationSequence (session termination callback, traffic-recording close, schema event)
   v
[caller]  join() returns CommitVersions
```

Side actors not on the diagram:

- `walDrainingTask` (service scheduler, 1 s delay) runs
  `processTransactions(lastDurable, flushFrequency, alive=true, waitForLock=false, noOp)` on a **service-pool**
  thread when Stage2 dropped a task (`TM:514-519, 1925-1948`). It never propagates the catalog to the live view;
  only Stage2 does (`rg newCatalogVersionConsumer.accept` hits only `TM:1608`).
- `pendingProgressSweepTask` (service scheduler, `max(5 s, acceptance/2)`) fails records older than
  `safetyDeadlineMs()` (`TM:521-529, 1962-1965`).
- `CheckpointCoordinator` ticker (service scheduler) performs the deferred fsync fence and bootstrap write under
  `checkpointLock` (`CheckpointCoordinator.java:129, 222-227, 407-423`; `DCPS:400, 4189-4203`).

## Everything that runs on the transaction executor

Class: CPU = compute; BLOCKING = file I/O or fsync; ORCH = orchestration that itself parks or joins.
"Waits on tx-pool task?" answers whether the task, while holding a pool thread, blocks until *another task that
also needs a transaction-pool thread* completes. One record per kind of work.

- **Stage1 consumer** (conflict check, version reservation, WAL append)
  - Entry: `TM:2004-2009`, `Stage1:151-230`
  - Class: CPU + BLOCKING (WAL write; WAL read on ring-buffer overflow, `TM:1036-1043`)
  - Serialised by: `Flow.Subscriber` one-at-a-time per subscription (`AbstractStage:92, 113`); locks are timeout
    gates (`TM:250-258`)
  - Waits on tx-pool task? No. `offer` into Stage2 is non-blocking (`AbstractStage:175`); `executor.execute` of
    the sync task is fire-and-forget (`Stage1:254`)
- **WAL sync task** (`force(true)` and release of durable transactions)
  - Entry: `Stage1:254, 270-308`
  - Class: BLOCKING (fsync)
  - Serialised by: `syncInFlight` CAS admits one at a time (`Stage1:118, 252`); `walSyncLock` against rotation
    (`AML:228-242, 1290`)
  - Waits on tx-pool task? No
- **Stage2 consumer** (WAL replay, flush, merge, live-view swap)
  - Entry: `TM:2010-2014`, `Stage2:87-144`
  - Class: CPU (dominant) + BLOCKING (WAL read, data-file writes, bootstrap fsync unless deferred) + WAIT
    (`waitUntilLiveVersionReaches`, `engineStateLock.tryLock`)
  - Serialised by: `Flow.Subscriber` contract; `trunkIncorporationLock` also excludes the service-pool drainer
    (`TM:1403-1408, 1925-1935`)
  - Waits on tx-pool task? Only indirectly: `engineStateLock.tryLock` up to 300 s while an engine operator (which
    runs on this pool) holds it (`EngineTransactionManager.java:386, 308`; the go-live drain holds it, ADR go-live
    §Option A)
- **Warm-up session-close flush**: one nested `ProgressingFuture` per entity collection, then the combine step
  - Entry: `EvitaSession.java:1925, 1959`; `Catalog.java:2519-2617`; `ProgressingFuture.java:254-270, 408-418`
  - Class: BLOCKING (OffsetIndex writes, per-file fsync, bootstrap fsync on every flush)
  - Serialised by: nothing beyond each collection's own persistence service; N collections run on N pool threads
  - Waits on tx-pool task? The combine is `CompletableFuture.allOf(...).thenApply`, so no thread parks inside it.
    The **caller** may: `Catalog.java:3192-3194, 3242-3244, 3424-3426` do `flushFuture.execute(unrejectable tx
    executor); flushFuture.join()` from schema operations in warm-up
- **Engine lifecycle operators** (create/drop/rename/go-live/deactivate/upgrade/restore)
  - Entry: `Evita.java:564-566` (`engineExecutor == transactionExecutor`), `EngineTransactionManager.java:138-141,
    746-756`
  - Class: ORCH + BLOCKING (engine WAL, folder operations)
  - Serialised by: `engineStateLock`, engine-wide (`EngineTransactionManager.java:155`)
  - Waits on tx-pool task? **Yes.** Go-live builds a nested warm-up flush future and its combine runs `goLive()`
    (`MakeCatalogAliveMutationOperator.java:268-272`); the drain before it parks under `engineStateLock` on
    `allOf(closeNow(WAIT_FOR_WAL_PERSISTENCE) futures).get(remaining)`, i.e. on Stage1 plus the sync task, for
    up to `DRAIN_GIVE_UP_TIMEOUT_MILLIS` = 5 s (`SessionRegistry.java:357-460, 497-521, 133`; ADR go-live)
- **Initial catalog loading at boot, and the post-upgrade retry**
  - Entry: `Evita.java:655-667`, `Evita.java:1438-1444`
  - Class: BLOCKING (disk load) + CPU
  - Serialised by: none
  - Waits on tx-pool task? No (the retry's `join()` at `Evita.java:1420-1427` runs on the *service* executor,
    `Evita.java:1446`)
- **Catalog termination on shutdown**
  - Entry: `Evita.java:2162-2185` (`closedFuture.execute(unrejectable executor)`)
  - Class: BLOCKING
  - Serialised by: `Catalog.terminationLock` (`Catalog.java:2091`)
  - Waits on tx-pool task? No
- **REST mutation handlers** (upsert, delete, delete-by-query, entity/catalog schema updates, create/delete/update
  catalog)
  - Entry: `EndpointExecutionContext.java:154-157`; `UpsertEntityHandler.java:134`, `DeleteEntityHandler.java:68`,
    `DeleteEntitiesByQueryHandler.java:79`, `UpdateCatalogSchemaHandler.java:90`,
    `UpdateEntitySchemaHandler.java:94`, `CreateCatalogHandler.java:62`, `DeleteCatalogHandler.java:68`,
    `UpdateCatalogHandler.java:72`
  - Class: CPU (mutation into the session's diff layer; in warm-up straight into indexes)
  - Serialised by: none (per session)
  - Waits on tx-pool task? **Yes**, two ways. (a) The handler chain's `whenComplete` closes the execution context
    on the completing thread, and `RestEndpointExecutionContext.closeSessionIfOpen` calls `session.close()`, which
    `join()`s the commit (`EndpointHandler.java:129-141`, `RestEndpointExecutionContext.java:165-169`,
    `EvitaSessionContract.java:346-362`). (b) A warm-up schema update `join()`s an engine operator
    (`EvitaSession.java:2117-2126`) and a warm-up `createEntitySchema` joins a flush future
    (`Catalog.java:3192-3194`)
- **GraphQL mutation data fetchers**
  - Entry: `AsyncDataFetcher.java:98-146` (`WriteDataFetcher` -> `evita.getTransactionExecutor()`)
  - Class: CPU
  - Serialised by: none
  - Waits on tx-pool task? **Yes**: `EvitaSessionManagingInstrumentation.instrumentExecutionResult` calls
    `evitaSession.close()` (a `join`) on the thread that completes the mutation result, i.e. the transaction-pool
    thread that ran the fetcher (`EvitaSessionManagingInstrumentation.java:91-100`)
- **gRPC restore-catalog upload chunk handling**
  - Entry: `EvitaManagementService.java:843`
  - Class: BLOCKING (file write)
  - Serialised by: none
  - Waits on tx-pool task? No
- **Commit-completion notifications — NOT on this pool.** `CPR.enqueueCompletion` runs on the **request** executor
  handed in by every `complete(...)` call (`Stage1:359, 634`; `Stage2:96, 139, 171, 181`; `TM:154-156`;
  `Catalog.java:751-759` passes `evita.getRequestExecutor()`). Serial per record via `completionSequencer`
  (`CPR:172-197, 210-225`).
- **gRPC session calls including `upsertEntity` / `deleteEntity` — NOT on this pool.** `EvitaSessionService.java:1825`
  and every other `executeWithClientContext(..., this.evita.getRequestExecutor(), ...)` site (`:650-1464`).

Note on the "unrejectable" wrapper: `ProgressingFuture.unrejectableExecutor` marks runnables so the pool's
`GrowAwareRejectionHandler` force-enqueues them past `queueSize` instead of rejecting
(`ProgressingFuture.java:147-156`; `OTE:1258-1274, 1222-1224`). It does **not** create a thread beyond
`maxThreadCount`; an unrejectable task waits in the unbounded backlog until a worker frees up (`OTE:208-232`,
`OTE:1200-1212`). That is what turns every "Yes" above into a pool-starvation hazard when all `maxThreadCount`
workers are parked in a `join()` whose completion needs a pool thread.

## Guarantees: what comes from where

**From the `SubmissionPublisher` / `Flow.Subscriber` contract (executor-independent):**

- One `onNext` at a time per subscription. The JDK `Flow.Subscriber` javadoc states that its methods are invoked in
  strict sequential order for each subscription; `SubmissionPublisher` delivers through one consumer task per
  subscription on the supplied executor and gives every subscriber an independent buffer. The TM restates it: "The
  appending stage is a `Flow` subscriber that requests one task at a time and `SubmissionPublisher` delivers to a
  subscriber serially, so there is only ever one thread appending" (`TM:250-258`). The stages additionally
  self-throttle with `request(1)` after each item (`AbstractStage:92, 113`), so at most one buffered item is ever
  "in hand".
- FIFO per subscriber buffer, so **WAL order = version-assignment order = Stage2 replay order**. Stage1 assigns
  versions from a single counter on its single thread (`Stage1:171, 606`; `TM.getNextCatalogVersionToAssign`),
  appends in that order (asserted at `TM:1347-1352`), and the sync task releases in append order
  (`Stage1:340-382`). Stage2 asserts strict `+1` continuity (`TM:1478-1487`); `updateLastWritten`,
  `updateLastDurable` and `updateLastFinalized` assert monotonicity (`TM:800-820, 839-853, 876-896`).
- `offer` semantics: an item is dropped, not blocked, when a subscriber buffer is saturated, and the `onDrop`
  handler decides on a single retry. Both call sites return `false` from the handler after failing the record
  (`TM:683-690`; `AbstractStage:177-189`), so a saturated pipeline **rejects at admission** and a saturated Stage2
  buffer **loses a durable transaction's client notification** (the WAL still has it; the drainer re-incorporates,
  `TM:1912-1948`). `maxBufferCapacity` is rounded up to a power of two by the JDK, so the effective capacity for
  the default `queueSize = 100` is 128.

**Consequence for a virtual-thread executor:** `SubmissionPublisher` accepts any `Executor`. Because the
serialisation is per-subscription inside the publisher (a consumer task is started only when none is active) and
not a property of the thread, swapping the executor for `Executors.newVirtualThreadPerTaskExecutor()` preserves the
one-consumer-at-a-time and FIFO guarantees. What changes is *which* thread runs consecutive `onNext` calls: today it
is already "any pool thread" (the consumer task is re-dispatched after the buffer drains), so nothing in the stages
may assume thread identity across items. See the thread-bound-state section for the one place that assumes thread
identity *within* an item.

**From locks (and what each one is really for):**

- `conflictResolutionLock` (fair), `TM:248, 1004, 1082-1084`: acceptance-timeout gate for a transaction that spent
  its budget queuing; never contended in practice (single Stage1 thread).
- `walAppendingLock` (fair), `TM:258, 1345, 1366-1368`: same; the javadoc says so explicitly.
- `trunkIncorporationLock` (fair), `TM:262, 1403-1408, 1594-1596`: real exclusion between Stage2 (`lock()`) and the
  service-pool WAL drainer (`tryLock(0)`); also makes a Stage2 task wait for a drainer already running.
- `catalogPropagationLock` (fair), `TM:266, 1607, 1632-1634`: `tryLock(0)`, throws `TransactionTimedOutException` if
  anything else is propagating; defensive.
- `DCPS.walWriteLock`, `DCPS:386, 2837`: guards WAL handle creation and the append; sync is deliberately outside it
  (`DCPS:2802-2806`).
- `AML.walSyncLock`, `AML:228-242, 1290, 2219`: force versus rotation/close of the channel.
- `DCPS.checkpointLock`, `DCPS:392-400, 2341`: Stage2's `storeHeader` versus a ticker/backup checkpoint on the
  service pool.
- `DCPS.bootstrapWriteLock`, `DCPS:382, 5265`: bootstrap-file writer exclusion (leaf lock).
- `EngineTransactionManager.engineStateLock`, `EngineTransactionManager.java:155, 386-388`: engine-wide; Stage2
  takes it when a committed transaction carried schema mutations (`TM:2089, 1610-1622`).
- `Catalog.terminationLock` (`synchronized`), `Catalog.java:2091`: terminate once.
- `Stage1.pendingDurabilityLock`, `Stage1:114, 238-243, 274-283, 343-351`: appender versus syncer over the
  group-commit deque.

None of these is `synchronized` on a hot path except `Catalog.terminate` and `CPR.enqueueCompletion`
(sub-microsecond append, `CPR:203-210`). Every `ReentrantLock` above is a `java.util.concurrent` lock, which
virtual threads unmount on, so none pins a carrier. The `synchronized (this.pendingRemovals)` in the WAL purge
path (`AML:2051`) runs on a service-pool task.

**Catalog-version publication is guarded by asserts, not locks:** `notifyCatalogPresentInLiveView` asserts the
live view never regresses and never outruns `lastFinalized` (`TM:923-947`); `updateLastFinalizedCatalog` asserts
`+1` ordering and logs the bookkeeping invariant (`TM:876-919`).

## Blocking sections inside the pipeline

One record per step, in pipeline order.

- **Conflict ring-buffer scan** — Stage1 — CPU — `TM:1024-1032`. Ring buffer default 65 536 keys
  (`TransactionOptions.java:148`).
- **Conflict fallback, read older transactions from the WAL** — Stage1 — BLOCKING-IO — `TM:1036-1043`,
  `TM:1121-1165` (`getCommittedLiveMutationStream`). Only when the ring buffer aged out the window.
- **Lock acquisition with remaining acceptance budget** — Stage1 — WAIT, bounded by 20 s default —
  `TM:1004`, `TM:1345`; budget `TransactionOptions.java:113`. Uncontended in practice.
- **WAL append**: serialize `TransactionMutation` (Kryo from pool), `FileChannel.write`, then either write the
  off-heap buffer or `transferTo` from the isolated WAL file; rotate the file at 16 MiB — Stage1 — BLOCKING-IO
  (page cache, no force) — `AML:1330-1420`, `AML:1339-1343`; `DCPS:2833-2837`. The isolated WAL lives off-heap
  or spills to a temp file (`OffHeapMemoryManager.java:113-135`;
  `TransactionalStoragePartPersistenceService.java:65-72`).
- **WAL force** (`channel.force(true)`), one per batch — sync task — BLOCKING-IO (fsync) — `Stage1:316-321`;
  `AML:1281-1300, 758-760`. Skipped entirely when the channel is `DSYNC` (`AML:1283-1289`). ADR: fsync ~7 ms per
  transaction on the measured box.
- **Client notification (WAL persisted)** — sync task, hops to the request pool — ORCH — `Stage1:355-360`;
  `CPR:210-225`. Never blocks the sync task.
- **`trunkIncorporationLock.lock()`** — Stage2 — WAIT, unbounded but only against the drainer — `TM:1403-1405`.
- **Read WAL mutations for replay** (Kryo from pool) — Stage2 — BLOCKING-IO — `TM:1433-1435`; the stream is
  bounded by `lastDurable`.
- **Replay every mutation into the trunk's transactional layer** — Stage2 — CPU, dominant — `TM:1497-1502`,
  `TM:2028-2100`. ADR: "trunk re-apply, ~38 % of remaining application CPU"; greedy batching up to
  `flushFrequencyInMillis` = 10 s (`TransactionOptions.java:130`; `TM:1516-1521`).
- **Per-collection flush**: `popTrappedChanges` plus OffsetIndex writes, plus per-file fsync unless a
  `PendingSyncRegistry` defers it — Stage2 — BLOCKING-IO — `Catalog.java:2779-2783`;
  `EntityCollection.java:2368-2380`; `WriteOnlyFileHandle.java:245-249, 352`. Flush precedes merge; the ADR's Key
  technical details say that ordering is load-bearing.
- **Catalog-level `flushTrappedUpdates`** — Stage2 — BLOCKING-IO — `Catalog.java:2786-2790`; `DCPS:4069-4118`.
- **`storeHeader`**: collection headers, catalog header, build bootstrap — Stage2 under `checkpointLock` —
  BLOCKING-IO — `DCPS:2325-2431`.
- **Bootstrap publication**: `forcePendingSyncs` fence, bootstrap `checkAndExecuteAndSync` (fsync), retirement
  release — Stage2 **or** the service-pool ticker — BLOCKING-IO (fsync x N changed files + 1) — `DCPS:2424-2431`,
  `DCPS:5258-5330`; `CheckpointCoordinator.java:52-85, 261, 407-423`. Deferred when `checkpointIntervalInMillis`
  (default 1 000 ms, `TransactionOptions.java:147`) has not elapsed and `syncWrites` is on. The coordinator's
  javadoc quotes the measurement: the fixed `N_changed + 2` flushes were 57 % of a round at two writers and nothing
  at sixty-four.
- **Merge** (`getStateCopyWithCommittedChanges`) plus `verifyLayerWasFullySwept` — Stage2 — CPU —
  `TransactionTrunkFinalizer.java:105, 118`. ADR: small-tx visibility median ~301 ms, big-tx ~2 511 ms after tuning.
- **`waitUntilLiveVersionReaches(previous)`** — Stage2 — WAIT: spin 4 096 x `onSpinWait`, then a
  `parkNanos(100 us)` loop, deadline `max(60 s, 5 x acceptance)` — `TM:1584, 1670-1696, 1725-1727`. A virtual
  thread parks fine here; the spin phase burns a carrier briefly.
- **`propagateCatalogSnapshot`**: memory swap, then `engineStateLock.tryLock(transactionTimeout = 300 s)` for
  queued schema mutations — Stage2 — CPU / WAIT — `TM:1605-1636`; `EngineTransactionManager.java:386, 308`;
  `ServerOptions.java:96`. The engine operator itself is then submitted to the tx pool, not joined
  (`EngineTransactionManager.java:746-756`).
- **Client notification (changes visible) plus range fan-out** — Stage2, hops to the request pool — ORCH —
  `Stage2:168-182`.

Which dominates: from the write-path ADR, Stage2's CPU work (replay plus merge) dominates by orders of magnitude
over the I/O on both hot threads ("disk I/O is ~1 % of both hot threads and fsync is ~7 ms/tx against a
multi-second trunk phase", `2026-07-27-write-path-performance-tuning/README.md:86`). The ADR also records that
tuning `syncWrites`, `flushFrequencyInMillis` and `minimalActiveRecordShare` were measured no-ops for that workload
and says to revisit only on I/O-constrained hardware. No fresher numbers exist in the tree; the dataset was deleted
(`README.md:174-176`).

## Thread-bound state in the pipeline

**`Transaction.CURRENT_TRANSACTION` (`ThreadLocal<Transaction>`, `Transaction.java:71-75`).** The field's own
javadoc says the binding "could move to a `ScopedValue` once the project targets Java 21+".

Binding sites (`bindTransactionToThread` / `unbindTransactionFromThread`, `Transaction.java:522-540`, always via
`executeInTransactionIfProvided`, `Transaction.java:121-186`):

- Every session business method in an ALIVE read-write session (the isolated run): request thread (gRPC,
  embedded) or transaction-pool thread (REST/GraphQL mutation) — `EvitaSession.java:2411-2458`.
- Replay of each WAL transaction into the trunk: Stage2 thread — `TM:2034`.
- Flush plus merge of the trunk batch: Stage2 thread — `TM:371` (`commitChangesToSharedCatalog`).
- Unbind on close: the same thread as the last bind — `Transaction.java:517` (`CURRENT_TRANSACTION.remove()`).

Rules the code enforces or relies on:

- At most one transaction per thread; binding a different one throws (`Transaction.java:524-528`). Nested binds of
  the same transaction are no-ops that do not unbind (`Transaction.java:529-533, 181-183`).
- The binding must be present on the thread that *performs* the flush: the B+ tree's paged-leaf emission reads
  `currentState()` through the thread-local and would "emit no pages at all" on a foreign thread; the comment
  names this "confinement by consequence, not by an asserted invariant" and requires a combined `(values, peek)`
  publish before anything moves it off the writer's thread (`AbstractTransactionalBPlusTree.java:711-737`). Stage2
  satisfies it because `Catalog.flush` runs *inside* `executeInTransactionIfProvided` (`TM:371-388` ->
  `TransactionTrunkFinalizer.java:100`). The ALIVE flush is therefore **synchronous on the Stage2 thread**; the
  warm-up flush is asynchronous and fans out per collection, but in warm-up there is no transaction to bind
  (`EvitaSession.java:1901-1904`; `Catalog.java:2538-2546`).
- The `Evita` constructor refuses a direct executor for the transaction pool even in tests "because it uses thread
  local variables for transaction management" (`Evita.java:464-471`), i.e. the pool boundary is what keeps a
  caller's binding from leaking into pipeline work and vice versa.
- Hot-path cost of the thread-local is documented: `ThreadLocal` machinery is 5.25 % of a profile, so callers
  resolve the transaction once and pass it down (`Transaction.java:331-354`; `UnorderedLookupTree.java:325`;
  `TransactionalObjectBPlusTree.java:2940, 3045`).

**`WarmUpSavepoint.CURRENT` (`ThreadLocal<WarmUpSavepoint>`, `WarmUpSavepoint.java:129`).** Opened and closed by
`LocalMutationExecutorCollector` around each root entity mutation (`LocalMutationExecutorCollector.java:436-445`;
`WarmUpSavepoint.java:267-275, 636-641`) on **whatever thread performs the warm-up upsert**: the request thread for
gRPC/embedded, a transaction-pool thread for REST/GraphQL. The class javadoc justifies the thread-local by
`WARMING_UP` being "contractually single-threaded — a catalog being bulk loaded has exactly one writer", and warns
that a savepoint leaked on a thread makes the next entity on that thread fail its `open()` as nested
(`WarmUpSavepoint.java:101-105`; `LocalMutationExecutorCollector.java:430-435`). The ADR records the same
constraint (`2026-08-26-warm-up-per-entity-mutation-atomicity.md:643-644`).

**Other per-thread state touched by the write path** (from the `ThreadLocal` inventory of main code):

- Kryo instances for WAL and bootstrap: `com.esotericsoftware.kryo.util.Pool<Kryo>` obtain/free
  (`AML:216-218, 1345, 1480`; `DCPS:5263, 5327`). A pool, not thread-local; safe on any thread.
- Kryo read instances for OffsetIndex: `FileOffsetIndexKryoPool` (`OffsetIndex.java:220, 403-405`). A pool.
- WAL output buffer per WAL file: single owner, the appender (`AML:1352-1354`; `CurrentMutationLogFile#getOutput`).
  Relies on one appender at a time, guaranteed by the stage contract.
- Data-file `ObservableOutput` per target file: keyed cache with a **lease flag that asserts, never waits**
  (`ObservableOutputKeeper.java:108-112, 190-221, 290-320`). A double lease throws; impossible today because
  there is one flusher per file (Stage2, or one warm-up collection future).
- Off-heap WAL outputs: free-list (`ConcurrentLinkedDeque`), `ObservableOutputKeeper.java:247-273`. No affinity.
- Off-heap regions for the isolated WAL: `AtomicReferenceArray` slots, non-blocking `Optional`
  (`OffHeapMemoryManager.java:113-135`); spill to file (`WriteOnlyOffHeapWithFileBackupHandle.java:488`). No waits.
- `EntitySchemaContext.ENTITY_SCHEMA_SUPPLIER`: `ThreadLocal<Deque<EntitySchema>>` set around Kryo
  (de)serialization (`EntitySchemaContext.java:47, 58`; `EntityCollection.java:3741-3768`;
  `DataStoreChanges.java:380`). Scoped set/restore around a call; runs on Stage2, request and pool threads.
- `CatalogSchemaStoragePart.CATALOG_ACCESSOR`: `ThreadLocal<CatalogContract>` for catalog load / rename
  deserialization (`CatalogSchemaStoragePart.java:51-52, 70`; `Catalog.java:823`; `DCPS:5176`). Load path,
  engine executor.
- `Catalog.PENDING_TRIGGER_REBUILDS`: `ThreadLocal<Deque<Set<String>>>` for schema-update batching on the updating
  thread (`Catalog.java:236-245`). Request thread, or Stage2 for replayed schema mutations.
- `FrontCodedStringColumn.SCRATCH`: `ThreadLocal<DecodeScratch>`, grown never shrunk
  (`FrontCodedStringColumn.java:87, 226`). Per-thread allocation; harmless on a VT but re-created per new virtual
  thread.
- `Crc32CWrapper` scratch: `ThreadLocal` (`Crc32CWrapper.java:170-175`). Same.
- `CollationKeyCache`: a **striped pool instead of `ThreadLocal`**, chosen because the per-miss `ThreadLocal.get()`
  cost more than the key computation (`CollationKeyCache.java:147-149`). Already VT-friendly.
- `TracingContext.CLIENT_LABELS`: `ThreadLocal<Label[]>` (`TracingContext.java:134`). Request side only.
- `DCPS.CURRENT_TIME_MILLIS`: `ThreadLocal<LongSupplier>` test hook (`DCPS:297`).

## Does the pool size encode a constraint?

Defaults: `minThreadCount = availableProcessors()`, `maxThreadCount = availableProcessors() x 4`,
`queueSize = 100`, priority 5 (`ThreadPoolOptions.java:89-92`), i.e. 16 threads only on a four-core box.
`queueSize` doubles as the pipeline buffer capacity (`TM:1998`), which the WAL-replay benchmark harness has to
raise because it "must comfortably exceed the number of transactions that may be in flight at once"
(`WalReplayState.java:164-168`).

- **The pipeline itself needs at most three threads per catalog** (Stage1 consumer, Stage2 consumer, sync task).
  Nothing is sized from the pool; the only pool-derived number is the buffer capacity above. Retries do not
  consume pool threads (`TM:707-711`).
- **Per-catalog multiplicity:** every `Catalog` has its own `TransactionManager` and pipeline
  (`Catalog.java:751-759`), so `3 x catalogs` threads can be busy with pipelines alone. The lock and stage state is
  per catalog; only `engineStateLock` is engine-wide.
- **What the size actually bounds** is the concurrency of everything else in the executor table: REST/GraphQL
  mutation bodies, warm-up flush fan-out (one thread per collection per flushing session), engine operators,
  boot-time loads, and the parked closers listed under "Waits on tx-pool task".
- **Two threads are not enough** as soon as one REST/GraphQL mutation is in flight, because its closer parks a
  pool thread until Stage2 completes its record, and Stage2 needs a pool thread. With `maxThreadCount` closers
  parked (all pool workers), the Stage1/Stage2 consumer tasks and the sync task sit in the unbounded backlog as
  unrejectable tasks and nothing progresses until the sweep fails the records after `safetyDeadlineMs()` (60 s or
  more) and the joiners unwind (`TM:1725-1727`; `PendingCommitProgressRegistry.java:187-200`). The javadoc on
  `safetyDeadlineMs` describes exactly this symptom class ("a starved executor makes a perfectly healthy commit
  look identical to a dropped one") and attributes it to host oversubscription.
- **Unbounded virtual threads for the REST/GraphQL mutation bodies would not park in `submit()`** (there is no
  `submit`), but they would raise the *admission* rate into a buffer of 128 items: the excess is rejected at
  `offer` time with `TransactionException("Conflict resolution transaction queue is full!")` (`TM:683-690`) rather
  than queued. Today the same 128 cap applies; the difference is only that the platform pool's
  `maxThreadCount + queueSize` currently throttles how many mutation bodies can even reach `commit`. The
  Stage1 -> Stage2 hop has the same 128 cap and a *lossier* drop path (durable transaction, client told "some
  committed data will be lost", `AbstractStage:178-186`), but Stage2 is already single-threaded, so unbounded VTs
  upstream do not change its inflow, which is bounded by Stage1's own throughput.

## The commit wait on the request side

`CommitBehavior` (`TransactionContract.java:197-291`): `WAIT_FOR_CONFLICT_RESOLUTION` (completed by Stage1 after
version reservation, `Stage1:630-635`), `WAIT_FOR_WAL_PERSISTENCE` (completed by the sync task after the force,
`Stage1:355-360`), `WAIT_FOR_CHANGES_VISIBLE` (default, `TransactionContract.java:286-289`; completed by Stage2
after the live-view swap, `Stage2:168-182`). The `WAIT_FOR_WAL_PERSISTENCE` javadoc still says "fsync operations
are **not** batched across transactions" (`TransactionContract.java:238-240`); the stage code batches them
(`Stage1:97-103`). Documentation lag, not a behavioural claim to rely on.

**What is waited on:** three `CompletableFuture<CommitVersions>` in `CommitProgressRecord` (`CPR:144-159`),
selected by `CommitProgress.on(behaviour)` (`CommitProgress.java:259-269`). Completion is always asynchronous
through `enqueueCompletion -> completionSequencer.thenRunAsync(stage.complete, requestExecutor)` (`CPR:210-225`),
so **the future is completed on a request-pool thread**, not on the stage thread; if the request executor
rejects, the stage completes inline as a fallback (`CPR:217-222`). The termination callback (session termination
hook, traffic-recording close) runs first, on that same request-pool thread (`CPR:438-467`;
`EvitaSession.java:379-381, 2040-2085`).

**Which thread waits, per API:**

- Embedded Java: `closeNow(b).toCompletableFuture().join()` with no timeout, on the caller's thread
  (`EvitaSessionContract.java:346-362`).
- gRPC: no wait; `commitProgress.on(b).whenComplete(...)` sends the response asynchronously
  (`EvitaSessionService.java:1058-1090`).
- REST: `session.close()` -> the same `join()`, on the thread completing the handler chain's `whenComplete`; for a
  write endpoint that is the transaction-pool thread that ran the handler body (`EndpointHandler.java:129-141`;
  `RestEndpointExecutionContext.java:165-169`; `EndpointExecutionContext.java:154-157`).
- GraphQL: `evitaSession.close()` -> the same `join()`, on the thread completing the mutation execution result,
  i.e. the transaction-pool thread that ran the `WriteDataFetcher` (`EvitaSessionManagingInstrumentation.java:91-100`;
  `AsyncDataFetcher.java:98-146`).
- Warm-up session close, any API: the same `join()`, waiting for the flush future executed on the transaction pool
  (`EvitaSession.java:1925-1966`).

**How long:** the pipeline latency for `WAIT_FOR_CHANGES_VISIBLE` is the Stage1 queue wait, append, next force,
Stage2 queue wait, a greedy round of up to `flushFrequencyInMillis` (10 s), flush and merge. Measured medians after
tuning were ~301 ms (small transactions) and ~2 511 ms (large), on a 300-transaction production slice
(`2026-07-27-write-path-performance-tuning/README.md:121-135`). The only hard bound is the watchdog:
`safetyDeadlineMs() = max(60 000, 5 x waitForTransactionAcceptanceInMillis)`, 100 s by default (`TM:1725-1727`;
`TransactionOptions.java:113`), after which the sweep or the live-view wait completes the record exceptionally
(`TM:1962-1965`, `TM:1670-1696`). `transactionTimeoutInMilliseconds` (300 s, `ServerOptions.java:96`) bounds the
*engine state lock*, not this wait (`EngineTransactionManager.java:308`). `TM.close()` fails everything still
pending (`TM:1765-1779`).

**Forced close: `ClosingSequence` and `FutureAwaiter` (`core/session`).** These two small types are the waits
around a close that somebody *else* initiates, and they are bounded where the client's own `join()` is not.

- `FutureAwaiter.awaitWithTimeout` is `future.get(timeout, unit)`: a timeout returns `false`, an interrupt
  restores the flag and throws `SessionBusyException`, an execution failure throws `SessionBusyException`
  (`FutureAwaiter.java:48-64`). It is the only `CompletableFuture.get` on the session path and it always carries
  a timeout. `ClosingSequence` is a `(closeLambda, closedFuture)` pair whose `awaitFinish` delegates to it
  (`ClosingSequence.java:34-53`). Neither is related to `EvitaSession.closingSequenceFuture`
  (`EvitaSession.java:223`), which is the field a *second* closer of the same session `join()`s without a
  timeout until the first closer has built `closedFuture` (`EvitaSession.java:1993-1996`).
- **User 1, the session proxy.** Every session is handed out behind `EvitaSessionProxy`
  (`SessionRegistry.java:688-691`). `executeWhenMethodIsNotRunning(Runnable)` installs a `ClosingSequence`; if no
  business method is running, the close lambda runs immediately **on the caller's thread**, otherwise it runs in
  the `finally` of the running business method, **on the session's own thread** (`EvitaSessionProxy.java:616-626,
  665-673, 682-690`). While a closing sequence is installed, `isActive` answers `false` eagerly and any other
  business call parks up to 500 ms on `closedFuture` and then throws `SessionBusyException`
  (`EvitaSessionProxy.java:606-612, 649-660`). `closedFuture` completes when the close *lambda* returns, and that
  lambda is `plainSession.closeNow(WAIT_FOR_WAL_PERSISTENCE).toCompletableFuture().exceptionally(...)`
  (`SessionRegistry.java:497-521`), which returns after the synchronous half of `closeInternal` (memory commit
  plus `offer`, or warm-up pop plus dispatch) without waiting for the pipeline. So the 500 ms wait covers the
  synchronous close, never the commit pipeline.
- **The drain that uses it.** `closeAllActiveSessionsAndSuspend` publishes the suspension, takes and releases the
  registration write-lock as a barrier, then loops: hand every active session a forced close through the proxy,
  `allOf(closeFutures).get(remaining)`, park 10 ms between passes, give up at 5 s end to end
  (`SessionRegistry.java:357-460`; `DRAIN_GIVE_UP_TIMEOUT_MILLIS = 5000`, `:133`; `DRAIN_PASS_PARK_NANOS`,
  `:160`). The futures awaited here are `WAIT_FOR_WAL_PERSISTENCE` futures, i.e. the drainer waits for the
  Stage1 append plus the sync task's force **on the transaction pool**; when the drainer is the go-live operator
  it does so on a transaction-pool thread while holding `engineStateLock` (`MakeCatalogAliveMutationOperator.java:265`;
  ADR go-live §Option A). Expiry throws with the suspension standing and the close futures uncancelled
  (`SessionRegistry.java:405-430, 441-458`).
- **User 2, session admission during a suspension.** A new session arriving while a `POSTPONE` suspension stands
  waits up to 500 ms on the registry's `suspendFuture` via the same `FutureAwaiter`, retries the gate once, then
  throws `SessionBusyException`; under `REJECT` it throws `InstanceTerminatedException` at once
  (`SessionRegistry.java:946-990, 1320-1345`; `SuspendOperation.java:31-38`). `resumeOperations` completes that
  future (`SessionRegistry.java:549-552`). This parks the *session-creating* thread: request pool for gRPC,
  transaction pool for REST/GraphQL mutations.

**Do reads and writes share a pool?** Request and transaction pools are distinct `ObservableThreadExecutor`s
(`Evita.java:459-471`). gRPC executes **every** session call, reads and writes, on the request pool
(`EvitaSessionService.java:650-1464, 1825`), and parks nothing on commit, so gRPC write load cannot starve gRPC
reads through parked threads (it can through CPU). REST and GraphQL split reads to the request pool and writes to
the transaction pool (`EndpointExecutionContext.java:144-157`; `AsyncDataFetcher.java:140-150`) and **park the
transaction-pool thread on commit**; write-heavy REST/GraphQL load therefore starves the *transaction* pool (and
with it the pipeline that must complete those very commits), not the read path. Up to `maxThreadCount` such threads
can be parked at once.

## Warm-up (bulk load) path

- Every warm-up upsert runs **synchronously on the calling thread** (request pool for gRPC/embedded, transaction
  pool for REST/GraphQL), with no `Transaction` bound (`EvitaSession.java:1901-1904`) and a thread-local
  `WarmUpSavepoint` bracket per root entity mutation (`LocalMutationExecutorCollector.java:436-445`). Writes go
  straight into the live indexes and into the non-transactional `DataStoreChanges` buffer.
- **Session close flushes**: `Catalog.flush()` pops the catalog's trapped changes synchronously on the closing
  thread (`Catalog.java:2537`; `EvitaSession.java:1919-1925`), then executes one nested `ProgressingFuture` per
  entity collection on the transaction pool plus a combine step that writes the catalog header **and a bootstrap
  record** (`Catalog.java:2538-2599`; `EvitaSession.java:1959`). The closer `join()`s that future
  (`EvitaSessionContract.java:361`). In warm-up every flush publishes (`.claude/rules/durability-model.md`, "In
  WARM_UP every flush publishes"); `storeHeader` never defers in warm-up (`DCPS:2424-2428`).
- Waits on this path: the closer's `join()`; `Catalog.java:3192-3194, 3242-3244, 3424-3426` `join()` a flush future
  from schema operations executed without a transaction; `EvitaSession.java:2117-2126` `join()`s an engine
  operator for a non-transactional schema update. All three park the calling thread on work that needs a
  transaction-pool thread.
- A failed warm-up flush marks the catalog unpublishable and deactivates it (`Catalog.java:2586-2616, 2651-2720`;
  ADR warm-up atomicity §The barrier); the closer sees the exception through the same future.
- Go-live: the operator runs on the engine executor (= transaction pool), drains sessions under the engine-wide
  `engineStateLock` (bounded), builds a nested flush future and runs `goLive()` in its combine step
  (`MakeCatalogAliveMutationOperator.java:262-300`; `Catalog.java:1401-1470`).

## ADR and rule constraints on the executor design

- **`.claude/rules/durability-model.md`**: nothing is durable until the bootstrap record is published, and "refuse
  to persist" means "refuse to publish". Consequence for any re-threading: the *only* ordering that must survive
  is (bytes written) -> (fsync fence) -> (bootstrap record), which `DCPS.writeCatalogBootstrap` enforces
  structurally (`DCPS:5265-5272`), plus the WAL-side ordering (append) -> (force) -> (client ack / trunk
  checkpoint), which the sync task enforces by sampling before forcing (`Stage1:264-321`; `TM:1424-1435`). Both
  are thread-agnostic; neither depends on which thread does the write.
- **`2026-07-18-paged-index-corruption-and-flush-failure-boundary`**: a failed trunk flush/merge suspends the
  catalog's transaction processing rather than retrying, because "no next flush may run against the baselines a
  failed flush left behind"; parked commit futures "must complete exceptionally in bounded time, never hang"
  (`README.md:57-60`). Trunk incorporation is "the only route to `popTrappedUpdates`" in ALIVE (`README.md:85-89`).
  Any design that adds a second incorporation path (a parallel or per-catalog VT) must keep it single and keep
  the suspension gate in front of it (`TM:1398-1400, 1548-1563`).
- **`2026-07-27-write-path-performance-tuning`**: flush strictly before merge is load-bearing for the dirty-set
  prune (`README.md:88-92`); trunk re-apply (~38 % CPU) is the standing open item and an MVCC-sensitive project of
  its own (`README.md:170-172`); storage-knob tuning was a measured no-op because I/O is ~1 % of the hot threads
  (`README.md:86`). A VT migration buys nothing on this axis; the pipeline is CPU-bound on one thread.
- **`2026-08-26-warm-up-per-entity-mutation-atomicity`**: `WARMING_UP` remains contractually single-threaded and
  the thread-confined savepoint relies on it (`:643-644`); publication refusal, not write refusal, is the barrier
  (`:290-327`). A `ScopedValue` for the savepoint is compatible with this only if the whole root-mutation bracket
  stays on one (virtual) thread, which it does today.
- **`2026-09-06-go-live-session-drain`**: `engineStateLock` is engine-wide and held across a bounded drain; a
  `transactionTimeoutInMilliseconds` near the drain's 5 s worst case would turn the delay into
  `TransactionTimedOutException` on an unrelated catalog (§Option A). Stage2 takes that lock when a replayed
  transaction carried schema mutations (`TM:1610-1622`), so a slow drain stalls Stage2 for up to 300 s.
- **`2026-08-14-interruption-weaving-and-task-cancellation`**: cancellation is delivered through the executor's
  `Future` because interrupting a tracked thread "poisons the next task on that pooled thread";
  `ThreadPoolExecutor.runWorker` clears the leftover flag (`:99-106`). A per-task virtual thread removes the "next
  task on the same thread" hazard entirely but also removes the `runWorker` flag-clearing the design leans on;
  the woven `@Interruptible` checkpoints abort the next query on any thread whose interrupt flag is set
  (`:230-233`). `catch (RuntimeException)` cleanup blocks are a latent-bug class on any path that can now be
  interrupted (`:236-247`).
- **`2026-07-31-bulk-ingest-write-path`**: the deferred "move deflate off the ingest thread" item is blocked by
  "Record N+1's file offset is arithmetic on record N's compressed length, which forbids out-of-order completion"
  (`:63-72`); the append-only files are single-writer by construction. All its figures are warm-up only
  (`:130-134`).

## Facts relevant to a Virtual Thread design

1. No `SubmissionPublisher.submit()` exists in the tree; every hop uses non-blocking `offer` with a drop handler
   (`TM:666-691`; `AbstractStage:174-191`). Nothing parks on admission.
2. Stage serialisation is a property of the `Flow` subscription (one consumer at a time, `request(1)`), not of
   the executor; a virtual-thread-per-task executor preserves WAL order and version monotonicity unchanged
   (`AbstractStage:90-114`; `TM:250-258`).
3. The pipeline's steady-state footprint is three threads per catalog: Stage1 consumer, Stage2 consumer, sync
   task (`Stage1:251-261`). Only Stage2 is CPU-heavy; it holds `trunkIncorporationLock` for up to a 10 s greedy
   round plus flush and merge (`TM:1403-1405, 1516-1521`).
4. `Transaction.CURRENT_TRANSACTION` is bound and unbound around each replay and around the flush plus merge on
   the Stage2 thread (`TM:371, 2034`; `Transaction.java:121-186, 517`), and the B+ tree page emission silently
   emits nothing if the flush runs on a thread other than the binding one
   (`AbstractTransactionalBPlusTree.java:711-737`). A `ScopedValue` bound for the duration of
   `executeInTransactionIfProvided` satisfies this as long as `Catalog.flush` stays inside that scope.
5. `WarmUpSavepoint.CURRENT` is opened and closed on the upserting thread within one root mutation
   (`LocalMutationExecutorCollector.java:436-445`; `WarmUpSavepoint.java:267-275, 636-641`); a leaked binding breaks
   the next entity on that thread. With per-task virtual threads there is no "next entity on that thread".
6. Kryo instances and output buffers are pooled or single-owner, never `ThreadLocal`, on the WAL and data-file
   write paths (`AML:216-218`; `ObservableOutputKeeper.java:108-112, 247-273`; `OffsetIndex.java:220`).
   `FrontCodedStringColumn.SCRATCH` and the `Crc32CWrapper` scratch are per-thread allocations that a per-task VT
   would re-create per task (`FrontCodedStringColumn.java:226`; `Crc32CWrapper.java:170-175`).
7. Every lock on the pipeline is a `ReentrantLock` or a CAS; the only `synchronized` on the commit path is the
   sub-microsecond `CPR.enqueueCompletion` (`CPR:210`). `Catalog.terminate` is `synchronized` (`Catalog.java:2091`).
8. `waitUntilVersionReaches` busy-spins 4 096 iterations before parking in 100 us slices (`TM:1670-1696`); on a
   virtual thread the spin phase occupies a carrier.
9. Commit completion callbacks always hop to the **request** executor via `thenRunAsync` (`CPR:210-225`); the
   stage threads never run client code.
10. The parked platform thread today is the session closer: embedded callers, REST and GraphQL `join()` without a
    timeout (`EvitaSessionContract.java:346-362`; `RestEndpointExecutionContext.java:165-169`;
    `EvitaSessionManagingInstrumentation.java:91-100`). gRPC never parks (`EvitaSessionService.java:1058-1090`).
11. REST and GraphQL park that join on a **transaction-pool** thread, the same pool the pipeline that completes
    the join runs on (`EndpointExecutionContext.java:154-157`; `AsyncDataFetcher.java:140-150`). Unrejectable
    pipeline tasks wait in the unbounded backlog rather than getting a new worker (`OTE:1258-1274, 1200-1212`). The
    bound on that stall is the dangling-commit sweep at `max(60 s, 5 x acceptance)` (`TM:1725-1727`).
12. Three more caller-side joins on transaction-pool work exist: warm-up schema operations
    (`Catalog.java:3192-3194, 3242-3244, 3424-3426`), non-transactional schema updates via engine mutations
    (`EvitaSession.java:2117-2126`), and the go-live operator's nested flush
    (`MakeCatalogAliveMutationOperator.java:268-272`).
13. `queueSize` (default 100, effective 128) is both the pool backlog and the per-stage pipeline buffer
    (`TM:1998`); the Stage1 -> Stage2 drop path loses a *durable* transaction's client notification and relies on
    the service-pool drainer to re-incorporate it, and the drainer does not propagate the result to the live view
    (`TM:1925-1948`; only `Stage2:166` propagates).
14. Background actors on the **service** scheduler already touch pipeline state concurrently: the WAL drainer
    (`trunkIncorporationLock.tryLock(0)`), the checkpoint ticker (`checkpointLock`), and the pending-progress
    sweep (`TM:514-529`; `CheckpointCoordinator.java:222-227, 407-423`).
15. The Stage2 thread can block up to `transactionTimeoutInMilliseconds` (300 s) on the engine-wide
    `engineStateLock` when a replayed transaction carried catalog-schema mutations (`TM:1610-1622`;
    `EngineTransactionManager.java:308, 386`).
16. The measured cost profile is CPU on one thread (trunk re-apply ~38 %, I/O ~1 %, fsync ~7 ms/tx); thread
    footprint, not latency, is the only thing a VT migration of the pipeline can change
    (`documentation/adr/2026-07-27-write-path-performance-tuning/README.md:86, 170-172`).
17. The transaction pool exists as a separate pool explicitly because of thread-local transaction state
    (`Evita.java:464-471`); with a `ScopedValue` that reason disappears, but the pool-starvation facts in 11 and 12
    are independent of it.
18. The only timed `CompletableFuture.get` on the session path is `FutureAwaiter.awaitWithTimeout`
    (`FutureAwaiter.java:48-64`), used for two 500 ms waits: a business call arriving while a forced close is
    installed on the session proxy (`EvitaSessionProxy.java:649-660`) and session admission during a `POSTPONE`
    suspension (`SessionRegistry.java:978-990`). Both throw `SessionBusyException` on expiry; neither waits on the
    commit pipeline, only on the synchronous half of a close or on `resumeOperations`.
19. A forced close runs either on the drainer's thread or, if a business method is in flight, on the session's
    own thread in the `finally` of that method (`EvitaSessionProxy.java:616-626, 665-673`); the registry then
    awaits the `WAIT_FOR_WAL_PERSISTENCE` futures with a 5 s end-to-end budget and 10 ms parks between passes
    (`SessionRegistry.java:357-460, 133, 160`). Under go-live that wait sits on a transaction-pool thread holding
    the engine-wide `engineStateLock`.
