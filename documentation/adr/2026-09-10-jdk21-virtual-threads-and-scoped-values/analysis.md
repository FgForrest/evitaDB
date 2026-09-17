# JDK 21 `ScopedValue` and Virtual Threads for evitaDB — architectural analysis

- **Status:** analysis complete, awaiting review. No production code was changed.
- **Date:** 2026-09-08, branch `1518-upgrade-to-jdk-21` at `a23e5f4be` (JDK 21 platform bump, #1518 / PR #1519).
- **Runtime analysed:** OpenJDK 21.0.12, Armeria 1.41.1, Netty 4.2.16.Final, graphql-java 25.0, gRPC 1.83.1,
  logback 1.6.1, OpenTelemetry 1.65.0, Kryo 5.6.2, Byte Buddy 1.17.8.
- **Method:** static source analysis only. No compilation, test, benchmark or throwaway experiment was run — the
  machine was reserved for a JMH run during the whole session. JDK-internal facts were verified with `javap` on
  the installed runtime (the JDK `src.zip` on this box is a dangling symlink); statements that could not be
  verified that way are labelled *from knowledge*. Armeria was read from the `-sources.jar` artifacts in `~/.m2`
  (all six artifacts the project depends on); there is no Armeria git checkout on this machine.
- **Evidence:** seven detailed reports under `parts/`, one per investigation. Every claim below traces to one of
  them or to a `file:line` in the repository. Where this document and a part disagree, the part carries the
  line-level evidence and this document carries the synthesis.

| Part | Covers |
|---|---|
| `parts/01-armeria-netty.md` | Armeria/Netty execution model, context storage, response marshalling, executor slot |
| `parts/02-threadlocal-inventory.md` | All 21 `ThreadLocal`s, classification, cache cost model, `ScopedValue` fit |
| `parts/03-pinning-and-blocking.md` | `synchronized` inventory, blocking waits, JDK 21 pinning rules, workloads |
| `parts/04-preview-and-docs.md` | Preview mechanics, every `--enable-preview` surface, documentation impact |
| `parts/05-request-flow.md` | Per-API flow from event loop to executor and back, backpressure, waits |
| `parts/06-transaction-pipeline.md` | Commit pipeline, transaction executor users, thread-bound state, joins |
| `parts/07-scheduler-inventory.md` | Service scheduler internals and all 25 timers plus long-running jobs |

---

## 1. Decisions at a glance

| Proposed change | Category | Verdict |
|---|---|---|
| Ship `ScopedValue` on JDK 21 (`--enable-preview`) | DO NOT CHANGE | **NO-GO** |
| Lexical-scope binding facade over the context `ThreadLocal`s, ThreadLocal-backed today | RECOMMENDED | **GO** |
| Switch that facade to `ScopedValue` when the runtime baseline is JDK 25 | RECOMMENDED (deferred) | GO, later |
| Virtual threads for Netty event loops | DO NOT CHANGE | **NO-GO** |
| Virtual threads for the request executor | DO NOT CHANGE (now) | **NO-GO** |
| Asynchronous session close for REST and GraphQL (removes the only unbounded park) | RECOMMENDED | **GO** |
| Dedicated commit-pipeline executor, separated from write-request work | RECOMMENDED | **GO** |
| Virtual-thread-per-task for pipeline, flush and lifecycle work | RECOMMENDED WITH BENCHMARK GATE | conditional |
| Scheduler split: small platform timer pool plus a job executor | RECOMMENDED | **GO** |
| Virtual-thread-per-task job executor (after the pinning fix) | RECOMMENDED WITH BENCHMARK GATE | conditional |
| Redesign per-thread caches | DO NOT CHANGE | — |

**Why, in ten sentences.**

1. evitaDB is an in-memory database whose request work is CPU-bound; the only I/O on the read path is file
   I/O, which on JDK 21 never unmounts a virtual thread — it blocks the carrier and is compensated by spawning
   another. A virtual-thread request executor would therefore behave like an elastic platform pool with less
   control, not like a scalable blocking server.
2. The bounded request pool with its threads-first queue (#1204, `da7e4f6ae`) is the right shape for CPU plus
   compensated I/O, and it is also the *only* admission control the server has. Nothing replaces it for free.
3. The genuinely blocking work is elsewhere: the commit pipeline's fsync and flush, warm-up flushes, backups,
   exports, JFR and traffic recordings, S3 long-polls, and the parked session closers.
4. The one structural hazard found is not a thread-kind problem: REST and GraphQL mutations park a
   *transaction-pool* thread in an uninterruptible `join()` on a commit that can only complete on that same
   bounded pool. The fix is asynchronous close and a dedicated pipeline executor, both executor-kind-agnostic.
5. The service scheduler mixes 25 timers — including the durability checkpoint ticker and CDC heartbeats — with
   multi-hour jobs on one fixed pool, and can be exhausted by client-submitted backups today. Splitting timers
   from jobs is the real win; virtual threads are a reasonable but optional carrier for the jobs.
6. No pinning risk exists on the request or transaction executors on JDK 21; the single `Object.wait()` in the
   code base sits on the traffic-recorder path, which only matters if service jobs move to virtual threads.
7. `ScopedValue` fits 16 of the 21 `ThreadLocal`s on design grounds, but in JDK 21 it is a preview API: every
   class that uses it loads only on exactly JDK 21 with `--enable-preview`, the API used on 21 was changed or
   removed in JDK 22–23 and finalised only in JDK 25, and it cannot implement Armeria's context SPI at all.
8. `ScopedValue` also does not propagate across executor submission (only through `StructuredTaskScope`, another
   preview), so it buys nothing at the boundaries evitaDB actually crosses — all of which already re-establish
   context explicitly.
9. The design benefits of scoped bindings (leak-proof lifetime, no `remove()` discipline, no interrupt-leak
   coupling) can be had now behind a project-internal facade; the JDK type is a one-class swap later.
10. Two `ThreadLocal`s are genuine per-thread caches. Both stay; only one needs a benchmark, and only under a
    per-request virtual-thread model that this analysis does not recommend.

---

## 2. Current threading and executor architecture

### 2.1 Execution-flow overview

```
                     clients (gRPC / HTTP/2 / WebSocket)
                                   |
      +----------------------------v-----------------------------+
      |  Netty event loops  (platform, N = availableProcessors)   |  ExternalApiServer.java:539-554
      |  - TLS, HTTP/2, protobuf/gRPC framing, JSON aggregation   |
      |  - REST: JSON parse, Query build, session create  (!)     |  parts/05 §1
      |  - GraphQL: envelope parse                                |
      |  - WebSocket paths: whole session lifecycle       (!)     |
      +------+---------------------------+-----------------------+
             | CancellationSupport       | executeWithClientContext
             | (REST/GraphQL)            | (gRPC, 43 sites)
             v                           v
  +---------------------------+   +----------------------------+   +-----------------------------+
  | Evita-request-N           |   | Evita-transaction-N        |   | Evita-service-N             |
  | ThreadPoolExecutor        |   | ThreadPoolExecutor         |   | ScheduledThreadPoolExecutor |
  | threads-first, bounded    |   | threads-first, bounded     |   | fixed size, unbounded queue |
  | code C..4C q100           |   | code C..4C q100            |   | code 2C, registry 2*q       |
  | YAML 4..16 q100           |   | YAML 4..16 q100            |   | YAML 16, registry 200       |
  |---------------------------|   |----------------------------|   |-----------------------------|
  | queries (CPU)             |   | commit pipeline (3/catalog)|   | 25 timers (checkpoint fsync,|
  | entity fetch (file read,  |   |  stage1 CPU+WAL write      |   |  session killer, CDC beats, |
  |  compensated)             |   |  WAL sync task (fsync)     |   |  sweeps, purges, drainer)   |
  | (de)serialization (CPU)   |   |  stage2 CPU replay+flush   |   | jobs: backup/restore/export,|
  | gRPC writes + async close |   | REST/GraphQL write bodies  |   |  JFR, traffic recorder, S3  |
  | gRPC gated streams (park) |   |  + their commit join  (!)  |   | Armeria blockingTaskExecutor|
  | CDC deliveries            |   | warm-up flush fan-out      |   |  (Lab static files, TLS)    |
  | commit completions        |   | engine operators, loads    |   |                             |
  +---------------------------+   +----------------------------+   +-----------------------------+
             ^                                 |
             | CommitProgressRecord.thenRunAsync(requestExecutor)   CommitProgressRecord.java:210-225
             +---------------------------------+
```

`(!)` marks findings that were not in the mental model the task started from; see §3.2.

### 2.2 Executors, pools and threads

Every executor, thread pool, event loop and manually created thread found in main code. Sizes quote the code
defaults (`ThreadPoolOptions.java:85-101`) and the shipped server defaults (`evita-configuration.yaml:4-18`),
which override them. `C` = `availableProcessors()`.

- **Netty event loops** — `ExternalApiServer.java:539-554`, `EventLoopGroups.builder().numThreads(C)`
  (`ApiOptions.java:86`). Platform, epoll where available, one group for I/O and services. Lifecycle: server.
  Work: TLS, framing, decoding, all Armeria service-method bodies (see §3). CPU-bound; must never block.
  Backpressure: Armeria `requestTimeoutMillis` (YAML 2 s), `maxRequestLength` 2 MiB, idle timeout 60 s; no
  `maxNumConnections`, no HTTP/2 stream-cap override. Thread-local state: Armeria `RequestContext`
  (`FastThreadLocal`), gRPC `Context`, MDC set by `TracingContext`.
- **Request executor `Evita-request-N`** — `Evita.java:459-463`, `ObservableThreadExecutor.java:201-232`.
  `ThreadPoolExecutor(core = min, max = max, 60 s, TaskQueue)`; `TaskQueue` grows the pool to `max` before it
  queues, then a backlog of `queueSize`, then `RejectedExecutionException`. Daemon threads, priority 8 (code) /
  5 (YAML). Test mode swaps in `ImmediateExecutorService`. Work: everything a session call does — query
  planning and evaluation, entity-body fetch through `OffsetIndex` and `RandomAccessFile`, serialization, gRPC
  reads *and writes* (all 43 `executeWithClientContext` sites), REST/GraphQL reads, GraphQL root fields, CDC
  deliveries, commit-completion callbacks, readiness probes. Class: CPU-BOUND with compensated file reads; a
  few bounded parks (parts/03 §2, parts/05 §8). It **is** the admission limit for the server (§13).
- **Transaction executor `Evita-transaction-N`** — `Evita.java:464-471`, same class and defaults. Never the
  direct executor, even in tests, "because it uses thread local variables for transaction management". Work:
  the commit pipeline (three tasks per catalog: stage 1 conflict resolution + WAL append, the WAL sync task,
  stage 2 trunk incorporation + flush), REST and GraphQL mutation bodies *and their commit join*, warm-up
  flush fan-out (one task per collection), engine lifecycle operators, catalog loading at boot, termination.
  Class: MIXED — stage 2 is CPU-dominant (trunk re-apply measured at ~38 % of application CPU, I/O ~1 %,
  fsync ~7 ms per transaction; `2026-07-27-write-path-performance-tuning/README.md:86,170-172`).
- **Service scheduler `Evita-service-N`** — `Evita.java:454-458`, `Scheduler.java:184-196`.
  `ScheduledThreadPoolExecutor(maxThreadCount)`: fixed size (code 2C, YAML 16), unbounded `DelayedWorkQueue`,
  non-daemon (factory does not set the flag), priority 1 (code) / 5 (YAML). A separate `ArrayBlockingQueue`
  registry of `2 × queueSize` slots bounds `ServerTask`s only. Work: 25 periodic or self-rescheduling timers
  (24 `DelayedAsyncTask`, 1 `scheduleAtFixedRate`), long jobs (backup, full backup, restore, traffic export,
  JFR recorder, traffic recorder, S3 refresh), and Armeria's `blockingTaskExecutor` (`ExternalApiServer.java:571`;
  in this configuration only Lab static-file serving and TLS setup reach it). Class: BLOCKING plus timers.
- **Observability probe pool** — `ObservabilityProbesDetector.java:261-266`, `ForkJoinPool(parallelism =
  number of APIs)`, used with `CompletableFuture.runAsync` for readiness probes over OkHttp; the calling
  request thread waits `allOf().get(10 s)` (`:191`).
- **`ForkJoinPool.commonPool()`** — `Evita.java:2095-2129` shutdown fan-out and one CDC stream-death path
  (`AbstractChangeCaptureSubscriber.java:259`). No request work.
- **Manual threads** — the JVM shutdown hook (`EvitaServer.java:202`); benchmark harness threads
  (`evita_test/evita_performance_tests`, out of scope).
- **Java driver client pool** (`EvitaClient.java:795-801`, `ClientTaskTracker.java:100-103`, `CdcCallbackThread`)
  — client library, out of scope for the server decision but relevant to §7.6 and §20.

### 2.3 Where context lives today

- `Transaction.CURRENT_TRANSACTION` — bound per session call by `EvitaSessionProxy` (`:493`), per replay and
  per flush+merge on the stage 2 thread (`TransactionManager.java:371, 2034`). At most one per thread; nested
  re-binding of the same transaction is a no-op; a different one is a premise failure.
- `WarmUpSavepoint.CURRENT` — one savepoint per root entity mutation on the upserting thread, warm-up only.
- `TracingContext` MDC keys + `CLIENT_LABELS` — set on the event loop or worker by the API layer, captured at
  task construction and restored on the worker by `ObservableThreadExecutor` (`:698, :1000-1016`).
- Armeria `RequestContext` — event loop only; `null` on every `Evita-*` thread (parts/01 §2.6).
- gRPC `io.grpc.Context` — captured on the event loop and re-entered on the worker with `grpcContext.run`
  (`EvitaSessionService.java:198-206`).
- OpenTelemetry `Context` — plain `ThreadLocal`, scopes closed lexically (parts/02 §6.3).

The complete boundary map is in §12.

---

## 3. Armeria / Netty execution model relevant to evitaDB

### 3.1 The mental model, verified

```
Netty / Armeria EventLoop  -->  application dispatch boundary  -->  evitaDB executor  -->  query / transaction
```

holds, with these confirmations (parts/01, parts/05):

- **Every Armeria service-method body starts on the event loop.** HTTP: `HttpServerHandler.serve0` under
  `reqCtx.push()`. gRPC: `GrpcService` is built without `useBlockingTaskExecutor(true)` and no service carries
  `@Blocking`, so `FramedGrpcService.startCall` and `AbstractServerCall.invokeOnMessage` run
  `listener.onMessage()` inline on the loop (`FramedGrpcService.java:297-313`).
- **The handoff is evitaDB's.** REST/GraphQL: `EndpointExecutionContext.executeAsyncIn*ThreadPool` →
  `CancellationSupport.submitWithCancellation` → `ObservableThreadExecutor.execute`. gRPC:
  `EvitaSessionService.executeWithClientContext` captures `ServiceRequestContext.current()`, the metadata and
  `io.grpc.Context` on the loop, then submits (`:187-221`). Nothing uses `ctx.blockingTaskExecutor()`.
- **The response returns to Armeria from any thread, and Armeria marshals it itself.** `HttpResponseWriter`
  and `WebSocketWriter` go through a multi-producer queue to the channel's event loop; gRPC `sendHeaders`,
  `sendMessage`, `close` and `request` do `inEventLoop()`-else-`execute`; `HttpResponse.of(CompletableFuture)`
  subscribes upstream on the loop. A virtual thread is never `inEventLoop()` (reference comparison against the
  loop's platform thread), so behaviour equals today's platform workers, one hop per call.
- **Request cancellation** callbacks (`whenRequestCancelling`) run on the event loop for timeouts and peer
  resets, or on the calling thread for explicit cancels; `task.cancel()` is non-blocking, which is the only
  requirement. Cancellation reaches the worker as `Thread.interrupt()` delivered through the task state machine
  (`ObservableThreadExecutor.java:854-890`) and is consumed by the woven `@Interruptible` checkpoints.

### 3.2 Corrections to the mental model

- **REST does real evitaDB work on the event loop before the handoff.** `HttpRequest.aggregate()` completes on
  the request's event loop, so the non-async `thenApply` chain in `EndpointHandler.readRequestBody`
  (`:249-269`) — Jackson parse, constraint deserialization, `Query` reconstruction
  (`QueryOrientedEntitiesHandler.java:119-150`) — and the session creation in `beforeRequestHandled`
  (`RestEndpointHandler.java:124-127`, registry locks with up to 500 ms wait during a `POSTPONE` suspension)
  all run on the loop. GraphQL parses only the JSON envelope there; gRPC parses EvitaQL on the worker.
- **The WebSocket paths run session lifecycles and graphql-java on the event loop** (`GraphQLWSSubProtocol.java:168`,
  `ChangeCatalogCaptureStreamHandler.java:75-90`); pushes come from the request pool.
- **Lab static files are served through Armeria's `HttpFile`, i.e. on the service scheduler** — the one place
  where the `blockingTaskExecutor` slot carries request work in this configuration.

These are executor-independent facts; they are listed under incidental findings (§20.3), not as part of the
virtual-thread decision.

### 3.3 Constraints the application executor must respect

From parts/01 "Constraints", condensed:

1. The `blockingTaskExecutor` slot needs a `ScheduledExecutorService`; `Executors.newVirtualThreadPerTaskExecutor()`
   cannot be passed without an adapter. Nothing in evitaDB's configuration dispatches request work there, so the
   simplest option is to leave a scheduled executor in the slot whatever else changes.
2. Never block the event loop; keep the handoff. Producer calls need no thread affinity.
3. The Armeria context is absent on workers unless pushed; if pushed, it must be popped on the same thread
   inside the task. Pushing on a virtual thread allocates Netty's `InternalThreadLocalMap` (`Object[32]` plus
   the map, roughly 200–300 bytes) once per thread, i.e. once per request.
4. A `ScopedValue` cannot implement `RequestContextStorage`: `push()` returns the previous context and
   `pop(current, toRestore)` is an imperative, cross-frame protocol invoked from Netty listeners, and the
   storage is a JVM-global singleton chosen at class-initialisation (parts/01 §2.7). evitaDB's own scoped
   bindings must be opened by evitaDB's task wrapper.
5. Keep buffers heap-backed on workers; every pooled Netty buffer is allocated and released on the loop today,
   and virtual threads get no allocator cache (both the pooled and the 4.2-default adaptive allocator gate
   caches on `FastThreadLocalThread` identity).
6. `req.aggregate()` completes on the loop; redesigns of the handoff must not assume otherwise.

---

## 4. Findings from the Armeria 1.41.1 sources

- **No virtual-thread support of any kind.** Zero hits for `VirtualThread`, `Thread.ofVirtual`,
  `newVirtualThreadPerTaskExecutor`, `isVirtual(`, `Loom` or `ScopedValue` across all six sources jars and
  their `META-INF`. No configuration, no detection, no recommended pattern, no tests (tests are not shipped
  in sources jars, so their absence cannot be established either way).
- **Netty 4.2.16 has two touchpoints Armeria never uses**: a reflective `Thread.isVirtual` probe in
  `PlatformDependent0` with no callers, and `FastThreadLocalThread.runWithFastThreadLocal(Runnable)`, an
  opt-in wrapper that makes a virtual thread behave like a `FastThreadLocalThread` for one task (and would also
  enable per-virtual-thread allocator caches, which is undesirable for per-task threads).
- **Context propagation** is a Netty `FastThreadLocal` (`ThreadLocalRequestContextStorage.java:31`) with a
  JDK `ThreadLocal` fallback on non-Netty threads. Armeria propagates it only through its own wrappers
  (`ContextAwareExecutor`, `ctx.blockingTaskExecutor()`, `makeContextAware`), none of which evitaDB uses;
  consequently `AppLogJsonLayout` never emits `duration_ms` from worker threads and `GrpcOutboundGate`
  captures the context on the loop before the handoff. Nothing changes here with virtual threads.
- **`blockingTaskExecutor` semantics**: `ServerBuilder.blockingTaskExecutor(ScheduledExecutorService, boolean)`;
  Armeria's own default is a `BlockingTaskExecutor` built on a `ScheduledThreadPoolExecutor`. A
  `Thread.ofVirtual().factory()` could be handed to that builder, but the executor would still pool the threads.
  In evitaDB's configuration only `HttpFile` (Lab GUI), TLS setup, `GracefulShutdownSupport` (already tolerant
  of a non-`ThreadPoolExecutor`) and a Micrometer binding (behaviour for unknown executor types not verifiable
  here) touch it.
