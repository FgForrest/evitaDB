---
title: Pace gRPC server-streaming producers with a readiness gate, and unblock large file transfers
date: 2026-08-24
updated: 2026-08-25 05:00
status: accepted
kind: fix
issues: [1441]
prs: [1450, 1451]
areas:
  - evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/utils
  - evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/services
  - evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/services/interceptors
  - evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver
supersedes: []
superseded-by: []
relates: [2026-08-05-streaming-calls-must-not-be-retry-decorated]
---

# Pace gRPC server-streaming producers with a readiness gate that parks the worker

Server-streaming RPCs used to push messages into Armeria's outbound queue as fast as they could
produce them, without ever asking whether the transport could take another one. The queue is
unbounded, so a client slower than the producer made the server materialise the entire payload in
memory. `fetchFile` was the acute case: a 690 MB backup download over a slow tunnel died part-way
through with a bare `UNKNOWN`. Producers now pace themselves against `ServerCallStreamObserver.isReady()`
through a shared helper, `GrpcOutboundGate`, which parks the producing worker until the transport
drains. `restoreCatalog`, the upload counterpart, moved its blocking file IO off the event loop and
replaced the throttle it lost in the process with explicit inbound demand. Two size caps found along
the way — both pre-existing, both making a large transfer fail with a status that explained nothing —
were fixed on the driver side in the same line of work.

## Why

gRPC-Java's send path never blocks — a fact that is easy to get wrong, because grpc-go and the
gRPC C++ sync API *do* block on the flow-control window. `StreamObserver.onNext` marshals the message
and hands it to Armeria's `StreamMessage`, whose `tryWrite` refuses a message only once the stream is
closed, never because it is full. `isReady()` is the only back-pressure signal there is, and nothing
in the module consulted it: `setOnReadyHandler` appeared **zero** times across all 20 server-streaming
RPCs.

The cost is paid off-heap and is worse than the payload. Because marshalling is eager, the queued
objects are pooled Netty buffers: a 512 MiB file measured **1035 MiB** of retained direct memory,
about 2x the payload once pooled-arena reservation is counted. The ceiling such a download hits is
therefore `-XX:MaxDirectMemorySize`, not `-Xmx`, and the failure surfaces as `UNKNOWN` with no message
because the allocation that finally fails does so inside Armeria, whose fallback for an escaping
`Throwable` is exactly that.

Two constraints made the fix non-obvious, and both are the reason this record exists rather than a
commit message:

- **`ServerCall.isReady()` is not abstract.** It ships a default returning an unconditional `true`.
  `ObservabilityInterceptor` decorated the call without extending `ForwardingServerCall`, so it
  answered `true` forever — any readiness-driven loop written against it would have been a silent
  no-op. Nothing about the service method reveals this.
- **The producing loop's thread is not guaranteed to be a worker.** `executeWithClientContext` hands
  off to `Evita.getRequestExecutor()`, which for an engine built with `directExecutor = true` is an
  `ImmediateExecutorService` that runs the task inline — on the transport event loop.

### Previous state

`fetchFile` read the file in 64 KB chunks in a tight `while` loop, calling `onNext` per chunk with no
gating (`EvitaManagementService.java:721-727` before this change). `getMutationsHistory` and
`getTrafficRecordingHistory` had the same shape via `Stream.forEach`. This was invisible for as long
as every consumer kept up, which is the normal case on a LAN.

`restoreCatalog` performed `Files.createTempFile`, a per-chunk `writeTo` against an **unbuffered**
stream, and `close`/`Files.size`/`newInputStream` directly on the event loop. It was never throttled
deliberately: gRPC auto-requests the next message only after `onMessage` returns, so the inline
blocking write *was* the throttle — accidentally, and at the price of thousands of blocking write
syscalls on a thread shared with every other connection.

## Options considered

### Option A — park the producing worker on a readiness gate (chosen)

A small shared helper attached synchronously in the service method; the producing loop calls
`awaitWritable()` before each `onNext` and stops when it returns `false`.

- **Pros:** keeps the loop's straight-line shape, so try-with-resources, tracing scope and error
  handling stay in one method. Pins no thread the RPC did not already own for its whole duration.
  One helper covers every call site.
- **Cons:** holds a request-executor thread for the duration of a slow download rather than for the
  duration of a fast disk read. Needs a stall timeout so a client that stops reading without hanging
  up cannot pin that thread forever.

### Option B — drive the loop from the on-ready callback (declined)

Turn each producer into a pump re-entered by `setOnReadyHandler`, holding the source and buffer as
call state.

