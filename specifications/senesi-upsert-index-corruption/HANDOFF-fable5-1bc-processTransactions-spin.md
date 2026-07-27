# HANDOFF → Fable 5: bug-03 items 1b + 1c (processTransactions empty-conflation + unbounded spin)

**Goal for this session:** validate the hypothesis below, then implement items **1b** and **1c** of
`FIXES.md` §1 (bug-03). Item **1a is already DONE and green** (21/0 on the two WAL classes) — its supplier
changes are the foundation these two build on; do not revert them. There are **no reproduction unit tests**
for 1b/1c (FIXES.md marks them "fuzzer-only"); part of your job is to decide whether a focused unit test is
now cheap enough to add (I argue 1c is trivially testable — see §Verification).

Everything you need is in this doc; you should not need to re-derive the call graph.

---

## 0. Orientation — what bug-03 is, and where 1a left us

bug-03 is a **commit-progress hang**: a transaction is durably in the WAL, but the trunk-incorporation
thread reads the mutation stream, gets *nothing back*, interprets "nothing" as "someone already did this",
finalizes at a **stale** catalog version, and the client's `CommitProgress` future is **never completed** —
`Evita.close()` / `waitUntilLiveVersionReaches` then spin a core at ~100% forever.

Three independent defects (FIXES.md §1 calls them 1a/1b/1c; fix all three):

- **1a — DONE, green.** The WAL `MutationSupplier`/`AbstractMutationSupplier` used to convert a mid-read
  `KryoException` into a silent end-of-stream. Fixed: in `avoidPartiallyFilledBuffer` mode a read that fails
  *before* delivering the requested version now **throws** `WriteAheadLogCorruptedException`; greedy
  (non-avoid) reads keep the old graceful-EOF behavior for torn recovery tails. Files:
  `evita_store/evita_store_server/src/main/java/io/evitadb/store/wal/supplier/{MutationSupplier,AbstractMutationSupplier}.java`.
- **1b — TODO (this doc).** `TransactionManager.processTransactions` still conflates an *empty stream* with
  "already processed" at the `!mutationIterator.hasNext()` branch.
- **1c — TODO (this doc).** `TransactionManager.waitUntilLiveVersionReaches` is an unbounded
  `Thread.onSpinWait()` loop; on a genuine stall no caller ever completes the `CommitProgressRecord`.

---

## 1. THE load-bearing fact: how a thrown exception becomes `completeExceptionally`

`AbstractTransactionStage.onNext` (`evita_engine/.../core/transaction/stage/AbstractTransactionStage.java:95-114`)
wraps the concrete stage in try/catch:

```java
@Override
public final void onNext(T task) {
    try {
        ...
        handleNext(task);                 // <-- TrunkIncorporationTransactionStage.handleNext
    } catch (Throwable ex) {
        try { handleException(task, ex); } // <-- completes commit progress EXCEPTIONALLY
        catch (Throwable e) { log.error(...); }
    }
    this.subscription.request(1);
}

protected void handleException(@Nonnull T task, @Nonnull Throwable ex) {
    if (!(ex instanceof ConflictingCatalogMutationException)) { log.error(...); }
    task.commitProgress().completeExceptionally(ex);   // <-- THE completion we want
    this.onException.accept(task, ex);
}
```

**Consequence:** for both 1b and 1c, *throwing loudly* is the correct mechanism to "complete the commit
progress exceptionally". Nothing swallows a `Throwable` between `processTransactions` and `handleException`.
We do **not** need to touch `handleNext` to add try/catch — throwing is enough.

---

## 2. The three callers of `processTransactions` (and where a throw goes)

`public Optional<ProcessResult> processTransactions(long nextCatalogVersion, long timeoutMs, boolean alive, boolean waitForLock, LongConsumer progressCallback)` — `TransactionManager.java:1050`.

| Caller | Line | alive | waitForLock | Stream used | A throw routes to… |
|---|---|---|---|---|---|
| `TrunkIncorporationTransactionStage.handleNext` | 100 | **true** | true | `getCommittedLiveMutationStream` = **avoid mode** | `onNext` catch → `handleException` → `completeExceptionally` ✅ (the hang path) |
| `drainWal` (watchdog) | 1356 | true | false | avoid mode | **catches `TransactionTimedOutException`** → reschedules (return 0); other throws propagate to the scheduler |
| `processEntireWriteAheadLog` (catalog open / recovery) | 464 | **false** | true | `getCommittedMutationStream` = **greedy/non-avoid** | returns `Optional` to catalog-instantiation code (no CommitProgressRecord) |

