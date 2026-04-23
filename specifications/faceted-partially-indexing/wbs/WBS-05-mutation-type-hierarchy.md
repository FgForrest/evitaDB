# WBS-05: Mutation Type Hierarchy — `MutationContract`, `IndexMutation`, `EntityIndexMutation`, `ReevaluateFacetExpressionMutation`

> **Parent document:** [Conditional Facet Indexing — Problem Analysis](../conditional-facet-indexing.md)

## Objective

Establish a clean mutation type hierarchy that separates engine-internal index mutations from
external API-facing mutations without forcing a leaky shared abstraction. Introduce
`MutationContract` as a taxonomy root, `IndexMutation` as the engine-internal marker interface,
`EntityIndexMutation` as a transport envelope for routing index mutations to target collections,
and `ReevaluateFacetExpressionMutation` as the first concrete leaf mutation.

## Scope

### In Scope

- New `MutationContract` empty root marker interface in `io.evitadb.api.requestResponse.mutation`
  (`evita_api` module)
- Modify existing `Mutation` interface to add `extends MutationContract`
- New `IndexMutation` marker interface in `io.evitadb.index.mutation` (`evita_engine` module)
- New `EntityIndexMutation` transport envelope record in `io.evitadb.index.mutation`
  (`evita_engine` module)
- New `ReevaluateFacetExpressionMutation` concrete record implementing `IndexMutation` in
  `io.evitadb.index.mutation` (`evita_engine` module)
- New `IndexImplicitMutations` record for `EntityIndexLocalMutationExecutor` output
- Integration with the existing `ImplicitMutations` record (unchanged)

### Out of Scope

- Executor strategy interface and dispatch registry (`IndexMutationExecutor`,
  `IndexMutationExecutorRegistry`) — see WBS-06
- Concrete executor implementation (`ReevaluateFacetExpressionExecutor`) — see WBS-07
- Source-side detection (`popIndexImplicitMutations` implementation) — see WBS-08
- Trigger registry (`CatalogExpressionTriggerRegistry`) — see WBS-04
- Dispatch loop changes in `MutationCollector`/`LocalMutationExecutorCollector` — see WBS-10
- Future `IndexMutation` subtypes (e.g., `ReevaluateHistogramExpressionMutation`)
- WAL serialization (index mutations are never written to WAL)
- Schema evolution logic

## Dependencies

### Depends On

- **WBS-03** (Trigger Construction and Evaluation): `DependencyType` enum must be defined before
  `ReevaluateFacetExpressionMutation` can reference it
- **Existing `Mutation` interface**: located in `io.evitadb.api.requestResponse.mutation`
  (`evita_api` module) — must be modified to extend `MutationContract`
- **Existing `Scope` enum**: used as a field in `ReevaluateFacetExpressionMutation`

### Depended On By

- **WBS-04** (Trigger Registry): `CatalogExpressionTriggerRegistry` stores triggers keyed by
  entity type and `DependencyType`
- **WBS-06** (Target-Side Dispatch): `IndexMutationExecutor` dispatches on `IndexMutation`
  subtypes; `IndexMutationExecutorRegistry` maps concrete types to executors
- **WBS-07** (Executor): `ReevaluateFacetExpressionExecutor` processes
  `ReevaluateFacetExpressionMutation` instances
- **WBS-08** (Source-Side Detection): `popIndexImplicitMutations()` creates
  `ReevaluateFacetExpressionMutation` instances wrapped in `EntityIndexMutation` envelopes
- **WBS-10** (Collector/Dispatch): `LocalMutationExecutorCollector` collects
  `EntityIndexMutation` envelopes and dispatches them through the dedicated index mutation path
- Any future `IndexMutation` subtype must extend the hierarchy defined here

## Technical Context

### Design Principle: Two Honest Loops Over One Dishonest Abstraction

`IndexMutation` and `EntityMutation` have fundamentally different processing requirements.
Attempting to unify them under a shared interface (e.g., `EntityTypeBoundMutation`) would
create a leaky abstraction — the dispatch code would immediately branch into completely
different code paths. Instead, they remain independent types with separate dispatch loops.

