---
title: On-demand export of the buffered traffic-recording window via a bounded snapshot walk
date: 2026-07-18
updated: 2026-07-31 21:15
status: accepted
kind: feature
issues: [1282]
prs: [1292]
areas: [evita_store/evita_traffic_engine, evita_engine/core/traffic, evita_api/api/traffic, evita_external_api/evita_external_api_grpc]
supersedes: []
superseded-by: []
relates: [2026-07-24-traffic-discard-reason-attribution]
---

# On-demand export of the buffered traffic-recording window

Added a way to export the traffic-recording window the server already keeps buffered on disk,
without starting, stopping or interrupting the existing streaming recorder task. A client calls
`ExportTrafficRecording` over gRPC and downloads a ZIP through the normal file-fetch API. Building
it required replacing the disk ring buffer's OS-level file locks with an in-JVM span lock, because
the existing locking model could not support a third concurrent actor (the export) without
crashing.

## Why

The only existing way to get traffic recordings out of the server was `TrafficRecorderTask`: start
a fresh recording, stream it to a ZIP as it happens, stop it. There was no way to retrieve the
recent window the server keeps buffered on disk *right now* — an operator diagnosing an incident
after the fact had nothing to pull.

The constraint that made this non-obvious: `DiskRingBuffer` used per-record OS `FileChannel` region
locks (`lockAndRead`/`lockAndWrite`). Those locks are JVM-wide, and an overlapping same-JVM lock
request throws `OverlappingFileLockException` immediately instead of queuing — OS-level blocking is
cross-process only, and the disk buffer is a transient single-process file, so the OS lock was
protecting against a reader that doesn't exist. Adding a second concurrent reader (the export,
alongside the existing UI/API reads) on top of the periodic flush writer made the collision routine
rather than rare: in a full ring the head is physically adjacent to the tail, so the export's first
reads land exactly where the next flush burst writes.

### Previous state

`DiskRingBuffer.lockAndRead` (:947, pre-change line numbers) acquired and released one OS
`FileLock` per record — two syscalls per record — and on `OverlappingFileLockException` simply gave
up, silently dropping the record (a second reader colliding with a first). `lockAndWrite` (:912)
caught only `IOException`, so the *unchecked* `OverlappingFileLockException` was not caught at all
and crashed the flush task. `ringBufferHead`/`ringBufferTail` were plain non-volatile `long`s with
no happens-before edge from the OS lock. There was no snapshot/export primitive on `DiskRingBuffer`
or `OffHeapTrafficRecorder` at all.

## Options considered

### Option A — bounded snapshot walk over an in-JVM span lock (chosen)

Freeze `maxSeq` at export start, walk the ring's session locations oldest→newest, export every
session with `seq <= maxSeq`, and replace the OS region locks with a small in-process lock
(`RingBufferSpanLock`) so the writer and the export/readers can coexist safely.

- **Pros:** termination is a sequence-number comparison, not tail-pointer geometry, so it can't
  race a concurrent flush; skip accounting and progress reporting are well-defined because the
  work set is fixed up front; the in-JVM lock lets the export take one shared lock per session
  instead of per record, which also incidentally removes the 2-syscalls-per-record OS lock cost
  from every reader.
- **Cons:** requires replacing an existing locking primitive across the whole read/write path, not
  just adding a new method — the largest single piece of the change.

### Option B — moving-tail chase (declined)

Have the export follow the live tail as it advances during the walk, so it also picks up sessions
that close while the export is running.

- **Pros:** none identified in the source material beyond "exports slightly more recent data."
- **Rejected because:** the termination condition is load-dependent — if the export's own deflate
  work is slower than the writer's raw copy during a flush burst, the tail can advance faster than
  the export walk, and the walk never catches up. This is described as "guaranteed pathological"
  when `trafficFlushIntervalInMilliseconds=0`. It also leaves skip accounting and task progress
  ill-defined, since the size of the work set isn't fixed at the start.

### Option C — keep OS `FileChannel` region locks (declined, implicit baseline)

Keep the existing per-record OS lock and just add a third caller (the export) on top of it.

- **Pros:** no change to an already-working locking primitive.
- **Rejected because:** same-JVM overlapping lock requests throw immediately rather than queuing,
  so an export colliding with the flush writer would either crash the writer (the write path only
  caught `IOException`, not the unchecked `OverlappingFileLockException`) or silently drop records
  on the read side — both pre-existing defects (see *Key technical details*) that a three-way
  contended buffer would hit far more often than the two-way case that shipped with them unnoticed.

## Decision

