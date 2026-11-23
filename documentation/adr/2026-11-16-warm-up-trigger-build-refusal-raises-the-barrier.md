---
title: A warm-up schema change refused when its expression triggers are built raises the unpublishable barrier, and the inline DDL flush waits until they build
date: 2026-11-16
updated: 2026-11-16 18:45
status: accepted
kind: fix
issues: [1922]
prs: [1970]
areas: [evita_engine/src/main/java/io/evitadb/core/catalog, evita_api/src/main/java/io/evitadb/api, evita_engine/src/main/java/io/evitadb/core/expression/trigger/IndexingExpressionInputValidator.java]
supersedes: []
superseded-by: []
relates: [2026-09-08-warm-up-invalid-schema-refuses-to-publish, 2026-11-03-schema-builder-no-op-mutation-pruning]
---

# A warm-up schema change refused when its expression triggers are built raises the unpublishable barrier

At the end of every schema change, evitaDB builds the triggers that keep conditional facets and histograms current
from the indexing expressions of each entity type the change touched. Some definitions are refused only by that build,
after the change has already been applied to its collections. In a `WARMING_UP` catalog that refusal now raises the
unpublishable barrier of `2026-09-08-warm-up-invalid-schema-refuses-to-publish`: nothing further is published, the
catalog is deactivated, and activating it again loads the last state it published. The warm-up flush that
collection-level DDL runs in the middle of a schema change is skipped while an entity type the change has touched so far
does not build its triggers, so it cannot publish the refused definition ahead of the refusal. A warm-up rename whose
source collection has not been published yet is refused before it changes anything. `ALIVE` catalogs are unchanged:
their transaction rolls the refused change back. Limiting the change to the warm-up phase was decided deliberately.

## Why

A refused definition reached the disk. In a warming-up catalog the build's refusal reached the caller, and the
catalog-level `catch` of `Catalog#updateSchema` restored only the catalog schema. The refused entity schema stayed
exchanged in its collection and its storage part stayed in the data-store buffer. The session close validated the
catalog schema - `validate()` builds no trigger - and published it; the next start built every trigger again and
reported the catalog `CORRUPTED`. This was measured for twelve expressions `IndexingExpressionInputValidator` accepts,
among them `($entity.attributes['tags'] *? $reference.referencedEntity).size() > 0 &&
$reference.referencedEntity.attributes['status'] == 'A'`, `($entity.references['tag'] ?? null).any($.attributes['x'] >
0)` and a numeric default its attribute type cannot hold (`$reference.attributes['share'] ?? 0.5` on an `Integer`
attribute fails with `ArithmeticException: Rounding necessary`). Each extension of the payload validator found new
shapes the build refused, one of them opened by the extension before it, so predicting the build's verdict shape by
shape did not converge.

A second route published the same state before the build ran at all. `createEntitySchema`, `removeEntitySchema` and
`doReplaceEntityCollectionInternal` flush inline in warm-up, gated by `validate()` alone, so a change that modified B
with a refused expression and then created C published B's refused schema together with C before the build of the same
change refused it.

### Previous state

Until the trigger rebuild was deferred to the end of the schema change (`2cc6285041`, 2026-04-24), the build ran inside
`EntityCollection#updateSchema`, whose `catch` restored the collection's schema and whose `finally` re-buffered the
restored storage part: a refused build was reverted together with its collection. The deferral moved the refusal
outside that per-collection revert, to the catalog-level `catch`, which restores only the catalog schema. Its commit
message ("any validation failure still flows into the existing revert path") and the comment it left in the `finally`
("the revert above restored the pre-batch schema") both assumed otherwise. The test that later pinned the warm-up
behaviour called it the behaviour warm-up "retains", and its published state was consistent only because it supplied
the missing attribute and forced a rebuild before its session closed. Nobody chose the outcome.

## Options considered

### Option A - raise the barrier on a refused build in warm-up, and gate the inline DDL flushes on the build (chosen)

`Catalog#drainTriggerRebuildFrame` raises the barrier through `markUnpublishableDueToInvalidSchema` when the build
throws without a transaction in `WARMING_UP`. `Catalog#flushMidSessionIfSchemaValid` additionally skips while an entity
type on the pending rebuild frames does not build. A warm-up rename of a collection the published catalog header does
not name is refused before it changes anything.

