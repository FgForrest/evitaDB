# Assessment request: test-lane execution model for the gRPC session-cancellation cascade

You are a senior reviewer. We need an objective recommendation on **how the test lane should execute
asynchronous work**, in the context of a fix for real "random CANCELLED / ConcurrentSessionAccessException"
failures in evitaDB. Below is everything you need; the repository is not available to you, so all load-bearing
facts are stated inline with `file:line` anchors for traceability only.

Please read the whole brief, then answer the **Decision** and **Sub-questions** at the end. Weigh test
*determinism* against production *fidelity* explicitly, and give a concrete, actionable recommendation (not a
menu). State your confidence and the assumptions your recommendation depends on.

---

## 1. System & test harness (background)

- **evitaDB** is an in-memory NoSQL database. It runs as an **embedded engine** and exposes remote **gRPC, REST and
  GraphQL** APIs (Armeria + gRPC-Java + graphql-java). A networked test boots a real server and drives it over the
  wire; an embedded test uses the engine in-process.
- **Test harness:** JUnit 5 with a custom `@DataSet` / `@UseDataSet` extension (`EvitaParameterResolver`). One
  dataset (a booted server + built catalog) is shared across many `@Test` methods to amortise setup.
- **Tests run in parallel and are CPU-saturating.** Surefire runs methods/classes concurrently; a full run pins all
  cores. This is the crucial operating condition for everything below.

### 1.1 The `directExecutor` determinism mechanism (the established philosophy)

`ServerOptions.directExecutor` defaults to `DevelopmentConstants.isTestRun()` — i.e. **`true` in every test JVM**.
When `true`, the engine swaps its real thread pools for immediate/caller-runs executors:

```
Evita.java:372   serviceExecutor = directExecutor
                     ? new Scheduler(new ImmediateScheduledThreadPoolExecutor())   // runs inline, synchronously
                     : new Scheduler(server().serviceThreadPool());                // real async pool
```

The request executor is swapped the same way. **Intent (design rationale, per the maintainers):** under
CPU-saturated parallel test execution, *any* genuinely asynchronous handoff (thread pool, scheduler, event-loop
deferral) becomes a source of flakiness — a scheduled task can be starved for hundreds of ms when all cores are
busy, so timing-dependent assertions fail intermittently. The deliberate policy is therefore:

> **Run the direct (synchronous) executor everywhere in tests, so async handoffs collapse to inline calls and tests
> are deterministic. The only exceptions are the few tests that specifically exercise asynchronous behaviour, which
> are written with `CompletableFuture`s and very generous timeouts.**

`directExecutor=false` (real pools) is the **production** configuration.

### 1.2 gRPC execution model and the keep-alive self-kill

- All 43 gRPC service methods offload their body to `evita.getRequestExecutor()`. With `directExecutor=true` that is
  an immediate executor, so the body runs **inline on the Armeria event-loop thread** that dispatched the call.
- **Keep-alive:** client and server each send an HTTP/2 PING every `pingIntervalMillis`; both are hard-coded to
  **1000 ms** (`EvitaClient.java:391`, `ExternalApiServer.java:599`). Armeria's minimum ping interval is 1000 ms
  (0 disables it). Armeria schedules a connection shutdown `pingIntervalMillis` after each PING, cancelled only by
  the ACK. If a slow service body **stalls the event loop** for >~1 s, the peer's PING goes unacked and it **kills
  its own connection** → `ClosedSessionException` → the driver maps it to `CANCELLED`.
- **The cascade → CSAE:** the server-side invocation orphaned by the drop keeps running; the driver, seeing
  CANCELLED, did *not* mark the session dead locally, so its try-with-resources close went **remote** and raced the
  orphan. A session is `@NotThreadSafe`; the `EvitaSessionProxy` guard is a **per-method-invocation** thread-ownership
  CAS (`EvitaSessionProxy.java:607`) that throws `ConcurrentSessionAccessException` (CSAE) when a second thread
  touches the session concurrently. That CSAE was the visible symptom: **7 failing `EvitaClient*Test` methods.**
- Note the mechanism is **test-specific**: because `directExecutor=true` collapses the body onto the event loop, a
  slow call stalls the loop and trips the 1 s ping. In production (real pools) the body runs off the event loop, so
  this exact lane is far narrower (only a GC pause or frame en/decode could stall the loop).

