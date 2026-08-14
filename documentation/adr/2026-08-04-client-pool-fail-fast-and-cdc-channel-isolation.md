---
title: Fail fast on client pool saturation and never run consumer callbacks on the submitting thread
date: 2026-08-04
updated: 2026-08-05 09:45
status: accepted
kind: fix
issues: [1387]
prs: [1389]
areas: [evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver, evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/cdc, evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/exception, evita_api/src/main/java/io/evitadb/api/configuration]
supersedes: []
superseded-by: []
relates: [2026-08-03-driver-connection-resilience, 2026-08-04-http2-connection-teardown-observability]
---

# Fail fast on client pool saturation and never run consumer callbacks on the submitting thread

The gRPC Java driver's shared thread pool used `ThreadPoolExecutor.CallerRunsPolicy`. Once the pool
saturated, rejected tasks ran inline on whatever thread submitted them — and change-data-capture
teardown tasks are submitted from the Armeria event loop. The event loop then executed driver work
that blocks waiting for an inbound message, i.e. a message only that same thread could have read.
The result was not a slowdown but the total, unrecoverable death of the HTTP/2 connection. The pool
now rejects with a driver exception, every teardown path survives that rejection, consumer callbacks
are dispatched off the submitting thread unconditionally, and CDC streams run on their own channel.

## Why

Observed live on a deployment whose CDC subscriber entered a re-subscribe storm: every worker thread
of the pool **and the single Armeria event loop thread** were parked in
`ClientChangeCaptureSubscriber.awaitAcknowledgement` with stacks 700–840 frames deep, repeating
every 21 frames. No inbound frames were read at all, so every outstanding and future call on that
connection failed on timeout. The re-subscribe storm is a client-application concern; what turned a
recoverable storm into a permanently dead transport is the driver behaviour recorded here.

The constraint that makes this non-obvious: `CallerRunsPolicy` is the textbook way to apply
backpressure on a thread pool, and it is correct in a *server* where the submitter is a request
thread you own. It cannot be correct in a **client library**, which does not control who submits.
When the submitter is an event loop, "backpressure" means handing arbitrary library work to the one
thread that reads the connection. A client normally has exactly one event loop per endpoint —
Armeria's `DefaultEventLoopScheduler.DEFAULT_MAX_NUM_EVENT_LOOPS` is `1` and `HttpChannelPool` is
instantiated per event loop — so blocking it takes everything down, not just the caller.

### Previous state

`EvitaClient` built its pool with `CallerRunsPolicy` and a `queueSize`-bounded backlog. Two CDC
teardown sites submitted into it (`ClientSubscription.cancel`, `ClientChangeCaptureSubscriber.close`),
as did the terminal delegate notifications (`onError`, `onComplete`, the client-failure path) and the
per-item drain in `ClientSubscription.consume`. All of them assumed the submission succeeded, which
under `CallerRunsPolicy` it always did — by running the task on the caller. `cancel()` alone had a
`catch (Throwable) → runnable.run()` fallback, added when the concern was a shut-down pool.

CDC and ordinary request/response traffic also shared one `ClientFactory`, hence one connection and
one event loop.

## Options considered

### Option A — Fail fast, and give capture callbacks their own executor (chosen)

Replace the policy with a handler that throws `EvitaClientPoolSaturatedException`. Split every
rejected task into two classes and treat them differently: **driver-internal cleanup** completes
inline; **consumer callbacks** never run on the submitting thread, and are carried by a
**dedicated capture callback executor** rather than the shared client pool. When even that executor
refuses, the affected subscription is terminated rather than the callback being relocated anywhere.

- **Pros:** the bounded queue remains the backpressure mechanism, and its far end is now a named,
  actionable exception. No path can hand consumer code to the event loop. Because captures no longer
  share the pool with ordinary query work, general query load can no longer refuse a capture callback
  at all — which removes the *trigger* of #1387 rather than only its consequence. Refusal costs
  nothing: no threads are created on an already-struggling JVM.
- **Cons:** a second pool, and one more exception type. The pool is created lazily on the first
  capture subscription, so a client that never subscribes pays nothing, and `allowCoreThreadTimeOut`
  returns its threads once captures go quiet.

**An earlier revision of this record chose a one-shot rescue thread per refused callback instead.**
That was wrong and is superseded by the executor split above: nothing bounded those threads, and
because the capture drain re-submits itself, sustained saturation meant *unbounded thread creation*.
It traded a capture outage for a process-wide one — a worse failure than the one being fixed. The
reasoning that motivated it stands (a terminal `onError`/`onComplete` that never arrives leaves the
consumer believing a dead subscription is alive), but the remedy for that is to **fail the
subscription loudly**, not to manufacture threads.

