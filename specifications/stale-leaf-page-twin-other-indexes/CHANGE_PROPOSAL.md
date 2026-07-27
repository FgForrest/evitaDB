# Change proposal — remove twin healing entirely: fail fast at load, detect early before disk

Audience: the implementation session (any model). This document supersedes the *healing* aspect of
`ASSIGNMENT.md`; that document and `RESULTS.md` remain as history. Do not re-audit; the audit
findings in `ASSIGNMENT.md` still stand.

**Document status (revised 2026-07-15):** Phase 1 is IMPLEMENTED and independently reviewed; its
sections are retained below as the as-built specification, with deviations annotated in place. The
baseline for the remaining work is therefore the POST-Phase-1 fail-fast tree — not the healing-era
working tree this paragraph originally described. Phase 2 is REVISED per the post-Phase-1 review
and is the next work package (prerequisite: Phase 1 committed via the pending branch-split). Phase
3 is NEW: it collects the reviewer's remaining gaps and complaints about the earlier phases.

## Owner decision and premise

**Decision (project owner, 2026-07-14): there is no healed shape at all.** The paged B+ tree
persistence has never shipped in a released evitaDB version, so no production catalog can carry a
stale leaf-page twin — the only damaged artifacts are local dev datasets with preserved pristine
snapshots. Healing therefore has no field-remediation value, and silently repairing a state that a
now-guarded writer bug produced contradicts the project's defensive-design rule. Any detected twin —
including the previously-healed strict-prefix-before shape — must throw `GenericEvitaInternalError`
with full diagnostics and an operator remediation hint (restore from backup, or fully rebuild /
reindex the catalog). In exchange, detection moves EARLIER: into the tree assemblers at load and
(Phase 2) into op-time tree asserts and the commit path before a transaction enters the WAL. (The
original flush-path idea — item 1.4 — is superseded by the Tier A boundary-mutation extension; see
Phase 2.)

Consequences accepted by the owner — do NOT "fix" these:

- The pristine senesi snapshot (4 known `published` twins) and the `data_dirty_*` datasets will
  REFUSE to load from Phase 1 onward.
- The "load behavior as classifier" trick in `RESULTS.md` §evidence-run inverts: loading a damaged
  copy now throws on the FIRST twin instead of WARN-listing all of them. A full twin census would
  need a raw-storage-part reader (see §Out of scope).

## Phase 1 — replace healing with fail-fast validation (single choke point)

**Status: DONE (2026-07-14, independently reviewed) except 1.4 (deferred, now superseded — see
§1.4). Verified: twin + tree suites 240/0, writer-repro 4/0, acceptance suite 6993/0/1-skip. One
accepted deviation from 1.5: `StaleLeafPageTwinWriterReproductionTest` was left unchanged — it pins
the FIXED writer's soundness, not a synthesized twin; see the revised Phase 2 tests section.**

### 1.1 Relocate validation into the three spine assemblers

Add cross-leaf validation INSIDE each `assembleFromSingleLeafTrees` implementation:

- `TransactionalBucketBPlusTree.assembleFromSingleLeafTrees` (~:1415) — key order = the tree's own
  comparator
- `TransactionalLongBPlusTree.assembleFromSingleLeafTrees` (~:406) — key order = natural `long`
- `TransactionalElementBPlusTree.assembleFromSingleLeafTrees` (~:296) — key order = the element order

Each gains a `@Nonnull String structureDescription` parameter (diagnostics only; reads like a noun
phrase after `persisted `, e.g. `range index for …` — reuse the exact strings the call sites already
build for the healer today). Semantics, identical across the three:

- walk the collected leaves in list order; empty leaves carry no key and impose no boundary
  constraint (skip them when locating the previous key-bearing leaf — mirror the healer's rule);
- the last key of the previous key-bearing leaf must sort STRICTLY before the first key of the next;
- on violation throw `GenericEvitaInternalError` naming the structure description, both page
  sequences, both boundary keys, and the remediation hint. No WARN path, no drop path, no payload
  comparison — key overlap alone is disqualifying.

This is the single choke point: it covers all six structures, `InvertedIndex`, and every future
caller automatically. Keep the per-structure `fromPersistedPages` methods free of validation logic.

### 1.2 Delete the healing machinery

- Delete `LeafPageTwinHealer` (class, `PageProbe` interface) entirely.
- Delete every per-structure `PageProbe` implementation and every call-site kept-indices /
  effective-array filtering block (`InvertedIndex`, `RangeIndex`, `ReferenceTypeCardinalityIndex`,
  `PriceListAndCurrencyPriceSuperIndex`, `OwnerUniqueIndex`, `GlobalUniqueIndex`) — the restore
  paths revert to plain assembly, passing their description string into the assembler instead.
- KEEP the `String indexDescription` parameters and loader threading added by the previous
  implementation (`AttributeIndexLoader`, `HistogramIndexMapLoader`,
  `ReferenceTypeCardinalityLoader`) — they now feed the assembler diagnostics.
- Note: the `PriceRecord.equals`-is-identity-only trap documented in `RESULTS.md` becomes moot
  (payloads are no longer compared); it needs no replacement code.

### 1.3 ChainIndex — duplicate record id is now unconditionally fatal

In `healChainLeafPageTwins` (rename to `assertNoDuplicateChainRecords` or similar):

- keep the O(N) `IntHashSet` fast-path scan;
- delete `isStrictRecordPrefix`, `headMarksDivergeOverPrefix`, the drop pass and the WARN;
- when a duplicate is found, run the existing attribution pass (`firstPageOfRecord`) and throw the
  existing `GenericEvitaInternalError` for the first duplicate — reword the message: every duplicate
  is now corruption (drop the "not the healable strict-prefix-before twin shape" clause), keep the
  record id + both page sequences + attribute identity + remediation hint.

### 1.4 Validate the persisted page list before it reaches disk

**Status: deferred during Phase 1 (four-reason rationale in `RESULTS.md`), now formally SUPERSEDED
by the Tier A boundary-mutation extension in Phase 2 — do NOT implement this item as written. Kept
for the historical record.**

Wherever a paged index (re)builds its ROOT storage part carrying the ordered leaf-page list — the
warm-up one-shot flush and any transactional flush that rewrites the page list — run the same
cross-leaf boundary walk (expose it as a reusable method on the three trees, e.g.
`assertCrossLeafOrder(String structureDescription)`, and reuse it from 1.1). A corrupt in-memory
tree must throw BEFORE its pages are written, so the on-disk state stays clean. Locate the exact
hook(s) via the `PageStreamRegistry` flush path; the frequency bound is "whenever the page list is
produced", which only happens on structural change, so the cost is O(pages) at bounded frequency.

### 1.5 Test changes

- Flip every heal-asserting test to a throw-asserting one across all seven twin test classes
  (`StaleLeafPageTwinReproductionTest` + the six per-structure `*StaleLeafPageTwinTest` classes):
  the synthesized strict-prefix-before twin now asserts `GenericEvitaInternalError` at
  `fromPersistedPages`.
- Delete heal-only assertions and methods: WARN content checks, healed-page-absent checks,
  companion-consistency-after-heal checks (price/unique/global/cardinality), the ChainIndex
  head-divergence-tolerated case, the RangeIndex sentinel-heal case (flip it to a throw case — the
  sentinel nuance still deserves a pin).
- The two boundary pins (equal-content twin, twin-after-superseder) stay; update expected messages
  if asserted textually.
- ADD assembler-level unit tests: feed overlapping single-leaf trees directly to each of the three
  `assembleFromSingleLeafTrees` variants and assert the throw — this is now the core unit surface;
  the per-structure tests confirm integration.
- ADD a flush-path test for 1.4 (corrupt tree must throw before pages are written; unit-testing the
  exposed `assertCrossLeafOrder` directly is acceptable if a full flush harness is disproportionate).
- `StaleLeafPageTwinWriterReproductionTest`: flip its reload expectation to the load-time throw. If
  its corruption synthesis goes through live tree operations it may already be caught by 1.4 at
  flush — in that case assert THAT (earlier is better) and keep one raw-storage-part variant that
  pins the load-time (1.1) throw independently.

### 1.6 Phase 1 acceptance criteria

1. All flipped/new tests green; no test asserts a heal anywhere.
2. `LeafPageTwinHealer` and all `PageProbe` implementations are gone; `rg -l "PageProbe|TwinHealer"
   evita_engine/src/main` returns nothing.
3. Acceptance suite `evita_functional_tests` `-Dgroups="indexing | filter | order"` fully green
   (baseline: 6991/0/1-skip per `RESULTS.md`); full-module run as belt-and-suspenders.
4. No production code path silently drops or rewrites a persisted page — every overlap/duplicate
   throws.

## Phase 2 — early detection tiers (separate commit/PR from Phase 1)

**Revision status (2026-07-15): revised after the Phase 1 review; NOT started. Prerequisite: the
Phase 1 work is committed on its own branch first (the pending branch-split) — Phase 2 stacks on
the committed fail-fast baseline as its own commit/PR.**

Rationale (agreed with the owner): evitaDB transactions run fully isolated in STM; the isolated diff
is discarded at commit and the WAL's logical mutations are replayed by the single-threaded trunk
coordinator. Detecting tree corruption in the isolated run therefore rejects the transaction BEFORE
its mutation sequence becomes durable — preventing a deterministic tree bug from becoming a
poison-pill WAL entry that re-corrupts on every restart.

### Tier A — structural-operation + boundary-mutation asserts (always on)

In the three transactional B+ trees, at every STRUCTURAL mutation (leaf split, merge, replace,
page-forget/supersede): assert O(1) local invariants — the affected leaf's boundaries against its
in-spine neighbors and the separator keys bracketing the touched slot. Throw
`GenericEvitaInternalError` immediately on violation.

**Extension (added by the 2026-07-15 review — this is what closes item 1.4):** additionally assert
boundary-changing PLAIN mutations. The concrete design is specified in `TIER_A_ADVISORY.md` (same
directory), which REFINES this paragraph and wins where they differ — in particular: only DOWNWARD
first-key moves and UPWARD last-key moves can violate cross-leaf order (removals need no order
check); the tail check uses the descent/cursor fence separator (complete across parent edges,
zero-alloc); the head check reads the same-parent left sibling's last key (O(1)) or, at `ci == 0`
only, the cross-parent predecessor leaf via a rare O(height) right-spine walk; a separator-order
belt lives in `updateParentKeys` but is NOT a substitute for the head check.
Rationale: a mis-routed insert (the classic search-path bug) corrupts key order WITHOUT any
structural op firing, so structural-only asserts would miss it. In transactional mode Tier B
catches that leaf (it is dirty), but warm-up mode has neither Tier B nor a WAL — without this
extension the first detector would be the load-time walk (1.1), i.e. AFTER the corruption became
durable. With it, every shape-changing path through a tree is asserted at op time, so by induction
a tree that only ever mutated through asserted ops cannot emit an overlapping page list at flush.
**Item 1.4 is therefore formally superseded — do not implement a separate flush-path validation.**

Constraints unchanged: zero allocation on the happy path, no full-tree walks; structural ops occur
once per ~block-size mutations, and the boundary-mutation check adds two comparisons on the touched
leaf only (sequential bulk inserts append at a boundary every time and still pay just those two).
These asserts fire in isolated STM runs, trunk replay AND warm-up mode alike. Expect the asserts to
be mirrored across the three trees — they are deliberate primitive/generic specializations, same as
the Phase 1 validation; there is no shared base-class seam to hang this on.

### Tier B — pre-WAL dirty-scope validation (kill-switchable, default on)

The concrete design for Tiers B and C is specified in `TIER_BC_ADVISORY.md` (same directory), which
REFINES this section and the Tier C section and wins where they differ — in particular: enumeration
comes from a maintainer-hosted dirty-leaf registry populated at the Tier A mutation seams
(registered objects are key sources only; validation always relocates by key); the Tier B hook is
the start of `TransactionWalFinalizer.commit(...)` with a mirror-rollback failure path (never a raw
throw); Tier C validates POST-merge at the end of each tree's
`createCopyWithMergedTransactionalMemory` with the poison-pill wording added by the trunk wrapper.

BEFORE the transaction's mutations are appended to the shared WAL: for every B+ tree carrying a
diff layer in the transaction's transactional memory, validate the MODIFIED leaves against their
immediate neighbors and bracketing separators — O(dirty leaves), two comparisons per leaf the
transaction already rewrote; full-tree walks are forbidden here. On failure the commit is rejected
(commit progress completes exceptionally), and the shared WAL verifiably does NOT contain the
transaction.

**Hook-placement caution (verified against the current tree, 2026-07-15):** the stage
`ConflictResolutionAndWalAppendingTransactionStage` exists, but by this proposal's own rationale
the isolated diff may already be discarded when that asynchronous stage runs — the stage may carry
only the logical mutation stream, not the live diff layers. If so, hook the validation at
TRANSACTION CLOSE (commit initiation), while the transactional memory is still alive; that still
runs strictly before the shared-WAL append. The GUARANTEE ("the WAL verifiably does not contain the
transaction"), not the stage, is normative; discovery latitude granted on the exact location.

**Savepoint interaction:** with the atomic-upsert savepoint architecture an object may carry a
STACK of diff layers, not a single one; enumerate the dirty leaves from the EFFECTIVE (merged)
state of the stack, not from one layer.

Kill switch: a system property following the `evita.collationKeyCache.size` naming pattern (e.g.
`evita.bPlusTree.preCommitValidation=false`), default enabled.

### Tier C — coordinator post-replay validation (always on)

In trunk incorporation (`TrunkIncorporationTransactionStage`), after a transaction's mutations are
replayed and the merged tree versions are produced, BEFORE the new catalog version propagates to
the live view: run the same dirty-scope validation on the trees modified by the replay (the replay
must track which trees it touched — discovery latitude granted; resolved by `TIER_BC_ADVISORY.md`:
the same registry, consumed post-merge inside each tree's merge method, message wrapped with the
poison-pill caveat in the trunk path). On failure complete the commit
progress exceptionally with a message that states the poison-pill caveat explicitly: the
transaction is already durable in the WAL, a restart will replay it, remediation is restore/rebuild
plus a bug report. This tier is the authoritative backstop for shape-dependent bugs that pass Tier
B (the isolated run mutates the session's snapshot; the replay mutates the possibly-different trunk
shape).

### Phase 2 tests

- Tier A structural: unit tests forcing an inconsistent structural op via package-private test
  hooks; assert the immediate throw.
- Tier A boundary-mutation extension: unit tests forcing a mis-routed insert/removal (a boundary
  key landing outside the bracketing separators) via the same hooks; assert the immediate throw.
  One test per tree.
- Tier B: an integration test where a (test-hook-corrupted) tree fails validation at commit — assert
  the client receives the exception, the WAL does not contain the transaction (inspect via the
  mutation supplier), the catalog remains healthy and accepts subsequent commits; plus a kill-switch
  test asserting the validation is skipped when disabled.
- Tier C: force corruption during replay via a test hook; assert exceptional commit progress with
  the poison-pill message.
- `StaleLeafPageTwinWriterReproductionTest` (bullet REVISED — the original was written before the
  Phase 1 outcome): Phase 1 deliberately left this test unchanged; it pins that the FIXED writer
  produces sound state (single-threaded warm-up seed sweep, 4/0) and contains NO corruption
  synthesis for the tiers to catch. It must simply STAY green. Corruption induction for tier tests
  happens exclusively via the test hooks above.

### Phase 2 acceptance criteria

1. Tier tests green; Phase 1 suites stay green (twin + tree suites 240/0, writer-repro 4/0);
   acceptance suite `-Dgroups="indexing | filter | order"` fully green (current baseline
   6993/0/1-skip).
2. Full-module `unitAndFunctional` run as belt-and-suspenders — since the client-session-cancellation
   stream landed this is now a USABLE gate (current baseline 20029 tests / 0F / 1 known pre-existing
   `ProgressRecordTest` flake that passes in isolation).
3. Demonstrated absence of measurable commit-latency regression from Tier B: compare a SINGLE-SHOT
   indexing-heavy run against a Tier-B-disabled run — not under `parallel=all`, where saturation
   noise swamps the signal; an order-of-magnitude sanity check suffices, no formal benchmark
   required.
4. Tier A/C are unconditional; only Tier B is gated.
5. Item 1.4's closure (superseded by the Tier A extension) is recorded in this stream's
   `RESULTS.md`.

## Phase 3 — reviewer findings on Phases 1–2 (small, separate commit)

Gaps and complaints from the independent review (2026-07-15). None of these block Phase 2; all of
them should land, ideally immediately after Phase 2 while the tree-validation context is warm.

### 3.1 Intra-leaf key order is validated nowhere at load

The 1.1 walk checks only CROSS-leaf boundary keys. A leaf whose INTERIOR keys are out of order —
a serializer bug, a truncated write, bit rot, or any future writer defect — passes the cross-leaf
walk untouched, while binary search inside that leaf silently returns wrong answers. Tier A guards
live mutations, not what deserialization read back from disk, so nothing covers this today. Close
it at the cheapest point: where a leaf's keys are materialized at load (inside the 1.1 walk, or
during page deserialization if the keys stream through there anyway), assert each key sorts
STRICTLY after its predecessor within the leaf — one comparison per key, zero allocation, O(total
keys) exactly once per load. Same `GenericEvitaInternalError`, diagnostics (structure description,
page sequence, both offending keys) and remediation hint as 1.1. Add one unit test per tree feeding
an intra-leaf-disordered page.

### 3.2 Rename the `HealedShapeBoundaryTest` nested test class

`StaleLeafPageTwinReproductionTest` (~:228) still carries a nested class named after the REMOVED
healing concept. Rename it to describe what it pins today — the fail-fast throw on the
strict-prefix-before twin shape (e.g. `StrictPrefixTwinBoundaryTest`). No logic change.

### 3.3 Record the 1.4 supersession trail in RESULTS.md

This stream's `RESULTS.md` still describes 1.4 as "deferred" with the original four-reason
rationale. Once the Tier A extension lands, update it to "superseded" with a pointer at §Tier A —
otherwise the next reader re-opens the deferral debate from a stale premise. (This is the
documentation half of Phase 2 acceptance criterion 5; it lives here so it is not forgotten if the
acceptance list is skimmed.)

## Ground rules (carried over from ASSIGNMENT.md)

TDD per change (failing test first); Maven via `rtk mvn ...`, never piped through grep/head
(`tail -N` or surefire `.txt` reports); targeted runs need `-Dtest.tag.policy=off`; after engine
signature changes run `rtk mvn -pl evita_engine install -DskipTests` if surefire resolves a stale
jar. Repo rules: tabs, JavaDoc on everything (Markdown, no HTML), no TODOs, no commented-out code,
no issue numbers or plan-doc references in code comments, `final` locals, no streams on hot paths,
defensive-design rule everywhere. Never open the preserved `data*` datasets except as copies.

## Out of scope / deferred

- TwinDetector rework into a raw-storage-part census reader (the loaded-index approach is moot; the
  load-time throw only reports the first twin). Deferred until a census is actually needed.
- The dataset evidence run (owner declined the 2.4 GB loads); under fail-fast-everywhere it no
  longer gates any decision.
- Any WAL-format or mutation-replay change: the WAL's logical mutations are valid by construction;
  corruption lives only in derived index state, which is exactly what the tiers validate.
