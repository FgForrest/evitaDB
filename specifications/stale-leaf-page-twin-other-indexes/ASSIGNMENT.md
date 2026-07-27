# Assignment — extend stale-leaf-page-twin healing to all paged index restore paths

Audience: the implementation session (any model). The audit is DONE — do not re-audit the restore
paths; the findings table below carries the file:line evidence. Read
`specifications/senesi-upsert-index-corruption/scenarios/bug-04-stale-leaf-page-twin.md` first for
the corruption anatomy, and study the shipped healing in
`evita_engine/src/main/java/io/evitadb/index/invertedIndex/InvertedIndex.java` (`fromPersistedPages`
:490, `resolveHealedPageIndices` :583, `isStrictPrefix` :652) — it is the template every fix below
adapts.

## Background (one paragraph)

A `PAGED` index persists one StoragePart per B+ tree leaf; the root part carries the ordered
leaf-page-sequence list and the reload path re-assembles one in-memory leaf per persisted page. The
senesi incident proved the persisted list can reference a **frozen stale snapshot of a leaf next to
the page that superseded it** (the "twin"): the writer race (bug-04, concurrent calls on one
`@NotThreadSafe` warm-up session) left a stale leaf clone reachable in the spine, and the one-shot
warm-up flush persisted both. The writer is now guarded (`EvitaSessionProxy`), but **already-damaged
catalogs exist in the field** — the load path must detect (and where provably safe, heal) the twin
for EVERY paged structure, not just the `InvertedIndex` where the fix landed first. The three
spine-assembly skeletons (`TransactionalBucketBPlusTree.assembleFromSingleLeafTrees` :1415,
`TransactionalLongBPlusTree` :406, `TransactionalElementBPlusTree` :296) perform **no cross-leaf
ordering validation whatsoever** — `buildSpine`/`buildInternalNode` take each leaf's
`leftBoundaryKeyOf` as separator verbatim — so a twin loads silently into all three tree types and
serves corrupt data until it crashes with a confusing signature far from the cause.

## Findings table (audit of 2026-07-14; all paths relative to `evita_engine/src/main/java/io/evitadb`)