| Aspect                    | `EntityMutation`                             | `IndexMutation`                        |
|---------------------------|----------------------------------------------|----------------------------------------|
| Creates executors         | Both container + index                       | Only index                             |
| Storage changes           | Yes                                          | No                                     |
| Schema evolution          | Yes                                          | No                                     |
| Conflict keys             | Yes                                          | No                                     |
| WAL                       | Root-level only                              | Never                                  |
| Returns updated entity    | Optional                                     | Never                                  |

This table makes clear that any shared interface would need to immediately distinguish
between the two branches, providing no real abstraction benefit.

### `MutationContract` — Unified Mutation Taxonomy Root

The existing `Mutation` interface is a sealed interface with methods (`operation()`,
`collectConflictKeys()`) that make no sense for engine-internal index mutations. Renaming it
would affect 50+ files across all layers. Instead, a new empty root interface establishes
type hierarchy navigability:

```java
// in io.evitadb.api.requestResponse.mutation (evita_api)
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

Changes required to adopt:

- `Mutation` adds `extends MutationContract` (1 line change — does not affect the sealed
  `permits` clause or any of the 50+ files importing `Mutation`)
- `IndexMutation` adds `extends MutationContract` (1 line)

Zero backward compatibility impact.

### `IndexMutation` — Engine-Internal Marker Interface

```java
// in io.evitadb.index.mutation (evita_engine)
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

**Key design choice — no `entityType` on `IndexMutation`:** The entity type belongs to the
routing layer (`EntityIndexMutation` envelope), not to the mutation payload itself. A concrete
`IndexMutation` carries only the data the executor needs to perform its work (reference name,
mutated entity PK, dependency type, scope). This keeps the leaf mutations focused and avoids
redundant data when multiple mutations target the same collection and are batched into a single
envelope.

### Full Mutation Type Hierarchy

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

### `EntityIndexMutation` — Transport Envelope (NOT an `IndexMutation`)

**Design principle: the source executor detects THAT a relevant change occurred, the target
executor decides WHAT is affected and HOW to handle it.**

The source entity's executor (e.g., ParameterGroup's `EntityIndexLocalMutationExecutor`)
consults the `CatalogExpressionTriggerRegistry`, verifies that the relevant attribute value
actually changed (old != new), and creates `ReevaluateFacetExpressionMutation` instances.
These are wrapped into an `EntityIndexMutation` carrying the target schema type. The source
does NOT evaluate the expression and does NOT determine the add/remove direction — it only
signals that a relevant change occurred.

The source does NOT resolve affected owner entity PKs — it cannot, because the reverse
lookup indexes (`ReferencedTypeEntityIndex`, `ReducedGroupEntityIndex`) live in the target
collection. PK resolution is the `IndexMutationExecutor`'s responsibility on the target side.

```java
// in io.evitadb.index.mutation (evita_engine)
/**
 * Transport envelope carrying concrete {@link IndexMutation} instances targeting a
 * specific {@link EntityCollection}. Created by the source executor after detecting
 * relevant changes. The target collection dispatches each nested mutation to the
 * appropriate {@link IndexMutationExecutor} via {@link IndexMutationExecutorRegistry}.
 *
 * This is a standalone record — it does NOT implement {@link IndexMutation}. It serves
 * purely as a routing envelope: the {@code entityType} identifies the target collection,
 * and the nested mutations are the actual work items.
 *
 * <p><b>Note on equality:</b> Java records use {@code Object.equals()} for array fields,
 * so two envelopes with identical contents but different array references will NOT be
 * {@code equals()}. This is intentional — envelope equality is not needed for correctness.</p>
 */
public record EntityIndexMutation(
    @Nonnull String entityType,
    @Nonnull IndexMutation[] mutations
) {
}
```

**Why `EntityIndexMutation` does NOT implement `IndexMutation`:** It is a routing/transport
concern, not a mutation payload. Implementing `IndexMutation` would conflate routing with
content, leading to confusion about what an executor should process. The executor processes
the individual `IndexMutation` instances inside the envelope, not the envelope itself.
Multiple concrete mutations can be batched when the same source entity change affects multiple
reference schemas on the same target collection.

### `ReevaluateFacetExpressionMutation` — Concrete Leaf Mutation

