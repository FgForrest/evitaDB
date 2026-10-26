---
title: String attributes declare a single-pass normalization chain, and a populated index keeps the chain it was built with
date: 2026-10-25
updated: 2026-10-26 20:45
status: accepted
kind: feature
issues: [545]
prs: []
areas: [evita_api/src/main/java/io/evitadb/api/requestResponse/schema/analysis, evita_api/src/main/java/io/evitadb/api/requestResponse/schema/builder/InternalSchemaBuilderHelper.java, evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/attribute/SetAttributeSchemaNormalizedMutation.java, evita_engine/src/main/java/io/evitadb/index/analysis, evita_engine/src/main/java/io/evitadb/spi/analysis, evita_engine/src/main/java/io/evitadb/index/attribute, evita_engine/src/main/java/io/evitadb/index/component/loader/LoadContext.java, evita_engine/src/main/java/io/evitadb/core/collection/EntityCollection.java, evita_engine/src/main/java/io/evitadb/core/catalog/Catalog.java, evita_engine/src/main/java/io/evitadb/core/query, evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/storageParts/index, evita_store/evita_store_server/src/main/java/io/evitadb/store/index, evita_external_api]
supersedes: []
superseded-by: []
relates: [2026-10-05-schema-changes-never-refused-dormant-accelerator, 2026-10-08-unique-indexes-normalize-like-filter-indexes, 2026-08-10-stored-value-normalization-split, 2026-09-21-cardinality-counter-normalized-keys, 2026-10-20-reference-content-filter-row-by-row, 2026-10-20-single-owner-partitions-as-registry-rows, 2026-08-24-fulltext-search-lucene-vs-inhouse]
---

# String attributes declare a single-pass normalization chain, and a populated index keeps the chain it was built with

A `String` / `String[]` attribute (entity, reference or global) can declare `normalized(lowercase(), asciiFolding(),
trim(), removeCharacters("- "))`: an ordered list of analysis steps that turns a value into the key its filter,
unique, sort, cardinality, compound and trigram structures store and compare. The stored value is untouched. The
chain runs once per raw value (NFC in, steps in declared order, NFD out); a key that is already stored is never run
through it again. A declaration change is always accepted; an attribute that already holds indexed keys keeps the
chain those keys were built with until reindexing (#409), an empty one adopts the declaration at once. The
built-with chains are owner state persisted beside the schema, and every key-binding site reads them through one
resolver.

## Why

Case-insensitive lookups and uniqueness (`ABC` = `abc`) were the issue's request; shops also need separators and
diacritics folded out of codes. Every String key was normalized with one fixed, idempotent type-level function (NFD),
and many paths relied on that: probes were normalized again along a lookup path, loaders re-canonicalized persisted
keys, virtual partitions re-normalized keys on promotion. A user-chosen chain breaks all three assumptions - it is
not idempotent, it differs per attribute and per fold locale, and it can change while an index is populated.

### Previous state

`FilterIndex#getNormalizer` / `SortIndex#createNormalizerFor` bound NFD for every String tree and comparator; the
query side hardcoded NFD in `AttributeValueMatcher` and the row filters. Unique trees had just been aligned with the
filter key (2026-10-08). There was no per-attribute key policy and no storage for one.

## Options considered

### Shape of the declaration

- **Own chain on the attribute, in the fulltext `AnalysisStep` vocabulary (chosen).** `AnalysisStep` (name +
  parameters, data only) and the `AnalysisSteps` factories live in `evita_api`; providers are ServiceLoader
  `NormalizingAnalysisStepFactory` implementations that must declare themselves normalizer-safe, as Elasticsearch's
  keyword normalizers accept only `NormalizingTokenFilterFactory` filters. v1 ships `lowercase`, `ascii-folding`,
  `trim`, `remove-characters` as built-ins written in JDK code plus Lucene's static ASCII folding table.
- **Catalog-level named normalizer profiles (Elasticsearch style) - declined.** Rejected because it waits for the
  whole profile machinery of the fulltext line (F1c) and would run a Lucene `TokenStream` on every write, lookup and
  unique check. Revisit if profiles land for fulltext and a cheap non-stream execution exists.
- **The issue's own flags (`caseInsensitive()`, `asciiOnly()`) - declined.** Rejected because it creates a second
  vocabulary next to fulltext's for the same operations.

### Fold locale

- **The index's own locale (chosen).** A localized attribute folds by the locale of its value tree, a non-localized
  one by root. Consequence: a localized attribute unique across locales folds its uniqueness tree by root, while its
  filter and sort trees use each locale.
- **Locale-independent everywhere / an explicit `lowercase(locale)` parameter - declined.** Rejected because Turkish
  and Azeri users expect `I`/`ı` to behave per locale in a localized value. The `lowercase` step implements that rule
  for tr / az only and folds every other locale (Lithuanian included) like root, so the cost is limited to those
  trees.

### Chain execution: single pass versus repeat-until-stable

- **Single pass in declared order (chosen).** Lucene, Elasticsearch and PostgreSQL do the same; the order is the
  schema author's responsibility (`removeCharacters("-"), trim()` turns `" - abc"` into `abc`, the reverse leaves
  `" abc"`).
