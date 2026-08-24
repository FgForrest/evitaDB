---
title: Refresh a reference's representative key whenever its built state is replaced
date: 2026-08-24
updated: 2026-08-24 13:20
status: accepted
kind: fix
issues: [1438]
prs: [1442, 1443]
areas: [evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure]
supersedes: []
superseded-by: []
relates: []
---

# Refresh a reference's representative key whenever its built state is replaced

`InitialReferencesBuilder.createReference` registers a brand new — and therefore still empty —
reference in its `BuilderReferenceBundle` and only afterwards hands the key back so the caller can
populate the attributes. The key it registers is derived from the *default* representative values,
normally `[null]`, and nothing refreshed it once the caller filled the attributes in. Every
replacement of a reference's built state now re-keys it in the bundle, so a provisional key never
outlives the state it was derived from. The same work fixes the reference counter the bundle keeps
alongside those keys, which double-counted in two places.

## Why

An `EvitaIncrementalIndexJob` in production failed with

```
InvalidMutationException: Cannot add duplicate reference `media` with the same representative
attributes [null] as it would be indistinguishable from existing reference with internal id -7!
```

on the **third** duplicate of one business key. Two duplicates always worked; the third never did.
The client was driving entity proxies, and every `getOrCreate`-style reference method in
`SetReferenceMethodClassifier` routes to `createReference`, runs the caller's consumer and only then
propagates the builder back — precisely the create-then-populate order that leaves a stale key
behind.

The loud rejection was only half of it. Because the bundle never learned the real representative
values, two references that genuinely *were* indistinguishable were silently accepted into the
entity instead of being refused: duplicate detection was not merely over-strict on the third
reference, it was also blind on the second. That half never raised an exception and would have
written indistinguishable references into an entity.

### Previous state

`createReference` registers into the bundle at creation time; `addOrReplaceReferenceMutations` — the
path the proxy commits through — only refreshed the reference *collection* and never touched the
bundle. So:

- reference #1 registers under the generic key and is fine (a lone reference carries no attribute
  values in its key);
- reference #2 triggers `convertToDuplicateReference`, which re-derives **its predecessor's** key
  from that predecessor's already-built state — self-healing #1, but registering #2 itself under
  `[null]`;
- reference #3 computes `[null]` too, collides with #2's stale slot, and is rejected.

The `[null]` slot was vacated by accident until `b8400f922` (2026-07-25, first released in
v2026.2.0): `upsertDuplicateReference` used to remove `previousRRK` unconditionally, including when
it was the key just written. That commit correctly guarded the removal, which is why the failure
first appears in 2026.2 even though the missing refresh dates back to v2025.7.0. Empirically:
v2025.7.0 pass, v2026.1.20 pass, v2026.2.0 fail, `a7c9b78ba` fail.

`ExistingReferencesBuilder` was never affected, and the reason is structural rather than lucky: its
`addOrReplaceReferenceMutations` passes the **populated** builder to `replaceChangeSet` →
`upsertWithDuplicateReferenceConversion`, so it re-registers on every commit and the stale-key window
never opens. `InitialReferencesBuilder` was the only commit path that skipped bundle re-registration
entirely.

## Options considered

### Option A — refresh the key at the collection choke point (chosen)

Add `BuilderReferenceBundle.refreshRepresentativeKey`, which recomputes a registered reference's
representative key from its current state, and call it from
`InitialReferencesBuilder.addOrReplaceReferenceInternal` — the single private method every path that
replaces a reference's built state funnels through.

- **Pros:** fixes both known entry points (the proxy's `addOrReplaceReferenceMutations` and
  `mutateReference(ReferenceAttributeMutation)`) and any future one, because it sits at the choke
  point rather than at a call site. Runs before the collection is touched, so a rejected re-key
  leaves the builder unchanged.
- **Cons:** one more method on a class that already carries several near-synonymous upsert paths;
  the choke point runs on every reference replacement, including the many where it is a no-op.

