# Fix-session handoff brief — senesi upsert index corruption

Audience: the fix session (any model). All root-causing and reproduction work is DONE — do not
re-investigate. Every fix below has a failing test already on disk; your job is to make them pass
with minimal, targeted changes. Read the per-bug scenario MD in `scenarios/` before touching code.

## Ground rules

- **TDD is already half-done**: the failing tests exist. Run the relevant test FIRST, watch it fail
  with the documented signature, then fix, then watch it pass. Do not weaken or delete a test to
  make it pass. Do not "fix" the two BY-DESIGN-failing concurrency tests (see item 5) as a side
  effect of unrelated changes.
- Maven via `rtk mvn ...`; never pipe its output through grep/head (use `tail -N` or read surefire
  `.txt` reports). If surefire throws `NoSuchMethodError` after an engine signature change, run
  `rtk mvn -pl evita_engine install -DskipTests` (stale ~/.m2 jar trap).
- Tag policy blocks targeted runs: add `-Dtest.tag.policy=off`.
- Repo rules apply: JavaDoc on everything, no TODOs, no commented-out code, no issue numbers or
  plan-doc references in code comments, defensive-design rule (unexpected state ⇒ throw, never
  silently skip). `serialVersionUID`: bump + BWC reader only if the format change crosses a
  RELEASED minor boundary; in-line change otherwise.
- Branching: from `dev`, name `{issue-id}-{kebab-description}`. File GitHub issues first
  (`gh api repos/FgForrest/evitaDB/milestones` for the milestone; labels: `bug`). One PR may carry
  fixes 1–3 (same subsystem); keep 4 and 5 separate.
- Never touch `/www/oss/evita/evitaDB-dev/data*` directories or
  `/home/jno/Downloads/backup_senesi_actual_*.zip`. Ports 5555/8005 belong to a manually-managed
  test server.

## Fix order (dependency-free, ordered by production impact)

### 1. Bug-03 — WAL read path swallows read failures (`scenarios/bug-03-commit-progress-hang.md`)

The full causal chain is documented in the MD (§ROOT CAUSE, §Distilled reproduction). Three
independent defects; fix all three:

**1a. Distinguish "read failure" from "end of data" in the WAL mutation stream.**
- `MutationSupplier.get()` Phase 3, `evita_store/evita_store_server/.../store/wal/MutationSupplier.java:198-201`:
  the broad `catch (Exception ex)` converts a mid-read `KryoException` (thrown by
  `ObservableInput.require()`, `ObservableInput.java:467-537`) into a silent end-of-stream. A read
  that fails BEFORE reaching `requestedCatalogVersion` must surface (throw), not end the stream.
  A genuine clean EOF (no more bytes at a transaction boundary) may still end the stream.
- `AbstractMutationSupplier` constructor, `.../store/wal/AbstractMutationSupplier.java:277-283`:
  `catch (BufferUnderflowException)` is DEAD CODE (nothing in the codebase throws it — verified);
  the real exception there is `KryoException`, which today escapes uncaught (crash path). Replace
  the dead catch with deliberate handling consistent with 1a: incomplete TAIL of the WAL (bytes of
  the last, possibly still-being-written transaction) may be treated as not-yet-available ONLY when
  the caller did not explicitly request that version; a requested version must never silently
  vanish.
- Acceptance: the two failing tests pass —
  `CatalogWriteAheadLogTest$DryReadVisibilityRaceTests.shouldNotReturnDryStreamForLastAppendedVersionMissingOnlyTrailingChecksum`
  and
  `CatalogWriteAheadLogIntegrationTest$MisalignedReadSwallowTests.shouldNotSilentlyEndStreamWhenAdvancingIntoAGenuinelyUnderflowingTransaction`
  (both in `evita_test/evita_functional_tests/src/test/java/io/evitadb/store/wal/`).
  Run: `rtk mvn -pl evita_test/evita_functional_tests test -Dtest='CatalogWriteAheadLogTest,CatalogWriteAheadLogIntegrationTest' -Dtest.tag.policy=off`
  (expect 21 run / 0 fail after the fix; today it is 21 run / exactly these 2 fail).

**1b. `TransactionManager.processTransactions` must not conflate an empty stream with "already
processed".** `evita_engine/.../core/transaction/TransactionManager.java:1092-1094`: before
returning `empty()`, verify `getLastFinalizedCatalogVersion() >= nextCatalogVersion`. If not, the
requested transaction exists (`lastWrittenCatalogVersion >= nextCatalogVersion`) but was not
readable — retry with backoff or fail the commit progress record loudly; never return the
"someone else did it" signal.

