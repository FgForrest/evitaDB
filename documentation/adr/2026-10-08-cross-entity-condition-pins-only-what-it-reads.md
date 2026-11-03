---
title: A cross-entity condition pins a reference axis it does not read only when it needs it to tell rows apart
date: 2026-10-08
updated: 2026-10-08 14:40
status: accepted
kind: fix
issues: [1726]
prs: [1727]
areas: [evita_engine/src/main/java/io/evitadb/index/mutation, evita_engine/src/main/java/io/evitadb/core/expression/trigger]
supersedes: []
superseded-by: []
relates: [2026-09-08-conditional-histogram-per-contribution-verdicts, 2026-09-17-row-scoped-reference-having-body]
---

# A cross-entity condition pins a reference axis it does not read only when it needs it to tell rows apart

The cross-entity trigger executor evaluates a `bucketedPartially` / `facetedPartially` condition once per
`(referencedEntity, group)` contribution, pinning both axes into the condition's `referenceHaving`. A pin on an
axis the condition never reads is now added only when nothing else can tell the reference rows apart. In
practice that means the referenced entity is pinned without a container only for conditions that read the
reference's own attributes, and the group is never pinned without a container.

## Why

Issue #1726, scenario B: a histogram value `$reference.referencedEntity?.attributes['basicUnitValue'] ?? 0`
gated by a group-only condition. The owner is written while the referenced entity does not exist yet, so its
own write indexes the `?? 0` default. When the referenced entity is then created with a value, the trigger adds
the real value and leaves the default. The owner counts twice and the histogram minimum is stuck at `0`. This is
likely the common production order, because owners are often published before newly created referenced
entities.

The default stayed because the removal pre-pass
([[2026-08-31-cross-entity-histogram-removal-pre-pass]]) answered that the owner had never contributed. The
unpinned condition, `referenceHaving(R, groupHaving(inputWidgetType = INTERVAL))`, does match that owner. The
executor's pin `entityHaving(entityPrimaryKeyInSet(1))` is what made it not match: every pin reaches the query
engine as a lookup of the **target entity**, and the engine does not see a reference whose managed target does
not exist. The owner's own write never consults the engine. It evaluates the expression per reference
(`ExpressionIndexTrigger#evaluate`) and does not care whether the target exists. The pin made the two paths
disagree, and the disagreement surfaced as a contribution that could never be removed.

The same mechanism hits the group axis. With a condition that reads only the referenced entity, a missing
group entity made the pre-pass and the post-state answer "does not match" while the owner was indexed. Changing
the referenced entity's value then removed nothing and added nothing, so the histogram kept the old value.

### Previous state

[[2026-09-08-conditional-histogram-per-contribution-verdicts]] (Option A) took both pin axes from the
contribution: `entityHaving(pk)` always, and `groupHaving(pk)` when the reference is grouped. Each pin was merged
into the condition's own container, or appended beside the condition when it had none. That record reasoned
about which reference a pin binds to. It did not consider that a pin also requires the target to exist.

## Options considered

### Option A — pin an unread axis only when the rows need it (chosen)

`parameterizeForContribution` merges a pin into the condition's own `entityHaving` / `groupHaving` exactly as
before. When the condition has no container for an axis, it appends a pin only if the rows cannot be told apart
without one:

- **Referenced-entity axis:** pinned only when the condition reads the reference's own attributes. Those
  predicates are row-specific and carry no container, so the row has to be named.
- **Group axis:** never pinned without a container. The referenced-entity pin already names the row: an owner
  holds one reference to a target, and `mergeVerdict` keys the verdicts by referenced entity alone.

A condition that reads only the group is answered exactly by the merged group pin. Every row of an owner in the
same group gets the same verdict by construction, and the contribution's owner set scopes the answer to the right
owners.

- **Pros:** the pre-pass and the owner's own write agree for every condition that does not read the missing
  target; there is one fewer nested query per contribution; the change is local to the executor.
- **Cons:** a condition that reads reference attributes still pins the referenced entity, so a missing target
  still reads as "does not match" for that shape (see *Consequences*).

### Option B — pin with a bare `entityPrimaryKeyInSet` instead of `entityHaving` (declined)

Inside a `referenceHaving` body, `entityPrimaryKeyInSet` names the row's referenced entity, which
[[2026-09-17-row-scoped-reference-having-body]] made row-scoped. It looked existence-independent.

