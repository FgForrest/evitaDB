# WBS-07: `ReevaluateFacetExpressionExecutor` — Target-Side Reevaluation Pipeline

> **Parent document:** [Conditional Facet Indexing — Problem Analysis](../conditional-facet-indexing.md)

## Objective

Implement the `ReevaluateFacetExpressionExecutor`, a stateless singleton that handles the full
target-side reevaluation pipeline for cross-entity facet expression changes. When a cross-entity
attribute changes (e.g., a group entity or referenced entity attribute), this executor resolves
all affected owner entities, evaluates the expression against current index state, and performs
the necessary facet add/remove operations using bitmap set operations.

## Scope

### In Scope

- Implementation of `ReevaluateFacetExpressionExecutor` as a stateless singleton registered in
  `IndexMutationExecutorRegistry`
- Two-step PK resolution via `IndexMutationTarget` for both `GROUP_ENTITY_ATTRIBUTE` and
  `REFERENCED_ENTITY_ATTRIBUTE` dependency types
- FilterBy parameterization with PK-scoping to prevent false positives from unrelated
  groups/references
- Bitmap set operations (`shouldBeFaceted` / `shouldNotBeFaceted`) for determining add/remove
  direction
- Target index routing to `GlobalEntityIndex` and applicable `ReducedEntityIndex` instances
- The `AffectedEntityResolution`, `AffectedFacetGroup`, and `AffectedFacetEntry` supporting
  records
- Idempotent add/remove operations (natural de-duplication)

### Out of Scope

- Source-side trigger detection (WBS-03)
- `ReevaluateFacetExpressionMutation` definition (WBS-05)
- Dispatch infrastructure and `EntityIndexMutation` routing (WBS-06)
- Local trigger guards in `ReferenceIndexMutator` (inline expression checks at reference
  creation time)
- Schema expression changes (handled by full re-index, separate mechanism)
- Cross-collection index access optimization (architectural decision AD-5 forbids this)

## Dependencies

### Depends On

| WBS | Provides |
|-----|----------|
| WBS-03 | `CatalogExpressionTriggerRegistry` and `ExpressionIndexTrigger` — supplies the pre-translated `FilterBy` constraints and trigger lookup by `(referenceName, dependencyType, scope)` |
| WBS-05 | `ReevaluateFacetExpressionMutation` — the mutation type carrying `referenceName`, `mutatedEntityPK`, `dependencyType`, and `scope` |
| WBS-06 | `IndexMutationExecutorRegistry` dispatch infrastructure, `IndexMutationTarget` interface providing access to indexes, schema, triggers, and query evaluation |

### Depended On By

- No downstream WBS tasks depend on this executor directly; it is the terminal pipeline stage
  that performs the actual index mutations

## Technical Context

### Executor Design

`ReevaluateFacetExpressionExecutor` is a **stateless singleton** that implements
`IndexMutationExecutor<ReevaluateFacetExpressionMutation>`. All collection context flows
through `IndexMutationTarget`, which provides access to the target collection's indexes,
entity schema, trigger registry, and query evaluation capabilities.

### Full Executor Code

```java
/**
 * Re-evaluates the facetedPartially expression for all owner entities
 * affected by a cross-entity change. Uses index-based query evaluation
 * (no per-entity storage access) to determine which entities currently
 * satisfy the expression, then compares with current facet state and
 * performs the necessary add/remove operations.
 *
 * Stateless singleton — registered in {@link IndexMutationExecutorRegistry}.
 */
class ReevaluateFacetExpressionExecutor
    implements IndexMutationExecutor<ReevaluateFacetExpressionMutation> {

    /**
     * Resolves affected owner entity PKs and associated facet PKs using the
     * target collection's indexes. Returns a structured result mapping each
     * reduced index to its (facetPK, groupPK, ownerPKs) tuple.
     *
     * Two-step lookup:
     * 1. {@code target.getIndexIfExists(REFERENCED_GROUP_ENTITY_TYPE/...)}
     *    → {@link ReferencedTypeEntityIndex}
     * 2. {@code rtei.getAllReferenceIndexes(mutatedPK)} → {@code int[]} storage PKs
     * 3. For each storage PK: {@code target.getIndexByPrimaryKeyIfExists(pk)}
     *    → {@link ReducedGroupEntityIndex} / {@link ReducedEntityIndex}
     *    — for GROUP_ENTITY_ATTRIBUTE: facetPKs recovered from
     *      {@code ReducedGroupEntityIndex.referencedPrimaryKeysIndex.keySet()}
     *      (NOT from the discriminator, which carries the group PK);
     *      groupPK = mutatedEntityPK
     *    — for REFERENCED_ENTITY_ATTRIBUTE: facetPK = mutatedEntityPK;
     *      groupPK resolved from {@code FacetReferenceIndex.getGroupIdForFacet()}
     * 4. {@code reducedIndex.getAllPrimaryKeys()} → owner entity PKs
     */
    @Nonnull
    private AffectedEntityResolution resolveAffected(
        @Nonnull IndexMutationTarget target,
        @Nonnull ReevaluateFacetExpressionMutation mutation
    ) { /* ... */ }

    /**
     * Resolves target indexes for facet operations (GlobalEntityIndex +
     * applicable ReducedEntityIndex instances) via {@link IndexMutationTarget}.
     */
    @Nonnull
    private EntityIndex[] resolveTargetIndexes(
        @Nonnull IndexMutationTarget target,
        @Nonnull String referenceName,
        @Nonnull Scope scope
    ) { /* ... */ }

    @Override
    public void execute(
        @Nonnull ReevaluateFacetExpressionMutation mutation,
        @Nonnull IndexMutationTarget target
    ) {
        // 1. Resolve affected (facetPK, groupPK, ownerPKs) tuples
        final AffectedEntityResolution affected = resolveAffected(target, mutation);
        final Bitmap allAffectedOwnerPKs = affected.allOwnerPKs();

        // 2. Get the pre-translated FilterBy from the trigger
        final ExpressionIndexTrigger trigger = target.getTrigger(
            mutation.referenceName(),
            mutation.dependencyType(),
            mutation.scope()
        );
        if (trigger == null) {
            return; // no expression — nothing to reevaluate
        }

        // 3. Parameterize the FilterBy with the mutated entity PK:
        //    - GROUP_ENTITY_ATTRIBUTE: inject
        //      entityGroupHaving(entityPrimaryKeyInSet(mutatedPK))
        //    - REFERENCED_ENTITY_ATTRIBUTE: inject
        //      entityHaving(entityPrimaryKeyInSet(mutatedPK))
        //    This scopes the query to references pointing to the SPECIFIC
        //    changed entity, avoiding false positives from other groups/refs.
        final FilterBy parameterizedFilter = parameterize(
            trigger.getFilterByConstraint(),
            mutation.referenceName(),
            mutation.mutatedEntityPK(),
            mutation.dependencyType()
        );

        // 4. Evaluate against current indexes → PKs where expression is TRUE now
        final Bitmap currentlyTruePKs = target.evaluateFilter(parameterizedFilter);

        // 5. Determine adds and removes via bitmap set operations
        final Bitmap shouldBeFaceted = RoaringBitmapBackedBitmap.and(
            new RoaringBitmap[]{
                RoaringBitmapBackedBitmap.getRoaringBitmap(allAffectedOwnerPKs),
                RoaringBitmapBackedBitmap.getRoaringBitmap(currentlyTruePKs)
            }
        );
        final Bitmap shouldNotBeFaceted = new BaseBitmap(
            RoaringBitmap.andNot(
                RoaringBitmapBackedBitmap.getRoaringBitmap(allAffectedOwnerPKs),
                RoaringBitmapBackedBitmap.getRoaringBitmap(currentlyTruePKs)
            )
        );

        // 6. Apply changes — for each (facetPK, groupPK, ownerPK) tuple
        final ReferenceSchemaContract refSchema =
            target.getEntitySchema()
                .getReference(mutation.referenceName())
                .orElseThrow();
        final EntityIndex[] targetIndexes = resolveTargetIndexes(
            target, mutation.referenceName(), mutation.scope()
        );

        // Add facet entries for entities that should be faceted but aren't yet
        for (AffectedFacetEntry entry : affected.entriesForOwnerPKs(shouldBeFaceted)) {
            final ReferenceKey refKey =
                new ReferenceKey(mutation.referenceName(), entry.facetPK());
            for (EntityIndex index : targetIndexes) {
                index.addFacet(refSchema, refKey, entry.groupPK(), entry.ownerPK());
            }
        }

        // Remove facet entries for entities that should not be faceted but are
        for (AffectedFacetEntry entry : affected.entriesForOwnerPKs(shouldNotBeFaceted)) {
            final ReferenceKey refKey =
                new ReferenceKey(mutation.referenceName(), entry.facetPK());
            for (EntityIndex index : targetIndexes) {
                index.removeFacet(refSchema, refKey, entry.groupPK(), entry.ownerPK());
            }
        }
    }
}
```

### Supporting Records

```java
/**
 * Structured result of PK resolution. Maps each reduced index to a
 * (facetPK, groupPK, ownerPKs) tuple — all information needed to
 * construct {@code ReferenceKey} and call {@code addFacet/removeFacet}.
 */
record AffectedEntityResolution(
    @Nonnull List<AffectedFacetGroup> groups
) {
    /** Union of all owner PKs across all groups. */
    @Nonnull Bitmap allOwnerPKs() { /* ... */ }
    /** Filters entries to only those whose ownerPK is in the given bitmap. */
    @Nonnull Iterable<AffectedFacetEntry> entriesForOwnerPKs(@Nonnull Bitmap pks) { /* ... */ }
}

record AffectedFacetGroup(int facetPK, @Nullable Integer groupPK, @Nonnull Bitmap ownerPKs) { }
record AffectedFacetEntry(int facetPK, @Nullable Integer groupPK, int ownerPK) { }
```

### PK Resolution — `resolveAffected()` Method

The `resolveAffected()` method performs a two-step lookup using the target collection's own
indexes (never accessing source collection indexes — see AD-5 below). The resolution path
differs by dependency type:

#### GROUP_ENTITY_ATTRIBUTE Resolution Path

1. Look up `ReferencedTypeEntityIndex` for `REFERENCED_GROUP_ENTITY_TYPE`
   (discriminator = referenceName)
2. Call `getAllReferenceIndexes(groupEntityPK)` — returns storage PKs of
   `ReducedGroupEntityIndex` instances for that group
3. For each `ReducedGroupEntityIndex`:
   - **Facet PK** is recovered from the `EntityIndexKey.discriminator`, which is a
     `ReferenceKey(referenceName, facetPK)`
   - **Group PK** is `mutatedEntityPK` itself (the group entity that changed)
   - `getAllPrimaryKeys()` yields the **owner entity PKs**

This is confirmed by `FilterByVisitor.getMatchingGroupEntityPrimaryKeys()` which performs the
reverse mapping via `getReferencedPrimaryKeysForIndexPks()`.

