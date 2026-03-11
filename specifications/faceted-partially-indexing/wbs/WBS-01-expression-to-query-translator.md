# WBS-01: `ExpressionToQueryTranslator` — Expression to FilterBy Translation

> **Parent document:** [Conditional Facet Indexing — Problem Analysis](../conditional-facet-indexing.md)

## Objective

Implement the `ExpressionToQueryTranslator` component that translates a `facetedPartially` expression (parsed expression tree) into an evitaDB `FilterBy` constraint tree at schema load time. The translator produces a reusable `FilterBy` template stored in the `ExpressionIndexTrigger`, enabling index-based evaluation of cross-entity triggers without per-entity expression interpretation.

## Scope

### In Scope

- Translating all supported expression AST node types to their corresponding evitaDB `FilterBy` constraints
- Mapping boolean operators (`&&`, `||`, `!`) to `and()`, `or()`, `not()` with flattening of consecutive same-type operators
- Mapping entity attribute access paths (`$entity.attributes['x'] op v`) to `attributeEquals`, `attributeGreaterThan`, `attributeGreaterThanEquals`, `attributeLessThan`, `attributeLessThanEquals`, etc.
- Mapping attribute null-check patterns (`$entity.attributes['x'] == null` / `!= null`) to `attributeIsNull` / `attributeIsNotNull`
- Mapping reference attribute paths (`$reference.attributes['x'] op v`) to `referenceHaving("refName", attributeEquals(...))` constraints
- Mapping group entity attribute paths (`$reference.groupEntity?.attributes['x'] op v`) to `referenceHaving("refName", groupHaving(attributeEquals(...)))` constraints
- Mapping referenced entity attribute paths (`$reference.referencedEntity.attributes['x'] op v`) to `referenceHaving("refName", entityHaving(attributeEquals(...)))` constraints
- Mapping parent existence check (`$entity.parent != null`) to `hierarchyWithinRootSelf()`
- Mapping parent absence check (`$entity.parent == null`) to `not(hierarchyWithinRootSelf())`
- Detecting and rejecting non-translatable expressions at schema time with clear error messages
- Returning a `FilterBy` constraint tree that can be stored in `ExpressionIndexTrigger.getFilterByConstraint()`

### Out of Scope

- **Trigger registration and lifecycle** — building `FacetExpressionTrigger` instances and populating `CatalogExpressionTriggerRegistry` (separate WBS task)
- **FilterBy parameterization at trigger time** — injecting `groupHaving(entityPrimaryKeyInSet(mutatedPK))` / `entityHaving(entityPrimaryKeyInSet(mutatedPK))` scoping clauses (handled by `ReevaluateFacetExpressionExecutor`, separate WBS task)
- **FilterBy evaluation against indexes** — executing the parameterized query via `IndexMutationTarget.evaluateFilter()` (separate WBS task)
- **Per-entity expression evaluation** — the `evaluate()` path using Proxycian proxies for local triggers (separate WBS task)
- **`AccessedDataFinder` path analysis** — static analysis of expression AST to discover accessed data paths and classify dependencies (already exists / separate WBS task)
- **Schema mutation handling** — reacting to schema changes that add/modify/remove `facetedPartially` expressions (separate WBS task)
- **Histogram expression translation** — while the architecture is designed to be extensible to `HistogramExpressionTrigger`, the histogram use case is deferred to a follow-up assignment
- **`$reference.referencedPrimaryKey` translation** — this path is valid in the expression language (parent analysis lists it as a data source) but the translator does not map it to a FilterBy constraint. PK-based scoping is handled at trigger time by the executor (injecting `entityPrimaryKeyInSet(...)` clauses). If an expression contains `$reference.referencedPrimaryKey` in a comparison, the translator should reject it with a clear error explaining that reference PK comparisons are handled via executor scoping, not FilterBy translation

## Dependencies

### Depends On

- **Expression parsing infrastructure** — the expression must already be parsed into an AST (`ExpressionNode` tree) before `ExpressionToQueryTranslator` can process it. This is existing functionality in the expression language module.
- **`AccessedDataFinder`** — the translator may collaborate with `AccessedDataFinder` for dynamic attribute path detection (paths like `$entity.attributes[someVariable]` where the attribute name is a runtime expression). `AccessedDataFinder` determines which specific attributes are accessed; if it cannot resolve a path statically, the translator rejects the expression. The translator itself focuses on the AST-to-FilterBy mapping.
- **evitaDB query constraint model** — the `FilterBy`, `attributeEquals`, `referenceHaving`, `groupHaving`, `entityHaving`, `and`, `or`, `not`, `hierarchyWithinRootSelf`, `entityPrimaryKeyInSet`, and related constraint classes must exist. These are part of the existing evitaDB query API.

### Depended On By

- **Trigger building (schema load time)** — when `EntityCollection` processes a `ReferenceSchema` with a `facetedPartially` expression, it calls `ExpressionToQueryTranslator` to produce the `FilterBy` template that gets stored in the `FacetExpressionTrigger`.
- **`ReevaluateFacetExpressionExecutor`** — the executor retrieves the pre-translated `FilterBy` via `ExpressionIndexTrigger.getFilterByConstraint()` and parameterizes it at trigger time. The quality and correctness of the translation directly determines executor behavior.
- **Schema validation** — if the translator throws an exception for a non-translatable expression, the schema is rejected. The translator acts as a gatekeeper for expression validity in the context of cross-entity triggers.

## Technical Context

### Role in the Architecture

`ExpressionToQueryTranslator` sits at the boundary between the expression language and the evitaDB query model. It is invoked **once per expression at schema load time** (not on the hot path). Its output — a `FilterBy` constraint tree — is cached in the `ExpressionIndexTrigger` and reused for every cross-entity trigger evaluation.

The translator is part of the **two-mode evaluation architecture**:
- **Local triggers** use `ExpressionIndexTrigger.evaluate()` — per-entity evaluation via Proxycian proxies backed by storage parts. The translator is NOT involved in this path.
- **Cross-entity triggers** use `ExpressionIndexTrigger.getFilterByConstraint()` — the translator's output. The executor parameterizes the template and evaluates it against indexes via `IndexMutationTarget.evaluateFilter(FilterBy)`.

### Translation Mapping — Full Expression Paths

| Expression path | evitaDB `FilterBy` constraint |
|---|---|
| `$entity.attributes['x'] == v` | `attributeEquals("x", v)` |
| `$entity.attributes['x'] > v` | `attributeGreaterThan("x", v)` |
| `$entity.attributes['x'] >= v` | `attributeGreaterThanEquals("x", v)` |
| `$entity.attributes['x'] < v` | `attributeLessThan("x", v)` |
| `$entity.attributes['x'] <= v` | `attributeLessThanEquals("x", v)` |
| `$entity.attributes['x'] == null` | `attributeIsNull("x")` |
| `$entity.attributes['x'] != null` | `attributeIsNotNull("x")` |
| `$entity.associatedData['x'] == v` | *no direct constraint — reject or use attribute-based approximation* |
| `$entity.parent != null` | `hierarchyWithinRootSelf()` *(entity has a parent)* |
| `$entity.parent == null` | `not(hierarchyWithinRootSelf())` *(entity has no parent)* |
| `$reference.attributes['x'] == v` | `referenceHaving("refName", attributeEquals("x", v))` |
| `$reference.groupEntity?.attributes['x'] == v` | `referenceHaving("refName", groupHaving(attributeEquals("x", v)))` |
| `$reference.referencedEntity.attributes['x'] == v` | `referenceHaving("refName", entityHaving(attributeEquals("x", v)))` |

Note: The `refName` in `referenceHaving` is derived from the `ReferenceSchema` context — the translator knows which reference schema the expression belongs to.

### Boolean Operator Mapping

