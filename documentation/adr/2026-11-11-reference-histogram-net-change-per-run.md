---
title: A reference's histogram contribution is withdrawn by the first mutation that can change it and added once per run of the deferred queue
date: 2026-11-11
updated: 2026-11-19 16:36
status: accepted
kind: fix
issues: [1911, 1916, 1918, 1922, 1923, 1948, 2029]
prs: [1921, 1954, 1955, 1970]
areas: [evita_engine/src/main/java/io/evitadb/index/mutation/local, evita_engine/src/main/java/io/evitadb/core/catalog,
  evita_engine/src/main/java/io/evitadb/core/expression/proxy]
supersedes: []
superseded-by: []
relates: [2026-04-23-bucketed-histogram-indexing, 2026-08-31-cross-entity-histogram-removal-pre-pass,
  2026-09-08-conditional-histogram-per-contribution-verdicts, 2026-11-19-one-histogram-store-per-group]
---

# A reference's histogram contribution is withdrawn by the first mutation that can change it and added once per run

The local write path of reference histograms no longer lets every local mutation queue its own "remove, then
add" action. Inside one run of the deferred queue a reference is either **clean** (the histogram holds exactly
what its stored state implies) or **pending** - in the code, awaiting its final add (the histogram holds nothing
from it and one add is owed). The first mutation that can change a clean reference's contribution withdraws that
contribution on the spot, while storage still holds the state the index reflects, and schedules the final add.
Every later mutation of the same reference does nothing for the histogram, and removing the reference cancels
the scheduled add. When the queue runs, every pending reference must still exist and is added once, from the
state stored at that moment. A reference, index or value that is not where this protocol expects it fails
loudly instead of being skipped.

## Why

One upsert that changed a reference twice left the histogram wrong, and the error was persisted. A reference
moved to another group together with a changed attribute kept its old value in the old group and held the new
one twice in the new group. A value changed there and back was held twice. A reference that never contributed
could take a sibling reference's unit of the same value. A doubled value is invisible to minimum, maximum and
bucket counts; it surfaces only when a later removal leaves one copy behind.

The constraint that made this non-obvious: the index executor sees each local mutation **before** the storage
executor does, so anything that depends on the final state has to be deferred, while anything that depends on
the starting state is only available at the moment the mutation arrives. A correct update needs both halves, and
they are available at different times.

### Previous state

Each handler queued its own action, and the handlers followed different rules:

- A reference attribute change captured the old values and the old condition when it was queued, removed them
  when the queue ran, and then added **every** histogram of the reference, once per affected histogram.
- A group change resolved the old group when it was queued but read the value when the queue ran, and removed
  by presence alone.
- An entity attribute change removed by presence and added, for every reference, when the queue ran.
- An insert queued an add and registered the reference in a set, so that a following attribute change skipped
  its own add. The group change and the entity attribute change did not consult that set.
- Reference removal removed synchronously, guarded by an evaluated condition.

The histogram matches storage only at the two ends of a run. Inside a run storage moves ahead with every
mutation and the histogram does not move. Each queued action still reasoned as if the histogram matched storage
at the moment the action was queued, which is true for the first action that touches a reference and false for
every later one. Two actions for one reference therefore added the final state twice, and at most one of them
removed the right thing. The unit of work was the mutation; the unit of correctness is the reference per run.

This held as long as an upsert changed a reference once, which is what the first tests of the feature did.

## Options considered

### Option A — withdraw at the first mutation that can change it, add once per run (chosen)

A set of pending references on the executor. A mutation that can change a clean reference's contribution
removes it immediately, with the condition evaluated against current storage and every removed value asserted
present, and marks the reference pending. Removing the reference cancels its pending add. The run adds every
pending reference, each of which must still exist.

- **Pros:** one rule for every handler; both halves use the same evaluator on the same storage accessor; the
  removal happens while the old group's indexes are still in place, so no destination has to be remembered; the
  only per-reference state is one small identity record.
- **Cons:** a reference whose contribution does not change in the end is still removed and added; an entity
  attribute change read by a condition evaluates every histogram condition of every affected reference twice.

### Option B — keep the first queued action of a reference and drop the later ones (declined)