### Option B — Fail fast, and run *all* rejected cleanup inline (declined)

This is what issue #1387 §2 literally prescribes: catch the rejection at both teardown sites and
"perform the cleanup synchronously in place."

- **Pros:** no extra threads; deterministic; simplest possible fallback.
- **Rejected because:** it is only safe at one of the two sites. `ClientSubscription.cancel`'s
  runnable is genuinely driver-internal, but `ClientChangeCaptureSubscriber.close`'s submission
  carries the **consumer's** `close` callback, which commonly re-subscribes. Running it in place
  reproduces the exact chain in the issue's own stack trace — `close(:572)` → rejected →
  run-on-caller → consumer re-subscribes → `subscribe()` → `awaitAcknowledgement()` — with an
  explicit `catch` standing in for `CallerRunsPolicy`. The issue's justification ("the fallback is a
  local close, not the re-entrant path into `subscribe()`") holds for `cancel()` and is false for
  `close()`. The same objection applies to the three terminal delegate notifications.

### Option C — Fail fast only, no teardown resilience (declined)

- **Pros:** smallest diff; removes the deadlock outright.
- **Rejected because:** it trades a loud deadlock for a *silent* permanent outage, which is strictly
  worse. A consumer that catches a `subscribe()` failure and calls `cancel()` from its error handler
  would hit a second exception thrown from inside that handler — the pool is still saturated, which
  is precisely the state that produced the first failure. The consumer's remaining cleanup never
  runs, a dead subscriber stays registered, and its own "recreate if missing" guard then sees a
  non-null subscriber and skips recovery forever. No exception surfaces and no log line is emitted.
  A consumer cannot defend against this, because the throw originates inside the handler that exists
  to clean up.

### Option D — Per-session channel affinity instead of a dedicated CDC channel (declined)

- **Pros:** would keep every call of one session on one connection, preserving HTTP/2 ordering by
  construction rather than by a gate.
- **Rejected because:** Armeria's extension point
  `EventLoopScheduler.acquire(sessionProtocol, endpointGroup, endpoint)` receives no request context,
  so it cannot key on a session id. Emulating affinity would require N endpoint groups with hashing
  plus hand-rolled lifecycle and failover — disproportionate to the benefit. **Revisit if** Armeria
  ever passes request context to the scheduler.

## Decision

**Chosen: Option A**, with C's fail-fast core and B's inline fallback kept exactly where it is safe.

The rule the code now follows, and which future changes must not break:

> Driver-internal cleanup may run on the rejecting thread. **Consumer callbacks never may.**

`CallerRunsPolicy` would win again only if the driver could guarantee that no event loop ever submits
to the shared pool — that would need CDC dispatch fully decoupled from the shared executor, at which
point this record should be superseded.

Item ordering deviates from the issue, which asks for 1+2, then 4, then 3. §4's own note concedes
that §3's regression test is a prerequisite for moving CDC onto a separate connection, so the test
landed before the split.

## Key technical details

- `EvitaClientRejectingExecutorHandler` — the pool's rejection handler. Distinguishes saturation
  from shutdown (`executor.isShutdown()`); both throw, because `CallerRunsPolicy` *silently
  discarded* post-shutdown submissions and that silence is what the teardown fallbacks now rely on
  noticing. The saturation message names both operator knobs (`maxThreadCount`, `queueSize`).
- `EvitaClientPoolSaturatedException` — extends `EvitaInvalidUsageException`, deliberately **not**
  `RejectedExecutionException`. Consumers commonly catch the latter around submissions to their own
  schedulers to detect shutdown; inheriting from it would make driver-side saturation
  indistinguishable from a consumer's scheduler stopping, and the real condition would be logged as
  benign and swallowed.
- `CdcCallbackDispatcher.dispatch` — the single seam enforcing the invariant above. Every consumer-facing
  submission goes through it: `onError`, `onComplete`, the client-failure path, the delegate `close`,
  the per-item drain in `ClientSubscription.consume`, and `HeartBeatSensor.onHeartBeat`. The last one
  is easy to miss and was the sharpest remaining edge — `onHeartBeat` is a public SPI invoked straight
  from `onNext`, i.e. **on the event loop**, and its entire purpose is to let a consumer notice a stale
  stream and re-establish it. That is the #1387 re-entrance exactly, on the connection this change just
  carved out. The driver's own heartbeat gap detection deliberately stays inline: it is non-blocking and
  depends on the inbound thread's ordering.