- **Pros:** closes the class for every expression shape whose owner the build at the end of a change checks,
  independently of how well the payload validator predicts the build; reuses the barrier, every route that already
  consults it and its operator story; 61 lines of code added and 21 removed in `Catalog`, most of the removed ones
  the build moved into a method of its own; `ALIVE` is untouched.
- **Cons:** a definition mistake only the build catches - an ordering mistake such as a histogram defined before the
  attribute it reads, in separate calls, included - costs a deactivation and the session's unpublished work, where it
  used to throw and let the session continue. An inline flush still publishes the buildable prefix of a change refused
  later. A rename of a collection whose inline publication was skipped is refused, also in a change that is valid by
  the time of the rename.

### Option B - restore the pre-change entity schemas (declined)

Keep, per outermost schema change, the first pre-image of every entity type it exchanged and the trigger registry it
started from; on a refused build re-install them through `EntityCollection#exchangeSchema`, re-buffer their storage
parts (the key is constant, so the later write replaces the pointer and the refused record becomes inert), and set the
registry back.

- **Pros:** a late refusal becomes an ordinary definition refusal - no deactivation, no lost work - for every
  expression shape; it restores what the engine did before the deferral.
- **Cons:** it needs a record of pre-images and a restore path across collections.
- **Rejected because:** it is the cross-collection undo `2026-09-08` names as absent, built on an enumeration of the
  exchange's side effects (the capability usage registry, dropped reduced-index lookups, reflected re-binding of other
  collections, the schema index, a nested cascade's catalog schema part) where a missed one fails silently;
  `initRootNodes` cannot be undone, and a change that creates, removes or renames a collection cannot be undone either,
  so those changes still need the barrier; estimated at 80-130 lines across `Catalog` and `EntityCollection`.
  **Revisit when** deactivation for build refusals shows up as a real cost in imports, or warm-up gains a journal of
  schema objects (the per-entity savepoint journals storage parts and maps, not schemas).

### Option C - hold the refusal pending until the session closes (declined)

Keep the set of entity types whose build was refused; every later build retries them; the session close and go-live
raise the barrier only if the set is still not empty.

- **Pros:** an ordering mistake corrected in the same session costs nothing.
- **Cons:** a second, non-terminal catalog state beside the barrier.
- **Rejected because:** while the set is not empty, every publication route and every entity write has to stop - an
  entity written while a refused owner's triggers are stale is indexed wrongly - so the pending state needs its own
  consultation points on the same routes the barrier guards, and the close and go-live need to read it outside the
  schema-version gate of `EvitaSession#validateCatalogSchema`, which skips validation when the version did not move
  and which the refused change's own `catch` resets. That is lifecycle integration beyond the scope chosen for this
  change. **Revisit when** the pending state can be folded into `isPublishable()` itself, so every route consults it
  without being listed.

### Option D - build the triggers in the definition preflight (declined)

Build the post-change schemas as an overlay of the preflight's dry runs and call
`DefaultCatalogExpressionTriggerRegistry.buildFromSchemas` on it once, before anything is exchanged, discarding the
result.

- **Pros:** every build refusal of a change that modifies existing collections becomes an ordinary refusal, in warm-up
  and live alike; the verdict equals the load's, which builds every entity type and so also catches a change on the
  target side; the factories need no change - they are static, register nothing and generate no proxy classes at build
  time, and no refusal at build or load depends on entity data, so a build-and-discard there needs no new seam.
- **Cons:** a second, side-effect-free view of the post-change schemas.
- **Rejected because:** the preflight has no post-change view: its dry run skips collections the change creates,
  swallows failures, applies every mutation against the pre-change catalog schema, does not chain two modifications of
  one type and returns nothing. Creates, renames, removals, cascades and reflected re-binding would need a second apply
  pass at schema level, several hundred lines. It also changes what a live session sees: a refusal before registration
  leaves the transaction usable. **Revisit when** turning the common case - a change that modifies existing
  collections - into an ordinary refusal is worth it; the partial form (overlay plus one build, 60-100 lines) does
  that and leaves this barrier as the backstop, and is also the place to rewrap the build's raw exceptions (an
  `ArithmeticException` reaches the client as an internal error).

