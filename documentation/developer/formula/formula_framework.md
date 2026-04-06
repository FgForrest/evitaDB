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
TransactionalDataRelatedStructure          ◄── cost / hash / transactional-id contract
    └── Formula                            ◄── compute(), accept(), getCloneWithInnerFormulas(), …
        └── AbstractFormula                ◄── memoization, hash computation, default cost model
            └── AbstractCacheableFormula   ◄── computation callback for cache integration
```

Choose your base class based on the formula's role:

| Base class                   | When to use                                                        |
|------------------------------|--------------------------------------------------------------------|
| `AbstractCacheableFormula`   | Formula produces a bitmap result that can be stored in the cache.  |
| `AbstractFormula`            | Formula is a decorator/marker or carries side-data that must not be flattened into a cached bitmap. |

Additionally, implement marker interfaces as needed:

| Interface                   | Effect                                                                   |
|-----------------------------|--------------------------------------------------------------------------|
| `CacheableFormula`          | Enables cache promotion (automatically satisfied when extending `AbstractCacheableFormula`). |
| `NonCacheableFormula`       | Prevents this formula **and all ancestors** from being cached (upward poisoning). |
| `NonCacheableFormulaScope`  | Prevents **all children** from being cached (downward blocking); formula itself may still be cached. |
| `ChildrenDependentFormula`  | Formula is meaningless without children — optimizer drops it when all children are pruned. |

### Lifecycle

1. **Construction** — set formula-specific fields, call `initFields(innerFormulas)`.
2. **Initialization** — `initialize(QueryExecutionContext)` propagates the execution context down the tree.
   Hash and estimated cost are already available after `initFields()`.
3. **Post-processing** — zero or more `FormulaPostProcessor` visitors may clone/restructure the tree.
4. **Computation** — `compute()` is called on the root, which recursively evaluates the tree. Results are
   memoized per node.
5. **Cache analysis** — `FormulaCacheVisitor` may instrument the tree with computation callbacks for the
   `CacheSupervisor`.
6. **Cleanup** — `clearMemory()` resets memoized results and cost caches; structural hash and transactional
   IDs are *not* reset.

## Writing a new formula: step by step

### 1. Choose a unique class ID

Every concrete formula class must return a unique `long` constant from `getClassId()`. This constant:

- **must not change** over time for the same class,
- **must not be inherited** from a superclass,
- **must be different** for every leaf class.

It is a critical component of the structural hash.

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
    super(null); // no computation callback (or pass one for cacheable formulas)
    Assert.notNull(attributeKey, "Attribute key must not be null!");
    this.attributeKey = attributeKey;
    this.initFields(innerFormula);
}
```

For cacheable formulas with bitmap-based inputs, provide a secondary constructor accepting `Bitmap[]`:

```java
public MyFormula(long indexTransactionId, @Nonnull Bitmap... bitmaps) {
    super(null);
    this.indexTransactionId = new long[]{indexTransactionId};
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
| `getCost()`          | After `compute()`     | Exact cost for cache ROI calculation.              |
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
| `DisentangleFormula` | `2130`    | Element-level deduplication.        |
| `JoinFormula`   | `2560`         | Expensive merge with duplicates.    |

These values were derived from the JMH benchmark in
`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/FormulaCostMeasurement.java`.
When introducing a new formula, add a corresponding benchmark method to that class, run the suite,
and calibrate `getOperationCost()` relative to the existing results (COST 1 ≈ 1 mil. ops/s).

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

If the formula has internal data beyond inner formulas (e.g., a control bitmap in `DisentangleFormula`), override
this to include its cost. Default is `0L`.

### 6. Implement hashing

#### `includeAdditionalHash(LongHashFunction)`

Return a hash covering formula-specific state that affects `compute()` output but is **not** already covered by
inner formula hashes. Inner formulas are implicitly included — do not hash them here.

```java
@Override
protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
    return hashFunction.hashChars(this.attributeKey);
}
```

For bitmap-based formulas, hash the bitmap transactional IDs:

```java
@Override
protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
    if (this.bitmaps.length > EXCESSIVE_HIGH_CARDINALITY) {
        return hashFunction.hashLongs(this.indexTransactionId);
    }
    final long[] ids = new long[this.bitmaps.length];
    int idx = 0;
    for (Bitmap bitmap : this.bitmaps) {
        if (bitmap instanceof TransactionalLayerProducer<?, ?> tlp) {
            ids[idx++] = tlp.getId();
        }
    }
    final long[] result = idx == ids.length ? ids : Arrays.copyOf(ids, idx);
    Arrays.sort(result);
    return hashFunction.hashLongs(result);
}
```

#### `isFormulaOrderSignificant()`

Override and return `true` only for non-commutative operations (e.g., `NotFormula` where `A NOT B ≠ B NOT A`).
When `false` (default), inner formula hashes are sorted before structural hash computation, ensuring
`A AND B` and `B AND A` produce the same hash.

### 7. Implement transactional ID gathering

#### Why transactional IDs matter

Every bitmap at the leaf of a formula tree originates from an index data structure. Each index instance carries
a **transactional ID** — a monotonically increasing version number that changes whenever a write transaction
modifies the underlying data. The distinct, sorted set of transactional IDs gathered from all leaf bitmaps is
hashed into a single `transactionalIdHash` that becomes a **key component of the cache key** (see
[Cache keys](#cache-keys)).

When a query arrives, the cache looks up a cached record by the formula's structural hash and then **compares
the stored `transactionalIdHash` against the current formula's `transactionalIdHash`**. If any source index has
moved to a new version (i.e., a transaction committed new data), the transactional IDs change, the hash changes,
and the cache correctly returns a miss — preventing stale results from being served. Without correct transactional
ID gathering, the cache layer would have no way to distinguish a formula built on old data from one built on
current data, and could silently return outdated results.

In short: transactional IDs are the cache **freshness guarantee**. Getting them wrong means either stale cache
hits (IDs missing → hash doesn't change when data does) or unnecessary cache misses (extra IDs included → hash
changes too often).

#### Implementation

Override `gatherBitmapIdsInternal()` if the formula wraps bitmaps directly (rather than delegating to inner
formulas). The default implementation recursively gathers IDs from inner formulas — this is correct for formulas
whose computation depends solely on their children's results.

```java
@Nonnull
@Override
protected long[] gatherBitmapIdsInternal() {
    if (this.bitmaps == null) {
        return super.gatherBitmapIdsInternal();
    }
    if (this.bitmaps.length > EXCESSIVE_HIGH_CARDINALITY) {
        return this.indexTransactionId; // use aggregate ID to avoid excessive allocation
    }
    final long[] ids = new long[this.bitmaps.length];
    int idx = 0;
    for (Bitmap bitmap : this.bitmaps) {
        if (bitmap instanceof TransactionalLayerProducer<?, ?> tlp) {
            ids[idx++] = tlp.getId();
        }
    }
    return idx == ids.length ? ids : Arrays.copyOf(ids, idx);
}
```

The `EXCESSIVE_HIGH_CARDINALITY` threshold (100) triggers a fallback to the index-level transactional ID instead
of enumerating individual bitmap IDs — this avoids excessive memory allocation in `CacheEden`.

### 8. String representation

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

## Hash computation in depth

The structural hash uniquely identifies a formula tree's **logical shape**. Two formula trees that would always
produce the same result from the same data should have the same hash.

```
hash = xxHash(
    classId,
    isFormulaOrderSignificant()
        ? [innerFormula₁.hash, innerFormula₂.hash, …]                  // ordered
        : sort([innerFormula₁.hash, innerFormula₂.hash, …]),           // canonical order
    includeAdditionalHash()
)
```

The **transactional ID hash** identifies the *data sources* the formula depends on. It is computed from the
distinct, sorted array of `TransactionalLayerCreator.getId()` values gathered from all bitmaps:

```
transactionalIdHash = xxHash(distinct(sort(gatherTransactionalIds())))
```

Both hashes are computed once during `initFields()` and are immutable for the formula's lifetime.
`clearMemory()` does **not** reset them.

### Hash function

All hash computation uses `LongHashFunction.xx3()` (xxHash, zero-allocation), obtained from
`CacheSupervisor.createHashFunction()`.

## Caching mechanism

### Overview

evitaDB uses a two-stage cache — **CacheAnteroom** (observation) and **CacheEden** (storage) — to memoize
expensive formula sub-trees across queries.

```
Formula tree
    │
    ▼
FormulaCacheVisitor traverses tree
    │
    ├─ Is formula CacheableFormula?
    ├─ Is estimatedCost ≥ minimalComplexityThreshold?
    ├─ Not inside NonCacheableFormulaScope?
    ├─ No NonCacheableFormula descendant?
    │
    ▼
CacheAnteroom.register()
    │
    ├─ Cache hit in CacheEden? ──────────────────────► Return cached FlattenedFormula
    │     (transactionalIdHash must match)
    │
    └─ Cache miss → create instrumented clone
         via getCloneWithComputationCallback()
         │
         ▼
    Query executes, formula.compute() fires
         │
         ▼
    Callback records costToPerformanceRatio + sizeEstimate
    as CacheRecordAdept in CacheAnteroom
         │
         ▼
    Periodic evaluation (when anteroom fills or timer fires)
         │
         ▼