### Option B — revert the `previousRRK` guard added by `b8400f922` (declined)

Bisection points straight at `b8400f922`, and its guard is the line that stopped vacating `[null]`.

- **Pros:** one-line change; restores the last known-good behaviour on this exact scenario.
- **Cons:** the unconditional removal it would restore deletes the key the method has just written.
- **Rejected because:** that guard fixes a real, separate defect — without it a reference is dropped
  from `repRefKeysToInternalPk` while left in `internalPkToRepRefKeys`, and an indistinguishable twin
  takes over the vacated slot. Reverting trades the loud third-duplicate rejection for silent
  reference loss. The vacating was never the design; it was a side effect of a bug that happened to
  cancel this one out. **Revisit if** the guard is ever shown to be unnecessary — but the refresh in
  Option A still has to exist, because the guard is not what keys references correctly.

### Option C — do not register in `createReference` until the reference is populated (declined)

Let `createReference` hand back a key without touching the bundle, and register once the caller
commits the populated builder.

- **Pros:** removes the provisional key entirely rather than repairing it; no re-keying needed.
- **Cons:** opens a window in which a reference exists in the collection but not in the bundle.
- **Rejected because:** `convertToDuplicateReference` asserts the *generic* slot is already present
  when the second reference for a business key arrives, and `getReference(key)` has to resolve
  between create and populate — the proxy calls it immediately. Both would need redesigning, and a
  caller that creates a reference and never commits it would leave the two structures permanently out
  of step. **Revisit if** the bundle ever becomes derived at build time (as `ExistingReferencesBuilder`'s
  effectively is) rather than maintained incrementally.

### Option D — call the existing `upsertDuplicateReference` from the choke point (declined)

Mirror `ExistingReferencesBuilder` and re-register through the existing upsert; it already drops the
stale `previousRRK` when it differs, so it *does* re-key correctly.

- **Pros:** no new method; makes the two builders symmetric.
- **Cons:** it is an insert-or-update that presumes the caller intends registration, and the choke
  point cannot promise that.
- **Rejected because:** all three of its preconditions are false at the choke point. It asserts
  `representativeAttributeDefinition != null`, so it throws for every bundle not in duplicate mode —
  which is most of them. It registers references it has not seen, but the choke point also runs
  *inside* `createReference` **before** the bundle registration, so it would register the reference
  twice and corrupt the create flow. And it would promote a lone reference held under a generic key
  into an attribute-bearing key, which is wrong while it is still the only one. The choke point needs
  a *no-op-unless-already-keyed-with-attributes* operation; upsert is the opposite by construction.
  (The counter inflation that first argued against this option was a real bug and is fixed here —
  it is no longer a reason, but the three preconditions are structural and remain.)

## Decision

**Chosen: Option A.** It is the only option that keys references from the state they actually have at
the moment that state becomes current, which is the invariant the bundle needs and none of the others
establish. B restores a symptom-level accident, C requires redesigning two contracts to remove a key
that is cheap to refresh, and D is the right *shape* but wrong preconditions.

## Key technical details

- `BuilderReferenceBundle.refreshRepresentativeKey(ReferenceContract)` — recomputes the key, claims the new
  slot with `putIfAbsent` **before** releasing the old one so a collision is a no-op on the bundle,
  and throws `InvalidMutationException` when the new key belongs to a different internal primary key.
- It is a no-op when the bundle never entered duplicate mode (`representativeAttributeDefinition ==
  null`), or when the reference is registered under a generic key
  (`representativeAttributeValues().length == 0`) — generic keys carry no attribute values and cannot
  go stale. Those two guards are the whole reason it is not just `upsertDuplicateReference`; see
  Option D before merging them.
- `InitialReferencesBuilder.addOrReplaceReferenceInternal` calls it *before* mutating the collection.
  The ordering is load-bearing: it is what makes a rejected re-key leave the builder untouched.
