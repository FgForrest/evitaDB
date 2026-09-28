---
title: Every scope a reference is indexed in must carry REFERENCED_ENTITY; a stored catalog lacking it loads, and every query and schema change over it refuses loudly
date: 2026-09-28
updated: 2026-09-28 19:20
status: accepted
kind: fix
issues: [1601, 1583]
prs: [1657]
areas: [evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/ReferenceSchema.java, evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/ReflectedReferenceSchema.java, evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference, evita_engine/src/main/java/io/evitadb/core/query/filter/translator/reference, evita_engine/src/main/java/io/evitadb/core/query/filter/FilterByVisitor.java, evita_engine/src/main/java/io/evitadb/core/query/QueryPlanningContext.java, evita_engine/src/main/java/io/evitadb/core/query/sort, evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/reference/producer, evita_engine/src/main/java/io/evitadb/index/mutation/ReevaluateExpressionExecutor.java, evita_engine/src/main/java/io/evitadb/core/collection/EntityCollection.java, evita_engine/src/main/java/io/evitadb/core/exception/ReferenceComponentNotIndexedException.java]
supersedes: []
superseded-by: []
relates: [2026-09-17-row-scoped-reference-having-body, 2026-09-15-bidirectional-reference-counterpart-rewrite]
---

# Every scope a reference is indexed in must carry `REFERENCED_ENTITY`, and a stored catalog lacking it refuses loudly