### Option E - publish collection-level DDL once, at the end of the outermost schema change (declined)

Move the three inline flushes after the build, so a refused change publishes none of its prefix.

- **Pros:** nothing of a refused change reaches the disk; one publication per change.
- **Cons:** changes when warm-up DDL publishes.
- **Rejected because:** a rename finds the data file of the collection it renames through the published catalog header
  (`DefaultCatalogPersistenceService#replaceCollectionWith`), so `[create c, rename c → d]` in one change depends on
  the inline publication of its first step. Keeping it working needs a publication point before such a rename, which
  publishes part of a change that may still be refused, or a storage-layer lookup of an unpublished source.
  **Revisit when** the storage layer can rename a collection that has not been published.

### Option F - build what a failed change exchanged, in its failure handler (declined)

When a warm-up change fails for another reason before its build, build the entity types it exchanged in the `catch`: a
refusal raises the barrier, success brings the triggers in line with the schemas warm-up keeps.

- **Pros:** closes the route by which a change failing for an unrelated reason leaves an unbuildable schema for the next
  flush to publish; brings the kept prefix's triggers in line with its schemas.
- **Cons:** a change that fails for an unrelated reason while an earlier mutation's expression does not build yet - a
  mutation later in the same change would have completed it - deactivates the catalog.
- **Rejected because:** it widens the breaking change beyond the build refusal itself, and the scope of this change was
  limited to the barrier on the build. **Revisit when** a catalog is found `CORRUPTED` after a warm-up change that
  failed for another reason, or the next change to warm-up failure handling is made anyway.

### Option G - one more rule in the payload validator (declined)

Refuse a property or constant-key step on a chain that does not start at a variable, predicates included.

- **Pros:** no engine change; it closes five of the six shapes that a step or a predicate on the result of `*?` or
  `??` produces.
- **Cons:** it closes only the shapes it was measured on and leaves the numeric default and every shape not found yet
  to the build, so the build's refusal still needs an answer of its own.
- **Rejected because:** each extension of the validator found new accepted-then-refused shapes, one of them opened by
  the extension before it; the rule widens a refusal that also runs at load, where a refusal means `CORRUPTED`, beyond
  the shapes it was measured on; and it cannot decide refusals that depend on another entity type's schema, such as the
  numeric default. **Revisit when:** never as the way to close the class; single rules may still turn a common shape
  into an ordinary preflight refusal. The validator applies such rules, narrower than this one, to the top-level forms
  the build refuses: a property or constant-key step on the result of `??` or `*?` in a histogram condition, and a name
  applied to a data container that is such a result (*The payload validator's step rules* under Key technical
  details; Option H covers the forms that build without error).

### Option H - drop the validator's step rules and rely on the barrier (declined)

Accept every step on the result of `??` or `*?` and every parenthesized name in the payload validator, and leave the
forms the trigger build refuses to the barrier of Option A.

- **Pros:** fewer rules in the validator, whose every rule also runs at load, where a refusal means `CORRUPTED`.
- **Rejected because:** the barrier catches only what the trigger build refuses. A parenthesized name and a name
  applied to a coalesced container inside a predicate build without error and record no dependency on the data they
  read, so nothing would refuse them, and the index would be expected to go stale when that data changes (suspected,
  not measured). The forms the build does refuse would cost a deactivation in warm-up where the rule costs an ordinary
  refusal at definition.
  **Revisit when** the dependency analysis maps a step on the result of `??` or `*?` to each operand and reads a name
  through parentheses; each rule can then go.

## Decision

**Chosen: Option A.** The class could not be closed by prediction, and the only answer that is complete without an undo
is the one `2026-09-08` already chose: refuse to publish, then reload the last published state. The gate on the inline
flushes is the smallest change that keeps them from publishing ahead of the refusal; it does not move any publication,
so a rename after a create in the same change keeps finding its source on the published header whenever the change's
types build at the preceding inline flush. Limited to warm-up deliberately: `ALIVE` rolls a refused change back with its
transaction, and the `ALIVE`-side gaps below are left as they are. Option B would win if deactivation for build refusals
proved to be a real cost in imports and warm-up gained a journal of schema objects.

