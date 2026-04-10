# Conditional Facet Indexing — E2E Test Plan

## Overview

This document defines the end-to-end integration test plan for the Conditional Facet Indexing feature
described in [conditional-facet-indexing.md](conditional-facet-indexing.md). The tests verify the
infrastructure behavior — data access paths, trigger mechanisms, state transitions, fan-out, and
query correctness — without re-testing the expression language itself.

**Test location:**

```
evita_test/evita_functional_tests/src/test/java/io/evitadb/api/functional/indexing/
├── ConditionalFacetIndexingTest.java    (indexing infrastructure + triggers)
└── ConditionalFacetQueryTest.java       (query-side verification)
```

**Total: 33 tests across 2 classes.**

---

## Fixture Strategy: One Schema, Multiple Reference Types

Instead of creating/tearing down different schemas per test, we define **one Product entity schema
with multiple named reference types**, each carrying a different `facetedPartially` expression.
This way:

- Schema is defined once (in a shared setup method called at the start of each test)
- Each test creates only the **1–5 entities** it needs to exercise its specific path
- No bulk data — every entity has a clear, documented purpose

### Entity Types (4 total)

| Entity Type      | Role                                             | Attributes / Config                                     |
|------------------|--------------------------------------------------|---------------------------------------------------------|
| `Product`        | Owner entity — has references with expressions   | `isActive` (Boolean, filterable), `code` (String, filterable), associated data `metadata` (String), hierarchy enabled (supports parent) |
| `Parameter`      | Referenced entity                                | `status` (String, filterable), `priority` (Integer, filterable), reference `tag` to `Tag` with ref attr `weight` (Integer, filterable) |
| `ParameterGroup` | Group entity for references                      | `widgetType` (String, filterable), reference `tag` to `Tag` with ref attr `weight` (Integer, filterable) |
| `Tag`            | Entity for reference-on-referenced-entity tests  | *(no attributes needed — just exists as a target)* |

### Reference Types on Product

There are **10 reference types total**: 7 single-path references (one per data access path) and
3 compound-expression references (for mixed/cross-cutting tests).

#### Single-path references (7)

| Reference Name            | Referenced Entity | Group Entity    | Expression                                                                             | Tests Path                                                    |
|---------------------------|-------------------|-----------------|----------------------------------------------------------------------------------------|---------------------------------------------------------------|
| `paramByEntityAttr`       | `Parameter`       | *(none)*        | `$entity.attributes['isActive'] == true`                                               | `$entity.attributes['x']`                                     |
| `paramByRefAttr`          | `Parameter`       | *(none)*        | `$reference.attributes['priority'] > 0`                                                | `$reference.attributes['x']`                                  |
| `paramByAssocData`        | `Parameter`       | *(none)*        | `$entity.associatedData['metadata'] != null`                                           | `$entity.associatedData['x']`                                 |
| `paramByParent`           | `Parameter`       | *(none)*        | `$entity.parentEntity != null`                                                         | `$entity.parent`                                              |
| `paramByGroupAttr`        | `Parameter`       | `ParameterGroup`| `$reference.groupEntity?.attributes['widgetType'] == 'CHECKBOX'`                       | `$reference.groupEntity?.attributes['x']`                     |
| `paramByRefEntityAttr`    | `Parameter`       | *(none)*        | `$reference.referencedEntity.attributes['status'] == 'ACTIVE'`                         | `$reference.referencedEntity.attributes['x']`                 |
| `paramByRefEntityRefAttr` | `Parameter`       | *(none)*        | `$reference.referencedEntity.references['tag']*.attributes['weight'] > 5`              | `$reference.referencedEntity.references['r']*.attributes['x']`|

Note: `paramByRefAttr` needs a reference attribute `priority` (Integer, filterable) defined on
the reference schema itself (not on the referenced entity).

#### Compound-expression references (3 — for mixed/cross-cutting tests)

| Reference Name               | Referenced Entity | Group Entity    | Expression                                                                                                                          | Used By Test |
|------------------------------|-------------------|-----------------|-------------------------------------------------------------------------------------------------------------------------------------|---|
| `paramByMixedAnd`            | `Parameter`       | `ParameterGroup`| `$reference.groupEntity?.attributes['widgetType'] == 'CHECKBOX' && $entity.attributes['isActive'] == true`                          | `shouldEvaluateMixedExpressionCombiningGroupAndEntityAttributes` |
| `paramByMultiSourceOr`       | `Parameter`       | `ParameterGroup`| `$reference.groupEntity?.attributes['widgetType'] == 'CHECKBOX' \|\| $reference.referencedEntity.attributes['status'] == 'ACTIVE'`  | `shouldEvaluateOrExpressionAcrossMultipleCrossEntitySources` |
| `paramByGroupAttrSecondary`  | `Parameter`       | `ParameterGroup`| `$reference.groupEntity?.attributes['widgetType'] == 'CHECKBOX'`                                                                    | `shouldReevaluateAllReferenceTypesWhenSharedGroupEntityChanges` (second ref type with same group dependency) |

### Entity Counts Per Test

Most tests need **3–5 entities total**:

| Test Category                      | Products | Parameters | ParameterGroups | Tags |
|------------------------------------|----------|------------|-----------------|------|
| Initial indexing (per-path)        | 2        | 1          | 0–1             | 0–1  |
| Local trigger state transitions    | 1        | 1          | 0–1             | 0    |
| Cross-entity referenced entity     | 1        | 1          | 0               | 0–1  |
| Cross-entity group fan-out         | 3–5      | 1–2        | 1               | 0    |
| Late arrival (referenced/group)    | 1        | 1          | 0–1             | 0    |
| Mixed expressions                  | 1–2      | 1          | 1               | 0    |
| Query verification                 | 3        | 2          | 1               | 0    |

**Maximum** across any single test: ~5 Products + 2 Parameters + 1 ParameterGroup + 1 Tag = **9 entities**.

---

## Concrete Schema Setup

### Expression Parsing

Expressions are parsed via `ExpressionFactory.parse(String)` from package
`io.evitadb.api.query.expression` (module `evita_query`). Returns an `Expression` object
(from `io.evitadb.dataType.expression`).

### Builder API for `facetedPartially`

The `ReferenceSchemaEditor` interface (in `evita_api`) provides:

```java
// Sets expression for the default scope (Scope.LIVE)
default T facetedPartially(@Nonnull Expression expression) {
    return facetedPartiallyInScope(Scope.DEFAULT_SCOPE, expression);
}

// Sets expression for a specific scope
T facetedPartiallyInScope(@Nonnull Scope scope, @Nonnull Expression expression);

// Clears expression (reverts to full faceting)
default T nonFacetedPartially() { ... }
T nonFacetedPartially(@Nonnull Scope... inScope);
```