**Important note on key structure:** `ReducedGroupEntityIndex` is keyed by the **referenced
entity PK** (facet PK), not the group PK (confirmed by test `GroupEntityIndexingTest:334`).
However, the type-level `ReferencedTypeEntityIndex` for `REFERENCED_GROUP_ENTITY_TYPE` maps
**group entity PKs** to reduced-index storage PKs via `ReferenceTypeCardinalityIndex`. This
two-step indirection is why the lookup works: `getAllReferenceIndexes(groupPK)` returns storage
PKs, then each storage PK resolves to a `ReducedGroupEntityIndex` whose key reveals the
facet PK.

#### REFERENCED_ENTITY_ATTRIBUTE Resolution Path

1. Look up `ReferencedTypeEntityIndex` for `REFERENCED_ENTITY_TYPE`
   (discriminator = referenceName)
2. Call `getAllReferenceIndexes(referencedEntityPK)` — returns storage PKs of
   `ReducedEntityIndex` instances
3. For each `ReducedEntityIndex`:
   - **Facet PK** is `mutatedEntityPK` itself (the referenced entity that changed)
   - **Group PK** is resolved via `FacetReferenceIndex.getGroupIdForFacet(facetPK)` on the
     `GlobalEntityIndex`'s `FacetIndex` (see Group 10 in task breakdown). Returns
     `@Nullable Integer` — null for ungrouped facets.
   - `getAllPrimaryKeys()` yields the **owner entity PKs**

### FilterBy Parameterization

The pre-translated `FilterBy` from the trigger must be **parameterized** with the mutated
entity PK to scope the query to references pointing to the specific changed entity. Without
this scoping, an entity referencing multiple groups would produce false positives.

- **GROUP_ENTITY_ATTRIBUTE**: inject `entityGroupHaving(entityPrimaryKeyInSet(mutatedPK))`
- **REFERENCED_ENTITY_ATTRIBUTE**: inject `entityHaving(entityPrimaryKeyInSet(mutatedPK))`

> **Naming note:** `entityGroupHaving(...)` and `entityHaving(...)` are the query DSL method
> names used in evitaQL. The corresponding Java classes are `GroupHaving` and `EntityHaving`
> respectively. Both forms appear throughout this document: DSL names in query/filter
> examples, Java class names in code examples.

**Example of why this is critical:** A product referencing BOTH group 99 (now `'RADIO'`) and
group 55 (still `'CHECKBOX'`) would incorrectly match because of group 55 if the query were
not scoped to group 99. The PK-scoping constraint ensures only the changed group is evaluated.

### Bitmap Set Operations

After evaluating the parameterized `FilterBy` against current indexes, the executor determines
the add/remove direction using bitmap operations:

- `shouldBeFaceted = AND(allAffectedOwnerPKs, currentlyTruePKs)` — entities where the
  expression is true and that are in the affected set
- `shouldNotBeFaceted = ANDNOT(allAffectedOwnerPKs, currentlyTruePKs)` — entities in the
  affected set where the expression is false

### Target Index Routing

Facet add/remove operations target the same indexes as existing facet mutations:

- **`GlobalEntityIndex`** — always
- **`ReducedEntityIndex`** for the reference — if it exists and has
  `FOR_FILTERING_AND_PARTITIONING` indexing level

### De-duplication via Idempotency

Since the executor performs idempotent index operations, de-duplication happens naturally. If
both a local trigger and a cross-entity trigger fire for the same entity, the second operation
is a no-op at the index level. Multiple triggers for the same target collection with identical
`FilterBy` constraints each run their own `evaluateFilter()` independently — this is an
optimization opportunity for the future but not a correctness issue.

### No Cross-Collection Index Access (AD-5)

The source entity's executor never reaches into the target collection's indexes. The executor
on the target side resolves affected PKs via the two-step lookup:
`ReferencedTypeEntityIndex` (type-level, `REFERENCED_GROUP_ENTITY_TYPE` or
`REFERENCED_ENTITY_TYPE`) -> `getAllReferenceIndexes(mutatedPK)` -> reduced-index storage PKs
-> `ReducedGroupEntityIndex`/`ReducedEntityIndex` -> `getAllPrimaryKeys()` -> owner entity PKs.
Facet PK and group PK are recovered from `EntityIndexKey` discriminators.

### Why No Other Mutation Types Are Needed

| Scenario | Handled by | Needs `IndexMutation`? |
|---|---|---|
| Reference created (expression=true) | Local trigger in `ReferenceIndexMutator` | No — inline guard |
| Reference created (expression=false) | Local trigger — skip `addFacetToIndex` | No — inline guard |
| Reference removed | Existing `removeFacetInIndex` logic | No — always removes |
| Group assignment changes | Existing `setFacetGroupInIndex` logic | No — then expression re-eval may add/remove |
| Cross-entity attr change or entity removal | **This mechanism** | Yes — `ReevaluateFacetExpressionMutation` in `EntityIndexMutation` |
| Schema expression changes | Full re-index (separate mechanism) | Not via this path |

### Multi-Schema Group Entity Processing

Each schema's collection handles its own triggers independently. The post-processing step in
`EntityIndexLocalMutationExecutor` calls
`CatalogExpressionTriggerRegistry.getTriggersForAttribute()` which returns triggers across ALL
dependent schemas. The detection step groups triggers by `getOwnerEntityType()` and generates
one `EntityIndexMutation` per target collection. Each target's `applyIndexMutations()`
dispatches the nested mutations independently to its own executors. No cross-collection
coordination is needed — each mutation carries all context needed for independent execution.

### Fan-Out Examples

#### Example 1: Purely Cross-Entity Expression

**Expression:** `$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'`

**Scenario:** Group entity G (parameterGroup "Color", PK=99) changes `inputWidgetType` from
`'CHECKBOX'` to `'RADIO'`.

