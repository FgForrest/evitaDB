---
title: A resource handed to a JVM-lifetime registry is released by its owner's close, never by the registry
date: 2026-09-14
updated: 2026-09-14 15:30
status: accepted
kind: fix
issues: []
prs: []
areas: [evita_engine/src/main/java/io/evitadb/core, evita_engine/src/main/java/io/evitadb/core/cache, evita_engine/src/main/java/io/evitadb/core/cdc, evita_engine/src/main/java/io/evitadb/core/executor, evita_store/evita_store_server/src/main/java/io/evitadb/store/engine]
supersedes: []
superseded-by: []
relates: []
---

# A resource handed to a JVM-lifetime registry is released by its owner's close, never by the registry

Closing an `Evita` instance terminated its catalogs, shut down its executors and released its file locks,
and the instance stayed in the heap anyway — along with every catalog, persistence service and pooled
output buffer it could reach. Three separate omissions caused it, but they share one shape: something the
instance had handed to a longer-lived holder was never asked for back. Each hand-off is now matched by a
release on the owner's close path.

## Why

The functional test suite builds and closes thousands of engines in one JVM — 13,263 in a single run,
never more than 24 at once by design. It was exhausting an 8 GB surefire fork heap and dying mid-suite,
taking unrelated tests with it. A forced full GC showed 1,483 `Evita` instances still **live**: not
garbage awaiting collection, genuinely reachable.

The constraint that made this non-obvious is that none of the three retainers is reachable from evitaDB
code. The output buffers sat in a keeper whose only other reclaim path ran on a scheduler the closing
engine had already stopped. The engines themselves were held by `jdk.jfr.internal.RequestEngine#entries`
— a `private static final List` inside the JDK, and therefore a GC root that no amount of correct
shutdown on our side can reach. A profiler pointed at a parked scheduler thread instead, and killing 91%
of those threads recovered exactly zero bytes; the reading that cost the most time was treating one
sampled path to a GC root as *the* retainer.

### Previous state

`FlightRecorder.addPeriodicEvent(Class, Runnable)` looks like it hands a callback to the JFR subsystem
to run. It does not hand it to a thread at all — it appends it to that static list, and the only way out
is `removePeriodicEvent`, which matches on **object identity**. Four sites registered hooks and none
released them: the four engine-wide hooks in `Evita#emitStartObservabilityEvents`, the per-catalog hook
in `Evita#emitCatalogStatistics`, and the constructors of `CacheEden` and `SystemChangeObserver`. The
per-catalog hook was an anonymous inner class, so it carried a reference to the engine.

The per-catalog hook did contain a self-removal, which is why the omission survived review: it looks
self-cleaning. It could never fire. Periodic hooks run only while a JFR recording is active, so in an
ordinary run the body was never invoked; and every line of it, removal included, sat inside
`if (Evita.this.isActive())` — gating the one path that could unregister a hook on the instance being
alive, when the instances needing unregistration are exactly the dead ones.

Registration was also unbounded. `emitCatalogStatistics` runs on every `ModifyCatalogSchemaMutation`,
not only on catalog creation, so each schema change added another permanent hook for the same catalog.

## Options considered

Three genuine forks, one per problem.

### Fork 1 — where the release happens

#### Option A — the owner tracks what it registered and releases it on close (chosen)

`Evita` keeps the hook instances it registered (a list for the engine-wide ones, a map keyed by catalog
name for the per-catalog ones) and hands them all back as the first act of `closeInternal()`. The hook's
own self-retirement stays as a backstop, moved outside the `isActive()` guard.

- **Pros:** works with no recording running, which is the common case; deterministic; the owner already
  knows its own lifetime.
- **Cons:** the owner must store the instances — a method reference yields a fresh object on every
  evaluation, so `removePeriodicEvent(this::emitEvitaStatistics)` would remove nothing and report no
  error.

#### Option B — rely on the hook retiring itself (declined)

Keep the pre-existing design and only fix the `isActive()` gate.

