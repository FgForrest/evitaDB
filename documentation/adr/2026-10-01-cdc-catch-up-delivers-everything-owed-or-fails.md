---
title: A CDC subscriber catching up from the WAL is served everything it is owed or told why not
date: 2026-10-01
updated: 2026-10-01 21:55
status: accepted
kind: fix
issues: [1687, 1446]
prs: [1688]
areas: [evita_store/evita_store_key_value/src/main/java/io/evitadb/store/kryo, evita_store/evita_store_server/src/main/java/io/evitadb/store/wal, evita_store/evita_store_server/src/main/java/io/evitadb/store/engine, evita_engine/src/main/java/io/evitadb/core/cdc, evita_engine/src/main/java/io/evitadb/spi/store]
supersedes: []
superseded-by: []
relates: [2026-09-13-off-record-reads-must-not-restore-an-invalidated-buffer-limit, 2026-08-24-grpc-streaming-backpressure-readiness-gate]
---

# A CDC subscriber catching up from the WAL is served everything it is owed or told why not

A change-data-capture subscriber that falls behind the in-memory ring buffer catches up by reading the write-ahead
log. That read used to end quietly whenever anything went wrong, and the subscriber then re-asked for the same
position forever: no `onError`, no `onComplete`, heartbeats still flowing. Two things changed. The compressed WAL
reader no longer miscounts its own bytes - the defect that made an intact WAL unreadable in the first place. And
the catch-up read now names the version it must reach, so every way it can fall short reaches the subscriber as
`onError`: a damaged or unreadable transaction, a position the WAL retention has already removed, and - by
construction rather than by luck - nothing else.

## Why

A production e-commerce deployment (compression and CRC on, three CDC subscribers per catalog) reported
subscriptions that stopped receiving captures after a bulk transaction pushed them out of the ring buffer. The
stream stayed open, `lagging_subscribers` read 0, and the only trace was `io_evitadb_errors_total` rising by three
(one per subscription) and then by roughly twenty every 30 s for as long as writes continued - each a
`GenericEvitaInternalError: Invalid WAL file on position …` that nobody was shown. Trunk incorporation, reading the
same WAL, was unaffected.

### Previous state

- **The reader.** `ObservableInput#fill` inflates a compressed record from a raw buffer; when the inflater needs
  input it refills that buffer from index 0 but did not advance `decompressionTotalBefore`, the stream offset of
  index 0 that `markEnd` restores `total` from. After every refill inside a compressed record, `total()` came out
  short by the discarded buffer (16 KiB by default). Bytes and read position were right; only the count was wrong.
  The one consumer that compares `total()` across a record is the framing premise in
  `AbstractMutationSupplier#readAndRecordTransactionMutation` (`contentLength + 4 == leadSize + walSizeInBytes`), so
  an intact WAL failed it whenever a transaction's compressed leading record straddled a refill - observed as
  `leadSize = -16307` for a 73-byte record. A supplier's constructor seeks, so the first transaction of every read
  is safe; only a sequential advance into the next one can meet the straddle, at roughly lead-size / buffer-size
  (~0.4 %) per advance.
- **Why only CDC.** Trunk incorporation opens a fresh, seeking stream every round and advances over a handful of
  transactions; a hit at the end of a round is swallowed as a graceful end (moving only the error counter) and the
  next round starts at a different alignment. A lagging CDC subscriber reads hundreds of transactions in one stream,
  so a hit is near certain - and its retry is byte-for-byte the same read.
