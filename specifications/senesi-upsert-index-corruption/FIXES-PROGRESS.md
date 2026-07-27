# FIXES.md progress — resumption brief

Authoritative state of the fix session for `FIXES.md`. Read this + `FIXES.md` to continue. Branch:
`warmup-upsert-alloc-optimization` (carries UNRELATED warmup/docs changes too — sort out branching before any
commit; nothing here is committed). Build with `rtk mvn`; after editing an engine/store module reinstall it
(`rtk mvn -pl <module> install -DskipTests`) before running functional tests; targeted runs need
`-Dtest.tag.policy=off`.

## Status summary

| Item | Sub | State | Verifying test(s) |
|---|---|---|---|
| 3 (bug-01/02) load-side twin guard/heal | — | ✅ DONE, green | `StaleLeafPageTwinReproductionTest` (3/3) |
| 4 (bug-04) session concurrency fail-fast | — | ✅ DONE, green | `StaleLeafPageTwinWriterReproductionTest` (4/4), `SessionKillerTest` (8/8), transaction/session/proxy sweep (1851/0) |
| 2 (bug-05) flush-throw completes close future | — | ✅ DONE, green | `WarmUpFlushFailureCloseTest` (new, TDD; hung 88s → 3.7s) |
| 1 (bug-03) WAL read path | 1a | ✅ DONE, green | `CatalogWriteAheadLogIntegrationTest` Test 2 ✅; `CatalogWriteAheadLogTest` Test 1 ✅ |
| 1 (bug-03) | 1b | ✅ DONE, green | (hand-traced; guarded by WAL suite 21/0 + tx\|session sweep 1414/0) |
| 1 (bug-03) | 1c | ✅ DONE, green | `TransactionManagerBoundedWaitTest` (new, 3/3) |

WAL suite right now: `CatalogWriteAheadLogTest,CatalogWriteAheadLogIntegrationTest` = **21 run, 0 fail, 0 error** ✅.

Test 1 unblocked by Fable 5: NOT a reader misalignment — the fixture's `txSizes={55,152,199,46}` were
content-byte counts filled with literal bytes `0,1,2,3,…`, never serialized mutations, so once 1a un-dried
the constructor, Phase 2 faithfully deserialized garbage (`class ID: 11` = content byte 13 `0x0D` minus 2,
byte-exact). Fix was test-only: `DryReadVisibilityRaceTests` got a `@BeforeEach` that rebuilds the WAL with
real serialized mutations via `CatalogWriteAheadLogIntegrationTest.writeWal` (assertion untouched). This
**validates** the 1a supplier changes — they exposed the bad fixture, they did not cause it.

## Files changed this session (all uncommitted)

- **Item 3** — `evita_engine/.../index/invertedIndex/InvertedIndex.java`: added `@Slf4j`, imports; in
  `fromPersistedPages` added `resolveHealedPageIndices(...)` + `isStrictPrefix(...)` — enforce cross-page
  monotonicity, heal (drop) a strict-prefix stale twin with a WARN, fail-fast `GenericEvitaInternalError` on
  any other overlap.
- **Item 4** — new `evita_api/.../api/exception/ConcurrentSessionAccessException.java`
  (extends `EvitaInvalidUsageException`, takes `UUID sessionId, owningThreadName, intrudingThreadName`);
  `evita_engine/.../core/session/EvitaSessionProxy.java`: added `AtomicReference<Thread> owningThread`; in
  `invoke`'s **else-branch only** (business methods; housekeeping like `isActive`/`isInactiveAndIdle` stay
  unguarded), reject a *different* thread entering while the session is owned (thread-identity based, allows
  same-thread re-entrancy), release ownership on the outermost unwind. Updated
  `evita_test/.../api/functional/storage/StaleLeafPageTwinWriterReproductionTest.java` oracle: race casualties
  must be `ConcurrentSessionAccessException` (`assertConcurrentAccessRejectedLoudly`).
- **Item 2** — `evita_engine/.../core/session/EvitaSession.java` `closeInternal` WARM_UP branch: wrapped
  `this.catalog.flush()` in try/catch — on synchronous throw, `commitProgress.completeExceptionally(ex)`, set
  `closedFuture = commitProgress.on(commitBehavior)`, complete `closingSequenceFuture`, `return`. New test
  `evita_test/.../api/functional/storage/WarmUpFlushFailureCloseTest.java` (injects a throwing
  `DataStoreMemoryBuffer` proxy via reflection on `EntityCollection.dataStoreBuffer`).
