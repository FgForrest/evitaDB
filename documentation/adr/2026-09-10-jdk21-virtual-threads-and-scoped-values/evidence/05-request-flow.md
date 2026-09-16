# 05 — Request-processing flow per external API

Read-only trace of how a request travels from the Armeria/Netty event loop into evitaDB's application
executors and back, for every external API. Every step is anchored to `file:line` in the current worktree
(`1518-upgrade-to-jdk-21`, Armeria 1.41.1 per `pom.xml:142`). Armeria behaviour is cited from the sources
jars in the local Maven repository (`armeria-1.41.1-sources.jar`, `armeria-grpc-1.41.1-sources.jar`).

Thread vocabulary used below:

- **EL** — Armeria/Netty event loop. Built by `EventLoopGroups.builder().numThreads(...)` at
  `ExternalApiServer.java:539-554`; thread count = `ApiOptions.workerGroupThreadsAsInt()` =
  `availableProcessors()` by default (`ApiOptions.java:86,154-157`). One group serves both I/O and services
  (`ExternalApiServer.java:616,618`; rationale comment at `536-538`).
- **REQ** — `Evita-request-N`, platform daemon thread with configured priority
  (`ObservableThreadExecutor.java:1146-1150`), pool `"request"` (`Evita.java:459-463`).
- **TX** — `Evita-transaction-N`, same factory, pool `"transaction"` (`Evita.java:464-471`); never the
  direct executor, even in tests, because transaction handling uses thread-locals (`467-469`).
- **SVC** — `Evita-service-N`, a `ScheduledThreadPoolExecutor` fixed at `maxThreadCount` with an unbounded
  delay queue (`Scheduler.java:179-189`, factory `1030-1040`).
- **FJP** — a private `ForkJoinPool(parallelism = number of APIs)` (`ObservabilityProbesDetector.java:261-266`).
- **CP** — `ForkJoinPool.commonPool()`; used only by `AbstractChangeCaptureSubscriber.java:259`.

Pool sizing. Code defaults live in `ThreadPoolOptions.java:85-96`: request min = CPUs, max = CPUs×4,
priority 8, queue 100; transaction the same with priority 5; service min = CPUs, max = CPUs×2, priority 1,
queue 20. The shipped YAML overrides all three pools to `4 / 16 / priority 5 / queue 100`
(`evita-configuration.yaml:4-18`). Pool construction is `ObservableThreadExecutor.java:201-224`:
`core = max(1, min)`, `max = max(core, max)`, threads-first `TaskQueue`, `allowCoreThreadTimeOut(true)`,
60 s keep-alive. Admission = `maxThreadCount` running + `queueSize` waiting; overflow goes to
`GrowAwareRejectionHandler` (`1249-1276`) and then `EvitaRejectingExecutorHandler.handleRejection` (`71-76`):
JFR `BackgroundTaskRejectedEvent`, `log.error`, `RejectedExecutionException("Evita executor queue full...")`.
`UnrejectableTask`s are force-enqueued past the limit (`1264-1268`).

Who else holds the pools: the request executor is the CDC dispatch executor of `SystemChangeObserver`
(`Evita.java:557-562`) and of every `CatalogChangeObserver` (`TransactionManager.java:420-425`, constructed
from `Catalog.java:755,860`). The transaction executor drives `EngineTransactionManager` (`Evita.java:565`)
and every catalog's commit pipeline (`Catalog.java:698,793` → `TransactionManager.java:1997-2017`). The
service executor is Armeria's `blockingTaskExecutor` (`ExternalApiServer.java:571`).

## Executive summary

- **Every API hands off to `requestExecutor` from the event loop; no request work uses Armeria's blocking
  executor.** REST/GraphQL go through `CancellationSupport.submitWithCancellation` /
  `submitAsyncWithCancellation` (`CancellationSupport.java:178-193, 228-251`); gRPC through
  `EvitaSessionService.executeWithClientContext` (`EvitaSessionService.java:187-221`). `GrpcService` is built
  without `useBlockingTaskExecutor` (`GrpcProviderRegistrar.java:108-127`), so service methods run on the EL
  (`FramedGrpcService.java:297-301`); the gRPC server module contains no `@Blocking` annotation.
- **REST does real work on the event loop before the handoff.** `HttpRequest.aggregate()` completes on the
  request's event loop (`HttpRequest.java:564-566` → `StreamMessage.java:676-681`), so JSON parsing, constraint
  deserialization and `Query` reconstruction (`QueryOrientedEntitiesHandler.java:119-150`) plus the session
  creation in `beforeRequestHandled` (`RestEndpointHandler.java:124-127,162-175`) all run on the EL. GraphQL
  parses only the JSON envelope on the EL and runs graphql-java on REQ. gRPC parses EvitaQL on REQ, after the
  handoff (`EvitaSessionService.java:1205-1233`).
- **REST and GraphQL mutations run and wait for commit on the transaction pool; gRPC mutations run on the
  request pool.** All 43 `executeWithClientContext` call sites in `EvitaSessionService` pass
  `evita.getRequestExecutor()` (upserts, deletes, `close`, `goLiveAndClose` included); only the unary restore
  at `EvitaManagementService.java:843` uses the transaction executor.
- **The only blocking waits on evitaDB worker threads are session close (commit or warm-up flush joins),
  engine-level DDL joins, the gRPC outbound gate park and the readiness probe's 10 s `allOf().get`.** Query
  execution is CPU-bound and never parks. gRPC `close` is fully asynchronous
  (`EvitaSessionService.java:1058-1090`); REST/GraphQL `close` is a `CompletableFuture.join()`
  (`EvitaSessionContract.java:346-361`).
- **A structural nested wait exists on the transaction pool.** A REST/GraphQL mutation holds a TX thread while
  joining `WAIT_FOR_CHANGES_VISIBLE`, and the pipeline stages that satisfy that join are tasks on the same
  bounded TX pool (`TransactionManager.java:1997-2017`, `ProgressingFuture.unrejectableExecutor`). The commit
  completion callback is additionally dispatched through the request pool (`CommitProgressRecord.java:210-224`,
  inline fallback on rejection).
- **GraphQL has nested submission but no nested join.** The REQ thread running `graphQL.executeAsync` submits
  every root field to REQ (reads) or TX (writes) through `AsyncDataFetcher` (`AsyncDataFetcher.java:78-131`)
  and returns; composition is pure `CompletableFuture`. Session close and Jackson serialization run on the
  thread that completed the last root field.
- **Backpressure today is the pool bound.** Rejection surfaces as HTTP 500 for REST/GraphQL/System
  (`JsonApiExceptionHandler.java:73`, `ExternalApiExceptionHandler.java:96-115`,
  `LoggingServerErrorHandler.java:62-63`) and gRPC `INTERNAL` (`GlobalExceptionHandlerInterceptor.java:134-139`).
  There is no 429/503/`RESOURCE_EXHAUSTED` for overload, no `maxNumConnections`, and no per-catalog or
  per-session admission control beyond the warm-up single-session rule.
- **`server.queryTimeoutInMilliseconds` (5000) is not enforced anywhere.** It is only reported by
  `EvitaStatisticsEvent.java:220`. The effective query deadline is Armeria's `requestTimeoutMillis` (YAML
  2000 ms, code default 1000 ms), which cancels the task via `Thread.interrupt()`; woven `InterruptionAdvice`
  checkpoints (`InterruptionTransformer.java:101-105`) turn that into `InterruptedException`.
  `CompletableFuture.join()` waits ignore the interrupt.