**1c. Bound the spin.** `TransactionManager.waitUntilLiveVersionReaches`
(`TransactionManager.java:1253-1258`) is an unbounded `Thread.onSpinWait()` loop; the caller
(`TrunkIncorporationTransactionStage.handleNext` empty branch,
`.../core/transaction/stage/TrunkIncorporationTransactionStage.java:119-137`) never completes the
`CommitProgressRecord` if the wait never ends. Give the wait a deadline (aligned with the 60 s
sweeper, or configurable); on timeout, complete the commit progress exceptionally. A swept commit
must never leave an `Evita-transaction` thread with cpu≈elapsed (100 % core) behind.

- End-to-end validation (optional, needs the test server + pristine senesi data): boot
  `evita_server/run-server.sh` on a pristine copy, run
  `io.evitadb.spike.SenesiUpsertFuzzer localhost 5555 senesi 1 500 1 /tmp/oplog.txt`
  (classpath: regenerate via `rtk mvn -pl evita_test/evita_performance_tests dependency:build-classpath -Dmdep.outputFile=cp.txt`
  plus `evita_test/evita_performance_tests/target/classes`). Expect: batch commits with NO
  "Sweeping dangling CommitProgressRecord" WARN, ~2 min wall time. The hang fired 2-of-3 runs
  pre-fix, so run it at least three times.

### 2. Bug-05 — session close future never completes on flush throw (`scenarios/bug-05-session-close-future-never-completes.md`)

Any exception thrown from the close-time warm-up flush (reached via
`EntityCollection.createFlushFuture` → `popTrappedUpdates`) must complete the session close future
EXCEPTIONALLY. Today it completes never, so
`SessionRegistry.closeAllActiveSessionsAndSuspend` (`SessionRegistry.java:213-226`,
`allOf().join()` — its `.exceptionally(ex -> null)` only helps futures that complete) and
`Evita.closeInternal` (`Evita.java:1846-1854`) hang forever.
- Write the missing failing unit test first (none exists yet — this is the only item without one):
  a session whose flush throws must produce a close future completed exceptionally within a bounded
  time; `Evita.close()` must terminate. Model the trigger on the scenario MD (any
  `RuntimeException` from the flush path is sufficient; no index corruption needed).
- Acceptance: new test passes; the `@Timeout(SEPARATE_THREAD)` tearDown workaround in
  `StaleLeafPageTwinWriterReproductionTest` is no longer load-bearing (leave the timeout in place —
  it is cheap belt-and-braces — but the tearDown must now finish well within it).

### 3. Bugs 01+02 — load-side guard for the stale leaf-page twin (`scenarios/bug-01-*.md`, `bug-02-*.md`, root doc `bug-04-stale-leaf-page-twin.md`)

The persisted PAGED `InvertedIndex` loader assembles pages without any cross-page check, so a page
list containing a frozen stale snapshot of a leaf NEXT TO its superseding page loads silently and
produces the two production signatures ("Key is already present in the tree!",
"Sanity check - record not found!").
- Fix site: `evita_engine/.../index/invertedIndex/InvertedIndex.java` — `fromPersistedPages`
  (:487-536) / `assembleFromSingleLeafTrees` / `buildInternalNode`: enforce strict cross-page
  monotonicity (last key of page i < first key of page i+1).
- **Fail-fast is mandatory** (`GenericEvitaInternalError` naming the index, attribute, page
  sequences, and boundary keys). **Healing is additionally allowed and desirable**: in the observed
  corruption the stale page is a STRICT PREFIX of its successor (verified on all four production
  twins), so when `pages[i]` is a prefix of `pages[i+1]`, dropping `pages[i]` provably loses no
  data — heal, log a WARN with full identification, and continue. Any overlap that is NOT a strict
  prefix must fail fast (unknown corruption shape).
- Acceptance: all 3 methods of
  `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/attribute/StaleLeafPageTwinReproductionTest.java`
  pass (they are written to pass under fail-fast AND under heal).
  Run: `rtk mvn -pl evita_test/evita_functional_tests test -Dtest=StaleLeafPageTwinReproductionTest -Dtest.tag.policy=off`.