- **Item 1a** — `evita_store/evita_store_server/.../wal/supplier/AbstractMutationSupplier.java`:
  `readAndRecordTransactionMutation` room check requires only **content** (`4 + contentLength`) in
  `avoidPartiallyFilledBuffer` mode; constructor scan loop uses new helper `requiredEndPosition(...)`
  (full end minus `CUMULATIVE_CRC32_SIZE` in avoid mode). `.../wal/supplier/MutationSupplier.java` Phase 3
  rewritten: `mayEndGracefully = !avoid || lastDeliveredVersion >= requestedCatalogVersion`; content-aware
  `canProceed`; the "no room / can't move to next file" branch throws when `!mayEndGracefully`;
  `catch (WriteAheadLogCorruptedException)` always rethrows; other read exception → graceful if
  `mayEndGracefully` else throw a `WriteAheadLogCorruptedException`. (`ReverseMutationSupplier` uses
  `avoidPartiallyFilledBuffer=false`, so these relaxations are inert for it.) **+ review remark #3** — the
  Phase-3 `transactionMutation == null` branch is now also gated on `mayEndGracefully` (see Post-review
  follow-ups).
- **Item 1a Test-1 fixture fix** — `evita_test/.../store/wal/CatalogWriteAheadLogTest.java`:
  `DryReadVisibilityRaceTests` got its own `@BeforeEach` rebuilding the WAL with real serialized mutations via
  `CatalogWriteAheadLogIntegrationTest.writeWal` (+ 2 imports, `@AfterEach` closing the output keeper); the
  test method/assertion untouched.
- **Items 1b + 1c** — `evita_engine/.../core/transaction/TransactionManager.java`: imports
  (`WriteAheadLogCorruptedException`, `WalKind`, `LockSupport`, `LongSupplier`), constants
  `SPIN_ATTEMPTS_BEFORE_PARK`/`PARK_INTERVAL_NANOS`, guarded empty-stream branch in `processTransactions`,
  bounded `waitUntilLiveVersionReaches` + static `waitUntilVersionReaches` seam, shared `safetyDeadlineMs()`
  (also used by `sweepDanglingCommitProgress`). New test
  `evita_test/.../core/transaction/TransactionManagerBoundedWaitTest.java`. **+ review remark #5** — `drainWal`
  catch widened to `TransactionTimedOutException | WriteAheadLogCorruptedException` (see Post-review
  follow-ups).

## Working-tree inventory & commit-split map (CRITICAL before any commit)

The working tree mixes THREE unrelated bodies of work + untracked data dirs. **Only these files belong to the
bug-03/04/05 fix set** (commit these together, off a fresh branch from `dev`):

