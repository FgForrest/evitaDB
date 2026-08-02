---
title: Conditional (partial) facet indexing via schema-compiled expression triggers, not per-mutation full-entity evaluation
date: 2026-04-23
updated: 2026-07-31
status: accepted
kind: feature
issues: [8]
prs: [1136]
areas: [evita_api/src/main/java/io/evitadb/api/requestResponse/mutation, evita_engine/src/main/java/io/evitadb/core/expression, evita_engine/src/main/java/io/evitadb/core/catalog, evita_engine/src/main/java/io/evitadb/index/mutation]
supersedes: []
superseded-by: []
relates: [2026-04-23-bucketed-histogram-indexing, 2026-05-06-reference-histogram-statistics, 2026-05-27-range-and-multi-histogram-schema]
---

# Conditional (partial) facet indexing

`ReferenceSchemaContract.getFacetedPartiallyInScope(Scope)` lets a reference carry a boolean
`Expression` that decides, per entity, whether the reference participates in faceting at all —
"facet this `parameter` reference only when the referenced entity's `status` is `ACTIVE`". The
expression can read the owner entity, the reference itself, the referenced entity, or the group
entity (one hop out, in any direction). Evaluating it cheaply during local mutation processing, and
correctly propagating it when data it depends on changes on a *different* entity, required a new
evaluation and trigger layer inside the indexing engine. This record covers that layer: the
Proxycian-based local evaluator, the expression→`FilterBy` cross-entity re-evaluator, and the new
engine-internal mutation channel that carries the re-evaluation signal between entity collections.

## Why

Facet gating previously had exactly one input: `ReferenceSchemaContract.isFacetedInScope(Scope)`, a
boolean. Real catalogs need facets that only make sense conditionally — a parameter should only be
facetable while its referenced entity is published, or while a group's widget type supports it. That
requires evaluating an arbitrary boolean expression at index time, and re-evaluating it whenever data
the expression reads changes — including data that lives on a *different* entity than the one being
faceted.

The constraint that made this non-obvious: the expression can reference data on the referenced entity
or the group entity, not just the owner entity. A change to a shared group entity's attribute can
affect thousands of owner entities' facet state (`groupEntity` fan-out), and the entity that changed
has no access to the target collection's indexes to resolve who is affected — collections are
mutation-isolated by design. The re-evaluation also has to stay off the hot path: most mutations
touch no conditional facet at all, and evaluating full `Entity`/`Reference` objects per candidate
would mean assembling `EntityAttributes`, `References`, `AssociatedData` and `Prices` wrappers for a
single attribute read.

### Previous state

Before this change, `isFaceted()` was the only gate `ReferenceIndexMutator` consulted before adding or
removing a facet entry — there was no notion of a per-entity condition, and no mechanism existed for
one entity's mutation to affect another entity's index state outside of the reference/group wiring
already in place (`insertIntoGroupIndexes()` / `removeFromGroupIndexes()`). The `Mutation` hierarchy
in `evita_api` was the only mutation taxonomy: everything that touched an index arrived as an
API-facing, WAL-serialized `Mutation`.

## Options considered

### Option A — Proxycian proxies locally, `FilterBy`-translated queries cross-entity, on a new engine-internal mutation channel (chosen)

Two different evaluation strategies for two different situations, sharing one expression-derived
trigger object built once at schema load time:

- **Local** (expression depends only on the mutating entity/reference): the trigger instantiates
  lightweight ByteBuddy/Proxycian proxies — composed per expression from reusable partial method
  implementations backed directly by storage-part data — and evaluates the expression inline in
  `ReferenceIndexMutator`, in the same transaction, no new mutation type involved.
- **Cross-entity** (expression depends on the referenced or group entity): the *source* entity's
  executor only detects that a relevant attribute changed and emits a new engine-internal
  `IndexMutation` (never WAL-serialized, regenerated on WAL replay) naming the reference and the
  changed entity's PK — it does not evaluate the expression or decide add/remove. The *target*
  collection resolves affected owner PKs from its own reverse-lookup indexes and evaluates the
  expression as a pre-translated `FilterBy` query against its own current indexes.

