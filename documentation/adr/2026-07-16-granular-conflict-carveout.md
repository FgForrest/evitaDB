---
title: Carve out granular conflict items from the coarse entity conflict scope
date: 2026-07-16
updated: 2026-07-31 19:46
status: accepted
kind: fix
issues: [503]
prs: [1287]
areas: [evita_api/requestResponse/mutation/conflict, evita_api/requestResponse/data/mutation]
supersedes: []
superseded-by: []
relates: []
---

# Carve out granular conflict items from the coarse entity conflict scope

A per-item `ConflictResolutionOverride.GRANULAR` (or a dimension in an entity's granularity set)
promised to isolate concurrent writes of that item from writes touching other parts of the same
entity, but did not deliver when the sibling writer ran under the coarse `ConflictPolicy.ENTITY`
default. The coarse writer's monolithic `EntityConflictKey` was an ancestor of every finer key on
the entity — including the carved-out one — so disjoint writes falsely conflicted. The fix splits
that key into a full entity key (whole-entity operations) and a new `EntityResidualConflictKey`
(the shared, non-carved-out surface), so a coarse writer of an ordinary field no longer collides
with a writer of a carved-out item.

## Why

EdeeShop writes one `Product` entity from two independent, concurrently-scheduled jobs: an
incremental indexer writing the authoritative product data (attributes, prices, references), and a
feed-snippet job writing only `feed-<code>` associated data plus one expiration attribute. The two
touch disjoint parts of the same primary key but serialized against each other on the entity lock.
Declaring just the feed items `GRANULAR` — the documented use case — had no effect; the only
workaround without an evitaDB change was moving the *whole* entity to a full granularity set, which
needlessly relaxed serialization of the real product data too.

The constraint that made this non-obvious: `EntityConflictKey` was doing two jobs at once — "the
entity's existence/identity changed" (removal, forced creation, scope change, which must still
conflict with carved-out items) and "some non-granular field was touched" (the policy-coarseness
catch-all, which must conflict only with the shared surface). Any fix had to separate those two
meanings without giving the matcher (`IncomingConflictScope`) live schema access, and without
breaking write-path/recompute-path agreement — conflict keys are generated at WAL-write time and
must be reproducible from schema alone at recompute time.

### Previous state

`EntityMutation.getConflictKeyStream` added a single `EntityConflictKey(type, pk)` to the emitted
key set whenever at least one local mutation produced no granular key (`atLeastOneKeyMissing`) or
the mutation was a forced creation (`MUST_NOT_EXIST`), under coarse `ENTITY` policy. Every granular
key's `parentConflictKey()` (`AssociatedDataConflictKey`, `AttributeConflictKey`, `PriceConflictKey`,
`ReferenceConflictKey`, …) unconditionally returned that same `EntityConflictKey` as its parent, so
`IncomingConflictScope`'s containment check always matched a coarse writer against a granular one,
regardless of whether the granular item was declared carved out.

## Options considered

The source analysis (`ASSIGNMENT.md`) weighed one real alternative to fixing the behavior, plus a
variant of the chosen fix that it noted "arrives at the same place from the other side":

### Option A — split the entity key into full + residual (chosen)

`EntityConflictKey` keeps meaning "whole-entity operation" (removal, forced creation, scope
change) and stays an ancestor of every finer key, carved-out or not. A new
`EntityResidualConflictKey(entityType, entityPrimaryKey)` is emitted by the policy-coarseness
fallback instead, represents only the shared surface, conflicts with other shared-surface writers
by equality, and is a **sibling** of the granular keys rather than their ancestor.

- **Pros:** delivers the `GRANULAR` contract exactly as documented; no schema access needed at
  match time (the residual key carries no derived payload, so write-time and recompute-time keys
  agree by construction); whole-entity operations keep conflicting with everything without change.
- **Cons:** touches five files across two packages (`data/mutation` and `mutation/conflict`) and
  needs a matching `sharedSurface` flag threaded through the two range-constrained delta key types
  so a shared-surface delta still serializes against a coarse absolute writer.

*(A variant — "always emit per-item keys even under coarse policy, plus an explicit shared-surface
key" — was noted in the analysis as "essentially the residual key arrived at from the other side";
it collapses to the same design and was not pursued separately.)*

### Option B — documentation-only: correct the `GRANULAR` Javadoc (declined)

Leave the behavior as-is and instead document that per-item granularity only isolates an item
against *other writers that are themselves granular on the parts they touch* — i.e. it requires
sibling parts to opt into granularity too. EdeeShop would then adopt the full-granularity
workaround knowingly.

- **Pros:** zero code risk; no change to conflict-key containment semantics.
- **Cons:** the shipped feature keeps failing to deliver what its own Javadoc promises; the
  workaround it forces (moving the whole entity to a granularity set) needlessly relaxes
  serialization guarantees for the entity's real data.
- **Rejected because:** the analysis itself labeled this "the fallback, not the preferred outcome."
  The surgical carve-out was judged worth the engineering cost rather than weakening the documented
  contract.

## Decision

**Chosen: Option A.** Implemented as `EntityResidualConflictKey`, a schema-payload-free record
whose only relationships are equality with itself and containment by the full entity/collection/
catalog keys — it never needs to act as an ancestor of a granular key, because every absolute
granular key (`AttributeConflictKey`, `AssociatedDataConflictKey`, `PriceConflictKey`,
`ReferenceConflictKey`, `ReferenceAttributeConflictKey`, `HierarchyConflictKey`) is only ever
emitted for an item that was carved out in the first place.

## Key technical details