- **Pros:** no new state; the smallest possible diff.
- **Rejected because:** a periodic hook executes only while a JFR recording is active, so with no
  recording — an ordinary test run, an ordinary server with observability off — the self-removal is
  unreachable code and the leak is unchanged. Self-cleaning that depends on being run cannot clean up
  after the thing that stopped running.

### Fork 2 — bounding registration without stranding a hook

Registration happens on the asynchronous change-capture thread and on the catalog-load path; retirement
happens on the change-capture thread and on the closing thread. The map and the JFR registry must not
disagree, because a hook that reaches the registry without reaching the map is invisible to the drain and
leaks exactly as before.

#### Option A — register with JFR first, record second, then re-read `active` (chosen)

- **Pros:** the only interleaving the ordering permits is a record whose hook is not yet registered, which
  is inert — the drain removes nothing and nothing is stranded. The re-read closes the remaining case, a
  close that drains between the guard and the write: the registering thread writes its record then reads
  `active`, while `close()` writes `active` then reads the records, so at least one side always observes
  the other and exactly one of them retires the hook.
- **Cons:** a hook can briefly be registered twice for one catalog before the loser retracts, and the
  correctness argument rests on the interleaving above rather than on a lock.

#### Option B — `putIfAbsent` first, then register (declined)

- **Pros:** reads more naturally; the map is the gate.
- **Rejected because:** a concurrent retirement landing between the put and the register finds the entry,
  removes it, and unregisters nothing — the hook is not in the registry yet. The registration then lands
  into a registry nothing tracks. That is precisely the failure this record exists to prevent, reachable
  by dropping a catalog while it is being created.

#### Option C — do both inside `ConcurrentHashMap#compute` (declined)

- **Pros:** atomic per catalog name; no window at all.
- **Rejected because:** it calls into JFR while holding a map bin lock, and `addPeriodicEvent` reaches
  `EventType.getEventType()`, which takes JFR's own metadata locks. A JFR thread executing a hook that
  touches the same map would invert the lock order. **Revisit if** JFR ever documents its internal
  locking well enough to rule the inversion out.

### Fork 3 — how to pin it with a test

#### Option A — assert the closed engine becomes unreachable (chosen)

`EvitaJfrHookRetentionTest` walks every registration path, closes the engine, and asserts a
`WeakReference` to it clears.

- **Pros:** asserts the property that actually matters, and keeps asserting it whichever retainer a
  future change introduces — it is not coupled to JFR at all.
- **Cons:** needs a bounded retry around `System.gc()`, since an explicit collection is a request.

#### Option B — assert the tracking map's size (declined)

- **Pros:** deterministic, instant, no GC involved.
- **Rejected because:** it cannot fail for the reason it exists. Changing `putIfAbsent` to `put` — the
  most likely way to reintroduce the duplicate-registration bug — keeps the map at one entry while
  registering N hooks, and the assertion passes on broken code.

#### Option C — count emitted events in a real JFR recording (deferred, not rejected)

A `jdk.jfr.Recording` with `period=everyChunk` fires every registered hook at chunk begin and end, so
counting `io.evitadb.storage.CatalogFlush` rows per catalog name measures the registry directly and needs
no sleeps.

- **Deferred because:** it is the only faithful test of the deduplication half, but a recording is
  JVM-wide and fires every other engine's hooks too; the interaction with a suite running at parallelism
  8 is unexamined. Left as a follow-up rather than shipped untried.

## Decision

**Chosen: A in all three forks.** The unifying rule, and the thing worth remembering after the diff has
been forgotten:

> When an object hands a reference to a holder that outlives it, the object's `close()` is what takes it
> back. A holder that promises to clean up on its own is only usable if it is guaranteed to run, and a
> JVM-lifetime static never is.