| Expression operator | evitaDB constraint |
|---|---|
| `a && b` | `and(a, b)` |
| `a \|\| b` | `or(a, b)` |
| `!a` | `not(a)` |
| `a && b && c` | `and(a, b, c)` (flattened — consecutive same-type boolean operators are merged into a single variadic constraint) |

### Complete Translation Examples

**Example 1 — Cross-entity OR expression:**

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
            groupHaving(attributeEquals("status", "ACTIVE")),
            entityHaving(attributeEquals("status", "PREVIEW"))
        )
    )
)
```

**Example 2 — Mixed-dependency expression (cross-entity AND local):**

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
            groupHaving(attributeEquals("inputWidgetType", "CHECKBOX"))
        ),
        attributeEquals("isActive", true)
    )
)
```

Note: Mixed-dependency expressions combine both local entity data (`$entity.attributes`) and cross-entity data (`$reference.groupEntity?.attributes`). The **entire** expression is translated to a single `FilterBy` constraint tree — there is no expression partitioning. The query engine handles local, cross-entity, and mixed paths uniformly (see AD 18 in the parent document).

### Parameterization at Trigger Time

The `FilterBy` produced by the translator is a **template**. It is not directly executable for a specific cross-entity trigger — it needs to be scoped to the specific mutated entity. This parameterization is NOT done by the translator (it happens at trigger time in the executor), but the translator must produce a structure that supports it.

At trigger time, `ReevaluateFacetExpressionExecutor` injects a PK-scoping constraint:

- **`GROUP_ENTITY_ATTRIBUTE`**: adds `groupHaving(entityPrimaryKeyInSet(mutatedPK))` within the `referenceHaving` clause
- **`REFERENCED_ENTITY_ATTRIBUTE`**: adds `entityHaving(entityPrimaryKeyInSet(mutatedPK))` within the `referenceHaving` clause

**Why scoping is critical:** Without PK-scoping, an entity referencing multiple groups (e.g., group 99 changed, group 55 unchanged) would incorrectly match because of the unchanged group. The scoping ensures the query evaluates the expression only for references to the specific changed entity.

The translator must therefore produce a `FilterBy` tree where `referenceHaving` clauses are structured in a way that allows the executor to inject additional scoping constraints. The structure should make it straightforward for the executor to locate the `referenceHaving` node and add the `groupHaving(entityPrimaryKeyInSet(...))` or `entityHaving(entityPrimaryKeyInSet(...))` clause.

### Non-Translatable Expressions — Rejected at Schema Time

If the expression contains constructs that cannot be translated to a `FilterBy` constraint, an exception is thrown at schema load time and the schema is rejected. Non-translatable constructs include:

1. **Dynamic attribute paths** — `$entity.attributes[someVariable]` where the attribute name is a runtime expression (not a string literal). `AccessedDataFinder` cannot determine which specific attribute is accessed, so `getDependentAttributes()` cannot be populated and the trigger cannot be correctly scoped. Same applies to spread operators (`.*[expr]`).

2. **Direct cross-to-local comparisons** — `$reference.groupEntity?.attributes['type'] == $entity.attributes['category']` where a cross-entity value is compared directly with a local value. evitaDB's `FilterBy` does not support cross-constraint value comparisons (there is no way to express "attribute A on entity X equals attribute B on entity Y" in a single FilterBy tree).

3. **Unsupported operators** — expression operators that have no `FilterBy` equivalent (e.g., arithmetic operators, string concatenation, complex method calls).

The validation is performed by `ExpressionToQueryTranslator` at schema load time (or by `AccessedDataFinder` for dynamic path detection). Users must see a clear error message explaining which construct is unsupported and why.

### Associated Data Access

The translation mapping explicitly notes that `$entity.associatedData['x'] == v` has **no direct constraint** in the `FilterBy` model. The translator should reject such expressions (or, if a future approximation is added, map them to an attribute-based alternative). This is a known limitation.

## Key Interfaces

### `ExpressionIndexTrigger` (consumer of the translator's output)

```java
// in io.evitadb.index.mutation (evita_engine)

public interface ExpressionIndexTrigger {

    @Nonnull String getOwnerEntityType();
    @Nonnull String getReferenceName();
    @Nonnull Scope getScope();
    @Nonnull DependencyType getDependencyType();
    @Nonnull Set<String> getDependentAttributes();

    /**
     * Returns the full expression pre-translated to an evitaDB FilterBy
     * constraint tree. Built at schema load time by ExpressionToQueryTranslator.
     *
     * At trigger time, the executor parameterizes this constraint by injecting
     * a PK-scoping clause for the specific mutated entity:
     * - GROUP_ENTITY_ATTRIBUTE: adds
     *   groupHaving(entityPrimaryKeyInSet(mutatedPK))
     *   within the referenceHaving clause
     * - REFERENCED_ENTITY_ATTRIBUTE: adds
     *   entityHaving(entityPrimaryKeyInSet(mutatedPK))
     *   within the referenceHaving clause
     */
    @Nonnull FilterBy getFilterByConstraint();

    boolean evaluate(
        int ownerEntityPK,
        @Nonnull ReferenceKey referenceKey,
        @Nonnull WritableEntityStorageContainerAccessor storageAccessor
    );
}
```

### `DependencyType` (classifies the cross-entity relationship)

```java
// in io.evitadb.index.mutation (evita_engine)

public enum DependencyType {
    /** $reference.referencedEntity.attributes['x'] — mutated entity is the referenced entity */
    REFERENCED_ENTITY_ATTRIBUTE,
    /** $reference.groupEntity?.attributes['x'] — mutated entity is the group entity */
    GROUP_ENTITY_ATTRIBUTE
}
```

### `IndexMutationTarget` (provides `evaluateFilter` used downstream)

```java
public interface IndexMutationTarget {
    // ... index access methods ...

    @Nullable ExpressionIndexTrigger getTrigger(
        @Nonnull String referenceName,
        @Nonnull DependencyType dependencyType,
        @Nonnull Scope scope
    );

    @Nonnull Bitmap evaluateFilter(@Nonnull FilterBy filterBy);
}
```

### `ExpressionToQueryTranslator` (the component this WBS delivers)

The translator should accept the parsed expression AST and the reference schema context, and return a `FilterBy` constraint tree. Suggested signature:

```java
// in io.evitadb.index.mutation (evita_engine) or a sub-package

public class ExpressionToQueryTranslator {

    /**
     * Translates a facetedPartially expression into an evitaDB FilterBy
     * constraint tree. Called at schema load time.
     *
     * @param expression     the parsed expression AST
     * @param referenceName  the name of the reference carrying the expression
     * @return the FilterBy constraint tree (template — not yet parameterized)
     * @throws NonTranslatableExpressionException if the expression contains
     *         constructs that cannot be mapped to FilterBy constraints
     */
    @Nonnull
    public FilterBy translate(
        @Nonnull Expression expression,
        @Nonnull String referenceName
    ) { /* ... */ }
}
```

## Acceptance Criteria

