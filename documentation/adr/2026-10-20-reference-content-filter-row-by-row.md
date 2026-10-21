---
title: The filter of referenceContent is evaluated row by row over the fetched owners' references, and its index route is deleted
date: 2026-10-20
updated: 2026-10-21 13:00
status: accepted
kind: refactor
issues: [1615]
prs: [1738]
areas:
  - evita_engine/src/main/java/io/evitadb/core/query/fetch
  - evita_engine/src/main/java/io/evitadb/core/query/filter
  - evita_engine/src/main/java/io/evitadb/core/query/filter/translator/reference
  - evita_engine/src/main/java/io/evitadb/core/query/filter/translator/attribute
  - evita_engine/src/main/java/io/evitadb/core/query/filter/translator/attribute/alternative
  - evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/reference
  - evita_engine/src/main/java/io/evitadb/index/attribute
supersedes: []
superseded-by: []
relates: [2026-10-21-one-locale-per-entity-level, 2026-10-20-single-owner-partitions-as-registry-rows, 2026-09-23-reference-decode-narrowing-by-referenced-key, 2026-09-28-indexed-reference-scope-requires-entity-component, 2026-09-17-row-scoped-reference-having-body, 2026-09-25-attribute-is-null-in-reference-having, 2026-10-08-scope-faithful-planning-and-facet-summary-parity]
---

# The filter of `referenceContent` is evaluated row by row over the fetched owners' references

`referenceContent(name, filterBy(...))` decides which references of the returned entities are fetched. It is now
decided **reference row by reference row, over the rows of the owners the query fetched**: the filter is compiled once
per fetch and reference into a small tree of row tests (`ReferenceRowFilterCompiler`), every existing row of every
fetched owner is judged by that tree alone, and an admitted row is recorded for its owner alone
(`ValidEntityToReferenceMapping#admit`). No reduced reference index, type index, group index or virtual partition is
read. The route that evaluated the same filter on those index structures - and everything only it reached - is deleted.
On a production retail catalog (160,216 products, 18 collections) the row route took 0.07x-0.81x the time of the index
route and allocated 0.03x-0.84x the memory in every measured case.

## Why

Three things came together in #1615:

- **The index route stands on the structures #1615 turns into registry rows.** A single-owner partition is a row of a
  per-reference registry, and an index object exists only while a partition has two or more owners
  ([2026-10-20-single-owner-partitions-as-registry-rows](2026-10-20-single-owner-partitions-as-registry-rows.md)). The
  index route had to evaluate every constraint of the filter twice - against index objects and, through
  `VirtualPartitionBodyEvaluator`, against registry rows - and keep the two answers equal for every constraint it
  admitted. That second evaluator already read the owners' bodies.
- **Its answer depended on what the scopes indexed.** An owner living in a scope that does not index the reference got
  the filter ignored or its duplicates hidden, a reference indexed in no queried scope was never validated at all, and a
  reference attribute had to be filterable or unique in every examined scope. A filter on data the query already holds
  was refused or silently skipped because of a schema flag meant for the main query.
- **The data in hand already answers the question.** A fetch decodes the owners' reference rows anyway
  ([2026-09-23-reference-decode-narrowing-by-referenced-key](2026-09-23-reference-decode-narrowing-by-referenced-key.md)
  keeps every row the filter could admit). The index route looked the verdict up per *referenced key* and then
  reconstructed a per-row verdict by restricting the key for every owner known to the fetch - which is how one owner's
  rows decided another owner's answer.

### Previous state