- **Pros:** parks no thread at all; the natural shape for a purely async server.
- **Rejected because:** the on-ready callback runs on the Armeria event loop, so the pump would have
  to bounce every chunk back to a worker anyway — and then needs its own serialization guard to stop
  two callbacks from pumping concurrently. That buys back the thread at the cost of turning three
  straight-line loops into three hand-written state machines, each with its own resource-lifetime and
  tracing-context problem. Worth revisiting only if request-executor exhaustion from slow downloads
  is ever actually observed.

### Option C — annotate the handlers `@Blocking` (declined, for `restoreCatalog`)

Armeria supports the annotation per method and it would route the handler and its listener callbacks
to `evita.getServiceExecutor()` in one line.

- **Pros:** one-line change; no explicit flow control to maintain.
- **Rejected because:** `AbstractServerCall` dispatches each message as
  `blockingExecutor.execute(() -> invokeOnMessage(...))` onto a **shared pool**, so per-call ordering
  would rest on an undocumented implicit invariant — that auto-request never lets a second message be
  dispatched before the first returns. If that ever fails, the symptom is a silently corrupted catalog
  ZIP, discovered at restore time. Revisit only if Armeria documents the ordering guarantee.

## Decision

**Chosen: Option A**, with Option C explicitly rejected for the upload path in favour of an explicit
ordered chain.

The deciding driver was that a slow download already owns its worker thread for the whole transfer;
gating changes how *long* it is held, not whether. That is a bounded, measurable cost, and it is
bounded further by a stall timeout. Option B's cost — three bespoke state machines whose failure modes
are resource leaks and lost tracing context — is unbounded in review effort and lands on every future
streaming RPC someone adds.

Option B wins the day request-executor exhaustion from concurrent slow downloads is observed in
production. That is the trigger for an ADR superseding this one.

## Key technical details

- **`GrpcOutboundGate.attach(...)` must be called synchronously in the service method, before it
  returns.** gRPC's `ServerCallStreamObserverImpl` freezes handler registration the moment the service
  method returns and throws for any later `setOnReadyHandler`/`setOnCancelHandler`. Only the loop
  belongs on the worker. The codebase already documents the same constraint for cancel handlers in
  `AbstractChangeCaptureSubscriber`.
- **The gate owns the call's cancel handler.** gRPC keeps only the last one registered, so cleanup
  that service methods used to register themselves (closing the underlying `Stream`) is passed into
  `attach(...)` instead.
- **`awaitWritable()` checks cancellation *before* readiness, in every branch.** Armeria increments
  its pending-message counter in `sendMessage` and only unwinds it once the payload is genuinely
  consumed, which never happens for a cancelled call — so readiness stays false forever. A gate that
  consulted readiness first would pin a worker for the full stall timeout every time a client pressed
  cancel. Registering a cancel handler also stops gRPC throwing `CANCELLED` from `onNext`, so this
  check is the only thing left that stops a producing loop from reading its whole source into a dead
  call.
- **Producing on the event loop is detected and degrades to ungated**, with one warning per call.
  Waiting there is a self-deadlock, not back-pressure: the parked thread is the only one that could
  make readiness true again. This is reachable only for an engine built with `directExecutor = true`
  (embedded test runs); `EvitaServer` always uses real pools. The consequence for tests is in
  *Verification* below.
- **`fetchFile`'s chunk size is 1 MB, and that is load-bearing, not cosmetic.** Armeria's readiness
  is `pendingMessages == 0`, so a gated loop keeps exactly one message in flight and every chunk costs
  an event-loop round trip; at the original 64 KB a large backup would have been over ten thousand
  sequential round trips and slower than the unbounded loop for clients that keep up. The ceiling is
  the receiver's maximum inbound message size — 4 MB in gRPC-Java, unlimited in Armeria's client.
- **`ObservabilityServerCall` now extends `SimpleForwardingServerCall`.** Forwarding by default is the
  invariant: `isReady`, `setOnReadyThreshold`, `getAttributes`, `getAuthority`, `getSecurityLevel`,
  `setCompression` and `setMessageCompression` all have non-abstract defaults that silently disagree
  with the transport.
- **Repairing readiness broke a hidden dependency on it.**
  `GlobalExceptionHandlerInterceptor.handleException` guarded its `close(...)` with
  `if (serverCall.isReady())`. That only ever read as an open/closed test because readiness was
  hard-wired to `true`; once real, it would have swallowed the error status for any RPC that had
  already sent data, leaving the client on a stream that is never closed. It now guards on
  `isCancelled()`.
