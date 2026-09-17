---
title: Virtual threads and ScopedValue stay out of the JDK 21 server; the executor defects the analysis exposed are fixed on their own terms
date: 2026-09-10
updated: 2026-09-10 09:50
status: accepted
kind: infrastructure
issues: [1518, 1532, 1533, 1534, 1535, 1536, 1537, 1538]
prs: [1519]
areas: [evita_engine/core/executor, evita_engine/core/transaction, evita_engine/core/traffic, evita_external_api/evita_external_api_core, evita_external_api/evita_external_api_rest, evita_external_api/evita_external_api_graphql, evita_external_api/evita_external_api_grpc, evita_server/log, evita_store/evita_traffic_engine]
supersedes: []
superseded-by: []
relates: [2026-09-08-jdk21-safe-modernization, 2026-09-10-simd-vector-api-feasibility]
---

# Virtual threads and ScopedValue stay out of the JDK 21 server; the executor defects the analysis exposed are fixed on their own terms

After the platform moved to JDK 21 (#1518) the open question was whether to adopt the two concurrency features
that usually motivate such a move: `ScopedValue` in place of the context `ThreadLocal`s, and virtual threads for
the request, transaction and service executors. A static analysis of the whole execution model — every executor,
all 21 `ThreadLocal`s, all 69 `synchronized` sites, every executor boundary, the Armeria/Netty handoff, the commit
pipeline and the 25 scheduler timers — concluded that neither feature buys anything on JDK 21, and that one of
them would pin every consumer to an exact JDK release. What the analysis did find were seven defects in the
existing executor and API layers that have nothing to do with thread kind. Those are filed as issues. Nothing
migrates.

## Why

evitaDB is an in-memory database whose request work is CPU-bound. The only I/O on the read path is file I/O, which
on JDK 21 never unmounts a virtual thread: it blocks the carrier and the scheduler compensates by spawning another
(`jdk.internal.misc.Blocker`). A virtual-thread request executor would therefore behave like an elastic platform
pool with less control, and the bounded request pool with its threads-first queue (#1204) is also the server's only
admission control. `ScopedValue` on JDK 21 is a preview API: every class that uses it is compiled with class-file
minor version 65535 and loads only on exactly JDK 21 with `--enable-preview`; the API shape on 21 was retyped or
removed in JDK 22–24 and finalized only in 25; it does not propagate across `ExecutorService.submit`, which is the
only kind of boundary evitaDB crosses; and it cannot implement Armeria's `RequestContextStorage`. The genuinely
blocking work — fsync and flush in the pipeline, backups, exports, recordings, S3 long-polls, parked session
closers — sits on the transaction and service pools, and the problems found there are structural, not a matter of
thread kind.

## Decisions taken

| Date | Decision | Why | Detail |
|------|----------|-----|--------|
| 2026-09-08 | `ScopedValue` on JDK 21: **no** | Preview pin (minor 65535) for every embedded user, the Docker image, `dist.zip`, `java -jar`, and every driver user if `evita_api` is touched; `runWhere`/`callWhere`/`getWhere`/`Carrier.get(Supplier)` removed or retyped in 22–24; no propagation across `submit`; not usable as Armeria context storage | `analysis.md` §7 |
| 2026-09-08 | Virtual threads for the request executor: **no** | CPU-bound work plus compensated file reads that never unmount; the pool *is* the admission limit; per-request scratch churn (`FrontCodedStringColumn`, MDC map); a global scheduler without time slicing holds a carrier for a whole multi-second query | §8 |
| 2026-09-08 | Virtual threads for the transaction executor as it exists: **no**; separating the pipeline from write bodies: **yes** | Three pipeline tasks per catalog, CPU-dominant stage 2; the hazard is the untimed REST/GraphQL close join on the same bounded pool, which is independent of thread kind | §9 → #1533 |
| 2026-09-08 | Virtual threads for the scheduler timers: **no**; for jobs: conditional, after the pinning fix and a JFR gate | 25 timers including the durability checkpoint ticker share a fixed pool with hour-long client-submitted jobs; `RingBufferSpanLock#acquireExclusive` is the one `Object.wait()` under a monitor | §10 → #1534, #1537 |
| 2026-09-10 | No migration now; the defects are fixed on their own terms | Review verdict: nothing to migrate yet; every fix is executor-kind agnostic and pays off on platform threads | #1532–#1538 |
| 2026-09-10 | The `ThreadLocal`-backed lexical facade (`ScopedBinding`) is deferred | It only prepares a JDK 25 switch nobody plans; the leak-proofing it brings is small next to the three restructures it needs (`Transaction#close` firing inside the bound scope, the `TransactionalUnorderedIntArray` suspend sites, the `WarmUpSavepoint` scope) | §7.2, §7.5 |

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Ship `ScopedValue` with `--enable-preview` | 40+ build, launcher, IDE and CI surfaces become exact pins; every consumer must run exactly JDK 21 with the flag; code written against the 21 surface does not compile on 25 | Never on 21; on a JDK 25 baseline the facade swaps its implementation in one class |
| Virtual-thread-per-request | The parks that would unmount are a handful of bounded waits (gRPC gated streaming, readiness probes, 500 ms suspension waits); everything else is CPU or compensated I/O; the pool metrics, the `INPUT_QUEUES_OVERLOADED` signal and `threadPriority` lose meaning | The read path gains genuinely unmounting I/O (network-backed storage), or the baseline reaches JDK 24+ with per-executor schedulers — and only with an admission limit designed first |
| Armeria `@Blocking` / `useBlockingTaskExecutor` | Routes gRPC bodies through a sequential per-call executor on the service pool; evitaDB already owns its handoff | — |
| Pushing the Armeria `RequestContext` onto workers | Must be popped on the same thread, allocates Netty's `InternalThreadLocalMap` per thread, and buys `duration_ms` nothing because `isRequestComplete()` stays false during processing | Other Armeria-dependent features are wanted on workers (#1538 takes the MDC route instead) |
| Redesigning the per-thread caches (`FrontCodedStringColumn` scratch, `Crc32CWrapper` scratch, `CollationKeyCache` stripes) | They only multiply under virtual-thread-per-request, which is not recommended; the collator stripe was chosen over a `ThreadLocal` after profiling | The request executor ever moves to virtual threads |

## Key technical details

- Executors: `Evita.java:454-471` creates the three pools; `ExternalApiServer.java:539-571` the Netty event loops
  and hands the *service scheduler* to Armeria's `blockingTaskExecutor` slot; `Scheduler.java:184-196` is a fixed
  `ScheduledThreadPoolExecutor` with an unbounded delay queue.
- Context travels explicitly at every boundary: `TracingContext.captureContext()` at task construction and
  `setContext()` on the worker (`ObservableThreadExecutor.java:698`), `grpcContext.run(...)`,
  `executeInTransactionIfProvided` with a captured reference. No boundary relies on `ThreadLocal` inheritance, which
  is exactly the discipline `ScopedValue` would require — the propagation design needs no change for either storage.
- Pinning: of 69 real `synchronized` sites, only `RingBufferSpanLock#acquireExclusive` (`Object.wait()` reached from
  the `synchronized` `OffHeapTrafficRecorder#drainFinalizedSessionsToDisk` through `DiskRingBuffer#lockAndWrite`) is
  a real pin, and only on the service path. Engine-index monitors are pure-memory and park nowhere.
- Blocking file I/O on JDK 21 is Blocker-compensated, never unmounted; `RandomAccessFile`, `FileChannelImpl` including
  `force`, `FileOutputStream` and the Unix dispatchers are all wrapped. This single fact is what turns
  "virtual threads for CPU plus file reads" into "an elastic platform pool with less control".
- A correction made during review: the request timeout mechanism exists exactly as assumed (Armeria
  `whenRequestCancelling` → `CancellationSupport` → task cancel with interrupt → the woven `@Interruptible` polls),
  but the key that drives it is `api.requestTimeoutInMillis` (`ExternalApiServer.java:605`, 2 s in the shipped
  YAML). `server.queryTimeoutInMilliseconds` (5 s) is read only by `EvitaStatisticsEvent`, while the `ServerOptions`
  javadoc promises that read-only requests abort after it.

## Verification

Static analysis only: the machine was reserved for a JMH run during the analysis session, so no test, benchmark or
throwaway experiment was run; JDK-internal facts were checked with `javap` on OpenJDK 21.0.12 and Armeria 1.41.1 was
read from its sources jars. On 2026-09-10, before any issue was filed, each "wrong today" claim was re-verified
against the tree at `d02ab4a0e`: the `@InternallyScheduledTask` annotation and the `getClass()` check, the
`close()` → `closeWhen()` → `join()` chain and the pool assignment of the REST and GraphQL write handlers, the
scheduler's construction and shared Armeria slot, the REST `aggregate().thenApply` chain and the `serve()` call
order, the WebSocket call sites, the Lab `HttpFile` usage, the absence of a `RejectedExecutionException` handler in
REST and GraphQL, and the two `synchronized` methods around the span lock. The starvation scenarios themselves are
derived from those facts and have not been reproduced; the issues say so.

## Consequences & open follow-ups

- Filed, milestone 2026.3: #1532 (`@InternallyScheduledTask` never matches; three recorder tasks hold a service
  worker each, and a "fixed" annotation would run them inline on the submitter), #1533 (REST/GraphQL writes park a
  transaction-pool thread in an untimed join on their own commit; async close plus a dedicated pipeline executor),
  #1534 (timers and client-submitted jobs share one fixed pool; split), #1535 (REST parsing and session creation,
  WebSocket execution and Lab static files off the event loop), #1536 (overload reported as 500 / `INTERNAL` instead
  of 503 / `RESOURCE_EXHAUSTED`), #1537 (span lock and recorder monitors to j.u.c locks), #1538 (`duration_ms` never
  emitted on application log lines; carry the request start through the captured tracing context).
