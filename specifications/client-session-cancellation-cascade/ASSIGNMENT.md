# Assignment — random CANCELLED calls under load + client-session cancellation cascade

Audience: the implementation session (any model). Root-caused 2026-07-14; every link below is
verified in sources (Armeria sources at `/www/oss/armeria/`) or in the full-module test log. This is
a SEPARATE work stream from `specifications/stale-leaf-page-twin-other-indexes/` — do not touch
anything that CHANGE_PROPOSAL covers.

## Root cause (verified, read this first)

The months-old "random CANCELLED gRPC calls under load" and the new `ConcurrentSessionAccessException`
(CSAE) cascade in `EvitaClient*Test` share one causal chain:

1. **In test runs, evita executes gRPC business logic directly on Armeria event-loop threads —
   through its own executor configuration, not through Armeria's dispatch default.** (CORRECTED
   2026-07-14 after the implementation session's challenge — the original claim blamed the missing
   `useBlockingTaskExecutor(true)`; that reading was wrong.) All 43 session/evita service methods DO
   offload their body via `EvitaSessionService.executeWithClientContext` (:183-214) to
   `evita.getRequestExecutor()` — the offload design is correct. But
   `ServerOptions.DEFAULT_DIRECT_EXECUTOR = DevelopmentConstants.isTestRun()` (`ServerOptions.java`),
   and with `directExecutor=true` the Evita constructor (`Evita.java:373-381`) replaces the request
   pool (and the scheduler) with `ImmediateExecutorService`, which runs every task synchronously ON
   THE CALLING THREAD — i.e. the event loop that invoked the service method. So in EVERY test-run
   JVM the whole business call (multi-second schema updates during dataset setup, warm-up flush at
   close, large queries) executes on the event loop. Empirical proof: the CSAE names TWO event-loop
   threads (`armeria-eventloop-epoll-10-5` attempted a call while `armeria-eventloop-epoll-10-2` was
   still executing). In production (`isTestRun()==false`) the offload is real and the loop-stall
   lane narrows to GC pauses, CPU starvation, and request/response frame decode+encode. Side note:
   `ServerSessionInterceptor` (:211) calls `session.isActive()` on the event loop, but the proxy
   handles `isActive` in its housekeeping branch WITHOUT acquiring the ownership guard, so the
   interceptor is never a CSAE party.
2. **An HTTP/2 connection is pinned to one event loop.** While that loop executes a slow evita call
   (catalog-schema update during dataset setup, large upserts, warm-up flush at close — seconds under
   load), it processes NO other frames for its connections — including PING acks.
3. **Both sides run a 1-second keep-alive with a 1-second ack deadline.** Client:
   `EvitaClient.java:391` `pingIntervalMillis(1000)`; server: `ExternalApiServer.java:599` the same.
   Armeria semantics (`AbstractKeepAliveHandler.java:320`): after ~1s of quiet (a pending unary call
   with no data flowing IS quiet) a PING is written, and a **connection shutdown is scheduled
   `pingIntervalMillis` later**, cancelled only by the ack (`Http2KeepAliveHandler.java:99-101` —
   "shutdownFuture cannot be cancelled because of late PING ACK").
4. **Self-inflicted kill:** the client waits on a slow call; after 1s of quiet it pings; the server's
   event loop is busy executing that very call and cannot ack; after ~1 more second the client KILLS
   ITS OWN CONNECTION. The in-flight RPC fails with `ClosedSessionException` → the driver maps it to
   `GenericEvitaInternalError: CANCELLED`. Any gRPC call occupying the event loop for more than
   ~2 seconds is at risk — no overload required; load (jacoco, parallel dataset setups, GC pauses)
   just makes it frequent and "random". Request/response timeouts are IRRELEVANT to this lane —
   raising them to 10 minutes changed nothing, by design.
5. Same mechanism in reverse (server pings, client JVM stalls in GC → server closes → GOAWAY) is the
   likely source of the `SystemRestStreamingFunctionalTest` GoAway flake family.
6. **The new CSAE cascade is the aftershock:** after a CANCELLED, the server-side invocation keeps
   running (orphan) and still owns the session; the driver deadifies the session locally ONLY on
   `Code.UNAUTHENTICATED` (`EvitaClient.transformStatusRuntimeException`, ~:303), so `isActive()`
   stays true and the try-with-resources close goes REMOTE (`closeWhen` → `closeNowWithProgress`
   streaming call on a fresh connection) and races the orphan → the `EvitaSessionProxy` owning-thread
   guard throws CSAE. The guard is CORRECT: pre-guard this same race silently interleaved a close
   (which pops trapped changes / flushes in warm-up) with a still-running mutation — a real
   corruption vector. Do NOT exempt close from the guard.

