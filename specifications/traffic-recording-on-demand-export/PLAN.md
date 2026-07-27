# Implementation plan — issue #1282: on-demand export of the disk ring buffer + bug hunt + JMH hardening

Audience: the implementing session (any model). This plan is deliberately **coarse** — it fixes the
architecture, the phase order, the acceptance gates, and the known hazards; local micro-decisions
(method names, exact test data, buffer sizes in tests) are the implementer's to make. All file:line
anchors were verified on branch
`1282-traffic-recording-on-demand-export-of-in-memory-on-disk-recordings-plus-bug-hunt-jmh-performance-hardening`
on 2026-07-16.

Issue: https://github.com/FgForrest/evitaDB/issues/1282 (milestone 2026.2, labels: enhancement, performance).

## Ground truth about the existing code (verified — do not re-derive)

- **Write path**: `OffHeapTrafficRecorder` (`evita_store/evita_traffic_engine/.../store/traffic/OffHeapTrafficRecorder.java`)
  stages sessions in off-heap blocks (`SessionTraffic`, per-record closure queue + single-writer CAS
  drain, `SessionTraffic.java:392-455`); `closeSession` queues into `finalizedSessions`; the
  `freeMemory()` task (`OffHeapTrafficRecorder.java:754`, `synchronized`, paced by
  `trafficFlushIntervalInMilliseconds`) drains into `DiskRingBuffer.appendSession/append`
  (`DiskRingBuffer.java:320/348`), evicting oldest sessions (`updateSessionLocations:692`).
- **Read path**: disk-only, needs `TrafficRecordingIndex` (`IndexNotReady` if absent), per-record
  `lockAndRead` (shared `FileChannel` range lock, `DiskRingBuffer.java:947`) + re-validation
  against live `[head, tail]` (`isSessionLocationStillInValidArea:873`); wrap-around reads go
  through `RingBufferInputStream`.
- **Existing live export**: `TrafficRecorderTask` + nested `ExportSessionSink`
  (`evita_engine/.../core/traffic/task/TrafficRecorderTask.java:70/233`) — eviction-driven,
  forward-only; writes chunked zip entries `traffic_recording_<seq>.bin` (raw
  `<lead descriptor><payload>` bytes, chunk rollover at `chunkFileSizeInBytes`,
  `compressToFinalDestination:377`) plus a human-readable `metadata.txt` (`close():314-338`).
  Origin tag = task simple class name. Zip goes through
  `ExportService.storeFile(name+".zip", desc, "application/zip", origin)` → `ExportFileHandle`
  → `FileForFetch`.
- **Round-trip reader**: `InputStreamTrafficRecordReader`
  (`evita_store/evita_traffic_engine/.../InputStreamTrafficRecordReader.java:97-155`) consumes a
  **single extracted `.bin` stream** (parses the 16-byte lead descriptor itself, then Kryo). It
  never opens the zip or `metadata.txt` → **extending `metadata.txt` with new lines is safe**; the
  only consumer of zip structure is the functional test helper
  `EvitaOnDemandTrafficRecordingTest.listAndVerifyFilesInArchive`
  (`evita_test/evita_functional_tests/.../api/EvitaOnDemandTrafficRecordingTest.java:444-483`),
  which skips non-`.bin` entries.
- **gRPC**: `GrpcEvitaTrafficRecordingService` proto at
  `evita_external_api/evita_external_api_grpc/shared/src/main/resources/META-INF/io/evitadb/externalApi/grpc/GrpcEvitaTrafficRecordingAPI.proto`
  (service :94, `StopTrafficRecording` :115, `GetTrafficRecordingStatusResponse` :89 wraps
  `GrpcTaskStatus`). Impl `EvitaTrafficRecordingService.startTrafficRecording`
  (`.../grpc/services/EvitaTrafficRecordingService.java:249-276`) is the pattern to mirror
  (`executeWithClientContext`, chunk-size default from server config :261-263). Download path:
  `EvitaManagementService.listFilesToFetch` (:606, filters by repeated `origin`) + `fetchFile`
  (:671). No registrar change needed.
- **Session contract**: `startRecording`/`stopRecording` live on **`EvitaInternalSessionContract`**
  (`evita_engine/.../core/session/EvitaInternalSessionContract.java:282/301`), NOT on the public
  `EvitaSessionContract` — so the new export method there is automatically invisible to
  `EvitaClient` (verified: no matches in `evita_java_driver/`). Impl pattern:
  `EvitaSession.startRecording` (`EvitaSession.java:1663-1691`) — builds the task with
  `catalog.getTrafficRecordingEngine()`, `evita.management().exportService()`, submits via
  `evita.getServiceExecutor()`.