The reference must already be `.faceted()` before calling `.facetedPartially()` — the expression
narrows which faceted entities actually participate.

### Complete `defineConditionalFacetSchema` Implementation

This is the shared schema setup method called at the start of each test. The implementer
should define it as a `private` method with `@Nonnull` parameter and JavaDoc.

```java
/**
 * Defines all entity types and reference schemas needed for conditional facet indexing tests.
 * Called at the beginning of each test's {@code updateCatalog} lambda.
 *
 * @param session the active evitaDB session
 */
private void defineConditionalFacetSchema(@Nonnull EvitaSessionContract session) {
    // 1. Define Tag (simple entity — target for reference-on-referenced-entity tests)
    session.defineEntitySchema("tag").updateVia(session);

    // 2. Define ParameterGroup (group entity)
    session.defineEntitySchema("parameterGroup")
        .withAttribute("widgetType", String.class, AttributeSchemaEditor::filterable)
        .withReferenceToEntity(
            "tag", "tag", Cardinality.ZERO_OR_MORE,
            whichIs -> whichIs
                .indexedForFiltering()
                .withAttribute("weight", Integer.class, AttributeSchemaEditor::filterable)
        )
        .updateVia(session);

    // 3. Define Parameter (referenced entity)
    session.defineEntitySchema("parameter")
        .withAttribute("status", String.class, AttributeSchemaEditor::filterable)
        .withAttribute("priority", Integer.class, AttributeSchemaEditor::filterable)
        .withReferenceToEntity(
            "tag", "tag", Cardinality.ZERO_OR_MORE,
            whichIs -> whichIs
                .indexedForFiltering()
                .withAttribute("weight", Integer.class, AttributeSchemaEditor::filterable)
        )
        .updateVia(session);

    // 4. Define Product (owner entity with all reference types)
    session.defineEntitySchema("product")
        .withHierarchy()  // enables parent for $entity.parent tests
        .withAttribute("isActive", Boolean.class, AttributeSchemaEditor::filterable)
        .withAttribute("code", String.class, AttributeSchemaEditor::filterable)
        .withAssociatedData("metadata", String.class)

        // --- Single-path references ---

        // $entity.attributes['isActive'] path
        .withReferenceToEntity(
            "paramByEntityAttr", "parameter", Cardinality.ZERO_OR_MORE,
            whichIs -> whichIs
                .indexedForFilteringAndPartitioning()
                .faceted()
                .facetedPartially(
                    ExpressionFactory.parse("$entity.attributes['isActive'] == true")
                )
        )

        // $reference.attributes['priority'] path
        .withReferenceToEntity(
            "paramByRefAttr", "parameter", Cardinality.ZERO_OR_MORE,
            whichIs -> whichIs
                .indexedForFilteringAndPartitioning()
                .faceted()
                .withAttribute("priority", Integer.class, AttributeSchemaEditor::filterable)
                .facetedPartially(
                    ExpressionFactory.parse("$reference.attributes['priority'] > 0")
                )
        )

        // $entity.associatedData['metadata'] path
        .withReferenceToEntity(
            "paramByAssocData", "parameter", Cardinality.ZERO_OR_MORE,
            whichIs -> whichIs
                .indexedForFilteringAndPartitioning()
                .faceted()
                .facetedPartially(
                    ExpressionFactory.parse("$entity.associatedData['metadata'] != null")
                )
        )

        // $entity.parentEntity path
        .withReferenceToEntity(
            "paramByParent", "parameter", Cardinality.ZERO_OR_MORE,
            whichIs -> whichIs
                .indexedForFilteringAndPartitioning()
                .faceted()
                .facetedPartially(
                    ExpressionFactory.parse("$entity.parentEntity != null")
                )
        )

        // $reference.groupEntity?.attributes['widgetType'] path
        .withReferenceToEntity(
            "paramByGroupAttr", "parameter", Cardinality.ZERO_OR_MORE,
            whichIs -> whichIs
                .indexedForFilteringAndPartitioning()
                .faceted()
                .withGroupTypeRelatedToEntity("parameterGroup")
                .facetedPartially(
                    ExpressionFactory.parse(
                        "$reference.groupEntity?.attributes['widgetType'] == 'CHECKBOX'"
                    )
                )
        )

        // $reference.referencedEntity.attributes['status'] path
        .withReferenceToEntity(
            "paramByRefEntityAttr", "parameter", Cardinality.ZERO_OR_MORE,
            whichIs -> whichIs
                .indexedForFilteringAndPartitioning()
                .faceted()
                .facetedPartially(
                    ExpressionFactory.parse(
                        "$reference.referencedEntity.attributes['status'] == 'ACTIVE'"
                    )
                )
        )

        // $reference.referencedEntity.references['tag']*.attributes['weight'] path
        .withReferenceToEntity(
            "paramByRefEntityRefAttr", "parameter", Cardinality.ZERO_OR_MORE,
            whichIs -> whichIs
                .indexedForFilteringAndPartitioning()
                .faceted()
                .facetedPartially(
                    ExpressionFactory.parse(
                        "$reference.referencedEntity.references['tag']*.attributes['weight'] > 5"
                    )
                )
        )

        // --- Compound-expression references ---

        // AND of group entity + entity attribute
        .withReferenceToEntity(
            "paramByMixedAnd", "parameter", Cardinality.ZERO_OR_MORE,
            whichIs -> whichIs
                .indexedForFilteringAndPartitioning()
                .faceted()
                .withGroupTypeRelatedToEntity("parameterGroup")
                .facetedPartially(
                    ExpressionFactory.parse(
                        "$reference.groupEntity?.attributes['widgetType'] == 'CHECKBOX'"
                            + " && $entity.attributes['isActive'] == true"
                    )
                )
        )

        // OR of group entity + referenced entity
        .withReferenceToEntity(
            "paramByMultiSourceOr", "parameter", Cardinality.ZERO_OR_MORE,
            whichIs -> whichIs
                .indexedForFilteringAndPartitioning()
                .faceted()
                .withGroupTypeRelatedToEntity("parameterGroup")
                .facetedPartially(
                    ExpressionFactory.parse(
                        "$reference.groupEntity?.attributes['widgetType'] == 'CHECKBOX'"
                            + " || $reference.referencedEntity.attributes['status'] == 'ACTIVE'"
                    )
                )
        )

        // Second group-dependent reference (same expression as paramByGroupAttr)
        .withReferenceToEntity(
            "paramByGroupAttrSecondary", "parameter", Cardinality.ZERO_OR_MORE,
            whichIs -> whichIs
                .indexedForFilteringAndPartitioning()
                .faceted()
                .withGroupTypeRelatedToEntity("parameterGroup")
                .facetedPartially(
                    ExpressionFactory.parse(
                        "$reference.groupEntity?.attributes['widgetType'] == 'CHECKBOX'"
                    )
                )
        )

        .updateVia(session);
}
```