## Part A — keep slow work off the event loops / survive stalls (the actual CANCELLED fix)

REWRITTEN 2026-07-14 after the directExecutor discovery (Root cause §1): the keep-alive tightness
is now the PRIMARY lever; the execution-model change targets the test lane specifically. The
original step 1 (`useBlockingTaskExecutor(true)` pointed at `evita.getServiceExecutor()`) is
retracted — the service executor is the scheduler, the wrong pool, and the premise it rested on
was incomplete.

1. **Make the keep-alive survivable (primary; protects tests AND production).** Both hard-coded
   `pingIntervalMillis(1000)` values (`EvitaClient.java:~391` client side,
   `ExternalApiServer.java:~599` server side) must become configurable. Researched defaults
   (2026-07-14, owner-reviewed): **client 30 s, server 0 (ping disabled — the Armeria default)**.
   Rationale the implementer must preserve: Armeria couples the ack deadline to the interval
   (`AbstractKeepAliveHandler.java:320`) and a late ack cannot cancel the shutdown, so the interval
   IS the stall budget — it must absorb the worst realistic GC pause / CPU-starvation window, not
   act as a probe frequency. Ecosystem calibration: gRPC ships client keepalive DISABLED and
   recommends "not much below one minute" when enabled, with a DECOUPLED 20 s ack timeout; gRPC
   servers ping on a 2-hour cycle and police clients that ping more often than every 5 min without
   data (GOAWAY `too_many_pings`); Armeria itself ships `pingIntervalMillis=0` and reaps quiet
   connections via idle timeouts instead. Armeria's idle close is POLITE — it skips connections
   with requests in progress (`AbstractKeepAliveHandler.java:385`) — while the ping-ack shutdown
   closes unconditionally; disabling the server ping therefore removes a kill lane without losing
   cleanup (idle timeout + SessionKiller already reap). Constraints: the client ping interval must
   stay below both sides' `idleTimeoutMillis` (or the ping never fires before the graceful idle
   close) and below any LB/NAT idle window on the path; document that deployments behind
   third-party gRPC infrastructure (Envoy, nginx, managed LBs) may enforce the 5-minute ping
   policy and need the interval raised or the proxy configured. Present the residual trade-off
   (dead-peer detection ~2× interval vs stall tolerance) in RESULTS.
   Production context (owner-confirmed 2026-07-14): CANCELLED also occurs in PRODUCTION under load
   when GC churns CPU — with real request pools, so the executor options in step 2 do NOT address
   it; this step is the only production fix. Note that 1000 ms is the MINIMUM Armeria even permits
   (`ClientFactoryBuilder.MIN_PING_INTERVAL_MILLIS = 1000`), the ack deadline is not independently
   configurable (shutdown is scheduled `pingIdleTimeNanos` — the interval itself — after the PING
   write, and a late ack cannot cancel it once fired), and evita's write workloads are measurably
   GC-bound, so >1 s pauses are expected, not exceptional. BOTH sides ping today, so either side's
   deadline can kill the connection (two chances per stall) — the client-30s/server-0 defaults
   above resolve this asymmetrically, matching the ecosystem convention that pinging is the
   client's job while the server polices and reaps.
2. **Fix the test-lane execution collapse — Option 1 SELECTED by the owner (2026-07-14): web-API
   tests run with the original (real) executors.** Option 2 stays documented below as the rejected
   alternative — do not implement it.
   - *Option 1 (SELECTED):* in the test support that starts web-API servers
     (`EvitaParameterResolver` / server test harness), set `ServerOptions.directExecutor(false)`
     whenever web APIs are opened. `DEFAULT_DIRECT_EXECUTOR = isTestRun()` exists to make embedded
     tests deterministic; for network tests the client side is asynchronous anyway, so the
     determinism value is nil while the cost is proven (business calls caller-run on event loops,
     including the SessionKiller-relevant scheduler). Scope the change to web-API test setups —
     do NOT change the default for embedded/unit tests.
   - *Option 2:* `.useBlockingTaskExecutor(true)` on the `GrpcService` builder in
     `GrpcProviderRegistrar` (:77). This relocates the service-method invocation — and therefore
     the caller-run `ImmediateExecutorService` body — onto Armeria's blocking task executor. If
     pointed at evita's request pool it needs an adapter: `ServerBuilder.blockingTaskExecutor(...)`
     accepts only `ScheduledExecutorService` / `BlockingTaskExecutor`
     (`BlockingTaskExecutor extends ScheduledExecutorService`; `BlockingTaskExecutor.of(...)` wraps
     a `ScheduledExecutorService` only) — delegate `execute`/`submit` to the request pool and the
     unused `schedule*` methods to the `Scheduler`. Do NOT point it at `evita.getServiceExecutor()`
     (the scheduler — wrong pool). Beware the production double-dispatch (Armeria blocking pool →
     `executeWithClientContext` → request pool again) and the request-pool rejection semantics
     (`EvitaRejectingExecutorHandler` throws "Evita executor queue full"; there is no caller-run
     fallback, so a saturated pool rejects instead of stalling the loop).
