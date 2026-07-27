# Phase 0 JMH baseline — issue #1282

Captured before any production code changes, on branch
`1282-traffic-recording-on-demand-export-of-in-memory-on-disk-recordings-plus-bug-hunt-jmh-performance-hardening`.

Benchmark suite: `evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/TrafficRecording*.java`
(shared construction helper: `TrafficRecordingBenchSupport`). Covers Phase 0 items 1-4 from
`PLAN.md`; item 5 (export interference) is deferred to Phase 1, once the export path exists.

## Environment

- CPU: AMD Ryzen AI 9 HX 370 w/ Radeon 890M, 24 threads
- JVM: OpenJDK 21.0.11 (build 21.0.11+10-1-24.04.2-Ubuntu), default GC
- JMH 1.37, `@Fork(1)`, `@Warmup(3x1s)`, `@Measurement(5x1s)`, `@BenchmarkMode({AverageTime, SampleTime})`
- Run via: `java -cp target/benchmarks.jar io.evitadb.spike.<Class> "<Class>" [-prof gc]`
  (the shaded jar's default main class, `io.evitadb.performance.ArtificialTestRunner`, runs the
  **entire** legacy spike suite regardless of arguments — invoke each benchmark class's own
  `main()`, which delegates to `org.openjdk.jmh.Main`, directly instead of `java -jar benchmarks.jar`.)

Raw logs: `1-write.log`, `2-flush.log`, `3-index.log`, `4-read.log` (not committed; available on
request / reproducible by re-running the suite).

## 1. Write throughput/allocation (`TrafficRecordingWriteBenchmark`)

