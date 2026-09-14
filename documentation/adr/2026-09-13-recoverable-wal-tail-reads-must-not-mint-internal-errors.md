---
title: Off-record number reads must not restore a buffer limit the read has invalidated
date: 2026-09-13
updated: 2026-09-14 05:40
status: accepted
kind: fix
issues: [1551]
prs: []
areas: [evita_store/evita_store_key_value/src/main/java/io/evitadb/store/kryo, evita_store/evita_store_server/src/main/java/io/evitadb/store/wal, evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog, evita_engine/src/main/java/io/evitadb/core/cdc]
supersedes: []
superseded-by: []
relates: [2026-08-28-attributable-internal-error-metrics, 2026-07-18-paged-index-corruption-and-flush-failure-boundary, 2026-08-24-grpc-streaming-backpressure-readiness-gate]
---

# Off-record number reads must not restore a buffer limit the read has invalidated

`ObservableInput#simpleIntRead` and `#simpleLongRead` read a bare number from between records: they cap
`limit` for the duration of the read and put the captured value back in a `finally`, unconditionally. When the
read has to call `require(int)` in between — which refills the buffer, and may compact it — `limit` has already
been set to a value describing the buffer as it now is. The restore overwrites that with a number describing a
buffer that no longer exists. After a compaction it is not even measured from the same origin, because
`position` was reset to zero and the remaining bytes shifted to the front.

Those two methods read the 4-byte content-length prefix and the 8-byte cumulative checksum that frame every
WAL transaction. They therefore run at **every transaction boundary**, which is exactly where a reader tailing
a write-ahead log sits.

## Why

A production deployment reported an internal-error health problem in **57.6 %** of probe cycles, continuously,
with `io_evitadb_errors_total` rising by 876 in 12 hours from a single origin while
`io_evitadb_client_errors_total` stayed flat at 0. The origin was a premise in
`AbstractMutationSupplier#readAndRecordTransactionMutation` building a `GenericEvitaInternalError`, which the
supplier then handled cleanly as a graceful end-of-stream — but *construction* is what the observability agent
counts (`2026-08-28-attributable-internal-error-metrics`), so the metric moved anyway.

The obvious reading, and the one issue #1551 was filed on, is that change-data-capture was reading a WAL tail
the commit pipeline had not finished writing. **That reading is wrong**, and the correction is the substance
of this record:

- The writer emits the 4-byte prefix **and** the whole leading `TransactionMutation` record from one
  `ByteBuffer` in a single write loop (`AbstractMutationLog:1381-1393`). A reader cannot observe half of them.
- A file too short for the transaction is already rejected upstream by the guards at
  `AbstractMutationSupplier:441` and `:465`, which return `empty()` before the premise is reached.

So the premise can only fail on a file whose bytes are all present — which means the *reader* miscounted, not
the writer. It did: given an understated content length, the truncation guard at `:465` passes trivially, the
leading record still reads correctly because its bytes really are there, and the premise then compares a
too-small prefix against the record's true declared extent and fails. That is the reported error, on an intact
and fully flushed WAL, with no concurrent append involved.

### The two-year-old "probably"

`AbstractMutationLog`'s javadoc had said since 2024 that *"there is probably some bug in our observable input
implementation"*, and `avoidPartiallyFilledBuffer` existed to route around it. This is that bug. It was never
found because nothing drove the off-record reads through a partially filled buffer: the existing
`ObservableInputTest` coverage exercises **records**, and records are read through a different path
(`markStart`/`markPayloadStart`/`markEnd`) that does not perform this restore. Both the javadoc and the
workaround are removed by this change — see *The workaround* below.

## Options considered

### Option A — repair the limit bookkeeping in the two off-record readers (chosen)

Restore the pre-read cap only when the read did not move the buffer, via
`restoreLimitAfterOffRecordRead(cappedLimit, totalBeforeRead)`. The test is exact rather than heuristic:
`require`'s fill branch raises `limit` by a non-zero count, and its compaction branch raises `limit` *and*
advances `total` by the old `position`. If neither changed, `require` returned from bytes already buffered and
the capture is still valid.

- **Pros:** fixes the cause, so every symptom it produces goes with it — the miscounted prefix, the silently
  wrong bytes, and the `position > limit` state from which `require` computes a negative `remaining` and hands
  `System.arraycopy` a negative length. No new state, no configuration, no behaviour change on any path where
  the read was served from the buffer.