A single mutation type signals that a relevant cross-entity change occurred and the expression
needs re-evaluation on the target side. The mutation is a pure data carrier — it does NOT carry
the add/remove direction (unknown at source time for complex expressions with OR / multiple
cross-entity sources) or resolved owner entity PKs.

```java
// in io.evitadb.index.mutation (evita_engine)

/**
 * Signals that a cross-entity change occurred that may affect the facet
 * indexing expression for the given reference. The source executor detected
 * a relevant attribute change (old != new) on a group or referenced entity
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
public record ReevaluateFacetExpressionMutation(
    @Nonnull String referenceName,
    int mutatedEntityPK,
    @Nonnull DependencyType dependencyType,
    @Nonnull Scope scope
) implements IndexMutation {
}
```

### Implicit Mutation Records

Each executor produces its own dedicated record — no shared type carrying unused fields.

**`ImplicitMutations`** — existing record, unchanged, only used by
`ContainerizedLocalMutationExecutor`:

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

`EntityIndexLocalMutationExecutor` does **not** implement
`ConsistencyCheckingLocalMutationExecutor`. It exposes its own dedicated method:

```java
@Nonnull
IndexImplicitMutations popIndexImplicitMutations(
    @Nonnull List<? extends LocalMutation<?, ?>> inputMutations
);
```

This separation keeps the two executor families independent — container executors produce
`ImplicitMutations` (entity + local mutations), index executors produce
`IndexImplicitMutations` (index mutations only).

## Key Interfaces

| Interface / Type                         | Module          | Package                                       | Role                                                 |
|------------------------------------------|-----------------|-----------------------------------------------|------------------------------------------------------|
| `MutationContract`                       | `evita_api`     | `io.evitadb.api.requestResponse.mutation`     | Empty taxonomy root for IDE navigation               |
| `Mutation`                               | `evita_api`     | `io.evitadb.api.requestResponse.mutation`     | Existing sealed interface — adds `extends MutationContract` |
| `IndexMutation`                          | `evita_engine`  | `io.evitadb.index.mutation`                   | Engine-internal marker interface                     |
| `EntityIndexMutation`                    | `evita_engine`  | `io.evitadb.index.mutation`                   | Transport envelope — routes mutations to target collection |
| `ReevaluateFacetExpressionMutation`      | `evita_engine`  | `io.evitadb.index.mutation`                   | Concrete leaf mutation — signals cross-entity change |
| `ImplicitMutations`                      | `evita_engine`  | `io.evitadb.index.mutation` (nested in `ConsistencyCheckingLocalMutationExecutor`) | Container executor output — unchanged         |
| `IndexImplicitMutations`                 | `evita_engine`  | `io.evitadb.index.mutation` (preferred standalone; alternatively nested in `EntityIndexLocalMutationExecutor`) | Index executor output — new record                   |
| `DependencyType`                         | `evita_engine`  | `io.evitadb.index.mutation` (see WBS-03)      | Enum: how mutated entity relates to the owner        |

## Acceptance Criteria

1. `MutationContract` exists as a public interface with no methods in
   `io.evitadb.api.requestResponse.mutation` (`evita_api`).
2. `Mutation` extends `MutationContract` — the sealed `permits` clause and all 50+ importing
   files remain unchanged.
3. `IndexMutation` exists as a public interface extending `MutationContract` in
   `io.evitadb.index.mutation` (`evita_engine`), with no methods.
4. `EntityIndexMutation` is a public record in `io.evitadb.index.mutation` with fields
   `entityType` (String) and `mutations` (IndexMutation[]). It does NOT implement
   `IndexMutation`.
5. `ReevaluateFacetExpressionMutation` is a public record implementing `IndexMutation` with fields
   `referenceName` (String), `mutatedEntityPK` (int), `dependencyType` (DependencyType),
   `scope` (Scope).
6. `IndexImplicitMutations` is a record with a single field `indexMutations`
   (EntityIndexMutation[]).
7. Existing `ImplicitMutations` record is unchanged.
8. All new types have comprehensive JavaDoc.
9. The full mutation type hierarchy diagram is accurate and navigable via IDE "Show
   Implementations".
10. No new types carry `entityType` on the `IndexMutation` interface itself — entity type
    routing is solely on the `EntityIndexMutation` envelope.
