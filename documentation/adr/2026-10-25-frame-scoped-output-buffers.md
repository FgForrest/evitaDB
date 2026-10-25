---
title: Output buffers are borrowed per frame from one JVM-wide pool, and the configured size stays a hard limit
date: 2026-10-25
updated: 2026-10-25 20:10
status: accepted
kind: optimization
issues: [1792, 1793]
prs: [1801]
areas: [evita_store/evita_store_key_value/src/main/java/io/evitadb/store/kryo, evita_store/evita_store_key_value/src/main/java/io/evitadb/store/offsetIndex, evita_engine/src/main/java/io/evitadb/core/metric/event/storage]
supersedes: []
superseded-by: []
relates: [2026-07-10-more-optimized-data-structures, 2026-09-14-closed-engine-resource-release]
---

# Output buffers are borrowed per frame from one JVM-wide pool, and the configured size stays a hard limit

Every `ObservableOutput` used to own an `outputBufferSize` array for its whole life, although the array holds
data only while one frame is being assembled. Outputs now borrow an array of exactly that size from
`ObservableOutputBufferPool.INSTANCE` when a frame starts and return it once the frame is appended. The compaction
copy no longer needs a frame-sized buffer at all: it streams each frame through a 16 KiB chunk. The setting keeps
its meaning and its exact size. Nothing grows or shrinks, and the file format is unchanged.

## Why

A frame is `length | control | generationId | payload | CRC`. The length goes first, so a frame has to be complete
in memory before it is appended to the append-only file, and that is what `outputBufferSize` sizes. A record larger
than the buffer already continues in further frames, so the setting bounds one frame and one indivisible value (a
string or a byte array), never a record.

Between frames the array holds nothing, yet full-size arrays were held by the keeper (one per data file written in
the last five minutes, per catalog), by every transaction from its first mutation to commit and then by the
off-heap output free list, by every engine bootstrap write, by every compaction (plus a soft-referenced scratch
buffer per compacted file) and by every sampled traffic-recording session. On G1 an array of at least half a region
is a humongous object occupying whole regions: a 4 MiB array costs 5 MiB of heap on a 2 GB heap and 8 MiB on
8-31 GB. The functional suite, which keeps hundreds of catalogs open in one 8 GB JVM, ran out of heap with the
server's 4 MB setting.

A frame census of a production e-commerce catalog (~6.9 million records, 18 collections, 5.3 GB) shows how little
of the array a frame uses: median record 138 B, p99 8.3 KiB, p99.99 216 KiB, largest 5.0 MiB (written as
continuation frames, 57 in the whole catalog). The largest mutation in 3,587 WAL transactions was 28.9 KiB.

Separately, the compaction copy loaded each stored frame into a scratch buffer of the copier's own
`outputBufferSize`. A file may hold frames up to the size of whichever instance wrote it, so an embedded instance at
the 2 MiB default compacting a catalog that a server wrote with 4 MB failed with an `ArrayIndexOutOfBoundsException`
on the first larger frame and suspended the catalog.

### Previous state

`ObservableOutput` allocated its array in its constructor, plus a second array of the same size for compression.
To stop that per-transaction allocation (14.5 % of all allocated bytes in the measured write churn), the
2026-07-08 decision recorded in `2026-07-10-more-optimized-data-structures` added a keyless free list of whole
off-heap `ObservableOutput` instances to `ObservableOutputKeeper`, and rejected pooling only the `byte[]` because the
`Checksum`, the `Deflater` and the compression array would still have been allocated per transaction. That fixed the
allocation rate. It could not fix retained memory, because a recycled output still owned its arrays between frames,
and so did every output the keeper cached per file.

## Options considered

### Option A - borrow the array per frame from a JVM-wide pool (chosen)

An output keeps its small per-file state (stream, checksum, deflater, positions) and borrows the array when a frame
starts. It returns the array once the frame is on disk.