**Chosen: Option A.** The span lock was already necessary to make the existing two-actor locking
safe (Option C's defects were real bugs, not export-specific), and once it exists, giving the
export shared per-session tokens under it is a small addition. The bounded walk was chosen over the
tail-chase because it gives the export a fixed, well-defined unit of work; the plan that shaped this
decision records the tail-chase's termination problem as the deciding reason, not a preference.

## Key technical details

- **Entry point:** `OffHeapTrafficRecorder.exportTrafficRecording(...)` — not gated by the
  `recordingActive` guard that gates `startRecording`/`stopRecording`; it operates on whatever
  window happens to be buffered regardless of whether streaming recording is active.
- **`RingBufferSpanLock`** (`evita_store/evita_traffic_engine/.../store/traffic/RingBufferSpanLock.java`)
  — a plain `synchronized` monitor over a small set of held spans (not an interval tree; the design
  explicitly sized this to "at most a handful of holders" — one writer, the export, a few readers).
  Shared acquisitions never block a request thread: a span conflicting with a held exclusive lock
  returns `null` immediately (give up + count) rather than waiting. The exclusive (writer) side
  waits, bounded in practice by one session copy. A later hardening commit (`b79bee0a8`) added
  writer preference — a shared acquisition that conflicts with a *pending* exclusive request also
  gives up immediately, so a steady stream of readers/exports can no longer starve the writer.
- **The export walk is not the plan's original correctness argument.** PLAN.md's D2 pseudocode
  asserted "validate once, after acquiring the token, never before; no re-validation after the
  copy" — i.e. holding the shared span token was believed sufficient once
  `isSessionLocationStillInValidArea` passed. The shipped `DiskRingBuffer.exportSnapshot`
  (`DiskRingBuffer.java:819`) adds a second check, `onDiskSessionIdentityMatches` (:852), because a
  span that is back inside the live window can still belong to a *different* session than the one
  originally at that location — an evicted-then-reused slot passes the geometric validity check but
  no longer holds the session being exported. Without this check the export could splice a foreign
  session's bytes into a `.bin` entry. This is counter-intuitive relative to the plan and worth
  knowing before touching this method.
- **Lazy-stream caveat, deliberately not fixed everywhere:** the existing lazy `getRecordings`/
  `getRecordingsReversed` read paths keep the old per-record acquire/release pattern (now cheap —
  no syscall, just the in-JVM lock) rather than one token per session, because a caller can abandon
  a lazy stream mid-iteration and a token held across that would leak and block the writer forever.
  Only the eager export loop uses one shared token per whole session. Widening the read path to
  per-session granularity was deliberately left for a future change with an `onClose`-based release.
- `ringBufferHead`/`ringBufferTail` are now `volatile` — the span lock's acquire/release supplies
  the happens-before edge for lock holders; volatile covers any remaining unlocked read.
- The export bypasses `TrafficRecordingIndex` entirely — no filtering is needed, and the index
  rebuild is a full Kryo deserialize of the whole buffer (see *Verification*), so skipping it avoids
  the `IndexNotReady` window altogether.
- **Session contract:** `exportTrafficRecording` is on `EvitaInternalSessionContract`
  (`evita_engine/.../core/session/EvitaInternalSessionContract.java:318`) and its implementation
  `EvitaSession.exportTrafficRecording` (`EvitaSession.java:1724`) — deliberately **not** on the
  public `EvitaSessionContract`, so it is invisible to `EvitaClient`/the Java driver by construction
  (confirmed: no match for `exportTrafficRecording` under `evita_java_driver/`).
- **gRPC:** `ExportTrafficRecording` RPC on `GrpcEvitaTrafficRecordingService`
  (`.../GrpcEvitaTrafficRecordingAPI.proto:124`), taking an optional chunk-size override; a
  supplied value `<= 0` falls back to the configured default (`b79bee0a8` — applies to both the
  export and the pre-existing start RPC). Download is the existing `ListFilesToFetch`/`FetchFile`
  path, distinguished by the export task's `origin` tag.
- **Failure cleanup** follows the `BackupTask` model: on any exception the partial export file is
  deleted via `fileForFetchFuture().getNow(null)` before the failure propagates, rather than being
  left in export storage (this was also retrofitted onto the pre-existing `TrafficRecorderTask`,
  which previously only suppressed the `FileForFetch` via a `corrupted` flag and left the file
  behind on failure).
- Along the way, `TrafficRecorderTask`'s own `ExportSessionSink.compressToFinalDestination` was
  confirmed to already need (and now has) wrap-aware copying for sessions that physically span the
  ring buffer's end (`TrafficRecorderTask.java:507-513`).

## Verification

Test-first, six new test classes: `TrafficRecordingExportTaskTest`,
`TrafficRecorderTaskExportCleanupTest`, `TrafficRecorderTaskWrapCopyTest`,
`EvitaGrpcTrafficRecordingExportIntegrationTest`, `TrafficRecorderPredicateTest`,
`RingBufferInputStreamTest`, plus substantial extensions to `EvitaOnDemandTrafficRecordingTest`,
`DiskRingBufferTest` and `OffHeapTrafficRecorderTest` — all present in the tree under
`evita_test/evita_functional_tests/`. Per the author's commit messages: the gRPC round-trip test
(`shouldExportTrafficRecordingOverGrpcAndFetchTheResultingZip`) was hardened for a startup race and
verified "15/15 green under nproc CPU hogs"; the wider `traffic_engine|grpc` suite is reported
unchanged at "501 tests, 0 failures, 0 errors" (commit `6d5865881`). These figures are the author's
own reported verification, not independently re-run for this record.

**Phase-0 JMH baseline** (captured before any production code change, commit `d3fdcc916`,
`evita_test/evita_performance_tests/.../spike/TrafficRecording*.java`, AMD Ryzen AI 9 HX 370 /
OpenJDK 21.0.11, JMH 1.37, `@Fork(1)` `@Warmup(3×1s)` `@Measurement(5×1s)`) — kept here since the
source `BASELINE.md` is retired:

- **Write path** (`recordQuery`/`recordFetch`/`recordMutation`, 1 thread): 2 878–7 828 B/op
  allocated depending on payload size (64 B–4096 B), dominated by payload size rather than thread
  count. This is the baseline the later "cuts 656–712 B/op from `recordQuery`" label-merge
  optimization (below) was measured against — no separate before/after table for that change was
  found committed anywhere in the PR.
- **Index rebuild** (`DiskRingBuffer#indexData`, full Kryo deserialize of every session): ~11
  µs/session, linear — 1 074 µs at 100 sessions, 57 154 µs (~57 ms) at 5 000 sessions. This is the
  concrete cost the export avoids by bypassing `TrafficRecordingIndex` (see *Key technical
  details*).
- **Per-record OS file lock**: two syscalls per record in the pre-change `readSessionRecords`,
  flagged as the motivation for the span lock's per-session (not per-record) locking on the eager
  export path.
- Full 15-row write table, the flush-drain and forward/reverse-read tables, and the raw
  `1-write.log`…`4-read.log` logs are not carried over — they are reproducible by re-running the
  suite, and the numbers above are the ones the design decisions above cite.

**Not verified, stated explicitly:**
- No committed before/after numbers exist anywhere in the PR's commits for the export-interference
  benchmark (`TrafficRecordingExportInterferenceBenchmark.java` — the class exists and is
  committed, but no result numbers were found in git history for it) or for the label-merge
  allocation win beyond the commit-message range ("656–712 B/op").
- H1-11 from the source plan (`readSessionRecords` end-detection: `lastFileLocation ==
  endPosition() % diskBufferFileSize`, the exact-wrap edge case) shows no diff in the feature
  commit — the line is unchanged from before this PR. Whether it was checked and found already
  correct, or simply not investigated, could not be determined from git.

## Consequences & open follow-ups

- Phase 3 of the source plan ("H2 optimizations", explicitly scoped as "numbers-driven... optimize
  only what the numbers justify") shipped only one of its five listed items — the `recordQuery`
  label-merge allocation cut. Confirmed still unapplied in the current tree:
  - `OffHeapTrafficRecorder.java:757` — `new ArrayBlockingQueue<>(blockCount, true)` (fair queue for
    free blocks) is unchanged; the plan flagged fairness as costly under contention.
  - `DiskRingBuffer.java:648` — `Collections.reverse(recordings)` for `getRecordingsReversed` is
    unchanged; the plan's own baseline benchmark noted its one-record-per-session test data made
    this cost barely measurable, which may be why it was never revisited.
  - Per-block → per-session eviction/sink callback granularity (`onSessionLocationsUpdated`,
    `DiskRingBuffer.java:992`) is unchanged — still called once per `append()`/block rather than
    once per session.
  Whether these were measured and found not worth it, or simply not reached, is not recorded
  anywhere retrievable via git.
- The read-path lazy-stream lock granularity (`getRecordings`/`getRecordingsReversed`) stays at
  per-record acquire/release by design (see *Key technical details*); revisiting it needs an
  `onClose`-based token release and a fresh benchmark, per the plan's own Phase 3 note.
- The export's soft accounting (skipped sessions from eviction, write-lock conflict, or now also
  identity mismatch on a reused slot) is surfaced in the ZIP's `metadata.txt`; nothing parses that
  file today, so its format is free to extend.

## Related work

- **`2026-07-24-traffic-discard-reason-attribution`** (#1314) — same recorder subsystem
  (`OffHeapTrafficRecorder`), landed six days later; its *Related work* section already cites this
  record's JMH baseline as the reference for the hot-path costs it weighed.

## Timeline

- **2026-07-16** — issue #1282 explored on the feature branch, ground truth about the existing code
  verified, locking model and bounded-walk design settled with Johnny
- **2026-07-16** — Phase 0 JMH baseline captured before any production change (`d3fdcc916`)
- **2026-07-17** — core feature landed: export path, span lock, and the H1 bug-hunt fixes bundled
  together (`33ccb1ce3`)
- **2026-07-17/18** — Copilot and deep-review follow-ups: `InputStream` skip contract and UTF-8
  metadata fix (`bbb11313f`), drain/span-lock/export-bounds hardening (`b79bee0a8`),
  `RingBufferInputStream` mark/reset made explicitly unsupported (`655e99c97`)
- **2026-07-18** — gRPC export test de-flaked, `TrafficRecordingEngine` activation race fixed
  (`6d5865881`); PR #1292 merged (`887cf7814`)
- **2026-07-31** — planning documents retired, replaced by this record