11. Unit tests verify:
    - `MutationContract` is assignable from both `Mutation` and `IndexMutation`
    - `EntityIndexMutation` is NOT assignable from `IndexMutation`
    - `ReevaluateFacetExpressionMutation` is assignable from `IndexMutation`
    - Record equality and field accessors work correctly

## Implementation Notes

- **Module boundary:** `MutationContract` must live in `evita_api` because `Mutation` is in
  `evita_api` and sealed interfaces require `extends` from the same compilation unit.
  `IndexMutation` lives in `evita_engine` — it extends `MutationContract` across the module
  boundary (allowed because `MutationContract` is not sealed).
- **No serialization:** None of the new types (`IndexMutation`, `EntityIndexMutation`,
  `ReevaluateFacetExpressionMutation`, `IndexImplicitMutations`) need Kryo serializers or WAL
  integration. They are regenerated deterministically on replay.
- **Batching:** Multiple `ReevaluateFacetExpressionMutation` instances targeting the same
  entity collection can be batched into a single `EntityIndexMutation` envelope. This is an
  optimization, not a correctness requirement.
- **Extensibility:** Future index mutation types (e.g., `ReevaluateHistogramExpressionMutation`)
  simply implement `IndexMutation` and add a new executor — no changes to the hierarchy or
  dispatch infrastructure.
- **`@Nonnull` annotations:** All reference-type fields and method parameters must be annotated
  with `@Nonnull` following existing evitaDB conventions.

## Phase Placeholders

### Detailed Task Breakdown

#### Source Code Research Results

**Mutation interface** (`Mutation.java`):
- Path: `evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/Mutation.java`
- Declaration: `public sealed interface Mutation extends Serializable permits EngineMutation, CatalogBoundMutation`
- Methods: `Operation operation()`, `Stream<ConflictKey> collectConflictKeys(ConflictGenerationContext, Set<ConflictPolicy>)`
- The `sealed permits` clause lists exactly two subtypes: `EngineMutation` and `CatalogBoundMutation`
- Adding `extends MutationContract` to this interface will NOT affect the `permits` clause or any of the 50+ files that import `Mutation`

**CatalogBoundMutation interface** (`CatalogBoundMutation.java`):
- Path: `evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/CatalogBoundMutation.java`
- Declaration: `public sealed interface CatalogBoundMutation extends Mutation permits EntityMutation, LocalMutation, SchemaMutation, TransactionMutation`
- Adds `toChangeCatalogCapture(MutationPredicate, ChangeCaptureContent)` method

**EngineMutation interface** (`EngineMutation.java`):
- Path: `evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/EngineMutation.java`
- Declaration: `public non-sealed interface EngineMutation<T> extends Mutation`
- Instance-level mutations on the entire evitaDB instance (not catalog-scoped)

**EntityMutation interface** (`EntityMutation.java`):
- Path: `evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/EntityMutation.java`
- Declaration: `public non-sealed interface EntityMutation extends CatalogBoundMutation`
- Carries `entityType` via `String getEntityType()` and `Integer getEntityPrimaryKey()`
- Also has `EntityExistence expects()`, `verifyOrEvolveSchema(...)`, `Entity mutate(...)`, `List<? extends LocalMutation<?, ?>> getLocalMutations()`

**LocalMutation interface** (`LocalMutation.java`):
- Path: `evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/LocalMutation.java`
- Declaration: `public non-sealed interface LocalMutation<T, S extends Comparable<S>> extends CatalogBoundMutation, Comparable<LocalMutation<T, S>>`
- Has `compareTo` default method that compares by priority, class name, comparable key, and decisive timestamp
- Methods: `containerType()`, `mutateLocal(...)`, `getPriority()`, `getComparableKey()`, `getDecisiveTimestamp()`, `withDecisiveTimestamp(...)`

