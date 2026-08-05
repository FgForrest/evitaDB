---
title: Fail fast on client pool saturation and never run consumer callbacks on the submitting thread
date: 2026-08-04
updated: 2026-08-05 01:25
status: accepted
kind: fix
issues: [1387]
prs: []
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

### Option A — Fail fast, and move consumer callbacks off-thread (chosen)

Replace the policy with a handler that throws `EvitaClientPoolSaturatedException`. Split every
rejected task into two classes and treat them differently: **driver-internal cleanup** completes
inline, **consumer callbacks** move to a one-shot rescue thread.

- **Pros:** the bounded queue remains the backpressure mechanism, and its far end is now a named,
  actionable exception. No path can hand consumer code to the event loop.
- **Cons:** introduces an ad-hoc thread on a saturated pool, and one more exception type.

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
  submitter, at most one at a time, in submission order — and ordering survives pool saturation,
  because the rescue thread is still the *single* active drain. Guava's `MoreExecutors.newSequentialExecutor`
  is the same primitive and was the first choice, but **rejected because Guava is only a transitive
  dependency of the driver (via gRPC), not a declared one** — building on it would harden an
  accidental dependency into a real one.
- One consequence is recorded on `HeartBeatSensor.onHeartBeat` itself, which is where a consumer
  looks: the callback is now asynchronous, so the acknowledgement heartbeat may still be in flight
  when `subscribe()` returns, and state written by the sensor must not be read immediately after
  subscribing.
- The dispatcher wraps every callback so consumer code that throws is logged rather than escaping to
  stderr via a rescue thread's default handler, and it distinguishes a saturated pool from a closing
  one — `EvitaClient.close()` shuts the pool down before tearing down the transport, so every live
  capture stream reports its terminal notification through the rejection path. Logging "widen your
  pool" on an ordinary shutdown would be actively misleading.
- **Rescue-thread bound.** At most one rescue thread per subscription is in flight for the drain (the
  `currentlyConsuming` CAS), plus one terminal notification and one delegate close. It is *not*
  bounded over time: the drain tail-calls `consume()`, so sustained saturation creates a fresh
  short-lived thread per drain cycle. That churn is deliberate — it is why the rejection is logged at
  `warn`, since a saturated client pool is a condition to fix, not to ride out.
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

`ClientChangeCapturePublisherTest`, `CdcCallbackDispatcherTest` and
`EvitaClientRejectingExecutorHandlerTest`, 25 tests, all passing. The full `driver | cdc` tag
selection is **693 tests, 0 failures, 1 skipped** — which exercises the split CDC channel against
real embedded servers, including the session-bound `registerChangeCatalogCapture` that would break
outright if the second connection mis-wired session-id propagation.

Every consumer-callback dispatch site is pinned by a test asserting the callback did *not* run on the
delivering thread. Two of them were confirmed to be real guards rather than tautologies, by reverting
the fix and observing the failure:

- reverting `CdcCallbackDispatcher.dispatch` at `ClientChangeCaptureSubscriber.close` to a bare
  `executorService.execute(...)` makes `shouldCloseDelegateOffCallingThreadWhenPoolRefusesTheTask`
  fail — the delegate is never closed (5 s latch timeout);
- reverting the `onHeartBeat` dispatch to a direct call makes
  `shouldDeliverHeartBeatOffCallingThreadWhenPoolRefusesTheTask` fail with
  `expected: not same but was: Thread[...]`, i.e. the sensor ran on the delivering thread.

Both pass once restored. The `cancel()` teardown assertion would have passed before this change
(`cancel()` already carried an inline fallback) and is kept as a regression guard.

`shouldBlockSubscribeUntilAcknowledgementArrives` pins the acknowledgement gate directly: it asserts
the `subscribe()` thread is still alive 500 ms after the stream initializer has run and only
completes once the ACK is delivered.

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
- **Rescue threads are unbounded over time** (see the bound above). Under sustained saturation the
  drain creates one short-lived thread per cycle. A shared single-thread rescue executor would cap
  that, and was considered — rejected because one blocking consumer callback would then head-of-line
  block every other subscription's rescue work, which is the failure this change exists to prevent.
  **Revisit if** the churn shows up in production profiles; the fix would be a small bounded pool
  rather than a single thread. Note that *dropping* a terminal callback re-creates the silent outage
  of Option C, so any cap must be loud.
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
- **CDC still shares the `executor` with query traffic.** The channel is isolated; the dispatch pool
  is not. Query load can therefore still saturate the pool that CDC dispatch uses, which is what the
  rescue thread now absorbs. A dedicated CDC dispatch executor would remove the rescue path
  entirely — deferred as scope beyond the issue.

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
