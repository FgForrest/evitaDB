# Formula framework

Formulas are the computational backbone of evitaDB's query filtering pipeline. Every user query is translated into
a tree of `Formula` nodes mirroring the original constraint structure. Leaf nodes wrap index bitmaps; inner nodes
perform set operations (AND, OR, NOT, etc.). Evaluating the root formula produces a `Bitmap` of matching entity
primary keys — the starting point for sorting, pagination, and extra-result fabrication.

This document covers everything a developer needs to know to write a correct, efficient, and cache-friendly
`Formula` implementation.

## How formulas fit into the query pipeline

```
User query
    │
    ▼
QueryPlanner.planQuery()
    │
    ├─ 1. Index selection ─── IndexSelectionVisitor picks best EntityIndex(es)
    │
    ├─ 2. Filter formula construction
    │      │
    │      ├─ FilterByVisitor walks the FilterConstraint tree
    │      │    └─ For each constraint: looks up FilteringConstraintTranslator → translator.translate() → Formula
    │      │
    │      ├─ FormulaPostProcessor pipeline transforms the tree (optimizer, deduplicator, scope injection, …)
    │      │
    │      └─ FormulaDeduplicator is always applied last (reuses structurally identical sub-trees)
    │
    ├─ 3. Sorter creation ─── OrderByVisitor
    ├─ 4. Slicer creation ─── pagination configuration
    └─ 5. Extra result producers ─── ExtraResultPlanningVisitor
            │
            ▼
        QueryPlan(filterFormula, sorters, slicer, producers)
            │
            ▼
QueryPlan.execute()
    ├─ filterFormula.initialize(executionContext)
    ├─ Bitmap result = filterFormula.compute()        ◄── lazy, memoized evaluation
    ├─ Sorters sort & slice the bitmap
    ├─ ExtraResultProducers fabricate facets, histograms, hierarchy stats, …
    └─ Entity bodies fetched for final page → EvitaResponse
```

**Key design properties:**

- **Two-phase architecture** — planning builds the formula tree (cheap); execution evaluates it (lazy, memoized).
- **Translator pattern** — `FilterByVisitor` holds a static map
  `FilterConstraint class → FilteringConstraintTranslator`. Each translator converts exactly one constraint type
  into one or more `Formula` nodes. This is the primary extension point for new filter constraints.
- **Post-processing pipeline** — after the raw tree is built, registered `FormulaPostProcessor` instances transform
  it. The `FormulaOptimizer` prunes dead branches; the `FormulaDeduplicator` collapses structurally identical
  sub-trees so that memoized results are shared.
- **Result flow** — the root `Bitmap` is consumed by sorters (for ordering), by extra-result producers
  (for counts), and by the entity fetcher (for the final page). The formula tree itself is also traversed by
  extra-result producers (e.g., facet-summary needs to locate `UserFilterFormula` nodes).

### Relevant source files

| Component                     | Path                                                                                     |
|-------------------------------|------------------------------------------------------------------------------------------|
| `Formula` interface           | `evita_engine/…/query/algebra/Formula.java`                                              |
| `AbstractFormula`             | `evita_engine/…/query/algebra/AbstractFormula.java`                                      |
| `AbstractCacheableFormula`    | `evita_engine/…/query/algebra/AbstractCacheableFormula.java`                              |
| `FilterByVisitor`             | `evita_engine/…/query/filter/FilterByVisitor.java`                                       |
| `FilteringConstraintTranslator` | `evita_engine/…/query/filter/translator/FilteringConstraintTranslator.java`            |
| `QueryPlanner`                | `evita_engine/…/query/QueryPlanner.java`                                                 |
| `QueryPlan`                   | `evita_engine/…/query/QueryPlan.java`                                                    |
| `FormulaOptimizer`            | `evita_engine/…/query/filter/FormulaOptimizer.java`                                      |
| `FormulaDeduplicator`         | `evita_engine/…/query/filter/FormulaDeduplicator.java`                                   |
| Base formulas (AND, OR, …)    | `evita_engine/…/query/algebra/base/`                                                     |
| Cache system                  | `evita_engine/…/cache/`                                                                  |

## Anatomy of a formula

