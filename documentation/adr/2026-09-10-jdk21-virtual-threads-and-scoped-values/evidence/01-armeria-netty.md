# Part 01 — Armeria 1.41.1 / Netty 4.2.16 execution model vs. virtual threads

Scope: what Armeria and Netty assume about the thread that runs application code, and what
that means for moving evitaDB's own executors (request / transaction / service pools) to JDK 21
virtual threads and possibly ScopedValues. Analysis only; nothing was built or run.

Sources read:

- Armeria 1.41.1 sources jars, extracted (paths below are relative to the extraction root):
  `armeria/`, `armeria-grpc/`, `armeria-grpc-protocol/`, `armeria-graphql/`,
  `armeria-graphql-protocol/`, `armeria-protobuf/` — 1,738 files in total.
- Netty version pinned by the Armeria pom: **4.2.16.Final**
  (`~/.m2/repository/com/linecorp/armeria/armeria/1.41.1/armeria-1.41.1.pom` lines 79-100 pin every
  `io.netty:*` dependency to `4.2.16.Final`; evitaDB's root `pom.xml` pins only `armeria.version`
  (`pom.xml:142`) and does not override Netty). The matching sources jars
  `netty-common-4.2.16.Final-sources.jar` and `netty-buffer-4.2.16.Final-sources.jar` were present in
  `~/.m2` and were extracted for the files cited below (cited as `netty-common:` / `netty-buffer:`).
- evitaDB wiring in this worktree (paths relative to the repo root). Two long prefixes are
  abbreviated: `<core>` = `evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi`
  and `<grpc-server>` = `evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc`.

Test sources are **not** shipped in sources jars, so any statement about Armeria's *tests* is
"cannot be established from this checkout".

---

## Executive summary

1. **Armeria 1.41.1 contains no virtual-thread support at all** — zero hits for `VirtualThread`,
   `Thread.ofVirtual`, `newVirtualThreadPerTaskExecutor`, `isVirtual(`, `Loom`, `ScopedValue` across
   all six jars, including `META-INF`. The only "virtual" tokens are "virtual host" and "Java Virtual
   Machine".
2. **Netty 4.2.16 does know about virtual threads**, in two places: a reflective
   `Thread.isVirtual` probe (`PlatformDependent0`) that nothing in netty-common/netty-buffer calls,
   and `FastThreadLocalThread.runWithFastThreadLocal(Runnable)`, an opt-in wrapper that makes a
   non-`FastThreadLocalThread` (explicitly "e.g. a virtual thread") behave like one for the duration of
   a task. Armeria never calls it.
3. **The Armeria `RequestContext` is a Netty `FastThreadLocal`.** On a plain or virtual thread the
   lookup falls back to a JDK `ThreadLocal<InternalThreadLocalMap>` whose map allocates an
   `Object[32]` on first touch and is never cleaned up on non-Netty threads (garbage-collected with a
   per-task virtual thread; retained for the life of a pooled platform thread).
4. **On evitaDB's own worker threads (`Evita-request-N`, `Evita-transaction-N`, `Evita-service-N`)
   `RequestContext.currentOrNull()` is `null`.** evitaDB submits to its executors directly, never via
   `ctx.blockingTaskExecutor()` / `ctx.makeContextAware(...)`; `ObservableThreadExecutor` propagates
   only its own MDC keys. `AppLogJsonLayout` therefore never emits `duration_ms` for log events written
   from those threads, and `GrpcOutboundGate` deliberately captures the context on the event-loop
   thread before the handoff.
5. **Every Armeria service-method invocation happens on the Netty event loop**, for HTTP
   (`HttpServerHandler.serve0` under `reqCtx.push()`) and for gRPC (no `useBlockingTaskExecutor`, no
   `@Blocking`, so `listener.onMessage()` runs inline on the loop). evitaDB's handoff to its own pools
   is the only thing that leaves the loop.
6. **Producer-side calls are thread-agnostic and Armeria marshals them itself**: `HttpResponseWriter`
   / `WebSocketWriter` writes go through a multi-producer queue and hop to the subscriber executor;
   gRPC `sendHeaders` / `sendMessage` / `close` / `request` all do `if (!eventLoop.inEventLoop())
   eventLoop.execute(...)`; `HttpResponse.of(CompletableFuture)` subscribes upstream on the channel
   event loop. Virtual threads are never `inEventLoop()`, so every such call costs exactly one hop.
7. **The `blockingTaskExecutor` slot must be a `ScheduledExecutorService`** at the type level
   (`ServerBuilder.blockingTaskExecutor(ScheduledExecutorService, boolean)` /
   `BlockingTaskExecutor extends ScheduledExecutorService`). In evitaDB's configuration nothing in
   Armeria actually submits request work to it — no annotated services, no `FileService`, no
   `@Blocking`, no `useBlockingTaskExecutor(true)`; its only live uses are `GracefulShutdownSupport`
   (which already tolerates a non-`ThreadPoolExecutor`) and a Micrometer metrics binding.
8. **`whenRequestCancelling` callbacks run on the event loop** for timeouts and peer resets, and on the
   *calling* thread for explicit `ctx.cancel()` / an already-expired `setRequestTimeout(...)`.
   evitaDB's `task.cancel()` hook is cheap and non-blocking, which is the only requirement.
9. **Netty allocator thread caches never attach to plain or virtual threads** (both the pooled and
   the 4.2-default adaptive allocator gate caches on `FastThreadLocalThread` / event-executor
   identity). evitaDB's response paths produce heap `byte[]`-backed `HttpData`; the direct-buffer copy
   and every pooled-buffer release happen on the event loop. gRPC marshalling into `ByteBuf` also runs
   on the loop.
10. **A ScopedValue-backed `RequestContextStorage` is not implementable against the 1.41.1 SPI**:
    `push()` returns the previous context and `pop(current, toRestore)` is an imperative,
    try-with-resources protocol invoked from arbitrary listeners; the storage is a JVM-global singleton
    chosen at class-initialisation. ScopedValues can only be layered *on top*, via the per-request
    `contextHook` that fires on every push/pop.

---

## 1. Virtual-thread support in Armeria 1.41.1

**Search performed** (whole extraction root, all file types, case-insensitive):

```
rg -n -i \
  "VirtualThread|virtual thread|Thread\.ofVirtual|newVirtualThreadPerTaskExecutor|isVirtual\(|\bLoom\b|ScopedValue" .
```

Result: **no matches** in any of the 1,738 files. A broader `rg -n -i -w "virtual"` finds only
virtual-host handling (`armeria/com/linecorp/armeria/server/DefaultServerConfig.java:325`,
`.../server/ServerBuilder.java:1845-1912`, `.../server/RoutingContext.java:49`,
`.../server/Http1RequestDecoder.java:418`, `.../internal/server/websocket/DefaultWebSocketService.java:234`),
"Java Virtual Machine" in `armeria/com/linecorp/armeria/common/util/SystemInfo.java:142`, and a
public-suffix data entry (`armeria/com/linecorp/armeria/public_suffixes.txt:9151`).

`META-INF` contents were listed and contain only version properties, `MANIFEST.MF`, ServiceLoader
registrations (`DocServicePlugin`, `ExceptionClassifier`, `FieldMaskerSelectorProvider`,
`BlockHoundIntegration`, `SerializationFormatProvider`, `ClientFactoryProvider`,
`GrpcClientStubFactory`), native-image configs and multi-release class files for Java 9/12
`ContextAwareFuture` variants. No thread-related configuration.

**Conclusion.** Armeria 1.41.1 has no explicit virtual-thread support, detection, configuration flag,
or thread-type branching. Tests cannot be assessed from sources jars. Armeria treats "the thread" via
exactly two identity mechanisms, both delegated to Netty: `FastThreadLocal` storage (section 2) and
`EventExecutor.inEventLoop()` (sections 4 and 6).

**Netty 4.2.16, by contrast, has two virtual-thread touchpoints:**

