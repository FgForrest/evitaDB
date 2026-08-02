# Bug 03 — Commit hangs ("missed completion path"), swept after 60s

> **ROOT MECHANISM CAPTURED LIVE (2026-07-14).** The hang is a WAL-read visibility race
> misinterpreted by trunk incorporation, followed by an unbounded busy-spin. See
> "§ROOT CAUSE — live capture" below for the full evidence chain (thread stack + version counters
> read from the hung JVM). The savepoint-rollback hypothesis is EXONERATED for the hang mechanism;
> the fresh-boot-only hypothesis is DEAD (second occurrence hit a server up for 100 minutes with a
> prior clean commit).

## Signature
```
io.evitadb.api.exception.TransactionException
Commit progress for catalog version <N> has been pending for more than 60000ms.
The transaction pipeline dropped this record; failing it to unblock waiters.
```
Server side, first a WARN then the sweep:
```
WARN  PendingCommitProgressRegistry - Sweeping dangling CommitProgressRecord for catalog version N —
      the transaction pipeline did not complete it within 60000ms of the commit start time.
      This indicates a missed completion path in the pipeline and should be investigated.
```
Throw site: `PendingCommitProgressRegistry.sweepRecordsOlderThan`
(`evita_engine/.../core/transaction/PendingCommitProgressRegistry.java:202`), invoked by
`TransactionManager.sweepDanglingCommitProgress` (`TransactionManager.java:1392`).

## Evidence — server log + stacktrace (fuzzer seed=1 batch=500, from pristine)
```
WARN  i.e.c.t.PendingCommitProgressRegistry - Sweeping dangling CommitProgressRecord for catalog
      version 98 — the transaction pipeline did not complete it within 60000ms of the commit start
      time. This indicates a missed completion path in the pipeline and should be investigated.
ERROR i.e.e.g.s.i.GlobalExceptionHandlerInterceptor - Internal error occurred during processing of
      gRPC call: Commit progress for catalog version 98 has been pending for more than 60000ms.
      The transaction pipeline dropped this record; failing it to unblock waiters.
io.evitadb.api.exception.TransactionException: Commit progress for catalog version 98 has been
      pending for more than 60000ms. The transaction pipeline dropped this record; failing it to
      unblock waiters.
	at io.evitadb.core.transaction.PendingCommitProgressRegistry.sweepRecordsOlderThan (PendingCommitProgressRegistry.java:202)
	at io.evitadb.core.transaction.TransactionManager.sweepDanglingCommitProgress (TransactionManager.java:1392)
	at io.evitadb.core.executor.DelayedAsyncTask.runTask (DelayedAsyncTask.java:312)
	... (scheduler frames) ...
```
> NOTE: the stacktrace above is the **60s sweeper** that force-fails the dangling record — NOT the
> thread that actually hung. To root-cause, catch the hung commit/flush thread's stack at hang time
> (`jdwp_dump_locks` / thread dump while a batch commit is pending); that stack is the real evidence
> and is still TO BE CAPTURED.

## Where it fires
The **commit of the whole transaction never completes** — the transaction/flush pipeline has a
"missed completion path": nothing ever completes the `CommitProgressRecord`, so waiters block until
the 60s sweeper force-fails it. The client sees the `TransactionException` above; the server logs the
sweep.

## Trigger (senesi — CONFIRMED via fuzzer)
`SenesiUpsertFuzzer` seed=1, batch=500 (from pristine): the batch had 466 successful upserts + 34
per-entity failures (incl. 7 sort-tree "Key already present" and injected invalid ops that
savepoint-rolled-back), then the **commit of the batch (catalog version 98) hung** and was swept
after 60s. See `scenarios/fuzz-seed1-oplog.txt` (op-log of that batch).

> The refactored fuzzer (deterministic per-entity `(seed,pk)`) can replay the exact batch and, via
> `onlyPk`, replay any single entity alone from pristine — see PLAN.md §5.1.

## New evidence (2026-07-14) — hang is TIMING-DEPENDENT, not op-content-deterministic

Replaying the IDENTICAL batch (seed=1, batch=500, same op stream: ok=465 perEntityFail=35) against a
server that had been up for ~30 minutes (several failed single-entity replays beforehand) **committed
cleanly — no hang, no sweep**. Yesterday's hang fired on the FIRST commit after a fresh boot from
pristine. ⇒ the "missed completion path" is a RACE (plausibly tied to first-commit-after-boot
pipeline initialization or concurrent timing), not a function of the op content. Reproduction
attempts must mirror the fresh-boot-then-immediate-batch condition and may need several tries.