- **Pros:** the smallest change to the queue.
- **Rejected because:** the kept action's removal knows only what its own mutation saw. For an attribute change
  followed by a group change, the kept action resolves the group when the queue runs and removes from the new
  group, while the value sits in the old one. It also keeps the "add every histogram per affected histogram"
  loop, which doubles the untouched histogram of a reference that carries two.

### Option C — capture the full state at every action (declined)

- **Pros:** each action is self-contained.
- **Rejected because:** the second action's capture describes a state that was never indexed, because the first
  action has not run yet. Its removal hits nothing, or a sibling's unit, and both actions still add the final
  value.

### Option D — capture a start contribution at the first touch, remove it when the queue runs (declined)

The same unit as Option A, with the removal deferred as well: a contribution object per touched reference, the
storage keys of every reduced index the values went to, and a removal that works from the captured values.

- **Pros:** exact; could skip the write when the start and the final contribution are equal.
- **Rejected because:** the equality skip was the only thing that needed the captured start, and it had to go.
  Range values compare by their own precision, while the histogram stores them at the schema precision, so two
  different stored ranges can compare equal and the update would be skipped. Without the skip the capture buys
  nothing over an immediate removal and costs an allocation per touched reference, a captured destination that
  can be larger than where the values sit, and a dependency on the old group's index surviving until the queue
  runs. Worth revisiting only together with a comparison of normalized values and a measurement that shows the
  saved writes matter.

### Option E — the pending set for reference mutations only, the old action for entity attributes (declined)

- **Pros:** leaves the entity attribute path, the one with the widest fan-out, as it was.
- **Rejected because:** correctness then rests on which of the two mechanisms runs first, and it still fails
  when the entity attribute changes before the first touch of a reference whose sibling carries the same value:
  the withdrawal evaluates the condition after the change and removes the sibling's unit. Two mechanisms with an
  ordering rule between them is the shape this change replaces.

### Option F — attribute every histogram unit to the reference that contributed it (declined)

- **Pros:** add and remove become idempotent per reference; a careless writer can no longer take a sibling's
  unit.
- **Rejected because:** the per-value counter is a released on-disk format, and counts cannot be converted into
  attribution without rebuilding every histogram from entity bodies. It would not remove a stale value either:
  a writer that removes the wrong value still leaves the old one behind. An exact writer does not need it. This
  is the same blocker [[2026-08-31-cross-entity-histogram-removal-pre-pass]] recorded for its Option C, and
  nothing has changed since.

### Option G — apply histogram changes synchronously, or let the index executor run after storage (declined)

- **Pros:** no queue and no pending state.
- **Rejected because:** a condition that reads two attributes changed by two mutations is only right at the end
  of the batch, so the add has to be deferred in any order. Swapping the executors only relocates the capture:
  the removal, and the ordinary attribute, sort and unique index updates of the whole executor, all need the
  state before the mutation. Running each action right after its own mutation would be correct, but it writes
  intermediate states into group indexes and evaluates conditions on half-built references, once per mutation.

### Option H — treat a reference re-indexed by a scope change as pending too (declined)

A scope change removes the entity from one scope and indexes it into the other. Leaving its references pending
in the new scope would make the scope change one more instance of the rule.

- **Pros:** no special case; later mutations of the same upsert find the reference pending and do nothing.
- **Rejected because:** it was implemented and measured as a regression. The deferred add then read the locales of
  a reference through a view of the entity's locales **before** the batch, while the re-index read the full
  entity body. An upsert that adds a locale and then changes the scope lost the new locale's value in the new
  scope: green before, red with the pending variant, green again with the synchronous add.
- **Since #1922** the deferred add reads the current locale set, by the same rule as the re-index
  (`ReferenceIndexMutator#resolveHistogramLocales`), so that reason no longer holds. The synchronous re-index stays
  anyway: no defect requires the change, the pending variant was neither re-implemented nor re-measured under the
  new rule, and the synchronous add is what the scope-change cells of `ReferenceHistogramLocaleSetFunctionalTest`
  and their re-index counterfactuals pin. Revisit only together with a measurement of the pending variant under
  the current rule.

## Decision

**Chosen: Option A, with the scope-change re-index kept synchronous (against Option H).**

The driver is the unit. Whatever is done once per mutation composes wrongly as soon as two mutations meet on one
reference; whatever is done once per reference per run cannot. Among the per-reference options, the immediate
removal is the one that needs nothing remembered between the withdrawal and the run, because the removal wants
exactly the state storage still holds when the first mutation of the reference arrives.