- `netty-common: io/netty/util/internal/PlatformDependent.java:435-441` —
  `isVirtualThread(Thread)` delegating to `PlatformDependent0.isVirtualThread` (`PlatformDependent0.java:72`,
  `:593` builds a `MethodHandle` for `Thread.isVirtual`, `:612-617` invokes it). A full-text search of
  the extracted netty-common and netty-buffer sources finds **no caller** other than the two
  definitions.
- `netty-common: io/netty/util/concurrent/FastThreadLocalThread.java:163-182` —
  `runWithFastThreadLocal(Runnable)`: registers the current thread id in a copy-on-write bitmap
  (`fallbackThreads`, lines 35-36, 201-255), runs the task, then `FastThreadLocal.removeAll()`.
  `currentThreadHasFastThreadLocal()` (`:142-145`) and `currentThreadWillCleanupFastThreadLocals()`
  (`:130-137`) consult that bitmap, so allocator caches and recyclers treat the thread as a
  `FastThreadLocalThread` for the task's duration. The javadoc (`:152-159`) names virtual threads as
  the intended user and notes that scoped values may replace the backing `ThreadLocal` "in the
  future". Armeria does not call it (`rg runWithFastThreadLocal` over the Armeria tree: no hits).

---

## 2. `RequestContext` propagation mechanism

### 2.1 Storage

- SPI: `armeria/com/linecorp/armeria/common/RequestContextStorage.java:65-119` — three operations:
  `push(toPush)` returns the previously stored context (`:96-97`); `pop(current, toRestore)` must be
  handed the context that is *currently* stored and restores the previous one (`:99-108`);
  `currentOrNull()` (`:113-114`). `RequestContextStorage.hook(Function)` (`:78-80`) wraps the active
  storage and must be called at startup only (`:72-76`). `threadLocal()` returns the enum singleton
  (`:86-88`).
- Default implementation: `armeria/com/linecorp/armeria/common/ThreadLocalRequestContextStorage.java:27-61`.
  The backing store is a single static Netty `FastThreadLocal<RequestContext>` (`:31`). `push` obtains
  `InternalThreadLocalMap.get()` and swaps the slot (`:36-42`); `pop` re-reads the slot, compares
  `current.unwrapAll() != contextInThreadLocal.unwrapAll()` and **throws** `IllegalStateException` on
  mismatch (`:44-53`, exception built at `internal/common/RequestContextUtil.java:119-131`, logged once
  per thread via a weak-keyed `REPORTED_THREADS` set, `:63-64`).
- Selection: `internal/common/RequestContextUtil.java:66-82` — a `static` initialiser reads
  `Flags.requestContextStorageProvider()` and, unless `Flags.requestContextLeakDetectionSampler()`
  is `Sampler.never()` (the default: `common/DefaultFlagsProvider.java:507-510`,
  `common/Flags.java:1615-1628`), wraps the storage in `LeakTracingRequestContextStorage`. The
  provider is resolved by `common/DefaultFlagsProvider.java:138-155`: the first ServiceLoader
  `RequestContextStorageProvider` (`common/FlagsUtil.java:30-36`) wins; otherwise an anonymous
  provider returning `RequestContextStorage.threadLocal()`. A specific FQCN can be forced with
  `-Dcom.linecorp.armeria.requestContextStorageProvider=<FQCN>` (`common/Flags.java:531-546`).
  `RequestContextUtil.hook(...)` (`:133-137`) replaces the static field in place, without
  synchronisation.
- `common/RequestContextStorageProvider.java:24-32` is a one-method `@UnstableApi` functional
  interface; `common/RequestContextStorageWrapper.java:28-54` is the delegating base for hooks.
- Leak tracing: `internal/common/LeakTracingRequestContextStorage.java:53-61` replaces the pushed
  context with a `Traceable*RequestContext` wrapper that captures `Thread.getStackTrace()` at push
  (`:113-118`, `:129-134`); `pop` and `currentOrNull` delegate unchanged (`:73-83`). This is why the
  default storage compares with `unwrapAll()`.

### 2.2 Reading the context

- `common/RequestContext.java:76-82` — `RequestContext.current()` calls `currentOrNull()` and throws
  `IllegalStateException("RequestContext unavailable")` when nothing is stored.
  `currentOrNull()` (`:89-92`) is `RequestContextUtil.get()` → `requestContextStorage.currentOrNull()`
  (`internal/common/RequestContextUtil.java:142-145`).
- `server/ServiceRequestContext.java:77-87` — `ServiceRequestContext.current()` additionally requires
  `ctx.root() != null` and throws otherwise; `currentOrNull()` (`:96-104`) returns `null` when no
  context is stored; `mapCurrent(...)` (`:115-135`) throws only when a *client* context without a root
  is found.

### 2.3 Pushing

- `server/ServiceRequestContext.java:216-260` — `push()` calls `RequestContextUtil.getAndSet(this)`
  and accepts exactly three prior states: nothing stored (`:239-241`), a client context with no root
  (`:243-245`), or the same root context (re-entrance, `:247-255`). Any other stored context is put
  back and `newIllegalContextPushingException` is thrown (`:257-259`). The returned `SafeCloseable`
  invokes the context hook and pops (`internal/common/RequestContextUtil.java:191-203`).
- `common/RequestContext.java:610-614` — `replace()` swaps without validation ("do not use this if you
  don't know what you are doing").
- `internal/common/RequestContextUtil.java:167-176` — `pop()` (no arguments) removes whatever is
  stored and returns a closeable that pushes it back; the javadoc names Netty `ChannelFutureListener`s
  as the use case, i.e. pop/push pairs are issued from arbitrary Netty callbacks, not only from lexical
  scopes.

### 2.4 Context-aware wrappers

- `common/RequestContext.java:621-654` — `makeContextAware(Executor|ExecutorService|
  ScheduledExecutorService|BlockingTaskExecutor)` produce `DefaultContextAware*` wrappers;
  `:660-702` wrap `Runnable`/`Callable`/functions; `:708-734` re-wrap a `CompletionStage` so that its
  completion is delivered under `push()`.
- `common/AbstractContextAwareExecutor.java:75-86` — every submitted `Runnable` becomes
  `context.makeContextAware(task)`; if the wrapper has no context (the *propagating* variants) it
  falls back to a Micrometer `ContextSnapshot` (`:80`). `withoutContext()` (`:71-73`) exposes the raw
  executor.
- `common/DefaultContextAwareRunnable.java:32-55` — captures
  `ArmeriaContextPropagation.captureAll()` at construction (`:35`) and runs the task inside
  `context.push()` **and** `contextSnapshot.setThreadLocals()` (`:49-54`).
  `internal/common/context/ArmeriaContextPropagation.java:27-44` builds a private Micrometer
  `ContextRegistry` from ServiceLoader `ThreadLocalAccessorProvider`s; only the interface exists in
  the shipped jars (`internal/common/context/ThreadLocalAccessorProvider.java:23`), so the registry
  is empty unless the application registers one. Micrometer `context-propagation` 1.2.1 is a compile
  dependency of Armeria (`armeria-1.41.1.pom:74-77`).
- `common/PropagatingContextAwareExecutor.java:42-46` resolves the context at submit time via
  `RequestContext.mapCurrent` and logs a one-time warning with a synthetic stack trace when none is
  present (`common/AbstractContextAwareExecutor.java:30-59`).
- `common/AbstractContextAwareExecutorService.java:50-61, 80-116` and
  `common/AbstractContextAwareScheduledExecutorService.java:32-50` wrap `submit`/`invokeAll`/
  `invokeAny`/`schedule*` the same way.
- `internal/server/DefaultServiceRequestContext.java:283-291` — `ctx.blockingTaskExecutor()` lazily
  wraps `config().blockingTaskExecutor()` in `ContextAwareBlockingTaskExecutor.of(this, executor)`
  (`common/ContextAwareBlockingTaskExecutor.java:34-43` → `DefaultContextAwareBlockingTaskExecutor`).
  A task submitted through it therefore runs on the configured blocking pool **with the context
  pushed**; `withoutContext()` returns the bare `BlockingTaskExecutor` (`:56-57`).
- `internal/server/DefaultServiceRequestContext.java:324-330` — `ctx.eventLoop()` returns a
  `DefaultContextAwareEventLoop` (`common/DefaultContextAwareEventLoop.java:37-56`, an
  `AbstractContextAwareExecutorService<EventLoop>`), so callbacks submitted via `ctx.eventLoop()` run
  on the channel loop with the context pushed; `inEventLoop()` delegates to the raw loop (`:68-76`).

### 2.5 What happens on a non-Netty thread (platform or virtual)

`FastThreadLocal.get()` (`netty-common: io/netty/util/concurrent/FastThreadLocal.java:136-143`)
calls `InternalThreadLocalMap.get()` (`netty-common: io/netty/util/internal/InternalThreadLocalMap.java:110-117`):

- a `FastThreadLocalThread` reads the map from a field (`fastGet`, `:119-125`);
- **any other thread** goes through `slowGet()` (`:127-134`), a plain JDK
  `ThreadLocal<InternalThreadLocalMap>` (`:42-43`), creating the map on first access.

Per-thread cost of that first access: one `InternalThreadLocalMap` whose constructor allocates
`Object[INDEXED_VARIABLE_TABLE_INITIAL_SIZE]` with `INDEXED_VARIABLE_TABLE_INITIAL_SIZE = 32`
(`:54`, `:162-170`, filled with the `UNSET` sentinel) plus the map's own lazily-populated fields
(`futureListenerStackDepth`, `localChannelReaderStackDepth`, `handlerSharableCache`,
`typeParameterMatcherGetCache`, `charsetEncoderCache`, `:67-75`; `stringBuilder` sized by
`-Dio.netty.threadLocalMap.stringBuilder.initialSize`, `:56-57`, `:213-223`), plus the JDK
`ThreadLocalMap` entry in the thread. Rough order of magnitude: the 32-slot array is 144 bytes with
compressed oops, the map object a few dozen bytes more; treat "~200-300 bytes per thread that ever
touches a `FastThreadLocal`" as an estimate, not a measurement. The table grows on demand
(`expandIndexedVariableTableAndSet`). Nothing removes it on a non-Netty thread: `remove()` /
`destroy()` (`:136-147`) are invoked only by `FastThreadLocal.removeAll()`, which
`FastThreadLocalRunnable` (used by `DefaultThreadFactory`, `netty-common:
io/netty/util/concurrent/DefaultThreadFactory.java:105, 120-121`) and `runWithFastThreadLocal`
(`FastThreadLocalThread.java:176-181`) call at task end. Consequences:

- **per-task virtual thread**: the map is created on the first `push()` (or any other
  `FastThreadLocal` touch) and dies with the thread — a per-request allocation, no retention;
- **pooled platform thread**: created once, retained for the thread's lifetime (today's behaviour).