- `evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/conflict/EntityResidualConflictKey.java`
  — new record `(entityType, entityPrimaryKey)`; `parentConflictKey()` returns `EntityConflictKey`;
  `conflictScope()` returns `ConflictScope.ENTITY`; carries no schema-derived payload by design (see
  its Javadoc for why write-path and recompute-path agree without one).
- `evita_api/.../data/mutation/EntityMutation.java` (`getConflictKeyStream`) — the coarse fallback
  now branches: `expects == MUST_NOT_EXIST` (forced creation) still emits the full
  `EntityConflictKey`; otherwise (`atLeastOneKeyMissing` only) emits `EntityResidualConflictKey`.
  `COLLECTION`/`NONE` policy branches and the null-pk branch are unchanged.
- `evita_api/.../data/mutation/scope/SetEntityScopeMutation.java` — no longer free-rides on the
  fallback; emits the full `EntityConflictKey` explicitly under `ENTITY` policy (`CollectionConflictKey`
  under `COLLECTION`, empty otherwise), so a scope change still conflicts with carved-out items.
- `evita_api/.../mutation/conflict/AttributeDeltaConflictKey.java` and
  `ReferenceAttributeDeltaConflictKey.java` — gained a `sharedSurface` boolean record component.
  `parentConflictKey()` routes to `EntityResidualConflictKey` when `sharedSurface == true`, else to
  the absolute attribute/reference-attribute key (unchanged). Computed at emission time in
  `ApplyDeltaAttributeMutation` / `ReferenceAttributeMutation` from
  `context.shouldEmit*Key(...)`. `aggregationKey()` / `DeltaAggregationKey` deliberately excludes
  the flag, so deltas on the same attribute keep aggregating together regardless of carve-out
  status.
- **Invariant preserved on purpose:** `PriceInnerRecordHandlingStrategyConflictKey.parentConflictKey()`
  still returns the full `EntityConflictKey` — inner-record-handling changes stay on the
  whole-entity side, not the residual side.
- **Untouched by design** (per the fix's own scope boundary): the `SetPriceInnerRecordHandlingMutation`
  containment gap under `PRICE` granularity (a sibling-not-ancestor issue, tracked separately); no
  schema API additions; the `GRANULAR` Javadoc contract text itself (this fix makes the existing
  promise true rather than needing to change it).

## Verification

`git show 617a48fac` (the fix) touches
`EvitaTransactionalFunctionalTest`, `DataMutationConflictKeyEmissionTest` and
`IncomingConflictScopeTest`; `cc597cdd3` (same-day review follow-up) refines the delta key naming
and `toString()` with no behavioral change. Test names confirm every row of the fix assignment's
test matrix was implemented, not just proposed — for example:

- `EvitaTransactionalFunctionalTest`: *"A coarse writer of a plain attribute and a writer of a
  carved-out associated data item do not conflict — the granular carve-out fix"*, plus
  entity-removal, scope-change and forced-creation counterparts that still conflict, and a
  granularity-set-flavor case (*"an attribute writer and an associated-data writer do not conflict
  when `ASSOCIATED_DATA` is carved out via the entity's granularity set (no per-item overrides)"*).
- `DataMutationConflictKeyEmissionTest`: *"A non-granular mutation triggers the coarse residual
  key, not the full entity key, under ENTITY policy"*, *"Forced creation emits the full entity key
  alongside a granular key, never the residual key"*, plus the range-constrained-delta
  shared-surface/carved-out pair for both attribute and reference-attribute deltas.
- `IncomingConflictScopeTest`: a *"Residual (shared-surface) containment"* nested class covering
  both directions against granular keys, the full entity key, collection/catalog keys, and the
  commutative delta cases.
- `TransactionManagerConflictWindowTest` gained 117 lines covering the recompute path.

This record does not re-run the suite — the fix has been on `dev` since 2026-07-16 and merging it
went through the project's normal PR/CI gate. Numbers were not re-captured for this record; if that
matters, run `IncomingConflictScopeTest`, `DataMutationConflictKeyEmissionTest`,
`TransactionManagerConflictWindowTest` and `EvitaTransactionalFunctionalTest` directly.

## Consequences & open follow-ups

- The analysis's open question 1 ("is `GRANULAR` intended to work with coarse siblings, or only as
  finer control within an already-granular entity?") is resolved in favor of the Javadoc's original
  promise: it now works against coarse siblings.
- Open question 2 ("should per-dimension carve-out be expressible without moving the whole entity
  granular?") is answered by the granularity-set-flavor test case: an entity can declare a
  dimension (e.g. `ASSOCIATED_DATA`) in its granularity set with *no* per-item overrides and still
  get the carve-out against a coarse sibling writer — no new schema surface was needed for this.
- Still open, out of scope for this fix: the `SetPriceInnerRecordHandlingMutation` containment gap
  under `PRICE` granularity (inner-record-handling key is a sibling, not an ancestor, of price
  keys) — noted as "tracked separately" by the fix assignment, but no issue number is recorded in
  the source material or in this repository's issue references; a future reader chasing it should
  search for it explicitly rather than assume it exists.

## Timeline

- **2026-07-16** — analysis and fix assignment written (exact authoring date not preserved — the
  source documents carry no date and were only added to git in a later bulk commit); both options
  costed, Option A chosen and implemented test-first.
- **2026-07-16** — PR #1287 merged into `dev` (`617a48fac` fix, `cc597cdd3` same-day review
  follow-up), on top of the base granular-conflict-resolution feature (#503, PR #1286, merged the
  same day).
- **2026-07-31** — planning documents retired, replaced by this record.