- **`restoreCatalog` serialises its file work on one `CompletableFuture` chain per call**, and issues
  `request(1)` only once each chunk is on disk (`disableAutoRequest()` in the service method).
  The `request(1)` is dispatched back onto the event loop: Armeria's `StreamingServerCall` keeps its
  upstream subscription in a plain, non-volatile field, so raising demand from an arbitrary thread
  relies on a happens-before its own code does not promise. Chunk ordering is the invariant here; its
  loss is a corrupted ZIP, not an exception.
- **The driver's streaming channels no longer carry a total-response-length cap.** Armeria defaults
  `maxResponseLength` to 10 MiB and counts the *whole* HTTP body, which for a server-streaming call is
  every message added together. `EvitaClient` never overrode it, so `fetchFile` could not download a
  backup larger than 10 MiB at all — it died part-way through with `RESOURCE_EXHAUSTED`. This is a
  pre-existing defect independent of the buffering one, found because the new upload test needed a
  14 MB backup. The cap is lifted on the streaming and CDC channels and **kept on the unary channel**,
  where a 10 MiB reply genuinely is anomalous. Lifting it globally was rejected: it would remove the
  only guard against a runaway unary response, and unary replies have no other bound.
- **The driver uploads a catalog restore through `RestoreCatalogUnary`, not the client-streaming RPC.**
  A client-streaming upload is a *single* HTTP request, so `maxRequestLength` — which evitaDB wires to
  `api.maxEntitySizeInBytes`, 2 MB by default (`ExternalApiServer.java:617`) — bounded the **whole
  backup** rather than a chunk. Any real-sized catalog therefore died part-way through the upload with
  a bare `RESOURCE_EXHAUSTED`, making `EvitaClient`'s restore unusable. `RestoreCatalogUnary` (on the
  wire since 2024-09) sends each chunk as its own request, so only the chunk has to fit; the server
  accumulates them into one temp file keyed by the `fileId` it returns and submits the restoration task
  when the accumulated size reaches the announced total. Raising `maxEntitySizeInBytes` instead was
  rejected — it is a deliberate operator guard that applies to REST and GraphQL too, and overriding a
  security setting to make one RPC work is the wrong trade. Chunk size is 512 KB, which must stay under
  whatever the operator configures; nothing client-side can discover that value.
- **The upload stub is built on the *streaming* channel even though the call is unary.** The unary
  channel carries the retry decorator, and with `retry` enabled that rule set replays on timeouts and
  on 503/504/UNKNOWN. Replaying an append would add the chunk twice. The server catches the overshoot,
  so the damage is a spurious failure rather than a corrupt catalog — but appends are not idempotent
  and have no business on a channel that replays.
- **An oversized request now says which limit it hit.** `GrpcProviderRegistrar` maps Armeria's
  `ContentTooLargeException` to `RESOURCE_EXHAUSTED` *with a description* naming
  `api.maxEntitySizeInBytes` and pointing at `RestoreCatalogUnary`. The driver no longer needs it, but
  gRPC-Web and third-party clients still use the streaming RPC and previously got a bare status
  indistinguishable from any other resource failure.
- **A stalled stream is abandoned with `DEADLINE_EXCEEDED`, not `UNKNOWN`.**
  `StalledGrpcStreamException` is mapped explicitly in `GlobalExceptionHandlerInterceptor` and logged
  at WARN — it is a client/network condition, not a server fault.

## Verification

- `LongRunningGrpcFetchFileBackpressureTest` (long-running module, `useRealThreadPools = true`) —
  512 MiB file, gRPC-Web framing, a manual-flow-control client that takes 4 chunks and stops.
  Retained memory (heap **plus** Netty and NIO direct counters, which is the whole point — heap alone
  moved 6.2 MiB and would have reported the bug as absent):
  **1035 MiB before the fix, 14.4 MiB after**, of which 12.0 MiB was still present after the client
  drained the stream, i.e. allocator arena growth rather than queued messages. Genuine in-flight data
  ~2.4 MiB, about two chunks. The thread-state line flipped from `0` threads inside `fetchFile` (the
  loop had run to completion) to `1` (parked in the gate). The client then resumes and the whole file
  arrives with a matching CRC32.
- `LongRunningGrpcFetchFileDeadlineTest` (long-running module, `useRealThreadPools = true`) — 64 MiB
  to a client that drip-feeds `request(1)` every 100 ms, with a 2000 ms server deadline injected as a
  `grpc-timeout` header by a `ClientInterceptor` so it binds on the server and nowhere else.
  **Completes in 6819 ms with the re-arm, dies at 2144 ms after 19 chunks without it.** The test asserts
  the scenario as well as the outcome — a machine fast enough to finish inside the deadline fails the
  precondition rather than passing vacuously.