### 2.6 Does a task on evitaDB's own executor see the Armeria context? — No.

- evitaDB never submits through `ctx.blockingTaskExecutor()` or `ctx.makeContextAware(...)`. HTTP:
  `EndpointExecutionContext.executeAsyncInRequestThreadPool` →
  `CancellationSupport.submitWithCancellation` → `executor.createTask(...)` + `executor.execute(task)`
  (`<core>/http/EndpointExecutionContext.java:143-146, 182-188`;
  `<core>/http/CancellationSupport.java:162-179`). gRPC: `EvitaSessionService.executeWithClientContext`
  (`<grpc-server>/services/EvitaSessionService.java:187-218`)
  captures `ServiceRequestContext.current()` on the calling (event-loop) thread (`:194`), captures the
  gRPC `Context` (`:198`) and re-enters it on the worker via `grpcContext.run(...)` (`:203`), then
  `executor.execute(task)` (`:217`). Neither path pushes the Armeria context on the worker.
- `ObservableThreadExecutor` wraps every task in `ObservableRunnable`/`ObservableCallable`
  (`evita_engine/src/main/java/io/evitadb/core/executor/ObservableThreadExecutor.java:235-253, 937, 1034`)
  which capture `TracingContext.captureContext()` at construction (`:698`) and restore it around
  `delegate.run()` (`:1001-1015`, `:1099-1112`). `TracingContext.captureContext()` copies four MDC
  keys and the `CLIENT_LABELS` `ThreadLocal`
  (`evita_api/src/main/java/io/evitadb/api/observability/trace/TracingContext.java:230-262`)
  — nothing Armeria-related. Worker threads are plain `new Thread(...)`
  (`ObservableThreadExecutor.java:1145-1152`; `Scheduler.java:1036-1041`), not `FastThreadLocalThread`.
- Therefore, on `Evita-request-N` / `Evita-transaction-N` / `Evita-service-N`,
  `RequestContext.currentOrNull()` reads a `FastThreadLocal` slot that was never set on that thread
  (`ThreadLocalRequestContextStorage.java:58-60`) and returns **`null`**, and `RequestContext.current()`
  / `ServiceRequestContext.current()` **throw**. Two places in evitaDB already reflect this:
  `evita_server/src/main/java/io/evitadb/server/log/AppLogJsonLayout.java:146-162` (`duration_ms` is
  emitted only when `RequestContext.currentOrNull()` is non-null — i.e. only for log events written on
  the event loop or inside a context-aware callback), and
  `<grpc-server>/utils/GrpcOutboundGate.java:243-244`,
  which captures `ServiceRequestContext.currentOrNull()` and `ctx.eventLoop()` at attach time on the
  service-method thread because the producing loop runs on a worker.

### 2.7 Could the SPI be swapped for a ScopedValue-backed storage?

Constraints imposed by the 1.41.1 SPI, all from the sources above:

1. **Imperative push/pop.** `push(toPush)` must return the previously stored context and
   `pop(current, toRestore)` must restore it later, from a different call frame
   (`RequestContextStorage.java:96-108`). A `ScopedValue` binding is lexically scoped
   (`ScopedValue.where(...).run(...)`) and cannot be "returned and restored later". Every Armeria call
   site is written as `try (SafeCloseable ignored = ctx.push()) { ... }` — e.g.
   `armeria/com/linecorp/armeria/server/HttpServerHandler.java:541`,
   `armeria-grpc/com/linecorp/armeria/server/grpc/FramedGrpcService.java:310`,
   `armeria-grpc/com/linecorp/armeria/internal/server/grpc/AbstractServerCall.java:408, 425, 434` — so
   a storage cannot see the body it should scope.
2. **Pop from arbitrary listeners.** `RequestContextUtil.pop()` (`:167-176`) removes the context
   inside Netty `ChannelFutureListener`s and pushes it back afterwards. There is no enclosing scope.
3. **JVM-global singleton, chosen at class-init.** `RequestContextUtil.<clinit>` (`:70-82`) picks one
   storage for the whole JVM via ServiceLoader or system property; `hook()` (`:133-137`) may wrap it at
   startup only. There is no per-server or per-thread-type choice.
4. **Identity discipline.** Both `pop` (`ThreadLocalRequestContextStorage.java:49-51`) and `push`
   (`ServiceRequestContext.java:247-259`) validate the stored object against the caller's context and
   throw on mismatch. Any alternative storage must preserve `unwrapAll()` identity semantics because
   `LeakTracingRequestContextStorage` may have wrapped what was pushed (`:55-61`).

**Conclusion.** A ScopedValue cannot *replace* the storage. What the SPI does allow is layering: the
per-request `contextHook` (`server/ServerBuilder.java:2389-2400`,
`common/RequestContext.java:544-560`, invoked from `RequestContextUtil.invokeHookAndPop`
`:191-203` on every push and closed on every pop) can bind or clear application state whenever
Armeria pushes a context. It cannot open a `ScopedValue` scope either (same lexical problem), but it
can populate an ordinary `ThreadLocal` or MDC. A ScopedValue binding for evitaDB's own request state
would have to be opened by evitaDB's task wrapper on the worker, not by Armeria.

---

## 3. `blockingTaskExecutor`

### 3.1 What it is and how it is wired