**Source executor** (ParameterGroup's `EntityIndexLocalMutationExecutor`):
1. Detects that attribute `inputWidgetType` changed on entity PK 99
2. Checks old value (`'CHECKBOX'`) != new value (`'RADIO'`) — actual change confirmed
3. Consults `CatalogExpressionTriggerRegistry` — finds a trigger for Product/"parameter"
   depending on `GROUP_ENTITY_ATTRIBUTE` / `inputWidgetType`
4. Creates a `ReevaluateFacetExpressionMutation` (does NOT evaluate the expression or
   determine add/remove direction):

```java
new EntityIndexMutation(
    "product",                             // target collection
    new IndexMutation[] {
        new ReevaluateFacetExpressionMutation(
            "parameter",                   // reference with the expression
            99,                            // group entity PK that changed
            DependencyType.GROUP_ENTITY_ATTRIBUTE,
            Scope.DEFAULT_SCOPE
        )
    }
)
```

**Target executor** (`ReevaluateFacetExpressionExecutor` in Product collection):
1. Looks up `ReferencedTypeEntityIndex` for `REFERENCED_GROUP_ENTITY_TYPE`
   (discriminator="parameter")
2. Calls `getAllReferenceIndexes(99)` — returns storage PKs of `ReducedGroupEntityIndex`
   instances associated with group 99 (e.g., 10 indexes — one per Parameter/facet PK)
3. For each `ReducedGroupEntityIndex`, calls `getAllPrimaryKeys()` — collects all affected
   Product PKs (e.g., 1000 products total across all 10 indexes). Also collects the facet PK
   from each `ReducedGroupEntityIndex`'s `EntityIndexKey` discriminator.
4. Gets the pre-translated `FilterBy` from the trigger via
   `target.getTrigger("parameter", GROUP_ENTITY_ATTRIBUTE, DEFAULT_SCOPE)`
5. **Parameterizes** the `FilterBy` by injecting
   `entityGroupHaving(entityPrimaryKeyInSet(99))` — scoping the query to references within
   the specific changed group
6. Evaluates the parameterized `FilterBy` against Product's current indexes — bitmap of
   products where the expression is currently `true` for references to group 99 — empty
   (group 99's `inputWidgetType` is now `'RADIO'`, no longer `'CHECKBOX'`)
7. Compares with current facet state for each affected (facetPK, ownerPK) pair:
   - `shouldBeFaceted = AND(affectedPKs, queryResult)` — empty
   - `shouldNotBeFaceted = ANDNOT(affectedPKs, queryResult)` — all 1000 PKs
8. For each of the 1000 products: removes facet entry (for the specific facetPK within
   group 99) from `GlobalEntityIndex` and applicable `ReducedEntityIndex` instances.
   Products not currently faceted result in idempotent no-ops.

#### Example 2: Mixed-Dependency Expression

**Expression:** `$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX' && $entity.attributes['isActive'] == true`

**Scenario:** Same as above — group entity PK=99 changes `inputWidgetType` from `'CHECKBOX'`
to `'RADIO'`.

**Source side** (ParameterGroup executor): Identical to Example 1 — detects change, confirms
old != new, creates `ReevaluateFacetExpressionMutation`. Does NOT evaluate the expression.

**Target side** (`ReevaluateFacetExpressionExecutor` in Product collection):
1-3. Same PK resolution as above — 1000 candidate Product PKs
4. Gets the pre-translated `FilterBy`:
   `and(referenceHaving("parameter", entityGroupHaving(attributeEquals("inputWidgetType", "CHECKBOX"))), attributeEquals("isActive", true))`
5. **Parameterizes** with `entityGroupHaving(entityPrimaryKeyInSet(99))`
6. Evaluates — empty bitmap (group 99 is `'RADIO'`, so the AND fails regardless of
   `isActive`)
7. `shouldNotBeFaceted` = all 1000 PKs. Among these, 800 were actually faceted (active
   products), 200 were never faceted (inactive). The 200 remove calls are idempotent no-ops.
8. Removes facet entries for the 800 active products

#### Example 3: Multi-Source Cross-Entity Expression

**Expression:** `$reference.groupEntity?.attributes['status'] == 'ACTIVE' || $reference.referencedEntity.attributes['status'] == 'PREVIEW'`

This expression depends on TWO cross-entity sources. The registry has entries for BOTH:
- `("ParameterGroup", GROUP_ENTITY_ATTRIBUTE, "status")` -> trigger
- `("Parameter", REFERENCED_ENTITY_ATTRIBUTE, "status")` -> trigger

**Scenario:** Group entity PK=99 changes `status` from `'ACTIVE'` to `'INACTIVE'`.

**Source side:** Creates `ReevaluateFacetExpressionMutation` targeting Product.

**Target side:** Same flow as above. The parameterized `FilterBy` becomes:

```
referenceHaving("parameter",
    and(
        entityGroupHaving(entityPrimaryKeyInSet(99)),
        or(
            entityGroupHaving(attributeEquals("status", "ACTIVE")),
            entityHaving(attributeEquals("status", "PREVIEW"))
        )
    )
)
```

This correctly handles the OR: a product whose referenced Parameter entity has
`status='PREVIEW'` will still match (the second operand is true), even though the group part
is now false. Only products where NEITHER operand is true for their reference within group 99
will have their facet removed.

### Hidden Traps

| # | Trap | Severity | Mitigation |
|---|------|----------|------------|
| 14 | **Multi-source cross-entity dependencies** — expression has OR with different cross-entity sources (`groupEntity \|\| referencedEntity`) | High | Single `ReevaluateFacetExpressionMutation` (no Add/Remove split). Target evaluates full expression via query — handles OR naturally. Registry has entries for BOTH dependency types. |
| 15 | **FilterBy parameterization required** — without PK-scoping, entity with refs to multiple groups gets false positives | High | Executor injects `entityGroupHaving(entityPrimaryKeyInSet(mutatedPK))` / `entityHaving(entityPrimaryKeyInSet(mutatedPK))` at trigger time to scope to the specific changed entity. |

## Key Interfaces

| Interface / Class | Role |
|---|---|
| `IndexMutationExecutor<ReevaluateFacetExpressionMutation>` | Interface this executor implements |
| `IndexMutationTarget` | Provides access to indexes, schema, triggers, and query evaluation for the target collection |
| `IndexMutationExecutorRegistry` | Singleton registry where this executor is registered |
| `ReevaluateFacetExpressionMutation` | Input mutation carrying `referenceName`, `mutatedEntityPK`, `dependencyType`, `scope` |
| `ExpressionIndexTrigger` | Provides the pre-translated `FilterBy` constraint via `getFilterByConstraint()` |
| `ReferencedTypeEntityIndex` | Type-level index for `REFERENCED_GROUP_ENTITY_TYPE` / `REFERENCED_ENTITY_TYPE`; entry point for PK resolution |
| `ReducedGroupEntityIndex` | Per-facet reduced index within a group; provides owner PKs and facet PK via `EntityIndexKey` discriminator |
| `ReducedEntityIndex` | Per-referenced-entity reduced index; provides owner PKs |
| `GlobalEntityIndex` | Target index — facet operations always applied here |
| `ReferenceSchemaContract` | Schema for the reference — required by `addFacet` / `removeFacet` |
| `RoaringBitmapBackedBitmap` | Bitmap implementation for `and()` set operations; `andNot()` uses `RoaringBitmap.andNot()` directly |
| `AffectedEntityResolution` | Result record from `resolveAffected()` — contains list of `AffectedFacetGroup` |
| `AffectedFacetGroup` | Record: `(int facetPK, @Nullable Integer groupPK, Bitmap ownerPKs)` |
| `AffectedFacetEntry` | Record: `(int facetPK, @Nullable Integer groupPK, int ownerPK)` — individual entry for iteration |

## Acceptance Criteria

1. **GROUP_ENTITY_ATTRIBUTE resolution**: When a group entity attribute changes, the executor
   correctly resolves all affected owner entity PKs and facet PKs via the two-step lookup
   through `ReferencedTypeEntityIndex` -> `ReducedGroupEntityIndex`
2. **REFERENCED_ENTITY_ATTRIBUTE resolution**: When a referenced entity attribute changes, the
   executor correctly resolves affected PKs via `ReferencedTypeEntityIndex` ->
   `ReducedEntityIndex`
3. **FilterBy parameterization**: The executor injects the correct PK-scoping constraint
   (`entityGroupHaving` or `entityHaving`) to prevent false positives from unrelated
   groups/references
4. **Bitmap correctness**: `shouldBeFaceted` and `shouldNotBeFaceted` bitmaps are computed
   correctly — `AND(affected, queryResult)` and `ANDNOT(affected, queryResult)` respectively
5. **Facet add operations**: For each entry in `shouldBeFaceted`, `addFacet` is called on all
   target indexes (`GlobalEntityIndex` + applicable `ReducedEntityIndex`)
6. **Facet remove operations**: For each entry in `shouldNotBeFaceted`, `removeFacet` is
   called on all target indexes
7. **Idempotency**: Duplicate triggers (local + cross-entity firing for the same entity) result
   in no-ops at the index level — no double-adds or double-removes
8. **Null trigger handling**: If `target.getTrigger()` returns null, the executor returns
   immediately without side effects
9. **Multi-source expressions**: An expression with OR across different cross-entity sources
   (e.g., `groupEntity || referencedEntity`) is handled correctly — the full expression is
   evaluated via query, not split
10. **No cross-collection access**: The executor never accesses indexes from the source
    collection — all resolution uses the target collection's own indexes via
    `IndexMutationTarget`
11. **Target index routing**: Operations target `GlobalEntityIndex` always, plus
    `ReducedEntityIndex` only when it exists with `FOR_FILTERING_AND_PARTITIONING` level

## Implementation Notes

- The executor is a **stateless singleton** — all state comes from `IndexMutationTarget` and
  the mutation itself. This simplifies registration and lifecycle management.
- The `parameterize()` method must handle both dependency types differently:
  `GROUP_ENTITY_ATTRIBUTE` wraps with `entityGroupHaving(entityPrimaryKeyInSet(...))` while
  `REFERENCED_ENTITY_ATTRIBUTE` wraps with `entityHaving(entityPrimaryKeyInSet(...))`.
- `AffectedEntityResolution.entriesForOwnerPKs(bitmap)` should filter efficiently — consider
  checking bitmap membership rather than materializing intersection lists.
- The `allOwnerPKs()` method should return a union bitmap across all `AffectedFacetGroup`
  entries. Since `allOwnerPKs()` is called once (for the bitmap operations) and then
  `entriesForOwnerPKs()` is called twice (for add and remove), **lazy computation without
  caching** is sufficient — compute the union on each call. If profiling shows this is a
  bottleneck, add caching.
- For `GROUP_ENTITY_ATTRIBUTE`, the **groupPK** is always `mutatedEntityPK` (the group entity
  that changed). For `REFERENCED_ENTITY_ATTRIBUTE`, the groupPK must be determined from the
  `ReducedGroupEntityIndex` structure.
- `resolveTargetIndexes()` follows the same pattern as existing facet mutation code — reuse
  the existing helper if available.
- Multiple triggers for the same target collection with identical `FilterBy` constraints each
  run their own `evaluateFilter()` independently. This is correct but suboptimal — a future
  optimization could batch-evaluate identical filters.

## Phase Placeholders

### Detailed Task Breakdown

#### Source Code Research Results

**`ReferencedTypeEntityIndex` -- two-step PK resolution entry point:**
- Path: `evita_engine/src/main/java/io/evitadb/index/ReferencedTypeEntityIndex.java`
- Package: `io.evitadb.index`
- Key method (line 281): `@Nonnull public int[] getAllReferenceIndexes(int referencedEntityPrimaryKey)` -- delegates to `this.indexPrimaryKeyCardinality.getAllReferenceIndexes(referencedEntityPrimaryKey)`, returns storage PKs (`int[]`) of `ReducedEntityIndex` or `ReducedGroupEntityIndex` instances associated with the given referenced entity PK
- Method `getReferenceName()` (line 250): returns `(String) Objects.requireNonNull(getIndexKey().discriminator())` -- the discriminator for `REFERENCED_ENTITY_TYPE` / `REFERENCED_GROUP_ENTITY_TYPE` keys is a `String` (the reference name)
- Method `getReferencedPrimaryKeysForIndexPks(Bitmap)` (line 297): reverse lookup -- given reduced-index storage PKs, returns the referenced entity PKs (e.g., group PKs) whose bitmaps overlap. Used by `FilterByVisitor.getMatchingGroupEntityPrimaryKeys()`.
- This index exists once per `(ReferenceSchemaContract, scope, EntityIndexType)` combination -- accessed via `EntityIndexKey(REFERENCED_ENTITY_TYPE, scope, referenceName)` or `EntityIndexKey(REFERENCED_GROUP_ENTITY_TYPE, scope, referenceName)`

**`ReferenceTypeCardinalityIndex` -- internal cardinality tracking:**
- Path: `evita_engine/src/main/java/io/evitadb/index/cardinality/ReferenceTypeCardinalityIndex.java`
- Package: `io.evitadb.index.cardinality`
- Method `getAllReferenceIndexes(int referencedEntityPrimaryKey)` (line 214): returns `int[]` from `this.referencedPrimaryKeysIndex.get(referencedEntityPrimaryKey)` mapped via `TransactionalBitmap::getArray`, or `EMPTY_INT_ARRAY` if not found
- Method `getReferencedPrimaryKeysForIndexPks(Bitmap)` (line 232): iterates `referencedPrimaryKeysIndex` entries, checks `RoaringBitmap.intersects()`, collects matching referenced entity PKs
- Internal structure: `TransactionalMap<Integer, TransactionalBitmap> referencedPrimaryKeysIndex` -- maps **referenced entity PK** to **bitmap of reduced-index storage PKs**. For `REFERENCED_GROUP_ENTITY_TYPE`, the key is the group entity PK; for `REFERENCED_ENTITY_TYPE`, the key is the referenced entity PK.

**`ReducedGroupEntityIndex` -- per-group reduced index:**
- Path: `evita_engine/src/main/java/io/evitadb/index/ReducedGroupEntityIndex.java`
- Package: `io.evitadb.index`
- Handles `EntityIndexType.REFERENCED_GROUP_ENTITY` indexes
- Discriminator: `RepresentativeReferenceKey` (accessed via `getRepresentativeReferenceKey()` inherited from `AbstractReducedEntityIndex` line 210)
- `RepresentativeReferenceKey.referenceKey()` returns a `ReferenceKey(referenceName, primaryKey)` where `primaryKey` is the **group entity PK** (NOT the facet PK). This is a critical distinction from the WBS-07 document's initial assumption.
- **Correction to WBS-07 assumption:** The WBS-07 doc states "facetPK recovered from `EntityIndexKey.discriminator` (which is a `ReferenceKey(referenceName, facetPK)`)". This is **incorrect** for `REFERENCED_GROUP_ENTITY` indexes. The discriminator's `primaryKey` is the **group entity PK**. For `GROUP_ENTITY_ATTRIBUTE` resolution where `mutatedEntityPK` IS the group PK, the facet PKs must be resolved differently -- see `referencedPrimaryKeysIndex` field on `ReducedGroupEntityIndex` (line 103): `TransactionalMap<Integer, TransactionalBitmap> referencedPrimaryKeysIndex` which maps referenced entity PK (facet PK) to bitmaps of owner entity PKs within that group. This provides the (facetPK, ownerPKs) mapping per group.
- `getAllPrimaryKeys()` (inherited from `EntityIndex` line 290): returns `this.entityIds` -- bitmap of all owner entity PKs in this index
- Cardinality tracking: `pkCardinalities` (`TransactionalMap<Integer, Integer>`) tracks how many references contribute each owner PK to this group index; `insertPrimaryKeyIfMissing(int entityPK, int referencedEntityPK)` increments cardinality

**`ReducedEntityIndex` -- per-referenced-entity reduced index:**
- Path: `evita_engine/src/main/java/io/evitadb/index/ReducedEntityIndex.java`
- Package: `io.evitadb.index`
- Handles `EntityIndexType.REFERENCED_ENTITY` indexes
- Discriminator: `RepresentativeReferenceKey` (accessed via `getRepresentativeReferenceKey()`)
- `RepresentativeReferenceKey.referenceKey()` returns `ReferenceKey(referenceName, primaryKey)` where `primaryKey` is the **referenced entity PK** (= facet PK for `REFERENCED_ENTITY_ATTRIBUTE` case)
- `getAllPrimaryKeys()`: returns bitmap of owner entity PKs referencing this specific entity

**`EntityIndexKey` -- record structure:**
- Path: `evita_engine/src/main/java/io/evitadb/index/EntityIndexKey.java`
- Declaration: `public record EntityIndexKey(@Nonnull EntityIndexType type, @Nonnull Scope scope, @Nullable Serializable discriminator)`
- Discriminator types enforced by constructor validation (line 64-98):
  - `GLOBAL`: discriminator must be `null`
  - `REFERENCED_ENTITY_TYPE`, `REFERENCED_GROUP_ENTITY_TYPE`: discriminator must be `String` (reference name)
  - `REFERENCED_ENTITY`, `REFERENCED_GROUP_ENTITY`: discriminator must be `RepresentativeReferenceKey`
- `EntityIndexType` enum (line 41): `GLOBAL`, `REFERENCED_ENTITY_TYPE`, `REFERENCED_ENTITY`, `REFERENCED_HIERARCHY_NODE` (deprecated), `REFERENCED_GROUP_ENTITY_TYPE`, `REFERENCED_GROUP_ENTITY`

**`RepresentativeReferenceKey` -- compound key for reduced indexes:**
- Path: `evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/RepresentativeReferenceKey.java`
- Declaration: `public record RepresentativeReferenceKey(@Nonnull ReferenceKey referenceKey, @Nonnull Serializable[] representativeAttributeValues)`
- `referenceName()` (line 128): delegates to `this.referenceKey.referenceName()`
- `primaryKey()` (line 137): delegates to `this.referenceKey.primaryKey()` -- for `REFERENCED_ENTITY` this is the referenced entity PK; for `REFERENCED_GROUP_ENTITY` this is the group entity PK

**`ReferenceKey` -- reference identifier:**
- Path: `evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/reference/ReferenceKey.java`
- Declaration: `public record ReferenceKey(@Nonnull String referenceName, int primaryKey, int internalPrimaryKey)`
- Used as the key for facet operations: `new ReferenceKey(referenceName, facetPK)` -- where `facetPK` is the referenced entity PK (the facet itself)

**Facet add/remove operations on `EntityIndex`:**
- `EntityIndex` (abstract base class) uses `@Delegate(types = FacetIndexContract.class)` to delegate to `FacetIndex facetIndex` field (line 127-128)
- `FacetIndexContract` interface at `evita_engine/src/main/java/io/evitadb/index/facet/FacetIndexContract.java`:
  - `void addFacet(@Nullable ReferenceSchemaContract referenceSchema, @Nonnull ReferenceKey referenceKey, @Nullable Integer groupId, int entityPrimaryKey)` (line 56)
  - `void removeFacet(@Nullable ReferenceSchemaContract referenceSchema, @Nonnull ReferenceKey referenceKey, @Nullable Integer groupId, int entityPrimaryKey)` (line 69)
- These methods are available on ALL `EntityIndex` subclasses including `GlobalEntityIndex`, `ReducedEntityIndex`, `ReducedGroupEntityIndex`
- `AbstractReducedEntityIndex` overrides to add `assertPartitioningIndex()` guard (line 290-298)
- `FacetIndex.addFacet()` (line 144): creates or retrieves `FacetReferenceIndex` by `referenceKey.referenceName()`, then delegates to `FacetReferenceIndex.addFacet(facetPK, groupId, entityPK)`
- `FacetIndex.removeFacet()` (line 169): retrieves `FacetReferenceIndex`, calls `removeFacet()`, cleans up empty indexes
- Both operations are **idempotent** at the `FacetIdIndex` level -- adding a PK that already exists or removing one that doesn't exist are safe no-ops

**`ReferenceIndexMutator.addFacetToIndex()` / `removeFacetInIndex()` -- existing facet mutation pattern:**
- Path: `evita_engine/src/main/java/io/evitadb/index/mutation/index/ReferenceIndexMutator.java`
- `addFacetToIndex()` (line 1123): signature `static void addFacetToIndex(@Nonnull EntityIndex index, @Nonnull ReferenceSchemaContract referenceSchema, @Nonnull ReferenceKey referenceKey, @Nullable Integer groupId, int entityPrimaryKey, @Nonnull EntityIndexLocalMutationExecutor executor, @Nullable Consumer<Runnable> undoActionConsumer)` -- guards with `shouldIndexFacetToTargetIndex()` and `isFaceted()`, then calls `index.addFacet(referenceSchema, referenceKey, groupId, entityPrimaryKey)`. Note: the guard checks require `EntityIndexLocalMutationExecutor` which the cross-entity executor does NOT have -- the executor must call `index.addFacet()` directly, bypassing the guard (expression evaluation replaces the isFaceted guard).
- `removeFacetInIndex()` (line 1276): similar structure but also loads existing reference to determine groupId. Again, cross-entity executor already knows groupId from PK resolution, so bypasses this.
- **Key finding for executor design:** The cross-entity executor does NOT need `EntityIndexLocalMutationExecutor` or undo actions. It calls `index.addFacet()` / `index.removeFacet()` directly on `EntityIndex` instances obtained via `IndexMutationTarget`. The expression evaluation result replaces the `isFaceted()` guard. The `shouldIndexFacetToTargetIndex()` check (whether the index should carry facets) is determined by the target index resolution -- only indexes that support facet partitioning are included in `resolveTargetIndexes()`.

**Bitmap set operations -- AND and ANDNOT:**
- `RoaringBitmapBackedBitmap.and(RoaringBitmap[])` (line 131 of `RoaringBitmapBackedBitmap.java`): static method computing conjunction of multiple bitmaps. Returns `Bitmap`. For two-operand AND, call as `RoaringBitmapBackedBitmap.and(new RoaringBitmap[]{bitmap1, bitmap2})`.
- **No static `andNot` exists on `RoaringBitmapBackedBitmap`**. Use `RoaringBitmap.andNot(RoaringBitmap x1, RoaringBitmap x2)` directly (returns `RoaringBitmap`), then wrap in `BaseBitmap`. Example usage found in `BitmapChanges.java` line 142: `RoaringBitmap.andNot(RoaringBitmap.or(originalBitmap, insertions), removals)`.
- `RoaringBitmapBackedBitmap.getRoaringBitmap(Bitmap)` (line 72): extracts `RoaringBitmap` from any `Bitmap` implementation without copying.
- For `shouldBeFaceted = AND(allAffectedOwnerPKs, currentlyTruePKs)`: use `RoaringBitmapBackedBitmap.and(new RoaringBitmap[]{getRoaringBitmap(affected), getRoaringBitmap(truePKs)})`
- For `shouldNotBeFaceted = ANDNOT(allAffectedOwnerPKs, currentlyTruePKs)`: use `new BaseBitmap(RoaringBitmap.andNot(getRoaringBitmap(affected), getRoaringBitmap(truePKs)))`

**FilterBy programmatic construction and parameterization:**
- `FilterBy` class at `evita_query/src/main/java/io/evitadb/api/query/filter/FilterBy.java`: constructor accepts `FilterConstraint...` children
- `ReferenceHaving` at `evita_query/src/main/java/io/evitadb/api/query/filter/ReferenceHaving.java`: constructor `ReferenceHaving(@Nonnull String referenceName, @Nonnull FilterConstraint... children)`
- `EntityHaving` at `evita_query/src/main/java/io/evitadb/api/query/filter/EntityHaving.java`: constructor `EntityHaving(@Nonnull FilterConstraint child)` -- accepts a **single** child filter constraint; filters based on the referenced entity's properties
- `GroupHaving` at `evita_query/src/main/java/io/evitadb/api/query/filter/GroupHaving.java`: constructor `GroupHaving(@Nonnull FilterConstraint child)` -- accepts a **single** child filter constraint; filters based on the group entity's properties
- `EntityPrimaryKeyInSet` at `evita_query/src/main/java/io/evitadb/api/query/filter/EntityPrimaryKeyInSet.java`: constructor `EntityPrimaryKeyInSet(int... primaryKeys)`
- `And` at `evita_query/src/main/java/io/evitadb/api/query/filter/And.java`: constructor `And(@Nonnull FilterConstraint... children)`
- **Parameterization approach for GROUP_ENTITY_ATTRIBUTE:** The trigger's `FilterBy` already wraps the expression in a `referenceHaving(referenceName, ...)`. To parameterize, inject a `groupHaving(entityPrimaryKeyInSet(mutatedPK))` into the existing `referenceHaving` children using an `and(existingChildren..., groupHaving(entityPrimaryKeyInSet(mutatedPK)))`. Concretely:
  ```java
  FilterConstraint groupScope = new GroupHaving(new EntityPrimaryKeyInSet(mutatedPK));
  // Clone existing referenceHaving children and add the scope constraint
  FilterConstraint[] existingChildren = trigger.getFilterByConstraint().getChildren();
  // Reconstruct: new FilterBy(and(existingChildren..., groupScope)) or inject into the referenceHaving
  ```
- **Parameterization approach for REFERENCED_ENTITY_ATTRIBUTE:** inject `entityHaving(entityPrimaryKeyInSet(mutatedPK))` similarly:
  ```java
  FilterConstraint entityScope = new EntityHaving(new EntityPrimaryKeyInSet(mutatedPK));
  ```
- The parameterized constraint is then passed to `target.evaluateFilter(parameterizedFilterBy)` which evaluates against the collection's `GlobalEntityIndex`.

**`FilterByVisitor.getMatchingGroupEntityPrimaryKeys()` -- existing reverse mapping pattern (line 810):**
- Confirms the two-step pattern: evaluates a `FilterBy` against `REFERENCED_GROUP_ENTITY_TYPE` indexes to get reduced-index PKs, then calls `getReferencedPrimaryKeysForIndexPks()` to map those back to group entity PKs.
- This is the reverse direction of what the executor needs -- the executor has a group entity PK and needs reduced-index storage PKs (forward direction via `getAllReferenceIndexes(groupPK)`). The `getReferencedPrimaryKeysForIndexPks()` method exists for other use cases but is not needed by the executor.

**`ReducedGroupEntityIndex.referencedPrimaryKeysIndex` -- facet PK to owner PK mapping within a group:**
- Field (line 103): `TransactionalMap<Integer, TransactionalBitmap> referencedPrimaryKeysIndex` -- maps **referenced entity PK** (= facet PK) to **bitmap of owner entity PKs** within this group
- `insertPrimaryKeyIfMissing(int entityPK, int referencedEntityPK)` (line 341): tracks the mapping
- This field provides the facet PK resolution for GROUP_ENTITY_ATTRIBUTE: when we resolve a `ReducedGroupEntityIndex` for a specific group, its `referencedPrimaryKeysIndex` keys give us the facet PKs, and for each facet PK, the bitmap gives us the owner PKs
- **Important:** This field is `private` -- accessor is needed (or use the discriminator approach from the original WBS). However, the discriminator `RepresentativeReferenceKey.primaryKey()` is the **group PK**, not the facet PK. The facet PKs are available from the `referencedPrimaryKeysIndex` entries.
- **Resolution approach for GROUP_ENTITY_ATTRIBUTE:** For each `ReducedGroupEntityIndex` resolved from `getAllReferenceIndexes(groupPK)`:
  1. The group PK = `mutatedEntityPK` (the group entity that changed)
  2. The facet PKs come from the `referencedPrimaryKeysIndex.keySet()` -- each key is a referenced entity PK (facet PK)
  3. For each facet PK, the owner PKs come from `referencedPrimaryKeysIndex.get(facetPK)`
  4. Alternatively, `getAllPrimaryKeys()` gives the union of all owner PKs across all facet PKs in this group

**Resolution approach for REFERENCED_ENTITY_ATTRIBUTE:**
- `ReferencedTypeEntityIndex` for `REFERENCED_ENTITY_TYPE` maps referenced entity PK to reduced-index storage PKs
- `getAllReferenceIndexes(mutatedEntityPK)` returns storage PKs of `ReducedEntityIndex` instances
- Each `ReducedEntityIndex`'s discriminator `RepresentativeReferenceKey.primaryKey()` IS the referenced entity PK = facet PK = `mutatedEntityPK`
- The group PK must be determined from the facet index structure. The group PK is not needed for determining shouldBeFaceted/shouldNotBeFaceted (bitmap operations) -- only for the actual add/remove calls.
- **Chosen approach:** Add a `@Nullable Integer getGroupIdForFacet(int facetPK)` method to `FacetReferenceIndex` (see Group 10 in task breakdown). This method iterates `FacetGroupIndex` instances and returns the `groupId` of the group containing the facet PK, or null for ungrouped facets. The lookup is performed once per facet PK during resolution via the `GlobalEntityIndex`'s `FacetIndex` -> `FacetReferenceIndex`. Passing the wrong groupId to `addFacet()`/`removeFacet()` would create a duplicate entry in the wrong group, so the correct groupId must be determined -- null cannot be used as a default.

**`EntityIndex.getAllPrimaryKeys()` -- owner PK retrieval:**
- Path: `evita_engine/src/main/java/io/evitadb/index/EntityIndex.java` line 290
- Returns `this.entityIds` (a `Bitmap`) -- the set of all entity PKs in this index

**Target index routing -- existing pattern in `ReferenceIndexMutator`:**
- `GlobalEntityIndex` is always a target -- accessed via `EntityIndexKey(GLOBAL, scope)`
- `ReducedEntityIndex` instances for the reference are targets only when `isIndexedReferenceForFilteringAndPartitioning()` is true for the reference schema in that scope
- The `resolveTargetIndexes()` method should retrieve the `GlobalEntityIndex` and then check for applicable `ReducedEntityIndex` instances per reference

**Package placement** (from WBS-06 research):
- `ReevaluateFacetExpressionExecutor`: `io.evitadb.index.mutation` (`evita_engine` module) -- same package as `IndexMutationExecutor`, `IndexMutationExecutorRegistry`
- `AffectedEntityResolution`, `AffectedFacetGroup`, `AffectedFacetEntry`: nested records within `ReevaluateFacetExpressionExecutor` or standalone records in the same package

#### Detailed Task List

**Group 1: Supporting records -- `AffectedEntityResolution`, `AffectedFacetGroup`, `AffectedFacetEntry`**

- [ ] Create `AffectedFacetGroup` record in `evita_engine/src/main/java/io/evitadb/index/mutation/` (or as nested record in executor). Fields: `int facetPK`, `@Nullable Integer groupPK`, `@Nonnull Bitmap ownerPKs`. JavaDoc: represents a single (facetPK, groupPK) tuple with the set of owner entity PKs that have this facet in this group. The `groupPK` is nullable to support ungrouped facets.
- [ ] Create `AffectedFacetEntry` record -- fields: `int facetPK`, `@Nullable Integer groupPK`, `int ownerPK`. JavaDoc: individual entry for iteration during add/remove operations. The `groupPK` is nullable to support ungrouped facets.
- [ ] Create `AffectedEntityResolution` record -- field: `@Nonnull List<AffectedFacetGroup> groups`. Implement:
  - `@Nonnull Bitmap allOwnerPKs()` -- union of all `ownerPKs` across all groups. Use `RoaringBitmapWriter` to collect, then wrap in `BaseBitmap`. Compute eagerly (called once) — no caching needed.
  - `@Nonnull Iterable<AffectedFacetEntry> entriesForOwnerPKs(@Nonnull Bitmap pks)` -- iterate all groups, for each group iterate `ownerPKs` checking membership in `pks` bitmap via `RoaringBitmap.contains()`. Return flattened iterable of `AffectedFacetEntry`. Avoid materializing full list -- use lazy iteration.
- [ ] Add comprehensive JavaDoc to all three records explaining their role in the PK resolution pipeline.

**Group 2: `resolveAffected()` for `GROUP_ENTITY_ATTRIBUTE`**

- [ ] Implement the `GROUP_ENTITY_ATTRIBUTE` branch in `resolveAffected()`:
  1. Look up `ReferencedTypeEntityIndex` via `target.getIndexIfExists(new EntityIndexKey(REFERENCED_GROUP_ENTITY_TYPE, mutation.scope(), mutation.referenceName()))`. Return empty resolution if null.
  2. Call `rtei.getAllReferenceIndexes(mutation.mutatedEntityPK())` -- returns `int[]` storage PKs of `ReducedGroupEntityIndex` instances for this group.
  3. For each storage PK, call `target.getIndexByPrimaryKeyIfExists(storagePK)` to get the `ReducedGroupEntityIndex` instance. Skip if null.
  4. Cast to `ReducedGroupEntityIndex` (or check type via `getIndexKey().type() == REFERENCED_GROUP_ENTITY`).
  5. The **group PK** = `mutation.mutatedEntityPK()` (the group entity that changed).
  6. The **facet PK** = `reducedGroupIndex.getRepresentativeReferenceKey().primaryKey()` -- **CORRECTION: this is the group PK, not the facet PK**. The facet PKs must come from the `referencedPrimaryKeysIndex` field. However, this field is private. Two options:
     - a) Use `getAllPrimaryKeys()` to get all owner PKs, and construct a single `AffectedFacetGroup` with facetPK determined from the reference structure. Since one `ReducedGroupEntityIndex` can contain multiple facet PKs (multiple referenced entities within the same group), this needs careful handling.
     - b) Add a package-private or public accessor for `referencedPrimaryKeysIndex` keys on `ReducedGroupEntityIndex`. This would return the set of facet PKs within the group.
     - **Decision:** Option (b) is cleaner. Add `@Nonnull Set<Integer> getReferencedEntityPrimaryKeys()` method to `ReducedGroupEntityIndex` that returns `this.referencedPrimaryKeysIndex.keySet()`. For each facet PK, the owner PKs within that facet are `this.referencedPrimaryKeysIndex.get(facetPK)`. However, for the bitmap comparison approach (shouldBeFaceted/shouldNotBeFaceted), we need owner PKs at the group level (not per-facet) because the expression evaluation returns a flat bitmap. The per-facet split is only needed for the add/remove calls.
     - **Revised approach:** Collect owner PKs at the group level using `getAllPrimaryKeys()`. For the add/remove operations, iterate the `referencedPrimaryKeysIndex` entries to get per-facet owner PKs. Create one `AffectedFacetGroup` per facet PK within each `ReducedGroupEntityIndex`.
  7. For each `ReducedGroupEntityIndex`, iterate its referenced entity PKs (facet PKs). For each facet PK, create `AffectedFacetGroup(facetPK, groupPK, ownerPKsBitmap)` where `ownerPKsBitmap` comes from the `referencedPrimaryKeysIndex`.