- The translator correctly maps all expression paths listed in the translation mapping table to their corresponding `FilterBy` constraints.
- Boolean operators `&&`, `||`, `!` are correctly mapped to `and()`, `or()`, `not()`.
- Consecutive same-type boolean operators are flattened (e.g., `a && b && c` becomes `and(a, b, c)`, not `and(and(a, b), c)`).
- Entity attribute comparisons produce top-level `attributeEquals`/`attributeGreaterThan`/etc. constraints.
- Reference attribute comparisons are wrapped in `referenceHaving("refName", ...)`.
- Group entity attribute comparisons are wrapped in `referenceHaving("refName", groupHaving(...))`.
- Referenced entity attribute comparisons are wrapped in `referenceHaving("refName", entityHaving(...))`.
- Attribute null checks (`== null` / `!= null`) produce `attributeIsNull("x")` / `attributeIsNotNull("x")` (or wrapped in `referenceHaving`/`groupHaving`/`entityHaving` as appropriate).
- Parent existence checks produce `hierarchyWithinRootSelf()`.
- Parent absence checks produce `not(hierarchyWithinRootSelf())`.
- Mixed-dependency expressions (combining local and cross-entity paths) produce a single unified `FilterBy` tree with no expression partitioning.
- The `FilterBy` tree structure supports downstream parameterization — `referenceHaving` clauses are structured so the executor can inject `groupHaving(entityPrimaryKeyInSet(...))` or `entityHaving(entityPrimaryKeyInSet(...))` scoping.
- Dynamic attribute paths (`$entity.attributes[someVariable]`) are detected and rejected with a clear exception at schema load time.
- Direct cross-to-local comparisons (`$reference.groupEntity?.attributes['x'] == $entity.attributes['y']`) are detected and rejected with a clear exception at schema load time.
- Unsupported operators are detected and rejected with a clear exception at schema load time.
- Associated data access paths (`$entity.associatedData['x'] == v`) are rejected (no FilterBy equivalent).
- Exception messages clearly identify the unsupported construct and explain why it cannot be translated.
- The translator is invoked at schema load time only — no class generation or translation on the trigger-time hot path.

## Implementation Notes

- **Visitor pattern over the expression AST** — the translator likely implements a visitor (or recursive descent) over the `ExpressionNode` tree. Each node type maps to a specific constraint factory call.
- **Reference name context** — the translator needs the `referenceName` from the `ReferenceSchema` to generate `referenceHaving(refName, ...)` wrappers. This context must be passed in at translation time.
- **`AccessedDataFinder` collaboration** — for dynamic attribute path detection, the translator may delegate to `AccessedDataFinder` or share validation logic. The parent document notes that detection can be performed by either component: "The validation is performed by `ExpressionToQueryTranslator` at schema load time (or by `AccessedDataFinder` for dynamic path detection)."
- **Existing constraint factory methods** — evitaDB has static factory methods for all `FilterBy` constraints (`QueryConstraints.attributeEquals(...)`, `QueryConstraints.and(...)`, etc.). The translator should use these rather than constructing constraint objects directly.
- **Immutable output** — the `FilterBy` tree is stored in the trigger and reused across all trigger evaluations. It must be safe for concurrent read access (evitaDB constraints are immutable by design).
- **Test with the parameterization step** — although parameterization is out of scope for this WBS, integration tests should verify that the translator's output is compatible with the executor's `parameterize()` method. The `FilterBy` structure must be navigable by the executor to locate `referenceHaving` nodes for PK-scoping injection.
- **Error message quality** — since non-translatable expressions are rejected at schema load time (a user-facing operation), error messages should be actionable: identify the specific expression node, the unsupported construct, and ideally suggest an alternative.
- **Module placement** — per AD 14 in the parent document, all trigger infrastructure lives in `evita_engine`, not `evita_api`. The translator should be placed in `io.evitadb.index.mutation` or a sub-package thereof, alongside `ExpressionIndexTrigger`.

## Phase Placeholders

### Detailed Task Breakdown

#### Source Code Research Results

##### Expression Language AST Types

The expression language is parsed via ANTLR (`EvitaELParser`/`EvitaELLexer`) in the `evita_query` module. `ExpressionFactory.parse(String)` returns an `Expression` wrapping a root `ExpressionNode`.

- **`ExpressionNode`** (interface) — `evita_common/src/main/java/io/evitadb/dataType/expression/ExpressionNode.java`
  - `getChildren(): ExpressionNode[]` — children of this node (null if leaf)
  - `compute(ExpressionEvaluationContext): Serializable` — evaluates the node
  - `accept(ExpressionNodeVisitor)` — visitor dispatch
- **`Expression`** (wrapper) — `evita_common/src/main/java/io/evitadb/dataType/expression/Expression.java`
  - Wraps a root `ExpressionNode`; delegates all operations; single child array `[root]`
- **`ExpressionNodeVisitor`** (interface) — `evita_common/src/main/java/io/evitadb/dataType/expression/ExpressionNodeVisitor.java`
  - Single method: `void visit(ExpressionNode node)` — must use `instanceof` dispatch since the interface has a single generic `visit`

**Boolean / comparison operator node types** (all in `io.evitadb.api.query.expression.bool`):
- `ConjunctionOperator` — `&&`, binary, left+right children
- `DisjunctionOperator` — `||`, binary, left+right children
- `InverseOperator` — `!`, unary, single child
- `XorOperator` — `^`, binary, left+right children
- `EqualsOperator` — `==`, binary
- `NotEqualsOperator` — `!=`, binary
- `GreaterThanOperator` — `>`, binary
- `GreaterThanEqualsOperator` — `>=`, binary
- `LesserThanOperator` — `<`, binary
- `LesserThanEqualsOperator` — `<=`, binary
- All implement marker interface `BooleanOperator extends ExpressionNode`

**Operand types** (in `io.evitadb.api.query.expression.operand`):
- `ConstantOperand` — holds a `Serializable value`, leaf node (no children)
- `VariableOperand` — holds `String variableName` (null if `this`), leaf node; `isThis()` returns true when null

**Object access types** (in `io.evitadb.api.query.expression.object`):
- `ObjectAccessOperator` — wraps a source operand (`ExpressionNode`) + an `ObjectAccessStep` chain
- `PropertyAccessStep` — `.property`, has `getPropertyIdentifier(): String` and `getNext(): ObjectAccessStep`
- `ElementAccessStep` — `['key']`, has `getElementIdentifierOperand(): ExpressionNode` and `getNext()`
- `NullSafeAccessStep` — `?.` / `?[`, wraps next step, returns null if operand is null
- `SpreadAccessStep` — `.*[expr]`, iterates collection/map with mapping expression

**Utility types**:
- `NestedOperator` — parenthesized expression wrapper, single child
- `NullCoalesceOperator` — `??` operator
- Numeric operators: `AdditionOperator`, `SubtractionOperator`, `MultiplicationOperator`, `DivisionOperator`, `ModuloOperator`, `NegativeOperator`, `PositiveOperator`
- `FunctionOperator` — `random()`, `abs()`, etc.

**All operator fields are private** — the translator must access left/right operands via `getChildren()` which returns `ExpressionNode[]` (index 0 = left, index 1 = right for binary operators; index 0 = operand for unary).

##### AccessedDataFinder and Path Classification

- **`AccessedDataFinder`** — `evita_query/src/main/java/io/evitadb/api/query/expression/visitor/AccessedDataFinder.java`
  - Static method: `findAccessedPaths(ExpressionNode): List<List<PathItem>>` — traverses the AST and collects all accessed data paths
  - Returns paths as lists of `PathItem` records
  - **Path structure**: e.g. `$entity.attributes['name']` produces `[VariablePathItem("entity"), IdentifierPathItem("attributes"), ElementPathItem("name")]`
  - **Path items** (sealed interface `PathItem`, permits 3 records):
    - `VariablePathItem(String value)` — variable reference, e.g. `$entity` -> `value = "entity"`
    - `IdentifierPathItem(String value)` — property access, e.g. `.attributes` -> `value = "attributes"`
    - `ElementPathItem(String value)` — element access, e.g. `['name']` -> `value = "name"`
    - `VariablePathItem` is used when the element access key is a variable (dynamic path) rather than a `ConstantOperand` (static path)
  - Located at: `evita_query/src/main/java/io/evitadb/api/query/expression/visitor/PathItem.java` and siblings

- **`BooleanExpressionChecker`** — `evita_query/src/main/java/io/evitadb/api/query/expression/visitor/BooleanExpressionChecker.java`
  - Static method: `isBooleanExpression(ExpressionNode): boolean`
  - Unwraps `Expression` and `NestedOperator` wrappers, checks if root is `BooleanOperator`
  - Good template for visitor pattern usage in this codebase