- **Blocking-service support** exists (`@Blocking`, `useBlockingTaskExecutor`) but is not used; enabling it
  would route gRPC bodies to the service scheduler through a *sequential* executor per call — not a model
  evitaDB wants, since it already owns the handoff.
- **MDC / logging**: Armeria's `RequestContextExporter` and `armeria-logback` are not on the classpath;
  evitaDB uses its own MDC keys via `TracingContext`.

---

## 5. Complete `ThreadLocal` inventory and classification

Twenty-one `ThreadLocal`s exist in main code (7 `evita_engine`, 2 `evita_common`, 1 `evita_api`, 1 `evita_query`,
3 `evita_store`, 6 `evita_external_api`, 1 benchmark harness). No `InheritableThreadLocal`, no
`FastThreadLocal`. **Every one is set and cleared on the same thread; none reads a binding made by another
thread.** Cross-executor movement is always an explicit re-establishment (§12). The full table with evidence is
§22; the classification summary:

- **Category A — execution context (11):** `Transaction.CURRENT_TRANSACTION`, `WarmUpSavepoint.CURRENT`,
  `EntitySchemaContext.ENTITY_SCHEMA_SUPPLIER`, both `CatalogSchemaStoragePart` accessors, `ParserExecutor.CONTEXT`,
  `CurrentSessionRecordContext.SESSION_SEQUENCE_ORDER`, `TrieNodeSerializer.ARRAY_TYPE`, `TracingContext.CLIENT_LABELS`
  (with four MDC keys), `AssociatedDataMutationConverter.CLIENT_VERSION`, `ClientSessionInterceptor` session
  descriptor (driver).
- **Category B — per-thread performance scratch (3):** `FrontCodedStringColumn.SCRATCH`, the two
  `Crc32CWrapper` scratch locals.