**Pros:** the hot path (no conditional facets touched) costs nothing beyond a lookup in the trigger
registry; the local path allocates roughly 2 objects per entity instead of assembling full `Entity`/
`Reference` graphs; the cross-entity path never needs bidirectional index access between collections,
which the collection-isolation model doesn't allow; `FilterBy` evaluation reuses the existing query
engine instead of a bespoke per-entity walk, so `AND`/`OR` across local and cross-entity terms falls
out for free.
**Cons:** a materially larger surface than a single-mode design — two evaluation code paths, a new
proxy-generation layer, a new registry, a new mutation type — all to keep the common case cheap.

### Option B — Materialize full `Entity`/`Reference` objects and evaluate against them (declined)

Reuse the existing entity assembly pipeline (the same one that builds objects for query results) to
construct a real `Entity`/`Reference` and run the expression against it directly.

- **Pros:** zero new proxy/partial infrastructure; expressions run against the same object shape used
  everywhere else in the codebase.
- **Cons:** the base `Reference` class returns `Optional.empty()` for `getReferencedEntity()` and
  `getGroupEntity()` — only `ReferenceDecorator` populates them, and building one requires the full
  assembly pipeline anyway; assembling `EntityAttributes` + `References` + `AssociatedData` + `Prices`
  wrappers to answer a single attribute read is 5-6 extra allocations per candidate, for a check that
  fires on every mutation of a faceted reference, not just the rare conditional ones.
- **Rejected because:** the allocation cost lands on the common case (every mutation on a faceted
  reference), not the conditional one — the opposite of where the cost should sit.

### Option C — Route the new cross-entity signal through the existing `Mutation`/`EntityMutation` sealed hierarchy (declined)

Make the re-evaluation signal just another `EntityMutation`, dispatched the same way as any other
entity-level mutation.

- **Pros:** one mutation taxonomy, one dispatch loop, no new marker interfaces.
- **Cons:** `EntityMutation` processing creates both container and index executors, touches storage,
  participates in schema evolution and conflict-key resolution, and is written to the WAL — none of
  which apply to a signal that only ever says "re-check this reference's expression," carries no
  storage payload, and must be regenerated deterministically on WAL replay rather than persisted.