**Important notes for the implementer:**

- The `$entity.parentEntity` expression path — verify the exact accessor path. In the
  expression language, parent access may be `$entity.parentEntity` or `$entity.parent`.
  Check `EntityContractAccessor` for the registered property name. If it's `parent`, the
  expression should be `$entity.parent != null`.
- The `$entity.associatedData[...]` expression path — associated data is NOT filterable/indexed
  in the regular sense, but the expression evaluator accesses it via storage parts directly
  (via Proxycian proxy). The `withAssociatedData("metadata", String.class)` call on the entity
  schema is sufficient — no `.filterable()` needed on associated data.
- All reference types must be `.faceted()` BEFORE calling `.facetedPartially()` — the expression
  narrows within faceted references, not replaces the faceted flag.
- The `.indexedForFilteringAndPartitioning()` is needed for facet support — just
  `.indexedForFiltering()` is not enough for faceted references.
- References that need a group use `.withGroupTypeRelatedToEntity("parameterGroup")`.
- The `paramByRefAttr` reference has its own reference attribute `priority` defined via
  `.withAttribute("priority", Integer.class, AttributeSchemaEditor::filterable)` on the
  reference builder — this is a reference-level attribute, not on the referenced entity.

### Required Imports for Test Classes

```java
import io.evitadb.api.CatalogContract;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;
```

---

## Concrete Assertion Helpers

### Facet Index Navigation Chain

The `EntityIndex` uses `@Delegate(types = FacetIndexContract.class)` so `FacetIndexContract`
methods are directly callable on `EntityIndex`:

```
EntityIndex
  └── getFacetingEntities()                           → Map<String, FacetReferenceIndex>
        └── .get("paramByGroupAttr")                  → FacetReferenceIndex (nullable)
              ├── .getNotGroupedFacets()               → FacetGroupIndex (nullable, for ungrouped)
              └── .getFacetsInGroup(groupId)            → FacetGroupIndex (nullable, for grouped)
                    └── .getFacetIdIndex(facetPK)       → FacetIdIndex (nullable)
                          └── .getRecords()             → Bitmap
                                └── .contains(ownerPK)  → boolean
```

### `assertFacetIndexed` — Full Implementation

```java
/**
 * Asserts that the specified owner entity is present in the facet index for the given
 * reference name and facet primary key.
 *
 * @param collection    the entity collection to inspect
 * @param referenceName the reference schema name
 * @param facetPK       the primary key of the faceted entity (Parameter PK)
 * @param groupPK       the group entity PK (null if ungrouped)
 * @param ownerPK       the primary key of the owner entity (Product PK)
 */
private void assertFacetIndexed(
    @Nonnull EntityCollectionContract collection,
    @Nonnull String referenceName,
    int facetPK,
    @Nullable Integer groupPK,
    int ownerPK
) {
    final EntityIndex globalIndex = IndexingTestSupport.getGlobalIndex(collection);
    assertNotNull(globalIndex, "Global index must exist");

    final FacetReferenceIndex facetRefIndex = globalIndex.getFacetingEntities().get(referenceName);
    assertNotNull(facetRefIndex, "FacetReferenceIndex for '" + referenceName + "' must exist");

    final FacetGroupIndex facetGroupIndex = facetRefIndex.getFacetsInGroup(groupPK);
    assertNotNull(facetGroupIndex, "FacetGroupIndex for group " + groupPK + " must exist");

    final FacetIdIndex facetIdIndex = facetGroupIndex.getFacetIdIndex(facetPK);
    assertNotNull(facetIdIndex, "FacetIdIndex for facet PK " + facetPK + " must exist");

    assertTrue(
        facetIdIndex.getRecords().contains(ownerPK),
        "Owner entity PK " + ownerPK + " should be in facet index for reference '"
            + referenceName + "', facet PK " + facetPK
    );
}
```

### `assertFacetNotIndexed` — Full Implementation

```java
/**
 * Asserts that the specified owner entity is NOT present in the facet index for the given
 * reference name and facet primary key. Handles cases where any level of the index chain
 * may be absent (which also means "not indexed").
 *
 * @param collection    the entity collection to inspect
 * @param referenceName the reference schema name
 * @param facetPK       the primary key of the faceted entity (Parameter PK)
 * @param groupPK       the group entity PK (null if ungrouped)
 * @param ownerPK       the primary key of the owner entity (Product PK)
 */
private void assertFacetNotIndexed(
    @Nonnull EntityCollectionContract collection,
    @Nonnull String referenceName,
    int facetPK,
    @Nullable Integer groupPK,
    int ownerPK
) {
    final EntityIndex globalIndex = IndexingTestSupport.getGlobalIndex(collection);
    if (globalIndex == null) {
        return; // no global index = no facets at all
    }

    final FacetReferenceIndex facetRefIndex = globalIndex.getFacetingEntities().get(referenceName);
    if (facetRefIndex == null) {
        return; // no FacetReferenceIndex for this reference = not indexed
    }

    final FacetGroupIndex facetGroupIndex = facetRefIndex.getFacetsInGroup(groupPK);
    if (facetGroupIndex == null) {
        return; // no FacetGroupIndex for this group = not indexed
    }

    final FacetIdIndex facetIdIndex = facetGroupIndex.getFacetIdIndex(facetPK);
    if (facetIdIndex == null) {
        return; // no FacetIdIndex for this facet PK = not indexed
    }

    assertFalse(
        facetIdIndex.getRecords().contains(ownerPK),
        "Owner entity PK " + ownerPK + " should NOT be in facet index for reference '"
            + referenceName + "', facet PK " + facetPK
    );
}
```

### `assertReferenceStillIndexed` — Full Implementation

```java
/**
 * Asserts that the specified owner entity is still present in the reduced entity index
 * for the given reference, confirming that reference-based filtering still works even
 * when the facet is conditionally excluded.
 *
 * @param collection    the entity collection to inspect
 * @param referenceName the reference schema name
 * @param refPK         the primary key of the referenced entity
 * @param ownerPK       the primary key of the owner entity (Product PK)
 */
private void assertReferenceStillIndexed(
    @Nonnull EntityCollectionContract collection,
    @Nonnull String referenceName,
    int refPK,
    int ownerPK
) {
    final EntityIndex reducedIndex = IndexingTestSupport.getReferencedEntityIndex(
        collection, referenceName, refPK
    );
    assertNotNull(
        reducedIndex,
        "Reduced entity index for reference '" + referenceName + "' PK " + refPK + " must exist"
    );
    assertTrue(
        reducedIndex.getAllPrimaryKeys().contains(ownerPK),
        "Owner entity PK " + ownerPK + " should be in reduced index for reference '"
            + referenceName + "', referenced PK " + refPK
    );
}
```