- **Category C — mutable thread state (5):** `Catalog.PENDING_TRIGGER_REBUILDS` (a per-frame stack),
  `ObservabilityTracingContext.parentContextAvailable` (toggle), `ErrorOriginLogger.REPORTING` and
  `ErrorMonitor.REPORTING` (re-entrancy guards), `EvitaClient.timeout` (driver, per-thread stack of overrides).
- **Category D — framework/infrastructure:** logback MDC (two plain `ThreadLocal` maps, no inheritance),
  gRPC `Context` and OpenTelemetry `Context` (plain `ThreadLocal`, re-entered explicitly), Armeria
  `RequestContext` (Netty `FastThreadLocal`, event loop only), Kryo (pooled, never per-thread).
- **Test seam (1):** `DefaultCatalogPersistenceService.CURRENT_TIME_MILLIS`. **Harness (1):**
  `AbstractArtificialBenchmarkState.session`.

`ScopedValue` verdicts: 13 clean candidates, 3 after a restructure (`Transaction`, `WarmUpSavepoint`,
`PENDING_TRIGGER_REBUILDS`), 3 guards/toggles that gain nothing, 2 scratch buffers that must stay per-thread,
1 public non-lexical driver API (`EvitaClient.timeout`), 1 test seam, 1 harness. Details in §7 and §22.

---

## 6. Per-thread cache analysis (Category B)

Thread population today: request `C..4C`, transaction `C..4C`, service `2C`, plus `C` event loops — on 16 cores
up to ~176 long-lived platform threads, each able to hold one instance of every holder. Under a per-request
virtual thread the holder count equals the number of concurrently live requests and each holder dies with its
request.

- **`FrontCodedStringColumn.SCRATCH`** (`:226`; javadoc `:85-95, 170-191`). One `DecodeScratch` per thread:
  eight fields, a 48-byte initial buffer, doubling growth, never shrunk; ceiling bounded by leaf size × longest
  keys ("kilobytes each … a 32-thread commit pool retains at most 32 holders"). Initialisation is trivial;
  the value is amortisation of the *grown* buffers across hops on the same thread. It runs on the write path
  (warm-up and stage 2 — stable threads) and on the read path (`findKeyPosition`, `decodeAt` — request
  threads). Under a per-request virtual thread the read path would re-create and re-grow it per request,
  returning the young-generation churn the holder was introduced to remove, as one growth series per request.
  Verdict: **KEEP AS-IS** for the write path; **NEEDS BENCHMARK** only under a virtual-thread-per-request model,
  which §8 does not recommend; the design-preserving alternatives are carrying the scratch in the query
  execution context or a small striped pool like `CollationKeyCache`.
- **`Crc32CWrapper` scratch** (`:170-175`): one `CRC32C` and an 8-byte array, ~48 bytes, fixed size, write and
  commit paths only. Verdict: **KEEP AS-IS** under every model.
- **Things that look like per-thread caches but are not** — and are therefore already virtual-thread-neutral:
  `CollationKeyCache` (a 16-way pool striped by `threadId() & 15`, chosen *instead of* a `ThreadLocal` after
  profiling showed the `get()` cost more than the collation work, `:147-157`; with thousands of live threads the
  collision rate rises — `COLLATOR_STRIPES` is the knob; **KEEP, NEEDS BENCHMARK** at the target concurrency);
  every Kryo instance (`kryo.util.Pool`, `LinkedBlockingQueue`-backed, bounded at 16 for WAL/engine pools and
  `20 × C` for offset-index read pools — exhaustion creates and discards, never multiplies); `SharedBufferPool`,
  `OffsetIndex.decompressionPool`, `OffHeapTrafficRecorder.copyBufferPool` (global, bounded);
  `ObservableOutputKeeper` (one output per *file*, lease flag that asserts rather than waits).

Two prior records already reasoned the same way: the 2026-08-19 usage-statistics ADR rejected hand-rolled
`ThreadLocal` striping as "hostile to virtual threads", and the 2026-08-24 fulltext prototype notes that a
per-thread analyzer cache "stops making sense" under per-task threads.

---

## 7. `ScopedValue` suitability

### 7.1 JDK 21 facts (verified with `javap` unless marked)

- `java.lang.ScopedValue` is class-level `@PreviewFeature(feature=SCOPED_VALUES)`. API on 21: `newInstance`,
  `where(k, v) → Carrier`, static `runWhere`, `callWhere(…, Callable)`, `getWhere(…, Supplier)`, instance `get`,
  `isBound`, `orElse`, `orElseThrow`; `Carrier.where`, `Carrier.get(key)`, `Carrier.run(Runnable)`,
  `Carrier.call(Callable) throws Exception`, `Carrier.get(Supplier)`. `ScopedValue$CallableOp` does not exist.
- `get()` first probes a 16-slot per-thread cache (`Cache.TABLE_SIZE = 16`, primary and secondary slot), then
  `slowGet()` walks the binding chain. Bindings are inherited by child threads only through
  `StructuredTaskScope` (`Thread.inheritScopedValueBindings(ThreadContainer)`); plain `Thread.start()` and
  every `ExecutorService.submit` start with `NEW_THREAD_BINDINGS`, i.e. nothing.
- javac marks class files per *source file*: minor version 65535 is written only for files that used a preview
  feature (`ClassWriter.writeClassFile`, bytecode quoted in parts/04 §A.4). A marked class loads only on exactly
  JDK 21 with `--enable-preview`; JDK 22+ refuses it (`UnsupportedClassVersionError`, *from knowledge* for the
  HotSpot message text). Any compiler that reads a marked class also needs the flag, and `--enable-preview`
  requires `--release` equal to the compiler's own release, so the toolchain becomes an exact pin.
- *From knowledge:* JDK 22 (JEP 464) retyped `Carrier.call` to `CallableOp` and removed `Carrier.get(Supplier)`
  and `getWhere`; JDK 23 (JEP 481) removed static `runWhere`/`callWhere`; JDK 24 (JEP 487) made `orElse(null)`
  throw; JDK 25 (JEP 506) finalised the JDK 23/24 shape. Code written against the 21 surface does not compile
  on the final API unless confined to `newInstance`, `where`, `Carrier.where`, `Carrier.run`, `Carrier.get(key)`,
  `get`, `isBound`, `orElse` (non-null), `orElseThrow`.

### 7.2 Design fit

The pattern the task prefers —

```java
ScopedValue.where(CONTEXT, context).run(() -> processRequest());
```

— is already how most of the code base is written: 13 of the 21 sites set and clear inside `try/finally`
around a lambda on one thread, and every callback reader (Kryo serializers, ANTLR visitors, gRPC interceptors)
runs on that same thread within the scope (parts/02 §3). Nesting is either a no-op re-bind of the same value
(`Transaction`), rejected (`WarmUpSavepoint`), or an explicit deque (`PENDING_TRIGGER_REBUILDS`,
`EntitySchemaContext`), all of which a scoped binding expresses natively. Exceptions cannot leak a binding
past its scope — which is exactly the class of bug the `WarmUpSavepoint` ordering test (`:332-361`) and the
interrupt-flag state machine in `ObservableThreadExecutor` exist to prevent today.

Three sites are non-lexical and must be restructured before any scoped binding, JDK or facade, can carry them
(parts/02 §5):

1. `Transaction#close()` removes the binding in `finally` (`Transaction.java:517`), and in the main path this
   fires *inside* the proxy's bound scope (`EvitaSession.java:1970`). With a scoped binding the transaction
   stays bound until the scope ends, so `getTransaction()` must treat a *closed* transaction as absent, and the
   code between `:1970` and scope exit must be audited for reads that rely on "no transaction".
2. `TransactionalUnorderedIntArray.assembleFromPagesInBase` and `bulkLoadInBase` (`:170-183, :213-226`)
   *suspend* the binding so a bulk build lands in the committed base. That becomes a nested re-binding to
   `null` (`where(CURRENT, null).run(...)`; binding `null` is permitted in 21 — *from knowledge* for 25), and
   `isTransactionAvailable()` becomes `orElse(null) != null` rather than `isBound()`. The public
   `bindTransactionToThread`/`unbindTransactionFromThread` pair then retires.
3. `WarmUpSavepoint` is opened at `LocalMutationExecutorCollector.java:443` and closed in `finish()` from the
   level-0 `finally` (`:573-578`); `execute` must be restructured so one scope encloses the apply loop and
   `finish()`, with `commit()`/`rollback()` choosing the outcome inside it.

`Catalog.PENDING_TRIGGER_REBUILDS` becomes a per-frame binding (deletes the deque and the leaked empty
`ArrayDeque` left by `withInitial`). `ObservabilityTracingContext.parentContextAvailable` should be redesigned
onto `Span.current()` rather than migrated. `AssociatedDataMutationConverter.CLIENT_VERSION` goes with #538.
The two re-entrancy guards and the test clock seam stay `ThreadLocal`s.

### 7.3 What `ScopedValue` does *not* give evitaDB

- **No propagation across executors.** Every boundary in §12 crosses an `ExecutorService.submit`; scoped
  bindings do not follow. The code base already re-establishes context explicitly at each boundary
  (`TracingContext.captureContext()`/`setContext()`, `grpcContext.run`, `executeInTransactionIfProvided` with a
  captured reference), which is the correct pattern for both `ThreadLocal` and `ScopedValue`. This does not
  change with virtual threads either.
