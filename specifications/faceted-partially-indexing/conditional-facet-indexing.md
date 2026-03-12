# Conditional Facet Indexing — Problem Analysis

## Problem Statement

`ReferenceSchemaContract#getFacetedPartially()` returns an `Expression` that narrows which entities
participate in faceting. When the expression evaluates to `true`, the entity's facet is indexed;
when `false`, it is not. We need to:

1. Evaluate the expression during indexing and conditionally add/remove facets
2. React to cascading data changes that invalidate/validate the expression result

**Scope note:** This analysis covers conditional **facet** indexing. Conditional **histogram** indexing
(via `@Histogram` / `bucketedPartially`) follows a similar architectural pattern but introduces
additional concerns (value expressions, named indexes, different index maintenance ops) — it will
be addressed in a follow-up assignment. The architecture designed here is intentionally extensible
to accommodate histograms (see `HistogramExpressionTrigger` subtype,
`ReevaluateHistogramExpressionMutation` + executor in the handler hierarchy).

---

## Domain 1: Expression Evaluation at Indexing Time

### What data does the expression need?

The expression can reference any property accessible through the expression language's variable
bindings (`$entity`, `$reference`) and accessor chain. `EntityContract` extends
`AttributesContract`, `AssociatedDataContract`, `PricesContract`, `ReferencesContract`,
`WithEntitySchema`, and `EntityClassifierWithParent` — so expressions can potentially access
all of these. The full set of accessible data paths:

| Path Pattern | Data Source | Storage Part(s) Needed |
|---|---|---|
| `$entity.primaryKey` | Owner entity identity | `EntityBodyStoragePart` |
| `$entity.attributes['x']` | Owner entity attribute | `AttributesStoragePart` (global) |
| `$entity.localizedAttributes['x']` | Owner entity localized attribute | `AttributesStoragePart` (per-locale) |
| `$entity.associatedData['x']` | Owner entity associated data | `AssociatedDataStoragePart` (global) |
| `$entity.localizedAssociatedData['x']` | Owner entity localized assoc. data | `AssociatedDataStoragePart` (per-locale) |
| `$entity.references['r']` | Owner entity references | `ReferencesStoragePart` |
| `$entity.parent` | Owner entity parent | `EntityBodyStoragePart` (parent PK) |
| `$reference.referencedPrimaryKey` | Reference key | Directly available from mutation context |
| `$reference.attributes['x']` | Reference attribute | `ReferencesStoragePart` (reference entry) |
| `$reference.localizedAttributes['x']` | Reference localized attribute | `ReferencesStoragePart` (reference entry) |
| `$reference.referencedEntity.*` | Referenced entity's properties | Target entity's storage parts (cross-collection) |
| `$reference.referencedEntity.references['r']*.attributes['x']` | Reference attribute on the referenced entity's reference 'r' | Target entity's `ReferencesStoragePart` (cross-collection) |
| `$reference.groupEntity?.*` | Group entity's properties | Group entity's storage parts (cross-collection) |
| `$reference.groupEntity?.references['r']*.attributes['x']` | Reference attribute on the group entity's reference 'r' | Group entity's `ReferencesStoragePart` (cross-collection) |

Nested paths on referenced/group entity follow the same pattern as `$entity.*` — the
expression can access any property supported by `EntityContract` on the nested entity,
**including its own references and their attributes** (but NOT the entities those references
point to — that would be a second entity hop).

### Accessor registrations — already exist

The expression evaluator uses `ObjectPropertyAccessor` and `ObjectElementAccessor` SPI
implementations, registered via ServiceLoader in `META-INF/services`. All needed registrations
already exist:

| Accessor | Type | Handles | Contract methods called |
|---|---|---|---|
| `EntityContractAccessor` | Property | `EntityContract` | `getPrimaryKey()`; wraps entity into DTOs for attributes, associated data, references |
| `ReferenceContractAccessor` | Property | `ReferenceContract` | `getReferencedPrimaryKey()`, `getReferencedEntity()`, `getGroupEntity()`; wraps into DTOs for attributes |
| `AttributesContractAccessor` | Element | `AttributesContract` | `getAttributeSchema(String)`, `getAttribute(String)`, `getAttribute(String, Locale)`, `getAttributeLocales()` |
| `ReferencesContractAccessor` | Element | `ReferencesContract` | `getSchema()`, `getReferences(String)` |
| `AssociatedDataContractAccessor` | Element | `AssociatedDataContract` | `getAssociatedDataSchema(String)`, `getAssociatedData(String)`, `getAssociatedData(String, Locale)`, `getAssociatedDataLocales()` |

No new accessor registrations are needed. Accessors work at the contract interface level — any
implementing class (including Proxycian proxies) works automatically via `ObjectAccessorRegistry`'s
type hierarchy traversal.

**DTO delegation pattern:** Each accessor wraps the contract into a scoped evaluation DTO (e.g.,
`EntityAttributesEvaluationDto`) that uses `@Delegate EntityContract`. This means the DTO
delegates ALL `EntityContract` methods to the underlying object. The proxy must implement every
method the DTO delegation might reach — not just the methods the accessor calls directly. Unused
methods must throw a controlled exception (see catch-all partial below).

### Chosen approach: Proxycian proxies with composable partial implementations

Constructing full `Entity`/`Reference` objects from storage parts requires building heavyweight
wrapper objects — `EntityAttributes` (builds `LinkedHashMap` from `AttributeValue[]`),
`References` (builds `HashMap` + duplicate detection), `AssociatedData`, `Prices` — even when
the expression only needs a single attribute value. For a fan-out of 1000 entities, this means
~5000 intermediate allocations.

Additionally, the base `Reference` class returns `Optional.empty()` for both
`getReferencedEntity()` and `getGroupEntity()` (`Reference.java:303-304`, `327-328`). Only
`ReferenceDecorator` populates these, but constructing a decorator requires the full entity
assembly pipeline.

Instead, we generate **lightweight Proxycian proxy classes** (using ByteBuddy) that implement
only the contract methods the specific expression actually calls. Each method classification
delegates to the raw storage part data without intermediate wrapper objects.

**Why this approach:**

- **Avoids circular dependency** — uses `WritableEntityStorageContainerAccessor` to fetch
  storage parts, not the query engine
- **Minimal allocations** — ~2 objects per entity (proxy instance + state record) instead of
  ~5-6 (Entity + EntityAttributes + LinkedHashMap + References + AssociatedData + Prices)
- **Fail-fast safety** — `@Delegate` in DTOs delegates ALL contract methods to the proxy.
  Unused methods throw a controlled `ExpressionEvaluationException` with the exact method name,
  immediately surfacing missing implementations in tests. This is safer than constructing an
  `Entity` with empty wrappers that silently return wrong data (e.g., empty collections for
  parts not loaded from storage)
- **Established pattern** — same approach as `ReferencedTypeEntityIndex.createThrowingStub()`
  (`ReferencedTypeEntityIndex:182-211`), already proven in the codebase

### Composable partial implementations

Each **partial** is a `PredicateMethodClassification` (or group thereof) that implements a
related set of contract methods backed by specific storage part data. Partials are reusable
building blocks — composed per expression at schema load time based on `AccessedDataFinder`
path analysis.

#### EntityContract partials

| Partial | Contract Methods Implemented | State Needed |
|---|---|---|
| **SchemaPartial** | `getSchema()`, `getType()` | `EntitySchemaContract` (always available) |
| **PrimaryKeyPartial** | `getPrimaryKey()` | `EntityBodyStoragePart` |
| **EntityAttributePartial** | `getAttribute(String)`, `getAttribute(String, Locale)`, `getAttributeSchema(String)`, `getAttributeLocales()`, `attributesAvailable()` | `AttributesStoragePart` (global + locale-specific) |
| **AssociatedDataPartial** | `getAssociatedData(String)`, `getAssociatedData(String, Locale)`, `getAssociatedDataSchema(String)`, `getAssociatedDataLocales()`, `associatedDataAvailable()` | `AssociatedDataStoragePart` (global + locale-specific) |
| **ReferencesPartial** | `getReferences(String)`, `getReference(String, int)`, `referencesAvailable()` | `ReferencesStoragePart` |
| **ParentPartial** | `getParent()`, `parentAvailable()` | `EntityBodyStoragePart` |
| **PricesPartial** *(future)* | `getPriceForSale(...)`, `getAllPricesForSale()`, `pricesAvailable()` | `PricesStoragePart` |
| **CatchAllPartial** *(always present)* | All other methods | (none) — throws `ExpressionEvaluationException` |

Implementation notes:

- `getAttribute(String)` does a **linear scan** of `AttributeValue[]` — faster than HashMap
  lookup for typical attribute counts (<30 entries) due to cache locality, and avoids the
  `LinkedHashMap` allocation entirely
- `getAttributeSchema(String)` delegates to `EntitySchemaContract.getAttribute(String)` — no
  storage part needed, just the schema reference
- `getReferences(String)` filters `Reference[]` from `ReferencesStoragePart` by reference name
- **SchemaPartial is always included** — `ReferencesContractAccessor` calls `getSchema()` to
  resolve cardinality, and `AttributesContractAccessor` calls `getAttributeSchema()` which
  delegates to schema

#### ReferenceContract partials

| Partial | Contract Methods Implemented | State Needed |
|---|---|---|
| **ReferenceIdentityPartial** | `getReferenceKey()`, `getReferencedPrimaryKey()`, `getReferencedEntityType()`, `getReferenceCardinality()`, `getReferenceSchema()`, `getReferenceName()` | `ReferenceKey` + `ReferenceSchemaContract` |
| **ReferenceAttributePartial** | `getAttribute(String)`, `getAttribute(String, Locale)`, `getAttributeSchema(String)`, `getAttributeLocales()`, `attributesAvailable()` | Reference's `AttributeValue[]` (from `ReferencesStoragePart` entry) |
| **GroupReferencePartial** | `getGroup()` | `GroupEntityReference` from reference data |
| **ReferencedEntityPartial** | `getReferencedEntity()` → returns `Optional.of(nestedEntityProxy)` | Referenced entity's storage parts → nested EntityContract proxy |
| **GroupEntityPartial** | `getGroupEntity()` → returns `Optional.of(nestedEntityProxy)` | Group entity's storage parts → nested EntityContract proxy |
| **CatchAllPartial** *(always present)* | All other methods | (none) — throws `ExpressionEvaluationException` |

Implementation notes:

- **ReferenceIdentityPartial is always included** — needed for the reference to identify itself
- `getReferencedPrimaryKey()` and `getReferenceName()` are default methods on `ReferenceContract`
  that delegate to `getReferenceKey()`, so implementing `getReferenceKey()` covers them
- **ReferencedEntityPartial** and **GroupEntityPartial** create nested EntityContract proxies
  (see wiring below) — the nested proxy class is ALSO pre-built at schema time with its own
  set of partials, determined by what the expression accesses on the nested entity

#### CatchAllPartial — the safety net

Always present as the **last** classification in every proxy. Matches all methods not handled
by selected partials:

```java
private static final PredicateMethodClassification<?, Void, ?> CATCH_ALL =
    new PredicateMethodClassification<>(
        "Unsupported expression method",
        (method, proxyState) -> true,  // matches everything not already matched
        (method, state) -> null,
        (proxy, method, args, methodContext, proxyState, invokeSuper) -> {
            throw new ExpressionEvaluationException(
                "Method " + method.getName() + "() is not available during expression " +
                "evaluation — the expression does not access this data.",
                "Cannot access " + method.getName() + "."
            );
        }
    );
```

This ensures that if the `@Delegate`-based DTOs forward a call to a method the expression
doesn't need, we get an immediate, informative failure rather than silent wrong data.

### Path-to-partial mapping

`AccessedDataFinder` analyzes the expression and produces paths (lists of `PathItem`). Each
path maps deterministically to a set of partials:

| Path prefix | Root proxy | Partial(s) selected |
|---|---|---|
| `$entity.primaryKey` | EntityContract | SchemaPartial + PrimaryKeyPartial |
| `$entity.attributes[...]` | EntityContract | SchemaPartial + EntityAttributePartial |
| `$entity.localizedAttributes[...]` | EntityContract | SchemaPartial + EntityAttributePartial |
| `$entity.associatedData[...]` | EntityContract | SchemaPartial + AssociatedDataPartial |
| `$entity.localizedAssociatedData[...]` | EntityContract | SchemaPartial + AssociatedDataPartial |
| `$entity.references[...]` | EntityContract | SchemaPartial + ReferencesPartial |
| `$entity.parent` | EntityContract | SchemaPartial + ParentPartial |
| `$reference.referencedPrimaryKey` | ReferenceContract | ReferenceIdentityPartial |
| `$reference.attributes[...]` | ReferenceContract | ReferenceIdentityPartial + ReferenceAttributePartial |
| `$reference.referencedEntity.*` | ReferenceContract | ReferenceIdentityPartial + ReferencedEntityPartial → **recurse** for nested entity partials |
| `$reference.groupEntity?.*` | ReferenceContract | ReferenceIdentityPartial + GroupEntityPartial → **recurse** for nested entity partials |

When an expression uses multiple paths, partials are **unioned** — e.g., an expression using
both `$entity.attributes['code']` and `$entity.associatedData['desc']` gets one EntityContract
proxy with SchemaPartial + EntityAttributePartial + AssociatedDataPartial + CatchAllPartial.

### Schema load time: build proxy classes and state recipe