| Structure | Writer exposure | Restore behavior on a twin | Verdict |
|---|---|---|---|
| `RangeIndex` (`index/range/RangeIndex.java:791`) | Same mechanism: path-copying `TransactionalLongBPlusTree` mutated per upsert of range-typed attributes, flushed per-leaf via `PageStreamRegistry` (`RANGE_PAGE_STREAM`, :814). Loaders: `AttributeIndexLoader.loadRangeIndex:440`, `HistogramIndexMapLoader:264`. | **Silent.** `TransactionalLongBPlusTree.assembleFromSingleLeafTrees:406` does no cross-leaf check; a twin duplicates threshold runs and their starts/ends bitmaps → wrong range-formula results, later removal-path sanity failures. | **VULNERABLE** |
| `ReferenceTypeCardinalityIndex` (`index/cardinality/ReferenceTypeCardinalityIndex.java:183`) | Same mechanism: bucket tree keyed by composed `long`, mutated per reference upsert, `CARDINALITY_PAGE_STREAM` (:208). Loader: `ReferenceTypeCardinalityLoader:93`. | **Silent.** `TransactionalBucketBPlusTree.assembleFromSingleLeafTrees:1415` does no cross-leaf check; duplicated keys in a unique-keyed tree, non-monotonic separators misroute lookups → wrong cardinality counts, wrong reduced-index lifecycle decisions. | **VULNERABLE** |
| `PriceListAndCurrencyPriceSuperIndex` (`index/price/PriceListAndCurrencyPriceSuperIndex.java:161`) | Same mechanism: `TransactionalElementBPlusTree` keyed by `internalPriceId`, mutated per price upsert, `PRICE_PAGE_STREAM` (:183). Loader: `PriceSuperIndexLoader:122`. | **Silent, and amplified:** the restore derives `entityPrices` / entity-id / price-id bitmaps from `tree.toArray()` (:188-202) — a twin feeds the SAME price record twice into `EntityPrices.addPriceRecord`, so the derived companion structures are corrupt too (the bitmaps dedup silently, hiding it). | **VULNERABLE** |
| `OwnerUniqueIndex` (`index/attribute/OwnerUniqueIndex.java:231`) | Same mechanism: bucket tree with single-record payloads, `UNIQUE_PAGE_STREAM` (:268). The bug-04 race census directly observed unique-tree corruption ("value already present in a unique long-payload bucket tree"). Loader: `AttributeIndexLoader:241`. | **Silent.** Same unguarded bucket-tree assembly; a duplicated value run in a UNIQUE tree misroutes equality probes once twins diverge → missed unique lookups, false "already present" violations. The derived `recordIds` bitmap (:248-262) dedups silently. | **VULNERABLE** |
| `GlobalUniqueIndex` (`index/attribute/GlobalUniqueIndex.java:269`) | Same mechanism, catalog-level (`UNIQUE_PAGE_STREAM`, :306); globally-unique attributes (e.g. `url`) are mutated on every upsert. Loader: `DefaultCatalogPersistenceService:1679` (evita_store_server). | **Silent.** Same unguarded assembly; `entitiesPerType` is rebuilt by unpacking every payload including the twin's (:297-301, bitmap dedup hides it). Catalog-wide blast radius: URL routing lookups miss. | **VULNERABLE** |
| `SortIndex` / `OwnerSortIndex` (`index/attribute/OwnerSortIndex.java:190`) | Value tree IS an `InvertedIndex` — same stream and flush as the filter tree. | **Already healed transitively:** `OwnerSortIndex.fromPersistedPages` delegates to `InvertedIndex.fromPersistedPages` (:205), which applies `resolveHealedPageIndices`; the positional `sortedRecords` façade is NOT persisted for a PAGED owner and is reconstructed from the (healed) tree (:210, `SortIndexView.reconstructSortedRecords`). The view-mode SortIndex shares the FilterIndex tree, healed at its own restore. The non-head-aware `TransactionalUnorderedIntArray` backing `sortedRecords` is non-paged (`TransactionalUnorderedIntArray.java:177-190`). | **NOT-APPLICABLE** (covered) |
| `ChainIndex` (`index/attribute/ChainIndex.java:338`) | Same mechanism: paged `UnorderedLookupTree` leaves (1024-record pages), `ELEMENTS_PAGE_STREAM` (:415); the bug-04 race census directly observed chain corruption ("Record with id X is not present in the array…", "Position N not found!"). Loader: `AttributeIndexLoader:589`. | **Silent, DIFFERENT SHAPE:** there is no ordering invariant to violate — pages are positional. `UnorderedLookupTree.assembleFromLeafPages:476` copies pages verbatim with no duplicate check; a twin's duplicated record ids silently overwrite the value index (`TransactionalUnorderedIntArray.accept:248` — "insert overwrites an existing mapping") while the physical array keeps BOTH copies. `size` counts both; predecessors/descriptors are computed over the duplicated stream (`ChainIndex:353-396`) → wrong chain lengths, later "Position N not found!" — exactly the confusing race-census signatures. | **VULNERABLE** (needs a duplicate-record check, not a comparator boundary check) |

Supporting fact: `PageStreamRegistry.restoredFrom` (`index/page/PageStreamRegistry.java:253`)
collects live pages into a `HashSet` — a duplicate page sequence would silently collapse, and the
observed twin carries DISTINCT sequences anyway, so the registry catches nothing.

## Ground rules

- **TDD**: for each structure write the failing twin test FIRST (see §Required tests), watch it
  fail with the documented silent-corruption behavior, then fix, then watch it pass.