- **CDC deliveries for GraphQL/REST WebSocket subscriptions and gRPC capture streams are pushed from the
  request pool** (`DefaultChangeCaptureSubscription.java:182` submits `consumeQueue` to `cdcExecutor` =
  request executor) and delivered onto the EL (`GraphQLWSSubProtocol.java:286`, `RestWSSubProtocol.java:222`)
  or straight into gRPC `onNext` (`AbstractChangeCaptureSubscriber.java:266-278`). Heartbeats run on SVC.
  Streams are long-lived; each capture batch is one request-pool task.
- **Thread identity is used; thread names and priorities are not parsed.** The read-write session guard
  compares `Thread` references (`EvitaSessionProxy.java:634-649`); `CollationKeyCache.java:263` stripes on
  `Thread.currentThread().threadId()`; `threadPriority` is applied with `setPriority`
  (`ObservableThreadExecutor.java:1150`, `Scheduler.java:1038-1039`); pool metrics come from
  `ThreadPoolExecutor` counters.

## Per-API flow diagrams

### 1. REST (`evita_external_api_rest`)

```
EL  RestEndpointHandler.serve                          RestEndpointHandler.java:86-88
EL    instrumentRequest: tracing block "REST"          RestEndpointHandler.java:254-263
EL    EndpointHandler.serve                            EndpointHandler.java:83-140
EL      createExecutionContext (+ExecutedEvent)        RestEndpointHandler.java:92-121
EL      validateRequest, content negotiation           EndpointHandler.java:84-88, 209-213, 293-352
EL      beforeRequestHandled -> createSession          RestEndpointHandler.java:124-127, 162-175
EL        Evita.createSessionInternal                  Evita.java:1927-1975
EL          SessionRegistry.addSession                 SessionRegistry.java:629-648
            (exclusiveAdmissionLock when the catalog is non-transactional)
EL          registerWhileNotSuspended                  SessionRegistry.java:949-965
            (read lock; POSTPONE suspension -> awaitFinish 500 ms -> SessionBusyException)
EL      HttpResponse.streaming()                       EndpointHandler.java:97
EL      doHandleRequest (QueryEntitiesHandler)         QueryEntitiesHandler.java:72-92
EL        resolveQuery -> readRawRequestBody           QueryOrientedEntitiesHandler.java:119-150
                                                       EndpointHandler.java:214-254
EL          httpRequest().aggregate()                  Armeria HttpRequest.java:564-566
            -> defaultSubscriberExecutor() = ctx.eventLoop   StreamMessage.java:676-681
EL          .thenApply: Jackson parse                  JsonRestHandler.java:67-77
EL          .thenApply: constraint deserialization,
            Query reconstruction, head enrichment      QueryOrientedEntitiesHandler.java:132-150
EL        .thenCompose -> executeAsyncInRequestThreadPool
                                                       QueryEntitiesHandler.java:77
                                                       EndpointExecutionContext.java:137-139
EL          CancellationSupport.submitWithCancellation CancellationSupport.java:178-193
EL            createTask; wireCancellation             CancellationSupport.java:98-121
              (ctx.whenRequestCancelling -> task.cancel -> Thread.interrupt)
EL            executor.execute(task)                   ObservableThreadExecutor.java:293-309
              <-- RejectedExecutionException is thrown HERE, on the EL, when saturated
REQ   task body: session().query(query)                QueryEntitiesHandler.java:79-85
      (EvitaSessionProxy.invoke -> EvitaSession.query; CPU-bound)
REQ     convertResultIntoSerializableObject (DTOs)     QueryEntitiesHandler.java:89, 96-110
REQ     result.complete(...)                           CancellationSupport.java:185
REQ   .thenAccept (runs on the completing thread)      EndpointHandler.java:101-131
REQ     afterRequestHandled -> closeSessionIfOpen      RestEndpointHandler.java:130-134
                                                       RestEndpointExecutionContext.java:165-167
REQ       session.close() -> closeNow(..).join()       EvitaSessionContract.java:346-361
          (read-only: completes immediately, EvitaSession.java:1953-1963)
REQ     responseWriter.write(headers)                  EndpointHandler.java:111-118
REQ     writeResponse(ctx, writer, result, ctx.eventLoop())
                                                       JsonRestHandler.java:81-89
        (Jackson writeValueAsBytes -> responseWriter.write(HttpData); the EventLoop argument is unused)
REQ   .whenComplete -> responseWriter.close(); executionContext.close()
                                                       EndpointHandler.java:132-144
                                                       RestEndpointExecutionContext.java:207-211
EL    Armeria flushes the HttpResponseWriter frames to the socket
```

Mutation variant (`UpsertEntityHandler.java:101-140`, `DeleteEntitiesByQueryHandler.java:70-100`, ...) is
identical up to the handoff, but uses `executeAsyncInTransactionThreadPool`
(`EndpointExecutionContext.java:148-150`), so the mutation, the blocking commit wait in `session.close()` and
the JSON serialization all run on **TX**. Mutation body parsing and `EntityMutation` reconstruction
(`UpsertEntityHandler.java:104-131`) also happen on the EL.

Why an `EventLoop` is passed to `writeResponse`: the abstract signature (`EndpointHandler.java:266-275`)
carries `ctx.eventLoop()` for handlers that want to schedule streaming writes. Both concrete implementations
(`JsonRestHandler.java:81-89`, `GraphQLWebHandler.java:216-229`) ignore it and write synchronously from the
worker thread through Armeria's thread-safe `HttpResponseWriter`. The only EL work after the handoff is
Armeria's own frame writing.

REST endpoints by executor (from `rg executeAsyncIn(Transaction|Request)ThreadPool`):

- Transaction executor (8): `UpsertEntityHandler.java:134`, `DeleteEntityHandler.java:68`,
  `DeleteEntitiesByQueryHandler.java:79`, `UpdateEntitySchemaHandler.java:94`,
  `UpdateCatalogSchemaHandler.java:90`, `CreateCatalogHandler.java:62`, `UpdateCatalogHandler.java:72`,
  `DeleteCatalogHandler.java:68`.
- Request executor (13): `QueryEntitiesHandler.java:77`, `ListEntitiesHandler.java:61`,
  `ListUnknownEntitiesHandler.java:78`, `GetEntityHandler.java:70`, `GetUnknownEntityHandler.java:70`,
  `CollectionsHandler.java:57`, `GetEntitySchemaHandler.java:56`, `GetCatalogSchemaHandler.java:54`,
  `ListCatalogsHandler.java:61`, `GetCatalogHandler.java:55`, `LivenessHandler.java:58`,
  `OpenApiSpecificationHandler.java:61`.

The Lab module's REST endpoints reuse `RestEndpointHandler` with `RestInstanceType.LAB`
(`RestEndpointHandler.java:99-102`), so they follow this same flow.

### 2. GraphQL HTTP (`evita_external_api_graphql`)