- **Pros:** the number of arrays follows how many threads assemble a frame at the same moment, not how many files
  or transactions are open; arrays keep exactly the configured size; no format change; one borrow and one return
  per frame, no extra copy. The 2026-07-08 objection to pooling only the array no longer applies: the churn it
  measured was the arrays, and both of them, the compression array included, now come from the pool.
- **Cons:** the pool is shared by every engine and catalog in the JVM, so its metrics are JVM-wide and carry no
  catalog; every frame passes the pool's monitor twice; an array whose borrower never returns it stays counted; a
  transaction's output still creates its own checksum and, with compression on, its own `Deflater`.

### Option B - grow buffers on demand up to the configured size (declined)

Start every output small and enlarge its array to the frame being written; in compaction, enlarge the scratch buffer
to the frame length read from the source header.

- **Pros:** most outputs would stay tiny; the smallest change to the code.
- **Cons:** retained memory becomes a function of the largest records the instance has seen.
- **Rejected because:** the configured `outputBufferSize` must stay a hard, predictable limit. An operator sizes the
  heap from the configuration, and memory that follows the data cannot be planned. The same rule rejects growing the
  compaction scratch buffer. Revisit only if the setting is redefined; nothing measured would change this.

### Option C - small fixed frames written into the format (declined for now)

With 256 KiB frames no array would be humongous on any heap, and 99.99 % of records would still fit in one frame.

- **Pros:** removes the humongous allocation entirely.
- **Cons:** an indivisible value must fit in one frame, so the limit on a single string or byte array would drop
  from about 4 MiB to 256 KiB, and `outputBufferSize` would become a different public setting.
- **Rejected because:** values cannot span frames today. Allowing it needs reader support and tests, and option A
  gets most of the saving without touching the format. Revisit if values become splittable across frames.

### Option D - off-heap buffers (declined)

- **Rejected because:** they avoid G1's humongous penalty but not the number of buffers, and the JVM zeroes them on
  allocation, so the memory is committed anyway.

Three ways to avoid buffering a whole frame were also rejected. Writing a placeholder length and patching it later
rewrites bytes already handed to the file, breaking append-only immutability, and a crash before the patch leaves a
zero length that stops every forward scan. Putting the length at the end does not work because WAL replay and
verification read forward. Serializing twice to measure first doubles serialization CPU or needs a size estimator
per serializer.

### The compaction copy