- [ ] Add accessor method `@Nonnull Map<Integer, TransactionalBitmap> getReferencedEntityPrimaryKeysIndex()` (or `@Nonnull Set<Map.Entry<Integer, TransactionalBitmap>> getReferencedEntityEntries()`) to `ReducedGroupEntityIndex` to expose the facet PK to owner PK mapping. This is a read-only accessor -- no mutation. Alternatively, add `@Nonnull Set<Integer> getReferencedEntityPrimaryKeys()` and `@Nullable Bitmap getOwnerPKsForReferencedEntity(int referencedEntityPK)`.

**Group 3: `resolveAffected()` for `REFERENCED_ENTITY_ATTRIBUTE`**

- [ ] Implement the `REFERENCED_ENTITY_ATTRIBUTE` branch in `resolveAffected()`:
  1. Look up `ReferencedTypeEntityIndex` via `target.getIndexIfExists(new EntityIndexKey(REFERENCED_ENTITY_TYPE, mutation.scope(), mutation.referenceName()))`. Return empty resolution if null.
  2. Call `rtei.getAllReferenceIndexes(mutation.mutatedEntityPK())` -- returns `int[]` storage PKs of `ReducedEntityIndex` instances for this referenced entity.
  3. For each storage PK, call `target.getIndexByPrimaryKeyIfExists(storagePK)` to get the `ReducedEntityIndex` instance. Skip if null.
  4. The **facet PK** = `mutation.mutatedEntityPK()` (the referenced entity that changed = the facet itself).
  5. The **group PK** must be determined. Since `ReducedEntityIndex` does not carry group information directly, resolve the group PK by looking at the `GlobalEntityIndex`'s `FacetIndex`. Steps:
     - Get `GlobalEntityIndex` via `target.getIndexIfExists(new EntityIndexKey(GLOBAL, mutation.scope()))`
     - Access `globalIndex.getFacetIndex()` (delegated via `@Delegate`)
     - Look up the `FacetReferenceIndex` for `mutation.referenceName()`
     - Find which `FacetGroupIndex` contains `facetPK` -- iterate group indexes and check if facet PK is present
     - Extract `groupId` from the matching `FacetGroupIndex`
  6. The **owner PKs** = `reducedEntityIndex.getAllPrimaryKeys()`.
  7. Create `AffectedFacetGroup(facetPK, groupPK, ownerPKs)` for each resolved `ReducedEntityIndex`.