CacheEden.evaluateAdepts()
    ├─ Merge adepts + existing cached records
    ├─ Sort by spaceToPerformanceRatio (descending)
    ├─ Promote top candidates within memory budget
    └─ Evict cold records (unused for ≥ 3 evaluation intervals)
```

### Cache keys

The cache uses a **three-level key**:

1. **Record key** — `xxHash(catalogName, entityType, formula.getHash())` — scopes cache entries
   per catalog and entity type.
2. **Structural hash** (`formula.getHash()`) — identifies the formula tree shape. Same query structure
   → same hash.
3. **Transactional ID hash** (`formula.getTransactionalIdHash()`) — validates freshness. If the underlying
   data changed (transaction committed), the transactional IDs change, the hash changes, and the cached
   entry is a miss.

### Cache record lifecycle

| State             | Description                                                              |
|-------------------|--------------------------------------------------------------------------|
| **Adept**         | Candidate in anteroom; usage count being tracked.                        |
| **Promoted**      | Moved to CacheEden; awaiting first computation to capture payload.       |
| **Initialized**   | Payload (`FlattenedFormula`) stored; future queries return it directly.   |
| **Cooling**       | In cache but unused for consecutive evaluation intervals.                |
| **Evicted**       | Removed after `≥ 3` unused intervals or exceeded memory budget.          |

### Cache invalidation

Invalidation is **automatic and transactional**:

1. A write transaction modifies an entity → the affected index bitmap gets a new transactional layer ID.
2. On the next read query, the formula is reconstructed with new transactional IDs.
3. `CacheEden.getCachedRecord()` compares `cachedRecord.transactionalIdHash` vs `formula.transactionalIdHash`.
4. Mismatch → cache miss → formula is re-evaluated → new result may be promoted.

There is no explicit invalidation API. `clearMemory()` on a formula resets its memoized result but does not
interact with the cache system.

### NonCacheableFormula vs NonCacheableFormulaScope

These two marker interfaces control caching in opposite directions:

| Interface                  | Direction  | Effect                                                                    |
|----------------------------|------------|---------------------------------------------------------------------------|
| `NonCacheableFormula`      | ↑ Upward   | Prevents this formula **and all ancestors** from being cached.            |
| `NonCacheableFormulaScope` | ↓ Downward | Prevents **all descendants** from being cached; formula itself may still be cached. |

**Example**: `UserFilterFormula` implements **both**. The facet-summary extra-result producer needs to locate
and inspect the user-filter sub-tree in its original form. Replacing any part of it with a flattened cached
payload would break that traversal.

### toSerializableFormula()

When a formula is promoted, `toSerializableFormula(formulaHash, hashFunction)` is called to materialize the
computation result into a lightweight `CachePayloadHeader`. The default implementation in
`AbstractCacheableFormula` produces a `FlattenedFormula`:

```java
new FlattenedFormula(
    formulaHash,
    getTransactionalIdHash(),
    distinct(sort(gatherTransactionalIds())),
    compute()  // already memoized
)
```

Specialized variants exist for price-filtered queries that must preserve side-data:

- `FlattenedFormulaWithFilteredPrices` — adds `FilteredPriceRecords` + `PriceEvaluationContext`.
- `FlattenedFormulaWithFilteredOutRecords` — tracks filtered-out entity IDs.
- `FlattenedFormulaWithFilteredPricesAndFilteredOutRecords` — combined.

If your formula carries side-data that downstream consumers need (sorters, extra-result producers), you must
either override `toSerializableFormula()` to include that data, or mark the formula as `NonCacheableFormula`.

### Cache configuration

| Parameter                       | Purpose                                                    |
|---------------------------------|------------------------------------------------------------|
| `cacheSizeInBytes`              | Total cache memory budget (default ~1 GB).                 |
| `anteroomRecordCount`           | Buffer size before triggering evaluation.                  |
| `minimalComplexityThreshold`    | Minimum `getEstimatedCost()` for cache candidacy.          |
| `minimalUsageThreshold`         | Minimum usage count before promotion is considered.        |
| `reevaluateEachSeconds`         | Evaluation interval (default 60 s).                        |

Single records larger than 1 MB (`MAX_BUFFER_SIZE`) are rejected.

### Read-only session requirement

Caching is active **only for read-only sessions**. Write sessions bypass the cache entirely because they may
contain client-specific uncommitted modifications.

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
| `FormulaCacheVisitor`             | Instruments cacheable formulas with callbacks.        |
| `FormulaLocator` (`FormulaFinder`)| Finds formula nodes by type in the tree.             |
| `PrettyPrintingFormulaVisitor`    | Serializes tree as human-readable text.              |

## Testing a new formula

Formula tests live in `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/query/algebra/`.

### Setting up test data

Create bitmaps with `ArrayBitmap` and wrap them in `ConstantFormula` for formula-based tests:

```java
private static final long INDEX_TRANSACTION_ID = 1L;

