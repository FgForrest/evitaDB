# Results — fail-fast on every stale-leaf-page twin (CHANGE_PROPOSAL Phase 1)

Implementation of `CHANGE_PROPOSAL.md`, which supersedes the *healing* approach of `ASSIGNMENT.md`.
Status as of 2026-07-14.

## Owner decision (recap)

The paged B+ tree persistence layout has never shipped in a released evitaDB version, so no production
catalog can carry a stale leaf-page twin — the only damaged artifacts are local dev datasets. Healing
therefore has no field-remediation value, and silently repairing a state a now-guarded writer bug produced
contradicts the defensive-design rule. So: **healing is removed entirely; every detected twin (including
the previously-healed strict-prefix-before shape) fails fast with `GenericEvitaInternalError`** carrying
full diagnostics and an operator remediation hint.

## Phase 1 — implemented and green

### 1.1 Validation relocated into the three spine assemblers (single choke point)

Each `assembleFromSingleLeafTrees` gained a `@Nonnull String structureDescription` parameter and now
validates strict cross-leaf key order **before** building the spine (validating the in-hand leaf list, so
the corruption diagnostic fires ahead of any left-boundary separator invariant the builder would otherwise
trip on):

- `TransactionalLongBPlusTree` — natural `long` order.
- `TransactionalElementBPlusTree` — element order via the tree's key extractor.
- `TransactionalBucketBPlusTree` — the tree's comparator (or natural order when none is set).

The shared per-tree core is `assertCrossLeafBoundaries(List<leaf>, structureDescription)`: walk the leaves
in list order; empty leaves (`getPeek() < 0`) carry no key and impose no boundary; the last key of each
key-bearing leaf must sort **strictly** before the first key of the next; on violation throw
`GenericEvitaInternalError` naming the structure, both page sequences, both boundary keys and the hint
"Restore the catalog from a backup, or fully rebuild / reindex the affected catalog." No WARN path, no drop
path, **no payload comparison** — key overlap alone is disqualifying (so the `PriceRecord.equals`-identity
trap the healer had to work around is now moot). This one choke point covers all six structures,
`InvertedIndex` and every future caller.

### 1.2 Healing machinery deleted

`LeafPageTwinHealer` (class + `PageProbe` interface) is gone, along with every per-structure `PageProbe`
implementation and the call-site kept-indices / effective-array filtering. The six restore paths
(`InvertedIndex`, `RangeIndex`, `ReferenceTypeCardinalityIndex`, `PriceListAndCurrencyPriceSuperIndex`,
`OwnerUniqueIndex`, `GlobalUniqueIndex`) revert to plain assembly, passing their raw per-page arrays plus a
description string straight into the assembler. The `String indexDescription` parameter + loader threading
added by the previous implementation (`AttributeIndexLoader`, `HistogramIndexMapLoader`,
`ReferenceTypeCardinalityLoader`) are KEPT — they now feed the assembler diagnostics.
`rg -l "PageProbe|TwinHealer" evita_engine/src/main` returns nothing (criterion 1.6.2).

### 1.3 ChainIndex — duplicate record id is unconditionally fatal

`healChainLeafPageTwins` → `assertNoDuplicateChainRecords` (returns `void`): keeps the O(N) `IntHashSet`
fast-path scan; the strict-prefix drop pass, the head-mark-divergence tolerance and the WARN are deleted.
On any duplicate it runs the `IntIntHashMap firstPageOfRecord` attribution pass and throws
`GenericEvitaInternalError` for the first duplicate (record id + both page sequences + attribute identity +
remediation hint). `@Slf4j` was removed (its only use was the deleted WARN).

### 1.4 Pre-disk flush validation — DEFERRED (see rationale below)

### 1.5 Tests

