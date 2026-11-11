---
title: A reference's histogram contribution is withdrawn at its first touch and added once per run of the deferred queue
date: 2026-11-11
updated: 2026-11-11 14:05
status: accepted
kind: fix
issues: [1911, 1916, 1918]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/index/mutation/local, evita_engine/src/main/java/io/evitadb/core/catalog]
supersedes: []
superseded-by: []
relates: [2026-04-23-bucketed-histogram-indexing, 2026-08-31-cross-entity-histogram-removal-pre-pass,
  2026-09-08-conditional-histogram-per-contribution-verdicts]
---

# A reference's histogram contribution is withdrawn at its first touch and added once per run

The local write path of reference histograms no longer lets every local mutation queue its own "remove, then
add" action. Inside one run of the deferred queue a reference is either **clean** (the histogram holds exactly
what its stored state implies) or **pending** (the histogram holds nothing from it and one add is owed). The
first mutation that can change a clean reference's contribution removes that contribution on the spot, while
storage still holds the state the index reflects. Every later mutation of the same reference does nothing for
the histogram. When the queue runs, each pending reference that still exists is added once, from the state
stored at that moment.

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

### Option A — withdraw at the first touch, add once per run (chosen)

A set of pending references on the executor. A mutation that can change a clean reference's contribution
removes it immediately, with the condition evaluated against current storage, and marks the reference pending.
The run adds every pending reference that still exists.

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
- **Rejected because:** it was implemented and measured as a regression. The deferred add reads the locales of a
  reference through a view of the entity's locales **before** the batch, while the re-index reads the full
  entity body. An upsert that adds a locale and then changes the scope lost the new locale's value in the new
  scope: green before, red with the pending variant, green again with the synchronous add. Revisit once the
  deferred add reads the current locale set (see the follow-ups).

## Decision

**Chosen: Option A, with the scope-change re-index kept synchronous (against Option H).**

The driver is the unit. Whatever is done once per mutation composes wrongly as soon as two mutations meet on one
reference; whatever is done once per reference per run cannot. Among the per-reference options, the immediate
removal is the one that needs nothing remembered between the touch and the run, because the removal wants
exactly the state storage still holds when the touch happens.

The scope-change exception does not weaken the rule. The re-index runs right after the queue has been run, so
nothing is pending at that point, and it leaves every reference **clean** in the new scope: a later mutation in
the same upsert withdraws what the re-index added, like for any other clean reference.

Option D becomes the better choice if the removal ever has to be decided at the end of the run, for instance to
skip unchanged contributions. Option F becomes the better choice only together with a rework of the histogram
storage that pays for a rebuild anyway.

## Key technical details

- **The state** is `pendingHistogramReferences` in
  `evita_engine/src/main/java/io/evitadb/index/mutation/local/EntityIndexLocalMutationExecutor.java`. It is
  allocated only for references with histogram triggers.
- **The touch** is `EntityIndexLocalMutationExecutor#touchReferenceHistogram`. Five rules hold it together:
  1. It runs **before** the handler of the mutation does anything else: at the top of `#updateReferences`, ahead
     of the `switch`. The first resolution of a representative key in a batch can move the owner between the
     reduced indexes of a group without moving histogram values, and group handlers clean up group indexes; the
     withdrawal has to see the indexes the values were written to.
  2. The withdrawal is decided by the **evaluated condition**, per trigger. The presence probe underneath it is
     only tolerance for catalogs that already drifted, where the counter may hold fewer units than storage
     implies. It must never become the decision again.
  3. The add reads the group, the condition and the values **when the queue runs**, never a captured copy.
  4. The add **skips a reference that no longer exists**. A trigger without a condition evaluates to true
     without looking at the reference, and a value taken from the referenced entity is readable after the
     reference is gone.
  5. The pending set is **cleared wherever the queue is run**, in a `finally`:
     `#executeDeferredReEvaluations`, which also runs before a scope change.
- **Which mutations touch** is `#isHistogramRelevant`. Every reference mutation other than an attribute change
  does. An attribute change does when the attribute is a value source or a condition input, and **always** on a
  reference that allows duplicates and is indexed by its group component. That last clause looks too wide and is
  deliberate: there the attribute may be a representative one, and the migration it causes fires at the
  reference's first handler in the batch, which can be a mutation of an attribute nothing reads.
- **Entity attribute changes** touch every existing reference whose histogram condition reads the attribute:
  `handler/AttributeMutationFanOut.java`, fed by the lookup
  `LocalTriggerIndex#getHistogramReferencesForEntityAttribute` in
  `evita_engine/src/main/java/io/evitadb/core/catalog/`. An attribute no histogram reads costs one map lookup.
- **The identity is strict**: `HistogramReferenceIdentity`, next to the executor. `ReferenceKey#equals` treats a
  stored reference, a temporary one and an unknown one to the same target as equal, so a reference removed and
  inserted again in one upsert would collapse into one entry and the new reference would never be added.
- **An inserted reference is born pending** without a withdrawal (`#markHistogramPending`).
- **The scope-change re-index adds synchronously** from the full entity body in `#indexAllReferences` and marks
  nothing pending. See Option H before changing it.