- Production remediation note (ops, not code): the production senesi catalog carries FOUR twins
  (list in bug-04 MD, §Writer-identity round 2). With heal implemented, a reload+flush cycle
  repairs them; without it, a full reindex on the fixed build is required. Surface this in the PR
  description.

### 4. Session concurrency guard (`scenarios/bug-04-stale-leaf-page-twin.md`, §Writer-side synthetic reproduction)

`EvitaSessionProxy` (`evita_engine/.../core/session/EvitaSessionProxy.java:93`, `insideInvocation`
inc :392 / dec :411) COUNTS concurrent invocations of a `@NotThreadSafe` session but never
serializes or rejects them; two threads on one WARM_UP session demonstrably corrupt shared index
trees silently (two failing tests prove it).
- **DECIDED (Johnny, 2026-07-14): fail-fast.** Throw a `ConcurrentInitializationException`-style
  error when a second invocation arrives while `insideInvocation > 0` on entry — do not serialize.
  Rationale: matches the project's defensive-design rule (unexpected states must throw, never be
  silently absorbed — see CLAUDE.md); the known FG client (`lib_eshop_evita`) was already
  investigated and exonerated of concurrent session use (sequential loop, no retry, no leak), so
  this is unlikely to break that caller. Other unaudited callers remain an open risk to monitor
  after rollout, but do not let that block implementation.
- After implementing, UPDATE the two by-design-failing tests in
  `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/functional/storage/StaleLeafPageTwinWriterReproductionTest.java`
  (`shouldSurviveConcurrentUpsertsOnSingleWarmUpSession`,
  `shouldSurviveSplitAimedOverlappingUpsertsOnSingleWarmUpSession`): the oracle expectation becomes
  "either every upsert serialized correctly OR the racing call was loudly rejected — NEVER silent
  corruption, NEVER a lost successful upsert". Until this item lands, those two tests fail by
  design — do not merge them into a green-required CI lane (add `@Disabled` with a reason if a full
  CI run is needed earlier, and say so in the PR).
- Note: this guard is justified by the failing tests alone. Whether it is also THE production
  writer of the four twins is still open (bug-04 investigation continues separately) — do not block
  on that answer, and do not claim in the PR that it closes the incident.

### 5. Out of scope for the fix session

- Bug-04 writer identity (the WARM_UP full-reindex simulation and the off-request-thread audit are
  running in the investigation session). Do not attempt.
- Any performance work, refactoring, or cleanup beyond the fix sites above.
- The unrelated `core` file in the repo root (rtk crash dump, June 22) and the `data_dirty_*`
  directories — leave them.

## Verification matrix (run after each fix, all after the last)

| What | Command | Expected |
|---|---|---|
| WAL tests | `rtk mvn -pl evita_test/evita_functional_tests test -Dtest='CatalogWriteAheadLogTest,CatalogWriteAheadLogIntegrationTest' -Dtest.tag.policy=off` | 21 run, 0 fail |
| Twin load guard | `rtk mvn -pl evita_test/evita_functional_tests test -Dtest=StaleLeafPageTwinReproductionTest -Dtest.tag.policy=off` | 3 run, 0 fail |
| Writer harness | `rtk mvn -pl evita_test/evita_functional_tests test -o -Dtest=StaleLeafPageTwinWriterReproductionTest -Dtest.tag.policy=off` | pre-item-4: 4 run, exactly 2 fail (by design), tearDown < 90 s; post-item-4: 4 run, 0 fail (after oracle update) |
| Engine unit sweep | `rtk mvn -pl evita_engine test` | no new failures vs dev baseline |
| Fuzzer soak (optional, needs server) | see item 1 end-to-end validation | no sweep WARN, no new signatures, 3+ runs |

## Primary sources

- `scenarios/bug-01-filterindex-record-not-found.md` — load-side signature 1 + JDWP capture recipe
- `scenarios/bug-02-sorttree-key-already-present.md` — load-side signature 2
- `scenarios/bug-03-commit-progress-hang.md` — full causal chain, live capture, distilled repro
- `scenarios/bug-04-stale-leaf-page-twin.md` — root corruption anatomy, writer-side evidence rounds
- `scenarios/bug-05-session-close-future-never-completes.md` — close-future hang
- `investigations/lib-eshop-evita-client-concurrency-REPORT.md` — client exoneration (context)
- `PLAN.md` — original runbook; §5 fuzz/bisect procedure