```
EL  GraphQLWebHandler.serve -> instrumentRequest       GraphQLWebHandler.java:115-117, 231-239
EL    EndpointHandler.serve (no session in beforeRequestHandled)
                                                       EndpointHandler.java:83-140
EL    doHandleRequest -> parseRequestBody              GraphQLWebHandler.java:152-166, 194-214
EL      aggregate().thenApply(Jackson -> GraphQLRequest)   (EL, same reason as REST)
EL      .thenCompose -> executeAsyncSupplierInRequestThreadPool
                                                       EndpointExecutionContext.java:164-170
EL        CancellationSupport.submitAsyncWithCancellation -> executor.execute
                                                       CancellationSupport.java:228-251
          <-- rejection thrown on the EL
REQ   task body: executeWithinBlockAsync -> executeRequestAsync
                                                       GraphQLWebHandler.java:160-165, 245-268
REQ     graphQL.executeAsync(executionInput)
        (GraphQL built with no executor: CatalogGraphQLBuilder.java:60-73)
REQ       graphql-java parse, validate, instrumentation chain
REQ       EvitaSessionManagingInstrumentation.beginExecuteOperation
          -> createReadOnly/ReadWriteSession           EvitaSessionManagingInstrumentation.java:66-82
          (SessionRegistry locks as in REST, but on REQ)
REQ       AsyncExecutionStrategy invokes every root DataFetcher on THIS thread:
REQ         AsyncDataFetcher.get                       AsyncDataFetcher.java:78-131
REQ           parallelize=false -> delegate.get() inline, mutations included
                                                       AsyncDataFetcher.java:80-86
              (YAML default true: evita-configuration.yaml:132; GraphQLOptions.java:59,65,81)
REQ           parallelize=true -> executor.createTask; wireCancellation; executor.execute(task);
              return CompletableFuture                 AsyncDataFetcher.java:88-130
              executor = requestExecutor for ReadDataFetcher,
                         transactionExecutor for WriteDataFetcher
                                                       AsyncDataFetcher.java:145-156
              (rejection: RejectedExecutionException escapes DataFetcher.get
               -> graphql-java DataFetcherExceptionHandler -> GraphQL error entry)
REQ       executeAsync returns; the task ends; REQ thread released
          (task completes before the result: CancellationSupport.java:238-247)
REQ/TX  each root-field task: delegate.get(environment) inside a tracing block;
        child fields are resolved on the completing thread
REQ/TX  the thread completing the LAST root field completes the ExecutionResult, and on that thread:
          instrumentExecutionResult -> evitaSession.close()
                                                       EvitaSessionManagingInstrumentation.java:86-100
          (mutation: BLOCKING join until WAIT_FOR_CHANGES_VISIBLE, on TX)
          .handle -> GraphQLResponse.fromExecutionResult
                                                       GraphQLWebHandler.java:250-266
          result.complete -> EndpointHandler .thenAccept -> writeResponse (Jackson)
                                                       GraphQLWebHandler.java:216-229
          .whenComplete -> responseWriter.close()
EL    Armeria writes frames
```

Nested submission: yes. A REQ task submits child tasks into the same bounded REQ pool (and into TX). Nested
join: no. The submitting task returns immediately and every downstream step is a `CompletableFuture`
continuation on the completing thread. `rg` for `.join()` / `.get()` in the GraphQL main sources finds no
blocking wait on the request path (only `Optional` and atomic getters). The one join in the flow is the
session close inside `instrumentExecutionResult`, which runs on the last completing root-field thread.

### 2b. GraphQL WebSocket and subscriptions (`io/webSocket`)

```
EL  GraphQLWebSocketHandler.handle
    -> in.incomingWebSocket().subscribe(subscriber)    GraphQLWebSocketHandler.java:82-91
    (no executor given -> ctx.eventLoop, StreamMessage.java:676-681)
EL  GraphQLWebSocketSubscriber.onNext -> GraphQLWSSubProtocol.handleEvent
                                                       GraphQLWebSocketSubscriber.java:62
EL    "subscribe": build ExecutionInput; graphQL.executeAsync(executionInput)
                                                       GraphQLWSSubProtocol.java:145-169
      <-- graphql-java parse/validate on the EL
EL      EvitaSessionManagingInstrumentation.beginExecuteOperation
        -> createReadOnlySession (SUBSCRIPTION)        EvitaSessionManagingInstrumentation.java:66-76
EL      subscription root fetcher: ChangeCaptureSubscribingDataFetcher.get
        (NOT wrapped in AsyncDataFetcher)              ChangeCaptureSubscribingDataFetcher.java:50-52
                                                       SystemGraphQLSchemaBuilder.java:768,783
EL        OnCatalogChangeCaptureSubscribingDataFetcher.createPublisher -> evita.queryCatalog(...)
                                                       OnCatalogChangeCaptureSubscribingDataFetcher.java:68-94
EL          second session create -> session.registerChangeCatalogCapture -> session close (all on EL)
EL    future.handleAsync(handleExecutionResult, ctx.eventLoop())
                                                       GraphQLWSSubProtocol.java:168-175
EL      StreamMessage.of(publisher).subscribe(executionResultSubscriber, ctx.eventLoop())
                                                       GraphQLWSSubProtocol.java:246-286
REQ publisher side: DefaultChangeCaptureSubscription.consumeQueue submitted to cdcExecutor
                                                       DefaultChangeCaptureSubscription.java:182, 300-325
    cdcExecutor = requestExecutor                      Evita.java:557-562 (system)
                                                       TransactionManager.java:420-425 (catalog)
EL  ExecutionResultSubscriber.onNext -> writeNext (Jackson) -> WebSocketWriter
                                                       ExecutionResultSubscriber.java:63
                                                       GraphQLWSSubProtocol.java:250-270
```

Lifetime: as long as the socket is open; each delivered capture batch is one REQ task; the EL does the JSON
serialization of every pushed event.

### 3. gRPC (`evita_external_api_grpc/server`)

Service wiring is `GrpcProviderRegistrar.java:108-127`: four services, interceptors in the order
`ServerSessionInterceptor`, `GlobalExceptionHandlerInterceptor`, `ObservabilityInterceptor`;
`supportedSerializationFormats(all)` (gRPC, gRPC-Web, text variants); `enableHttpJsonTranscoding(true)`;
`enableUnframedRequests(true)`; `useClientTimeoutHeader(true)`; a CORS wrapper for gRPC-Web (`129-141`). No
`useBlockingTaskExecutor`, so `FramedGrpcService.java:297-301` runs the listeners on the EL. HTTP/JSON
transcoding and gRPC-Web decoding are Armeria work on the EL ahead of the same path.

```
EL  ServerSessionInterceptor.interceptCall             ServerSessionInterceptor.java:145-178
EL    metadata parse; evita.getSessionById(uuid)       ServerSessionInterceptor.java:159-161, 193-207
      (Evita.java:760; SessionRegistry activeSessions is a ConcurrentHashMap, 281)
EL    missing or inactive session -> UNAUTHENTICATED, stub listener
                                                       ServerSessionInterceptor.java:162-171
EL    Context.withValue(METADATA, SESSION)             ServerSessionInterceptor.java:173-178
EL  GlobalExceptionHandlerInterceptor.ExceptionHandler and ObservabilityListener wrap the listener
EL  onMessage (protobuf already decoded by Armeria) -> onHalfClose -> service method
                                                       GlobalExceptionHandlerInterceptor.java:175-197
                                                       ObservabilityInterceptor.java:163-190
EL    queryOne -> executeWithClientContext             EvitaSessionService.java:1205-1233, 187-221
EL      ServiceRequestContext.current(); METADATA.get(); SESSION.get(); Context.current()
EL      executor.createTask(methodName, ...); CancellationSupport.wireCancellation(ctx, task)
EL      executor.execute(task)   <-- rejection thrown on the EL, inside onHalfClose
REQ   grpcContext.run -> tracing block -> lambda(session)
REQ     QueryUtil.parseQuery(...)  (EvitaQL parsing AFTER the handoff)
                                                       EvitaSessionService.java:1209, 1246, 1282,
                                                       1319, 1357, 1394, 1951
REQ     session.queryOne(...); EntityConverter.toGrpc...
                                                       EvitaSessionService.java:231-272
REQ     responseObserver.onNext(...); onCompleted()    (gRPC-Java marshals, Armeria queues outbound)
REQ     RuntimeException -> GlobalExceptionHandlerInterceptor.sendErrorToClient
                                                       EvitaSessionService.java:210-213
                                                       GlobalExceptionHandlerInterceptor.java:69-74
EL    Armeria writes frames and trailers
```