- Maven via `rtk mvn ...`; never pipe its output through grep/head (use `tail -N` or read surefire
  `.txt` reports). Targeted runs need `-Dtest.tag.policy=off`. If surefire throws
  `NoSuchMethodError` after an engine signature change: `rtk mvn -pl evita_engine install -DskipTests`.
- Repo rules apply: JavaDoc on everything, no TODOs, no commented-out code, no issue numbers or
  plan-doc references in code comments, defensive-design rule (unknown corruption shape ⇒ throw
  `GenericEvitaInternalError`, never silently skip). Do NOT weaken the hard failure to make a
  dataset load.
- Never modify the preserved datasets in `/www/oss/evita/evitaDB-dev/data*` — opening a real
  catalog with branch code can trigger a one-way format migration, so ALWAYS copy a dataset to a
  scratch directory and run tools against the copy.
- Every heal WARN must fully identify the index (entity type / attribute or price-list key /
  reference name, the dropped and superseding page sequences, the boundary key) — mirror the
  message shape of `InvertedIndex.resolveHealedPageIndices:618-624`.

## Recommended fix approach

### 1. Extract the healing into a shared static helper

Project convention: **shared static helper, not a base class** (the `UniqueIndex` precedent —
single-inheritance wall). Suggested home: a new `public final` class (private constructor, static
methods only) in `io.evitadb.index.page` next to `PageStreamRegistry`, e.g. `LeafPageTwinHealer`.

The check needs, per page: its length, a cross-page key comparison, and a payload-identity
comparison. To serve `Serializable`-keyed buckets, `long`-keyed range points and
`int`-keyed price records without boxing, define a small accessor interface the helper walks:

```java
interface PageProbe {
	int pageCount();
	int pageLength(int page);
	int pageSequence(int page);
	/** compares key at (pageA, idxA) with key at (pageB, idxB) using the index's OWN order */
	int compareKeys(int pageA, int idxA, int pageB, int idxB);
	/** true when the full payloads at the two positions are identical (record-id sets, bitmaps, …) */
	boolean payloadsEqual(int pageA, int idxA, int pageB, int idxB);
	/** diagnostic rendering of the key for WARN / error messages */
	String describeKey(int page, int idx);
}
```

`resolveHealedPageIndices` moves into the helper generalized over the probe, preserving the exact
semantics of `InvertedIndex.resolveHealedPageIndices:583-640`: walk pages in list order, keep
empty pages unconditionally (they impose no boundary constraint — mirror :596-608), enforce
strictly-ascending cross-page boundaries, heal a provable strict-prefix-before twin in place with
a WARN, throw `GenericEvitaInternalError` on any other overlap (see §Healing-shape boundary — the
strict-prefix-before shape is the ONLY healed one, by design). `isStrictPrefix` adapts identically
(key equality via `compareKeys`, payload identity via `payloadsEqual`). Return the kept indices;
each call site filters its positionally-aligned arrays exactly as
`InvertedIndex.fromPersistedPages:511-524` does (reuse the caller's arrays when nothing was
dropped).

**Refactor `InvertedIndex` to delegate to the helper** so there is a single implementation; its
existing tests pin the behavior.

### 2. Apply at each vulnerable restore site

Insert the healing BEFORE the per-page trees are built, filtering both the sequences and the
per-page content arrays:

| Site | Key comparison | Payload identity |
|---|---|---|
| `RangeIndex.fromPersistedPages` (`RangeIndex.java:791`) | `long` threshold of `TransactionalRangePoint` | starts AND ends bitmaps equal. The `Long.MIN_VALUE`/`MAX_VALUE` border sentinels live in the first/last pages (:782-783) — a twin of the first page carries the sentinel in both copies, which the prefix check handles naturally; add a test for it. |
| `ReferenceTypeCardinalityIndex.fromPersistedPages` (`ReferenceTypeCardinalityIndex.java:183`) | composed `long` key | `long` cardinality count equal |
| `PriceListAndCurrencyPriceSuperIndex.fromPersistedPages` (`PriceListAndCurrencyPriceSuperIndex.java:161`) | `internalPriceId` of `PriceRecordContract` | full `PriceRecordContract` equality. Healing must run BEFORE the `entityPrices` / bitmap derivation (:188-202) so the companions are derived from the healed tree only. |
| `OwnerUniqueIndex.fromPersistedPages` (`OwnerUniqueIndex.java:231`) | attribute value via `comparatorFor(plainType)` (:246) | single `int` record id equal. Healing must run BEFORE `allRecordIds` accumulation (:248-262). |
| `GlobalUniqueIndex.fromPersistedPages` (`GlobalUniqueIndex.java:269`) | attribute value via `comparatorFor(plainType)` (:285) | packed `long` payload equal. Healing must run BEFORE the `entitiesPerType` unpacking loop (:288-302). |

### 3. ChainIndex — same disease, different healing shape

`ChainIndex.fromPersistedPages` (`ChainIndex.java:338`) concatenates positional pages; there is no
key order to violate, so the twin manifests as **duplicate record ids across pages**. Heal in
`ChainIndex.fromPersistedPages` before constructing the `TransactionalUnorderedIntArray` (:344-349):

- Scan all pages' `recordIds` against one accumulated bitmap (records are ints — O(N), cheap).
  No duplicates ⇒ nothing to do (the overwhelmingly common case).
- On a duplicate, apply the containment argument positionally: when a page's `recordIds` sequence
  is a **strict prefix** of its immediate successor's (the grow-only frozen-snapshot shape — the
  same anatomy as the senesi twin), drop the stale page with a WARN. The successor's head words are
  authoritative over the shared prefix (head state legitimately changes as chains split/merge, and
  chain state is recomputed from the persisted predecessors anyway — :385-396); note in the WARN
  when they diverge. Dropping the stale page reproduces the true adjacency: in the genuine tree
  only the successor leaf exists, and its left neighbour is the stale page's left neighbour.
- Any other duplicate shape (non-adjacent duplicate, partial overlap, reordered prefix) ⇒
  `GenericEvitaInternalError` naming the record id, both page sequences and the attribute identity.

Do NOT force this through the comparator-based helper — a small dedicated static method beside the
shared helper (or private in `ChainIndex`) is cleaner; share only the WARN/error message
conventions.

## Healing-shape boundary (InvertedIndex) — fail-fast decision

Two additional twin shapes exist in theory beyond the healed one: a byte-identical equal-length
twin (`isStrictPrefix`, `InvertedIndex.java:657-659`, requires the candidate to be strictly
shorter, so two identical copies throw today) and a twin that FOLLOWS its superseding page (the
walk at `resolveHealedPageIndices:612-636` only tests whether the KEPT page is a prefix of the
INCOMING one, so a trailing contained page throws today).

**Decision by the project owner: these stay UNHEALED, by design.** The observed, provably-redundant
**strict-prefix-before** shape remains the ONLY healed shape — in `InvertedIndex` AND in the shared
helper applied to every structure above. Every other overlap shape must keep failing fast with
`GenericEvitaInternalError`: healing shapes never observed in the wild would mask unknown
corruption mechanics. Do NOT generalize the containment check.

Mandated work instead:

1. **Evidence run.** Extend
   `evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/TwinDetector.java` — it
   currently walks ONLY `FilterIndex`-backed `InvertedIndex`es (`TwinDetector.java:55`, :149-151) —
   to also scan the range companions, both unique indexes, the price super-index element tree, the
   cardinality trees and the chain-index element pages (duplicate-record signal for the latter).
   Run it against COPIES of the preserved dirty datasets at the repo root
   (`data_dirty_after_fuzz_20260714/`, `data_dirty_bug03_spin_20260714/`) and classify every
   detected twin by shape: strict-prefix-before / equal-content / twin-after / partial-overlap.
   Record the classification in this directory. Expected outcome: only strict-prefix-before. **If
   any other shape is found, STOP and report back to the project owner** — the fail-fast decision
   would need revisiting, because a real damaged catalog would then refuse to load. Re-run the
   positive control (senesi pristine copy) — it must still report exactly the four known
   `published` twins and nothing else.