3. Verify context propagation is unaffected by whichever option lands: `SessionIdHolder` /
   `ServerSessionInterceptor` metadata, `ObservabilityInterceptor` tracing, and the streaming
   responses (`closeNowWithProgress`, change capture).
4. **Regression proof (TDD):** a functional test with a deliberately slow gRPC call (a test service
   method or a large schema mutation taking >3s) against a client with `pingIntervalMillis(1000)`
   and a server under the current test-run defaults (directExecutor on). It must fail with
   CANCELLED before the fix and pass after.

## Part B — driver: treat transport failure as session loss

1. In the driver, when a session call fails at the TRANSPORT level (status `CANCELLED`,
   `UNAVAILABLE`, `DEADLINE_EXCEEDED` from the local deadline, or a cause chain containing
   `ClosedSessionException` / `ClosedStreamException`), mark the session dead locally — the same
   `closeInternally()` route the `UNAUTHENTICATED` branch takes — and SKIP the remote close: the
   server session's state is indeterminate for the client, and the server's `SessionKiller` reaps
   orphans on inactivity. The try-with-resources close in `EvitaClient.updateCatalog` must become a
   local no-op in this state.
2. Do NOT retry automatically, and preserve the original transport exception as the failure the
   caller sees (the current CANCELLED-mapped `GenericEvitaInternalError` loses the "connection died"
   semantics — consider mapping transport failures to a dedicated, documented client exception, e.g.
   `TransportException`/`EvitaClientServerCallException`, so callers can distinguish "server said
   no" from "connection died, outcome unknown"). State the at-most-once/at-least-once ambiguity in
   its javadoc: the orphaned server-side call MAY still complete after the client saw the failure.
3. **Regression proof (TDD):** simulate a mid-call connection drop (close the client factory's
   connection or use a fault-injecting decorator); assert the driver (a) surfaces the transport
   failure, (b) marks the session inactive locally, (c) sends NO further RPC for that session (no
   CSAE anywhere), and the server reaps the session.

## Part C — server guard: bounded-wait before CSAE (optional hardening)

On guard collision in `EvitaSessionProxy.invoke`, instead of throwing CSAE instantly, bounded-wait
for ownership release (pattern already in the proxy: `closingSequence.awaitFinish(500ms)` →
`SessionBusyException`) and throw only when the wait expires. This converts rare legitimate
collisions (e.g. an orphan finishing its last milliseconds) into a short serialization, while
genuine concurrent misuse still fails. Keep the wait short (≤500ms) and the thrown CSAE message
unchanged. Implement only if Parts A+B leave any observed collision in the full-module run;
otherwise record the decision to skip it in RESULTS.

## Verification / acceptance

1. Part A slow-call test + Part B connection-drop test green (both watched failing first).
2. `EvitaClientReadOnlyTest` + `EvitaClientReadWriteTest` fully green in a full-module
   `rtk mvn -pl evita_test/evita_functional_tests test` run — these 7 errors are the regression
   detectors for this whole work stream.
3. No new CSAE occurrences anywhere in the full-module log.
3a. **Owner mandate (2026-07-14): after the directExecutor change lands, rerun the ENTIRE unit +
   functional suite** — the change alters what every web-API test exercises (real pools introduce
   new interleavings that the caller-run mode serialized away). Triage any new failures against
   the known flake families before attributing them to this work.
4. Ground rules as usual: TDD; `rtk mvn` (never piped through grep/head); tabs; JavaDoc; no TODOs;
   no issue/spec references in code comments; `-Dtest.tag.policy=off` for targeted runs.

## Explicitly out of scope

- Anything in `specifications/stale-leaf-page-twin-other-indexes/CHANGE_PROPOSAL.md` (another agent
  is implementing it concurrently — coordinate only through the shared test suite staying green).
- Exempting close-family methods from the session guard (rejected — see Root cause §6).
- Armeria changes — the library behaves as documented; both fixes are evita-side configuration and
  driver state handling.