### 1.3 API regeneration on schema change (why one candidate fix backfired)

The GraphQL and REST layers rebuild their per-catalog API (endpoints + schema fields) when a catalog schema changes.
They do this by subscribing to the embedded change-data-capture stream:

```
GraphQLManager / RestManager  ->  evita.registerSystemChangeCapture(...).subscribe(refreshingObserver)
Evita.java:451  changeObserver = new SystemChangeObserver(..., serviceExecutor)   // dispatch on serviceExecutor
```

So with `directExecutor=true` the API rebuild runs **synchronously** during the schema-mutation call — a test that
mutates a schema then immediately hits the regenerated endpoint always finds it ready. With `directExecutor=false`
the rebuild is **async** and lags; the follow-up call can hit a not-yet-registered field/endpoint. This is
production-correct (production is async and clients tolerate it) but breaks tests that assume instant rebuild.

---

## 2. The fixes under consideration

Four changes were implemented on the branch. Three are uncontested; one (A2) is the subject of this assessment.

- **A1 — configurable keep-alive (production fix, uncontested).** `pingIntervalMillis` is now configurable on both
  client and server. Chosen defaults: **client 30 s, server 0 (disabled)**. Rationale: the 1 s interval is the stall
  budget; widening it (or disabling the server side) stops a transient stall from self-killing a healthy connection.
  Idle timeouts + a server-side session reaper still clean up abandoned sessions. Armeria accepts only `0` or
  `>= 1000` ms.