The scope-change exception does not weaken the rule. The re-index runs right after the queue has been run, so
nothing is pending at that point, and it leaves every reference **clean** in the new scope: a later mutation in
the same upsert withdraws what the re-index added, like for any other clean reference.

Option D becomes the better choice if the removal ever has to be decided at the end of the run, for instance to
skip unchanged contributions. Option F becomes the better choice only together with a rework of the histogram
storage that pays for a rebuild anyway.

## Key technical details

- **The state** is `referencesAwaitingFinalHistogramAdd` in
  `evita_engine/src/main/java/io/evitadb/index/mutation/local/EntityIndexLocalMutationExecutor.java`. It records
  references whose final histogram values must be added once in the current deferred run and is allocated only
  for references with histogram triggers.
- **Withdrawal and scheduling** use `#withdrawHistogramValuesAndScheduleFinalAdd`:
  1. It runs before the mutation handler, at the top of `#updateReferences`. Representative-key migration and
     group-index removal must happen after withdrawal so it sees the indexes that contain the current values.
  2. It asserts that the reference exists in storage. Inserts use a separate method and entity attribute fan-out
     supplies stored existing references. Later mutations of a recorded reference skip withdrawal.
  3. Each trigger removes its values when its condition holds against current storage. Withdrawal asserts that
     every value is present for the owner in the locale bucket: in the referenced-type index when ungrouped,
     or in every reduced index listed for the group. A grouped reference requires the group-type index and at
     least one listed index. Missing indexes, histograms, locale buckets or value-owner pairs fail loudly.
     The values must have the shape the histogram descriptor declares: `ReferenceIndexMutator#resolveHistogramValues`
     fails on an array where a single value is declared or vice versa, and on a value of the wrong number or
     range kind, instead of resolving to fewer values whose presence would then go unchecked. Whole-entity
     removal takes another route, see the last item.
  4. `RemoveReferenceMutation` cancels the final add for the exact identity that withdrawal or insertion recorded.
  5. `#addFinalHistogramValuesOfAwaitingReferences`, called by `#executeDeferredReEvaluations`, asserts that every
     recorded identity resolves to an existing reference. It adds final values once using the current group and
     condition; a grouped value requires the group-type index to list at least one reduced index for the group,
     as the withdrawal does. The set is cleared in `finally` after every deferred run, including before a scope
     change.
- **Mutation selection** uses `#canMutationChangeHistogramValues`. Every non-attribute reference mutation other
  than insertion requires withdrawal. An attribute change does when it is a histogram value source or condition
  input. Any attribute change on a grouped reference allowing duplicates requires withdrawal because it can
  start representative-key migration, including when the attribute itself is unrelated to the histogram.
- **Entity attribute, parent and associated-data changes** withdraw values for stored references whose histogram
  condition reads the changed input, before it changes in storage. Attributes go through
  `handler/AttributeMutationFanOut.java`, using `LocalTriggerIndex#getHistogramReferencesForEntityAttribute`; the
  parent and associated data through `handler/ParentMutationFanOut.java` and
  `handler/AssociatedDataMutationFanOut.java`, which share `handler/OwnerInputHistogramWithdrawal.java` and use
  `#getHistogramReferencesForParent` and `#getHistogramReferencesForAssociatedData`. All three lookups list
  cross-entity triggers too: a condition reading an attribute of the parent is one. An input no histogram reads
  costs one registry lookup. The parent fan-out does nothing while the entity is removed entirely, see the last item.
- **Reference identity** uses `HistogramReferenceIdentity`: reference name, referenced primary key and internal
  primary key. Positive, temporary negative and unknown `0` keys remain distinct. Unlike `ReferenceKey#equals`,
  this prevents a removed reference and a new reference to the same target from sharing one scheduled add.
  The index executor drains before the storage executor assigns missing IDs at the end of the phase, so a key
  recorded as `0` still resolves as `0` during the final add.
- **Insertion** uses `#scheduleFinalHistogramAddOfInsertedReference`, without withdrawal. Recording through
  `#recordAwaitingFinalHistogramAdd` asserts that the identity was not already recorded. Membership is checked
  by `#isAwaitingFinalHistogramAdd`.
- **The scope-change re-index** in `#indexAllReferences` asserts that no reference awaits a final add, adds
  synchronously from the full entity body and records nothing. See Option H before changing it.
