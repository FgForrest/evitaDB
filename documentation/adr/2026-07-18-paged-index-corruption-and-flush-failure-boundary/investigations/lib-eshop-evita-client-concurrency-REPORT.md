# Can the evita publishing client execute two calls on ONE warm-up session concurrently?

Scope: `lib_eshop_evita` (`com.fg.eshop.evita.*`) + job-manager wrapping, driver `evita_java_driver-2026.2.RC1-SNAPSHOT`
(pinned `lib_eshop_parent/pom.xml:39`), job-manager `lib_job_manager` (source read at 5.0.1, prod 5.0.2 — same shape).

## HEADLINE (exoneration)

The eshop client layer is **exonerated** as the source of a second concurrent execution on the warm-up
session. Each `updateCatalog` creates **exactly one** session bound to the **single thread** running the
greedy loop; no cross-thread reference to that session is ever used to invoke a *mutation*; retry is
disabled and there is no fan-out. The client therefore cannot issue a racing insert — **except** the
swallowed-timeout path (scenario A), which is gated by an ERROR-level log that is **silent** in the
incident window. This points the racing insert **server-ward** (or to a client timeout not captured/
searched — worth confirming ERROR-logger coverage for that pod).

---

## Q1a — Retry / re-issue by FailoverEvitaClient — VERDICT: NO

`FailoverEvitaClient` is a Lombok `@Delegate` decorator; "failover" = lazy **(re)creation of the client**
on connection/SSL init failure, NOT re-running calls and NOT switching evitaDB instances (there is only
one `configuration`).

- `FailoverEvitaClient.java:297-298` — `@Delegate private EvitaClient evita()`. `updateCatalog` is **not**
  overridden, so the stack frame `FailoverEvitaClient.updateCatalog(:297)` is the Lombok-generated delegate
  that forwards to `evita().updateCatalog(...)`. No retry.
- `FailoverEvitaClient.java:285-287` — explicit `executeWithExtendedTimeout` override: `return evita().executeWithExtendedTimeout(lambda, timeout, unit);`. No retry.
- `FailoverEvitaClient.java:298-311` — the ONLY failover: `if (this.evitaClient == null) { this.evitaClient = new EvitaClient(...); }` (re)creates a dead client. Class javadoc lines 31-37 confirm this is the whole mechanism.
- `FailoverEvitaClient.java:112,144` — construction failure only: `log.error("Failed to create EvitaClient, will try to create a new one on each method call.", e)`. A mid-call exception **propagates**; it is not re-run.
- `lockEvitaCatalogForChanges` (157-169) and the per-entity `upsertEntity` path carry no retry wrapper.

## Q1b — Driver retry / gRPC hedging — VERDICT: NO

- eshop config `EvitaServiceContext.java:100-125`: `EvitaClientConfiguration.builder().host(...).port(...).certificateFolderPath(...).timeout(...).openTelemetryInstance(...).build()` — **no `.retry(...)`**.
- Driver default `EvitaClientConfiguration.java:311`: `private boolean retry = false;`.
- Retry decorator gated `EvitaClient.java:478`: `if (configuration.retry()) { grpcClientBuilder.decorator(RetryingClient.builder(RetryRule.of(RetryRule.builder().onTimeoutException().thenBackoff(), ...)).newDecorator()); }`. retry=false ⇒ **RetryingClient not installed**.
  - Note: if it were enabled, `onTimeoutException().thenBackoff()` (line 482) would re-issue on timeout — the exact corruption shape. It is off.
- Interceptors present: driver-internal `ClientSessionInterceptor` (EvitaClient.java:476, session/client-id headers) + eshop `GrpcOpenTelemetryInterceptor` (EvitaServiceContext.java:128). Neither retries/hedges.

## Q1c — Single-upsert timeout & behavior — VERDICT: CONDITIONAL (timeout caught per-entity, NOT retried; see Scenario A)

