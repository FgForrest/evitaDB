# 07 — Scheduler and background-task inventory

Analysis only. Every claim cites `file:line` on branch `1518-upgrade-to-jdk-21` at the time of writing. No code was
run; classifications come from reading each task's run body. The inventory in Part 2 is laid out as one record per
task rather than a wide table, because the repository's `.editorconfig` caps lines at 120 columns; every record
carries the same fields in the same order.

## Executive summary

- The service `Scheduler` is a **fixed-size** `ScheduledThreadPoolExecutor`: `corePoolSize = maxThreadCount`
  (default `2 × availableProcessors`, so 16 on 8 cores), an **unbounded** `DelayedWorkQueue`, and no separate lane for
  timers versus jobs (`Scheduler.java:184-192`, `ThreadPoolOptions.java:93-96`). A due timer waits behind any 16
  running jobs.
- Two submission paths coexist: the plain `ScheduledExecutorService` methods (no registry, no backpressure, futures
  usually discarded) and the `ServerTask` registry path (`submit(ServerTask)` → `addTaskToQueue` → executor),
  which is the only path with backpressure — an `ArrayBlockingQueue` of `2 × queueSize` (default 40) slots that
  also holds finished tasks for 5 minutes (`Scheduler.java:193-196, 816-852, 938-946`).
- 25 distinct periodic or self-rescheduling timers exist, all but one built on `DelayedAsyncTask`; the exception is
  the certificate watcher on `scheduleAtFixedRate`. Per catalog there are 9 timers by default and up to 12 with time
  travel and traffic recording on; per open gRPC CDC stream there is one heartbeat timer.
- Long-running jobs on the same pool: one **permanent** thread for the Prometheus `MetricTask` (JFR `RecordingStream`
  event loop), up to one JFR recorder and one traffic recorder (each parks a thread on a latch), plus an unbounded
  number of backups, full backups, restores and traffic exports, plus one horizon-reconcile per catalog open and one
  S3 refresh that can block indefinitely on a notification long-poll.
- `@InternallyScheduledTask` is dead in production: it is declared only on the abstract `ClientInfiniteCallableTask`,
  is not `@Inherited`, and `executeIssuedTask` tests the concrete class — so every infinite task goes to the pool and
  occupies a thread (`InternallyScheduledTask.java:38-42`, `ClientInfiniteCallableTask.java:43`, `Scheduler.java:778`).
- Pool exhaustion by long jobs is reachable today: any client may submit backups until the 40-slot registry rejects,
  and 16 concurrent backups plus the metric task hold every worker. Then the checkpoint ticker (the durability fence),
  CDC heartbeats (stream keep-alive), the session killer and the scheduler's own purge all wait.
- No task waits on a same-pool `Future` with `get()`/`join()`. Same-pool dependencies exist through timers (the
  traffic recorder's stop timer) and through locks; cross-pool joins exist (restore registration and catalog upgrade
  join the transaction pool).
- `DelayedAsyncTask#close` cancels with `cancel(false)` and never interrupts; registry tasks cancel with `cancel(true)`
  through the retained executor handle and are interrupted; `Scheduler#shutdown` cancels every registered task.
- No task reads the thread name, priority, daemon flag or thread group; no task body uses MDC. The WAL-draining
  timer binds the transaction `ThreadLocal` for the duration of its run (scoped bind/unbind).
- Service threads are priority 1, non-daemon (inherited from the creating thread), never time out, and keep an
  embedded JVM alive until `Evita.close()`.

## Part 1 — the Scheduler itself

### Backing executor

`Scheduler` (`evita_engine/src/main/java/io/evitadb/core/executor/Scheduler.java`) implements both
`ObservableExecutorService` (submitted/rejected counters, `ObservableExecutorService.java:33-45`) and
`ScheduledExecutorService` (`Scheduler.java:67`).

- **Executor type**: `ScheduledThreadPoolExecutor` (`Scheduler.java:100, 184`).
- **Core pool size**: `options.maxThreadCount()`, fixed; `minThreadCount` is never read (`Scheduler.java:185`,
  `ThreadPoolOptions.java:93-96`). Default `max(availableProcessors << 1, 1)` (`ThreadPoolOptions.java:94`).
- **Work queue**: the JDK `DelayedWorkQueue`, unbounded (comment at `Scheduler.java:181-183`).
- **Rejection handler**: `EvitaRejectingExecutorHandler("service")` emits `BackgroundTaskRejectedEvent`, logs and
  throws `RejectedExecutionException` (`Scheduler.java:180-188`, `EvitaRejectingExecutorHandler.java:71-76`). Because
  the queue is unbounded it only fires for submissions after shutdown; the registry overflow path invokes it by hand
  (`Scheduler.java:181-183, 845-848`).
- **Shutdown policies**: no continuation of periodic tasks, no execution of delayed tasks after shutdown,
  remove-on-cancel (`Scheduler.java:189-191`).
- **Thread factory**: `EvitaThreadFactory` names threads `Evita-service-N`, uses the creating thread's group, applies
  the configured priority (default 1) and does not touch the daemon flag (`Scheduler.java:1015-1043`,
  `ThreadPoolOptions.java:95`).
- **Test double**: `ImmediateScheduledThreadPoolExecutor` with 4 real threads; delay-0 work runs inline, delayed work
  and every `InfiniteTask` go to the pool (`ImmediateScheduledThreadPoolExecutor.java:56-58, 74, 110`). `Evita`
  picks it when `directExecutor` is set, otherwise the `serviceThreadPool` options (`Evita.java:454-458`).
- **Shutdown**: `Evita.closeAndDestroy` → `shutdownScheduler("service", …, 60)`: `shutdown()`, await 60 s, then
  `shutdownNow()` (`Evita.java:313-326, 2129`).
- **Pre-shutdown gate**: `prepareForBeingShutdown()` flips `shutdownInProgress`; afterwards every submission returns a
  pre-failed `NonScheduledFuture` instead of throwing (`Scheduler.java:744-746, 1051-1073`; `EvitaServer.java:869`).

`ScheduledThreadPoolExecutor` semantics that follow from this and matter for the split: core threads are created
lazily up to `corePoolSize` and never time out (`allowCoreThreadTimeOut` is not set); every `execute`/`submit` is a
zero-delay `ScheduledFutureTask`, so the executor captures exceptions into the future; a periodic task that throws is
silently never run again (JDK contract of `scheduleAtFixedRate`); and a delayed task whose time has come is taken by
the next free worker in trigger-time order, with no preference over zero-delay jobs already queued.

### Two submission paths

**Plain path** (`schedule`, `schedule(Callable)`, `scheduleAtFixedRate`, `scheduleWithFixedDelay`, `execute`,
`submit(Runnable|Callable)`, `invokeAll`, `invokeAny` — `Scheduler.java:225-514`):

- Delegates straight to the executor and bumps `submittedTaskCount`.
- No registry entry, no capacity check, no backpressure. The only guard is the shutdown check.
- `execute` wraps the runnable to log otherwise-swallowed exceptions and rethrows `Error` (`Scheduler.java:300-321`).
- `submit(Runnable/Callable)` transitions a `ServerTask` to issued if one is passed, but does **not** register it
  (`Scheduler.java:376-423`).

**Registry path** (`submit(ServerTask)` → `addTaskToQueue` → `submitTaskInQueue` → `executeIssuedTask`,
`Scheduler.java:517-526, 762-796, 816-852`):