> **Performance notice:** The formula framework is the most performance-sensitive part of the engine — every
> query evaluation walks the formula tree, and hot formulas are evaluated millions of times per second. All
> implementations **must**:
>
> - **Avoid Java streams** — write index-based loops instead; streams allocate iterator objects, lambda
>   capture objects and intermediate arrays on every invocation.
> - **Avoid unnecessary autoboxing** — use primitive arrays (`int[]`, `long[]`) and primitive-typed local
>   variables; never pass primitives through generic (`Object`-based) APIs such as `Objects.hash()`.
> - **Minimize memory allocations** — reuse shared constants (e.g., `EmptyBitmap.INSTANCE`,
>   `EMPTY_FORMULA_ARRAY`), pre-size collections and `StringBuilder` instances, and prefer stack-local
>   computation over temporary object creation.


### Inheritance hierarchy

```
TransactionalDataRelatedStructure          ◄── cost / detached identity contract
    └── Formula                            ◄── compute(), accept(), getCloneWithInnerFormulas(), …
        └── AbstractFormula                ◄── memoization, lazy identity, default cost model
            └── AbstractCacheableFormula   ◄── base for result-bearing formulas
```

Choose your base class based on the formula's role:

| Base class                   | When to use                                                        |
|------------------------------|--------------------------------------------------------------------|
| `AbstractCacheableFormula`   | Formula produces a supported bitmap or bitmap-and-price tuple. |
| `AbstractFormula`            | Formula stays query-local, for example a decorator or a producer of unsupported side outputs. |

Additionally, implement marker interfaces as needed:

| Interface                   | Effect                                                                   |
|-----------------------------|--------------------------------------------------------------------------|
| `ChildrenDependentFormula`  | Formula is meaningless without children — optimizer drops it when all children are pruned. |

### Lifecycle

1. **Construction** — set formula-specific fields, call `initFields(innerFormulas)`.
2. **Initialization** — `initialize(QueryExecutionContext)` propagates the execution context down the tree.
   Estimated cost is available after `initFields()`; identity is built lazily on the first key request.
3. **Post-processing** — zero or more `FormulaPostProcessor` visitors may clone/restructure the tree.
4. **Computation** — `compute()` is called on the root, which recursively evaluates the tree. Results are
   memoized per node.
5. **Cleanup** — `clearMemory()` resets memoized results and costs. The detached key remains available.

## Writing a new formula: step by step

### 1. Generate a unique class ID

Every concrete formula class must return a unique `long` constant from `getClassId()`. This constant:

- **must not change** over time for the same class,
- **must not be inherited** from a superclass,
- **must be different** for every leaf class.

It discriminates the concrete holder type in `FormulaKey`. Formulas, bitmap suppliers, hierarchy predicates,
histogram computers and entity filtering alternatives each declare their own identifier and identity record.

Generate it, never invent it and never copy a sibling's:

```shell
tools/generate-class-id.sh io.evitadb.core.query.algebra.base.AndFormula
```

The script derives the value the way a `serialVersionUID` is derived, but seeds it on the fully qualified
class name alone, so it is reproducible and stays stable across every later edit to the class. Do not use
the IDE's `serialVersionUID` action instead: that value is derived from the class signature and changes
whenever a member is added, which the first rule above forbids. `FormulaClassIdUniquenessTest` fails the
build if two classes ever end up with the same constant.

```java
private static final long CLASS_ID = -7493244674442362190L;

@Override
protected long getClassId() {
    return CLASS_ID;
}
```

### 2. Implement the constructor

Call `initFields(innerFormulas)` at the end of the constructor, after all formula-specific fields are set.
Validate preconditions with `Assert.isTrue()`.

```java
public MyFormula(@Nonnull Formula innerFormula, @Nonnull String attributeKey) {
    Assert.notNull(attributeKey, "Attribute key must not be null!");
    this.attributeKey = attributeKey;
    this.initFields(innerFormula);
}
```

For cacheable formulas with bitmap-based inputs, provide a secondary constructor accepting `Bitmap[]`:

```java
public MyFormula(@Nonnull Bitmap... bitmaps) {
    this.bitmaps = bitmaps;
    this.initFields(); // no inner formulas
}
```

### 3. Implement `computeInternal()`

This is the core computation. It is called exactly once per formula lifetime (result is memoized by
`AbstractFormula.compute()`).