- **Repeat the chain until it stops changing (max 3 rounds) - declined.** It was proposed because no order of the two
  common steps is stable: with Lucene 9.12.3 folding and JDK casing, `p = NFD(steps(NFC(x)))` over every defined single
  code point gives `p(p(x)) != p(x)` for 52 code points with `lowercase(), asciiFolding()` (root and tr - Lucene folds
  lowercase `ǥ ǧ` and small capitals `ɢ ɪ ɴ ʀ ᴀ` to upper-case ASCII) and 15 (root) / 40 (tr) with
  `asciiFolding(), lowercase()`. **Rejected because** the instability is harmless once no stored key is ever run
  through the chain again (next fork); it only corrupted indexes because loaders and partitions re-normalized keys.
  Repetition would hide a real design rule behind extra work on every value.

### Raw input versus stored keys

- **Two functions per site, never mixed (chosen).** The chain applies once to raw input (writes, removals, query
  values). A stored key is taken verbatim in regular processing (virtual-partition promotion / demotion, probes and
  removals by key). Only the storage migration may re-canonicalize a stored bucket key, and only with the type-level
  function (`FilterIndex#getLegacyStringNormalizer`, NFD), which is harmless for chain keys because every chain ends
  with NFD.
  Filter and histogram loads restore the stored keys verbatim. Unique parts instead carry declared-type values;
  every unique load converts those values back to keys with the type-level function, never a declared chain,
  because a persisted `String` is already a chain-built key.
- **Keep normalizing keys idempotently wherever convenient - declined.** Rejected because it is only correct for a
  fixed idempotent function; with a chain it re-keys values silently (with `asciiFolding(), lowercase()` on a
  Turkish tree, `I` keys as `ı` and a second pass turns that into `i`; the folding cases above likewise).

### Changing the chain of a populated attribute

- **K' - built-with, switched only at a schema change (chosen).** Owners keep a built-with row per attribute: a
  collection map `(reference name or null, attribute)` → steps, and one catalog map for global attributes (the catalog
  unique trees and every adopting collection share it). A schema mutation reconciles rows: for every attribute whose
  declaration differs from its row it probes the in-memory indexes (both scopes, all locales, global / reduced /
  referenced-type / group indexes, cardinality counters, virtual dictionaries, non-null String elements of compound
  tuples; for a global attribute every adopting collection and the catalog trees). No key → the row becomes the
  declaration; keys → the row stays and one warning is logged. Writes never change a row. Size estimate when chosen:
  ~0.7x the rest of #545.
- **K - built-with with an automatic switch when an attribute empties - declined.** Needs a transactional support
  census per attribute across every index family, an atomic switch at the root-mutation boundary, rebinding of
  surviving empty holders and replay / rollback handling - ~1.5-2x the rest of #545 and the riskiest part. **Rejected
  because** the only gain is that an attribute emptied by deleting its data switches without a schema statement;
  with K' the user repeats the declaration (any schema change of that entity reconciles).