// Direct bitmap mode
new AndFormula(INDEX_TRANSACTION_ID,
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
| **Hash determinism**       | `assertEquals(formula.getHash(), identicalFormula.getHash())`.                    |
| **Hash sensitivity**       | Different configuration → different hash.                                         |
| **Cardinality estimate**   | Verify `getEstimatedCardinality()` returns sensible bounds.                       |
| **Cost ordering**          | `getEstimatedCost()` must be less than or equal to `getCost()` for typical inputs.|
| **Semantic equivalence**   | When testing optimizers: `assertArrayEquals(original.compute().getArray(), optimized.compute().getArray())`. |
| **Tree structure**         | Use `assertInstanceOf` and `getInnerFormulas()` to verify post-processed trees.   |
| **Cache behavior**         | Use `FormulaCacheVisitor.analyse()` with a `CacheAnteroom` to verify registration.|

### Initialization in tests

Formulas require initialization before hash/cost methods work. Use `TestQueryExecutionContext` or create a
minimal context:

```java
formula.initialize(new TestQueryExecutionContext(entitySchema, query));
```

For simple computation-only tests, `compute()` works without initialization — but `getHash()`,
`getEstimatedCost()`, and `gatherTransactionalIds()` will throw if `initFields()` has not been called.

## Common anti-patterns

| Anti-pattern                                             | Correct approach                                         |
|----------------------------------------------------------|----------------------------------------------------------|
| Calling `compute()` during construction or cost estimation | Use cardinality estimates; compute is lazy and expensive. |
| Allocating new empty bitmaps                             | Return `EmptyBitmap.INSTANCE`.                           |
| Forgetting to preserve state in `getCloneWithInnerFormulas()` | Clone **all** configuration fields.                  |
| Not validating constructor arguments                     | Use `Assert.isTrue()` / `Assert.notNull()`.              |
| Inheriting `getClassId()` from a superclass              | Each leaf class must define its own unique constant.      |
| Hashing inner formulas in `includeAdditionalHash()`      | Inner formulas are already included — hash only additional state. |
| Using `new StringBuilder()` without capacity             | Always estimate capacity: `new StringBuilder(64)`.       |
| Using `Objects.hash()` with primitives                   | Use `31 * result + Type.hashCode(primitive)`.            |
| Using Java streams in hot paths                          | Write index-based `for` loops — streams allocate iterators, lambdas, and intermediate arrays per call. |
| Unnecessary autoboxing (`int` → `Integer`)               | Use primitive arrays and primitive-typed locals; avoid generic APIs that force boxing. |
| Allocating temporary objects in `compute()` loops        | Pre-size arrays, reuse shared constants, prefer stack-local computation. |

## Summary checklist for a new formula

- [ ] Extends `AbstractFormula` or `AbstractCacheableFormula`
- [ ] Implements marker interfaces as appropriate (`CacheableFormula`, `NonCacheableFormula`,
  `ChildrenDependentFormula`, …)
- [ ] Unique `CLASS_ID` constant returned from `getClassId()`
- [ ] Constructor validates preconditions and calls `initFields()` last
- [ ] `computeInternal()` returns correct bitmap, uses `EmptyBitmap.INSTANCE` for empty results
- [ ] `getCloneWithInnerFormulas()` preserves all configuration, handles 0/1/N children edge cases
- [ ] `getOperationCost()` returns a meaningful constant
- [ ] `getEstimatedCardinality()` returns a sensible estimate (min for AND, sum for OR, delegate for wrappers)
- [ ] `includeAdditionalHash()` covers formula-specific state (not inner formulas)
- [ ] `isFormulaOrderSignificant()` returns `true` if operation is non-commutative
- [ ] `gatherBitmapIdsInternal()` overridden if formula wraps bitmaps directly
- [ ] `toString()` and optionally `toStringVerbose()` implemented
- [ ] If cacheable: `toSerializableFormula()` preserves any side-data needed by downstream consumers
- [ ] Tests cover computation correctness, edge cases, memoization, cloning, hash determinism, and cost ordering