The copy streams: the source header gives the frame length before any payload byte is read, so the target header
is written at once, the payload passes through a 16 KiB chunk, and the CRC is verified on the way. The copied frames
are byte-identical to what the buffered copy produced. Two alternatives were declined: growing the scratch buffer to
the frame length (option B's rule), and a clear error with aligned defaults, which only names the failure while a
catalog written by a server still cannot be compacted by an embedded instance.

## Decision

**Chosen: option A, with the streaming compaction copy.** The drivers were retained heap and predictable memory.
Option A ties retained memory to concurrency and bounds the idle part, while the configured size stays exact. The
pool keeps one fixed array of idle references across all sizes, at most twice the processor count, and evicts the
oldest when full. Per-size buckets were not used because they bound neither their own metadata nor the memory held
in sizes no longer requested, while one LRU store lets a changed setting warm up without keeping obsolete sizes.
Option C would win if indivisible values could span frames.

## Key technical details

- `ObservableOutputBufferPool` (`io.evitadb.store.kryo`): `INSTANCE` is sized by `availableProcessors()`, keeps at
  most `2 x availableProcessors()` idle arrays across all sizes and looks them up by exact size. Idle memory is
  therefore at most `2 x CPUs x outputBufferSize`. `ObservableOutputChangeEvent` fires only when the pool allocates
  or drops an array, never on reuse. Its counters cover borrowed and idle arrays of the whole JVM.
- `ObservableOutput`: pooled outputs start detached and borrow on their first write. They return the array once
  the frame is appended, or on flush, reset or close when nothing is pending. A record chained over continuation
  frames keeps one array for the whole record, and the compression array is borrowed only while a frame is
  finished. An output discarded mid-frame without `close()` leaves its array counted by the pool.
- `ObservableOutputKeeper` caches open file streams only; its free list of off-heap outputs is gone. Lease,
  release and close are atomic per entry. Done as two steps, a close could release an array that a writer had just
  leased and hand it to another output.
- A failed serialization abandons only its unfinished frame (`doWithOnBufferOverflowHandler`) and never calls Kryo's
  `reset()`, which zeroes the output's byte total and with it every later `FileLocation`. After a partial append
  (header written, payload failed) the output refuses further appends, and the keeper reopens the file at its
  physical length.
- A single value that cannot fit in one frame fails with a `KryoException` naming the value size and
  `outputBufferSize`, before Kryo writes any of it.
- `StorageRecord#copyRawRecord` and `ObservableOutput#writeDirect` implement the streaming copy
  (`OffsetIndexSerializationService.COPY_CHUNK_SIZE`). The per-index compaction scratch buffer is gone.
- `StorageRecord`'s payload-writer constructor reports the location of the whole record, all frames included. The
  WAL size accounting adds that length, and reporting only the last frame made a transaction with one mutation
  larger than `outputBufferSize` fail at commit with a WAL size mismatch.

## Verification

Base is the code before this change. Both arms ran on the same machine, one at a time.

Heap, full functional suite in the CI configuration (`-P full,unitAndFunctional`, 4 forks, 8 GB main fork, G1 log
of the main fork):

| | before, 4 MiB | after, 4 MiB | before, 2 MiB | after, 2 MiB |
|---|---:|---:|---:|---:|
| mean heap after GC | 7,142 MB | 2,002 MB | 5,811 MB | 1,791 MB |
| max humongous regions | 1,737 | 212 | 1,140 | 120 |
| GCs triggered by a humongous allocation | 1,129 | 36 | 293 | 28 |
| total GC pause | 143.4 s | 17.1 s | 51.7 s | 18.1 s |

Write throughput, 401 WAL transactions of the same production catalog replayed into an embedded instance
(`WalReplayBenchmark`, 4 MiB, 12 forks in ABBA order): in the undisturbed pairs after/before was 0.95-0.98, inside
the run-to-run spread, so throughput is unchanged. Allocation fell by 2.3 % (170.5 GB to 166.6 GB per run), stable
across every fork.

Compaction: the same WAL replayed with `outputBufferSize` 2 MiB onto the catalog its server wrote with 4 MB.
Before, the copy failed with `ArrayIndexOutOfBoundsException` and suspended the catalog (294 transactions applied,
108 failed); after, all 401 applied.

Tests: `ObservableOutputBufferPoolTest`, `ObservableOutputTest`, `ObservableOutputKeeperTest`, `StorageRecordTest`
and `EvitaMultiFrameWalTest`, which commits a mutation larger than the buffer end to end and failed before the fix.

## Consequences & open follow-ups

- The `io_evitadb_storage_observable_output_change_*` metrics describe the JVM-wide pool and carry no catalog label.
- The embedded default (`StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE`, 2 MiB) and the server configuration (4 MB)
  still differ. A larger setting no longer costs an array per open file, so the reason to keep them apart is gone;
  aligning them is a separate change.
- The frames that still fill the array are offset-index fragments, which
  `OffsetIndexSerializationService#computeExpectedRecordCount` sizes to fill it by construction, and index parts
  written as one multi-MiB record (reference cardinality up to 5.0 MiB in the census). Neither was changed here.

## Related work

- **`2026-07-10-more-optimized-data-structures`** - its 2026-07-08 free list of whole off-heap outputs is removed
  here, and its reason for not pooling only the array no longer holds (see *Options considered*).
- **`2026-09-14-closed-engine-resource-release`** - its close ordering for `ObservableOutputKeeper` still holds;
  the keeper now caches file streams rather than buffers.

## Timeline

- **2026-10-24** - a WAL replay at 2 MiB suspends, in compaction, a catalog its server wrote at 4 MB; frame census
  of the production catalog
- **2026-10-25** - issues #1792 and #1793 filed, implemented and measured; PR #1801 opened