- **R - refuse the change on a populated attribute (the issue's text) - declined.** ~0.2x: no policy store, the
  declaration always built the keys. **Rejected because** it contradicts
  `2026-10-05-schema-changes-never-refused-dormant-accelerator` (schema changes are accepted and degrade until
  reindexing).
- **Re-key inside the schema transaction from entity bodies - declined.** It is #409 for one attribute, and on
  catalogs with many reduced indexes it is one huge transaction.
- **Make the attribute unwritable until reindexed (as a changed `indexedDecimalPlaces` does) - declined**: the
  attribute becomes unusable. **Retire the indexes (as fulltext does on an index-side analyzer change) - declined**:
  `attributeIsNull` would match every old entity and uniqueness would vanish.

### Query routing of typed equality

- **The index answers typed `attributeEquals` / `attributeInSet` with no prefetch alternative (kept).** Each index
  role applies its own bound function to the raw probe once.
- **Give them a prefetch alternative that re-checks bodies - declined.** An implementation phase added one
  (`SelectionFormula` + a unique-index bitmap filter whose prefetch arm computes the index lookup anyway and
  intersects). **Rejected because** it does strictly more work than the index arm and silently reverses
  `2026-10-20-reference-content-filter-row-by-row`; normalization does not need it because the index already applies
  the bound function.

### External API input

- **Typed inputs generated from the installed normalizing providers (chosen):** GraphQL `@oneOf`
  `[{lowercase: true}, {removeCharacters: {characters: "- "}}]` (parameterless steps take `true`: GraphQL forbids
  empty input objects), the same shape as OpenAPI `oneOf` in REST, generic `GrpcAnalysisStep {name, parameters}` in
  gRPC. Output everywhere is the declared list `{step, parameters}`.
- **Generic `{step, parameters}` input in GraphQL / REST, typed later - declined.** Rejected because a generic input
  leaves step names and parameter types to runtime validation and gives clients no discovery of the installed steps;
  graphql-java 25.0 supports `@oneOf` natively, so the typed form cost little to build now.

## Decision

Chain on the attribute in the `AnalysisStep` vocabulary, folded by the index's locale, executed once on raw input,
never on stored keys; K' for changes on populated attributes. K' wins over K because the census and the write-time
switch carry most of the risk for a convenience the user gets with one repeated schema statement; it wins over R
because schema changes are never refused. Revisit K if #409 adds a cheap per-attribute footprint census anyway, and
the whole built-with machinery once #409 can rebuild an attribute's keys - then a declaration change can trigger the
rebuild and the rows become transient.

## Key technical details

- **Compiler:** `StringNormalizationCompiler` (NFC → steps once → NFD; returns the input instance when unchanged);
  `AnalysisStepRegistry` (catalog-owned) validates declarations; the compiler caches functions per (steps, fold
  locale).
- **Seam:** every tree, counter, compound, matcher and comparator binds its function through
  `AttributeNormalizationResolver`; the implementation is `BuiltWithAttributeNormalizationResolver` over
  `AttributeNormalizationPolicy` (collection) and the catalog's global rows. Functions are bound when a structure is
  created or loaded and travel with it through copies; an owner publishes a new resolver only when an effective row
  changes, and compound wrappers are replaced only when one of their String elements' steps changed.
- **Reconciliation:** `AttributeNormalizationReconciler`, called from `EntityCollection#exchangeSchema` (also for a
  version-neutral `updateSchema`, so repeating a declaration after the last value was deleted applies it) and
  `Catalog#reconcileGlobalNormalization`. The only `TOBEDONE JNO #409` marker sits at its warning. Population probes
  never iterate scalar values; they read the structure's type and emptiness and iterate only compound tuples whose
  binding names the attribute at a String position.
- **Local versus global ownership:** a collection identity moves between its own row and the catalog row, in either
  direction, only when it holds no keys. Replacing a populated local attribute with a same-named global one keeps the
  local row (and warns) until the identity empties. The collection resolver therefore follows the policy's adoption
  set, never the attribute's current schema type; catalog-owned structures always bind the catalog row, and the
  catalog's population probe counts only collections that have adopted it.
- **Reference ordering binds the owner of the fetched entity** (`ReferenceOrderByVisitor`), not the query's
  collection - a collection-less query or a nested fetch otherwise sorts references with another collection's rows.
- **Storage:** singleton parts `AttributeNormalizationStoragePart` (collection) and
  `GlobalAttributeNormalizationStoragePart` (catalog), Kryo ids 645 / 646, record types 56 / 57, appended in
  `IndexStoragePartConfigurer`. They hold step descriptions, compound element identities and retained global names -
  never functions. Absent part = empty rows = NFD only, which is what every catalog written before #545 holds; an
  EMPTY policy removes its part, so a catalog using neither normalization nor sortable compounds stores exactly
  what it stored before. Rows load before any value tree and before WAL replay; replay reaches the same rows because
  the decision depends only on the replayed schema mutation and the index state.
- **Compounds:** `ComparatorSource` carries no attribute identity, so each compound's ordered element bindings are
  persisted; loaders bind from them even if the compound definition changed later.
- **Orphans:** an index that outlives its declaration (removal, rename, type change) keeps its row and binds it at
  load (`LoadContext`, the catalog unique loader); without a row it binds the legacy function.
- **The schema always reports the declaration.** External APIs never expose built-with rows.
- **Looks like a bug, is not:** the same attribute can show `normalized(lowercase())` in its schema while its index
  is case-sensitive - that is K' on a populated attribute, and the warning says so.

## Verification

- `DeclaredNormalizationQueryFunctionalTest` - 3,176 dynamic cases. Before a real restart, every query route
  (index, prefetch, reference row filter, accelerated substring) x nine chains (the four steps alone, the two
  orders of `lowercase` / `asciiFolding`, `trim` / `removeCharacters` both ways, all four) x root and Turkish fold x
  a no-step control x SINGLE / PAGED value trees, with the compiler as the only oracle. After the restart, equality
  and natural order for every chain plus the full route set for the four unstable chains, then virtual-partition
  promotion and demotion.
- `DeclaredNormalizationWriteRoundTripTest` - writes, updates, removals and reloads; all 28 combinations of a
  removed or renamed declaration over a populated index (seven roles x remove / rename x two page modes) reopen
  with their retained binding.
- `BuiltWithNormalizationFunctionalTest` - K': a populated attribute keeps its chain through the change, a live
  reader, a restart and WAL replay; an emptied one adopts the declaration at the next schema change of its entity.
  `DeclaredNormalizationUniquenessFunctionalTest` covers collection, cross-locale and catalog-wide uniqueness.
  `BuiltWithNormalizationOwnershipFunctionalTest` pins local-to-global and global-to-local replacement of a populated
  attribute live, after a restart and after WAL replay; `DeclaredNormalizationReferenceOrderingFunctionalTest` pins
  reference ordering parity between collection-qualified, collection-less and nested fetches.
- `NormalizationSchemaChangeCostTest` - a schema change reads no values and rebinds only changed rows;
  `AttributeNormalizationReconcilerTest` pins reconciliation's ownership moves and compound rebinding on plain
  policies, `AttributeNormalizationStoragePartSerializerTest` the wire layout and byte stability of both policy
  parts; `AnalysisStepGoldenOutputTest` pins 78 built-in outputs; `StringNormalizationCompilerTest`,
  `NormalizationSerializationTest` (schema, WAL, storage parts) and 61 external-API tests.
- Final state, offline build on one machine: focused selection 4,789 tests green (1:12), `external_api` group 2,495
  green (0:53). Full functional fast loop after the quality passes: **33,763 tests in 15:30**, against **30,185
  tests in 12:39** for the base commit `e223d5bb19` run the same morning on the same machine. The base failed only
  timing-sensitive tests (`CollationKeyCacheSweeperTest`, `CatalogGoLiveSessionDrainTest`,
  `EvitaOnDemandTrafficRecordingTest`; 1 failure / 3 errors) and had been green in 12:35 two days earlier; the #545
  run failed only a connection-setup timeout in `CatalogGraphQLSchemaSubscriptionsFunctionalTest`, a class it does
  not touch and which passes alone. Before the test
  restructuring the query matrix was a single sequential factory, 829 s of suite wall time at the tail of the run;
  it now runs its readers concurrently in 450 s. The three engine-lifecycle classes went from 617 / 244 / 126 s to
  4.5 / 2.7 / 1.2 s.

## Consequences & open follow-ups

- **#409 (reindexing) owns:** rebuilding a populated attribute whose declaration differs from its row (the
  `TOBEDONE JNO #409` site); reporting the unique collisions such a rebuild creates (`ABC` and `abc` already owned by
  two entities); reshaping compounds whose definition changed while populated. In that last state a compound tree
  created after the change binds today's elements until restart and the retained binding after it - inconsistent,
  but only inside a state whose old tuples already hold other attributes.