- Every heal-asserting test across the seven twin test classes flipped to assert the throw, and — to guard
  against a premise-error false-pass (`Assert.isPremiseValid` also throws `GenericEvitaInternalError`) —
  each asserts the thrown message contains the distinctive cross-leaf diagnostic ("overlaps its successor
  leaf-page sequence" for the key-ordered structures, "appears in more than one leaf page" for ChainIndex).
- `StaleLeafPageTwinReproductionTest`: `LoadInvariantTest` tightened to a strict throw; `SortValueTreeTest`
  (a heal-era survival check now subsumed by the load-time throw) deleted; `FilterRemovalTest` →
  `InterleavedTwinTest` asserting the interleaved (non-monotonic cross-page) shape throws at load; the two
  boundary pins (`shouldRefuseEqualContentTwin`, `shouldRefuseTwinAfterSuperseder`) kept.
- ChainIndex's two former heal cases (strict-prefix, head-divergent) both flipped to assert the throw.
- **New assembler-level unit tests** added to all three tree test classes: feed two overlapping single-leaf
  trees directly to `assembleFromSingleLeafTrees` and assert the throw (the Long one uses a 5-slot leaf so
  the 3-key pages don't split into the "exactly one leaf" premise error — the cross-leaf diagnostic is what
  is pinned).
- `StaleLeafPageTwinWriterReproductionTest`: **left unchanged** — its tests assert the (fixed) write path
  produces a *sound* PAGED index, not a synthesized twin; empirically it stays 4/0 green under fail-fast
  (a sound index reloads without throwing), so no flip was warranted.

## Verification

- **Targeted twin + tree suites** (`-Dgroups="indexing"`, the 7 twin classes + all 3 full tree test
  classes): **240 tests, 0 failures**. Every twin shape now fails fast; the full tree suites confirm no
  regression in healthy assembly.
- **Writer-side reproduction** (`StaleLeafPageTwinWriterReproductionTest`): **4/0** under the new code.
- **Acceptance / false-positive gate** `-Dgroups="indexing | filter | order"`: **6993 run, 0 failures, 0
  errors, 1 skipped — BUILD SUCCESS**. This exercises every *healthy* paged reload end-to-end; a
  getPeek/comparator/empty-leaf bug in the new strict check would surface here, and none did.
- `evita_engine` main compiles clean; `evita_functional_tests` test-compiles clean.

## Phase 1 acceptance criteria

1. ✓ All flipped/new tests green; no test asserts a heal.
2. ✓ `LeafPageTwinHealer` and all `PageProbe` implementations gone (`rg` clean).
3. ✓ Acceptance suite fully green (6993/0/1-skip vs the 6991 baseline).
4. ✓ No production code path silently drops or rewrites a persisted page — every overlap/duplicate throws.

## 1.4 status — SUPERSEDED by the Phase 2 Tier A boundary-mutation extension

**Update (2026-07-15): item 1.4 is now formally SUPERSEDED, not merely deferred.** The Phase 2 Tier A
op-time asserts (see `## Phase 2 — Tier A + Phase 3` below and `CHANGE_PROPOSAL.md` §Tier A + `TIER_A_ADVISORY.md`)
close 1.4's goal from the other direction: every shape-changing op through a tree is now asserted at op
time (tail-fence check on last-key raise, predecessor check on first-key lower, separator belt, split
assert), so by induction a tree that only ever mutated through asserted ops cannot emit an overlapping page
list at flush — the exact guarantee 1.4 sought, without touching the 6+ scattered per-index flush-emit
sites. Do NOT implement a separate flush-path `assertCrossLeafOrder`. The original four-reason deferral
rationale is retained below for the historical record.

1.4 asks for an earlier, defense-in-depth line: validate the in-memory tree's cross-leaf order at flush,
before its pages reach disk. It was originally **deferred**, for four concrete reasons:

1. **Its acceptance goal is already met by 1.1.** Criterion #4 ("no path silently drops or rewrites a
   persisted page") holds because load-time validation means a corrupt page list can never be *read* back
   into a live tree. 1.4 only narrows the window from read-time to write-time.
2. **No single clean hook exists.** The flush emit path is per-index: each paged index has its own
   `collectChangedPages()` calling `PageStreamRegistry.collectChangedPages(...)` with its own `PageBuilder`;
   the registry has no tree/comparator to validate with, and `AbstractTransactionalBPlusTree` exposes only
   `leafPageHandles()` (read), no shared flush-emit method. Wiring would mean touching 6+ scattered
   per-index emit sites — exactly the tangled wiring the proposal grants latitude to avoid.
3. **It guards an already-guarded bug.** The only way a corrupt tree forms in memory is the warm-up writer
   race, which is itself already guarded by the (separate, in-flight) concurrent-session-access work.
4. **An exposed-but-unwired `assertCrossLeafOrder` would be dead code** (forbidden by the project rules),
   and its only untested delta over 1.1 is leaf enumeration — the validation *throw path* is already
   covered by the three new assembler-level unit tests that drive the shared `assertCrossLeafBoundaries`
   core directly.

If a future change makes 1.4 worthwhile, the clean implementation is a `assertCrossLeafOrder(String)` on
each tree (enumerate leaves → reuse `assertCrossLeafBoundaries`) wired into each index's `collectChangedPages`
before the root part is staged — but only alongside a real caller, never as an exposed-but-unused method.

## Phase 2 — Tier A + Phase 3 (implemented and green; Tier B/C pending)

Implemented per `CHANGE_PROPOSAL.md` (Phase 2/3, revised 2026-07-15) and `TIER_A_ADVISORY.md`.

### Tier A — op-time boundary-mutation asserts (DONE, all three trees, green)

Mirrored across `TransactionalLongBPlusTree` (reference), `TransactionalElementBPlusTree` and
`TransactionalBucketBPlusTree`. Each carries, adapted to its key type (natural `long`; extracted `int` via
the key extractor; comparator-`K` via a shared `compareKeys` helper — no raw `>=` on objects):

- **Check T (tail)** — on a mutation that RAISES a leaf's last key, walk the cursor up to the nearest level
  whose descended child index `< peek` and assert the new last key sorts strictly below that ancestor
  separator (the successor leaf's first key, even cross-parent); a rightmost descent at every level = tree's
  last leaf, nothing to check. Zero-alloc, no cross-parent leaf navigation.
- **Check H (head)** — on a mutation that LOWERS a leaf's first key, assert the predecessor leaf's last key
  sorts strictly before it. `ci>0`: the same-parent left sibling (O(1)); `ci==0`: walk to the clamp ancestor
  and descend its left-neighbour subtree's right spine (O(height), off the happy path). An empty predecessor
  is skipped.
- **Check S (separator belt)** — after `updateKeyForNode` rewrites a separator, assert strict local order
  against its neighbours (catches stale/aliased internal-node state — the historical twin signature).
- **Split assert** — atop `splitLeafNode`, the left half's last key must sort before the right half's first.
- Removals get no order check (proven safe a-fortiori); interior inserts get none. Checks live on the public
  key-adding path (`insert`/`upsert`; Bucket's new-bucket branch of `addRecord`/`addLongRecord`), which the
  bypass audit confirmed is the sole live key-adder (load-path `assembleFrom*` is Phase 1/3.1-covered).
  **Bucket in-place audit: CLEAN** — adding a record to an existing bucket touches only the record-set, never
  the ordering key; no key-mutation-in-place path exists.

Item **1.4 is thereby closed by induction** (see the 1.4-supersession section above).

### Phase 3.1 — intra-leaf order at load (DONE, all three trees)

The load-time `assertCrossLeafBoundaries` walk now also asserts each leaf's interior keys strictly increase
(one comparison per key, once per load) — closing the gap where a serializer bug / truncated write / bit rot
leaves an intra-leaf-disordered page that the cross-leaf walk alone would pass.

### Phase 3.2 — DONE

Renamed the nested `HealedShapeBoundaryTest` → `BoundaryTwinShapeTest` (accurate to its content — the two
boundary-twin pins; the strict-prefix shape lives in `LoadInvariantTest`).

### Verification (2026-07-15)

- Three tree test classes: **239/0/0** (each gained a `TierABoundaryMutationTest` with 5 tests — cross-parent
  tail fence, same-parent + cross-parent head, single-key 0→1, happy-path pin — plus an intra-leaf-order
  test; each tree's existing randomized churn suite stays green = no false-positives).
- Acceptance `-Dgroups="indexing | filter | order"`: **7011/0/0/1-skip — BUILD SUCCESS** (baseline 6993 +
  the new Tier A/intra-leaf tests). The live Check T/H/S + split asserts now fire on every real insert across
  the whole attribute/price/filter/range index surface with zero false-positives.
- Engine compiles + installs clean.

### Tier B / Tier C — DONE (all three trees) + production-path coverage proven

Implemented per `TIER_BC_ADVISORY.md` (which refines the CHANGE_PROPOSAL Tier B/C sections). One shared,
type-agnostic dirty-leaf **registry** on `TransactionalLayerMaintainer` (`Map<DirtyLeafScopeValidator,
IdentityHashSet<Object>>`, lazily built, populated UNCONDITIONALLY at each tree's boundary-changing mutation
seams — insert / delete / split-halves / rebalance) feeds both tiers; registered objects are KEY SOURCES
ONLY (validation relocates by key via a root descent and validates the covering leaf, making it savepoint-
and merge-away-proof). Each tree implements `DirtyLeafScopeValidator.validateDirtyLeafScope(...)` mirroring
Tier A's per-key-type boundary machinery.

- **Tier B** — pre-WAL, kill-switchable (`evita.bPlusTree.preCommitValidation`, default on; a
  `static final` read at class load). First thing in `TransactionWalFinalizer.commit(...)`:
  `maintainer.validatePreCommitDirtyLeafScopes()` re-derives each registered tree's cross-leaf invariants
  against the still-live transactional view. On violation it MIRRORS `rollback(...)` (close+delete the
  isolated WAL, `completeExceptionally`, return normally — a raw throw would hang the commit future and leak
  the isolated WAL) so the shared WAL never receives the transaction. Not run for the trunk-replay
  transaction (Tier C owns post-WAL).
- **Tier C** — always on, post-build, at the end of each tree's `createCopyWithMergedTransactionalMemory`:
  relocate each registered leaf in the freshly MERGED (plain, layer-free) tree and validate both boundaries.
  The tree-level message is NEUTRAL; the poison-pill caveat (durable in WAL **and possibly in already-flushed
  data files**) is added by the `TransactionTrunkFinalizer` wrapper catching `BPlusTreeCorruptedException`,
  because the same merge code also fires on the isolated-finalizer path where WAL wording would be false.

`BPlusTreeCorruptedException extends GenericEvitaInternalError` is the marker the trunk wrapper catches.

**Unit verification** — three tree test classes **262/0/0** (Long 127 incl. 8 new, Element 32 incl. 7 new,
Bucket 103 incl. 8 new `DirtyLeafScopeValidationTest`): per tree — sound-scope no-throw, tail/head overlap
detected, empty-key-source skipped, registration-identity, Tier B pre-commit REJECT, Tier C commit-merge
REJECT (runs the real `createCopyWithMergedTransactionalMemory`), and savepoint-rollback no-false-positive.
The reject tests prove the pipeline is LIVE, not a silent no-op.

**Production-path coverage — PROVEN (task #32, 2026-07-15).** The unit tests exercise the raw trees; the
concern was whether a real Evita transactional upsert actually drives the Element (price super index) and
Bucket (unique/filter index) trees through Tier B/C — the acceptance suite runs under `indexing|filter|order`
tags, but every transactional-upsert test is tagged `transaction`, so the acceptance number alone was a
potentially vacuous gate for these two trees. Settled empirically by JDWP against a single real write-session
upsert of a priced + globally-unique + filterable product into a LIVE catalog
(`EvitaTransactionalFunctionalTest#shouldUpdateCatalogWithAnotherProduct`). All four cells confirmed, each
validating a non-empty registered scope:

| Tree    | Backing index                        | Tier B (pre-WAL, `ForkJoinPool` commit thread)     | Tier C (trunk-merge, `Evita-transaction-*` thread)          |
|---------|--------------------------------------|----------------------------------------------------|-------------------------------------------------------------|
| Element | `PriceListAndCurrencyPriceSuperIndex`| ✓ `TransactionWalFinalizer.commit` → `validatePreCommitDirtyLeafScopes` | ✓ `PriceSuperIndex → GlobalEntityIndex` merge → `createCopyWithMergedTransactionalMemory` |
| Bucket  | `OwnerUniqueIndex` + `FilterIndex`   | ✓ same pre-WAL pass                                | ✓ `OwnerUniqueIndex → AttributeIndex → GlobalEntityIndex` merge |

**Positive gate (clean, non-debug, Tier B/C enabled):** `EvitaTransactionalFunctionalTest` — **25/0/0,
13.55 s, BUILD SUCCESS** — 25 real transactional scenarios (upserts, granular conflicts, delta mutations,
30-thread parallel inserts, WAL replay) with zero false positives (no `BPlusTreeCorruptedException` / poison
pill). Together the JDWP matrix (the trees ARE driven through both tiers) and this green gate (with the tiers
active) establish the coverage the `indexing|filter|order` number could not.

Acceptance `-Dgroups="indexing|filter|order"` with Tier B/C present: **7034/0/0/1-skip — BUILD SUCCESS**
(no regression; the +delta over the Tier A 7011 number is the new tree unit tests, now indexing-tagged).

### Remaining Tier B/C tests (task #32) — DONE

Of the six test additions in `TIER_BC_ADVISORY.md`: detect/skip/reject/savepoint (1, 2, partial 6) are the
unit tests above; the false-positive gate (6) is the acceptance + transaction-suite numbers above; the
production-path coverage proof is the JDWP matrix + clean gate above. The remaining three landed this session
(no production corruption-injection hook was added):

- **Test 5 (registry hygiene)** — two tree-unit tests per tree (`shouldRegisterLeafOnRemoveOnlyTransaction`,
  `shouldRegisterBothHalvesOnLeafSplit`): a remove-only transaction still registers its dirtied leaf, and a
  leaf split registers both halves (asserted via `getRegisteredDirtyLeaves`; `size() >= 2` is self-checking —
  an identity set collapses a double-registration to 1 and fails). Added to all three trees'
  `DirtyLeafScopeValidationTest`; the nested class is now 10 tests on Long, mirrored on Element/Bucket.
- **Test 4 (poison-pill flushed-data wording)** — the inline trunk wrap in `TransactionTrunkFinalizer.commit`
  `CatalogChanges` was extracted to `public static wrapPostReplayBoundaryCorruption(BPlusTreeCorruptedException)`
  (a refactor, not a hook — the catch now calls it) so its operator-facing wording is testable without
  corrupting a real merge. `TransactionTrunkFinalizerTest` pins that the message names the write-ahead-log
  durability, the flushed-data-files caveat and restore/rebuild remediation, and chains the neutral cause.
- **Test 3 (kill-switch independence)** — the kill switch is a `static final` resolved at class load, so it is
  exercised in a dedicated property-set JVM fork rather than a runtime toggle. `TierBKillSwitchTest`
  (tree-agnostic, Long vehicle) is `@EnabledIfSystemProperty(...="(?i)false")` — DISABLED on normal runs (the
  switch-on Tier B throw is already pinned by `shouldRejectCorruptedScopeInPreCommitPass`) and RUN only under
  the fork. It reuses that reject test's exact corruption so the switch is the sole changed variable, and
  asserts `validatePreCommitDirtyLeafScopes()` early-returns (Tier B gated off) while the commit merge still
  throws (Tier C always on). Dedicated invocation:
  `rtk mvn -pl evita_test/evita_functional_tests -o test -Dtest=TierBKillSwitchTest -Devita.bPlusTree.preCommitValidation=false -Dtest.tag.policy=off`.

**Verification (2026-07-15).** Normal pass over the three tree classes + `TransactionTrunkFinalizerTest` +
`TierBKillSwitchTest`: **270 run, 0 failures, 0 errors, 1 skipped** (the skip is `TierBKillSwitchTest`,
correctly disabled without the property; tree total 262 → 268 with the 6 new registry-hygiene tests; poison-pill
1/0/0). Dedicated kill-switch fork (`-Devita.bPlusTree.preCommitValidation=false`): `TierBKillSwitchTest`
**RAN 1/0/0** (not skipped — proof the property reached the fork and the static-final resolved false). Engine
reinstalled to `~/.m2` after the `TransactionTrunkFinalizer` refactor.

### Tier B commit-latency sanity (CHANGE_PROPOSAL criterion #3)

Criterion #3 asks for an order-of-magnitude, single-shot sanity check (NOT under `parallel=all`, where
saturation noise swamps the signal) that Tier B adds no measurable commit-latency regression — no formal
benchmark required. Vehicle: `EvitaTransactionalFunctionalTest` run single-shot (one class, no surefire
parallelism) with Tier B on (default) vs off (`-Devita.bPlusTree.preCommitValidation=false`), comparing the
surefire per-class wall-clock. This is the honest vehicle because Tier B fires only on transactional commits
(warm-up bulk load no-ops registration). Two back-to-back pairs, every run 25/0/0:

| Pair | Tier B ON | Tier B OFF |
|------|-----------|------------|
| 1    | 14.15 s   | 18.80 s    |
| 2    | 19.64 s   | 17.69 s    |

The within-condition spread (ON alone ranges 14.15–19.64 s) exceeds the between-condition difference, and the
sign flips across pairs (pair 1 ON faster, pair 2 OFF faster). Tier B's cost is therefore below the wall-clock
noise floor — no measurable single-shot regression. This matches the mechanism: Tier B is O(distinct dirty
leaves × height) read-path descents once per commit — the JDWP run above measured a registered scope of 1 per
touched tree on an ordinary upsert — i.e. microseconds against a commit that also appends the write-ahead log,
flushes and merges.

### CHANGE_PROPOSAL Phase 2/3 acceptance criteria — status

1. ✓ Tier tests green; Phase 1 suites stay green; acceptance `-Dgroups="indexing | filter | order"`
   **7034/0/0/1-skip** (baseline measured in the Tier B/C mirror session; this session added 6 registry-hygiene
   + 1 poison-pill tests that are `@Tag(INDEXING)`-inherited, so a fresh run reads ~7041/0/0/2-skip — not
   re-run to move the number, since the trunk-finalizer refactor's catch branch never fires on healthy
   workloads and cannot change the result); the transaction-suite gate (production Tier B/C path) **25/0/0**.
2. Full-module `unitAndFunctional` belt-and-suspenders — the targeted acceptance + transaction + tier suites
   cover the change surface (three B+ trees, one trunk-finalizer extract-method, two new test classes); the
   full-module run re-surfaces only known unrelated flakes (`ProgressRecordTest`, the CSAE/concurrent-session
   family) and is available on request rather than re-run as a Tier B/C gate.
3. ✓ No measurable single-shot Tier B commit-latency regression (table above).
4. ✓ Tier A/C unconditional; only Tier B gated (kill-switch fork test above proves the independence).
5. ✓ Item 1.4's closure (superseded by the Tier A extension) recorded above.

### Spec-owner review follow-up — Findings 1 & 2 addressed

An independent spec-owner review (`TIER_BC_REVIEW.md`) found the implementation faithful with no correctness
bug, and raised one robustness fix + one coverage gap; both are now closed:

- **Finding 1 (fix)** — the Tier B failure path in `TransactionWalFinalizer.commit` ran
  `closeRegisteredCloseables() → WAL close → completeExceptionally` sequentially, so a closeable throwing an
  `Error` (which escapes `closeRegisteredCloseables`' own `catch (Exception)`) would skip the WAL release and
  the commit completion — reintroducing the hang + isolated-WAL leak the path exists to prevent. Reshaped to
  mirror `rollback()` exactly: `closeRegisteredCloseables()` in a `try`, the WAL release + `completeExceptionally`
  in the `finally`. The broad `catch (Throwable)` is kept (deliberate — an unexpected validation error must also
  reject cleanly).
- **Finding 2 (test)** — the WAL-finalizer catch block had zero direct coverage (the tree reject tests drive
  the detection pass, never the finalizer). New `TransactionWalFinalizerTest` (2 tests, no production hook): a
  stubbed maintainer throws the same `BPlusTreeCorruptedException` a real corrupt scope produces, against a
  recording isolated-WAL stub. It pins (a) `commit` returns normally, (b) the isolated WAL is closed, (c)
  registered closeables are closed, (d) the commit future completes exceptionally with `RollbackException`
  chaining `BPlusTreeCorruptedException` — plus a throwing-closeable case pinning the Finding-1 fix. TDD-verified:
  the throwing-closeable test FAILS on the pre-fix sequential form (`the isolated WAL must be released … expected
  <true> but was <false>`) and passes after the fix.

**Verification:** the three tree classes + `TransactionTrunkFinalizerTest` + `TransactionWalFinalizerTest` +
`TierBKillSwitchTest` — **272/0/0, 1 skipped** (BUILD SUCCESS; the 1 skip is the property-gated
`TierBKillSwitchTest`). Engine reinstalled to `~/.m2` after the `TransactionWalFinalizer` fix.

## Working-tree note

Phase 1 fix-set edits (unchanged): 3 B+ trees, 6 restore paths (`InvertedIndex`, `RangeIndex`,
`ReferenceTypeCardinalityIndex`, `PriceListAndCurrencyPriceSuperIndex`, `OwnerUniqueIndex`,
`GlobalUniqueIndex`), `ChainIndex`, `LeafPageTwinHealer` DELETED, 3 loaders. Phase 2/3 edits (this session)
add Tier A + Phase 3.1 to the same 3 B+ tree production files and their 3 test classes, plus the Phase 3.2
rename in `StaleLeafPageTwinReproductionTest`. The branch also carries unrelated senesi-WAL / warmup /
collation / GroupHaving / concurrent-session / ProgressRecordTest work — do NOT fold those into a commit of
this fix set. Phase 1 and Phase 2/3 are intended as separate commits per the proposal.
