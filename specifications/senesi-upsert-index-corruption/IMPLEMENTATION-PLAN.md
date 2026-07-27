# Implementation plan — warm-up page-baseline: corruption + leaks

*Authoritative next-steps doc, 2026-07-17. Written to survive a context compaction: a fresh session should be able
to continue from this file alone. Nothing below is committed — Johnny drives git.*

## ✅ BATCH CLOSED 2026-07-17 (agents verified, RED observed, discardStaged removed)

The 4 dispatched agents all delivered and their output has been **reviewed and gated**. Everything below is in the
working tree, **uncommitted** — Johnny drives git.

**Full functional gate (`unitAndFunctional`): 20474 run, 0 failures, 1 error, 37 skipped.** The single error is
`ConcurrentSessionAccessTest.shouldRejectConcurrentAccessToReadWriteSession` — a **confirmed flake under parallel load,
not introduced by this batch** (passes **2/0/0 in isolation**; the failing path is thread-starvation in the test's own
design — the writer thread wraps around and re-creates PRODUCT pk 1 after `PRODUCT_COUNT` rounds when the guard is slow
to race, surfacing as `InvalidMutationException: already present` instead of a clean guard trip; touches none of this
batch's flush-failure code). *Test-hardening note (outside this work): that wrap-around makes starvation look like a
data error — worth a clean timeout instead.* Batch tests all green: suspension 6/0/0, poison 3/0/0, all B collapse
tests, G's T6 tripwire, `PageStreamRegistryTest` 14/0/0 (one fewer after the `discardStaged` test removal). Both RED
checks observed and restored; the tree is leak-free (no revert left behind). All 7 `publishPreviousFlush` javadocs
name the right mechanism per path (SUSPEND for trunk incorporation, POISON for warm-up).

| agent | delivered | verified |
|---|---|---|
| javadoc reword (item 3) | **12 sites** (not ~8), comments-only across 7 index files; all `publishStaged()` calls intact | reviewed: replaces "flush failure is fatal" with the now-real suspend (tx path) / poison (warm-up path) guarantee, notes the publish runs at COLLECT time, keeps the true parts non-load-bearing |
| B tests: histogram-range + filter | `HistogramIndexLoaderPagingRoundTripTest` (range collapse) + new `FilterIndexPagedCollapseEmissionTest` (bucket + range axes) | green in the gate |
| B tests: unique + sort | collapse tests in `OwnerUnique…`, `GlobalUnique…`, `SortIndexOwner…PagingRoundTripTest` | green; vacuity guard derives N from the tree walk, not from the accessor under test |
| B tests: price + cardinality | collapse tests in `PriceListAndCurrencyPriceSuperIndexPagingTest` (`@Nested WarmUpFlushCollapseTest`) + `ReferenceTypeCardinalityIndexPagingRoundTripTest` | green; **RED empirically confirmed** on the cardinality site (see below) |

**RED observed empirically (the two blocking checks, both restored afterward):**
- **Cardinality B site** — reverting the accessor to the published set (`livePages(…)`) made the collapse test fail
  `expected: <6> but was: <0>` with the leak message. Genuine, discriminating. Restored to `pendingLivePageSequences(…)`.
- **Suspension gate** — neutering the `processTransactions` gate (`if (false && …)`) made
  `shouldNeverRetryTheIncorporationWhoseFlushFailed` **error on the second drain** with `UnexpectedIOException` — i.e.
  "it tried again," the defect itself. Restored.

**Also done this batch:** `discardStaged()` deleted from `PageStreamRegistry` (+ its dedicated unit test removed, the
empty-handshake test trimmed to publish-only); the class javadoc + `staged` field doc reworded to the suspend/rebuild
story; a stale `{@link #stage(int, Set)}` link fixed to `int[]`.

**Update 2026-07-18 — T3 + T10 now DONE (item 5 fully built).** The corruption-level T3 was built and run (GREEN +
RED). Finding: item 5 (suspend) and item G (page-list tripwire) each independently do real work, but the RED did NOT
reproduce on-disk overlap through the transactional retry — G fired loudly (`pageListChanged=true`, a visible desync),
the predicted silent-skip path did not occur, and whether disk corruption is reachable absent BOTH guards is left OPEN
(don't-chase). See item 5's "T3 — corruption-level test" section for the full reading. **The only remaining plan item
is item 6 (diagnostics, rescoped to stop-loud + rich diagnostics, no repair).**

## Read-first map

| doc | what it is |
|---|---|
| `ANALYSIS-warmup-freed-page-baseline.md` | the diagnosis + the pilot fix + the repro (RED→GREEN evidence) |
| `BRIEF-warmup-page-baseline-for-advisor.md` | self-contained problem statement handed to the advisory model |
| `ADVISORY-VERDICT-warmup-page-baseline.md` | the advisory model's 4-track code audit + verdict (options A–G scored) |
| `DECISION-failure-boundary.md` | **owner decision: SUSPEND, not fatalize** + advisory sign-off + the owner-side check of its refinement |
| `AUDIT-bplustree-family-block-size-confusion.md` | the *previous*, already-shipped D1–D4 bug (different defect; do not confuse) |

## The defect in one paragraph

`PageStreamRegistry`'s published live-page set (= "which leaf pages are on disk") advances **only** via
`publishStaged()`, called only from each index's `createCopyWithMergedTransactionalMemory` (the transactional
commit-merge). **A WARM_UP flush never reaches a commit-merge** ⇒ the baseline stays empty for the whole
re-index ⇒ `freedPageSequences() ≡ ∅` ⇒ (a) merge-freed pages are never removed [**leak L1**] and (b)
`pageListChanged = anyFreshLeaf || freed≠∅` is false for a **merge-only** flush ⇒ the PAGED root is skipped and
still lists the dropped page ⇒ cold load assembles survivor(absorbed keys) + stale donor ⇒ **overlap corruption**.
Trigger is a leaf **MERGE** (survivor mutates in place, keeps its page, nothing allocated), never a split.

## Current state

- **Seam A rolled out to all 7 paged indexes — DONE, in the working tree, uncommitted. Gate: 3749/0/0.**
  (`InvertedIndex`, `RangeIndex`, `ChainIndex`, `OwnerUniqueIndex`, `GlobalUniqueIndex`,
  `PriceListAndCurrencyPriceSuperIndex`, `ReferenceTypeCardinalityIndex` — each `publishPreviousFlush()` →
  `pageStreamRegistry.publishStaged()` as the first statement of its per-flush entry point; each with a TDD test
  verified RED → GREEN → still-RED-with-fix-disabled.)
- **Corruption-capable subset**: `InvertedIndex` (3 owners: FilterIndex / HistogramIndex / **OwnerSortIndex**),
  `RangeIndex`, `ChainIndex`, `OwnerUniqueIndex`. The other three re-emit their PAGED root **unconditionally**
  (it carries an inline companion), so `pageListChanged` cannot skip ⇒ **leak-only, corruption impossible**.
- **Known inconsistency**: `ChainIndex` publishes in `doAppendStorageParts` (**pre-branch**, so its collapse path
  is already covered = L2 closed there); the rest publish inside `collectChangedPages` (PAGED-only, L2 open).
  Item 2 makes this uniform.
- **Known weak spot**: `ChainIndexTest`'s merge precondition rests on an empirically-known shrink sequence, not
  derived arithmetic (its position tree is `UnorderedLookupTree`, not a `TransactionalBPlusTree`).

## Design decisions already made (do not re-litigate)

- **SUSPEND, never fatalize.** Reader liveness is the top priority. Seam A does not need the process to die — it
  needs *"no NEXT flush against a FAILED flush's baselines"*. Suspending the catalog's transaction processing
  delivers that vacuously. Safe because the **in-memory tree is always correct** (only persisted state + baselines
  lie) and the tx is already durable in the WAL. See `DECISION-failure-boundary.md`.
- **`discardStaged()` → DELETED (2026-07-17)** (no next flush consumes it; resume-without-reload is impossible anyway
  because the popped storage parts are already gone). Doc reworded to the suspend/rebuild story.
- Options **C / D / E / F rejected**; **A + B + G** adopted (verdict §4).

## Work items

TDD is mandatory throughout: **write the test, observe it RED, then fix, then GREEN, then re-verify it still fails
with the fix disabled.** A fix whose test was never red is not accepted.

**Ordering constraint**: `1 → 2 → {4, 5, 3} as ONE batch with 3 last → 6`. Items 4/5 are separable in review but
**not shippable apart from 3** — see the hazard note in item 3.

*(Note: `Catalog.java` lives at `io/evitadb/core/catalog/Catalog.java`, `EntityCollection.java` at
`io/evitadb/core/collection/EntityCollection.java` — both moved package; the line numbers below are current.)*

### 1. G — direct page-list comparison in the shared registry — **✅ DONE, GREEN**

**Status 2026-07-17**: implemented in the working tree, **uncommitted**. `PageStreamRegistryTest` **15/0/0**; T6
observed **RED first** with the exact predicted signature (*"Expected GenericEvitaInternalError to be thrown, but
nothing was thrown"*) → GREEN after the fix. **Full gate `io.evitadb.index.**` + `io.evitadb.api.functional.storage.**`
= 3751/0/0** (3749 baseline + T6 + the characterization test) — i.e. the assertion is live on every flush across all
7 indexes and **does not false-fire** against real splits/merges/steals/collapses/restores/goLive/tx-commits. The
reload tests passing is the specific evidence that `restoredFrom` seeds the ordered baseline correctly (gotcha 2).

What landed, vs. what this section originally specified:
- `PageStream` gained `liveOrdered` (`int[]`, `EMPTY_INT_ARRAY` for a never-published stream) + `stagedOrdered`,
  promoted in lockstep with `live`/`staged` in `publishStaged()` and cleared together in `discardStaged()`.
- **`stage` and `restore` now take an ordered `int[]` and derive the set internally** — the null-skew fix the
  advisor endorsed. Blast radius was exactly as predicted: **zero** production call sites for `stage`, **one** for
  `restore` (`restoredFrom`), plus the unit tests. Engine compiled clean on the first try, which independently
  confirms the call-site map above.
- Both entry points additionally assert **no duplicate page** in the list (a repeat would silently shrink the
  derived set and desync it from the ordered list — the very class of drift this item exists to prevent).
- `collectChangedPages` decides `pageListChanged` by `!Arrays.equals(orderedPageSequences, stream.liveOrdered)` and
  cross-checks it against `anyFreshLeaf || freed.length > 0` via `Assert.isPremiseValid`.



**Why first**: one method, benefits all 7 indexes, and would have turned this entire bug class into a loud
flush-time failure instead of a corrupt catalog found weeks later. It is the piece that makes "we'll never hit
this again" safe rather than hopeful.

**The load-bearing reason (do not lose this):** the two signals *cannot disagree* under a correct baseline (see
"why throwing is right" below), so a disagreement **is** a baseline bug — and the proxy is the one that fails
*silently and dangerously*: `freed = published − nextLive` under-reports under a stale/empty baseline
(`∅ − anything = ∅` ⇒ root skipped ⇒ **corruption**), which is precisely how this bug reached disk unnoticed. A
direct compare of `publishedOrdered` vs `orderedPageSequences` cannot under-report that way, so holding the two
side by side converts the entire bug class from *silent corruption discovered weeks later at cold load* into a
**loud failure at the flush that caused it**.

**Chosen semantics: THROW, do not self-heal.** `Assert.isPremiseValid` always throws (it is not the JVM `assert`
keyword — there is no `-da` escape), so a divergence **fails the flush**. That is deliberate and matches doctrine
("never silently skip unexpected states"). It costs nothing in reader liveness: item 5 turns the failed flush into
a clean **suspend** — readers keep serving, writers are refused, the in-memory tree is still correct. During B+
stabilization a baseline regression should be surfaced forcefully, not papered over.
*Do not* describe G as "degrading to a harmless wasted write" — with a throwing assert that path is **never
operative**, and the earlier draft of this plan said so incorrectly. (Self-heal + log — direct decides, divergence
logs and proceeds — is the only variant where the "harmless wasted write" story is literally true. It contradicts
project doctrine, so it is **Johnny's call, not a default**.)

G does **not** fix the leak (reclaim still reads the published set) — that is A + B. G is the tripwire.

- **File**: `evita_engine/src/main/java/io/evitadb/index/page/PageStreamRegistry.java` →
  `collectChangedPages(...)`.
- **Change**: compute `pageListChanged` by **directly comparing** `orderedPageSequences` against the last-published
  ordered list. Demote the two proxies (`anyFreshLeaf`, `freed≠∅`) to a **cross-check assertion that throws on
  mismatch** (project doctrine: unreachable states must throw).
- **Do NOT** take the "drop the root-skip" variant — the O(1) steady-state root cost is worth keeping once the
  skip cannot be wrong.

**Verified implementation gotchas** (each one would otherwise make the assertion false-positive):

- **The live set is a `HashSet<Integer>`** (`PageStream.live`, `:422`) — order is genuinely lost, so the baseline
  must be a **separate ordered `int[]` per stream** (`Arrays.equals` compare). Do not try to reuse `live`.
- **Seed the ordered baseline at restore**, or the *first post-load flush* direct-compares `null` vs `[0,1,2]` ⇒
  "changed" while the proxies say "unchanged" ⇒ **the cross-check throws at boot-adjacent time**, the worst place.
  *Good news, verified*: `restore(int, int, Set)` has **exactly one caller** — `restoredFrom(:253)`, used by all 7
  indexes — and it already walks the handles **in ascending key order** (`:256-260`), so the ordered list is free
  there. Prefer changing `restore` to take the ordered `int[]` and derive the set internally (one source of truth)
  over adding an overload.
- **Advance the ordered baseline in `publishStaged()`**, symmetric with `live` — so the capture re-run compares
  equal. *Verified*: `stage(int, Set)` has **no external callers** (only `collectChangedPages:356`), so the ordered
  staged list can be recorded inside `collectChangedPages` without touching a single call site.
- **Why throwing is right** (not a benign disagreement to tolerate): an order-only change without a membership
  change is **impossible** — leaves are key-ordered, and steal/merge/split each either preserve order or change
  membership. So a proxy-vs-direct mismatch is *always* a genuine bug.

**Tests**

- **T6**: force a baseline desync (stage a set, never publish, then collect a merged-away tree — the warm-up bug's
  exact registry-level shape) ⇒ assert `collectChangedPages` **throws**. Nothing else: with a throwing assert you
  can never observe the "root re-emitted anyway" outcome in the same run, so do not assert both.
- **The real verification is not T6 — it is the full gate green with the assertion LIVE.** T6 proves the tripwire
  *fires*; only the 3749 gate proves it does not **false**-fire, because that suite already exercises real
  splits/merges/steals/collapses/restores/goLive/tx-commits across all 7 indexes, now asserting on every flush.
  **If an existing persistence test goes red, do NOT patch the test** — that is a genuine direct≠proxy divergence
  falsifying the "they always agree" claim (either a case not enumerated above, or a mis-seeded baseline). The most
  likely false-fire is the first post-load flush if `restore` seeds `live` but not the ordered baseline (gotcha 2);
  the reload tests are exactly what catches it.
- **T5 — capture-pass pin** *(restored; was dropped from the verdict's matrix)*. Now load-bearing twice over: item 3
  rewords eight javadocs around the *publish-at-collect* reality, and item 1's baseline-advance semantics assume it.
  Pin it: after `popTrappedUpdates`, `live == staged == nextLive`. Without the pin a future refactor of
  `notifyFlushed` could silently move publish timing and invalidate both.
- **T7 — goLive hand-off** *(restored)*. **Verified genuinely uncovered**: the e2e repro covers warm-up→warm-up, and
  the three existing goLive+paged tests (`FilterIndexPagedPersistenceTest`, `PriceSuperIndexPagedPersistenceTest`,
  `StaleLeafPageTwinWriterReproductionTest`) all use `goLiveAndClose()` **without forcing a merge in the first
  transactional commit**, so none would catch this. The uncovered locus is real: a warm-up flush stages set S,
  goLive carries S **by reference**, and the first transactional collect is what publishes it — pre-A a merge-only
  first tx would diff against `published ≡ ∅` and skip the root. Test = warm-up flush → goLive → merge-only tx →
  reload clean.

### 2. B — collapse reclaim from `pendingLivePageSequences()` — **✅ DONE (gate pending)**

**Status 2026-07-17**: implemented in the working tree, uncommitted. **T2 observed RED first**:
`expected: <25> but was: <0>` — the first warm-up flush wrote 25 leaf pages and the collapse emitted **zero**
removals ⇒ 25 permanently-leaked records per collapse. GREEN after the fix (`HistogramIndexLoaderPagingRoundTripTest`
14/0/0). All **10** collapse sites converted; a repo-wide grep confirms **no collapse site reads the published set
any more**.

**The natural experiment that settled the fix direction** (found while writing T2, worth keeping): the *drop-from-map*
path at `HistogramIndex:245` already reclaims via `currentLeafPageSequences()` (= pending) and its **two-warm-up-flush,
no-reload** test is green. The *collapse* path used `livePageSequences()` (= published) and leaks. Same duty, two
accessors, one already correct — B just makes the collapse use the one that works.

**Why the existing collapse test never caught it**: `HistogramIndexLoaderPagingRoundTripTest`
`shouldRemovePriorLeafPagesAndCarryInlineWhenCollapsingFromPagedToSingle` **reloads through the real loader** before
collapsing, and the loader calls `restoredFrom` ⇒ the baseline *is* seeded from disk ⇒ the published set is correct
there. T2 is its exact mirror **minus the reload** — which is the warm-up shape.

**B is needed even WITH seam A**, which is easy to get wrong: seam A publishes inside `collectChangedPages`, and the
SINGLE branch never calls it. So at a collapse `live` = the set published *two* flushes ago while disk holds the
*last* flush's set. Pending (= staged) is the only accessor that equals "what disk holds now".

**Dead code removed as part of this** (flag on review — an API deletion, not in the original spec): with all 10 sites
converted, `InvertedIndex.livePageSequences()` and `RangeIndex.livePageSequences()` had **zero callers repo-wide**,
and the registry's `livePageSequences(int)` was called only by those two. All three deleted, plus the now-single-use
`liveSequencesExcluding` helper folded back into `freedPageSequences`. Rationale: they are not merely dead, they are
the **footgun that caused L2** sitting next to the correct accessor, carrying a javadoc that had become false
("Used by the `PAGED -> SINGLE` fallback…"). Leaving them invites the next reader to reintroduce the bug.
*(Untouched: `UnorderedLookupTree`/`TransactionalUnorderedIntArray.livePageSequences()` — a different mechanism,
not `PageStreamRegistry`-backed, still used by tests.)*

Closes **L2**; **L3 then dissolves** (the audit verified re-issued page ids are content- and time-travel-safe:
supersede-at-same-key + per-version roots + missing-removals are silent no-ops).

- **Sites**: every `PAGED → SINGLE` collapse branch that reclaims via `livePageSequences()` (the *published* set)
  before `forgetPageStream()`. Grep `livePageSequences(` — includes `FilterIndex.appendBucketAxis` /
  `appendRangeAxis`, `HistogramIndex.appendHistogramStorageParts`, `OwnerSortIndex:498`, `GlobalUniqueIndex`,
  `OwnerUniqueIndex`, `ChainIndex:990`, `PriceListAndCurrencyPriceSuperIndex`, `ReferenceTypeCardinalityIndex`.
- **Change**: reclaim from `pendingLivePageSequences()` (staged-or-live) instead. At a warm-up collapse `staged`
  holds exactly the last PAGED flush's set; on the tx path `staged` is null after the merge ⇒ falls back to
  `live`. Same invariant as A, same precedent as `AttributeIndex.currentLeafPageSequences()`.
- Optional (not load-bearing): replace `forgetPageStream()` with reclaim-from-pending + `stage(∅)`, preserving the
  advance-only allocator.
- **`ChainIndex:990` is changed for UNIFORMITY ONLY** — its pre-branch publish (`doAppendStorageParts:926`) already
  closes L2 there, so this site is not a live defect. Written down so a later reader does not "fix" the apparent
  inconsistency **backwards** by moving ChainIndex's publish down into the PAGED-only branch to match the others.
- **Test T2**: warm-up PAGED→SINGLE collapse ⇒ assert every prior `*LeafPagePart` is **absent from storage** at the
  latest version; regrow + reload clean. Expect **RED today** (records linger). ✅ **Done** —
  `HistogramIndexLoaderPagingRoundTripTest.shouldRemovePriorLeafPagesWhenCollapsingAcrossTwoWarmUpFlushesWithoutAnIntermediateReload`.

#### ✅ DONE — B's per-site test rollout (2026-07-17)

The **fix** is rolled out to all 10 sites and every site now has a **warm-up-shape collapse test** = the existing
collapse test **minus the reload**, asserting `removals == priorLeafPageCount`. This coverage cannot be inferred from
the green gate: every *existing* collapse round-trip test reloads through the loader before collapsing, which seeds the
baseline from disk ⇒ it passes with or without B ⇒ it proves nothing about the warm-up shape. The new tests reload
nothing.

Delivered (4 agents, disjoint indexes): HistogramIndex **range axis** + `FilterIndexPagedCollapseEmissionTest` (bucket
+ range), `OwnerSortIndex`, `OwnerUniqueIndex`, `GlobalUniqueIndex`, `PriceListAndCurrencyPriceSuperIndex`,
`ReferenceTypeCardinalityIndex`. **`ChainIndex` needs none** — its pre-branch publish makes pending == published there,
so its site is uniformity, not a defect. **RED empirically confirmed** on the cardinality site (`expected: <6> but
was: <0>`); the others share the identical registry mechanism and each derives N from the tree walk, not from the
accessor under test, so none can degenerate to `0 == 0`.

### 3. Javadoc reword — **✅ DONE (12 sites, reviewed, gate-green)**

*Delivered by the javadoc agent: 12 comment-only sites across 7 index files (7 `publishPreviousFlush()` javadocs + 5
merge-side parentheticals — `OwnerUnique`/`GlobalUnique` never carried the "is fatal" clause, so they correctly have
one site each). All `publishStaged()` calls intact. Reviewed: the text now matches what items 4/5 enforce. Original
brief kept below for the record.*

> **Ordering hazard — ship 3 with (and last in) the 4+5 batch.** Rewording these javadocs to suspend/poison
> semantics *before* items 4/5 implement them re-creates **the exact sin being fixed**: a comment asserting
> behaviour nothing enforces. For however long the commits sit apart, the tree would again document a guarantee it
> does not have. Either ship 3 after 5, or ship 3+4+5 as one batch.

The rollout replicated an invariant that is **false**: *"a flush failure is fatal"*. Reword to the guarantee
actually enforced: **a failed flush suspends further flushes of that catalog (warm-up: poisons the collection), so
no later flush ever diffs against the baselines it left behind.** Say that suspend and poison are the same
invariant in two dresses.

- 7 × `publishPreviousFlush()` javadocs, **plus** the merge-side comment in
  `InvertedIndex.createCopyWithMergedTransactionalMemory` (*"a flush failure is fatal — restart rebuilds a clean
  registry"*), which the rollout copied family-wide.
- Also drop the *"still staged when the next flush begins ⇒ on disk"* narrative: the baseline-capture pass
  re-enters `collectChangedPages`, so A actually publishes **at collect time, pre-write**, on every flush.

### 4. Warm-up poison — **✅ DONE, GREEN (gate 3762/0/0)**

**Status 2026-07-17**: implemented, uncommitted. `WarmUpDataStoreMemoryBufferPoisonTest` 3/0/0, **RED first**
(2 failures, *"Expected GenericEvitaInternalError to be thrown, but nothing was thrown"*).

- **Poison CHECK** at `WarmUpDataStoreMemoryBuffer.popTrappedChanges` — the single choke point.
- **Poison SET** at `EntityCollection.flushInternal` (`catch (Throwable)` → `dataStoreBuffer.poison(ex)` → rethrow).
  Confirmed the right locus: **both** flush shapes funnel through it, so the advisory's suggested
  "flush future's exceptional completion" would have **missed the synchronous `flush()`**.
- **Catalog-level** poison via `flushFuture.whenComplete` in `Catalog.flush()` — needed because `:1801` pops the
  catalog's own changes on the caller thread before any collection future exists.
- `poison()` is a **no-op default on `DataStoreMemoryBuffer`** (advisory's suggestion), so the ALIVE path needs no
  `instanceof`: the transactional buffer is discarded wholesale on a failed commit and cannot outlive its flush.
- **The data-loss defect is now pinned by a passing characterization test** (`shouldPopTrappedChangesDestructively`):
  pop really is destructive, which is *why* poison must exist.

### 4b. (was 4) Original spec

Fixes a **pre-existing silent data-loss defect** found by the audit, independent of the page baseline:
`popTrappedUpdates` is destructive and advances every baseline *before* the write, so a failed warm-up flush
permanently loses the popped parts.

- **Poison CHECK — single choke point** (supersedes any path enumeration):
  `WarmUpDataStoreMemoryBuffer.popTrappedChanges` — every warm-up collect passes through it (session close;
  lifecycle `Catalog.java:2236`/`:2286`/`:2430`; goLive `MakeCatalogAliveMutationOperator:105`; **terminate
  `:2464`**; and the schema-op flushes `:2263`/`:2266`, `:2401`/`:2424`).
- **Poison SET — the hook the choke point cannot provide.** The pop is where the flag is *read*; the failure
  happens **later**, in the write. **Verified structure**: `EntityCollection.createFlushFuture():1620` pops
  **synchronously on the caller thread** (`:1621`) and only then wraps `flushInternal` in a `ProgressingFuture` —
  so pop and write are genuinely separated. Both public shapes (`createFlushFuture():1620` async and
  `flush():1638` sync) funnel through the one private `flushInternal(:1656)`, which makes **`flushInternal` the
  single failure hook for both** — better than hooking the future's exceptional completion, which would miss the
  sync shape. On any `Throwable`: flip the collection's buffer poison, then rethrow.
- **The gap is WIDER than collection granularity — the catalog buffer needs it too.** `Catalog.flush():1793` pops
  **the catalog's own** trapped changes at `:1801`, on the caller thread, *before* any collection future is even
  constructed (`:1808`), and writes them only in the **combine step** at `:1822-1827`. So one collection's write
  failure can strand the catalog's already-popped parts, with the combine step never running. Poison must cover
  the catalog-level `WarmUpDataStoreMemoryBuffer`, not just each collection's.
- **Emergent behaviour worth stating** (it is the *goal*, not a side effect): once a collection is poisoned, the
  next **catalog-wide** flush maps over all collections (`:1805-1809`) and therefore **fails deterministically at
  the poisoned collection's pop**. That is the desired loud refusal — no partial write, no silent proceed.
- **Test T4**: inject a failure into the collection-created flush (`Catalog.java:2236`), then attempt a further
  flush/session ⇒ assert **precisely that shape**: the catalog-wide flush fails at the poisoned collection's pop,
  deterministically, not a silent proceed (also assert the popped parts are gone, pinning the data-loss defect).
  **Test T9 (the real trap)**: poisoned warm-up collection → `terminate()` → assert **no bytes written**.
- **Open question to probe while here** (suspected, NOT asserted — verify before acting): `Catalog.flush():1822`
  gates the catalog's own write on `if (resolvedChangeOccurred)`, but the pop at `:1801` already happened
  unconditionally. If a catalog-level trapped change can exist while `resolvedChangeOccurred` is false, those parts
  are **popped and dropped unwritten** — a third shape of the same pop-before-write defect. Determine whether that
  state is reachable.

### 5. Suspend boundary (transactional) — **⚠️ MECHANISM-VERIFIED, corruption-RED (T3) still open**

**Status 2026-07-17**: core implemented + cheap RED done, uncommitted. `TransactionManagerSuspensionTest` now **6/0/0**,
reworked to drive suspension through a **real thrown flush** (not a direct `suspend()` call). The discriminating test
`shouldNeverRetryTheIncorporationWhoseFlushFailed` was **observed RED** by neutering the gate (second drain errored with
`UnexpectedIOException` = "it tried again"); restored to green. **T12 free**:
`shouldKeepRetryingWhenTheFailureHappenedBeforeTheCollectBegan` (a pre-collect `setVersion` throw ⇒ no suspend, still
retries). `discardStaged()` deleted this batch.

**What this does and does NOT prove.** It proves the gate stops the *retry* — the livelock/anti-retry mechanism. It does
**not** prove suspend prevents the on-disk overlap corruption. That is **T3** — now **partially built (GREEN done, RED
pending)**, see below.

#### T3 — corruption-level test (2026-07-18): GREEN done, RED done — DEFENCE-IN-DEPTH CONFIRMED

`TransactionalMergeFlushFailureSuspendTest` (functional_tests, `io.evitadb.api.functional.storage`). Real embedded
Evita, the 513-entity ascending-`OffsetDateTime` recipe → paged 4 leaves → `goLive` → a **transactional** delete of
pk 129/1/2 forces leaf 0 `mergeWithRight` during trunk incorporation. Injection = reflective proxy on the live
`Catalog.persistenceService` (a proxyable interface; `private final`, reflectable on the classpath — the established
`WarmUpFlushFailureCloseTest` pattern; **no `--add-opens` needed**) that throws **once** from `storeHeader` — the point
AFTER every collection has staged its next page baseline but BEFORE the version is durable.

- **Milestone 1 (harness) — GREEN, 1/0/0**: a transactional leaf merge with no injection incorporates + cold-reloads
  cleanly.
- **Milestone 2 (GREEN) — 2/0/0**: with item 5 active, the transient `storeHeader` failure → the merge commit surfaces
  the failure (does not hang), the catalog keeps serving N−1 (all 513 resolve, deletes not applied), and a cold reload
  replays the WAL and lands a clean 3-leaf tree. **Item 5 recovers cleanly at the corruption level.**

**RED RESULT (2026-07-18) — both guards do real work; the predicted SILENT-skip corruption did NOT reproduce; disk
corruption absent BOTH guards is OPEN.** Ran a throwaway probe with the `processTransactions` gate neutered
(`if (false && …)`, engine reinstalled), a tracked transient `storeHeader` failure (entry + completion counters), a
bounded 60 s wait, then a reload — probe + engine-neuter both restored afterward, full class back to 2/0/0. What
happened:

- **Attempt 1** (client `WAIT_FOR_CHANGES_VISIBLE` path): collections flush and stage the merged baseline `[0,2,3]`,
  then `storeHeader` throws → `suspend()` fires, the parked commit fails. `storeHeaderEntries=1`.
- **Attempt 2 — the retry DID fire** (gate off): `drainWal → processTransactions` re-collects. `publishPreviousFlush()`
  promotes attempt-1's lingering staged `[0,2,3]` to the live baseline, but the re-collected tree is the **unmerged**
  `[0,1,2,3]` — `forgetVolatileData` reverted the merge and the retry did **not** re-merge before this flush (exactly
  *why* it didn't re-merge is an open sub-question; it is load-bearing — see below). So collected `[0,1,2,3]` **≠**
  baseline `[0,2,3]` ⇒ `pageListChanged=true`, `freed=∅`, `anyFreshLeaf=false` ⇒ G's cross-check
  `pageListChanged == (anyFreshLeaf || freed>0)` is `true == false` ⇒ **G's tripwire FIRES**, before `storeHeader`
  (so `storeHeaderEntries` stayed 1, `completions=0`), before any root-skip decision exists:
  `GenericEvitaInternalError: Page stream 0 has a stale published page baseline: the collected page list [0, 1, 2, 3]
  disagrees with the published baseline [0, 2, 3]` at `PageStreamRegistry.collectChangedPages:372`. The flush aborted,
  the merge tx stayed in the WAL.
- **Cold reload**: replayed that one WAL tx against a fresh registry → clean 3 leaves.

Reading it precisely (corrects an earlier draft of this paragraph):
- **The predicted trace was CONTRADICTED, not confirmed.** The prediction was that the retry *re-merges* ⇒ collected
  equals the phantom merged baseline `[0,2,3]` ⇒ `pageListChanged=false` ⇒ G stays SILENT ⇒ root skipped ⇒ overlap ⇒
  item 5 is the SOLE guard. Instead `pageListChanged` was **true** and G fired LOUDLY on a *visible* desync
  (tree-reverted-unmerged vs baseline-advanced-merged). The silent-skip path did not occur because the retry did not
  re-merge.
- **Both guards do real work — stated precisely:** item 5 (suspend) stops the retry entirely (GREEN); item G (the
  direct page-list tripwire) loudly aborts the retry's stale-baseline flush when item 5 is off (RED).
- **NOT established (OPEN, per the SNAPSHOT "don't chase" stance):** whether the flush G aborted would have corrupted
  disk absent BOTH guards. With item 5 off *and* G off, `pageListChanged=true` means the root would be re-emitted as
  `[0,1,2,3]` — plausibly a stale-but-consistent tree that WAL replay repairs, not necessarily the overlap. This RED
  did **not** demonstrate on-disk overlap through the transactional retry; that remains unproven. The open
  sub-question (why attempt 2 collected the unmerged tree rather than re-merging) is exactly what decides whether a
  silent-corruption path exists at all — record it, do not paper over it.

Landed: `CatalogSuspension` record (cause / durable / failedCatalogVersion / servingCatalogVersion) + `suspend()` +
`getSuspension()`; **gate at the `processTransactions` entry**, before the trunk lock is taken; **positional scoping**
via `collectingVersion` set immediately before `commitChangesToSharedCatalog` (a pre-collect failure still rethrows
and keeps its bounded retry); `failAllPending` on suspend; **fresh-commit refusal at `commit(:494)`** copying the
queue-full handler's shape; pre/post-durability distinguished via a new `Catalog.getLastPersistedCatalogVersion()`;
`Assert.isPremiseValid(!suspended)` in `processEntireWriteAheadLog`.

**Verified while building** (advisory's verify-list):
- `drainWal` needs **no change**: it reschedules only on `TransactionTimedOutException`/`WriteAheadLogCorruptedException`
  and otherwise returns `-1` (pause). The gate returning **`Optional.empty()` rather than throwing** is what makes it
  pause — that is the anti-livelock guarantee. **Do not make the gate throw.**
- `retryTransactionProcessing` **kept** (it serves the publisher-closed / `RejectedExecutionException` overload case);
  the gate neutralises it for flush/merge failures. That IS "scope positionally, not by exception type".
- **T11 confirmed free by construction**: `updateLastFinalizedCatalog` runs *after* `commitChangesToSharedCatalog`, so
  a collect-began throw can never advance `lastFinalized` past N−1.

#### ✅ DONE — the "cheap RED"

The behaviour that was *wrong* before item 5 is not the absence of `suspend()` — it is that **the catalog RETRIES**.
The test needs **no injection and no new API in the assertion**:

> make the flush throw once ⇒ let `processTransactions` fail ⇒ then assert **a subsequent drain does nothing**.

**How it was built (for reference / to extend into T3):** `TransactionManagerSuspensionTest` keeps the mocked-`Catalog`
fixture but now stubs `getCommittedLiveMutationStream(…)` to hand out a fresh single empty `TransactionMutation` per
call (a `Stream` is single-use — the *second* drain is the whole subject), and arms `catalog.flush(anyLong(), any())`
to throw `UnexpectedIOException`. That flush is what `TransactionTrunkFinalizer.commitCatalogChanges` calls first, before
the merge, so it reaches the collect-began locus and suspends. Pre/post-durability is driven by stubbing
`getLastPersistedCatalogVersion()` (< vs == the failing version). The empty transaction replays trivially
(`mutationCount == 0`) and `TransactionalLayerMaintainer.commit()` only calls `finalizer.commit(layer)` (stores a ref,
no catalog interaction), so the mock survives all the way to the flush.

**Observed RED** by wrapping the gate in `if (false && …)`: the second drain re-enters flush and errors with
`UnexpectedIOException` — literally "it tried again." Restored to green. This is a **mechanism** RED, not the corruption
RED — see T3.

#### Full T3 — the seam EXISTS; it is a production extension point, not a test flag

`CatalogPersistenceServiceFactory` is a **ServiceLoader SPI**
(`evita_store/evita_store_server/src/main/resources/META-INF/services/io.evitadb.spi.store.catalog.persistence.CatalogPersistenceServiceFactory`);
`Catalog.java` resolves it via `ServiceLoader`, and `evita_test_support` already ships a `META-INF/services` dir. So a
test-scope factory that **delegates everything** and fails the Nth `flushTrappedUpdates` uses the architecture rather
than adding a `-Dtest.fail.flush` prod flag (which project policy bans).

- **Verify FIRST, it decides the route**: `ServiceLoader` is JVM-global for the module. Check how `Catalog` selects
  among multiple providers — if it takes the first found, registering a test factory silently changes **every** test in
  `evita_functional_tests`. It MUST be inert by default (pure delegation) and armed per-test via a static/ThreadLocal
  latch. If it cannot be made inert-by-default, this route is OUT (fallback: chmod the storage dir read-only — fragile,
  and root ignores permissions).
- Other ingredients: the merge-only transactional commit (port the 513-entity ascending-`OffsetDateTime`
  `mergeWithRight` recipe from `FilterIndexPagedPersistenceTest` from warm-up to post-`goLive`); a **transient** failure
  (fail once, then pass) at a controlled point, distinguishing pre- from post-`storeHeader`; and a cold reload to
  inspect the on-disk root. RED = the overlap error verbatim; GREEN = suspended + readers served + clean after reload.

#### 🚨 HONEST GAPS — do not mistake the green gate for completion

1. **The 3 suspension tests were NEVER RED.** They exercise API that did not previously exist, so they could not fail
   beforehand. They pin the mechanism; they do **not** prove it fixes anything.
2. **T3 — the actual RED — is NOT built.** It needs a transactional merge-only tx + an **injected transient flush
   failure**, and no failure-injection seam exists (the project bans test-only production flags, and the persistence
   service is not reachable from a functional test). **Build the probe before writing T3's assertion** — the advisory's
   warning stands: T3's premise predates G, and you cannot tell from reading whether the retry now (a) still corrupts
   or (b) trips G's assertion instead.
3. **A reasoned trace (NOT an observation — verify it) says (a): G does NOT catch this window.** On retry, seam A's
   `publishPreviousFlush()` publishes the phantom staged set S_N ⇒ `liveOrdered` becomes `ordered_N` ⇒ the direct
   compare *agrees* with the collected list ⇒ `pageListChanged=false` ⇒ root skipped over a stale on-disk root ⇒ the
   same overlap corruption, transactionally. Both signals agree because both read the phantom baseline, so **G stays
   silent**. If that trace holds, item 5's gate is the *only* thing closing this window — which makes T3 the single
   most valuable test in this whole plan. **Either way item 5 is needed**: under (b) the throw would be re-drained
   forever by `retryTransactionProcessing` = the livelock.
4. Not done: **`discardStaged()` deletion** (+ its 2 unit tests), T10, T12.

#### Original spec

- **Gate at the WAL-drain / `processTransactions` entry** — *not* inside `Catalog.flush(long, TransactionMutation)`.
  Reason: a freshly appended WAL tx schedules `walDrainingTask` independently of the retry path, so the gate must
  stop the drain **starting**. (Note: the "schema ops collect in ALIVE" argument for this placement was checked and
  **refuted** — see `DECISION-failure-boundary.md`; the drain-trigger reason is the real one.)
- **Remove the unbounded in-place retry** for flush/merge failures (`TransactionManager.java:1302` catch →
  `retryTransactionProcessing:542`). It is the corruption vector *and* the livelock; it has no good case.
- **Scope positionally, at "collect began"** — NOT by exception type. `:1302` is a blanket `RuntimeException`
  catch and overload cases (`RejectedExecutionException`, publisher re-creation) must keep their bounded retry.
  The latch is **catalog-level** (the catalog-wide flush maps over all collections).
- **Distinguish the two failure loci in the state + alert** (they are asymmetric): pre-durability (flush stage) ⇒
  disk at N−1, reload clean; **post-durability (merge stage, e.g. `DataStructureCorruptedException`) ⇒ N is
  durable, memory at N−1 ⇒ reload lands on a suspect N**, and a deterministic merge failure re-fails on replay ⇒
  restore/repair territory. **Alert must carry the version pair (disk N / serving N−1).**
- **Commit futures (already parked)**: clients parked on the visibility stage must complete **exceptionally in
  bounded time, never hang**. Precedent to copy: `TransactionManager.close()` already does
  `pendingCommitProgressRegistry.failAllPending("the transaction manager is being closed")`.
- **Fresh commits (NEW — the acceptance surface `failAllPending` does not cover).** Parked futures are drained, but
  nothing yet **refuses new commits** while suspended, which `DECISION-failure-boundary.md` Gap 3.2 requires.
  **Verified locus**: `TransactionManager.commit(:494)` is the single entry from session commit into the pipeline.
  Refuse there — check suspended **before** `this.transactionalPipeline.offer(:501)` and complete `commitProgress`
  exceptionally. The **exact precedent is already inline**: the offer's queue-full rejection handler (`:513-520`)
  does `commitProgress.completeExceptionally(new TransactionException("...queue is full..."))`. Copy that shape
  with a suspension-specific message. Reads stay unaffected — this rejects writers only.
- Keep serving reads; recovery = operator-chosen reload/restart. Defence in depth:
  `Assert.isPremiseValid(!suspended)` inside the flush overload.
- **Delete `discardStaged()`** (+ its 2 unit tests) once this lands.
- **Tests**: **T3** (the window test — merge-only tx + injected transient failure in `flushTrappedUpdates` +
  WAL retry + cold reload; must be **RED with the pilot**, passing pre-pilot; GREEN = *"catalog suspends, readers
  keep serving, clean after operator reload"*). **T10** bounded commit-future completion. **T11** WAL purge held
  back while suspended (`lastFinalized` pinned at N−1). **T12** a pre-collect failure still retries and does
  **not** suspend. *(The advisory's proposed "T12 twin" is unwritable — re-scope it to pin the
  `transaction == null` gate on the schema-op branches, or drop it.)*

### 6. Diagnostics on tree corruption (Q6) — **✅ DONE 2026-07-18 (rescoped: report loudly, do NOT repair)**

**SNAPSHOT-phase directive (Johnny):** the B+ trees are new functionality; getting them wrong on the first try is
acceptable. The job of this iteration is to **stop loudly the first time something is off in the trees and gather as
much information as possible** — so the root cause can be fixed in the *next* iteration. **No repair. No backup. No
restore triage.** The three-mode operator repair tool (containment-rule auto-fix, orphan sweep, suspect-N verify) is
**dropped**.

What stays / what to build:
- **Keep fail-fast** (already shipped: `DataStructureCorruptedException` from the dirty-scope validators + the
  load-time page-overlap checks). Do not silently self-heal, do not auto-repair.
- **Enrich the diagnostic captured at every detection point** so a developer — not an operator — can diagnose from the
  log/exception alone: the offending stream + page list, the specific containment/overlap signature (which listed page
  nests inside which), the catalog/index versions, the merge direction if known, and enough surrounding tree structure
  to reconstruct what happened. The containment rule (a listed page whose key range is fully contained in another's is
  the stale donor a merge dropped) becomes a **diagnostic classifier in the report**, not a repair action.
- Detection points to cover: **load time** (cold reload assembling the paged tree) and **operation/merge time**
  (the transactional dirty-scope validators). Both should emit the same rich report shape.

Fix of whatever the report reveals = a **future iteration**, not this one. **Test T8** re-scoped to: inject a known
corrupt on-disk tree, assert the loud stop fires AND the diagnostic report carries the expected signature fields.

**✅ AS BUILT (2026-07-18, uncommitted). Both detection points enriched; the cost is paid ONLY on the error path
(Johnny's steer).**
- **Merge-time (item G)** — `PageStreamRegistry.collectChangedPages` keeps the `Assert.isPremiseValid` **lazy
  supplier**; the whole enriched message is built inside the lambda, so a healthy flush pays nothing. It now reports the
  cross-check inputs that made `pageListChanged` disagree with `anyFreshLeaf || freed>0`: `pageListChanged`,
  `anyFreshLeaf` (snapshotted into a final local — it is reassigned in the collect loop, so the lambda cannot capture
  it directly), `freedPages`, `highWater`, and the symmetric page-list difference (collected−baseline and
  baseline−collected). The difference helper is null-safe (the published baseline is null before a stream's first
  publish), so the diagnostic never throws a second exception that would mask the report.
- **Load-time** — the three paged B+ trees (`Bucket`, `Long`, `Element`) route their `assertCrossLeafBoundaries` overlap
  branch through a shared `AbstractTransactionalBPlusTree.overlappingLeafPagesDiagnostic(...)`. All context is gathered
  inside the overlap branch (error path only): the ordered leaf-page list, both offending page sequences, each leaf's
  key range `[first..last]` and key count, and `successorRangeWithinPredecessorRange` — the containment fact stated
  **raw, not interpreted** (the paged layout is new; a novel corruption must not be pre-labelled as the known twin).
  Page/leaf versions are deliberately absent (not threaded to the reassembly layer) and the message says so.
- **ChainIndex is NOT a gap** — its position tree is `UnorderedLookupTree`, not a B+ tree, but `ChainIndex.fromPersistedPages`
  already has a structure-appropriate load-time detector that reports the duplicated record id and both page sequences
  with the same remediation hint.
- **T8** = `TransactionalBucketBPlusTreeTest$AssembleFromSingleLeafTreesValidationTest`: the existing overlap test now
  asserts every enriched field (partial-overlap case, `successorRangeWithinPredecessorRange=false`), plus a new
  `shouldReportContainmentWhenSuccessorRangeNestsInsidePredecessor` (nested case, `=true`). Gate: the three tree test
  classes = **278/0/0**; engine `BUILD SUCCESS`.
- **IDE follow-ups folded in (Johnny's inspection):** `pageSequenceDifference` made null-safe; two obsolete
  `PageStreamRegistry` Javadoc links fixed (`livePageSequences(int)` → `livePages(int)`; `restore(…, Set)` →
  `restore(…, int[])`).

## Verification recipe

```bash
# ALWAYS rebuild the engine into ~/.m2 first — surefire otherwise resolves a stale jar over target/classes
rtk mvn -pl evita_engine install -DskipTests

# the standing gate for this work (3749/0/0 as of the seam-A rollout)
rtk mvn -pl evita_test/evita_functional_tests test \
  -Dtest='io.evitadb.index.**.*Test, io.evitadb.api.functional.storage.**.*Test' \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

- Always `rtk mvn`, never plain `mvn`. Never pipe rtk output through `grep`/`head` (hides assertions) — use
  `tail -40` or read the surefire `.txt` reports.
- The end-to-end repro is `FilterIndexPagedPersistenceTest.shouldReloadAfterAWarmUpFlushMergedALeaf` (~2 s).
- Merge-forcing arithmetic (from `AbstractTransactionalBPlusTree.consolidate`): a leaf underflows when
  `keyCount < minValueBlockSize`; a sibling can donate only when `sibling.keyCount() > minValueBlockSize`; a merge
  happens when `sibling.keyCount() + node.keyCount() < valueBlockSize`. **Bring the sibling to exactly the minimum
  first (so it cannot donate), then push the target one below.** Also assert the leaf-page count actually dropped,
  or the test can rot into a vacuous pass.

## Housekeeping

- Three agent worktrees under `.claude/worktrees/agent-*` still exist; their work is already harvested into the
  main tree. They can be removed (git op ⇒ needs Johnny's go-ahead).
- Nothing is committed. Johnny drives all git.