Everything before `executor.execute` is non-blocking map and metadata work. The only EL-side failure path
is the `RejectedExecutionException`, thrown synchronously and mapped to `INTERNAL` by
`GlobalExceptionHandlerInterceptor.onHalfClose` through the `createErrorStatus` fall-through (`134-139`).

Variants:

- **Session close and goLive** (`EvitaSessionService.java:1058-1090`, `729-760`, `790-840`): on REQ the
  method calls `session.closeNowWithProgress()` or `goLiveAndCloseWithProgress()` and attaches
  `whenComplete`. No join. The response is emitted from whichever thread completes the chosen
  `CommitBehavior` stage (see section 8).
- **Server streaming with back-pressure** (`GrpcOutboundGate`): `fetchFile`
  (`EvitaManagementService.java:1052-1055`), the mutation-history streaming helper at
  `EvitaSessionService.java:2477` (used by `getMutationsHistory` `2541` and `getMutationsHistoryForward`
  `2559`), and `getTrafficRecordingHistory` (`EvitaTrafficRecordingService.java:169`). The gate must be
  attached on the EL before the service method returns (`GrpcOutboundGate.java:66-73`). The producing loop
  runs on REQ and parks in `awaitWritable` on a `ReentrantLock` / `Condition` (`149-150`, `273`) between
  messages, holding the REQ thread for the whole stream. The park is bounded by the stall timeout (default
  300 s, `106`; configured by `api.endpoints.gRPC.streamingRequestTimeoutInMillis`) and the Armeria deadline
  is re-armed per message (`121-140`). A direct executor is detected and the gate disables itself (`85-90`).
- **Client streaming (restore upload)** (`EvitaManagementService.java:722-733`): chunks go to an
  `uploadExecutor` = `requestExecutor` (`731`) with `disableAutoRequest()` / `request(1)` flow control;
  rejection fails the upload explicitly (`1373-1397`). Oversized bodies map to `RESOURCE_EXHAUSTED`
  (`GrpcProviderRegistrar.java:72-84`).
- **CDC capture streams** (`registerChangeCatalogCapture` `EvitaSessionService.java:2611-2648`,
  `registerSystemChangeCapture` `EvitaService.java:618-650`): the subscriber is constructed on the EL because
  transport handlers must be bound before the method returns; its heartbeat `DelayedAsyncTask` is scheduled on
  **SVC** (`AbstractChangeCaptureSubscriber.java:159-183`, `399-422`); the subscription itself is made on REQ
  via `executeWithClientContext`; every `onNext` runs on the CDC publisher thread, which is **REQ**
  (`DefaultChangeCaptureSubscription.java:182`, `AbstractChangeCaptureSubscriber.java:266-278`), writes to the
  gRPC observer and re-arms the request deadline. One failure path uses `CompletableFuture.runAsync` on the
  common pool (`259`).
- **Executor choice**: all 43 `executeWithClientContext` call sites in `EvitaSessionService` use
  `getRequestExecutor()`, so gRPC upserts, deletes, schema mutations and close run on **REQ**.
  `getServiceExecutor()` is passed only to the CDC subscribers for heartbeats (`EvitaSessionService.java:2630`,
  `EvitaService.java:629`); `getTransactionExecutor()` only to `restoreCatalogUnary`
  (`EvitaManagementService.java:843`).

### 4. REST WebSocket, Lab, System, Observability

**REST WebSocket (CDC over WS).** `RestWebSocketHandler.handle` (`78-79`) subscribes without an executor, so
frames arrive on the EL. `RestWSSubProtocol.handleEvent` "subscribe" (`110-124`) calls
`restWebSocketExecutor.subscribe(payload)` on the EL; `ChangeCatalogCaptureStreamHandler.doSubscribe`
(`75-90`) runs `evita.queryCatalog(...)` (session create, `registerChangeCatalogCapture`, session close) on
the EL; `handleExecutionResult` (`173-222`) subscribes the Armeria `StreamMessage` on
`serviceContext.eventLoop()`. The publisher side is as in section 2b (REQ).
`ChangeSystemCaptureStreamHandler` (`81-90`) is the system-scope twin.

**Lab.** `GuiHandler.serve` (`176-191`) routes to
`HttpFile.builder(classLoader, resource).build().asService().serve(ctx, req)` (`136-146`, `226-237`).
Armeria's `AbstractHttpFile` reads attributes and content on `ctx.blockingTaskExecutor()`
(`AbstractHttpFile.java:259, 303`), which is **SVC** (`ExternalApiServer.java:571`). No evitaDB session and no
request-pool task. Lab's data endpoints are the REST handlers above.

**System API.** `SystemProviderRegistrar.java:299-322` returns `HttpResponse.of(future)` where the future is
`evita.executeAsyncInRequestThreadPool(...)`, a plain `CompletableFuture.supplyAsync(supplier,
requestExecutor)` (`Evita.java:1184-1186`) with no cancellation wiring and no unrejectable marker. A rejection
is thrown synchronously inside the service lambda on the EL and mapped by `LoggingServerErrorHandler`
(`53-63`) to 500. On REQ:

- readiness (`192-227`): `ObservabilityProbesDetector.getReadiness` (`158-227`) fans one
  `CompletableFuture.runAsync` per API onto a private `ForkJoinPool(parallelism = number of registered APIs)`
  (`261-266`) and blocks the REQ thread in `allOf(...).get(10, SECONDS)` (`191`). Each probe
  (`SystemProvider.isReady` `124-160` → `probe` `162-185` → `NetworkUtils.isReachable` `104-130`) performs a
  blocking OkHttp loopback GET with `requestTimeout` (`SystemProviderRegistrar.java:415`) against the server
  itself; the `/server-name` target is a constant response built on the EL (`289`). Results are cached and a
  `HEALTH_CHECK_RUNNING` CAS (`166`) prevents concurrent probes. The code carries no comment explaining the
  dedicated pool. Observable facts: it is sized to the API count, the probes are blocking socket I/O, and the
  detector's own health checks read the request executor's rejected/submitted counters (`checkInputQueues`
  `334-345`), so running the probes on that executor would perturb the metric they report.
- liveness (`229-260`): same handoff, runs `getHealthProblems` on REQ without fan-out;
  `INPUT_QUEUES_OVERLOADED` is raised when the rejected/submitted delta ratio exceeds 2, yielding 503.
- `ReadinessDiscoveryStallTracker` (`evita_external_api_core/.../ReadinessDiscoveryStallTracker.java:51-67`)
  is a timestamp helper with a 60 s grace period and no thread of its own.

**Observability.** `PrometheusMetricsHttpService.serve` (`56-71`) uses
`CancellationSupport.submitWithCancellation` on REQ; the scrape is rendered into a byte array on REQ and
returned via `HttpResponse.of(future)`. JFR endpoints extend `EndpointHandler`
(`JfrRecordingEndpointHandler.java:53`) and submit to REQ (`StartJfrRecordingHandler.java:55`,
`StopJfrRecordingHandler.java:51`, `CheckJfrRecordingHandler.java:52`,
`GetJfrRecordingEventTypesHandler.java:51`).

## Handoff table

One entry per API surface. Fields: handoff site; executor; parses on EL; blocks on EL; nested submission;
response completion thread; backpressure on reject.

- **REST read**
  - Handoff: `EndpointExecutionContext.java:137-139` via `CancellationSupport.java:178-193`, invoked from
    the `aggregate()` continuation on the EL.
  - Executor: REQ.
  - Parses on EL: yes. JSON body, constraint deserialization, `Query` build
    (`QueryOrientedEntitiesHandler.java:119-150`); session creation (`RestEndpointHandler.java:124-127`).
  - Blocks on EL: session-registry locks; read lock, up to 500 ms during a POSTPONE suspension
    (`SessionRegistry.java:949-965`).
  - Nested submission: no.
  - Response completion: REQ (`EndpointHandler.java:101-131`).
  - Reject: `RejectedExecutionException` inside the composed future → 500 via
    `JsonApiExceptionHandler.java:73` / `ExternalApiExceptionHandler.java:96-115`, logged at error.