##### evitaDB FilterBy Constraint Factory Methods

All in `evita_query/src/main/java/io/evitadb/api/query/QueryConstraints.java` (static interface methods):

| Factory method | Signature |
|---|---|
| `filterBy` | `static FilterBy filterBy(@Nullable FilterConstraint... constraint)` |
| `and` | `static And and(@Nullable FilterConstraint... constraints)` |
| `or` | `static Or or(@Nullable FilterConstraint... constraints)` |
| `not` | `static Not not(@Nullable FilterConstraint constraint)` |
| `attributeEquals` | `static <T extends Serializable> AttributeEquals attributeEquals(@Nullable String attributeName, @Nullable T attributeValue)` |
| `attributeGreaterThan` | `static <T extends Serializable> AttributeGreaterThan attributeGreaterThan(@Nullable String attributeName, @Nullable T attributeValue)` |
| `attributeGreaterThanEquals` | `static <T extends Serializable> AttributeGreaterThanEquals attributeGreaterThanEquals(@Nullable String attributeName, @Nullable T attributeValue)` |
| `attributeLessThan` | `static <T extends Serializable> AttributeLessThan attributeLessThan(@Nullable String attributeName, @Nullable T attributeValue)` |
| `attributeLessThanEquals` | `static <T extends Serializable> AttributeLessThanEquals attributeLessThanEquals(@Nullable String attributeName, @Nullable T attributeValue)` |
| `referenceHaving` | `static ReferenceHaving referenceHaving(@Nullable String referenceName, @Nullable FilterConstraint... constraint)` |
| `entityHaving` | `static EntityHaving entityHaving(@Nullable FilterConstraint filterConstraint)` |
| `groupHaving` | `static GroupHaving groupHaving(@Nullable FilterConstraint filterConstraint)` |
| `hierarchyWithinRootSelf` | `static HierarchyWithinRoot hierarchyWithinRootSelf(@Nullable HierarchySpecificationFilterConstraint... with)` |
| `hierarchyWithinRoot` | `static HierarchyWithinRoot hierarchyWithinRoot(@Nullable String referenceName, @Nullable HierarchySpecificationFilterConstraint... with)` |
| `entityPrimaryKeyInSet` | `static EntityPrimaryKeyInSet entityPrimaryKeyInSet(@Nullable Integer... primaryKey)` |
| `attributeIsNull` | `static AttributeIs attributeIsNull(@Nullable String attributeName)` |
| `attributeIsNotNull` | `static AttributeIs attributeIsNotNull(@Nullable String attributeName)` |

**Constraint classes**:
- `FilterBy` — `evita_query/src/main/java/io/evitadb/api/query/filter/FilterBy.java`
- `ReferenceHaving` — `evita_query/src/main/java/io/evitadb/api/query/filter/ReferenceHaving.java`
- `EntityHaving` — `evita_query/src/main/java/io/evitadb/api/query/filter/EntityHaving.java`
- `GroupHaving` — `evita_query/src/main/java/io/evitadb/api/query/filter/GroupHaving.java`
- `HierarchyWithinRoot` — `evita_query/src/main/java/io/evitadb/api/query/filter/HierarchyWithinRoot.java`

**IMPORTANT**: The parent analysis document uses `entityGroupHaving(...)` as a constraint name, but the actual evitaDB constraint is `groupHaving(...)` / `GroupHaving`. The translator must use `QueryConstraints.groupHaving(...)`, not a non-existent `entityGroupHaving(...)`.

##### ReferenceSchema and facetedPartiallyInScopes

- **`ReferenceSchema`** — `evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/ReferenceSchema.java`
  - Field: `Map<Scope, Expression> facetedPartiallyInScopes` — per-scope expressions that narrow facet participation
  - Method: `getFacetedPartiallyInScope(Scope): Expression` — returns the parsed `Expression` for a scope, or null
  - The `Expression` is already parsed (it is the `io.evitadb.dataType.expression.Expression` AST wrapper)

##### Existing Visitor/Translator Patterns

1. **`AccessedDataFinder`** — implements `ExpressionNodeVisitor`, uses `instanceof` dispatch in `visit()`, private constructor with static factory method `findAccessedPaths()`. Traverses the AST to collect paths. Best template for the translator.

2. **`BooleanExpressionChecker`** — implements `ExpressionNodeVisitor`, simpler visitor that only checks the root node type. Demonstrates the `Expression`/`NestedOperator` unwrapping pattern.

3. **Query filter translators in `evita_engine`** (e.g. `ReferenceHavingTranslator`, `EntityHavingTranslator`, `GroupHavingTranslator`, `HierarchyWithinRootTranslator`) — these translate evitaDB `FilterConstraint` nodes into query algebra formulas, the reverse direction. Located in `evita_engine/src/main/java/io/evitadb/core/query/filter/translator/`. These are NOT direct templates (they translate constraints to algebra, not expressions to constraints), but show the engine's translator naming conventions.

##### Module and Package Placement

- The translator should live in the `evita_engine` module per AD 14 in the parent document
- Package: `io.evitadb.index.mutation` or a sub-package (e.g., `io.evitadb.index.mutation.expression`)
- The `io.evitadb.index.mutation` package is NOT exported in `module-info.java` (it is engine-internal), which is correct
- The engine module already depends on `evita.query` and `evita.common`, so all expression AST types and `QueryConstraints` factory methods are accessible
- Exception class: Can use `EvitaInvalidUsageException` (from `evita_common`) for user-facing schema validation errors

##### Key Design Observations

1. **Binary operators expose children via `getChildren()`**: All binary comparison operators (`EqualsOperator`, etc.) store children as `ExpressionNode[] {leftOperator, rightOperator}`. The translator must use `getChildren()[0]` (left) and `getChildren()[1]` (right) since the fields are private.

2. **Comparison operators are always binary with ObjectAccessOperator on one side and ConstantOperand on the other**: For translatable expressions, one operand must be an `ObjectAccessOperator` (the data path) and the other a `ConstantOperand` (the literal value). If both are `ObjectAccessOperator` (cross-to-local comparison), reject. **Null literal handling**: A `null` on the right side of a comparison is represented as a `ConstantOperand` with a `null` value. The translator must detect null-comparisons and dispatch based on the path type:
   - `$entity.parent == null` / `$entity.parent != null` -> hierarchy check (`not(hierarchyWithinRootSelf())` / `hierarchyWithinRootSelf()`)
   - `$entity.attributes['x'] == null` / `$entity.attributes['x'] != null` -> `attributeIsNull("x")` / `attributeIsNotNull("x")` (using `QueryConstraints.attributeIsNull()` / `attributeIsNotNull()`)
   - Other path types with null (e.g., `$reference.attributes['x'] == null`) follow the same null-check pattern wrapped in `referenceHaving`

3. **Path interpretation requires walking the `ObjectAccessStep` chain**: The translator must walk `ObjectAccessOperator.getAccessChain()` to determine the data path type:
   - `$entity.attributes['x']` — local entity attribute
   - `$reference.attributes['x']` — reference attribute
   - `$reference.groupEntity?.attributes['x']` — group entity attribute (note the `?.` null-safe access)
   - `$reference.referencedEntity.attributes['x']` — referenced entity attribute
   - `$entity.parent` — parent existence check

4. **Boolean operator flattening**: `ConjunctionOperator` and `DisjunctionOperator` are always binary (left + right). Nested trees like `(a && b) && c` must be flattened to `and(a, b, c)`. This requires recursive descent that collects all same-type operands.

5. **`NotEqualsOperator` has no direct FilterBy equivalent**: Must be translated to `not(attributeEquals(...))` for non-null values. For null-comparisons, the translator must use dedicated null-check constraints: `$entity.attributes['x'] != null` -> `attributeIsNotNull("x")`, `$entity.attributes['x'] == null` -> `attributeIsNull("x")`. The `$entity.parent != null` case is a special path type that maps to `hierarchyWithinRootSelf()` (not a null-check constraint).

