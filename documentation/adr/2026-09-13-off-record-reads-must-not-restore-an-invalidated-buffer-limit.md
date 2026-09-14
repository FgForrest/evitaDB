---
title: Off-record number reads must not restore a buffer limit the read has invalidated
date: 2026-09-13
updated: 2026-09-14 10:08
status: accepted
kind: fix
issues: [1551]
prs: []
areas: [evita_store/evita_store_key_value/src/main/java/io/evitadb/store/kryo, evita_engine/src/main/java/io/evitadb/spi/store/catalog/wal, evita_store/evita_store_server/src/main/java/io/evitadb/store/wal, evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog, evita_engine/src/main/java/io/evitadb/core/cdc]
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
  `ByteBuffer` in a single write loop (`AbstractMutationLog#doAppend`). A reader cannot observe half of them.
- A file too short for the transaction is already rejected upstream by the end-of-file guards in
  `AbstractMutationSupplier#readAndRecordTransactionMutation`, which return `empty()` before the premise is
  reached.

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
  `MutationSupplier#get()` parses transaction N+1's header before it applies the version bound, so bounding at
  N still reads N+1. At the time this option was weighed, the bounded path was in fact *more* eager than the
  greedy one, requiring the record's content but not its trailing checksum — eight bytes less on disk before
  it deserialized. **That last asymmetry no longer exists**: both paths now require the whole record (see *The
  workaround*), which removes the sharpest edge of this objection without rescuing the option, since the first
  two reasons are untouched.

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
meaning drifted away when `880e6a142` re-purposed it. It carried two behaviours, and separating them is the
substance of this record, because **only one of them turned out to be sound**:

- a caller that names a `requestedVersion` is **asserting that version is durably written**, which was taken
  to license delivering a record whose content is on disk without waiting for the trailing cumulative
  checksum. **This is removed.** See below — the premise does not survive reading the writer;
- and failing to reach that version is surfaced loudly rather than as an exhausted stream — the
  loud-vs-graceful contract `880e6a142` introduced to stop the trunk-incorporation stage misreading a
  durably-written transaction as "already processed" and spinning forever. **This is kept**, and sharpened:
  *which* exception is raised now depends on who named the version.

#### The checksum relaxation was never load-bearing

`AbstractMutationLog#doAppend` writes the record head, the content, and then the 8-byte trailing cumulative
checksum, and only afterwards calls `forceDurable`. `appendDeferringSync` defers the **force**, never the
bytes — it is `doAppend(…, false)`, and its only caller reads the returned record length immediately. So in
both append variants the record is length-complete on the channel before anything downstream can learn the
version exists.

The interval in which content is on disk and the checksum is not therefore belongs to an append that has not
returned, carrying a version nobody has been handed and no caller is in a position to name. The relaxation
guarded a window no legitimate caller could observe, and in exchange it made the reader willing to deliver a
record whose integrity it could not check. **Every read now requires the whole record**, and
`AbstractMutationSupplier#requiredEndPosition`, `#readAndRecordTransactionMutation` and
`MutationSupplier#get()` no longer branch on whether a version was named.

The one test that pinned the old behaviour asserted the opposite — that such a record *was* delivered — and
its own comment claimed the file shape it built mirrored "the moment `append()` has written everything except
the final checksum write". Per the above that moment is unreachable by a caller who can name the version, so
the test was inverted rather than deleted: the shape it constructs is reachable only by a crash mid-append or
by deliberate truncation, and in both cases it is damage.

#### Loud-vs-graceful now splits by caller, not by whether a version was named

Two real use cases were being served by one flag:

- the stream is acquired **internally**, from the transaction-processing pipeline. The version is the
  engine's own, already observed to be durable. Not finding it is damage — `WriteAheadLogCorruptedException`,
  an `EvitaInternalError`, counted by `io_evitadb_errors_total`;
- the stream is acquired **over an external API**, where the caller supplies the version and the database has
  no control over what it contains. It may name a version rotated out of retention, or one that never
  existed. Not finding it is a bad argument — `EvitaInvalidUsageException`, which the error counter ignores,
  because no amount of operator attention makes a client stop sending it.

The distinction is carried by `VersionSource` (`INTERNAL` | `CLIENT`), threaded from the entry point down to
the supplier. **It has to travel down rather than be translated on the way back up**, and that is the
non-obvious part: `WriteAheadLogCorruptedException` extends `EvitaInternalError`, and the Byte Buddy advice
instruments `EvitaInternalError` *constructors* — so an exception built internally and converted at the
session boundary has already moved the counter. Constructing the right type the first time is the only fix
that works.

`EvitaSession#getMutationsHistoryForward` is the sole `CLIENT` call site today. Note the axis is the **start**
position, not `requestedVersion`: all three named-version call sites derive the ceiling internally, and what
differs is that `criteria.sinceVersion()` comes off a client request.

Two boundaries were established by measurement rather than argument, each after a test failed:

- **an empty interval is not a missing version.** When the start version is above the requested ceiling —
  which is what a mutation-history query whose time frame begins after the last committed transaction
  resolves to — the range is empty by construction and the answer is an empty stream. Raising there turned an
  ordinary "no results" page into a gRPC `INVALID_ARGUMENT`;
- **a greedy read stays silent.** Recovery, replay and change-data-capture promise nothing about where the
  log ends, so a torn tail is simply the end of the data for them. Only a caller that named a version gets
  the loud treatment.