- `server/ServerBuilder.java:1080-1092` accepts any `ScheduledExecutorService` plus a
  `shutdownOnStop` flag; `:1094-1106` accepts a `BlockingTaskExecutor`; `:1108-1121` builds one from a
  thread count. Default: `CommonPools.blockingTaskExecutor()` set in the builder constructor
  (`:269`). `server/VirtualHostBuilder.java:1177-1181` wraps a plain `ScheduledExecutorService` via
  `BlockingTaskExecutor.of(...)`.
- `common/util/BlockingTaskExecutor.java:28` — `interface BlockingTaskExecutor extends
  ScheduledExecutorService`; `of(ScheduledExecutorService)` (`:43-50`) returns the argument if it
  already is one, else a `DefaultBlockingTaskExecutor` (`common/util/DefaultBlockingTaskExecutor.java:29-132`,
  pure delegation, `unwrap()` returns the delegate).
- Default pool: `common/util/BlockingTaskExecutorBuilder.java:40-45, 130-143` — a
  `ScheduledThreadPoolExecutor` with `numThreads = Flags.numCommonBlockingTaskThreads()` (200,
  "from Tomcat default maxThreads", `common/DefaultFlagsProvider.java:55, 203-206`), 60 s keep-alive
  with `allowCoreThreadTimeOut(true)`, daemon threads, name prefix `armeria-blocking-tasks`, and the
  executor's inherent unbounded delayed work queue. `CommonPools` creates its instance eagerly at
  class-init (`common/CommonPools.java:41-43`), which happens as soon as `ServerBuilder` is
  constructed (`ServerBuilder.java:269`), regardless of what evitaDB passes afterwards. Threads are
  only spawned on demand.
- `server/DefaultServerConfig.java:207-208, 364-371` binds Micrometer `ExecutorServiceMetrics` to
  `executor.unwrap()`.
- `server/GracefulShutdownSupport.java:32-37, 150-158` — the executor is only used to ask "are
  blocking tasks still running?", and only if it `instanceof ThreadPoolExecutor`; otherwise it
  answers "yes, all completed" (`:151-154`). `server/Server.java:474-480` passes
  `config().blockingTaskExecutor()`.

### 3.2 Who submits to it (Armeria core + gRPC)

All call sites of `blockingTaskExecutor()` in the core jar (the gRPC ones are in 3.3):

- Annotated services (`@Blocking`, `useBlockingTaskExecutor`) —
  `internal/server/annotation/DefaultAnnotatedService.java:405-431, 466`;
  `server/annotation/Blocking.java:26-33`. **Not active**: evitaDB registers no annotated services.
- `FileService` / `HttpFile` / `CachingHttpFile` read files and attributes on it —
  `server/file/FileService.java:242, 318, 390, 443, 531`; `server/file/AbstractHttpFile.java:259, 303`;
  `server/file/CachingHttpFile.java:80`. **Not active.**
- Response converters for `Stream`/Jackson/String/byte[]/SSE —
  `server/annotation/JacksonResponseConverterFunction.java:117, 130`,
  `server/annotation/StringResponseConverterFunction.java:85`,
  `server/annotation/ByteArrayResponseConverterFunction.java:87`,
  `server/annotation/ServerSentEventResponseConverterFunction.java:76`,
  `internal/server/annotation/AggregatedResponseConverterFunction.java:76`. **Not active.**
- `StreamMessage` file / `InputStream` sources fall back to it when a `ServiceRequestContext` is
  current — `common/stream/StreamMessage.java:394-396`, `common/stream/InputStreamStreamMessage.java:144-146`,
  `common/stream/PathStreamMessage.java:157`, `common/stream/StreamMessages.java:68-75`. **Not
  active**: evitaDB does not use those factories.
- Multipart temp files — `internal/server/FileAggregatedMultipart.java:104`. **Not active.**
- Kotlin coroutine dispatcher — `armeria-grpc/.../server/grpc/ArmeriaCoroutineContextInterceptor.java:100-101`.
  **Not active.**
- `GracefulShutdownSupport` — `server/Server.java:479`. **Active** (see 3.1).
- Metrics binding — `server/DefaultServerConfig.java:364-371`. **Active.**

Things that look related but use the **common** pool, not the server's:
`common/TlsProvider.java:57-59` (`ofScheduled` → `CommonPools.blockingTaskExecutor()`),
`common/util/DefaultAsyncLoader.java:73`, `common/file/PathWatcher.java:79`. evitaDB does not use
`TlsProvider.ofScheduled`; it installs its own `DynamicTlsProvider`
(`<core>/certificate/DynamicTlsProvider.java:45`)
and reloads certificates via `serviceExecutor.scheduleAtFixedRate(...)`
(`.../certificate/CertificateService.java:106`), i.e. on evitaDB's `Scheduler`, not through Armeria.
`DocService` loads specifications on its own single-thread executor
(`server/docs/DocService.java:205-206`).

**evitaDB passes `evita.getServiceExecutor()`** with `shutdownOnStop = false`
(`evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/ExternalApiServer.java:571`).
That object is `io.evitadb.core.executor.Scheduler`, which `implements ObservableExecutorService,
ScheduledExecutorService` over a `ScheduledThreadPoolExecutor` of plain threads named
`Evita-service-N` (`evita_engine/src/main/java/io/evitadb/core/executor/Scheduler.java:67, 100, 184-186, 1036-1041`;
constructed in `evita_engine/src/main/java/io/evitadb/core/Evita.java:454-458`). Since `Scheduler`
is not a `ThreadPoolExecutor`, `GracefulShutdownSupport.completedBlockingTasks()` already returns
`true` unconditionally for evitaDB today.

### 3.3 gRPC: which thread runs `EvitaSessionService.*`?

- `armeria-grpc/com/linecorp/armeria/server/grpc/GrpcServiceBuilder.java:758-767` —
  `useBlockingTaskExecutor(boolean)`: "By default, service methods are executed directly on the event
  loop".
- `armeria-grpc/com/linecorp/armeria/server/grpc/HandlerRegistry.java:180-182` —
  `needToUseBlockingTaskExecutor(methodDef)` is a set lookup; the set is filled at registration when
  the method **or its class** carries `@Blocking` (`:278-281`, used at `:372` and `:412`).
- `armeria-grpc/com/linecorp/armeria/server/grpc/FramedGrpcService.java:287-314` — if neither flag
  applies, `blockingExecutor == null` and `startCall(...)` runs immediately inside `ctx.push()`
  (`:307-313`); otherwise it is dispatched to
  `MoreExecutors.newSequentialExecutor(ctx.blockingTaskExecutor())` (`:299`).
- `armeria-grpc/com/linecorp/armeria/internal/server/grpc/AbstractServerCall.java:365-369, 402-417`
  — `onRequestMessage` → `invokeOnMessage` calls `listener.onMessage(request)` (and `onHalfClose`)
  inline, under `ctx.push()`, when `blockingExecutor == null`; `onRequestComplete` → `invokeHalfClose`
  likewise (`:375-385, 419-431`); `invokeOnComplete` / `invokeOnCancel` (`:309-321`).
  `onRequestMessage` itself is driven by the HTTP/2 deframer on the channel event loop.
- evitaDB's registration (`<grpc-server>/GrpcProviderRegistrar.java:108-125`)
  never calls `useBlockingTaskExecutor(...)`, and `rg "@Blocking"` over `evita_external_api` finds no
  annotated service or method.

**Confirmed:** every `EvitaSessionService` / `EvitaService` / `EvitaManagementService` method body
starts on an `armeria-eventloop-epoll-N` thread (an `EventLoopThread extends FastThreadLocalThread
implements NonBlocking`, `internal/common/util/EventLoopThread.java:28`, created by
`common/util/EventLoopThreadFactory.java:92`), with the `ServiceRequestContext` pushed; that is why
`ServiceRequestContext.current()` at `EvitaSessionService.java:194, 783, 919, 958, 2617` and
`EvitaService.java:140` succeeds. `executeWithClientContext` then hands the body to
`evita.getRequestExecutor()` (`EvitaSessionService.java:187-218`).