- **Pros:** keeps a pin on every contribution, and needs no nested query.
- **Rejected because:** it is not existence-independent. Candidate discovery translates it against the
  `ReferencedTypeEntityIndex`, and `ReferencedEntityIndexPrimaryKeyTranslatingFormula` intersects the
  requested keys with the target's existing primary keys whenever the target type is managed. This was
  implemented and measured: the pre-pass filter `referenceHaving(R, and(groupHaving(and(…, pk 10)), entityPrimaryKeyInSet(1)))`
  answered `EMPTY` before the referenced entity existed and `[1]` after, and scenario B kept failing.

### Option C — make the query engine see references to missing managed targets (declined)

- **Pros:** would also fix the reference-attribute shape that Option A leaves open.
- **Rejected because:** it changes public query semantics. `referenceHaving(brand, entityPrimaryKeyInSet(5))`
  would start matching products whose brand 5 does not exist, for every client, to fix an internal
  re-evaluation. Revisit only if dangling references to managed entities are meant to become visible to
  filtering anyway.

## Decision

**Chosen: Option A.** The pins exist to make the verdict per row, not to assert that the target exists. Where
the condition already reads the target, the existence requirement comes from the condition itself and the
merged pin adds nothing new. Where it does not read the target, the requirement came from the pin alone, and
the owner-write path never shared it.

## Key technical details

- `ReevaluateExpressionExecutor#parameterizeForContribution` decides which axes are pinned without a container.
  `#injectPkScope` returns the clause untouched when there is nothing to merge into and no pin is wanted. The
  global path (`#parameterize`) still appends, because it is chosen only for conditions that read no reference
  axis, so it never reaches the fallback.
- Facet triggers share `evaluateCondition`, so a facet whose condition does not read the referenced entity now
  stays faceted when that entity is deleted. That is what the owner's own write does for a reference to a
  missing target.
- Do not "restore" an always-present referenced-entity pin for symmetry. It is the defect this record fixes.

## Verification

All tests are in `ConditionalBucketIndexingTest$CrossEntityReferencedEntityTriggerTest`, in both `WARMING_UP`
and `ALIVE`:

- `shouldReplaceDefaultContributionWhenReferencedEntityIsInsertedLater` reproduces scenario B. It fails before
  the change with `Owner PK 1 should NOT be in histogram 'valueHistogram' bucket 0`, and passes after it.
- `shouldMoveContributionWhenValueChangesWhileGroupEntityIsMissing` covers the group axis. It fails before the
  change with `should be in … bucket 50` (the new value was never added), and passes after it.
- `shouldReplaceDefaultContributionWhenUnsetValueIsSet` (scenario A, fixed by #1651) still passes.
- `ReevaluateExpressionExecutorTest.shouldPreventCrossReferenceFalsePositive` now asserts that a group-only
  condition gets the group pin and no `entityHaving`.

## Consequences & open follow-ups

- **A condition that reads reference attributes still cannot see a missing referenced entity.** Value
  `referencedEntity?.… ?? d` gated by `$reference.attributes[…]`, with the owner written before the referenced
  entity, still leaves the default behind, because that shape needs the row pinned and every available pin
  requires the target to exist. Fixing it needs a row pin the engine does not filter by existence, either an
  internal-only constraint or evaluating the body against the contribution's own reduced entity index. Neither
  is a local change.
- **Already-stored duplicate contributions are not repaired.** Catalogs written by affected versions keep the
  default until the owner is written again or the catalog is reindexed. This goes into the upgrade note on
  #1726, not into the code.

## Related work

- [[2026-09-08-conditional-histogram-per-contribution-verdicts]] introduced the per-contribution pins this
  record narrows. Its statement that the referenced entity is pinned "always" no longer holds.
- [[2026-09-17-row-scoped-reference-having-body]] made a `referenceHaving` body row-scoped. That is why a merged
  group pin is enough on its own for a group-only condition.
- [[2026-08-31-cross-entity-histogram-removal-pre-pass]] is the pre-pass whose verdict the pins decide.

## Timeline

- **2026-10-08** — scenario B reproduced on `dev`; bare-pin attempt refuted; conditional pinning implemented on
  the #1727 branch