Concretely, for this codebase: **every `FlightRecorder.addPeriodicEvent` needs a matching
`removePeriodicEvent` tied to the lifecycle of whatever the hook captures, and the hook instance must be
stored to make that possible.** The engine is currently the only place that registers periodic hooks —
`EvitaJfrEventRegistry` calls `FlightRecorder.register`, which registers an event *type* and retains no
instances — but several modules define JFR events and the next hook registered anywhere will hit this.

## Key technical details

- `Evita#retireStatisticsHooks()` runs as the **first** statement of `closeInternal()`, before sessions,
  catalogs and executors, because a hook held by a JDK static outlives every resource released after it.
- `Evita#emitCatalogStatistics` and `#registerEngineStatisticsHook` refuse to register once `isActive()`
  is false; `active` is flipped by `close()` before `closeInternal()` runs.
- `CatalogStatisticsHook#retire()` uses `Map#remove(key, value)`, not `remove(key)`: a catalog dropped
  and recreated under the same name is served by a different hook instance, which must not be retired by
  its predecessor.
- `DefaultEnginePersistenceService#close()` closes its `ObservableOutputKeeper` **after** the mutation
  log, so nothing is still leasing a buffer, and **before** the folder lock, so no cached file handle
  outlives the claim on the directory it points into. The catalog-side service already did this; the
  engine-side one is what diverged.
- `ImmediateScheduledThreadPoolExecutor` is a test utility. Its `awaitTermination` previously returned
  `true` unconditionally, which made `Evita#shutdownScheduler` skip its `shutdownNow()` fallback — the
  escape hatch could never fire.

## Verification

Functional suite, parallelism 4, 8 GB fork — the CI conditions, reproduced locally.

| | baseline | + keeper close | + hook release |
|---|---|---|---|
| outcome | **OutOfMemoryError** | no OOM | no OOM |
| tests | 23,079 | 23,975 | 23,976 |
| failures | 7 failures + 7 errors | 0 failures, 1 error | 0 failures, 1 error |
| wall | 09:43 | 06:36 | 05:08 |

The remaining error is the Docker-dependent `ExportS3ServiceTest`, expected off a Docker host.

Live objects after a forced full GC, all at the same point in the test fork's life:

| | baseline | + keeper close | + hook release |
|---|---|---|---|
| `RequestEngine$RequestHook` | 7,389 | 6,687 | **191** (fresh-JVM baseline 47) |
| live `Evita` instances | 1,273 | 1,151 | **153** |
| live byte arrays | 2,982 MB | 662 MB | — |
| total live heap | 3,523 MB | 1,083 MB | — |

Every hook was accounted for arithmetically before the fix: per-catalog hooks plus observers plus caches
plus the fresh-JVM baseline of 47 predicted the total to within 6–8 across six independent snapshots. After
the fix the residual inverts to −37 — objects alive whose hooks have already been handed back.

The registry used to only climb: 47, 7,389, 9,058, 9,097 within one run, with no downward path. It now
decays towards the number of engines actually alive, adding 86, then 27, then 13 per 40 seconds.

`EvitaJfrHookRetentionTest` passes and fails for the right reason: it asserts reachability, so it would
catch any retainer, not merely a JFR hook.

## Consequences & open follow-ups

- **~105 fully-constructed engines are still reachable** at 122 s of fork life, against a harness peak of
  24 simultaneous — down from ~1,250 and no longer a heap risk, but the retainer is unnamed. A JDI
  referrer walker (`ReferenceType#instances` plus `ObjectReference#referringObjects`, which enumerates
  every inbound edge rather than one sampled path) is the right instrument; `jhsdb` is not, because SA
  attach needs `ptrace`.