- **Accepted downside of K':** case-insensitive uniqueness added to a populated attribute is not enforced until #409;
  deleting all values does not switch the chain without a schema statement.
- **Behaviour fixes the route-parity tests forced (pre-existing, kept):** the prefetched compound comparator gave
  all-NULL tuples a NULLS_LAST slot instead of passing them to the next sorter, and broke equal keys without the
  index's directional primary-key tie-break; the prefetched comparator of a non-localized attribute collated by the
  query locale while the index does not; the exact sorter read the entity attribute when ordering by a reference
  attribute and ignored the locale of a localized one; a collection-less query whose reference ordering needed
  prefetched attributes failed with `EntityCollectionRequiredException` (`ReferencedEntityFetcher` derived the
  prefetch request from the query's collection instead of the fetched entity's).
- **Pre-existing WAL bug fixed on the way:** `ModifyAttributeSchemaTypeMutationSerializer` wrote the type with
  `writeObject(Class)` (which appends ClassSerializer's primitive/wrapper flag) but read it with `readClass`, leaving
  the flag unread - replaying a type change to `Integer` or another wrapper misread `indexedDecimalPlaces`. The
  reader now consumes the flag; the writer and layout are unchanged. The same fix goes to `dev` separately (#1794).
- **Pre-existing builder combination bugs fixed on the way.** The API-lane review found that a schema builder could
  combine a `normalized(...)` statement with a later one across an attribute rename. The renamed attribute then lost
  its chain, and every `Set*` / `Modify*` statement and every element kind behaved the same way.
  - **The rules now** (`InternalSchemaBuilderHelper#addMutations`):
    - a new mutation never combines with a rename of its element, or with anything collected before one;
    - a creation never combines once its name was renamed, because creation combiners judge against the builder's
      base schema (`#isJudgedAgainstBaseSchema`);
    - rename mutations no longer combine with each other.
  - **Merge order:** `ModifyEntitySchemaMutation#combineWith` now keeps older statements first.
  - **Not the contract:** one-by-one equivalence. Builders accumulate repeated declarations (indexed scopes) through
    combination, so statements after the last rename still combine.
  - **Still open:** #1800 - merged entity edits moving past catalog mutations, entity-level removals swallowing
    reference statements, and builders deciding create-vs-modify against the base schema.
  - **Verification:** `SchemaMutationCombinationAcrossRenameTest`, 18 cases, each red before its rule.
- **Known and not changed here:** removing an attribute of a reference to a managed entity type throws an NPE in
  `RemoveAttributeSchemaMutation#mutate` (pre-existing on the base; fixed on `dev` by #1795);
  `RepresentativeReferenceKey` identity stays on raw values; collection-less equality lookups of a cross-locale
  unique attribute read only the requested locale, as before.
- **Extension point:** a third-party `NormalizingAnalysisStepFactory` must produce stable output (keys are
  persisted), keep NFD-compatible output and be installed whenever the catalog loads; GraphQL and REST generate its
  typed input automatically.
- **No provider version is recorded.** Built-with rows hold step descriptions only. A behaviour version on the SPI
  was drafted, but nothing read it, so it was removed with the other members that had no production caller. A
  built-in step whose output changes with a dependency upgrade (Lucene's folding table, the JDK's Unicode data)
  would leave stored keys out of step with query-time keys; `AnalysisStepGoldenOutputTest` pins the built-in
  outputs to catch that before release. A stamped version is worth adding once #409 can rebuild the keys it
  invalidates - before that, a detected mismatch could only be reported.

## Related work

- [2026-10-05-schema-changes-never-refused-dormant-accelerator](2026-10-05-schema-changes-never-refused-dormant-accelerator.md)
  — the rule that eliminated option R; K' is its application to key policy.
- [2026-10-08-unique-indexes-normalize-like-filter-indexes](2026-10-08-unique-indexes-normalize-like-filter-indexes.md)
  — unique trees key like filter trees; the chain extends that key, so collisions follow it.
- [2026-08-10-stored-value-normalization-split](2026-08-10-stored-value-normalization-split.md) - its "probes are
  normalized more than once along a lookup path" holds for type-level normalizers only; String chains are applied
  once to raw input.
- [2026-09-21-cardinality-counter-normalized-keys](2026-09-21-cardinality-counter-normalized-keys.md) - the
  prefetch / index divergence it fixed is what the route-parity matrix here guards against.
- [2026-10-20-reference-content-filter-row-by-row](2026-10-20-reference-content-filter-row-by-row.md) - its
  no-prefetch-alternative rule for typed equality is kept; row filters bind the same functions.
- [2026-10-20-single-owner-partitions-as-registry-rows](2026-10-20-single-owner-partitions-as-registry-rows.md) -
  virtual partitions now move stored keys verbatim on promotion / demotion.
- [2026-08-24-fulltext-search-lucene-vs-inhouse](2026-08-24-fulltext-search-lucene-vs-inhouse/README.md) - the
  `AnalysisStep` vocabulary #545 starts and the fulltext analyzer configuration will reuse.

## Timeline

- **2026-10-23** — design forks decided (shape, v1 steps, fold locale, built-with); engine analysis
- **2026-10-24** — K' chosen over K and R; single-pass execution and the raw-input / stored-key rule decided
- **2026-10-25** — implementation, cost fix-ups, external APIs, documentation and the test-cost restructuring
  completed; an adversarial review found the local/global ownership and reference-ordering defects fixed above, and
  a code-quality pass added the reconciler, policy and serializer unit tests
- **2026-10-26** — the filter and histogram bucket reload canonicalizer moved into `Migration_2026_3`;
  unique loads retain the declared-value-to-key format conversion using the type-level function.