## ROOT CAUSE — live capture (2026-07-14, second occurrence)

**Occurrence:** fuzzer seed=1 batch=500 launched 13:07:01 against the server up since 11:27
(base = catalog version 98 after a clean batch commit at ~11:59; boot was pristine v97). The batch
applied in ~5 s (ok=436 perEntityFail=64 — counts differ from the v97-base runs because ops are
derived from current entity state), commit was assigned **catalog version 99**, hung, and was swept
at 13:08:07. ⇒ NOT tied to fresh boot / first commit.

**Live thread capture (jstack + JDWP on the still-hung JVM, ~4 min after the sweep):**
```
"Evita-transaction-20" RUNNABLE  cpu=241172ms elapsed=241.25s   ← 100% CPU since commit start
  at io.evitadb.core.transaction.TransactionManager.waitUntilLiveVersionReaches(TransactionManager.java:1256)
  at TrunkIncorporationTransactionStage.lambda$handleNext$1(TrunkIncorporationTransactionStage.java:131)
  at TrunkIncorporationTransactionStage.handleNext(TrunkIncorporationTransactionStage.java:107)
```
The sweeper only fails the `CommitProgressRecord` (unblocks the client); the pipeline thread keeps
spinning FOREVER — one `Evita-transaction` slot and one core are permanently lost per occurrence.

**Version counters read from the spinning frame (JDWP, `this` = TransactionManager):**
```
catalogVersion (param) = 99          ← what the spinner waits for
lastAssignedCatalogVersion  = 99     ← conflict-resolution stage: done
lastWrittenCatalogVersion   = 99     ← WAL-append stage: done (txn 99 IS durably in the WAL)
lastFinalizedCatalogVersion = 98     ← trunk incorporation: NEVER processed 99
livingCatalog version       = 98     ← live view: stuck; nothing will ever advance it
```

**Mechanism — two compounding defects:**
1. `TransactionManager.processTransactions` (TransactionManager.java:1050) has exactly ONE
   empty-return: `if (!mutationIterator.hasNext()) return empty()` with comment *"previous
   execution already processed all the mutations"*. The iterator comes from
   `getCommittedLiveMutationStream(lastFinalized+1, lastWrittenCatalogVersion)` →
   `CatalogWriteAheadLog.getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(99, 99)`, which
   can return a DRY stream for a just-appended transaction (the reader's safe-tail watermark lags
   the `lastWrittenCatalogVersion` bump — the deliberate "avoid partially written buffer" logic).
   "Nothing visible yet" is thus conflated with "already processed".
   (`DefaultCatalogPersistenceService.getCommittedLiveMutationStream`:2380 also returns
   `Stream.empty()` when `catalogWal == null`.)
2. `TrunkIncorporationTransactionStage.handleNext`'s empty branch
   (TrunkIncorporationTransactionStage.java:~125-140) then ASSUMES "a concurrent trunk task already
   drained the mutation stream" and calls `waitUntilLiveVersionReaches(task.catalogVersion())` —
   an **unbounded `Thread.onSpinWait()` loop** (TransactionManager.java:1254-1258, no timeout, no
   sweep integration) — before completing the commit progress. With only ONE commit in flight the
   assumption is provably false: nobody processed txn 99, live never reaches 99, the record is
   never completed → 60 s sweep, eternal 100% CPU spin.

**Why intermittent:** the window is between the WAL append publishing `lastWrittenCatalogVersion`
and the WAL reader's safe watermark advancing. Hit 2 of 3 times on this box; op content is
irrelevant except as timing.

## Isolation status — reproduced (batch), root not yet pinned
> **RESOLVED by §ROOT CAUSE above:** the hang is the WAL-visibility race + unbounded spin;
> savepoint rollbacks are neither cause nor contributor (they only shape timing). The decisive
> experiments below are superseded and kept for history.

Key open question — **which is cause vs symptom**:
- Does a per-entity failure that was **savepoint-rolled-back** leave a diff layer / index tree in a
  state the commit/flush pipeline can't complete (→ hang)? **This is the user's savepoint-damage
  fear, in the commit path.**
