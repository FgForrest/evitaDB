# Bug 04 — ROOT CORRUPTION: stale leaf-page twin in the persisted PAGED InvertedIndex

**Status: corruption fully characterized on the production catalog (live JDWP, 2026-07-14); bugs 01 + 02 are its
mechanical consequences. The WRITE-side event that persists the twin is NOT yet reproduced from a
clean catalog — that reproduction is the remaining open task (see §Writer-reproduction below).**

## What is on disk (ground truth, production-catalog pristine snapshot)

Reduced index `categories:142816`, attribute `published` (both-flagged ⇒ shared value tree; bucket
keys are `Instant`s produced by the `OffsetDateTime→Instant` filter normalizer,
`FilterIndex.getNormalizer`). The persisted `FilterIndexStoragePart` leaf-page list references 43
pages `[0..42]` (contiguous, no duplicates in the list). Their content, however:

| page seq | buckets | key span | note |
|---|---|---|---|
| 28 | 128 | `11:52:07.920` … `11:52:31.158` | healthy |
| **29** | **128** | `11:52:31.256` … `12:00:47.504` | **frozen STALE snapshot** |
| **30** | **190** | `11:52:31.256` … `12:01:33.385` | **first 128 buckets IDENTICAL to page 29** (same keys, same record ids), +62 later keys |
| 31 | 186 | `12:01:33.490` … | healthy |

⇒ page 29 is an old snapshot of the same logical leaf that page 30 supersedes; **both** stayed in
the persisted page list. Every catalog load re-assembles both verbatim → the in-memory bucket tree
holds a duplicated 128-key run (5726 buckets total, 5598 distinct) and violates its fundamental
strictly-ascending-keys invariant at exactly one point (bucket[3838] sorts before bucket[3837]).

All catalog files' mtimes are `2026-07-13 13:14–13:15` (preserved by `cp -a`) — the corruption was
written by whatever produced the dataset (production-shaped transactional re-publish churn,
catalogVersion=97), **not** by any later local experiment.

## Why the loader accepts it silently

`InvertedIndex.fromPersistedPages` → `TransactionalBucketBPlusTree.bulkLoadPage` asserts strict
ascending order **within** each page, but `assembleFromSingleLeafTrees` → `buildSpine` /
`buildInternalNode` takes each page's `getLeftBoundaryKey()` as separator **without any cross-page
monotonicity check** — an overlapping twin page loads silently.

## Mechanical consequences (both confirmed live via JDWP on the loaded production tree)

- **Bug 02** (`Key is already present in the tree!`): `SortIndexChanges.getValueTree()` iterates the
  buckets in physical order and inserts every key into the `CumulativeWeightBPlusTree`; the second
  copy of a twin key collides. Confirmed at the throw: colliding key `Instant
  2026-07-13T11:52:31.256666037Z` = bucket @3710 and @3838, both `recs=[1205621]`.
- **Bug 01** (`Sanity check - record not found!`): B+ descent routes equality probes by separators
  that are no longer monotonic. On the *prefix*-twin geometry point reads still succeed (verified
  live: `getRecordsEqualTo` finds records — the reachable leaf is a superset); once live churn makes
  the twins DIVERGE (removals/inserts land only in the reachable copy, separators shift), probes
  start landing in the stale leaf and miss records ⇒ the removal sanity check throws.

## Distilled reproduction test (DONE — fails on current code with both signatures)

`evita_test/evita_functional_tests/src/test/java/io/evitadb/index/attribute/StaleLeafPageTwinReproductionTest.java`
— loads hand-crafted twin pages through the real `InvertedIndex.fromPersistedPages` path (production-catalog
anatomy: 128-bucket stale twin + 190-bucket successor; plus a diverged/interleaved variant) and
asserts the desired invariant. Current failures:

```
shouldRefuseOrHealStaleTwinPageOnLoad            bucket[256] does not sort after bucket[255]
shouldSurviveSortIndexMaintenanceOverTwinCorruptedTree   Key is already present in the tree!   (bug-02)
shouldSurviveFilterRemovalOverTwinCorruptedTree          Sanity check - record not found!      (bug-01)
```

The tests pass under EITHER accepted fix direction: (a) load-side fail-fast/heal (cross-page
monotonicity check in `fromPersistedPages`/`assembleFromSingleLeafTrees`), and/or (b) write-side fix
of the flush that emits the twin.

## Writer identified as the WARM_UP path (2026-07-14, checksum proof)