- `GrpcTimeoutUtilTest` (functional module) — three cases pinning how a streaming deadline is rolled
  forward. The load-bearing one runs both strategies **side by side on two real
  `ServiceRequestContext`s**: three re-arms 120 ms apart from a captured budget leave the horizon at
  1364 ms (last message + the configured 1000 ms), while re-reading `requestTimeoutMillis()` each time
  leaves it at ~1720 ms and climbing. The counterfactual is therefore inside the test rather than
  reachable only by reverting the fix. It is deliberately a **characterisation test of Armeria**: the
  compounding is not part of its documented contract, so an upgrade that changes it fails here loudly
  instead of silently restoring the ratchet. The `Thread.sleep` between re-arms is a detection widener —
  a loaded machine makes the two strategies diverge further, never less.
- `GrpcOutboundGateTest` (functional module) — six deterministic cases against a fake observer:
  pass-through when ready, wake on the on-ready callback, refuse a cancelled call **without parking**,
  release a waiting producer on cancel and on close, and abandon a stream nobody reads.
- `ObservabilityInterceptorReadinessTest` (functional module) — pins the forwarding of `isReady()`.
- `EvitaClientReadWriteTest#shouldBackupAndRestoreCatalogViaDownloadingAndUploadingFileContents` —
  end-to-end backup → `fetchFile` → streaming `restoreCatalog` → restored catalog queried for a
  matching entity count. Its dataset already carries `useRealThreadPools = true`, so it exercises the
  gated download and the ordered upload chain for real, not the collapsed topology. What it does *not*
  exercise is the multi-message path: its ten-product catalog archives into a single 64 KB message.
- `LongRunningGrpcRestoreCatalogUploadTest` — three cases. (1) A **5.47 MB** backup (2.6x the 2 MB
  request-body limit that used to be the ceiling) uploaded across ~10 unary chunks, restored, and every
  one of its 200 entities' 32 KB payloads compared byte for byte against what was backed up; it asserts
  its own size against that limit from below, so shrinking the payload can no longer hide the ceiling
  being gone. (2) A **549 KB** backup of a second, deliberately small catalog pushed through the raw
  client-streaming RPC in ~17 messages and verified the same way — the only end-to-end coverage of
  `RestoreCatalogUploadObserver`; it asserts its size against the limit from *above*, the mirror of
  case (1), because that RPC is one request and would otherwise fail as oversize rather than as a
  regression. Calibrated: deleting the per-write `request(1)` stalls the upload and fails its 30 s latch
  deterministically. (3) A client that still uses the client-streaming RPC pushing past the limit,
  asserting the rejection carries a description naming `maxEntitySizeInBytes` and `RestoreCatalogUnary`
  rather than a bare status. Writing case (1) is what surfaced the driver's 10 MiB response cap.
- `LongRunningEvitaClientLargeFileDownloadTest` — 24 MiB fetched through `EvitaClient` with its
  **default** channel configuration, CRC32 verified. This is the only test that covers the
  response-cap fix: the functional round trip downloads ~1 MB, and
  `LongRunningGrpcFetchFileBackpressureTest` moves 512 MiB through its own stub built with
  `maxResponseLength(0)`, so neither exercises the driver default. It asserts its own file size against
  the 10 MiB cap so it cannot be shrunk below the thing it guards.
- Regression: 648 tests across `driver | (grpc & external_api)`, green.

**The default test engine cannot exercise the gate.** `EvitaParameterResolver` builds the engine with
`directExecutor = !useRealThreadPools`, so `executeWithClientContext` runs inline on the event loop and
every functional test takes the ungated branch. A test that means to cover back-pressure must say
`@DataSet(..., useRealThreadPools = true)` and say why; without it the test measures the unbounded
path no matter what the server does, and can never fail.

## Consequences & open follow-ups