---

#### Detailed Task List

**Group 1: Core Translator Class**

- [x] Create `ExpressionToQueryTranslator` class in `evita_engine/src/main/java/io/evitadb/core/expression/query/ExpressionToQueryTranslator.java` — implement `ExpressionNodeVisitor` with a public static `translate(Expression, String referenceName): FilterBy` method. Follow the `AccessedDataFinder` pattern: private constructor, static factory method, internal mutable state for building the constraint tree during traversal. *(Note: placed in `io.evitadb.core.expression.query` package instead of `io.evitadb.index.mutation.expression`)*

- [x] Implement `Expression` and `NestedOperator` unwrapping — when visiting these wrapper nodes, delegate to the single child (follow the `BooleanExpressionChecker` pattern at `evita_query/src/main/java/io/evitadb/api/query/expression/visitor/BooleanExpressionChecker.java` lines 73-80).

- [x] Implement boolean operator translation: `ConjunctionOperator` -> `and()`, `DisjunctionOperator` -> `or()`, `InverseOperator` -> `not()`. For conjunction and disjunction, implement recursive flattening to collect all operands of the same type into a single variadic `and(...)` or `or(...)` call. `XorOperator` has no FilterBy equivalent and should be rejected with a clear error. *(XOR rejection deferred to iteration 5)*

- [x] Implement comparison operator translation — for each of `EqualsOperator`, `GreaterThanOperator`, `GreaterThanEqualsOperator`, `LesserThanOperator`, `LesserThanEqualsOperator`: extract the `ObjectAccessOperator` operand (data path) and `ConstantOperand` operand (literal value), determine the data path type, and produce the corresponding attribute constraint. Handle either operand order (path on left or right). When the `ConstantOperand` value is `null`, dispatch to null-check handling (see below).

- [ ] Implement null-check translation — when a comparison operator has a `null` `ConstantOperand`, produce the appropriate null-check constraint based on path type:
  - `$entity.attributes['x'] == null` -> `attributeIsNull("x")`
  - `$entity.attributes['x'] != null` -> `attributeIsNotNull("x")`
  - `$entity.parent == null` -> `not(hierarchyWithinRootSelf())`
  - `$entity.parent != null` -> `hierarchyWithinRootSelf()`
  - Reference/group/referenced entity attribute null checks follow the same pattern, wrapped in the appropriate `referenceHaving`/`groupHaving`/`entityHaving` containers.

- [x] Implement `NotEqualsOperator` translation — decompose into `not(attributeEquals(...))` for non-null attribute comparisons. For null comparisons (`!= null`), use `attributeIsNotNull("x")` for attributes or `hierarchyWithinRootSelf()` for the parent path. For non-null value comparisons, produce `not(attributeEquals("x", v))`. *(Null-check part blocked on EvitaEL grammar — no null literal)*

- [x] Implement data path classification — create a private helper method that walks an `ObjectAccessOperator`'s step chain (`PropertyAccessStep`, `ElementAccessStep`, `NullSafeAccessStep`) and returns a structured result indicating: (a) path type (ENTITY_ATTRIBUTE, REFERENCE_ATTRIBUTE, GROUP_ENTITY_ATTRIBUTE, REFERENCED_ENTITY_ATTRIBUTE, ENTITY_PARENT, ASSOCIATED_DATA), and (b) the attribute name (extracted from the `ElementAccessStep`'s `ConstantOperand`). *(ENTITY_PARENT and ASSOCIATED_DATA deferred — blocked on grammar / deferred to iteration 5)*

- [x] Implement constraint wrapping based on path type:
  - `ENTITY_ATTRIBUTE` -> bare `attributeXxx("attrName", value)` (no wrapper)
  - `REFERENCE_ATTRIBUTE` -> `referenceHaving(referenceName, attributeXxx("attrName", value))`
  - `GROUP_ENTITY_ATTRIBUTE` -> `referenceHaving(referenceName, groupHaving(attributeXxx("attrName", value)))` (using `QueryConstraints.groupHaving()`, **not** `entityGroupHaving`)
  - `REFERENCED_ENTITY_ATTRIBUTE` -> `referenceHaving(referenceName, entityHaving(attributeXxx("attrName", value)))`
  - `ENTITY_PARENT` (existence check) -> `hierarchyWithinRootSelf()` *(deferred — blocked on grammar)*

- [x] Implement `referenceHaving` merging — when multiple comparison nodes within the same **AND** context reference the same reference name, merge their inner constraints under a single `referenceHaving` wrapper rather than producing duplicate `referenceHaving` nodes. This is important for downstream parameterization: the executor needs a single `referenceHaving` node to locate and inject PK-scoping. **Merging is limited to AND contexts only.** In OR contexts (e.g., `$reference.attributes['a'] == 1 || $reference.attributes['b'] == 2`), the two `referenceHaving` nodes should be placed under the `or(...)` without merging — producing `or(referenceHaving("ref", attrEq("a",1)), referenceHaving("ref", attrEq("b",2)))`. Similarly, if one branch is negated, it should not be merged with non-negated branches. The rationale: merging inside OR would change the semantics of the generated FilterBy tree and break PK-scoping parameterization, because the executor injects scoping at the `referenceHaving` level.

- [x] Wrap the final constraint tree in `filterBy(...)` — the public `translate()` method should return `FilterBy` (using `QueryConstraints.filterBy(...)`).

**Group 2: Validation and Error Handling**

- [x] Create `NonTranslatableExpressionException` class in `evita_engine/src/main/java/io/evitadb/core/expression/query/NonTranslatableExpressionException.java` — extend `EvitaInvalidUsageException`. *(Note: placed in `io.evitadb.core.expression.query` package instead of `io.evitadb.index.mutation.expression`)*

- [x] Validate: reject dynamic attribute paths — when an `ElementAccessStep`'s identifier operand is a `VariableOperand` instead of a `ConstantOperand`, throw `NonTranslatableExpressionException` with message: "Dynamic attribute path `$entity.attributes[<variable>]` cannot be translated to a FilterBy constraint because the attribute name is not a compile-time constant."

- [x] Validate: reject `SpreadAccessStep` — if the `ObjectAccessOperator`'s step chain contains a `SpreadAccessStep`, throw `NonTranslatableExpressionException` with message explaining that spread operators cannot be mapped to FilterBy constraints.

- [x] Validate: reject associated data paths — when the path contains `.associatedData[...]` (i.e., `IdentifierPathItem("associatedData")`), throw `NonTranslatableExpressionException` explaining that associated data has no FilterBy equivalent.

- [x] Validate: reject cross-to-local comparisons — when both operands of a comparison operator are `ObjectAccessOperator` nodes, throw `NonTranslatableExpressionException` explaining that cross-constraint value comparisons are not supported in FilterBy.

- [x] Validate: reject unsupported operators — for `AdditionOperator`, `SubtractionOperator`, `MultiplicationOperator`, `DivisionOperator`, `ModuloOperator`, `NegativeOperator`, `PositiveOperator`, `FunctionOperator`, `NullCoalesceOperator`, `SpreadNullCoalesceOperator`, throw `NonTranslatableExpressionException` explaining that arithmetic/function operators cannot be translated to FilterBy.

- [x] Validate: reject `XorOperator` — throw `NonTranslatableExpressionException` explaining that XOR has no FilterBy equivalent. Suggest rewriting as `(a || b) && !(a && b)`.

- [x] Validate: ensure the expression is a boolean expression — at entry, check `BooleanExpressionChecker.isBooleanExpression(expression)` and reject non-boolean expressions (e.g., pure arithmetic expressions that return a number rather than a boolean).

**Group 3: Collaboration with AccessedDataFinder**