- `addTaskToQueue` offers into the bounded `ArrayBlockingQueue` under `bufferLock`; on a full queue it runs the purge
  inline and retries; if still full it fails the task, calls the rejecting handler (throws) and otherwise throws
  `IllegalStateException` (`Scheduler.java:825-850`). This is the **only** backpressure in the scheduler.
- `executeIssuedTask` runs `@InternallyScheduledTask`-annotated classes inline, otherwise submits to the executor
  (`Callable` tasks as callables, others as `task::execute`) and hands the `Future` to `InterruptibleServerTask`
  implementations so `cancel()` can interrupt (`Scheduler.java:777-796`; `AbstractServerTask.java:195-220`).
- Waiting tasks: `registerWaitingTask` adds to the registry without issuing (`Scheduler.java:630-636`); `findTask`
  looks up **and renews** the idle timestamp under `bufferLock` (`Scheduler.java:655-682`); `submitWaitingTask`
  transitions and executes (`Scheduler.java:689-715`). The idle timeout is 10 minutes, finished tasks are retained 5
  minutes (`Scheduler.java:68-69`), both governed by the ADR `2026-08-14-waiting-task-idle-timeout`.
- Purge (`purgeAndCollectTimedOutTasks`, `Scheduler.java:891-976`): drains the queue in 512-element batches under
  `bufferLock.tryLock()` (a second caller blocks on `lock()/unlock()` instead of spinning, lines 969-974), removes
  finished/failed tasks (re-queuing those in the 5-minute defence period up to two thirds of physical capacity),
  removes idle waiting tasks and returns them to be failed with `TaskTimedOutException` **after** every lock hold is
  released (`Scheduler.java:872-876, 1005-1010`). Runs every minute as the scheduler's own `DelayedAsyncTask`
  (`Scheduler.java:198-205`) and inline on registry overflow.
- Listing/status/cancel APIs stream over the registry without a lock (`Scheduler.java:539-623`).
- `shutdown()` closes the purge task, then for every registry entry calls `InfiniteTask#stop()` or `cancel()`
  (which interrupts running work), then shuts the executor down (`Scheduler.java:334-347`); `shutdownNow()` cancels
  all and calls executor `shutdownNow()` (`Scheduler.java:351-357`).

### Task base classes

- `AbstractServerTask` (`AbstractServerTask.java:54`): status machine, `CompletableFuture` result, exception handler,
  `executionHandle` for interruption; `cancel()` cancels the result future first then `handle.cancel(true)`
  (`AbstractServerTask.java:206-220`); `execute()` distinguishes cancellation-driven unwinds from real failures
  (`AbstractServerTask.java:224-285`).
- `ClientRunnableTask` / `ClientCallableTask`: lambda wrappers (`ClientRunnableTask.java:40-108`;
  `ClientCallableTask.java:42-128`). `ClientInfiniteCallableTask` adds `InfiniteTask#stop()` and carries the
  `@InternallyScheduledTask` annotation (`ClientInfiniteCallableTask.java:43-44, 79-102`).
- `SequentialTask`: runs steps inline on its own worker, stops at the first step boundary after a cancel, cancels
  through the executor handle (`SequentialTask.java:156-217, 244-265`).
- `InterruptibleServerTask`: the seam through which the scheduler attaches the executor `Future`
  (`InterruptibleServerTask.java:54-83`).

### `DelayedAsyncTask` (the timer half)

`DelayedAsyncTask` (`DelayedAsyncTask.java:54`) is a self-rescheduling one-shot timer over `Scheduler#schedule`:

- **Construction**: catalog name, task name, scheduler, a `LongSupplier` body, a delay and unit, and an optional
  minimal scheduling gap (default 1000 ms, `DelayedAsyncTask.java:58, 120-149`). A delay of `Long.MAX_VALUE` marks a
  manual task that `schedule()` never plans (`DelayedAsyncTask.java:218-221`).
- **Arming**: `schedule()` plans one tick at `now + delay` only if none is planned (`nextPlannedExecution` CAS from
  `MIN`); if the body is currently running it sets `reSchedule` instead (`DelayedAsyncTask.java:176-185, 217-236`).
  `scheduleImmediately()` plans at `now` respecting the minimal gap (`DelayedAsyncTask.java:155-169`).
  `trySchedule()` is the same under the scheduling lock but returns `false` instead of throwing on a closed task
  (`DelayedAsyncTask.java:200-211`).
- **Body contract**: the supplier returns `< 0` to **pause** (no re-plan until someone calls `schedule()` again),
  `0` to re-plan at the full default delay, or `n > 0` to re-plan `n` units earlier than the default
  (`DelayedAsyncTask.java:46-49, 318-330, 371-377`).
- **Run**: `runTask` asserts single execution (`running` CAS), emits `BackgroundTaskStartedEvent`/`FinishedEvent`
  JFR events, runs the body, records `lastFinishedExecution`, then under the scheduling lock clears `running` and
  either re-plans or pauses; a deferred `schedule()` request is honoured through `trySchedule()`
  (`DelayedAsyncTask.java:335-390`).
- **Exceptions**: a `RuntimeException` is logged, rethrown into the executor's future (nobody reads it) and the task
  **pauses** (`DelayedAsyncTask.java:350-352, 373-377`). A periodic task whose body throws therefore stops until an
  external event calls `schedule()`.
- **Close**: `cancel(false)` on the pending future, lambda released, next execution reset
  (`DelayedAsyncTask.java:239-254`). A run already in flight completes.
- **Concurrency**: `schedulingLock` (`ReentrantLock`) serialises arming, re-planning and close; per-instance atomics
  carry state. Nothing is thread-bound.

### Coupling with Armeria

The same `Scheduler` instance is handed to Armeria as `blockingTaskExecutor` (`ExternalApiServer.java:571`), so
Armeria's blocking services and `useBlockingTaskExecutor` handlers run on `Evita-service-*` threads and compete with
everything below; what Armeria runs there is covered by another part. TLS setup builds `CertificateService` on it
(`ExternalApiServer.java:318-330, 744`), which is where the `scheduleAtFixedRate` certificate watcher comes from
(`CertificateService.java:106`).

### Things that look periodic but are not on this pool

- JFR periodic events (`Scheduler#emitStatistics`, `CacheEden#reportStatistics`, CDC statistics) run on the JFR
  periodic-event thread via `FlightRecorder.addPeriodicEvent` (`Evita.java:715-729`, `CacheEden.java:173-176`,
  `SystemChangeObserver.java:128-131`).
- CDC delivery to `Flow` subscribers runs on the **request** executor, passed as `cdcExecutor`
  (`Evita.java:556-561`, `TransactionManager.java:420-427`).
- Catalog loading, including the retry after an upgrade, runs on the **transaction** executor through
  `ProgressingFuture` (`Evita.java:1442-1444`, `Evita.java:565` with `EngineTransactionManager.java:306, 456`).
- gRPC management handlers and the chunked-restore upload steps run on the **request** executor
  (`EvitaManagementService.java:314-1165`, upload observer constructed with `getRequestExecutor()` at ~731).
- `ReadinessDiscoveryStallTracker` and `Http2ConnectionMonitor` are passive (atomics and Netty pipeline hooks; no
  executor, `ReadinessDiscoveryStallTracker.java:45-69`, `Http2ConnectionMonitor.java:53-80`).
- `IndexPopulation` is a `ThreadLocal`-based savepoint counter on the commit thread (`IndexPopulation.java:150`).
- The driver's `ClientTaskTracker` uses its own client `ScheduledExecutorService`
  (`ClientTaskTracker.java:84, 153`).

## Part 2 — inventory

Field key, identical for every record:

- **Producer**: where the task is created and armed.
- **Mode / period**: one-shot delay / fixed rate / fixed delay / submit-now (plain path) / registry (`ServerTask`
  path) / DAT (`DelayedAsyncTask` self-rescheduling), with the period, delay or trigger.
- **Does**: one line.
- **Class**: TIMER-CALLBACK / CPU-MAINTENANCE / FILE-IO / NETWORK-IO / ORCHESTRATION.
- **Duration / bound**: from code or ADR, or "unbounded" with what it is proportional to.
- **Blocks on**: locks or futures the body waits for.
- **Timely**: whether anything degrades when it runs late.
- **Cancel**: how it is stopped and whether stopping interrupts the worker.
- **Thread-bound state**: `Transaction` thread-local, other `ThreadLocal`, MDC.

### 2.1 Scheduler-internal and engine-global timers (one set per `Evita` instance)

**#1 `Scheduler#purgeFinishedAndLongWaitingTasks`**
- Producer: `Scheduler.java:198-205`.
- Mode / period: DAT, returns 0; every 1 min.
- Does: drains the registry in 512 batches, drops finished tasks older than 5 min and idle waiting tasks older than
  10 min, fails the latter.
- Class: CPU-MAINTENANCE.
- Duration / bound: µs–ms; O(registry ≤ 40 by default).
- Blocks on: `bufferLock` (`tryLock`; blocks on `lock()` if another purge runs, `Scheduler.java:969-974`).
- Timely: weak — overflow reclaim also runs inline in `addTaskToQueue:830`; lateness only delays waiting-task
  timeouts.
- Cancel: `close()` → `cancel(false)`.
- Thread-bound state: none.

**#2 `SessionKiller#run`**
- Producer: `SessionKiller.java:74-86`, created at `Evita.java:475`.
- Mode / period: DAT, returns 0; `min(60 s, closeSessionsAfterSecondsOfInactivity)`, default 60 s
  (`ServerOptions.java:98`).