- **Heartbeats need ordering as well as off-thread delivery**, and that combination needed its own
  primitive. A `HeartBeatSensor` derives its missed-heartbeat count from the continuity of
  `HeartBeat.index()`, so dispatching each notification independently onto the multi-threaded pool
  would manufacture phantom gaps — `LongRunningCdcHeartbeatTest` asserts exactly zero missed
  heartbeats and would have started failing. `SerialCdcExecutor` (queue + single active drain, the
  drain itself submitted through `CdcCallbackDispatcher`) gives both guarantees: never on the
  submitter, at most one at a time, in submission order. When the executor refuses the drain it does
  **not** retry or fall back: it terminates, discards its backlog and fails the owning subscription,
  because resuming later would leave a gap indistinguishable from missed server heartbeats. Guava's
  `MoreExecutors.newSequentialExecutor` is the same primitive and was the first choice, but **rejected
  because Guava is only a transitive dependency of the driver (via gRPC), not a declared one** —
  building on it would harden an accidental dependency into a real one.
- One consequence is recorded on `HeartBeatSensor.onHeartBeat` itself, which is where a consumer
  looks: the callback is now asynchronous, so the acknowledgement heartbeat may still be in flight
  when `subscribe()` returns, and state written by the sensor must not be read immediately after
  subscribing.
- The dispatcher wraps every callback so consumer code that throws is logged rather than escaping to
  a thread's default handler, and it distinguishes a saturated executor from a closing one — logging
  "widen your executor" on an ordinary shutdown would be actively misleading.
- **`EvitaClient.cdcCallbackExecutor()` — the capture callback executor.** Separate from the shared
  client pool, created **lazily** on the first capture subscription (most clients never open one) with
  `allowCoreThreadTimeOut` so an idle subscription holds no threads. Its threads are
  `EvitaClient.CdcCallbackThread`, a named type rather than a name prefix, so the driver can recognise
  "I am on a capture callback" without parsing thread names — `close()` uses it to avoid awaiting the
  executor from inside one of its own tasks.
- **Saturation logging is rate-limited** to one report per 10 s, with the suppressed count carried in
  the message. Saturation arrives in storms, so an unthrottled `log.error` per rejection would add log
  and disk-IO pressure to a client that is already struggling — one flood becoming two.
- `ClientSubscription.cancel` keeps its inline fallback — the runnable is driver-local, and the
  consumer-facing work reachable from it (`internalSubscriber.close()` → delegate `close`) is
  dispatched off-thread at its own submission site. `IOUtils.closeSafely(Runnable...)` swallows
  rather than rethrows, so `onCloseCallback` runs even if the subscriber's close throws — without
  that, a rejected cleanup would leave the subscription in `subscriptions` and the publisher would
  never auto-close.
- `consume()` no longer rethrows a failed dispatch. It is reached from `produce()`, i.e. from a gRPC
  inbound callback, which has no defined error path. When the dispatch fails outright *and* the
  subscription is already doomed, the teardown completes in place rather than waiting for a retry that
  cannot come: `produce()` early-returns once `walkingDead` is set, and on the healthy path gRPC credit
  is only restored from inside the drain loop, so the server stops pushing after `queueSize` messages.
- **CDC channel isolation:** `EvitaClient.cdcClientFactory` is a second `ClientFactory` built from
  the same `ClientFactoryBuilder` with its own **single-thread** event loop group.
  `ClientFactoryBuilder.build()` snapshots its options, which is what makes re-pointing the worker
  group between the two `build()` calls safe. Both factories are closed in `EvitaClient.close()`;
  each owns its group via `shutdownOnClose = true`. One thread is not a thrift: Armeria assigns one
  event loop per endpoint (`DefaultEventLoopScheduler.DEFAULT_MAX_NUM_EVENT_LOOPS` is `1`) and the
  driver talks to exactly one endpoint, so any further threads would never be used. The isolation comes
  from the group being *distinct*, not from its size — and because that one thread serves every capture
  stream on the client, nothing that blocks may run on it.
- **The load-bearing invariant, written down at last:**
  `EvitaClientSession.registerChangeCatalogCapture` is the one session-bound call that does not
  block — it uses the async stub and returns immediately. Ordering holds *only* because its sole
  caller, `ClientChangeCapturePublisher.subscribe`, gates it with a blocking
  `awaitAcknowledgement()`. Session calls are serialized **by one deliberate gate, not by
  construction.** Moving CDC to its own connection makes that gate load-bearing in a second way:
  HTTP/2 guarantees ordering only *within* a connection, so the session-bound capture call now
  travels a different connection from the rest of its session. Any new asynchronous session API must
  block on its own acknowledgement or be gated the same way. `closeNow` / `closeNowWithProgress` are
  a second ungated async path, acceptable only because the session is being torn down.