- **Rejected because:** unifying the two would immediately re-branch into two different code paths
  inside the shared abstraction — a leaky abstraction, not a simplification. ("Two honest loops are
  better than one dishonest abstraction.")

### Option D — Resolve affected owner PKs on the source side and push explicit add/remove mutations to the target (declined)

Have the entity whose data changed (e.g. the group entity) resolve which owner entities are affected
and emit concrete add/remove instructions.

- **Pros:** the target collection would have less work to do per mutation.
- **Cons:** the reverse-lookup indexes needed to resolve "which owner entities reference this group"
  (`ReferencedTypeEntityIndex`, `ReducedGroupEntityIndex`) live in the *target* collection, not the
  source — resolving them from the source side would require either replicating those indexes or
  reaching across collection boundaries, both of which break collection isolation. Add/remove
  direction is also frequently undecidable at the source: an `OR` expression spanning both the group
  entity and the referenced entity can't be resolved from either side alone.
- **Rejected because:** "source detects, target decides" is the only split that keeps each side
  working exclusively with indexes it already owns.

## Decision

**Chosen: Option A.** The deciding driver was where the cost lands: Option B's allocation overhead
hits every mutation on every faceted reference, while Option A's two-mode split keeps the non-
conditional common case at the cost of one registry lookup. Option C would have made `IndexMutation`
a `Mutation` in name only, immediately special-cased everywhere it mattered. Option D was ruled out
by the collection-isolation model itself, independent of cost — the indexes needed to resolve
"decide" simply aren't reachable from "detect."

Both `FacetExpressionTrigger` and a later `HistogramExpressionTrigger` (added in the same PR, see
*Related work*) implement the same generic `ExpressionIndexTrigger` base, which is the extensibility
point this design was built around — a third conditional-indexing feature would add another subtype,
not a parallel mechanism.

## Key technical details

- **Layering.** `evita_api`'s `ReferenceSchemaContract.getFacetedPartiallyInScope(Scope)` /
  `getFacetedPartiallyInScopes()` (`evita_api/src/main/java/io/evitadb/api/requestResponse/schema/ReferenceSchemaContract.java`)
  hold the declarative `Map<Scope, Expression>` — public API, pure data. All operational machinery
  lives in `evita_engine`:
  - `io.evitadb.core.expression.proxy` — the Proxycian partials and proxy instantiation
    (`ExpressionProxyInstantiator`, `ExpressionProxyDescriptor`, `CatchAllPartial`, per-contract
    partials such as `ReferenceIdentityPartial`, `ReferencedEntityPartial`).
  - `io.evitadb.core.expression.trigger` — `ExpressionIndexTrigger` (base),
    `AbstractExpressionIndexTrigger`, `DefaultFacetExpressionTrigger` / `DefaultHistogramExpressionTrigger`
    (concrete impls — named differently from the plan's `FacetExpressionTriggerImpl`),
    `FacetExpressionTriggerFactory` / `HistogramExpressionTriggerFactory`, `DependencyType`.
  - `io.evitadb.core.expression.query.ExpressionToQueryTranslator` — compiles the full expression to
    a `FilterBy` template at schema load time; rejects (via `NonTranslatableExpressionException`)
    dynamic attribute paths, arithmetic operators, function calls, and anonymous `this` — confirmed
    in the current source, not just documented intent.
  - `io.evitadb.core.catalog.CatalogExpressionTriggerRegistry` (+ `DefaultCatalogExpressionTriggerRegistry`,
    `LocalTriggerIndex`) — the catalog-level inverted index keyed by `(mutatedEntityType, dependencyType)`.
  - `io.evitadb.index.mutation` — `IndexMutation` (marker), `EntityIndexMutation` (transport
    envelope), `IndexMutationTarget` (role interface), `IndexMutationExecutor<M>` /
    `IndexMutationExecutorRegistry`, `ReevaluateExpressionMutation` / `ReevaluateExpressionExecutor`.
  - `evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/MutationContract.java` — empty
    marker root; `Mutation` and `IndexMutation` both extend it purely for IDE type-hierarchy
    navigation, with no change to `Mutation`'s sealed `permits` clause.

- **`IndexMutation` dispatch invariant.** `IndexMutationExecutorRegistry` dispatches by exact
  `Class` identity (`Map.get(mutation.getClass())`), not by hierarchy traversal — the `IndexMutation`
  JavaDoc states implementations **must** be final classes or records, or a subclass will silently
  fail to dispatch.

- **`DependencyType` grew beyond the original four values** — the shipped enum
  (`DependencyType.java`) has six: `REFERENCED_ENTITY_ATTRIBUTE`, `GROUP_ENTITY_ATTRIBUTE`,
  `REFERENCED_ENTITY_REFERENCE_ATTRIBUTE`, `GROUP_ENTITY_REFERENCE_ATTRIBUTE`, and two added during
  implementation: `PARENT_ENTITY_ATTRIBUTE` / `PARENT_ENTITY_REFERENCE_ATTRIBUTE`. The original
  analysis treated `$entity.parent` as purely local data (`EntityBodyStoragePart`, same-entity
  fetch); the shipped design instead treats the parent entity as a genuine cross-entity hop
  (`$entity.parentEntity...`, not `$entity.parent`), with its own fan-out bounded by hierarchy
  branching factor.

- **Local-only triggers return `null` from `getDependencyType()`**, not a synthesized `LOCAL` enum
  value — resolved during the WBS-03 review as the simpler of two options once `getDependencyType()`
  was changed from `@Nonnull` to `@Nullable`. `getFilterByConstraint()` on such a trigger throws
  `UnsupportedOperationException`; local-only expressions are evaluated exclusively via `evaluate()`.

- **Mutation-ordering correction versus the original design doc.** The analysis this record replaces
  claimed attribute mutations are guaranteed to sort before reference mutations via
  `LocalMutation#compareTo`, so an expression reading `$entity.attributes['code']` during an
  `InsertReferenceMutation` would see the new value. That claim does not hold —
  `compareTo` exists for CDC ordering, not execution ordering. Correctness instead comes from
  `WritableEntityStorageContainerAccessor` reflecting previously-applied mutations regardless of
  declared order. A future change must not rely on `compareTo` for expression-evaluation correctness.

- **Group-PK resolution for `REFERENCED_ENTITY_ATTRIBUTE`** needed a new accessor,
  `FacetReferenceIndex.getGroupIdForFacet(int facetPK)`, not present in the original analysis — added
  during WBS-07 implementation because the obvious source (`EntityIndexKey` discriminator on
  `ReducedGroupEntityIndex`) turned out to hold the *group* entity PK, not the facet PK.

- **`ReflectedReferenceSchema` cannot inherit `facetedPartially`** from its source reference, because
  `$reference.referencedEntity` resolves to a different entity type on each side of a reflected pair.
  Enforced at three layers: `ReflectedReferenceSchema.withReferencedSchema()`,
  `SetReferenceSchemaFacetedMutation.mutate()`, and `ReflectedReferenceSchema.validate()` — all three
  confirmed present in the current tree.

- **Safe over-firing, twice.** Localized-attribute changes fire triggers keyed by attribute name only
  (locale is dropped for the trigger lookup, though the old-value cache itself is locale-correct), and
  `ApplyDeltaAttributeMutation` with a zero delta still fires — both are deliberate: the target-side
  add/remove is idempotent, so an unnecessary re-evaluation costs a query, not correctness.

## Verification

- `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/functional/indexing/ConditionalFacetIndexingTest.java`
  (2,378 lines, added in `a4e9a72b4`) and the sibling `ConditionalFacetQueryTest.java` (568 lines) —
  end-to-end indexing and query-side coverage across all documented data-access paths, local triggers,
  cross-entity referenced/group/parent triggers, and mixed expressions.
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/ReevaluateExpressionExecutorTest.java`
  (1,559 lines, `f8bb0df58`) — the cross-entity executor tested against real production index objects,
  no mocks (an explicit rewrite goal, per the commit message).
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/local/EntityIndexLocalMutationExecutorTriggerTest.java`
  (1,033 lines, `0ace4916e`) — source-side detection and dispatch.
- `evita_test/evita_long_running_tests/src/test/java/io/evitadb/api/EvitaConditionalFacetGenerationalTest.java`
  — generational/fuzz coverage, added alongside the histogram generational test in `92573446c`.
- All of the above are ancestors of PR #1136's merge commit (`7ba51ae72`, 2026-04-23), confirmed via
  `git merge-base --is-ancestor`.

## Consequences & open follow-ups

- **Post-ship bug, fixed.** On 2026-05-28 (`9dc4b9427`, committed directly to `dev`, no PR — five
  weeks after merge), the cross-entity executor was found to issue blind `addFacet`/`removeFacet`
  calls against the freshly-resolved group bucket. When a faceted reference migrated to a
  non-matching group, the local `SetReferenceGroupMutation` path had already removed the facet from
  every bucket; the cross-entity `GROUP_ENTITY_ATTRIBUTE` re-evaluation then called `removeFacet`
  against the now-empty new-group bucket and raised `"Facet <pk> not found in index"`, with a
  symmetric risk of orphan duplicates on the add path. Fixed by routing both the local and
  cross-entity facet updates through the same presence-aware
  `ReferenceIndexMutator.applyFacetDecisionMatrix` (made public for this purpose): missing bucket is
  a no-op, already-present is a no-op, wrong bucket self-heals. A reproducer covering both
  `WARMING_UP` and `ALIVE` catalog states was added to `ConditionalFacetIndexingTest`.
- **The AD-12 extensibility model was not how histogram support actually shipped.** The original
  analysis sketched a *future* `ReevaluateHistogramExpressionMutation` +
  `ReevaluateHistogramExpressionExecutor` pair alongside the facet one. What shipped instead: one
  `ReevaluateExpressionMutation` / `ReevaluateExpressionExecutor` pair handles both facet and
  histogram cross-entity re-evaluation (`IndexMutationExecutorRegistry` maps only the one mutation
  class; the executor calls `IndexMutationTarget.getHistogramTriggers(...)` internally). A future
  third trigger type should check whether extending the existing executor is enough before adding a
  new mutation/executor pair.
- **Schema evolution remains deferred, confirmed still true.** No re-index-on-`facetedPartially`-change
  mechanism exists in the current tree (checked: no such logic in `EntityCollection` or the
  `CatalogExpressionTriggerRegistry` rebuild path). The expression is still effectively immutable
  after initial schema creation for the purposes of this mechanism.
- Several fan-out correctness bugs (`715d0072f`, `d31fdee9a` — cross-group facet contamination and
  group-entity enrichment; `a427cd47b` — duplicate cross-entity dispatch) were found and fixed
  *within* PR #1136 before merge — not lingering issues, but a sign that the group fan-out path
  (Option A's most complex part) needed real hardening before it was trustworthy. Anyone touching
  `ReevaluateExpressionExecutor`'s group-PK resolution should treat that path as fragile.
- The annotation-driven Java client schema builder (`ClassSchemaAnalyzer`, `@Reference` /
  `@ReflectedReference`) gained equivalent `facetedPartially`/bucketed support in follow-up commits
  after PR #1136 (`c06bd892c`, `c7029e39a`, `26c26c53e`) — outside this record's scope, but the same
  underlying schema field.
- **Cross-reference resolved:** the histogram folders have been converted into
  [[2026-04-23-bucketed-histogram-indexing]] (schema + conditional indexing engine),
  [[2026-05-06-reference-histogram-statistics]] (query-time statistics + `histogramHaving`), and
  [[2026-05-27-range-and-multi-histogram-schema]] (range-typed sources + multi-histogram
  annotation). Where they previously cited the facet working documents as the reference implementation, they now
  point at this record.

## Related work

- **Histogram indexing** ([[2026-04-23-bucketed-histogram-indexing]], same issue #8, same PR #1136;
  plus its query-time sibling [[2026-05-06-reference-histogram-statistics]] and the later
  [[2026-05-27-range-and-multi-histogram-schema]] follow-up) shares this record's base
  infrastructure directly: `ExpressionIndexTrigger`, `CatalogExpressionTriggerRegistry`, and the
  single `ReevaluateExpressionMutation`/`ReevaluateExpressionExecutor` pair all serve both facet and
  histogram conditional indexing. Histogram-specific mechanics (bucket maintenance, named indexes,
  value-expression handling) belong in those records, not here.

## Timeline

- **2026-03-05** — per-scope `facetedPartially` schema support lands (`6aa90385f`)
- **2026-03-10** — problem analysis and 11-item WBS breakdown written (`25fd7be90`)
- **2026-03-11 – 2026-04-10** — WBS-01 through WBS-11 implemented in sequence (translator, proxy
  infrastructure, trigger hierarchy, catalog registry, mutation type hierarchy, dispatch
  infrastructure, cross-entity executor, source-side detection, local trigger integration, collector
  integration, cold-start wiring), including parent-entity dependency support and reflected-reference
  inheritance blocking
- **2026-04-23** — PR #1136 merged into `dev`
- **2026-05-28** — post-ship presence-aware re-evaluation fix (`9dc4b9427`, direct commit, no PR)
- **2026-07-31** — planning documents retired, replaced by this record