### `getProductCollection` — Helper to Get Collection Reference

```java
/**
 * Returns the Product entity collection from the current catalog.
 *
 * @return the Product collection
 */
@Nonnull
private EntityCollectionContract getProductCollection() {
    final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
    return catalog.getCollectionForEntity("product").orElseThrow();
}
```

---

## Concrete Test Specifications

### PK Assignment Convention

To make tests readable and predictable, use fixed PK values:

| Entity Type      | PK Range | Specific PKs Used |
|------------------|----------|--------------------|
| `Product`        | 1–10     | 1, 2, 3, 4, 5     |
| `Parameter`      | 1–10     | 1, 2               |
| `ParameterGroup` | 1–5      | 1                  |
| `Tag`            | 1–5      | 1                  |

---

## `ConditionalFacetIndexingTest` — Detailed Test Specifications

### `@Nested InitialIndexingTest`

#### `shouldIndexFacetConditionallyBasedOnEntityAttribute`

**Reference type:** `paramByEntityAttr`
**Expression:** `$entity.attributes['isActive'] == true`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1 (no special attributes needed)
  3. Create Product PK=1: isActive=true, reference paramByEntityAttr → Parameter PK=1
  4. Create Product PK=2: isActive=false, reference paramByEntityAttr → Parameter PK=1

Assert:
  - assertFacetIndexed(productCollection, "paramByEntityAttr", 1, null, 1)
  - assertFacetNotIndexed(productCollection, "paramByEntityAttr", 1, null, 2)
  - assertReferenceStillIndexed(productCollection, "paramByEntityAttr", 1, 2)
```

#### `shouldIndexFacetConditionallyBasedOnReferenceAttribute`

**Reference type:** `paramByRefAttr`
**Expression:** `$reference.attributes['priority'] > 0`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1
  3. Create Product PK=1: reference paramByRefAttr → Parameter PK=1,
     with reference attribute priority=5
  4. Create Product PK=2: reference paramByRefAttr → Parameter PK=1,
     with reference attribute priority=-1

Assert:
  - assertFacetIndexed(productCollection, "paramByRefAttr", 1, null, 1)
  - assertFacetNotIndexed(productCollection, "paramByRefAttr", 1, null, 2)
  - assertReferenceStillIndexed(productCollection, "paramByRefAttr", 1, 2)
```

#### `shouldIndexFacetConditionallyBasedOnAssociatedData`

**Reference type:** `paramByAssocData`
**Expression:** `$entity.associatedData['metadata'] != null`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1
  3. Create Product PK=1: associatedData metadata="some-value",
     reference paramByAssocData → Parameter PK=1
  4. Create Product PK=2: NO associated data metadata,
     reference paramByAssocData → Parameter PK=1

Assert:
  - assertFacetIndexed(productCollection, "paramByAssocData", 1, null, 1)
  - assertFacetNotIndexed(productCollection, "paramByAssocData", 1, null, 2)
  - assertReferenceStillIndexed(productCollection, "paramByAssocData", 1, 2)
```

#### `shouldIndexFacetConditionallyBasedOnEntityParent`

**Reference type:** `paramByParent`
**Expression:** `$entity.parentEntity != null`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1
  3. Create Product PK=1 (as hierarchy root — no parent itself, used as parent)
  4. Create Product PK=2: parent=1, reference paramByParent → Parameter PK=1
  5. Create Product PK=3: NO parent, reference paramByParent → Parameter PK=1

Assert:
  - assertFacetIndexed(productCollection, "paramByParent", 1, null, 2)
  - assertFacetNotIndexed(productCollection, "paramByParent", 1, null, 3)
  - assertReferenceStillIndexed(productCollection, "paramByParent", 1, 3)
```

Note: Product PK=1 serves as the parent node. Products PK=2 and PK=3 are the test subjects.

#### `shouldIndexFacetConditionallyBasedOnGroupEntityAttribute`