The user's backup `backup_<catalog>_actual_2026-07-13T13-14-35...zip`, taken immediately after the full
reindex (fresh catalog populated in ONE `WARM_UP` session on the ~2026-07-13-morning dev build, then
`goLive`), is **byte-for-byte identical** (md5 of `product-6_0.collection`, `<catalog>_0.catalog`,
`<catalog>.boot`) to the corrupted dataset. ⇒ **the twin was written by the single warm-up
flush at goLiveAndClose; transactional commits and savepoints are fully exonerated for the writer.**

Refined anatomy (bounds the in-memory event):

- Warm-up flush happens ONCE (session close), so the **in-memory tree already contained both twin
  leaves** at that moment; all 43 page sequences were allocated in that one
  `collectChangedPages` walk (hence contiguous `0..42`).
- The frozen twin (128 keys = exactly half of the 256 block) is the **left half of a leaf split
  captured at the split moment**; its right neighbors (190 = 128+62, 186 = 128+58) are both split
  halves that kept growing afterwards.
- The persisted list is strictly distinct ⇒ the twins are **two distinct node instances** with
  identical content (one shared instance referenced twice would have re-used its stamped sequence
  and produced a duplicate entry in the list). ⇒ a leaf split (possibly with a cascading parent
  split, on the path-copying write path) left a stale CLONE of the left half reachable in the spine;
  descent with equal separators routes right, so the clone never receives inserts and freezes.
- The insert stream was NEAR-monotonic `OffsetDateTime`s with jitter (production-catalog leaf sizes
  127/128/186/190/235 prove out-of-order inserts → middle-leaf splits/borrows, not pure appends).