When a schema is loaded or changed and contains a `facetedPartially` expression, the system
performs full pre-analysis:

```
1. Parse expression → ExpressionNode tree
2. AccessedDataFinder.findAccessedPaths(expression) → List<List<PathItem>>
3. Map paths to partials (per table above)
4. Union partials per root variable → final set of PredicateMethodClassification[]
5. Compose ByteBuddyDispatcherInvocationHandler for EntityContract proxy (if needed)
6. Compose ByteBuddyDispatcherInvocationHandler for ReferenceContract proxy (if needed)
7. Compose ByteBuddyDispatcherInvocationHandler for nested entity proxies (if needed)
8. ByteBuddy generates proxy classes (cached — one-time cost per unique partial set)
9. Build state recipe: which storage parts to fetch for each proxy
```

The result is stored in the `FacetExpressionTrigger` (see Domain 2) as:

- **Proxy class factories** — one per root variable and one per nested entity level. These
  are the pre-generated ByteBuddy classes, ready to be instantiated with a state record.
- **State recipe** — a descriptor of which storage parts to fetch from
  `WritableEntityStorageContainerAccessor` for each proxy level. Derived directly from the
  selected partials.

**No classes are generated at trigger time.** Only state records are created and pre-built
proxy classes instantiated.

### Trigger time: instantiate and wire

When a trigger fires, a single factory method on `FacetExpressionTrigger` creates the complete
evaluation context:

```java
ExpressionEvaluationContext createEvaluationContext(
    int entityPK,
    @Nonnull ReferenceContract reference,
    @Nonnull WritableEntityStorageContainerAccessor storageAccessor
)
```

This method:

1. **Fetches only the storage parts the recipe requires** from `storageAccessor` — e.g., if
   the expression only accesses `$entity.attributes['code']`, only `EntityBodyStoragePart` and
   the global `AttributesStoragePart` are fetched, not `ReferencesStoragePart`,
   `AssociatedDataStoragePart`, or `PricesStoragePart`
2. **Creates state records** — lightweight records holding references to fetched parts and
   schema objects
3. **Instantiates pre-built proxy classes** with those states — just object allocation + state
   assignment
4. **Wires nested proxies** — for expressions accessing `$reference.groupEntity?.attributes['x']`:
   - Fetch group entity's storage parts → create entity proxy state → instantiate entity proxy
   - Create reference proxy state with `groupEntity = entityProxy` → instantiate reference proxy
5. **Returns** `ExpressionEvaluationContext` with bound variables (`$entity`, `$reference`)

#### Wiring example

Expression: `$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'`

```
① Fetch group entity's EntityBodyStoragePart + AttributesStoragePart
   → create EntityProxyState(groupSchema, bodyPart, attrValues)
   → instantiate pre-built GroupEntityProxy class → groupEntityInstance

② Create ReferenceProxyState(refSchema, refKey, group, groupEntity=groupEntityInstance)
   → instantiate pre-built ReferenceProxy class → referenceInstance

③ Return context: { $reference = referenceInstance }
```

Total allocations: 2 state records + 2 proxy instances = **4 objects**, regardless of how
many entity properties exist. Compare with full `Entity` construction: Entity + EntityAttributes
+ LinkedHashMap + References + HashMap + AssociatedData + Prices = **7+ objects** with
intermediate collection building.

### Caching via DataStoreMemoryBuffer

Storage parts are fetched through `WritableEntityStorageContainerAccessor` backed by
`DataStoreMemoryBuffer`, which provides a read-through cache layer:

- **Within a single entity's mutation processing:** Multiple expressions (across different
  references of the same entity) likely access the same storage parts — the buffer serves
  them from cache after the first fetch
- **Cross-entity access:** When evaluating `$reference.groupEntity?.attributes['x']` for
  multiple owner entities that share the same group, the group entity's storage parts are
  fetched once and cached for subsequent evaluations
- **Transaction-scoped:** Cache is bounded to the transaction — discarded after mutation
  processing completes, so no persistent memory overhead

**To be verified by integration tests:** Confirm that `DataStoreMemoryBuffer` makes entity B's
pending changes visible to entity A's accessor when both are in the same transaction (relevant
for cross-entity triggers in Domain 2). This is a correctness prerequisite for the cross-entity
trigger mechanism.

### Resolved questions (Domain 1)

- [x] **Accessor registrations** — already exist (`EntityContractAccessor`,
  `ReferenceContractAccessor`, `AttributesContractAccessor`, `ReferencesContractAccessor`,
  `AssociatedDataContractAccessor`), all registered via ServiceLoader. No new registrations
  needed — Proxycian proxies implement the contract interfaces and work automatically.
- [x] **Minimal storage part set per expression** — determined by `AccessedDataFinder` path
  analysis at schema load time. Paths map to partials, partials declare their required storage
  parts. The union of required parts across all paths becomes the state recipe, stored in the
  trigger descriptor. Only those parts are fetched at trigger time.
- [x] **Pre-analyze vs lazy fetch** — **pre-analyze at schema load time**. The entire proxy
  class composition (partial selection → ByteBuddy class generation) and state recipe happen
  once when the schema is loaded or changed. At trigger time, only instantiation occurs — no
  class generation, no path analysis, no partial selection. This makes the hot path (expression
  evaluation during mutation processing) as cheap as possible.

---

## Domain 2: Triggers — When Must We Re-evaluate?

### Design Decision: Single Entity Hop Limitation

Expressions are limited to **single entity hop** — they can reference data on the entity itself,
the reference, the referenced entity, or the group entity, but NOT entities reachable through
further navigation (e.g., `$reference.referencedEntity.references['x'].referencedEntity...`).

**Clarification — references on the referenced/group entity are in scope:**
The referenced entity (or group entity) itself may have its own references, and expressions
**can** access reference attributes on those references. For example:

- `$reference.referencedEntity.references['x']*.attributes['A'] > 1` — valid (reads reference
  attribute on the referenced entity's reference 'x')
- `$reference.groupEntity?.references['y']*.attributes['B'] == 'active'` — valid (reads reference
  attribute on the group entity's reference 'y')
- `$reference.referencedEntity.references['x'].referencedEntity.attributes['z']` — **invalid**
  (second entity hop — navigates from the referenced entity through its reference to yet another
  entity)

The single-hop rule means: from the owner entity, you may navigate to exactly one other entity
(the referenced entity or group entity). Once there, you may read that entity's own properties
**including its references and their attributes**, but you may NOT follow those references to
reach a third entity.

**Rationale:**
- Without this, we'd face transitive closure explosion (A→B→C→D...)
- Cycle detection would require graph analysis at evaluation time
- Multi-hop cascades have non-deterministic ordering
- With single-hop, the dependency graph is a **bipartite graph** (entity ↔ target/group entity),
  which is tractable

### MECE Mutation Classification

**Note on category overlap:** Categories 2a-2c are MECE with respect to **where the mutation
occurs** (owner entity vs. referenced entity vs. group entity). However, a single **expression**
can reference data from multiple categories simultaneously — e.g.,
`$reference.groupEntity?.attributes['type'] == 'CHECKBOX' && $entity.attributes['isActive'] == true`
depends on both 2a (owner entity attribute) and 2c (group entity attribute). A cross-entity
change creates a `ReevaluateFacetExpressionMutation` — the target executor translates the full
expression to a `FilterBy` query and evaluates it against current indexes to determine which
entities should (or should not) be faceted. See "Expression-to-query translation" section below.

#### 2a. Mutations on the entity owning the reference (LOCAL triggers)

| Mutation                           | Triggers re-evaluation when...                                |
|------------------------------------|---------------------------------------------------------------|
| `UpsertAttributeMutation`          | Expression uses `$entity.attributes['x']`                     |
| `UpsertReferenceAttributeMutation` | Expression uses `$reference.attributes['x']`                  |
| `UpsertAssociatedDataMutation`     | Expression uses `$entity.associatedData['x']`                 |
| `RemoveAssociatedDataMutation`     | Expression uses `$entity.associatedData['x']`                 |
| `SetParentMutation`                | Expression uses `$entity.parent`                              |
| `RemoveParentMutation`             | Expression uses `$entity.parent`                              |
| `InsertReferenceMutation`          | Always — new facet candidate, must evaluate expression        |
| `RemoveReferenceMutation`          | Always — facet removal (existing logic, no expression needed) |
| `SetReferenceGroupMutation`        | Expression uses `$reference.groupEntity`                      |
| `RemoveReferenceGroupMutation`     | Expression uses `$reference.groupEntity`                      |

These are **local triggers** — handled within the same entity's mutation processing, inline
with existing `ReferenceIndexMutator` logic.

#### 2b. Mutations on the referenced entity (CROSS-ENTITY trigger)

| Mutation                              | Triggers re-evaluation when...                                |
|---------------------------------------|---------------------------------------------------------------|
| Attribute change on referenced entity | Expression uses `$reference.referencedEntity.attributes['x']` |
| Reference mutation on referenced entity (`InsertReferenceMutation`, `RemoveReferenceMutation`, `UpsertReferenceAttributeMutation` for reference 'r') | Expression uses `$reference.referencedEntity.references['r']*.attributes['x']` |
| `EntityRemoveMutation` on referenced entity | Expression uses `$reference.referencedEntity.*` — entity removal changes all accessed properties (null-safe paths return `null`, non-null-safe paths may throw). All dependent expressions must be re-evaluated. |

Entity A's facet indexing depends on entity B's data.

#### 2c. Mutations on the group entity (CROSS-ENTITY trigger, fan-out)

| Mutation                              | Triggers re-evaluation when...                                |
|---------------------------------------|---------------------------------------------------------------|
| Attribute change on group entity      | Expression uses `$reference.groupEntity?.attributes['x']`     |
| Reference mutation on group entity (`InsertReferenceMutation`, `RemoveReferenceMutation`, `UpsertReferenceAttributeMutation` for reference 'r') | Expression uses `$reference.groupEntity?.references['r']*.attributes['x']` |
| `EntityRemoveMutation` on group entity | Expression uses `$reference.groupEntity?.*` — group entity removal changes all accessed properties (null-safe `?.` returns `null`). All dependent expressions must be re-evaluated. |

Example: `$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'`

When group entity's `inputWidgetType` changes (or entity is removed) → must re-evaluate ALL
entities that reference this group entity through ANY reference type.

**Fan-out problem:** One attribute change on a group entity can cascade to thousands of entities.

**Reverse lookup needed:** Given entity X used as a group in reference type R, which entity PKs
reference X as their group?

Existing indexes that may help:

- `ReferencedTypeEntityIndex` — tracks entities per reference type
- `ReducedEntityIndex` per group — tracks entities within a specific group
- Group indexes in `EntityIndexLocalMutationExecutor` — `insertIntoGroupIndexes()` /
  `removeFromGroupIndexes()`

#### 2d. Schema mutations

| Mutation                              | Effect                                             |
|---------------------------------------|----------------------------------------------------|
| `facetedPartially` expression changes | Must re-index ALL entities for that reference type |
| Reference becomes faceted/unfaceted   | Already handled by existing logic                  |

**Out of scope:** Full re-index on expression change is deferred — for this iteration, the
`facetedPartially` expression is treated as effectively immutable after initial schema creation.
The re-index mechanism will be addressed in a separate issue.

### The Dependency Graph

```
Expression depends on:
├── $entity.attributes['x']                                     → local trigger (same entity mutation)
├── $entity.associatedData['x']                                 → local trigger (same entity mutation)
├── $entity.parent                                              → local trigger (same entity mutation)
├── $reference.attributes['x']                                  → local trigger (same reference mutation)
├── $reference.referencedEntity.attributes['x']                 → cross-entity trigger (referenced entity attribute change)
├── $reference.referencedEntity.references['r']*.attributes['x']→ cross-entity trigger (referenced entity reference mutation)
├── $reference.groupEntity?.attributes['x']                     → cross-entity trigger (group entity attribute change)
└── $reference.groupEntity?.references['r']*.attributes['x']    → cross-entity trigger (group entity reference mutation)
                                                                   ↑ THESE ARE THE FAN-OUT CASES
```

**Note on reference-level dependencies:** When the expression accesses
`$reference.referencedEntity.references['r']*.attributes['x']`, the trigger must fire on
reference mutations on the referenced entity (insert/remove of reference 'r', or change of
attribute 'x' on reference 'r') — not just on entity attribute mutations. This requires
separate `DependencyType` values (`REFERENCED_ENTITY_REFERENCE_ATTRIBUTE`,
`GROUP_ENTITY_REFERENCE_ATTRIBUTE`) to distinguish entity-attribute dependencies from
reference-attribute dependencies. The `getDependentAttributes()` for reference-level
dependencies must encode both the reference name and the attribute name.

The FilterBy translation for such paths produces nested `referenceHaving`:
`referenceHaving('ownerRef', entityHaving(referenceHaving('r', attributeGreaterThan('x', v))))`.

### Chosen approach: Post-processing triggers (modeled after `popImplicitMutations`)

#### How `popImplicitMutations` works (existing precedent)

1. Apply explicit mutations to both entity indexes and storage
2. `popImplicitMutations()` generates `ImplicitMutations` (local + external)
3. Local mutations applied to same entity's index and storage executors
4. External mutations recursively call `applyMutations()` on other entity collections
5. Implicit mutations NOT written to WAL — regenerated on replay
6. `LocalMutationExecutorCollector` handles nesting via `level` counter

#### Mutation ordering within a single entity

When multiple mutations are applied to the same entity (e.g.,
`[SetAttribute('code','ABC'), InsertReference('param',5)]`), the reference insert evaluates
the expression which may use `$entity.attributes['code']`. This works because mutations are
ordered by `LocalMutation#compareTo` and `ContainerizedLocalMutationExecutor` caches attribute
values locally — so attribute mutations sort before reference mutations and the expression
sees the updated value.

#### `ReflectedReferenceSchema` and inherited expressions

`ReflectedReferenceSchema` can inherit `facetedPartially` from the source `ReferenceSchema`.
Trigger registration must account for inherited expressions:

- When building triggers at schema load time, `EntityCollection` processes both direct and
  reflected reference schemas. For reflected schemas that inherit `facetedPartially`, the
  trigger is built from the inherited expression and registered under the reflected schema's
  entity type.
- When the source schema's `facetedPartially` expression changes, all reflected schemas that
  inherit it must have their triggers rebuilt. The `CatalogExpressionTriggerRegistry` rebuild
  for the source entity type must cascade to all entity types with reflected references that
  inherit from the changed source.

#### Trigger architecture

A post-processing step in `EntityIndexLocalMutationExecutor` consults the
`CatalogExpressionTriggerRegistry`, keyed by the MUTATED entity's type, to find which OTHER
entity schemas have `facetedPartially` expressions depending on this entity's data. For each
matching trigger, the source executor checks that the relevant attribute value actually changed
(old value ≠ new value) and creates a `ReevaluateFacetExpressionMutation` wrapped in an
`EntityIndexMutation` that carries the target schema type. The source does NOT evaluate the
expression and does NOT determine the add/remove direction — it only detects that a relevant
change occurred. These mutations are then sent to the appropriate `EntityCollection` for
handling. The target collection dispatches each concrete mutation through
`IndexMutationExecutorRegistry` to the `ReevaluateFacetExpressionExecutor`, which translates
the full expression to a `FilterBy` query, evaluates it against current indexes, compares
with current facet state, and performs the necessary add/remove operations.

#### Trigger hierarchy — generic base with specific incarnations

The trigger is a **function derived from a `ReferenceSchema`'s expression**. It encapsulates
the expression together with pre-built Proxycian proxy infrastructure and a pre-translated
`FilterBy` constraint tree. It has two roles:

- **Local evaluation** (inline in `ReferenceIndexMutator`): evaluates the expression per entity
  using Proxycian proxies backed by storage parts — used during local trigger processing
- **Cross-entity query** (used by `ReevaluateFacetExpressionExecutor`): provides a pre-translated
  `FilterBy` constraint that the executor runs against the target collection's indexes to
  determine which entities currently satisfy the expression — used during cross-entity trigger
  processing (no per-entity evaluation, pure index queries)

The trigger concept is generic — facet conditional indexing is just one incarnation. Future
incarnations (e.g., conditional histogram indexing for reference attributes) follow the same
pattern: expression evaluation → conditional add/remove of index data. The base interface
captures the common evaluation contract; specific subtypes add type-safety and domain-specific
metadata.

```java
// in io.evitadb.index.mutation (evita_engine)

/**
 * Generic base for expression-based index triggers. Each trigger wraps
 * a parsed {@link Expression} together with pre-built Proxycian proxy classes,
 * a state recipe derived from {@link AccessedDataFinder} path analysis, and
 * a pre-translated {@link FilterBy} constraint tree for index-based evaluation.
 *
 * Built at schema load/change time. Supports two evaluation modes:
 * - **Per-entity evaluation** via {@link #evaluate} — used for local triggers
 *   (inline in {@code ReferenceIndexMutator})
 * - **Index-based query evaluation** via {@link #getFilterByConstraint()} —
 *   used for cross-entity triggers (the executor runs the constraint against
 *   indexes, no per-entity storage access needed)
 *
 * The {@link FilterBy} constraint is built at schema time by
 * {@link ExpressionToQueryTranslator}. If the expression cannot be translated
 * (e.g., dynamic attribute paths, direct cross-to-local comparisons), an
 * exception is thrown at schema load time — non-translatable expressions
 * are rejected.
 *
 * Does NOT:
 * - resolve affected entity PKs (the IndexMutationExecutor's job on the target side)
 * - modify indexes (the IndexMutationExecutor's job)
 * - wrap results into EntityIndexMutation (the source executor's job)
 */
public interface ExpressionIndexTrigger {

    /**
     * Entity type owning the reference with the expression (the target
     * collection — e.g., "product").
     */
    @Nonnull
    String getOwnerEntityType();

    /**
     * Name of the reference carrying the conditional expression
     * (e.g., "parameter").
     */
    @Nonnull
    String getReferenceName();

    /**
     * Scope to which this trigger applies. A reference with expressions
     * in multiple scopes produces one trigger per scope.
     */
    @Nonnull
    Scope getScope();

    /**
     * How the mutated entity relates to the owner entity:
     * `GROUP_ENTITY_ATTRIBUTE` or `REFERENCED_ENTITY_ATTRIBUTE`.
     * Local dependencies (entity's own attributes, reference attributes)
     * are handled inline and do not need cross-entity triggers.
     */
    @Nonnull
    DependencyType getDependencyType();

    /**
     * Attribute names on the mutated entity (group or referenced) that
     * this expression reads. Used by the detection step to skip triggers
     * whose dependent attributes were not changed by the current mutation.
     */
    @Nonnull
    Set<String> getDependentAttributes();

    /**
     * Returns the full expression pre-translated to an evitaDB
     * {@link FilterBy} constraint tree. Built at schema load time by
     * {@link ExpressionToQueryTranslator}.
     *
     * At trigger time, the executor **parameterizes** this constraint by
     * injecting a PK-scoping clause for the specific mutated entity:
     * - {@code GROUP_ENTITY_ATTRIBUTE}: adds
     *   {@code entityGroupHaving(entityPrimaryKeyInSet(mutatedPK))}
     *   within the {@code referenceHaving} clause
     * - {@code REFERENCED_ENTITY_ATTRIBUTE}: adds
     *   {@code entityHaving(entityPrimaryKeyInSet(mutatedPK))}
     *   within the {@code referenceHaving} clause
     *
     * This scoping ensures the query only matches references to the
     * SPECIFIC changed entity, not all entities of the same type.
     *
     * The parameterized constraint is then evaluated against the target
     * collection's current indexes via
     * {@link IndexMutationTarget#evaluateFilter(FilterBy)}.
     */
    @Nonnull
    FilterBy getFilterByConstraint();

    /**
     * Evaluates the expression for a specific owner entity and reference.
     * Used for **local triggers** only (inline in {@code ReferenceIndexMutator}).
     *
     * Instantiates pre-built Proxycian proxy classes backed by StoragePart
     * data (fetched per the pre-computed state recipe), binds them as
     * expression variables (`$entity`, `$reference`), and computes the
     * expression result.
     *
     * NOT used for cross-entity triggers — those use
     * {@link #getFilterByConstraint()} for index-based evaluation instead.
     *
     * @param ownerEntityPK   primary key of the entity owning the reference
     * @param referenceKey    identifies the specific reference instance
     * @param storageAccessor accessor for fetching required StorageParts
     * @return true if the index entry should exist, false otherwise
     */
    boolean evaluate(
        int ownerEntityPK,
        @Nonnull ReferenceKey referenceKey,
        @Nonnull WritableEntityStorageContainerAccessor storageAccessor
    );
}
```

Specific trigger types extend the base with a type marker (and potentially domain-specific
metadata in the future):

```java
/**
 * Trigger for conditional facet indexing. Derived from
 * {@link ReferenceSchemaContract#getFacetedPartiallyInScope(Scope)}.
 * When the expression evaluates to true, the reference is facet-indexed;
 * when false, it is not.
 */
public interface FacetExpressionTrigger extends ExpressionIndexTrigger {
    // Type marker — no additional methods for now.
    // Future: may carry facet-specific metadata (e.g., facet group info).
}

/**
 * Trigger for conditional histogram indexing on reference attributes.
 * When the expression evaluates to true, reference attribute histogram
 * data is maintained for this reference; when false, it is excluded.
 */
public interface HistogramExpressionTrigger extends ExpressionIndexTrigger {
    // Type marker — may carry histogram-specific metadata
    // (e.g., which attributes to histogram, bucket count).
}
```

**Trigger hierarchy:**

```
ExpressionIndexTrigger (generic base — expression evaluation)
├── FacetExpressionTrigger (conditional facet indexing)
└── HistogramExpressionTrigger (conditional histogram indexing, future)
```

#### `DependencyType` — cross-entity relationship classification

Classifies how the mutated entity relates to the owner entity in a cross-entity trigger.
Only cross-entity dependencies need this classification — local dependencies (owner entity
attributes, reference attributes) are handled inline in `ReferenceIndexMutator` without
the cross-entity trigger mechanism.

```java
// in io.evitadb.index.mutation (evita_engine)

/**
 * Classifies the relationship between a mutated entity and the owner entity
 * in a cross-entity expression trigger. Used as a registry key in
 * {@link CatalogExpressionTriggerRegistry} to look up which triggers should
 * fire when a given entity type's attributes change.
 *
 * Only cross-entity relationships are represented here. Local dependencies
 * ({@code $entity.attributes['x']}, {@code $reference.attributes['x']}) are
 * handled inline in {@code ReferenceIndexMutator} and do not require this enum.
 */
public enum DependencyType {

    /**
     * The mutated entity is the **referenced entity** of the reference.
     * Expression path: {@code $reference.referencedEntity.attributes['x']}
     *
     * The trigger fires when an attribute on the referenced entity changes
     * (or the entity is removed) and the expression reads that attribute.
     * Fan-out is typically 1:1 (one referenced entity per reference instance).
     */
    REFERENCED_ENTITY_ATTRIBUTE,

    /**
     * The mutated entity is the **referenced entity** of the reference,
     * and the dependency is on a **reference attribute** of that entity.
     * Expression path: {@code $reference.referencedEntity.references['r']*.attributes['x']}
     *
     * The trigger fires when a reference mutation on the referenced entity
     * affects reference 'r' (insert, remove, or attribute 'x' change).
     * {@code getDependentReferenceName()} returns 'r',
     * {@code getDependentAttributes()} returns {'x'}.
     */
    REFERENCED_ENTITY_REFERENCE_ATTRIBUTE,

    /**
     * The mutated entity is the **group entity** of the reference.
     * Expression path: {@code $reference.groupEntity?.attributes['x']}
     *
     * The trigger fires when an attribute on the group entity changes
     * (or the entity is removed).
     * Fan-out can be significant — one group entity may be shared across
     * many references (and thus many owner entities).
     */
    GROUP_ENTITY_ATTRIBUTE,

    /**
     * The mutated entity is the **group entity** of the reference,
     * and the dependency is on a **reference attribute** of that entity.
     * Expression path: {@code $reference.groupEntity?.references['r']*.attributes['x']}
     *
     * The trigger fires when a reference mutation on the group entity
     * affects reference 'r' (insert, remove, or attribute 'x' change).
     * {@code getDependentReferenceName()} returns 'r',
     * {@code getDependentAttributes()} returns {'x'}.
     * Fan-out can be significant (same as GROUP_ENTITY_ATTRIBUTE).
     */
    GROUP_ENTITY_REFERENCE_ATTRIBUTE
}
```

**Module boundary — `evita_api` vs `evita_engine`:**

- `ReferenceSchema.facetedPartiallyInScopes` (`Map<Scope, Expression>`) lives in `evita_api` —
  it is the declarative contract, a pure data field, part of the public API
- `ExpressionIndexTrigger` and all its implementations live in `evita_engine` alongside
  `EntityCollection` — they are operational engine internals that should never be part of
  the public API surface
- Triggers are built from the `Expression` at schema load/change time by `EntityCollection`
  (or a helper it delegates to) and registered in `CatalogExpressionTriggerRegistry`
- This placement gives `EntityCollection` symmetric responsibility: it hosts both the
  **inbound** dispatcher (processes `IndexMutation` from other collections) and the
  **outbound** trigger infrastructure (generates `IndexMutation` from this collection's
  schema definitions)

**Implementation notes:**

- The concrete implementation holds `Expression`, proxy class factories
  (`ByteBuddyDispatcherInvocationHandler` per root variable), a state recipe (which
  StorageParts to fetch for each proxy level), and a pre-translated `FilterBy` constraint
  tree — all built once at schema load time
- `evaluate()` (local triggers only) fetches only the StorageParts the recipe requires,
  creates state records, instantiates pre-built proxy classes, wires nested proxies (for
  group/referenced entity access), computes the expression, and casts the result to `boolean`
- `getFilterByConstraint()` (cross-entity triggers) returns the pre-translated `FilterBy` —
  the executor parameterizes it with the mutated entity PK and runs it against target indexes
- The same trigger instance is used for both **local evaluation** (inline in
  `ReferenceIndexMutator` via `evaluate()`) and **cross-entity evaluation** (by the target
  executor via `getFilterByConstraint()` — no per-entity evaluation in the cross-entity path)

**No `affectedPKsAccessor` here** — the trigger does NOT resolve affected owner entity PKs.
That resolution happens inside the `IndexMutationExecutor` on the target side using the
target collection's own indexes (see "Processing inside target `EntityCollection`" below).
The source executor consults triggers, creates `ReevaluateFacetExpressionMutation` instances,
and wraps them into `EntityIndexMutation` — but PK resolution is delegated to the executor.

#### `CatalogExpressionTriggerRegistry` — cross-schema wiring

When entity B (a group entity) changes attribute `inputWidgetType`, we need to find which
entity schemas have references using B's entity type as group with a conditional expression
depending on group entity attributes.

This requires an **inverted index at the catalog level**, built at schema load/change time.
The registry embodies the cross-schema wiring: schema B (ParameterGroup) maintains a set of
triggers that may fire a handler in schema A (Product). The wiring is established when schema
definitions are loaded or altered — specifically, when a `ReferenceSchema`'s conditional
expression (`facetedPartially`, future histogram expression, etc.) is set or changed.

The registry is generic — it stores all `ExpressionIndexTrigger` instances regardless of type
(facet, histogram, etc.). Callers can filter by trigger subtype if needed.

```java
// in io.evitadb.core.catalog (evita_engine)

/**
 * Catalog-level inverted index that maps mutated entity types to the
 * {@link ExpressionIndexTrigger} instances that depend on their data.
 *
 * Built/rebuilt when entity schemas change. Consulted during the
 * post-processing step of `EntityIndexLocalMutationExecutor` to
 * determine which cross-entity triggers need to fire.
 *
 * The registry inverts the ownership:
 * - Schema A (Product) defines reference "parameter" with conditional
 *   expression depending on group entity ParameterGroup
 * - Registry stores: ("ParameterGroup", GROUP_ENTITY_ATTRIBUTE) → [trigger for
 *   Product/"parameter"]
 *
 * So "ParameterGroup maintains triggers that fire handlers in Product."
 */
public interface CatalogExpressionTriggerRegistry {

    /**
     * Finds all triggers that depend on the given entity type with the
     * specified dependency relationship. Called during post-processing
     * to discover which target collections need notification.
     *
     * @param mutatedEntityType the entity type being mutated
     *        (e.g., "parameterGroup")
     * @param dependencyType how the mutated entity relates to the owner
     * @return all matching triggers (empty list if none)
     */
    @Nonnull
    List<ExpressionIndexTrigger> getTriggersFor(
        @Nonnull String mutatedEntityType,
        @Nonnull DependencyType dependencyType
    );

    /**
     * More selective variant — returns only triggers whose
     * {@link ExpressionIndexTrigger#getDependentAttributes()} contains the
     * given attribute name. Avoids firing triggers when the changed attribute
     * is irrelevant to the expression.
     */
    @Nonnull
    List<ExpressionIndexTrigger> getTriggersForAttribute(
        @Nonnull String mutatedEntityType,
        @Nonnull DependencyType dependencyType,
        @Nonnull String attributeName
    );

    /**
     * Rebuilds the registry index for the given entity type based on current
     * schemas. Called when an entity schema's reference definitions change.
     *
     * **Immutability principle:** the rebuild constructs a **new** registry instance.
     * The original instance remains untouched and continues serving concurrent
     * readers until they switch to the new instance (same copy-on-write pattern
     * as {@code EntityCollection} transactional copies). This eliminates
     * concurrent mutation processing seeing stale or partially-built trigger state.
     */
    @Nonnull
    CatalogExpressionTriggerRegistry rebuildForEntityType(@Nonnull String entityType);
}
```

**Example wiring:** Schema Product defines reference "parameter" with expression
`$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'`. At schema load time,
Product's `EntityCollection` reads the `Expression` from `ReferenceSchema.facetedPartiallyInScopes`,
`AccessedDataFinder` discovers that the expression reads attribute `inputWidgetType` on the
group entity. `EntityCollection` builds a `FacetExpressionTrigger` and registers it under:
- key: `("parameterGroup", GROUP_ENTITY_ATTRIBUTE)`
- value: `[trigger(ownerEntityType="product", referenceName="parameter", ...)]`

When ParameterGroup entity 99 changes `inputWidgetType`, the detection step calls
`registry.getTriggersForAttribute("parameterGroup", GROUP_ENTITY_ATTRIBUTE, "inputWidgetType")`
and generates an `EntityIndexMutation` targeting "product".

#### Mutation ordering guarantee

Cross-entity triggers fire AFTER the source entity's mutations complete (same as
`popImplicitMutations` external mutations). This means:

- Entity B's storage already reflects the NEW attribute value
- `WritableEntityStorageContainerAccessor` on entity A will see B's updated data
- Expression evaluation produces correct result based on new state

**To be verified by integration tests:** Confirm that `DataStoreMemoryBuffer` makes B's pending
changes visible to A's accessor when both are in the same transaction.

#### Initial catalog load / cold start

Data loaded from disk storage is already consistent with the schema (and its expressions) —
indexes are persisted in their correct state. No expression re-evaluation is needed on load.

New changes arriving after load go through WAL and the standard mutation pipeline, where
`ReferenceIndexMutator`'s updated `isFaceted()` guard (with expression evaluation) handles
them normally.

After `EntityCollection` instantiation, the catalog must **wire active trigger implementations
between collections** according to their current schemas. This means populating the
`CatalogExpressionTriggerRegistry` by scanning all loaded `ReferenceSchema` definitions that
carry `facetedPartiallyInScopes` expressions — the same process as schema load time trigger
building, just executed during catalog initialization.

#### Chosen approach: Separate `IndexMutation` type with dedicated dispatch path

**Design principle: Two honest loops are better than one dishonest abstraction.**

`IndexMutation` and `EntityMutation` have fundamentally different processing requirements:

| Aspect                    | `EntityMutation`                             | `IndexMutation`                        |
|---------------------------|----------------------------------------------|----------------------------------------|
| Creates executors         | Both container + index                       | Only index                             |
| Storage changes           | Yes                                          | No                                     |
| Schema evolution          | Yes                                          | No                                     |
| Conflict keys             | Yes                                          | No                                     |
| WAL                       | Root-level only                              | Never                                  |
| Returns updated entity    | Optional                                     | Never                                  |

Attempting to unify them under a shared interface (e.g., `EntityTypeBoundMutation`) would
create a leaky abstraction — the dispatch would immediately branch into completely different
code paths. Instead, they remain independent types with separate dispatch loops.

#### `MutationContract` — unified mutation taxonomy root

The existing `Mutation` interface is a sealed interface with methods (`operation()`,
`collectConflictKeys()`) that make no sense for engine-internal index mutations. Renaming it
would affect 50+ files across all layers. Instead, we introduce a new empty root interface
that both `Mutation` and `IndexMutation` extend:

```java
// in io.evitadb.api.requestResponse.mutation
/**
 * Root marker interface for all mutation types in evitaDB — both external (API-facing,
 * WAL-serialized) and internal (engine-only, derived from triggers).
 *
 * This interface carries no methods. Its sole purpose is to establish a type hierarchy
 * that allows IDE navigation across the complete mutation landscape via "Show
 * Implementations" / type hierarchy views.
 *
 * - {@link Mutation} — external mutations: passed through the API, written to WAL,
 *   carry {@link Mutation#operation()} and conflict key semantics
 * - engine-internal mutations (e.g., `IndexMutation` in `evita_engine`) — engine-generated,
 *   never serialized, never written to WAL, regenerated deterministically on replay
 */
public interface MutationContract {
}
```

Changes required:

- `Mutation` adds `extends MutationContract` (1 line — does not affect the sealed `permits`
  clause or any of the 50+ files importing `Mutation`)
- `IndexMutation` adds `extends MutationContract` (1 line)

#### `IndexMutation` interface

```java
// in io.evitadb.index.mutation
/**
 * Engine-internal mutation that affects only index structures, never entity storage.
 * Generated by post-processing triggers (e.g., facet expression re-evaluation) and
 * dispatched through a dedicated path on `EntityCollection`.
 *
 * Unlike {@link Mutation}, these mutations:
 * - are never passed from outside the engine
 * - are never written to WAL (regenerated deterministically on replay)
 * - are processed entirely by the target `EntityCollection`
 *
 * This is a marker interface — concrete leaf mutations (e.g.,
 * {@link ReevaluateFacetExpressionMutation}) carry domain-specific fields. The target
 * entity type is NOT on this interface — it is carried by the wrapping
 * {@link EntityIndexMutation} transport envelope, which routes the mutations to the
 * correct {@link EntityCollection}.
 */
public interface IndexMutation extends MutationContract {
}
```

#### Mutation type hierarchy

```
MutationContract (empty root — IDE navigation)                   [evita_api]
├── Mutation (sealed — external, API-facing)                     [evita_api]
│   ├── EngineMutation<T>                                        [evita_api]
│   └── CatalogBoundMutation (sealed)                            [evita_api]
│       ├── EntityMutation                                       [evita_api]
│       ├── LocalMutation<T, S>                                  [evita_api]
│       ├── SchemaMutation                                       [evita_api]
│       └── TransactionMutation                                  [evita_api]
└── IndexMutation (internal, engine-only — marker)                [evita_engine]
    ├── ReevaluateFacetExpressionMutation (leaf — no entityType)  [evita_engine]
    └── (future: ReevaluateHistogramExpressionMutation, etc.)     [evita_engine]

EntityIndexMutation (transport envelope — does NOT implement IndexMutation)  [evita_engine]
└── contains: IndexMutation[]
```

#### `EntityIndexMutation` — transport envelope for concrete index mutations

**Design principle: the source executor detects THAT a relevant change occurred, the target
executor decides WHAT is affected and HOW to handle it.**

The source entity's executor (e.g., ParameterGroup's `EntityIndexLocalMutationExecutor`)
consults the `CatalogExpressionTriggerRegistry`, verifies that the relevant attribute value
actually changed (old ≠ new), and creates `ReevaluateFacetExpressionMutation` instances.
These are wrapped into an `EntityIndexMutation` carrying the target schema type. The source
does NOT evaluate the expression and does NOT determine the add/remove direction — it only
signals that a relevant change occurred.

