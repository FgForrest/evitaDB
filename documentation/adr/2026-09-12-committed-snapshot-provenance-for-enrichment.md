---
title: Derive the enrichment shortcut from committed-snapshot provenance, not from a caller-supplied flag
date: 2026-09-12
updated: 2026-09-13 09:55
status: accepted
kind: fix
issues: [1547, 1559]
prs: [1548]
areas: [evita_engine/src/main/java/io/evitadb/core/collection, evita_engine/src/main/java/io/evitadb/core/query/response, evita_engine/src/main/java/io/evitadb/core/query/filter, evita_engine/src/main/java/io/evitadb/core/query/fetch]
supersedes: []
superseded-by: []
relates: [2026-09-11-reference-name-narrowing, 2026-09-13-per-entity-io-statistics-attribution]
---

# Derive the enrichment shortcut from committed-snapshot provenance

`EntityCollection#enrichEntityInternal` may return its input untouched when the request asks for
nothing the entity does not already carry. Deciding *when* that is safe used to be the caller's job.
It is now derived from the data's own provenance, and the entity carries what it needs to prove it.

## Why

The shortcut took a `skipWhenNothingWidens` boolean. Its own JavaDoc admitted the hazard: the flag
was only safe for callers whose entity had been loaded from the catalog version the enrichment would
read, and nothing enforced that. Three call sites passed it, two with `false` and one with `true`,
and the difference between them was a convention no compiler or test could check.

### Previous state

`limitAndFetchExistingEntities` passed `true` and skipped unconditionally. `enrichEntity` and
`fetchEntityDecorator` — both public — passed `false` and always paid the storage round trip. The
arrangement happened to be safe, for a reason nobody had written down: the only site that trusted
the shortcut was the only site whose decorators had not yet been narrowed by `limitEntity`, and the
shortcut's six-predicate identity test can only hold for an un-narrowed decorator.

## Options considered

### Option A — carry committed-snapshot provenance on the decorator (chosen)

`ServerEntityDecorator` records the catalog identity and version its data were materialised at, and
records them **only** when a read performed at that moment yields committed, immutable data:
`CatalogState.ALIVE` with no transaction bound. Everything else — a warming-up catalog, a transaction
overlay, a cache restore — carries `UNKNOWN_CATALOG_VERSION` and a `null` catalog id, which can never
match a real snapshot.

- The decision moves to where the fact is known. A caller cannot get it wrong, because a caller is no
  longer asked.
- Costs a pointer compare and a `long` compare, against the body read it replaces.

### Option B — keep a flag, add clauses to the guard (declined)

Add `ALIVE`-state and transaction clauses to the condition at the call site.

- **Rejected because:** it fixes one consumer. The field would still mean "the version this was read
  at", so every future reader of `getCatalogVersion()` inherits the same wrong premise, and the next
  consumer has to rediscover all four ways a bare version number lies.

### Option C — compare entity versions instead of catalog versions (declined)

- **Rejected because:** reading the stored entity version requires reading the body, and that read is
  precisely what the shortcut exists to avoid. It also does not help across catalogs, where two
  entities routinely share a version.

### Option D — raise a conflict exception on stale input (declined)

- **Rejected because:** `ConflictingCatalogMutationException` is a writer-side signal. Enrichment runs
  routinely in read-only sessions with no transaction, and failing a read whenever some writer had
  committed would be an availability regression. Stale input is refreshed, not rejected.

## Decision

A catalog version identifies an immutable snapshot **only in `ALIVE` state, outside a transaction**.
That is the invariant the shortcut rests on, and it is now enforced at the point data are
materialised rather than assumed at the point they are reused.

## Key technical details

- `EntityCollection#readsCommittedSnapshot()` is the single predicate; `materialisedCatalogId()` and
  `materialisedCatalogVersion()` are the only way a decorator is stamped.
- **Two roles, one accessor.** `Catalog#getVersion()` is used both as the version a storage read is
  performed *at* — unconditional, whatever the state — and as the provenance stamped *on* the result,
  which is conditional. Collapsing them into one local reintroduces the defect. The site carries a
  comment saying so.
- `ServerEntityDecorator` has three construction families and the rule differs per family:
  materialising records fresh provenance, wrapping inherits it, unknown uses the sentinel. A
  constructor placed in the wrong family is a silent staleness bug, which is why the rule is stated
  in the class JavaDoc rather than left to the reader.
- `Catalog#setVersion` asserts a transaction is open, so a warming-up catalog's version never moves
  for its whole life, and a transaction's base version is visible throughout its body.

## Verification

`EntityEnrichmentVersionGuardFunctionalTest` builds its own catalog because every scenario mutates.
Four groups, each red before the fix: a warm-up fetch stamped a frozen version
(`expected: <-1> but was: <0>`); an uncommitted overlay was served to a read-only session
(`expected: not same but was: <Entity PRODUCT ID=1, ? code : changed>`); the same after rollback; and
a decorator from another catalog was answered with its own data. `ServerEntityDecoratorCatalogVersionTest`
pins the construction-time rules at unit cost. Full functional suite: 21,201 tests, no regression.

Calibration: delete either clause of `readsCommittedSnapshot()` and the matching group goes red.

## Consequences & open follow-ups

- **The shortcut is unreachable from the public enrichment entry points**, and was before this change.
  `EntityDecorator#getXPredicate()` returns the *underlying* predicate while
  `createXPredicateRicherCopyWith(...)` derives from the *narrowing* one, so the six-predicate identity
  test cannot hold for any entity a client holds — every query and `getEntity` result is narrowed by
  `limitEntity`. The measured benefit lives at `limitAndFetchExistingEntities`, where decorators are
  not yet narrowed. Anyone planning to widen the shortcut should fix that asymmetry first, and should
  expect the public entry points to show no gain until they do.
- Cross-catalog reuse is now refused outright rather than silently answered. The underlying cause is
  older and broader: `DefaultEntityCollectionPersistenceService` decides whether to refetch from the
  *entity* version alone, and entity versions are per-entity, so two catalogs' primary key 1 agree
  routinely. The refusal closes the enrichment route; other consumers of that comparison were not
  audited.
- Inside a transaction the shortcut never fires. That is deliberate and costs one body read per
  prefetched entity in a read-write session; the profiled read path is read-only sessions.

## Related work

- `2026-09-11-reference-name-narrowing` — same branch and same read path; that record covers what is
  decoded, this one covers when a decode can be skipped entirely.

## Timeline

- 2026-09-11 — `skipWhenNothingWidens` replaced by a catalog-version check.
- 2026-09-12 — review found the version alone insufficient in four ways; replaced by committed-snapshot
  provenance carrying catalog identity as well as version.