- Whole reindex runs under `executeWithExtendedTimeout(() -> reindexEvita(...), getTimeoutForPublishing() [default 300s], SECONDS)` — `EvitaFullReindexJob.java:301-305`.
- Driver `EvitaClient.java:1167-1176`: pushes a `Timeout` onto a **thread-local** `LinkedList<Timeout>` (`this.timeout.get()`), runs synchronously, pops ⇒ each gRPC call on the calling thread uses that as a **per-call** deadline (default 300s/call). Session captures it at creation: `Objects.requireNonNull(this.timeout.get().peek())` (EvitaClient.java:~644).
- `upsertEntity` is **blocking**: `EvitaClientSession.java:1516` → `executeWithBlockingEvitaSessionService` (line 2343): `...FutureStub.withDeadlineAfter(timeout...)` then `.get(timeout.timeout(), timeout.timeoutUnit())` — blocks the caller until server reply or client deadline.
- Client-side `.get()` timeout: `EvitaClientSession.java:2375-2377` `catch (TimeoutException e) { throw new EvitaClientTimedOutException(...); }` — session **NOT closed**. Contrast `catch (ExecutionException)` → `transformException(... closeInternally() ...)` — session **closed** (fail-fast next call).
- In the loop, an exception from `evitaSession.upsertEntity(...)` (`EvitaFullReindexJob.java:428`) is caught per-entity at 434-440 `log.error("Failed to index entity {} ...")`, entity **skipped, not retried**, loop continues.

## Q2a — Fan-out inside the job — VERDICT: NO

- `EvitaFullReindexJob.doUniqueJobTurn` (335-467): single `for` (line 390) with sequential `evitaSession.upsertEntity(...)` (line 428) on the calling thread. No `ExecutorService`/`parallelStream`/concurrent `CompletableFuture` in the upsert path (the only `CompletableFuture` uses are the synchronous `.join()` on goLive line 543 and backup line 609 — both after/outside the loop).
- Greedy loop `GreedyJobSupport.java:83-113`: `do { processedInThisTurn = edeeTracer.execute(... doUniqueJobTurn ...); ... } while(...)` — assigns the return value each turn ⇒ blocks on each turn; turns strictly sequential, single thread.
- Converters (`publishing/converter/`): **zero** parallel primitives (grep empty).
- `EvitaIncrementalIndexJob`: same — no executor/parallelStream/future; sequential `evitaSession.upsertEntity(editor)` (line 800); session confined via `EVITA_SESSION_SUPPLIER` ThreadLocal (line 88). `reconcileReferencesToSamePageRemovals` only picks between two upsert strategies, still sequential.

## Q2b — Session leak to another thread — VERDICT: NO

- Warm-up session lives only inside `evita.updateCatalog(intermediateCatalog, session -> {...})` (`EvitaFullReindexJob.java:528`). Driver `EvitaClient.java:1032-1048`: `try (session = createSession(traits)) { session.execute(updater); }` — synchronous, one thread for the session's whole life.
- Stored only in: (1) lambda closure — thread-confined; (2) `EVITA_SESSION_REFERENCE` ThreadLocal (`:95`, set `:530`); the context reads it via the `EVITA_SESSION_REFERENCE::get` supplier (`:328`), which returns the **calling** thread's value (null elsewhere); (3) `newEvitaCatalogInstance.setInstanceId(session.getCatalogId().toString())` (`:531`) — a **String**, not the session.
- Cross-thread reference **does** exist in the driver `activeSessions` map: `this.activeSessions.put(evitaClientSession.getId(), evitaClientSession)` (`EvitaClient.java:646`). But it is harmless here:
  - `getSessionById` (`EvitaClient.java:651`) — **no eshop callers** (grep).
  - Only off-thread mutator is `evictLocalSessionsForCatalog` → `clientSession.terminateLocally()`, called ONLY after top-level delete/rename/replace (`EvitaClient.java:792,811-812,831`; javadoc 1190-1197). During warm-up none hit the temp catalog: `replaceCatalog(intermediateCatalog, catalog)` runs at `EvitaFullReindexJob.java:572` AFTER the session lambda returns (`:557`); `removeIntermediateCatalogs()` runs before the session (`:496`); a 2nd reindex is darwin-locked out.
  - Even if it fired: `terminateLocally()` = `closeInternally().complete(null)` — **local only, no server round-trip** ⇒ cannot produce a racing insert.
- Progress: `publishEventInSandbox(eventPublisher, new JobProgressEvent(...))` (`GreedyJobSupport.java:99`) — synchronous same-thread Spring publish carrying **counts**, not the session.
- CDC subscriber (the tempting off-thread path): session-creation callback runs **synchronously on the calling thread** (`EvitaClient.java:647` `onSessionCreationCallback.accept(...)`), and returns immediately for WARMING_UP (`AbstractEvitaClient.java:143-145` `if (session.getCatalogState() != CatalogState.ALIVE) return;`). Async dispatch to `cdcSetupExecutor` (`FailoverEvitaClient.java:359`) is reached only after the ALIVE guard, and `recreateCatalogSubscriber` opens its **own new** session (`AbstractEvitaClient.java:319`) — never the warm-up session.

## Q2c — Overlapping turns / same job twice — VERDICT: NO

