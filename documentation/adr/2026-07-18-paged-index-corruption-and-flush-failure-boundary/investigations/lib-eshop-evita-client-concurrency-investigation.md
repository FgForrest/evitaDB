# Investigation: can the evita publishing client ever execute two calls on ONE session concurrently?

You are investigating FG client code (`lib_eshop_evita`, `com.fg.eshop.evita.*`, plus its job-manager
wrapping) to answer ONE overarching question with file:line evidence:

> **During a full reindex, can a second execution (retry, failover re-issue, parallel task, watchdog,
> overlapping job turn) reach the evitaDB server on the SAME warm-up session while another call on
> that session is still executing?**

## Why (context — read once, don't re-verify)

evitaDB sessions are `@NotThreadSafe` and the server does not serialize concurrent invocations of one
session. On 2026-07-13, `EvitaFullReindexJob` built the `senesi` catalog (temp catalog
`senesi_1783937623260`, warm-up window ~10:13–13:07 UTC) and the resulting dataset contains index
corruption ("stale leaf-page twin") whose in-memory inception happened at ~12:00:47 UTC. On the
evitaDB side it is now PROVEN (failing tests exist) that:
- a strictly single-threaded warm-up load of this shape does NOT corrupt, and
- ONE extra racing insert on the shared session, landing while a B+ tree leaf split is in progress,
  DOES corrupt (silently — the transport still returns success).

So the corruption requires some source of a second concurrent execution on the single warm-up
session. The evitaDB driver's own retry knob (`EvitaClientConfiguration.retry()`) defaults to
`false`, and the platform Loki logs show no retry/timeout/failover lines in the ±5 min window around
the corruption instant — but that is only meaningful if these layers log at all.

Known call stack of the reindex job (from production logs, versions
`lib_eshop_evita-10.9.0-SNAPSHOT`, `evita_java_driver-2026.2.RC1-SNAPSHOT`,
`lib_job_manager-5.0.2`):

```
com.fg.eshop.evita.publishing.EvitaFullReindexJob.doJob(:301)
  → io.evitadb.driver.EvitaClient.executeWithExtendedTimeout(EvitaClient.java:1171)
  → com.fg.eshop.evita.service.client.FailoverEvitaClient.executeWithExtendedTimeout(:286)
  → EvitaFullReindexJob.reindexEvita(:528)
  → com.fg.eshop.evita.service.client.FailoverEvitaClient.updateCatalog(:297)
  → io.evitadb.driver.EvitaClient.updateCatalog(:989)
  → io.evitadb.driver.EvitaClientSession.executeInTransactionIfPossible(:2835)
  → EvitaFullReindexJob.lambda$reindexEvita$8(:538)
  → EvitaFullReindexJob.doUniqueJobTurn(:84 → :336)   ← SQL fetch + entity upserts happen in here
(wrapped by com.fg.job.mgr AbstractGreedyJobWithContext / GreedyJobSupport.executeGreedyLogic,
 running on thread EdeeONE_moduleExecutor_2 via NotificationRunnable)
```

## Questions (answer each with VERDICT + file:line + quoted code)

**Q1 — Retry / re-issue after timeout.**
1a. What exactly does `FailoverEvitaClient` do on failure/timeout of a call (`updateCatalog:297`,
    `executeWithExtendedTimeout:286`, and the per-entity `upsertEntity` path)? Does "failover" mean
    switching to another evitaDB instance, RE-RUNNING the failed lambda/call, both, or neither?
    Quote the failover trigger conditions (which exceptions/status codes) and the re-execution code
    if any.
1b. How is the underlying `io.evitadb.driver.EvitaClient` constructed — is
    `EvitaClientConfiguration.retry(...)` ever enabled? Any Armeria/gRPC-level retry or hedging
    configured anywhere (interceptors, channel builders)?
1c. What timeout applies to a single `upsertEntity` during the reindex (values, where configured),
    and what happens when one times out mid-warm-up: is the exception propagated (job dies), caught
    and the ENTITY retried, or caught and the whole job TURN re-run? Note: a client-side timeout
    with a server call still running + ANY re-issue = the corruption scenario.

**Q2 — Fan-out / thread leakage inside the job.**
2a. Inside `EvitaFullReindexJob.doUniqueJobTurn(:336)` and everything it calls: are entity upserts
    strictly sequential on the calling thread, or is there ANY `ExecutorService`, `parallelStream`,
    `CompletableFuture`, batching helper, or producer/consumer queue that could issue two session
    calls concurrently? Same check for `EvitaIncrementalIndexJob` (its
    `reconcileReferencesToSamePageRemovals:800` is a separate incident path).
2b. Can the session object (`EvitaSessionContract` captured by `lambda$reindexEvita$8`) leak to any
    other thread — progress reporters, watchdogs, metrics/heartbeat, UI status endpoints — anything
    that might call a session method while the loop runs?
2c. Job-manager layer: under `AbstractGreedyJobWithContext`/`GreedyJobSupport` (lib_job_manager
    5.0.2), can two `doUniqueJobTurn` invocations of the SAME job overlap (second trigger while a
    turn is still running)? Quote the mutual-exclusion mechanism (or its absence).

**Q3 — Any other writer on the temp catalog.**
During a full reindex the temp catalog is named `senesi_<epochMillis>`. Is there any component that
could concurrently write to it — e.g. can a queued `EvitaIncrementalIndexJob` start while the full
reindex is still inside its warm-up session? What prevents it (job-manager exclusivity? catalog-name
isolation? nothing)?

**Q4 — Logging calibration.**
For every retry/failover/re-issue site found in Q1–Q3: what logger name + level would it emit at?
(We need to know whether the observed log SILENCE at 11:55–12:06 UTC on 2026-07-13 actually rules
those paths out — the platform captures WARN+ reliably, DEBUG only if enabled.)

## Required output format
Per question: `VERDICT: yes/no/conditional` + the evidence (file:line, short quoted snippets).
Explicit `NO SUCH PATH EXISTS` statements are as valuable as positive findings — say them plainly.
End with a ranked list: which concrete code path (if any) is the most plausible source of a second
concurrent execution on the warm-up session during the 2026-07-13 reindex.