- `RepresentativeReferenceKey` normalizes its `ReferenceKey` to `(referenceName, primaryKey)`, so keys
  from different internal primary keys compare equal — that is what makes collision detection work at
  all, and what makes the `putIfAbsent` check meaningful.
- **`usedReferenceKeys` counts references per business key, and both writers over-counted.**
  `upsertDuplicateReference` bumped on every call, so a builder that re-registers a reference on each
  commit counted the same reference repeatedly; `convertToDuplicateReference` bumped for the incoming
  reference and then called `upsertDuplicateReference`, which bumped for it again. The bump is now
  gated on `previousRRK == null` — an internal primary key the bundle has not seen before — and
  `convertToDuplicateReference`'s own bump is gone, since the call that closes it does the counting.
  Its unreachable `cardinality == null ? 2` fallback went with it: the method asserts the generic slot
  is present, and that slot is only ever written by `upsertNonDuplicateReference`, which sets the count
  to 1.
- The exception message in `upsertDuplicateReference` reported `internalPk` — the *incoming*
  reference — where it meant `previousValue`, the existing one. The production trace's "internal id
  -7" was the reference being added, not the one it collided with, which sent the investigation after
  the wrong reference. Now reports `previousValue`.

## Verification

`CreateReferenceDuplicateBookkeepingTest` — 9 tests, all green; **5 fail without the fix**:

- `shouldAcceptThreeDuplicatesCreatedViaCreateReference` and
  `shouldAcceptManyDuplicatesCreatedViaCreateReference` (6 duplicates) reproduce the production
  failure verbatim — `Cannot add duplicate reference ... [null] ...`.
- `shouldThrowExceptionWhenDuplicatesShareRepresentativeAttributes` pins the other half: without the
  fix **nothing is thrown** and two identical references are accepted.
- `shouldRefreshRepresentativeKeyWhenAttributeMutationIsApplied` and
  `shouldThrowExceptionWhenAttributeMutationCollidesWithSibling` cover
  `mutateReference(ReferenceAttributeMutation)`, the second entry point the choke point fixes.
- `shouldAcceptThreeDuplicatesCreatedViaSetOrUpdateReference` is the control: the
  populate-then-register path was always healthy and stays so.
- The two `ExistingReferencesBuilder` tests pass with *and* without the fix, confirming that path's
  structural immunity rather than assuming it.

`BuilderReferenceBundleTest` — 23 tests, all green; the 3 new counting tests
(`shouldStopReportingReferenceKeyOnceAllDuplicatesAreRemoved`,
`shouldNotCountRepeatedUpsertsOfTheSameDuplicateReference`,
`shouldNotCountReKeyingOfAnAlreadyRegisteredDuplicateReference`) fail without the counting fix, each
on `containsReferenceKey` still answering `true` after every reference under the key was removed.
`count()` was correct throughout — it reads `internalPkToRepRefKeys`, not the counter — which is what
localised the bug to `usedReferenceKeys`.

Regression: full `unitAndFunctional` suite, 20 919 tests.

## Consequences & open follow-ups

- The choke point runs on every reference replacement. It is two map lookups and an early return in
  the overwhelmingly common non-duplicate case, but it is on the write path.
- `mutateReference(InsertReferenceMutation)` adds a reference to the collection without registering it
  in the bundle at all. `refreshRepresentativeKey` cannot help — it only re-keys what is already registered.
  Not investigated; flagged so the next reader does not mistake this record for a claim that every
  path is now consistent.
- `convertToDuplicateReference` still does `internalPkToRepRefKeys.remove(genericInternalPk)` followed
  by `put(previousRefInternalPk, ...)` on what is provably the same key. Harmless, left alone
  deliberately: it is on a path this change already touches and shrinking it further would have
  widened the diff without changing behaviour.

## Timeline

- **2026-08-24** — reported from production, diagnosed live over JDWP, issue #1438 filed
- **2026-08-24** — fixed, counter defect found and fixed alongside, verified, recorded