## Key technical details

- **The build raises the barrier where it throws.** `Catalog#drainTriggerRebuildFrame` marks the catalog unpublishable
  when a build throws with no transaction in `WARMING_UP`, and rethrows the build's exception unchanged. A catalog
  mutation's cascade is applied as a nested schema change and raises it from its own build. In `ALIVE` the session's
  transaction is marked rollback-only as before, and the commit replay runs in a transaction of its own; neither reaches
  the barrier.
- **A failure of the marking is attached, not thrown.** `recordUnpublishableCause` sets the cause before it schedules
  the deactivation, so the barrier stands even when scheduling is rejected; the rejection is added to the build's
  exception as suppressed, and the operator log line is then missing. This path has no test: the only rejection is a
  shut-down scheduler while a schema change still runs, which no public route reaches.
- **The inline DDL flush asks the build too.** `Catalog#canBuildPendingExpressionTriggers` builds the triggers of every
  entity type on every pending rebuild frame and discards them; the registry is not touched. It catches every
  `RuntimeException`, `EvitaInternalError` included, because the factories refuse user input with several exception
  types, `GenericEvitaInternalError` among them, and skipping a publication never refuses; an engine defect it swallows
  is thrown again by the build at the end of the change when the change reaches it - a change that fails earlier
  discards its rebuild frame unbuilt (see the limitations). VM errors propagate.
- **What an inline flush still publishes.** It flushes every collection. The gate guarantees only the entity types on
  the pending frames: a change's own refused definition never goes out with it, its buildable prefix can. Anything
  another change left behind is not checked (see the limitations).
- **A warm-up rename needs its source on the published catalog header.**
  `DefaultCatalogPersistenceService#replaceCollectionWith` resolves the renamed collection's data file through the
  last published header, by the collection's name and primary key, and `Catalog#doReplaceEntityCollectionInternal`
  has exchanged the schema and taken the collection out of the collection map before that lookup runs. The method
  therefore asks the header first and refuses with `InvalidSchemaMutationException` when the source is not there,
  before anything changes. The source can be missing from the header for three reasons: the gate skipped the inline
  flush of the operation that created or renamed it, that flush found no change to publish, or the collection was
  removed and re-created under its published name while the flushes were skipped, so the header entry under that
  name belongs to the removed collection. `Catalog#flush` publishes when the catalog schema version moved or a
  collection's header version grew; the copy a rename installs reports no growth, and the catalog schema version the
  rename bumps is exchanged only after the rename returns. Two renames in a row with nothing before them therefore
  failed in the storage step without the gate as well, with `CollectionNotFoundException`. Only the source is checked,
  by name and primary key; a published collection replacing one that is not published yet is accepted.
- **The operator message is the validation barrier's.** `markUnpublishableDueToInvalidSchema` says the stored data is
  intact and activation loads the last published state; the cause attached to the log line names the build's refusal.
- **The payload validator's step rules.** `IndexingExpressionInputValidator` refuses, by decision, two kinds of step
  that the dependency analysis (`AccessedDataFinder`) cannot map to the data they read; `IndexingExpressionInputCells`
  holds the verdicts.
  - *A step on the result of `??` or `*?`.* In a histogram condition, outside a predicate, a spread mapping or a key,
    every property step and constant-key element step of the chain applied to such a result is refused:
    `($entity.attributes['tags'] ?? $entity.attributes['fallbackTags'])[0] == 'A'`,
    `($entity.attributes['validity'] ?? $entity.attributes['fallbackValidity']).from != null`. In every indexing
    expression, facet conditions and predicates included, a name applied to a data or `references` container that is
    such a result is refused, also when it is a string constant: `($entity.attributes ?? null)['status'] == 'A'`; a
    histogram condition refuses that form earlier, as a container read without a name. A method such as `any(...)` or
    `size()` on such a result, and a `??` whose result nothing steps into, are not refused by this rule. The reason:
    the finder continues the path of such a step from the `??` node, which is no variable. At the top level the path
    starts at the step's own name or key, and the trigger build throws "Expected variable at path root" - seen for
    `($entity.attributes ?? null)['status']` in a probe run before the rule existed, which no retained test repeats,
    and traced for the histogram forms - so without the rule such a definition
    would cost a deactivation in warm-up instead of an ordinary refusal. Inside a predicate the name is appended to the
    predicate's receiver without the container: `$entity.references['tag'].any(($.attributes ?? null)['w'] > 1)` builds
    and records no dependency on `w`. A property step on the result of `??` in a facet condition, such as
    `($reference.referencedEntity ?? $reference.groupEntity).attributes['status']`, passes the check: in a facet
    condition this rule refuses only the container-name form. Its build verdict is not measured.
  - *A name in parentheses*, `$entity.attributes[('status')]`, is refused as a dynamic name. The finder takes a name
    only from a key written as a bare constant; for a constant in parentheses it records the container alone, and
    `ExpressionDependencyClassifier#extractLocalDependencies` finds no attribute in that path, so no hook learns that
    `status` is read. The trigger builds without error. The stale index this would cause is suspected, not measured.