**ConsistencyCheckingLocalMutationExecutor interface** (`ConsistencyCheckingLocalMutationExecutor.java`):
- Path: `evita_engine/src/main/java/io/evitadb/index/mutation/ConsistencyCheckingLocalMutationExecutor.java`
- Package: `io.evitadb.index.mutation`
- Extends: `LocalMutationExecutor`
- Key method: `ImplicitMutations popImplicitMutations(List<? extends LocalMutation<?, ?>> inputMutations, EnumSet<ImplicitMutationBehavior> implicitMutationBehavior)`
- Nested record: `ImplicitMutations(LocalMutation<?, ?>[] localMutations, EntityMutation[] externalMutations)`
- Nested enum: `ImplicitMutationBehavior { GENERATE_ATTRIBUTES, GENERATE_REFERENCE_ATTRIBUTES, GENERATE_REFLECTED_REFERENCES }`
- This class was recently moved from `evita_api` to `evita_engine` (see git status showing the rename)

**ContainerizedLocalMutationExecutor** (`ContainerizedLocalMutationExecutor.java`):
- Path: `evita_engine/src/main/java/io/evitadb/index/mutation/storagePart/ContainerizedLocalMutationExecutor.java`
- Declaration: `public final class ContainerizedLocalMutationExecutor implements ConsistencyCheckingLocalMutationExecutor, WritableEntityStorageContainerAccessor, EntityStoragePartAccessor`
- Implements `popImplicitMutations(...)` at line 1162, returns `mutationCollector.toImplicitMutations()` at line 1276

**MutationCollector** (`MutationCollector.java`):
- Path: `evita_engine/src/main/java/io/evitadb/index/mutation/storagePart/MutationCollector.java`
- Package-private class, collects `LocalMutation` and `EntityMutation` instances
- Produces `ImplicitMutations` via `toImplicitMutations()` method

**EntityIndexLocalMutationExecutor** (`EntityIndexLocalMutationExecutor.java`):
- Path: `evita_engine/src/main/java/io/evitadb/index/mutation/index/EntityIndexLocalMutationExecutor.java`
- Declaration: `public class EntityIndexLocalMutationExecutor implements LocalMutationExecutor`
- Does NOT implement `ConsistencyCheckingLocalMutationExecutor`
- Has NO implicit mutation methods currently — no `popImplicitMutations`, no `popIndexImplicitMutations`
- Has field `private final String entityType`

**LocalMutationExecutor interface** (`LocalMutationExecutor.java`):
- Path: `evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/LocalMutationExecutor.java`
- Methods: `applyMutation(LocalMutation<?, ?>)`, `commit()`, `rollback()`

**LocalMutationExecutorCollector** (`LocalMutationExecutorCollector.java`):
- Path: `evita_engine/src/main/java/io/evitadb/core/collection/LocalMutationExecutorCollector.java`
- Currently only processes `ImplicitMutations` from `ContainerizedLocalMutationExecutor`
- Does not interact with `EntityIndexLocalMutationExecutor` for implicit mutations at all
- This is the dispatch loop that WBS-10 will modify to also collect `IndexImplicitMutations`

**Scope enum** (`Scope.java`):
- Path: `evita_common/src/main/java/io/evitadb/dataType/Scope.java`
- Package: `io.evitadb.dataType`
- Values: `LIVE`, `ARCHIVED`
- Has static constants: `DEFAULT_SCOPE = LIVE`, `DEFAULT_SCOPES`, `NO_SCOPE`

**DependencyType enum**:
- Does NOT exist anywhere in the Java source code. Only referenced in documentation files (WBS docs).
- Must be created as part of WBS-03 (or as a prerequisite task within this WBS if WBS-03 is not yet implemented).

**Package `io.evitadb.api.requestResponse.mutation`** (evita_api module):
- Verified at: `evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/`
- Contains: `CatalogBoundMutation.java`, `EngineMutation.java`, `Mutation.java`, `MutationPredicate.java`, `MutationPredicateContext.java`, `StreamDirection.java`
- Subdirectories: `conflict/`, `infrastructure/`
- Exported in `module-info.java` at line 85: `exports io.evitadb.api.requestResponse.mutation;`

**Package `io.evitadb.index.mutation`** (evita_engine module):
- Verified at: `evita_engine/src/main/java/io/evitadb/index/mutation/`
- Contains: `ConsistencyCheckingLocalMutationExecutor.java`
- Subdirectories: `index/` (contains `EntityIndexLocalMutationExecutor.java`), `storagePart/`
- NOT exported in `module-info.java` — no `exports io.evitadb.index.mutation;` line exists. The new types (`IndexMutation`, `EntityIndexMutation`, `ReevaluateFacetExpressionMutation`) will need an export if they are referenced from other modules, or can remain unexported if used only within `evita_engine`.