- **Whole-entity removal** removes the values present for the owner through
  `ReferenceIndexMutator#removeHistogramFromIndex`, without evaluating any condition, on both of its paths: deleting the
  entity (`RemoveReferenceMutation` while `isEntityRemovedEntirely()`, routed by `#updateReferences` to
  `#removeHistogramValuesPresentForOwnerOfRemovedEntity`) and unindexing the entity from a scope it leaves
  (`#unindexReferences`, which first asserts that no reference awaits a final add). All references of the owner leave
  together. Asserting presence would require evaluating each reference's condition on every such removal, and entity
  deletion drops the parent before the references, so a condition reading the parent would see a state the values were
  never added under. Entity removal schedules no final add, so a reference awaiting one there fails like at unindexing.

## Verification

Every scenario checks the histogram right after the upsert and again after a follow-up that moves the value
into the range of anchor products, in memory and after a restart; only the second check can see a doubled value.

- `ReferenceHistogramNetChangeFunctionalTest` — 74 cases, 0 failures. Before the change, on the same test code, 26 of
  its then 46 cases failed. They cover two histograms on one reference, an entity attribute change with a
  non-contributing sibling, a group move of a non-contributing reference, an entity attribute change together with a
  reference change, a condition attribute removed before the reference with a value fallback, a group change followed by
  removal with a value from the referenced entity, removal and re-insertion of one target, and the only owner of a group
  leaving it. Six cases cover localized values, among them the locale added before a scope change that decided against
  Option H. Later cases add an array changed twice in one upsert, a defaulted reference inserted after a scope change,
  mutations of a missing reference rejected by the withdrawal, a reference inserted and removed in one upsert, changes
  on both sides of a scope change, the removal of an owner whose references changed and the only owner of a group losing
  its group.
- `ScopeChangeDeferredReEvaluationFunctionalTest` — 197 cases, 0 failures; 40 of them failed before the change.
- Together **271 of 271**.
- `EntityIndexLocalMutationExecutorHistogramProtocolTest` — 28 cases, 0 failures. All assertions of the protocol but two
  are shown silent on the code before they existed and failing after. A withdrawn reference missing from storage, an
  awaiting reference missing or dropped at the final add, a re-index or unindexing that starts with a reference
  awaiting, an insertion already awaiting and a value missing at withdrawal, ungrouped and grouped, fail with the
  identifiers that locate the data. A grouped final add whose group lists no reduced index names the group, reference,
  histogram and owner. Seven value shapes that contradict the histogram descriptor, and one such value reached through a
  reference removal, fail naming the source attribute and the value's class only. Two assertions have no negative test,
  because their callers guarantee the precondition: the failed cancellation (its caller passes the identity the
  withdrawal has just recorded or found scheduled) and the missing reference of an entity removed entirely (entity
  removal names only its stored references). An entity removal that meets a reference awaiting a final add fails in a
  protocol test that schedules the add before marking the entity removed. Three cases cancel the final add of an
  inserted reference for each internal key form and check that the histogram holds nothing afterwards; three controls
  show that the same sequence without the removal adds the value.