- **A restore upload's temporary archive is owned by whoever created it, and the owner must survive a
  hand-off the request pool refuses.** Both restore paths assemble the upload into a temporary file that
  nothing else will ever collect: `FileManagementService.createTempFile` deliberately does *not* reserve
  the file (that is `createManagedTempFile`), no sweeper walks the work directory, and the restore step
  that deletes it — `deleteAfterRestore` — only runs for an upload that completed. An upload that stops
  half way therefore leaves an archive the size of a catalog behind for the lifetime of the process.

  The two paths hang that cleanup on different things, and neither is obvious from the call site:
  - **Client-streaming** (`RestoreCatalogUploadObserver`) owns the file directly and discards it in
    `failUpload`. Because the request pool is bounded and *throws* once its queue fills
    (`EvitaRejectingExecutorHandler`), every hand-off to it has to be made by hand rather than through
    `thenRunAsync`: a rejection raised while a previous step is in flight surfaces on that worker inside
    `CompletableFuture#postComplete`, unrelated to the call, and the stage simply never completes.
    Worse, a rejection raised once the chain is idle throws *before* `uploadChain` is reassigned, leaving
    the chain looking healthy — so the next chunk reopens the archive that was just deleted. Poisoning
    the successor stage in both cases is what closes that second hole.
  - **Chunked unary** (`restoreCatalogUnary`) has no half-close to notice, so the *restoration task's
    future* carries the cleanup. It completes on every terminal outcome, including the scheduler's
    ten-minute purge of tasks still waiting for a precondition, which is what catches a client that
    crashed or lost the network. The driver additionally cancels the task on its way out
    (`EvitaClientManagement#abandonRestoreUpload`), which only makes the reclaim prompt — it is best
    effort by construction, since the failure that triggers it may have taken the channel with it.

  Anything that later adds a third upload path inherits this obligation. One invariant is worth stating
  because nothing enforces it locally: the unary hook is registered on the *first* chunk, while the
  over-size branch that relies on it can fire on any chunk. It works because `getWaitingTask` hands back
  the same task instance across requests — a cross-request dependency that a future change to task
  lookup could quietly break.

  Both halves are verified, and both tests were calibrated against the pre-fix code rather than merely
  observed to pass:
  - `RestoreCatalogUploadObserverTest` — three rejection cases, each of which hangs for the full 30 s
    latch without the fix instead of terminating the call, plus a control case that a progressing upload
    keeps its archive.
  - `EvitaClientReadWriteTest#shouldDiscardTheArchiveOfAnAbandonedChunkedRestoreUpload` — drives a real
    chunked upload whose source dies after the first chunk and asserts the work directory is unchanged.
    With the task-future hook removed it fails with the archive still present
    (`[.lock, 69cb300e-….zip]`), which is precisely the leak being fixed.
- A slow download now holds a request-executor thread for the duration of the transfer (bounded by
  `GrpcOutboundGate.DEFAULT_STALL_TIMEOUT_MILLIS`, 5 minutes of *zero* progress). The pool is
  `availableProcessors() * 4` by default. If concurrent slow downloads ever exhaust it, that is the
  trigger for Option B.
- **CDC demand gating was deliberately not done.** Both shared publishers are pull-based, so
  withholding demand is lossless for engine mutations — it only moves a subscriber from the ring
  buffer to the WAL. But `DefaultChangeCaptureSubscription.deliverImmediate` **silently drops** host
  events when `requested == 0`, so gating would widen a microsecond drop window to however long the
  client lags. It is blocked on a second defect anyway: a subscriber whose WAL pointer has been purged
  (`walFileCountKept`, default 8) gets `findWalIndexFor == -1`, a nonexistent file, a null supplier and
  an empty stream — no error, no advance, re-requesting the same pointer forever. Filed as **#1446**
  (milestone 2026.3); it has to land before CDC gating does.