- **Cons:** it is a hot path shared by every reader in the database, not only the WAL, so the blast radius of
  being wrong is wide. Mitigated by the full functional suite rather than by argument.

### Option B — decide the mismatch from the record's declared extent (declined as the primary fix)

Reconstruct where the transaction must end from `recordLength()` and `walSizeInBytes`, and return `empty()`
when that end lies past the visible file. This was implemented first, before the cause was known.

- **Pros:** stops the metric moving without touching a shared read path; cheap and backportable.
- **Rejected because:** it treats a symptom of Option A's defect and would **mask** it. The state it tests for
  — a transaction extending past the end of file — is one the writer cannot produce, for the two reasons given
  under *Why*. Its own counterfactual test can only reach the premise by falsifying the prefix by hand. With
  the reader fixed the premise is no longer reached in the tailing case at all, so what remains is a genuine
  reader fault or genuine corruption, and silently returning `empty()` would hide exactly the class of defect
  this record is about. Revisit only if a mismatch is ever shown to arise from a cause the reader cannot
  control.

### Option C — switch CDC to the bounded "live" stream (declined)

The fix proposed by the issue itself: call `getCommittedLiveMutationStream(from, bound)` instead of the greedy
`getCommittedMutationStream(from)`, whose contract says *"DO NOT USE THIS METHOD if the WAL is being actively
written to"*.

- **Pros:** one line on the catalog side; matches the documented contract and the precedent in
  `TransactionManager`.
- **Rejected because:** it does not fix the symptom and adds a worse failure. `WriteAheadLogCorruptedException`
  is also an `EvitaInternalError` carrying no `@NotMonitored`, so it moves the same counter and raises the same
  gauge — the fix would only change the `error_type` label. The bound does not prevent the read either:
  `MutationSupplier` parses transaction N+1's header at `:202` and applies the version bound only at `:236`, so
  bounding at N still reads N+1. Safe mode is in fact *more* eager, requiring `contentRecordLength` rather than
  `fullRecordLength` — eight bytes less on disk before it deserializes.

### Option D — mark the tail-read failure `@NotMonitored` (declined)

Introduce a purpose-specific exception type carrying `@NotMonitored`, as the traffic recorder and the conflict
exceptions already do.

- **Pros:** an established house pattern with existing precedent.
- **Rejected because:** it would have silenced a true positive. The metric was reporting a real defect; the
  correct response to a loud alarm that turns out to be right is not a quieter alarm. `@NotMonitored` also
  silences the `ErrorOriginLogger` WARN, which is the only reason this was diagnosable at all. Revisit if a
  future recoverable condition genuinely needs to unwind a stack, where an exception is the mechanism rather
  than the report.

## Decision

**Chosen: Option A.** The metric was not lying. `io_evitadb_errors_total` was reporting a reader that returns
wrong bytes, and the work that started as "stop a recoverable condition polluting a health probe" ended as
"the condition was never recoverable and never a tail read".

Option B's guard was implemented before the cause was known and is **not** retained: see its rejection above.

### The workaround

`avoidPartiallyFilledBuffer` is removed in the same change. It was the routing-around for the defect Option A
fixes, and the alternative — keeping it as belt-and-braces — was rejected: it is the trap that produced this
issue. A caller reaching for a WAL stream met a method pair whose names describe an implementation quirk
rather than a contract, and change-data-capture picked the wrong one and stayed on it for two years. A flag
that no longer routes around anything, still consulted on the read path, is a worse hazard than the one it was
introduced for, because it invites the next reader to reason about a buffer condition that no longer exists.

What the flag *actually* controlled by the time it was deleted had nothing to do with buffer filling — that
meaning drifted away when `880e6a142` re-purposed it. It carried two real behaviours, both of which survive
unchanged, now derived from the one fact they were always about:

- a caller that names a `requestedVersion` is **asserting that version is durably written**, which lets the
  reader deliver a record whose content is on disk without waiting for the trailing cumulative checksum the
  writer emits in a later `FileChannel.write`;
- and failing to reach that version is surfaced as a `WriteAheadLogCorruptedException` rather than an
  exhausted stream — the loud-vs-graceful contract `880e6a142` introduced to stop the trunk-incorporation
  stage misreading a durably-written transaction as "already processed" and spinning forever.

Both now read directly off `AbstractMutationSupplier#requestedVersion`, a `@Nullable Long` that is `null` for
a greedy read. The boolean was derived from `requestedVersion != null` at the only place it was set
(`AbstractMutationLog#createSupplier`), so this removes a parameter that could disagree with the field it
shadowed, and nothing else.

