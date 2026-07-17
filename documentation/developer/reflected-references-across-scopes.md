# Reflected references across scopes

This document describes how *reflected references* behave when the entities that participate in a reflected relation
live in - or move between - the `LIVE` and `ARCHIVED` [scopes](../user/en/use/schema.md#scopes). It is the reference
for the behavior implemented by
<SourceClass>evita_engine/src/main/java/io/evitadb/index/mutation/storagePart/ContainerizedLocalMutationExecutor.java</SourceClass>
and verified by
<SourceClass>evita_test/evita_functional_tests/src/test/java/io/evitadb/api/functional/indexing/EvitaArchivingTest.java</SourceClass>.
Reflected references combined with scopes form one of the more intricate corners of the engine; this page is meant to
give a developer the complete mental model before touching the code.

## 1. Concepts

Consider two entity types and a relation between them:

- **Holder (`H`)** owns the **primary reference** `H -> T` (e.g. `Product.categories`). The primary reference is the
  single source of truth for the relation.
- **Target (`T`)** owns the **reflected reference** `T -> H` (e.g. `Category.products`), declared with
  `withReflectedReferenceToEntity(...)`. The reflected reference is a *derived mirror* of the primary reference on the
  opposite entity.

Together the primary reference and its reflected counterpart form a single **bi-directional relation**. A reflected
reference is never an independent source of truth: unlike a primary reference (which may be *orphaned* - point at a
target that does not exist yet), a reflected reference can only exist while its primary reference exists.

## 2. The bi-directional contract

While the relation is *maintained* (see section 3), the following holds:

1. **Shared existence.** The reflected reference exists if and only if the primary reference it mirrors exists.
2. **Shared attributes.** Attributes that the reflected reference schema declares as *inherited*
   (`withAttributesInherited()`) are carried over from the primary reference and kept in sync with it.
3. **Symmetric propagation.** A change made on *either* end (create, update of an inherited attribute, remove) is
   propagated to the other end. Manipulating the primary reference updates the reflected reference, and manipulating the
   reflected reference updates the primary reference.

## 3. Index visibility is the enabling condition

Propagation is only possible when the engine has the *means to find the other side* of the relation, and that means is
the **reference index in the relevant scope**. A reference schema indexed in a scope makes the relation traversable in
that scope; a reference schema not indexed in a scope makes it invisible there.

The single governing rule is:

> **A reflected reference is maintained in a given scope arrangement of `H` and `T` if and only if both the primary
> reference schema and the reflected reference schema are indexed in every scope that the relation currently spans.**

- When `H` and `T` are in the **same scope `S`**, the relation spans `{S}` - the reflected reference is maintained iff
  both schemas are indexed in `S`.
- When `H` and `T` are in **different scopes** (one `LIVE`, the other `ARCHIVED`), the relation spans `{LIVE, ARCHIVED}`
  - the reflected reference is maintained iff both schemas are indexed in **both** scopes.

When the condition is **not** met, the bi-directional contract cannot be upheld (a change on the invisible side could
not propagate), so the reflected reference is **discarded** rather than left in a silently divergent state. A stale
half-maintained mirror is strictly worse than no mirror. When a later scope change **restores** full mutual visibility,
the reflected reference is **recreated** from the still-authoritative primary reference (including its inherited
attributes).

The **primary reference is unaffected by this rule**: it is retained wherever its own schema is indexed, independently
of whether the reflected mirror can be maintained. Discarding a reflected reference never removes or hides the primary
reference.

## 4. Legal indexing configurations

A reflected reference may only be indexed in a subset of the scopes in which its primary reference is indexed (enforced
by schema validation). That leaves three legal configurations:

| Id | Primary indexed in | Reflected indexed in | Name |
|----|--------------------|----------------------|------|
| C1 | `{LIVE}`           | `{LIVE}`             | symmetric LIVE-only |
| C2 | `{LIVE, ARCHIVED}` | `{LIVE, ARCHIVED}`   | symmetric both-scopes |
| C3 | `{LIVE, ARCHIVED}` | `{LIVE}`             | asymmetric (primary both / reflected LIVE-only) |

Applying the rule of section 3:

- **C1** - the reflected reference is maintained only while **both** `H` and `T` are `LIVE`. Archiving either side spans
  two scopes while the reflected is LIVE-only, so the reflected reference is discarded. It is recreated only when both
  ends are back in `LIVE`.
- **C2** - the reflected reference is maintained in **every** scope arrangement (both `LIVE`, both `ARCHIVED`, or split
  across scopes). C2 is the only configuration that supports a *cross-scope* reflected reference. All mutations
  propagate regardless of the scopes of `H` and `T`.
- **C3** - the reflected reference is maintained only while **both** `H` and `T` are `LIVE` (same as C1, because the
  reflected side is LIVE-only). The difference from C1 is that the **primary** reference remains indexed - and therefore
  queryable - in the `ARCHIVED` scope as well.

## 5. Scope-transition rules

When an entity moves between scopes (archive / restore):

1. **Primary references are always retained** on the moved entity, and remain indexed in whatever scopes their schema
   declares. They are never dropped by a scope transition.
2. **Reflected references are re-evaluated** against the rule of section 3 for the new scope arrangement:
   - if the condition is met in the new arrangement, the reflected reference is kept (or recreated if it had been
     discarded earlier);
   - if the condition is not met, the reflected reference is discarded.
3. **Transitions never fail** because of the state of the *other* side of the relation. Archiving, restoring or deleting
   one participant must not depend on the other participant living in the same scope.

## 6. Behavior matrix (operation x configuration)

`H` = holder (owns primary), `T` = target (owns reflected). "mirror" = the reflected reference on `T`. Unless stated,
`T` is `LIVE`.

| Operation | C1 (both LIVE-only) | C2 (both both-scopes) | C3 (primary both / reflected LIVE-only) |
|-----------|---------------------|-----------------------|------------------------------------------|
| Archive `H` | mirror discarded; primary retained (LIVE index only) | mirror **retained** (cross-scope); primary retained in both | mirror discarded; **primary retained and queryable in `ARCHIVED`** |
| Restore `H` (mirror previously discarded) | mirror **recreated** on `T` | n/a (never discarded) | mirror **recreated** on `T` |
| Add primary ref on archived `H` -> live `T` | no mirror (not mutually visible) | mirror **created** on `T` | no mirror (reflected LIVE-only) |
| Update inherited attr on archived `H` | n/a (no mirror) | mirror **updated** on `T` | n/a (no mirror) |
| Remove one of several primary refs on archived `H` | only that mirror gone | only that mirror gone; **surviving siblings' mirrors intact** | only that mirror gone |
| Remove dangling primary ref on archived `H` (target already removed) | succeeds; ref removed | succeeds; ref removed | succeeds; ref removed |
| Archive `T` while `H` is `LIVE` | mirror discarded; succeeds | mirror **retained** cross-scope; succeeds | mirror discarded; succeeds |
| Delete archived `H` | mirror (if any) removed; succeeds | mirror on `T` removed; succeeds | succeeds |

The full combinatoric space is larger than this table (each of create / update-attr / remove-ref / archive / restore /
delete can be applied to `H` or `T`, in each of the three configurations, with `T` alive or removed and in either
scope). The table lists the load-bearing cases; the E2E suite covers the space systematically.

## 7. How the engine maintains reflected references

Reflected references are not stored twice. Each side is an ordinary reference on its own entity; the engine keeps the
two sides in sync by generating **implicit mutations** against the counterpart entity whenever one side changes. This
happens in
<SourceClass>evita_engine/src/main/java/io/evitadb/index/mutation/storagePart/ContainerizedLocalMutationExecutor.java</SourceClass>
while a mutation batch is applied.

The relevant entry points are:

- **`insertReflectedReferences`** - runs when an entity is created and fully set up (and when it is moved between
  scopes). For every primary reference the entity holds it sets up the matching reflected reference on the referenced
  entity, and for every reflected reference it sets up the matching primary reference on the referenced entity -
  provided the counterpart schema exists.
- **`verifyReflectedReferences`** - runs for each reference mutation in the batch. An `InsertReferenceMutation` or a
  `RemoveReferenceMutation` on one side is propagated to the other side.
- Both funnel into **`propagateReferencesToEntangledEntities`** -> **`propagateReferenceModification`**, which emits the
  implicit <SourceClass>evita_engine/src/main/java/io/evitadb/core/transaction/stage/mutation/ServerEntityUpsertMutation.java</SourceClass>
  (carrying an `InsertReferenceMutation` or `RemoveReferenceMutation`) against the counterpart entity.

Because the two participants of a relation may live in different scopes, the engine first **partitions the referenced
primary keys by the scope the counterpart entity currently exists in** (by intersecting them with the `GLOBAL` entity
index of the referenced collection in each scope; keys found in no scope belong to removed or not-yet-created
entities). For every `(entity scope, counterpart scope)` pair it then evaluates the **visibility rule of section 3**
(`isRelationMaintained` - both schemas indexed in both scopes of the span) and decides by the `CreateMode`:

| `CreateMode` | Trigger | Behavior per counterpart-scope partition |
|--------------|---------|------------------------------------------|
| `INSERT_MISSING` | entity created, scope changed or reference inserted | create the missing counterpart iff the relation is maintained; a reflected reference pointing at a primary key existing in **no** scope raises `EntityMissingException` |
| `REMOVE_NON_INDEXED` | scope transition | when the relation is **not** maintained: if the entity owns the **reflected** side, its own mirror is discarded **locally**; if it owns the **primary** side, the mirror on the counterpart is removed - the primary reference itself is never touched |
| `REMOVE_ALL_EXISTING` | explicit reference removal or entity deletion | propagate the removal to the counterpart iff the relation is maintained - a discarded mirror has nothing to clean up and a dangling reference must remain freely removable |

The direction awareness in `REMOVE_NON_INDEXED` is essential: a scope transition may only ever discard the *derived*
side (the mirror). Discarding is a local, non-propagating operation - the primary reference stays the source of truth
from which the mirror is recreated when a later transition restores visibility (invariants I4 and I5). Explicit
removals (`REMOVE_ALL_EXISTING`), by contrast, do propagate to the other side - that is the symmetric propagation of
section 2 - but only while the relation is maintained.

Finding the counterpart relies on the per-scope indexes. The `GLOBAL` entity index of the referenced collection tells
the engine in which scope a target entity exists; the `REDUCED` (`REFERENCED_ENTITY`) index carries the per-reference
reduced data and always follows the scope of the entity it indexes - all reduced-index lookups for the counterpart are
therefore performed in the *counterpart's* scope, while lookups in the entity's own collection use the entity's
(target) scope. The same scope-resolution applies to inherited attribute propagation
(`propagateOrphanedReferenceAttributeMutations`) and to the mirror setup on entity creation / scope transition
(`createAndRegisterReferencePropagationMutation`), which searches the primary holders' reduced indexes in every scope
where the relation is maintained.

Scope transitions reuse this same machinery: archiving or restoring an entity applies a scope change through
`changeEntityScopeInternal`, which re-runs the reflected-reference maintenance (`popImplicitMutations` ->
`insertReflectedReferences` / `verifyReflectedReferences`). This is why moving an entity between scopes automatically
re-evaluates its reflected references against the rule in section 3.

The query side mirrors the same principle (invariant I1): when a `referenceHaving` filter restricts referenced entity
primary keys, the existence superset built by
<SourceClass>evita_engine/src/main/java/io/evitadb/core/query/algebra/reference/ReferencedEntityIndexPrimaryKeyTranslatingFormula.java</SourceClass>
unions the referenced collection's `GLOBAL` indexes across **all** scopes - the scope of a query constrains the
*queried* entities only, never the scope the referenced entity happens to live in (an archived product referencing a
live category is found by that reference when querying the `ARCHIVED` scope).

## 8. Invariants

- **I1 - Primary survival.** A primary reference is queryable in every scope in which its schema is indexed, before and
  after any scope transition of its holder.
- **I2 - No stale mirror.** A reflected reference is either fully consistent with its primary (existence + inherited
  attributes) or absent. It is never present with diverged data.
- **I3 - Full propagation under visibility.** When the section 3 condition holds, create / update / remove on the
  primary side is reflected on the mirror, and vice versa - including when `H` and `T` are in different scopes (C2).
- **I4 - Clean discard.** When the condition does not hold, exactly the reflected side is discarded; the primary side and
  the mirrors of *other* relations are untouched.
- **I5 - Recreation on regained visibility.** When a scope transition restores the condition, the mirror is recreated
  from the primary, with current inherited attributes.
- **I6 - No cross-scope failures.** No scope transition or reference mutation fails because the other side of the
  relation lives in a different scope.

## References

- User documentation: [Scopes](../user/en/use/schema.md#scopes), [reflected references](../user/en/use/data-model.md)
- Engine: <SourceClass>evita_engine/src/main/java/io/evitadb/index/mutation/storagePart/ContainerizedLocalMutationExecutor.java</SourceClass>
- Contracts: <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ReflectedReferenceContract.java</SourceClass>, <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/ReflectedReferenceSchemaContract.java</SourceClass>
- E2E tests: <SourceClass>evita_test/evita_functional_tests/src/test/java/io/evitadb/api/functional/indexing/EvitaArchivingTest.java</SourceClass>