#### Detailed Task List

**Group 1: `MutationContract` root marker interface (evita_api)**

- [x] Create `MutationContract.java` in `evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/` — empty public interface with no methods, comprehensive JavaDoc explaining its role as a taxonomy root for both external `Mutation` and engine-internal `IndexMutation` types. No `@Immutable`/`@ThreadSafe` annotations needed (no methods to constrain). Must NOT be `sealed` (to allow cross-module extension by `IndexMutation` in `evita_engine`).

**Group 2: Modify existing `Mutation` interface (evita_api)**

- [x] Edit `Mutation.java` at `evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/Mutation.java` — change declaration from `public sealed interface Mutation extends Serializable permits EngineMutation, CatalogBoundMutation` to `public sealed interface Mutation extends MutationContract, Serializable permits EngineMutation, CatalogBoundMutation`. This is a single-line change. The `permits` clause is unchanged. No imports need to be added (same package).

**Group 3: `IndexMutation` marker interface (evita_engine)**

- [x] Create `IndexMutation.java` in `evita_engine/src/main/java/io/evitadb/index/mutation/` — public interface extending `MutationContract` with no methods. JavaDoc must explain: engine-internal, never passed from outside, never written to WAL, regenerated deterministically on replay. Import `io.evitadb.api.requestResponse.mutation.MutationContract`. Entity type is NOT on this interface (carried by the `EntityIndexMutation` envelope).

**Group 4: `EntityIndexMutation` transport envelope record (evita_engine)**

- [x] Create `EntityIndexMutation.java` in `evita_engine/src/main/java/io/evitadb/index/mutation/` — public record with fields `@Nonnull String entityType` and `@Nonnull IndexMutation[] mutations`. Does NOT implement `IndexMutation` or `MutationContract`. JavaDoc must explain the routing/transport role and why it does not implement `IndexMutation`. Must import `javax.annotation.Nonnull`.

**Group 5: `ReevaluateFacetExpressionMutation` concrete leaf record (evita_engine)**