- **REST mutation**
  - Handoff: `EndpointExecutionContext.java:148-150` (the 8 handlers listed above).
  - Executor: TX.
  - Parses on EL: yes, plus `EntityMutation` reconstruction (`UpsertEntityHandler.java:104-131`).
  - Blocks on EL: same registry locks.
  - Nested submission: no.
  - Response completion: TX, after `session.close().join()` (`RestEndpointHandler.java:130-134`).
  - Reject: same, 500.
- **GraphQL HTTP**
  - Handoff: `EndpointExecutionContext.java:164-170` via `CancellationSupport.java:228-251`.
  - Executor: REQ for the outer task; root fields on REQ (reads) or TX (writes)
    (`AsyncDataFetcher.java:145-156`).
  - Parses on EL: JSON envelope only (`GraphQLWebHandler.java:194-214`); graphql-java parse on REQ.
  - Blocks on EL: no; no session is created on the EL.
  - Nested submission: yes, submission only, no join (`AsyncDataFetcher.java:88-130`).
  - Response completion: the thread completing the last root field (REQ or TX); session close there.
  - Reject: outer rejection → 500 (same handlers); root-field rejection → GraphQL error entry, HTTP 200.
- **GraphQL WebSocket**
  - Handoff: none; `graphQL.executeAsync` runs on the EL (`GraphQLWSSubProtocol.java:168-169`).
  - Executor: EL for setup; pushes from REQ (`DefaultChangeCaptureSubscription.java:182`).
  - Parses on EL: yes, the whole graphql-java parse/validate plus two session lifecycles.
  - Blocks on EL: yes; session create/close and `registerChangeCatalogCapture` on the EL.
  - Nested submission: not applicable.
  - Response completion: EL (`subscribe(..., ctx.eventLoop())`, `286`).
  - Reject: none at subscribe time; a rejected `consumeQueue` submission is handled publisher-side
    (`ChangeSystemCaptureSharedPublisher.java:323-347` falls back to synchronous delivery for host events).
- **gRPC unary**
  - Handoff: `EvitaSessionService.java:187-221` (`executeWithClientContext`).
  - Executor: REQ (all 43 sites).
  - Parses on EL: no. EvitaQL is parsed on REQ (`1209` and siblings); protobuf, transcoding and gRPC-Web
    decoding are Armeria work on the EL.
  - Blocks on EL: session lookup only (map).
  - Nested submission: no.
  - Response completion: REQ (`onNext` / `onCompleted` from the task).
  - Reject: `RejectedExecutionException` thrown in `onHalfClose` → `INTERNAL`
    (`GlobalExceptionHandlerInterceptor.java:175-197`, `134-139`).
- **gRPC gated server streaming**
  - Handoff: same; gate attached on the EL (`GrpcOutboundGate.java:66-73`).
  - Executor: REQ, thread held for the stream (park in `awaitWritable`, `273`).
  - Parses on EL: no. Blocks on EL: no. Nested submission: no.
  - Response completion: REQ.
  - Reject: same `INTERNAL`; a stalled consumer → `DEADLINE_EXCEEDED`
    (`GlobalExceptionHandlerInterceptor.java:86-95`).
- **gRPC CDC**
  - Handoff: `EvitaSessionService.java:2611-2648`, `EvitaService.java:618-650`.
  - Executor: REQ to subscribe, REQ per delivery, SVC heartbeat.
  - Parses on EL: no. Blocks on EL: no (the subscriber object is built on the EL).
  - Nested submission: yes; deliveries are REQ tasks submitted by the publisher.
  - Response completion: REQ (`AbstractChangeCaptureSubscriber.java:266-278`).
  - Reject: subscribe rejection → `INTERNAL`; delivery rejection is publisher-side.
- **gRPC client streaming (restore)**
  - Handoff: `EvitaManagementService.java:722-733` (`uploadExecutor` = REQ).
  - Executor: REQ; the unary restore uses TX (`843`).
  - Parses on EL: no. Blocks on EL: no. Nested submission: no.
  - Response completion: REQ.
  - Reject: explicit `failUpload` (`1373-1397`); oversized → `RESOURCE_EXHAUSTED`.
- **REST WebSocket (CDC)**
  - Handoff: none; `restWebSocketExecutor.subscribe` on the EL (`RestWSSubProtocol.java:124`).
  - Executor: EL setup; REQ pushes.
  - Parses on EL: yes (JSON payload).
  - Blocks on EL: yes; `evita.queryCatalog` session lifecycle on the EL
    (`ChangeCatalogCaptureStreamHandler.java:75-90`).
  - Nested submission: not applicable. Response completion: EL (`222`). Reject: none.
- **Lab GUI**
  - Handoff: `HttpFile.asService().serve` (`GuiHandler.java:136-146`).
  - Executor: SVC (Armeria blocking executor, `AbstractHttpFile.java:259,303`).
  - Parses on EL: not applicable. Blocks on EL: no. Nested submission: no.
  - Response completion: EL.
  - Reject: not reachable; the Scheduler's delay queue is unbounded (`Scheduler.java:179-183`).
- **System readiness and liveness**
  - Handoff: `SystemProviderRegistrar.java:197,234` → `Evita.java:1184-1186` (`supplyAsync`).
  - Executor: REQ, plus FJP for the probes.
  - Parses on EL: no. Blocks on EL: no.
  - Nested submission: yes; FJP fan-out, and REQ blocks in `allOf().get(10 s)`
    (`ObservabilityProbesDetector.java:191`).
  - Response completion: REQ.
  - Reject: `RejectedExecutionException` thrown on the EL → 500 via `LoggingServerErrorHandler.java:62-63`.
- **Prometheus scrape**
  - Handoff: `PrometheusMetricsHttpService.java:56-71`. Executor: REQ. Parses on EL: no. Blocks on EL: no.
    Nested submission: no. Response completion: REQ. Reject: 500 via `LoggingServerErrorHandler`.
- **JFR endpoints**
  - Handoff: `EndpointHandler` plus `executeAsyncInRequestThreadPool`. Executor: REQ. Parses on EL: JSON
    settings. Blocks on EL: no. Nested submission: no. Response completion: REQ. Reject: 500.

## 5. Internal async API

- `Evita.queryCatalogAsync` (`908-921`) and `updateCatalogAsync` (`925-1000`) use
  `CompletableFuture.supplyAsync(..., requestExecutor)` or a session-per-call with
  `closeNow(commitBehaviour)`; `executeAsyncInRequestThreadPool` is `1184-1186`. Callers outside tests:
  `SystemProviderRegistrar.java:120,197,234` only; `WalReplayState.java:599` (performance harness) uses
  `updateCatalogAsync`. No engine-internal caller uses these.
- `Evita.java:557-562`: `requestExecutor` is the CDC dispatch executor of `SystemChangeObserver` (and via
  `TransactionManager.java:420-425` of every `CatalogChangeObserver`). `Evita.java:565`: `transactionExecutor`
  drives `EngineTransactionManager`. `Evita.java:1414-1447`: the storage-protocol auto-upgrade runs on **SVC**
  and joins `applyMutation(...).onCompletion()` (`1420-1427`), then re-executes the catalog load on the engine
  transaction executor (`1441-1443`).