Modified:
- `evita_engine/.../core/session/EvitaSession.java` (item 2)
- `evita_engine/.../core/session/EvitaSessionProxy.java` (item 4)
- `evita_engine/.../core/transaction/TransactionManager.java` (items 1b/1c + remark #5)
- `evita_engine/.../index/invertedIndex/InvertedIndex.java` (item 3)
- `evita_store/.../wal/supplier/AbstractMutationSupplier.java` (item 1a)
- `evita_store/.../wal/supplier/MutationSupplier.java` (item 1a + remark #3)
- `evita_test/.../store/wal/CatalogWriteAheadLogIntegrationTest.java` (item 1a Test 2)
- `evita_test/.../store/wal/CatalogWriteAheadLogTest.java` (item 1a Test 1 fixture)

New (untracked):
- `evita_api/.../api/exception/ConcurrentSessionAccessException.java` (item 4)
- `evita_test/.../api/functional/storage/StaleLeafPageTwinWriterReproductionTest.java` (item 4)
- `evita_test/.../api/functional/storage/WarmUpFlushFailureCloseTest.java` (item 2)
- `evita_test/.../core/transaction/TransactionManagerBoundedWaitTest.java` (item 1c)
- `evita_test/.../index/attribute/StaleLeafPageTwinReproductionTest.java` (item 3)
- `specifications/senesi-upsert-index-corruption/` (this spec + handoffs + progress doc)

**NOT part of this fix set — do NOT fold in** (separate warmup/collation/GroupHaving/spike work): the
`Attributes.java`/`Reference.java`/`ContainerizedLocalMutationExecutor.java` edits, all `LocalizedStringComparator*`
+ new `CollationKeyCache.java`, the three `*ConstraintSchemaBuilder.java`, all `documentation/user/**`, every
`evita_test/evita_performance_tests/**` spike + `WARMUP_*.md`, `warmup-copy-benchmark.sh`, and the other
`specifications/**` dirs.

**Untracked data dirs — DO NOT `rm`, DO NOT commit** (fuzzer artifacts; deleting a catalog dir orphans the
engine WAL — use the API if cleanup is ever needed): `core`, `data_dirty_after_fuzz_20260714/`,
`data_dirty_bug03_spin_20260714/`, `data_snapshot_pristine/`.

## The blocker (Item 1a, Test 1) — RESOLVED

`CatalogWriteAheadLogTest$DryReadVisibilityRaceTests.shouldNotReturnDryStreamForLastAppendedVersionMissingOnlyTrailingChecksum`
used to derail in Phase 2 with `KryoException: Encountered unregistered class ID: 11`. The
`TAIL_MANDATORY_SPACE` suspicion was refuted (the reader's 8-byte reserve is record-relative, never
file-relative); the actual cause was the test fixture writing literal bytes `0,1,2,3,…` as transaction
content — see the "Test 1 unblocked" paragraph above. Investigation brief:
`HANDOFF-fable5-wal-dryread-test1.md` (same dir); fixed 2026-07-14, WAL suite 21/0.

## Remaining work (in order)

1. **Test 1** — ✅ DONE. Fable 5 root-caused it as a bad test fixture (synthetic content bytes, not real
   mutations); fix was test-only (`DryReadVisibilityRaceTests` `@BeforeEach` rebuilds the WAL with real
   serialized mutations via `CatalogWriteAheadLogIntegrationTest.writeWal`). Verified 21/0. The 1a supplier
   changes are validated, not refuted.
2. **Items 1b + 1c** — ✅ DONE (Fable 5, 2026-07-14 eve). All in
   `evita_engine/.../core/transaction/TransactionManager.java`:
   - **1b**: the `!mutationIterator.hasNext()` branch of `processTransactions` now returns `empty()` only when
     `lastFinalizedVersion >= nextCatalogVersion` (the local is exact — `updateLastFinalizedCatalog` is only
     invoked under the held trunk lock); otherwise it throws `WriteAheadLogCorruptedException(WalKind.CATALOG)`,
     which the existing `catch (RuntimeException)` routes through `forgetVolatileData()` +
     `forgetMutationsAfter(...)` before rethrow → `completeExceptionally`. Unconditional (no `alive` gating) —
     but note the §3 recovery-safety argument in the handoff was corrected: `getFirstNonProcessedTransaction`'s
     tail `readLong` proves only CONTENT-completeness (its seek lands 8B *before* the trailing CRC); what
     actually guarantees the greedy stream can't legitimately go dry is boot-time `checkAndTruncate`
     (`AbstractMutationLog` ctor ~:742): `scanWalFileForLastCompleteTransaction` advances
     `offset += 4 + txSize + 8`, so a CRC-less tail makes `offset > fileLength` → damaged-tail truncation
     before recovery ever reads the WAL. Also, 1b is load-bearing (not just defense-in-depth) for the alive
     path: 1a throws on failed *reads*, but a constructor room-check miss (header not yet visible in the
     reader's file-length view) still yields a silent empty stream that only 1b converts into a loud failure.
   - **1c**: `waitUntilLiveVersionReaches` delegates to new package-private static
     `waitUntilVersionReaches(LongSupplier, long, deadlineMs, catalogName)` — spins 4096× (`Thread.onSpinWait`),
     then parks in 100µs intervals, throws `TransactionTimedOutException` on deadline expiry (overflow-safe
     `nanoTime` comparison). Deadline = new shared `safetyDeadlineMs()` = `max(60s, acceptanceTimeout*5)`,
     also reused by `sweepDanglingCommitProgress` (deduplicated). New unit test
     `evita_test/.../core/transaction/TransactionManagerBoundedWaitTest.java` (3/3, `@Timeout`-guarded,
     `@Tag(ENGINE) @Tag(TRANSACTION)`).
3. **Final verification matrix** (FIXES.md §"Verification matrix"): WAL tests 21/0 ✅ (re-run after 1b/1c);
   `StaleLeafPageTwinReproductionTest` 3/0 ✅; writer harness 4/0 ✅; `transaction | session` sweep
   **1414/0** ✅; earlier `wal | storage` sweep 1351/0 ✅. Remaining: optional E2E senesi fuzzer (3×, needs
   Johnny's manually-run server on :5555).

## Verify commands (copy/paste)

```
# item 3
rtk mvn -pl evita_test/evita_functional_tests test -o -Dtest=StaleLeafPageTwinReproductionTest -Dtest.tag.policy=off
# item 4
rtk mvn -pl evita_test/evita_functional_tests test -o -Dtest=StaleLeafPageTwinWriterReproductionTest -Dtest.tag.policy=off
rtk mvn -pl evita_test/evita_long_running_tests test -o -P longRunning -Dtest=SessionKillerTest -Dtest.tag.policy=off
# item 2
rtk mvn -pl evita_test/evita_functional_tests test -o -Dtest=WarmUpFlushFailureCloseTest -Dtest.tag.policy=off
# item 1 (1a+1b+1c all landed; expect 21/0)
rtk mvn -pl evita_test/evita_functional_tests test -o -Dtest='CatalogWriteAheadLogTest,CatalogWriteAheadLogIntegrationTest' -Dtest.tag.policy=off
# item 1c bounded-wait unit test (expect 3/0)
rtk mvn -pl evita_test/evita_functional_tests test -o -Dtest=TransactionManagerBoundedWaitTest -Dtest.tag.policy=off
# post-1b/1c sweep (expect 0 failures; was 1414/0/1-skipped on 2026-07-14)
rtk mvn -pl evita_test/evita_functional_tests test -o -Dgroups="transaction | session" -Dtest.tag.policy=off
```

## Decisions / gotchas locked in
- Item 4 guard is **thread-identity** (reject a different thread; allow same-thread re-entrancy) placed in the
  `invoke` else-branch so `SessionKiller` housekeeping methods stay unguarded. Full transaction/session/proxy/
  gRPC suite (1851) stayed green — no legitimate concurrent single-session caller surfaced.
- Item 2 fix preserves the existing `flushFuture.execute(...)` rethrow behavior; only the `catalog.flush()`
  synchronous-throw gap was closed (that is the actual hang path: it left `commitProgress` /
  `closingSequenceFuture` pending, so a SECOND close — `Evita.close()` — hung).
- Item 1 rule gates on `avoidPartiallyFilledBuffer`; greedy reads (`requestedCatalogVersion == Long.MAX_VALUE`)
  and `ReverseMutationSupplier` keep the strict full-record behavior (torn tails end gracefully during
  recovery, never throw).

## Post-review follow-ups (code-review remarks on the 1b/1c changeset)

- **Remark #3 — residual silent-dry path in `MutationSupplier` — APPLIED (2026-07-14).** Phase-3
  `if (this.transactionMutation == null) return null` (after advancing past a delivered txn, incl. across a
  file rollover) bypassed the `mayEndGracefully` gate: a next record whose content isn't yet on disk
  (`readAndRecordTransactionMutation` → `empty()` at `AbstractMutationSupplier.java:464-466`) ended the stream
  silently even with the requested version undelivered → partial-delivery-then-stale-finalize (NOT covered by
  1b, which only guards the zero-delivery empty stream). Gated it: `mayEndGracefully` → `return null`, else
  `throw WriteAheadLogCorruptedException`. Greedy/recovery + `ReverseMutationSupplier` unaffected. WAL suite +
  bounded-wait re-verified **24/0**.
- **Remark #3 adjacent (NOT applied — separate, pre-existing):** `MutationSupplier.java:190-191` passes the
  *old* file's `currentFileLength` into `readAndRecordTransactionMutation` even after `moveToNextWalFile(1)`
  reset `filePosition` to the new file — stale `fileSize` across a rollover. Rarely bites; awaiting a decision
  before touching (Johnny scoped this pass to the gate only).
- **Remark #5 — `drainWal` only catches `TransactionTimedOutException` — APPLIED (2026-07-14).** 1b's new
  `WriteAheadLogCorruptedException` could propagate out of the scheduled `drainWal` task
  (`TransactionManager.java:~1449`). Traced `DelayedAsyncTask.runTask` (`:301-333`): on a `RuntimeException` it
  logs ERROR and **rethrows**, skipping `pause()`, so `nextPlannedExecution` stays non-MIN → subsequent
  `schedule()` calls no-op → **the WAL-draining task would be permanently disabled** (more than "surfaces in
  logs"); a *transient* lagging-visibility race (the bug-03 class) in the background drainer would kill it.
  Fix: widened the catch to `TransactionTimedOutException | WriteAheadLogCorruptedException` → reschedule
  (`return 0`). Rationale: the drainer is best-effort and owns no commit-progress record; the authoritative
  loud surfacing of a genuine inconsistency is the trunk-incorporation stage (`handleException` →
  `completeExceptionally`) on the client commit path, which is unchanged. WAL + bounded-wait re-verified 24/0;
  `transaction | session` sweep **1414/0/1-skipped** (unchanged from baseline).
</content>
