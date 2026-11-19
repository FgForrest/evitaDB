---
title: Direct entity calls check the requested content against the schema, as a planned query does
date: 2026-11-17
updated: 2026-11-19 18:33
status: accepted
kind: fix
issues: [1949]
prs: [2004]
areas:
  - evita_engine/src/main/java/io/evitadb/core/collection
  - evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/reference
  - evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver
supersedes: []
superseded-by: []
relates: [2026-11-05-nested-inscope-requested-scopes, 2026-10-21-one-locale-per-entity-level]
---

# Direct entity calls check the requested content against the schema, as a planned query does

`getEntity`, `enrichEntity` / `enrichOrLimitEntity`, `upsertAndFetchEntity`, `deleteEntity`,
`deleteEntityAndItsHierarchy`, `archiveEntity` and `restoreEntity` plan no query, so they never ran the planner's
content checks. They now run `EntityFetchContentVerifier`, a walk of the requested fetch tree that shares the
planner's leaf checks, before they read or change any entity. They refuse what a planned query refuses: an unknown
attribute, associated data or reference name, and a named localized attribute or associated data whose fetch level
has no locale. The gRPC driver's enrichment propagates the entity's locales, as the embedded session does.

## Why

A planned query and a direct call answered the same request differently. `queryOne` refused
`entityFetch(attributeContent("label"))` without a locale (`EntityLocaleMissingException`); `getEntity` returned the
entity without the attribute, and reading it then threw `ContextMissingException` - far from the request that caused
it. Unknown names were refused by a query and silently ignored by a direct call. The same held for the bodies of
`referenceContent` and the parents of `hierarchyContent`.

The constraint that shaped the fix: `getEntity` is a hot read path. Some clients call it once per entity, and the
GraphQL unknown-entity list calls `enrichEntity` once per found entity. A check that allocates, or plans, on every call
costs every reader who never asks for named localized data.

### Previous state

Only `QueryPlanner` runs `ExtraResultPlanningVisitor`, whose `AttributeContentTranslator` and
`AssociatedDataContentTranslator` check names and locales for every fetch level. The direct calls of
`EntityCollection` ran `assertScopeUsageValid` and then fetched; nothing compared the content they asked for with the
schema. The split was older than the nested-scope work: ADR `2026-11-05-nested-inscope-requested-scopes` recorded it
as an open follow-up.

## Options considered

### Option A - a schema walk at the direct entry points, sharing the planner's leaves (chosen)

A static walk of the request's `entityFetch` runs next to `assertScopeUsageValid` at each entry point. It visits the
levels the planner visits (entity, reference attributes, referenced entity and group bodies, hierarchy parents,
nested levels), carries one "a locale is available" flag down the tree, and calls the same leaf checks the planner
calls. A pre-scan returns at once when no level names anything.

- **Pros:** no planning on the hot path; nothing is allocated for requests without named content; one leaf rule for
  both paths.
- **Cons:** the derivation of a level's locale exists twice (the walk's flag and
  `ExtraResultPlanningVisitor#getEntityFetchRequest`), so a change to the locale rule must change both.

### Option B - run `ExtraResultPlanningVisitor` in the direct calls

- **Pros:** one implementation; parity by construction.
- **Cons:** the visitor needs a filtering formula, target indexes and a filter visitor, registers prefetch
  requirements and runs every require translator - none of which a fetch by primary key has.
- **Rejected because:** it puts query planning on the hottest read path to answer a question a schema lookup answers.
  Revisit if direct calls ever plan a query for another reason.

### Option C - check lazily, where the fetch reads a level

- **Rejected because:** the answer becomes data-dependent - an owner without references passes, the same request for
  an owner with them is refused. The nested-scope work removed exactly that split from the planned path.

### Option D - check in `EvitaSession#getEntityInternal`

- **Rejected because:** it misses `upsertAndFetchEntity`, the delete, archive and restore calls, and every caller of
  `EntityCollection` that does not come through the session.

### Option E - check the reduced request (`EvitaRequest#getEntityRequirement`)

- **Pros:** the reduced request is what the fetch reads.
- **Rejected because:** the planner checks the requirements as the client wrote them. With
  `attributeContent("label")` next to `attributeContentAll()` the reduced request holds only the bulk content, so a
  query refuses the named attribute and the direct call would accept it. The project owner's rule: a named localized
  attribute or associated data without a locale is refused even next to bulk content; bulk content alone is accepted.

### Option F - check `upsertAndFetchEntity` before the mutation evolves the schema

- **Rejected because:** with automatic schema evolution the mutation may add the very attribute the fetch names, and
  a check against the old schema refuses a valid call. The check runs in `upsertEntityInternal` after
  `verifyOrEvolveSchema` and before the mutation is applied.

### Option G - skip the check for calls the engine makes itself

- **Rejected because:** there is nothing to skip. The engine's own fetches (`fetchEntity`, `fetchEntityDecorator`,
  persistence) never reach the seven entry points. The one repeated server-side caller is the GraphQL unknown-entity
  list (`ListUnknownEntitiesDataFetcher`, `enrichEntity` per entity), and its requirements carry the entity's locales,
  so it passes the pre-scan or the walk. The JMH run found 0-64 B of allocation per call for the check (see
  Verification), so the skip would buy nothing measurable. Revisit if a timing run on a quiet machine shows a cost
  on that path.

## Decision

**Chosen: Option A.** It gives one rule for a query and a direct call without planning on the hot path. The
duplicated locale derivation is the price; it is pinned by tests that run every case through both paths. Option B
wins only if a fetch by primary key starts planning anyway.

## Key technical details

