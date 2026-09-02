---
title: Make the scheduler's waiting interval an idle timeout renewed by lookup, linearized on the buffer lock
date: 2026-08-14
updated: 2026-08-14 18:35
status: accepted
kind: fix
issues: [1415]
prs: [1420]
areas:
  - evita_engine/src/main/java/io/evitadb/core/executor
  - evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/services
supersedes: []
superseded-by: []
relates: [2026-08-14-interruption-weaving-and-task-cancellation]
---

# Make the scheduler's waiting interval an idle timeout renewed by lookup, linearized on the buffer lock

`Scheduler`'s queue purge drops tasks that have waited too long for their precondition. The interval it
enforced was crossed with the finished-task one (five minutes instead of ten), it measured a task's whole
life rather than its idleness, and it dropped tasks with a bare `it.remove()` that left their futures
uncompleted forever. All three are fixed: the constants are uncrossed, a successful `findTask` lookup now
renews a waiting task so the interval behaves as an idle timeout, and a timed-out task is removed **and**
failed with a `TaskTimedOutException`. The renewal is made correct by holding `bufferLock` across the
lookup and the renewal, not by making the activity map concurrent.

## Why

`EvitaManagementService#restoreCatalogUnary` is the chunked catalog-restore upload used where true gRPC
client streaming is unavailable (gRPC-Web). It creates the restoration task on the **first** chunk, parks
it with `registerWaitingTask`, and submits it only once the received bytes reach `totalSizeInBytes`. Every
later chunk re-locates that task through `getWaitingTask` → `Scheduler#findTask`. The waiting queue is the
only thing carrying upload state from one call to the next.

A parked task genuinely sits in the state the purge matches on: `registerWaitingTask` never calls
`transitionToIssued`, so `TaskStatus#issued` stays null and `simplifiedState()` returns
`WAITING_FOR_PRECONDITION`. The purge runs every minute and measured age from `status.created()` — the
first chunk. Any upload lasting longer than the interval therefore lost its task mid-flight, and the next
chunk failed with `"Task not found for file id!"`.

The constraint that makes this non-obvious: **fixing the crossed constants is not enough.** Because the
clock ran from creation, ten minutes is a ceiling on total upload duration, not a timeout. A 2 GB backup
needs roughly 27 Mbit/s sustained to fit inside it. Uncrossing the constants alone would have moved the
same silent data loss from the five-minute mark to the ten-minute mark and called it fixed.

### Previous state

`purgeFinishedAndLongWaitingTasks` built both thresholds from the wrong constants — `waitingThreshold`
from `FINISHED_TASKS_KEEP_INTERVAL_MILLIS` and the defense-period threshold from
`WAITING_TASKS_KEEP_INTERVAL_MILLIS`. `findTask` and `submitWaitingTask` were the only registry
operations that ran **without** `bufferLock`, while the purge drains the queue into a private buffer and
refills it under that lock.

## Options considered

### Option A — last-activity map in the scheduler, renewed by `findTask` (chosen)

A `Map<UUID, OffsetDateTime>` inside `Scheduler`, written when a lookup finds a still-waiting task; the
purge measures from the later of `created` and that timestamp.

- **Pros:** no API or wire-format change; the one caller that exists already announces its interest by
  calling `getWaitingTask`, so no new obligation is placed on callers; an absent entry degrades to the
  old creation-based behaviour, so abandoned tasks are still collected.
- **Cons:** makes a lookup mutate state, which is surprising unless documented; requires giving
  `findTask` and `submitWaitingTask` a lock they did not previously take.

### Option B — a new component on `TaskStatus` (declined)

Carry the last-activity timestamp on the task status record itself.

- **Pros:** the timestamp travels with the task; no scheduler-side map to keep in step with the queue.
- **Rejected because:** `TaskStatus` is a public record in `evita_api` that crosses the gRPC boundary via
  `toGrpcTaskStatus`. A new component means a new proto field, converters on both sides and a
  version-skew story — permanent wire surface for a value no client reads. It would be worth revisiting
  only if a client ever needed to *display* when a waiting task was last touched.

### Option C — an explicit `touch()` / `renew()` API called by the restore handler (declined)

- **Pros:** entirely explicit; a lookup stays free of side effects.
- **Rejected because:** it is a second call that says what the first one already said, and it obliges
  every future caller to remember it. The failure mode is silent — forget the call and uploads start
  dying again at ten minutes, which is exactly the bug this record exists to close. Worth revisiting if a
  caller ever needs to inspect a waiting task *without* extending its life; today none does.

### Option D — leave it a creation-time budget and only uncross the constants (declined)

- **Pros:** minimal diff; fixes the reported symptom.
- **Rejected because:** it does not fix the reported *problem*. See the constraint under **Why** — it
  relocates the failure rather than removing it.

## Decision

**Chosen: Option A**, with the correctness carried by the lock rather than by the data structure.