```java
@Nonnull
@Override
protected Bitmap computeInternal() {
    // For bitmap-based mode:
    if (this.bitmaps != null) {
        return RoaringBitmapBackedBitmap.and(this.bitmaps);
    }
    // For formula-based mode:
    final Bitmap[] results = new Bitmap[innerFormulas.length];
    for (int i = 0; i < innerFormulas.length; i++) {
        results[i] = innerFormulas[i].compute();
    }
    return RoaringBitmapBackedBitmap.and(results);
}
```

**Rules:**

- Return `EmptyBitmap.INSTANCE` for empty results — never allocate a new empty bitmap.
- For AND-like operations, sort children by estimated cost and short-circuit on empty intermediate results.
- For OR-like operations, all children must be evaluated (no short-circuit).
- Never call `compute()` during construction, `initFields()`, or cost estimation — it triggers the full
  evaluation chain.

### 4. Implement `getCloneWithInnerFormulas()`

Used by `FormulaCloner` to rebuild the tree after transformation. Must preserve **all formula-specific state**
while replacing inner formulas.

```java
@Nonnull
@Override
public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
    Assert.isTrue(innerFormulas.length == 1, "Expected exactly one inner formula!");
    return new MyFormula(innerFormulas[0], this.attributeKey);
}
```

**Rules:**

- Preserve all configuration fields (attribute keys, price contexts, locales, …).
- Do **not** preserve memoized results or cost caches.
- Handle edge cases for container formulas:
  - 0 children → return `EmptyFormula.INSTANCE`
  - 1 child → return the child directly (optimization)
  - 2+ children → return a new formula instance

### 5. Implement the cost model

Each formula exposes three cost levels:

| Method               | When available        | Purpose                                            |
|----------------------|-----------------------|----------------------------------------------------|
| `getEstimatedCost()` | After `initFields()`  | Cheap upfront estimate for index selection ranking. |
| `getCost()`          | After `compute()`     | Exact cost, summed by parents and reported by query telemetry. |
| `getOperationCost()` | Always                | Per-element multiplier, constant per formula type. |

#### `getOperationCost()`

Return a constant reflecting the relative expense of this operation. Benchmark against a no-op copy:

| Formula type    | Operation cost | Rationale                           |
|-----------------|----------------|-------------------------------------|
| `EmptyFormula`  | `0`            | No computation at all.              |
| `ConstantFormula` | `1`          | Trivial bitmap return.              |
| `AndFormula`    | `9`            | Efficient bitmap intersection.      |
| `NotFormula`    | `9`            | Bitmap subtraction.                 |
| `OrFormula`     | `13`           | More expensive union.               |
| `RangeCountFormula` | `1462`     | Per-element signed-count scatter over a range index's endpoint families. |

The `AndFormula`/`NotFormula`/`OrFormula` values were derived from the JMH benchmark in
`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/FormulaCostMeasurement.java`.
When introducing a new formula, add a corresponding benchmark method to that class, run the suite,
and calibrate `getOperationCost()` relative to the existing results (COST 1 ≈ 1 mil. ops/s).
`RangeCountFormula` has no benchmark method in that class; its `1462` is instead derived directly
against the pair of formulas it replaced, on a measured production shape — see the derivation in
`RangeCountFormula#getOperationCost()`'s own javadoc, mirrored in
`documentation/adr/2026-09-18-range-index-counting-kernel.md`. The algorithm it prices is explained in
[Range counting kernel](../algorithms/range-counting-kernel.md).

#### `getEstimatedCardinality()`

Returns a rough cardinality estimate of the result without triggering computation.

**Important:** the estimate must respect the **worst-case scenario** — it should return an upper bound on the
expected result size rather than an optimistic or average guess. The cost model multiplies
`getOperationCost() × getEstimatedCardinality()` to produce the upfront cost estimate. An underestimate can cause
the planner to pick a more expensive execution path (e.g., choosing a larger index when a smaller one would suffice).

Conventions (each returning the worst-case upper bound):

- **AND-like** (intersection) → return `min` of children's cardinalities (worst case: everything matches
  the smallest input).
- **OR-like** (union) → return `sum` of children's cardinalities (worst case: no overlap between children).
- **NOT-like** (subtraction) → return the cardinality of the base (subtracted-from) formula (worst case:
  nothing is subtracted).