- `ParentHistogramConditionLifecycleFunctionalTest` — 24 cases, 0 failures. Six of them remove an entity whose
  histogram condition reads its parent, in both catalog states: an archive-and-return round trip with a value
  whose condition does not hold, a direct deletion in the same state (it left the value behind before deletion
  stopped evaluating conditions: `1:20` instead of `10:20`), and the deletion of an entity whose condition holds
  only without a parent (it failed with `Cannot withdraw histogram value`). The value whose condition does not
  hold is stored before the condition is attached, which is not recomputed (#409). The cases reached it by a
  parent change until #1923 made a parent change re-evaluate the condition; on that old route the four cases fail
  with `1:20` expected and `10:20` found.
- `ReferenceHistogramPathParityFunctionalTest` — 12 cases, 0 failures. It reaches one final state through the
  local path and through the cross-entity path and compares every bucket of the two histograms, after the change,
  after a follow-up removal and after a restart. The same 12 pass on the code before this change.
- `HistogramReferenceIdentityTest` pins the strict identity, including a temporary reference beside a dropped
  stored one.
- Neighbouring suites, **333 of 333**: `RepeatedScopeChangeFunctionalTest` 36,
  `LocaleRemovalWithArchivingFunctionalTest` 68, `ConditionalBucketIndexingTest` 135,
  `ConditionalBucketQueryTest` 8, `HistogramScopeChangeTest` 12, and the four
  `EntityIndexLocalMutationExecutor*Test` classes 74.

No benchmark was run. The cost was assessed by reading only: an unrelated non-localized attribute change is cheaper
than before, a second mutation of one reference costs a set lookup, and an entity attribute change read by a
histogram condition pays one more condition evaluation per affected reference and histogram. Since #1922 an unrelated
localized change is not cheaper on an entity type with a localized defaulted reference-attribute histogram: an
upsert in a locale the entity lacks, any localized removal and the removal of a reference holding localized
attributes walk the N stored references and withdraw and re-add the K that carry such a histogram, evaluating every
histogram trigger of each - O(N·K). On any other entity type it costs one registry map probe.

## Consequences & open follow-ups

- **The deferred add read the locales the entity had before the upsert.** *Fixed: #1922.* Every path of a localized
  `REFERENCE_ATTRIBUTE` value read `ExistingAttributeValueSupplier#getEntityExistingAttributeLocales`: the entity's
  attribute locales from before the upsert, at the final add too; after a scope change only the locales of the entity's
  own attributes, so a locale only a reference gave the entity was lost; and a lost associated-data-only locale counted
  as an attribute locale, so a withdrawal asked for a default never added.
  `ReferenceIndexMutator#resolveHistogramLocales` now serves the final add, the re-index, the withdrawal and both
  removals with one rule:
  - without a default, the locales of the reference's own values of the source attribute;
  - with a `??` default, those plus every locale of the entity as its in-flight body has them at that moment,
    associated data included, because a query admits an owner by all its locales and the histogram synthesizes no
    default at read time. Whole-entity removal reads the locales from before the removal: it removes the references
    first, and a removed reference can take a locale a sibling still holds the default in.
  - **Every mutation that may change the entity's locale set withdraws first.** `handler/EntityLocaleFanOut`, called at
    the top of six handlers - a localized entity or reference attribute upserted or removed, localized associated data
    upserted or removed, a reference removed - withdraws every stored reference whose histogram reads a localized
    defaulted reference attribute (`CatalogExpressionTriggerRegistry#getLocalHistogramReferencesWithLocalizedDefault`).
    A withdrawal therefore reads the set the reference's last add used, and the run's final add the set the run left.
  - **Binding on later work.** A new writer of the entity's locale set needs the same hook, or the next withdrawal of a
    defaulted value is refused. The two reference hooks must precede `ReferenceMutationFanOut#apply`: it returns early
    for a reference not indexed in the scope, whose locales still count, and a removal there cancels the removed
    reference's final add. A path that adds or removes a defaulted localized value - the cross-entity path included -
    must use the same rule.
  - **Declined:** the hook in `upsertEntityLocale` / `removeEntityLocale`, because they run in `applyChanges` after the
    last final add, so a withdrawal there is never re-added; and a delta of default units in `applyChanges`, because it
    is a second update protocol with its own finishing-phase lifecycle that the in-protocol hook makes unnecessary -
    revisit only if a locale writer turns up that the index executor cannot see before storage applies it.
  - **Cost.** An owner type without such a histogram pays one registry map probe per localized mutation or reference
    removal, a non-localized mutation nothing. With one, an upsert in a locale the entity has costs one set lookup
    more; a possible change walks the N stored references and withdraws the K selected, each withdrawal resolving its
    attribute supplier by a linear scan and evaluating every histogram trigger of the reference: O(N·K). Removals are
    conservative and re-add what they withdrew. No benchmark was run.
  - The scope-change re-index stays synchronous; Option H is not revisited in 2026.3. Catalogs written before the fix
    hold defaults over the old set, so a withdrawal there may ask for a default that was never added - read from the
    code, not run; the full reindex the release recommends rebuilds them.
  - **Verified** by `ReferenceHistogramLocaleSetFunctionalTest`, 64 cases in both catalog states: the 8 cases #1922
    was reported with, 4 owner-side scenarios of the #1778 sweep and 24 locale-set scenarios. Of the first 54 cases,
    40 were red before the fix (26 of them refusing a write); the scenario applying a delta to a localized value and
    the four of a reference holding no value of the source attribute in any locale - its owner gains or loses a
    locale, it gets its first value, it loses its last - were added later and never run on the code before the fix.
    All 64 green after. Nineteen counterfactual arms - each of the six hooks, both halves of the rule, the default
    alone of a reference holding no value, the original enumeration of a defaulted value, the entity domain, the
    scope-blind leak of a lost associated-data-only locale, the re-index, the removal domain, the removal guard and
    the two hook placements - each turn their own cells red; the arm returning no locale for a reference holding no
    value turns exactly the eight cases of those four scenarios red, by a missing default rather than a refusal. In
    the campaign's nine repro classes only #1922's four failing cases changed, to green.
- **A reference inserted together with a scope change got no schema default values.** *Fixed in the same pull
  request: #1930.* The scope-change branch of `ContainerizedLocalMutationExecutor#popImplicitMutations` skipped
  the reference attribute verification, so the default values were missing from the stored reference and a
  missing mandatory attribute was accepted. It now verifies the references the upsert names, as the same-scope
  branch does.
- **A group split into several reduced indexes holds and answers its histogram wrongly.** *Fixed: #1924.*
  [One histogram store per group](2026-11-19-one-histogram-store-per-group.md) removes replication: one
  `GroupHistogramEntityIndex` per reference, group and scope holds contributions, and boundary resolution uses
  all reduced indexes listed for the group. Withdrawal checks the store rather than every reduced index.
- **Conditions reading the parent or associated data are re-evaluated locally.** *Fixed: #1923.*
  `ParentMutationFanOut` and `AssociatedDataMutationFanOut` withdraw the histogram values of every stored
  reference whose condition reads the parent or the changed associated data before the input changes; the final
  add of the run evaluates the condition again. The references come from
  `LocalTriggerIndex#getHistogramReferencesForParent` and `#getHistogramReferencesForAssociatedData`. An input no
  histogram reads costs one registry lookup. The parent fan-out does nothing while the entity is removed entirely:
  entity removal drops the parent before the references and removes their values without a condition.
  A withdrawal evaluates the condition against the stored parent, which may sit in another scope than the evaluated
  one. Such a condition failed to evaluate - `EntityParentPartial` handed it a reference without data, on which `?.`
  does not short-circuit - so the withdrawal would have refused parent and label writes that were accepted before.
  An expression reading data of the parent now sees no parent in another scope, as `ExpressionProxyInstantiator`
  documents (`EntityProxyState#parentEntityRequested`); a bare `$entity.parentEntity != null` still answers from the
  entity body and sees such a parent as present. Facet conditions take the same path and failed the same way.
  `ParentAndAssociatedDataHistogramConditionFunctionalTest` — 86 cases, 0 failures; 58 failed without the
  withdrawals, 30 of them with a refused write. It changes either input alone, with the share rewritten before or
  after, with the reference removed before or after or inserted before, before and after a scope change into a
  scope with the histogram in both scopes or in that scope only, on a reference with a histogram per input, and
  removes a product whose condition holds. `CrossScopeParentConditionFunctionalTest` — 24 cases, 0 failures; 20
  failed before with `Property accessor for class EntityReferenceWithParent not found`. *Open:* a parent changing
  scope does not re-evaluate its children's parent-reading conditions - archiving it leaves a live child's value,
  restoring it does not add the value back. A later touch of such a child keeps the stale value silently (it was
  refused while a parent in another scope failed the condition), and a later parent change adds it a second time,
  as an entity attribute change already does for any value its current condition denies.
- **A changed histogram definition or condition is not followed by any recomputation.** *Open, covered by the
  general rebuild work in #409.* The index keeps contributions computed under the old definition.
- **Catalogs written by the previous release may hold doubled or stale values.** *Not repaired by this change.* A
  correct writer stops new drift and does not look for old drift. Where a withdrawal expects a value such a catalog
  lacks, the 2026.3 default (`REPORT`, see the next item) logs the value at error level and skips it, and the write goes
  on; the write's own final add may leave the affected contribution correct, but drift it does not touch can remain
  until the catalog is rebuilt, which the release recommends. An engine started with the
  hidden system property `evitadb.referenceHistogram.missingValueBehavior=REFUSE` makes such a write fail instead. The
  property is a temporary safety valve, read once when the engine starts and deliberately absent from `ServerOptions`,
  the server YAML and `documentation/user/`, because the engine is expected to keep its histograms consistent;
  documenting or promoting it reverses this decision. It replaces the public setting `missingHistogramValueBehavior` of
  #1953, which existed on `dev` only and was withdrawn before any release, and it is removed in a later release under
  #1948.
- **2026.3 ships with `REPORT` as the default; `REFUSE` is meant to return as the default in 2026.4.** *Decided
  2026-11-19 under #1948.* An absent or blank property now selects `REPORT`; `REFUSE` stays selectable. The open
  defects that leave values missing were not all fixed for 2026.3. Measured on 2026-11-16 with the group store and
  the array emission fix in place, 22 of the 226 cases of the histogram reproduction classes were refused under
  `REFUSE`; under `REPORT` all 22 completed their writes, 6 with a correct histogram and 16 still failing their
  histogram assertions. The defects filed later (#2005 to #2007) lie outside that comparison. **Keeping `REFUSE` as
  the default (declined). Rejected because** an ordinary update of an affected reference would fail for drift the
  operator cannot repair short of a rebuild, while the entity bodies are intact; the release accepts histograms that
  can stay wrong instead. Every asserted withdrawal that finds its value missing logs one error line starting with
  `Cannot withdraw histogram value` - also where the write's final add leaves the histogram correct, as in the 6
  cases above that turned green. Revisit once the histogram maintenance no longer leaves values missing; that
  is when `REFUSE` becomes the default again (#2029). `MissingHistogramValueBehaviorTest` pins the default (absent,
  empty and blank settings resolve to `REPORT`, a session write with a missing value completes and logs one error
  line), and the release-fixture and migration upgrade tests assert the reported write next to an explicit `REFUSE`
  cell; resolving an absent setting to `REFUSE` again fails five of their cells.
- **Local and cross-entity work can apply one contribution twice.** *Open: #1925.* For a self-referencing entity
  type, and for a reference with a reflected counterpart, one upsert engages the local or nested path and the
  cross-entity path on the same contribution, and the value is added twice. Measured: 36 failing cases in three
  reproduction classes; the same cases fail on the code before this change.
- **The cross-entity path reads reference-attribute values by the wrong identifiers.** *Open: #1926.* It
  intersects owner primary keys with the record IDs of a reference-attribute filter index, which are partition
  keys on an ungrouped reference and referenced keys on a grouped one. Measured: 16 of 48 cases fail, the same
  before this change. A later local withdrawal of a value this path failed to add finds it missing, which the
  session write logs under the 2026.3 default and refuses under `REFUSE`.
- **A scope change does not pass an existing reference's attribute change to its reflected counterpart.** *Open:
  #1927.*
- **Duplicates on the cross-entity path** stay as coarse as
  [[2026-09-08-conditional-histogram-per-contribution-verdicts]] left them. The strict identity here covers the
  local path only.

## Related work

- [One histogram store per group](2026-11-19-one-histogram-store-per-group.md) — fixes split-group replication,
  preserves this record's deferred-run protocol and uses reporting for all committed WAL replay.

- [[2026-04-23-bucketed-histogram-indexing]] — introduced the counter-gated insert and remove pair and the
  deferred local queue this record changes the use of. Its invariant, an owner at most once per value, is what
  the old protocol broke.
- [[2026-08-31-cross-entity-histogram-removal-pre-pass]] — gave the cross-entity path a first-capture-wins
  answer for "did this reference contribute". This record gives the local path the same unit. Its Options C and
  D are this record's Option F and the wholesale recompute; both rejections still stand.
- [[2026-09-08-conditional-histogram-per-contribution-verdicts]] — the per-contribution answer on the
  cross-entity path, and the place where the duplicate-reference limit is recorded.

## Timeline

- **2026-11-10** — four defect reports traced to one cause; reproduction tests written and shown to fail
- **2026-11-11** — implemented; the pending variant of the scope-change re-index measured as a regression and
  replaced by the synchronous add
- **2026-11-11** — three defects found while testing fixed in the same pull request (#1928, #1929, #1930); the
  other defects found on the way filed as #1922 to #1927
- **2026-11-19** — the missing-value policy defaults to `REPORT` for 2026.3, with `REFUSE` planned to return as
  the default in 2026.4 (#1948, #2029)