- **Silence, six ways.** `ChangeCatalogCaptureSharedPublisher#readWal` used the greedy
  `getCommittedMutationStream(version)`, whose Phase 3 turns any failure into end-of-stream, and the subscription's
  position (`lastVersion`/`lastIndex`) moves only on delivery. Auditing that path found:
  - **S1** a read failure mid-stream (this defect) ends the read; the same read repeats forever;
  - **S2** a position whose WAL file retention removed (#1446) gives `findWalIndexFor == -1`, an empty stream, and
    every later capture is lost too;
  - **S3** a subscriber whose criteria match nothing for a while never moves its position, re-reads an ever longer
    stretch on every fill and eventually walks into S2 without having missed anything;
  - **S4** a position below the first WAL transaction of a log that never lost a file (versions from warm-up) - not
    a skip: nothing below was ever a transaction;
  - **S5** `ChangeSystemCaptureSharedPublisher` repeats S1-S3 over the engine WAL;
  - **the engine WAL never shed a file while the server ran** - `EngineMutationLog` kept the processed version it
    was opened at (only the catalog called `walProcessedUntil`), so rotated files stayed until a restart opened
    the log at a later version; S2 at the engine level could therefore only happen across restarts;
  - **S6** `clearUnusedDataInRingBuffer` deleted the version counts below the ring start - exactly the lagging
    subscribers - so `getLaggingSubscribersCount` was 0 by construction.
- **The log's "last written version" was its active file's.** Rotation creates the next file holding only its
  8-byte checksum, and a crash before the first append leaves it so - a state the log's constructor accepts.
  `AbstractMutationLog#getLastWrittenVersion` then answered -1 for a log whose transactions were intact one file
  over, and every caller read that as an empty log: the CDC bound became -1 (a silent stall on a quiet catalog,
  found by the Codex review), `verifyIntegrity` declared the catalog corrupted (`Expected '6' but found '-1'`), the
  engine refused to boot (`WAL lastWrittenVersion=-1, engineState.version=9`), and an empty active file accepted
  an append of *any* version, so a repeated one could reach disk and make the log unopenable. The stall was
  hidden behind the load failure; nobody had hit the state yet.
- **Three recovery paths broke on the same state.** `getFirstNonProcessedTransaction(null)` - a catalog that went
  live and never published a checkpoint - started at the **newest** file and found nothing in the stub, so the
  whole log went unreplayed (`Expected '1' but found '6'`). The engine's startup `truncateWriteAheadLog` treated
  the trailer of a finalized file as an unfinished tail and cut it off, so the boot *after* a successful one
  failed. And `rollbackWalAppend` truncated only the pre-append file when the failed append had rotated, leaving
  the rolled-back transaction in the next file; retrying the version failed (`Expected: '11', but got '10'`).
- **The floor lookup raced the retention.** `getFirstReplayableVersion` lists the oldest file and then reads it;
  a removal in between answered -1 ("nothing ever purged") or threw out of the CDC pre-check as if the log were
  unreadable.

## Options considered

### Option A — bounded catch-up read, retention classified up front (chosen)

`readWal` calls `getCommittedLiveMutationStream(position, min(published, lastVersionInWal),
VersionSource.INTERNAL)`. Before it, a position below `getFirstReplayableVersion()` raises
`TemporalDataNotAvailableException`; after a failure, the same check runs again, because retention may have
removed the file in between. A fill that left room in the subscriber's queue records the newest version it
examined, and the next fill starts after it.

- **Pros:** reuses the reader's existing "a named version must be reached" contract; the subscriber sees the real
  cause; S2 is reported in client vocabulary and does not move the internal-error counter.
- **Cons:** one more WAL-lookup per catch-up fill (`getFirstReplayableVersion` lists the WAL folder); SPI grows by
  three methods.

### Option B — keep the greedy read, check the result in `readWal` (declined)

Track the last transaction version the greedy stream reached and throw when it ended short of the bound with
room left in the queue.

- **Pros:** no change to any WAL API.
- **Rejected because:** the stream has already swallowed the exception, so the subscriber learns "ended early" and
  never why - the operator loses the only diagnostic there is. It also leaves S2 to a second, separate mechanism.

### Option C — make the greedy Phase 3 loud for every reader (declined)

The end-of-file guards now guarantee every byte a Phase-3 advance reads is on disk, so any failure there is damage
or a reader defect.

- **Pros:** fixes every greedy reader at once.
- **Rejected because:** greedy reads also drive crash-recovery replay, the engine WAL and a branch of
  `TransactionManager`; changing their failure semantics is a separate decision with its own blast radius, and
  CDC does not need it.

### Reading the bounded stream as `VersionSource.CLIENT` (declined)

- **Rejected because:** it would report real WAL damage to the subscriber as invalid usage. Classifying S2 before
  the read lets the read itself stay `INTERNAL`, where a failure means damage.

### Keeping WAL files while a subscriber still needs them (declined for now)

Retention holds back the files the slowest live subscriber needs, up to a cap, and fails it only past the cap.

- **Rejected because:** it trades disk for availability under a new configuration knob, and a stuck client would
  pin storage. Failing loudly is the prerequisite either way; revisit once CDC demand gating (see
  `2026-08-24-grpc-streaming-backpressure-readiness-gate`) lets slow subscribers fall behind on purpose.

## Decision

**Chosen: Option A, plus the reader fix.** The rule is that a subscriber is either served everything it is owed
or told why not; Option A is the only one that keeps the cause, and the only one that covers S1, S2 and S3 with a
single contract. This reverses **Option C of `2026-09-13-off-record-reads-must-not-restore-an-invalidated-buffer-limit`**
(switch CDC to the bounded read). Its objections no longer hold: the error-counter noise it feared was this reader
defect, now fixed, and a bound that still parses transaction N+1's header is irrelevant to whether a failure is
reported.

## Key technical details

- `ObservableInput#fill` (compressed branch) advances `decompressionTotalBefore` by the raw bytes each refill
  discards. Any future change to the refill must keep `markEnd`'s `total` equal to the stream offset of the
  record end.
- `ChangeCatalogCaptureSharedPublisher#readWal` / `ChangeSystemCaptureSharedPublisher#readWal` - bound
  `min(published, last written)`.
- `AbstractMutationLog#getLastWrittenVersion` is **log-wide**: the active file's last version, or - while it holds
  no transaction - the last version of the finalized file before it, carried on `CurrentMutationLogFile` (seeded
  by the constructor, handed over by rotation) so no reader sees it out of step with the file swap. The name was
  kept and its meaning changed because every caller - both SPI "last version in the mutation stream" methods, the
  engine's startup drift check and `rewriteEngineStateAtNextVersion`, `verifyIntegrity`, the transaction
  manager's seeding - meant the whole log. Only rotation's own trailer means the active file; it uses
  `getLastWrittenVersionOfCurrentWalFile`. `checkNextVersionMatch` compares log-wide too.
- `getFirstNonProcessedTransaction(null)` starts at the **oldest** file. A null reference is a published state that
  processed no transaction of the log, and retention removes only processed files, so nothing before it is gone.
- `DefaultEnginePersistenceService#truncateWriteAheadLog` (startup) truncates only the newest file - a file with a
  successor ends with its trailer, not with a tail. `#rollbackWalAppend` restores by **physical size**, not through
  the published reference: it deletes the file a rotating append landed in, truncates the pre-append file back to
  the size captured before the append (which also drops a rotation's trailer), and re-opens the log at once. The
  published reference can point one file back (a crash-left stub), and restoring through it left the rolled-back
  transaction on disk. Neither the deleted file nor the cut bytes were ever published, so this is not the delete
  `.claude/rules/durability-model.md` forbids. The engine log is always opened at the **published** reference
  (`getPublishedWalReference`), never at file `0`, which retention may now have removed during uptime.
- A rolled-back engine append no longer hides the WAL: the log used to stay closed until the next commit, so the
  system CDC bound read 0 and its stream came back empty. An engine with no WAL at all still answers an empty
  stream - a system subscriber from version 0 on a fresh engine legitimately reads nothing.
- `AbstractMutationLog#getFirstReplayableVersion` answers -1 while WAL file `0` exists. That is what tells S4
  (warm-up versions, nothing lost) from S2 (history removed). It goes through `resolveFirstReplayableVersion`,
  which re-lists when the oldest file vanished between listing and reading, and requires each retry to see a
  strictly newer oldest file (retention deletes oldest first, never the active file) - so it terminates without a
  retry cap and reports a file that vanished any other way. `readFirstVersionOf` tells "vanished" from "stub"
  from "unreadable". Its oldest-index lookup (`getOldestWalFileIndex`) takes the minimum of the listing without the
  contiguity premise of `getFirstAndLastWalFileIndex`: a listing taken while retention removes several files may
  miss one in the middle. `WalReadResult#classifyReadFailure` attaches a failing retention re-check to the read
  failure as suppressed rather than replacing it.
- `DefaultChangeCaptureSubscription#markExaminedThrough` - only a fill that left room in the queue may vouch for a
  version; a fill cut short by a full queue would skip what it did not reach. The ring-buffer path reads
  `getEffectiveLastCatalogVersion()` **before** the copy: captures of a version are offered before the version
  becomes visible, so a complete copy has seen everything up to that value.
- `ChangeCatalogCaptureSharedPublisher#processMutation` initialises the ring with effective end `version - 1`. It
  was `version + 1`, which made a version still being offered visible to a concurrent fill.
- `DefaultChangeCaptureSubscription#consumeQueue` holds a fill failure back (`pendingFillFailure`) until the
  captures that fill had already queued are delivered. A fill offers captures as it reads them, so the ones right
  before the damage are intact; dropping them with the error would make them unreachable, because a resubscription
  from the last delivered position fails at the same place again.
- `clearUnusedDataInRingBuffer` sweeps only zero counts below the ring start.
- `DefaultEnginePersistenceService#writeBootstrapFile` reports the version it publishes to the engine WAL as
  processed - the engine's twin of the catalog's `walProcessedUntil` after `writeCatalogBootstrap`. Replay starts
  after the published version, and retention still keeps `walFileCountKept` files.
- `AbstractMutationSupplier`'s premise message now prints the prefix, the measured lead size, the declared
  mutation size and the version - a negative lead size cannot come from bytes on disk.

## Verification

- `CompressedInputOutputTest#shouldReportStreamOffsetAfterCompressedRecordsSpanningRawBufferRefills` - 40
  compressed records through a 64-byte buffer; without the fix `total()` after the first record is 44 instead of
  108.
- `CatalogWriteAheadLogIntegrationTest$CompressedWalTests` - 1,500 compressed transactions read forward, loud and
  greedy; without the fix it fails with the production signature: version 665's leading record "read as -16330
  bytes".
- The investigation probe (6 seeds × 41 start versions × 3,000 transactions, compression on): 223 of 246 forward
  reads ended early before the fix, 0 after.
- `CatalogChangeCaptureWalCatchUpTest` and `SystemChangeCaptureWalCatchUpTest` - real engines through the public
  registration API, each test proven against the guard it protects:
  - S1 (content-length prefix of transaction 8 of 2..11 falsified): `onError(WriteAheadLogCorruptedException)` with
    the reader failure as cause, every version before the damage delivered. With the greedy read both fail: versions
    2..7 delivered and no error. With the fill failure signalled at once the catalog test fails with `[2..6]` instead
    of `[2..7]`.
  - S2 (retention purged up to version 26 / engine version 34): `onError(TemporalDataNotAvailableException)` naming
    the first replayable version. Without the retention classification: `WriteAheadLogCorruptedException`; with the
    pre-fix greedy read: no signal at all.
  - S3 (BRAND subscriber, 30 PRODUCT transactions, its file purged): no error and the late BRAND capture delivered.
    Without the examined-through watermark: `TemporalDataNotAvailableException` (29).
  - S6: `getLaggingSubscribersCount()` reads 1 for a subscriber parked behind the ring; 0 with the old sweep.
  - S4 is `CatalogChangeObserverTest#shouldRegisterObserverAndReceiveAllExistingMutations`: checking retention
    against the first version in the WAL instead of the first replayable one drops it from 20 captures to 0.
- Engine WAL retention: `SystemChangeCaptureWalCatchUpTest`'s S2 fixture creates and removes 30 catalogs on a
  1 KiB engine WAL and restarts once, so closing the log drains the queued removals; without the
  `walProcessedUntil` call nothing is removed (first replayable version -1). Engine suites (12 classes, 211 tests)
  green.
- CDC, WAL and reader suites together: 22 classes, 301 tests, 0 failures.
- The crash between rotation and the first append, reproduced by `WalRotationCrashTestSupport` (writes the trailer
  and next-file header exactly as rotation would, after `Evita.close()`), each test against its counterfactual:
  - `CatalogChangeCaptureWalCatchUpTest#shouldServeEverythingOwedFromALogLeftByACrashBetweenRotationAndTheFirstAppend`,
    `#shouldContinueTheVersionSequence…` (a checkpoint kept) and `#shouldReplayALogNoCheckpointReferences…` (only
    the go-live record kept). With the active-file accessor all three fail to load the catalog
    (`Expected '6'` / `'3'` / `'2' but found '-1'`); with replay starting at the newest file the last fails with
    `Expected '1' but found '6'`.
  - `CatalogWriteAheadLogIntegrationTest$MultiFileWalTests#shouldReportTheLastVersionOfTheWholeLogWhileItsActiveFileHoldsNoTransaction`:
    3, against -1.
  - `DefaultEnginePersistenceServiceTest$WalRotation`: the engine boots (drift refusal without the fix); an append
    repeating a version of the finalized file is refused (accepted silently with the active-file check); the
    finalized file survives startup truncation (next boot fails reading a transaction tail as the file's version
    range without the guard); a rotating append rolls back completely (retry fails with `Expected: '11', but got
    '10'` without the delete).
  - `CatalogWriteAheadLogIntegrationTest$FirstReplayableVersionRaceTests` (4 tests of the pure helper): a vanished
    oldest file is followed to the next (`expected 17 but was -1` without the retry); a non-advancing retry is
    reported; a stub answers without re-listing; a log with file `0` reads no file.
- After those fixes: 34 classes / 441 tests plus `EvitaTest` (101), 0 failures.
- `DefaultEnginePersistenceServiceTest$WalRotation#shouldAppendAFullSizeTransactionToAWalFileThatHoldsNoTransactionYet`
  - `doAppend` rotates only a file that holds a transaction, so a transaction sized up to `walFileSizeBytes` lands
  in an empty file and overruns the limit by its framing. Rotating the empty file instead failed every such
  transaction with `Invalid first catalog version in the WAL file ('-1')`; this predates #1687.
- The engine rollback path, each red before its fix (code-quality review):
  `DefaultEnginePersistenceServiceTest$WalRotation#shouldRollBackAnAppendIntoAWalFileLeftEmptyByACrashAfterRotation`
  (`expected 8 but was 140` - the rolled-back transaction left in the stub);
  `#shouldAcceptAppendsAfterARolledBackAppendOnceRetentionRemovedTheFirstWalFile` (`evitaDB_0.wal … is not among
  the files present … replay cannot start from a file that is gone!`; also held by the re-open alone);
  `$FusedAppendAndStoreState#shouldKeepServingTheWalAfterARolledBackAppend` (`expected 5 but was 0`);
  `CatalogWriteAheadLogIntegrationTest$MultiFileWalTests#shouldResolveTheFirstReplayableVersionFromAListingThatMissedAFileRemovedMidListing`
  (`Missing WAL file with index 2`). `WalReadResultTest` pins the suppressed re-check;
  `ChangeCaptureSubscriptionFillFailureTest` pins `pendingFillFailure` directly (`expected 2 but was 0` with the
  failure signalled at once); `CatalogChangeObserverTest` / `SystemChangeObserverTest`
  `#shouldSweepOnlyUntrackedVersionsBelowTheRingBufferStart` pin the zero-only sweep.

## Consequences & open follow-ups

- **A lagging subscriber can now be told it is too late.** `TemporalDataNotAvailableException` carries the first
  replayable version; the client has to resubscribe and resynchronise. Before, it waited forever.
- **The engine-level CDC reports S2 in catalog-version vocabulary** - `TemporalDataNotAvailableException`'s message
  says "catalog version" for an engine version.
- **`2026-09-13-…`'s production evidence** came from the same deployment, which runs with compression on. Some of
  the internal-error noise attributed there is likely this reader defect; that record's own fix stands.
- **The retention pre-check in `readWal` is invisible to the subscriber** - the post-failure re-check yields the
  same exception. It keeps a retention miss from first being built as `WriteAheadLogCorruptedException`, an
  internal error that moves `io_evitadb_errors_total`; no test pins that counter.
- **Not pinned by a test:** S3 at the engine level and S6's lagging count there (same code shape as the catalog
  publisher; the engine sweep itself is pinned by `SystemChangeObserverTest`), and the ring initialisation
  `version - 1` (a race with a concurrent fill).
- **An engine with no WAL at all answers an empty catch-up stream** rather than throwing - a fresh engine's system
  subscriber from version 0 legitimately reads nothing. Only a rollback could leave an existing WAL unopened, and it
  now re-opens the log before it returns.
- **A subscriber that stops requesting** hears about a fill failure only once it has drained the captures queued
  before it - reactive streams would allow the error earlier, but those captures are owed.
- **Engine history is now bounded by retention during uptime** - `Evita#getCommittedMutationStream` and the
  reversed engine stream see the last `walFileCountKept` files, as catalog history always did.
- **A stub as the oldest surviving file** still makes `getFirstReplayableVersion` answer -1. Retention cannot
  produce it - removing file K needs a processed version in a later file - so only deleting files by hand can.
- **`getFirstVersionOf` keeps answering -1 for a vanished file**; only the floor lookup needs the distinction, and
  it uses `readFirstVersionOf`.
- **Greedy readers other than CDC** still end quietly on a Phase-3 failure (Option C). Revisit if crash-recovery
  replay ever needs to distinguish damage from a torn tail.

## Related work

- `2026-09-13-off-record-reads-must-not-restore-an-invalidated-buffer-limit` - the previous WAL reader miscount,
  in the off-record number reads; its Option C is reversed here.
- `2026-08-24-grpc-streaming-backpressure-readiness-gate` - CDC demand gating was blocked on #1446, which this
  closes.

## Timeline

- **2026-10-01** — #1687 reported: CDC subscriptions stall after a bulk transaction; reproduced only once
  compression was turned on; reader defect found and fixed; the six silent paths audited and closed; the Codex
  review's active-file "last written version" and floor-lookup race fixed, together with the recovery paths
  that broke on a crash between rotation and the first append