- **Wrapper/decorator** → delegate to the single child.
- **Bitmap-based leaf** → return `bitmap.size()`.

#### Overriding `getEstimatedBaseCost()`

If the formula has internal data beyond inner formulas (e.g., the bitmap array `FacetGroupAndFormula` scans
directly), override this to include its cost. Default is `0L`.

### 6. Declare identity

Every concrete formula declares `identity()` and `getClassId()` itself. `identity()` returns an immutable
`IdentityRecord` containing every input that can affect results or side outputs. Formula children are included
in their supplied order by `AbstractFormula#getKey()`; the record describes the holder's additional inputs.

```java
@Nonnull
@Override
protected IdentityRecord identity() {
    return new Identity(this.attributeKey);
}

public record Identity(@Nonnull String attributeKey) implements IdentityRecord {
    @Override
    public long classId() {
        return CLASS_ID;
    }

    @Override
    public void describeTo(@Nonnull KeyAccumulator accumulator) {
        accumulator.add(this.attributeKey);
    }
}
```

Use detached values from the identity domain: primitives, strings, supported immutable domain values,
`IdentityToken`, `TokenList`, `ValueList`, or other `FormulaKey` values. Copy mutable inputs at the identity
boundary. Every record with components declares `describeTo()` and feeds each component to `KeyAccumulator`.

Raw bitmap inputs use tokens. A transactional producer contributes its version id. Small content-only bitmaps
contribute copied exact content; large unowned content makes the holder opaque. A derived token requires a
producer version and a role that together determine the named contents. Lists preserve multiplicity; canonical
lists explicitly normalize operand order where the operation permits it.

Every instance field must either correspond to an identity record component or carry `@IdentityExempt`:
`STATE` for memoized execution state, `DERIVED` for values determined by identity inputs, or `DECOMPOSED` for
inputs represented by detached components. Register an identity fixture for every construction mode so the
contract test verifies sensitivity, clone behavior, opacity and the declared identifier pair.

### 7. String representation

Implement `toString()` for tree printing and optionally override `toStringVerbose()` for detailed diagnostics:

```java
@Override
public String toString() {
    return "MY_OP[" + attributeKey + "]";
}

@Nonnull
@Override
public String toStringVerbose() {
    return toString() + " → " + (memoizedResult == null ? "?" : memoizedResult.size()) + " records";
}
```

## Identity and reuse

`FormulaKey` contains a class discriminator, a detached identity record and ordered child keys. Its 64-bit hash
is a prefilter; equality compares exact identity and children. Equal hashes alone do not establish equality.
Keys are immutable and confined to the JVM lifetime. A plan's diagnostic hash is process-local.

`AbstractFormula#getKey()` creates and memoizes the key on demand. Construction and unbound computation do not request
keys. A bound computation reads its memoized key for result-cache lookup. `clearMemory()` clears results and
measured costs without replacing the key. Clones preserve semantic inputs and create their own execution state. Clearing a cacheable formula also removes its result-cache binding;
its retained key may describe an earlier mutable facet delegate. Hypothetical facet trees are never analysed or bound.

Opaque inputs bind identity to the holder instance. Oversized exact values retain exact equality but mark the
key query-local. Query-local status propagates through children and side keys. Key weight accounts for retained
identity content. An identity containing a predicate or expansion callback whose semantics cannot be described
must be opaque.

`FormulaDeduplicator` interns formulas in a map keyed by `FormulaKey`, rebuilding changed parents after their
children are canonical. Its pre-order hook can replace an already completed duplicate before descending again.
Equal subtrees share computed results within a query. Every formula's children are ordered, including AND and OR
in formula mode. Canonical raw bitmap operand lists express their own ordering rule inside the identity record.

### Caching mechanism

The engine installs `NoCacheSupervisor` by default. With `cache.enabled: true`, it owns one
`FormulaCacheSupervisor` backed by Caffeine. Formula analysis binds eligible instances after deduplication,
without substituting nodes. A hit fills the existing node's bitmap memo, paid cost and any price outputs as one
tuple. It records zero cost for that node and avoids computing its children. Structured telemetry still shows
those children's structure, with no computed outcomes unless another reader evaluated them.

