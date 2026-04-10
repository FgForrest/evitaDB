# WBS-03a: Reference-Attribute Dependencies on Referenced/Group Entities

> **Parent document:** [WBS-03: ExpressionIndexTrigger and FacetExpressionTrigger](WBS-03-expression-index-trigger.md)
> **Architecture Decision:** AD-22 in [conditional-facet-indexing.md](../conditional-facet-indexing.md)

## Objective

Extend the conditional facet indexing trigger infrastructure to support expressions that navigate
from the owner entity through a reference to the referenced (or group) entity, then access
**references of that target entity** and check their attributes. This is the "single entity hop +
reference attribute" pattern:

```
$reference.referencedEntity.references['x'].attributes['A'] > 1
$reference.referencedEntity.references['x']*.attributes['A'] > 1   (spread variant for ZERO_OR_MORE)
```

The equivalent evitaQL for both is the same nested `referenceHaving`:

```
referenceHaving('ownerRef', entityHaving(referenceHaving('x', attributeGreaterThan('A', 1))))
```

The `referenceHaving` constraint has existential semantics (matches if ANY reference of the given
type satisfies the condition), which is the evitaQL equivalent of the spread operator iterating
over all references of a type.

This stays within the "single entity hop" limitation (AD-8): we navigate to the referenced entity
and read its own data (including its references and their attributes), but we never follow those
references to a third entity.

## Motivation