**Reference type:** `paramByGroupAttr`
**Expression:** `$reference.groupEntity?.attributes['widgetType'] == 'CHECKBOX'`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create ParameterGroup PK=1: widgetType='CHECKBOX'
  3. Create Parameter PK=1
  4. Create Product PK=1: reference paramByGroupAttr → Parameter PK=1,
     group=ParameterGroup PK=1
  5. Create ParameterGroup PK=2: widgetType='RADIO' (non-matching)
     (alternatively: just don't set group on Product PK=2)
  6. Create Product PK=2: reference paramByGroupAttr → Parameter PK=1,
     group=ParameterGroup PK=2

Assert:
  - assertFacetIndexed(productCollection, "paramByGroupAttr", 1, 1, 1)     // group PK=1
  - assertFacetNotIndexed(productCollection, "paramByGroupAttr", 1, 2, 2)  // group PK=2
  - assertReferenceStillIndexed(productCollection, "paramByGroupAttr", 1, 2)
```

Note: `assertFacetIndexed` needs the `groupPK` parameter — 1 for the first product, 2 for the
second.

#### `shouldIndexFacetConditionallyBasedOnReferencedEntityAttribute`

**Reference type:** `paramByRefEntityAttr`
**Expression:** `$reference.referencedEntity.attributes['status'] == 'ACTIVE'`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1: status='ACTIVE'
  3. Create Parameter PK=2: status='INACTIVE'
  4. Create Product PK=1: reference paramByRefEntityAttr → Parameter PK=1
  5. Create Product PK=2: reference paramByRefEntityAttr → Parameter PK=2

Assert:
  - assertFacetIndexed(productCollection, "paramByRefEntityAttr", 1, null, 1)
  - assertFacetNotIndexed(productCollection, "paramByRefEntityAttr", 2, null, 2)
  - assertReferenceStillIndexed(productCollection, "paramByRefEntityAttr", 2, 2)
```

#### `shouldIndexFacetConditionallyBasedOnReferencedEntityReferenceAttribute`

**Reference type:** `paramByRefEntityRefAttr`
**Expression:** `$reference.referencedEntity.references['tag']*.attributes['weight'] > 5`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Tag PK=1
  3. Create Parameter PK=1: reference tag → Tag PK=1, ref attr weight=10
  4. Create Parameter PK=2: reference tag → Tag PK=1, ref attr weight=2
  5. Create Product PK=1: reference paramByRefEntityRefAttr → Parameter PK=1
  6. Create Product PK=2: reference paramByRefEntityRefAttr → Parameter PK=2

Assert:
  - assertFacetIndexed(productCollection, "paramByRefEntityRefAttr", 1, null, 1)
  - assertFacetNotIndexed(productCollection, "paramByRefEntityRefAttr", 2, null, 2)
  - assertReferenceStillIndexed(productCollection, "paramByRefEntityRefAttr", 2, 2)
```

---

### `@Nested LocalTriggerTest`

#### `shouldToggleFacetOnEntityAttributeChange`

**Reference type:** `paramByEntityAttr`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1
  3. Create Product PK=1: isActive=false, reference paramByEntityAttr → Parameter PK=1

Assert FALSE state:
  - assertFacetNotIndexed(productCollection, "paramByEntityAttr", 1, null, 1)

Mutate to TRUE:
  4. Load Product PK=1, openForWrite(), setAttribute("isActive", true), upsertVia(session)

Assert TRUE state:
  - assertFacetIndexed(productCollection, "paramByEntityAttr", 1, null, 1)

Mutate back to FALSE:
  5. Load Product PK=1, openForWrite(), setAttribute("isActive", false), upsertVia(session)

Assert FALSE state again:
  - assertFacetNotIndexed(productCollection, "paramByEntityAttr", 1, null, 1)
```

#### `shouldToggleFacetOnReferenceAttributeChange`

**Reference type:** `paramByRefAttr`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1
  3. Create Product PK=1: reference paramByRefAttr → Parameter PK=1,
     ref attr priority=-1

Assert FALSE → Mutate priority=5 → Assert TRUE → Mutate priority=-1 → Assert FALSE
```

#### `shouldToggleFacetOnAssociatedDataChange`

**Reference type:** `paramByAssocData`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1
  3. Create Product PK=1: NO associated data, reference paramByAssocData → Parameter PK=1

Assert FALSE → setAssociatedData("metadata", "value") → Assert TRUE
→ removeAssociatedData("metadata") → Assert FALSE
```

#### `shouldToggleFacetOnParentChange`

**Reference type:** `paramByParent`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1
  3. Create Product PK=1 (root — used as parent target)
  4. Create Product PK=2: NO parent, reference paramByParent → Parameter PK=1

Assert FALSE → setParent(1) on Product PK=2 → Assert TRUE
→ removeParent() on Product PK=2 → Assert FALSE
```

#### `shouldToggleFacetOnGroupAssignmentChange`

**Reference type:** `paramByGroupAttr`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create ParameterGroup PK=1: widgetType='CHECKBOX'
  3. Create Parameter PK=1
  4. Create Product PK=1: reference paramByGroupAttr → Parameter PK=1, NO group

Assert FALSE (no group → null-safe ?. returns null → expression false)
→ setGroup(ParameterGroup, 1) on reference → Assert TRUE (group PK=1, widgetType=CHECKBOX)
→ removeGroup on reference → Assert FALSE
```

Note: `setGroup` and `removeGroup` are mutations on the reference, done via
`openForWrite().setReference("paramByGroupAttr", 1, ref -> ref.setGroup("parameterGroup", 1))`.

#### `shouldNotReevaluateWhenIrrelevantAttributeChanges`

**Reference type:** `paramByEntityAttr`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1
  3. Create Product PK=1: isActive=true, code="ABC",
     reference paramByEntityAttr → Parameter PK=1

Assert TRUE:
  - assertFacetIndexed(productCollection, "paramByEntityAttr", 1, null, 1)

Mutate irrelevant attribute:
  4. Load Product PK=1, openForWrite(), setAttribute("code", "XYZ"), upsertVia(session)

Assert still TRUE (no change):
  - assertFacetIndexed(productCollection, "paramByEntityAttr", 1, null, 1)
```

---

### `@Nested CrossEntityReferencedEntityTriggerTest`

#### `shouldToggleFacetWhenReferencedEntityAttributeChanges`

**Reference type:** `paramByRefEntityAttr`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1: status='ACTIVE'
  3. Create Product PK=1: reference paramByRefEntityAttr → Parameter PK=1

Assert TRUE:
  - assertFacetIndexed(productCollection, "paramByRefEntityAttr", 1, null, 1)

Cross-entity mutation:
  4. Load Parameter PK=1, openForWrite(), setAttribute("status", "INACTIVE"),
     upsertVia(session)

Assert FALSE:
  - assertFacetNotIndexed(productCollection, "paramByRefEntityAttr", 1, null, 1)

Restore:
  5. Load Parameter PK=1, openForWrite(), setAttribute("status", "ACTIVE"),
     upsertVia(session)

Assert TRUE again:
  - assertFacetIndexed(productCollection, "paramByRefEntityAttr", 1, null, 1)
```

#### `shouldToggleFacetWhenReferencedEntityReferenceAttributeChanges`

**Reference type:** `paramByRefEntityRefAttr`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Tag PK=1
  3. Create Parameter PK=1: reference tag → Tag PK=1, ref attr weight=10
  4. Create Product PK=1: reference paramByRefEntityRefAttr → Parameter PK=1

Assert TRUE → Mutate Parameter PK=1's tag ref attr weight=2 → Assert FALSE
→ Mutate weight=10 → Assert TRUE
```

#### `shouldRemoveFacetWhenReferencedEntityIsRemoved`

**Reference type:** `paramByRefEntityAttr`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1: status='ACTIVE'
  3. Create Product PK=1: reference paramByRefEntityAttr → Parameter PK=1

Assert TRUE:
  - assertFacetIndexed(productCollection, "paramByRefEntityAttr", 1, null, 1)

Delete referenced entity:
  4. session.deleteEntity("parameter", 1)

Assert FALSE:
  - assertFacetNotIndexed(productCollection, "paramByRefEntityAttr", 1, null, 1)
```

#### `shouldIndexFacetWhenReferencedEntityIsInsertedAfterReferencingEntity`

**Reference type:** `paramByRefEntityAttr`

This tests evitaDB's eventually consistent behavior — you can reference an entity that
doesn't exist yet.

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Product PK=1: reference paramByRefEntityAttr → Parameter PK=1
     (Parameter PK=1 does NOT exist yet!)

Assert FALSE:
  - assertFacetNotIndexed(productCollection, "paramByRefEntityAttr", 1, null, 1)

Late arrival:
  3. Create Parameter PK=1: status='ACTIVE'

Assert TRUE:
  - assertFacetIndexed(productCollection, "paramByRefEntityAttr", 1, null, 1)
```

---

### `@Nested CrossEntityGroupEntityTriggerTest`

#### `shouldCascadeToAllOwnerEntitiesWhenGroupEntityAttributeChanges`

**Reference type:** `paramByGroupAttr`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create ParameterGroup PK=1: widgetType='CHECKBOX'
  3. Create Parameter PK=1
  4. Create Product PK=1: reference paramByGroupAttr → Parameter PK=1,
     group=ParameterGroup PK=1
  5. Create Product PK=2: reference paramByGroupAttr → Parameter PK=1,
     group=ParameterGroup PK=1
  6. Create Product PK=3: reference paramByGroupAttr → Parameter PK=1,
     group=ParameterGroup PK=1

Assert ALL TRUE:
  - assertFacetIndexed(productCollection, "paramByGroupAttr", 1, 1, 1)
  - assertFacetIndexed(productCollection, "paramByGroupAttr", 1, 1, 2)
  - assertFacetIndexed(productCollection, "paramByGroupAttr", 1, 1, 3)

Cross-entity mutation (fan-out):
  7. Load ParameterGroup PK=1, setAttribute("widgetType", "RADIO"), upsertVia(session)

Assert ALL FALSE:
  - assertFacetNotIndexed(productCollection, "paramByGroupAttr", 1, 1, 1)
  - assertFacetNotIndexed(productCollection, "paramByGroupAttr", 1, 1, 2)
  - assertFacetNotIndexed(productCollection, "paramByGroupAttr", 1, 1, 3)

Restore:
  8. Load ParameterGroup PK=1, setAttribute("widgetType", "CHECKBOX"), upsertVia(session)

Assert ALL TRUE again:
  - assertFacetIndexed(productCollection, "paramByGroupAttr", 1, 1, 1)
  - assertFacetIndexed(productCollection, "paramByGroupAttr", 1, 1, 2)
  - assertFacetIndexed(productCollection, "paramByGroupAttr", 1, 1, 3)
```

#### `shouldToggleFacetWhenGroupEntityReferenceAttributeChanges`

This test requires a reference type with expression accessing
`$reference.groupEntity?.references['tag']*.attributes['weight'] > 5`. Since this path is not
among the 10 defined reference types, either:

- **Option A:** Add an 11th reference type `paramByGroupEntityRefAttr` to the schema setup with
  this expression.
- **Option B:** Reuse this test to verify via the `paramByGroupAttr` path and skip this
  specific sub-path.

**Recommendation:** Add the 11th reference type. The expression would be:
`$reference.groupEntity?.references['tag']*.attributes['weight'] > 5`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Tag PK=1
  3. Create ParameterGroup PK=1: reference tag → Tag PK=1, ref attr weight=10
  4. Create Parameter PK=1
  5. Create Product PK=1: reference paramByGroupEntityRefAttr → Parameter PK=1,
     group=ParameterGroup PK=1

Assert TRUE → Mutate ParameterGroup PK=1's tag ref attr weight=2 → Assert FALSE
→ Mutate weight=10 → Assert TRUE
```

#### `shouldRemoveFacetWhenGroupEntityIsRemoved`

**Reference type:** `paramByGroupAttr`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create ParameterGroup PK=1: widgetType='CHECKBOX'
  3. Create Parameter PK=1
  4. Create Product PK=1: reference paramByGroupAttr → Parameter PK=1,
     group=ParameterGroup PK=1
  5. Create Product PK=2: reference paramByGroupAttr → Parameter PK=1,
     group=ParameterGroup PK=1

Assert TRUE:
  - assertFacetIndexed for both Products

Delete group entity:
  6. session.deleteEntity("parameterGroup", 1)

Assert FALSE:
  - assertFacetNotIndexed for both Products
```

#### `shouldIndexFacetWhenGroupEntityIsInsertedAfterReferencingEntity`

**Reference type:** `paramByGroupAttr`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1
  3. Create Product PK=1: reference paramByGroupAttr → Parameter PK=1,
     group=ParameterGroup PK=1 (ParameterGroup PK=1 does NOT exist yet!)

Assert FALSE:
  - assertFacetNotIndexed(productCollection, "paramByGroupAttr", 1, 1, 1)

Late arrival:
  4. Create ParameterGroup PK=1: widgetType='CHECKBOX'

Assert TRUE:
  - assertFacetIndexed(productCollection, "paramByGroupAttr", 1, 1, 1)
```

---

### `@Nested MixedAndCrossCuttingTest`

#### `shouldEvaluateMixedExpressionCombiningGroupAndEntityAttributes`

**Reference type:** `paramByMixedAnd`
**Expression:** `$reference.groupEntity?.attributes['widgetType'] == 'CHECKBOX' && $entity.attributes['isActive'] == true`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create ParameterGroup PK=1: widgetType='CHECKBOX'
  3. Create Parameter PK=1
  4. Create Product PK=1: isActive=true, reference paramByMixedAnd → Parameter PK=1,
     group=ParameterGroup PK=1

Assert TRUE (both conditions hold):
  - assertFacetIndexed(productCollection, "paramByMixedAnd", 1, 1, 1)

Toggle entity attribute to false:
  5. Load Product PK=1, setAttribute("isActive", false), upsertVia(session)

Assert FALSE (local part broke):
  - assertFacetNotIndexed(productCollection, "paramByMixedAnd", 1, 1, 1)

Restore entity attribute, break group attribute:
  6. Load Product PK=1, setAttribute("isActive", true), upsertVia(session)
  7. Load ParameterGroup PK=1, setAttribute("widgetType", "RADIO"), upsertVia(session)

Assert FALSE (cross-entity part broke):
  - assertFacetNotIndexed(productCollection, "paramByMixedAnd", 1, 1, 1)

Restore group attribute:
  8. Load ParameterGroup PK=1, setAttribute("widgetType", "CHECKBOX"), upsertVia(session)

Assert TRUE (both restored):
  - assertFacetIndexed(productCollection, "paramByMixedAnd", 1, 1, 1)
```

#### `shouldEvaluateOrExpressionAcrossMultipleCrossEntitySources`

**Reference type:** `paramByMultiSourceOr`
**Expression:** `$reference.groupEntity?.attributes['widgetType'] == 'CHECKBOX' || $reference.referencedEntity.attributes['status'] == 'ACTIVE'`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create ParameterGroup PK=1: widgetType='CHECKBOX'
  3. Create Parameter PK=1: status='ACTIVE'
  4. Create Product PK=1: reference paramByMultiSourceOr → Parameter PK=1,
     group=ParameterGroup PK=1

Assert TRUE (both branches true):
  - assertFacetIndexed(productCollection, "paramByMultiSourceOr", 1, 1, 1)

Break group branch only:
  5. Load ParameterGroup PK=1, setAttribute("widgetType", "RADIO"), upsertVia(session)

Assert STILL TRUE (referenced entity branch still true):
  - assertFacetIndexed(productCollection, "paramByMultiSourceOr", 1, 1, 1)

Also break referenced entity branch:
  6. Load Parameter PK=1, setAttribute("status", "INACTIVE"), upsertVia(session)

Assert FALSE (both branches false):
  - assertFacetNotIndexed(productCollection, "paramByMultiSourceOr", 1, 1, 1)

Restore one branch:
  7. Load Parameter PK=1, setAttribute("status", "ACTIVE"), upsertVia(session)

Assert TRUE again (one branch is enough for OR):
  - assertFacetIndexed(productCollection, "paramByMultiSourceOr", 1, 1, 1)
```

#### `shouldHandleNullSafeGroupEntityAccess`

**Reference type:** `paramByGroupAttr`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1
  3. Create Product PK=1: reference paramByGroupAttr → Parameter PK=1,
     group=ParameterGroup PK=1 (ParameterGroup PK=1 does NOT exist!)

Assert FALSE (null-safe ?. returns null → comparison false):
  - assertFacetNotIndexed(productCollection, "paramByGroupAttr", 1, 1, 1)

Create the group entity:
  4. Create ParameterGroup PK=1: widgetType='CHECKBOX'

Assert TRUE:
  - assertFacetIndexed(productCollection, "paramByGroupAttr", 1, 1, 1)

Delete the group entity:
  5. session.deleteEntity("parameterGroup", 1)

Assert FALSE again:
  - assertFacetNotIndexed(productCollection, "paramByGroupAttr", 1, 1, 1)
```

#### `shouldReevaluateAllReferenceTypesWhenSharedGroupEntityChanges`

**Reference types:** `paramByGroupAttr` AND `paramByGroupAttrSecondary`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create ParameterGroup PK=1: widgetType='CHECKBOX'
  3. Create Parameter PK=1
  4. Create Product PK=1:
     - reference paramByGroupAttr → Parameter PK=1, group=ParameterGroup PK=1
     - reference paramByGroupAttrSecondary → Parameter PK=1, group=ParameterGroup PK=1

Assert BOTH TRUE:
  - assertFacetIndexed(productCollection, "paramByGroupAttr", 1, 1, 1)
  - assertFacetIndexed(productCollection, "paramByGroupAttrSecondary", 1, 1, 1)

Cross-entity mutation:
  5. Load ParameterGroup PK=1, setAttribute("widgetType", "RADIO"), upsertVia(session)

Assert BOTH FALSE:
  - assertFacetNotIndexed(productCollection, "paramByGroupAttr", 1, 1, 1)
  - assertFacetNotIndexed(productCollection, "paramByGroupAttrSecondary", 1, 1, 1)
```

#### `shouldInheritFacetedPartiallyFromSourceSchemaViaReflectedReference`

This test requires setting up a reflected reference. The pattern is:

- Entity type A has a reference `ref` to entity type B with `facetedPartially` expression
- Entity type B has a reflected reference `reflectedRef` pointing back to A's `ref`
- The reflected reference inherits `facetedPartially` from the source

```
Setup (schema):
  1. Define entity type "category" with attribute "priority" (Integer, filterable)
  2. Define entity type "item" with:
     - reference "category" → "category", faceted, facetedPartially(
         ExpressionFactory.parse("$reference.referencedEntity.attributes['priority'] > 0")
       )
  3. Define entity type "category" UPDATED with:
     - reflected reference "items" reflecting "item"/"category"
       (inherits faceted + facetedPartially from source)

Setup (data):
  4. Create Category PK=1: priority=5
  5. Create Category PK=2: priority=-1
  6. Create Item PK=1: reference category → Category PK=1
  7. Create Item PK=2: reference category → Category PK=2

Assert on Item collection:
  - assertFacetIndexed(itemCollection, "category", 1, null, 1)    // priority=5 > 0
  - assertFacetNotIndexed(itemCollection, "category", 2, null, 2) // priority=-1 ≤ 0

Assert inherited expression on Category collection (via reflected reference):
  - The reflected reference "items" on Category should also apply the same
    facetedPartially expression, controlling facet indexing from Category's perspective
```

Note: The exact behavior of reflected references with `facetedPartially` needs verification
during implementation — confirm that the reflected reference in "category" inherits the
expression and applies it when indexing "item" PKs as facets from category's perspective.

#### `shouldNotTriggerReevaluationWhenAttributeValueDoesNotActuallyChange`

**Reference type:** `paramByRefEntityAttr`

```
Setup:
  1. defineConditionalFacetSchema(session)
  2. Create Parameter PK=1: status='ACTIVE'
  3. Create Product PK=1: reference paramByRefEntityAttr → Parameter PK=1

Assert TRUE:
  - assertFacetIndexed(productCollection, "paramByRefEntityAttr", 1, null, 1)

No-change mutation (same value):
  4. Load Parameter PK=1, openForWrite(), setAttribute("status", "ACTIVE"),
     upsertVia(session)

Assert STILL TRUE (no reevaluation should have occurred — old == new optimization):
  - assertFacetIndexed(productCollection, "paramByRefEntityAttr", 1, null, 1)
```

Note: This test verifies the optimization where `popIndexImplicitMutations()` checks
old ≠ new before creating `ReevaluateFacetExpressionMutation`. The test itself cannot
directly assert that no trigger fired (that's an internal optimization), but it confirms
the end state is correct and no spurious state changes occurred.

#### `shouldRejectNonTranslatableExpressionAtSchemaTime`

```
Test:
  1. evita.updateCatalog(TEST_CATALOG, session -> {
       session.defineEntitySchema("parameter").updateVia(session);
       session.defineEntitySchema("testEntity")
           .withReferenceToEntity(
               "ref", "parameter", Cardinality.ZERO_OR_MORE,
               whichIs -> whichIs
                   .indexedForFilteringAndPartitioning()
                   .faceted()
                   .facetedPartially(
                       ExpressionFactory.parse(
                           "$reference.referencedEntity.attributes['type']"
                               + " == $entity.attributes['category']"
                       )
                   )
           )
           .updateVia(session);
     });
  2. Expect exception (NonTranslatableExpressionException or similar)
     wrapping the assertThrows around the updateCatalog call
```

The exception class is `NonTranslatableExpressionException` from package
`io.evitadb.core.expression.query`.

---

## `ConditionalFacetQueryTest` — Detailed Test Specifications

This class uses a simpler schema — only the `paramByGroupAttr` reference type is needed,
since query tests focus on facet summary / facet filtering behavior, not on exercising
all data access paths.

### Schema and Fixture

```
Schema:
  - ParameterGroup with attribute "widgetType" (String, filterable)
  - Parameter (plain entity)
  - Product with reference "paramByGroupAttr" → Parameter,
    group=ParameterGroup, faceted, facetedPartially(
      "$reference.groupEntity?.attributes['widgetType'] == 'CHECKBOX'"
    )

Base fixture (created by each test as needed):
  - ParameterGroup PK=1: widgetType='CHECKBOX'
  - Parameter PK=1
  - Parameter PK=2
  - Product PK=1: reference paramByGroupAttr → Parameter PK=1, group=PG PK=1  (faceted)
  - Product PK=2: reference paramByGroupAttr → Parameter PK=1, group=PG PK=1  (faceted)
  - Product PK=3: reference paramByGroupAttr → Parameter PK=2, group=PG PK=1  (faceted)
```

Note: All products start in faceted state. Tests then selectively break the expression.

### `shouldReturnCorrectFacetSummaryExcludingConditionallyExcludedFacets`

```
Setup:
  1. Create base fixture (all 3 Products faceted)
  2. Create ParameterGroup PK=2: widgetType='RADIO' (non-matching)
  3. Create Product PK=4: reference paramByGroupAttr → Parameter PK=1,
     group=ParameterGroup PK=2 (NOT faceted — group doesn't match)

Query:
  session.query(
      query(
          collection("product"),
          require(facetSummaryOfReference("paramByGroupAttr"))
      ),
      EntityReferenceContract.class
  )

Assert:
  - FacetSummary for "paramByGroupAttr" should show:
    - Parameter PK=1: count=2 (Products 1, 2 — NOT Product 4 which is in non-matching group)
    - Parameter PK=2: count=1 (Product 3)
```

### `shouldFilterByFacetCorrectlyWhenSomeFacetsConditionallyExcluded`

```
Setup:
  1. Create base fixture (Products 1, 2, 3 faceted via PG PK=1)
  2. Create ParameterGroup PK=2: widgetType='RADIO'
  3. Create Product PK=4: ref → Parameter PK=1, group=PG PK=2 (NOT faceted)

Query:
  session.query(
      query(
          collection("product"),
          filterBy(
              userFilter(
                  facetInSet("paramByGroupAttr", 1)  // Parameter PK=1
              )
          )
      ),
      EntityReferenceContract.class
  )

Assert:
  - Result contains Product PK=1 and PK=2 (faceted for Parameter 1 in matching group)
  - Result does NOT contain Product PK=4 (not faceted despite referencing Parameter 1)
```

### `shouldReturnUpdatedFacetSummaryAfterCrossEntityTriggerChangesIndexState`

```
Setup:
  1. Create base fixture (all 3 Products faceted via PG PK=1 with CHECKBOX)

Assert initial state:
  - FacetSummary shows counts for Parameter PK=1 and PK=2

Cross-entity mutation:
  2. Load ParameterGroup PK=1, setAttribute("widgetType", "RADIO"), upsertVia(session)

Query again:
  - FacetSummary for "paramByGroupAttr" should be empty (no facets indexed)
    OR the reference type should not appear in the summary at all
```

### `shouldStillFilterByReferenceWhenFacetConditionallyExcluded`

```
Setup:
  1. Create base fixture
  2. Load ParameterGroup PK=1, setAttribute("widgetType", "RADIO"), upsertVia(session)
     (breaks expression → all facets removed)

Query with referenceHaving (NOT facet-based):
  session.query(
      query(
          collection("product"),
          filterBy(
              referenceHaving("paramByGroupAttr", entityPrimaryKeyInSet(1))
          )
      ),
      EntityReferenceContract.class
  )

Assert:
  - All 3 Products still returned (reference indexing is independent of facet indexing)
```

### `shouldReturnCorrectFacetCountsAfterFanOutGroupEntityChange`

```
Setup:
  1. Create ParameterGroup PK=1: widgetType='CHECKBOX'
  2. Create Parameter PK=1
  3. Create Products PK=1..5: each with reference paramByGroupAttr → Parameter PK=1,
     group=ParameterGroup PK=1

Assert initial:
  - FacetSummary: Parameter PK=1 count=5

Cross-entity mutation (fan-out):
  4. Load ParameterGroup PK=1, setAttribute("widgetType", "RADIO"), upsertVia(session)

Assert after:
  - FacetSummary: Parameter PK=1 count=0 (or absent)

Restore:
  5. Load ParameterGroup PK=1, setAttribute("widgetType", "CHECKBOX"), upsertVia(session)

Assert restored:
  - FacetSummary: Parameter PK=1 count=5
```

---

## Test Count Summary

| Class | Nested Group | Tests |
|---|---|---|
| `ConditionalFacetIndexingTest` | `InitialIndexingTest` | 7 |
| | `LocalTriggerTest` | 6 |
| | `CrossEntityReferencedEntityTriggerTest` | 4 |
| | `CrossEntityGroupEntityTriggerTest` | 4 |
| | `MixedAndCrossCuttingTest` | 7 |
| `ConditionalFacetQueryTest` | *(flat)* | 5 |
| **Total** | | **33** |

---

## Design Principles

1. **Surgical setup** — 1–5 entities per test, no bulk data. Every entity has a documented purpose.
2. **One schema, many paths** — 10 reference types with different expressions (7 single-path +
   3 compound) avoid schema churn while covering all data access paths from the specification.
3. **Three reusable assertion helpers** — `assertFacetIndexed`, `assertFacetNotIndexed`,
   `assertReferenceStillIndexed` encapsulate multi-level index traversal.
4. **Full cycles, not half-tests** — state transition tests do FALSE→TRUE→FALSE in one method,
   covering both add and remove without duplicating setup.
5. **No expression language re-testing** — tests verify infrastructure (paths, triggers, fan-out),
   not expression operators (==, >, !=). The expression language has its own test suite.
6. **Eventually consistent late arrival** — dedicated tests verify that creating the
   referenced/group entity AFTER the referencing entity correctly triggers expression evaluation.
7. **Fixed PK convention** — Products use PKs 1–10, Parameters 1–10, ParameterGroups 1–5,
   Tags 1–5. Makes test specifications unambiguous and easy to trace.

## Open Questions for Implementer

1. **Expression path for parent access** — verify whether the expression language uses
   `$entity.parentEntity` or `$entity.parent`. Check `EntityContractAccessor` for the
   registered property name.
2. **`paramByGroupEntityRefAttr` reference type** — the `shouldToggleFacetWhenGroupEntityReferenceAttributeChanges`
   test needs an 11th reference type with expression
   `$reference.groupEntity?.references['tag']*.attributes['weight'] > 5`. Add this to
   `defineConditionalFacetSchema` if this test is kept.
3. **Reflected reference behavior** — the `shouldInheritFacetedPartiallyFromSourceSchemaViaReflectedReference`
   test needs verification that reflected references actually inherit and apply `facetedPartially`
   from the source schema. This test may need a separate schema setup (not the shared one).
4. **Transaction boundaries** — verify whether cross-entity triggers fire within the same
   `updateCatalog` lambda or require separate transactions. All test specs assume single-lambda
   execution.