Eligibility is resolved independently for each planning root: its session must be read-only, its pinned catalog
must be ALIVE, its request must target an entity type, and no transaction may be bound to the thread. Nested
contexts inherit that decision. Body-fetch roots decide independently for their own requests. Execution contexts
also exclude eager planning, prefetch filtering and transactions bound when the context is created. Verification
executions of the query's own plans follow ordinary cache rules; the key-free baseline always uses
`NoCacheSupervisor` and requests no keys for the result cache.

Candidates are the subclasses of `AbstractCacheableFormula` and the attribute and price histogram computers.
Estimated cost selects candidates before lookup. Query-local and overweight keys remain unbound. Entries contain
detached keys and immutable bitmap, price or histogram tuples. Both price arrays are frozen in entity-primary-key
order before publication; SUM component maps are included in the charge. Consumers must not change retained bitmap
contents, price arrays, records, component maps or histogram buckets. Sorters work on fresh arrays or bitmap clones.

The weigher measures the complete retained graph using long arithmetic and refuses unsupported shapes, entries
above one percent of the configured budget and charges above the integer range. The aggregate budget is a target
with transient overshoot; queries' own memos and concurrent duplicate computations are outside it. Idle expiry
uses best-effort periodic maintenance. Close releases retained entries and cancels maintenance. Committed producer
IDs provide freshness, so catalog writes and catalog lifecycle operations need no cache purge.

A configured fraction of hits is recomputed with the cache suspended across execution contexts and body-fetch
roots. Semantic comparison includes bitmap membership, complete price tuples and SUM components, and strict
histogram contents. An equal sample keeps its recomputed tuple and normal cost. A confirmed mismatch fails the
detecting request, disables the cache for this engine's lifetime, clears retained entries and records a bounded
text witness. It does not retract memos already injected into other in-flight queries. Sampling can reuse memos
already computed in the query, so it is not an independent oracle. Counters and effective activity are available
through the supervisor's statistics accessor; `EngineSettings.queryCacheEnabled` reflects the effective state.

## Visitor pattern

`FormulaVisitor` is the traversal mechanism for formula trees. The key design choice: **traversal is
visitor-driven**. `Formula.accept(visitor)` delegates to `visitor.visit(formula)` but does **not** recurse
into children. The visitor decides whether and how to recurse.

This gives visitors full control over:

- Depth-first vs breadth-first traversal
- Early termination
- Selective sub-tree skipping

### FormulaPostProcessor

`FormulaPostProcessor` extends `FormulaVisitor` and adds `getPostProcessedFormula()` which returns the
transformed tree after traversal. Usage:

```java
FormulaPostProcessor processor = new MyPostProcessor();
rootFormula.accept(processor);                          // traverse + transform
Formula result = processor.getPostProcessedFormula();   // retrieve result (resets state)
```

Post-processors are registered during filter translation via `FilterByVisitor.registerFormulaPostProcessor()`
and are applied in order by `FilterByVisitor.constructFinalFormula()`. The `FormulaDeduplicator` is always
applied last.

### Key visitor implementations

| Visitor                           | Purpose                                              |
|-----------------------------------|------------------------------------------------------|
| `FormulaCloner`                   | Rebuilds formula tree with selective mutations.       |
| `FormulaOptimizer`                | Prunes empty branches, unwraps redundant containers.  |
| `FormulaDeduplicator`             | Collapses duplicate sub-trees for memoization reuse.  |
| `FormulaLocator` (`FormulaFinder`)| Finds formula nodes by type in the tree.             |
| `PrettyPrintingFormulaVisitor`    | Serializes tree as human-readable text.              |

## Testing a new formula

Formula tests live in `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/query/algebra/`.

### Setting up test data

Create bitmaps with `ArrayBitmap` and wrap them in `ConstantFormula` for formula-based tests:

```java
// Direct bitmap mode
new AndFormula(
    new ArrayBitmap(1, 3, 4, 5, 8),
    new ArrayBitmap(1, 2, 4, 8)
);

// Formula-wrapping mode
new AndFormula(
    new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 3, 4, 5, 8))),
    new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 4, 8)))
);
```

### Result verification

Always verify `compute()` result using `getArray()`:

```java
assertArrayEquals(
    new int[]{1, 4},
    formula.compute().getArray()
);
```

### What to test