The same holds for HTTP: `server/HttpServerHandler.java:539-556` (`serve0`) invokes
`service.serve(reqCtx, req)` under `reqCtx.push()` on the service event loop (evitaDB configures the
same group as `serviceWorkerGroup` and `workerGroup`, `ExternalApiServer.java:616, 618`);
`EndpointHandler.serve` (`.../http/EndpointHandler.java:84-144`) runs there, returns
`HttpResponse.streaming()` immediately (`:98`) and dispatches via
`executeAsyncInRequestThreadPool` / `executeAsyncSupplierInRequestThreadPool`.

---

## 4. Response-completion threading

Armeria's rule, consistently: **producers may call from any thread; the consumer side (encoder,
pooled-buffer release, timeout task) must be on the channel event loop, and Armeria performs that
hop itself.** Evidence per path:

### 4.1 HTTP: `HttpResponse.of(CompletableFuture)` / `from` / `streaming()`

- `common/HttpResponse.java:576-593` — `of(CompletableFuture)` / `of(CompletionStage)` create a
  `DeferredHttpResponse` and `delegateWhenComplete(stage)`; `:605-613` allows an explicit
  `subscriberExecutor`; `:621-631` (`of(Supplier, Executor)`) runs the supplier on the given executor.
  `from(...)` (`:97-142`) are the deprecated aliases.
- `common/stream/DeferredStreamMessage.java:167-213` — `delegate(upstream)` is executed on **whichever
  thread completes the future**; it CAS-sets the upstream (`:170`), forwards completion, and calls
  `safeOnSubscribeToUpstream()` (`:357-370`), which subscribes the upstream with
  `downstreamSubscription.executor()` — the executor the *server* supplied when it subscribed.
  `subscribe(...)` (`:319-337`) delivers `onSubscribe` directly only when
  `subscription.needsDirectInvocation()`, otherwise via `subscription.executor().execute(...)`;
  `needsDirectInvocation()` is `executor.inEventLoop()` (`common/stream/CancellableStreamMessage.java:226-228`).
- `common/HttpResponse.java:83-84` — `streaming()` returns a `DefaultHttpResponse`, a
  `DefaultStreamMessage`. `common/stream/DefaultStreamMessage.java:112` backs it with a JCTools
  `MpscChunkedArrayQueue` (multi-producer); `write` → `addObjectOrEvent` (`:322-326`) enqueues and
  `notifySubscriber()` (`:328-348`) hops to `subscription.executor()` unless already in the loop;
  `close`/`abort` follow the same pattern (`:222-232, 300-305`).
- The server subscribes every response with the **channel event loop** and
  `SubscriptionOption.WITH_POOLED_OBJECTS` (`server/HttpServerHandler.java:502-515`; the aggregated
  path uses `res.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), channelEventLoop))`,
  `:514`). The response subscriber releases `HttpData` on that loop
  (`server/AbstractHttpResponseSubscriber.java:115-120, 154, 223`), and the encoders assert it
  (`internal/common/HttpObjectEncoder.java:49, 65`).

evitaDB's `EndpointHandler` writes headers, body and `close()` from the continuation of
`doHandleRequest(...)` (`EndpointHandler.java:100-141`), i.e. on whichever worker completed the
future (the `eventExecutor` parameter handed to `writeResponse` is `ctx.eventLoop()` at `:119`, but
`JsonRestHandler.java:83`, `GraphQLWebHandler.java:216`, `GraphQLSchemaHandler.java:132`,
`JfrRecordingEndpointHandler.java:116`, `OpenApiSpecificationHandler.java:97` write directly). That is
supported by the queue+hop design above; a virtual-thread worker changes nothing except that
`inEventLoop()` is always `false` (it already is for `Evita-request-N`).

### 4.2 gRPC: `StreamObserver#onNext/onCompleted` from a non-event-loop thread

`StreamObserver` calls land on Armeria's `ServerCall` implementations, each of which marshals:

- `sendHeaders` — `armeria-grpc/.../internal/server/grpc/AbstractServerCall.java:479-486`.
- `sendMessage` — `armeria-grpc/.../server/grpc/UnaryServerCall.java:121-128` (stores the single
  message on the loop, `:130-139`; the payload is built in `doClose`, `:147-175`) and
  `armeria-grpc/.../server/grpc/StreamingServerCall.java:132-140` (`doSendMessage` → `res.tryWrite`,
  `:142-181`; `onReady` is re-armed from `res.whenConsumed()` on the loop, `:161-174`).
- `close` — `AbstractServerCall.java:210-249`: status mapping through the exception handler's
  future (`:218-223, 230-235`), then `if (ctx.eventLoop().inEventLoop()) doClose(...) else
  ctx.eventLoop().execute(...)` (`:241-249`); `doClose` checks `closeCalled` and completes the
  response on the loop (`:251-281`).
- `request(n)` — `StreamingServerCall.java:111-116`.
- Cancellation feedback — `AbstractServerCall.java:180-188` hops `res.whenComplete()` → `maybeCancel`
  onto the loop.
- Listener callbacks are always delivered on the loop, or, when a blocking executor is configured, on
  a `sequentialExecutor` that preserves ordering (`AbstractServerCall.java:309-321, 365-383, 387-431`;
  `armeria-grpc/.../server/grpc/DeferredListener.java:54-62`).

So evitaDB's `responseObserver.onNext(...)` / `onCompleted()` from `Evita-request-N`
(`EvitaSessionService.java:267-269, 324-326, 428-430` etc.) is supported; ordering is preserved by
the single event-loop task queue; each call costs one `eventLoop.execute`. Marshalling to
`ByteBuf` (`toPayload`) happens on the loop, not on the producer. `GrpcOutboundGate` relies on the
`onReady` callback from `StreamingServerCall.java:164-173` and on `callEventLoop.inEventLoop()` to
detect a producer that *is* the loop (`GrpcOutboundGate.java:286-300`); a virtual-thread producer is
never the loop, so the gate behaves as with today's platform workers. Its timeout re-arm
(`GrpcTimeoutUtil.java:124` → `ctx.setRequestTimeout(...)`) is thread-safe: see 5.2.

### 4.3 WebSocket writes from foreign threads

- `common/websocket/DefaultWebSocket.java:20` — `WebSocket.streaming()` is a
  `DefaultStreamMessage<WebSocketFrame>`; `tryWrite` is the same MPSC-queue path as 4.1 (`:23-32`).
- `internal/server/websocket/DefaultWebSocketService.java:409-443` — the outbound `WebSocket` is
  mapped frame-by-frame to `HttpData.wrap(encoder.encode(ctx, frame))` and returned as
  `HttpResponse.of(headers, StreamMessage)`; the `map` runs on the subscriber's executor (the channel
  loop, 4.1), and the encoder allocates from `ctx.alloc()` there
  (`internal/common/websocket/WebSocketFrameEncoder.java:153-175`).
- evitaDB's sub-protocol handlers already subscribe their result streams with `ctx.eventLoop()`
  (`evita_external_api/evita_external_api_graphql/.../io/webSocket/GraphQLWSSubProtocol.java:171-175, 286`;
  `evita_external_api/evita_external_api_rest/.../io/webSocket/RestWSSubProtocol.java:222`), so their
  `out.tryWrite(...)` calls (`GraphQLWSSubProtocol.java:351-418`, `RestWSSubProtocol.java:287-331`)
  run on the loop today; calling them from a worker would be equally legal.

### 4.4 Thread-affinity assumptions the application executor must respect

None on the producer side beyond "do not block the event loop". The only hard rule that involves the
application thread is the **context push discipline** of 2.3: if application code calls
`ctx.push()` on a worker, it must pop on the same thread before the task ends, and it must not push
while a *different* root context is stored there (`ServiceRequestContext.java:257-259`). With one
virtual thread per task this cannot leak across requests; with pooled platform threads a missed pop
poisons the thread for its next request.

---

## 5. Event-loop configuration and timeout callbacks