The decisive point is that a concurrent map would not have made this correct. `ConcurrentHashMap` makes
each individual operation atomic while leaving every compound one open: a lookup can find a task, stall
before publishing its renewal, and lose to a purge that has already read the old timestamp and selected
the task for timeout; and a lookup running during the purge's drain window can report *no such task* for
one that is merely held in the buffer. `findTask` and `submitWaitingTask` therefore take `bufferLock`
across lookup, state check and activity update, and the map is deliberately a plain `HashMap` — the lock
is the mechanism, and a concurrent map would only have disguised that.

For Option B to win, a client would have to need the last-activity timestamp over the wire; for Option C,
a caller would have to need a non-renewing lookup.

## Key technical details

- **Entry points.** `Scheduler#findTask` renews (and documents that it renews); `Scheduler#purgeAndCollectTimedOutTasks`
  decides timeouts; `Scheduler#lastWaitingActivityOf` is the "later of creation and last lookup" rule.
- **A lookup keeps a waiting task alive.** This is a contract, not an implementation detail. A caller
  that must *not* extend a task's life cannot use `findTask`. Adding a second caller — a monitoring
  endpoint that lists waiting tasks, say — would silently make waiting tasks immortal.
- **Timed-out tasks are removed from the queue, never failed in place and kept.** Three separate reasons,
  each sufficient: the purge doubles as the queue's capacity-reclaim path (`addTaskToQueue` calls it only
  once `offer` reports full, then retries), so a retained task frees no slot; a retained task stays
  discoverable, and the restore predicate matches on file id with **no state filter** while
  `SequentialTask#matches` tests every child; and `SequentialTask#transitionToIssued` — unlike
  `AbstractServerTask`'s — does **not** refuse an already-completed task, so a failed restore sequence
  could be transitioned back to queued and scheduled.
- **Failing happens after every lock hold is released.** Task futures are public, so a completion callback
  is arbitrary client code that may re-enter the scheduler. Note that "after the purge's own `finally`" is
  *not* sufficient: on the overflow path `addTaskToQueue` holds `bufferLock` reentrantly across the purge,
  so the purge's unlock only decrements the hold count. `purgeAndCollectTimedOutTasks` therefore returns
  the collected tasks and both callers fail them once their own hold is gone.
- **`submitWaitingTask` transitions to issued under the lock**, so a purge sees a task either still
  waiting or already issued and never both; handing it to the executor stays outside the lock, because an
  `@InternallyScheduledTask` runs inline and a task body must not execute under the registry lock. That
  is why `submitTaskInQueue` is split into the transition and `executeIssuedTask`.
- **The activity map is pruned in the purge walk** — any task found in a non-waiting state loses its
  entry — rather than by a separate sweep.

## Verification

`SchedulerTest.QueuePurging`, 11 tests; `SchedulerTest` 26 tests, 0 failures; the `task & engine`
selector 111 tests, 0 failures (`mvn -o -pl evita_test/evita_functional_tests test -P unitAndFunctional
-Dgroups="task & engine"`).

The two tests that pinned the original defect failed on **opposite** branches before the fix — a waiting
task at 9m55s was dropped, a finished task at 5m05s was kept — which is what evidences a crossing rather
than a single wrong value. All four threshold tests bracket their interval to within five seconds, so
they pin 10 and 5 minutes specifically; a ±3-minute bracket would also have been satisfied by an 8/6
implementation. `shouldDropWaitingTaskWhenLookupWentStale` and `shouldKeepWaitingTaskAcrossRepeatedLookups`
drive a controllable `Clock` and are the pair that distinguishes "renewal postpones" from "renewal confers
immortality".

## Consequences & open follow-ups

- **The orphaned temporary `.zip` of an abandoned upload is still leaked.**
  `restoreCatalogUnary` uses `createTempFile` rather than `createManagedTempFile`, so the file is neither
  reserved nor swept at service close, and `RestoreTask` opens the archive `DELETE_ON_CLOSE` and therefore
  deletes it only if the task actually runs. Failing the task does **not** clean it up. Deferred because
  it is a distinct leak whose fix touches the streaming `restoreCatalog` sibling.
- **A restore chunk arriving after its task timed out still reports "Task not found for file id!"**
  rather than the timeout reason, because the task is gone from the queue by then. Turning the now-known
  `TaskTimedOutException` into a better client message is a gRPC-layer change, not a scheduler one.
- **The lookup-renews contract is enforced only by documentation.** Nothing prevents a future caller from
  taking a renewing lookup when it wanted an inert one. A non-renewing `peekTask` would be the obvious
  answer if a second caller ever appears.
- **`cancelTask` does not physically remove its queue entry** despite its JavaDoc saying it does; the
  entry survives until a later purge collects it as finished/failed. Noticed while tracing the queue
  lifecycle, left alone as out of scope.

## Related work

- `2026-08-14-interruption-weaving-and-task-cancellation` — same `io.evitadb.core.executor` package and
  the same week; it made cancellation actually interrupt a running task, while this record governs the
  lifecycle of a task that never starts running at all.

## Timeline

- **2026-08-14** — reported as #1415, root-caused, implemented and verified