The source does NOT resolve affected owner entity PKs — it cannot, because the reverse
lookup indexes (`ReferencedTypeEntityIndex`, `ReducedGroupEntityIndex`) live in the target
collection. PK resolution is the `IndexMutationExecutor`'s responsibility on the target
side.

```java
// in io.evitadb.index.mutation
/**
 * Transport envelope carrying concrete {@link IndexMutation} instances targeting a
 * specific {@link EntityCollection}. Created by the source executor after detecting
 * relevant changes. The target collection dispatches each nested mutation to the
 * appropriate {@link IndexMutationExecutor} via {@link IndexMutationExecutorRegistry}.
 *
 * This is a standalone record — it does NOT implement {@link IndexMutation}. It serves
 * purely as a routing envelope: the {@code entityType} identifies the target collection,
 * and the nested mutations are the actual work items.
 */
public record EntityIndexMutation(
    @Nonnull String entityType,
    @Nonnull IndexMutation[] mutations
) {
}
```

Each concrete `IndexMutation` inside carries all context the executor needs — reference name,
mutated entity PK, dependency type, scope — but NOT the affected owner entity PKs. Multiple
concrete mutations can be batched when the same source entity change affects multiple reference
schemas on the same target collection.

#### `IndexMutationTarget` — role interface for collection access

Executors need access to the target collection's indexes and schema, but should not see
the full `EntityCollection` surface. A limited role interface restricts the blast radius:

```java
// in io.evitadb.index.mutation (evita_engine)

/**
 * Limited view of an {@link EntityCollection} exposed to {@link IndexMutationExecutor}
 * implementations. Restricts access to index lookup, schema retrieval, expression
 * trigger access, and query-based filter evaluation — prevents executors from reaching
 * into collection internals (mutations, persistence, cache, etc.).
 *
 * {@link EntityCollection} implements this interface, so the dispatcher simply
 * passes {@code this} to the executor — zero extra allocations.
 */
public interface IndexMutationTarget {

    /**
     * Returns the entity index for the given key, creating it if absent.
     * Used by executors that need to ensure a target index exists
     * (e.g., creating a {@code ReducedEntityIndex} for a new reference).
     */
    @Nonnull
    EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey key);

    /**
     * Returns the entity index for the given key, or null if it doesn't exist.
     * Primary lookup method for executors — used to find
     * {@code ReferencedTypeEntityIndex}, {@code GlobalEntityIndex}, etc.
     */
    @Nullable
    EntityIndex getIndexIfExists(@Nonnull EntityIndexKey key);

    /**
     * Returns the entity index by its storage primary key, or null if not found.
     * Used to resolve the {@code int[]} storage PKs returned by
     * {@link ReferencedTypeEntityIndex#getAllReferenceIndexes(int)} into actual
     * {@code ReducedGroupEntityIndex} / {@code ReducedEntityIndex} instances.
     */
    @Nullable
    EntityIndex getIndexByPrimaryKeyIfExists(int indexPrimaryKey);

    /**
     * Returns the current entity schema for this collection.
     * Used by executors to look up {@link ReferenceSchemaContract} for
     * the reference being modified.
     */
    @Nonnull
    EntitySchema getEntitySchema();

    /**
     * Returns the expression trigger for the given reference name, dependency type,
     * and scope. Used by the executor to access the pre-translated {@link FilterBy}
     * constraint for expression evaluation against indexes.
     *
     * @return the trigger, or null if no conditional expression is defined
     */
    @Nullable
    ExpressionIndexTrigger getTrigger(
        @Nonnull String referenceName,
        @Nonnull DependencyType dependencyType,
        @Nonnull Scope scope
    );

    /**
     * Evaluates a {@link FilterBy} constraint against this collection's current
     * indexes and returns the matching entity PK bitmap. Used by executors to
     * determine which entities currently satisfy the expression.
     *
     * Delegates to the collection's existing query evaluation infrastructure
     * against {@code GlobalEntityIndex}.
     */
    @Nonnull
    Bitmap evaluateFilter(@Nonnull FilterBy filterBy);
}
```