| Aspect                     | How to test                                                                       |
|----------------------------|-----------------------------------------------------------------------------------|
| **Computation correctness** | Verify `compute().getArray()` against expected primary keys.                     |
| **Empty inputs**           | Test with empty bitmaps, single-element sets, fully overlapping sets.             |
| **Edge cases**             | Non-overlapping sets, single child, maximum cardinality.                          |
| **Memoization**            | `assertSame(formula.compute(), formula.compute())` — same object reference.       |
| **clearMemory()**          | After reset, `compute()` must produce the same result (fresh evaluation).         |
| **Cloning**                | `getCloneWithInnerFormulas()` must preserve behavior: `assertArrayEquals(original.compute().getArray(), clone.compute().getArray())`. |
| **Identity determinism**   | `assertEquals(formula.getKey(), identicalFormula.getKey())`.                       |
| **Identity sensitivity**   | Different semantic inputs produce different keys.                               |
| **Cardinality estimate**   | Verify `getEstimatedCardinality()` returns sensible bounds.                       |
| **Cost ordering**          | `getEstimatedCost()` must be less than or equal to `getCost()` for typical inputs.|
| **Semantic equivalence**   | When testing optimizers: `assertArrayEquals(original.compute().getArray(), optimized.compute().getArray())`. |
| **Tree structure**         | Use `assertInstanceOf` and `getInnerFormulas()` to verify post-processed trees.   |

### Initialization in tests

Formulas require execution initialization before context-dependent computation. Use `TestQueryExecutionContext` or create a
minimal context:

```java
formula.initialize(new TestQueryExecutionContext(entitySchema, query));
```

For context-free formulas, `compute()` works without execution initialization. Constructors must call
`initFields()` before estimated costs or keys are requested.

## Common anti-patterns

| Anti-pattern                                             | Correct approach                                         |
|----------------------------------------------------------|----------------------------------------------------------|
| Calling `compute()` during construction or cost estimation | Use cardinality estimates; compute is lazy and expensive. |
| Allocating new empty bitmaps                             | Return `EmptyBitmap.INSTANCE`.                           |
| Forgetting to preserve state in `getCloneWithInnerFormulas()` | Clone **all** configuration fields.                  |
| Not validating constructor arguments                     | Use `Assert.isTrue()` / `Assert.notNull()`.              |
| Inheriting `getClassId()` from a superclass              | Each leaf class must define its own unique constant.      |
| Omitting result or side-output inputs from identity | Declare detached components and verify them with an identity fixture. |
| Using `new StringBuilder()` without capacity             | Always estimate capacity: `new StringBuilder(64)`.       |
| Using `Objects.hash()` with primitives                   | Use `31 * result + Type.hashCode(primitive)`.            |
| Using Java streams in hot paths                          | Write index-based `for` loops — streams allocate iterators, lambdas, and intermediate arrays per call. |
| Unnecessary autoboxing (`int` → `Integer`)               | Use primitive arrays and primitive-typed locals; avoid generic APIs that force boxing. |
| Allocating temporary objects in `compute()` loops        | Pre-size arrays, reuse shared constants, prefer stack-local computation. |

## Summary checklist for a new formula

- [ ] Extends `AbstractFormula` or `AbstractCacheableFormula`
- [ ] Implements marker interfaces as appropriate (`ChildrenDependentFormula`, …)
- [ ] Unique `CLASS_ID` constant returned from `getClassId()`
- [ ] Constructor validates preconditions and calls `initFields()` last
- [ ] `computeInternal()` returns correct bitmap, uses `EmptyBitmap.INSTANCE` for empty results
- [ ] `getCloneWithInnerFormulas()` preserves all configuration, handles 0/1/N children edge cases
- [ ] `getOperationCost()` returns a meaningful constant
- [ ] `getEstimatedCardinality()` returns a sensible estimate (min for AND, sum for OR, delegate for wrappers)
- [ ] `identity()` declares every semantic input beyond the ordered children
- [ ] Identity record declares `describeTo()` and matches the holder's `CLASS_ID`
- [ ] Identity fixture covers construction modes, changed inputs, clone paths and opaque inputs
- [ ] `toString()` and optionally `toStringVerbose()` implemented
- [ ] Tests cover computation correctness, edge cases, memoization, cloning, identity determinism, and cost ordering