- Entry points: `EntityCollection#getEntity`, `#enrichEntity`, `#deleteEntity`, `#deleteEntityAndItsHierarchy`,
  `#archiveEntity`, `#restoreEntity` call `EntityFetchContentVerifier#verifyEntityFetchContent` right after
  `assertScopeUsageValid`; `#upsertEntityInternal` calls it for the fetching variant only
  (`returnType == EntityWithFetchCount.class`). **A new direct entry point must call it too.**
- Shared leaves: `AttributeContentTranslator#verifyAttributes` and
  `AssociatedDataContentTranslator#verifyAssociatedData` take a boolean "locale available" and serve both paths. A
  level has a locale when its owner has one, when it names its own `dataInLocales`, or when the reference filter gives
  the body one (`QueryUtils#resolveReferenceBodyLocale`). A change to that rule (the `and`-only locale per level,
  #1951) must change the walk and `ExtraResultPlanningVisitor#getEntityFetchRequest` together.
- The unique-in-scope exemption uses the request's root scopes at every level, as the planner does for the main fetch
  (`ExtraResultPlanningVisitor#executeEntityFetchInContext` starts it with the query's searched scopes even below a
  require-level `inScope`, and every fetch context below it inherits them).
- Missing locales are collected across all levels into one `EntityLocaleMissingException`, without duplicate names;
  an unknown name throws at once.
- Unmanaged referenced and group types are skipped, as the fetch skips their bodies; managed siblings are still
  checked. A managed reference whose target schema does not exist yet throws `EvitaInvalidUsageException`.
- Performance: the pre-scan reads raw constraint children only and allocates nothing when no level names an
  attribute, associated data or reference. The leaves allocate nothing on success. A managed body with named content
  costs an `Optional` (target schema lookup), an all-references request a `values()` iterator, a reference filter its
  locale extraction.
- Looks like a bug, is not: `entityFetch(attributeContent("label"), attributeContentAll())` without a locale is refused
  although the fetch would read only the bulk content - the requirements are checked as written.
- A refused `upsertAndFetchEntity` writes no entity. A schema it evolved or a collection it created shares the fate of
  the session's other writes: a refusal escaping the outermost session call marks the transaction rollback-only and
  closing the session discards them; one caught inside `updateCatalog` lets them commit; in warm-up they stay.
- The gRPC driver's `EvitaClientSession#enrichEntity` / `#enrichOrLimitEntity` add `dataInLocales(<locales of the
  entity>)` when the caller passes none (no locale = all locales), mirroring
  `EvitaSession#createEvitaRequestFactoryPropagatingOriginalLocale`. Both copy the caller's requirements into an
  explicitly typed `EntityContentRequire[]` before appending: the array a caller passes through the varargs may be
  narrower (`AttributeContent[]`), and `ArrayUtils#mergeArrays` types its result after its first argument, which
  threw `ArrayStoreException` in both sessions.

## Verification

- `GetEntityLocalizedAttributeRefusalTest` (59 tests) runs each case through a planned query and the direct call:
  every entry point, every level, unknown names, locale inheritance and isolation, the scope exemption, the
  as-written rule, schema evolution on write-and-fetch, and the three rollback shapes of a refused
  `upsertAndFetchEntity`. `EvitaClientReadOnlyTest` covers the driver's locale propagation and narrower requirement
  arrays, `EntityLazyLoadFunctionalTest` the embedded session's narrower arrays (both red with
  `ArrayStoreException` before their fix).
- Counterfactual campaign: 28 production mutations (each guard removed, the upsert check moved before evolution, each
  leaf skipped, the as-written rule replaced by the reduced request, the driver's propagation dropped from the wire),
  all 28 detected.
- An adversarial review of the implementation compared a planned query and a direct call on 120 request shapes
  (differing target and group schemas, nested references, hierarchy stops without a body, an archived owner, named
  next to bulk content): no disagreement.
- Full functional suite with the change: 39,231 tests, 0 failures, 0 errors.
- JMH (`DirectEntityFetchBenchmark`, `-prof gc`, a three-entity embedded fixture): the check allocates 0 B per call
  when no level names content (`EMPTY`, `BULK`) and 32-64 B when the entity level or two reference levels name a
  localized attribute, against 9-63 KB of the whole `getEntity` call. The baseline arm resolves the request's
  memoized locales and scopes first, because `getEntity` resolves them anyway (the scope lookup of the primary key,
  the locale predicates of the fetched entity); without that, the check seems to cost about 1.1 KB that it only
  moves earlier. Times were not quotable: the machine's load average rose from 7 to 32 during the run.

## Consequences & open follow-ups

- **Refused now, answered before** (upgrade note on #1949): the seven direct calls with an unknown attribute,
  associated data or reference name, or with a named localized attribute or associated data at a level without a
  locale, throw before they read or change anything.
- The check runs on the server, so it also refuses clients that do not add the entity's locales to an enrichment:
  a Java driver older than this change sends `enrichEntity` / `enrichOrLimitEntity` without them, and a named
  localized attribute or associated data in that call is now refused. Such callers pass `dataInLocales` or upgrade
  the driver.
- `deep-dive/transactions.md` says a client may catch an error and keep writing; on a bare session call any exception
  marks the transaction rollback-only, so that holds only inside `updateCatalog` / `execute`.
- The unique-in-scope exemption follows the root scopes on both paths, not the scopes a body is fetched in. Whether
  it should is a question for both paths together; no reproduction exists.

## Related work

- `2026-11-05-nested-inscope-requested-scopes` - recorded this split as an open follow-up; its scope check
  (`assertScopeUsageValid`) is the guard this one sits next to.
- `2026-10-21-one-locale-per-entity-level` - the locale rule per fetch level that the walk's flag follows.

## Timeline

- **2026-11-12** - split found during the nested-scope work, reproduced red, issue filed
- **2026-11-17** - implemented and verified