Key interaction with 1a:
- **alive=true (avoid mode):** a genuine dry read of an unfinalized *requested* version already **throws**
  inside 1a → caught by `processTransactions`' `catch (RuntimeException ex)` at **line 1170** →
  `forgetVolatileData()` + `forgetMutationsAfter(...)` → **rethrow** → `completeExceptionally`. So in the alive
  path, 1b's empty branch is reached **only** when the stream is legitimately drained (everything up to
  `lastWrittenCatalogVersion` already finalized) ⇒ `getLastFinalizedCatalogVersion() >= nextCatalogVersion`
  holds. 1b is therefore **defense-in-depth** for the alive path.
- **alive=false (recovery, greedy):** 1a intentionally lets a torn tail end **gracefully** (never throws in
  recovery). So during startup replay the stream *can* come back short/empty with
  `finalized < nextCatalogVersion`. **This is the path 1b actually guards.** ⇐ read §3 "Risk" carefully.

---

## 3. Item 1b — don't conflate empty stream with "already processed"

### Current code (`TransactionManager.java:1091-1094`)

```java
final Iterator<CatalogBoundMutation> mutationIterator = committedMutationStream.iterator();
if (!mutationIterator.hasNext()) {
    // previous execution already processed all the mutations
    return empty();
}
```

### Hypothesis / proposed change

`empty()` is the "someone else already did it" signal — the caller's `ifPresentOrElse` *empty* branch
(`handleNext:119-137`) merely waits for the live view and marks the record done. Returning it when the
requested version was **not** actually finalized is the silent-drop. Guard it:

```java
final Iterator<CatalogBoundMutation> mutationIterator = committedMutationStream.iterator();
if (!mutationIterator.hasNext()) {
    // An empty stream is only legitimately "already processed" when the finalized version has
    // actually reached the version we were asked to process. If it has not, the WAL delivered
    // nothing for a version we must reach — never signal "someone else did it" (that silently
    // finalizes at a stale version and hangs the commit-progress record; see bug-03).
    final long finalizedVersion = getLastFinalizedCatalogVersion();
    if (finalizedVersion >= nextCatalogVersion) {
        return empty(); // genuinely already processed by a concurrent/prior run
    }
    throw new WriteAheadLogCorruptedException(   // match the real ctor (see AbstractMutationLog.java:1266)
        <walKind>,
        "WAL mutation stream for catalog `" + <catalogName> + "` went dry at version " +
        finalizedVersion + " without reaching requested version " + nextCatalogVersion +
        " (last written version " + this.lastWrittenCatalogVersion.get() + "). A written " +
        "transaction was not readable — refusing to signal 'already processed'.",
        "Write-ahead log mutation stream ended before reaching a committed transaction."
    );
}
```

Notes / choices (RESOLVED — implement as written):
- **Exception type: `WriteAheadLogCorruptedException`** (`io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException`).
  It lives in **evita_engine** (not trapped in evita_store_server), so `TransactionManager` can import it
  directly, and it is symmetric with 1a *and* with the upstream gate `getFirstNonProcessedTransaction`
  (§below) which throws the same type. It is a `RuntimeException`, so the existing `catch (RuntimeException ex)`
  at line 1170 still runs `forgetVolatileData()` + `forgetMutationsAfter(...)` before rethrow →
  `completeExceptionally`. Check its constructor signature (`walKind`, message, …) at that file and match it
  (see `AbstractMutationLog.java:1266-1269` for a call example). If the constructor is awkward to satisfy from
  here, `GenericEvitaInternalError` (already imported) is an acceptable fallback.
- **Throw vs retry: fail loudly (throw).** FIXES.md permits "retry with backoff **or** fail loudly". 1a is the
  primary mechanism for the alive path and this branch is a backstop, so a plain throw is minimal, and the
  trunk-incorporation task naturally re-arrives via the publisher (another pipeline-level attempt) anyway. A
  bounded in-method retry would require re-opening the stream under the held trunk lock — more code, no test.

### ✅ Recovery-path risk — RESOLVED, no `alive` gating needed

The concern was: `processEntireWriteAheadLog` (alive=false, line 464) is the one caller that reaches this
branch after a greedy graceful-EOF, so could a **torn tail** legitimately land here and make the throw brick
catalog-open? **No.** Traced end to end:

- Caller: `Catalog.processWriteAheadLog` (`evita_engine/.../core/catalog/Catalog.java:1237-1272`) calls
  `processEntireWriteAheadLog(firstNonProcessedTxVersion, …)` **only inside**
  `getFirstNonProcessedTransactionInWal(getVersion()).ifPresentOrElse(txn -> …)` — i.e. only when a
  non-processed transaction actually exists — and passes that transaction's version as `nextCatalogVersion`.
- `getFirstNonProcessedTransactionInWal` → `catalogWal.getFirstNonProcessedTransaction`
  (`AbstractMutationLog.java:1216-1275`) **fully validates the record before returning**: it reads the
  transaction length, reads the complete `StorageRecord` (content), **and** seeks to the transaction's end
  and `readLong()`s the trailing cumulative checksum. Any torn/short tail (including the exact bug-03
  stripped-checksum scenario) hits `catch (Exception e) → throw new WriteAheadLogCorruptedException(...)` at
  lines 1265-1269 — recovery **fails loud at the persistence layer, before `processEntireWriteAheadLog` is
  even called.**

⇒ When `processEntireWriteAheadLog` runs, `firstNonProcessedTxVersion` is a **structurally complete, durable**
transaction, and `readFromVersion (= max(lastFinalized+1, 2))` equals it, so the stream must deliver at least
that transaction. An **empty** stream there is a genuine inconsistency in *both* paths. Ship the
**unconditional throw** — do not gate on `alive`. (In the alive path the empty branch is unreachable for an
unfinalizable version anyway, because 1a throws first; so the throw fires spuriously in neither path.)

---

## 4. Item 1c — bound `waitUntilLiveVersionReaches`

### Current code (`TransactionManager.java:1253-1258`)

```java
public void waitUntilLiveVersionReaches(long catalogVersion) {
    while (getLivingCatalog().getVersion() < catalogVersion) {
        Thread.onSpinWait();
    }
}
```

Unbounded busy-spin. If propagation to the live view never happens, the thread burns a full core forever and
the `CommitProgressRecord` is never completed. Called from exactly three sites (verified — no external
callers):
- `handleNext:88` (already-processed branch) — throw ⇒ `onNext` catch ⇒ `completeExceptionally` ✅
- `handleNext:131` (empty branch)          — throw ⇒ `onNext` catch ⇒ `completeExceptionally` ✅
- `processTransactions:1194` (after finalize, before returning the result) — throw propagates out of
  `processTransactions`: for `handleNext` ⇒ `completeExceptionally` ✅; for `drainWal` ⇒ **caught &
  rescheduled** (it catches `TransactionTimedOutException`) ✅; for recovery ⇒ surfaces during catalog open.

### Hypothesis / proposed change

Bound the wait with a deadline and throw `TransactionTimedOutException` (already imported; and `drainWal`
already treats it as "retry later") on expiry. Keep the fast path a spin, but stop burning a core on a
genuine stall by parking after a short spin window:

```java
public void waitUntilLiveVersionReaches(long catalogVersion) {
    // Generous deadline: mirrors the dangling-commit sweeper threshold
    // (sweepDanglingCommitProgress: max(60s, acceptanceTimeout*5)) so this never fires under normal
    // back-pressure, only on a genuine propagation stall.
    final long acceptanceTimeoutMs = this.configuration.transaction().waitForTransactionAcceptanceInMillis();
    final long deadlineMs = Math.max(60_000L, acceptanceTimeoutMs * 5);
    final long deadlineNanos = System.nanoTime() + deadlineMs * 1_000_000L;
    int spins = 0;
    while (getLivingCatalog().getVersion() < catalogVersion) {
        if (System.nanoTime() >= deadlineNanos) {
            throw new TransactionTimedOutException(
                "Live view of catalog `" + getCatalogName() + "` did not reach version " +
                catalogVersion + " within " + deadlineMs + " ms (stuck at " +
                getLivingCatalog().getVersion() + "); refusing to spin indefinitely."
            );
        }
        if (spins < 4096) {          // hot path: propagation normally lands in microseconds
            Thread.onSpinWait();
            spins++;
        } else {                     // genuine stall: stop burning a core
            LockSupport.parkNanos(100_000L); // 100µs
        }
    }
}
```

Choices to confirm:
- **`System.nanoTime()`** is fine here (monotonic, not wall-clock); the file already uses
  `System.currentTimeMillis()` for the processing loop — either is acceptable, prefer `nanoTime` for a
  duration.