- **The ungated producer plausibly starved *sibling* RPCs on the same connection, not just memory.**
  Reported from production: while a backup download was in flight, unrelated gRPC calls issued by other
  browser threads failed. Memory exhaustion does not explain that on its own. Three mechanisms do, and
  gating addresses all three — but the ordering below matters, because the first draft of this bullet
  led with the least likely one.

  1. **Bandwidth saturation of the shared connection (leading explanation).** The ungated loop pushed
     the whole file into Armeria's unbounded queue at disk speed, so the link ran at capacity for the
     duration. A sibling call's handful of bytes queues behind that backlog, and only has
     `api.requestTimeoutInMillis` — 2 s shipped — before it is killed. No flow-control subtlety
     required; a saturated tunnel is sufficient.
  2. **Event-loop saturation.** Marshalling happens in `doSendMessage` on the event loop, and Armeria
     pins a connection to one. A 690 MB file at the old 64 KB chunk size meant ~10,500 marshal tasks
     queued onto the one thread that also serves every other call on that connection.
  3. **HTTP/2 connection-window exhaustion (least likely — see the correction below).**

  Gating addresses all three structurally rather than incidentally: readiness in Armeria is
  `pendingMessages == 0`, so a gated producer never writes more than one message ahead of what the
  transport has actually consumed. The backlog, the marshal-task flood and the window pressure all
  disappear together.

  **Correction to an earlier revision of this record, which had the flow-control argument wrong in two
  ways.** It cited Armeria's `http2InitialConnectionWindowSize` / `http2InitialStreamWindowSize`
  defaults (1 MiB) as the operative numbers: those are what the evitaDB server advertises for *inbound*
  (client→server) data and play no part in bounding server→client writes. It is indeed the **client's**
  advertised windows that bound what the server may write — for Chrome, roughly 6 MiB per stream against
  a session window raised to ~15 MiB. And the starvation step needs a condition the earlier text
  asserted without stating: one stream can only exhaust the *connection* window if the client returns
  connection-level credit as the application consumes. Browsers deliberately do the opposite — session
  credit is returned as data lands in per-stream buffers, and only stream-level credit tracks the
  consumer, precisely to stop one stream starving the others. Under that policy a stalled download pins
  at most its own stream window and siblings keep flowing, so "no other stream can send a byte" is
  unlikely for a browser client. It remains plausible for a non-browser peer with a naive
  window-management policy.

  **Not confirmed**, and worth confirming before it is treated as closed: the failed siblings' statuses
  would distinguish the mechanisms — `DEADLINE_EXCEEDED`/cancellation points at saturation or window
  starvation plus the request timeout, a bare `UNKNOWN` with no description at direct-memory exhaustion
  (the signature recorded above), and if evitaLab was on HTTP/1.1 gRPC-Web the cause would instead be the
  browser's ~6-connection-per-origin pool, which gating does not address.
- **Which streaming paths got the new budget, and the two that deliberately did not.**
  `api.endpoints.gRPC.streamingRequestTimeoutInMillis` is resolved once per call through
  `GrpcTimeoutUtil.resolveStreamingBudgetMillis`, which returns `0` when the call carries no deadline so
  that "the caller asked for none" survives the substitution. Five sites use it: `GrpcOutboundGate` (all
  three gated server-streaming loops), the client-streaming `RestoreCatalogUploadObserver`,
  `goLiveAndCloseWithProgress`, and `streamBackupProgress`. The last of those was already safe by
  accident — its 1 s poll re-arms whether or not anything was sent — but safety resting on a poll
  interval is not a property anyone should have to preserve.

  Two sites were left on the whole-request budget, and neither is an oversight:

  - **`RestoreCatalogUnary` — cannot be widened from the handler.** Armeria's `UnaryServerCall`
    aggregates the request body and deframes only in the aggregation callback, so the handler runs
    strictly *after* full body receipt: by the time our code could re-arm anything, the window it would
    need to widen is already spent. The consequence is a real floor on uploads — the driver's 512 KB
    chunk against the shipped 2 s budget needs roughly **2.1 Mbit/s** upstream — and the escape hatch, if
    slow-uplink restores ever matter, is an HTTP-level decorator on that route extending the context
    timeout at request start, before aggregation. Not built, because no report has yet demanded it.
  - **CDC (`AbstractChangeCaptureSubscriber`) — the budget is load-bearing elsewhere.** It feeds
    `resolveHeartBeatDelay`, which is `clamp(min(requestTimeout, idleTimeout) − 5 s, 1 s, 5 min)`.
    Substituting 300 s does **not** break it — the 60 s idle timeout caps the result at 55 s, which still
    keeps both the deadline and the idle timeout alive — but it coarsens liveness detection from the
    clamped 1 s floor to 55 s, a ~55x change made for a subsystem that never exhibited the symptom. CDC
    demand gating is blocked on **#1446** regardless; whoever does that work should revisit this
    together with the heartbeat, rather than change one clock in isolation now.

    (An earlier revision of this reasoning claimed ~150x and implied the substitution would break the
    heartbeat outright. Both were wrong — the arithmetic above is what the code actually computes.)
- **Gating trades unbounded memory for a parked request-executor thread.** The gate parks the producing
  worker rather than driving the loop from the on-ready callback, so a download now holds one pool
  thread for its whole duration instead of returning immediately. `K` concurrent slow downloads hold `K`
  threads, bounded only by `api.endpoints.gRPC.streamingRequestTimeoutInMillis`. This is a deliberate trade — the
  callback-driven pump gRPC-Java intends would avoid it but turns each producing loop into a state
  machine, losing its try-with-resources, tracing scope and error handling — but it is a capacity
  consideration that did not exist before, and a deployment serving many concurrent large downloads
  should size the request executor with it in mind.

  Note what the streaming budget does **not** lengthen: a gRPC deadline is enforced by the *client*
  (Armeria's client maps `withDeadlineAfter` onto its own response timeout, and grpc-java runs a deadline
  timer), so a client that asked for 500 ms still sees its call end at 500 ms and the resulting cancel
  releases the parked worker promptly. What the server-side substitution changes is only how long a
  **live but silent** peer can pin a worker. That is why re-arming to the streaming budget rather than
  the client's own `grpc-timeout` is safe rather than an override of the caller's intent.