- Deferred with a trigger: the lexical facade when the baseline moves to JDK 25; virtual threads for the job executor
  after #1537, gated on a run with 20 concurrent backups plus one JFR and one traffic recording showing zero
  `jdk.VirtualThreadPinned` events above 1 ms and unchanged checkpoint-tick latency; virtual threads for the
  pipeline executor gated on `CommitThroughputBenchmark` (`-t 1/8/64`) throughput and visibility p99.
- Open product question: whether REST/GraphQL write bodies should join the request pool after async close (as gRPC's
  already do) or keep a bounded write pool.
- Fixed in passing, same PR: the run guide's base image and environment-variable table, the Docker README's variable
  list and entrypoint, the IDE language level, and the `WAIT_FOR_WAL_PERSISTENCE` javadoc that claimed fsyncs are not
  batched.
- Not filed: the dead `server.queryTimeoutInMilliseconds` key (see the correction above) — reported, decision pending.

## Related work

- `2026-09-08-jdk21-safe-modernization` — the platform bump this analysis followed; it holds the driver's JDK 17
  floor and the reasons preview-feature pins were unacceptable there.
- `2026-09-10-simd-vector-api-feasibility` — the other "what does JDK 21 buy us" question, with the opposite answer
  because incubator modules do not pin class files.

## Supporting material

- `analysis.md` — the complete analysis (24 sections): decision table, executor and `ThreadLocal` inventories with
  verdicts, per-API request flows, migration phases and the benchmark plan. Line numbers are valid at `a23e5f4be`.
  Kept because the seven issues cite it and it cannot be regenerated without repeating the session.
- `evidence/01-armeria-netty.md` … `07-scheduler-inventory.md` — the seven sub-investigations the analysis was
  assembled from: the Armeria/Netty execution model and context storage; all 21 `ThreadLocal`s classified; the 69
  `synchronized` sites and every blocking wait; every `--enable-preview` surface; the per-API flow from event loop to
  executor; the commit pipeline's thread-bound state; the 25 scheduler timers and long jobs. Kept as the line-level
  evidence the analysis cites by section; regenerable only by repeating the investigation.

## Timeline

- **2026-09-08** — analysis requested after the JDK 21 bump and delivered the same day, static only
- **2026-09-10** — reviewed; no migration; findings re-verified and filed as #1532–#1538; record written