## Verification

`ClientChangeCapturePublisherTest`, `CdcCallbackDispatcherTest`, `SerialCdcExecutorTest`,
`EvitaClientCdcChannelIsolationTest` and `EvitaClientRejectingExecutorHandlerTest` — **35 tests, all
passing**. The full `driver` tag selection is **276 tests, 0 failures, 1 skipped**, and exercises the
split CDC channel against real embedded servers, including the session-bound
`registerChangeCatalogCapture` that would break outright if the second connection mis-wired session-id
propagation.

Every consumer-callback dispatch site is pinned by a test asserting the callback did *not* run on the
delivering thread. The guards were confirmed to be real rather than tautologies by reverting the fix
and observing failures — reverting `SerialCdcExecutor`'s terminate-on-refusal to a silent flag release
fails three tests:

- `SerialCdcExecutorTest.shouldTerminateTheSubscriptionWhenTheDrainIsRefused` — `expected: not <null>`,
  i.e. the owner was never told the callback could not be delivered;
- `SerialCdcExecutorTest.shouldReportTheFailureOnlyOnce` — `expected: <1> but was: <0>`;
- `ClientChangeCapturePublisherTest.shouldFailSubscriptionWhenExecutorRefusesAHeartBeat` — the
  subscription silently stayed open with its heartbeats dropped.

All three pass once restored. `EvitaClientCdcChannelIsolationTest` pins the Armeria builder-snapshot
semantics the channel split rests on (see *Key technical details*) — a claim previously asserted in
prose and flagged in review as unverified, now both source-checked and executable, so an Armeria
upgrade that changed it would fail the build rather than silently re-merge the two event loops.

`shouldBlockSubscribeUntilAcknowledgementArrives` pins the acknowledgement gate directly: it asserts
the `subscribe()` thread is still alive 500 ms after the stream initializer has run and only
completes once the ACK is delivered.

**The re-check in `SerialCdcExecutor.drain()`'s `finally` is guarded, but not from the fast loop.**
Removing it was measured — the entire functional suite still passes. Reaching the window it protects
requires a submission to land between the `poll()` that returned null and the `draining.set(false)` two
lines below, which no functional test can place deterministically. `SerialCdcExecutorTest` pins the
adjacent, reachable case (a task enqueued while the drain is still running) and is named for it; it was
previously named for the unreachable one, which is how the gap went unnoticed.

The window itself is covered by `LongRunningSerialCdcExecutorStressTest`, which sweeps the arrival time
of a competing submission across the drain's start-up over 200 000 independent rounds. Calibrated both
ways: with the re-check in place all rounds pass in **~2 s**; with it removed the first callback strands
around **round 20 000** and the run hits its 10-strand cap by round 27 000. It is `@Disabled` and lives
in `evita_long_running_tests` deliberately — a probabilistic test in the fast loop fails once every few
hundred CI runs and trains people to press re-run, whereas the same test on a quiet machine, run on
purpose after touching the drain loop, is evidence. **A stress test whose counterfactual stops failing
has become decorative**, which is why the calibration numbers are recorded rather than left implicit.

Test waits follow one rule, since the suite runs in parallel forks that contend for CPU: **positive
liveness waits are latch-based and generous (30 s), negative ones stay short (250 ms)**. A generous
positive bound costs nothing on a passing run — the latch returns the moment the work completes — and
a short negative bound is safe in the other direction, because a loaded machine only makes "this did
*not* happen" more likely to hold. The one remaining `Thread.sleep` (1 ms, inside a task in
`shouldRunAtMostOneTaskAtATime`) is a detection widener rather than a synchronisation device: it makes
an overlap easier to observe and cannot cause a false failure.

The premise underpinning the whole change was verified against Armeria 1.40.0 sources rather than
assumed: `ArmeriaClientCall` uses `MoreExecutors.directExecutor()` when `callOptions.getExecutor()`
is null (which it is — the driver sets no call executor), so `listener.onMessage` runs on the thread
draining the deframed response stream, and that thread is the event loop.

## Consequences & open follow-ups

- **Callers can now see a new exception.** `EvitaClientPoolSaturatedException` escapes from any
  submission path, CDC or not — the mechanism does not distinguish them. Because §1 and §2 shipped
  together, the broad error handling consumers already have around `subscribe()` absorbs it and no
  downstream change is required.