- **Gating put `fetchFile` under the request deadline, which exposed that the per-message re-arm was
  wrong everywhere.** Before the gate the loop drained into Armeria's outbound queue at disk speed and
  returned, so the request timeout never bound it. Now the handler lives as long as the client takes to
  consume — and `fetchFile` was the only streaming producer in the module not re-arming the deadline at
  all. Adding the re-arm there is what surfaced the deeper defect: **the other five sites were re-arming
  from a budget that compounds.**

  `serviceContext.requestTimeoutMillis()` reads like "the configured timeout" and is neither that nor
  the time remaining. Armeria stores the timeout **relative to the request's start**, and
  `SET_FROM_NOW` writes `elapsed + newTimeout` into that same field
  (`DefaultCancellationScheduler#setTimeoutNanosFromNow0`, 1.40.0), which the getter returns verbatim.
  Feeding it back into the next re-arm therefore *ratchets*: a 2 s budget re-armed every 100 ms becomes
  2.1 s, then 2.3 s, then 2.6 s, growing without bound. The rolling stall window the whole design rests
  on had quietly become an ever-receding horizon that a genuinely stalled client never reaches — the
  opposite failure from the one being chased, and invisible because it only ever makes calls *survive*.

  Fixed by capturing the budget once, before the first message, via the new
  `GrpcTimeoutUtil.captureRequestTimeoutMillis`. All eight sites now do this;
  `AbstractChangeCaptureSubscriber` already did, which is why CDC never showed the symptom.

  **The re-arm is now the gate's job, not the call site's.** `GrpcOutboundGate` captures the budget at
  `attach()` — by construction "before the loop" — and re-arms on every `awaitWritable()` that grants a
  send, on all three of its return paths (a fast-path-only re-arm would leave a *fast* client's transfer
  un-armed, which is the original bug with extra steps). The hand-written re-arms in the three gated
  loops are gone; the ungated producers keep theirs, corrected. This is the structural half: a new gated
  streaming method cannot forget, because the grant and the re-arm are the same act.

  One asymmetry this introduced, and why `grantCompletionWindow()` exists: a grant happens *before* each
  message, whereas the deleted hand-written re-arms happened *after*. So moving the re-arm into the gate
  silently orphaned the **last** message of every stream — the deadline would stay frozen at the final
  grant while the half-close and the residual queue were still in flight, and the residual is exactly
  what a slow client is slowest at. The three gated loops now call `grantCompletionWindow()` immediately
  before `onCompleted()`. Anyone adding a gated producer must do the same; the loop-shaped alternative
  (grant *after* the write) was rejected because it would leave the first message un-armed instead.

  **Re-measured against the corrected code**, with `LongRunningGrpcFetchFileDeadlineTest`: 64 MiB in
  64 chunks to a client that drip-feeds `request(1)` every 100 ms, server armed to 2000 ms by an
  injected `grpc-timeout`.

  | | outcome |
  |---|---|
  | with the re-arm | **completes in 6819 ms**, all 64 chunks, CRC matches |
  | re-arm removed | **dies at 2144 ms**, 19 chunks, `RST_STREAM: INTERNAL_ERROR` |

  So the deadline now bounds silence rather than the transfer: 3.4× the budget elapses without the call
  being touched. The earlier "8696 ms with the re-arm" figure was measured against the *ratcheting*
  version and is not comparable — since the ratchet only ever extends the deadline, whatever ended that
  run was not the request timeout. It is not worth chasing: the scenario is now covered by a test that
  passes for the right reason and fails at the budget when the guard is removed.

  The earlier note in this record that a client sending no `grpc-timeout` leaves the server timeout
  *disabled* was **wrong**, and the correction matters. Armeria's `FramedGrpcService` overrides the
  context timeout only when the header is present; absent it the server's own
  `api.requestTimeoutInMillis` stays in force — 1 s in code, 2 s in the shipped configuration. A client
  that sets no deadline therefore gets the *shortest* budget of anyone, which makes the re-arm load-
  bearing for exactly the clients least able to compensate. What the earlier test actually hit was
  `responseTimeoutMillis(0)` suppressing the header Armeria would otherwise have derived from the
  deadline, leaving the harness's 10-minute server budget in force.