`AbstractMutationLog#getCommittedMutationStreamAvoidingPartiallyWrittenBuffer` is renamed to
`getCommittedLiveMutationStream`, matching the SPI method it implements
(`CatalogPersistenceService#getCommittedLiveMutationStream`) so the concept carries one name the whole way
down. **Readers of `2026-07-18-paged-index-corruption-and-flush-failure-boundary` will find the old name
throughout its `bug-03-commit-progress-hang.md` scenario; it is this method.** The two entry points are *not*
collapsed into one — they are genuinely different contracts, and the greedy one is what recovery and replay
need.

## Key technical details

- `ObservableInput#restoreLimitAfterOffRecordRead` — the fix, called from the `finally` of both
  `simpleIntRead()` and `simpleLongRead()`. Its javadoc carries the reasoning, because the next person to read
  that `finally` will not be reading this record.
- **The failure is not confined to the WAL.** `simpleIntRead`/`simpleLongRead` are the shared mechanism for
  reading a number that sits outside any `StorageRecord`; the WAL is simply their heaviest user.
- **Why the crash is rarely what is seen.** `seek(FileLocation)` resets `limit`, `position`, `total` and
  `actualLimit` wholesale, so any reader that seeks before a record wipes the corrupt state. Within a
  transaction the WAL reader is **sequential** — Phase 2 mutations are not individually seeked — so corruption
  minted at a boundary carries into the following reads and surfaces there as `Buffer underflow`,
  `CorruptedRecordException`, a failed premise, or silently wrong data.
- `MutationSupplier#get()` — `currentFileLength` is now re-read after `moveToNextWalFile`. It was sampled from
  the *previous* file and then used as the new file's size, which disabled both end-of-file guards for the
  first record of every rotated file. Independent defect, found while tracing this one.
- `DefaultChangeCaptureSubscription#consumeQueue` — the queue fill is now wrapped, routing a failure to
  `onError`. It previously sat in a block guarded only by `finally { unlock }`, so a WAL read failure escaped
  entirely: `finished` stayed `false`, the subscription was never removed from its publisher, the WAL version
  it pinned was never released, and the subscriber was told neither `onError` nor `onComplete`. On the
  `request(n)` path the same throw propagated out of `Flow.Subscription#request(long)`, which
  reactive-streams forbids.

## Verification

Every test was run against the unfixed code first and shown failing, so none can pass for the wrong reason.

- `ObservableInputTest$BoundaryReadTests` — drives the WAL's own boundary sequence (8-byte checksum, 4-byte
  content length, 8-byte checksum, payload) over a trickle stream across 6 buffer sizes x 9 chunk sizes.
  **Single-threaded; no WAL, no concurrency, no Kryo pool.** Before the fix: **93 failing assertions** — 68
  `ArrayIndexOutOfBoundsException: arraycopy: length -N is negative`, 68 `position` past `limit`, and **50
  cases of a wrong value returned with no exception at all**. After: passes.
- Whole `ObservableInputTest` class after the fix: **27 tests, 0 failures**, including the pre-existing
  trickle-stream, compressed, uncompressed and partially-filled-buffer suites.
- `ConcurrentWalTailReadStressTest` — one writer appending 400 transactions of varied sizes while three
  readers tail through the greedy stream. Before the fix it failed with `KryoException: Buffer underflow` at
  `ObservableInput.require`; after, **3 consecutive runs green** with `escapedFailures=0; constructedErrors=0`,
  and a post-quiesce read returning every committed version in order. It lives in `evita_long_running_tests`:
  it costs ~9 s alone but was measured at **28.9 s** inside a full functional run, and tagging it `slow` where
  it was first written would have meant it never ran, since `unitAndFunctional` excludes that tag.
- `ChangeCaptureSubscriptionFillFailureTest` — with the catch removed, fails with the fill exception escaping
  `Subscription#request(long)`.
- Full `wal | cdc` tag sweep: **635 tests, 0 failures, 1 skipped**, including the gRPC and GraphQL subscription
  functional tests that exercise CDC end to end.
- Full functional suite: **23,756 tests, 0 failures**, 39 skipped, with the only error the Docker-dependent
  `ExportS3ServiceTest` that does not run in this environment.

### A test fixture was corrupting the evidence, and that is worth recording