## Verification

`IndexingExpressionInputRefusalTest$TriggerBuildRefusalInWarmUp` defines twelve expressions the payload validator
accepts and the build refuses - nineteen definitions, the histogram conditions in both the `bucketedPartially` and the
`assignedWhen` slot - each in a catalog of its own, and asserts that the refusal comes from the build, that the barrier
deactivates the catalog, and that the restarted catalog loads its last published state without the refused reference,
never `CORRUPTED`. The histogram conditions: `$entity.parentEntity?.primaryKey > 0 &&
$entity.parentEntity?.attributes['status'] == 'A'`; `$reference.referencedEntity.scope == 'LIVE' &&
$reference.referencedEntity.attributes['status'] == 'A'`; `$reference.referencedEntity != null &&
$reference.referencedEntity.primaryKey > 0 && $reference.referencedEntity.attributes['status'] == 'A'`;
`($reference.referencedEntity).attributes['status'] == 'A'`; `($entity.attributes['tags'] *?
$reference.referencedEntity).size() > 0 && $reference.referencedEntity.attributes['status'] == 'A'`;
`($entity.attributes['tags'] *? 'none').size() > 0 && $reference.referencedEntity.attributes['status'] == 'A'`;
`($entity.attributes['tags'] ?? $entity.attributes['fallbackTags']).any($[0] == 'x')`. The histogram value:
`$reference.attributes['share'] ?? 0.5` on an `Integer` attribute. The facet conditions:
`$reference.referencedEntity.attributes['price'] + 5 > 10`; `($entity.references['tag'] ?? null).any($.attributes['x'] >
0)`; `($entity.references['brand'] *? $reference).size() > 0 && $reference.referencedEntity.attributes['status'] ==
'A'`; `($entity.references['brand'] *? $reference).any($.attributes['share'] > 0)`. Before the change all nineteen
failed: no deactivation, and the restart failed to load with the build's refusal and reported `CORRUPTED`.

`WarmUpRefusedSchemaPersistenceTest$TriggerBuildRefusal` covers the change shapes, twenty-one tests. A refused
modification followed by an explicit create, an implicit create, a removal and a rename; a create followed by a refused
modification, whose reopened catalog holds the created collection - nothing publishes after the barrier, so that
collection was published by the inline flush before the build; a catalog cascade followed by a refusal; a catalog
cascade refused by its own nested build. Controls: two creates, a create followed by a rename of the same collection,
two renames in a row after a modification of another collection, a histogram and the attribute it reads in one change,
the same with a create between them (the skipped flush is published by the close), a preflight refusal that leaves later
writes working, an implicit create by an entity upsert, a published collection renamed over one created in the same
change, and a rename of a collection an earlier session published under the name an earlier rename gave it. Refused
before they change anything, with the session going on and the close publishing the rest: a rename of a collection whose
create the gate skipped, a rename of a collection whose earlier rename the gate skipped, an overwriting rename whose
source the gate left unpublished, the second of two renames in a row with nothing before them, and a rename of a
collection removed and re-created under its published name while the gate skipped the flushes. The flipped tests:
`RefusedSchemaMutationTransactionFunctionalTest#shouldDeactivateWhenWarmUpTriggerBuildRefuses` (the corrected order is
accepted after the reactivation) and `BatchedCatalogSchemaMutationTest$SeparateCalls`.