`recordQuery`/`recordFetch`/`recordMutation` against an initialized `OffHeapTrafficRecorder` at
100% sampling; `-prof gc` for allocation rate. `recordQuery` carries the `@Threads(1/4/16)` sweep
(the plan's primary hot-path emphasis); `recordFetch`/`recordMutation` are single-threaded only.

| Benchmark | payload | avg time (ns/op) | alloc (B/op) |
|---|---:|---:|---:|
| recordFetch | 64 | 1 094.8 ± 115.7 | 2 878.4 |
| recordFetch | 1024 | 1 717.5 ± 36.1 | 3 855.4 |
| recordFetch | 4096 | 4 311.3 ± 1 404.6 | 6 972.6 |
| recordMutation | 64 | 510.3 ± 12.3 | 910.2 |
| recordMutation | 1024 | 1 280.9 ± 65.5 | 1 847.9 |
| recordMutation | 4096 | 4 103.5 ± 153.9 | 4 980.7 |
| recordQuery (1 thread) | 64 | 1 443.2 ± 81.2 | 3 639.8 |
| recordQuery (1 thread) | 1024 | 2 069.1 ± 129.5 | 4 620.5 |
| recordQuery (1 thread) | 4096 | 4 298.6 ± 522.0 | 7 756.2 |
| recordQuery (4 threads) | 64 | 2 935.6 ± 284.7 | 3 648.5 |
| recordQuery (4 threads) | 1024 | 4 239.3 ± 652.8 | 4 669.3 |
| recordQuery (4 threads) | 4096 | 8 799.2 ± 993.1 | 7 759.9 |
| recordQuery (16 threads) | 64 | 15 724.1 ± 2 828.2 | 3 653.3 |
| recordQuery (16 threads) | 1024 | 20 727.5 ± 1 544.8 | 4 631.5 |
| recordQuery (16 threads) | 4096 | 36 328.9 ± 5 256.0 | 7 828.1 |

Observations:
- Allocation per call is dominated by the query payload size, not by thread count - consistent with
  the "known perf-sensitive spot" list (label-merging streams, per-record lambda + deque node,
  `Instant` boxing all scale with call count, not payload).
- `recordQuery` time-per-op degrades faster than linearly with thread count (1->4 threads: ~2x at
  payload 64; 4->16 threads: another ~5x) - contention on the shared `finalizedSessions` queue /
  block allocation is the suspected cause; worth isolating in Phase 3 with `-prof` lock profiling.
- `recordMutation` is consistently the cheapest of the three (no label-merging, no query object
  graph) and `recordFetch`/`recordQuery` are close, as expected given they share most of the
  container-construction code.

## 2. Flush drain cost (`TrafficRecordingFlushBenchmark`)

`OffHeapTrafficRecorder#freeMemory()` (reached via reflection - it is private, only ever invoked on
a schedule) draining N pre-finalized sessions of ~S bytes each. Disk buffer deliberately small
(8 MiB) so repeated drains wrap the ring and pay a real eviction-scan cost.

| sessions (N) | session size | avg time (us/op) |
|---:|---:|---:|
| 1 | 1 024 B | 3.38 ± 0.38 |
| 1 | 16 384 B | 7.26 ± 0.51 |
| 10 | 1 024 B | 31.60 ± 1.74 |
| 10 | 16 384 B | 80.96 ± 10.73 |
| 100 | 1 024 B | 326.09 ± 17.04 |
| 100 | 16 384 B | 820.23 ± 39.09 |

Drain cost scales roughly linearly with session count (as expected - one `appendSession` +
`append` + `sessionWritten` per session) and sub-linearly with session size (fixed per-session
overhead, e.g. `updateSessionLocations`'s O(n) eviction scan over `sessionLocations`, dominates at
small sizes).

## 3. Index (re)build latency (`TrafficRecordingIndexBenchmark`)

`OffHeapTrafficRecorder#index()` (private, wraps `DiskRingBuffer#indexData`) fully rebuilding the
in-memory index from a pre-filled, pre-drained disk buffer - i.e. the `IndexNotReady` window.

| sessions in buffer | avg time (us/op) |
|---:|---:|
| 100 | 1 074.3 ± 42.1 |
| 1 000 | 11 061.9 ± 941.1 |
| 5 000 | 57 153.5 ± 1 109.6 |

Scales linearly with session count (~11 us/session), as expected for a full Kryo-deserialize walk
of every session. At 5 000 sessions the `IndexNotReady` window is already ~57 ms - a real,
user-visible latency for the first read after a cold start or `releaseIndex()`.

## 4. Forward vs. reverse read (`TrafficRecordingReadBenchmark`)

`getRecordings` vs `getRecordingsReversed` over an already-indexed buffer.

| sessions | forward (us/op) | reverse (us/op) |
|---:|---:|---:|
| 100 | 901.0 ± 11.7 | 926.0 ± 129.4 |
| 1 000 | 9 247.6 ± 961.0 | 9 075.2 ± 426.9 |
| 5 000 | 46 085.3 ± 3 008.8 | 46 045.3 ± 668.1 |

**Caveat:** each session in this benchmark holds exactly one record, so the `ArrayList` +
`Collections.reverse` cost flagged in the plan (`DiskRingBuffer.java:499-507`) barely shows -
reversing a 1-element list is nearly free. These numbers establish the per-session streaming
baseline; a multi-record-per-session variant is needed to properly quantify the reverse-read
buffering overhead, and should be added when Phase 3 re-measures this benchmark.

## Known perf-sensitive spots still to correlate (per plan, not yet profiled in isolation)

- Per-record OS file lock in `readSessionRecords` (2 syscalls/record) - addressed structurally by
  the Phase 1a span-lock redesign; re-measure benchmark 4 after that lands to attribute the delta.
- Fair `ArrayBlockingQueue` for free blocks - not yet isolated; candidate for a dedicated Phase 3
  micro-benchmark under thread contention.
- Label-merging streams / per-record lambda + deque node / `Instant` boxing on the write path -
  the allocation numbers above (2 878-7 828 B/op depending on payload) are the aggregate signal;
  Phase 3 should re-measure with `-prof gc` after removing each allocation individually to attribute
  savings.