- `CommitProgressRecord.enqueueCompletion` (`210-224`): every commit-stage completion is
  `thenRunAsync(..., executor)`, and the trunk stage passes `transactionManager.getRequestExecutor()`
  (`TrunkIncorporationTransactionStage.java:86-97, 130-142, 165-185`). Commit completions, hence REST/GraphQL
  mutation responses and gRPC `close` responses, queue behind request-pool traffic; on rejection the stage is
  completed inline.

## 6. Session-level concurrency

- `EvitaSession` is `@NotThreadSafe` (`EvitaSession.java:152-159`); `nestLevel` is deliberately non-atomic
  (`244-254`). There is no lock or queue inside the session.
- Serialisation is enforced by the proxy. `EvitaSessionProxy.invoke` (`634-649`) does
  `owningThread.compareAndExchange(null, Thread.currentThread())` for read-write sessions only
  (`concurrentAccessGuarded = traits.isReadWrite()`, `541`). A different thread entering while another owns
  the session gets `ConcurrentSessionAccessException`, whose message carries both thread names (`639-641`).
  The outermost invocation releases ownership in `finally` (`663-665`). Read-only sessions are unguarded by
  design: the GraphQL API opens one read-only session per operation and fans root fields across several REQ
  threads (`121-131`).
- A session in its closing sequence makes callers wait `awaitFinish(500 ms)` and then throw
  `SessionBusyException` (`650-660`). A forced close (catalog rename, replace, termination, `goLive`,
  shutdown) runs on the suspending thread when the session is idle and on the session's own thread when a
  method is in flight (`616-627`, `667-671`); see section 8b. `SessionKiller` on SVC closes sessions idle for
  `closeSessionsAfterSecondsOfInactivity` = 60 s (`Evita.java:473-476`, `evita-configuration.yaml:21`).
- Registry: one per catalog (`Evita.java:1932-1938`). `addSession` (`SessionRegistry.java:629-648`) takes
  `exclusiveAdmissionLock` for non-transactional (warm-up) catalogs and refuses a second session with
  `ConcurrentInitializationException`; transactional catalogs register under a read lock only
  (`registerWhileNotSuspended` `949-965`, 500 ms wait during a POSTPONE suspension). gRPC sessions are
  long-lived and shared by many RPCs; REST and GraphQL create one session per request.
- Consequence: one read-write gRPC session can be driven by one RPC at a time because of the thread-identity
  guard. The guard is per invocation, not per pool, so it limits concurrent calls regardless of thread count.

## 7. Backpressure and timeouts today

- **Request and transaction pools.** `maxThreadCount` running + `queueSize` queued (YAML 16 + 100 each),
  threads first; overflow → `RejectedExecutionException`, JFR `BackgroundTaskRejectedEvent`, `log.error`;
  `UnrejectableTask`s bypass the bound (`ObservableThreadExecutor.java:201-224, 1173-1276`;
  `EvitaRejectingExecutorHandler.java:71-76`).
- **Service pool.** `ScheduledThreadPoolExecutor(maxThreadCount)` with an unbounded `DelayedWorkQueue`; the
  task registry queue (`queueSize` × 2) is bounded separately (`Scheduler.java:179-197`).
- **Client-visible reject status.** REST/GraphQL/System/Prometheus → HTTP 500 as `ExternalApiInternalError`,
  logged at error; gRPC → `INTERNAL`; no 429, 503, `UNAVAILABLE` or `RESOURCE_EXHAUSTED` for overload
  (`JsonApiExceptionHandler.java:66-73`, `ExternalApiExceptionHandler.java:96-115`,
  `LoggingServerErrorHandler.java:53-63`, `GlobalExceptionHandlerInterceptor.java:77-140, 175-197`).
- **Health signal.** `INPUT_QUEUES_OVERLOADED` when the rejected/submitted delta ratio exceeds 2, yielding
  liveness 503 (`ObservabilityProbesDetector.java:334-345`; `SystemProviderRegistrar.java:229-260`).
- **Armeria per request.** `requestTimeoutMillis` = YAML 2000 ms, code default 1000 ms (`ApiOptions.java:88`,
  `evita-configuration.yaml:91`), re-armed by streaming; `maxRequestLength` 2 MiB (`ApiOptions.java:90`);
  `idleTimeoutMillis` 60 s with keep-alive-on-ping; `pingIntervalMillis` 0; the RST-flood defence is turned
  off in favour of monitoring; graceful shutdown 1 s. No `maxNumConnections` and no explicit HTTP/2
  max-concurrent-streams override, so Armeria defaults apply (`ExternalApiServer.java:571-620`).
- **Cancellation.** `ctx.whenRequestCancelling` → `task.cancel()` → `Thread.interrupt()` on the recorded
  executing thread; the interrupt flag is cleared in `finally` (`CancellationSupport.java:98-121`;
  `ObservableThreadExecutor.java:720, 787-822, 854-890`).
- **Interrupt checkpoints.** ByteBuddy-woven `InterruptionAdvice.onMethodEnter` checks
  `Thread.currentThread().isInterrupted()` and throws `InterruptedException` on
  `FilteringConstraintTranslator.translate`, `Formula.compute`, `OrderingConstraintTranslator.createSorter`,
  `Sorter.sortAndSlice` and `@Interruptible` methods (`InterruptionTransformer.java:44-80, 101-105`).
- **`queryTimeoutInMilliseconds` (5000).** Not enforced. Read only by `EvitaStatisticsEvent.java:220`; there is
  no scheduled cancel and no deadline check (`rg queryTimeoutInMilliseconds\(\)`).
  `transactionTimeoutInMilliseconds` (300 s) is the engine-mutation wait (`EngineTransactionManager.java:308`).
- **Transaction pipeline.** `SubmissionPublisher.offer` with a drop handler →
  `TransactionException("... queue is full")`, non-blocking (`TransactionManager.java:645-689`); the
  conflict-resolution and WAL locks time out after `waitForTransactionAcceptanceInMillis` (20 s default,
  `TransactionOptions.java:113`) on TX threads (`TransactionManager.java:1003-1075, 1343-1359`).
- **Session admission.** Warm-up catalogs: one session at a time (`ConcurrentInitializationException`);
  closing or suspended: 500 ms then `SessionBusyException`; no per-catalog or per-session request quota
  (`SessionRegistry.java:629-648, 949-965`; `EvitaSessionProxy.java:650-660`).
- **gRPC stream stall.** Gate stall timeout 300 s → `StalledGrpcStreamException` → `DEADLINE_EXCEEDED`
  (`GrpcOutboundGate.java:106-140`; `GlobalExceptionHandlerInterceptor.java:86-95`).

Note on interrupt semantics: `CompletableFuture.join()` does not return on interrupt. A cancelled request
whose worker is parked in a session-close join (section 8) stays parked until the commit stage completes; the
interrupt takes effect only at the next woven checkpoint or at a blocking call that honours it. The 500 ms
registry and closing-sequence waits go through `FutureAwaiter.awaitWithTimeout` (`FutureAwaiter.java:54-72`),
which uses `get(timeout)` and therefore does honour the interrupt (it rethrows as `SessionBusyException`).

## 8. Where a request thread waits today

- **REST/GraphQL mutation `session.close()`.**
  - Thread: TX. REST calls it inside `thenAccept` (`RestEndpointHandler.java:130-134`); GraphQL on the last
    completing root-field thread (`EvitaSessionManagingInstrumentation.java:86-100`).
  - Joined future: `closeNow(behaviour).toCompletableFuture().join()` (`EvitaSessionContract.java:346-361`)
    → `commitProgress.on(WAIT_FOR_CHANGES_VISIBLE)` by default (`TransactionContract.java:279-287`;
    `EvitaSession.java:1884-2003`).
  - Completed by: stage 1 (conflict resolution and WAL append) and stage 2 (trunk incorporation) on TX
    (`TransactionManager.java:1997-2017`), `waitUntilLiveVersionReaches`
    (`TrunkIncorporationTransactionStage.java:92`), with the completion dispatched via REQ `thenRunAsync`
    (`CommitProgressRecord.java:210-224`).
  - Bound: lock waits 20 s (`waitForTransactionAcceptanceInMillis`); otherwise pipeline throughput. The join is
    uninterruptible.