- **Deadline source.** Reusing `max(60s, acceptanceTimeout*5)` keeps it aligned with the sweeper (§1386-1392)
  — the sweeper is the *other* safety net that fails dangling records; if both fire, `completeExceptionally`
  first-wins and the second is a harmless no-op. **Confirmed idempotent:** `CommitProgressRecord.completeExceptionally`
  (`evita_api/.../api/CommitProgressRecord.java:349`) just delegates to the three stage futures'
  (`onConflictResolved` / `onWalAppended` / `onChangesVisible`) `completeExceptionally`, which are
  `CompletableFuture`-semantics (first-wins, later calls return false / no-op). `this.configuration.transaction().waitForTransactionAcceptanceInMillis()`
  is reachable from here (already used at line 1390).
- **Spin-then-park.** The 4096-spin window preserves the microsecond-latency normal case; the 100µs park
  bounds CPU to ≈nothing during a real stall. If you consider this over-engineered, a plain deadline over the
  existing `Thread.onSpinWait()` is acceptable per FIXES.md (the *unbounded* spin is the bug) — but note the
  stated symptom is literally "cpu≈elapsed (100% core)", so parking is the more faithful fix.
- **`LockSupport`** import: `java.util.concurrent.locks.LockSupport` (add it).

---

## 5. Verification (no repro test exists — make one where cheap, then sweep)

1. **1c is trivially unit-testable — please add it.** Construct/insert a `TransactionManager` (or a minimal
   seam) whose `getLivingCatalog().getVersion()` never reaches the target, with the acceptance timeout set to
   a tiny value (so `max(60s, …)` … — note the 60s floor: to test the timeout cheaply you may need to make
   the deadline honor a smaller floor when the configured acceptance timeout is tiny, or expose the deadline
   as a package-private parameter). Assert `waitUntilLiveVersionReaches` throws `TransactionTimedOutException`
   within the deadline rather than hanging. Tag `@Tag(ENGINE) @Tag(TRANSACTION)`. If wiring a real
   `TransactionManager` is too heavy, at minimum add a `@Timeout`-guarded test that proves the method is
   bounded.
2. **1b hand-trace** (no easy unit test): confirm the recovery-path risk (§3) with the actual
   `processEntireWriteAheadLog` caller, and confirm the alive path still reaches the finalize path unchanged
   when the stream is non-empty.
3. **Regression sweep** (must stay green):
   ```
   rtk mvn -pl evita_store/evita_store_server install -DskipTests   # 1a supplier diff into ~/.m2 first
   rtk mvn -pl evita_test/evita_functional_tests test -o -Dtest='CatalogWriteAheadLogTest,CatalogWriteAheadLogIntegrationTest' -Dtest.tag.policy=off   # expect 21/0
   rtk mvn -pl evita_test/evita_functional_tests test -o -Dgroups="transaction | session" -Dtest.tag.policy=off
   ```
4. **Optional E2E fuzzer** (FIXES.md §1, needs the manually-run test server + pristine senesi data — do NOT
   touch `/www/oss/evita/evitaDB-dev/data*` or the backup zips; ports 5555/8005 are Johnny's): expect batch
   commits with **no** "Sweeping dangling CommitProgressRecord" WARN, ~2 min wall; run 3× (hang fired 2-of-3
   pre-fix).

---

## 6. Constraints (unchanged, must hold)

- Address the human as **Johnny**. Use `rtk mvn`, never plain `mvn`; never pipe `rtk mvn` through `grep/head`
  (use `tail -N` or read the surefire `.txt`). `rtk` also compresses identifiers in *other* commands' output
  (e.g. `rg`) — use `rtk proxy <cmd>` when you need raw identifiers.
- **Do not** run irreversible git ops (stash/reset/checkout/commit/push) without Johnny's permission. Nothing
  in this fix session is committed; the branch `warmup-upsert-alloc-optimization` also carries UNRELATED
  warmup/docs changes — do not fold them in.
- Reinstall `evita_store_server` to `~/.m2` before functional runs (stale-jar trap).
- Keep the existing failing/oracle tests intact; do not weaken assertions.

## 7. What to report back

For each of 1b and 1c: the exact patch you applied, the recovery-path decision from §3 (with the evidence
from the `processEntireWriteAheadLog` caller trace), whether you added a unit test and its result, and the
regression-sweep numbers. If you diverge from the proposed patch, say why.
