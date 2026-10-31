---
title: Schema builders drop the mutations that leave their level of the schema unchanged, judged by value at toMutation()
date: 2026-10-31
updated: 2026-10-31 19:15
status: accepted
kind: fix
issues: [1843]
prs: []
areas: [evita_api/src/main/java/io/evitadb/api/requestResponse/schema/builder, evita_api/src/main/java/io/evitadb/api/requestResponse/schema/ClassSchemaAnalyzer.java, evita_query/src/main/java/io/evitadb/api/query/expression/object, evita_external_api/evita_external_api_grpc/shared]
supersedes: []
superseded-by: []
relates: []
---

# Schema builders drop the mutations that leave their level of the schema unchanged, judged by value at toMutation()

Re-defining an unchanged schema from annotated model classes, as applications do on every start, produced schema
mutations that changed nothing. The server records every received mutation in the transaction and derives conflict
keys from it. A mutation of a global attribute takes a catalog-wide key, so a start-up during a rolling deployment
collided with the old instance's writes. `InternalEntitySchemaBuilder.toMutation()` and
`InternalCatalogSchemaBuilder.toMutation()` now compare the final schema with the base schema by value and do not
emit the mutations of a level that ends up unchanged. Four point fixes remove the sources of the no-op mutations
themselves.

## Why

- The builders' own no-op check runs once per call and compares with the current builder state.
- `combineWith` replaces an older mutation of the same kind without looking at the base schema.
- A value that goes stored → annotation → stored therefore leaves a mutation equal to the base. This happens when
  `ClassSchemaAnalyzer` applies the annotation and a `SchemaPostProcessor` restores the stored value.
- The cost was not the WAL noise but failed start-ups. `ConflictingCatalogMutationException` named an entity of a
  collection the reconciliation never touched, and an application that does not retry skipped its remaining schema
  steps.

### Previous state