- **Part B — driver treats transport failure as session loss (uncontested).** On `CANCELLED`/`UNAVAILABLE`/
  `DEADLINE_EXCEEDED` (or a cause chain carrying Armeria's `ClosedSessionException`/`ClosedStreamException`), the
  driver marks the session dead **locally** and throws a dedicated `TransportException` instead of issuing a remote
  close — removing the close-vs-orphan race that produced the CSAE. Documents at-most/at-least-once ambiguity.
- **CDC ordering fix (uncontested; a real production bug).** The client's `subscribe()` for change-data-capture used
  to return before the server confirmed the subscription. Under real pools a caller that did
  `session.registerChangeCatalogCapture(...).subscribe(sub)` then immediately mutated the same session could (a) race
  the still-pending server-side registration (CSAE) or (b) fire the mutation before the subscriber was wired in (a
  missed event). Fix: client `subscribe()` now **blocks until the server ACK**. Verified strictly correct (the
  per-method ownership guard releases before the ACK is emitted). This is a production-path fix independent of the
  test-executor question.
- **A2 — the contested change.** Make `directExecutor` configurable (`ServerOptions.Builder.directExecutor(boolean)`)
  and have `EvitaParameterResolver` set **`directExecutor(false)` for every web-API-opening dataset**. Motivation at
  the time: "gRPC bodies shouldn't run on the event loop; real pools keep slow work off the loop, so the ping can't
  self-kill." In effect A2 makes networked tests run on **production-like async pools**.

---

## 3. What we measured (empirical, decisive)

We added a toggle (`-Devita.test.disableRealPools`) to flip A2 off while keeping A1 + Part B + CDC, and ran targeted
classes in isolation plus the full functional suite.

| Run | A2 **on** (real pools in web-API tests) | A2 **off** (direct executor; A1+B+CDC only) |
|---|---|---|
| `CatalogRestUpsertEntityMutationFunctionalTest` (isolation) | **9 failures / 12** | **0 / 12** |
| `EvitaClientReadOnlyTest` | 0 fail | 0 fail |
| `EvitaClientReadWriteTest` (contains the 7 original CSAE + the 2 CDC tests) | 0 fail | **0 fail** |
| Full `unitAndFunctional` suite (20 017 tests), with A2 on | **23 failures + 9 errors** | not yet run to green |

- The **23 failures** are all `FieldUndefined` (GraphQL) / HTTP `404` (REST) on collection endpoints across 8+
  web-API classes — the async-API-rebuild race from §1.3. Deterministic, not flaky (9/12 in isolation).
- Of the **9 errors**: 7 were self-inflicted (a CDC *unit* test drove the now-blocking `subscribe()` single-threaded;
  fixed by running it on a background thread — unrelated to A2); 2 are pre-existing/unrelated
  (`ProgressRecordTest` future-timeout; a REST WebSocket CDC awaitility timeout).
- **Key result:** with A2 **off**, the 7 CSAE/CANCELLED stay gone and the 2 CDC tests pass. **⇒ A1 + the CDC fix
  alone fix the reported problem; A2 is not needed for correctness and is the sole cause of the 23 web-API
  regressions.**

This is consistent with the determinism philosophy in §1.1: A2 injected async into CPU-saturated parallel tests and
predictably produced timing failures.

---

## 4. The tension to resolve

Two legitimate values collide:

- **Determinism (the house rule):** direct executor everywhere in tests; async only in a few explicitly-async tests
  with generous timeouts. A2 breaks this rule. Dropping A2 restores it.
- **Fidelity:** the CANCELLED-cascade and the CDC register-then-mutate race are **real-pool-only** phenomena — they
  do **not** manifest under the direct executor. A2 (real pools) is what *exposed* the genuine CDC production bug. If
  the test lane is synchronous everywhere, **no integration test exercises these production behaviours**, so future
  regressions of A1/Part B/CDC would pass CI silently. (The production fixes still ship and have unit coverage; what
  is lost is end-to-end guard-rails.)

### Maintainer's current leaning (for your consideration, not a constraint)

The maintainers are inclined to **keep the direct executor everywhere and additionally disable the keep-alive ping in
the test environment entirely** — accepting that doing so removes the very signal that surfaced this problem — because
in the deterministic test model a stalled inline call on the event loop is an artefact of the test executor, not a
real defect, and it should not terminate the connection. Production keeps a real (widened) ping.

Please assess this leaning critically rather than simply endorsing it.

---

## 5. Decision & sub-questions

**Primary decision.** Choose and justify one path for the **test lane** (production keeps real pools + A1 defaults
regardless):

- **(A) Drop A2; keep direct executor everywhere; disable the ping in tests.** Restores determinism; zero web-API
  regressions; relies on unit tests + the shipped production fixes for A1/Part B/CDC correctness.
- **(B) Keep A2 (real pools for web-API tests); add async-readiness barriers** (await endpoint/API readiness after
  schema changes) to the ~23+ affected tests. Maximises fidelity; fights the determinism rule and risks reintroducing
  flakiness under CPU saturation; unknown blast radius.
- **(C) Narrow A2** to only the handful of datasets that reproduce the cascade, direct executor elsewhere. Targeted;
  a per-dataset opt-in that keeps a small real-pool island.
- **(D) Something else** you consider better.

**Sub-questions (please answer each):**

1. Is disabling the keep-alive ping **entirely** in the test environment sound, or does it hide a class of real
   defects (e.g. genuine deadlocks/livelocks that a ping would otherwise surface as a failure)? If risky, what is the
   minimal safer alternative (e.g. a long-but-finite test ping, or a ping enabled only in the few async tests)?
2. If the test lane is synchronous everywhere, **how should the real-pool-only behaviours** (CANCELLED→session-loss
   handling in Part B; the CDC register-then-mutate ordering) be regression-guarded? Concretely: a few targeted
   real-pool tests with generous `CompletableFuture` timeouts? Which behaviours genuinely need one, and which are
   adequately covered by unit tests?
3. Are the **production** ping defaults sound — **client 30 s, server 0 (disabled)** — given idle timeouts +
   server-side session reaping still handle cleanup, and third-party LB/NAT idle windows exist? Would you change
   either default?
4. Does running the entire networked test suite on the **direct/immediate executor** risk masking *other* real
   concurrency bugs on production code paths (ordering, visibility, races) that only appear under real pools — and if
   so, is that an acceptable trade for determinism, or does it argue for a small always-real-pools smoke tier?
5. Is the **CDC blocking-`subscribe()`** fix still the right production design once the test lane no longer exercises
   it, or should the ACK-gate be reconsidered (e.g. optional/awaitable rather than blocking)?

Please conclude with a single recommended path, the concrete follow-up work it implies, and your confidence level.