### 5.1 Sizes and flags

- `common/util/EventLoopGroupBuilder.java:56` — default `numThreads = Flags.numCommonWorkers()`;
  `:132-152` builds via `TransportType.newIoEventLoopGroup(numThreads, factory)`, default factory
  `ThreadFactories.newEventLoopThreadFactory("armeria-eventloop-<transport>", false)` (`:138-139`),
  which produces `EventLoopThread` (`common/util/EventLoopThreadFactory.java:92`).
- `common/DefaultFlagsProvider.java:193-201` — `numCommonWorkers` = `availableProcessors() * 2`
  (`* 1` for io_uring); exposed as `Flags.numCommonWorkers()` (`common/Flags.java:225-227, 712-717`).
- `common/DefaultFlagsProvider.java:162-171` — `transportType` = epoll if available, else kqueue,
  else nio (`Flags.java:575-584`; `useEpoll()` deprecated, `:560-573`).
- `common/DefaultFlagsProvider.java:55, 203-206` — `numCommonBlockingTaskThreads` = 200.
- `common/DefaultFlagsProvider.java:73, 56` — default request timeout 10 s, max request length 10 MiB
  (`Flags.java:740, 764`); evitaDB overrides both (`ExternalApiServer.java:605, 617`).
- `common/DefaultFlagsProvider.java:507-510` — leak-detection sampler `never()`.
- evitaDB: `EventLoopGroups.builder().numThreads(apiOptions.workerGroupThreadsAsInt())`
  (`ExternalApiServer.java:544-553`), used as both `serviceWorkerGroup` and `workerGroup` (`:616,
  618`), so service invocation and I/O share the same loops.

### 5.2 Which thread runs `whenRequestCancelling` callbacks

- `internal/server/DefaultServiceRequestContext.java:182-189` — the context owns a
  `CancellationScheduler.ofServer(requestTimeoutNanos)` initialised with `ch.eventLoop()`.
- `server/AbstractHttpResponseHandler.java:226-230` — `scheduleTimeout()` installs the cancellation
  task and calls `start()`; `internal/common/DefaultCancellationScheduler.java:108-129` schedules
  `eventLoop().schedule(() -> invokeTask(null), timeoutNanos)` (`:124`).
- `DefaultCancellationScheduler.java:353-379` — `invokeTask(cause)` takes the lock, flips the state,
  releases the lock, then **synchronously on the invoking thread** completes `whenCancelling()`
  (`:369`), runs the task (`:375`; the server's task hops to the loop if needed,
  `AbstractHttpResponseHandler.java:246-253`), and completes `whenCancelled()` (`:378`). The futures are
  plain `CompletableFuture`s (`CancellationFuture extends UnmodifiableFuture`, `:418-423`), so
  non-async `thenAccept` continuations run inline on that thread.
- Invoking threads:
  - request timeout → **event loop** (`:124, 223, 248, 271`);
  - peer reset / stream close → `abortResponse` → `ctx.cancel(cause)` on the **event loop**
    (`server/AggregatingDecodedHttpRequest.java:182-201`, `server/StreamingDecodedHttpRequest.java:202-218`);
  - explicit `ctx.cancel()` / `timeoutNow()` → `finishNow` → `invokeTask` on the **calling thread**
    (`DefaultServiceRequestContext.java:371-373`, `DefaultCancellationScheduler.java:286-289`);
  - `setRequestTimeout(...)` whose remaining budget is already ≤ 0 → `INVOKE_IMMEDIATELY` →
    `invokeTask` on the **calling thread** (`DefaultCancellationScheduler.java:167-191, 218-219,
    243-244`).
- `setRequestTimeout` is safe from any thread: it takes the scheduler's lock, cancels the previous
  `ScheduledFuture`, and re-schedules on `eventLoop().schedule(...)` (`:167-187, 253-274`). evitaDB
  calls it from workers (`GrpcTimeoutUtil.java:124` via `GrpcOutboundGate.java:370`).

evitaDB's hooks (`CancellationSupport.java:85-93, 105-129`) only call `task.cancel()` (thread
interrupt + future completion) and set an `AtomicReference`, so they are safe on the event loop.
They will keep running on the loop with a virtual-thread executor — the executor choice does not
change where Armeria completes these futures.

---

## 6. Netty per-thread state relevant to virtual threads

### 6.1 `FastThreadLocal` on a non-`FastThreadLocalThread`

Covered in 2.5. Additional detail:

- `netty-common: io/netty/util/concurrent/FastThreadLocalThread.java:86-87` — `threadLocalMap()`
  warns when read from a different thread; identity is `this != Thread.currentThread()`.
- `netty-common: io/netty/util/internal/ThreadExecutorMap.java:29-36` — the "current
  `EventExecutor`" is itself a `FastThreadLocal<EventExecutor>`, set by the executor's thread
  factory wrapper (`:51-93`). On any thread not started by an event executor `currentExecutor()` is
  `null`; this is the second gate the allocators use.

### 6.2 `inEventLoop` identity checks

- `netty-common: io/netty/util/concurrent/EventExecutor.java:42-44` — `inEventLoop()` is
  `inEventLoop(Thread.currentThread())`.
- `netty-common: io/netty/util/concurrent/SingleThreadEventExecutor.java:91, 722-723` —
  `inEventLoop(Thread thread)` is `thread == this.thread`, a reference comparison against the loop's
  own (platform, `FastThreadLocalThread`) thread. A virtual thread is never that object, so every
  Armeria `inEventLoop()` guard in section 4 evaluates to `false` on a virtual thread and the call is
  queued — the correct, already-exercised path.

### 6.3 Allocators and thread-local caches

- Default allocator in Netty 4.2: `netty-buffer: io/netty/buffer/ByteBufUtil.java:76-98` —
  `-Dio.netty.allocator.type` defaults to `"adaptive"` (`AdaptiveByteBufAllocator`); `"pooled"`
  selects `PooledByteBufAllocator.DEFAULT`. Armeria does not set `ChannelOption.ALLOCATOR` anywhere
  (`rg ChannelOption.ALLOCATOR` over the Armeria tree: no hits), and `ctx.alloc()` is simply
  `ch.alloc()` (`internal/server/DefaultServiceRequestContext.java:332-335`), so the server uses
  Netty's default unless evitaDB sets the system property (not found in the checkout).
- Pooled allocator: `netty-buffer: io/netty/buffer/PooledByteBufAllocator.java:149-150` —
  `io.netty.allocator.useCacheForAllThreads` defaults to **false** (`:448-451`).
  `PoolThreadLocalCache.initialValue` (`:516-551`) creates a real `PoolThreadCache` only when that
  flag is set, or `FastThreadLocalThread.currentThreadHasFastThreadLocal()`, or
  `ThreadExecutorMap.currentExecutor() != null`; otherwise a zero-sized cache (`:550`). Freed on
  removal (`:553-556`), which for non-Netty threads only happens via
  `FastThreadLocal.removeAll()` (`:584` consults `currentThreadWillCleanupFastThreadLocals()`).
- Adaptive allocator: `netty-buffer: io/netty/buffer/AdaptiveByteBufAllocator.java:34-38` —
  `io.netty.allocator.useCachedMagazinesForNonEventLoopThreads` defaults to **false**;
  `netty-buffer: io/netty/buffer/AdaptivePoolingAllocator.java:208-215` creates thread-local magazine
  groups only for that flag or `currentExecutor() != null`, and `:243-252` uses them only when
  `currentThreadWillCleanupFastThreadLocals()`; everything else goes to the shared size-classed
  magazines. Ownership of free-lists is checked by `Thread.currentThread() == ownerThread`
  (`:1827, 1883, 1894`).
- `netty-buffer: io/netty/buffer/ByteBufUtil.java:116` — temporary thread-local byte arrays are used
  only when `currentThreadHasFastThreadLocal()`.