- [ ] Add `@Nullable Integer getGroupIdForFacet(int facetPK)` method to `FacetReferenceIndex` (see Group 10) — iterates `FacetGroupIndex` instances and returns the `groupId` of the group containing the facet PK, or null if the facet is in an ungrouped group or not found. Use this method to resolve the group PK during `REFERENCED_ENTITY_ATTRIBUTE` resolution.
- [ ] Handle the case where the facet PK is not found in any `FacetGroupIndex` (facet not currently indexed) -- this means the entity was never faceted, so the ownerPKs in the resolution will have empty facet state (all entries become candidates for "add" if the expression evaluates to true).

**Group 4: `parameterize()` -- FilterBy parameterization with PK-scoping**

- [ ] Implement `parameterize(FilterBy triggerFilterBy, String referenceName, int mutatedEntityPK, DependencyType dependencyType)` method:
  1. For `GROUP_ENTITY_ATTRIBUTE`: create PK-scoping constraint `new GroupHaving(new EntityPrimaryKeyInSet(mutatedEntityPK))`
  2. For `REFERENCED_ENTITY_ATTRIBUTE`: create PK-scoping constraint `new EntityHaving(new EntityPrimaryKeyInSet(mutatedEntityPK))`
  3. The trigger's `FilterBy` already contains a `referenceHaving(referenceName, ...)` subtree. The PK-scoping constraint must be injected **within** the `referenceHaving` children.
  4. Strategy: walk the trigger's `FilterBy` children, find the `ReferenceHaving` node for the matching `referenceName`, inject the PK-scoping constraint as an additional AND child. Complete pseudocode:
  ```java
  FilterConstraint pkScope = (dependencyType == GROUP_ENTITY_ATTRIBUTE)
      ? new GroupHaving(new EntityPrimaryKeyInSet(mutatedEntityPK))
      : new EntityHaving(new EntityPrimaryKeyInSet(mutatedEntityPK));

  // Walk FilterBy top-level children to find the ReferenceHaving node
  FilterConstraint[] topChildren = triggerFilterBy.getChildren();
  FilterConstraint[] newTopChildren = new FilterConstraint[topChildren.length];
  for (int i = 0; i < topChildren.length; i++) {
      if (topChildren[i] instanceof ReferenceHaving rh
          && rh.getReferenceName().equals(referenceName)) {
          // Inject pkScope as AND sibling of existing children
          FilterConstraint[] rhChildren = rh.getChildren();
          FilterConstraint[] andChildren =
              new FilterConstraint[rhChildren.length + 1];
          System.arraycopy(rhChildren, 0, andChildren, 0, rhChildren.length);
          andChildren[rhChildren.length] = pkScope;
          newTopChildren[i] = new ReferenceHaving(
              referenceName, new And(andChildren)
          );
      } else {
          newTopChildren[i] = topChildren[i];
      }
  }
  return new FilterBy(newTopChildren);
  ```
  5. If the existing `ReferenceHaving` children already have an `And` wrapper, the new `And(existingChildren..., pkScope)` wraps both the old AND and the scope constraint. This is functionally equivalent and the query engine handles nested ANDs correctly.