- [x] Before translation, call `AccessedDataFinder.findAccessedPaths(expression)` to pre-validate that all paths are statically resolvable. If any path contains a `VariablePathItem` at a position that represents an attribute name (i.e., after an `IdentifierPathItem("attributes")`), reject with `NonTranslatableExpressionException`. This provides early detection before the AST traversal, with a clearer error than if the translator encountered it mid-traversal.

### Test Cases

#### `ExpressionToQueryTranslatorTest` (unit)

Test class location: `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/expression/ExpressionToQueryTranslatorTest.java`

Expressions are parsed via `ExpressionFactory.parse(String)` and translated via `ExpressionToQueryTranslator.translate(Expression, String)`. Assertions verify the resulting `FilterBy` constraint tree structure (constraint types, attribute names, values, nesting).

**Happy path — entity attribute comparisons:**
- [x] `translates_entity_attribute_equals_string` — `$entity.attributes['status'] == 'ACTIVE'` translates to `filterBy(attributeEquals("status", "ACTIVE"))`
- [x] `translates_entity_attribute_equals_boolean_true` — `$entity.attributes['isActive'] == true` translates to `filterBy(attributeEquals("isActive", true))`
- [x] `translates_entity_attribute_equals_boolean_false` — `$entity.attributes['isActive'] == false` translates to `filterBy(attributeEquals("isActive", false))`
- [x] `translates_entity_attribute_equals_integer` — `$entity.attributes['priority'] == 1` translates to `filterBy(attributeEquals("priority", 1))`
- [x] `translates_entity_attribute_greater_than` — `$entity.attributes['price'] > 100` translates to `filterBy(attributeGreaterThan("price", 100))`
- [x] `translates_entity_attribute_greater_than_equals` — `$entity.attributes['price'] >= 100` translates to `filterBy(attributeGreaterThanEquals("price", 100))`
- [x] `translates_entity_attribute_less_than` — `$entity.attributes['price'] < 100` translates to `filterBy(attributeLessThan("price", 100))`
- [x] `translates_entity_attribute_less_than_equals` — `$entity.attributes['price'] <= 100` translates to `filterBy(attributeLessThanEquals("price", 100))`

**Happy path — not-equals decomposition:**
- [x] `translates_entity_attribute_not_equals_to_not_attribute_equals` — `$entity.attributes['status'] != 'DELETED'` translates to `filterBy(not(attributeEquals("status", "DELETED")))`
- [x] `translates_reference_attribute_not_equals` — `$reference.attributes['priority'] != 0` translates to `filterBy(referenceHaving("refName", not(attributeEquals("priority", 0))))`

**Happy path — attribute null checks:**
- [ ] `translates_entity_attribute_equals_null_to_attribute_is_null` — `$entity.attributes['status'] == null` translates to `filterBy(attributeIsNull("status"))`
- [ ] `translates_entity_attribute_not_equals_null_to_attribute_is_not_null` — `$entity.attributes['status'] != null` translates to `filterBy(attributeIsNotNull("status"))`
- [ ] `translates_reference_attribute_equals_null_to_attribute_is_null` — `$reference.attributes['priority'] == null` translates to `filterBy(referenceHaving("refName", attributeIsNull("priority")))`
- [ ] `translates_reference_attribute_not_equals_null_to_attribute_is_not_null` — `$reference.attributes['priority'] != null` translates to `filterBy(referenceHaving("refName", attributeIsNotNull("priority")))`
- [ ] `translates_null_check_combined_with_value_comparison` — `$entity.attributes['status'] != null && $entity.attributes['status'] == 'ACTIVE'` translates to `filterBy(and(attributeIsNotNull("status"), attributeEquals("status", "ACTIVE")))`

**Happy path — parent existence check:**
- [ ] `translates_entity_parent_not_equals_null_to_hierarchy_within_root_self` — `$entity.parent != null` translates to `filterBy(hierarchyWithinRootSelf())`
- [ ] `translates_entity_parent_equals_null_to_not_hierarchy_within_root_self` — `$entity.parent == null` translates to `filterBy(not(hierarchyWithinRootSelf()))` (entity has no parent)

**Happy path — reference attribute comparisons:**
- [x] `translates_reference_attribute_equals` — `$reference.attributes['priority'] == 1` translates to `filterBy(referenceHaving("refName", attributeEquals("priority", 1)))`
- [x] `translates_reference_attribute_greater_than` — `$reference.attributes['order'] > 5` translates to `filterBy(referenceHaving("refName", attributeGreaterThan("order", 5)))`
- [x] `translates_reference_attribute_less_than_equals` — `$reference.attributes['weight'] <= 10` translates to `filterBy(referenceHaving("refName", attributeLessThanEquals("weight", 10)))`

**Happy path — group entity attribute comparisons:**
- [x] `translates_group_entity_attribute_equals` — `$reference.groupEntity?.attributes['status'] == 'ACTIVE'` translates to `filterBy(referenceHaving("refName", groupHaving(attributeEquals("status", "ACTIVE"))))`
- [x] `translates_group_entity_attribute_greater_than` — `$reference.groupEntity?.attributes['priority'] > 0` translates to `filterBy(referenceHaving("refName", groupHaving(attributeGreaterThan("priority", 0))))`

**Happy path — referenced entity attribute comparisons:**
- [x] `translates_referenced_entity_attribute_equals` — `$reference.referencedEntity.attributes['status'] == 'PREVIEW'` translates to `filterBy(referenceHaving("refName", entityHaving(attributeEquals("status", "PREVIEW"))))`
- [x] `translates_referenced_entity_attribute_less_than` — `$reference.referencedEntity.attributes['rank'] < 50` translates to `filterBy(referenceHaving("refName", entityHaving(attributeLessThan("rank", 50))))`

**Happy path — boolean combinations (AND):**
- [x] `translates_conjunction_of_two_entity_attributes` — `$entity.attributes['a'] == 1 && $entity.attributes['b'] == 2` translates to `filterBy(and(attributeEquals("a", 1), attributeEquals("b", 2)))`
- [x] `translates_conjunction_of_three_entity_attributes_flattened` — `$entity.attributes['a'] == 1 && $entity.attributes['b'] == 2 && $entity.attributes['c'] == 3` translates to `filterBy(and(attributeEquals("a", 1), attributeEquals("b", 2), attributeEquals("c", 3)))` — verifies flattening into a single `and` with 3 children, not `and(and(a,b),c)`
- [x] `translates_deeply_nested_conjunction_flattened` — `$entity.attributes['a'] == 1 && ($entity.attributes['b'] == 2 && $entity.attributes['c'] == 3)` flattens to `filterBy(and(attributeEquals("a", 1), attributeEquals("b", 2), attributeEquals("c", 3)))` — verifies that parenthesized nested conjunctions are also flattened

**Happy path — boolean combinations (OR):**
- [x] `translates_disjunction_of_two_entity_attributes` — `$entity.attributes['a'] == 1 || $entity.attributes['b'] == 2` translates to `filterBy(or(attributeEquals("a", 1), attributeEquals("b", 2)))`
- [x] `translates_disjunction_of_three_entity_attributes_flattened` — `$entity.attributes['a'] == 1 || $entity.attributes['b'] == 2 || $entity.attributes['c'] == 3` translates to `filterBy(or(attributeEquals("a", 1), attributeEquals("b", 2), attributeEquals("c", 3)))` — verifies flattening

**Happy path — boolean combinations (NOT):**
- [x] `translates_negation_of_entity_attribute_equals` — `!($entity.attributes['isActive'] == true)` translates to `filterBy(not(attributeEquals("isActive", true)))`
- [x] `translates_negation_of_disjunction` — `!($entity.attributes['a'] == 1 || $entity.attributes['b'] == 2)` translates to `filterBy(not(or(attributeEquals("a", 1), attributeEquals("b", 2))))`