- Or is the hang independent of the rollbacks (e.g. a pipeline completion path missed when a batch
  mixes many entity types / a specific op)?

Decisive experiments (from pristine each time, PLAN.md §5.1):
1. Replay the batch with **invalid-op injection disabled** (no forced rollbacks) — if the commit
   still hangs, rollbacks are not the cause.
2. Replay with only the successful subset — if it commits fine, a *failed+rolledback* entity is
   implicated.
3. Bisect the batch to the minimal set of entities whose commit hangs.

## Distilled reproduction (2026-07-14, CONFIRMED — two failing tests)

**Mechanism refined — NOT a raw JMM/OS visibility gap.** On a single JVM + local filesystem,
`FileChannel.write()` bytes are visible to a freshly-opened `RandomAccessFile.length()`/read
immediately (no fsync needed for same-machine visibility), and
`TransactionManager.updateLastWrittenCatalogVersion`'s `AtomicLong.set()`
(`TransactionManager.java:632`) gives a JMM happens-before edge to any reader that observes the
new value via `.get()`. The writer thread (`ConflictResolutionAndWalAppendingTransactionStage
.handleNext`, `ConflictResolutionAndWalAppendingTransactionStage.java:123,139`) always finishes
`appendToSharedWal` (⇒ `AbstractMutationLog.append`, `AbstractMutationLog.java:963-1121`, which
writes header+content+trailing-checksum via `walFileChannel.write(...)` at lines 1031-1091) BEFORE
calling `updateLastWrittenCatalogVersion` at line 139 — so by the time any reader observes
`lastWrittenCatalogVersion == N`, transaction N's bytes are already 100% on disk and visible.
A pure "torn write becomes visible late" theory does not hold up under code reading and was
dropped.