- Secondary suspect ingredient if plain churn doesn't reproduce: the warm-up **store compaction**
  (`WarmUpDataStoreMemoryBuffer.setPersistenceService`, "internal store compaction… unique for the
  warm-up phase").

## Writer-reproduction findings (2026-07-14, round 2)

- **Vintage ruled out:** no commit since 2026-07-12 touched `TransactionalBucketBPlusTree` /
  `PageStreamRegistry` / `InvertedIndex` / `FilterIndex` — the reindex build ran tree code identical
  to dev HEAD. The bug is in current code.
- **Savepoints architecturally cannot reach disk:** trunk state is rebuilt by WAL replay
  (`TransactionManager.replayMutationsOnCatalog`, atomicRollback=false) and the WAL only contains
  savepoint-committed entities (`LocalMutationExecutorCollector.commit`). (twin-writer-hunter agent.)
- **Deterministic single-writer churn does NOT reproduce** — 8-variant stress harness all green
  (`StaleLeafPageTwinWriterReproductionTest`, evita_functional_tests): monotonic/jittered churn,
  savepoint skip-on-fail, deletes/merges, split-adjacent failure positioning, transactionally-born
  reduced index, shrink churn, async burst commits, 4-thread parallel *sessions*. Split/page-carry
  code reads sound single-threaded (see agent verdicts; note stale comment at
  `TransactionalBucketBPlusTree:3923-3926` contradicted by :1822/:1838).
- **TOP HYPOTHESIS now: concurrent upserts on ONE warm-up session.** `EvitaSession` is
  `@NotThreadSafe` by contract only; `EvitaSessionProxy` has NO lock in the invocation path. A
  client pipelining/parallelizing upserts over a single WARM_UP session makes two request threads
  race the path-copying bucket tree — a racing leaf split can leave a stale clone of a half attached
  in the spine = the exact twin anatomy, at a rare-interleaving rate (~1 event / 33K upserts on
  the production catalog). **Open question for the client team: does `EvitaIncrementalIndexJob`'s full reindex issue
  upserts strictly sequentially (blocking, one at a time) or with any pipelining/async batching over
  the single session?**

## Writer-reproduction round 3 — the duplicate-in-flight-upsert lead (2026-07-14)

The user confirmed the reindex client is strictly sequential blocking gRPC on ONE warm-up session
(and warm-up enforces a single simultaneous session) — ruling out plain client concurrency. Two
mechanisms can still produce TWO CONCURRENT WRITERS server-side on that one session:

1. **Driver/application retries of a timed-out upsert.** `EvitaClient` (driver) wraps the channel in
   Armeria `RetryingClient` when `EvitaClientConfiguration.retry=true` (default false):
   `onTimeoutException().thenBackoff()` + retries on `UNKNOWN`/`SERVICE_UNAVAILABLE`/`GATEWAY_TIMEOUT`
   (`EvitaClient.java:478-492`). A client-side response timeout does NOT stop the server-side
   execution — the retry runs concurrently with the original ⇒ two request threads mutate the same
   path-copying bucket tree; a racing split leaving a stale clone in the spine = the twin. The same
   applies to APPLICATION-level retry of a timed-out `upsertEntity` (the job's own catch-and-retry).
   `EvitaSession` is `@NotThreadSafe` by contract only; `EvitaSessionProxy` has NO lock — the server
   never guards against this. OPEN QUESTION for the client team: does the reindex job set
   `retry(true)` on its `EvitaClientConfiguration`, and/or does it re-try timed-out upserts itself?
2. **Engine-internal background work racing the session thread during warm-up** — mid-session store
   compaction (`WarmUpDataStoreMemoryBuffer.setPersistenceService`) / any mid-session flush; under
   investigation (threading + forced-compaction stress) by the twin-writer-hunter agent.

## Loki-verified reindex pipeline (2026-07-14; the client test namespace)

- **NO compaction** log line in the `evita` container during the whole reindex window (10:00–13:30Z)
  — the mid-warm-up-compaction hypothesis is dead (matches the near-zero-waste argument).
- The full reindex builds a TEMP catalog `<catalog>_1783937623260` (epoch = 10:13:43Z ≈ start), bulk-loads
  it in WARM_UP, the catalog goes **ALIVE at 13:07:15Z** (`CatalogInstalledIntoLiveView`,
  currentEngineVersion=7) — and the SAME reindex job keeps publishing **transactionally** until
  ~13:11:43Z (~90 commits ⇒ catalogVersion=97 explained), then the temp catalog REPLACES the live catalog;
  backup at 13:14:35Z. `<catalog>-evitaIncrementalIndexJob` was queued (planned 13:12:11Z) but did NOT
  run before the backup.
- ⇒ Three one-shot writer windows for the twin, all engine-side: (1) WARM_UP in-memory churn
  (split-instant leaf cloning) — still the best fit for the frozen content (state as of ~12:00:47,
  never a transactional boundary); (2) the **goLive WARM_UP→transactional conversion** capturing a
  stale node reference (one-shot structural pass over all indexes — matches "rare, once per
  reindex, several indexes affected"); (3) the transactional tail's commit-merge identity edge
  (`BPlusInternalTreeNode.createCopyWithMergedTransactionalMemory` memoized state-copy). The catalog
  REPLACE flush choreography is a fourth, less likely, window.

## Writer-reproduction — open task

Reproduce from a clean embedded catalog: ONE warm-up session, thousands of near-monotonic-with-jitter
`OffsetDateTime` upserts (fixed seed), `goLiveAndClose`, reopen; oracle = bucket iteration strictly
ascending + page-sequence uniqueness + every value resolves (see the fixture test's
`firstOrderingViolation` helper) — and, ideally, the same scan on the in-memory tree BEFORE goLive
(the corruption exists in memory pre-flush). Code focus: `TransactionalBucketBPlusTree`
non-transactional insert — leaf split + parent split + path-copy child-array replacement + any
leaf-cloning site. A `bug-hunter-tdd` agent is on this (reproduction only, no fixes).

## Writer-side synthetic reproduction attempts (negative results)

All in `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/functional/storage/StaleLeafPageTwinWriterReproductionTest.java`
(tags INDEXING/ATTRIBUTE/FILTER; run: `rtk mvn -pl evita_test/evita_functional_tests test -o
-Dtest=StaleLeafPageTwinWriterReproductionTest -Dtest.tag.policy=off`). Oracle used everywhere:
merge-walk of `getValueIterator()` against an exact pk→value model (fatal: cross-bucket ordering
violation, duplicate live page sequence via `leafPageHandles()` reflection, phantom record of a
non-failed upsert, silently lost successful upsert), run on the IN-MEMORY tree (incl. right before
goLive), after the flush, after cold reload, plus a full `attributeEquals` sweep; checked on GLOBAL
and REDUCED indexes.

**GREEN — do NOT retry these recipes as-is (current dev code, 2026-07-14):**

1. **Single-threaded WARM_UP churn (the checksum-era primary)** — ONE warm-up session, 7 000 ops ×
   3 seeds: near-monotonic `OffsetDateTime` 50 ms grid ± 2 000 ms jitter (middle-leaf splits,
   value collisions ⇒ multi-record buckets), 15 % re-publishes (remove+insert), ~7 % deletions
   (borrows/merges), 60 % category membership (reduced index gets a filtered subsequence);
   in-memory scan every 1 000 ops + before goLive. Resulting tree ≈ 40+ leaves — production-comparable
   leaf count (43). NO twin, no ordering violation, at any scan point.
2. **Transactional churn era (pre-checksum, all superseded but exonerating):** plain monotonic;
   skip-on-fail savepoints (mandatory-attr violation fires in
   `ContainerizedLocalMutationExecutor.popImplicitMutations:1328`, i.e. AFTER the index writes —
   the savepoint rollback does revert a mid-savepoint split); failures+deletes+20 % out-of-order;
   deterministic split-adjacent failure positioning (fail at split−1/split/split+1/last-op before
   commit, probed via rightmost-leaf fill); reduced index born+grown entirely inside transactions
   (incl. creation inside a rolled-back savepoint); shrinking churn (drained left leaves +
   failing re-publishes from the drained region); async burst commits (6 WAL txs in flight ⇒ trunk
   batching); 4 parallel *sessions*. All green at 30–100 commits × 60–80 ops and at 3–4× soak.

**FAILING (kept in the harness — positive findings, though NOT the production writer after the client
was confirmed strictly sequential):**

3. `shouldSurviveConcurrentUpsertsOnSingleWarmUpSession` — 8 threads × 1 500 upserts on ONE shared
   warm-up session: **silently lost successful upsert** in the GLOBAL `published` tree (in-memory,
   pre-goLive), plus a loud cascade (~11.9 k exceptions) once corruption seeds.
4. `shouldSurviveSplitAimedOverlappingUpsertsOnSingleWarmUpSession` — full-speed writer + a sniper
   that fires one racing insert only when the rightmost leaf fill ≥ 254: fails identically, and its
   first sampled exception is **`Cannot insert into a full leaf node, split the node first!`** —
   two threads inside `splitLeafNode`/`adaptToLeafSplit` on the same full leaf, i.e. the split
   window IS raceable and is exactly the event family that can mint the twin. Race-exception
   census: NPE `PersistentRoaringBitmap.contains:2050` via `EntityIndex.insertPrimaryKeyIfMissing`
   (`EntityIndex.java:390`); "value already present in a unique long-payload bucket tree";
   "Record with id X is not present in the array…" / "Position N not found!" (SortIndex chain);
   AIOOBE `SortIndex.getSortedRecordValues:606` (flush-time). NOTE: these two tests fail BY DESIGN;
   before any CI merge they need `@Disabled` or a policy decision.

**NEW ENGINE BUG found en route (bounded in the harness by `@Timeout(SEPARATE_THREAD)` on
tearDown):** when the close-time warm-up flush throws (e.g. the SortIndex AIOOBE above, reached via
`popTrappedUpdates` ← `EntityCollection.createFlushFuture:1621`), the session close future NEVER
completes — not even exceptionally — so `SessionRegistry.closeAllActiveSessionsAndSuspend`
(`SessionRegistry.java:213-226`, `allOf().join()`; the `.exceptionally(ex -> null)` guard cannot
help a never-completing future) and `Evita.closeInternal` (`Evita.java:1846-1854`) hang forever
(observed 11+ min, jstack-verified twice). Same "missed completion path" family as bug-03.

**Code-reading verdicts (single-threaded soundness — don't re-audit blindly), all in
`TransactionalBucketBPlusTree.java` unless noted:** `splitLeafNode` 1802–1865 (fresh halves,
original's arrays deliberately preserved, layer removal 1841–1843); `adaptToLeafSplit` 2677–2724
(loud identity + key-absence asserts — no silent double-insert); `splitInternalNode` 1900–1956 with
the internal range ctor 2282–2303 copying into FRESH arrays (no aliasing); `consolidate` 1625–1731
(merge/steal parent bookkeeping consistent); page-sequence carry leaf:3941 / internal:2919;
`PageStreamRegistry.collectChangedPages` 325–363 (invariant-sound). Savepoint capture is complete
(`TransactionalLayerMaintainer` 110–139/149–173/207–217). Gotchas recorded: STALE comment at
:3923–3926 claims split-born nodes are `transactionalLayer=false` — contradicted by :1822/:1838
(true); `EvitaSessionProxy.java:93` `insideInvocation` is a counting-only AtomicInteger
(inc :392 / dec :411) — nothing serializes concurrent session calls; a root-level session
exception marks the whole tx rollback-only (`EvitaSession.executeInTransactionIfPossible:2321-2350`)
— skip-on-fail only works nested inside `updateCatalog`.

**Handoff to the full-reindex simulation:** the twin needs BOTH leaves live-reachable in the final
in-memory tree (all 43 seqs allocated in the one goLive walk), so the spine duplication happened in
memory at ~12:00:47 — either a second concurrent execution on the session (driver/app retry of a
timed-out upsert — my failing tests prove one racing insert in the split window suffices to corrupt)
or a production-scale/mutation-shape ingredient my 7 k-op synthetic stream lacks (real entity
complexity, reflected refs, richer local-mutation mix). Recommended traps for the simulation: the
in-memory pre-goLive oracle above, plus a temporary assert in `adaptToLeafSplit` verifying the
parent's children keys stay strictly ascending after the replacement.

## Writer-identity evidence round 2 (2026-07-14 afternoon)

**1. The corruption is a FOUR-twin cluster in one ~50 s window.** The TwinDetector positive control
(warmup-sim agent) found not one but FOUR corrupted `published` reduced indexes in the pristine
backup, all with literal cross-page twin signatures, freeze instants tightly clustered:
- `categories:142816` — leaf[29]/leaf[30], freeze 12:00:47.504 Z
- `categories:142817` — leaf[20]/leaf[21], freeze 12:00:43.718 Z
- `brand:419051` — leaf[27]/leaf[28], freeze 12:01:32.171 Z
- `groups:754312` — leaf[67]/leaf[68], freeze 12:01:33.490 Z
Pairwise clustering (two at ~12:00:44-47, two at ~12:01:32-33) suggests TWO corruption micro-events
~46 s apart, each damaging the shared reduced indexes of the entity/entities being upserted
(`published` ≈ upsert wall-clock since the job stamps `nowWithOffset()` per entity).

**2. The eshop client is EXONERATED** (full report:
`investigations/lib-eshop-evita-client-concurrency-REPORT.md`): FailoverEvitaClient is a pure
delegate (no retry/failover re-issue), driver retry=false (Armeria RetryingClient not installed),
strict single-thread sequential loop, no session leak, no overlapping turns (darwin lease), no other
writer on the temp catalog. The one theoretical path (swallowed >300 s upsert timeout → next call on
still-open session) logs at ERROR and the window is ERROR-silent.

**3. The Armeria access log PROVES no wire-level overlap at the corruption instants.** Per-request
completion timestamps (ms) + durations for container=evita, 12:00:41-51 Z: every consecutive
UpsertEntity starts 5-10 ms AFTER the previous completes. Sequential also at 12:01:42-43. So no two
gRPC calls executed concurrently — any concurrency must be server-INTERNAL (something touching index
trees off the request thread during warm-up), or the corruption is single-threaded after all.

**4. Mid-warm-up compaction is REFUTED for the incident** (it would log
`Compacting catalog ... entity collection ...` at INFO — zero such lines in the whole reindex
window; also confirmed earlier for the catalog-level compaction).

**5. NEW LEAD — the freeze instants coincide with same-size upsert QUADS.** Right at each freeze the
access log shows a burst of ~4 identical-response-size upserts (4× 1294 B spanning 12:00:43.888-44.083
around the 43.718 freeze; 4× 1222 B at 12:00:47.502-580 around the 47.504 freeze) — i.e. the same
entity (or 4 near-identical variant siblings) re-published several times within tens of ms, each
re-publish = remove+insert of a fresh `published` value across ALL that entity's reduced indexes.
Per the user these are most plausibly **master+variant sibling quads with adjacent PKs** (created
together, near-identical data ⇒ identical serialized sizes), NOT same-entity re-publishes. Siblings
share the same category/brand/group references, so each quad = 4 rapid sequential inserts into the
SAME reduced indexes with adjacent `published` values. NOTE: a PK-ordered full copy reproduces this
burst structure naturally (siblings are PK-adjacent) — the plain simulation is therefore a faithful
replica of the suspected trigger shape. **Remaining audit: any warm-up index work dispatched off the
request thread server-side.**

## Determinism verdict — full-scale WARM_UP replay simulation (2026-07-14, warmup-sim agent)

**Checker** (`TwinDetector.java`, `evita_test/evita_performance_tests/.../spike/`): walks every
`FilterIndex` of every `EntityIndex` (global + every reduced index) via reflection. Two signals per
index: cross-bucket ordering violation using the index's OWN comparator (not natural order — the production
catalog has Czech-collated string attributes where collation order != natural order; first attempt with
natural order threw 206k false positives, fixed by switching to `InvertedIndex.getComparator()`),
and leaf-page-level signals (duplicate live page sequence + literal cross-page boundary overlap).

**Positive control** (read-only copy of `data_snapshot_pristine`): PASSED cleanly — exactly 8
findings (the four twins from round 2 above, ×2 signals each), zero noise elsewhere in the catalog.
Validates the checker before trusting its negative result below.

**Full run** (`WarmupTwinSimulation.java`, same dir): embedded, no gRPC — two in-process Evita
instances (source read-only, target WARM_UP), schema replicated faithfully, single WARM_UP session,
entities upserted in STRICT ascending-PK order per collection, collections processed alphabetically.
**380,016 entities across all 19 collections** (113,136 Product — the collection where the real
corruption lives), copy loop 1390.6 s, `goLiveAndClose` 31.2 s. **All 4 checkpoints CLEAN (0
findings):** mid-load (after Media, 204,425/380,016 entities), pre-goLive (decisive, right before
the one-and-only flush), post-goLive in-memory, post-reload cold (forces the real
`InvertedIndex.fromPersistedPages` load path). `COPY_VERIFIED_OK` — exact per-collection count match
against source.

**Verdict: deterministic single-threaded full-reindex replay — exact real production dataset, exact real
procedure (one WARM_UP session, PK order, goLiveAndClose) — does NOT reproduce the stale-leaf-page
twin, even at true production scale.** No rerun performed: the pipeline has no randomization and no
concurrency (entity content, PK ordering, batch order, and schema-replication map iteration are all
deterministic and fixed by the source data), so a second run would reproduce the identical result.
This closes out the "plain deterministic full reindex" hypothesis at both functional scale (the
earlier 7-12k synthetic harness) and production scale (this 380k run) — consistent with, and
reinforcing, the writer-side reproduction result (`StaleLeafPageTwinWriterReproductionTest`:
single-threaded churn is clean, ONE racing insert on a shared WARM_UP session during a leaf split
suffices to corrupt). **Strengthens the remaining open hypotheses:** (1) concurrent upserts via
client retry/driver pipelining racing the same WARM_UP session — though see eshop exoneration above;
(2) the goLive WARM_UP→transactional conversion pass; (3) the transactional-tail commit-merge; (4)
the catalog-replace flush choreography. Remaining open audit item unchanged: any warm-up index work
server-side dispatched off the request thread.

**Two anomalies found along the way (not corruption, minor tickets):**
- An `Evita` instance whose constructor throws mid-load (WAL-format mismatch, schema-version
  mismatch) leaves already-started non-daemon background threads alive — the JVM never exits on its
  own and the storage `.lock` file is never released (had to `kill -9` twice). Any catalog-load
  failure path should not leave the process un-exitable or the lock held.
- `storage.compress` must be explicitly set to match the source data's actual on-disk format
  (the production catalog was `compress=true`); a plain default `EvitaConfiguration.builder()` silently produces
  "Record is compressed and ObservableInput has compression support disabled" — not obvious from the
  error alone.

Artifacts (under `/tmp/.../scratchpad/warmup-sim/`, not committed): `TwinDetector.java`,
`WarmupTwinSimulation.java` (both now committed under
`evita_test/evita_performance_tests/.../spike/`), `control-stdout.log` (positive control),
`smoke2-stdout.log` (pipeline validation), `full1-stdout.log` (the determinism verdict itself),
`source-storage` (untouched checksummed-identical copy of `data_snapshot_pristine`), `full1-storage`
(freshly built target catalog, clean, no twin).

## Fix acceptance

1. `StaleLeafPageTwinReproductionTest` passes (all three methods).
2. From-pristine production catalog: `ProductionCatalogUpsertFuzzer` seed=1 no longer surfaces `Key is already present` /
   `Sanity check - record not found` on entities whose indexes carry twins — note the EXISTING production-catalog
   dataset stays corrupted on disk; acceptance there means the load path detects (or heals) the twin
   instead of silently serving corrupt trees.
3. Once the writer event is reproduced: the churn harness stays green across commits and reopens.