**Happy path — mixed boolean operators (no flattening across types):**
- [x] `translates_and_inside_or_without_cross_flattening` — `$entity.attributes['a'] == 1 || ($entity.attributes['b'] == 2 && $entity.attributes['c'] == 3)` translates to `filterBy(or(attributeEquals("a", 1), and(attributeEquals("b", 2), attributeEquals("c", 3))))` — verifies that AND inside OR is NOT flattened into the outer OR
- [x] `translates_or_inside_and_without_cross_flattening` — `($entity.attributes['a'] == 1 || $entity.attributes['b'] == 2) && $entity.attributes['c'] == 3` translates to `filterBy(and(or(attributeEquals("a", 1), attributeEquals("b", 2)), attributeEquals("c", 3)))` — verifies that OR inside AND is NOT flattened into the outer AND

**Happy path — cross-entity OR expression (WBS Example 1):**
- [x] `translates_cross_entity_or_from_wbs_example_1` — `$reference.groupEntity?.attributes['status'] == 'ACTIVE' || $reference.referencedEntity.attributes['status'] == 'PREVIEW'` translates to `filterBy(referenceHaving("parameter", or(groupHaving(attributeEquals("status", "ACTIVE")), entityHaving(attributeEquals("status", "PREVIEW")))))` *(implemented as `translates_cross_entity_or` using `REF_NAME` instead of `"parameter"`)*

**Happy path — mixed-dependency AND expression (WBS Example 2):**
- [x] `translates_mixed_dependency_and_from_wbs_example_2` — `$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX' && $entity.attributes['isActive'] == true` translates to `filterBy(and(referenceHaving("parameter", groupHaving(attributeEquals("inputWidgetType", "CHECKBOX"))), attributeEquals("isActive", true)))` *(implemented as `translates_mixed_reference_and_entity_attribute` using `REF_NAME` instead of `"parameter"`)*

**Happy path — reversed operand order:**
- [x] `translates_reversed_operand_order_constant_on_left` — `'ACTIVE' == $reference.groupEntity?.attributes['status']` produces the same `filterBy` as `$reference.groupEntity?.attributes['status'] == 'ACTIVE'` — verifies that constant-on-left, path-on-right is handled correctly
- [x] `translates_reversed_operand_order_for_comparison` — `100 < $entity.attributes['price']` translates equivalently to `$entity.attributes['price'] > 100` — verifies flipped comparison semantics when operands are reversed

**Happy path — Expression and NestedOperator unwrapping:**
- [x] `translates_parenthesized_expression` — `($entity.attributes['isActive'] == true)` translates identically to the non-parenthesized version — verifies `NestedOperator` unwrapping
- [x] `translates_double_parenthesized_expression` — `(($entity.attributes['isActive'] == true))` translates identically — verifies nested `NestedOperator` unwrapping

**Happy path — referenceHaving merging (AND context only):**
- [x] `merges_reference_having_for_same_reference_name` — `$reference.attributes['a'] == 1 && $reference.attributes['b'] == 2` produces `filterBy(referenceHaving("refName", and(attributeEquals("a", 1), attributeEquals("b", 2))))` — a single `referenceHaving` with merged children, not two separate `referenceHaving` nodes
- [x] `merges_group_entity_attributes_under_single_reference_having` — `$reference.groupEntity?.attributes['a'] == 1 && $reference.groupEntity?.attributes['b'] == 2` produces a single `referenceHaving` wrapping an `and(groupHaving(...), groupHaving(...))`
- [x] `merges_referenced_entity_attributes_under_single_reference_having` — `$reference.referencedEntity.attributes['a'] == 1 && $reference.referencedEntity.attributes['b'] == 2` produces a single `referenceHaving` wrapping an `and(entityHaving(...), entityHaving(...))`
- [x] `does_not_merge_reference_having_in_or_context` — `$reference.attributes['a'] == 1 || $reference.attributes['b'] == 2` produces `filterBy(or(referenceHaving("refName", attributeEquals("a", 1)), referenceHaving("refName", attributeEquals("b", 2))))` — two separate `referenceHaving` nodes under `or`, NOT merged into a single `referenceHaving`

**Happy path — complex mixed expressions:**
- [x] `translates_complex_three_path_conjunction` — `$entity.attributes['isActive'] == true && $reference.groupEntity?.attributes['status'] == 'ACTIVE' && $reference.referencedEntity.attributes['visible'] == true` produces a valid `FilterBy` tree combining all three path types under a single `and`
- [x] `translates_entity_attribute_with_negated_cross_entity_attribute` — `$entity.attributes['isActive'] == true && !($reference.groupEntity?.attributes['status'] == 'INACTIVE')` produces `filterBy(and(attributeEquals("isActive", true), not(referenceHaving("refName", groupHaving(attributeEquals("status", "INACTIVE"))))))`

**Rejection cases — non-translatable expressions:**
- [x] `rejects_dynamic_attribute_path_with_variable` — `$entity.attributes[$someVar] == 1` throws `NonTranslatableExpressionException` with a message identifying the dynamic path
- [x] `rejects_spread_access_step` — expression using `.*[...]` syntax (e.g., `$entity.references['categories'].*[$.referencedPrimaryKey] == 1`) throws `NonTranslatableExpressionException` identifying the spread access operator
- [x] `rejects_associated_data_access` — `$entity.associatedData['desc'] == 'test'` throws `NonTranslatableExpressionException` explaining associated data has no FilterBy equivalent
- [x] `rejects_cross_to_local_comparison_both_operands_are_paths` — `$reference.groupEntity?.attributes['type'] == $entity.attributes['category']` throws `NonTranslatableExpressionException` explaining cross-constraint value comparisons are unsupported
- [x] `rejects_xor_operator` — `$entity.attributes['a'] == 1 ^ $entity.attributes['b'] == 2` throws `NonTranslatableExpressionException` mentioning XOR has no FilterBy equivalent and suggesting rewrite as `(a || b) && !(a && b)`
- [x] `rejects_addition_operator` — `$entity.attributes['price'] + 10 > 100` throws `NonTranslatableExpressionException`
- [x] `rejects_subtraction_operator` — `$entity.attributes['price'] - 10 > 100` throws `NonTranslatableExpressionException`
- [x] `rejects_multiplication_operator` — `$entity.attributes['price'] * 2 > 100` throws `NonTranslatableExpressionException`
- [x] `rejects_division_operator` — `$entity.attributes['price'] / 2 > 50` throws `NonTranslatableExpressionException`
- [x] `rejects_modulo_operator` — `$entity.attributes['count'] % 2 == 0` throws `NonTranslatableExpressionException`
- [x] `rejects_negation_arithmetic_operator` — `-$entity.attributes['price'] < 0` throws `NonTranslatableExpressionException` (numeric negation, not boolean NOT) *(rejected as unsupported comparison operand type)*
- [x] `rejects_function_operator` — `random() > $entity.attributes['threshold']` throws `NonTranslatableExpressionException`
- [x] `rejects_null_coalesce_operator` — `$entity.attributes['name'] ?? 'default' == 'test'` throws `NonTranslatableExpressionException` *(rejected as unsupported comparison operand type)*
- [x] `rejects_reference_primary_key_comparison` — `$reference.referencedPrimaryKey == 42` throws `NonTranslatableExpressionException` explaining that reference PK comparisons are not supported in FilterBy translation (PK scoping is handled at trigger time by the executor)
- [x] `rejects_non_boolean_expression` — `$entity.attributes['price'] + 10` (pure arithmetic, not boolean) throws `NonTranslatableExpressionException` since the expression must be a boolean expression producing a filter predicate