- **Guards**: singleton guard for the live task = task-status lookup in `EvitaSession` (:1675) +
  `recordingActive` CAS in `TrafficRecordingEngine.startRecording` (:191). The reader guard to
  mirror for the export: `TrafficRecordingEngine.getRecordings:514` — `instanceof
  TrafficRecordingReader`, else `EvitaInvalidUsageException`. Per R4 the export must **not** be
  gated by the singleton guard.
- **Cleanup parity gap**: `BackupTask` deletes the partial export file on failure
  (`evita_store/evita_store_server/.../catalog/task/BackupTask.java:239-242` —
  `fileForFetchFuture().getNow(null)` → `deleteFile`); `TrafficRecorderTask` only suppresses the
  `FileForFetch` via a `corrupted` flag. The new task must follow the `BackupTask` model.

---

## Phase 0 — JMH baseline (before any code change)

Build the H2 benchmark suite **first** so every optimization and the export's interference claim
have honest "before" numbers.

Location & conventions: `evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/`
(standalone micro-benchmarks; JMH 1.37; pattern per `SortIndexTimingBenchmark`:
`@BenchmarkMode(AverageTime)` / `@OutputTimeUnit` / `@Fork(1)` / `@Warmup(3×1s)` /
`@Measurement(5×1s)` / `@State(Scope.Benchmark)` / `Blackhole` params / `main()` delegating to
`org.openjdk.jmh.Main`). Long-running perf tests stay out of `functional_tests`. Run backgrounded;
detect completion by result-file content, never by PID liveness.

Benchmarks (one class each, or grouped where states are shared):

1. **Write throughput/allocation** — `recordQuery`/`recordFetch`/`recordMutation` against an
   initialized `OffHeapTrafficRecorder` at 100% sampling. Sweep `@Param`: record payload size,
   `@Threads(1/4/16)`. Run with `-prof gc` — alloc-rate is the primary metric (this sits on the
   query hot path). Also a `SampleTime` variant for p99 latency.
2. **Flush drain cost** — `freeMemory()` with N pre-finalized sessions (`@Param` N and session
   size); measures `DiskRingBuffer.append` + eviction scan + file-lock overhead per drained batch.
3. **Index (re)build latency** — pre-filled disk buffer (`@Param` fill %, session count), measure
   `DiskRingBuffer.indexData` (i.e. the `IndexNotReady` window).
4. **Forward vs. reverse read** — `getRecordings` vs `getRecordingsReversed` over an indexed
   buffer; quantifies the `ArrayList` + `Collections.reverse` cost (`DiskRingBuffer.java:499-507`).
5. **Export interference** (added in Phase 1, measured before/after any locking change) —
   writer threads recording at fixed rate while the export runs in a background group
   (`@Group`-based asymmetric benchmark): measure write p99 with/without a concurrent export, plus
   raw export throughput (MB/s). This is the proof artifact for R2's non-interference guarantee.

Deliverable: committed suite + a `BASELINE.md` in this spec dir with numbers from this machine.

### Known perf-sensitive spots the benchmarks should illuminate (verified observations, fix only if numbers justify)

- **Per-record OS file lock**: `readSessionRecords` acquires and releases a `FileLock` for *every
  record* (`DiskRingBuffer.java:820-861`) — two syscalls per record. Export/read should lock once
  per session (or one lock per contiguous span).
- **Fair `ArrayBlockingQueue` for free blocks** (`OffHeapTrafficRecorder.java:564`,
  `new ArrayBlockingQueue<>(blockCount, true)`) — fairness costs heavily under contention; block
  alloc happens on the write hot path (`prepareStorageBlock:719`).
- **Stream/lambda churn on the write path**: label merging in `recordQuery`
  (`OffHeapTrafficRecorder.java:344-350`) allocates streams per query; `SessionTraffic.record`
  allocates a capturing lambda + `ConcurrentLinkedDeque` node per record (`SessionTraffic.java:398`);
  `(int)(System.currentTimeMillis() - now.toInstant().toEpochMilli())` allocates an `Instant` per
  record. Project code style mandates allocation-optimized loops in perf-critical code.
- **Per-block sink + eviction overhead**: `updateSessionLocations` runs (and calls
  `SessionSink.onSessionLocationsUpdated` with the whole deque) once per `append()` — i.e. per
  16 KiB block, not per session (`DiskRingBuffer.java:742-745`).
- **`calculateIndexingPercentage`** calls `ConcurrentLinkedDeque.size()` — O(n) — potentially per
  `IndexNotReady` throw (`DiskRingBuffer.java:655`).
- **Index rebuild** Kryo-deserializes the entire 32 MiB buffer to build the in-memory index.

---

