# Part 03 — Virtual-thread pinning and blocking-vs-CPU character of the executors

Static analysis only (see `../README.md` for the method constraints). Every JDK statement below was verified
against the installed runtime with `javap -p -c` (OpenJDK 21.0.12, `/usr/lib/jvm/java-21-openjdk-amd64`) and,
where readable source was needed, against the `openjdk/jdk21u` tree on GitHub (branch `master`, fetched
2026-09-08). Where both were consulted for the same fact, the bytecode of the installed runtime is the one quoted.
Nothing was compiled, run or measured.

Path conventions: `Class.java:NN` in tables; the full path is given once in the notes under each table. Unless
stated otherwise a path is `<module>/src/main/java/io/evitadb/<package>`.

## Executive summary

1. **The main code base contains 69 real `synchronized` sites** (plus 107 in generated gRPC stubs, all
   double-checked lazy `MethodDescriptor` creation, e.g. `EvitaServiceGrpc.java:54`). The team lead's counts
   included javadoc mentions: `EvitaContract`, `CacheEden`, both CDC shared publishers, `OffsetIndex`, the three
   write-handle classes, `OffHeapMemoryOutputStream`, `DefaultCatalogPersistenceService`, `DiskRingBuffer`,
   `SortIndex`, both B+ trees, `ValueIdConsumerRegistry`, `PageStreamRegistry`, `ReadWriteKeyCompressor`,
   `CollationKeyCache`, `AttributeIs`, `ConcurrentSessionAccessException` and `HostSystemEvent` have **zero**
   monitors in code; they use `ReentrantLock`, `volatile`, or nothing.
2. **Exactly one `Object.wait()` exists in the main code base**: `RingBufferSpanLock.java:121` inside the
   `synchronized acquireExclusive` (line 112), entered while the caller already holds the `OffHeapTrafficRecorder`
   monitor (`OffHeapTrafficRecorder.java:803`). It is the only **REAL PINNING RISK** found and is reachable only
   from the service scheduler (traffic drain / export / close), never from the request or transaction executors.
   Readers use `tryAcquireShared` (line 91), which never blocks.
3. **`SubmissionPublisher` does not pin on JDK 21.** `doOffer` holds a `ReentrantLock` (installed runtime: zero
   `monitorenter` in `SubmissionPublisher` and `BufferedSubscription`; `Field lock:ReentrantLock` +
   `ReentrantLock.lock` at the top of `doOffer`). evitaDB never calls `submit`: both hand-offs use
   `offer(item, onDrop)` (`TransactionManager.java:666`, `AbstractTransactionStage.java:175`), which is
   `doOffer(item, 0L, onDrop)`; `retryOffer` calls the blocking `awaitSpace` only when `nanos > 0L`, so a full
   buffer invokes `onDrop` and fails the commit with `TransactionException` instead of parking.
4. **Request executor**: query execution is CPU-bound; the only I/O is storage-part reads through
   `RandomAccessFile` (`OffsetIndex.java:1852` → `ReadOnlyFileHandle.java:95-96,117-118` →
   `RandomAccessFileInputStream.java:121-168`), which JDK 21 wraps in `jdk.internal.misc.Blocker` (compensated,
   no unmount). Every blocking wait on this executor is a `java.util.concurrent` park outside any monitor:
   session-close joins (REST `RestEndpointExecutionContext.java:167`, GraphQL
   `EvitaSessionManagingInstrumentation.java:95`, both → `EvitaSessionContract.java:347,361`), warm-up schema
   flushes (`Catalog.java:3194,3244,3426`), the gRPC outbound gate (`GrpcOutboundGate.java:317`) and `tryLock`
   on store write handles during WARM_UP writes and isolated-WAL spills. Under virtual threads all of them unmount.
5. **Transaction executor**: WAL append + `force(true)` (`AbstractMutationLog.java:1402,1450-1451,758-760`)
   under three nested `ReentrantLock`s (`walAppendingLock` :1345, `walWriteLock` :2837, `walSyncLock` :1290);
   trunk incorporation (CPU replay + flush with fsync) under `trunkIncorporationLock`
   (`TransactionManager.java:1404-1407`); checkpoints under a `checkpointLock` shared with the service ticker
   (`DefaultCatalogPersistenceService.java:2341`, `CheckpointCoordinator.java:297-408`). All j.u.c: parks
   unmount, file I/O is compensated.
6. **Blocking file I/O is compensated, not unmounted.** `Blocker.begin()` calls
   `ForkJoinPools.beginCompensatedBlock(ct.getPool())` when the carrier is a `CarrierThread`. In the installed JDK
   `RandomAccessFile` (read/write/seek/length/open), `FileChannelImpl` (read/write/force/size),
   `FileOutputStream` (12 sites), `FileInputStream` (24), `FileDescriptor.sync` (3), `UnixFileSystem` (45, incl.
   `delete`) and `UnixNativeDispatcher` (114) are wrapped. `ForkJoinPool.tryCompensate` spawns a spare carrier up
   to `maxPoolSize`; past it, because the VT scheduler is built with `saturate = pool -> true`
   (`VirtualThread.lambda$createDefaultScheduler$3` returns `iconst_1`), it returns 0 and the thread blocks its
   carrier instead of throwing `"Thread limit exceeded replacing blocked worker"`.
7. **Scheduler defaults** (installed runtime, `VirtualThread.createDefaultScheduler`):
   `jdk.virtualThreadScheduler.parallelism` = `Runtime.availableProcessors()`; `maxPoolSize` =
   `max(parallelism, 256)`; `minRunnable` = `max(parallelism / 2, 1)`; carrier keep-alive 30 s.
8. **Native frames**: `Deflater`/`Inflater` (only with `storage.compress=true`) run under the JDK's own
   `synchronized (zsRef)` with no park inside; `CRC32C` is intrinsic; direct `ByteBuffer`s are allocated once at
   init; TLS is native BoringSSL (`netty-tcnative-boringssl-static` 2.0.78, runtime scope in
   `armeria-1.41.1.pom:197-203`) on the Netty event loop; MinIO uploads go through OkHttp on the service
   scheduler (`ExportS3Service.java:1025-1051`). No `Unsafe`, `MemorySegment`, `sun.misc` or `jdk.internal` in
   evitaDB code.
9. **The service scheduler is the blocking pool.** It is a fixed `ScheduledThreadPoolExecutor(maxThreadCount)`
   (`Scheduler.java:184-188`, default `2 × CPU`, `ThreadPoolOptions.java:94`), it is **also Armeria's
   `blockingTaskExecutor`** (`ExternalApiServer.java:571`), and two infinite tasks (`JfrRecorderTask.java:137`,
   `TrafficRecorderTask.java:204`) each park a thread on a `CountDownLatch` for their whole lifetime.