**Rejection cases — error message quality:**
- [x] `rejection_message_identifies_dynamic_path_variable_name` — when rejecting `$entity.attributes[$myVar] == 1`, the exception message contains the variable name or a description of the unsupported dynamic path construct *(covered by `rejects_dynamic_attribute_path_with_variable` test — asserts message contains "Dynamic" or "compile-time constant")*
- [x] `rejection_message_identifies_unsupported_operator_type` — when rejecting an arithmetic expression, the exception message identifies the specific operator type (e.g., "multiplication") and explains it cannot be translated to FilterBy *(covered by individual rejection tests — each asserts message contains the operator name)*
- [x] `rejection_message_for_xor_suggests_alternative` — when rejecting XOR, the exception message suggests the `(a || b) && !(a && b)` rewrite pattern *(covered by `rejects_xor_operator` test)*
- [x] `rejection_message_for_associated_data_explains_no_filter_equivalent` — when rejecting associated data access, the message explains there is no FilterBy constraint for associated data *(covered by `rejects_associated_data_access` test)*

**Edge cases — boundary conditions:**
- [x] `translates_single_boolean_literal_comparison` — `$entity.attributes['flag'] == true` with a boolean value works correctly (boolean is `Serializable`) *(covered by `translates_entity_attribute_equals_boolean_true`)*
- [x] `translates_attribute_name_with_special_characters` — attribute names containing underscores, digits, or camelCase (e.g., `$entity.attributes['myAttr_v2'] == 1`) are passed through as-is to the constraint
- [x] `translates_string_value_with_special_characters` — `$entity.attributes['status'] == 'ACTIVE_v2'` passes the string literal through unchanged
- [x] `translates_numeric_value_as_long` — `$entity.attributes['count'] == 42` where `42` is parsed as a numeric constant — verifies the constant type is preserved in the constraint
- [x] `handles_nested_not_with_and` — `!(($entity.attributes['a'] == 1 && $entity.attributes['b'] == 2))` translates to `filterBy(not(and(attributeEquals("a", 1), attributeEquals("b", 2))))` — multiple layers of wrapping
- [x] `handles_double_negation` — `!!($entity.attributes['isActive'] == true)` translates to `filterBy(not(not(attributeEquals("isActive", true))))` — translator does not optimize away double negation, preserves semantics

**Edge cases — path classification robustness:**
- [x] `correctly_identifies_entity_attribute_path_not_confused_with_reference` — `$entity.attributes['refName'] == 'x'` produces `attributeEquals` (not `referenceHaving`) — verifies that the attribute name `refName` is not confused with a reference name
- [x] `uses_provided_reference_name_in_reference_having` — translating with `referenceName = "parameter"` produces `referenceHaving("parameter", ...)`, translating same expression with `referenceName = "brand"` produces `referenceHaving("brand", ...)` — verifies reference name is taken from parameter, not from expression

#### `ExpressionToQueryTranslatorIntegrationTest` (integration readiness)

Test class location: `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/expression/ExpressionToQueryTranslatorIntegrationTest.java`

These tests verify that the translator's output is structurally compatible with downstream parameterization by the `ReevaluateFacetExpressionExecutor`.

**Parameterization compatibility:**
- [x] `filter_by_tree_supports_group_entity_pk_scoping_injection` — translate `$reference.groupEntity?.attributes['status'] == 'ACTIVE'`, then navigate the resulting `FilterBy` tree to locate the `referenceHaving` node, and verify that a `groupHaving(entityPrimaryKeyInSet(99))` constraint can be programmatically added alongside the existing `groupHaving` within the `referenceHaving`'s children
- [x] `filter_by_tree_supports_referenced_entity_pk_scoping_injection` — translate `$reference.referencedEntity.attributes['status'] == 'PREVIEW'`, then navigate the resulting `FilterBy` tree to locate the `referenceHaving` node, and verify that an `entityHaving(entityPrimaryKeyInSet(99))` constraint can be programmatically injected
- [x] `filter_by_tree_with_mixed_paths_supports_scoping` — translate the mixed-dependency expression from WBS Example 2, navigate to `referenceHaving`, and verify scoping injection is feasible on the produced structure

**Immutability and thread safety:**
- [x] `translated_filter_by_is_immutable` — verify that the `FilterBy` tree returned by `translate()` uses only immutable constraint objects (all evitaDB constraint classes use `final` fields) — primarily an assertion that no mutable collections or state objects leak into the output
- [x] `translated_filter_by_is_safe_for_concurrent_reads` — translate an expression, share the resulting `FilterBy` across multiple threads reading its structure concurrently, verify no exceptions or inconsistencies — confirms the template is safe for reuse across trigger evaluations

**AccessedDataFinder collaboration:**
- [x] `pre_validation_rejects_dynamic_paths_before_traversal` — verify that calling `AccessedDataFinder.findAccessedPaths()` before translation detects dynamic attribute paths (paths with `VariablePathItem` at the attribute-name position) and that the translator leverages this for early rejection with a clear error message

---

## ⚠️ TOBEDONE JNO — Unsolved Issues Blocking Remaining Tests

The following 7 test cases cannot be implemented because they require changes outside the translator's scope. Both issues are **language-level changes** to the EvitaEL expression infrastructure.

### ⚠️ Issue 1: No `null` literal in EvitaEL grammar (blocks 5 tests)

**Root cause:** The EvitaEL ANTLR grammar (`EvitaEL.g4`) defines `literal` as `STRING | INT | FLOAT | BOOLEAN` only — there is no `NULL` token. Additionally, `ConstantOperand` constructor explicitly rejects `null` values with `ParserException("Null value is not allowed!")`. Parsing `$entity.attributes['x'] == null` fails at the parser level before the translator is ever invoked.

**Required changes:**
1. Add `NULL` token to EvitaEL lexer
2. Add `nullLiteral` alternative to the `literal` parser rule (or a separate `nullExpression` rule)
3. Either allow `null` in `ConstantOperand` or create a dedicated `NullConstantOperand` operand type
4. Update `DefaultExpressionVisitor` to handle the new null literal rule
5. Update `ExpressionToQueryTranslator.translateComparison()` to detect null values and dispatch to `attributeIsNull()`/`attributeIsNotNull()` constraints

**Blocked tests:**
- [ ] `translates_entity_attribute_equals_null_to_attribute_is_null`
- [ ] `translates_entity_attribute_not_equals_null_to_attribute_is_not_null`
- [ ] `translates_reference_attribute_equals_null_to_attribute_is_null`
- [ ] `translates_reference_attribute_not_equals_null_to_attribute_is_not_null`
- [ ] `translates_null_check_combined_with_value_comparison`

### ⚠️ Issue 2: No `$entity.parent` property in EntityContractAccessor (blocks 2 tests)

**Root cause:** `EntityContractAccessor` supports properties: `primaryKey`, `attributes`, `localizedAttributes`, `associatedData`, `localizedAssociatedData`, `references` — but NOT `parent`. Accessing `$entity.parent` throws `ExpressionEvaluationException("Property 'parent' does not exist on EntityContract")`. This issue compounds with Issue 1 because the parent tests use null comparisons (`$entity.parent == null` / `$entity.parent != null`).

**Required changes:**
1. Add `PARENT_PROPERTY = "parent"` constant to `EntityContractAccessor`
2. Add `parent` case to the switch statement in `EntityContractAccessor`, returning the parent entity (or null if no parent)
3. Resolve Issue 1 above (null literal support) since parent existence checks use `== null` / `!= null`
4. Add `ENTITY_PARENT` path type to `ExpressionToQueryTranslator.PathType` enum
5. Update `classifyPath()` to recognize the `parent` property
6. Update `wrapForPathType()` to map `ENTITY_PARENT` existence to `hierarchyWithinRootSelf()` / `not(hierarchyWithinRootSelf())`

**Blocked tests:**
- [ ] `translates_entity_parent_not_equals_null_to_hierarchy_within_root_self`
- [ ] `translates_entity_parent_equals_null_to_not_hierarchy_within_root_self`