- The wholesale removal of an entity from a scope still removes by presence. That is correct in aggregate,
  because every reference of the owner leaves, and it relies on the queue having been run first.

## Verification

Every scenario checks the histogram right after the upsert and again after a follow-up that moves the value
into the range of anchor products, in memory and after a restart; only the second check can see a doubled value.

- `ReferenceHistogramNetChangeFunctionalTest` — 54 cases, 0 failures. Before the change, on the same test code,
  26 of its then 46 cases failed. The later cases add an array changed twice in one upsert and a defaulted
  reference inserted after a scope change. They cover two histograms on one reference, an entity attribute change with a
  non-contributing sibling, a group move of a non-contributing reference, an entity attribute change together
  with a reference change, a touch followed by removal with a value fallback, a group change followed by removal
  with a value from the referenced entity, removal and re-insertion of one target, and the only owner of a group
  leaving it. Six cases cover localized values, among them the locale added before a scope change that decided
  against Option H.
- `ScopeChangeDeferredReEvaluationFunctionalTest` — 197 cases, 0 failures; 40 of them failed before the change.
- Together **251 of 251**.
- `ReferenceHistogramPathParityFunctionalTest` — 12 cases, 0 failures. It reaches one final state through the
  local path and through the cross-entity path and compares every bucket of the two histograms, after the change,
  after a follow-up removal and after a restart. The same 12 pass on the code before this change.
- `HistogramReferenceIdentityTest` pins the strict identity, including a temporary reference beside a dropped
  stored one.
- Neighbouring suites, **333 of 333**: `RepeatedScopeChangeFunctionalTest` 36,
  `LocaleRemovalWithArchivingFunctionalTest` 68, `ConditionalBucketIndexingTest` 135,
  `ConditionalBucketQueryTest` 8, `HistogramScopeChangeTest` 12, and the four
  `EntityIndexLocalMutationExecutor*Test` classes 74.

No benchmark was run. The cost was assessed by reading only: an unrelated attribute change is cheaper than
before, a second touch of one reference costs a set lookup, and an entity attribute change read by a histogram
condition pays one more condition evaluation per affected reference and histogram.

## Consequences & open follow-ups

- **The deferred add reads the locales the entity had before the upsert.** *Open: #1922.* A new product stored
  with a localized value in one upsert gets no histogram value for it, and a localized value changed after a scope
  change in the same upsert is neither withdrawn nor added in the new locale. The first was measured and behaved
  the same before this change; the second is read from the code and matches the previous behaviour, but was not
  run. Fixing it removes the reason for the synchronous re-index.
- **A reference inserted together with a scope change got no schema default values.** *Fixed in the same pull
  request: #1930.* The scope-change branch of `ContainerizedLocalMutationExecutor#popImplicitMutations` skipped
  the reference attribute verification, so the default values were missing from the stored reference and a
  missing mandatory attribute was accepted. It now verifies the references the upsert names, as the same-scope
  branch does.
- **A group split into several reduced indexes holds and answers its histogram wrongly.** *Open: #1924.* On a
  reference that allows duplicates and has a representative attribute, every write goes to each reduced index
  listed for the group, a new reduced index starts empty, an abandoned one keeps stale copies, a removal can take
  a sibling's unit, and the reference summary reads one index per group. This change does not make the write
  side correct for that shape: the replication itself is the defect. The fix is planned as one histogram store
  per group and will carry its own record.
- **Conditions reading the parent or associated data are never re-evaluated locally.** *Open: #1923.* Histogram
  triggers may carry both dependencies, but only the facet side reacts to them. Until that is closed, a parent
  change followed by a reference touch in one upsert makes the withdrawal evaluate against the new parent.
- **A changed histogram definition or condition is not followed by any recomputation.** *Open, covered by the
  general rebuild work in #409.* The index keeps contributions computed under the old definition.
- **Catalogs written by the previous release may hold doubled or stale values.** *Not repaired by this change.*
  A correct writer stops new drift and cannot find old drift. The release already recommends a full reindex.
- **Local and cross-entity work can apply one contribution twice.** *Open: #1925.* For a self-referencing entity
  type, and for a reference with a reflected counterpart, one upsert engages the local or nested path and the
  cross-entity path on the same contribution, and the value is added twice. Measured: 36 failing cases in three
  reproduction classes; the same cases fail on the code before this change.
- **The cross-entity path reads reference-attribute values by the wrong identifiers.** *Open: #1926.* It
  intersects owner primary keys with the record IDs of a reference-attribute filter index, which are partition
  keys on an ungrouped reference and referenced keys on a grouped one. Measured: 16 of 48 cases fail, the same
  before this change.
- **A scope change does not pass an existing reference's attribute change to its reflected counterpart.** *Open:
  #1927.*
- **Duplicates on the cross-entity path** stay as coarse as
  [[2026-09-08-conditional-histogram-per-contribution-verdicts]] left them. The strict identity here covers the
  local path only.

## Related work

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