- **The driver's management facade had no notion of timeout tiers, and now does.**
  `EvitaClientManagement` took `ClientTimeoutOptions.timeout` — the **unary, whole-call** budget, 5 s by
  default — for every call it made, including `fetchFile`. So a default-configured `EvitaClient` could
  not fetch a file that took longer than 5 s whatever the server did, and every test missed it because
  the harness and each test build clients with `.timeout(10, MINUTES)`. `EvitaClientSession` had the
  two-tier regime all along; the facade was simply never given it.

  Rather than fix the call site, the tier is now carried by `EvitaClientChannel.TimeoutTier` and paired
  with each stub at construction, where the channel is in scope — the same seam that already makes
  "streaming stub off the retrying channel" not compile (#1388). A management method cannot pick a tier,
  and therefore cannot pick the wrong one. `EvitaClient.resolveTimeout` keeps an explicit
  `executeWithExtendedTimeout` override winning over both tiers, since that API's whole purpose is to
  name a duration for the work inside it.

  `fetchFile` needed all three of its caps addressed, not one: the gRPC deadline (now `PER_MESSAGE`), a
  missing per-message `setResponseTimeout` re-arm in `onNext`, and `downloadFuture.get(timeout)` — an
  independent whole-download budget on the calling thread, now a stall-based wait recomputed from the
  last message. A plain unbounded `get()` was rejected: it would hang forever on a stream that never
  produces a terminal event, and a spurious timeout is recoverable where a parked application thread is
  not.
- **`GrpcFetchFileRequest` still cannot resume.** It carries only `fileId`, so any retry restarts at
  byte 0. An additive `optional int64 offset = 2` is wire-compatible both ways, but *silently* so — an
  old server ignores it and streams from byte 0 with no error — so it needs an echo field on the
  response for the client to confirm the offset was honoured.
- **The client-streaming `RestoreCatalog` is covered again, but not for the invariant we assumed.**
  Rerouting the driver to `RestoreCatalogUnary` had left nothing driving the ordered chain end to end.
  `LongRunningGrpcRestoreCatalogUploadTest#shouldRestoreCatalogUploadedThroughClientStreamingRpc` closes
  that: a 549 KB backup — deliberately *under* `SERVER_REQUEST_LIMIT`, since a client-streaming upload
  is one request — pushed through the raw `EvitaManagementServiceStub` in ~17 messages, restored, and
  every payload compared byte for byte. The await that made this awkward turned out to be a non-problem:
  `EvitaClientManagement.createTask(TaskStatus)` is public, so the test converts the response's
  `GrpcTaskStatus` and gets a real `ClientTaskTracker`-backed future — no sleep-polling.

  **What calibrating it actually found is worth more than the test.** The intended counterfactual —
  unchaining `submitRestoration` from `onCompleted` — *does not fail*. Demand for chunk N+1 is issued
  from inside the **completed** write step for chunk N, so the demand protocol both orders the writes
  and withholds half-close until the last one has landed. The chain and `disableAutoRequest()` +
  `request(1)` are **redundant**, and removing either alone is unobservable. Removing *both*, with a
  2 ms widener in the write step, also stayed green at nine chunks — a failure to detect, not evidence
  of safety.

  Two things follow. First, the ordering invariant is **still unverified**; what the new test
  demonstrably guards is the hand-issued demand protocol, whose removal stalls the upload dead and fails
  the 30 s latch deterministically. Second, the `@Blocking` rejection above is weaker than it reads:
  with one-message-at-a-time demand, `AbstractServerCall`'s dispatch assumption is no longer the thing
  ordering rests on. The rejection stands on the remaining ground — an explicit chain over an
  undocumented scheduling property — but it is no longer the sole guarantee, and the class JavaDoc on
  `RestoreCatalogUploadObserver` now says so.

  The deprecation option is still open and still deliberate: gRPC-Web uses the unary RPC (the proto says
  so) and the driver now does too, so the client-streaming variant has **no known first-party client**.
  Keeping it costs one long-running test; that seems the right trade while third-party clients may exist.
- The long-running restore counterpart exercises tens of chunks, not thousands. The scale is not
  covered.

## Related work

- `2026-08-05-streaming-calls-must-not-be-retry-decorated` — same streaming call sites; that record
  explains why the driver must not retry them, this one why the server must pace them.

## Timeline

- **2026-08-24** — diagnosis confirmed by measurement, issue #1441 filed
- **2026-08-24** — implemented and verified