**Net effect on virtual threads:** no per-thread allocator cache is ever created, so there is no
cache-retention or cache-per-thread-explosion problem; allocations from a virtual thread take the
shared (lock-protected / CAS) path. Whether that is slower per allocation than today's plain platform
workers is *not* a change — those workers are not `FastThreadLocalThread`s either and already take
the same shared path.

### 6.4 Do evitaDB's application threads allocate pooled buffers?

- HTTP responses: `HttpData.copyOf(byte[])` / `wrap(byte[])` / `ofUtf8` create `ByteArrayHttpData`
  (`common/HttpData.java:56-62, 119-125`) — heap arrays, no `ByteBuf`. The direct copy for I/O is
  made only by the encoder (`internal/common/HttpObjectEncoder.java:116-124` →
  `internal/common/ByteArrayBytes.java:118-124, 147-149`, `ByteBufAllocator.DEFAULT.directBuffer`)
  on the event loop (`HttpObjectEncoder.java:65`). evitaDB main code never calls `ctx.alloc()`,
  `HttpData.wrap(ByteBuf)` or `PooledObjects` (grep over `evita_external_api` main sources: only
  `HttpData.copyOf`/`ofUtf8` at the sites listed in 4.1).
- gRPC: the message → `ByteBuf` marshalling (`toPayload`) is called from `doClose` /
  `doSendMessage`, both on the loop (`UnaryServerCall.java:157`, `StreamingServerCall.java:161`).
  Request deframing wraps retained pooled DATA frames on the loop
  (`server/Http2RequestDecoder.java:347`); `unsafeWrapRequestBuffers` is off
  (`GrpcProviderRegistrar.java:108-125` never enables it), so
  `GrpcUnsafeBufferUtil.storeBuffer` (`AbstractServerCall.java:361-363`) is never reached.
- WebSocket frame encoding allocates from `ctx.alloc()` on the loop (4.3).

**Cannot verify from this checkout:** whether Netty's *release* of a pooled buffer from a thread
other than the allocating one has a measurable cost beyond "no thread cache" — only the cache
selection code was read, not `PoolArena`/`PoolChunk`. It is moot for evitaDB, whose worker threads
neither allocate nor release pooled buffers.

---

## 7. MDC / logging

- Armeria's MDC bridge is `common/logging/RequestContextExporter.java:45` (`export()` reads
  `RequestContext.currentOrNull()` at `:204`). Inside the shipped jars it is referenced only from
  its builder, `ExportGroup` and `BuiltInProperty` docs (`common/logging/ExportGroup.java:32`,
  `common/logging/BuiltInProperty.java:54-56`); the Logback appender that would drive it lives in
  `armeria-logback`, which evitaDB does **not** depend on — the only Armeria artifacts in evitaDB's
  poms are `armeria` (`pom.xml:314`, `evita_external_api/evita_external_api_core/pom.xml:65`),
  `armeria-grpc` / `armeria-grpc-protocol` (`evita_external_api/evita_external_api_grpc/server/pom.xml:55-60`,
  `.../grpc/shared/pom.xml:186`, `.../grpc/client/pom.xml:60`) and `armeria-graphql`
  (`evita_external_api/evita_external_api_graphql/pom.xml:83`). `rg RequestContextExporter` over
  the evitaDB tree: no hits.
- evitaDB's MDC is its own: keys `clientId`, `traceId`, `clientIp`, `clientUri`
  (`TracingContext.java:107-127`), set by `executeWithinBlock` and propagated across executor hops by
  `ObservableThreadExecutor` (2.6). This is already a copy-per-task scheme, so it is indifferent to
  the thread type; on virtual threads `MDC` (a JDK `ThreadLocal` under the hood) simply becomes
  per-task.
- The one Armeria-dependent field, `duration_ms` in `AppLogJsonLayout.java:114-120, 146-162`, is
  populated only when a log event is written on a thread holding the Armeria context — i.e. today
  only for events emitted on the event loop or inside `ctx.eventLoop()` callbacks, never from
  `Evita-request-N`. Moving the workers to virtual threads neither fixes nor worsens this; pushing the
  context in evitaDB's task wrapper would.

---

## 8. What breaks if `blockingTaskExecutor` were a virtual-thread-per-task executor

- **Compile-time type.** Both overloads require a `ScheduledExecutorService`
  (`server/ServerBuilder.java:1087`) or a `BlockingTaskExecutor` (`:1101`), and
  `BlockingTaskExecutor extends ScheduledExecutorService` (`common/util/BlockingTaskExecutor.java:28`).
  `Executors.newVirtualThreadPerTaskExecutor()` returns an `ExecutorService`, so it cannot be
  passed without an adapter that supplies `schedule*`. Armeria does not provide one;
  `BlockingTaskExecutorBuilder` only builds a `ScheduledThreadPoolExecutor` with a `ThreadFactory`
  (`common/util/BlockingTaskExecutorBuilder.java:130-143`) — a `Thread.ofVirtual().factory()` could be
  handed to that builder's `threadFactory`, but `ScheduledThreadPoolExecutor` still pools those
  threads (core size = `numThreads`), which defeats the per-task model.
- **Does Armeria ever call `schedule*` on the server executor?** In this checkout, no:
  `rg "blockingTaskExecutor\(\)\.(schedule|scheduleAtFixedRate|scheduleWithFixedDelay)"` over
  core and gRPC finds nothing. The `schedule*` methods are only forwarded by the context-aware
  wrapper (`common/AbstractContextAwareScheduledExecutorService.java:32-50`). `TlsProvider.ofScheduled`
  schedules on the **common** pool, not the server's (`common/TlsProvider.java:57-59`).
- **Graceful shutdown** is unaffected: `GracefulShutdownSupport.completedBlockingTasks()`
  short-circuits for anything that is not a `ThreadPoolExecutor` (`server/GracefulShutdownSupport.java:150-158`)
  — evitaDB is already in that branch because `Scheduler` is a wrapper.
- **Metrics binding**: `DefaultServerConfig.java:364-371` hands `executor.unwrap()` to Micrometer's
  `ExecutorServiceMetrics`. How Micrometer treats an executor that is neither a `ThreadPoolExecutor`
  nor a `ForkJoinPool` cannot be established from this checkout (Micrometer sources not inspected);
  today it receives evitaDB's `Scheduler`, so whatever it does with an unknown type is already what
  happens.
- **Armeria's own `Scheduler`-like uses** (`CommonPools.blockingTaskExecutor()`) are a separate
  instance created at class-init (`common/CommonPools.java:41-43`) and untouched by the server
  setting.
- **In evitaDB's configuration nothing dispatches request work to the server executor** (3.2), so
  swapping it is behaviourally inert for request processing. The executors that matter for a
  virtual-thread migration are `requestExecutor` / `transactionExecutor`
  (`ObservableThreadExecutor`, `Evita.java:459-467`), which Armeria never sees, and the `Scheduler`
  used for maintenance jobs. If `Scheduler` stayed a `ScheduledThreadPoolExecutor` and only the
  request/transaction pools moved to virtual threads, the `blockingTaskExecutor` slot would not need
  to change at all.

---

## 9. Request aggregation and pooled-buffer ownership

- **Exchange type decides who aggregates.** `server/HttpService.java:71-73` defaults
  `exchangeType` to `BIDI_STREAMING`; evitaDB's services do not override it (`rg exchangeType` over
  `evita_external_api`: no hits), so `server/DecodedHttpRequest.java:53-57` creates a
  `StreamingDecodedHttpRequest` and `HttpServerHandler` invokes the service immediately (`:455-461`).
  gRPC computes the type per method (`armeria-grpc/.../server/grpc/FramedGrpcService.java:201-205`
  → `armeria-grpc/.../internal/common/grpc/GrpcExchangeTypeUtil.java:32-45`), so unary RPCs get an
  `AggregatingDecodedHttpRequest` (`DecodedHttpRequest.java:59-61`) and the service runs after
  `whenAggregated` (`HttpServerHandler.java:447-454`), on the loop.