- **No Armeria integration.** §3.3 item 4.
- **No proven performance gain.** The 5.25 % figure in `Transaction.java:331-350` is the `ThreadLocal` lookup
  share on the sort-attribute insert profile (#1332), largely mitigated by the "resolve once, pass down" rule
  already applied. `ScopedValue.get()` is a different probe (16-slot cache, then chain walk), plausibly cheaper
  in the hot case, but that is a benchmark result, not a property — and the measurement can only be taken on a
  `--enable-preview` build (§18, variant C).

### 7.4 Cost of shipping it on JDK 21

parts/04 enumerates 40+ surfaces (root pom compiler/surefire/javadoc, two module poms with
`combine.self="override"`, JShell in documentation tests, 25 `@Fork` sites, `.mvn/jvm.config`, Docker
entrypoint, dist and dev launchers, both benchmark scripts, tracked IntelliJ config, CI pins) and the blast
radius: every embedded user of `evita_db`, the Docker image, `dist.zip` and anyone running `java -jar` is
pinned to **exactly** JDK 21 with the flag; if `evita_api` is touched (`TracingContext.CLIENT_LABELS`), every
Java driver user too. Third-party bytecode tooling on the consumer side must accept minor version 65535
(JaCoCo 0.8.15 and Byte Buddy 1.17.8 do — verified; the project's build-time interruption weaving and the
runtime error-monitoring agent are both Byte Buddy).

### 7.5 Verdict

- **Shipping `ScopedValue` on JDK 21: NO-GO.** The lock-in (exact-release pin for every consumer), the API churn
  between 21 and 25, and the absence of any propagation or Armeria benefit outweigh a leak-proofing benefit that
  can be obtained without the JDK type.
- **Preparing for it now: GO.** Introduce a small project-internal facade — working name `ScopedBinding<T>`
  (no `class`/`interface`/`record` of that name exists; re-run the naming check before creating it) — with the
  surviving JDK 25 API shape only: `where(value).run(Runnable)`, `where(value).call(...)` via a holder,
  `orElse(null)`, `isBound()`. Back it with a `ThreadLocal` today; migrate the 16 candidates; do the three
  restructures. When the runtime baseline is JDK 25 the implementation swaps to `ScopedValue` in one class and
  nothing else moves. This yields the design benefits — lexical lifetime, no `remove()` discipline, no
  suspend/rebind hand-rolling — in the current release line and turns the later JDK migration into a mechanical
  change.
- **The one thing the facade cannot give is the lookup-cost change**, if any; that is what variant C of the
  benchmark plan measures on a throwaway preview build.

### 7.6 Interaction with virtual threads

Under the recommended architecture (§14) request work stays on platform threads, so scoped bindings are opened
and closed on one thread by `ObservableThreadExecutor`'s task wrapper (request context) and by
`EvitaSessionProxy` (transaction). Where virtual threads *are* introduced (pipeline, jobs), each task is its own
thread, which makes binding leaks structurally impossible whichever storage backs the facade. The only
`ThreadLocal`-flavoured hazards a virtual-thread executor would add are in the driver: `EvitaClient.java:1593`
tests `Thread.currentThread() instanceof CdcCallbackThread`, which is unsatisfiable on a virtual thread (a
`close()` from a CDC callback would wait on itself if that executor went virtual); and unnamed virtual threads
make `ConcurrentSessionAccessException`'s thread names empty (`EvitaSessionProxy.java:639-641`).

---

## 8. Virtual threads for request processing

**Why the executor exists.** It is the place where the event loop's work becomes evitaDB's work, and it is the
server's admission control: `maxThreadCount` running plus `queueSize` waiting, threads-first, then
`RejectedExecutionException` (surfaced today as HTTP 500 / gRPC `INTERNAL`, plus the JFR
`BackgroundTaskRejectedEvent` and the `INPUT_QUEUES_OVERLOADED` liveness signal). Its size limits CPU
oversubscription, concurrency and memory at once. The #1204 study (`da7e4f6ae`) replaced a `ForkJoinPool` with
this shape because the workload is "mixed blocking/CPU" and the pool must be sizeable independently of
`availableProcessors()`; `ThreadPoolOptions` documents the ~5×CPU point beyond which throughput halves from
context-switch thrash.

**What the workload is** (parts/03 §4, parts/05 §8):

- CPU-BOUND: query planning and evaluation (bitmaps, filtering, sorting, histograms), DTO conversion, Kryo /
  protobuf / Jackson serialization, transactional-layer writes for ALIVE write sessions, warm-up upserts.
- BLOCKING but **compensated, not unmounted**: entity-body and storage-part reads through `RandomAccessFile`
  (`OffsetIndex.java:1852` → `ReadOnlyFileHandle` → `RandomAccessFileInputStream`), isolated-WAL spills, warm-up
  data-file writes. On JDK 21 every one of these is wrapped in `jdk.internal.misc.Blocker`: the virtual thread
  keeps its carrier and the scheduler spawns a spare carrier up to `maxPoolSize` (default `max(C, 256)`);
  beyond that, blocking proceeds uncompensated. In practice these are page-cache hits.
- Bounded parks (all `java.util.concurrent`, all unmount): gRPC gated streaming producers waiting for a slow
  consumer (`GrpcOutboundGate.awaitWritable`, stall timeout 300 s, three streaming services), readiness probes
  (`allOf().get(10 s)`), session admission during a `POSTPONE` suspension and calls into a session being
  force-closed (both `FutureAwaiter.awaitWithTimeout`, 500 ms, *interruptible*, surfacing as
  `SessionBusyException` → HTTP 400 / gRPC `INVALID_ARGUMENT`), and — for REST/GraphQL *reads* — no wait at
  all, because a read-only session closes synchronously.
- Unbounded parks: none on the request pool for gRPC (`close` is callback-based). Embedded callers park their
  own thread in `EvitaSessionContract.close()`; REST/GraphQL writes park a *transaction* thread (§9).

**Nested submission.** GraphQL root fields are submitted from a request task to the request or transaction pool
and composed as `CompletableFuture`s — no join; CDC deliveries and commit completions are submitted to the
request pool from elsewhere — no join. The only nested *join* on a bounded pool is the REST/GraphQL mutation
close (§9). Read-only sessions permit parallel access by design; read-write sessions are serialised by a
`Thread`-reference guard that works identically on virtual threads.

**Evaluation of "virtual thread per request".** The carrier-release benefit of a virtual thread applies to the
parks above — a handful, bounded, and concentrated in streaming — not to query execution or to the file reads,
which do not unmount at all. Against that:

- The bounded pool would have to be replaced by an explicit admission limit reproducing `maxThreadCount +
  queueSize` (a semaphore plus a bounded queue), or CPU-heavy queries would oversubscribe the cores exactly the
  way the #1204 measurement warned about. That is the same limiter with a different name.
- JDK 21 has a single, global virtual-thread scheduler (`jdk.virtualThreadScheduler.*`, no custom-scheduler
  API) with no time-slicing: a multi-second CPU-bound query on a virtual thread holds its carrier for the whole
  compute; any latency-sensitive virtual thread elsewhere in the process competes with it.
- Per-request costs appear that a pooled thread amortises: the `FrontCodedStringColumn` scratch is re-created
  and re-grown per request on the read path (§6); a logback MDC map and, if the Armeria context were pushed, a
  Netty `InternalThreadLocalMap` are allocated per request; deep query stacks (recursive formula evaluation)
  are copied to the heap on every park.
- `RequestThreadPoolStatisticsEvent`, the `INPUT_QUEUES_OVERLOADED` health signal and the generated
  metrics/JFR documentation lose their source; `threadPriority` becomes a no-op; unnamed virtual threads empty
  `%thread` in logs and the session-guard diagnostics; `ThreadMXBean` does not see virtual threads.

**Verdict: DO NOT CHANGE (now).** Keep the bounded platform pool for request processing. Revisit when either
(a) the read path acquires genuinely blocking I/O that unmounts (remote or network-backed storage), or (b) the
JDK baseline moves to 24+ where monitors no longer pin and per-executor schedulers may exist — and even then
only with the admission limit designed first. The streaming producers are the one request-side case with a
real park; if they ever become numerous enough to matter (today: three services, 300 s stall bound), the
better change is a callback-driven pump or a small dedicated streaming executor, not a whole-pool migration.

---

## 9. Virtual threads for transaction processing

**What the transaction executor really does** (parts/06):

- **The commit pipeline**: `txPublisher → Stage1 → Stage2`, one `SubmissionPublisher` each, buffer capacity
  `transactionThreadPool.queueSize` (100, rounded to 128), consumer tasks on an *unrejectable* wrapper over the
  pool. Each stage `request(1)`s, so each is single-consumer and strictly sequential by the `Flow` contract —
  not by lock and not by executor. Stage 1 of tx N+1 overlaps stage 2 of tx N. A third task, the self-re-dispatching
  WAL sync task, performs one `force(true)` per batch. Steady state: **three tasks per catalog**. Retries never
  touch the pool (they schedule a service-pool drainer).
- **Admission never parks.** `TM.commit` uses `offer(task, onDrop)`; a full buffer fails the commit with
  `TransactionException("Conflict resolution transaction queue is full!")`. `submit()` is never called.
- **Ordering guarantees are executor-independent.** WAL order and catalog-version monotonicity come from the
  single-consumer contract plus asserted counters; the four `TransactionManager` locks are timeout gates and
  drainer exclusion. Swapping the executor changes which thread runs consecutive `onNext` calls, which is
  already "any pool thread" today.
- **Blocking is modest and isolated.** Page-cache WAL writes on stage 1, one fsync per batch on the sync task,
  data-file writes plus the bootstrap fence on stage 2 (often deferred to the 1 s checkpoint ticker on the
  service pool). Stage 2 is CPU-dominant (replay + merge, multi-second rounds bounded by
  `flushFrequencyInMillis` = 10 s). The measured profile: I/O ~1 % of both hot threads, fsync ~7 ms/tx.
- **Thread-bound state the pipeline depends on.** `Transaction.CURRENT_TRANSACTION` is bound around each replay
  and around flush + merge on the stage 2 thread, and B+ tree page emission *silently emits nothing* if the
  flush runs on a thread other than the binding one ("confinement by consequence",
  `AbstractTransactionalBPlusTree.java:711-737`). A scoped binding for the duration of
  `executeInTransactionIfProvided` satisfies this as long as `Catalog.flush` stays inside that scope — it does.
- **Everything else on the pool**: REST mutation bodies (8 handlers) and GraphQL `WriteDataFetcher`s **and their
  commit `join()`**; warm-up flush fan-out (one blocking task per collection); engine lifecycle operators
  (create/drop/rename/go-live/upgrade/restore, orchestration under the engine-wide `engineStateLock` with
  bounded drains); initial catalog loads and post-upgrade retry; catalog termination; the unary gRPC restore.
  gRPC session calls, including every upsert and delete, run on the *request* pool and never park.

**The hazard the pool size encodes.** Three threads suffice for the pipeline; the size actually bounds the other
users. REST and GraphQL close the session on the thread that completed the write — a transaction-pool thread —
and `EvitaSessionContract.close()` is `closeNow(...).join()` with **no timeout** and **no interrupt response**.
The stage tasks that complete that join are unrejectable tasks on the same bounded pool, which are *queued*,
never given a new worker, when all `maxThreadCount` workers are parked. With 16 parked closers the pipeline
stalls until the dangling-commit sweep fails the records after `max(60 s, 5 × acceptance)` — 100 s by default.
The `safetyDeadlineMs` javadoc describes exactly this symptom ("a starved executor makes a perfectly healthy
commit look identical to a dropped one"). Three more caller-side joins exist on the same pool: warm-up schema
operations (`Catalog.java:3192, 3242, 3424`), non-transactional schema updates (`EvitaSession.java:2117-2126`),
and the go-live operator's nested flush.

**Would virtual threads help?**

- *Simplify the model* — partly. Per-task threads make the interrupt-leak and `ThreadLocal`-leak hazards the
  code guards against (`ObservableThreadExecutor`'s state machine, `WarmUpSavepoint`'s ordering test) structurally
  impossible, and the parked closers stop occupying carriers. They also remove the "separate pool because of
  thread-locals" reason once the facade is in (§7.5).
- *Reduce blocked platform threads* — yes for the closers, warm-up flushes and drains; marginal for the
  pipeline (three tasks).
- *Little effect because CPU-bound* — true for stage 2, which is the cost centre; a virtual thread on a
  multi-second replay is a carrier held for seconds on the global scheduler (§8).
- *Interfere with per-thread caches* — no: WAL and data-file outputs are pooled or single-owner; Kryo is
  pooled; the only scratch holder is re-created per task at kilobyte cost.
- *Require explicit concurrency control* — the pipeline already has it (buffer of 128 + fail-fast `offer`;
  registry-level `ServerTask` limits for restore). Unbounded virtual threads for REST/GraphQL bodies would not
  park in `submit()` (there is none) but would raise the admission *rate* into the same 128-slot buffer; the
  excess is rejected at `offer` time, as today, and stage 2's inflow is bounded by stage 1's throughput.

**Verdict.**

1. **Asynchronous session close for REST and GraphQL: RECOMMENDED, GO.** Compose the response on
   `closeNow(behaviour)` instead of `join()`ing it: `RestEndpointExecutionContext.closeSessionIfOpen` returns the
   future and `EndpointHandler`'s chain awaits it; `EvitaSessionManagingInstrumentation.instrumentExecutionResult`
   already returns a `CompletableFuture<ExecutionResult>` and can return `closeNow(...).thenApply(v -> result)`.
   This removes the only unbounded park on a pool thread in the server, removes the starvation dependency
   entirely, makes REST/GraphQL consistent with gRPC, and makes a cancelled request stop holding a thread. It
   is independent of thread kind and should land first.
2. **Dedicated pipeline executor: RECOMMENDED, GO.** Give each `TransactionManager`'s publishers and stages an
   executor that write-request bodies cannot occupy — a per-`Evita` cached platform pool (`Evita-pipeline-N`,
   unbounded, idle-reclaimed; three long-lived tasks per catalog) is sufficient and keeps the JFR statistics
   shape. After (1) the remaining transaction-pool users are warm-up flush fan-out, engine operators, loads,
   termination and — if kept there — REST/GraphQL bodies, which could equally return to the request pool as
   gRPC's already are.
3. **Virtual-thread-per-task for the pipeline / flush / lifecycle executor: RECOMMENDED WITH BENCHMARK GATE.**
   Technically sound (no pinning on this path, j.u.c locks throughout, ordering executor-independent, thread-bound
   state stays within one task) and it removes the `maxThreadCount` knob and the leak classes. The gate: commit
   throughput and visibility latency (`CommitThroughputBenchmark`, `-t 1/8/64`) not worse than the platform
   variant, zero `jdk.VirtualThreadPinned` events above 1 ms on those threads, and no regression in the
   flush-per-collection warm-up benchmark. Without measured benefit, the platform pool from (2) is the safer
   default on JDK 21.

---

## 10. Virtual threads for scheduler and background execution

**The scheduler today** (parts/07): a fixed `ScheduledThreadPoolExecutor(maxThreadCount)` with an unbounded delay
queue and no lane between timers and jobs. Backpressure exists only on the `ServerTask` registry path
(`2 × queueSize` slots, finished tasks retained 5 minutes); the plain `schedule`/`execute`/`submit` path has none
and six submissions discard their futures. `@InternallyScheduledTask` — the mechanism meant to keep infinite
tasks off the pool — never fires in production: it is declared on the abstract `ClientInfiniteCallableTask`, is
not `@Inherited`, and `executeIssuedTask` tests the concrete class (`Scheduler.java:778`). So the Prometheus
`MetricTask` holds one worker permanently and every JFR or traffic recording parks another on a latch for its
lifetime. Any client can submit backups until the registry rejects; with the shipped defaults 15 backups plus the
metric task hold all 16 workers, and every due timer waits — the checkpoint ticker (the durability fence), CDC
heartbeats (streams die on the idle timeout), the commit-progress sweep, the session killer, the WAL drainer,
the traffic flush and the scheduler's own purge.

**Task classes found** (25 timers, full inventory in parts/07 §2):

- TIMER-CALLBACK (µs, re-schedule or signal): scheduler purge, session-killer tick (usually), collation
  sweeper, commit-progress sweep, CDC heartbeats, readiness stall tracker, HTTP/2 monitor.
- CPU-MAINTENANCE: cache supervisor / `CacheEden` evaluation (under a `tryLock(1 s)`), horizon reconcile.
- FILE-IO (bounded): checkpoint ticker (N fsyncs under `checkpointLock`, shared with stage 2), WAL drainer
  (incorporates a backlog under `trunkIncorporationLock`), session killer's bootstrap trim, WAL/obsolete-file
  purges, traffic flush and index.
- NETWORK-IO / unbounded: S3 refresh (MinIO notification long-poll, can block indefinitely), S3 uploads.
- LONG JOBS: backup, full backup, restore, traffic export, JFR recorder and traffic recorder (latch for hours).
- Not on this pool although periodic-looking: Armeria's own timeouts and the request-timeout scheduler.

Two bodies join the transaction pool (restore registration, catalog auto-upgrade); no body joins a same-pool
future; coupling is through one timer-released latch and through locks. Nothing reads the worker's name,
priority, daemon flag or group; only the WAL drainer binds the transaction `ThreadLocal`, scoped within one run.

**Pinning on this path.** The single `Object.wait()` in the code base — `RingBufferSpanLock.acquireExclusive`
(`:112-121`), entered while the `OffHeapTrafficRecorder` monitor is held (`:803`) — is reachable only from the
traffic drain, export and close tasks here. It is a REAL PINNING RISK for a virtual-thread job executor and
must be converted to a `ReentrantLock`/`Condition` first. Everything else on the service path is
Blocker-compensated file I/O, OkHttp socket I/O (unmounts unless inside a monitor — to be confirmed with JFR),
or j.u.c parks.

**Verdict.**

1. **Split timer mechanism from job execution: RECOMMENDED, GO.** A small platform `ScheduledThreadPoolExecutor`
   (two threads) owns every `DelayedAsyncTask` and `scheduleAtFixedRate`; timer bodies that do real work
   (checkpoint ticker's fsync batch, WAL drainer, session-killer trim, S3 refresh) are dispatched from the tick
   to the job executor rather than run on the timer thread. This is what prevents 16 backups from delaying the
   durability fence, and it keeps the scheduled-executor JFR statistics meaningful.
2. **Job executor on virtual threads: RECOMMENDED WITH BENCHMARK GATE.** These are the archetypal virtual-thread
   workload — few to dozens of concurrent tasks that block for minutes to hours — and per-task threads remove
   the latch-holding-a-worker problem outright. Preconditions: the `RingBufferSpanLock` conversion; the
   `ServerTask` registry keeps bounding admission (it does today); `InterruptibleServerTask` cancellation is
   `Future.cancel(true)`, which interrupts a virtual thread the same way. Gate: a run with 20 concurrent backups
   plus a JFR and a traffic recording shows zero pinned events above 1 ms and unchanged checkpoint-tick latency.
   A cached platform pool is the fallback with the same structural benefit and none of the JDK 21 caveats.
3. **Armeria's `blockingTaskExecutor` slot** keeps a `ScheduledExecutorService`: either the timer executor
   (only Lab static files and TLS setup reach it) or a thin adapter that schedules on the timer and executes on
   the job executor. Do not hand Armeria a bare virtual-thread executor.
4. **`@InternallyScheduledTask`**: decide whether the annotation should work (add `@Inherited` or check the
   hierarchy) or be removed; under the split, infinite tasks belong on the job executor either way. Reported,
   not changed (§20.3).

---

## 11. JDK 21 pinning risks

JDK 21 rules (verified against the installed runtime's bytecode where stated): a virtual thread pins its carrier
when it parks inside a `synchronized` block or method, when it blocks on a *contended* `monitorenter`, in
`Object.wait()`, and with a native frame on the stack. Blocking file I/O never unmounts; it is compensated
(`Blocker.begin()` → `ForkJoinPools.beginCompensatedBlock`; wrapped in `RandomAccessFile`, `FileChannelImpl`
incl. `force`, `FileOutputStream`, `FileInputStream`, `FileDescriptor.sync`, `UnixFileSystem`,
`UnixNativeDispatcher`). Scheduler defaults: `parallelism = C`, `maxPoolSize = max(C, 256)`,
`minRunnable = max(C/2, 1)`, keep-alive 30 s, `saturate = pool -> true` (past `maxPoolSize` a blocked carrier
simply blocks instead of throwing). JEP 491 (JDK 24) removes the monitor cases; nothing below relies on it.

**Inventory** (parts/03 §1): 69 real `synchronized` sites in main code (10 engine, 1 api, 16 store, 41 external
API core — vendored routing code, event loop only — 1 observability) plus 107 in generated gRPC stubs (lazy
`MethodDescriptor` creation). Many classes named in the initial counts (`EvitaContract`, `CacheEden`, both CDC
publishers, `OffsetIndex`, the write handles, `DefaultCatalogPersistenceService`, `DiskRingBuffer`, `SortIndex`,
the B+ trees, `CollationKeyCache`, …) have monitors only in javadoc and use `ReentrantLock` or `volatile`.

- **Pure-memory monitors on engine indexes** — `AttributeIndex` ×7, `HierarchyIndex`,
  `InvertedIndex.refreshValueIdDirectory`: NO PINNING RISK; a contended entry pins for the critical section
  only.
- **Sub-microsecond chaining** — `CommitProgressRecord.enqueueCompletion`: NO PINNING RISK.
- **File I/O inside a monitor** — `Catalog.terminate` (j.u.c locks taken inside, then file operations):
  PINS-BUT-SHORT, shutdown only.
- **`Object.wait()` inside nested monitors** — `RingBufferSpanLock.acquireExclusive` under the
  `OffHeapTrafficRecorder` monitor: **REAL PINNING RISK**, service path only.
- **Native code under the JDK's own monitor** — `Deflater`/`Inflater` (`storage.compress=true`): no park inside,
  no pin.
- **Vendored routing and gRPC stub lazies** — `PathMatcher`, `CopyOnWriteMap`, generated `*Grpc.java`:
  NOT ON A VT PATH.

**Blocking waits on the request and transaction executors** are all `java.util.concurrent` parks outside any
monitor (unmount) or compensated file I/O: the session-close joins, warm-up flush joins, engine-DDL joins,
`GrpcOutboundGate.awaitNanos`, `waitUntilLiveVersionReaches` (spin 4 096 then 100 µs parks), the WAL append
and `force(true)` under three nested `ReentrantLock`s, trunk incorporation and checkpoints under
`trunkIncorporationLock`/`checkpointLock`, write-handle `tryLock`s. `SubmissionPublisher.doOffer` holds a
`ReentrantLock` (zero `monitorenter` in the class) and is never called in its blocking form. One JDK-internal
monitor is held across native I/O: `FileChannelImpl.read/write(ByteBuffer)` take `synchronized (positionLock)`
around the Blocker-wrapped call, so every WAL append and traffic ring-buffer write runs native I/O under a
monitor — not a park, so no pinned event and the carrier is compensated; a second virtual thread contending
on the same channel would pin on `monitorenter`, but evitaDB serialises each channel with its own lock, so
that contention cannot arise. Locks held across device I/O are listed in parts/03 §5; fairness lives in the
AQS queue and is thread-kind-independent.

**Conclusion:** no real pinning risk on the request or transaction executors on JDK 21; one on the service
path that must be fixed before service jobs move to virtual threads; the memory-only monitors on the indexes
cost a pin for the duration of a contended critical section, which matters only if CPU-heavy work moves to
virtual threads (§8 says it should not).

**Validation** (parts/03 §6, to run only in a measurement window): JFR `jdk.VirtualThreadPinned` (default
threshold 20 ms in `default.jfc`; lower to 1 ms in a copied `profile.jfc`), `jdk.VirtualThreadSubmitFailed`,
`jdk.JavaMonitorEnter`; `-Djdk.tracePinnedThreads=full|short` (prints via `PinnedThreadPrinter` when a
continuation cannot yield); `jcmd <pid> Thread.dump_to_file -format=json` (virtual threads grouped by executor,
grep `PINNED` / `parkOnCarrierThread`); and the `jdk.virtualThreadScheduler.maxPoolSize = parallelism`
experiment to observe uncompensated file I/O on the pipeline.

---

## 12. Context propagation and executor boundaries

Every boundary a request or transaction crosses, what context it needs, how it travels today, how it would with
`ScopedValue` (or the facade), and whether the boundary survives the target architecture.

One record per boundary: *needs* — the context the far side requires; *today* — how it travels;
*scoped* — how it would travel with scoped bindings (facade or `ScopedValue`); *survives* — whether the
boundary still exists in the target architecture (§14).

- **Event loop → request pool (REST/GraphQL).** Needs: tracing MDC + client labels. Today:
  `TracingContext.captureContext()` at task construction, `setContext()` on the worker
  (`ObservableThreadExecutor`). Scoped: same capture; the task wrapper opens the scope. Survives: yes.
- **Event loop → request pool (gRPC).** Needs: Armeria context, gRPC `Context`, metadata, session. Today:
  captured on the loop; `grpcContext.run(...)`; session passed as an argument (`executeWithClientContext`).
  Scoped: unchanged. Survives: yes.
- **Request task → GraphQL root fields (request or transaction pool).** Needs: tracing. Today: a new
  `AsyncDataFetcher` task captures and restores again. Scoped: unchanged. Survives: yes.
- **Session call → transaction binding.** Needs: `Transaction`. Today: `EvitaSessionProxy` binds per call
  through `executeInTransactionIfProvided`. Scoped: the proxy opens the scope. Survives: yes.
- **Commit → stage 1 → WAL sync → stage 2.** Needs: the task object only. Today: `Flow` items carry
  everything; stage 2 re-binds the replay transaction. Scoped: stage 2 opens a scope per replay and per
  flush + merge. Survives: yes.
- **Stage → completion callbacks.** Needs: none (callbacks run without the request's MDC). Today:
  `thenRunAsync(…, requestExecutor)`. Scoped: unchanged. Survives: yes.
- **Engine operator (caller → pool).** Needs: `Transaction`. Today: the reference is captured on the caller
  and re-bound on the executor through `executeInTransactionIfProvided`. Scoped: same, value passed as an
  argument. Survives: yes.
- **Warm-up close → flush fan-out.** Needs: none (changes are popped synchronously first). Today: a detached
  `DataStoreChanges` per task. Scoped: unchanged. Survives: yes.
- **Timer tick → job.** Needs: none; no task body uses MDC. Today: direct or via `DelayedAsyncTask`. Scoped:
  unchanged. Survives: the timer/job split adds one hop that carries nothing.
- **gRPC streaming producer ↔ event-loop readiness.** Needs: Armeria context. Today: captured at `attach` on
  the loop. Scoped: unchanged. Survives: yes.
- **`ForkJoinPool.commonPool` (shutdown, CDC stream death).** Needs: none. Survives: yes.

Findings: no boundary relies on `ThreadLocal` inheritance; every one either passes the value as an argument
or captures and re-establishes it. This is exactly the discipline `ScopedValue` requires (bindings are not
inherited across `submit`), so the propagation design is already correct for both storages and does not have
to be redesigned around the current executor layout. The target architecture (§14) removes no boundary that
carries context; it adds one (timer → job) that carries none.

---

## 13. Concurrency and backpressure requirements

What the pool sizes protect today, and what must replace each role if a pool changes:

- **Request pool `max + queue`** — protects against CPU oversubscription, memory growth and unbounded
  concurrent sessions. Replacement: none needed; the pool stays.
- **Transaction pool `max + queue`** — bounds write bodies, flushes and operators, and *implicitly* commit
  admission. Replacement: the pipeline buffer (128) plus the fail-fast `offer` already bounds commits; write
  bodies stay bounded by whichever pool runs them.
- **`SubmissionPublisher` buffers** — in-flight commits per catalog. Unchanged.
- **`ServerTask` registry (`2 × queueSize`)** — number of client-submitted jobs. Unchanged; it becomes the
  *only* job limiter, which it effectively is today.
- **Armeria `requestTimeoutMillis`, `maxRequestLength`, idle timeout** — per-request lifetime and size.
  Unchanged.
- **gRPC stall timeout, CDC heartbeats** — streams. Unchanged.
- **Session registry** (warm-up single session, suspension waits) — per-catalog admission. Unchanged.

Nothing new is required for the recommended changes. Two pre-existing gaps are worth recording because a virtual-thread
migration would make them worse, not better: overload is reported as HTTP 500 / gRPC `INTERNAL` rather than 503 /
`UNAVAILABLE` / `RESOURCE_EXHAUSTED`, and `server.queryTimeoutInMilliseconds` is not enforced anywhere (the effective
deadline is Armeria's request timeout, 2 s in the shipped YAML). If the request executor ever moves to virtual
threads, the admission limit must be designed first: a semaphore of `maxThreadCount` permits plus a bounded
queue of `queueSize`, rejecting into the same handlers — i.e. the same numbers, so the change buys nothing
until a blocking read path exists.

---

## 14. Recommended target architecture

### 14.1 Current

```
Netty EL (platform, C) --dispatch--> Evita-request (platform, C..4C+q)   CPU + compensated reads
                       --dispatch--> Evita-transaction (platform, C..4C+q)
                                        pipeline 3/catalog  +  REST/GraphQL bodies + commit join (!)
                                        + warm-up flush fan-out + operators + loads
Evita-service (platform STPE, fixed 2C) : 25 timers + hour-long jobs + Armeria blocking slot (!)
context: ThreadLocal everywhere; explicit capture/re-bind at every boundary
```

### 14.2 Proposed

```
Netty EL (platform, C)                 unchanged; incidental: move REST parsing/session creation off the loop
   |
   +--dispatch--> Evita-request (platform, bounded, threads-first)      unchanged
   |                 queries, reads, gRPC reads+writes, GraphQL roots, CDC, streaming producers
   |                 REST/GraphQL writes: bodies here or on the write pool, close is ASYNC (no join)
   |
   +--commit------> Evita-pipeline (dedicated; platform cached pool now; VT-per-task behind a gate)
                     stage1 / WAL sync / stage2, 3 tasks per catalog, Flow-serialised
                     warm-up flush fan-out, engine operators, loads, termination
                     (write bodies may stay on a bounded "transaction" pool if kept separate from the pipeline)

Evita-timer  (platform STPE, 2 threads)   every DelayedAsyncTask / fixed-rate timer; bodies that do I/O dispatch
   |                                      -> job executor; also handed to Armeria's blockingTaskExecutor slot
   +--tick--------> Evita-job  (VT-per-task behind a gate; cached platform pool as fallback)
                     backups, restores, exports, S3, JFR + traffic recorders, checkpoint fsync batch,
                     WAL drainer, purges; admission = ServerTask registry (unchanged)

context: ScopedBinding facade (ThreadLocal-backed on 21, ScopedValue on 25); scopes opened by the task wrapper
         (request context) and by the session proxy / stage 2 (transaction); per-thread caches unchanged
```

Where things live:

- **Platform threads remain:** event loops, request pool, timer pool; pipeline unless the gate passes.
- **Virtual threads introduced (gated):** job executor; optionally pipeline/flush/lifecycle executor.
- **Bounded CPU execution remains:** request pool (the only CPU-oversubscription control), stage 2 (single
  consumer per catalog by construction).
- **Scoped-binding scopes begin/end:** `ObservableRunnable.run` (request MDC/labels), `EvitaSessionProxy`
  (transaction), `TransactionManager` stage 2 (replay, flush+merge), `LocalMutationExecutorCollector.execute`
  (warm-up savepoint), each Kryo/ANTLR bracket for the mechanical candidates.
- **Per-thread caches live** on the request and pipeline threads exactly as today.
- **Concurrency limits apply** at the request pool, the pipeline buffers, the `ServerTask` registry.
- **Scheduler tasks execute** on the timer pool (cheap ticks) or the job executor (everything that blocks).

---

## 15. Migration phases

Derived from the findings; each phase is independently shippable and testable, and none of the first four
depends on preview features.

- **Phase 0 — prerequisites (executor-kind-agnostic fixes).** (a) Asynchronous session close for REST and
  GraphQL; (b) dedicated pipeline executor per `Evita`; (c) `RingBufferSpanLock` from `wait/notify` to
  `ReentrantLock`/`Condition`; (d) decide `@InternallyScheduledTask`; (e) optional: move the REST body-decoding
  continuation and session creation off the event loop. Verification: the full functional suite, the
  go-live drain test (its thread filter names `Evita-transaction-N`, `CatalogGoLiveSessionDrainTest.java:462-467`,
  and will need the new pool name), a new test that parks `maxThreadCount` REST writers and asserts the
  pipeline still completes.
- **Phase 1 — scoped-binding facade and lexicalisation.** Introduce the facade; migrate the 13 mechanical
  candidates; restructure `Transaction` (close, suspend sites, retire public bind/unbind), `WarmUpSavepoint`
  (single scope in the collector), `PENDING_TRIGGER_REBUILDS` (per-frame). Re-run the #1332 sort-attribute
  insert profile and the bulk-ingest profile to confirm no regression from the facade indirection.
- **Phase 2 — scheduler split.** Timer pool + job executor behind the existing `ObservableExecutorService`
  contracts; `Scheduler` keeps its public API and registry; configuration gains a job-executor section (new
  keys must follow the naming rule and be searched first); metrics/JFR events regenerated (`JfrDocumentation`).
  Ship with the job executor on a cached platform pool; enable virtual threads behind a configuration switch
  and run the §18 scheduler workload as the gate.
- **Phase 3 — pipeline/flush/lifecycle executor on virtual threads (gated).** Same switch pattern; the §18
  transaction workloads decide whether the switch defaults on.
- **Phase 4 — deferred, conditional: request executor.** Not planned; the trigger conditions are in §8.
- **Phase 5 — JDK 25 baseline.** Swap the facade implementation to `ScopedValue`; no `--enable-preview` ever
  enters the build. If, and only if, the project decides to ship preview on 21 instead, parts/04 is the exact
  change list.

---

## 16. Required `--enable-preview` build, runtime and Docker changes

Applicable only under the NO-GO alternative (shipping `ScopedValue` on JDK 21); recorded so the cost is visible.
Full tables with quoted snippets and proposed replacements: parts/04 Part B.

- **Root `pom.xml`:** compiler `<compilerArgs>` (`:700-702`, add `--enable-preview` after `-parameters`);
  surefire `<argLine>` (`:759`, insert directly after `${surefireArgLine}` so the JaCoCo agent stays first —
  JaCoCo 0.8.15 masks minor 65535, verified in the cached jar); javadoc (`:768-776`, needs `<release>` plus
  `<additionalOptions>--enable-preview</additionalOptions>`, otherwise `failOnError=false` silently ships
  release deploys without javadoc jars); toolchains comment. New `.mvn/jvm.config` with `--enable-preview` for
  in-process `exec:java`. No manifest attribute can carry the flag; jar/shade configs stay.
- **Module poms:** `evita_test/evita_long_running_tests/pom.xml:272` and
  `evita_test/evita_documentation_tests/pom.xml:238-243` use `combine.self="override"` and therefore do
  not inherit the root `argLine`; JShell in
  `JavaTestContext.java:163-167` needs `compilerOptions("--enable-preview")`; JMH `FORK_JVM_ARGS`
  (`ArtificialTestRunner.java:63-68`), `BenchmarkForkArgs` and 25 `@Fork(jvmArgsAppend)` sites.
- **Launchers:** `docker/entrypoint.sh:64-67` — insert `--enable-preview \` as the first line after `exec java \`,
  before `-javaagent` and `$EVITA_JAVA_OPTS`, so the image starts without user action and an env override cannot
  drop it (`JDK_JAVA_OPTIONS` was evaluated and rejected as the primary mechanism: it is silently replaced by a
  user's `-e JDK_JAVA_OPTIONS=…` and prints a NOTE on every start; acceptable only as an *additional* `ENV` for
  the `exec "$@"` branch). The premain agent loads only unmarked classes, so ordering is not a hazard.
  `evita_server/dist/run.sh:29-31`, `evita_server/run-server.sh:31`, `run_performance_test.sh:25`,
  `src/automation/benchmark.sh:109-113` (parent line and `-jvmArgs` string). Base images
  (`azul/zulu-openjdk:21-latest`, `openjdk-21-jdk-headless`) and the eight `java-version: '21'` CI lines become
  exact pins.
- **IDE:** tracked `.idea/misc.xml:83` (already stale at JDK 17) and four run configurations.

---

## 17. Documentation changes

Under the recommended path (no preview): the thread-pool documentation changes when phases 2–3 land, nothing
changes for `ScopedValue`.

- `documentation/user/en/operate/configure.md:10-34, 399-419, 511-539` — pool sections: add the job executor
  and pipeline executor keys, note that `threadPriority` has no effect on virtual threads, keep `queueSize`
  semantics; `:340-346` environment-variable example must reference a key that still exists.
- `documentation/user/en/operate/reference/metrics.md` and `reference/jfr-events.md` — **generated** by
  `JfrDocumentation`; regenerate after the event classes change (`*_thread_pool_statistics_*` gauges
  `pool_core/pool_max/pool_size/largest_pool_size` lose meaning for a per-task executor; `active/completed/
  queued/queue_remaining` survive).
- `documentation/user/en/operate/observe.md` — no thread-pool content of its own; no change.
- `documentation/user/en/use/api/troubleshoot.md:34` ("how exhausted are thread pools") — adjust wording.
- Czech mirrors (`documentation/user/cs/**`) are regenerated by `tools/translate.sh`, never hand-edited.

Under the NO-GO alternative (preview on 21), parts/04 Part C lists 20 further edits: README (`:158, 170-213`),
`CLAUDE.md:9`, `docker/README.MD:33-45` (already stale), get-started `run-evitadb.md`, `query-our-dataset.md`,
`operate/run.md:11-12` (already wrong about the base image), `:193-243`, `configure.md:353` (`java -jar`
snippet), `connectors/java.md:18-21, 379-400`, `write-tests.md:38-56`, developer `test_guidelines.md:108-116,
888-905`, each with proposed text.

Two documentation defects were found in passing and are reported, not fixed: `operate/run.md:11-12` names a
RedHat base image (the Dockerfile uses Azul Zulu 21) and `docker/README.MD:36-45` shows an entrypoint without
the `-javaagent` line and with a wrong config variable.

---

## 18. Benchmark and validation plan

Constraints from project memory: never rank on the demo dataset; measure on the production-shaped catalogs the
performance module already loads; negotiate a quiet window before any run; verify the measured build.

**Variants.** A = current (platform threads, `ThreadLocal`); B = platform threads + facade (Phase 1; must be
indistinguishable from A); C = platform threads + `ScopedValue` on a throwaway `--enable-preview` branch
(measurement only, never merged — isolates the lookup-cost question); D = A + Phase 0 (async close, dedicated
pipeline pool); E = D + virtual-thread job executor; F = E + virtual-thread pipeline executor.

**Workloads and harnesses** (all exist in `evita_test/evita_performance_tests`):

1. **CPU-heavy queries** — `SanityChecker` (recorded production query set, N emitting threads) and the
   `ArtificialEntities{Throughput,Latency}Benchmark` family over gRPC, REST, GraphQL and the Java driver.
   Purpose: detect oversubscription or scheduling regressions; A vs B vs C (lookup cost on the read path), and
   A vs D/E/F (must be flat — the request pool does not change).
2. **Concurrent blocking requests** — REST and GraphQL write requests with `WAIT_FOR_CHANGES_VISIBLE` at 16,
   64 and 256 concurrent clients while reads run; today this parks transaction-pool threads. Purpose: expose the
   Phase 0 benefit (D vs A: no starvation, latency of the read side unchanged) and the virtual-thread effect (F).
   gRPC gated streaming with 16–64 deliberately slow consumers alongside queries: request-pool occupancy.
3. **Transactions** — `CommitThroughputBenchmark -t 1/8/64` with the `flushFrequencyInMillis` sweep, the WAL
   replay benchmark, and the warm-up ingest benchmarks (flush fan-out). Purpose: D vs F on commit throughput,
   visibility median/p99, and fsync behaviour; B vs A on the sort-attribute insert profile (#1332).
4. **Scheduler/background** — 20 concurrent backups plus one JFR and one traffic recording while measuring
   checkpoint-tick latency, CDC heartbeat punctuality and session-killer punctuality. Purpose: A (expect
   starvation) vs D/E (expect none); E's pinning count.
5. **High concurrency** — 1 000 to 10 000 concurrent HTTP/2 streams issuing small queries (gRPC), to confirm the
   request pool's admission behaviour is unchanged and that the per-thread caches do not multiply; under any
   future request-side virtual-thread experiment this is where the `FrontCodedStringColumn` churn and the
   collator-stripe collisions would show.

**Measurements.** JMH throughput and p50/p95/p99/p99.9 (`-prof` percentiles; p99.9 where the run is long enough);
CPU utilisation and allocation rate, GC pauses (JFR `jdk.GarbageCollection`, `jdk.ObjectAllocationSample`);
RSS (`/proc/<pid>/status` sampled); live platform-thread and virtual-thread counts (`jcmd Thread.dump_to_file
-format=json`, `jdk.VirtualThreadStart/End`); carrier utilisation (`jdk.virtualThreadScheduler.parallelism`
carriers busy, via the JSON dump); contention (`jdk.JavaMonitorEnter`, `jdk.ThreadPark`); context switches
(`pidstat -w` / `perf stat -e context-switches` on the JVM pid); pinned events (`jdk.VirtualThreadPinned` at a
1 ms threshold); the existing pool-statistics JFR events for A–D.

**Gates.** Phase 0: workload 2 shows no starvation and read p99 unchanged; workload 3 unchanged. Phase 1:
B within noise of A on workloads 1 and 3. Phase 2 virtual-thread switch: workload 4 shows zero pinned events
above 1 ms, checkpoint-tick latency unchanged, RSS not higher than D. Phase 3 switch: workload 3 throughput and
visibility p99 not worse than D, zero pinned events, no regression on workload 2. Any regression keeps the
platform fallback as the default.

---

## 19. Memory analysis

- **Virtual threads** (*from knowledge*): a `VirtualThread` object plus a heap-allocated stack chunk that grows
  and shrinks with the live stack; parked threads with shallow stacks cost on the order of a kilobyte, deep
  stacks proportionally more, and every park copies the frames to the heap. evitaDB's query stacks are deep
  (recursive formula and visitor evaluation), which is one more reason to keep queries off virtual threads;
  pipeline and job stacks are moderate and park rarely (I/O does not unmount).
- **Per-thread maps that would multiply under a per-request virtual thread** (not recommended): logback MDC
  map per thread that logs with MDC keys; Netty `InternalThreadLocalMap` (~200–300 bytes) only if the Armeria
  context is pushed on the worker (it is not today); `FrontCodedStringColumn` scratch (kilobytes, grown per
  request on the read path); `Crc32CWrapper` scratch (~48 bytes, write path only); a `CollationKeyCache`
  stripe collision clones a `Collator` (tens of microseconds). At 10 000 concurrent requests that is tens of
  megabytes of transient scratch and churn instead of ~64 long-lived holders — the figure that made §8 a
  no-go without evidence, and the reason workload 5 exists.
- **Under the recommended architecture** the request-side holder count is unchanged. The job executor creates
  at most `ServerTask`-registry-many threads (default 40 slots, YAML 200) plus timers' dispatches; the pipeline
  executor three per catalog plus flush fan-out. Both are well below today's fixed pools in steady state and
  bounded above by the same registries and buffers.
- **Scoped bindings**: a `ScopedValue` binding allocates a small `Snapshot`/`Carrier` chain per scope and no
  per-thread map; the facade on `ThreadLocal` allocates nothing new. `ScopedValue.get()` uses a 16-slot
  per-thread cache (`Object[32]`) allocated lazily per thread that reads a bound value.
- **Executor queues**: unchanged for the request pool; the transaction pool's queue stops doubling as an
  implicit commit limiter once write bodies and closers stop parking there (the pipeline buffers keep that
  role); the service registry is unchanged.

---

## 20. Risks, unresolved questions and incidental findings

### 20.1 Risks of the recommended path

- **Async close changes error timing for REST/GraphQL writes**: exceptions from the commit now surface through
  the composed future rather than a thrown `join()`; the existing exception handlers must see the same
  `TransactionException`/`RollbackException` types. Covered by the functional suite plus a targeted test.
- **Dedicated pipeline pool changes thread names** that one test filters on (`CatalogGoLiveSessionDrainTest`).
- **Facade indirection on the hot path**: one extra virtual call per `getTransaction()` unless the facade is a
  final class with a static-final `ThreadLocal`; gate B vs A.
- **Timer/job split re-orders lock acquisition** for bodies that today run on the timer thread (checkpoint
  ticker under `checkpointLock` shared with stage 2); the ordering is unchanged, only the thread differs.
- **Virtual-thread job executor on JDK 21**: pinning inside OkHttp/MinIO or zip code paths cannot be fully
  excluded statically; the gate's JFR run decides. `ThreadMXBean` does not report virtual threads (monitoring
  dashboards that count threads change meaning). Unnamed virtual threads: give the factory a name prefix.
- **Micrometer's `ExecutorServiceMetrics`** receives whatever sits in Armeria's blocking slot; behaviour for an
  unknown executor type could not be verified from this checkout (today it already receives `Scheduler`).

### 20.2 Unresolved questions

- Whether `ScopedValue.get()` is measurably cheaper than `ThreadLocal.get()` on the #1332 profile (variant C).
- Whether the read-path `FrontCodedStringColumn` scratch matters under high concurrency at all (workload 5);
  only relevant if the request executor is ever revisited.
- Whether REST/GraphQL write bodies should move to the request pool (as gRPC) after async close, or keep a
  separate bounded write pool; a product decision about read/write isolation, not a technical blocker.
- The go-live drain and the engine-wide `engineStateLock` (stage 2 can wait up to 300 s on it) are unchanged by
  this proposal but interact with any executor split; noted for the Phase 0 design.

### 20.3 Incidental findings (reported, not changed)

- `server.queryTimeoutInMilliseconds` (default 5 000) is read only by `EvitaStatisticsEvent`; the effective
  deadline is Armeria's `api.requestTimeoutInMillis` (2 000 in YAML, 1 000 in code).
- Overload is reported as HTTP 500 / gRPC `INTERNAL`, not 503 / `UNAVAILABLE` / `RESOURCE_EXHAUSTED`.
- REST parses bodies, reconstructs queries and creates sessions on the Netty event loop; the WebSocket paths run
  whole session lifecycles and graphql-java there.
- `@InternallyScheduledTask` never matches in production (not `@Inherited`, concrete class checked).
- The service scheduler can be exhausted by client-submitted backups, delaying the durability checkpoint
  ticker and CDC heartbeats.
- `RingBufferSpanLock` is the only `Object.wait()` in the code base and sits under a second monitor.
- `AppLogJsonLayout` never emits `duration_ms` from worker threads because the Armeria context is not pushed
  there (by design of the handoff).
- `TransactionContract.WAIT_FOR_WAL_PERSISTENCE` javadoc says fsyncs are not batched; the stage code batches them.
- `operate/run.md` and `docker/README.MD` describe a base image and an entrypoint that no longer exist.
- `.idea/misc.xml` still declares language level JDK 17.

---

## 21. Explicit recommendation per executor

Summary (details per executor follow):

| Executor | Proposed model | VT suitable? | Limit needed? |
|---|---|---|---|
| Netty event loops | keep platform | no | Armeria's |
| `Evita-request` | keep bounded platform pool | no (now) | yes — it *is* the limit |
| `Evita-transaction`, pipeline part | dedicated pipeline executor; VT gated | mixed | no (Flow buffers) |
| `Evita-transaction`, other users | async close; bodies bounded; rest follows pipeline | mixed | yes, bodies |
| `Evita-service`, timers | small platform STPE | no | no |
| `Evita-service`, jobs | job executor; VT gated | **yes** | registry (unchanged) |
| Armeria `blockingTaskExecutor` slot | timer executor or adapter | n/a | no |
| Probe `ForkJoinPool` | leave; may fold into jobs | yes | no |
| `ForkJoinPool.commonPool` | leave | n/a | no |

- **Netty event loops.** Role: I/O, framing, every service-method body. Workload: CPU, non-blocking. Proposed:
  platform, unchanged. VT: no. Limit: Armeria's own. Risk: the REST work done on the loop (incidental, §3.2).
- **`Evita-request`.** Role: all session work and the server's admission control. Workload: CPU plus
  compensated file reads, a few bounded parks. Proposed: keep the bounded platform pool. VT: no, for now.
  Limit: yes — the pool *is* the limit. Risk if changed: CPU oversubscription, per-request cache churn, lost
  pool metrics and health signal.
- **`Evita-transaction`, pipeline part.** Role: commit pipeline, three tasks per catalog. Workload: CPU
  (stage 2) plus fsync and flush. Proposed: dedicated pipeline executor, VT-per-task behind a gate. VT: mixed.
  Limit: no — the `Flow` buffers bound it. Risk: a multi-second CPU replay on a VT holds a carrier.
- **`Evita-transaction`, other users.** Role: REST/GraphQL write bodies and their commit join, warm-up flush
  fan-out, engine operators, loads. Workload: mixed, parks. Proposed: asynchronous close; bodies on a bounded
  pool; flushes and operators may follow the pipeline executor. VT: mixed. Limit: yes for bodies. Risk:
  starvation today; none after async close.
- **`Evita-service`, timers.** Role: 25 timers. Workload: microsecond ticks plus a few I/O bodies. Proposed:
  small platform `ScheduledThreadPoolExecutor`; I/O bodies dispatch to the job executor. VT: no. Limit: no.
  Risk: late ticks if jobs keep sharing the pool.
- **`Evita-service`, jobs.** Role: backups, restores, exports, JFR and traffic recordings, S3, purges.
  Workload: BLOCKING, minutes to hours. Proposed: job executor, VT-per-task behind a gate. VT: yes. Limit:
  the `ServerTask` registry, unchanged. Risk: the `RingBufferSpanLock` pin; OkHttp monitors (verify with JFR).
- **Armeria `blockingTaskExecutor` slot.** Role: Lab static files, TLS setup. Workload: blocking, rare.
  Proposed: the timer executor or a scheduling adapter. Risk: it must stay a `ScheduledExecutorService`.
- **Probe `ForkJoinPool`.** Role: readiness probes over OkHttp. Workload: network, bounded to 10 s. Proposed:
  leave; candidate to fold into the job executor. VT: yes. Limit: no. Risk: none.
- **`ForkJoinPool.commonPool`.** Role: shutdown fan-out, one CDC death path. Proposed: leave. Risk: none.


---

## 22. Explicit recommendation per `ThreadLocal`

`SV?` = scoped-binding candidate (facade now, `ScopedValue` on 25). Evidence and line numbers: parts/02 §3.

### 22a. Identity, purpose, lifetime

| # | ThreadLocal | Purpose | Current lifetime |
|---|---|---|---|
| 1 | `Transaction.CURRENT_TRANSACTION` | bound transaction | call / replay / commit |
| 2 | `WarmUpSavepoint.CURRENT` | warm-up savepoint | root entity mutation |
| 3 | `Catalog.PENDING_TRIGGER_REBUILDS` | schema-batch frame stack | schema batch |
| 4 | `EntitySchemaContext.ENTITY_SCHEMA_SUPPLIER` | schema for Kryo | (de)serialization call |
| 5 | `CatalogSchemaStoragePart.CATALOG_ACCESSOR` | catalog for deserializer | load / rename |
| 6 | `CatalogSchemaStoragePart.CATALOG_NAME_TO_REPLACE_ACCESSOR` | name override | rename |
| 7 | `FrontCodedStringColumn.SCRATCH` | decode/encode scratch | worker lifetime |
| 8 | `Crc32CWrapper.COMBINE_PRIMITIVE_SCRATCH` | CRC scratch | worker lifetime |
| 9 | `Crc32CWrapper.COMBINE_PRIMITIVE_BUFFER` | 8-byte scratch | worker lifetime |
| 10 | `TracingContext.CLIENT_LABELS` (+ 4 MDC keys) | request tracing | request |
| 11 | `ParserExecutor.CONTEXT` | parse context | parse call |
| 12 | `CurrentSessionRecordContext.SESSION_SEQUENCE_ORDER` | traffic record context | (de)serialization |
| 13 | `TrieNodeSerializer.ARRAY_TYPE` | array type for Kryo | read call |
| 14 | `DefaultCatalogPersistenceService.CURRENT_TIME_MILLIS` | test clock seam | test |
| 15 | `ObservabilityTracingContext.parentContextAvailable` | span-open toggle | tracing block |
| 16 | `ErrorOriginLogger.REPORTING` | re-entrancy guard | call |
| 17 | `ErrorMonitor.REPORTING` | re-entrancy guard | call |
| 18 | `AssociatedDataMutationConverter.CLIENT_VERSION` | client SemVer (deprecated) | call |
| 19 | `ClientSessionInterceptor…SESSION_DESCRIPTOR` (driver) | session id for metadata | call |
| 20 | `EvitaClient.timeout` (driver) | per-thread timeout stack | call stack |
| 21 | `AbstractArtificialBenchmarkState.session` (harness) | one session per JMH thread | benchmark |

### 22b. Verdicts

| # | SV candidate? | VT risk | Recommendation |
|---|---|---|---|
| 1 | yes* | low | migrate after the three restructures |
| 2 | yes* | low | migrate after the scope restructure |
| 3 | yes* | low | per-frame binding |
| 4 | yes | low | migrate |
| 5 | yes | low | migrate |
| 6 | yes | low | migrate |
| 7 | no | medium (read path, VT-per-request only) | KEEP; benchmark only under VT-per-request |
| 8 | no | low | KEEP AS-IS |
| 9 | no | low | KEEP AS-IS |
| 10 | part | low | keep capture/restore; labels through the facade |
| 11 | yes | low | migrate |
| 12 | yes | low | migrate |
| 13 | yes | low | migrate |
| 14 | no | none | keep |
| 15 | no | low | redesign onto `Span.current()` |
| 16 | no | none | keep |
| 17 | no | none | keep |
| 18 | n/a | none | delete with #538 |
| 19 | yes† | low | migrate after deprecating the public set/reset |
| 20 | yes | low | migrate (`executeWithTimeout` is lexical) |
| 21 | n/a | n/a | note only |

`*` after the restructure named in §7.2. `†` after the driver's two non-lexical call sites move to
`executeInSession`.

---

## 23. Decision categories

**RECOMMENDED** (strong case, low enough risk to implement):

- Asynchronous session close for REST and GraphQL (Phase 0a).
- Dedicated commit-pipeline executor (Phase 0b).
- `RingBufferSpanLock` conversion to `ReentrantLock`/`Condition` (Phase 0c).
- Scoped-binding facade, ThreadLocal-backed, with the three restructures and the 13 mechanical migrations
  (Phase 1).
- Scheduler split into a platform timer pool and a job executor (Phase 2).
- `ScopedValue` as the facade implementation once the runtime baseline is JDK 25 (Phase 5).

**RECOMMENDED WITH BENCHMARK GATE:**

- Virtual-thread-per-task job executor (gate: workload 4, zero pins > 1 ms, tick latency unchanged).
- Virtual-thread-per-task pipeline/flush/lifecycle executor (gate: workload 3, throughput and visibility p99
  not worse, zero pins).

**INVESTIGATE FURTHER:**

- `ScopedValue.get()` versus `ThreadLocal.get()` on the sort-attribute insert profile (variant C, preview
  measurement branch only).
- Whether REST/GraphQL write bodies should join the request pool after async close.
- Replacing the 500/`INTERNAL` overload responses with 503/`UNAVAILABLE` and enforcing `queryTimeoutInMilliseconds`
  — outside this proposal's scope but interacting with any backpressure change.

**DO NOT CHANGE:**

- Netty event loops.
- The request executor's model (bounded platform pool, threads-first, bounded queue).
- The per-thread scratch caches and the striped collator pool.
- Shipping `ScopedValue` on JDK 21 with `--enable-preview`.

### Explicit GO / NO-GO

- **`ScopedValue` on JDK 21: NO-GO.** Prepare via the facade now: **GO.** Adopt on JDK 25: **GO.**
- **Virtual threads — request executor: NO-GO** (revisit conditions in §8).
- **Virtual threads — transaction executor as it exists: NO-GO**; the split is **GO**; virtual threads for the
  pipeline/flush/lifecycle executor: **conditional GO** behind the §18 gate, platform fallback default.
- **Virtual threads — service scheduler timers: NO-GO** (small platform pool); **job executor: conditional GO**
  behind the §18 gate after the `RingBufferSpanLock` fix, platform fallback default.
- **Virtual threads — Netty event loops: NO-GO** by design.