- [ ] Add comprehensive JavaDoc explaining why PK-scoping is critical (prevent false positives from unrelated groups/references -- see WBS-07 "Hidden Traps" #15).
- [ ] Handle edge cases: trigger's FilterBy with multiple `referenceHaving` children (unlikely but possible with OR expressions), trigger with inline AND already.
- [ ] Import classes from `io.evitadb.api.query.filter`: `FilterBy`, `ReferenceHaving`, `EntityHaving`, `GroupHaving`, `EntityPrimaryKeyInSet`, `And`, `FilterConstraint`.

**Group 5: Bitmap comparison and add/remove operations in `execute()`**

- [ ] Implement the core `execute()` method flow as specified in the WBS-07 code outline:
  1. Call `resolveAffected(target, mutation)` -- get `AffectedEntityResolution`
  2. Call `target.getTrigger(referenceName, dependencyType, scope)` -- return early if null
  3. Call `parameterize(trigger.getFilterByConstraint(), ...)` -- get scoped `FilterBy`
  4. Call `target.evaluateFilter(parameterizedFilter)` -- get `currentlyTruePKs` bitmap
  5. Compute `shouldBeFaceted = AND(allAffectedOwnerPKs, currentlyTruePKs)` using `RoaringBitmapBackedBitmap.and(new RoaringBitmap[]{...})`
  6. Compute `shouldNotBeFaceted = ANDNOT(allAffectedOwnerPKs, currentlyTruePKs)` using `new BaseBitmap(RoaringBitmap.andNot(...))`
  7. Get `ReferenceSchemaContract` via `target.getEntitySchema().getReference(mutation.referenceName()).orElseThrow()`
  8. Get target indexes via `resolveTargetIndexes(target, mutation.referenceName())`
  9. For each entry in `affected.entriesForOwnerPKs(shouldBeFaceted)`: call `index.addFacet(refSchema, new ReferenceKey(referenceName, entry.facetPK()), entry.groupPK(), entry.ownerPK())` on each target index
  10. For each entry in `affected.entriesForOwnerPKs(shouldNotBeFaceted)`: call `index.removeFacet(refSchema, new ReferenceKey(referenceName, entry.facetPK()), entry.groupPK(), entry.ownerPK())` on each target index
- [ ] Pass `entry.groupPK()` directly to `FacetIndex.addFacet()`/`removeFacet()` as the `@Nullable Integer groupId` parameter -- no conversion needed since `AffectedFacetEntry.groupPK()` is already `@Nullable Integer`.
- [ ] Import `RoaringBitmapBackedBitmap`, `BaseBitmap`, `RoaringBitmap`, `ReferenceKey`.

**Group 6: `resolveTargetIndexes()` -- target index routing**

- [ ] Implement `resolveTargetIndexes(IndexMutationTarget target, String referenceName, Scope scope)`:
  1. Always include `GlobalEntityIndex`: `target.getIndexIfExists(new EntityIndexKey(GLOBAL, scope))` -- assert not null.
  2. Check if `ReducedEntityIndex` instances should be included: look up `ReferenceSchemaContract` from `target.getEntitySchema()`, check if the reference is indexed for `FOR_FILTERING_AND_PARTITIONING` in the applicable scope.
  3. If applicable, include `ReducedEntityIndex` instances for the reference that have `FOR_FILTERING_AND_PARTITIONING` indexing level. Follow the same pattern as `ReferenceIndexMutator.addFacetToIndex()`: target `GlobalEntityIndex` always, plus `ReducedEntityIndex` instances when the reference schema has `FOR_FILTERING_AND_PARTITIONING` level in the applicable scope. This ensures facet state consistency between global and reduced indexes.
  4. Return `EntityIndex[]` array containing the target indexes.
- [ ] Add JavaDoc explaining the routing decision and reference to existing `ReferenceIndexMutator` pattern.

**Group 7: `ReevaluateFacetExpressionExecutor` class**

- [ ] Create `ReevaluateFacetExpressionExecutor.java` in `evita_engine/src/main/java/io/evitadb/index/mutation/` -- package-private class implementing `IndexMutationExecutor<ReevaluateFacetExpressionMutation>` (from WBS-06).
- [ ] Class is stateless singleton -- no instance fields, no constructor arguments (beyond default).
- [ ] Implement `execute(@Nonnull ReevaluateFacetExpressionMutation mutation, @Nonnull IndexMutationTarget target)` as described in Group 5.
- [ ] Implement `resolveAffected()`, `parameterize()`, `resolveTargetIndexes()` as private methods.
- [ ] Add comprehensive class-level JavaDoc explaining the full reevaluation pipeline, the two-step PK resolution, and why this is a stateless singleton.
- [ ] Import all required types from `io.evitadb.index`, `io.evitadb.index.bitmap`, `io.evitadb.api.query.filter`, `io.evitadb.api.requestResponse.schema`, `io.evitadb.api.requestResponse.data.mutation.reference`.

**Group 8: Registration in `IndexMutationExecutorRegistry`**

- [ ] Verify that `IndexMutationExecutorRegistry.INSTANCE` (from WBS-06) includes the entry: `ReevaluateFacetExpressionMutation.class -> new ReevaluateFacetExpressionExecutor()`. If WBS-06 used a stub, replace the stub with the real executor.
- [ ] Ensure the executor is imported in the registry file.

**Group 9: Accessor methods on `ReducedGroupEntityIndex`**

- [ ] Add `@Nonnull Set<Integer> getReferencedEntityPrimaryKeys()` method to `ReducedGroupEntityIndex` -- returns `this.referencedPrimaryKeysIndex.keySet()`. This exposes the set of facet PKs within the group.
- [ ] Add `@Nullable Bitmap getOwnerPKsForReferencedEntity(int referencedEntityPK)` method to `ReducedGroupEntityIndex` -- returns `this.referencedPrimaryKeysIndex.get(referencedEntityPK)`. Returns the bitmap of owner entity PKs that reference the given entity within this group, or null if not found.
- [ ] Add JavaDoc to both methods explaining their use in the cross-entity reevaluation pipeline.

**Group 10: Accessor method on `FacetReferenceIndex` (for REFERENCED_ENTITY_ATTRIBUTE group PK resolution)**

- [ ] Add `@Nullable Integer getGroupIdForFacet(int facetPK)` method to `FacetReferenceIndex` at `evita_engine/src/main/java/io/evitadb/index/facet/FacetReferenceIndex.java`. Implementation: iterate `FacetGroupIndex` instances (via existing group index collection), check if the facet PK is present in the group's `facetIdIndexes`, return the `groupId` of the matching group. Return null if the facet is in an ungrouped group or not found in any group.
- [ ] Add JavaDoc explaining this is used by the cross-entity reevaluation executor to determine the group assignment for a facet when resolving `REFERENCED_ENTITY_ATTRIBUTE` dependencies.

**Group 11: Compilation verification**

- [ ] Verify all new/modified types compile cleanly within the `evita_engine` module -- run `mvn compile -pl evita_engine` (or via IntelliJ MCP build).
- [ ] Verify no circular dependencies between `io.evitadb.index.mutation` (executor) and `io.evitadb.index` (index classes, bitmap utilities).
- [ ] Verify that the executor does not import anything from `io.evitadb.index.mutation.storagePart` or `io.evitadb.core.collection` -- it accesses collection internals only through `IndexMutationTarget`.

### Test Cases

#### Test Class: `AffectedEntityResolutionTest`

**Location:** `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/AffectedEntityResolutionTest.java`

Covers the supporting records (`AffectedEntityResolution`, `AffectedFacetGroup`, `AffectedFacetEntry`) in isolation, verifying bitmap union computation and entry filtering logic.

**Category: `allOwnerPKs()` -- union bitmap computation**

- [ ] `all_owner_pks_returns_union_of_single_group` -- Create `AffectedEntityResolution` with one `AffectedFacetGroup` containing ownerPKs {1, 2, 3}. Verify `allOwnerPKs()` returns bitmap {1, 2, 3}.
- [ ] `all_owner_pks_returns_union_across_multiple_groups` -- Create resolution with three groups: group A ownerPKs {1, 2}, group B ownerPKs {3, 4}, group C ownerPKs {5}. Verify `allOwnerPKs()` returns bitmap {1, 2, 3, 4, 5}.
- [ ] `all_owner_pks_handles_overlapping_owner_pks_across_groups` -- Create resolution with two groups sharing ownerPKs: group A {1, 2, 3}, group B {2, 3, 4}. Verify `allOwnerPKs()` returns bitmap {1, 2, 3, 4} with no duplicates.
- [ ] `all_owner_pks_returns_empty_bitmap_for_empty_resolution` -- Create resolution with an empty groups list. Verify `allOwnerPKs()` returns an empty bitmap.
- [ ] `all_owner_pks_returns_empty_bitmap_when_all_groups_have_empty_owner_pks` -- Create resolution with groups whose ownerPKs bitmaps are all empty. Verify `allOwnerPKs()` returns an empty bitmap.

**Category: `entriesForOwnerPKs()` -- filtered entry iteration**

- [ ] `entries_for_owner_pks_returns_all_entries_when_filter_matches_all` -- Create resolution with groups containing ownerPKs {1, 2, 3}. Pass bitmap {1, 2, 3} as filter. Verify all entries are returned with correct `facetPK`, `groupPK`, and `ownerPK` values.
- [ ] `entries_for_owner_pks_returns_subset_when_filter_is_partial` -- Create resolution with group (facetPK=10, groupPK=99, ownerPKs={1, 2, 3, 4}). Pass bitmap {2, 4} as filter. Verify only entries with ownerPK=2 and ownerPK=4 are returned, both carrying facetPK=10 and groupPK=99.
- [ ] `entries_for_owner_pks_returns_empty_when_filter_has_no_overlap` -- Create resolution with ownerPKs {1, 2, 3}. Pass bitmap {10, 20} as filter. Verify no entries are returned.
- [ ] `entries_for_owner_pks_returns_empty_for_empty_resolution` -- Create resolution with no groups. Pass any non-empty bitmap. Verify no entries are returned.
- [ ] `entries_for_owner_pks_returns_empty_for_empty_filter_bitmap` -- Create resolution with groups containing ownerPKs {1, 2, 3}. Pass empty bitmap. Verify no entries are returned.
- [ ] `entries_for_owner_pks_handles_multiple_groups_with_different_facet_pks` -- Create resolution with group A (facetPK=10, groupPK=99, ownerPKs={1, 2}) and group B (facetPK=20, groupPK=99, ownerPKs={2, 3}). Pass bitmap {2}. Verify two entries are returned: (facetPK=10, groupPK=99, ownerPK=2) and (facetPK=20, groupPK=99, ownerPK=2).
- [ ] `entries_for_owner_pks_preserves_facet_and_group_pk_per_entry` -- Create resolution with multiple groups having distinct facetPK and groupPK values. Verify each returned `AffectedFacetEntry` carries the correct facetPK and groupPK from its parent `AffectedFacetGroup`.

**Category: `AffectedFacetGroup` record invariants**

- [ ] `affected_facet_group_exposes_correct_fields` -- Create `AffectedFacetGroup(facetPK=10, groupPK=99, ownerPKs={1,2,3})`. Verify `facetPK()`, `groupPK()`, and `ownerPKs()` return the expected values.
- [ ] `affected_facet_entry_exposes_correct_fields` -- Create `AffectedFacetEntry(facetPK=10, groupPK=99, ownerPK=1)`. Verify all accessor methods return expected values.

---

#### Test Class: `ReevaluateFacetExpressionExecutorTest`

**Location:** `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/ReevaluateFacetExpressionExecutorTest.java`

Tests the `ReevaluateFacetExpressionExecutor` using mocked `IndexMutationTarget` to verify PK resolution, filter parameterization, bitmap set operations, add/remove routing, and edge cases. All tests use mocked indexes and schema contracts -- no real catalog or storage involved.

**Category: PK resolution -- GROUP_ENTITY_ATTRIBUTE**

- [ ] `resolve_affected_for_group_entity_attribute_returns_correct_facet_pks_and_owner_pks` -- Mock `IndexMutationTarget` to return a `ReferencedTypeEntityIndex` (for `REFERENCED_GROUP_ENTITY_TYPE`) whose `getAllReferenceIndexes(groupPK=99)` returns two storage PKs. Each storage PK resolves to a `ReducedGroupEntityIndex` with known `referencedPrimaryKeysIndex` entries (facetPK -> ownerPKs). Verify that `resolveAffected()` produces an `AffectedEntityResolution` with two `AffectedFacetGroup` entries where facetPKs come from the `referencedPrimaryKeysIndex` keys (NOT from the discriminator's `primaryKey()`) and groupPK=99 for all entries.
- [ ] `resolve_affected_for_group_entity_attribute_returns_empty_when_no_referenced_type_index_exists` -- Mock `target.getIndexIfExists()` to return null for `REFERENCED_GROUP_ENTITY_TYPE`. Verify that `resolveAffected()` returns an empty `AffectedEntityResolution` (no groups, empty `allOwnerPKs()`).
- [ ] `resolve_affected_for_group_entity_attribute_returns_empty_when_no_reduced_indexes_for_group` -- Mock `ReferencedTypeEntityIndex.getAllReferenceIndexes(groupPK)` to return an empty `int[]`. Verify empty resolution.
- [ ] `resolve_affected_for_group_entity_attribute_skips_null_reduced_indexes` -- Mock `getAllReferenceIndexes()` to return three storage PKs, but `getIndexByPrimaryKeyIfExists()` returns null for the second one. Verify that resolution includes entries from the first and third reduced indexes only.
- [ ] `resolve_affected_for_group_entity_attribute_with_multiple_facets_in_one_group` -- Mock a single `ReducedGroupEntityIndex` whose `referencedPrimaryKeysIndex` contains three facet PKs (10, 20, 30), each with different owner PK bitmaps. Verify three `AffectedFacetGroup` entries are created, all with groupPK=99 but distinct facetPKs and ownerPKs.
- [ ] `resolve_affected_for_group_entity_attribute_collects_all_owner_pks_in_union` -- Mock multiple `ReducedGroupEntityIndex` instances with overlapping owner PKs. Verify that `allOwnerPKs()` on the resolution returns the union without duplicates.

**Category: PK resolution -- REFERENCED_ENTITY_ATTRIBUTE**

- [ ] `resolve_affected_for_referenced_entity_attribute_returns_correct_facet_pk_and_owner_pks` -- Mock `IndexMutationTarget` to return a `ReferencedTypeEntityIndex` (for `REFERENCED_ENTITY_TYPE`) whose `getAllReferenceIndexes(referencedEntityPK=50)` returns one storage PK. That storage PK resolves to a `ReducedEntityIndex` whose `getAllPrimaryKeys()` returns {1, 2, 3}. Verify resolution has one `AffectedFacetGroup` with facetPK=50 (= `mutatedEntityPK`), correct groupPK (from `FacetReferenceIndex` lookup), and ownerPKs={1, 2, 3}.
- [ ] `resolve_affected_for_referenced_entity_attribute_returns_empty_when_no_referenced_type_index_exists` -- Mock `target.getIndexIfExists()` to return null for `REFERENCED_ENTITY_TYPE`. Verify empty resolution.
- [ ] `resolve_affected_for_referenced_entity_attribute_returns_empty_when_no_reduced_indexes_for_entity` -- Mock `getAllReferenceIndexes(referencedEntityPK)` to return empty `int[]`. Verify empty resolution.
- [ ] `resolve_affected_for_referenced_entity_attribute_resolves_group_pk_from_facet_index` -- Mock `GlobalEntityIndex` with a `FacetIndex` -> `FacetReferenceIndex` containing a `FacetGroupIndex` with groupId=77 that includes facetPK=50. Verify the resolved `AffectedFacetGroup` carries groupPK=77.
- [ ] `resolve_affected_for_referenced_entity_attribute_handles_null_group` -- Mock `FacetReferenceIndex` where the facet PK is in a group with null groupId (ungrouped facet). Verify the resolved `AffectedFacetGroup` carries groupPK as null/sentinel correctly.
- [ ] `resolve_affected_for_referenced_entity_attribute_with_multiple_reduced_indexes` -- Mock `getAllReferenceIndexes()` returning three storage PKs, each resolving to a different `ReducedEntityIndex` with distinct owner PKs. Verify three `AffectedFacetGroup` entries all share facetPK=mutatedEntityPK but have their own ownerPKs.

**Category: FilterBy parameterization**

- [ ] `parameterize_injects_group_having_for_group_entity_attribute` -- Given a trigger `FilterBy` of `filterBy(referenceHaving("parameter", attributeEquals("inputWidgetType", "CHECKBOX")))` and dependencyType=`GROUP_ENTITY_ATTRIBUTE` with mutatedPK=99. Verify the result contains `groupHaving(entityPrimaryKeyInSet(99))` ANDed into the `referenceHaving` children.
- [ ] `parameterize_injects_entity_having_for_referenced_entity_attribute` -- Given a trigger `FilterBy` of `filterBy(referenceHaving("parameter", entityHaving(attributeEquals("status", "ACTIVE"))))` and dependencyType=`REFERENCED_ENTITY_ATTRIBUTE` with mutatedPK=50. Verify the result contains `entityHaving(entityPrimaryKeyInSet(50))` ANDed into the `referenceHaving` children.
- [ ] `parameterize_preserves_existing_and_constraint` -- Given a trigger `FilterBy` that already has an `and(...)` wrapper inside `referenceHaving`. Verify the PK-scoping constraint is added as an additional child of the existing `and`, not nested in a new `and`.
- [ ] `parameterize_handles_complex_or_expression_across_dependency_types` -- Given a trigger `FilterBy` of `filterBy(referenceHaving("parameter", or(groupHaving(attributeEquals("status", "ACTIVE")), entityHaving(attributeEquals("status", "PREVIEW")))))` with dependencyType=`GROUP_ENTITY_ATTRIBUTE` and mutatedPK=99. Verify `groupHaving(entityPrimaryKeyInSet(99))` is injected correctly without disrupting the OR structure.

**Category: Bitmap set operations (shouldBeFaceted / shouldNotBeFaceted)**

- [ ] `bitmap_operations_all_affected_satisfy_expression` -- `allAffectedOwnerPKs` = {1, 2, 3, 4, 5}, `currentlyTruePKs` = {1, 2, 3, 4, 5}. Verify `shouldBeFaceted` = {1, 2, 3, 4, 5} and `shouldNotBeFaceted` = empty.
- [ ] `bitmap_operations_no_affected_satisfy_expression` -- `allAffectedOwnerPKs` = {1, 2, 3, 4, 5}, `currentlyTruePKs` = empty. Verify `shouldBeFaceted` = empty and `shouldNotBeFaceted` = {1, 2, 3, 4, 5}.
- [ ] `bitmap_operations_partial_overlap` -- `allAffectedOwnerPKs` = {1, 2, 3, 4, 5}, `currentlyTruePKs` = {2, 4, 6, 8}. Verify `shouldBeFaceted` = {2, 4} and `shouldNotBeFaceted` = {1, 3, 5}. Note: PK 6 and 8 are in `currentlyTruePKs` but not in affected set, so they are ignored (not in union, not subject to add/remove).
- [ ] `bitmap_operations_empty_affected_set` -- `allAffectedOwnerPKs` = empty, `currentlyTruePKs` = {1, 2, 3}. Verify both `shouldBeFaceted` and `shouldNotBeFaceted` are empty (no affected entities to act on).
- [ ] `bitmap_operations_both_empty` -- `allAffectedOwnerPKs` = empty, `currentlyTruePKs` = empty. Verify both result bitmaps are empty.

**Category: Add/remove facet operations**

- [ ] `execute_calls_add_facet_for_should_be_faceted_entries_on_all_target_indexes` -- Mock setup: affected ownerPKs={1, 2} with facetPK=10 and groupPK=99, `currentlyTruePKs`={1, 2}, two target indexes (GlobalEntityIndex + ReducedEntityIndex). Verify `addFacet(refSchema, ReferenceKey("parameter", 10), 99, 1)` and `addFacet(..., 99, 2)` are called on BOTH target indexes (4 calls total). Verify `removeFacet()` is NOT called.
- [ ] `execute_calls_remove_facet_for_should_not_be_faceted_entries_on_all_target_indexes` -- Mock setup: affected ownerPKs={1, 2} with facetPK=10 and groupPK=99, `currentlyTruePKs`=empty. Verify `removeFacet(refSchema, ReferenceKey("parameter", 10), 99, 1)` and `removeFacet(..., 99, 2)` are called on both target indexes. Verify `addFacet()` is NOT called.
- [ ] `execute_calls_both_add_and_remove_for_mixed_result` -- Mock setup: affected group A (facetPK=10, groupPK=99, ownerPKs={1, 2, 3}), `currentlyTruePKs`={1, 3}. Verify `addFacet()` called for ownerPKs {1, 3} and `removeFacet()` called for ownerPK {2}.
- [ ] `execute_constructs_correct_reference_key_for_facet_operations` -- Verify that the `ReferenceKey` passed to `addFacet()`/`removeFacet()` uses the mutation's `referenceName` and the facetPK from the resolved `AffectedFacetEntry`, NOT the mutatedEntityPK (which is the group PK for `GROUP_ENTITY_ATTRIBUTE`).
- [ ] `execute_passes_correct_group_pk_to_facet_operations` -- Verify that the `groupId` parameter passed to `addFacet()`/`removeFacet()` comes from the `AffectedFacetEntry.groupPK()`, not from any other source. For `GROUP_ENTITY_ATTRIBUTE`, this should be `mutatedEntityPK`.
- [ ] `execute_handles_multiple_affected_facet_groups` -- Mock setup: three `AffectedFacetGroup` entries with different facetPKs (10, 20, 30) all under groupPK=99. `currentlyTruePKs` includes some ownerPKs from each group. Verify add/remove calls use the correct facetPK per entry (not a shared facetPK).

**Category: Target index routing**

- [ ] `resolve_target_indexes_always_includes_global_entity_index` -- Mock `IndexMutationTarget` to return a `GlobalEntityIndex`. Verify `resolveTargetIndexes()` returns an array containing the `GlobalEntityIndex`.
- [ ] `resolve_target_indexes_includes_reduced_entity_index_when_filtering_and_partitioning` -- Mock reference schema with `FOR_FILTERING_AND_PARTITIONING` indexing level. Verify `resolveTargetIndexes()` returns both `GlobalEntityIndex` and applicable `ReducedEntityIndex`.
- [ ] `resolve_target_indexes_excludes_reduced_entity_index_when_not_partitioning` -- Mock reference schema without `FOR_FILTERING_AND_PARTITIONING` level. Verify `resolveTargetIndexes()` returns only `GlobalEntityIndex`.

**Category: Null trigger handling (early return)**

- [ ] `execute_returns_immediately_when_trigger_is_null` -- Mock `target.getTrigger()` to return null. Verify `execute()` returns without calling `evaluateFilter()`, `addFacet()`, or `removeFacet()`. Verify `resolveAffected()` may or may not be called (implementation detail), but no side effects occur.
- [ ] `execute_returns_immediately_when_trigger_is_null_even_with_affected_entities` -- Mock setup: `resolveAffected()` would return non-empty resolution, but `target.getTrigger()` returns null. Verify no facet operations are performed.

**Category: Idempotency**

- [ ] `execute_twice_with_same_mutation_produces_no_double_adds` -- Mock setup: first `execute()` adds facets for ownerPKs {1, 2, 3}. Run `execute()` again with the same mutation and identical index state (expression still true). Verify that `addFacet()` is called again (caller is idempotent), but the underlying `FacetIndex` does not create duplicate entries. Test this by using a real `FacetIndex` instance and verifying bitmap cardinality remains unchanged after the second call.
- [ ] `execute_twice_with_same_mutation_produces_no_double_removes` -- Mirror of above for removes: first `execute()` removes facets, second `execute()` calls `removeFacet()` again. Verify no errors and no state corruption.
- [ ] `execute_is_safe_when_local_and_cross_entity_triggers_both_fire` -- Simulate the scenario where a local trigger already added a facet for ownerPK=1, then the cross-entity executor also tries to add it. Verify the second add is a no-op at the `FacetIndex` level.

**Category: Full pipeline -- fan-out scenario 1 (purely cross-entity)**

- [ ] `fan_out_group_entity_attribute_change_removes_all_facets_when_expression_becomes_false` -- Reproduce Example 1 from the WBS document: Group entity PK=99 changes `inputWidgetType` from `CHECKBOX` to `RADIO`. Mock 10 `ReducedGroupEntityIndex` instances under group 99, each with 100 owner PKs (1000 total). Mock `evaluateFilter()` to return empty bitmap (expression is now false for all). Verify `removeFacet()` is called 1000 times (once per owner PK per facet PK combination) on target indexes. Verify no `addFacet()` calls.
- [ ] `fan_out_group_entity_attribute_change_adds_all_facets_when_expression_becomes_true` -- Reverse of above: expression was false, now becomes true. Mock `evaluateFilter()` to return all 1000 affected PKs. Verify `addFacet()` called for all 1000 and no `removeFacet()` calls.

**Category: Full pipeline -- fan-out scenario 2 (mixed-dependency expression)**

- [ ] `fan_out_mixed_dependency_expression_correctly_filters_active_products` -- Reproduce Example 2: expression depends on both `groupEntity.inputWidgetType == 'CHECKBOX'` AND `entity.isActive == true`. Group 99 changes from CHECKBOX to RADIO. Mock `evaluateFilter()` returning empty bitmap. Mock 1000 affected PKs (800 were previously faceted, 200 never faceted). Verify `removeFacet()` called 1000 times. The 200 removes for never-faceted entities are idempotent no-ops at the index level.

**Category: Full pipeline -- fan-out scenario 3 (multi-source cross-entity, OR)**

- [ ] `fan_out_multi_source_or_expression_retains_facets_where_second_operand_is_true` -- Reproduce Example 3: expression is `groupEntity.status == 'ACTIVE' || referencedEntity.status == 'PREVIEW'`. Group PK=99 changes status from ACTIVE to INACTIVE. Mock `evaluateFilter()` to return the subset of owner PKs whose referenced entity has status=PREVIEW (e.g., 300 of 1000). Verify `shouldBeFaceted` = those 300 PKs, `shouldNotBeFaceted` = remaining 700 PKs. Verify `addFacet()` for 300 and `removeFacet()` for 700.
- [ ] `fan_out_multi_source_or_expression_removes_all_when_neither_operand_is_true` -- Same setup but `evaluateFilter()` returns empty (no referenced entity has PREVIEW status either). Verify all 1000 are removed.

**Category: Edge cases**

- [ ] `execute_no_ops_when_affected_set_is_empty` -- Mock `resolveAffected()` returning empty resolution (no `ReducedGroupEntityIndex` or `ReducedEntityIndex` found). Verify no `evaluateFilter()`, `addFacet()`, or `removeFacet()` calls occur.
- [ ] `execute_no_ops_when_all_affected_already_match_current_facet_state` -- Mock setup: all affected ownerPKs are already faceted and expression is still true (shouldBeFaceted = all, shouldNotBeFaceted = empty). Verify `addFacet()` is called (idempotent) but no `removeFacet()` calls. The index state does not change.
- [ ] `execute_handles_single_owner_pk_in_resolution` -- Mock resolution with exactly one `AffectedFacetGroup` containing one ownerPK. Verify the correct add or remove is called for that single entry.
- [ ] `execute_handles_reference_schema_not_found_by_throwing` -- Mock `target.getEntitySchema().getReference(referenceName)` to return `Optional.empty()`. Verify that `execute()` throws an exception (via `orElseThrow()`) rather than silently skipping.
- [ ] `execute_handles_large_affected_set_efficiently` -- Mock resolution with 10,000 ownerPKs across 50 facet groups. Mock `evaluateFilter()` returning 5,000 PKs. Verify correct number of add (5,000) and remove (5,000) calls. This is a scalability smoke test, not a performance benchmark.
- [ ] `execute_handles_group_pk_as_nullable_integer_for_ungrouped_facets` -- Mock resolution where groupPK is null (ungrouped reference). Verify `addFacet()`/`removeFacet()` is called with `null` as the groupId parameter.

---

#### Test Class: `ReducedGroupEntityIndexTest` (additions)

**Location:** `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/ReducedGroupEntityIndexTest.java`

Tests for the new accessor methods added to `ReducedGroupEntityIndex` in Group 9.

**Category: New accessor methods for cross-entity reevaluation**

- [ ] `get_referenced_entity_primary_keys_returns_all_facet_pks_in_group` -- Insert three references into the index for facetPKs {10, 20, 30}. Verify `getReferencedEntityPrimaryKeys()` returns the set {10, 20, 30}.
- [ ] `get_referenced_entity_primary_keys_returns_empty_set_for_fresh_index` -- Create a new `ReducedGroupEntityIndex` with no inserts. Verify `getReferencedEntityPrimaryKeys()` returns an empty set.
- [ ] `get_owner_pks_for_referenced_entity_returns_correct_bitmap` -- Insert owner PKs {1, 2, 3} referencing facetPK=10 in the group. Verify `getOwnerPKsForReferencedEntity(10)` returns bitmap {1, 2, 3}.
- [ ] `get_owner_pks_for_referenced_entity_returns_null_for_unknown_facet_pk` -- Verify `getOwnerPKsForReferencedEntity(999)` returns null when facetPK 999 was never inserted.
- [ ] `get_owner_pks_for_referenced_entity_reflects_insertions_and_removals` -- Insert ownerPK=1 for facetPK=10, verify it appears. Remove it, verify the mapping updates accordingly (bitmap becomes empty or entry removed, depending on implementation).

---

#### Test Class: `FacetReferenceIndexTest` (additions)

**Location:** `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/facet/FacetReferenceIndexTest.java`

Tests for the new `getGroupIdForFacet()` method added to `FacetReferenceIndex` in Group 10.

**Category: Group PK lookup for cross-entity reevaluation**

- [ ] `get_group_id_for_facet_returns_correct_group_when_facet_exists_in_group` -- Add facet PK=10 to group 99 in the `FacetReferenceIndex`. Verify `getGroupIdForFacet(10)` returns 99.
- [ ] `get_group_id_for_facet_returns_null_when_facet_is_ungrouped` -- Add facet PK=10 with null group. Verify `getGroupIdForFacet(10)` returns null.
- [ ] `get_group_id_for_facet_returns_null_when_facet_does_not_exist` -- Do not add facet PK=999. Verify `getGroupIdForFacet(999)` returns null.
- [ ] `get_group_id_for_facet_returns_correct_group_when_multiple_groups_exist` -- Add facet PK=10 to group 99 and facet PK=20 to group 77. Verify `getGroupIdForFacet(10)` returns 99 and `getGroupIdForFacet(20)` returns 77.
- [ ] `get_group_id_for_facet_reflects_group_reassignment` -- Add facet PK=10 to group 99, then reassign it to group 77 (remove from 99, add to 77). Verify `getGroupIdForFacet(10)` returns 77 after reassignment.