Both survive `requestedVersion`, a `@Nullable Long` that is `null` for a greedy read. The boolean was derived
from `requestedVersion != null` at the only place it was set (`AbstractMutationLog#createSupplier`), so its
removal takes away a parameter that could disagree with the field it shadowed. `VersionSource` is an **enum
rather than a second boolean**, deliberately: a boolean that bundled two orthogonal axes is what produced
this issue, and repeating the shape would have been the same mistake one axis over.

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
- `LongRunningConcurrentWalTailReadStressTest` — one writer appending 400 transactions of varied sizes while
  three readers tail through the greedy stream, asserting that no internal error is ever *constructed*. It is
  the only test in the repository that drives a real writer and real readers against one file, and it is what
  exposed the fixture fault recorded below. It lives in `evita_long_running_tests`: it costs ~7 s alone but was
  measured at **28.9 s** inside a full functional run, and tagging it `slow` where it was first written would
  have meant it never ran at all, since `unitAndFunctional` excludes that tag.
  **It is not a regression guard for this fix, and an earlier revision of this record wrongly said it was.**
  Re-measured against the counterfactual — the unconditional restore this change superseded — it stayed green
  over **three consecutive runs** on an idle box (`escapedFailures=0; constructedErrors=0`, all 400 versions
  returned in order), while `ObservableInputTest$BoundaryReadTests` failed hard on that same mutant in that same
  reactor. A `KryoException: Buffer underflow` at `ObservableInput.require` *was* observed from this scenario
  before the fix, under a fixture and a machine load that have both since changed; it has not reproduced since,
  and why is unsettled — see the open follow-up below.
- `ChangeCaptureSubscriptionFillFailureTest` — with the catch removed, fails with the fill exception escaping
  `Subscription#request(long)`.
- Full `wal | cdc` tag sweep: **640 tests, 0 failures, 1 skipped**, including the gRPC and GraphQL subscription
  functional tests that exercise CDC end to end.
- The two behaviours introduced by *The workaround* are each pinned by a mutant, run in this reactor:
  collapsing `VersionSource` so every failure is a `WriteAheadLogCorruptedException` fails exactly
  `CatalogWriteAheadLogTest$DryReadVisibilityRaceTests#shouldReportAClientSuppliedBoundAsInvalidUsageRatherThanCorruption`
  (1 of 50); disabling the constructor's not-found guard fails **3** of 50, adding
  `#shouldRaiseRatherThanGoDryForLastAppendedVersionMissingOnlyTrailingChecksum` and
  `CatalogWriteAheadLogIntegrationTest$MultiFileWalTests#shouldNotRaiseARawKryoFailureWhenTheNextWalFileIsStillAnEmptyStub`.
  The `CLIENT` test and its `INTERNAL` twin run against byte-identical on-disk state and differ only in the
  declared source, so a change that collapses the two arms cannot leave both green.
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
so a recurrence is reported rather than inferred. **Removing the pool fault did not make the stress test pass**,
which is what ruled the fixture out as the whole story at the time and sent the search back to the reader. It is
why the probe earns its place: a concurrency fixture that differs from production silently converts every result
into a question. Note the limit of that argument in light of the re-measurement above — it establishes that the
fixture was not the *only* thing wrong then, not that the run still fails for the reason it failed then.

## Consequences & open follow-ups

- **`VersionSource` is chosen per read, not per bound, and that was a decision rather than an oversight.**
  `EvitaSession#getMutationsHistoryForward` passes two version bounds with different origins — the floor is the
  client's `criteria.sinceVersion()`, the ceiling is the engine's own `catalog.getVersion()` — and labels the
  whole read `CLIENT`. Splitting them was considered: the supplier's two constructor guards are about reaching
  the *floor* and `MutationSupplier#get()`'s throws are about reaching the *ceiling*, so a second field would
  map cleanly and would let genuine damage met on the way to the engine's own version report as corruption.
  **Rejected because** it re-opens the door this record exists to shut — a client-initiated request that can
  raise an operator alarm — in exchange for better attribution of a failure that is rare on that path. The cost
  is real and accepted: damage discovered while serving a mutation-history query is reported as invalid usage
  and lands in `io_evitadb_client_errors_total` rather than the internal-error metric. Revisit only if that
  under-counting is ever observed to hide a real incident; the call site carries the same note.

- **The invariant now holds and is what the greedy stream rests on**: *a block that is safely written is
  immediately safe to read*. What guards it is `ObservableInputTest$BoundaryReadTests`, deterministically, in
  the fast loop — **not** the concurrent stress test, which does not fail on this defect's counterfactual. Do
  not read a green stress run as cover for a change to the buffer bookkeeping. The case for deleting
  `avoidPartiallyFilledBuffer` does not rest on the stress test either: it rests on the cause being found and
  covered, and on the flag carrying nothing about buffer filling by the time it was removed.
- **Why the stress test does not reproduce the defect is open, and worth settling before it is relied on.**
  Four experiments, cheapest first: re-run the counterfactual against the old unsynchronized
  `new Pool<>(false, false, 1)` fixture, which distinguishes a fixture fault in a single run; raise
  `TRANSACTION_COUNT` by an order of magnitude; run it under full-suite contention, where it was measured at
  28.9 s against ~7 s alone, since descheduling mid-read is what widens the window the defect needs. The fourth
  answers the question rather than bisecting it: instrument `require(int)` to count boundary reads that land in
  its fill or compaction branch during a stress run. Zero would settle it outright — it would mean no run length
  can help, because the supplier's `StorageRecord` reads realign the buffer to record boundaries and a WAL
  reader's 4- and 8-byte boundary reads then never straddle a refill.
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