The concurrent stress test originally failed for a reason that had nothing to do with any of the above. Every
WAL test built its Kryo pool as `new Pool<>(false, false, 1)` — an unsynchronized `ArrayDeque` — while
production uses `new Pool<>(true, false, 16)`, a `LinkedBlockingQueue`
(`DefaultCatalogPersistenceService:505`, `DefaultEnginePersistenceService:134`). The writer and every reader
draw from that one pool, so a racing `obtain()` handed **one Kryo to two threads**, and a Kryo is not
thread-safe. An identity-tracking probe added to the pool recorded four such violations in a single run
(`DOUBLE OBTAIN` / `FREE OF UNOBTAINED`), and the resulting garbage — a `BigDecimal` serializer dispatched at a
`LocalMutation` slot, a currency code read as `" g"` — is indistinguishable from a WAL read defect while
proving nothing about one. It cost a day of misattributed analysis.

The pool is now pinned to the production shape with no opt-out, and `KRYO_POOL_VIOLATIONS` stays in the fixture
so a recurrence is reported rather than inferred. **Removing it did not make the stress test pass** — that is
what isolated the real defect, and it is why the probe earns its place: a concurrency fixture that differs from
production silently converts every result into a question.

## Consequences & open follow-ups

- **The invariant now holds and is what the greedy stream rests on**: *a block that is safely written is
  immediately safe to read*. `ConcurrentWalTailReadStressTest` is the standing guard — it tails through the
  **greedy** stream, which is the variant with no durability assertion to fall back on, so a regression in the
  buffer bookkeeping fails there first. If that test is ever weakened, the case for having deleted the
  workaround goes with it.
- **Two further bookkeeping inconsistencies in `ObservableInput` were found by inspection and could not be made
  to fire.** They are recorded here so the next reader does not have to re-derive them, and **not** changed,
  because a hot path does not get edited on an argument:
  - `require():502` does `this.limit += count` after filling at the *local* `limit`, which is `actualLimit`
    when a record cap is in force; `this.limit = limit + count` would be correct on every path and identical
    wherever the two agree. The divergent path looks hard to reach — `constraintLimitWithRecordLength` caps
    precisely when the buffer holds more than the record still needs, in which case `require` returns early
    without filling.
  - The compaction branch at `:511-535` renumbers `position` and `total` but never `actualLimit`. `markEnd`'s
    `finally` and `seek()` both clear it, which is probably why it does not bite.
- **`TransactionLocations` has two defects in the same area**, found while tracing and out of scope here.
  `register()` does its lazy `locations == null` allocation *outside* the lock that `cut()` holds while setting
  the field to null — so two registrations can lose an array, and a `cut()` landing between the check and the
  `getLast()` inside the lock throws NPE. Separately `register()` uses a bare `tryLock()` and silently skips
  when it loses; because an entry is only appended when `last.version + 1 == version`, **one** skipped
  registration makes every later version un-appendable for the life of the file, and `findNearestLocation` then
  falls back to the file start, so every later supplier rescans the whole file.
- **`checkEvitaErrors` debouncing was deliberately not touched.**
  `2026-08-28-attributable-internal-error-metrics` records leaving it alone on purpose — *"with origins now
  logged, a flap is diagnosable rather than mysterious"*. Reversing that needs its own record.
- **Production confirmation is owed.** `increase(io_evitadb_errors_total[12h])` should fall to ~0 and
  `io_evitadb_probe_health_problem{problem_type="EVITA_DB_INTERNAL_ERRORS"}` should stay at 0 rather than
  57.6 % of cycles. It could not be checked while this was written — the observability MCP server would not
  connect.

## Related work

- `2026-08-28-attributable-internal-error-metrics` — established that error *construction* is what moves the
  counter, and shipped the `ErrorOriginLogger` that made this diagnosable at all. Its own consequences section
  predicted this class of report; what it did not predict is that the first such report would be a true
  positive.
- `2026-07-18-paged-index-corruption-and-flush-failure-boundary` — its `bug-03-commit-progress-hang.md`
  scenario root-caused a race on the *safe* stream variant, fixed by `880e6a142`. That fix worked around the
  reader defect for one caller; this record removes it for all of them.
- `2026-08-24-grpc-streaming-backpressure-readiness-gate` — records that CDC demand gating is blocked on #1446,
  a subscriber whose WAL pointer falls outside retention stalling silently. The `consumeQueue` catch here
  removes one of the two ways a CDC subscription can stall without telling anyone.

## Timeline

- **2026-09-11** — issue #1551 filed from production metrics
- **2026-09-13** — the issue's own proposed fix refuted; the tail-read framing refuted; the reader defect
  found, fixed and verified by a single-threaded counterfactual