- [x] Verify `DependencyType` enum availability — this record depends on `DependencyType` from WBS-03. If WBS-03 is not yet implemented, either create a stub `DependencyType` enum (to be replaced by WBS-03's implementation) or defer this task until WBS-03 is complete.
- [x] Create `ReevaluateFacetExpressionMutation.java` in `evita_engine/src/main/java/io/evitadb/index/mutation/` — public record implementing `IndexMutation` with fields: `@Nonnull String referenceName`, `int mutatedEntityPK`, `@Nonnull DependencyType dependencyType`, `@Nonnull Scope scope`. JavaDoc must describe that this signals a cross-entity change requiring facet expression re-evaluation, that the source does NOT evaluate the expression or determine add/remove direction, and that PK resolution happens on the target side. Import `io.evitadb.dataType.Scope`.

**Group 6: `IndexImplicitMutations` record (evita_engine)**

- [x] Create the `IndexImplicitMutations` record as a standalone type in `io.evitadb.index.mutation` (preferred, for consistency with other mutation types in this package and to avoid coupling to `EntityIndexLocalMutationExecutor`). Alternatively, it may be a nested record inside `EntityIndexLocalMutationExecutor` (at `evita_engine/src/main/java/io/evitadb/index/mutation/index/EntityIndexLocalMutationExecutor.java`). The record has a single field: `@Nonnull EntityIndexMutation[] indexMutations`. JavaDoc must explain this is the output record for index executors, analogous to `ImplicitMutations` for container executors.
- [x] Add `popIndexImplicitMutations` method signature to `EntityIndexLocalMutationExecutor` — signature: `@Nonnull IndexImplicitMutations popIndexImplicitMutations(@Nonnull List<? extends LocalMutation<?, ?>> inputMutations)`. This is a stub method for now (implementation is WBS-08/WBS-09 scope). The stub should return an empty `IndexImplicitMutations` (with an empty `EntityIndexMutation[]` array). Note: this method is on the concrete class, NOT on `ConsistencyCheckingLocalMutationExecutor` — the two executor families remain independent.

**Group 7: Module system updates (evita_engine)**

- [x] Evaluate whether `io.evitadb.index.mutation` needs to be added to the `exports` list in `evita_engine/src/main/java/module-info.java`. If `IndexMutation`, `EntityIndexMutation`, or `ReevaluateFacetExpressionMutation` are referenced from other modules (e.g., `evita_store` for persistence or test modules), the export is required. If all consumers are within `evita_engine`, no export is needed. Check cross-module references from WBS-06 (trigger registry) and WBS-09 (dispatch loop in `LocalMutationExecutorCollector` which is in `io.evitadb.core.collection` — same module) to determine. At minimum, `exports io.evitadb.index.mutation;` should be added since `ConsistencyCheckingLocalMutationExecutor` in this package is already imported by `LocalMutationExecutorCollector` (same module, so technically not required, but would be needed if test modules reference the new types directly). **Result:** No export needed — all consumers are within `evita_engine`.

### Test Cases

All tests use JUnit 5. Test classes live under
`evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/`.

---

#### ~~Test Class 1: `MutationTypeHierarchyTest`~~ — DELETED

Deleted during code review. All tests verified Java compile-time type hierarchy guarantees
(`isAssignableFrom`, `isSealed`, `getDeclaredMethods`) — the compiler already enforces these.
Tests were fragile (break on any intentional refactoring) and provided no bug-catching value.

---

#### ~~Test Class 2: `ReevaluateFacetExpressionMutationTest`~~ — DELETED

Deleted during code review. Tests verified Java record auto-generated accessors, `equals`,
`hashCode`, `toString`, and `instanceof` checks — all guaranteed by the Java language for
records. No project-specific behavior to test.

---

#### ~~Test Class 3: `EntityIndexMutationTest`~~ — DELETED

Deleted during code review. Tests verified Java record accessors, `instanceof` checks, and
array identity equality — all Java language guarantees, not project behavior.

---

#### ~~Test Class 4: `IndexImplicitMutationsTest`~~ — DELETED

Deleted during code review. Tests verified Java record accessors and `isAssignableFrom`
between unrelated types — Java language guarantees, not project behavior.

---

#### ~~Test Class 5: `ImplicitMutationsBackwardCompatibilityTest`~~ — DELETED

Deleted during code review. Tests verified record component count/names via reflection —
fragile, breaks on any intentional change, provides no bug-catching value. The existing
`ImplicitMutations` record was not modified by WBS-05.

---

#### ~~Test Class 6: `DependencyTypeCompletenessTest`~~ — DELETED

Deleted during code review. Tests verified enum constant count and names — breaks on any
legitimate addition, provides no bug-catching value. `DependencyType` is owned by WBS-03.

---

#### ~~Test Class 7: `MutationBackwardCompatibilityTest`~~ — DELETED

Deleted during code review. Tests verified sealed permits, `isAssignableFrom`, and method
existence via reflection — all Java compiler guarantees. The build verification step
(`mvn compile` on `evita_api`) was performed successfully during implementation.

---

#### Test Class 8: `PopIndexImplicitMutationsStubTest` — TRIMMED

Tests for the stub `popIndexImplicitMutations` method on `EntityIndexLocalMutationExecutor`.
Trimmed during code review — removed redundant tests that were subsumed by the kept ones.

**Category: stub behavior**

- [x] `should_return_empty_index_implicit_mutations_for_empty_input` — call
  `popIndexImplicitMutations(Collections.emptyList())` and verify the returned
  `IndexImplicitMutations` has an empty `indexMutations` array.
- [x] `should_return_empty_index_implicit_mutations_for_non_empty_input` — call
  `popIndexImplicitMutations(...)` with a list containing arbitrary `LocalMutation` instances
  and verify the returned `IndexImplicitMutations` still has an empty `indexMutations` array.
  (The stub does not process input — real implementation is WBS-08/WBS-09.)
- ~~`should_return_non_null_result`~~ — removed, subsumed by the two tests above (both
  implicitly verify non-null by calling `.indexMutations().length` on the result).
- ~~`should_return_empty_result_on_repeated_calls`~~ — removed, tests trivial idempotency
  guaranteed by the stateless stub implementation.