2. **Javadoc.** Extend the `resolveHealedPageIndices` / `isStrictPrefix` javadoc (which moves to
   the shared helper) to state explicitly that only the observed, provably-redundant
   strict-prefix-before shape is healed and that all other overlap shapes fail fast BY DESIGN. No
   issue numbers or spec-doc pointers in the javadoc (project rule).
3. **Error message.** Enrich the fail-fast `GenericEvitaInternalError` with a remediation hint for
   operators (restore the catalog from a backup, or rebuild the affected index), keeping the
   existing type / page-sequence / boundary-key diagnostics.
4. **Tests.** Add two expected-failure cases to `StaleLeafPageTwinReproductionTest` — (a) an
   equal-content twin, (b) a twin positioned after its superseding page — both asserting
   `GenericEvitaInternalError`, so the healed-set boundary is pinned intentionally.

## Required tests per structure

Model on
`evita_test/evita_functional_tests/src/test/java/io/evitadb/index/attribute/StaleLeafPageTwinReproductionTest.java`
(hand-crafted twin pages fed through the REAL `fromPersistedPages` path; senesi anatomy: a
128-entry stale twin next to a 190-entry successor whose first 128 entries are identical). For each
of `RangeIndex`, `ReferenceTypeCardinalityIndex`, `PriceListAndCurrencyPriceSuperIndex`,
`OwnerUniqueIndex`, `GlobalUniqueIndex`, `ChainIndex`:

1. **Heal test**: synthesize the persisted twin (stale strict-prefix page + superseding page),
   restore via `fromPersistedPages`, assert the index loads, the stale page is absent
   (`livePageSequences()` / leaf handles), iteration satisfies the structure's invariant
   (strictly-ascending keys, or no duplicate record ids for ChainIndex), every surviving record
   resolves, and derived companions are consistent (price bitmaps/`entityPrices`, unique
   `recordIds`, `entitiesPerType`, chain descriptors).
2. **Hard-failure test**: a diverged / partial overlap (same keys, different payloads — e.g.
   different record-id set, different cardinality count, different price record) must throw
   `GenericEvitaInternalError` at load, not assemble.
3. Structure-specific extras: RangeIndex sentinel-in-first-page twin; PriceSuper asserts no
   duplicated price record inside `EntityPrices`; ChainIndex asserts head-word divergence over the
   healed prefix is tolerated (successor wins) and the reconstructed chains match the successor
   state.

Place the tests next to the structures' existing unit tests in
`evita_test/evita_functional_tests` (tags consistent with `StaleLeafPageTwinReproductionTest`).
Delete nothing from the existing reproduction pair.

## Acceptance criteria

1. All new per-structure twin tests green, plus the two new expected-failure boundary pins in
   `StaleLeafPageTwinReproductionTest` (equal-content twin and twin-after-superseder both assert
   `GenericEvitaInternalError`; all its existing methods stay green).
2. `InvertedIndex` delegates to the shared helper with no behavior change (existing
   `StaleLeafPageTwinReproductionTest` + `InvertedIndex` unit tests green).
3. Extended `TwinDetector` positive control on a COPY of the senesi pristine snapshot still finds
   exactly the four known twins; its verdict on the two dirty fuzz datasets — including the
   per-twin shape classification — is recorded in this directory, and any shape other than
   strict-prefix-before was escalated to the project owner before implementation continued.
4. Full `evita_functional_tests` INDEXING/FILTER/SORT-tagged suites green
   (`rtk mvn -pl evita_test/evita_functional_tests test` with the project's standard tag profile);
   `evita_engine` unit tests green.
5. No production code path silently skips an unknown overlap shape — every non-provable overlap
   still throws `GenericEvitaInternalError`.