10. **Verdict**: no REAL PINNING RISK on the request or transaction executors on JDK 21; one on the service path
    that matters only if service tasks move to virtual threads. The JDK 21 rule set is wider than "park inside
    synchronized": a VT also pins while **blocked on a contended `monitorenter`** and in `Object.wait`, so the
    pure-memory monitors below cost a pin for the duration of a contended critical section (JEP 491 / JDK 24
    removes all three).

## 1. `synchronized` inventory

Verdict legend: **NO PINNING RISK** (nothing parks inside; a contended entry pins only for the critical section),
**PINS-BUT-SHORT** (Blocker-compensated file I/O inside), **REAL PINNING RISK** (parks inside: wait / queue /
lock / future / network), **NOT ON A VT PATH** (startup-only, event loop, internal pool, test).

### 1.1 `evita_engine` — 10 sites

| File:line | Guards | Inside | Thread/executor | Verdict | Note |
|---|---|---|---|---|---|
| `AttributeIndex.java:779` | lazy `uniqueIndex` map | alloc + store | request, transaction | NO PINNING RISK | E1 |
| `AttributeIndex.java:805` | lazy `sortIndex` map | alloc + store | request, transaction | NO PINNING RISK | E1 |
| `AttributeIndex.java:831` | lazy `chainIndex` map | alloc + store | request, transaction | NO PINNING RISK | E1 |
| `AttributeIndex.java:857` | lazy `sharedValueIndex` | alloc + store | request, transaction | NO PINNING RISK | E1 |
| `AttributeIndex.java:883` | lazy `sharedRangeIndex` | alloc + store | request, transaction | NO PINNING RISK | E1 |
| `AttributeIndex.java:911` | lazy `filterIndex` cache | alloc + store | request, transaction | NO PINNING RISK | E1 |
| `AttributeIndex.java:935` | lazy `uniqueViewIndex` | alloc + store | request, transaction | NO PINNING RISK | E1 |
| `HierarchyIndex.java:305` | lazy `nodeStore` | 4 allocations | request, transaction | NO PINNING RISK | E2 |
| `InvertedIndex.java:1709` | value-id directory | CPU rebuild | request (reads!), transaction | NO PINNING RISK | E3 |
| `Catalog.java:2091` | `terminationLock` | flush + WAL close | transaction, shutdown | PINS-BUT-SHORT | E4 |

Notes:

- **E1** `evita_engine/.../index/attribute/AttributeIndex.java`. Double-checked allocation of a sub-index family
  (`PersistentTransactionalProducerMap` / `TransactionalMap`, javadoc at :134). Pure memory. Executed on the
  request executor by WARM_UP upserts and by ALIVE write sessions (which apply mutations to the transactional
  layers on the caller's thread) and on the transaction executor during trunk replay.
- **E2** `evita_engine/.../index/hierarchy/HierarchyIndex.java` — same pattern (javadoc :153).
- **E3** `evita_engine/.../index/invertedIndex/InvertedIndex.java:1709 refreshValueIdDirectory()` —
  `buckets.rebuildValueIdDirectory()` (O(leaves), CPU) then a volatile clear. Reached from the **query** path when
  the directory is stale, so this is the one memory-only monitor whose critical section is not sub-microsecond:
  a contended reader pins for the rebuild. The comment at :1701 records the counterfactual test for it.
- **E4** `evita_engine/.../core/catalog/Catalog.java:2091 terminate()` → `terminateInternally()` (:3446) and
  `persistenceService.close()` (:2098). Inside: `transactionManager.close()` (:3450, non-blocking cancels,
  `TransactionManager.java:1765-1779`), per-collection `flush()` (:3473: file writes + fsync through
  `WriteOnlyFileHandle.handleLock.tryLock`, :390-438), WAL close under `walSyncLock`
  (`AbstractMutationLog.java:1686`), file closes. Callers: `SetCatalogStateMutationOperator.java:171`,
  `RemoveCatalogSchemaMutationOperator.java:156`, `ModifyCatalogSchemaNameMutationOperator.java:630,726`
  (transaction executor) and `Evita.java:2170` (shutdown). Caveat: j.u.c locks (`handleLock`, `walSyncLock`,
  `walWriteLock`, `checkpointLock`) are taken **inside** the monitor; if one is contended the park happens inside
  the monitor and pins. Catalog lifecycle only, so in practice also NOT ON A VT PATH.

### 1.2 `evita_api` — 1 site

| File:line | Guards | Inside | Thread/executor | Verdict | Note |
|---|---|---|---|---|---|
| `CommitProgressRecord.java:210` | `completionSequencer` | CF chaining | transaction, request | NO PINNING RISK | A1 |

- **A1** `evita_api/.../api/CommitProgressRecord.java:210 enqueueCompletion` (javadoc :189, :203):
  `CompletableFuture.thenRunAsync(…, executor).exceptionally(…)` — memory plus `ThreadPoolExecutor.execute`
  (`LinkedBlockingQueue.offer`, non-blocking; rejection is recovered). `EvitaContract` has no monitor (both hits,
  :559 and :573, are javadoc); its blocking is the `.join()` family in §2.

### 1.3 `evita_store` — 16 sites

| File:line | Guards | Inside | Thread/executor | Verdict | Note |
|---|---|---|---|---|---|
| `OffHeapTrafficRecorder.java:803` | drain vs free | file writes + span lock | service | **REAL PINNING RISK** | S1 |
| `OffHeapTrafficRecorder.java:1069` | same monitor | calls :803, :1100, `releaseIndex` | service | inherits S1 | S1 |
| `OffHeapTrafficRecorder.java:1100` | delta counters | JFR `commit()` | service | NO PINNING RISK | S1 |
| `RingBufferSpanLock.java:91` | span sets | conflict scan, returns null | request, service | NO PINNING RISK | S2 |
| `RingBufferSpanLock.java:112` | span sets | **`wait()` loop (:121)** | service | **REAL PINNING RISK** | S2 |
| `RingBufferSpanLock.java:143` | span sets | remove + `notifyAll()` | request, service | NO PINNING RISK | S2 |
| `RecoverableOutputStream.java:57` | current `ByteBuffer` | put; `poll()` when full | request | NO PINNING RISK | S3 |
| `RecoverableOutputStream.java:66` | current `ByteBuffer` | same | request | NO PINNING RISK | S3 |
| `RingBufferInputStream.java:155` | — | no-op | request | NO PINNING RISK | — |
| `RingBufferInputStream.java:160` | — | throws | request | NO PINNING RISK | — |
| `AbstractMutationLog.java:2051` | `pendingRemovals` | `walFile.delete()` (:2345) | service | PINS-BUT-SHORT | S4 |
| `ZoneOffsetSerializer.java:64` | `CACHE` | `ofTotalSeconds` | request, transaction | NO PINNING RISK | — |
| `CountingInputStream.java:91` | delegate | `delegate.mark` | service (restore) | NO PINNING RISK | — |
| `CountingInputStream.java:96` | delegate | `delegate.reset` | service (restore) | NO PINNING RISK | — |
| `OffHeapMemoryInputStream.java:151` | buffer mode | `ByteBuffer.mark` | request, transaction | NO PINNING RISK | — |
| `OffHeapMemoryInputStream.java:157` | buffer mode | `reset` | request, transaction | NO PINNING RISK | — |

Notes (paths: `evita_store/evita_traffic_engine/.../store/traffic/`, `evita_store/evita_store_server/.../store/`,
`evita_store/evita_store_key_value/.../store/offsetIndex/io/`):

- **S1** `OffHeapTrafficRecorder.java:803 drainFinalizedSessionsToDisk()` → `diskBuffer.updateIndexTransactionally`
  (`DiskRingBuffer.java:380`, in-memory index transaction) → `appendSession`/`append` (:421, :450) →
  `lockAndWrite` (:1178) → **`spanLock.acquireExclusive` (:1182), which `wait()`s** → `FileChannel.write`
  (compensated); `freeBlocks.offer` (`ArrayBlockingQueue`, non-blocking). Callers: `freeMemoryTask`
  (`DelayedAsyncTask`, :777), `exportTrafficRecording` (:714, run by `TrafficRecordingExportTask`), `close()`
  (:616) — all service scheduler. `:1069 freeMemory()` wraps :803 and `:1100 publishStatisticsEvents()`
  (JFR event commits, `freeBlocks.size()`), then `diskBuffer.releaseIndex()` (memory). The `Object.wait` nested
  under two monitors is a pin on JDK 21; today it is NOT ON A VT PATH.
- **S2** `RingBufferSpanLock.java`. `:91 tryAcquireShared` returns `null` on conflict (never waits) — used by
  request-executor readers (`DiskRingBuffer.lockAndRead:1208`, reached from
  `EvitaTrafficRecordingService.java:117-357`) and by the export (`DiskRingBuffer.java:841`). `:112
  acquireExclusive` loops on `wait()` (:121) until no conflicting span is held — service scheduler only via
  `lockAndWrite`. `:143 release` → `notifyAll()` (:145).
- **S3** `stream/RecoverableOutputStream.java:57,66`: `ByteBuffer.put`; when the buffer is full `ofBufferFull.get()`
  → `OffHeapTrafficRecorder.prepareStorageBlock` → `freeBlocks.poll()` (:1035; `ArrayBlockingQueue` created fair
  at :757, `poll()` is non-blocking but takes the queue's `ReentrantLock` for nanoseconds). Request executor
  (query/mutation recording). Theoretical only: a contended queue lock would park inside the monitor for
  nanoseconds.
- **S4** `wal/AbstractMutationLog.java:2051 removeWalFiles()`: `removeLambda.run()` → `walFile.delete()` (:2345)
  plus `updateFirstVersionKept`. `UnixFileSystem.delete` is Blocker-wrapped in 21.0.12. Scheduled by
  `removeWalFileTask` (:896) and called from `close()` (:1697) — service scheduler.

`DiskRingBuffer` (1 hit, :192) and `OffHeapMemoryOutputStream` (1 hit, :55) are javadoc only.

### 1.4 `evita_external_api` — 42 sites

All routing monitors guard **registration-time mutation** of route tables. The request-path lookups are
unsynchronized: `PathTemplateMatcher.match:50`, `PathMatcher.match:75`, `CopyOnWriteMap.get:112`,
`SubstringMap.get:46,50`, `RoutingHandlerService.handle:118`, `PathHandlingService.handle:93`. Paths are
`evita_external_api/evita_external_api_core/.../externalApi/utils/path/` and `.../path/routing/`.

| File:lines | Guards | Inside | Thread/executor | Verdict |
|---|---|---|---|---|
| `CopyOnWriteMap.java:47,58,70,81,117,122,127,134` | map swap | copy | startup, lifecycle | NOT ON A VT PATH |
| `SubstringMap.java:99,118,164` | probe table | rebuild / probe | same | NOT ON A VT PATH |
| `PathTemplateMatcher.java:93,149,154,177,182,208` | template sets | add/remove/lookup | same | NOT ON A VT PATH |
| `PathMatcher.java:122,141,190,194,212,222` | prefix/exact maps | puts, length array | same | NOT ON A VT PATH |
| `RoutingHandlerService.java:173-246` (9) | per-method matcher | delegates | same | NOT ON A VT PATH |
| `PathHandlingService.java:149-250` (9) | path matcher | delegates | same | NOT ON A VT PATH |
| `ObservabilityProbesDetector.java:180` | `readiness` map | `HashMap.put` | own FJP (:263) | NOT ON A VT PATH |

`RoutingHandlerService` lines: 173, 180, 203, 208, 213, 218, 223, 231, 246. `PathHandlingService` lines: 149,
169, 189, 204, 219, 234, 240, 245, 250. Generated stubs under
`evita_external_api_grpc/shared/.../grpc/generated/`: `EvitaSessionServiceGrpc` 44, `EvitaServiceGrpc` 31,
`EvitaManagementServiceGrpc` 23, `GrpcEvitaTrafficRecordingServiceGrpc` 9 — each a `synchronized (XxxGrpc.class)`
double-checked build of a `MethodDescriptor`/`ServiceDescriptor` (e.g. `EvitaServiceGrpc.java:54-60`), first call
only. `evita_common`, `evita_query`, `evita_server`, `evita_export`: **no** code-level `synchronized`
(`CollationKeyCache.java:70` and `AttributeIs.java:80` are javadoc).

## 2. Blocking waits on request / transaction paths

Sweep: `wait(`, `notify`, `.join()`, `Future.get`, `CountDownLatch`, `Semaphore`, `BlockingQueue take/put`,
`Thread.sleep`, `LockSupport`, `ReentrantLock`/`ReadWriteLock`/`StampedLock`, `Condition`, `Phaser`,
`CyclicBarrier`, `SubmissionPublisher`. There is **no `Thread.sleep`, `Semaphore`, `StampedLock`, `Phaser` or
`CyclicBarrier`** in main code and no `BlockingQueue.take/put` on the server (the only `take()` is in the Java
driver, `EvitaClientSession.java:3167`). "Sync?" = inside a `synchronized` block. "VT" = what the wait becomes
under virtual threads.

| Site | Waits for | Executor | Sync? | VT | Note |
|---|---|---|---|---|---|
| `TransactionManager.java:666` `offer` | nothing (non-blocking) | request | no | n/a | B1 |
| `AbstractTransactionStage.java:175` `offer` | nothing (non-blocking) | transaction | no | n/a | B1 |
| `EvitaSession.java:1995` `join` | first closer to build the future | request | no | unmount | — |
| `EvitaSessionContract.java:347,361` `join` | commit reaching behaviour | request (REST, GQL) | no | unmount | B2 |
| `EvitaSessionService.java:1068,1142` | callbacks only (`whenComplete`) | request | no | n/a | B2 |
| `Catalog.java:3194,3244,3426` `join` | warm-up flush on transaction executor | request | no | unmount | B3 |
| `EvitaSession.java:1925,1959` | warm-up flush at close | request | no | unmount | B3 |
| `Evita.java:795,1100`; `EvitaContract` | engine mutation on transaction executor | request | no | unmount | B4 |
| `Evita.java:1067,1427,1555,1588,2108-2130` | startup / shutdown | main | no | not a VT path | — |
| `GrpcOutboundGate.java:317` `awaitNanos` | client readiness (network) | request | no | unmount | B5 |
| `SessionRegistry.java:429` `parkNanos` | session drain | transaction, shutdown | no | unmount | B6 |
| `SessionRegistry.java:648,955,1030` locks | session admission | request | no | unmount | — |
| `TransactionManager.java:1692` `parkNanos` | live version to advance | transaction | no | unmount | B7 |
| `TransactionManager.java:1004` `tryLock` | conflict resolution (CPU) | transaction | no | unmount | B8 |
| `TransactionManager.java:1345` `tryLock` | WAL append (file write) | transaction | no | unmount | B8 |
| `TransactionManager.java:1404,1407` | trunk incorporation (replay + flush) | transaction | no | unmount | B8 |
| `TransactionManager.java:1607` `tryLock(0)` | catalog propagation (memory) | transaction | no | unmount | B8 |
| `ConflictResolution…Stage.java:238-432` | durability queue bookkeeping | transaction | no | unmount | B9 |
| `AbstractMutationLog.java:1290,1686,2219` | WAL `force(true)` / rotation / close | transaction | no | unmount | B10 |
| `DefaultCatalogPersistenceService.java:2837,3899` | WAL append (+ optional force) | transaction | no | unmount | B10 |
| `DefaultCatalogPersistenceService.java:2341` | checkpoint / bootstrap | transaction, service | no | unmount | B11 |
| `CheckpointCoordinator.java:297,318,362,408` | same lock instance | service (ticker) | no | unmount | B11 |
| `DefaultCatalogPersistenceService.java:3842-4924` | horizon bookkeeping | transaction, service | no | unmount | — |
| `WriteOnlyFileHandle.java:390,412,438` `tryLock` | file write + sync | request (warm-up), tx | no | unmount | B12 |
| `BootstrapWriteOnlyFileHandle.java:157,175,195` | bootstrap write + `force(true)` | transaction | no | unmount | B12 |
| `DefaultIsolatedWalService.java:170-171` | isolated-WAL write | request (ALIVE tx) | no | compensated | B13 |
| `OffsetIndex.java:2850` `tryLock` | opening a read handle | request (pool miss) | no | unmount | B14 |
| `OffsetIndex.java:2835-2843` pool | `LinkedBlockingQueue.poll/offer` | request | no | n/a | B14 |
| `DefaultChangeCaptureSubscription.java:211,310,365` | CDC delivery (`poll` only) | request, CDC | no | unmount | — |
| `CacheEden.java:291` `tryLock(1 s)` | cache adept evaluation (CPU) | service | no | unmount | — |
| `Scheduler.java:656-972`; `DelayedAsyncTask.java:157-368` | task bookkeeping | any submitter | no | unmount | — |
| `TransactionLocations.java:68,88,126,157` | WAL location cache | request (CDC), service | no | unmount | — |
| `ObsoleteFileMaintainer.java:429,625,685,699` | file purges | service | no | unmount | — |
| `DefaultEnginePersistenceService.java:493-922` | engine WAL write + force | transaction | no | unmount | — |
| `JfrRecorderTask.java:137`; `TrafficRecorderTask.java:204` | latch, task lifetime | service | no | unmount | B15 |
| `TrafficRecordingExportTask.java:122`; `BackupTask.java:286` | export/upload done | service | no | unmount | — |
| `EvitaSessionService.java:1019` `get(1 s)` | backup task start | request | no | unmount | — |
| `ObservabilityProbesDetector.java:191` `get(10 s)` | readiness probes | request | no | unmount | B16 |
| `FolderLock.java:89` `FileChannel.tryLock` | `flock`, non-parking | startup | no | not a VT path | — |

Notes:

- **B1** `evita_engine/.../core/transaction/TransactionManager.java:666` and
  `.../stage/AbstractTransactionStage.java:175` call `SubmissionPublisher.offer(item, onDrop)` =
  `doOffer(item, 0L, onDrop)`. In `retryOffer` the blocking `awaitSpace` runs only `if (nanos > 0L)`; with 0 the
  `onDrop` predicate runs (returns `false`) and the commit is failed with "queue is full". `awaitSpace` itself
  would go `ForkJoinPool.managedBlock` → `block()` → `LockSupport.park` under a `ReentrantLock`, i.e. even
  `submit` would unmount rather than pin on JDK 21. Publishers: `TransactionManager.java:1998-2003`
  (`unrejectableExecutor(transactionalExecutor)`, `maxBufferCapacity = transactionThreadPool.queueSize`) and
  `ConflictResolutionAndWalAppendingTransactionStage.java:137`.
- **B2** `EvitaSessionContract.close()` (:347) → `closeWhen(getCommitBehavior())` → `closeNow(...).join()` (:361).
  REST: `RestEndpointHandler.java:133 afterRequestHandled` → `RestEndpointExecutionContext.java:167`, invoked
  from `EndpointHandler.java:103` on the thread completing the handler future — a request thread
  (`EndpointExecutionContext.java:144-145`, REST handlers such as `CollectionsHandler.java:57`). GraphQL:
  `GraphQLWebHandler.java:160,253` runs `graphQL.executeAsync` inside the request pool, data fetchers use
  `AsyncDataFetcher.java:143` (request executor), and `EvitaSessionManagingInstrumentation.java:95` calls
  `close()` on the completing thread. gRPC (`EvitaSessionService.java:1068,1142`) is callback-based. Duration:
  one fsync (ms) for `WAIT_FOR_WAL_PERSISTENCE`, up to trunk incorporation for `WAIT_FOR_CHANGES_VISIBLE`.
- **B3** `Catalog.java:3194,3244,3426`: WARM_UP schema changes build `flush()` and `join()` it after
  `execute(unrejectableExecutor(transactionalExecutor))`. `EvitaSession.java:1925` builds the warm-up close flush
  synchronously and `:1959` executes it on `getTransactionExecutor()`; the caller then waits through
  `commitProgress` (B2). Duration: full collection flush (writes + fsync), ms to s.
- **B4** `Evita.java:795 defineCatalog`, `:1100 registerRestoredCatalog` and the `EvitaContract` default methods
  (:269-735) `join()` an engine-level mutation processed by `EngineTransactionManager` on the transaction
  executor (`Evita.java:565`).
- **B5** `evita_external_api_grpc/server/.../grpc/utils/GrpcOutboundGate.java:317`
  `transportStateChanged.awaitNanos` under `lock` (:149-150), bounded by `stallTimeoutMillis`; woken from the
  Armeria event loop (:401). A stream producer on the request executor can sit here for seconds while the client
  drains.
- **B6** `evita_engine/.../core/session/SessionRegistry.java:429`: drain loop of
  `closeAllActiveSessionsAndSuspend` (:357), bounded by `DRAIN_GIVE_UP_TIMEOUT`; the write lock at :367-368 is
  released before the loop. Callers: `MakeCatalogAliveMutationOperator.java:265`,
  `ModifyCatalogSchemaNameMutationOperator.java:362,397` (transaction executor), `Evita.java:1655,1693,1901`.
- **B7** `TransactionManager.java:1692`: `parkNanos(PARK_INTERVAL_NANOS)` after `SPIN_ATTEMPTS_BEFORE_PARK`
  spins, bounded by `safetyDeadlineMs()`. Callers: `TrunkIncorporationTransactionStage.java:92,135`,
  `TransactionManager.java:1584`.
- **B8** The four `TransactionManager` locks are fair `ReentrantLock(true)` (:248, :258, :262, :266).
  `conflictResolutionLock.tryLock(timeout)` :1004 (CPU only inside); `walAppendingLock.tryLock(timeout)` :1345
  wraps `appendWalAndDiscardDeferringSync` (file write, fsync deferred); `trunkIncorporationLock` :1404/:1407
  wraps replay (:2028) and `Catalog.flush`; `catalogPropagationLock.tryLock(0)` :1607 is memory only.
- **B9** `.../stage/ConflictResolutionAndWalAppendingTransactionStage.java:238,274,343,400,432`
  `pendingDurabilityLock` guards queue bookkeeping only. `forceAndRelease` (:316) → `transactionManager.syncWal()`
  (:318) runs outside the lock in `syncPendingTransactions`, dispatched via `executor.execute` (:254).
- **B10** `evita_store_server/.../store/wal/AbstractMutationLog.java`: `walSyncLock` (:242) around `force(true)`
  (:1290 → `forceDurable` :758-760), rotation (:2219: tail write + force + close) and close (:1686). Append path
  :1385-1451 (`walFileChannel.write` :1388, :1402, :1441; `forceDurable` :1451 when `syncOnCompletion`).
  `DefaultCatalogPersistenceService.walWriteLock` (:386) wraps `doAppendWalAndDiscard` (:2837) and WAL close
  (:3899); `syncWal` reached from `Catalog.java:2846` → `DefaultCatalogPersistenceService.java:2817`. Per the
  comments at :1281-1286 a force costs ~0.5 ms with no dirty pages and roughly ten times that with.
- **B11** `DefaultCatalogPersistenceService.checkpointLock` (:400) around `storeHeader` (:2341: collection header
  writes, bootstrap record, forces). The **same instance** is handed to `CheckpointCoordinator` (:209) and taken
  by the service ticker (:407-425) around `forcePendingSyncs` (:261-270 → `handle.forceDurable()`, i.e. open +
  `force(true)`, `WriteOnlyFileHandle.java:281-284`, ~5 ms per file per the comment at :277-280). A
  transaction-executor flush can therefore park behind N fsyncs issued by the ticker.
- **B12** `evita_store_key_value/.../offsetIndex/io/WriteOnlyFileHandle.java:390,412,438`
  `handleLock.tryLock(lockTimeoutSeconds)`: single writer per data file; inside: Kryo write to `FileOutputStream`
  and `doSyncOrDefer` (:266-273; `getFD().sync()` :249 only when `syncWrites` and no pending-sync registry).
  Reached on the request executor in WARM_UP (`WarmUpDataStoreMemoryBuffer.java:174-175` →
  `DataStoreChanges.java:686` → `OffsetIndex.put`) and on the transaction executor during flush.
  `BootstrapWriteOnlyFileHandle.java:157,175,195` wrap the bootstrap write + `force(true)` (:217-218).
- **B13** `evita_store_server/.../store/catalog/DefaultIsolatedWalService.java:170-171`
  `writeHandle.checkAndExecute` per mutation of an ALIVE write session (`Transaction.java:573-575`), backed by
  `WriteOnlyOffHeapWithFileBackupHandle`: off-heap memcpy until the region is exhausted, then a spill to a file
  (:458) with `getFD().sync()` (:147-148) and `force(true)` (:222-223). Runs on the **request executor**.
- **B14** `OffsetIndex.java:2850` `readFilesLock.tryLock` in `OffsetIndexObservableInputPool.create` around one
  `RandomAccessFile` open (`ReadOnlyFileHandle.java:95-96`). `borrowAndExecute` (:2835-2843) uses Kryo's
  thread-safe `Pool` whose queue is `Pool$1 extends LinkedBlockingQueue` (verified in `kryo-5.6.2.jar`);
  `obtain()`/`free()` are `poll()`/`offer()`, non-blocking.
- **B15** `JfrRecorderTask.java:137` (submitted at `ObservabilityManager.java:362`) and
  `TrafficRecorderTask.java:204` (submitted at `EvitaSession.java:1748`) hold a service-scheduler thread on a
  `CountDownLatch` for the whole recording — hours. Under VTs this would unmount; today it pins a platform pool
  thread.
- **B16** `ObservabilityProbesDetector.java:191` `allOf(futures).get(10 s)`; each probe is `runAsync` on the
  internal `ForkJoinPool` (:263); callers on the request executor (`PrometheusMetricsHttpService.java:59`,
  `ObservabilityProbesDetector.java:335`).

**Net**: every blocking wait reachable from the request or transaction executors is either a
`java.util.concurrent` park outside any monitor (unmounts under VTs) or Blocker-compensated file I/O. The only
park-inside-monitor is the traffic ring-buffer span lock, which is service-scheduler-only.

## 3. Native / JNI / foreign frames

- **zlib** (`storage.compress=true` only): `evita_store_key_value/.../store/kryo/ObservableOutput.java:311-315`
  (`Deflater.deflate`), `ObservableInput.java:76,203-243` (`Inflater`),
  `store/compression/ZipCompressionFactory.java:94,109`, `OffsetIndex.java:1897-1903` (decompress on read).
  JDK 21 `Deflater.deflate`/`Inflater.inflate` are
  `synchronized (zsRef)` (per instance, uncontended) and call neither `Blocker` nor `LockSupport`. CPU-bound; a
  native frame is on the stack but nothing parks, so no pin event and no unmount — the carrier is simply busy.
- **CRC32C**: `evita_common/.../utils/Crc32CWrapper.java:75,170` uses `java.util.zip.CRC32C` — pure Java,
  HotSpot intrinsic.
- **Direct buffers**: `OffHeapMemoryManager.java:99` and `OffHeapTrafficRecorder.java:753`
  (`ByteBuffer.allocateDirect`) — one allocation per region at init; all later access is `ByteBuffer` slicing
  (intrinsic, no JNI). `Bits.reserveMemory` can sleep/GC when direct memory is exhausted — init time only.
- **RoaringBitmap** (`evita_roaring_bitmap`, vendored): pure Java.
- **Folder lock**: `evita_common/.../utils/FolderLock.java:88-89` `FileChannel.open` + `tryLock` (`flock(2)`
  through `UnixNativeDispatcher`, Blocker-wrapped, non-parking) — startup.
- **TLS**: Armeria 1.41.1 (`pom.xml:142`) declares `netty-tcnative-boringssl-static` 2.0.78 for `linux-x86_64`
  at runtime scope (`~/.m2/.../armeria-1.41.1.pom:197-203`; the artifact is present under
  `~/.m2/repository/io/netty/`). `evita_external_api_core` never references `SslProvider`/`OpenSsl`, so Netty's
  default selects BoringSSL when the native library loads. Runs on the Netty event loop; no evitaDB executor
  thread touches it.
- **MinIO / S3** (`evita_export/evita_export_s3/pom.xml:51,56`: `minio` + `okhttp-jvm`):
  `ExportS3Service.java:263` builds an OkHttp client; `S3UploadOutputStream.close()` (:1025) reads the temp file
  back and calls `minioClient.putObject` (:1051). MinIO's async client returns a `CompletableFuture`; the socket
  work is OkHttp blocking I/O on OkHttp's own platform threads. The evitaDB caller is the service scheduler
  (backup/export tasks), which waits at `BackupTask.java:286` / `TrafficRecordingExportTask.java:122`. JDK 21
  socket I/O (`NioSocketImpl`) is VT-aware, so OkHttp threads on VTs would unmount.
- **`Unsafe`, `MemorySegment`, `sun.misc`, `jdk.internal`, `native`**: none in evitaDB main code.
- **JDK-side monitor on every WAL and ring-buffer write**: `sun.nio.ch.FileChannelImpl.read/write(ByteBuffer)`
  take `synchronized (positionLock)` around the Blocker-wrapped native call (installed runtime: `monitorenter`
  on `Field positionLock` plus `Blocker.begin` in both methods; the positional `read/write(ByteBuffer, long)`
  take the monitor only for append-mode channels; `force(boolean)` takes no monitor). Every WAL append
  (`AbstractMutationLog.java:1388,1402,1441`, channel opened in append mode) and every traffic ring-buffer write
  (`DiskRingBuffer.java:153`) therefore runs native I/O while holding a JDK monitor. That is not a park, so no
  `jdk.VirtualThreadPinned` event is produced and the carrier is compensated; a second VT contending on the same
  channel would pin on `monitorenter`, but evitaDB serialises each channel (`walWriteLock`, the recorder
  monitor), so the contention cannot arise.
- **JDK-side monitors on the request path**: `java.text.RuleBasedCollator.getCollationKey` is `synchronized`;
  `CollationKeyCache.java:70` explains the striped collator pool that keeps it uncontended. Logback 1.6.1
  (`pom.xml:127`) uses a `ReentrantLock` in its output-stream appender (from knowledge, not verified here).

## 4. Workload classification per executor

Executors (`evita_engine/.../core/Evita.java:454-466`): `requestExecutor` and `transactionExecutor` are
`ObservableThreadExecutor` → `ThreadPoolExecutor(core = min, max, 60 s keep-alive, TaskQueue extends
LinkedBlockingQueue)` with grow-before-queue (`core/executor/ObservableThreadExecutor.java:208-232,1173`);
defaults `min = CPU`, `max = 4 × CPU`, queue 100 (`evita_api/.../api/configuration/ThreadPoolOptions.java:85-92`).
`serviceExecutor` is `Scheduler` → `ScheduledThreadPoolExecutor(maxThreadCount)`
(`core/executor/Scheduler.java:184-188`; default `2 × CPU`, `ThreadPoolOptions.java:93-94`) with an
`ArrayBlockingQueue` task registry (:196). It is also Armeria's `blockingTaskExecutor`
(`externalApi/http/ExternalApiServer.java:571`) and the TLS-reload executor (:744).

| Executor | Work item | Class | Note |
|---|---|---|---|
| request | query planning + execution (filter/sort/histogram/facet, bitmaps) | CPU-BOUND | W1 |
| request | entity body / storage-part fetch | BLOCKING (compensated read) | W2 |
| request | Kryo / protobuf / Jackson serialization | CPU-BOUND | — |
| request | ALIVE write session: transactional layers + isolated WAL write | MIXED | W3 |
| request | WARM_UP bulk upsert | MIXED | W4 |
| request | session create / close | ORCHESTRATION (+ join for REST/GraphQL/embedded) | B2 |
| request | commit hand-off | ORCHESTRATION (non-blocking `offer`) | B1 |
| request | gRPC streaming producers (CDC, traffic recordings) | BLOCKING (network readiness) | W5 |
| request | readiness / metrics probes | ORCHESTRATION (10 s bounded) | B16 |
| transaction | stage 1: conflict resolution | CPU-BOUND | B8 |
| transaction | stage 1: WAL append + batched `force(true)` | BLOCKING | W6 |
| transaction | stage 2: trunk incorporation (replay) | CPU-BOUND | B8 |
| transaction | stage 2: `Catalog.flush` / checkpoint | BLOCKING (N writes + fsyncs) | W7 |
| transaction | catalog snapshot propagation | CPU-BOUND / ORCHESTRATION | B8 |
| transaction | engine mutations (create/rename/remove/goLive), `Catalog.terminate` | MIXED | W8 |
| transaction | warm-up flush requested by a closing session | BLOCKING | B3 |
| service | WAL remover, output-buffer releaser, obsolete-file purge | BLOCKING (delete/close) | W9 |
| service | checkpoint ticker | BLOCKING (fsyncs) | B11 |
| service | traffic recorder drain / index / export | BLOCKING (+ `Object.wait`) | S1 |
| service | backups / restore / S3 upload | BLOCKING (zip, `Files.copy`, OkHttp) | W10 |
| service | JFR recorder, traffic recorder (infinite tasks) | BLOCKING (latch, task lifetime) | B15 |
| service | session killer, cache supervisor, collation sweeper, purge, CDC heartbeat | CPU / ORCHESTRATION | W11 |
| service | Armeria blocking-task work (TLS reload, blocking handlers) | BLOCKING | W12 |

Notes:

- **W1** `evita_engine/.../core/query/**` contains no file-I/O primitive (module-boundaries rule); the only
  monitor on the read path is `InvertedIndex.refreshValueIdDirectory:1709` (CPU, E3).
- **W2** `OffsetIndex.get:754` → `doGet:1852` → `ReadOnlyFileHandle.execute:117` →
  `RandomAccessFile.seek/read` (`RandomAccessFileInputStream.java:121-168`), page-cache hits in practice;
  non-flushed values force `doSoftFlush()` first (:770-773); decompression is CPU (:1897).
- **W3** `Transaction.java:573-575` → `DefaultIsolatedWalService.java:170-171` →
  `WriteOnlyOffHeapWithFileBackupHandle` (spill at :458). CPU plus off-heap memcpy; file I/O only after a spill
  (B13).
- **W4** `WarmUpDataStoreMemoryBuffer.java:174-175` → `DataStoreChanges.java:686` → `OffsetIndex.put` →
  `WriteOnlyFileHandle.java:390-438` (`doSyncOrDefer` :266). **No WAL in WARM_UP**:
  `DefaultCatalogPersistenceService.java:1085` (WAL is null in warm-up), `Catalog.java:850,936` pick the
  warm-up buffer.
- **W5** `GrpcOutboundGate.java:317` (B5); `EvitaTrafficRecordingService.java:117-357` runs on the request
  executor and reads through `DiskRingBuffer.lockAndRead:1208` + `RandomAccessFile`.
- **W6** `TransactionManager.java:1345` → `Catalog.appendWalAndDiscardDeferringSync` →
  `DefaultCatalogPersistenceService.java:2837` → `AbstractMutationLog.java:1385-1451`; durability batched by
  `syncPendingTransactions` (`ConflictResolutionAndWalAppendingTransactionStage.java:254-284`) → `syncWal`
  (`AbstractMutationLog.java:1290`).
- **W7** `DefaultCatalogPersistenceService.java:2341` (`checkpointLock`), `WriteOnlyFileHandle.java:281-284`,
  `BootstrapWriteOnlyFileHandle.java:217-218`, `CheckpointCoordinator.java:261-270`.
- **W8** operators under `core/transaction/engine/operators/**` on the executor wired at `Evita.java:565`;
  engine WAL fsync `DefaultEnginePersistenceService.java:493-922`; session drain `SessionRegistry.java:429`;
  `Catalog.terminate` (E4).
- **W9** `AbstractMutationLog.java:896,2051`; `ObservableOutputKeeper.java:159-178`;
  `ObsoleteFileMaintainer.java:429-699`.
- **W10** `BackupTask.java:229,286`, `FullBackupTask.java:174`, `RestoreTask.java:144`,
  `ExportS3Service.java:1025-1051`.
- **W11** `core/session/task/SessionKiller.java`, `core/cache/HeapMemoryCacheSupervisor.java`
  (`CacheEden.java:291`), `core/cache/CollationKeyCacheSweeper.java`, `Scheduler.java:196-207`,
  `AbstractChangeCaptureSubscriber.java:174-181`, `MetricHandler.java:442`.
- **W12** `ExternalApiServer.java:571,744`.

## 5. Lock hierarchy relevant to virtual threads

Locks held **across device I/O** (a VT parking on any of these unmounts; the I/O itself is compensated):

1. `TransactionManager.walAppendingLock` (fair, :258, :1345) ⟶ `DefaultCatalogPersistenceService.walWriteLock`
   (:386, :2837) ⟶ WAL `FileChannel.write` under the JDK's `positionLock` monitor (§3) (+ `force(true)` when
   `syncOnCompletion`, `AbstractMutationLog.java:1450`).
2. `AbstractMutationLog.walSyncLock` (:242) around `force(true)` (:1290), rotation (:2219: tail write + force +
   close) and close (:1686). Taken by the batched syncer on the transaction executor; contended only by rotation.
3. `TransactionManager.trunkIncorporationLock` (fair, :262, :1404) ⟶
   `DefaultCatalogPersistenceService.checkpointLock` (:400, :2341) ⟶ per-file `WriteOnlyFileHandle.handleLock`
   (`tryLock(lockTimeoutSeconds)`, :390-438) ⟶ write + `getFD().sync()` / deferred force ⟶
   `BootstrapWriteOnlyFileHandle.handleLock` (:157-195) ⟶ `force(true)` (:217). The same `checkpointLock`
   instance is taken by the service ticker (`CheckpointCoordinator.java:209,407`) around `forcePendingSyncs`
   (:261-270), so a transaction-executor flush can park behind a ticker doing N fsyncs.
4. `DefaultEnginePersistenceService.walWriteLock` (:129, :493-922) ⟶ engine WAL write + force.
5. `ObsoleteFileMaintainer.directoryAccessLock` (:142) ⟶ `Files.delete` batches (service scheduler); the
   comment at :425-428 records the ordering rule "never while holding the catalog persistence service lock".
6. `OffHeapTrafficRecorder` monitor ⟶ `RingBufferSpanLock` monitor (`wait()`) ⟶ `FileChannel.write`
   (`DiskRingBuffer.java:1182`, :462-482) — the only monitor-based hierarchy, service scheduler only.
7. `OffsetIndex.readFilesLock` (:2821, :2850) ⟶ `RandomAccessFile` open — short, request path, pool miss only.
8. `SessionRegistry.registrationGate` (RW, :201) / `exclusiveAdmissionLock` (:247) — memory only; the drain park
   at :429 runs after the write lock is released (:367-368).

Memory-only `ReentrantLock`s (no I/O inside): `TransactionManager.conflictResolutionLock` (:248) and
`catalogPropagationLock` (:266), `ConflictResolutionAndWalAppendingTransactionStage.pendingDurabilityLock`
(:114), `DefaultCatalogPersistenceService.historyHorizonLock` (:486) and `cpsvLock` (:514),
`ReadWriteKeyCompressor.lock` (RW, :125), `CacheEden.lock` (:146), `core/buffer/RingBuffer.lock` (:75),
`Scheduler.bufferLock` (:78), `DelayedAsyncTask.schedulingLock` (:118), `DefaultChangeCaptureSubscription.lock`
(:121), `ChangeSystemCaptureSharedPublisher.lock` (:108), `ChangeCatalogCaptureSharedPublisher.lock` (:84),
`TransactionLocations.lock` (:48), `GrpcOutboundGate.lock` (:149, condition wait inside).

Fairness: the four `TransactionManager` locks are fair `ReentrantLock(true)`. Fairness lives in the AQS queue and
is independent of thread kind, so nothing changes under VTs. What changes is that a parked stage task no longer
occupies a carrier, so the `transactionThreadPool.maxThreadCount` ceiling stops being the back-pressure it is
today — the `queueSize`-bounded `SubmissionPublisher` buffers (`TransactionManager.java:1998-2003`,
`ConflictResolutionAndWalAppendingTransactionStage.java:137`) become the only limiter on in-flight commits.

## 6. Validation recipe (do not run in this session)

**What to look for.** On JDK 21 a virtual thread pins its carrier when it parks inside a `synchronized`
block/method or with a native frame on the stack, when it blocks on a contended `monitorenter`, and in
`Object.wait()`. Blocking file I/O never unmounts; it is compensated (summary item 6). The two diagnostics below
are complementary: JFR records every pin above a threshold, the trace property prints a stack for pins that park.

1. **JFR event `jdk.VirtualThreadPinned`** — enabled by default with a 20 ms threshold in the installed JDK's
   `lib/jfr/default.jfc:75-79` (and `profile.jfc:75-79`). The event is emitted from
   `VirtualThread.parkOnCarrierThread` (`VirtualThreadPinnedEvent.begin()/commit()` around the carrier park,
   verified in the 21.0.12 bytecode), so it captures pins that **park**, with the stack of the parked VT. Lower
   the threshold to catch the short ones:

   ```
   -XX:StartFlightRecording=filename=vt-pinning.jfr,settings=profile
   # copy lib/jfr/profile.jfc and set
   #   <event name="jdk.VirtualThreadPinned"><setting name="threshold">1 ms</setting> ...
   jfr print --events jdk.VirtualThreadPinned vt-pinning.jfr
   jfr summary vt-pinning.jfr
   ```

   `jdk.VirtualThreadSubmitFailed` (enabled by default, `default.jfc:81-84`) flags scheduler rejections;
   `jdk.JavaMonitorEnter` (`default.jfc:86-89`, 20 ms) shows contended monitor entries, which on a VT are pins.

2. **`-Djdk.tracePinnedThreads=full|short`** — read by `VirtualThread.tracePinningMode()` (the
   `"jdk.tracePinnedThreads"` constant is in the 21.0.12 class). When a continuation cannot yield,
   `VirtualThread$VThreadContinuation.onPinned` calls `PinnedThreadPrinter.printStackTrace(System.out, reason,
   printAll)` (verified via `javap`). `full` prints the whole stack, `short` only the frames holding monitors.
   Expected hits for evitaDB: `RingBufferSpanLock.acquireExclusive` (only if the service scheduler runs on VTs)
   and, under load, `InvertedIndex.refreshValueIdDirectory` contention.

3. **Thread dumps that include virtual threads**: `jcmd <pid> Thread.dump_to_file -format=json <path>` (the
   `Thread.dump_to_file` command string is present in the installed `libjvm.so`). The JSON groups virtual
   threads by scheduler/executor and shows carrier stacks; `jcmd <pid> Thread.print` still lists only platform
   threads. Grep the dump for `PINNED` / `TIMED_PINNED` states and for `parkOnCarrierThread`.

4. **Scheduler flags** — defaults quoted from the installed `VirtualThread.createDefaultScheduler` bytecode:
   - `-Djdk.virtualThreadScheduler.parallelism`: default `Runtime.getRuntime().availableProcessors()` — carrier
     threads normally running VTs.
   - `-Djdk.virtualThreadScheduler.maxPoolSize`: default `Integer.max(parallelism, 256)`; when set explicitly,
     parallelism is clamped to `Integer.min(parallelism, maxPoolSize)` — ceiling for carriers including those
     spawned by `Blocker` compensation.
   - `-Djdk.virtualThreadScheduler.minRunnable`: default `Integer.max(parallelism / 2, 1)` — minimum runnable
     carriers the pool tries to keep available.
   - Fixed: keep-alive `30 SECONDS`, `saturate = pool -> true`, async mode `true` — spare carriers retire after
     30 s; past `maxPoolSize` blocking proceeds uncompensated instead of throwing.

   For evitaDB's transaction path the interesting experiment is `maxPoolSize` = parallelism (no compensation):
   the WAL fsync then blocks a carrier outright, which makes fsync latency visible as scheduling latency for every
   VT.

5. **Where to put the probes**: run the commit-throughput and query benchmarks with
   `-Djdk.tracePinnedThreads=short` first (zero cost unless a pin happens), then JFR with the 1 ms threshold. A
   clean run on the request/transaction paths must show **no** `jdk.VirtualThreadPinned` events except transient
   `JavaMonitorEnter` contention on the `AttributeIndex` / `HierarchyIndex` / `InvertedIndex` monitors; any event
   whose stack contains `RingBufferSpanLock`, `OffHeapTrafficRecorder` or `Catalog.terminate` identifies the
   three sites catalogued above.