## Phase 1 — functional export (R1–R5)

### 1a. Core: consistent snapshot export in `DiskRingBuffer` / `OffHeapTrafficRecorder`

New `DiskRingBuffer` method, pull-driven raw-byte export of the current window.

**Settled design (2026-07-16, discussed with Johnny): bounded walk, not tail-chase.** The export
walks the session regions oldest→newest exactly as the ring is laid out, but the finish line is
frozen at start instead of chasing the live tail: a moving-tail chase has load-dependent
termination (deflate in the export can be slower than the writer's raw copy during a flush burst,
guaranteed pathological with `trafficFlushIntervalInMilliseconds=0`), R2 explicitly asks for the
window "at export start", and chase mode makes skip accounting and task progress ill-defined.

- Sequence: (1) synchronous pre-export drain (see below) so everything *closed* before the export
  request is flushed and sequence-numbered; (2) freeze `maxSeq` = current `sequenceOrder`;
  (3) walk the `sessionLocations` deque oldest→newest (weakly-consistent iteration of the
  `ConcurrentLinkedDeque` is fine), exporting sessions with `seq <= maxSeq`. Termination is a
  **sequence-number comparison, never byte-pointer geometry** — the tail position wraps and is
  ambiguous as a stop condition. **Do not use `TrafficRecordingIndex`** — the export needs no
  filtering, and bypassing the index removes the whole `IndexNotReady` interaction by design.
- Per session: take ONE shared `lockAndRead`-style lock over the session's span, re-validate via
  `isSessionLocationStillInValidArea`, then copy the raw `<lead descriptor><payload>` bytes into
  the caller's sink (wrap-aware: a session spanning the buffer end is two physical segments —
  reuse/extract the seek+copy logic shape of `ExportSessionSink.compressToFinalDestination:377`,
  which must itself be checked for wrap handling, see H1-7). Copy via a pooled buffer
  (`copyBufferPool`), no Kryo on this path.
- A session that fails validation or lock acquisition is **skipped and counted** (returned in an
  export-summary record: exported count, exported bytes, skipped count). Never emit partial bytes:
  re-validate *after* the copy as well — if the session became invalid mid-copy (lock semantics
  make this unlikely but see H1-2/H1-3), drop the zip entry content written for it or buffer the
  session fully before committing the entry (implementer's choice; the entry-per-session format
  makes "abort current entry" feasible only if each session is buffered or each entry is closed
  per chunk — decide locally, but the invariant is: **a `.bin` entry never contains a truncated
  session**).
- Pre-export drain (R1, settled: always on): expose an explicit drain hook on
  `OffHeapTrafficRecorder` (trigger `freeMemoryTask.scheduleImmediately()` and await, or call the
  drain synchronously) and invoke it at export start, **completing before the walk begins** —
  this is what delivers "recent closed sessions are included" without tail-chasing, and it must
  not overlap the walk (the drain writes exactly where the walk starts reading). Document the
  trade-off (drain advances the ring and may evict oldest sessions). Open in-flight sessions are
  never exported.

New `OffHeapTrafficRecorder` public method wiring the above; new `TrafficRecordingEngine` method
mirroring the `getRecordings:514` guard (`instanceof` check → `EvitaInvalidUsageException` for
`NoOpTrafficRecorder`/non-reader recorders). Not gated by `recordingActive`.

**Locking model (settled 2026-07-16, discussed with Johnny): replace OS region locks with an
in-JVM span-aware read/write lock.** Background: `FileChannel` region locks are JVM-wide and
overlapping same-JVM requests **throw** unchecked `OverlappingFileLockException` immediately —
they never queue or block intra-process (OS-level blocking is cross-process only). Today
`lockAndRead` treats that as "give up" (`DiskRingBuffer.java:961`) but `lockAndWrite` (:912)
catches only `IOException`, so the first writer/reader collision crashes the flush task. And in a
full ring the head is physically adjacent to the tail, so the export's *first* reads sit exactly
where the next flush burst writes — the collision is likely, not rare. The OS lock was originally
chosen for its convenient region API, but it protects against a cross-process reader that doesn't
exist (the disk buffer is a transient single-process file); nothing needs it.