- **`req.aggregate()` from evitaDB's worker** (`EndpointHandler.java:249-269`, called from
  `readRequestBody` inside the request-pool task): `common/HttpRequest.java:564-565` →
  `aggregate(defaultSubscriberExecutor())`; for decoded requests that executor is the channel
  event loop (`server/StreamingDecodedHttpRequest.java:141-143`,
  `server/AggregatingDecodedHttpRequest.java:145-147`). The default options are `cacheResult(true)`
  without pooled objects (`HttpRequest.java:581-586`; `common/AggregationOptionsBuilder.java:38-62`,
  `usePooledObjects` only when requested), so the aggregated content is a heap `byte[]`
  (`r.content()` close at `EndpointHandler.java:252` is a no-op) and the pooled DATA frames retained
  at `Http2RequestDecoder.java:347` are released by the aggregator on the loop. The returned future
  therefore completes **on the event loop**, and evitaDB's non-async `thenApply` (`:251-269`, which
  decodes the whole body into a `String`) runs there, not on the worker. This is independent of the
  executor type but worth knowing: on a large body the JSON/GraphQL text decode currently occupies an
  event-loop thread.
- **Does Armeria hold buffers that must be released on a specific thread?** No such contract is
  imposed on the application: Armeria releases what it owns on the loop (4.1, 4.3, 6.4) and hands
  evitaDB only heap-backed `HttpData` / deserialised protobuf messages. The two opt-ins that would
  change that — `AggregationOptions.usePooledObjects(...)` and `GrpcServiceBuilder.unsafeWrapRequestBuffers`
  — are not used by evitaDB. Netty reference counting itself is thread-agnostic (release is an atomic
  decrement, `netty-common: io/netty/util/internal/ReferenceCountUpdater` is even whitelisted for
  BlockHound at `common/CoreBlockHoundIntegration.java:42`); only the cache/magazine *placement*
  depends on the thread (6.3).
- **BlockHound.** Armeria marks `NonBlocking` threads (its event loops) as non-blocking
  (`common/CoreBlockHoundIntegration.java:31-33`; gRPC integration at
  `armeria-grpc/.../common/grpc/GrpcBlockHoundIntegration.java`). Worker threads, virtual or not, are
  outside that predicate.

---

## Constraints the application executor must respect

1. **Type of the `blockingTaskExecutor` slot.** Whatever is passed to
   `ServerBuilder.blockingTaskExecutor(...)` must be a `ScheduledExecutorService` or
   `BlockingTaskExecutor` (`server/ServerBuilder.java:1087, 1101`). A bare
   `newVirtualThreadPerTaskExecutor()` cannot be passed. Because nothing in evitaDB's Armeria
   configuration dispatches to that slot (3.2), the simplest option is to leave `Scheduler` there and
   migrate only `requestExecutor` / `transactionExecutor`.
2. **Never block the event loop; keep the handoff.** Service methods start on the loop for HTTP
   (`HttpServerHandler.java:539-556`) and gRPC (`FramedGrpcService.java:307-313`,
   `AbstractServerCall.java:402-417`). The application executor must remain the place where blocking
   work happens; a virtual-thread executor satisfies this exactly as the current pools do.
3. **Producer calls need no thread affinity.** `HttpResponseWriter.write/close`,
   `WebSocketWriter.tryWrite`, `StreamObserver.onNext/onCompleted/onError`, `ctx.cancel()`,
   `ctx.setRequestTimeout(...)` may be called from any thread; Armeria hops to the loop itself
   (`DefaultStreamMessage.java:343-347`, `AbstractServerCall.java:241-249, 479-486`,
   `UnaryServerCall.java:121-128`, `StreamingServerCall.java:111-116, 132-140`,
   `DefaultCancellationScheduler.java:167-191`). Each call costs one `eventLoop.execute`; batching
   (as `GrpcOutboundGate` already does per message) keeps that bounded.
4. **`inEventLoop()` is a reference comparison against the loop's platform thread**
   (`SingleThreadEventExecutor.java:722-723`). A virtual thread is never "in" a loop; code that
   branches on `inEventLoop()` (`GrpcOutboundGate.java:286-300`) keeps its current behaviour.
5. **The Armeria context is absent on the worker unless pushed.** `RequestContext.currentOrNull()`
   is `null` and `current()` throws on `Evita-*` threads (2.6). Any code that needs the context on a
   worker must capture it on the service-method thread (as `executeWithClientContext` and
   `GrpcOutboundGate` do) or run the task via `ctx.makeContextAware(...)` /
   `ctx.blockingTaskExecutor()` (2.4). Migrating to virtual threads does not change this.
6. **If the context is pushed on a worker, it must be popped on the same thread before the task
   ends**, in a try-with-resources; `pop` validates identity and throws
   (`ThreadLocalRequestContextStorage.java:44-53`), and a stale context on a pooled thread makes the
   next `push()` of a different root throw (`ServiceRequestContext.java:257-259`). One virtual thread
   per task removes the "next request" hazard but not the throw within the task.
7. **Pushing the context on a virtual thread allocates Netty's `InternalThreadLocalMap`**
   (`Object[32]` plus the map, `InternalThreadLocalMap.java:54, 162-170`) once per thread, i.e. once
   per request, and it is never explicitly removed (`:136-147` are unreachable from Armeria on
   non-Netty threads). Either accept the per-request allocation or avoid pushing on workers. Wrapping
   worker tasks in `FastThreadLocalThread.runWithFastThreadLocal` (`FastThreadLocalThread.java:163-182`)
   would clean up at task end but also enables allocator thread caches per virtual thread (6.3), which
   is undesirable for per-task threads.
8. **A `ScopedValue` cannot implement `RequestContextStorage`** (2.7): the push/pop protocol is
   imperative and cross-frame, pop is issued from Netty listeners
   (`RequestContextUtil.java:167-176`), and the storage is a JVM-global chosen at class-init
   (`RequestContextUtil.java:70-82`). ScopedValues for evitaDB's own request state must be bound by
   evitaDB's task wrapper (`ObservableThreadExecutor.java:996-1016` is the natural site); Armeria's
   `contextHook` (`ServerBuilder.java:2389-2400`) can only mirror push/pop into a `ThreadLocal`/MDC.
9. **`whenRequestCancelling` continuations run on the event loop (timeouts, peer resets) or on the
   thread that calls `cancel()` / an already-expired `setRequestTimeout(...)`**
   (`DefaultCancellationScheduler.java:353-379, 188-190`). They must stay non-blocking; `task.cancel()`
   (`CancellationSupport.java:85-129`) is. `setRequestTimeout` may be called from any thread.
10. **Body aggregation completes on the event loop** (`HttpRequest.java:564-565`,
    `StreamingDecodedHttpRequest.java:141-143`), so the non-async continuation in
    `EndpointHandler.readRequestBody` (`EndpointHandler.java:249-269`) executes there. Unchanged by
    the executor migration, but any redesign of the handoff should not assume that continuation runs
    on the worker.
11. **Keep buffers heap-backed on the worker.** Do not enable `usePooledObjects` aggregation,
    `unsafeWrapRequestBuffers`, or `HttpData.wrap(ByteBuf)` from worker threads; today every pooled
    buffer is allocated and released on the loop (4.1, 6.4), and virtual threads get no allocator
    cache (`PooledByteBufAllocator.java:531-550`, `AdaptivePoolingAllocator.java:208-215, 248-252`).
12. **gRPC `io.grpc.Context` is also thread-local** and is already re-entered manually
    (`EvitaSessionService.java:198, 203`); the same wrapper must keep doing that on virtual threads.
13. **Graceful shutdown cannot see pending blocking tasks** unless the server executor is a
    `ThreadPoolExecutor` (`GracefulShutdownSupport.java:150-158`) — already the case with `Scheduler`;
    a virtual-thread executor does not regress it, but does not improve it either. Draining
    evitaDB's own pools remains evitaDB's responsibility.
14. **Leak-detection bookkeeping is per `Thread` object** (`RequestContextUtil.java:63-64`,
    weak-keyed): with per-task virtual threads a context-misuse warning would be logged once per
    request rather than once per pool thread. Harmless unless a bug exists; then it is loud.