`EntityCollection implements IndexMutationTarget` — each method delegates to an existing
method already present on the collection:

| Interface method | Delegates to |
|---|---|
| `getOrCreateIndex(key)` | `this.entityIndexCreator.getOrCreateIndex(key)` |
| `getIndexIfExists(key)` | `this.entityIndexCreator.getIndexIfExists(key)` |
| `getIndexByPrimaryKeyIfExists(pk)` | existing `getIndexByPrimaryKeyIfExists(pk)` (line 1444) |
| `getEntitySchema()` | `this.getInternalSchema()` |
| `getTrigger(refName, depType, scope)` | lookup in cached trigger map built from `ReferenceSchema` at schema load time |
| `evaluateFilter(filterBy)` | thin delegation to query evaluation infrastructure against `GlobalEntityIndex` |

#### Processing inside target `EntityCollection`

The target collection is a **thin dispatcher** — it receives an `EntityIndexMutation`
containing concrete `IndexMutation` instances and dispatches each one to the appropriate
`IndexMutationExecutor` via the static singleton registry, passing `this` (typed as
`IndexMutationTarget`) as the collection context.

```java
// EntityCollection implements IndexMutationTarget

/**
 * Dispatches {@link IndexMutation} instances to their registered
 * {@link IndexMutationExecutor}. Passes {@code this} as {@link IndexMutationTarget}
 * so executors can access indexes, schema, triggers, and query evaluation
 * without seeing the full collection API surface.
 */
void applyIndexMutations(@Nonnull EntityIndexMutation entityIndexMutation) {
    for (IndexMutation mutation : entityIndexMutation.mutations()) {
        IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this);
    }
}
```

**PK resolution, expression evaluation, and index operations** happen inside each
`IndexMutationExecutor`. The executor uses the target collection's own indexes via
`IndexMutationTarget` (two-step lookup via type-level index → per-target reduced index):

- **GROUP_ENTITY_ATTRIBUTE**: `ReferencedTypeEntityIndex` for `REFERENCED_GROUP_ENTITY_TYPE`
  (discriminator = referenceName) → `getAllReferenceIndexes(groupEntityPK)` returns storage PKs
  of `ReducedGroupEntityIndex` instances for that group → for each reduced index, the facet PK
  (referenced entity PK) is recoverable from the `EntityIndexKey.discriminator` (which is a
  `ReferenceKey(referenceName, facetPK)`), and `getAllPrimaryKeys()` yields owner entity PKs.
  (Confirmed by `FilterByVisitor.getMatchingGroupEntityPrimaryKeys()` which does the reverse
  mapping via `getReferencedPrimaryKeysForIndexPks()`.)