Requirements for the replacement lock (structure is the implementer's choice):

- Span-granular, wrap-aware: a span is 1 or 2 physical segments (reuse the segment-splitting
  arithmetic already present in `isWasted`/`segmentsOverlap`); non-overlapping spans never
  contend.
- Writer (flush drain) takes exclusive span locks per `append()`; export and read paths take
  shared span locks **per session** (not per record — kills the current 2-syscalls-per-record
  overhead as a side effect).
- Asymmetric conflict policy, preserving current semantics: a reader/exporter that finds its span
  writer-locked **gives up immediately** (skip + count — never blocks a request thread); the
  writer that finds a span reader-locked **waits** — bounded in practice by one session copy
  (ms-scale), which satisfies R2's "never stall the live recorder". No crash path exists by
  construction.
- Lock acquire/release must provide the happens-before edge for `ringBufferHead`/`ringBufferTail`
  (fixes H1-3); make the fields volatile anyway as cheap defense for any unlocked read.
- Two readers on overlapping spans share freely — fixes the reader-vs-reader silent drop (H1-2)
  that the OS lock caused.

Write the failing tests for H1-1/H1-2/H1-3 *before* landing the redesign (TDD gate) — they double
as the proof the OS-lock defects were real and are the regression net for the new lock.

### 1b. `TrafficRecordingExportTask`

New `ServerTask` in `evita_engine/.../core/traffic/task/`, e.g.
`TrafficRecordingExportTask extends ClientCallableTask<…Settings, FileForFetch>` (plain callable —
NOT infinite; no `NEEDS_TO_BE_STOPPED` trait):

- `exportService.storeFile("traffic_recording_export_<catalog>_<ISO timestamp>.zip", desc,
  "application/zip", origin = task simple class name)`.
- Streams the Phase-1a snapshot into the zip in the **same chunked format** as `ExportSessionSink`
  (`traffic_recording_<seq>.bin` entries, rollover at `exportFileChunkSizeInBytes` default from
  server config) + final `metadata.txt` with the existing fields **plus exported-session count,
  exported bytes, and skipped/overwritten-session count** (safe — nothing parses this file).
- Progress: `updateProgress(0..100)` by sessions processed / snapshot size.
- Failure cleanup per the `BackupTask` model (`BackupTask.java:239-242`): delete the partial file
  via `fileForFetchFuture().getNow(null)` → `deleteFile`.

### 1c. Session contract

`EvitaInternalSessionContract` + `EvitaSession`: new `exportTrafficRecording(...)` next to
`startRecording` (:1663) — read-only-session guard as appropriate, build task, submit via
`evita.getServiceExecutor()`, return the `ServerTask`/status. **No** singleton-task lookup. Nothing
touches the public `EvitaSessionContract` → no `EvitaClient`/driver impact (scope guard, verify in
review).

### 1d. gRPC

- Proto: one RPC `ExportTrafficRecording(GrpcExportTrafficRecordingRequest) returns
  (GetTrafficRecordingStatusResponse)` inserted after `StopTrafficRecording` (:115). Request
  message minimal (optional `chunkFileSizeInBytes` mirroring start; nothing else needed).
  Regenerate stubs (generated classes live in `evita_external_api_grpc/shared/.../generated/`).
- `EvitaTrafficRecordingService`: one `@Override` mirroring `startTrafficRecording:249`
  (`executeWithClientContext`, chunk-size default from `server().trafficRecording()`), returning
  `toGrpcTaskStatus`.
- Download path unchanged (`listFilesToFetch` origin filter + `fetchFile`). Document the new
  origin value.

### 1e. Round-trip acceptance test (R3 gate)

Extend `EvitaOnDemandTrafficRecordingTest` (tags `CONTRACT`/`QUERY`/`TRAFFIC_ENGINE`, seeded
traffic generator :415, archive verifier :444): generate traffic → wait/force drain → call export
via session AND via gRPC → `fetchFile` → every `.bin` entry parses fully through
`InputStreamTrafficRecordReader` and the recording count/content matches what `getRecordings`
returns for the same window; `metadata.txt` assertions on the new counters.

---

## Detailed design — the complex parts (read before starting Phase 1)

These subsections pin down the parts where a wrong local decision is expensive. Follow them; the
rest of the plan stays coarse on purpose.

### D1. The span lock (replaces OS `FileChannel` region locks)

New small class in `evita_store/evita_traffic_engine/.../store/traffic/` (e.g.
`RingBufferSpanLock`). **Do not over-engineer**: at any moment there are at most a handful of
holders (one writer thread — `freeMemory()` is `synchronized`, so writer-vs-writer cannot happen —
plus the export and a few UI readers). A plain `synchronized` monitor guarding a small list of
held spans, with `wait()`/`notifyAll()`, is the right size. No interval tree, no striping.

Span normalization (shared with D3): a span is `(start, length)` with
`start ∈ [0, fileSize)`; if `start + length <= fileSize` it is ONE segment
`[start, start+length)`; otherwise TWO segments `[start, fileSize)` and
`[0, start+length-fileSize)`. Two spans conflict iff any of their segments overlap (reuse the
`rangesOverlap` arithmetic already in `DiskRingBuffer:983`).

API (shapes, names are free):

- `@Nullable Token tryAcquireShared(long start, long length)` — returns null immediately if the
  span conflicts with a held **exclusive** span (readers never wait, never block a request
  thread); otherwise registers and returns a token. Shared-vs-shared always succeeds.
- `Token acquireExclusive(long start, long length)` — `while (conflicts with ANY held span)
  wait();` then register. The wait is bounded in practice by one session copy (ms-scale). Untimed
  `wait()` is acceptable **only because** every acquisition site releases in `finally`; assert
  that discipline in review.
- `void release(Token)` — remove + `notifyAll()`.

Integration (all in `DiskRingBuffer`):

- `lockAndWrite` (:912) → `acquireExclusive` around the physical `writeDataToFileChannel` call;
  delete the `FileLock` usage and the `IOException`-swallowing catch (see H1-5 — a write failure
  must now propagate/fail the session, not be logged-and-forgotten).
- `lockAndRead` (:947) → `tryAcquireShared`; keep the null-on-conflict contract but **count**
  every give-up (new counter, surfaced per H1-2).
- Delete nothing else: `sessionLocations`, head/tail updates, validation all stay where they are.
- Make `ringBufferHead`/`ringBufferTail` (:162/:167) `volatile`. The monitor already provides
  happens-before for lock holders; volatile covers the remaining unlocked reads.

Lock-ordering / deadlock argument (keep this comment in the code): the writer holds the
`freeMemory` monitor and may wait on the span lock; the export/readers hold span tokens and never
acquire the `freeMemory` monitor while holding one (the pre-export drain completes **before** the
walk takes its first token). No cycle exists. Never call the drain while holding a span token.

**Lazy-stream caveat (important):** `getRecordings`/`getRecordingsReversed` return *lazy* streams
to callers who may abandon them; holding a span token across lazy stream consumption risks a
leaked shared lock that would block the writer forever. Therefore the lazy read paths KEEP
per-record acquire/release (now cheap — no syscall); only the **eager** export loop (D2) uses
one token per session. Revisit read-path granularity in Phase 3 only with an `onClose`-based
release and benchmark justification.

### D2. The export walk (`DiskRingBuffer` method)

```
exportSnapshot(sessionConsumer, progressCallback) -> ExportSummary:
    // caller (OffHeapTrafficRecorder) has ALREADY run the synchronous drain — see below
    maxSeq   = this.sequenceOrder.get()                 // freeze the finish line
    snapshot = copy of this.sessionLocations into ArrayList, keeping only seq <= maxSeq
    exported = 0; skipped = 0; bytes = 0
    for loc in snapshot:                                // oldest -> newest, deque order
        token = spanLock.tryAcquireShared(loc.start, loc.totalLenWithDescriptor)
        if token == null:            skipped++; continue          // writer owns it -> evicted
        try:
            if !isSessionLocationStillInValidArea(loc): skipped++; continue
            sessionConsumer.accept(loc, rawBytes)       // D3 copy, whole session, verbatim
            exported++; bytes += loc.totalLenWithDescriptor
        finally: spanLock.release(token)
        progressCallback(exported + skipped, snapshot.size())
    return ExportSummary(exported, skipped, bytes, snapshot.size())
```

Correctness argument (understand it, keep it as a comment): once the shared token is held AND
validation passes, the bytes **cannot change** — the writer performs physical writes only under an
exclusive span lock, which blocks while our token exists. A *logical* eviction mid-copy (deque
removal + head/tail advance in `updateSessionLocations:692` happens before the physical write) is
harmless: the bytes are still intact, the zip entry is still a complete valid session. Therefore:
**validate once, after acquiring the token, never before; no re-validation after the copy.** The
skip decision happens strictly before the first byte of that session is written to the zip — that
is the whole "never a truncated session" invariant. The only remaining truncation risk is an I/O
failure on the zip stream itself, handled at task level (D4 failure path).

Drain hook: promote the body of `freeMemory()` (`OffHeapTrafficRecorder.java:754`) into a
package-visible synchronous method (e.g. `drainFinalizedSessionsToDisk()`) that both the
`DelayedAsyncTask` and the export entry point call directly — `synchronized` already serializes
them. Do NOT schedule-and-poll the async task. Call order in the recorder's export method:
`drainFinalizedSessionsToDisk()` → `diskBuffer.exportSnapshot(...)`.

### D3. Wrap-aware raw copy

- **Do NOT use `RingBufferInputStream`** — it has three wrap bugs (H1-4) and the export must not
  depend on it even after they're fixed.
- Bytes to copy per session: exactly `loc.location().recordLength()` bytes starting at
  `loc.location().startingPosition()` — this INCLUDES the 16-byte lead descriptor
  (`LEAD_DESCRIPTOR_BYTE_SIZE`), copied verbatim; `InputStreamTrafficRecordReader` parses the
  descriptor itself (:115-117). No Kryo anywhere on this path.
- Split into 1–2 physical segments with the D1 normalization; for each segment, loop:
  `read(pooled byte[] from copyBufferPool)` from a dedicated read handle → `write` to the zip
  stream, until the segment is exhausted. Use a fresh
  `diskBufferFileReadInputStreamFactory.get()` (or a plain `FileChannel.read(ByteBuffer, pos)`)
  owned by the export for its whole duration and closed in `finally`; never share the writer's
  channel position state.
- Guard: short reads from the file are impossible here only if the handle is positioned inside the
  pre-allocated file — still, assert `bytesRead > 0` per iteration and treat `-1` as a hard error
  (the file has fixed length; EOF mid-segment means a logic bug, throw, never emit the entry).

### D4. Zip / chunk / metadata mechanics (mirror `ExportSessionSink` byte-for-byte)

- Stream stack: `ZipOutputStream(BufferedOutputStream(exportFileHandle.outputStream()))`
  (`BackupTask.java:174` pattern).
- Entry lifecycle (this is how `compressToFinalDestination:377` behaves — replicate): keep
  `currentChunkSize`; when no entry is open, open `traffic_recording_<seq>.bin` named by the
  **first session in that chunk**; append whole sessions into the open entry; after each session,
  if `currentChunkSize >= chunkFileSizeInBytes` → `closeEntry()`, reset. Entries therefore contain
  one or MORE complete sessions back-to-back; the reader parses consecutive lead descriptors
  within one entry. A session is never split across entries.
- `metadata.txt`: single entry written LAST. Replicate the existing lines
  (`TrafficRecorderTask.close():325-333` — started/finished at, sampling rates, duration) where
  they make sense for a one-shot export, and add: `exported N sessions`, `exported <bytes> of
  data`, `skipped M sessions (evicted or write-locked during export)`, `snapshot contained T
  sessions`. Nothing parses this file (verified) — formatting is free, keep it human-readable.
- Failure path (BackupTask model, :239-242): on ANY exception → close streams quietly →
  `exportFileHandle.fileForFetchFuture().getNow(null)` → if non-null `exportService.deleteFile(id)`
  → rethrow wrapped in the task's failure. Success → return `fileForFetchFuture().get()`.
- Task shape: `TrafficRecordingExportTask extends ClientCallableTask<TrafficRecordingExportSettings,
  FileForFetch>`; settings record carries `(catalogName, chunkFileSizeInBytes)`; NO task traits;
  progress = `updateProgress((processed * 100) / snapshotSize)` from the D2 callback.

### D5. gRPC stub regeneration (mechanical, but easy to get wrong)

The generated classes are **committed** under
`evita_external_api/evita_external_api_grpc/shared/src/main/java/.../generated/`. The
`protobuf-maven-plugin` (shared `pom.xml:51`, protoc 3.25.8) regenerates them during
`generate-sources` directly into `src/main/java` (`clearOutputDirectory=false`), followed by a
`replacer` plugin pass. So: edit the proto → `mvn generate-sources` (or plain `mvn compile`) on
the `evita_external_api_grpc/shared` module → commit the regenerated stubs together with the
proto change. Adding an RPC + a new request message is wire-compatible; touch nothing existing.

### D6. JMH state setup (how to stand up the recorder without a server)

Construction template — copy from `OffHeapTrafficRecorderTest.setUp`
(`evita_test/evita_functional_tests/src/test/java/io/evitadb/store/traffic/OffHeapTrafficRecorderTest.java:92-115`):
`new OffHeapTrafficRecorder(blockSize)` →
`init(catalogName, new FileManagementService(storageOptions), new Scheduler(new
ImmediateScheduledThreadPoolExecutor()), storageOptions, TrafficRecordingOptions.builder()...)`.
`DiskRingBufferTest` (same package) is the template for buffer-level states. For benchmarks:

- Control the flush deterministically — set a huge `trafficFlushIntervalInMilliseconds` and call
  the D2 drain hook explicitly inside the benchmark method / setup, so JMH measures the drain, not
  a background race. Use the `Immediate...` executor only where synchronous execution is wanted;
  use a real `ScheduledThreadPoolExecutor`-backed `Scheduler` for the interference benchmark.
- The perf module is outside the default reactor (`full` profile); benchmarks run standalone via
  their `main()`. Run long benchmarks backgrounded; detect completion by result-file content,
  never PID liveness.

### D7. Deterministic concurrency-test recipes (for the R2 gate tests)

- Force rotation: `trafficDiskBufferSizeInBytes` = a few hundred KiB, sessions of known payload
  size (fixed-length query descriptions), `trafficFlushIntervalInMilliseconds = 0`; N sessions ×
  size > buffer size guarantees eviction. Session byte size is observable via
  `SessionLocation.location().recordLength()`.
- Force a wrapped session: fill until `ringBufferTail` is near the end (sizes are deterministic,
  compute the fill), then close one more session — it must span the wrap; assert via its
  `SessionFileLocation.endPosition() > diskBufferFileSize`.
- Deterministic mid-export eviction: inject pacing between D2 iterations via the
  `sessionConsumer` (the test supplies a consumer that latches after session K while another
  thread drains new sessions until eviction of session K+1 is observed in `sessionLocations`),
  then release the latch and assert `skipped >= 1` and the zip round-trips cleanly. This needs no
  production test hook — the consumer callback IS the seam.

---

## Phase 2 — H1 bug hunt (strict TDD: failing repro test first, then fix)

Verified candidates, roughly by severity. For each: write the failing test, confirm the defect is
real, fix minimally, keep the test as regression. Anything that doesn't reproduce gets a pinning
test documenting the actual behavior instead.

1. **Writer `OverlappingFileLockException` crash** — reader/exporter holds a shared region lock on
   the same `FileChannel`; writer's `fileChannel.lock(...)` (:918) throws unchecked
   `OverlappingFileLockException` NOT caught by `lockAndWrite`'s `IOException` handler (:926) →
   propagates out of `freeMemory()` into `DelayedAsyncTask`. Repro: hold `getRecordings` stream
   open over a session while forcing a flush that overwrites it. **Resolution: the settled
   Phase-1a in-JVM lock** — the failing test comes first and stays as the regression net.
2. **Reader-vs-reader silent drops** — two concurrent readers (UI `getRecordings` + export) on
   overlapping regions of the same channel: second one gets `OverlappingFileLockException` →
   `lockAndRead` returns null → record silently dropped (:961). **Resolution: shared spans under
   the Phase-1a in-JVM lock.** The generic "read path returns null on failed re-validation with no
   accounting" issue remains its own fix — surface via counters/metric + export metadata.
3. **`ringBufferHead`/`ringBufferTail` visibility race** — plain non-volatile `long`s (:162/:167)
   written by the flush thread, read by reader threads in `isSessionLocationStillInValidArea`
   (:873) and `readSessionRecords`; no happens-before edge (the `FileLock` is not a JMM
   synchronization point). Stale head/tail can validate an already-overwritten span → garbled
   read. **Resolution: the Phase-1a lock's acquire/release edge + volatile fields.**
4. **`RingBufferInputStream` wrap defects** (`.../store/traffic/stream/RingBufferInputStream.java`):
   (a) single-byte `read()` leaves `position` desynchronized by one after wrap (:59-66);
   (b) bulk `read(byte[],int,int)` never advances `position` in the wrap branch (:68-80) and
   ignores short reads from the delegate;
   (c) `skip`/`skipNBytes` never move the delegate in the non-wrap case (:83-99).
   Unit-test each against a small backing file, wrap boundary crossings in all three paths.
   Directly relevant to "reverse-read correctness under wrap and eviction".
5. **`lockAndWrite` swallows `IOException`** (:926-928) — a failed write logs and returns; the
   session is then registered via `sessionWritten` as if valid → future readers decode garbage.
   Should surface (fail the drain for that session + not register the location).
6. **Predicate AND vs. OR doc mismatch** — `TrafficRecorder.createRequestPredicate`
   (`evita_engine/.../spi/store/catalog/trafficRecorder/TrafficRecorder.java:69+`) combines
   criteria with AND; `TrafficRecordingReader` javadoc says OR. Decide intended semantics (AND is
   almost certainly intended — it matches evitaLab filtering), fix the docs, pin with tests.
7. **`ExportSessionSink.compressToFinalDestination` wrap handling** (:377-403) — verify a session
   whose bytes wrap the buffer end is copied correctly (seek+copy of `recordLength` from
   `startingPosition` reads past EOF or garbage if not two-segment aware). If broken, the new
   export shares the fix.
8. **Sampling semantics contradictions** — `TrafficRecordingOptions.trafficSamplingPercentage`
   javadoc ("percentage of traffic that should be recorded, 0–100") vs `OffHeapTrafficRecorder`
   field javadoc (:179 "Zero means that all records are stored") vs code: `samplingPercentage == 0`
   records nothing (`createSession:246`), and `computeCurrentSamplingRate` returns 0 for the empty
   state while its javadoc says 100 (:623-628). Pin intended behavior with tests, reconcile docs.
9. **`stopTrafficRecording` double `onNext`** —
   `EvitaTrafficRecordingService.stopTrafficRecording` calls `responseObserver.onNext` twice
   (:292 + :297). Verify and fix.
10. **Memory-shortage paths** — `MemoryNotAvailableException` → `discardSession` (:601): test no
    block leak (all block ids returned to `freeBlocks`), no partial session reaches disk, correct
    counters. Also fix the copy-paste message on `DATA_TOO_LARGE` (:846 says "No free slot…").
    Note the static pre-built exceptions carry meaningless stack traces — acceptable (control
    flow), but document.
11. **`readSessionRecords` end-detection modulo edge** (:830) — `lastFileLocation ==
    endPosition % diskBufferFileSize`: probe the exact-wrap case (session ending at byte
    `diskBufferFileSize` → wrapped end 0) for premature/missed termination.
12. **`TrafficRecorderTask` partial-file delete parity** with `BackupTask` (see 1b) — on failure
    the zip stays in export storage today.
13. **Housekeeping**: stale service registration file
    `evita_store/evita_store_server/src/main/resources/META-INF/services/io.evitadb.spi.store.io.evitadb.spi.store.catalog.model.TrafficRecorder`
    (malformed FQN) — confirm dead and remove.

Coverage gate: ≥70% line coverage on every touched class (`OffHeapTrafficRecorder`,
`DiskRingBuffer`, `SessionTraffic`, `RingBufferInputStream`, `InputStreamTrafficRecordReader`,
serializers, new task/engine methods).

### Concurrency test design (the R2 gate)

- **Rotation-under-export stress test**: tiny disk buffer (e.g. 256 KiB) + continuous session
  writes while export runs; assert (a) export completes, (b) every `.bin` entry round-trips fully
  through `InputStreamTrafficRecordReader` (no truncated tail record), (c)
  `exported + skipped == snapshot size`, (d) writer thread never throws and write throughput
  during export stays within an agreed envelope (functional smoke; the precise number lives in the
  JMH interference benchmark).
- **Wrap test**: force sessions to span the physical buffer end, then export.
- **Deterministic mid-export eviction**: latch inside the per-session copy (test hook or small
  buffer + pacing) so a specific session is provably evicted mid-export → must appear in the
  skipped count, not in the zip.
- **Flush-vs-export**: export with pre-export drain enabled racing the periodic `freeMemory` task
  and an attached live `ExportSessionSink` — no deadlock (`freeMemory` is `synchronized`), sink
  still receives eviction callbacks, export skips consistently.

---

## Phase 3 — H2 optimizations (numbers-driven)

Re-run the Phase-0 suite after Phase 1+2; optimize only what the numbers justify, in this order of
expected payoff (all anchors verified in Phase 0 list): per-record → per-session lock granularity
on the `getRecordings` read path — ONLY with an `onClose`-based token release solving the D1
lazy-stream leak caveat, and only if the numbers still justify it after the span-lock redesign
already removed the 2-syscalls-per-record OS-lock cost (re-measure the read benchmarks right
after the redesign lands to attribute the delta); unfair `freeBlocks` queue;
write-path allocation (label streams, per-record lambda+deque node, `Instant` boxing);
reverse-read without full-session buffering (or index support, per the inline comment at
`DiskRingBuffer.java:499`); per-block → per-session eviction/sink callbacks. Each
optimization: before/after JMH numbers captured in this spec dir and in the PR description. Export
interference benchmark re-run last to certify R2.

## Phase 4 — docs & wrap-up

- Document the export operation where traffic recording is documented (user docs + javadoc on the
  new contract methods); note the metadata.txt additions and the new file `origin`.
- PR per repo conventions (`feat: ...`, `Ref: #1282`, target `dev`, Copilot review via API), issue
  checklist mapped to commits; before/after tables included.

## Acceptance gates (from the issue, restated)

1. Zip downloadable via `ListFilesToFetch` + `FetchFile`, catalog-specific, triggered over
   `GrpcEvitaTrafficRecordingService`.
2. R3 round-trip test green (`InputStreamTrafficRecordReader` consumes every exported `.bin`).
3. R2 proven: rotation-under-export stress green; skipped sessions counted in `metadata.txt`;
   JMH interference numbers show no writer stall/corruption.
4. H1 fixes each carry a failing-first regression test; touched classes ≥70% line coverage.
5. JMH suite committed with before/after numbers for every applied optimization.

## Explicit non-goals (scope guard)

Single disk-buffer export only (no memory-only/off-heap staging snapshot, no open in-flight
sessions); no Java `EvitaClient` exposure; no durable cross-restart storage; no new `ExportService`
backend.