Counterfactuals:
- With the barrier block removed from the build, 28 tests fail ("Catalog `testCatalog` was never reported as having
  settled into `INACTIVE`"): the nineteen definitions, the seven refusal shapes and the two flipped tests. The controls
  and the rename refusals pass.
- With the gate removed, 7 fail. The four refusals followed by DDL fail because the reopened catalog does not load
  ("references attribute 'missing' which does not exist on entity type 'BRAND'"). The three rename refusals that depend
  on the skipped flush fail with "nothing was thrown". The create-before-refusal case, the cascades, the nineteen
  definitions and every control pass.
- With the rename refusal removed, the four rename refusals fail with `CollectionNotFoundException` (measured before
  the fifth was added).
- With the rename refusal matching the source by name only, the rename of a collection removed and re-created under its
  published name passes it and fails in the storage step with `GenericEvitaInternalError` ("Entity collection
  persistence service for `renamedOnce` not found"); the control renaming a collection published by an earlier rename
  passes either way.

Blast radius, before and after: the nine reproduction classes of the 2026.3 histogram campaign, every functional test
class that defines an indexing expression or a warm-up schema refusal, and 17 classes that rename collections: 2,719
tests before, 2,765 after (the 46 new tests; measured before the two tests of the primary-key match were added). The 110
failures of the reproduction classes are the same testcases before and after, and no other verdict changed because of
this change. The barrier the build raises appears exactly 29 times, all in the tests above and in the warm-up row of
`ConditionalFacetIndexingTest#shouldRejectNonTranslatableExpressionAtSchemaTime`, which catches the refusal.

## Consequences & open follow-ups

**Known limitations** - every completeness statement above holds for owners whose trigger build the end of a schema
change actually runs:

- **Expressions the payload validator accepts and the build refuses cost a deactivation in warm-up**, not a preflight
  refusal: a `*?` or `??` whose result is stepped into or filtered where the validator's step rules (Key technical
  details) do not refuse it, in histogram and facet conditions; the histogram conditions reading the primary key, the
  scope or the bare entity one hop away beside an attribute read through it; arithmetic on an attribute of the
  referenced entity compared in a facet condition; the numeric default that does not fit the attribute type; and every
  shape of the same families not yet found. In `ALIVE` they roll the transaction
  back.
- **Removing or retyping an attribute another entity type's histogram reads is not refused**, in the same change or a
  later one, a catalog cascade included (a global attribute removed from the target type while the reader does not
  adopt it). The build covers only the entity types the change exchanged, and the reader is not among them; an inline
  flush or the session close publishes the state, and the next load reports the catalog `CORRUPTED`, in warm-up and live
  alike. Only a load-equivalent build after every schema change would catch it.
- **A change that fails for another reason before its build keeps what it exchanged, unbuilt.** The rebuild frame is
  discarded without a build, so an unbuildable definition among the kept exchanges reaches the disk with the next inline
  DDL flush of any later change or with the session close, and the next start is `CORRUPTED`; the kept owners also keep
  their previous triggers in memory. Measured with a diagnostic run kept out of the suite: a refused histogram followed
  by a create of an entity type that already exists fails at the create, raises no barrier, and the next start is
  `CORRUPTED`; so is a change that defines a histogram, creates and renames a collection, and only then adds the
  attribute the histogram reads - the rename is refused before the attribute is added. Option F closes it. The same
  route also loses part of a kept prefix: the `catch` restores the catalog schema version, so a kept rename that moved
  no collection header is not published by the session close unless something else changed - measured with two renames
  in a row, the second refused, where the session held the first name and the reopened catalog the original one.
- **A rename the barrier refuses leaves the data file it wrote for the new name.** The storage step of the rename
  writes the next generation of the collection under the new name before the change is refused. Nothing removes it:
  the shutdown drops only the retirements parked for files the published state names, and the load's purge keeps a
  collection file whose generation is newer than the published one. The same rename after reactivation computes the
  same file name and fails with "Cannot create new entity collection file" - measured with the diagnostic run above. A
  rename carried by a change the barrier refused therefore cannot be repeated under the same name after reactivation.
  The close-time validation barrier is traced to reach the same leftover when a rename precedes the refused change in
  the same session (not run). Repeating the other refused shapes after reactivation - a create, an implicit create, a
  removal - is not tested; repeating a modification is.
- **A warm-up rename of a collection created or renamed earlier in the same session is refused while that collection is
  unpublished**, also when the change is valid by the time of the rename (a histogram, a create, the attribute the
  histogram reads, then the rename, in one change). The storage-layer lookup of an unpublished source would keep these
  working (Option E's revisit condition). A rename whose storage step fails for another reason still leaves the
  collection under neither name in memory.
- **Go-live, the session close and engine shutdown publish without a full build.** The close validates and flushes;
  go-live validates and flushes before the new catalog builds every trigger; termination stores the header of a
  publishable catalog. None of them builds the triggers, so the limitations above reach the disk through them too.
- **Catalogs already stored with a refused expression** still load as `CORRUPTED`. Nothing here migrates or repairs
  them.
- **`ALIVE` is left as it is.** The session builds once after a change's last mutation, the commit replay applies and
  builds every mutation on its own, so a change that defines a histogram before the attribute it reads, in one change,
  is accepted by the session and refused by the replay; target-side changes reach a live catalog's disk as well.
- **Refusals at evaluation time are outside the build**: a local condition that does not evaluate to a boolean, a
  property the proxy does not map, arithmetic failing while a value is computed, and the presence check of a histogram
  withdrawal. They fail when an entity is written.
- **The cost of the barrier is the session's unpublished work**, for an ordering mistake or a typo as much as for a real
  error. #1497 (deactivate and drop have no undo) matters more for it. The options above are the ways to lower it.

Two limits of the payload validator itself are accepted as edge cases, documented and not fixed:

- **The check's time grows exponentially with the nesting of predicates whose `$` passes through `??` or `*?`.** The
  walk repeats an inner predicate once for every shape its receiver may have, and the result of `??` has the shapes of
  both operands. Measured on `$entity.attributes['tags']`, then N times `.any(($ ?? ($ *? 'x'))`, then `.size() > 0`,
  N closing parentheses and ` == true`: N = 16 (413 characters) 119 ms, N = 20 (505) 530 ms, N = 24 (597) 5.8 s. The
  check runs when the schema is defined, on every catalog load and on every trigger rebuild, so all three pay it. A
  fix must keep the order in which the walk finds refusals receiver by receiver, because the first refusal found is
  the read the message names.
- **A very long `??` chain overflows the thread stack in the check.** The walk takes two frames per operand
  (`walk` and `walkCoalesce`). A chain of about 3,000 operands (81,000 characters), which the parser handles, ends in
  `StackOverflowError` from the check; 2,600 operands still pass, with the default 1 MB thread stack.

**Revisit both when** indexing expressions are accepted from untrusted clients; a limit on the length or the nesting
depth of an indexing expression would cover both.

## Related work

- `2026-09-08-warm-up-invalid-schema-refuses-to-publish` - introduced the validation barrier, its message and the
  validity gate on the inline DDL flushes. This record adds the build as a second cause and a second condition on the
  same flushes, and narrows that record's assurance that an ordinary schema typo never costs a reload.
- `2026-11-03-schema-builder-no-op-mutation-pruning` - records that a warm-up change refused midway keeps what it
  applied. The build now runs over what such a change keeps when the change reaches its end, and raises the barrier
  when it refuses.

## Timeline

- **2026-11-15** - a warm-up refusal from the trigger build traced to the deferral of 2026-04-24; the barrier with a
  build gate on the inline DDL flushes chosen over restoring the pre-change schemas, holding the refusal pending,
  building in the preflight, publishing DDL at the end of the change, building in the failure handler and another
  validator rule; limited to warm-up deliberately; implemented under #1922
- **2026-11-15** - the validator's refusals of a step on the result of `??` or `*?` and of a name in parentheses kept
  as decisions over relying on the barrier; the validator's exponential time on nested coalescing predicates and its
  stack overflow on very long `??` chains accepted as edge cases
- **2026-11-16** - merged with PR #1970; the record accepted