- Does: clears session registries, terminates idle sessions after rolling back open transactions.
- Class: CPU-MAINTENANCE that **may become FILE-IO**: `terminateSession` → `catalogConsumersLeft` →
  `retentionStateChanged` can drain a bootstrap trim plus file deletion synchronously under `historyHorizonLock`
  (`DefaultCatalogPersistenceService.java:3945-3992, 4624-4650`; ADR `2026-08-06-time-travel-disk-budget`, "a last
  reader leaving pays for the trim").
- Duration / bound: O(active sessions).
- Blocks on: session locks; `historyHorizonLock`.
- Timely: moderate — idle sessions hold version pins, which hold the retention floor.
- Cancel: `close()` → `cancel(false)`; the body catches `Exception` (`SessionKiller.java:150`) so it never pauses.
- Thread-bound state: none.

**#3 `HeapMemoryCacheSupervisor` reevaluation**
- Producer: `HeapMemoryCacheSupervisor.java:82-94`, created at `Evita.java:477-478`.
- Mode / period: DAT, returns 0; `cache.reevaluateEachSeconds` (default 0 → 1 s minimal gap). The cache is disabled by
  default (`CacheOptions.java:67-69`).
- Does: `evaluateAssociatesSynchronouslyIfNoAdeptsWait` → `CacheEden#evaluateAdepts`: merges adepts with the cache,
  sorts by space-to-performance ratio, promotes and evicts.
- Class: CPU-MAINTENANCE.
- Duration / bound: O(n log n) in anteroom plus cache size.
- Blocks on: `CacheEden.lock` — `tryLock()` then `tryLock(1 s)` (`CacheEden.java:291`).
- Timely: no.
- Cancel: no `close()` of the supervisor observed in the sweep.
- Thread-bound state: none.

**#4 `CacheAnteroom` eden gate keeper**
- Producer: `CacheAnteroom.java:135-141`; armed from request threads at `CacheAnteroom.java:283, 349, 427`.
- Mode / period: DAT, delay 0, gap 0; on demand when the anteroom fills.
- Does: the same `CacheEden#evaluateAdepts`.
- Class: CPU-MAINTENANCE.
- Duration / bound: as #3.
- Blocks on: as #3.
- Timely: moderate — adepts already swapped out wait for promotion.
- Cancel: not closed anywhere in the sweep.
- Thread-bound state: none.

**#5 `CollationKeyCacheSweeper#sweep`**
- Producer: `CollationKeyCacheSweeper.java:86-98`, created at `Evita.java:479-481`.
- Mode / period: DAT, returns 0; `dropCollationKeysAfterSecondsOfInactivity`, default 300 s (`ServerOptions.java:116`).
- Does: CLOCK second-chance sweep of every locale's collation-key cache.
- Class: CPU-MAINTENANCE.
- Duration / bound: O(cached keys).
- Blocks on: nothing.
- Timely: no.
- Cancel: `close()`; the body is **unguarded** — a throw pauses the timer for good
  (`DelayedAsyncTask.java:350-352, 376`).
- Thread-bound state: none.

**#6 `SystemChangeObserver#cleanSubscribers`**
- Producer: `SystemChangeObserver.java:120-127`, created at `Evita.java:556-561`.
- Mode / period: DAT, returns 0; every 1 min.
- Does: `sharedPublisher.checkSubscribersLeft()` (`SystemChangeObserver.java:236-239`).
- Class: TIMER-CALLBACK.
- Duration / bound: µs.
- Blocks on: nothing seen.
- Timely: no.
- Cancel: `close()`; body unguarded.
- Thread-bound state: none.

**#7 `ExportFileService#purgeFiles`**
- Producer: `ExportFileService.java:201-208`.
- Mode / period: DAT, returns 0; every 5 min.
- Does: deletes expired export files (default 7 days, `ExportOptions.java:58`), lists the directory for orphans, walks
  the directory size and trims to the size limit (`ExportFileService.java:370-410`).
- Class: FILE-IO.
- Duration / bound: O(files in the export directory) including a size walk.
- Blocks on: nothing.
- Timely: no.
- Cancel: `close()`; partially guarded.
- Thread-bound state: none.

**#8 `ExportS3Service#purgeFiles`**
- Producer: `ExportS3Service.java:284-291`.
- Mode / period: DAT, returns 0; every 5 min.
- Does: MinIO `removeObject` per expired file, then size-limit trimming (`ExportS3Service.java:488-520`).
- Class: NETWORK-IO.
- Duration / bound: O(files) × round trip; unbounded by the network.
- Blocks on: HTTP.
- Timely: no.
- Cancel: `close()`; per-file exceptions caught.
- Thread-bound state: none.

**#9 `ExportS3Service#loadOrRefreshFiles`**
- Producer: `ExportS3Service.java:294-301`.
- Mode / period: DAT, `scheduleImmediately`; returns 0 (600 s) or 585 s (`ExportS3Service.java:129, 619-630`).
- Does: first run lists the bucket; every run then loops `while (iterator.hasNext())` over
  `listenBucketNotification` (`ExportS3Service.java:685-712, 732-756`).
- Class: NETWORK-IO, **long-blocking**: on a backend that supports notifications `hasNext()` is a long-poll and the
  loop does not return while the stream stays open; on backends that throw it falls back to 600 s polling.
- Duration / bound: unbounded from source; depends on the backend.
- Blocks on: the HTTP stream.
- Timely: no.
- Cancel: `close()` → `cancel(false)` does not interrupt; `close()` also closes the iterator
  (`ExportS3Service.java:552`).
- Thread-bound state: none.

**#10 `CertificateService#reloadCertificatesIfAnyModified`**
- Producer: `CertificateService.java:106`, via `ExternalApiServer.java:318-330, 744`.
- Mode / period: **fixed rate**, initial 0, every 1 min, `ScheduledFuture` discarded. Only when some endpoint
  requires TLS and the certificate is not self-signed-generated (`ExternalApiServer.java:324, 738`).
- Does: stats the certificate folder, reloads on change (`CertificateService.java:115-130`).
- Class: FILE-IO (small).
- Duration / bound: ms.
- Blocks on: nothing.
- Timely: moderate — TLS rotation lags by up to 1 min.
- Cancel: not individually cancellable; a thrown `GenericEvitaInternalError` (`CertificateService.java:129`)
  suppresses all further runs (JDK fixed-rate contract).
- Thread-bound state: none.

**#11 `MetricHandler.MetricTask`**
- Producer: `MetricHandler.java:435-442`; registered at boot by `ObservabilityManager.java:410`.
- Mode / period: **registry**, `ClientInfiniteCallableTask`; runs for the process lifetime.
- Does: builds Prometheus metrics from JFR events and blocks in `RecordingStream.start()`
  (`MetricHandler.java:684-692`).
- Class: CPU (event dispatch) on a **permanently occupied** worker.
- Duration / bound: unbounded (process lifetime).
- Blocks on: `RecordingStream.start()`.
- Timely: not applicable.
- Cancel: `stop()` → `disable("*")`, `close()` (`MetricHandler.java:698-704`); `cancel()` also interrupts via the
  handle.
- Thread-bound state: none.

### 2.2 Per-catalog timers

One set per loaded catalog; created where `Catalog` and its persistence service pass `evita.getServiceExecutor()`
(`Catalog.java:697, 754, 766, 792, 848, 859`).

**#12 `TransactionManager#drainWal`**
- Producer: `TransactionManager.java:517-522`; armed at `TransactionManager.java:709` when a trunk task is rejected.
- Mode / period: DAT; 1000 ms; returns −1 (pause) or 0 (retry).
- Does: `processTransactions(lastDurable, flushFrequency, alive, waitForLock=false)` — incorporates the WAL tail into
  the catalog (`TransactionManager.java:1925-1947`).
- Class: CPU + FILE-IO (WAL read, index mutation).
- Duration / bound: unbounded, proportional to the WAL backlog; one round bounded by `flushFrequencyInMillis`,
  default 10 s (`TransactionOptions.java:130`).
- Blocks on: `trunkIncorporationLock.tryLock(0)` (`TransactionManager.java:1407`) — never waits.
- Timely: moderate — a WAL tail nobody else drives stays unincorporated.
- Cancel: `close()`; the body catches the two transient exceptions and returns 0.
- Thread-bound state: **yes** — the trunk path binds the transaction `ThreadLocal` for the run via
  `Transaction.executeInTransactionIfProvided` (`TransactionManager.java:371, 2034`; `Transaction.java:75`); scoped
  bind and unbind, no leak across tasks.

**#13 `TransactionManager#sweepDanglingCommitProgress`**
- Producer: `TransactionManager.java:526-531`; armed at `TransactionManager.java:1983` on every commit registration.
- Mode / period: DAT; `max(5 s, waitForTransactionAcceptance / 2)` = 10 s by default (`TransactionOptions.java:113`);
  returns 0 while the registry is non-empty, −1 otherwise.
- Does: fails commit-progress records older than the safety deadline (`TransactionManager.java:1962-1965`).
- Class: TIMER-CALLBACK.
- Duration / bound: µs.
- Blocks on: nothing.
- Timely: **yes** — bounds how long a client waits on a dropped commit.
- Cancel: `close()`.
- Thread-bound state: none.

**#14 `CatalogChangeObserver#cleanInactivePublishers`**
- Producer: `CatalogChangeObserver.java:128-135`; CDC is on by default (`ChangeDataCaptureOptions.java:52`).
- Mode / period: DAT, returns 0; every 1 min.
- Does: `removeIf` closed shared publishers (`CatalogChangeObserver.java:271-278`).
- Class: TIMER-CALLBACK.
- Duration / bound: µs.
- Blocks on: nothing.
- Timely: no.
- Cancel: `close()`; body unguarded.
- Thread-bound state: none.

**#15 `CheckpointCoordinator#runTickerCheckpoint`**
- Producer: `CheckpointCoordinator.java:222-232`; armed by `noteCheckpointDeferred`
  (`CheckpointCoordinator.java:296-308`).
- Mode / period: DAT; delay = `checkpointIntervalMillis`, minimal gap = the same; returns −1 and is re-armed by the
  next deferring round. Default 1000 ms (`TransactionOptions.java:147`); no coordinator at all when 0.
- Does: `checkpointIfOwed` → `checkpointAction`: forces pending file handles (`forcePendingSyncs`,
  `CheckpointCoordinator.java:261-281`) and publishes the bootstrap record
  (`CheckpointCoordinator.java:361-370, 407-426`).
- Class: FILE-IO with **fsync**.
- Duration / bound: N handles × force (about 0.5 ms per redundant force measured, `CheckpointCoordinator.java:259`).
- Blocks on: `checkpointLock.lock()` (`CheckpointCoordinator.java:408`), contended by commit rounds.
- Timely: **yes** — this is the durability fence; a late tick lengthens the window in which acknowledged commits are
  unpublished (`Catalog.java:2758-2760`).
- Cancel: `close()` → `cancel(false)`; an in-flight run completes.
- Thread-bound state: none.

**#16 `ObsoleteFileMaintainer#purgeObsoleteFiles`**
- Producer: `ObsoleteFileMaintainer.java:185-190`; `trySchedule` at `ObsoleteFileMaintainer.java:222, 274, 364, 513,
  594, 710`.
- Mode / period: DAT, delay 0 → 1 s gap; returns −1; on demand (file retired, version released, hold released).
- Does: unlinks retired data files below the clamped threshold; may carry a pending unreachable-file sweep that reads
  the bootstrap file (`ObsoleteFileMaintainer.java:504-554, 624-654`).
- Class: FILE-IO.
- Duration / bound: O(maintained files) deletes plus an optional bootstrap read.
- Blocks on: `directoryAccessLock.tryLock()` (`ObsoleteFileMaintainer.java:625`); re-arms itself when contended.
- Timely: no (disk reclaim).
- Cancel: `close()`.
- Thread-bound state: none.

**#17 `DefaultCatalogPersistenceService#enforceTimeTravelSizeLimit`**
- Producer: `DefaultCatalogPersistenceService.java:4749-4762`; `trySchedule` from
  `DefaultCatalogPersistenceService.java:1974, 2110, 4486, 4491, 4658, 5311`.
- Mode / period: DAT, delay 0 → 1 s gap; returns −1; on demand (compaction, bootstrap publish, floor change). Only
  when time travel is on (default off, `StorageOptions.java:164`).
- Does: reclaims unreachable files, measures retained history (directory listing plus O(log n) header reads), trims
  the bootstrap file (`DefaultCatalogPersistenceService.java:4776-4874`).
- Class: FILE-IO.
- Duration / bound: O(files) + O(log n) reads + trim.
- Blocks on: `historyHorizonLock.lock()` (`DefaultCatalogPersistenceService.java:4780`), contended by session open
  and close.
- Timely: no (advisory).
- Cancel: `close()` → `cancel(false)`; the body re-checks `closed` under the lock
  (`DefaultCatalogPersistenceService.java:4782-4787`).
- Thread-bound state: none.

**#18 `DefaultCatalogPersistenceService#reconcileHistoryHorizonWithWal`**
- Producer: `DefaultCatalogPersistenceService.java:1978`.
- Mode / period: **submit-now** (plain `submit(Runnable)`), future discarded; once per catalog open.
- Does: reads the first replayable WAL version and advances the history horizon (bootstrap trim plus reclaim)
  (`DefaultCatalogPersistenceService.java:4602-4611`).
- Class: FILE-IO.
- Duration / bound: O(WAL files) + trim.
- Blocks on: `historyHorizonLock`.
- Timely: no.
- Cancel: not cancellable; dies with executor `shutdownNow`.
- Thread-bound state: none.

**#19 `AbstractMutationLog#cutWalCache`**
- Producer: `AbstractMutationLog.java:890-895` through the factories `CatalogWriteAheadLog.java:313-326` and
  `EngineMutationLog.java:213-226`; armed at `AbstractMutationLog.java:1562, 2493` on every WAL read. One per catalog
  WAL plus one for the engine WAL.
- Mode / period: DAT; returns time-to-oldest-entry or −1; 5 min (`AbstractMutationLog.java:175`).
- Does: cuts transaction-location cache entries idle for more than 5 min (`AbstractMutationLog.java:1990-2013`).
- Class: CPU-MAINTENANCE.
- Duration / bound: O(cache entries).
- Blocks on: per-entry `cut()` is tryLock-style (`size == -1` when not acquired).
- Timely: no.
- Cancel: `close()`.
- Thread-bound state: none.

**#20 `AbstractMutationLog#removeWalFiles`**
- Producer: `AbstractMutationLog.java:896-901`; armed at `AbstractMutationLog.java:1677` (processed-version advance)
  and `AbstractMutationLog.java:2361` (rotation).
- Mode / period: DAT, delay 0 → 1 s gap; returns −1; on demand.
- Does: deletes rotated WAL files at or below the processed version, then `updateFirstVersionKept` → history-horizon
  advancer → bootstrap trim (`AbstractMutationLog.java:2050-2080`; `CatalogWriteAheadLog.java:307-309`).
- Class: FILE-IO.
- Duration / bound: O(pending removals) + trim.
- Blocks on: `synchronized(pendingRemovals)` (`AbstractMutationLog.java:2051`); `historyHorizonLock` via the advancer.
- Timely: no.
- Cancel: `close()`; per-file exceptions caught.
- Thread-bound state: none.

**#21 `ObservableOutputKeeper#cutOutputCache`**
- Producer: `ObservableOutputKeeper.java:159-164, 178-183`; armed at `ObservableOutputKeeper.java:251, 345` on every
  lease. Catalog instances at `DefaultCatalogPersistenceService.java:1637, 1836, 2010`; engine instance at
  `DefaultEnginePersistenceService.java:219-224`.
- Mode / period: DAT; returns time-to-oldest or −1; 5 min (`ObservableOutputKeeper.java:78`).
- Does: closes idle `FileOutputStream`s and clears the off-heap free list (`ObservableOutputKeeper.java:375-406`).
- Class: FILE-IO (close).
- Duration / bound: O(open outputs).
- Blocks on: nothing; skips leased outputs.
- Timely: no.
- Cancel: `close()`.
- Thread-bound state: none.

**#22 `OffHeapTrafficRecorder#freeMemory`**
- Producer: `OffHeapTrafficRecorder.java:777-780`; armed at `OffHeapTrafficRecorder.java:420, 911` on session close
  or discard; exists only while a recorder is installed.
- Mode / period: DAT, gap 0; returns −1; `trafficFlushIntervalInMilliseconds`, default 60 s
  (`TrafficRecordingOptions.java:74`).
- Does: drains finalized sessions from off-heap blocks into the `DiskRingBuffer`, publishes statistics, releases the
  index after 10 min without reads (`OffHeapTrafficRecorder.java:1069-1083`; `INDEX_INACTIVITY_DURATION` at
  `OffHeapTrafficRecorder.java:114`).
- Class: FILE-IO.
- Duration / bound: O(finalized sessions × bytes).
- Blocks on: `synchronized(this)`, shared with the export's forced drain (`OffHeapTrafficRecorder.java:803`).
- Timely: **yes** — memory blocks are only freed here; otherwise sessions are discarded for `MEMORY_SHORTAGE`.
- Cancel: `close()`.
- Thread-bound state: none.

**#23 `OffHeapTrafficRecorder#index`**
- Producer: `OffHeapTrafficRecorder.java:782-785`; `scheduleImmediately` at `OffHeapTrafficRecorder.java:639, 656,
  669, 684`.
- Mode / period: DAT, delay `Long.MAX_VALUE` = manual only; returns −1; on demand when a traffic query needs the
  index.
- Does: `diskBuffer.indexData(readTrafficRecord)` (`OffHeapTrafficRecorder.java:1053-1060`).
- Class: FILE-IO.
- Duration / bound: O(disk buffer bytes, `trafficDiskBufferSizeInBytes`).
- Blocks on: the ring-buffer index lock.
- Timely: yes, for the query that asked.
- Cancel: `close()`; exceptions caught.
- Thread-bound state: none.

### 2.3 Per-connection timers

**#24 `AbstractChangeCaptureSubscriber#sendHeartbeat`**
- Producer: `AbstractChangeCaptureSubscriber.java:174-183`; one per gRPC CDC stream (`EvitaService.java:628-633`,
  `EvitaSessionService.java:2629-2636`).
- Mode / period: DAT; returns 0 or −1; `clamp(min(requestTimeout, idleTimeout) − 5 s, 1 s, 5 min)`
  (`AbstractChangeCaptureSubscriber.java:201-213`).
- Does: emits a heartbeat on the gRPC stream and re-arms the Armeria request timeout
  (`AbstractChangeCaptureSubscriber.java:399-421`).
- Class: NETWORK-IO (gRPC `onNext`, non-blocking; failures mapped at `AbstractChangeCaptureSubscriber.java:446-466`).
- Duration / bound: µs.
- Blocks on: nothing.
- Timely: **yes** — a late beat lets Armeria's idle or request timeout kill the stream.
- Cancel: `close()` via `markStreamDead`; body guarded.
- Thread-bound state: none.

### 2.4 Long-running jobs on the registry path

**#25 `BackupTask`**
- Producer: `DefaultCatalogPersistenceService.java:3447`, submitted by `Catalog.java:1624, 1653-1662`.
- Mode / period: registry; on request.
- Does: reads catalog and collection data files at a pinned version, optional WAL, zips into the export store
  (`BackupTask.java:194-294`).
- Class: FILE-IO, plus NETWORK-IO when the export store is S3 — `putObject` runs synchronously in the output stream's
  `close()` (`ExportS3Service.java:1039-1065`).
- Duration / bound: **unbounded, proportional to catalog size**.
- Blocks on: `fileForFetchFuture().get()` (`BackupTask.java:286`), already completed by the same thread's stream
  close; a version pin; a directory read hold during warm-up (`BackupTask.java:147`).
- Timely: no.
- Cancel: `InterruptibleServerTask`; five `@Interruptible` checkpoints (`BackupTask.java:358, 415, 455, 485, 526`);
  `cancel()` interrupts.
- Thread-bound state: none.

**#26 `FullBackupTask`**
- Producer: `DefaultCatalogPersistenceService.java:3480`, submitted by `Catalog.java:1635, 1653-1662`.
- Mode / period: registry; on request.
- Does: `Files.walk` and zip every file in the catalog folder while holding the directory read hold
  (`FullBackupTask.java:117, 149-215`).
- Class: FILE-IO, plus S3 as above.
- Duration / bound: **unbounded, proportional to folder size**.
- Blocks on: nothing itself; its hold turns the purge (#16) away.
- Timely: no.
- Cancel: `@Interruptible` (`FullBackupTask.java:294`); `cancel()` interrupts.
- Thread-bound state: none.

**#27 `RestoreTask` (step 1 of the restore `SequentialTask`)**
- Producer: `Catalog.java:460-478` → storage factory; submitted by `EvitaManagement.java:268-269, 280-285`, or parked
  and later submitted through the waiting path (`EvitaManagement.java:175-196`).
- Mode / period: registry (or waiting → `submitWaitingTask`); on request.
- Does: unzips the archive into a freshly allocated catalog folder (`RestoreTask.java:128-186`).
- Class: FILE-IO.
- Duration / bound: **unbounded, archive size**.
- Blocks on: nothing.
- Timely: no.
- Cancel: `@Interruptible readBlock` (`RestoreTask.java:191`); `SequentialTask#cancel` interrupts via the handle
  (`SequentialTask.java:244-265`).
- Thread-bound state: none.

**#28 Restore step 2 `registerInactiveCatalog`**
- Producer: `EvitaManagement.java:335-341` → `registerRestoredCatalogHoldingItsFolder`
  (`EvitaManagement.java:382-396`) → `Evita#registerRestoredCatalog`.
- Mode / period: inline inside #27's worker, after step 1.
- Does: `applyMutation(RestoreCatalogSchemaMutation).onCompletion().join()`.
- Class: ORCHESTRATION — a **cross-pool join** on the engine/transaction executor (`Evita.java:565`;
  `EngineTransactionManager.java:306, 456`).
- Duration / bound: unbounded, catalog load time.
- Blocks on: the join.
- Timely: no.
- Cancel: through the `SequentialTask`.
- Thread-bound state: none.

**#29 Chunked restore upload (`restoreCatalogUnary`)**
- Producer: `EvitaManagementService.java:765-796`.
- Mode / period: `registerWaitingTask` (no thread) → `submitWaitingTask` on the last chunk.
- Does: upload chunks are appended on the **request** executor; the scheduler only parks the task and enforces the
  10-minute idle timeout via #1.
- Class: registry only.
- Duration / bound: not applicable.
- Blocks on: `bufferLock` on every `findTask`.
- Timely: the idle timeout must not fire under a live upload (ADR `2026-08-14-waiting-task-idle-timeout`).
- Cancel: as #27.
- Thread-bound state: none.

**#30 `TrafficRecorderTask`**
- Producer: `EvitaSession.java:1740-1748`; singleton (`EvitaSession.java:1735-1738`).
- Mode / period: registry, `ClientInfiniteCallableTask`; on request.
- Does: starts recording, then `finalizationLatch.await()` until stop, duration or size limit, then stops and
  finalises the export (`TrafficRecorderTask.java:174-240`).
- Class: ORCHESTRATION — a blocking wait on a **pool thread**.
- Duration / bound: **unbounded, `recordingDuration` or manual stop**.
- Blocks on: the latch — released by a **same-pool** one-shot timer (`TrafficRecorderTask.java:135`), by `stop()` or
  `cancel()` from a request thread, or by the sink's size limit.
- Timely: no.
- Cancel: overrides `cancel()` → `stopInternal` plus interrupt (`TrafficRecorderTask.java:141-150`);
  `InterruptedException` handled (`TrafficRecorderTask.java:210-213`).
- Thread-bound state: none.

**#30a `TrafficRecorderTask` stop timer**
- Producer: `TrafficRecorderTask.java:135`.
- Mode / period: **one-shot delay**, future discarded; `recordingDuration`.
- Does: `stopInternal()` (latch countdown plus status text).
- Class: TIMER-CALLBACK.
- Duration / bound: µs.
- Blocks on: nothing.
- Timely: yes — ends the recording on time.
- Cancel: not cancellable; harmless if it fires after a manual stop.
- Thread-bound state: none.

**#30b `TrafficRecorderTask#updateTaskProgress`**
- Producer: `TrafficRecorderTask.java:136, 163, 166`.
- Mode / period: **one-shot delay, self-rescheduling via raw `schedule()`**, futures discarded; every 5 s until fewer
  than 5 s remain.
- Does: updates the progress percentage.
- Class: TIMER-CALLBACK.
- Duration / bound: µs.
- Blocks on: nothing.
- Timely: no.
- Cancel: not cancellable; stops by itself.
- Thread-bound state: none.

**#31 `TrafficRecordingExportTask`**
- Producer: `EvitaSession.java:1784-1790`.
- Mode / period: registry; on request.
- Does: forces a recorder drain and streams ring-buffer sessions into a zip export
  (`TrafficRecordingExportTask.java:91-146`).
- Class: FILE-IO.
- Duration / bound: **unbounded, `trafficDiskBufferSizeInBytes`**.
- Blocks on: `fileForFetchFuture().get(1 h)` (`TrafficRecordingExportTask.java:122`), completed by the same thread's
  close; the recorder monitor (#22).
- Timely: no.
- Cancel: `ClientCallableTask` → `cancel()` interrupts; no `@Interruptible` checkpoint in the class.
- Thread-bound state: none.

**#32 `JfrRecorderTask`**
- Producer: `ObservabilityManager.java:357-362`; singleton (`ObservabilityManager.java:352-355`).
- Mode / period: registry, `ClientInfiniteCallableTask`; on request.
- Does: `recording.start()`, `finalizationLatch.await()`, then copies the `.jfr` into a zip export
  (`JfrRecorderTask.java:119-182`).
- Class: ORCHESTRATION (blocking wait on a pool thread), then FILE-IO.
- Duration / bound: **unbounded, until `stop()`**.
- Blocks on: the latch.
- Timely: no.
- Cancel: `cancel()` → `stopInternal` plus interrupt (`JfrRecorderTask.java:110-113, 185-187`);
  `InterruptedException` handled (`JfrRecorderTask.java:173-176`).
- Thread-bound state: none.

**#33 Catalog storage-protocol auto-upgrade**
- Producer: `Evita.java:1408-1445` — `CompletableFuture.runAsync(…, serviceExecutor)` → `Scheduler#execute`.
- Mode / period: submit-now (plain), no future kept; once per catalog needing an upgrade at boot.
- Does: `applyMutation(UpgradeCatalogFormatMutation).join()`, then the load retry on the transaction executor.
- Class: ORCHESTRATION — a **cross-pool join**; the upgrade itself takes a pre-migration backup through the export
  service (`DefaultUpgradeExecutor.java:97-111`).
- Duration / bound: unbounded, catalog size.
- Blocks on: the join.
- Timely: no.
- Cancel: not cancellable; executor shutdown interrupts (comment at `Evita.java:1408-1414`).
- Thread-bound state: none.

**#34 `Catalog#scheduleDeactivation`**
- Producer: `Catalog.java:2732-2752` → `Scheduler#execute`.
- Mode / period: submit-now (plain); once, when a catalog becomes unpublishable.
- Does: `evita.deactivateCatalogWithProgress(name)` — the `Progress` is discarded, no join (`Evita.java:827-829`).
- Class: ORCHESTRATION (fire and forget).
- Duration / bound: µs.
- Blocks on: nothing.
- Timely: partly — the catalog refuses writes until it lands, but the barrier is already set.
- Cancel: not cancellable.
- Thread-bound state: none.

### 2.5 Pass-through holders (not producers themselves)

- `DefaultUpgradeExecutor` passes the scheduler into `CatalogPersistenceServiceFactory#upgradeStorageProtocol`
  (`DefaultUpgradeExecutor.java:57, 103-106`), which builds a persistence service and therefore #15 to #21.
- `DefaultEnginePersistenceService` builds the engine-level `ObservableOutputKeeper` (#21) and `EngineMutationLog`
  (#19, #20) (`DefaultEnginePersistenceService.java:219-224, 678-685`).
- `TrafficRecordingEngine` and `NoOpTrafficRecorder` hold it for `OffHeapTrafficRecorder` (#22, #23)
  (`TrafficRecordingEngine.java:131, 230, 664`).
- `EvitaManagement` exposes registry operations only (`EvitaManagement.java:175-196, 269, 285`).
- `ChangeCatalogCaptureSubscriber` and `ChangeSystemCaptureSubscriber` pass it to #24.
- `ExternalApiServer` passes it to Armeria as `blockingTaskExecutor` and to `CertificateService` (#10).

Every producer found by the sweep is classified above; none had a body that could not be read from source. The one
duration that cannot be bounded from source alone is #9, whose behaviour depends on the S3 backend's notification
support.

## Part 3 — facts for the timer-versus-execution split

### (a) Periodic timers versus long-running jobs

Timers (`DelayedAsyncTask` or fixed-rate), counted by scope:

| Scope | Default configuration | Maximum with all features on |
|---|---|---|
| Per `Evita` instance | 8 | 11 |
| Per loaded catalog | 9 | 12 |
| Per open gRPC CDC stream | 1 | unbounded by stream count |
| Per traffic recording | 2 raw timers (#30a, #30b) | — |

Per instance by default: #1, #2, #5, #6, #7, #10 when TLS is on, and the engine instances of #19 and #21. With the
cache on add #3 and #4; with S3 export #8 and #9 replace #7. Per catalog by default: #12, #13, #14, #15, #16, #19,
#20, #21, and #22 while recording; with time travel add #17, with traffic recording add #22 and #23.

Long-running jobs, each holding a worker for its whole life:

| Job | Concurrency bound | Lifetime |
|---|---|---|
| `MetricTask` (#11) | exactly 1 with observability on | process lifetime |
| `JfrRecorderTask` (#32) | ≤ 1 (singleton check) | until stopped |
| `TrafficRecorderTask` (#30) | ≤ 1 (singleton check) | until stopped or duration elapses |
| `BackupTask`, `FullBackupTask` (#25, #26) | registry only (40 slots) | catalog size |
| Restore `SequentialTask` (#27, #28) | registry only | archive size + catalog load |
| `TrafficRecordingExportTask` (#31) | registry only | ring-buffer size |
| `reconcileHistoryHorizonWithWal` (#18) | 1 per catalog open | WAL scan + trim |
| Catalog auto-upgrade (#33) | 1 per catalog needing upgrade | backup + rewrite |
| S3 refresh (#9) | 1 | potentially permanent |
| Armeria blocking tasks | Armeria's own limits | per request |

The registry's 40 slots also count finished tasks for 5 minutes. The default steady state is therefore roughly
8 + 9 × catalogs timers and one permanent job; on-demand jobs are unbounded in count by anything except the registry.

### (b) Can long jobs exhaust the pool and starve timers?

Yes, with the code as it stands:

- The pool is fixed at `maxThreadCount` (`Scheduler.java:184-188`); on an 8-core box that is 16 threads
  (`ThreadPoolOptions.java:94`). Timers and jobs share one unbounded `DelayedWorkQueue` with no lane or priority
  (`Scheduler.java:181-183`).
- The only submission limit is the registry: `2 × queueSize = 40` slots by default (`Scheduler.java:193-196`,
  `ThreadPoolOptions.java:96`), and finished tasks stay in it for 5 minutes (`Scheduler.java:938-946`). Any client
  with a session can call `backup`/`fullBackup` repeatedly (`Catalog.java:1653-1662`); each is a whole-catalog copy.
- With observability on, one worker is already taken permanently by #11. Fifteen concurrent backups then hold every
  remaining worker for as long as the catalog is large, and every due timer waits: the checkpoint ticker (#15, the
  durability fence), the CDC heartbeats (#24, so open streams hit Armeria's idle timeout and die), the commit-progress
  sweep (#13), the session killer (#2), the WAL drainer (#12), the traffic flush (#22) and the scheduler's own purge
  (#1). Armeria's blocking tasks queue behind them as well.
- The test executor makes the same shape visible with 4 threads (`ImmediateScheduledThreadPoolExecutor.java:57`):
  every `InfiniteTask` is pooled there (`ImmediateScheduledThreadPoolExecutor.java:74, 110`), so a metric task, a
  JFR task and a traffic recorder consume three of four threads in an integration test.
- Boot-time symptom of the same coupling: `MetricHandler#registerHandlers` waits up to one minute on the **boot**
  thread for the pool to start #11 (`MetricHandler.java:444-449`).

### (c) Nested submission and waits inside scheduled tasks

- **No task calls `get()`/`join()` on a future that is completed by another task on the same pool.** The three joins
  found target other pools: restore registration (#28) and the auto-upgrade (#33) join the engine/transaction
  executor; `BackupTask.java:286` and `TrafficRecordingExportTask.java:122` call `get()` on a future the same thread
  has already completed by closing the export stream (`ExportFileService.java:524`, `ExportS3Service.java:1065`).
- **Same-pool dependency through a timer:** the traffic recorder (#30) parks a worker on a latch that its own
  duration timer (#30a) releases from the same pool. Under exhaustion the recording cannot end by timeout; only a
  manual `stop()` or `cancel()` from a request thread ends it.
- **Same-pool re-entry without waiting:** every `DelayedAsyncTask` re-schedules itself from its own run through
  `Scheduler#schedule` (`DelayedAsyncTask.java:275-285, 371-377, 385-388`), and `CacheAnteroom#evaluateAssociates`
  arms #4 from inside #3's body (`CacheAnteroom.java:424-428`). Neither blocks.
- **Locks held by scheduled tasks that other pools contend:** `bufferLock` (#1 versus `findTask` and
  `submitWaitingTask` on request threads and `addTaskToQueue` from any submitter, `Scheduler.java:656, 696, 822`);
  `checkpointLock` (#15 versus commit rounds, `CheckpointCoordinator.java:297, 408`); `historyHorizonLock` (#17,
  #18, #20 versus session open/close and commit-side trims, `DefaultCatalogPersistenceService.java:4780`);
  `CacheEden.lock` (#3 and #4 versus request-side promotion); the `OffHeapTrafficRecorder` monitor (#22 versus #31
  and session close); the `ObsoleteFileMaintainer` directory lock (#16 versus backups' holds).
- **Blocking waits inside scheduled bodies:** `CacheEden.lock.tryLock(1 s)` (`CacheEden.java:291`);
  `checkpointLock.lock()` (`CheckpointCoordinator.java:408`); `historyHorizonLock.lock()`
  (`DefaultCatalogPersistenceService.java:4780`); the MinIO long-poll loop (#9); the two latches (#30, #32);
  `RecordingStream.start()` (#11). Everything else is `tryLock` or lock-free.

### (d) Reliance on `ScheduledFuture` cancellation and interrupt semantics

- `DelayedAsyncTask#close` calls `future.cancel(false)` (`DelayedAsyncTask.java:246`): a pending tick is removed
  from the queue (remove-on-cancel policy, `Scheduler.java:191`) and a running body finishes on its own. Two bodies
  explicitly rely on completing after close: #17 (`DefaultCatalogPersistenceService.java:4782-4787`) and #15 (an
  armed ticker is deliberately left armed, `CheckpointCoordinator.java:314-316`).
- Registry tasks rely on **thread interruption**: `AbstractServerTask#cancel` cancels the result future then calls
  `executionHandle.cancel(true)` (`AbstractServerTask.java:206-220`); `SequentialTask#cancel` does the same
  (`SequentialTask.java:244-265`). The ADR `2026-08-14-interruption-weaving-and-task-cancellation` chose the executor
  `Future` because `FutureTask`'s cancel state machine delivers the interrupt only while the task runs and
  `ThreadPoolExecutor.runWorker` clears a leftover flag before the next task. The woven `@Interruptible` checkpoints
  in `BackupTask`, `FullBackupTask` and `RestoreTask` poll `Thread.isInterrupted()` and raise a checked
  `InterruptedException` from bytecode.
- Infinite tasks handle `InterruptedException` by restoring the flag and throwing (`TrafficRecorderTask.java:210-213`,
  `JfrRecorderTask.java:173-176`); `MetricTask` is stopped by closing the stream (`MetricHandler.java:698-704`).
- `Scheduler#shutdown` cancels every registry entry (interrupting running backups) and stops infinite tasks
  (`Scheduler.java:338-345`); `Evita` waits 60 s then calls `shutdownNow` (`Evita.java:313-326`).
- Plain-path futures are **discarded** at #10, #18, #30a, #30b, #33 and #34, so none of these can be cancelled
  individually; they end with the executor.
- A throwing body ends a timer silently on both mechanisms: JDK fixed-rate suppression for #10, and the pause in
  `DelayedAsyncTask#runTask` (`DelayedAsyncTask.java:350-352, 373-377`) for #5, #6 and #14, whose unguarded bodies
  return 0 expecting to be re-planned.
- After `prepareForBeingShutdown`, every scheduling call returns an already-failed `NonScheduledFuture`
  (`Scheduler.java:230-234, 1051-1073`); `DelayedAsyncTask#scheduleTask` stores it without inspecting it.

### (e) Assumptions about thread name, priority, daemon status or group

- `Evita-service` appears only in the factory (`Scheduler.java:1037`); no production or test code reads a service
  thread's name, priority, daemon flag or group (`rg` over `src/main` and `evita_test`).
- Priority is `DEFAULT_SERVICE_THREAD_PRIORITY = 1` (`ThreadPoolOptions.java:95`), applied at
  `Scheduler.java:1038-1040`. Nothing consumes it; on a virtual thread `setPriority` is a no-op and `getPriority`
  reports 5.
- The daemon flag is not set by the factory, so workers inherit it from the thread that first triggers worker
  creation, which is the `Evita` constructor thread (the purge is armed at `Scheduler.java:205`). Core threads never
  time out, so non-daemon `Evita-service-*` threads keep an embedded JVM alive until `Evita.close()`. Virtual threads
  are always daemon.
- The thread group is the creating thread's (`Scheduler.java:1031`); nothing reads it.
- No task body uses MDC or a `ThreadLocal` of its own (`rg` over the task packages). The one thread-bound state on
  the pool is the transaction `ThreadLocal` bound for the duration of the WAL drainer's run (#12,
  `TransactionManager.java:371, 2034`; `Transaction.java:75`) — the same code path whose thread-local use is the
  stated reason the transaction pool is never the direct executor (`Evita.java:463-466`).

### (f) The `DelayedAsyncTask` pattern in detail

See Part 1, "`DelayedAsyncTask` (the timer half)". The properties that decide where the timer half can live:

- It never holds a thread between ticks; each tick is one `Scheduler#schedule(Runnable, delay)` whose returned
  `ScheduledFuture` is kept only for `cancel(false)`.
- The body runs entirely on the worker that dequeued the tick and re-plans from that worker under `schedulingLock`.
- Pause and resume are data-driven: a negative return pauses, and any later `schedule()`, `trySchedule()` or
  `scheduleImmediately()` from any thread re-arms; a `schedule()` arriving during a run is folded into `reSchedule`
  and honoured at the end of the run.
- The minimal gap (default 1 s, 0 for #4 and #22, equal to the interval for #15) throttles re-arming from hot paths.
- It emits two JFR events per run (`BackgroundTaskStartedEvent`, `BackgroundTaskFinishedEvent`).
- 24 of the 25 timers use it; body durations range from microseconds (#6, #13, #14, #24) through file operations
  (#7, #16, #19 to #23) and fsync (#15) to WAL incorporation (#12) and network long-polls (#9).

## Facts relevant to a timer/execution split

1. One fixed pool of `2 × cores` threads serves both sub-millisecond timers and multi-minute jobs, with an unbounded
   queue and no priority between them (`Scheduler.java:181-192`).
2. Backpressure exists only on the `ServerTask` registry (40 slots, finished tasks counted for 5 minutes); the plain
   scheduling path has none (`Scheduler.java:193-196, 816-852`).
3. At least one worker is occupied for the whole process lifetime by the metric task, and one each while a JFR or
   traffic recording runs, because `@InternallyScheduledTask` is checked on the concrete class and only the abstract
   base declares it (`Scheduler.java:778`, `ClientInfiniteCallableTask.java:43`, `InternallyScheduledTask.java:38-42`).
4. Timers whose lateness has a user-visible cost: the checkpoint ticker (durability window), CDC heartbeats (stream
   survival), the commit-progress sweep (bounded client waits), the traffic flush (memory-shortage discards), the
   duration stop timer of a traffic recording.
5. Timer bodies are not uniformly cheap: the checkpoint ticker fsyncs, the WAL drainer incorporates a WAL backlog,
   the session killer can trim the bootstrap file, the S3 refresh can block on a long-poll, and several bodies take
   locks that request and commit threads also take.
6. No scheduled body waits on a same-pool future; same-pool coupling is through one timer-released latch (#30) and
   through locks. Two bodies join the transaction pool (#28, #33).
7. Cancellation of timers is `cancel(false)` and tolerates a run in flight; cancellation of jobs is `cancel(true)`
   and depends on `FutureTask` interrupt delivery plus woven `@Interruptible` checkpoints.
8. Six plain-path submissions discard their futures and are uncancellable except by executor shutdown (#10, #18,
   #30a, #30b, #33, #34).
9. A throwing timer body silently stops the timer on both mechanisms in use; three unguarded bodies return 0
   expecting re-planning (#5, #6, #14).
10. Nothing reads the worker's name, priority, daemon flag or thread group; priority 1 and non-daemon status are
    side effects of the factory, not contracts. Non-daemon core threads currently keep an embedded JVM alive.
11. The only thread-bound state touched on the pool is the transaction `ThreadLocal`, bound and unbound within one
    run of the WAL drainer (#12).
12. The same executor is Armeria's `blockingTaskExecutor` and hosts the certificate watcher; whatever hosts the
    timers after a split also has to answer for Armeria's blocking work, or the two must be separated at
    `ExternalApiServer.java:571`.
13. Tests with `directExecutor=true` run against a 4-thread `ImmediateScheduledThreadPoolExecutor` that executes
    zero-delay work inline and pools only delayed work and infinite tasks
    (`ImmediateScheduledThreadPoolExecutor.java:56-119`).
14. The `Scheduler` exposes JFR statistics per tick (`ScheduledExecutorStatisticsEvent`: completed delta, active
    count, queue size and remaining capacity, pool sizes, `Scheduler.java:722-739`) — the metrics by which today's
    behaviour can be compared against any replacement.