**Actual mechanism — swallowed read exception on the WAL read path, not a length/staleness bug.**
`getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(N, N)`
(`AbstractMutationLog.java:1172-1175`) → `createSupplier(N, N)` (`AbstractMutationLog.java:1619
-1634`, sets `avoidPartiallyFilledBuffer = true` since `requestedVersion != null`) → constructs a
`MutationSupplier` whose superclass constructor (`AbstractMutationSupplier.java:187-292`) reads
transaction N's bytes through a **buffered** `ObservableInput` (`readAndRecordTransactionMutation`,
`AbstractMutationSupplier.java:406-476`, actual deserialization at line 442
`StorageRecord.readWithChecksum(theObservableInput, ...)`). The constructor wraps this whole scan
in:
```java
} catch (BufferUnderflowException e) {
    // incomplete write or premature EOF — treat as no data available
    ...
    this.transactionMutation = null;
```
(`AbstractMutationSupplier.java:277-283`) — and the forward-iteration path
(`MutationSupplier.get()` Phase 3) has an equally broad catch:
```java
} catch (Exception ex) {
    // EOF or incomplete transaction write — stop iteration gracefully
    return null;
```
(`MutationSupplier.java:198-201`). **Both catch blocks convert ANY read anomaly — including the
"internal pointers somehow misaligned" buffer-fill race the class's own javadoc already documents
at `AbstractMutationLog.java:1160-1166`** ("the buffer is 16k long, but the next transaction takes
only 2k and then file ends... in the meantime another transaction... has been written [and] is then
failed to be read from the observable input, because the internal pointers are probably somehow
misaligned") **— into a silent "no more data" signal** (`null` / empty `Optional`), indistinguishable
from a genuinely exhausted WAL. This propagates as `mutationIterator.hasNext() == false` at
`TransactionManager.java:1092`, which the one empty-return there conflates with "previous execution
already processed all the mutations" (comment at `TransactionManager.java:1093`) even though nobody
has processed transaction N. `TrunkIncorporationTransactionStage.handleNext`'s empty branch
(`TrunkIncorporationTransactionStage.java:119-137`) then calls
`waitUntilLiveVersionReaches(task.catalogVersion())` (`TransactionManager.java:1253-1258`, unbounded
`Thread.onSpinWait()` loop, no timeout) — which spins forever because nothing will ever advance
`livingCatalog` to N.

**Narrowing confirmed by the live capture.** The captured spinner frame was
`TrunkIncorporationTransactionStage.lambda$handleNext$1` at `TrunkIncorporationTransactionStage
.java:131` (the "concurrent trunk task already drained the stream" branch), **not** line 88 (the
"already processed" skip branch reachable when `task.catalogVersion() <=
lastFinalizedCatalogVersion`). Combined with `lastFinalizedCatalogVersion` staying at 98 (never
advanced), this rules out the alternative hypothesis that `drainWal()`
(`TransactionManager.java:1356-1371`, a periodic task that calls `processTransactions` with
`waitForLock=false` and **discards its `ProcessResult`** without ever calling
`propagateCatalogSnapshot`) had already processed transaction 99 ahead of the real trunk-incorporation
task — that scenario would leave `lastFinalizedCatalogVersion == 99` and park the spinner at line 88,
not line 131. So the dry read hit the trunk-incorporation task's own `(99, 99)` call directly, on its
first and only attempt.

**Secondary empty-stream source found, not reachable here:**
`DefaultCatalogPersistenceService.getCommittedLiveMutationStream` returns `Stream.empty()` when
`this.catalogWal == null` (`DefaultCatalogPersistenceService.java:2382-2383`). Not applicable to the
hung occurrence — the catalog had a live WAL (transaction 99 was durably appended to it).

**`java.nio.BufferUnderflowException` at `AbstractMutationSupplier.java:277` is DEAD CODE —
empirically confirmed.** `rg -n "BufferUnderflowException" evita_store evita_common evita_engine`
finds exactly two hits: the `import` and the `catch` clause itself, both in
`AbstractMutationSupplier.java` — nothing in the codebase ever throws
`java.nio.BufferUnderflowException`. The real failure mode when a read genuinely runs out of bytes
mid-deserialization is `com.esotericsoftware.kryo.KryoException` (from `ObservableInput.require()`,
`ObservableInput.java:467-537`, e.g. `"Buffer underflow."` at lines 498/524, or a corrupt-data
variant like `"Encountered unregistered class ID"` when the stream position is misaligned) — a type
the constructor's narrow catch does **not** match. Confirmed empirically: forcing a genuine
mid-header underflow inside `AbstractMutationSupplier`'s constructor scan (single-transaction
`(N,N)` path) let a `KryoException` propagate straight out of
`getCommittedMutationStreamAvoidingPartiallyWrittenBuffer` uncaught, rather than being swallowed to
`null` — the opposite of a silent hang (this path would surface as a loud crash of the
trunk-incorporation thread instead). The swallow that actually matches the silent-hang symptom lives
one level up, in `MutationSupplier.get()`'s Phase 3 (`MutationSupplier.java:198-201`,
`catch (Exception ex) { return null; }`), which — unlike the constructor's narrow catch — is broad
enough to catch `KryoException` too. See the two distilled tests below: the first freezes the
constructor's *coarse length pre-check* dry-read (the mechanism that actually fired in the live
`(99,99)` capture); the second freezes a *genuine Kryo-level underflow* hitting the Phase 3 broad
catch while advancing past an already-delivered transaction (the mechanism the class's own
"internal pointers are probably somehow misaligned" javadoc describes).

**Test 1 — CONFIRMED FAILING (reproduces the live `(N,N)` single-transaction capture).** Added
`DryReadVisibilityRaceTests.shouldNotReturnDryStreamForLastAppendedVersionMissingOnlyTrailingChecksum`
as a new `@Nested` class inside
`evita_test/evita_functional_tests/src/test/java/io/evitadb/store/wal/CatalogWriteAheadLogTest.java`
(reusing the class's existing `@BeforeEach`-appended 4-transaction WAL, `modifyWalFile` helper, and
`txSizes` fixture — same harness pattern as the neighboring `WalIntegrityTests`). It appends
transaction 4 through the real `CatalogWriteAheadLog.append()` API (no mock WAL layer), strips only
the trailing 8-byte cumulative checksum (`AbstractMutationLog.CUMULATIVE_CRC32_SIZE`) — the very
last thing `append()` writes, freezing the exact "durable content, not-yet-landed tail" window
deterministically — then asserts
`getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(4, 4)` is non-empty. Run via
`rtk mvn -pl evita_test/evita_functional_tests test -Dtest=CatalogWriteAheadLogTest -Dtest.tag.policy=off`:
14 of 15 tests in the class pass; only the new test fails:
```
org.opentest4j.AssertionFailedError: getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(4, 4)
returned a DRY stream even though transaction 4's header and content are durably on disk (only its
trailing checksum is momentarily missing). A caller that already believes this version is written
(as TransactionManager.lastWrittenCatalogVersion does in production) has no way to distinguish this
from "nothing left to process" and will silently drop the transaction instead of retrying - see
bug-03-commit-progress-hang.md. ==> expected: <false> but was: <true>
	at io.evitadb.store.wal.CatalogWriteAheadLogTest$DryReadVisibilityRaceTests
		.shouldNotReturnDryStreamForLastAppendedVersionMissingOnlyTrailingChecksum(CatalogWriteAheadLogTest.java:398)
```