- **REFERENCED_ENTITY_ATTRIBUTE**: `ReferencedTypeEntityIndex` for `REFERENCED_ENTITY_TYPE`
  (discriminator = referenceName) → `getAllReferenceIndexes(referencedEntityPK)` returns storage
  PKs of `ReducedEntityIndex` instances → the facet PK is `mutatedEntityPK` itself, and for
  each, `getAllPrimaryKeys()` yields owner entity PKs. The group PK for each reference is
  determined from the `ReducedGroupEntityIndex` structure (the reduced index lives within the
  group's scope).

**Target index routing** — same as existing facet mutations:

- `GlobalEntityIndex` — always
- `ReducedEntityIndex` for the reference — if it exists and has
  `FOR_FILTERING_AND_PARTITIONING` indexing level

#### Why no other mutation types are needed

| Scenario | Handled by | Needs `IndexMutation`? |
|---|---|---|
| Reference created (expression=true) | Local trigger in `ReferenceIndexMutator` | No — inline guard |
| Reference created (expression=false) | Local trigger — skip `addFacetToIndex` | No — inline guard |
| Reference removed | Existing `removeFacetInIndex` logic | No — always removes |
| Group assignment changes | Existing `setFacetGroupInIndex` logic | No — then expression re-eval may add/remove |
| Cross-entity attr change or entity removal | **This mechanism** | Yes → `ReevaluateFacetExpressionMutation` in `EntityIndexMutation` |
| Schema expression changes | Full re-index (separate mechanism) | Not via this path |

#### Fan-out example — purely cross-entity expression

Group entity G (parameterGroup "Color", PK=99) changes `inputWidgetType` from `'CHECKBOX'` to
`'RADIO'`. Expression: `$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'`

**Source executor** (ParameterGroup's `EntityIndexLocalMutationExecutor`):

1. Detects that attribute `inputWidgetType` changed on entity PK 99
2. Checks old value (`'CHECKBOX'`) ≠ new value (`'RADIO'`) — actual change confirmed
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
2. Calls `getAllReferenceIndexes(99)` → returns storage PKs of `ReducedGroupEntityIndex`
   instances associated with group 99 (e.g., 10 indexes — one per Parameter/facet PK)
3. For each `ReducedGroupEntityIndex`, calls `getAllPrimaryKeys()` → collects all affected
   Product PKs (e.g., 1000 products total across all 10 indexes). Also collects the facet PK
   from each `ReducedGroupEntityIndex`'s `EntityIndexKey` discriminator.
4. Gets the pre-translated `FilterBy` from the trigger via
   `target.getTrigger("parameter", GROUP_ENTITY_ATTRIBUTE, DEFAULT_SCOPE)`
5. **Parameterizes** the `FilterBy` by injecting
   `entityGroupHaving(entityPrimaryKeyInSet(99))` — scoping the query to references within
   the specific changed group. Without this scoping, a product referencing BOTH group 99
   (now `'RADIO'`) and group 55 (still `'CHECKBOX'`) would incorrectly match because of
   group 55.
6. Evaluates the parameterized `FilterBy` against Product's current indexes → bitmap of
   products where the expression is currently `true` for references to group 99 → empty
   (group 99's `inputWidgetType` is now `'RADIO'`, no longer `'CHECKBOX'`)
7. Compares with current facet state for each affected (facetPK, ownerPK) pair:
   - `shouldBeFaceted = AND(affectedPKs, queryResult)` → empty
   - `shouldNotBeFaceted = ANDNOT(affectedPKs, queryResult)` → all 1000 PKs
8. For each of the 1000 products: removes facet entry (for the specific facetPK within
   group 99) from `GlobalEntityIndex` and applicable `ReducedEntityIndex` instances.
   Products not currently faceted result in idempotent no-ops.

#### Fan-out example — mixed-dependency expression

Same scenario, but the expression is now mixed:
`$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX' && $entity.attributes['isActive'] == true`

**Source side** (ParameterGroup executor): Identical to above — detects change, confirms
old ≠ new, creates `ReevaluateFacetExpressionMutation`. Does NOT evaluate the expression.

**Target side** (`ReevaluateFacetExpressionExecutor` in Product collection):

1-3. Same PK resolution as above → 1000 candidate Product PKs
4. Gets the pre-translated `FilterBy`:
   `and(referenceHaving("parameter", entityGroupHaving(attributeEquals("inputWidgetType", "CHECKBOX"))), attributeEquals("isActive", true))`
5. **Parameterizes** with `entityGroupHaving(entityPrimaryKeyInSet(99))`
6. Evaluates → empty bitmap (group 99 is `'RADIO'`, so the AND fails regardless of
   `isActive`)
7. `shouldNotBeFaceted` = all 1000 PKs. Among these, 800 were actually faceted (active
   products), 200 were never faceted (inactive). The 200 remove calls are idempotent no-ops.
8. Removes facet entries for the 800 active products

#### Fan-out example — multi-source cross-entity expression

Expression: `$reference.groupEntity?.attributes['status'] == 'ACTIVE' || $reference.referencedEntity.attributes['status'] == 'PREVIEW'`

This expression depends on TWO cross-entity sources. The registry has entries for BOTH:
- `("ParameterGroup", GROUP_ENTITY_ATTRIBUTE, "status")` → trigger
- `("Parameter", REFERENCED_ENTITY_ATTRIBUTE, "status")` → trigger

When group entity PK=99 changes `status` from `'ACTIVE'` to `'INACTIVE'`:

**Source side**: Creates `ReevaluateFacetExpressionMutation` targeting Product.

**Target side**: Same flow as above. The parameterized `FilterBy` becomes:
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
`status='PREVIEW'` will still match (the second operand is true), even though the
group part is now false. Only products where NEITHER operand is true for their
reference within group 99 will have their facet removed.

#### Separate implicit mutation records per executor

Each executor produces its own dedicated record — no shared type carrying unused fields:

**`ImplicitMutations`** — existing record, unchanged, only used by `ContainerizedLocalMutationExecutor`:

```java
// in ConsistencyCheckingLocalMutationExecutor (existing — no changes)
record ImplicitMutations(
    @Nonnull LocalMutation<?, ?>[] localMutations,
    @Nonnull EntityMutation[] externalMutations
) { }
```

**`IndexImplicitMutations`** — new record, only used by `EntityIndexLocalMutationExecutor`:

```java
// in EntityIndexLocalMutationExecutor (or a new dedicated interface)
record IndexImplicitMutations(
    @Nonnull EntityIndexMutation[] indexMutations
) { }
```

`EntityIndexLocalMutationExecutor` does **not** implement `ConsistencyCheckingLocalMutationExecutor`.
It exposes its own dedicated method:

```java
@Nonnull
IndexImplicitMutations popIndexImplicitMutations(
    @Nonnull List<? extends LocalMutation<?, ?>> inputMutations
);
```

#### Dispatch in `LocalMutationExecutorCollector`

Two separate dispatch loops, each calling the appropriate executor method and consuming
the executor-specific record:

```java
// Step 5a: container implicit mutations (existing — unchanged)
ImplicitMutations containerImplicit = changeCollector.popImplicitMutations(
    localMutations, generateImplicitMutations
);
for (LocalMutation<?, ?> localMutation : containerImplicit.localMutations()) {
    entityIndexUpdater.applyMutation(localMutation);
    changeCollector.applyMutation(localMutation);
}
for (EntityMutation externalMutation : containerImplicit.externalMutations()) {
    ServerEntityMutation sem = (ServerEntityMutation) externalMutation;
    this.catalog.getCollectionForEntityOrThrowException(externalMutation.getEntityType())
        .applyMutations(session, externalMutation, ...);
}

// Step 5b: index trigger mutations (NEW — separate dispatch path, separate record)
//   The index executor consults CatalogExpressionTriggerRegistry, checks old != new
//   for relevant attributes, and creates ReevaluateFacetExpressionMutation instances
//   wrapped in EntityIndexMutation per target schema. The target collection dispatches
//   each to the appropriate IndexMutationExecutor.
IndexImplicitMutations indexImplicit = entityIndexUpdater.popIndexImplicitMutations(
    localMutations
);
for (EntityIndexMutation indexMutation : indexImplicit.indexMutations()) {
    this.catalog.getCollectionForEntityOrThrowException(indexMutation.getEntityType())
        .applyIndexMutations(indexMutation);
}
```

**Why container first:** Container mutations may create/update references that the index
triggers need to see. Index triggers must operate on fully consistent storage state.

#### Registry-based executor dispatch — the handler side

The handler side uses a **registry pattern**: concrete `IndexMutation` types are the keys,
`IndexMutationExecutor` implementations are the values. The target collection's
`applyIndexMutations()` is a thin dispatcher — the executor does all the real work:
resolving affected entity PKs from local indexes, translating the expression to a parameterized
`FilterBy` query, evaluating it against current indexes, comparing with current facet state,
and performing the actual add/remove operations. New mutation types can be added without
modifying existing code — just register a new mutation record + executor class.

##### `IndexMutationExecutor<M>` — the command handler interface

```java
// in io.evitadb.index.mutation (evita_engine)

/**
 * Stateless strategy interface for executing a concrete {@link IndexMutation}.
 * Each implementation handles exactly one mutation type and performs
 * the full processing pipeline:
 *
 * 1. Resolves affected owner entity PKs from the collection's own indexes
 *    ({@code ReferencedTypeEntityIndex} → {@code ReducedGroupEntityIndex} /
 *    {@code ReducedEntityIndex})
 * 2. Gets the pre-translated {@link FilterBy} from the trigger, parameterizes
 *    it with the mutated entity PK, and evaluates it against current indexes
 *    to determine which affected entities currently satisfy the expression
 * 3. Compares the query result with current facet state and performs the
 *    actual index modifications (add/remove facet) for affected entities
 *
 * Executor instances are stateless singletons — all collection-specific
 * state is received via the {@link IndexMutationTarget} parameter. This
 * means the {@link IndexMutationExecutorRegistry} and all its executors
 * can be a static singleton, avoiding reinstantiation when
 * {@link EntityCollection} creates transactional copies.
 *
 * Registered in {@link IndexMutationExecutorRegistry} keyed by the
 * concrete mutation class. The target {@link EntityCollection} dispatches
 * to the executor — no switch/case or orchestration logic in the collection.
 *
 * @param <M> the concrete IndexMutation subtype this executor handles
 */
public interface IndexMutationExecutor<M extends IndexMutation> {

    /**
     * Executes the mutation against the given target collection.
     * Resolves affected PKs, evaluates the expression via FilterBy query,
     * and performs index operations. The executor is stateless — all
     * collection context comes from the {@code target} parameter.
     *
     * @param mutation the concrete mutation to execute
     * @param target   limited view of the target EntityCollection
     */
    void execute(@Nonnull M mutation, @Nonnull IndexMutationTarget target);
}
```

##### Concrete `IndexMutation` type — `ReevaluateFacetExpressionMutation`

A single mutation type signals that a relevant cross-entity change occurred and the
expression needs re-evaluation on the target side. The mutation is a pure data carrier —
it does NOT carry the add/remove direction (unknown at source time for complex expressions
with OR / multiple cross-entity sources) or resolved owner entity PKs.

```java
// in io.evitadb.index.mutation (evita_engine)

/**
 * Signals that a cross-entity change occurred that may affect the facet
 * indexing expression for the given reference. The source executor detected
 * a relevant attribute change (old ≠ new) on a group or referenced entity
 * but does NOT evaluate the expression or determine add/remove direction.
 *
 * The target-side {@link ReevaluateFacetExpressionExecutor}:
 * 1. Resolves affected owner entity PKs from local indexes
 * 2. Translates the full expression to a parameterized FilterBy query
 * 3. Evaluates the query against current indexes
 * 4. Compares with current facet state and performs add/remove operations
 *
 * @param referenceName  reference with the facetedPartially expression
 * @param mutatedEntityPK the group/referenced entity PK that changed
 * @param dependencyType how the mutated entity relates to the owner
 * @param scope          scope of the expression to re-evaluate
 */
record ReevaluateFacetExpressionMutation(
    @Nonnull String referenceName,
    int mutatedEntityPK,
    @Nonnull DependencyType dependencyType,
    @Nonnull Scope scope
) implements IndexMutation {
}
```

##### Concrete executor — `ReevaluateFacetExpressionExecutor`

A **stateless singleton** that handles the full reevaluation pipeline. All collection
context flows via `IndexMutationTarget`.

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
     *    — facetPK recovered from {@code EntityIndexKey.discriminator}
     *      (which is a {@code ReferenceKey(referenceName, facetPK)})
     *    — groupPK: for GROUP_ENTITY_ATTRIBUTE = mutatedEntityPK;
     *      for REFERENCED_ENTITY_ATTRIBUTE = from ReducedGroupEntityIndex scope
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
        @Nonnull String referenceName
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
        final Bitmap shouldBeFaceted =
            RoaringBitmapBackedBitmap.and(allAffectedOwnerPKs, currentlyTruePKs);
        final Bitmap shouldNotBeFaceted =
            RoaringBitmapBackedBitmap.andNot(allAffectedOwnerPKs, currentlyTruePKs);

        // 6. Apply changes — for each (facetPK, groupPK, ownerPK) tuple
        final ReferenceSchemaContract refSchema =
            target.getEntitySchema()
                .getReference(mutation.referenceName())
                .orElseThrow();
        final EntityIndex[] targetIndexes = resolveTargetIndexes(
            target, mutation.referenceName()
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

record AffectedFacetGroup(int facetPK, int groupPK, @Nonnull Bitmap ownerPKs) { }
record AffectedFacetEntry(int facetPK, int groupPK, int ownerPK) { }
```

##### `IndexMutationExecutorRegistry` — static singleton

The registry is a static singleton with an immutable executor map. All executors are
stateless singletons themselves. This means the registry survives
`EntityCollection.createCopyWithMergedTransactionalMemory()` without reinstantiation —
it is never a field on `EntityCollection`, just a static constant.

```java
// in io.evitadb.index.mutation (evita_engine)

/**
 * Static singleton registry mapping concrete {@link IndexMutation} types to their
 * stateless {@link IndexMutationExecutor} implementations. Both the registry and
 * its executors hold no instance state — all collection-specific context is passed
 * via {@link IndexMutationTarget} at dispatch time.
 *
 * This design avoids reinstantiation when {@link EntityCollection} creates
 * transactional copies (which happens on every committed transaction).
 * Adding a new mutation type requires only: a new mutation record, a new
 * stateless executor class, and one entry in the map below.
 */
class IndexMutationExecutorRegistry {

    static final IndexMutationExecutorRegistry INSTANCE =
        new IndexMutationExecutorRegistry(
            Map.of(
                ReevaluateFacetExpressionMutation.class,
                    new ReevaluateFacetExpressionExecutor()
                // future: ReevaluateHistogramExpressionMutation.class,
                //         new ReevaluateHistogramExpressionExecutor()
            )
        );

    private final Map<Class<? extends IndexMutation>,
                      IndexMutationExecutor<?>> executors;

    private IndexMutationExecutorRegistry(
        @Nonnull Map<Class<? extends IndexMutation>, IndexMutationExecutor<?>> executors
    ) {
        this.executors = Map.copyOf(executors);
    }

    /**
     * Looks up the executor for the given mutation type and executes it
     * against the target collection.
     */
    @SuppressWarnings("unchecked")
    <M extends IndexMutation> void dispatch(
        @Nonnull M mutation,
        @Nonnull IndexMutationTarget target
    ) {
        final IndexMutationExecutor<M> executor =
            (IndexMutationExecutor<M>) this.executors.get(mutation.getClass());
        Assert.notNull(executor,
            "No executor registered for " + mutation.getClass().getName());
        executor.execute(mutation, target);
    }
}
```

##### Source-side creation, target-side execution

The processing pipeline splits cleanly between source and target:

**Source** (`EntityIndexLocalMutationExecutor.popIndexImplicitMutations()`):
1. Detects which attributes changed in the current mutation batch
2. For each changed attribute, checks that the old value ≠ new value — if equal, skips
   (optimizes for no-change scenarios, same pattern as existing attribute index updates)
3. Consults `CatalogExpressionTriggerRegistry` — finds matching triggers
4. For each matching trigger, creates a `ReevaluateFacetExpressionMutation`. The source
   does NOT evaluate the expression and does NOT determine the add/remove direction.
5. Groups mutations by target entity type, wraps in `EntityIndexMutation`

**Target** (`EntityCollection.applyIndexMutations()`):
1. Iterates nested `IndexMutation` instances
2. Dispatches each to `IndexMutationExecutorRegistry.INSTANCE`, passing `this`
   (typed as `IndexMutationTarget`)

**Executor** (`ReevaluateFacetExpressionExecutor`):
1. Receives `IndexMutationTarget` — accesses indexes, schema, triggers, and query evaluation
2. Resolves affected (facetPK, groupPK, ownerPKs) tuples from the collection's own indexes
3. Gets the pre-translated `FilterBy` from the trigger, parameterizes it with the mutated
   entity PK (scoping to the specific changed group/referenced entity)
4. Evaluates the parameterized `FilterBy` against current indexes → bitmap of entities where
   expression is currently true
5. Compares with affected PKs → determines `shouldBeFaceted` and `shouldNotBeFaceted` sets
6. Performs index operations (add/remove facet) for each affected (facetPK, groupPK, ownerPK)

**De-duplication:** Since the executor performs idempotent index operations, de-duplication
happens naturally. If both a local trigger and a cross-entity trigger fire for the same
entity, the second operation is a no-op at the index level. Multiple triggers for the same
target collection with identical `FilterBy` constraints each run their own `evaluateFilter()`
independently — this is an optimization opportunity for the future but not a correctness issue.

##### Expression-to-query translation (`ExpressionToQueryTranslator`)

At schema load time, `ExpressionToQueryTranslator` translates the **full** `facetedPartially`
expression into an evitaDB `FilterBy` constraint tree. This translation is mandatory — if the
expression cannot be translated, an exception is thrown and the schema is rejected.

**Translation mapping — full expression paths:**

| Expression path | evitaDB `FilterBy` constraint |
|---|---|
| `$entity.attributes['x'] == v` | `attributeEquals("x", v)` |
| `$entity.attributes['x'] > v` | `attributeGreaterThan("x", v)` |
| `$entity.attributes['x'] >= v` | `attributeGreaterThanEquals("x", v)` |
| `$entity.attributes['x'] < v` | `attributeLessThan("x", v)` |
| `$entity.associatedData['x'] == v` | *no direct constraint — reject or use attribute-based approximation* |
| `$entity.parent != null` | `hierarchyWithinRoot("self")` *(entity has a parent)* |
| `$reference.attributes['x'] == v` | `referenceHaving("refName", attributeEquals("x", v))` |
| `$reference.groupEntity?.attributes['x'] == v` | `referenceHaving("refName", entityGroupHaving(attributeEquals("x", v)))` |
| `$reference.referencedEntity.attributes['x'] == v` | `referenceHaving("refName", entityHaving(attributeEquals("x", v)))` |

**Boolean operators:**

| Expression operator | evitaDB constraint |
|---|---|
| `a && b` | `and(a, b)` |
| `a \|\| b` | `or(a, b)` |
| `!a` | `not(a)` |
| `a && b && c` | `and(a, b, c)` (flattened) |

**Complete translation example:**

Expression:
```
$reference.groupEntity?.attributes['status'] == 'ACTIVE'
    || $reference.referencedEntity.attributes['status'] == 'PREVIEW'
```

Translated `FilterBy`:
```java
filterBy(
    referenceHaving("parameter",
        or(
            entityGroupHaving(attributeEquals("status", "ACTIVE")),
            entityHaving(attributeEquals("status", "PREVIEW"))
        )
    )
)
```

**Mixed-dependency translation example:**

Expression:
```
$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'
    && $entity.attributes['isActive'] == true
```

Translated `FilterBy`:
```java
filterBy(
    and(
        referenceHaving("parameter",
            entityGroupHaving(attributeEquals("inputWidgetType", "CHECKBOX"))
        ),
        attributeEquals("isActive", true)
    )
)
```

**Parameterization at trigger time:**

The pre-translated `FilterBy` is a **template**. At trigger time, the executor injects a
PK-scoping constraint for the specific mutated entity:

- `GROUP_ENTITY_ATTRIBUTE`: adds `entityGroupHaving(entityPrimaryKeyInSet(mutatedPK))`
  within the `referenceHaving` clause
- `REFERENCED_ENTITY_ATTRIBUTE`: adds `entityHaving(entityPrimaryKeyInSet(mutatedPK))`
  within the `referenceHaving` clause

This scoping is critical for correctness: without it, an entity referencing multiple groups
(e.g., group 99 changed, group 55 unchanged) would incorrectly match because of the unchanged
group. The scoping ensures the query evaluates the expression only for references to the
specific changed entity.

**Non-translatable expressions — rejected at schema time:**

If the expression contains constructs that cannot be translated to a `FilterBy` constraint,
an exception is thrown at schema load time. Non-translatable constructs include:

- **Dynamic attribute paths** — `$entity.attributes[someVariable]` where the attribute name
  is a runtime expression (not a string literal). `AccessedDataFinder` cannot determine which
  specific attribute is accessed, so `getDependentAttributes()` cannot be populated and the
  trigger cannot be correctly scoped. Same applies to spread operators (`.*[expr]`).
- **Direct cross-to-local comparisons** — `$reference.groupEntity?.attributes['type'] == $entity.attributes['category']`
  where a cross-entity value is compared directly with a local value. evitaDB's `FilterBy`
  does not support cross-constraint value comparisons.
- **Unsupported operators** — expression operators with no `FilterBy` equivalent

The validation is performed by `ExpressionToQueryTranslator` at schema load time (or by
`AccessedDataFinder` for dynamic path detection). Users see a clear error message explaining
which construct is unsupported and why.

##### Full handler hierarchy

```
IndexMutationTarget (role interface — implemented by EntityCollection)
  getOrCreateIndex(), getIndexIfExists(), getIndexByPrimaryKeyIfExists(), getEntitySchema()
  getTrigger(referenceName, dependencyType, scope)  ← trigger access for FilterBy retrieval
  evaluateFilter(FilterBy)                          ← query-based expression evaluation

IndexMutationExecutor<M> (stateless strategy — execute(M, IndexMutationTarget))
└── ReevaluateFacetExpressionExecutor  handles: ReevaluateFacetExpressionMutation
    (future: ReevaluateHistogramExpressionExecutor handles: ReevaluateHistogramExpressionMutation)

ExpressionIndexTrigger (expression evaluation + FilterBy constraint)
  evaluate()                ← local triggers (inline in ReferenceIndexMutator)
  getFilterByConstraint()   ← cross-entity triggers (full expression as FilterBy template)
  getDependentAttributes(), getDependencyType(), getOwnerEntityType(), ...

IndexMutationExecutorRegistry (static singleton — INSTANCE)
  Map<Class<? extends IndexMutation>, IndexMutationExecutor<?>>
  dispatch(mutation, target) → lookup + execute
```

### Resolved questions (Domain 2)

- [x] **Single entity hop limitation** — confirmed, avoids complexity explosion
- [x] **Cross-entity dependency registration** — catalog-level `CatalogExpressionTriggerRegistry`
  built at schema load time, keyed by `(mutatedEntityType, dependencyType)`
- [x] **Affected entity enumeration** — `IndexMutationExecutor` on the target side resolves
  PKs from the collection's own indexes: `ReducedEntityIndex` (per group) and
  `ReferencedTypeEntityIndex` (per reference type)
- [x] **Processing model** — source executor detects relevant attribute changes (old ≠ new),
  consults triggers, and creates `ReevaluateFacetExpressionMutation` instances wrapped in
  `EntityIndexMutation`. The source does NOT evaluate the expression or determine direction.
  Target collection dispatches to `ReevaluateFacetExpressionExecutor` which translates the
  expression to a parameterized `FilterBy` query, evaluates against current indexes, compares
  with current facet state, and performs add/remove operations.
- [x] **Mutation hierarchy** — `EntityIndexMutation` is a transport envelope (does NOT implement
  `IndexMutation`) carrying `IndexMutation[]` instances. Currently one concrete type:
  `ReevaluateFacetExpressionMutation`. PK resolution and expression evaluation happen inside
  the executor on the target side.
- [x] **No forced abstraction** — `IndexMutation` and `EntityMutation` remain independent
  types; separate dispatch loops in collector for clarity
- [x] **No cross-collection index access** — source executor creates reevaluation mutations but
  never accesses target indexes. The executor on the target side uses the collection's own
  `ReducedEntityIndex`/`ReferencedTypeEntityIndex` to resolve affected entity PKs. No need
  for bidirectional references.
- [x] **Trigger/Handler separation** — registry-based dispatch with clear responsibilities:
  `ExpressionIndexTrigger` (generic base) → `FacetExpressionTrigger` (evaluation + FilterBy),
  `IndexMutationExecutorRegistry` (static singleton — maps `ReevaluateFacetExpressionMutation`
  to `ReevaluateFacetExpressionExecutor`), `EntityCollection implements IndexMutationTarget`
  (role interface — thin dispatcher passes `this`, provides trigger access and query evaluation),
  `CatalogExpressionTriggerRegistry` (cross-schema wiring).
- [x] **Cross-schema wiring** — `CatalogExpressionTriggerRegistry` inverts the ownership:
  schema A (Product) defines the expression, but the registry indexes it under schema B's
  entity type (ParameterGroup). Rebuilt when schemas change (immutability principle — new
  instance constructed, old one remains untouched via `rebuildForEntityType()` returning new
  instance). Detection step uses `getTriggersForAttribute()` for precise filtering.
- [x] **Mixed-dependency expressions** — the full expression (including both local and
  cross-entity paths) is translated to an evitaDB `FilterBy` constraint tree at schema time
  by `ExpressionToQueryTranslator`. At trigger time, the executor parameterizes the constraint
  with the mutated entity PK and evaluates it against current indexes. No expression
  partitioning needed — the query handles local, cross-entity, and mixed paths uniformly.
  Non-translatable expressions are rejected at schema load time with a clear error. See AD 18.

### Remaining open questions (Domain 2)

- [x] **Affected PK resolution for group entity changes** — `ReducedGroupEntityIndex` is keyed
  by the **referenced entity PK** (facet PK), not the group PK (confirmed by test
  `GroupEntityIndexingTest:334`). However, the type-level `ReferencedTypeEntityIndex` for
  `REFERENCED_GROUP_ENTITY_TYPE` maps **group entity PKs** → reduced-index storage PKs via
  `ReferenceTypeCardinalityIndex`. Two-step lookup: `getAllReferenceIndexes(groupPK)` → storage
  PKs → `ReducedGroupEntityIndex.getAllPrimaryKeys()` → owner entity PKs. Same pattern used by
  `FilterByVisitor.getMatchingGroupEntityPrimaryKeys()` for reverse mapping.
- [x] **Multi-schema group entity processing** — each schema's collection handles its own
  triggers independently. The post-processing step in `EntityIndexLocalMutationExecutor` calls
  `CatalogExpressionTriggerRegistry.getTriggersForAttribute()` which returns triggers across ALL
  dependent schemas. The detection step groups triggers by `getOwnerEntityType()` and generates
  one `EntityIndexMutation` per target collection. Each target's `applyIndexMutations()`
  dispatches the nested mutations independently to its own executors. No cross-collection
  coordination is needed — each mutation carries all context needed for independent execution.
- [x] **De-duplication of local + cross-entity triggers** — index operations (add/remove facet)
  are idempotent. If both a local trigger and a cross-entity trigger produce the same mutation
  for the same entity, the second operation is a no-op at the index level. For ordering:
  `LocalMutationExecutorCollector` processes the owner entity first (level N), then cross-entity
  triggers fire during post-processing (level N+1), so the local trigger always completes
  before the cross-entity reevaluation runs.

---

## Domain 3: Index/Unindex Mechanics

### 3a. Initial reference insert with expression

Before calling `addFacetToIndex()` in `ReferenceIndexMutator`, evaluate expression:

- `true` → add facet as normal
- `false` → skip facet add (but still index the reference itself for filtering!)

**Key distinction:** `isFaceted()` currently gates ONLY facet operations. With partial faceting,
the guard becomes: `isFaceted() && (noExpression || expressionEvaluatesToTrue())`.

### 3b. Re-evaluation on cascading change

The decision matrix applies to both local and cross-entity triggers:

| Was faceted? | Now faceted? | Action |
|---|---|---|
| yes | no | Remove from facet index |
| no | yes | Add to facet index |
| yes | yes | no-op |
| no | no | no-op |

**Two mechanisms for the same logic:**
- **Local triggers** (Domain 2a): inline guard in `ReferenceIndexMutator` calls
  `addFacetToIndex()` / `removeFacetInIndex()` directly
- **Cross-entity triggers** (Domain 2b/2c): source executor creates
  `ReevaluateFacetExpressionMutation` wrapped in `EntityIndexMutation`, dispatched to target
  collection → `ReevaluateFacetExpressionExecutor` translates the expression to a parameterized
  `FilterBy` query, evaluates against indexes, compares with current facet state, and calls
  `addFacet()` / `removeFacet()` on the collection's `EntityIndex` instances

**How to determine "was faceted before":** Check `FacetIdIndex.records.contains(entityPK)` —
if entity PK exists in the facet bitmap, it was faceted. No need to store the boolean separately.
This check is used at generation time (cross-entity) and at evaluation time (local) alike.

### 3c. Transactional consistency

Expression evaluation and facet index update must be atomic within the same transaction.
The existing transactional infrastructure (`TransactionalBitmap`, etc.) handles this.

### Resolved questions (Domain 3)

- [x] **Local trigger evaluation placement** — `ReferenceIndexMutator` calls
  `ExpressionIndexTrigger.evaluate()` inline, extending the existing `isFaceted()` guard.
  The trigger instance is obtained from `EntityCollection` (which caches triggers built from
  its own `ReferenceSchema` expressions at schema load time). Since `ReferenceIndexMutator`
  methods are static and receive `EntityIndexLocalMutationExecutor` as argument, the executor
  can provide access to the trigger via a method like `getTriggerFor(referenceName, scope)`.
  The `ContainerizedLocalMutationExecutor` already implements `EntityStoragePartAccessor`
  with cross-entity access — the same `WritableEntityStorageContainerAccessor` passed to
  `ExpressionIndexTrigger.evaluate()` supports fetching any entity type's storage parts.
  The guard becomes: `isFaceted() && (noExpression || trigger.evaluate(pk, refKey, accessor))`.

- [x] **Synchronous fan-out is sufficient** — the fan-out from a group entity change is
  bounded by the number of referencing entities (typically 100-5000 in e-commerce). Each
  facet add/remove is a `RoaringBitmap` O(1) operation. PK resolution reads already-materialized
  indexes. Async processing would add ordering, conflict, and transaction boundary complexity
  for negligible gain. Executor internals can be optimized (bulk bitmap ops) if profiling
  warrants it, without changing the pipeline architecture.

### Open questions (Domain 3)

None — all resolved.

---

## Hidden Traps Inventory

| #  | Trap                                                                                                                               | Severity | Mitigation                                                                                                     |
|----|------------------------------------------------------------------------------------------------------------------------------------|----------|-----------------------------------------------------------------------------------------------------------------|
| 1  | **Circular fetch during mutation** — fetching entity data while mutating it                                                        | High     | Use storage parts + pending mutations via `WritableEntityStorageContainerAccessor`, not query engine            |
| 2  | **Fan-out on group entity change** — one change cascades to many entities                                                          | High     | Use existing `ReducedEntityIndex` per group for reverse lookup; triggers are schema-derived and bounded         |
| 3  | **Multiple reference types to same group** — expression must be re-evaluated per reference type, not just per entity               | Medium   | `CatalogExpressionTriggerRegistry` maps `(targetEntityType, dependencyType)` → all affected triggers           |
| 4  | **Expression evaluation performance** — local triggers: Proxycian proxies with ~2 allocations per entity, pre-built at schema time. Cross-entity triggers: no per-entity evaluation, pure index-based `FilterBy` queries | Medium | Two-mode evaluation: `evaluate()` for local (per-entity), `getFilterByConstraint()` for cross-entity (index-based query) |
| 5  | **Scope awareness** — expression can differ per scope (`facetedPartiallyInScopes`); `ReevaluateFacetExpressionMutation` carries `Scope` | Medium | Must evaluate per-scope, same as `isFacetedInScope()`. Scope transitions (entity moved between scopes) handled naturally: entity removed from old scope indexes, inserted into new scope → expression evaluated inline |
| 6  | **Null safety in expression** — group entity might not exist yet when expression references it                                     | Medium   | `NullSafeAccessStep` handles `?.` but non-null-safe paths will throw — document requirement                    |
| 7  | **Schema evolution** — expression changes on existing data                                                                         | Medium   | Out of scope for this iteration — expression is effectively immutable after initial schema creation. Full re-index mechanism deferred to separate issue. |
| 8  | **Accessor registration** — expression evaluator needs `ObjectPropertyAccessor`/`ObjectElementAccessor` for contract types         | Resolved | All needed accessors already registered via ServiceLoader: `EntityContractAccessor`, `ReferenceContractAccessor`, `AttributesContractAccessor`, `ReferencesContractAccessor`, `AssociatedDataContractAccessor`. Proxycian proxies implement the contract interfaces, so no new registrations needed. |
| 9  | **Cross-transaction visibility** — trigger on entity B must see B's new data when evaluating expression for entity A               | Medium   | Collector calls container `popImplicitMutations` first (updates B's storage), then index `popImplicitMutations` (generates triggers that read B's data) — verify `DataStoreMemoryBuffer` visibility |
| 10 | **De-duplication** — owner entity mutated in same transaction as trigger fires (double re-evaluation)                              | Medium   | Index operations are idempotent; executor can skip redundant operations via `Set<(entityPK, referenceName)>` of already-processed pairs |
| 11 | **Reference still indexed for filtering even when not faceted** — must not skip reference indexing                                 | Low      | Ensure conditional logic only wraps facet add/remove, not the entire reference indexing flow                    |
| 12 | **Empty facet index cleanup** — when expression removes last faceted entity, ensure `FacetReferenceIndex` auto-cleanup still works | Low      | Existing auto-cleanup in `FacetIndex.removeFacet()` should handle this                                          |
| 13 | **Mixed-dependency expressions** — expression uses both local (`$entity.*`) and cross-entity (`$reference.groupEntity.*`) data    | High     | Full expression translated to `FilterBy` at schema time by `ExpressionToQueryTranslator`. Target executor parameterizes with mutated PK and evaluates against indexes. Non-translatable expressions rejected at schema time. See AD 18. |
| 14 | **Multi-source cross-entity dependencies** — expression has OR with different cross-entity sources (`groupEntity \|\| referencedEntity`) | High | Single `ReevaluateFacetExpressionMutation` (no Add/Remove split). Target evaluates full expression via query — handles OR naturally. Registry has entries for BOTH dependency types. |
| 15 | **FilterBy parameterization required** — without PK-scoping, entity with refs to multiple groups gets false positives             | High     | Executor injects `entityGroupHaving(entityPrimaryKeyInSet(mutatedPK))` / `entityHaving(entityPrimaryKeyInSet(mutatedPK))` at trigger time to scope to the specific changed entity. |
| 16 | **Dynamic attribute paths in expressions** — `$entity.attributes[variable]` prevents static analysis                              | Medium   | `AccessedDataFinder` / `ExpressionToQueryTranslator` throws exception at schema load time. Dynamic paths are not supported in `facetedPartially` expressions. |
| 17 | **Entity removal as cross-entity trigger** — group/referenced entity removal changes expression results                           | Medium   | `EntityRemoveMutation` classified as cross-entity trigger source in Domain 2b/2c. Creates `ReevaluateFacetExpressionMutation` same as attribute changes. |
| 18 | **ReflectedReferenceSchema inheritance** — reflected references inherit `facetedPartially` from source schema                     | Medium   | Trigger registration processes both direct and reflected schemas. Source expression change cascades trigger rebuild to all inheriting reflected schemas. |
| 19 | **Reference-level dependencies on referenced/group entity** — expression accesses `$reference.referencedEntity.references['r']*.attributes['x']`, creating a dependency on reference mutations (not entity attributes) on the referenced entity | Medium | New `DependencyType` values (`REFERENCED_ENTITY_REFERENCE_ATTRIBUTE`, `GROUP_ENTITY_REFERENCE_ATTRIBUTE`) with `getDependentReferenceName()` to distinguish from entity-attribute dependencies. Trigger dispatch must watch for `InsertReferenceMutation`, `RemoveReferenceMutation`, `UpsertReferenceAttributeMutation` on the target entity. FilterBy translation produces nested `referenceHaving` inside `entityHaving`/`groupHaving`. See AD-22. |

---

## Architecture Decisions

### Decided

1. **Pre-analysis at schema load time** — use `AccessedDataFinder` to classify expression
   dependencies, build proxy classes, compose state recipes, and populate
   `CatalogExpressionTriggerRegistry` — all at schema load/change time

2. **Catalog-level trigger registry** — maps `(targetEntityType, dependencyType)` → list of
   `ExpressionIndexTrigger` descriptors for cross-entity lookups

3. **`MutationContract` taxonomy root** — empty marker interface in
   `io.evitadb.api.requestResponse.mutation`. Both `Mutation` (external, sealed) and
   `IndexMutation` (internal) extend it. Enables IDE type hierarchy navigation across the
   complete mutation landscape. Zero backward compatibility impact — `Mutation` just adds
   `extends MutationContract`, no changes to its sealed `permits` clause or existing imports.

4. **Source detects, target decides** — the source `EntityIndexLocalMutationExecutor`
   detects relevant attribute changes (old ≠ new), consults `CatalogExpressionTriggerRegistry`,
   and creates `ReevaluateFacetExpressionMutation` instances. The source does NOT evaluate
   the expression and does NOT determine add/remove direction — it only signals that a
   relevant change occurred. PK resolution, expression evaluation (via `FilterBy` query),
   and add/remove decisions happen entirely on the target side.

5. **No cross-collection index access** — source entity's executor never reaches into the
   target collection's indexes. The executor on the target side resolves affected PKs via
   two-step lookup: `ReferencedTypeEntityIndex` (type-level, `REFERENCED_GROUP_ENTITY_TYPE`
   or `REFERENCED_ENTITY_TYPE`) → `getAllReferenceIndexes(mutatedPK)` → reduced-index
   storage PKs → `ReducedGroupEntityIndex`/`ReducedEntityIndex` → `getAllPrimaryKeys()` →
   owner entity PKs. Facet PK and group PK are recovered from `EntityIndexKey` discriminators.

6. **Thin dispatch path** — `EntityCollection implements IndexMutationTarget` (role interface
   limiting access to index lookup, schema retrieval, trigger access, and query evaluation).
   `applyIndexMutations()` iterates the `IndexMutation` instances nested in
   `EntityIndexMutation` and delegates each to
   `IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this)`. Zero allocations —
   `this` is the target. No container executor is created.

7. **Separate implicit mutation records per executor** — each executor produces its own
   dedicated record. `ContainerizedLocalMutationExecutor` returns `ImplicitMutations`
   (unchanged: `localMutations[]` + `externalMutations[]`) via `popImplicitMutations()`.
   `EntityIndexLocalMutationExecutor` returns `IndexImplicitMutations` (`indexMutations[]`
   only) via a new `popIndexImplicitMutations()` method — it does **not** implement
   `ConsistencyCheckingLocalMutationExecutor`. Collector calls both sequentially
   (container first, then index).

8. **Single entity hop limitation** — expressions can navigate from the owner entity to at most
   one other entity (referenced entity or group entity). Once there, the expression may read
   that entity's own properties **including its references and their attributes** (e.g.,
   `$reference.referencedEntity.references['x']*.attributes['A']`), but may NOT follow those
   references to reach a third entity. This enables expressions that react on reference
   mutations on the referenced/group entity while keeping the dependency graph bipartite.

9. **Proxycian proxy wrapping** — contract types (`EntityContract`, `ReferenceContract`) are
   implemented via Proxycian-generated proxies that delegate directly to storage part data.
   Avoids full entity assembly pipeline and the `Reference.getReferencedEntity()` →
   `Optional.empty()` problem. Same pattern as `ReferencedTypeEntityIndex.createThrowingStub()`.

10. **Composable partial implementations** — each partial is a `PredicateMethodClassification`
    that implements a related set of contract methods. Partials are selected per expression
    based on `AccessedDataFinder` path analysis. A catch-all partial throws
    `ExpressionEvaluationException` for unused methods, providing fail-fast safety against
    `@Delegate` delegation to unimplemented methods.

11. **Two-phase lifecycle** — proxy classes and state recipes are built at schema load time
    (expensive but amortized). At trigger time, only state records are created and pre-built
    proxy classes instantiated (cheap — ~2 allocations per entity). No class generation on
    the hot path.

12. **Registry-based executor dispatch — stateless static singleton** — clear separation
    of concerns across four layers:
    - `IndexMutationTarget` — role interface implemented by `EntityCollection`. Limits
      executor access to index lookup (`getOrCreateIndex`, `getIndexIfExists`,
      `getIndexByPrimaryKeyIfExists`), schema retrieval (`getEntitySchema`), trigger
      access (`getTrigger` — for `FilterBy` constraint retrieval), and query-based filter
      evaluation (`evaluateFilter` — for index-based expression evaluation). Executors
      never see the full collection API. Zero allocations — `EntityCollection` passes `this`.
    - `ExpressionIndexTrigger` — generic base interface for expression-driven index triggers.
      Subtypes: `FacetExpressionTrigger` (conditional facet indexing),
      `HistogramExpressionTrigger` (conditional histogram indexing, future).
      Two evaluation modes: `evaluate()` for local triggers (per-entity, Proxycian proxies),
      `getFilterByConstraint()` for cross-entity triggers (full expression as `FilterBy`
      template, parameterized at trigger time, evaluated against indexes).
    - `IndexMutationExecutorRegistry` — static singleton mapping concrete `IndexMutation`
      types (`ReevaluateFacetExpressionMutation`) to stateless `IndexMutationExecutor<M>`
      singletons (`ReevaluateFacetExpressionExecutor`). Both the registry and its executors
      hold no instance state — they survive `EntityCollection` transactional copy without
      reinstantiation. Extensible — new mutation types require only a new mutation record +
      executor class + registry entry.
    - `CatalogExpressionTriggerRegistry` — catalog-level inverted index. Maps
      `(mutatedEntityType, dependencyType)` → `List<ExpressionIndexTrigger>`. Inverts the
      ownership: expression defined in schema A, indexed under schema B. Rebuilt on schema
      changes (`rebuildForEntityType()` returns new instance — immutability principle).

13. **Cross-schema wiring at schema load time** — when a `ReferenceSchema`'s
    `facetedPartially` expression is set/changed, `EntityCollection` (in `evita_engine`) reads
    the `Expression` from `ReferenceSchema.facetedPartiallyInScopes`, uses `AccessedDataFinder`
    to analyze paths, builds `FacetExpressionTrigger` instances, and registers them in
    `CatalogExpressionTriggerRegistry` under the appropriate (mutatedEntityType, dependencyType)
    keys. Schema B "maintains" triggers for schema A by virtue of being the registry key.

14. **Trigger infrastructure in `evita_engine`, not `evita_api`** — `ReferenceSchema` in
    `evita_api` holds the raw `Expression` (`facetedPartiallyInScopes`) as a declarative data
    field — this is public API, appropriate there. All operational machinery —
    `ExpressionIndexTrigger` instances, Proxycian proxy wrapping logic, `CatalogExpressionTriggerRegistry` —
    lives in `evita_engine` alongside `EntityCollection`. Rationale:
    - `evita_api` is inherently public; trigger infrastructure is internal engine behavior
    - `EntityCollection` already manages schema lifecycle — trigger rebuild on schema change
      is a natural extension
    - Symmetric placement: `EntityCollection` hosts both the **inbound** dispatcher
      (`IndexMutationExecutorRegistry` — processes `IndexMutation` from other collections)
      and the **outbound** trigger infrastructure (`ExpressionIndexTrigger` instances —
      generates `IndexMutation` originating from this collection's schema definitions)
    - Trigger instances are built from `ReferenceSchema.facetedPartiallyInScopes` Expression
      at schema load/change time and cached in the catalog-level registry

15. **De-duplication via idempotency** — index operations (add/remove facet) are idempotent.
    If both a local trigger and a cross-entity trigger produce the same mutation for the same
    entity, the second operation is a no-op at the index level. Executors can optionally
    maintain a `Set<(entityPK, referenceName)>` to skip redundant PK resolution and
    StoragePart fetches for performance.

16. **Synchronous fan-out is sufficient** — cross-entity trigger processing via
    `popIndexImplicitMutations()` → `applyIndexMutations()` runs synchronously. The fan-out
    is bounded by the number of entities referencing the changed group/referenced entity.
    Each operation is a bitmap add/remove — O(1). The expensive part (PK resolution) reads
    already-materialized indexes. Async processing would add ordering guarantees, conflict
    detection, and transaction boundary complexity for negligible gain. If profiling reveals
    a hot spot, the executor can be internally optimized (bulk bitmap ops) without changing
    the pipeline architecture.

17. **`IndexMutationExecutorRegistry` lifecycle — static singleton** — the registry is a
    `static final` field with an immutable executor map. Executors are stateless singletons
    that receive all collection context via `IndexMutationTarget` (a role interface
    implemented by `EntityCollection`). This avoids reinstantiation during
    `EntityCollection.createCopyWithMergedTransactionalMemory()` — the registry and its
    executors are never fields on `EntityCollection`, just a static constant accessed at
    dispatch time. `EntityCollection` accesses it as `IndexMutationExecutorRegistry.INSTANCE`.

18. **Mixed-dependency expression handling — full expression translated to `FilterBy`** —
    when a `facetedPartially` expression references both cross-entity data
    (`$reference.groupEntity?.attributes['x']`) and local data (`$entity.attributes['y']`),
    the **entire** expression is translated to an evitaDB `FilterBy` constraint tree by
    `ExpressionToQueryTranslator` at schema load time. There is no expression partitioning —
    the full expression becomes a single `FilterBy` template stored in the trigger:
    - `ExpressionToQueryTranslator` maps every supported expression node to the corresponding
      evitaDB constraint (e.g., `$entity.attributes['x'] == v` → `attributeEquals("x", v)`,
      `$reference.groupEntity?.attributes['x'] == v` →
      `referenceHaving("refName", groupHaving(attributeEquals("x", v)))`,
      `$reference.referencedEntity.references['r']*.attributes['x'] > v` →
      `referenceHaving("refName", entityHaving(referenceHaving("r", attributeGreaterThan("x", v))))`)
    - Non-translatable expressions (dynamic attribute paths, direct cross-to-local comparisons,
      unsupported operators) are **rejected at schema time** with a clear error — no per-entity
      fallback exists
    - At trigger time, the `ReevaluateFacetExpressionExecutor` parameterizes the `FilterBy`
      template with the mutated entity PK (via `entityGroupHaving(entityPrimaryKeyInSet(pk))`
      or `entityHaving(entityPrimaryKeyInSet(pk))`) to scope evaluation to the specific
      changed entity — preventing false positives from other groups/referenced entities
    - The parameterized `FilterBy` is evaluated against the collection's current indexes
      (via `IndexMutationTarget.evaluateFilter()`) to determine which owner entity PKs
      currently satisfy the expression
    - The executor then compares the result against the current facet index state and
      adds/removes facets accordingly

19. **`CatalogExpressionTriggerRegistry` immutability** — the registry follows the same
    copy-on-write immutability principle as `EntityCollection`. When `rebuildForEntityType()`
    is called, a **new** registry instance is constructed from the updated schemas. The
    original instance remains untouched and continues serving concurrent readers until they
    switch to the new instance. This eliminates the risk of concurrent mutation processing
    seeing stale or partially-built trigger state.

20. **Schema evolution deferred** — full re-index on `facetedPartially` expression change is
    out of scope for this iteration. The expression is treated as effectively immutable after
    initial schema creation. The re-index mechanism will be addressed in a separate issue.

21. **Initial catalog load** — data loaded from disk is already consistent with the schema.
    No expression re-evaluation is needed on load. After `EntityCollection` instantiation,
    the catalog wires trigger implementations between collections by scanning all loaded
    `ReferenceSchema` definitions with `facetedPartiallyInScopes` expressions — populating
    `CatalogExpressionTriggerRegistry` via the same process as schema load time trigger
    building.

22. **Referenced/group entity reference attributes in expressions** — expressions may access
    reference attributes on the referenced entity or group entity via patterns like
    `$reference.referencedEntity.references['r']*.attributes['x']`. This is within the
    single-hop boundary (AD-8) because the expression navigates to the referenced entity and
    reads its own reference data, without following those references to a third entity.
    Implementation requires:
    - Two new `DependencyType` values: `REFERENCED_ENTITY_REFERENCE_ATTRIBUTE` and
      `GROUP_ENTITY_REFERENCE_ATTRIBUTE` — distinguishing entity-attribute dependencies from
      reference-attribute dependencies on the target entity
    - A new `getDependentReferenceName()` method on `ExpressionIndexTrigger` returning the
      reference name on the target entity (e.g., `'r'`), or `null` for entity-attribute
      dependencies
    - `ExpressionToQueryTranslator` extension: after `.referencedEntity`/`.groupEntity`,
      recognize `.references['r']` and produce nested
      `referenceHaving("r", attributeConstraint)` inside `entityHaving(...)` /
      `groupHaving(...)`
    - `FacetExpressionTriggerFactory` extension: `detectDependencyType()` must distinguish
      `.referencedEntity.attributes[...]` from `.referencedEntity.references['r'].attributes[...]`;
      `extractDependentAttribute()` must correctly identify the reference-level attribute
    - Cross-entity trigger dispatch must fire on reference mutations
      (`InsertReferenceMutation`, `RemoveReferenceMutation`, `UpsertReferenceAttributeMutation`)
      on the target entity, not just entity attribute mutations
    - The FilterBy translation: `referenceHaving('ownerRef', entityHaving(referenceHaving('r',
      attributeGreaterThan('x', v))))` — standard nested evitaQL, no new constraint types needed

### Still open

None — all Architecture Decisions resolved.