A reference indexed in a scope with `REFERENCED_GROUP_ENTITY` alone, or with no indexed component at all, reported
itself indexed while holding none of the indexes keyed by the referenced entity - and every query over it in that
scope answered with an empty result (a `not` around it with the whole collection), indistinguishable from a genuine
one. The schema now refuses the shape: **every indexed scope must contain `REFERENCED_ENTITY`**, checked in
`ReferenceSchema#validate`. Catalogs stored before the rule still load, but every query path that needs the missing
indexes refuses with `ReferenceComponentNotIndexedException`, and every session changing the schema of such a catalog
is refused at close until the reference is fixed. The reflected-reference defect that produced the empty shape
silently (#1583) is fixed in the same line of work.

## Why

#1601 reported that `referenceHaving(R, groupHaving(...))` on a reference indexed for `REFERENCED_GROUP_ENTITY` only
matched nothing - the one constraint that component exists to serve. The cause is not the translator: every query
path over a reference - `referenceHaving` and everything inside it, `hierarchyWithin`, `hierarchyOfReference`,
`facetHaving`, `referenceContent` with a filter or a predecessor ordering, `referenceProperty` ordering, reference
histograms and `histogramHaving` - first locates the reduced **entity** indexes, and only `REFERENCED_ENTITY` builds
them. Index selection sees no candidate and the planner shortcut (`IndexSelectionResult#isEmpty`) answers empty before
the body is translated. The row-scoped `referenceHaving` work had found this and deliberately left it unguarded
(`2026-09-17-row-scoped-reference-having-body`).

The fork was not *whether* to fix it but *which way*: make the group family answer, or refuse the shape.

While verifying the refusal on the broad suite, the bidirectional-rewrite dataset was refused at session close: the
reflected-reference builder left every scope its explicit components did not name indexed with `[]` - exactly the
shape the rule refuses, produced by ordinary schema evolution. That is the root cause of #1583 ("a reflected reference
on an archived owner gets no index at all"), so it was folded in rather than exempted.

### Previous state

- `ReferenceIndexedComponents` was documented as independent per scope ("or vice versa"), and the schema accepted any
  subset, including group-only and empty.
- The reflected branch of `SetReferenceSchemaIndexedMutation` applied explicit components verbatim, and
  `ReflectedReferenceSchema#withReferencedSchema` gave a scope inherited from the reflected reference no component when
  the reflected reference declared its own - so `withIndexedInScope(LIVE, ARCHIVED)` plus components for `LIVE` left
  `ARCHIVED=[]`. The same defect ships in v2026.2.15, which also lacks the plain-reference default fill (dev-only since
  2026-09-18), so released catalogs can carry both reflected and plain `[]` scopes.
- The #1585 `groupHaving` guard passed as soon as **one** queried scope carried the group component.

## Options considered

### Option A — refuse the shape in the schema, refuse the queries on stored catalogs (chosen)

A schema rule in `validate()`, plus a query guard judging the schema only, called where the entity index family is
looked up. The reflected builder fills the default component into scopes it would otherwise leave empty.

- **Pros:** one invariant every reader can rely on; no query semantics to design; the refusal names the reference, the
  scope and the fix.
- **Cons:** a stored catalog carrying the shape blocks every schema change until repaired (see Consequences).

### Option B — answer from the group family when the entity family is absent (declined)

- **Pros:** the declared schema would work as the user expected; no refusal.
- **Cons:** only `groupHaving` could ever be answered, and not faithfully.
- **Rejected because:** a reference without a group is indexed nowhere in the group family, so existence checks and
  `entityHaving` cannot be answered from it at all; and one group partition fuses the rows of an owner sharing the
  group, so row-scoped conditions (the #1585 semantics) cannot be told apart there. Revisit only if a per-row group
  index is ever built for another reason.

### Option C — refuse the queries only, keep the schema permissive (declined)

- **Pros:** no schema-level break for anyone.
- **Cons:** a schema that can never answer a query over the reference in that scope stays creatable, and the error
  arrives at query time, far from where the mistake was made.
- **Rejected because:** the shape has no working use - every query path needs the entity family - so accepting it only
  defers the failure. The query guard is kept anyway, for catalogs stored before the rule.

### Option D — validate at construction instead of in `validate()` (declined)

- **Rejected because:** construction-time validation runs on load - WAL replay of `CreateReferenceSchemaMutation`
  (array `_internalBuild` -> `validateScopeSettings`) and the rebinding of reflected references
  (`EntityCollection#initSchema` -> `withReferencedSchema`) - so stored catalogs carrying the shape would become
  unloadable. Kryo readers use the map overload and validate nothing, but they are not the only load path.

### Option E — repair the shape on load (declined)

Either fill the default into a stored empty scope, or treat it as not indexed.

- **Rejected because:** filling it makes the scope claim `REFERENCED_ENTITY` over indexes that were never built -
  exactly the silent wrong answer the rule exists to prevent, with the checks that would say so silenced. Treating it
  as not indexed makes the in-memory schema differ from the stored one, and other rules (faceting in a scope requires
  the reference indexed there) would start refusing on unrelated grounds; not analysed further.

### Option F — on upgrade, validate only the references a session changed (declined)

The blast radius of Option A on a released catalog is catalog-wide: validation walks the whole catalog schema, so an
upsert that evolves an unrelated collection's schema fails too.

- **Pros:** unrelated schema changes keep working; the loud part stays where the data is wrong (the queries).
- **Rejected because:** a catalog whose schema cannot answer queries over one of its references should not keep
  evolving as if it were healthy - the first schema change surfaces the problem with the fix named, rather than
  leaving it to a query that may run weeks later; the upgrade cost is carried by documentation instead. It needs
  `validate()` to know which references a session changed, which it does not today. Revisit if the upgrade proves too
  disruptive in practice: `EntityCollection#updateSchema` already collects `updatedReferenceSchemas`, the natural hook.

## Decision

**Chosen: Option A**, with the guard placed at the index-lookup chokepoints rather than per constraint, judging the
schema only (an indexed scope that holds no rows legitimately has no index), and refusing when **any** queried scope
lacks the component - the same "every queried scope" semantics now applies to the #1585 `groupHaving` guard. A union
that silently drops one scope's rows looks exactly like a complete answer.

## Key technical details

- Schema rule: `ReferenceSchema#validateEntityComponentIndexed`, called from `ReferenceSchema#validate` and from the
  bound branch of `ReflectedReferenceSchema#validate` (inherited scopes and components resolve only once bound).
  Never call it from construction - see Option D.
- Query guard: `HavingTranslatorHelper#assertEntityComponentIndexed`, called from
  `FilterByVisitor#getReducedIndexPrimaryKeyFormula`, `QueryPlanningContext#getReducedEntityIndexes`,
  `ReferenceOrderByVisitor#getChainIndex`, `ReferenceHistogramAccumulator` (ungrouped path) and the scope loop of
  `ReferencePropertyTranslator#createSorter`. The last one checks up front because index selection may hand it a
  reduced index set narrowed by `inScope(...)`, and then it never looks the family up.
- `FilterByVisitor` guards only scopes in `queryContext.getScopes()`: `referenceContent` merges the scopes its inner
  `scope(...)` names for the *referenced* entities into the owner-side lookup (`ReferencedEntityFetcher#gatherSearchedScopes`),
  and owners from other scopes are never fetched there.
- `ReferenceComponentNotIndexedException` is a dedicated type because one caller must not propagate it:
  `ReevaluateExpressionExecutor` evaluates conditional facet and histogram conditions through the query engine during
  writes and WAL replay. It catches the exception, logs a warning and treats the condition as matching nothing.
- `BidirectionalReferenceRewriter` requires `REFERENCED_ENTITY` of the counterpart in every scope its rows can live in
  (`counterpartScopes`), not only the requested ones, and declines otherwise. Skipping a widened scope without a type
  index is safe only because of that check - without it, owners reachable only through such a scope went missing.
- Default fill (#1583): `ReflectedReferenceSchema#withReferencedSchemaAfterSchemaChange` fills only scopes newly
  indexed by the binding; `EntityCollection#initSchema` (load) keeps the plain `withReferencedSchema` on purpose. A
  mutation never fills a scope the previous schema was already indexed in with no component
  (`ReferenceSchema#mayDefaultComponentsInScope`) - otherwise an unrelated change would silently "heal" a stored empty
  scope over indexes that were never built. Asking for the component there explicitly is the repair. The same holds
  for switching a reflected reference back to inherited scopes (`withIndexed(null)`): the inherited scopes are resolved
  from the reflected reference first, so the eligibility rule sees them.
- Because load re-binds without filling, a fill made by the cascade from the reflected-to reference
  (`EntityCollection#notifyAboutExternalReferenceUpdate`) must be **stored** by that cascade - it used to exchange the
  schema in memory only, which was harmless while everything it computed was re-derived on load. The restart check in
  `ReflectedReferenceDefaultComponentsFunctionalTest` caught the filled scope loading empty.
- A self-referencing pair (the reflected reference lives in the collection of the reference it reflects) cannot use
  that cascade: the collection would notify itself and then overwrite the result with the schema its own change stores.
  `EntityCollection#refreshReflectedSchemas` re-binds such references inside the schema being stored instead.
- Error messages offer the `scope(...)` / `inScope(...)` workaround only when the query spans another scope, and point
  a reflected reference inheriting its components at the reference it reflects.

## Verification

- `IndexedScopeRequiresEntityComponentTest` - the schema rule, plain and reflected, group-only and empty.
- `ReferenceWithoutEntityComponentQueryGuardFunctionalTest` - an `ALIVE` catalog holding the stored shape in-session:
  every query path refused (incl. `not`, `facetHaving`, `histogramHaving`, predecessor-ordered `referenceContent`,
  `referenceProperty` behind an `inScope`-narrowed filter, ungrouped reference histograms), facet summary and both
  workarounds still answer, and the refused close rolls the session back.
- `ReflectedReferenceDefaultComponentsFunctionalTest` - the four routes by which a reflected scope could land empty,
  each finding the archived owner inside the session, after commit and after an engine restart, plus the
  self-referencing pair whose original gains a scope later.
- `SchemaSerializationServiceTest.StoredShapesWithoutEntityComponent` - every stored shape loads unchanged, a stored
  empty scope stays empty on load and through later mutations, validation still refuses it.
- `ReferenceSchemaTest` (default fill and its eligibility rule), `SetReferenceSchemaIndexedMutationTest` (an unbound
  reflected reference), `HavingTranslatorHelperTest` (message variants), and
  `IndexedScopeRequiresEntityComponentTest.Refused#shouldKeepRefusingAStoredEmptyScopeAfterSwitchingToInheritedScopes`.
- `BidirectionalReferenceRewriterTest.WidenedScopeWithoutCounterpartIndex`, `ReevaluateExpressionExecutorTest`
  (refusal absorbed, any other failure propagated), and the #1583 pins in the bidirectional rewrite suites, no longer
  `@Disabled`.

## Consequences & open follow-ups

- **Upgrading a catalog that carries the shape blocks its schema changes.** Every session that changes the schema -
  including an upsert evolving it automatically - is refused at close (`ALIVE`: rolled back; warm-up: the catalog is
  deactivated) until every such reference gains `REFERENCED_ENTITY`, all in one session. Documented in
  `documentation/user/en/use/schema.md#indexed-components`. Option F is the escape hatch if this proves too harsh.
- **The repair does not index stored data (#409).** Entities written before the component was added stay invisible to
  queries over the reference until they are written again; every message and the documentation say so.
- **Some refusals depend on data.** When index selection finds no candidate (an empty collection, a hierarchy selector
  matching no node, an owner with no reference keys) the planner answers empty before any lookup runs, so no guard
  fires. The empty answer is correct in each of those cases; only consistency suffers. A per-constraint pre-check
  would close it, and was set aside for the chokepoint placement.
- **A WAL recorded before this change replays through the new fill.** A scope stored empty is not filled (the
  eligibility rule), and a scope newly indexed on a populated collection is the ordinary #409 situation.
- **A histogram write on a stored group-only reference throws**: the value inserted before any group is known needs
  the entity type index. Not pursued - the rule makes the shape impossible to create, and the repair removes it.
- The dead `validateScopeSettings(..., referencedGroupType)` overload refusing `REFERENCED_GROUP_ENTITY` without a
  group type is still never called; fixtures declare that combination, so reviving it needs an audit first.

## Related work

- `2026-09-17-row-scoped-reference-having-body` - found the shortcut, added the `groupHaving` guard this record
  tightens to "every queried scope", and filed #1601.
- `2026-09-15-bidirectional-reference-counterpart-rewrite` - filed #1583 and pinned it with the rows this record
  un-disables; its rewriter now declines on a widened scope lacking the entity component.

## Timeline

- **2026-09-17** - #1601 filed from the row-scoped `referenceHaving` work.
- **2026-09-25** - analysis; group-family answering rejected.
- **2026-09-28** - schema rule, query guard, #1583 default fill; adversarial review (Opus, Codex) found the rewriter
  under-report, the unrelated-change fill, the `referenceContent` false refusal, three unguarded readers and the
  write-path abort, all fixed; loud-everywhere upgrade behaviour confirmed; a restart test exposed that the
  cascaded fill was never stored; a bug hunt found the self-referencing pair never re-bound and the switch to
  inherited scopes filling a stored empty scope, both fixed. Full functional suite: 24,616 tests, 0 failures.