- **REST/GraphQL read `session.close()`.** Thread REQ; the same join, but `closeInternal` completes it
  synchronously when no transaction exists (`EvitaSession.java:1953-1963`). No wait.
- **Warm-up session close (catalog `WARMING_UP`).** Thread: whichever thread calls `close()` (TX for REST
  mutations, the last root-field thread for GraphQL, the caller for embedded use). Joined future: the same;
  completed by `catalog.flush()` executed on TX (`EvitaSession.java:1897-1937`,
  `flushFuture.execute(transactionExecutor)` at `1932`), a full catalog flush. Bound: flush duration.
- **gRPC `close` and `goLiveAndClose`.** No wait; `whenComplete` (`EvitaSessionService.java:1058-1090`,
  `729-760`). The response is emitted on the completing thread (REQ for commits, TX for flush).
- **Schema or catalog DDL outside an explicit transaction.** Thread: REQ (gRPC) or TX (REST mutation
  handlers). Joined future: `applyMutation(...).onCompletion().toCompletableFuture().join()` in
  `EvitaSession.updateSchemaInternal` (`2113-2125`), `Evita.defineCatalog` (`789-795`) and
  `Evita.registerRestoredCatalog` (`1097-1100`). Completed by `EngineTransactionManager` on TX
  (`Evita.java:565`). Bound: `engineMutationWaitIntervalInMillis` = 300 s (`EngineTransactionManager.java:308`).
- **gRPC gated streaming.** Thread REQ; `Condition.await` in `awaitWritable`
  (`GrpcOutboundGate.java:149-150, 273`); released by the EL on-ready callback; bound 300 s stall timeout.
- **System readiness.** Thread REQ; `CompletableFuture.allOf(futures).get(10, SECONDS)`
  (`ObservabilityProbesDetector.java:191`); FJP threads block in OkHttp (`NetworkUtils.java:116`); bound 10 s
  plus `requestTimeout` per probe.
- **Session-registry suspension (`POSTPONE`).** Thread EL (REST) or REQ (GraphQL, gRPC `createSession`
  `EvitaService.java:196-260`); `InSuspension.awaitFinish(500 ms)` (`SessionRegistry.java:976-984`);
  bound 500 ms, then `SessionBusyException`. Details in the sub-section below.
- **Closing-sequence wait on a session that is being force-closed.** Thread: whichever thread invokes the
  session (REQ, TX or EL); `ClosingSequence.awaitFinish(500 ms)` (`EvitaSessionProxy.java:650-660`);
  bound 500 ms, then `SessionBusyException`.
- **Session drain by the suspending thread.** Thread: TX for engine operators, or the caller of `goLive`;
  `allOf(closeFutures).get(remaining)` inside `closeAllActiveSessionsAndSuspend`
  (`SessionRegistry.java:357-432`); bound `DRAIN_GIVE_UP_TIMEOUT_MILLIS` = 5 s (`133`).
- **Storage-protocol auto-upgrade.** Thread SVC; `applyMutation(...).join()` (`Evita.java:1420-1427`);
  completed on TX; bound 300 s.
- **Catalog loading.** No wait found on the request path. A missing catalog throws `CatalogMissingException`
  (`JsonApiExceptionHandler.java:66`); `waitUntilFullyInitialized` (`Evita.java:1059-1068`) is a startup call.

Nested-wait facts on the transaction pool: (a) REST/GraphQL mutation threads are TX threads that park in the
commit join above. (b) The stages that must run to satisfy that join are consumer tasks submitted to
`ProgressingFuture.unrejectableExecutor(transactionalExecutor)` (`TransactionManager.java:1997-2017`), the
same bounded pool (`Catalog.java:698,793`). (c) Unrejectable tasks are never refused but only queued past the
bound (`ObservableThreadExecutor.java:1264-1268`); they still need a free TX thread to run. (d) The pool cannot
grow past `maxThreadCount` (16 shipped). The dependency therefore holds whenever every TX thread is parked in
a join at the moment a stage consumer task has to be started or restarted; nothing in the code prevents that
configuration, and the join does not respond to Armeria's interrupt-based cancellation.

### 8b. Suspension, forced close and the 500 ms waits

The `core/session` package has one timed-wait primitive and two wrappers around it, plus an enum that
decides whether a caller waits at all.

- **`FutureAwaiter.awaitWithTimeout`** (`FutureAwaiter.java:54-72`) is `future.get(timeout, unit)`: a
  timeout returns `false`; `InterruptedException` re-sets the interrupt flag and throws
  `SessionBusyException`; `ExecutionException` throws `SessionBusyException`. Unlike the `join()` waits above,
  this wait *is* interruptible, so Armeria's cancellation interrupt ends it immediately with a 400-class
  error.
- **`ClosingSequence`** (`ClosingSequence.java:33-57`) pairs a close lambda with a `closedFuture`;
  `awaitFinish` delegates to `FutureAwaiter` (`54-56`). `SessionRegistry.InSuspension`
  (`SessionRegistry.java:1322-1344`) does the same for a `suspendFuture`. Both are always called with 500 ms
  (`EvitaSessionProxy.java:653`, `SessionRegistry.java:987`).
- **`SuspendOperation`** (`SuspendOperation.java:29-39`): `POSTPONE` (catalog rename or replace in
  progress) makes new registrations wait; `REJECT` (catalog being terminated) refuses them.
  `awaitResumeOrRefuse` (`SessionRegistry.java:976-984`): `POSTPONE` → `awaitFinish(500 ms)` then
  `SessionBusyException`; `REJECT` → `InstanceTerminatedException` without waiting.
  `registerWhileNotSuspended` (`949-965`) makes two attempts under the registration read lock.

Who suspends, and on which thread:

- `POSTPONE`: `ModifyCatalogSchemaNameMutationOperator.java:362` (rename/replace), resumed at `333` and
  `602`. Engine mutation operators run under `EngineTransactionManager`, i.e. on **TX** (`Evita.java:565`).
- `REJECT`: `SetCatalogStateMutationOperator.java:140`, `RemoveCatalogSchemaMutationOperator.java:119`,
  `MakeCatalogAliveMutationOperator.java:265`, `ModifyCatalogSchemaNameMutationOperator.java:397` (all TX);
  `Evita.closeAllSessions` (`1896-1903`) at shutdown; and `goLive` from inside a warm-up session
  (`EvitaSession.java:484` → `Evita.closeAllSessionsAndSuspend` `1650-1656`), which runs on the thread that
  called `goLive` (**REQ** for gRPC `goLiveAndClose`, `EvitaSessionService.java:729-760`).

What the suspending thread does (`SessionRegistry.closeAllActiveSessionsAndSuspend`, `357-432`): CAS the
`InSuspension`; lock and unlock the `registrationGate` write lock as a barrier for in-flight registrations;
then loop: `startForcedCloseOfActiveSessions` (`488-520`) asks every active proxy to
`executeWhenMethodIsNotRunning` (mark rollback-only, `closeNow(WAIT_FOR_WAL_PERSISTENCE)`), waits
`allOf(futures).get(remaining)`, parks `DRAIN_PASS_PARK_NANOS` = 10 ms between passes (`159`), and gives up
after `DRAIN_GIVE_UP_TIMEOUT_MILLIS` = 5 s (`133`). An interrupt breaks the loop and restores the flag.

