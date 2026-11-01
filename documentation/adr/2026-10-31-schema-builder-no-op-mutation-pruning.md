---
title: Schema builders drop the mutations that leave their level of the schema unchanged, judged by value at toMutation()
date: 2026-10-31
updated: 2026-11-01 01:14
status: accepted
kind: fix
issues: [1843, 1845, 1846, 1847, 1848, 1849, 1850, 1851, 1852, 1853, 1854, 1859, 1860]
prs: [1844]
areas: [evita_api/src/main/java/io/evitadb/api/requestResponse/schema/builder, evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation, evita_api/src/main/java/io/evitadb/api/requestResponse/schema/dto/ReflectedReferenceSchema.java, evita_api/src/main/java/io/evitadb/api/requestResponse/schema/ClassSchemaAnalyzer.java, evita_query/src/main/java/io/evitadb/api/query/expression, evita_external_api/evita_external_api_grpc/shared]
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
themselves. The follow-ups found on the way - replay, combine and equality defects of the builders, and the text of
composed expressions - are fixed in the same pull request.

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
  base entity schema. It does not read it from `MutationEntitySchemaAccessor`: that accessor reflected mutations twice
  while `toInstance()` did not reset it on a replay from the base (#1846), and the pruning should not depend on it.
- The entity builder prunes against its own base, so the base has to be the state the new calls apply to.
  `InternalCatalogSchemaBuilder.withEntitySchema` therefore starts the nested entity builder from the entity schema
  as the catalog builder's collected mutations leave it, not from the stored one. Started from the stored one, a call
  restoring a value an earlier call of the same catalog builder changed emitted nothing, and the earlier change
  survived - a regression of the pruning that the fourth adversarial pass found.
- The entity modifications are applied against the final catalog schema. When one of them cannot be applied there
  (an `EvitaInvalidUsageException`, for example `UseGlobalAttributeSchemaMutation` of a global attribute the catalog
  removes later in the same builder), the levels depend on each other and the builder emits every collected mutation
  unchanged. The same fallback covers a replay of the builder's own state that rejects a sequence the server
  accepts. The one known such sequence (an entity modification replayed twice, #1846) is fixed, and the fallback
  stays for any other. Internal errors are not caught. A modification of an
  entity schema that does not exist at its position in the sequence also emits everything before any replay: the
  server creates that entity schema on the fly when the catalog allows adding entity types, while the replay has
  nothing to apply it to. Removing and re-creating the same global attribute never reaches the pruning: `combineWith`
  of the create and the remove mutation collapses the pair first.
- Expression equality matters. `NullSafeAccessStep` (`?.`), `SpreadAccessStep` and `FunctionOperator` (#1852) had no
  value `equals`, so any reference using such an expression never equalled its own re-parse and never pruned. All
  three now have it; a function is identified by its name, not by its processor instance.
- Source fixes, each needed on its own:
  - `CatalogSchemaConverter.toGrpcGlobalAttributeSchema` now sends `representative`, so gRPC clients see the real
    value.
  - `ClassSchemaAnalyzer.definePrice` no longer adds an empty currency mutation.
  - The reference reconciliation counts `facetedPartially` scopes as faceted, in both the scoped and the default-scope
    path.
  - The `ReferenceSchemaBuilder` setters `facetedPartiallyInScope`, `bucketedInScope` and `bucketedPartiallyInScope`
    are no-ops when nothing changes. They compare expressions as trees (`Expression.equals`), never by their text:
    before #1851 a tree composed by `ExpressionFactory.and` printed exactly like a parsed expression that groups its
    operands differently, and text stays a weaker identity than the tree.

### Follow-ups fixed in the same pull request

Each was a separate defect of the replay, combine or equality machinery that the pruning relies on; each has its own
issue and commit.

- **Combining two entity modifications** kept the older change of a property (#1845):
  `ModifyEntitySchemaMutation.combineWith` now takes the existing children as the base and adds the new ones.
- **The catalog builder's replay** (#1846, #1847) resets its `MutationEntitySchemaAccessor` when it starts from the
  base, serves entity schemas from its own state rather than from the delegated catalog DTO (Lombok's `@Delegate`
  routed `getEntitySchemaOrThrowException` there), and applies the cascades catalog mutations return
  (`CatalogSchemaWithImpactOnEntitySchemas.entitySchemaMutations()`). Like `Catalog.updateSchema`, it collects them and
  applies them after every explicit mutation; a replay that applied any makes the next one start from the base, so
  that mutations added later come before them. Applying each cascade right after its mutation was rejected: a batch
  removing a global attribute and then modifying it in an entity, which the server accepts, failed in the builder.
- **The entity builder's replay** (#1848) applies a run of consecutive mutations that change one attribute alone to
  the attributes and builds the entity schema once per run. A mutation qualifies when it does not override
  `EntityAttributeSchemaMutation.mutate(CatalogSchemaContract, EntitySchemaContract)`, detected once per class; a
  check that needs the catalog belongs to the new `verifyApplicableToEntity` hook, which is how
  `SetAttributeSchemaFilterableMutation` joins the runs. The result of a run is the result of applying its mutations
  one by one, attribute order included: the run holds the attributes in a `LinkedHashMap` the way
  `replaceAttributeIfDifferent` (the path the server takes) leaves them, moving each changed one to the end, and
  collects them into a hash map the same way. `EntitySchema._internalBuildWithAttributes` is the one copy of the
  19-argument build both use. Rejected:
  - keeping a changed attribute at its position in both paths: the attribute order is observable - generated test
    data follows it, and five data-dependent functional tests failed with it;
  - replaying from the first replaced position: the replaced mutation is nearly always the first one;
  - indexing `addMutations` by target: it was 0.3 % of the profile;
  - applying changes incrementally without a replay: the version diverges, and order-sensitive pairs exist.
- **Removing and re-creating a reference in one builder** (#1849) threw. The decision to start with a create now also
  holds when the builder state has no such reference; adding that condition, rather than replacing the stored-schema
  check as d8e18f9da2 did (reverted the same day after 1733 failed tests), keeps the second definition of a reference
  created in the same builder working. Switching the reference builder to create mode by itself was rejected: its
  base would be the removed stored reference, whose settings the new one would silently inherit. The re-creation
  reached code no builder sequence reached before: `ModifyReferenceAttributeSchemaMutation` and its compound
  counterpart returned copies instead of the examined mutation, which made `addMutations` combine the same pair
  forever, and compared a re-created attribute with the entity's attribute of that name. The managed flag of a
  re-created reference was dropped as well, and so was a change of an attribute's indexed decimal places alone, of an
  entity and of a global attribute alike (the collapse compared the type only, although the type mutation carries
  both; `AbstractAttributeSchemaMutation.makeTypeMutationIfDifferent` now compares them for both).
- **A second `withReferenceTo` call** on an existing reference (#1850) appended the mutations of the first call after
  its own, so a restored value lost; they now come first.
- **`ClassSchemaAnalyzer`** clears stored partial faceting and bucketing a model no longer declares (#1854).
- **`ReflectedReferenceSchema.equals`** compares the reflected reference's own definition (#1853) - the inheritance
  flags, the values only where it declares them, and the declared attributes and compounds - instead of the
  effective values, which depend on whether and to which version of the original the copy is bound.
- **Reflected references** (#1859, #1860): no mutation can redirect a reflected reference, so the builder refuses a
  definition naming another original reference and the remove-and-create collapse keeps both mutations then. The
  mutations of description, deprecation and cardinality, and the collapse, judge a change by the declaration
  (inherited, or the declared value), never by the effective value: a declared value equal to the inherited one is
  still a change, and inherited values are not available on a reference that is not bound.
- **Composed expressions** (#1851) print the parentheses their grouping implies. `ExpressionPrecedence` copies the
  precedence levels from the order of the alternatives in `EvitaEL.g4`, where every binary operator has a level of
  its own (`+` binds tighter than `-`, see #1855), and parenthesizes only where the grammar would group differently,
  so the text of every parsed expression stays byte for byte the same and no stored format changes.
  `ExpressionFactory.and` wraps an operand that binds looser than the conjunction in a `NestedOperator`, so the
  composed expression equals the parse of its own text. Rejected: wrapping every operand of `and` unconditionally,
  which breaks that equality for operands that need no parentheses and leaves trees composed by constructors broken.

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
- Each follow-up commit carries a test that was red before its fix; the commit messages name them. Among them:
  `EntitySchemaBuilderTest` re-creates stored standard and reflected references (a test of it looped forever before
  the wrapper fix), `EvitaClientSchemaRedefinitionTest` re-creates a reference over gRPC in one update and checks that
  the stored references, their attributes and the index answering a filter on them survive,
  `ExpressionTextGroupingTest` pins the precedence levels against the parser for every pair of operators (588 parsed
  texts unchanged; 347 cases over composed trees were red before), and `CatalogSchemaBuilderTest` covers the
  cascade order.
- Two more adversarial passes ran over the follow-ups. The third found the decimal places of a re-created entity
  attribute and the cascade order, the fourth the decimal places of a re-created global attribute and the stored
  base of the nested entity builder. Each is covered by a test in `EntitySchemaBuilderTest` or
  `CatalogSchemaBuilderTest` that was red before its fix.
- `EntitySchemaBuilderReplayTest` compares the batched replay with the one-by-one application in content, version,
  attribute order (500 attributes; it fails when the run does not move a changed attribute to the end) and attribute
  classes. Restoring a property of 800 attributes takes about 40 s of processor time without the batching and about
  3.5 s with it on an idle machine, JaCoCo agent attached. `EntitySchemaBuilderReplaySpeedTest` in the long-running
  tests fails above 15 s; it cannot live in the fast suite, where a loaded run took 77 s of processor time.
- Five data-dependent functional tests (`EntityByAttributeFilteringFunctionalTest`, its partitioning variant,
  `EvitaSessionServiceFunctionalTest`) failed with an attribute order changed to definition order and pass with the
  order kept; see the rejected option of the batched replay.
- The fast functional suite over the follow-ups, before the third adversarial pass: 32 462 tests, 0 failures,
  7 errors - the replay speed guard (moved to the long-running tests since), `ExportS3ServiceTest` without Docker,
  and five GraphQL subscription tests with a websocket session timeout, which pass when their classes run alone.
- On the final tree every functional test tagged `schema`, `attribute`, `reference`, `driver`, `grpc` or `expression`
  (excluding `slow` and `flaky`) passes - 13 405 tests - except two GraphQL subscription tests whose websocket client
  timed out creating its TLS session, before any schema call. Their two classes pass alone, 31 of 31 tests.
- Removing the `indexedPricePlaces` comparison from `SchemaContentComparator` makes exactly the matching
  genuine-change scenario fail, so the property scenarios can fail.

## Consequences & open follow-ups

Every follow-up listed in #1843 except the server-side guard is fixed here (see above). Found on the way and not
fixed:
- EvitaEL ranks every binary operator on a level of its own, which contradicts the documentation (#1855).
  `ExpressionPrecedence` copies the grammar and has to change in the same commit as the grammar.
- `ExpressionFactory.parse` silently ignores trailing input (#1856).
- The gRPC query converter sends an expression argument in its quoted form (#1857), and the generated `spacing`
  examples pass quoted expressions to `ExpressionFactory.parse` (#1858).
- When a combination in `InternalSchemaBuilderHelper.addMutations` dismantles the examined mutation, the mutations
  after it in the same round are processed in that round and queued for the next one as well. A mutation combined
  with itself replaces itself, which makes that harmless; the copies the reference attribute wrapper returned turned
  it into the endless loop of #1849. Anything that combines a mutation with itself into something else would loop
  again.
- `Catalog.updateSchema` loses the cascades collected before a catalog mutation without cascades in the same batch
  (#1861): removing a global attribute and then changing the catalog description leaves the attribute in the entity
  schemas. The builders apply every cascade, so their state differs from the server's for such a batch until #1861
  is fixed.
- The entity builder's replay is still quadratic: every replay re-applies the whole list, and `addMutations` scans
  the list for every new mutation. Restoring a property of 800 attributes takes about three seconds. Runs of
  reference mutations are not batched, and neither are global attributes in the catalog builder.

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
- **2026-10-31** — follow-ups #1845 to #1854, #1859 and #1860 fixed in #1844; #1855 to #1858 filed
- **2026-11-01** — third and fourth adversarial pass over the follow-ups, their findings fixed in #1844; #1861 filed