- Turns are one synchronous `do/while` (`GreedyJobSupport.java:83-113`).
- Same job cluster-wide mutual exclusion via darwin `Locker`: `locker.leaseProcess(sharedProcessName, computeLeaseTime(), 10_000)` (`EvitaFullReindexJob.java:295`), key `getName()` (485-487); 2nd run ⇒ `ProcessIsLockedException` → `log.warn("... already running on another node, skipping ...")` return (310-313).
- Even without the lock, two doJob invocations open **separate** sessions ⇒ can never share ONE session.

## Q3 — Other writer on temp catalog `<catalog>_<epochMillis>` — VERDICT: NO

- Full reindex writes to `intermediateCatalog = catalog + "_" + ++TMP_FOLDER_COUNTER` (`EvitaFullReindexJob.java:503`).
- `EvitaIncrementalIndexJob` writes to the LIVE `catalog` (`:482`, `:563` `evita.updateCatalog(catalog, ...)`), never the temp name ⇒ different `createSession` ⇒ different session. Catalog-name isolation.
- Temp catalog is WARMING_UP; `FailoverEvitaClient.containsCatalog` filters to ALIVE-only (`:248-258`, comment 239-246) ⇒ tag/media listeners and CDC registration deliberately cannot see/write it.
- Incremental job has its own darwin lease (`EvitaIncrementalIndexJob.java:234`) with a distinct lock name; may run concurrently with a full reindex but only on the live catalog/its own session.

## Q4 — Logging calibration

| Path | Site | Level | Captured? |
|---|---|---|---|
| Client (re)creation failure | `FailoverEvitaClient.java:112,144` | ERROR | yes |
| **Per-entity upsert failure/timeout** | `EvitaFullReindexJob.java:435` | **ERROR** | **yes** ← scenario A surfaces here |
| Greedy-turn RuntimeException | `GreedyJobSupport.java:145` | ERROR | yes |
| Second-run skip | `EvitaFullReindexJob.java:311` | WARN | yes |
| CDC recreation | `AbstractEvitaClient.java:182,185,187`; `FailoverEvitaClient.java:318,321,323` | WARN/INFO | yes (WARN) — not warm-up session |
| Driver retry | decorator not installed (retry=false) | — | n/a |

⇒ The observed ERROR-silence at 11:55–12:06 UTC rules out scenario A (`EvitaClientTimedOutException`
→ ERROR at `EvitaFullReindexJob:435`) and any turn-level RuntimeException (ERROR at `GreedyJobSupport:145`),
**iff** that logger is captured at ERROR for that pod/window (task premise: WARN+ reliably captured). No
DEBUG needed for these.

---

## Ranked plausibility — source of a 2nd concurrent execution on the warm-up session

1. **Scenario A — swallowed client-timeout, then next-entity upsert on the still-open session.**
   *Shape matches* the corruption; **empirically EXCLUDED for 2026-07-13.** A client `.get()` timeout
   (`EvitaClientSession.java:2375`, session left open) throws `EvitaClientTimedOutException`; the loop's
   per-entity catch (`EvitaFullReindexJob.java:434-440`) swallows it at **ERROR** and issues the next
   `upsertEntity` on the same session while the server may still be finishing the cancelled call.
   Even narrower: requires the `TimeoutException` branch to *win the race* against the equal gRPC
   `withDeadlineAfter` deadline — if the gRPC deadline wins → `ExecutionException` → `closeInternally()`
   → next upsert fails fast via `assertActive` (no overlap); and requires one upsert to exceed the ~300s
   per-call deadline. No timeout/DEADLINE_EXCEEDED/ERROR line in-window ⇒ did not fire.
2. **NO SUCH PATH — retry / failover re-issue** (Q1a/Q1b): FailoverEvitaClient never re-runs calls; driver `retry=false`.
3. **NO SUCH PATH — job fan-out / parallelism** (Q2a): sequential do/while + sequential for; no executor/parallelStream/concurrent future issuing session calls; converters clean.
4. **NO SUCH PATH — cross-thread session leak** (Q2b): session held in ThreadLocal + closure; `activeSessions` map exposes it but is never retrieved (no `getSessionById` callers) and its only off-thread mutator is local-only and never targets the temp catalog during warm-up; CDC callback is synchronous + WARMING_UP-guarded.
5. **NO SUCH PATH — overlapping turns / duplicate job** (Q2c): single synchronous turn loop; darwin lease serializes the job; duplicate runs get separate sessions anyway.
6. **NO SUCH PATH — other writer on temp catalog** (Q3): incremental + change-listeners target the LIVE catalog; WARMING_UP is ALIVE-filtered out; catalog-name isolation ⇒ separate session.