On which thread a forced close runs (`EvitaSessionProxy.java:616-627`, `667-671`, `682-690`): if the
session has no method in flight (`insideInvocation == 0`) the close lambda runs immediately on the
suspending thread; otherwise it is deferred and executed by the session's own thread in the `finally` of the
in-flight invocation, which then completes `closedFuture`. A concurrent caller arriving while a closing
sequence is installed waits `ClosingSequence.awaitFinish(500 ms)` and then gets `SessionBusyException`
(`650-660`).

Client-visible outcome: `SessionBusyException` (`SessionBusyException.java:36`), `InstanceTerminatedException`
(`InstanceTerminatedException.java:57`), `ConcurrentSessionAccessException`
(`ConcurrentSessionAccessException.java:61`) and `ConcurrentInitializationException`
(`ConcurrentInitializationException.java:63`) all extend `EvitaInvalidUsageException`, so they map to HTTP
400 (`JsonApiExceptionHandler.java:72`) and gRPC `INVALID_ARGUMENT`
(`GlobalExceptionHandlerInterceptor.java:96-108`).

## 9. Thread-identity, naming and priority assumptions

- **Worker threads are platform threads named `Evita-{pool}-N`, daemon, with `setPriority(threadPriority)`**
  (`ObservableThreadExecutor.java:1131-1152`; `Scheduler.java:1015-1042`, which also captures the creating
  thread's `ThreadGroup` at `1031`). Nothing parses the names: `rg` finds no `Thread.currentThread().getName()`
  in main sources and the `"Evita-request"` literal only in the factories. Names appear in
  `ConcurrentSessionAccessException` messages (`EvitaSessionProxy.java:639-641`).
- **`threadPriority` config** (request 8, transaction 5, service 1 in code; all 5 in YAML;
  `ThreadPoolOptions.java:62-65, 87, 91, 95`; `evita-configuration.yaml:7,12,17`; the driver too at
  `EvitaClient.java:803-804, 1524-1525`) is applied via `Thread.setPriority`. Virtual threads ignore priority
  (always `NORM_PRIORITY`), so the option would be a no-op for any pool moved to virtual threads.
- **Session ownership is a `Thread` reference** (`EvitaSessionProxy.java:118, 634-649`): reference equality
  on `Thread.currentThread()`. A virtual thread is a distinct `Thread` object per task, so per-request virtual
  threads keep the semantics (one invocation chain owns the session; re-entrancy on the same thread allowed).
- **Cancellation records the executing `Thread` and interrupts it**
  (`ObservableThreadExecutor.java:720, 787-822, 854-890`). `Thread.interrupt` and `isInterrupted` behave the
  same on virtual threads.
- **Collator striping by thread id** (`CollationKeyCache.java:261-268`,
  `threadId() & (COLLATOR_STRIPES - 1)`) relies on a small, stable set of thread ids so a worker keeps
  borrowing "its" collator. Virtual-thread ids are unique and monotonically increasing per thread, so the
  stripe still distributes but the per-thread affinity disappears.
- **Pool metrics are `ThreadPoolExecutor` counters** (pool size, active, queue depth, completed delta) emitted
  as JFR events (`ObservableThreadExecutor.java:60-140`, `emitStatistics`; `Evita.java:718-729`), and
  `getRejectedTaskCount` / `getSubmittedTaskCount` feed the health probe
  (`ObservabilityProbesDetector.java:334-345`). A per-task virtual-thread executor has no pool size, queue or
  rejection, so `RequestThreadPoolStatisticsEvent` fields and `INPUT_QUEUES_OVERLOADED` would have no source.
- **Test mode and the gate's direct-executor detection** (`ObservableThreadExecutor.java:74-77`;
  `GrpcOutboundGate.java:85-90`) detect by executor type, not by thread identity.
- **MDC and tracing context** are captured on the submitting thread and restored on the worker
  (`ObservableThreadExecutor.java:66-70`, `TracingContext.captureContext()`); thread-local based, inventoried
  in the thread-local part of this specification.
- **Blocking primitives on the request path**, listed for the pinning analysis: `GrpcOutboundGate` uses
  `ReentrantLock` / `Condition` (`149-150`); `CommitProgressRecord.enqueueCompletion` is `synchronized`
  (`210`); `DefaultChangeCaptureSubscription` guards `onNext` with a lock (`210, 300-325`);
  `ObservabilityProbesDetector` uses `synchronized (readiness)` (`180-182`). `join()` / `get()` sites are in
  section 8.

## 10. Implications for a VT-per-request design (facts only)

1. **What a request thread actually blocks on** is confined to section 8: commit and flush joins, engine-DDL
   joins, the gRPC outbound gate, the readiness probe, and sub-second registry waits. Query execution, DTO
   conversion and JSON or protobuf serialization are CPU-bound. The carrier-release benefit of virtual threads
   therefore applies to mutation sessions, warm-up bulk loads, gated streams and readiness, not to the read
   path.
2. **The bounded pools are the only admission control.** Removing or unbounding them leaves Armeria's
   `requestTimeoutMillis`, `maxRequestLength`, `idleTimeoutMillis` and the transaction pipeline's
   offer-with-drop as the remaining limits; there is no `maxNumConnections`, HTTP/2 stream cap override,
   per-catalog or per-session quota in the code today. The overload signals that exist
   (`INPUT_QUEUES_OVERLOADED`, JFR `BackgroundTaskRejectedEvent`, pool-statistics events) are derived from
   `ThreadPoolExecutor` rejection and queue counters.
3. **Nested submission is present in three places and nested joining in one.** GraphQL root fields (REQ → REQ
   or TX, no join), CDC deliveries and commit-completion callbacks (pipeline → REQ, no join), and the
   REST/GraphQL mutation close (a TX thread joins work that must run on TX). Only the last one is a
   starvation dependency on the pool bound; it disappears when the waiting side and the pipeline side are
   not competing for the same bounded set of carriers, and it is unaffected by cancellation because the wait
   is a `CompletableFuture.join()`.
4. **The event loop already does non-trivial evitaDB work before the handoff**: REST JSON parsing, `Query`
   reconstruction and session creation (with registry locks up to 500 ms), GraphQL envelope parsing, and, on
   the WebSocket paths, entire session lifecycles and graphql-java execution. These costs are independent of
   which executor runs the task afterwards.
5. **Mutation placement differs per API.** REST/GraphQL mutations run on TX (with `parallelize=false`,
   GraphQL mutations run on REQ), gRPC mutations run on REQ, and the commit pipeline itself always runs on
   TX. gRPC `close` is asynchronous; REST/GraphQL `close` is a join.
6. **Thread-identity mechanisms survive virtual threads unchanged** (session ownership guard,
   interrupt-based cancellation, `executingThread` tracking). **Thread-priority and pool-metric mechanisms do
   not**: `threadPriority` becomes a no-op, `RequestThreadPoolStatisticsEvent` and `INPUT_QUEUES_OVERLOADED`
   lose their source, and `CollationKeyCache` loses per-thread collator affinity.
7. **Timeouts.** The effective deadline is Armeria's 2 s (YAML) request timeout, delivered as a thread
   interrupt to woven checkpoints; `queryTimeoutInMilliseconds` is unenforced. Streaming and CDC re-arm the
   deadline per message; the gRPC gate and CDC heartbeats depend on the Scheduler (SVC) and on
   `ServiceRequestContext` deadline APIs, not on the request pool's thread type.
8. **Read-only sessions already permit parallel access; read-write sessions serialise per invocation.** A
   design that raises concurrency per gRPC session does not change the read-write guard: concurrent RPCs on
   one read-write session fail with `ConcurrentSessionAccessException` today and would fail the same way on
   virtual threads.