`toMutation()` returned every mutation that survived combining. That was acceptable while the analyzer applied each
property once. Two-way reconciliation of annotation flags (#1199) and post-processors made "set, then restore" the
normal start-up shape.

## Options considered

### Option A — compare the final schema with the base per level at `toMutation()` (chosen)

The entity builder emits nothing when its final schema equals its base. The catalog builder judges two kinds of level
separately:
- the catalog level (global attributes, description, conflict resolution, evolution modes);
- each existing entity schema it modifies.

Create, remove and rename of entity schemas are always kept.

- **Pros:**
  - It is a small change in two methods and leaves the mutation and combine machinery untouched.
  - It fails safe: an unreliable `equals` keeps a mutation, it never drops one, and levels that depend on each other
    are emitted as collected.
  - No-op mutations left inside a level that has a genuine change carry the same conflict key as that change. Every
    mutation of an entity carries its `CollectionConflictKey`, and a catalog-level change carries `CatalogConflictKey`
    anyway. The conflict surface therefore never widens.
- **Cons:**
  - No-op mutations still ride along with a genuine change in the same level and reach the WAL.
  - It depends on a property-by-property comparison that must be kept complete.

### Option B — prune per sub-schema (attribute, reference, compound) inside the mutation log (declined)

- **Pros:** finer granularity, matching the original elimination logic of the builders.
- **Cons:** it needs grouping and ordering logic for every combinable mutation kind, and the gain over A is WAL noise
  only.
- **Rejected because:** the maintainer judged the added logic too risky for no gain in conflict surface. It would be
  worth revisiting if WAL or CDC consumers started to suffer from riders next to genuine changes.

### Option C — rewrite the builders to hold target state and diff against the base (declined)

- **Pros:** it removes combine, replay and the shared mutation log. The phantom class becomes impossible by
  construction.
- **Cons:**
  - It replaces the internal model of all 11 builders, not only `toMutation()`.
  - It cannot recover intent from a diff. A rename would read as remove plus create, and for an entity collection that
    deletes its data.
  - It needs an ordering layer, and a property the differ forgets silently drops a genuine change.
- **Rejected because:** the analysis (supporting material below) found each claimed win only partly true. Its speed
  gain appears only on replacement-heavy sequences, not at start-up, and it trades a harmless failure for harmful
  ones.

### Option A′ — judge each entity modification against the catalog state at its own position (declined)

Option A applies the modifications of an entity to the final catalog schema. A position-aware variant would replay
the catalog and the entity levels interleaved, so that every entity modification sees the catalog as it was when the
modification was collected.

- **Pros:** it judges an entity that adopts a global attribute the catalog removes later correctly, where Option A
  falls back to emitting everything.
- **Cons:** it needs a second replay that keeps the catalog state per position next to `toInstance()`, inside
  a builder whose own replay is already known to be inexact (see the follow-ups).
- **Rejected because:** the fallback covers the same sequences without new replay logic, and they are rare - a
  migration that adopts and removes a global attribute in one builder. Worth revisiting if such migrations become
  common enough that their riders matter.

### Option D — register a mutation on the server only when it changes the schema (declined)

- **Pros:** it is the only option that also protects clients already deployed with an older driver.
- **Rejected because:** the maintainer chose to keep the server's mutation handling unchanged. Older clients keep
  sending the no-op mutations until they upgrade, except the `representative` one, whose cause is fixed on the server
  side of the gRPC converter.

## Decision

**Chosen: Option A.** It removes every phantom of #1843 and its conflict effect while touching the least code. If
riders next to genuine changes become a problem, Option B can be layered on top. If deployed clients that cannot
upgrade become a problem, Option D can be added. Neither contradicts A.

## Key technical details

- `schema/builder/SchemaContentComparator` holds the version-agnostic comparison of entity and catalog schemas.
  `EntitySchema` and `CatalogSchema` define `equals` by name and version only, and `EntitySchema.differsFrom`
  compares the version and omits several fields, so neither can be used. **Every new schema property must be added
  there**, otherwise the builders drop its genuine changes. `SchemaContentComparatorTest` fails when a schema DTO
  gains an unclassified field.
- `InternalCatalogSchemaBuilder.withoutNoOpMutations()` computes the final state of a modified entity schema from the
  base entity schema. It does not read it from `MutationEntitySchemaAccessor`, which is not reset when `toInstance()`
  replays from the base and can reflect mutations twice.
- The entity modifications are applied against the final catalog schema. When one of them cannot be applied there
  (an `EvitaInvalidUsageException`, for example `UseGlobalAttributeSchemaMutation` of a global attribute the catalog
  removes later in the same builder), the levels depend on each other and the builder emits every collected mutation
  unchanged. The same fallback covers a replay of the builder's own state that rejects a sequence the server
  accepts: the not-reset `MutationEntitySchemaAccessor` makes `toInstance()` apply an entity modification that
  creates a sub-schema and refines it over its own result. Internal errors are not caught. A modification of an
  entity schema that does not exist at its position in the sequence also emits everything before any replay: the
  server creates that entity schema on the fly when the catalog allows adding entity types, while the replay has
  nothing to apply it to. Removing and re-creating the same global attribute never reaches the pruning: `combineWith`
  of the create and the remove mutation collapses the pair first.
- Expression equality matters. `NullSafeAccessStep` (`?.`) and `SpreadAccessStep` had no `equals`, so any reference
  using such an expression never equalled its own re-parse and never pruned. Both now have it. `FunctionOperator`
  still has none, so references with function calls fall back to keeping their mutations.
- Source fixes, each needed on its own:
  - `CatalogSchemaConverter.toGrpcGlobalAttributeSchema` now sends `representative`, so gRPC clients see the real
    value.
  - `ClassSchemaAnalyzer.definePrice` no longer adds an empty currency mutation.
  - The reference reconciliation counts `facetedPartially` scopes as faceted, in both the scoped and the default-scope
    path.
  - The `ReferenceSchemaBuilder` setters `facetedPartiallyInScope`, `bucketedInScope` and `bucketedPartiallyInScope`
    are no-ops when nothing changes. They compare expressions as trees (`Expression.equals`), never by their text:
    `AbstractBinaryOperator.toString()` prints no parentheses, so a tree composed by `ExpressionFactory.and` reads
    exactly like a parsed expression that groups its operands differently.

## Verification

- `ClassSchemaAnalyzerRedefinitionTest`, embedded with restart, covers six shapes: a representative global attribute,
  global uniqueness, uniqueness restored by a post-processor, price places restored by a post-processor, a reference
  with partial faceting and conditional histograms, and the same reference re-declared by a trait. Four of the six
  were red on dev 698eac82c9. All six are green. Two more tests cover the reference declared by the general
  `@Reference` settings and a currency added over a price indexed in both scopes; the latter turns red when
  `definePrice` passes the default scopes.
- `EvitaClientSchemaRedefinitionTest`, over gRPC with a fresh client, has three tests that were all red, including
  the conflict with a concurrent writer to another collection. All three are green. A fourth test is their control:
  the same concurrent write does conflict when the re-definition changes the global attribute.
- `SchemaContentComparatorTest` checks both directions:
  - a genuine change of each of 16 entity-level and 4 catalog-level properties is still emitted;
  - restored values emit nothing;
  - per-level pruning keeps the genuine level and drops the other;
  - an entity that adopts a global attribute removed later keeps every mutation, and the emitted list applies to the
    base schema in its order. This scenario came from an adversarial review and threw in `toMutation()` before the
    fallback was added;
  - an existing entity modified twice in one builder emits the merged modification, and it applies to the base
    schema. The replay rejected it before the fallback covered `toInstance()`;
  - create, remove and rename of an entity schema are kept, and two modified entity schemas are judged independently;
  - a modification of an entity schema the catalog does not have yet is emitted. Before the existence check it
    failed the replay with an internal error;
  - the reference setters collect nothing for a setting the reference already has, and still collect every changed
    expression, histogram value or condition. With the three setter checks disabled, all four unchanged-setting
    scenarios turn red. A composed expression that prints like the current one but groups its operands differently
    is collected too. It was swallowed while the setters compared the text.

  The scenarios with the replay fallbacks, the entity existence check and the composed expression came from two
  adversarial review passes and a quality pass, each red before its fix.

  It also holds a field inventory of `EntitySchema` and `CatalogSchema`.
- The fast functional suite (`unitAndFunctional`, `largeMachine`) ran over the tree before the entity existence
  check and the structural expression comparison were added: 31 175 tests, 0 failures, 2 errors, both environmental.
  `ExportS3ServiceTest` found no Docker environment for its container. A GraphQL subscription test hit its 2-minute
  websocket timeout and passed 13 of 13 when its class was re-run alone; it creates a new collection, which the
  pruning never touches.
- On the final tree every functional test tagged `schema`, `expression`, `driver` or `grpc` (excluding `slow` and
  `flaky`) passes: 4380 tests, 0 failures, 1 skipped as before. This includes the GraphQL and REST schema API tests
  and the read-write client tests.
- Removing the `indexedPricePlaces` comparison from `SchemaContentComparator` makes exactly the matching
  genuine-change scenario fail, so the property scenarios can fail.

## Consequences & open follow-ups

The following are listed in #1843 and not fixed here:
- the inverted order in `ModifyEntitySchemaMutation.combineWith`;
- `withoutReferenceTo` followed by `withReferenceToEntity` throwing;
- the builder replay cost;
- the `ReflectedReferenceSchema.equals` inconsistency;
- a second `withReferenceTo` call on an existing reference being built against the stored reference rather than the
  builder state, so restoring a value set by the first call is lost (the same base-versus-current class);
- `FunctionOperator` lacking value equality, so references whose expressions call a function are never pruned;
- `InternalCatalogSchemaBuilder.toInstance()` replaying from the base schema without resetting its
  `MutationEntitySchemaAccessor`, which is why the pruning computes entity states from the base schema itself;
- `InternalCatalogSchemaBuilder.toInstance()` ignoring the entity schema impacts that catalog-level mutations return
  (`CatalogSchemaWithImpactOnEntitySchemas.entitySchemaMutations()`), so the builder's view of an entity misses what
  the server cascades into it, for example the removal of a global attribute the entity uses;
- `AbstractBinaryOperator.toString()` omitting the parentheses that the grouping of a composed expression tree
  implies, so wherever such an expression travels or is stored as text, it can change its meaning.

`toMutation()` of both builders now materializes the final schema through `toInstance()`. A builder with a pending
`MODIFIED_PREVIOUS` therefore replays at `toMutation()`, where before it replayed at the next read. When that replay
of the catalog builder fails on a valid sequence, `toMutation()` falls back to emitting every collected mutation.
`InternalEntitySchemaBuilder.toMutation()` has no such fallback. Its replay starts from its base entity schema and
keeps no accessor that could hold an earlier result; it reads only the catalog schema it cooperates with. No test of
the functional suite found a sequence it rejects.

The server-side guard (Option D) was not added. Clients on an older driver keep sending the no-op mutations of the
analyzer and the builders until they upgrade.

## Supporting material

- Analysis of Options B and C against the code and history, with micro-measurements of builder cost: kept outside the
  repository, summarised in Option C above.

## Timeline

- **2026-10-30** — start-up schema update fails during a rolling deployment
- **2026-10-31** — #1843 filed; Option A chosen and implemented