The fetch compiled nothing. `ReferencedEntityFetcher#getFilteredReferencedEntityIds` located, per examined scope, the
reduced entity index of each referenced key (the partitions of the owners' references), translated the filter against
it (index objects, plus the registry-row evaluator for single-owner partitions), collected the referenced primary keys
that passed, and `ValidEntityToReferenceMapping` narrowed those keys per owner (`restrictTo`, `forbidAll*`,
`RepresentativeMapping#unrestrictedKeysAllowed`). The planning check translated the filter through the same index
lookup. Known defects, all of one class - a verdict per key applied to every owner:

- one owner's rows decided another owner's answer (a root `groupHaving` judged per key and unioned over the owners of
  the page; an archived owner losing keys a live row was evaluated for; the answer varying with the page mates);
- duplicates of owners in a scope not indexing the reference were hidden, and a reference to a missing entity could not
  be hidden by `EXISTING` on a duplicate-cardinality reference;
- a root `scope(...)` of the filter raised `GenericEvitaInternalError` whenever a virtual candidate existed;
- a group-only filter on a schema without `REFERENCED_GROUP_ENTITY` passed planning and failed at fetch depending on
  the data.

The planning check was the weakest part: it refused shapes the fetch could evaluate (non-filterable attributes, a
reference not indexed in a scope, a missing entity or group component) and validated nothing for a reference indexed
nowhere, which is the default (`ReferenceIndexType.NONE`).

## Options considered

### Option A - judge every row of every fetched owner by itself (chosen)

The compiler turns the filter into a closed set of row tests; the fetch applies it to the raw rows of each owner. The
verdict of a row is a function of its owner's scope, the owner's locales, the row, the existence set and the nested
target sets, and of nothing else. A reference attribute leaf is built on the very normalizer and comparator of
`FilterIndex` (plus the range encoders of the `attributeBetween` / `attributeInRange` translators), so it answers what
the index would answer.

- **Pros:** no data-dependent answer; one evaluator for every owner; the planning check compiles with the same compiler
  and cannot drift from the fetch; far less work (see *Verification*).
- **Cons:** the owner must carry every row the filter could admit (a guard, below); a closed set of admitted constraints,
  everything else refused; user-visible changes (answered where it was refused, refused where it was ignored).

### Option B - keep the index route, adapted to registry rows (declined)

Leave the evaluation on index objects and make the registry-row evaluator carry what #1615 removes from them.

- **Pros:** no user-visible change; no new compiler; the measurement would not have been needed.
- **Cons:** two evaluators of one constraint set kept equal forever; the per-key reconstruction of a per-row verdict
  stays.
- **Rejected because:** the defects above are not bugs in either evaluator but in the join between them and the per-key
  mapping, so adapting the route moves them rather than removing them. The randomized parity sweep written to hold the
  two routes together reported 356-369 differences per 800 outcomes and about 1,400 per 3,000, every one of them in a
  decided class (a non-filterable attribute, a scope not indexing the reference, duplicates per row, a root
  `groupHaving`, the locale of a localized leaf, ...). That is how far the routes had drifted apart, and a route that
  needs a sweep that large to stay honest is the cost this decision removes. **Revisit if** a constraint is ever
  admitted in the filter whose answer cannot be a function of a row plus nested target sets (see *Decision*).

### Option C - keep the reductions already shipped and fix the defects in place (declined)

The first answer to "the logic is too complex" was a plan of behaviour-preserving cleanups of the index route
(dead branches, one flush per key, one comparator initialization, non-indexed scopes known before discovery, a named null
supplier, no per-reference body accessor). Six of them landed before this decision (`fb43cf6203`..`55fc057a22`). The two
that would have touched behaviour (a helper for the group-id call, and the decision on a root `groupHaving` and a
group-only filter without the group component) did not.

- **Pros:** each step safe on its own; nothing user-visible.
- **Cons:** every step is behaviour-preserving, so none can remove a defect that *is* the behaviour.
- **Rejected because:** the open choice was exactly the defects - `groupHaving` per key or per row, the group component
  required or not - and each answer on the table (route the root `groupHaving` through candidate discovery like a
  nested one, keep it per key and check each owner's own row, or only file the defect) either left the per-key mapping
  in place or moved a breaking change onto an architecture about to be replaced.

### Option D - a hybrid: index objects for indexed scopes, rows for the rest (declined)

Keep the index route where a scope indexes the reference and evaluate rows only where it does not (non-indexed scopes,
single-owner partitions).

- **Pros:** the index keeps whatever advantage it has on selective filters.
- **Cons:** the join of the two answers is where the defects sit; a filter would mean different things for owners of
  different scopes.
- **Rejected because:** it keeps "the answer depends on which scopes index what" - the U1/U2 problem - and the A/B
  found no case where the index route is faster: the largest BODY/INDEX ratio of any case in any round is 0.866.

### Decisions taken inside Option A

The labels in parentheses (R1, U2, Q1, ...) are the numbers the maintainer's decisions had in the design. They are kept
as tags only; every place states the decision itself.

| Fork | Chosen | Rejected because |
|---|---|---|
| Existence gate under `ANY` with a filter (R11) | **keep it**: a filtered row whose target does not exist in the examined scopes is not returned | dropping it returns bodiless rows (the body request inherits the request scopes) for live owners referencing archived targets - routine: the corpus of the #1615 record holds 40,344 archived products next to 130,033 live ones - and flips `not(entityHaving(f))` to TRUE for missing targets. **Revisit** as its own change with a docs change and an upgrade note |
| Scope of the filter: apply to all owners or only to owners of scopes indexing the reference (U1/U2) | **all scopes**; `inScope(S, ...)` confines it | #1048 (`649da332f9`) stated the ignoring as intent: the filter ignored for an owner living in a scope that does not index the reference, and the targets living in such a scope added unfiltered. Both are reversed on purpose (the maintainer, 2026-10-13): the second rested on the false premise that such rows are not indexed, the bypass held only where no partition of the target had been evaluated, so the answer depended on the data and on the page mates, and `inScope(LIVE, ...)` gives an archived owner the old reading. The refusal was incidental to the trait loop of `AttributeSchemaAccessor`, not a decision |
| Reference attribute leaves require `filterable`/`unique` (R1) | **no**, but they behave exactly as index filtering | a requirement that exists to guard an index lookup guards nothing when no index is read; the behaviour is kept identical so a user does not meet two meanings of one constraint |
| A constraint without a meaning on a row (R6), `scope(...)` below the root | **refused** with `EvitaInvalidUsageException`, at planning and at fetch | admitting `entityPrimaryKeyBetween`, `priceBetween`, `hierarchyWithin`, ... would keep their accidental owner-level reading; omitting a nested `scope(...)` from its boolean parent leaves `not(scope(...))` without a meaning to omit into |
| Locale of a localized leaf (Q1) | the locale of the filter's own `entityLocaleEquals`, found **exactly** as `findReferenceFilterLocale` finds it, else the owner query's | a dedicated finder that stops at `entityHaving` would split the leaves from the body fetch, the `EXISTING` gate and the ordering in exactly the shape `entityHaving(entityLocaleEquals(L))`. **Superseded by `2026-10-21-one-locale-per-entity-level`:** a leaf reads the reference level's own locale, else the owner's; an opinion inside `entityHaving` belongs to the referenced entity's level |
| Readings that depend on attribute flags | chosen **per owner scope** by that scope's own declaration (OQ-U) | one reading per leaf made a LIVE row's verdict change when ARCHIVED was added to the query |
| Sharing the leaf test with the main query's prefetch predicates | **yes** (flipped by the review of #1738, 2026-10-16): one `AttributeValueMatcher` per value constraint, created by its translator (`AttributeValueMatchingTranslator`) and asked by the prefetch alternative, the entity filtering of a collection-less query and the row filter alike | separate hand-written predicates per path - what shipped first. The four-arm parity class measured 41 of 184 cases diverging between the index, the prefetch, the entity filtering and the reference row (wrong answers and internal errors on the main query), and a new attribute constraint had a second and third place to be forgotten in. **Revisit** only if the matcher has to answer something a per-value test cannot |
| Invalid constraint values (an inverted scalar `attributeBetween`, a decimal the index key cannot encode) | **refused before any index is read, wherever the attribute schema is known**; a collection-less query refuses them per candidate entity type (Johnny, 2026-10-16: "agree - refuse") | a "do not refuse" mode that keeps today's data-dependent refusals: the index refuses only when it holds a value of the attribute, which the prefetch and the entity filtering cannot reproduce, and it keeps an answer that depends on the plan and the data - the thing this change removes. It costs one visible change: a query over an empty attribute is an error, not an empty result (upgrade note) |
| Which decimal probe is refused | one **beyond the `int` key of the attribute's indexed decimal places, when the attribute has a filter index in a requested scope** (`hasFilterIndexInScope`: filterable or unique) | refusing always - it would refuse probes on attributes no index holds, which entity filtering and the reference row compare as wide decimals; mirroring each scope's own unique-or-filter index choice - the answer would depend on the plan again; `isFilterableInScope` alone - a unique-only decimal attribute is index-encoded by the same normalizer and would keep leaking the raw `ArithmeticException` |
| A catalog attribute constraint of a query naming no collection (2026-10-17) | **entity filtering** over the entities the query finds; only `attributeEquals` / `attributeInSet` of an attribute unique globally in a requested scope keep the lookup in the catalog unique index | refusing the comparisons, intervals and string searches there - it removes a shape the entity filtering answers correctly; a filter index per globally unique attribute in `CatalogIndex` - a storage format change (a serializer with a backward-compatible reader, the bootstrap of existing catalogs) and memory for every unique attribute, for a shape nobody asked for. **Revisit** if collection-less range lookups become a measured workload |
| Completeness of the contract | a **load-time invariant** in the static initializer of `FilterByVisitor`, so the class fails to load and every query test fails | a unit test alone - it sees only what it enumerates, and a constraint added without the test is simply not checked; the descriptor registry as the enumeration - a constraint registered only for execution never appears there (Codex), so the check reads the execution registry itself |
| Decimal values on the reference row | the row keeps comparing **wide decimals** with no `int` limit - the one deliberate difference between the paths | a reference attribute need not be filterable or unique, so its stored values were never index-encoded; refusing a probe there would refuse what the data can answer. The refusal therefore lives in `assertIndexable` of the main-query wiring and must stay out of `createMatcher` |
| Recording only the groups of admitted rows on the unsorted paths | no, they stay keyed by referenced primary key | `isGroupAssignmentVarying` reads the same translator; the effect is extra group bodies, never a visible row |

## Decision

**Chosen: Option A.** The index route is deleted in the same pull request as #1615, after the A/B measured both routes
in one build.

The reasons are the drivers above: the route depended on index structures that no longer exist as objects, its answer
depended on schema flags that mean nothing to a fetch that holds the rows, and a per-row verdict reconstructed from
per-key results cannot be made right, only less wrong.

**What would have to change for the index route to win:** the fetch would have to stop holding the rows - for instance
if owners were fetched without their reference rows decoded - or the filter would have to admit a constraint that can
only be answered by an index (`facetHaving`, `referenceHaving`, `hierarchyWithin` on a reference row). Neither is on the
table. A measured case where the index route is faster (none of the eight was) would also reopen Option D.

## Key technical details

- **Entry points.** `ReferencedEntityFetcher#admitFetchedReferences` reads the owners' raw rows, applies the existence
  gate, the compiled `ReferenceRowFilter`, and records each admitted row. `ReferenceRowFilterCompiler#compile` builds the
  `RowTest` tree (`and`, `or`, `not`, `inScope`, the twelve attribute leaves, `entityPrimaryKeyInSet`,
  `entityLocaleEquals`, `entityHaving`, `groupHaving`; a root `scope(...)` only widens the examined scopes and produces
  no node). `NestedSet` holds the target sets of `entityHaving` /
  `groupHaving` (planned by `FilterByVisitor#getMatchingEntityPrimaryKeys`).
  `ValidEntityToReferenceMapping#admit` / `isReferenceSelected` and the sorted and unsorted slicers of `BitmapSlicer`
  consume the verdicts.
- **The translator owns the per-value test.** Eleven attribute value constraints (`attributeEquals`, `attributeInSet`,
  the four comparisons, `attributeBetween`, `attributeInRange` and the three string searches) have a translator that
  implements `AttributeValueMatchingTranslator`. `AttributeValueMatchingTranslator#createMatcher` returns the
  `AttributeValueMatcher` of the constraint over one attribute schema and index key, recording no capability;
  `#isAnsweredByUniqueIndex` is the operator's lookup policy (true for `attributeEquals` and `attributeInSet`) and picks
  which locales of a localized attribute the row filter reads; `#getIndexProbeValues` and `#assertIndexable` carry the
  decimal refusal; `#createMainQueryMatcher` is `assertIndexable` plus `createMatcher` for the main query;
  `#createAlternativeBitmapFilter` (prefetch, matcher created once at translation) and
  `#createEntityFilteringBitmapFilter` (collection-less, matcher created per candidate entity type) wire it into
  `AttributeBitmapFilter#matching`. The matcher lives in `core.query.filter.translator.attribute.alternative`, imports
  no translator and takes the thresholds of a range constraint already encoded by the translator.
  `ReferenceRowFilterCompiler` no longer names an attribute constraint: it looks the translator up with
  `FilterByVisitor#getTranslator` and calls `createMatcher`. `attributeIs` (is there any value at all) is the one
  attribute constraint outside the contract. Only that row dispatch is automatic: a translator's `translate` calls
  `createMainQueryMatcher`, `createAlternativeBitmapFilter` and `createEntityFilteringBitmapFilter` itself. Typed
  `attributeEquals` and `attributeInSet` are the exception: the unique or filter index answers them with no prefetch
  alternative, so their typed branch calls `assertIndexable` directly and only the collection-less branch uses the
  entity filtering factory. The entity filtering factory creates its matcher over the processing scope and the
  requested scopes of the moment the constraint was translated, not of the moment the filter runs (`inScope` has long
  ended by then).
- **Forgetting the contract fails every test run.** `FilterByVisitor#verifyAttributeTranslators` runs at the end of the
  static initializer and throws if an attribute constraint is registered with a translator that is not an
  `AttributeValueMatchingTranslator`, except those in `ATTRIBUTE_CONSTRAINTS_WITHOUT_VALUE_MATCHER`
  (`{AttributeIs}`). A load failure of the class, not a failing check someone has to run.
- **The planning check compiles the same filter** (`ReferencedEntityFetcher#verifyReferenceContentConstraints`,
  called from `ReferenceContentTranslator`) with a constraint-check visitor and throws the result away. In check mode `NestedSet`
  resolves its nested query eagerly, so nested filters are validated and their capabilities counted whatever the data;
  in a fetch it is lazy and restricted to the targets and groups of the rows tested. A nested level is planned in the
  locale its fetch uses (`ReferenceContentTranslator#getOwnerLocale` folds
  `ReferencedEntityFetcher#getReferencedEntityLocale` over the enclosing `referenceContent` requirements, outermost
  first - the level-2 test pins that order).
- **The owner must carry every row** (`getOwnerCarryingEveryRow`). Rows are read off the raw entity, never off the
  decorator, which hides names, rows and attributes the request did not ask for. A `ServerEntityDecorator` whose
  reference predicate covers the name carries every row that matters; one that requires no references is accepted on
  call order alone, so a caller must hand over the output of `EntityCollection#ensureReferencesFetched`. Anything else
  is a `GenericEvitaInternalError`, not a filter that silently sees no rows.
- **Existence gate (R11 a).** With a filter, or under `EXISTING`, and a managed referenced type, a row is tested only
  if its target exists in the examined scopes (and in the required locale for a localized target schema). It applies to
  owners of every scope.
- **Per-owner-scope readings.** A localized attribute is bound to the locale in scope `S` iff it is not unique there or
  unique within a locale. `equals` / `inSet` / `is` read the leaf locale when bound and any locale when not; each owner
  scope uses its own declaration. A scope outside the examined ones can occur - an owner archived, moved or enriched by
  a request reading another scope - so the compile context carries the owners' scopes too.
- **A `BigDecimal` is compared as the HALF_UP-scaled value** of `NumberUtils.scaleToInteger`: the index's int encoding
  without its final overflow, identical wherever the int fits. `attributeBetween(from > to)` on a scalar is refused at
  compile with the index's own message - and the main query now refuses it too, before any index is read, together
  with a decimal value the attribute's `int` index key cannot encode (`assertIndexable`; the reference row does not
  apply that second refusal).
- **No representative keys without a row-level verdict.** The mapping builds `RepresentativeReferenceKey`s only for a
  duplicate-cardinality reference with a filter; otherwise the verdict is a function of the referenced primary key.
- **Slicing is by row where rows can share a key.** The unsorted slicer pages the selected rows, not the distinct keys,
  when several rows may share a key; the sorted one filters by row before sorting and slicing, so a rejected duplicate
  never takes the slot of an admitted one.

## Verification

Tests that guard it. The full functional suite after the code-quality pass ran 28,835 tests; its only 12 failures are the
environmental ones every run of this branch has (`EvitaServerTest` misses a certificate key absent from the test environment,
`ExportS3ServiceTest` needs Docker). The whole reactor test-compiles.

- `ReferenceContentRowFilterFunctionalTest` (nested `VerdictsPerRow`, `Readings`, `RefusalsAtPlanning`,
  `OwnersOutsideRequestScopes`) - per-row verdicts, the locale reading incl. level 2, every refusal at planning on an
  owner with no rows and on no owner, and owners outside the request scopes.
- `ReferenceContentCrossScopeRowsTest` - the hand-written cross-scope expectations that
  `VirtualReferenceContentFilterParityTest` carried: singletons and duplicates in two scopes, a root `scope(...)`
  over a single-owner partition, Czech brand order with and without a query locale and under `inScope`.
- `ReferenceRowFilterCompilerTest`, `AttributeValueMatcherTest` (moved next to the matcher; every leaf pinned against a
  real `FilterIndex` / `RangeIndex` loaded with the same values), `ValidEntityToReferenceMappingTest`,
  `BitmapSlicerTest` (page and strip, sorted and unsorted, duplicates).
- `RequestedCapabilityAccumulationTest` - row leaves count no `FILTERABLE` / `INDEXED`; nested filters still count on
  their own collections, with and without a row.
- `EvitaArchivingTest.MultiScopeQueryingTest`, `ReferenceWithoutEntityComponentQueryGuardFunctionalTest.Allowed`,
  `AbstractEntityByFacetFilteringFunctionalTest` - the three refusals that became answers (U2, R3, R1).

The review round of 2026-10-16 (the matcher shared with the main query) is guarded by:

- `AttributeConstraintPathParityFunctionalTest` - the index, the prefetch alternative, the entity filtering and the
  reference row must answer alike: 311 four-arm cases (326 tests with the 15 plain ones) over the value families of
  `EvitaDataTypes` (scalars, arrays, numeric and date-time ranges, array probes), a localized `String` attribute read in
  Czech (collation order where "ch" follows "h", NFC and NFD accents, comparisons and searches), a `BigDecimal`
  attribute of the catalog and a unique-only one, and the named decimal deviation of the reference row. What the gate
  asserts: every case names the answer of all four arms - either "selects some owners but not all, no arm answers an
  error" or the exact outcome (the refusal text, `[]`, the set) - so four identical refusals cannot pass a case that must
  select and a set cannot pass a case that must refuse; the index arm must not have prefetched (except `attributeEquals`
  and `attributeInSet` over the catalog attribute, whose unique-index lookup fetches the bodies it resolves); the
  prefetch arm must
  have prefetched for every constraint with a `SelectionFormula` alternative (`SelectionFormula#computeInternal` answers
  from the alternative when the query is a prefetch execution, so the `EXECUTION_PREFETCH` telemetry step is the
  evidence). Plain tests feed the assertion outcomes that must fail - a prefetch arm that did not prefetch, an index
  arm that did, four identical errors on a case that must select, a vacuous selection (none, all), an expected refusal
  answered with a set - plus a positive control. Tests of the refusals and boundaries sit beside it
  (`shouldRefuseDecimalBeyondIndexKeyExceptOnReferenceRow`, the same on the catalog and the unique-only attribute,
  `shouldAnswerDecimalOfTheLargestIndexKey`, `shouldNotRefuseDecimalOfAttributeNoIndexHolds`,
  `shouldMatchEveryTypeAtItsOwnDecimalPlaces` - two types holding the attribute at two and at no decimal places, where
  one matcher serving both gives a different answer, `shouldRefuseInvertedIntervalOfCollectionWithoutEntity`,
  `shouldRefuseInvertedIntervalOnEntityFilteringPerCandidateType`). Before the change 41 of the first 184 cases were
  red (22 prefetch answers wrong or an internal `ClassCastException`, 18 entity-filtering ones, one row-only refusal);
  every one now agrees with the index.
  **Limits of the gate.** A `referenceHaving` leaf never runs a prefetch alternative (its nested processing scope reads
  indexes only), so it has no arm here; the row arm tests the `referenceContent` filter. Typed `attributeEquals` and
  `attributeInSet` have no prefetch alternative - the engine reads the index for them even when the bodies are
  prefetched - so the prefetch arm of their cases repeats the index arm (the case names say so) and tests no per-value
  path; their per-value path is the entity filtering arm and the row arm. The comparisons of the catalog attribute
  are held to one answer on all four arms by `shouldCompareCatalogDecimalAttributeOnEveryPath`.
- `CatalogAttributeWithoutCollectionFunctionalTest` - 36 tests: every value constraint over four catalog attributes
  (unique globally `String` and `BigDecimal`, filterable only, unique within a collection) on two collections answers
  the union of the per-collection answers in a query naming no collection; such a query with nothing to find its
  entities by is refused with `EntityCollectionRequiredException`. Before the fix 32 of the 36 were red, every one
  answering `[]`; the 4 green ones are `attributeEquals` / `attributeInSet` of the globally unique attributes.
  Since 2026-10-19 the class also holds six `inScope` containers over both scopes to the same union (all six answered
  `[]` without a collection before the `inScope` fix), and pins that the entity filtering factory judges a decimal by
  the scopes the constraint was translated in: inside `inScope(ARCHIVED, ...)` a decimal the live index cannot encode
  is answered, inside `inScope(LIVE, ...)` it is refused. These two replaced a Mockito test of the translator, written
  while no client query reached the difference.
- `FilterByVisitorAttributeTranslatorsTest` - the real registry passes the load-time invariant, a registry with a
  translator lacking the contract fails it naming constraint and contract, `attributeIs` is the one exception, and every
  described filter constraint has a translator or is read by its parent.
- `AttributeBitmapFilterTest` (rewritten on the matchers the translators create; 17 tests),
  `FilterIndexTest#shouldNotRebuildBigDecimalRangeRetainingTheIndexedScale`.
- Touched-area runs after the change: 5,898 tests over `*Attribute*Test`, `*Reference*Test`, `*Fetch*Test` and
  `*Prefetch*Test` (0 failures, 5 skipped) and 1,097 over the histogram, `FilterIndex`, `FilterByVisitor`, `groupHaving`
  and related classes (0 failures, 1 skipped).
- The full functional suite after the change: 29,133 tests; the 12 environmental failures above, plus two
  change-data-capture subscriber timing failures of `EvitaClientReadWriteTest` that pass when the class runs alone
  (69 tests). The whole reactor test-compiles.
- Counterfactuals, each run and watched fail: the load-time invariant made to accept everything (1 of 6 invariant tests
  fails); the prefetch wiring ignoring the matcher (12 of 17 `AttributeBitmapFilterTest` and 216 of 288 parity tests);
  the decimal refusal disabled (2 parity tests); the eager matcher of the typed `attributeBetween` disabled (3
  failures); and, against the gate of the second review round, the prefetch mark stripped in the assertion (both
  negative guards for it fail), the prefetch arm not steering onto prefetching (210 of 326 parity tests, 209 of them on
  the path evidence), one matcher serving every type of the entity filtering (2 tests, among them the two-scale one)
  and the entity filtering factory reading the scopes at the run (the Mockito test of the translator; on 2026-10-19
  rerun on its replacement, `CatalogAttributeWithoutCollectionFunctionalTest#shouldAnswerDecimalBeyondIndexKeyInScopeWithoutIndex`,
  which fails with the refusal).

Counterfactuals of the first round, each run and watched fail: eager nested resolution in check mode made lazy
(15 failures); the owner locale chain replaced by the top locale (the level-2 test); the leaves requiring `FILTERABLE`
(27 failures); the leaves recording `FILTERABLE` / `INDEXED` (4 failures); the check skipped (19 failures); the owner
scopes left out of the compile context (the `OwnersOutsideRequestScopes` tests and a compiler test); the slicer always
slicing by key (both page tests).

**The sweep that held the routes together is deleted.** `ReferenceContentRouteParityTest` generated filters over a
fixture with duplicates told apart by an attribute, dropped groups and owners in both scopes, fetched each as rows, pages,
strips (with and without an ordering), named sets, a nested level, an enrichment and `getEntity`, and compared both
routes with an independent oracle; a run had to meet every situation that makes it discriminate. It was deleted with the
route it compared. Besides the differences above it found **three defects that existed on both routes**, each fixed with
its own test:

- a strip starting past the last row sliced from the last row while the decorator starts at the first
  (`dd768dea2a`);
- a row whose group was dropped still carried that group's body through `getGroupEntity()` (`3c03198702`);
- an owner whose duplicates of one referenced entity sit in two groups lost the second group from the groups the
  unsorted slicer records, so its body was never fetched (`196dbe547c`).

A fourth, found by review and confirmed on both routes, was the unsorted page of a duplicate-cardinality reference
paging distinct keys while the decorator pages rows (`deeecc635c`).

A code-quality pass over the finished line found one defect of the row route alone: the compiler checked `inScope`
against the scopes of the request, so `filterBy(scope(ARCHIVED), inScope(ARCHIVED, ...))` was refused in a query of
`LIVE`, which the deleted route - running the filter in the examined scopes - answered. The check now takes the scopes
the container is compiled in (`ReferenceRowFilterCompilerTest#shouldAcceptInScopeOfScopeNamedByRootScope`,
`ReferenceContentRowFilterFunctionalTest#shouldAnswerInScopeOfScopeNamedByRootScope`, both watched fail with the old
check).

**Performance** (JMH `-bm avgt -f 1 -wi 5 -w 2s -i 10 -r 2s -prof gc`; one jar for both arms, the route set as the first
statement of each fork; `-Xmx24g`, result cache off; the production retail catalog above, migrated by `Migration_2026_3` on open;
5 rounds interleaved INDEX BODY | BODY INDEX; each run started quiet, mean busy 22.6-29.3 % of 24 CPUs; route identity
proven in all 80 forks by a filter on a non-filterable reference attribute, refused by INDEX in 8/8 forks and answered by
BODY in 8/8). Ratio = BODY / INDEX, geometric mean over 5 rounds (min-max):

| case | INDEX us/op | BODY us/op | time ratio | alloc ratio | INDEX B/op | BODY B/op |
|---|---:|---:|---:|---:|---:|---:|
| unfiltered fetch of 100 products (control) | 2,466-2,812 | 2,361-2,956 | 0.984 (0.906-1.053) | 0.787 | 4.81 M | 3.79 M |
| detail of one product, nested `entityHaving(referenceHaving(...))` | 694-826 | 570-644 | 0.806 (0.731-0.863) | 0.837 | 1.54 M | 1.29 M |
| page of 20 products | 1,121-1,273 | 862-1,034 | 0.805 (0.711-0.866) | 0.544 | 2.89 M | 1.57 M |
| page of 100 products | 4,261-4,971 | 2,228-2,554 | 0.521 (0.473-0.599) | 0.148 | 23.4 M | 3.47 M |
| page of 100, `scope(LIVE, ARCHIVED)`, filter in `inScope(LIVE, ...)` | 5,649-7,374 | 2,608-2,975 | 0.442 (0.354-0.491) | 0.091 | 36.5 M | 3.34 M |
| page of 1,000 products | 54,023-64,942 | 15,666-17,438 | 0.275 (0.241-0.310) | 0.028 | 786 M | 22.0 M |
| duplicate references, strip of 20 | 190-218 | 35-41 | 0.195 (0.178-0.215) | 0.060 | 1.72 M | 0.10 M |
| owners with 72,868 rows, strip of 20 | 101,042-110,340 | 7,015-7,922 | 0.071 (0.064-0.075) | 0.399 | 39.0 M | 15.5 M |

The noise floor is the control's time spread, 9.4 %; every case moved the same way in all five rounds and by more than
that. Result shapes were identical in every case and round except duplicates: the index route attached 159 referenced
bodies to 160 references, the row route 160 - most likely the unsorted paging defect above, not proven.

Two things about the table. The control is not a same-path control: an unfiltered `referenceContent` also took the index
route's key collection, so its 0.787 allocation ratio (stable to 0.3 %) is a real saving of the row route at equal time,
and the allocation column measures the whole reference fetch, not the filter alone. The heaviest case filters on the
referenced entity, not a reference attribute: the only filterable scalar reference attribute there holds one value on
all rows, so the harness used a boolean attribute of the referenced entity matching 7.7 % of the rows. The rig and the
route switch are gone with the route, so the table is not reproducible from the tree.

**Performance of the matcher on the main query** (the review round of 2026-10-16). JMH `-bm avgt -f 1 -wi 5 -w 2s -i 5
-r 2s -prof gc`, `-Xmx8g`, result cache off; a generated collection of 20,000 products from a fixed seed. Arm A is
`29d0e8f5be` (the hand-written predicates), arm B `d05d22e944` (the matcher); one jar per arm from one harness source.
Three rounds interleaved A B | B A | A B, each arm-run started after three 20 s windows at most 15 % busy (measured
3.3-6.1 % of 24 CPUs). 36 points: nine constraint shapes (decimal `lessThan` and `between`, integer and integer-array
`greaterThan`, `inRange` on a decimal range stored at the indexed scale and without it, range `between`, `contains` and
`startsWith` on Czech text) x the prefetch alternative (`entityPrimaryKeyInSet` of the candidates with
`debug(PREFER_PREFETCHING)`) or the entity filtering (no collection, `attributeInSet` of the candidates' codes) x 100 or
1,000 candidates. A fixture check of each jar proved every point selects some but not all candidates (25-53 %), every
prefetch point prefetched, and both arms selected identical sets. Ratio = B / A of the round means:

| path | points | time ratio, geometric mean (min-max) | allocation ratio | B faster / indistinguishable / B slower |
|---|---:|---:|---:|---|
| prefetch alternative | 18 | 1.001 (0.934-1.030) | 0.992 | 2 / 15 / 1 |
| entity filtering | 18 | 0.991 (0.945-1.017) | 0.991 | 3 / 14 / 1 |
| all | 36 | 0.996 | 0.992 | 5 / 29 / 2 |

"Indistinguishable" means the round means of the arms overlap. Integer-array comparisons got faster on both paths
(time 0.934-0.968, allocation 0.961-0.979). Of the two points slower in all three rounds, integer `greaterThan` over 100
candidates on entity filtering is 1.015; `startsWith` over 1,000 candidates on the prefetch path was 1.030 but did not
reproduce in a five-round confirmation of the four string-search prefetch points (1.006 there, all four
indistinguishable, geometric mean 1.010). The work the matcher adds per value - NFD normalization of the strings, the
rebuild of a decimal range stored without the indexed scale - is not measurable at this size (the `contains`,
`startsWith` and unscaled `inRange` points are indistinguishable or faster).

## Consequences & open follow-ups

### What a client sees (breaking; upgrade note on #1615)

**Answered where it was refused:**

- a filter on a reference attribute that is not filterable or unique (R1);
- an attribute leaf over a scope that does not index the reference, e.g. `scope(LIVE, ARCHIVED)` over a reference
  indexed in `LIVE` only (U2, was `ReferenceNotIndexedException`);
- a filtered `referenceContent` on a reference without `REFERENCED_ENTITY`, and `groupHaving` without
  `REFERENCED_GROUP_ENTITY` (R3, R4);
- a locale-less query with a filter that names its locale (Q1), and an attribute filterable in only one of two scopes
  (OQ-U).

**Refused where it was ignored or passed:**

- a filter on a reference indexed in no queried scope, the default, is applied and validated: a wrong attribute, a
  missing locale or a disallowed constraint now fails (U1);
- constraints without a meaning on a row, and a `scope(...)` below the root, whatever the data (R6);
- `attributeBetween(from > to)` on a scalar whatever the data (R10);
- a nameless `referenceContent(filterBy(attribute...))` fails when **any** reference of the schema lacks the attribute,
  where the index route failed only where a partition held data;
- nested `entityHaving` / `groupHaving` filters of a reference not indexed in a queried scope, which the old check never
  translated.

**Answered differently:**

- the filter is applied to the rows of owners in every scope; `inScope(S, ...)` confines it (U1);
- a root `groupHaving` beside other constraints is judged per row, not per referenced key (R5);
- a localized reference attribute leaf reads the locale of the filter's own `entityLocaleEquals`, else the query's (Q1);
- a dropped group is no group, and a dropped attribute value is no value.

Reference attribute leaves of a `referenceContent` filter count no `FILTERABLE` / `INDEXED` capability in the
statistics (R7); nested filters keep counting on their own collections. GraphQL and REST keep offering only unique and
filterable reference attributes in the filter (Q4); the wider acceptance is available to EvitaQL, the Java API and gRPC.

### Main query changes of the matcher review (breaking; upgrade note on #1615)

- an inverted scalar `attributeBetween` is refused whenever the attribute schema is known, also over an empty attribute
  or collection (it used to be refused only where an index held a value); a collection-less query refuses it per
  candidate entity type, and answers an empty result when there is no candidate;
- a `BigDecimal` value of `attributeEquals`, `attributeInSet`, the four comparisons or a scalar `attributeBetween`
  bound that the `int` index key of the attribute cannot encode is refused with an `EvitaInvalidUsageException`
  (it was an internal error carrying the index's `ArithmeticException`), where the attribute has a filter index in a
  requested scope;
- an inverted `attributeBetween` over a range attribute matches nothing on every path, entity filtering included;
- the prefetch and the entity filtering answer as the index: `attributeBetween` with bounds of another numeric type or
  over a `LocalDateTime`, decimal ranges in the rounding window and integer `attributeInRange` probes on decimal ranges
  no longer give an internal error or a wrong answer; a collection-less `attributeInSet` with several values works;
- a `String[]` value of a scalar `attributeEquals` matches nothing on every path (the index stringifies the array), it
  no longer means "any of its elements" on the entity filtering;
- a collection-less query applies every entity-filtering constraint it holds (only the first one took effect);
- a collection-less query answers a comparison, `attributeBetween` or a string search on a catalog attribute, and
  `attributeEquals` / `attributeInSet` on a catalog attribute that is not unique globally, on the entities it finds
  (it answered `[]`: the catalog index it targets keeps no filter index); with nothing else to find its entities by it
  is refused with `EntityCollectionRequiredException`, as for an attribute of a collection;
- an `inScope` container of a collection-less query restricts the entities of its scope and leaves those of the other
  requested scopes alone (it answered `[]`: the entities of a scope were read from entity indexes, and the catalog
  index the query targets is none).

### Parked, not filed

- **`applyOnFirstUniqueIndex` skips a scope where the attribute is only filterable** (suspected): `attributeEquals` on
  an attribute unique in `LIVE` and filterable in both never matches `ARCHIVED` entities in the main query. The row
  route reads such a scope by its own declaration; the main query keeps its reading. The same holds without a
  collection: `attributeEquals` / `attributeInSet` on a catalog attribute unique globally in one requested scope only
  are answered from the unique index of that scope alone (read from the code, not tested). The first shape is built
  only through raw schema mutations: the schema builder refuses an attribute filterable in any scope and unique in any
  scope (`AbstractAttributeSchemaBuilder`, "cannot be both unique and filterable"),
  `SetAttributeSchemaFilterableMutation` and `SetAttributeSchemaUniqueMutation` do not check it, and the per-owner-scope
  reading of the row route (OQ-U) is tested through them. Of the same family and never tested: the entity filtering
  reads the value of `attributeEquals` / `attributeInSet` in the query locale, while the unique index of a localized
  attribute unique across locales finds it in any locale.
- **A nested query ignores its own filter's `entityLocaleEquals`** and reads the parent's locale (suspected).
- **A root `scope(...)` in the reference filter widens the examined scopes** so targets in the added scope pass the
  existence gate, but the body request keeps the request scopes, so those targets come back without bodies (suspected;
  no test asserts body presence).
- **`EXISTING` means "exists in the examined scopes"**; whether it should mean "exists in any scope" is a separate
  question.
- **Dropped groups are counted** by the slicer and comparator group translators (`ReferenceMapping`).
- **`getEntity(..., {ARCHIVED})` on a collection without an archive index returns the LIVE entity** (found while fixing
  owners outside the request scopes; left alone).
- **Range indexes of partitions sharing a border** differ between the routes' oracles (known issue #1699); the sweep that
  met it is deleted.
- **The trait check of the prefetch and entity-filtering filters reads the scopes of the moment the filter runs**
  (suspected, from the code; raised by the Codex review of 2026-10-16 and left alone as older than the matcher round).
  `AttributeBitmapFilter#filter` resolves the attribute schema through `ProcessingScope#getAttributeSchema` with the
  traits the translator passed (`FILTERABLE` for `attributeBetween` and `attributeIs`), and that lookup reads
  `ProcessingScope#getScopes` when the filter runs - after `inScope` has ended. The matcher is created over the scopes
  of translation; this check is not. Since the `inScope` fix of 2026-10-19, a collection-less query over
  `scope(LIVE, ARCHIVED)` with `inScope(ARCHIVED, attributeBetween(a, ...))` on an attribute filterable in `ARCHIVED`
  only may be refused with `AttributeNotFilterableException`, and so may the prefetch path of a query naming its
  collection (`FilterByVisitor#isPrefetchPossible` stays true inside `inScope`) - a refusal that depends on the plan.
  Not run.
- **Array values of scalar `String` constraints** are compared as the array's identity string
  (`[Ljava.lang.String;@...`) on every path: the index normalizer stringifies any value that is not a `String`
  (`FilterIndex#getNormalizer`) and the matcher follows it. `attributeEquals` therefore matches nothing (see *Main
  query changes of the matcher review*), but `attributeGreaterThan(a, new String[]{"bar"})` selects every value
  starting with a letter - `AttributeConstraintPathParityFunctionalTest` pins that answer - and an `attributeBetween`
  with array bounds depends on identity hash codes. An `Integer[]` value is refused (`UnsupportedDataTypeException`).
  Refusing a `String[]` value too is the likely fix; it changes a public answer.

### Traps for a later change

- A new attribute constraint's translator must implement `AttributeValueMatchingTranslator`, or be added to
  `ATTRIBUTE_CONSTRAINTS_WITHOUT_VALUE_MATCHER` with a reason - otherwise `FilterByVisitor` fails to load. Only the
  reference row dispatch is automatic. Its `translate` creates the matcher eagerly with `createMainQueryMatcher` where
  the schema is known (that is where an invalid value is refused), passes it to `createAlternativeBitmapFilter` with the
  traits the constraint passes today, uses `createEntityFilteringBitmapFilter` on the collection-less branch - a catalog
  attribute included: the catalog index answers only a globally unique lookup, so only `attributeEquals` /
  `attributeInSet` may take the index branch there (`AbstractAttributeTranslator#isTypedLookupPossible`) - and
  overrides `getIndexProbeValues` when it probes the index with decimal values. A constraint the index answers with no
  prefetch alternative (as `attributeEquals` / `attributeInSet`) calls `assertIndexable` itself instead of the eager
  matcher. The load-time invariant enforces the
  contract, not the wiring; the parity class holds the semantics, so its cases belong in
  `AttributeConstraintPathParityFunctionalTest`.
- The decimal refusal must never move into `createMatcher`: the reference row would start refusing the wide decimals it
  compares on purpose. It belongs to `assertIndexable` of the main-query wiring.
- The traits of the prefetch filters are passed per translator on purpose (only `attributeBetween` passes `FILTERABLE`):
  a uniform list would change the capability statistics.
- `getOwnerCarryingEveryRow` fails loudly for an owner whose reference predicate may lack the name. A caller that builds
  owners without `ensureReferencesFetched` will hit it, and weakening the guard turns it into rows that silently
  disappear.
- The level-n locale depends on `getEntityContentRequireChain` returning outermost first.
- The compile context holds the scopes of the owners, not only of the request: remove them and an archived owner fetched
  by `archiveEntity`, `enrichEntity` or `upsertAndFetchEntity` fails an unconfined attribute leaf with an internal error.
  Those scopes widen only the readings of the attribute leaves. An `inScope` may name only the scopes it is compiled
  in - the examined scopes (the request and the root `scope(...)`), or the scope of an enclosing `inScope` - checking
  it against the owner scopes would admit a scope the query never asked for, checking it against the request alone
  refuses one the filter itself added.
- `FilterByVisitor`, `HavingTranslatorHelper` and `ProcessingScope` lost the nested restriction and comparator members
  the index route fed them; `referenceHaving` and the main query never used them.

## Related work

- [2026-10-20-single-owner-partitions-as-registry-rows](2026-10-20-single-owner-partitions-as-registry-rows.md) - the
  record whose registry rows made the index route a double evaluator; this one lands in the same pull request.
- [2026-09-23-reference-decode-narrowing-by-referenced-key](2026-09-23-reference-decode-narrowing-by-referenced-key.md) -
  the decode narrowing that guarantees an owner carries every row the filter could admit.
- [2026-09-28-indexed-reference-scope-requires-entity-component](2026-09-28-indexed-reference-scope-requires-entity-component.md) -
  its refusal of a filtered `referenceContent` over a reference lacking `REFERENCED_ENTITY` no longer applies to the
  filter (the main query still refuses).
- [2026-09-17-row-scoped-reference-having-body](2026-09-17-row-scoped-reference-having-body/README.md) - the row-scoped
  evaluation of `referenceHaving` this route borrows its nested-query planning from.

## Timeline

- **2026-10-13** - direction decided (row by row, index route deleted from the fetch, folded into PR #1738); the design
  reviewed twice; the compiler, the per-row mapping and the fetch switch land behind a temporary route switch.
- **2026-10-14** - both routes measured in one build; the index route, the switch and the sweep deleted; the record
  written (re-dated at merge).
- **2026-10-16** - review of #1738: the row matcher repeated the predicate work of the attribute translators. The
  matcher moved to the translators' package and became the one per-value test of every path that answers without the
  index; the prefetch and the entity filtering were pointed at it, which removed 41 measured divergences; invalid values
  refused before any index is read (decided by Johnny); the load-time invariant added.
  Fixed in passing, older than #1615: two entity-filtering formulas of a collection-less query hashed alike and
  `FormulaDeduplicator` dropped the second one; `EntityFilteringConjunctionFunctionalTest` showed 14 of 16 shapes wrong
  before the fix and 16 green after.
- **2026-10-17** - the matcher measured on the main query against the hand-written predicates: no measurable cost
  (time 0.996, allocation 0.992, geometric means over 36 points; see Verification).
  Fixed in passing, older than #1615: a collection-less query took the index branch for every catalog attribute, and
  the catalog index it targets answers only a globally unique lookup, so comparisons, intervals, string searches and
  the equality of a catalog attribute not unique globally answered `[]`;
  `CatalogAttributeWithoutCollectionFunctionalTest` showed 32 of 36 red before the fix and 36 green after.
  The maintainer declined backporting the hash fix of 2026-10-16 to `release_2026-2` on 2026-10-17; the
  catalog-attribute fix above and the collection-less `inScope` fix of 2026-10-19 that followed are likewise on `dev`
  only. 2026.2 therefore keeps all three: a collection-less query applies only the first of its entity-filtering
  constraints, answers `[]` to a comparison, an interval or a string search on a catalog attribute (and to the equality
  of one not unique globally), and answers `[]` to an `inScope` container.
- **2026-10-19** - manual review of #1738. Fixed in passing, older than #1615: an `inScope` container of a
  collection-less query answered `[]`, because `FilterByVisitor#getSuperSetFormula(Scope)` - every entity of the
  scope, which the container is intersected with - read entity indexes only. Without a collection it is now the
  entities the lookups found that live in the scope, told apart on their prefetched bodies
  (`ScopeEntityToBitmapFilter`). The six `inScope` cases of `CatalogAttributeWithoutCollectionFunctionalTest` were red
  before the fix. The fix made the scope capture of the entity filtering factory reachable, so its Mockito test was
  replaced by two functional cases of the same class.