**Test 2 — CONFIRMED FAILING (reproduces the documented "misaligned pointers" javadoc scenario).**
Added
`MisalignedReadSwallowTests.shouldNotSilentlyEndStreamWhenAdvancingIntoAGenuinelyUnderflowingTransaction`
as a new `@Nested` class inside
`evita_test/evita_functional_tests/src/test/java/io/evitadb/store/wal/CatalogWriteAheadLogIntegrationTest.java`
(reuses that class's `writeWal(...)` helper, which appends **real entity mutations** via
`DefaultIsolatedWalService`/`DataGenerator` — not synthetic bytes — the same harness the
`MultiFileWalTests`/`TransactionLookupTests` siblings use). It writes two genuine transactions
(sizes `{2, 3}`), locates transaction 2's exact on-disk start offset via the
`TransactionMutationWithLocation` the helper returns, then corrupts transaction 2 surgically: its
4-byte content-length prefix is overwritten with a small lie (`4`), and the file is truncated to
match that lie exactly. This satisfies the coarse length pre-check in `readAndRecordTransactionMutation`
(so it isn't caught by the same mechanism as Test 1) while the real, unmodified leading bytes of
transaction 2's serialized `TransactionMutation` genuinely run out mid-deserialization (a
`TransactionMutation`'s UUID field alone needs 16 bytes; only 4 real bytes are left). Reading
`getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(1, 2)` then delivers transaction 1's 3
elements (header + its 2 mutations) in full — proving transaction 1 itself was untouched — and then
silently ends the stream instead of surfacing the underflow it hit advancing into transaction 2. Run
via `rtk mvn -pl evita_test/evita_functional_tests test -Dtest=CatalogWriteAheadLogIntegrationTest -Dtest.tag.policy=off`:
5 of 6 tests in the class pass; only the new test fails:
```
CatalogWriteAheadLogIntegrationTest.shouldNotSilentlyEndStreamWhenAdvancingIntoAGenuinelyUnderflowingTransaction
getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(1, 2) silently ended the stream after 3
element(s) (transaction 1 only) instead of surfacing the read failure it hit while advancing into
transaction 2's genuinely underflowing header. MutationSupplier.get()'s broad
`catch (Exception ex) { return null; }` (MutationSupplier.java:198-201) swallowed whatever
underflow/deserialization error the buffered ObservableInput produced - exactly the "internal
pointers are probably somehow misaligned" bug documented at AbstractMutationLog.java:1160-1166 -
and reported an exhausted stream instead of a failure. ==> expected: not <null>
```

No fix applied to either test — both are expected to keep failing until `processTransactions`/the
WAL read path stop conflating a dry or truncated read with "already processed" (see Fix acceptance
below).

## Fix acceptance (updated after root-cause capture)
- A distilled failing test forcing the race: txn version N appended to the WAL
  (`lastWrittenCatalogVersion == N`) while `getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(N, N)`
  still returns a dry stream → `processTransactions`/trunk stage must RETRY (or park with a bound),
  complete the `CommitProgressRecord`, and never enter an unbounded spin.
- `waitUntilLiveVersionReaches` must be bounded (timeout/interrupt) — no `Thread.onSpinWait()` loop
  that can outlive the 60 s sweeper.
- Post-fix: fuzzer batches from pristine commit within the normal window; no "missed completion path"
  WARN; no `Evita-transaction` thread with cpu≈elapsed after a swept commit.

## Notes
- This is the strongest single candidate for "savepoint damages memory internals → follow-up errors":
  a hung commit blocks the whole catalog and is exactly a *follow-up* consequence of earlier in-batch
  activity. Prioritise the cause-vs-symptom experiments above.