- **~52 `Evita` objects never reached `Evita.java:608`**, where `changeObserver` is assigned — partly
  constructed instances that are still held. They cost almost nothing, since the heavy fields were never
  assigned, and they were invisible under the JFR husks. **Neither registry in this record can hold them**,
  which is worth knowing before anyone re-treads it: every construction step in that window was enumerated,
  and the only one that both captures the engine and installs itself somewhere with an independent lifetime
  is `SessionKiller` — which the functional harness disables, since `EvitaParameterResolver` sets
  `closeSessionsAfterSecondsOfInactivity(-1)` and `Evita.java:524` builds one only above zero. Confirmed
  against a heap histogram: 10 live `SessionKiller` against 52 unexplained engines. The persistence-service
  factory takes no `Evita` parameter, so nothing it builds can capture the engine either; the cache is off
  in that configuration; and `SystemChangeObserver` equals `EngineTransactionManager` in every snapshot, so
  no engine sits between `:608` and `:615`. The realistic throw sites are the persistence-service creation
  and the engine-state read that follows it.
- **A constructor that throws leaks everything it had already acquired, and nothing can release it.**
  `Evita` sets `active` only at the end of its constructor, and `close()` is
  `if (active.compareAndSet(true, false))` — so a construction that threw cannot be closed. It is worse than
  a missed recovery path: the constructor threw, so no caller ever received the reference, and there is no
  recovery path at all. Everything acquired from `SessionKiller` onward leaks, including the cache
  supervisor's Flight Recorder hook, the change observer's hook, the engine persistence service and its
  folder lock. The throwing candidates between those acquisitions and the end of the constructor are the
  `EnginePersistenceServiceFactory.create(...)` call, `drainPendingCatalogInventoryDivergence(...)` and
  `new EngineTransactionManager(...)`. The shape of the fix is a `try`/`catch (RuntimeException | Error)`
  around the constructor body from the first acquisition, releasing in reverse order whatever is non-null
  and rethrowing — the constructor frame is the only place holding a reference to the half-built object.
  Deliberately **not** done here: it is wider than hook lifecycle, it needs a test that deliberately fails
  a construction (nothing in the suite does that today), and a guard releasing only the hooks would read as
  "handled" while the folder lock still leaked.
- **The strongest named candidate for the engines still reachable is the scheduler's delayed queue, and it
  is not the same defect as this record.** `DelayedAsyncTask#runTask` re-enqueues itself whenever its
  `LongSupplier` returns zero or more, and `SystemChangeObserver#cleanSubscribers` returns `0L`
  unconditionally — so that cleaner is a permanent queue resident, and the queue entry's callable reaches
  the engine through the observer's shared publisher, which holds an `Evita` field. For an engine whose
  `close()` is never called this roots the entire engine graph with no Flight Recorder hook involved.
  Cancellation is not the issue: the JDK nulls a cancelled task's callable, so a closed engine releases
  normally. What this would catch is engines finished with but never closed. **It is a candidate, not a
  conclusion** — the obvious benign explanation was tested and does not fit, since at one snapshot only 19
  datasets held an engine against 129 fully-constructed live ones, so residency by design accounts for a
  small fraction of them.
- **The deduplication half has no regression test** — Fork 3 Option C above is the shape it should take.
- **The `isActive()` guard alone was not enough**, and the first draft of this change shipped two ways to
  strand a hook: a call past the guard could register after `retireStatisticsHooks()` had drained, and
  `registerEngineStatisticsHook` recorded the hook *before* registering it — the very order this record
  rejects under Fork 2 — so a concurrent drain could read it out of the list, call `removePeriodicEvent`
  on a hook not yet registered, clear the list, and leave the registration that followed stranded. Both are
  closed by the re-read described in Fork 2. The reachable trigger for the second was
  `MetricHandler#registerHandlers`, whose one-minute `get` can time out while the callback that calls
  `emitStartObservabilityEvents` stays pending and fires later, including during close.
- **`FormulaCacheVisitorTest` and `CacheAnteroomTest` build a `CacheEden` per test method** and now close
  it in teardown. Before, they did not — which is the entire explanation for the constant "5 live
  `CacheEden`" in every heap histogram taken during this investigation.

## Timeline

- **2026-09-14** — suite OOM investigated; output keeper omission found and fixed; Flight Recorder hook
  registry identified as the retainer of the engines themselves and fixed; test-scheduler shutdown
  policies corrected alongside.