The current implementation only handles paths of the form
`$reference.referencedEntity.attributes['X']` (direct attributes on the referenced entity). It
cannot handle `$reference.referencedEntity.references['r'].attributes['A']` (attributes on the
referenced entity's own references). This limitation prevents users from writing conditional facet
expressions that depend on reference-level data of the target entity — a common pattern in
e-commerce catalogs (e.g., "only facet-index a product-parameter reference if the parameter entity
has a 'tag' reference with attribute 'visible' == true").

## Scope

### In Scope

- Two new `DependencyType` enum values: `REFERENCED_ENTITY_REFERENCE_ATTRIBUTE` and
  `GROUP_ENTITY_REFERENCE_ATTRIBUTE`
- New `getDependentReferenceName()` method on `ExpressionIndexTrigger` interface
- Updated `FacetExpressionTriggerImpl` with `dependentReferenceName` field
- Updated `FacetExpressionTriggerFactory` path classification to distinguish entity-attribute vs.
  reference-attribute dependencies and extract the dependent reference name
- Updated `ExpressionToQueryTranslator` to recognize `.references['r'].attributes['A']` after
  `.referencedEntity`/`.groupEntity` and produce nested `referenceHaving`
- New `PathType` values and updated `DataPath` record in `ExpressionToQueryTranslator`
- Comprehensive tests for all changed components

### Out of Scope

- Multi-hop navigation (following references from the referenced entity to a third entity)
- Changes to `AccessedDataFinder` (it already produces correct paths for this pattern)
- Changes to `ExpressionProxyFactory` or proxy infrastructure (the per-entity `evaluate()` path
  already works via the accessor chain)
- Changes to `CatalogExpressionTriggerRegistry` or downstream trigger dispatch (WBS-04+)

### Spread Operator Handling

Both the non-spread pattern (`references['x'].attributes['A'] > 1` — for `ZERO_OR_ONE` /
`EXACTLY_ONE` cardinality) and the spread pattern (`references['x']*.attributes['A'] > 1` — for
`ZERO_OR_MORE` / `ONE_OR_MORE` cardinality) are supported in the translator.

The spread operator (`SpreadAccessStep`) in the `ExpressionToQueryTranslator` is currently
rejected globally (lines 248-252). This rejection must become **context-aware**: allow
`SpreadAccessStep` specifically after `.references['r']` on a referenced/group entity, reject it
everywhere else. The rationale is that `referenceHaving` has existential semantics ("any reference
of this type satisfies..."), which is the exact evitaQL equivalent of the spread operator
iterating over all references. The translator does not need to recurse into the spread's mapping
expression — it extracts the attribute name from the mapping expression's access chain and
produces the same nested `referenceHaving` as the non-spread variant.

For the spread variant, the `ObjectAccessOperator`'s access chain is:

```
PropertyAccessStep("referencedEntity")
  -> PropertyAccessStep("references")
    -> ElementAccessStep('x')
      -> SpreadAccessStep(mappingExpr: $.attributes['A'])
```

The mapping expression `$.attributes['A']` uses `$` (this) referring to each reference. The
translator extracts the attribute name `'A'` from this mapping expression's chain.

## Path Structure Analysis

For the expression `$reference.referencedEntity.references['x'].attributes['A'] > 1` (or its
spread variant `references['x']*.attributes['A'] > 1`), the `AccessedDataFinder` produces:

```
[VariablePathItem("reference"), IdentifierPathItem("referencedEntity"),
 IdentifierPathItem("references"), ElementPathItem("x"),
 IdentifierPathItem("attributes"), ElementPathItem("A")]
```

| Index | PathItem Type       | Value             | Description                             |
|-------|---------------------|-------------------|-----------------------------------------|
| 0     | VariablePathItem    | `reference`       | The `$reference` variable               |
| 1     | IdentifierPathItem  | `referencedEntity`| Navigation to referenced entity         |
| 2     | IdentifierPathItem  | `references`      | Access to entity's references collection|
| 3     | ElementPathItem     | `x`               | Reference name on the target entity     |
| 4     | IdentifierPathItem  | `attributes`      | Attributes accessor                     |
| 5     | ElementPathItem     | `A`               | Attribute name                          |

Compare with the existing entity-attribute path `$reference.referencedEntity.attributes['A']`:

| Index | PathItem Type       | Value             |
|-------|---------------------|-------------------|
| 0     | VariablePathItem    | `reference`       |
| 1     | IdentifierPathItem  | `referencedEntity`|
| 2     | IdentifierPathItem  | `attributes`      |
| 3     | ElementPathItem     | `A`               |

The key discriminator is **position 2**: `attributes`/`localizedAttributes` means entity-attribute
dependency; `references` means reference-attribute dependency.

## AST Structure Analysis

### Non-spread variant (ZERO_OR_ONE / EXACTLY_ONE cardinality)

For `$reference.referencedEntity.references['x'].attributes['A'] > 1`, the access chain is:

```
PropertyAccessStep("referencedEntity")
  -> PropertyAccessStep("references")
    -> ElementAccessStep('x')
      -> PropertyAccessStep("attributes")
        -> ElementAccessStep('A')
```

### Spread variant (ZERO_OR_MORE / ONE_OR_MORE cardinality)

For `$reference.referencedEntity.references['x']*.attributes['A'] > 1`, the access chain is:

```
PropertyAccessStep("referencedEntity")
  -> PropertyAccessStep("references")
    -> ElementAccessStep('x')
      -> SpreadAccessStep(mappingExpr: $.attributes['A'])
```

The chain ends at `SpreadAccessStep`. The attribute access `$.attributes['A']` is inside the
spread's mapping expression (a separate sub-AST). The `$` (this) refers to each reference of
type `'x'`. The translator extracts the attribute name from the mapping expression's
`ObjectAccessOperator` chain without recursing into the full sub-expression.

Both variants produce the same evitaQL: `referenceHaving('x', attributeGreaterThan('A', 1))`.
The `referenceHaving` existential semantics is equivalent to the spread's iteration.

---

## Implementation Tasks

### Group 1: `DependencyType` Enum Extension

**Dependency:** None (leaf task)

#### Task 1.1: Add new enum values

**File:** `evita_engine/src/main/java/io/evitadb/index/mutation/DependencyType.java`

Add two new values with complete JavaDoc:

- `REFERENCED_ENTITY_REFERENCE_ATTRIBUTE` — "The mutated entity is the referenced entity of the
  reference, and the dependency is on a reference attribute of that entity. Expression path:
  `$reference.referencedEntity.references['r'].attributes['x']`. The trigger fires when a
  reference mutation on the referenced entity affects reference 'r' (insert, remove, or
  attribute 'x' change)."
- `GROUP_ENTITY_REFERENCE_ATTRIBUTE` — "The mutated entity is the group entity of the reference,
  and the dependency is on a reference attribute of that entity. Expression path:
  `$reference.groupEntity?.references['r'].attributes['x']`. Fan-out can be significant
  (same as `GROUP_ENTITY_ATTRIBUTE`)."

---

### Group 2: `ExpressionIndexTrigger` Interface Extension

**Dependency:** Group 1

#### Task 2.1: Add `getDependentReferenceName()` method

**File:** `evita_engine/src/main/java/io/evitadb/index/mutation/ExpressionIndexTrigger.java`

Add:

```java
/**
 * Returns the name of the reference on the target entity (referenced or group) whose
 * attributes this expression reads. Non-null only for
 * {@link DependencyType#REFERENCED_ENTITY_REFERENCE_ATTRIBUTE} and
 * {@link DependencyType#GROUP_ENTITY_REFERENCE_ATTRIBUTE}.
 * Returns {@code null} for entity-attribute dependencies and local-only triggers.
 */
@Nullable
String getDependentReferenceName();
```

---

### Group 3: `FacetExpressionTriggerImpl` Update

**Dependency:** Group 2

#### Task 3.1: Add `dependentReferenceName` field and update constructors

**File:** `evita_engine/src/main/java/io/evitadb/core/expression/trigger/FacetExpressionTriggerImpl.java`

- Add field: `@Nullable private final String dependentReferenceName;`
- **Cross-entity constructor**: add `@Nullable String dependentReferenceName` parameter. For
  existing `REFERENCED_ENTITY_ATTRIBUTE`/`GROUP_ENTITY_ATTRIBUTE` callers, pass `null`.
- **Local-only constructor**: set `this.dependentReferenceName = null;` (no new parameter)
- Implement `getDependentReferenceName()` getter

---

### Group 4: `FacetExpressionTriggerFactory` Path Classification Update

**Dependency:** Group 3

#### Task 4.1: Update `detectDependencyType()` to distinguish reference-attribute paths

**File:** `evita_engine/src/main/java/io/evitadb/core/expression/trigger/FacetExpressionTriggerFactory.java`

Current implementation (line 195-217) only checks positions 0-1. After determining the base type
(`referencedEntity` or `groupEntity`), check **position 2**:

- If `IdentifierPathItem` with value `EntityContractAccessor.REFERENCES_PROPERTY` →
  return `REFERENCED_ENTITY_REFERENCE_ATTRIBUTE` or `GROUP_ENTITY_REFERENCE_ATTRIBUTE`
- If `IdentifierPathItem` with value matching `attributes`/`localizedAttributes` →
  return existing `REFERENCED_ENTITY_ATTRIBUTE` or `GROUP_ENTITY_ATTRIBUTE`
- Otherwise → return `null` (unknown path pattern)

Must use `EntityContractAccessor.REFERENCES_PROPERTY` constant, not a plain string.

#### Task 4.2: Make `extractDependentAttribute()` path-depth-aware

**File:** `evita_engine/src/main/java/io/evitadb/core/expression/trigger/FacetExpressionTriggerFactory.java`

Current implementation (line 228-240) scans the entire path for ANY `attributes` +
`ElementPathItem` pair — incorrect for reference-attribute paths because it would find
`attributes['A']` at positions 4-5 and misidentify it as an entity attribute.

Refactor to accept the `DependencyType` (or a start-index) to know where to scan:

- For `REFERENCED_ENTITY_ATTRIBUTE` / `GROUP_ENTITY_ATTRIBUTE`: scan from position 2
  (immediately after `referencedEntity`/`groupEntity`)
- For `*_REFERENCE_ATTRIBUTE`: scan from position 4 (after `references['r']`)

Existing behavior for entity-attribute dependencies must remain unchanged.

#### Task 4.3: Add `extractDependentReferenceName()` method

**File:** `evita_engine/src/main/java/io/evitadb/core/expression/trigger/FacetExpressionTriggerFactory.java`

New private static method:

```java
@Nullable
private static String extractDependentReferenceName(@Nonnull List<PathItem> path)
```

For reference-attribute paths (`[$reference, referencedEntity, references, x, ...]`), position 3
is an `ElementPathItem` containing the reference name. Return its value. For entity-attribute
paths, return `null`.

#### Task 4.4: Update `classifyPaths()` to track reference name alongside dependency type

**File:** `evita_engine/src/main/java/io/evitadb/core/expression/trigger/FacetExpressionTriggerFactory.java`

Current map key is `DependencyType`. This is insufficient because the same expression could
access multiple reference names on the target entity. Change key to a composite record:

```java
private record DependencyKey(@Nonnull DependencyType type, @Nullable String referenceName) {}
```

Updated return type: `LinkedHashMap<DependencyKey, Set<String>>`

This handles expressions like `$reference.referencedEntity.references['x'].attributes['A'] > 1
&& $reference.referencedEntity.references['y'].attributes['B'] > 2` — two separate dependency
keys, each producing a separate trigger.

#### Task 4.5: Update `buildTriggersForExpression()` to pass reference name to constructor

**File:** `evita_engine/src/main/java/io/evitadb/core/expression/trigger/FacetExpressionTriggerFactory.java`

The loop over `dependencyAttributes.entrySet()` must extract the reference name from the
composite `DependencyKey` and pass it to the `FacetExpressionTriggerImpl` cross-entity
constructor.

---

### Group 5: `ExpressionToQueryTranslator` Update

**Dependency:** None (independent of Groups 1-4, can be implemented in parallel)

#### Task 5.1: Add new `PathType` values

**File:** `evita_engine/src/main/java/io/evitadb/core/expression/query/ExpressionToQueryTranslator.java`

Add to the `PathType` enum:

- `REFERENCED_ENTITY_REFERENCE_ATTRIBUTE` — wrapped in
  `referenceHaving(ownerRef, entityHaving(referenceHaving('r', ...)))`
- `GROUP_ENTITY_REFERENCE_ATTRIBUTE` — wrapped in
  `referenceHaving(ownerRef, groupHaving(referenceHaving('r', ...)))`

#### Task 5.2: Extend `DataPath` record to include reference name

**File:** `evita_engine/src/main/java/io/evitadb/core/expression/query/ExpressionToQueryTranslator.java`

Current: `record DataPath(@Nonnull PathType pathType, @Nonnull String attributeName)`

Change to: `record DataPath(@Nonnull PathType pathType, @Nonnull String attributeName, @Nullable String referenceName)`

The `referenceName` is non-null only for `*_REFERENCE_ATTRIBUTE` path types. Update all existing
`DataPath` construction sites to pass `null` for `referenceName`.

#### Task 5.3: Update `extractEntityAttributePath()` to recognize `.references['r']` chains

**File:** `evita_engine/src/main/java/io/evitadb/core/expression/query/ExpressionToQueryTranslator.java`

After skipping the null-safe step on the navigation property (`.referencedEntity`/`.groupEntity`),
check the next property:

1. If `attributes`/`localizedAttributes` → existing behavior, return
   `DataPath(pathType, attributeName, null)`
2. If `EntityContractAccessor.REFERENCES_PROPERTY` ("references") → new behavior:
   a. Next step must be `ElementAccessStep` with `ConstantOperand` string → extract reference
      name `'r'`
   b. Check the step after the element access:
      - **Non-spread path** (ZERO_OR_ONE / EXACTLY_ONE cardinality):
        skip optional `NullSafeAccessStep`, next must be `PropertyAccessStep` with
        `attributes`/`localizedAttributes`, extract attribute name from `ElementAccessStep`
        after that
      - **Spread path** (ZERO_OR_MORE / ONE_OR_MORE cardinality):
        next step is `SpreadAccessStep` → get the mapping expression from the spread.
        The mapping expression must be an `ObjectAccessOperator` whose operand is
        `VariableOperand(this)` (`$` or `$.`). Walk its access chain to find
        `PropertyAccessStep("attributes"/"localizedAttributes")` → `ElementAccessStep('A')`.
        Extract the attribute name `'A'`.
   c. Map the incoming `pathType`: `REFERENCED_ENTITY_ATTRIBUTE` →
      `REFERENCED_ENTITY_REFERENCE_ATTRIBUTE`, `GROUP_ENTITY_ATTRIBUTE` →
      `GROUP_ENTITY_REFERENCE_ATTRIBUTE`
   d. Return `DataPath(mappedPathType, attributeName, referenceName)`
3. Otherwise → throw `NonTranslatableExpressionException` (existing behavior)

#### Task 5.3a: Make spread rejection context-aware

**File:** `evita_engine/src/main/java/io/evitadb/core/expression/query/ExpressionToQueryTranslator.java`

The blanket `SpreadAccessStep` rejection at lines 248-252 in `classifyPath()` must become
context-aware. The rejection runs BEFORE `extractEntityAttributePath()` is called, so it would
block the spread variant before it reaches the new handling code.

Two approaches:
- **Option A:** Remove the blanket rejection from `classifyPath()` and let
  `extractEntityAttributePath()` handle spread in context (rejecting it in other positions
  via the existing "expected `.attributes`" error)
- **Option B:** Move the rejection into `extractEntityAttributePath()` so it only fires for
  spread in unsupported positions

Option A is simpler — the spread after `.references['r']` is the only legal position, and all
other positions already produce errors because the translator expects specific step types.

#### Task 5.4: Update `wrapForPathType()` to handle new path types

**File:** `evita_engine/src/main/java/io/evitadb/core/expression/query/ExpressionToQueryTranslator.java`

Add cases:

```java
case REFERENCED_ENTITY_REFERENCE_ATTRIBUTE -> referenceHaving(
    this.referenceName,
    entityHaving(referenceHaving(dataPath.referenceName(), attributeConstraint))
);
case GROUP_ENTITY_REFERENCE_ATTRIBUTE -> referenceHaving(
    this.referenceName,
    groupHaving(referenceHaving(dataPath.referenceName(), attributeConstraint))
);
```

Note: `this.referenceName` is the owner entity's reference; `dataPath.referenceName()` is the
target entity's reference.

#### Task 5.5: Verify `mergeReferenceHaving()` handles nested patterns

**File:** `evita_engine/src/main/java/io/evitadb/core/expression/query/ExpressionToQueryTranslator.java`

No code changes expected. The existing merge logic groups by outer `ReferenceHaving` reference
name (`this.referenceName`), which is the same for all paths from the same trigger. Nested
`referenceHaving` inside `entityHaving`/`groupHaving` is a child constraint and passes through
correctly.

Verify with a test (Task 6.3.5).

---

### Group 6: Tests

**Dependency:** Groups 3, 4, 5

#### Task 6.1: Update `FacetExpressionTriggerImplTest`

**File:** `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/expression/trigger/FacetExpressionTriggerImplTest.java`

New `@Nested` class `DependentReferenceNameTest`:

1. `shouldReturnNullDependentReferenceNameForEntityAttributeDependency` — cross-entity trigger
   with `REFERENCED_ENTITY_ATTRIBUTE` and null reference name → `getDependentReferenceName()`
   returns null
2. `shouldReturnNullDependentReferenceNameForLocalOnlyTrigger` — local-only trigger → null
3. `shouldReturnDependentReferenceNameForReferencedEntityReferenceAttribute` — cross-entity
   trigger with `REFERENCED_ENTITY_REFERENCE_ATTRIBUTE`, reference name "tags" → returns "tags"
4. `shouldReturnDependentReferenceNameForGroupEntityReferenceAttribute` — cross-entity trigger
   with `GROUP_ENTITY_REFERENCE_ATTRIBUTE`, reference name "categories" → returns "categories"

Update existing helper methods to pass `null` for `dependentReferenceName` in existing test cases.

#### Task 6.2: Update `FacetExpressionTriggerFactoryTest`

**File:** `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/expression/trigger/FacetExpressionTriggerFactoryTest.java`

New `@Nested` class `ReferencedEntityReferenceAttributeTest`:

1. `shouldBuildReferencedEntityReferenceAttributeTrigger` — expression
   `$reference.referencedEntity.references['tags'].attributes['visible'] == true` → verify
   `REFERENCED_ENTITY_REFERENCE_ATTRIBUTE`, `getDependentReferenceName()` = "tags",
   `getDependentAttributes()` = `{"visible"}`, non-null FilterBy
2. `shouldCollectMultipleAttributesFromSameReferenceOnReferencedEntity` — two attributes on the
   same target reference → both in `getDependentAttributes()`
3. `shouldBuildSeparateTriggersForDifferentReferenceNamesOnReferencedEntity` — expression
   accessing `references['x'].attributes['A']` AND `references['y'].attributes['B']` → two
   separate triggers (one per dependency key)

New `@Nested` class `GroupEntityReferenceAttributeTest`:

4. `shouldBuildGroupEntityReferenceAttributeTrigger` — expression
   `$reference.groupEntity?.references['links'].attributes['weight'] > 0` → verify
   `GROUP_ENTITY_REFERENCE_ATTRIBUTE`, reference name "links"

New test in existing `DualAndMixedDependencyTest`:

5. `shouldBuildTriggersForMixedEntityAndReferenceAttributePaths` — expression combining
   `$reference.referencedEntity.attributes['code'] == 'A'` AND
   `$reference.referencedEntity.references['tags'].attributes['visible'] == true` → two triggers:
   `REFERENCED_ENTITY_ATTRIBUTE` (code) and `REFERENCED_ENTITY_REFERENCE_ATTRIBUTE` (visible on
   tags)

#### Task 6.3: Update `ExpressionToQueryTranslatorTest`

**File:** `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/expression/query/ExpressionToQueryTranslatorTest.java`

New test methods:

1. `shouldTranslateReferencedEntityReferenceAttributeEquals` — expression:
   `$reference.referencedEntity.references['tags'].attributes['visible'] == true` → expected:
   `filterBy(referenceHaving(REF_NAME, entityHaving(referenceHaving("tags", attributeEquals("visible", true)))))`

2. `shouldTranslateGroupEntityReferenceAttributeGreaterThan` — expression:
   `$reference.groupEntity?.references['metrics'].attributes['score'] > 5` → expected:
   `filterBy(referenceHaving(REF_NAME, groupHaving(referenceHaving("metrics", attributeGreaterThan("score", 5L)))))`

3. `shouldTranslateReferencedEntityReferenceLocalizedAttributeEquals` — expression:
   `$reference.referencedEntity.references['tags'].localizedAttributes['label'] == 'x'` →
   verify `localizedAttributes` path works through the reference chain

4. `shouldTranslateMixedEntityAttributeAndReferenceAttributeInAnd` — expression:
   `$reference.referencedEntity.attributes['code'] == 'A' && $reference.referencedEntity.references['tags'].attributes['visible'] == true`
   → verify both constraints merge correctly under a single outer `referenceHaving`

5. `shouldMergeReferenceHavingWithNestedReferenceHavingInAnd` — two reference-attribute
   comparisons for the same owner reference in AND context should merge under a single outer
   `referenceHaving`

6. `shouldTranslateSpreadOnReferencedEntityReferences` — expression with spread syntax:
   `$reference.referencedEntity.references['tags']*.attributes['visible'] == true` → expected:
   same `referenceHaving` nesting as non-spread variant (existential semantics match)

7. `shouldTranslateSpreadOnGroupEntityReferences` — expression with spread on group entity:
   `$reference.groupEntity?.references['metrics']*.attributes['score'] > 5` → expected:
   `filterBy(referenceHaving(REF_NAME, groupHaving(referenceHaving("metrics", attributeGreaterThan("score", 5L)))))`

8. `shouldRejectSpreadInUnsupportedPosition` — spread on a non-reference path (e.g.,
   `$entity.attributes.*[...]`) → verify `NonTranslatableExpressionException` is still thrown

---

## Dependency Ordering

```
Group 1 (DependencyType enum)
    |
    v
Group 2 (ExpressionIndexTrigger interface)
    |
    v
Group 3 (FacetExpressionTriggerImpl)           Group 5 (ExpressionToQueryTranslator)
    |                                               |
    v                                               |
Group 4 (FacetExpressionTriggerFactory)             |
    |                                               |
    +-------------------+---------------------------+
                        |
                        v
                  Group 6 (Tests)
```

Groups 1-4 and Group 5 can be implemented in parallel since the translator changes are
structurally independent.

---

## Risks and Open Questions

### Risk 1: CatalogExpressionTriggerRegistry Key Structure (WBS-04)

The new `DependencyType` values may require changes to how triggers are looked up in the registry.
The current key is `(entityType, DependencyType)`. With reference-attribute dependencies, the
dispatcher must also check `getDependentReferenceName()` to avoid false positives.

**Mitigation:** WBS-04 concern. The trigger's `getDependentReferenceName()` provides the
information needed for filtering.

### Risk 2: Source-Side Detection for Reference-Attribute Mutations (WBS-08+)

When a reference attribute changes on the target entity, the source-side detection must match
`(entityType, referenceName, attributeName)` — not just `(entityType, attributeName)`.

**Mitigation:** Addressed by the interface extension in Group 2 — `getDependentReferenceName()`
provides the reference name for matching.

### Risk 3: Spread Mapping Expression Complexity

The spread mapping expression (`$.attributes['A']` inside `SpreadAccessStep`) could be more
complex than a simple attribute access (e.g., `$.attributes['A'] + $.attributes['B'] > 10`).
The translator must extract the attribute name from the mapping expression's
`ObjectAccessOperator` chain, which assumes a simple `$.attributes['name']` pattern.

**Mitigation:** If the mapping expression is not a simple `ObjectAccessOperator` with
`$.attributes['name']` or `$.localizedAttributes['name']` chain, throw
`NonTranslatableExpressionException`. Complex spread mapping expressions that cannot be
extracted are rejected at schema load time — the per-entity `evaluate()` path still handles
them for local triggers.

### Resolved: Multiple Reference Names in a Single Expression

An expression accessing `references['x'].attributes['A']` AND `references['y'].attributes['B']`
on the same target entity produces two separate triggers (one per dependency key), each carrying
the same full FilterBy. Consistent with how existing dual-dependency expressions (referencedEntity
+ groupEntity) produce two triggers with the same FilterBy. Implemented via the composite
`DependencyKey` record in Task 4.4.

**Deduplication:** When multiple triggers for the same expression produce duplicate
`IndexMutation` instances after `popMutations`, a deduplication step must be introduced to avoid
processing the same mutation multiple times. This is a downstream concern (WBS-04+) — the trigger
infrastructure produces the mutations, the dispatch layer deduplicates them.

---

## Critical Files

| File | Change Summary |
|------|---------------|
| `evita_engine/.../index/mutation/DependencyType.java` | Add 2 new enum values |
| `evita_engine/.../index/mutation/ExpressionIndexTrigger.java` | Add `getDependentReferenceName()` |
| `evita_engine/.../core/expression/trigger/FacetExpressionTriggerImpl.java` | Add field, update constructors |
| `evita_engine/.../core/expression/trigger/FacetExpressionTriggerFactory.java` | Path classification, reference name extraction, composite key |
| `evita_engine/.../core/expression/query/ExpressionToQueryTranslator.java` | New PathTypes, DataPath extension, nested referenceHaving |
| `evita_test/.../core/expression/trigger/FacetExpressionTriggerImplTest.java` | 4 new tests |
| `evita_test/.../core/expression/trigger/FacetExpressionTriggerFactoryTest.java` | 5 new tests |
| `evita_test/.../core/expression/query/ExpressionToQueryTranslatorTest.java` | 8 new tests |