- **`io.evitadb.driver.exception` is still not exported by `module-info.java`.** Pre-existing for the
  whole client exception family (`EvitaClientTimedOutException`, `EvitaClientServerCallException`),
  but it is weaker than intended here, since this exception's stated purpose is that consumers can
  distinguish it. Modular consumers must catch `EvitaInvalidUsageException` instead. Deliberately
  left alone: exporting the package widens the module's public API for six other types too, which is
  a maintainer call rather than a side effect of a fix.
- **A refused capture callback terminates its subscription, and the consumer is told.** There is no
  rescue thread and no retry: `CdcCallbackDispatcher.dispatch` reports the refusal, the driver-internal
  half of the teardown runs inline (safe — local, non-blocking, non-re-entrant), and the consumer
  receives `EvitaClientPoolSaturatedException` through the ordinary terminal path. This is deliberately
  loud, because the alternative — dropping the callback — re-creates Option C's silent outage. For
  heartbeats specifically, silently resuming after a dropped notification would be *worse* than
  failing: consumers derive missed-heartbeat counts from `HeartBeat#index()` continuity, so a hidden
  gap reads as missed **server** heartbeats when in fact the driver dropped them.
- **Capture callbacks are on their own executor, so query load can no longer refuse them.** Refusal
  therefore means what it says — the consumer's own callbacks are not keeping up — rather than
  "something unrelated is busy". This is what makes terminating the subscription the proportionate
  response rather than a harsh one.
- **The refusal is propagated, never re-created.** `CdcCallbackDispatcher.dispatch` returns the
  `Throwable` the executor threw (NULL when accepted) rather than a boolean, and both call sites hand
  *that* object to the consumer. This is a contract, not a style choice: `EvitaClientPoolSaturatedException`
  has two constructors carrying operationally opposite messages — the saturation one names the
  `maxThreadCount`/`queueSize` knobs, the no-arg one says the client is shutting down and names no
  remedy. A `boolean` return forced the caller to *pick* one, and the first implementation picked the
  shutdown wording for every refusal, so the one consumer this exception exists to inform — an operator
  whose capture callbacks are overloaded — was sent looking for a shutdown that never happened. Guarded
  by `CdcCallbackDispatcherTest`, `SerialCdcExecutorTest` and `ClientChangeCapturePublisherTest`, each
  asserting the message survives rather than only the type.
- **`queryCatalogAsync` now throws synchronously on saturation** instead of running the query on the
  caller. This is the only path on which the new exception reaches a consumer, and it brings the
  driver into line with embedded evitaDB, whose own rejecting handler throws the same way — the type
  differs deliberately (see above). Documented on the method.
- **Heartbeats are no longer ordered against terminal signals.** Introduced here, not pre-existing:
  `onHeartBeat` used to run inline on the inbound thread while `onError`/`onComplete` were queued to
  the pool, so a heartbeat always happened-before a later terminal. It now travels
  `SerialCdcExecutor`'s own queue, so a delegate implementing both `Flow.Subscriber` and
  `HeartBeatSensor` can observe a heartbeat *after* the terminal signal. Reactive Streams does not
  forbid this — the two are separate SPIs — and the alternative, funnelling terminal notifications
  through the same serializer, was rejected because a sensor that re-subscribes blocks until the
  server acknowledges, which would delay every consumer's error handling to spare a rare reordering.
  The tolerance requirement is documented on `HeartBeatSensor#onHeartBeat`. **Revisit if** a consumer
  is found resurrecting a terminated subscription from a late heartbeat; the fix would be a
  terminated-flag check inside the heartbeat task, not a merged queue.
- **`EvitaClient#close()` orders the teardown deliberately: publishers first, then the capture
  executor drained, then `shutdownNow()`.** Closing the publishers is what *dispatches* every live
  subscription's terminal notification, so tearing the executor down first — or going straight to
  `shutdownNow()`, which discards queued tasks — would silently swallow exactly the notifications the
  dispatcher exists to guarantee. The drain window is bounded (5 s) and is skipped entirely when
  `close()` is itself called from a capture callback, since awaiting termination there would wait on
  the calling task. Do not reorder without re-reading `EvitaClient#close()`'s contract.

## Related work

- `2026-08-03-driver-connection-resilience` — same driver, adjacent failure mode: keep-alive timing
  and retrying provably-unprocessed calls. That record addresses connections dying from timing; this
  one addresses a connection dying from thread capture.
- `2026-08-04-http2-connection-teardown-observability` — the server-side counterpart for noticing
  HTTP/2 connections going away.

## Timeline

- **2026-08-04** — CDC starvation investigated on a live deployment; event-loop capture identified
  via JDWP thread dumps (64 threads, 713–839 frames, 21-frame cycle)
- **2026-08-04** — issue #1387 filed, implemented and verified
